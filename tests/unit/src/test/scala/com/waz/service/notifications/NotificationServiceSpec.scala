/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service.notifications

import java.util.Date

import android.database.sqlite.SQLiteDatabase
import com.waz.RobolectricUtils
import com.waz.api.NotificationsHandler.NotificationType
import com.waz.model.AssetStatus.{UploadCancelled, UploadDone}
import com.waz.model.ConversationData.ConversationType
import com.waz.model.GenericContent.{Asset, MsgEdit, MsgRecall, Text}
import com.waz.model.GenericMessage.TextMessage
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.service.Timeouts
import com.waz.service.push.NotificationService.NotificationInfo
import com.waz.testutils.Matchers._
import com.waz.testutils._
import com.waz.utils.events.EventContext
import com.waz.zms.GcmHandlerService.EncryptedGcm
import org.json.JSONObject
import org.scalatest._
import org.scalatest.prop.PropertyChecks
import org.threeten.bp.Instant

import scala.concurrent.Await
import scala.concurrent.duration._

class NotificationServiceSpec extends FeatureSpec with Matchers with PropertyChecks with BeforeAndAfter with BeforeAndAfterAll with RobolectricTests with RobolectricUtils with DefaultPatienceConfig { test =>

  @volatile var currentNotifications = Nil: Seq[NotificationInfo]

  lazy val selfUserId = UserId()
  lazy val oneToOneConv = ConversationData(ConvId(), RConvId(), None, selfUserId, ConversationType.OneToOne)
  lazy val groupConv = ConversationData(ConvId(), RConvId(), Some("group conv"), selfUserId, ConversationType.Group)

  lazy val zms = new MockZMessaging(selfUserId = selfUserId) { self =>
    notifications.notifications { currentNotifications = _ } (EventContext.Global)

    override def timeouts = new Timeouts {
      override val notifications = new Notifications() {
        override def clearThrottling = 100.millis
      }
    }
  }

  lazy val service = zms.notifications

  implicit def db: SQLiteDatabase = zms.db.dbHelper.getWritableDatabase

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    zms.convsStorage.insert(Seq(oneToOneConv, groupConv)).await()
  }

  before {
    zms.convsContent.updateConversationLastRead(oneToOneConv.id, Instant.now()).await()
    zms.convsContent.updateConversationLastRead(groupConv.id, Instant.now()).await()
    clearNotifications()
  }

  feature("Add notifications for events") {

    scenario("Process user connection event for an unsynced user") {
      val userId = UserId(oneToOneConv.id.str)
      val convId = oneToOneConv.remoteId
      Await.ready(zms.dispatch(UserConnectionEvent(Uid(), convId, selfUserId, userId, Some("hello"), ConnectionStatus.PendingFromOther, new Date, Some("other user")).withCurrentLocalTime()), 5.seconds)

      withDelay {
        currentNotifications should beMatching {
          case Seq(NotificationInfo(NotificationType.CONNECT_REQUEST, "hello", false, _, Some("other user"), Some("other user"), false, _, _)) => true
        }
      }
    }

    scenario("Process contact join event") {
      val userId = UserId()
      Await.ready(zms.dispatch(ContactJoinEvent(Uid(), userId, "test name")), 5.seconds)

      withDelay {
        currentNotifications should beMatching {
          case Seq(NotificationInfo(NotificationType.CONTACT_JOIN, _, _, _, _, _, false, _, _)) => true
        }
      }
    }

    scenario("Process mentions event") {
      val userId = UserId(oneToOneConv.id.str)
      val convId = oneToOneConv.id

      Await.ready(zms.dispatch(GenericMessageEvent(Uid(), oneToOneConv.remoteId, new Date, userId, TextMessage("test name", Map(selfUserId -> "name"))).withCurrentLocalTime()), 5.seconds)

      withDelay {
        currentNotifications should beMatching {
          case Seq(NotificationInfo(NotificationType.TEXT, "test name", _, `convId`, _, _, false, true, _)) => true
        }
      }
    }

    scenario("Process any asset event") {
      val userId = UserId(oneToOneConv.id.str)
      val convId = oneToOneConv.id
      Await.ready(zms.dispatch(GenericMessageEvent(Uid(), oneToOneConv.remoteId, new Date, userId, GenericMessage(MessageId().uid, Asset(UploadCancelled))).withCurrentLocalTime()), 5.seconds) // this should not generate a notification
      Await.ready(zms.dispatch(GenericAssetEvent(Uid(), oneToOneConv.remoteId, new Date, userId, GenericMessage(MessageId().uid, Asset(UploadDone(AssetKey(Left(RAssetDataId()), None, AESKey(), Sha256("sha"))))), RAssetDataId(), None).withCurrentLocalTime()), 5.seconds)

      withDelay {
        currentNotifications should beMatching {
          case Seq(NotificationInfo(NotificationType.ANY_ASSET, _, _, `convId`, _, _, false, _, _)) => true
        }
      }
    }

    scenario("Receiving the same message twice") {
      val ev1 = textMessageEvent(Uid(), groupConv.remoteId, new Date, UserId(), "meep").withCurrentLocalTime()
      val ev2 = ev1.copy()

      Await.ready(zms.dispatch(ev1, ev2), 5.seconds)

      val groupId = groupConv.id

      withDelay {
        currentNotifications should beMatching {
          case Seq(NotificationInfo(NotificationType.TEXT, "meep", _, `groupId`, Some("group conv"), _, true, _, _)) => true
        }
      }
    }

    scenario("Don't show duplicate notification even if notifications were cleared") {
      val ev = textMessageEvent(Uid(), groupConv.remoteId, new Date, UserId(), "meep").withCurrentLocalTime()

      zms.dispatch(ev)

      withDelay {
        currentNotifications should have size 1
      }
      clearNotifications()

      zms.dispatch(ev)

      awaitUi(250.millis)
      currentNotifications shouldBe empty
    }

    scenario("Ignore events from self user") {
      val ev = textMessageEvent(Uid(), groupConv.remoteId, new Date, selfUserId, "meep").withCurrentLocalTime()

      Await.ready(zms.dispatch(ev), 5.seconds)
      awaitUi(200.millis)

      currentNotifications shouldBe empty
    }

    scenario("Remove older notifications when lastRead is updated") {
      val time = System.currentTimeMillis()
      zms.dispatch(
        textMessageEvent(Uid(), groupConv.remoteId, new Date(time), UserId(), "meep").withCurrentLocalTime(),
        textMessageEvent(Uid(), groupConv.remoteId, new Date(time + 10), UserId(), "meep1").withCurrentLocalTime(),
        textMessageEvent(Uid(), groupConv.remoteId, new Date(time + 20), UserId(), "meep2").withCurrentLocalTime(),
        textMessageEvent(Uid(), groupConv.remoteId, new Date(time + 30), UserId(), "meep3").withCurrentLocalTime(),
        textMessageEvent(Uid(), groupConv.remoteId, new Date(time + 40), UserId(), "meep4").withCurrentLocalTime()
      )

      withDelay {
        currentNotifications should have size 5
      }

      zms.convsContent.updateConversationLastRead(groupConv.id, Instant.ofEpochMilli(time + 30))

      withDelay {
        currentNotifications should have size 1
      }
    }

    scenario("Remove notifications for recalled messages") {
      val id = Uid()
      val user = UserId()
      val ev = textMessageEvent(id, groupConv.remoteId, new Date(), user, "meep")

      zms.dispatch(ev.withCurrentLocalTime())

      withDelay {
        currentNotifications should have size 1
      }

      zms.dispatch(GenericMessageEvent(Uid(), groupConv.remoteId, new Date(), user, GenericMessage(Uid(), MsgRecall(MessageId(id.str)))).withCurrentLocalTime())

      withDelay {
        currentNotifications should have size 0
      }
    }

    scenario("Update notifications for edited messages") {
      val id = Uid()
      val user = UserId()
      val time = new Date()
      val ev = textMessageEvent(id, groupConv.remoteId, time, user, "meep")

      zms.dispatch(ev.withCurrentLocalTime())

      withDelay {
        currentNotifications should have size 1
      }

      zms.dispatch(GenericMessageEvent(Uid(), groupConv.remoteId, new Date(), user, GenericMessage(Uid(), MsgEdit(MessageId(id.str), Text("updated")))).withCurrentLocalTime())

      withDelay {
        currentNotifications should have size 1
        currentNotifications.head.tpe shouldEqual NotificationType.TEXT
        currentNotifications.head.message shouldEqual "updated"
      }
    }
  }

  feature("List notifications") {

    scenario("List notifications with local conversation ids") {
      Await.ready(zms.dispatch(textMessageEvent(Uid(), groupConv.remoteId, new Date, UserId(), "test msg").withCurrentLocalTime()), 5.seconds)

      val groupId = groupConv.id

      withDelay {
        currentNotifications should beMatching {
          case Seq(NotificationInfo(NotificationType.TEXT, _, _, `groupId`, Some("group conv"), _, true, _, _)) => true
        }
      }
    }
  }

  feature("Receive GCM") {

    scenario("parse encrypted notification") {

      val json = """{"data":{"payload":[{"conversation":"0a032ce9-2b6b-4f6c-9825-1ac958eeb94b","time":"2016-01-27T17:29:45.714Z","data":{"text":"owABAaEAWCCYir0PHK4qO8706uRy2aID1MZPjsZd3BCgrVBLZWm6uwJYxQKkAAQBoQBYIKk+iFvwuNS4u8C9kEr5euVnNecXconfHNcUv8BMSuM+AqEAoQBYIDcRmzxai6NPcUeVmcBAeKYl1ikB1zP5IdDBLKootTDdA6UAUGEAzoTIyssU5udKqxR2mDQBAAIAA6EAWCCT1VMZ3KIXptRnH9O2oeW4Z7Ck\/6iwshmRig\/+mpKA8gRYNREviDPKaPYzaUQpqlKWNm3EKyEF+B1eSoEdaOg52+0NIiHthTi22dfZc8MwAKeg5a4xZ\/lA","sender":"30f327954b3a772a","recipient":"ecd0802b04b01fe8"},"from":"b3fe68df-db7f-4a1d-b49c-370e77334af0","type":"conversation.otr-message-add"}],"transient":false,"id":"8eeb8574-c51b-11e5-a396-22000b0a2794"}}"""
      new JSONObject(json) match {
        case EncryptedGcm(notification) => info(s"notification: $notification")
        case resp => fail(resp.toString())
      }
    }
  }

  def clearNotifications(): Unit = Await.result(service.clearNotifications(), 5.seconds)
}
