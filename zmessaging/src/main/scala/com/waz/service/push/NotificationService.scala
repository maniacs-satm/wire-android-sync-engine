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
package com.waz.service.push

import android.app.AlarmManager
import android.content.Context
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.Message
import com.waz.api.NotificationsHandler.NotificationType
import com.waz.api.NotificationsHandler.NotificationType._
import com.waz.content._
import com.waz.model.AssetStatus.UploadDone
import com.waz.model.ConversationData.ConversationType
import com.waz.model.GenericContent.Asset.Original
import com.waz.model.GenericContent.{Asset, LastRead}
import com.waz.model.UserData.ConnectionStatus
import com.waz.model._
import com.waz.service._
import com.waz.threading.SerialDispatchQueue
import com.waz.utils._
import com.waz.utils.events.{AggregatingSignal, EventStream, Signal}
import com.waz.zms.NotificationsAndroidService.{checkNotificationsIntent, checkNotificationsTimeout}
import org.threeten.bp.Instant

import scala.collection.breakOut
import scala.concurrent.Future

class NotificationService(context: Context, selfUserId: UserId, messages: MessagesStorage, lifecycle: ZmsLifecycle,
    storage: NotificationStorage, usersStorage: UsersStorage, convs: ConversationStorage, reactionStorage: ReactionsStorage,
    kv: KeyValueStorage, timeouts: Timeouts) {

  import NotificationService._
  import com.waz.utils.events.EventContext.Implicits.global

  private implicit val dispatcher = new SerialDispatchQueue(name = "NotificationService")

  @volatile private var uiActive = false

  private val lastUiVisibleTime = kv.keyValuePref("last_ui_visible_time", Instant.EPOCH)

  val alarmService = context.getSystemService(Context.ALARM_SERVICE).asInstanceOf[AlarmManager]

  //For UI to decide if it should make sounds or not
  val otherDeviceActiveTime = Signal(Instant.EPOCH)

  val notifications = lifecycle.lifecycleState.zip(for {
    data <- storage.notifications.map(_.values.toIndexedSeq.sorted)
    uiVisibleTime <- lastUiVisibleTime.signal
  } yield {
    if (data.forall(_.localTime.isBefore(uiVisibleTime))) Seq.empty[NotificationData] // no new messages, don't show anything
    else data
  }).flatMap {
    case (LifecycleState.UiActive, _) => Signal.const(Seq.empty[NotificationInfo])
    case (_, data) => Signal.future(createNotifications(data))
  }

  val lastReadProcessingStage = EventScheduler.Stage[GenericMessageEvent] { (convId, events) =>
    events.foreach {
      case GenericMessageEvent(_, _, _, _, GenericMessage(_, LastRead(conv, time))) =>
        otherDeviceActiveTime ! Instant.now
        alarmService.set(AlarmManager.RTC, Instant.now().toEpochMilli + checkNotificationsTimeout.toMillis, checkNotificationsIntent(context))
      case _ =>
    }
    Future.successful(())
  }

  lifecycle.lifecycleState { state =>
    verbose(s"state updated: $state")
    uiActive = returning(state == LifecycleState.UiActive) { active =>
      if (active || uiActive) lastUiVisibleTime := Instant.now()
    }
  }

  val notificationEventsStage = EventScheduler.Stage[Event]({ (c, events) =>
    add(events collect {
      case ev @ UserConnectionEvent(_, _, _, userId, msg, ConnectionStatus.PendingFromOther, time, name) if ev.hasLocalTime =>
        NotificationData(s"$CONNECT_REQUEST-$userId", msg.getOrElse(""), ConvId(userId.str), userId, CONNECT_REQUEST, time.instant, userName = name)
      case ev @ UserConnectionEvent(_, _, _, userId, _, ConnectionStatus.Accepted, time, name) if ev.hasLocalTime =>
        // this event doesn't generate corresponding message, we can not use event time for notification, as it would not be properly dismissed
        NotificationData(s"$CONNECT_ACCEPTED-$userId", "", ConvId(userId.str), userId, CONNECT_ACCEPTED, Instant.EPOCH, userName = name)
      case ContactJoinEvent(_, userId, _) =>
        verbose("ContactJoinEvent")
        NotificationData(s"$CONTACT_JOIN-$userId", "", ConvId(userId.str), userId, CONTACT_JOIN, Instant.EPOCH)
    })
  }, _ => ! uiActive)

  /**
    * Map containing lastRead time for every conversation.
    * For muted conversations lastRead is always set to Instant.MAX,
    * we don't want to show notifications for muted conversations.
    */
  private val lastReadMap = {
    def convLastRead(c: ConversationData) = if (c.muted) Instant.MAX else c.lastRead

    val timeUpdates = EventStream.union(
      convs.onAdded,
      convs.onUpdated map { _ collect { case (prev, conv) if convLastRead(prev) != convLastRead(conv) => conv } }
    ) filter(_.nonEmpty)

    def loadAll() = convs.getAll.map(_.map(c => c.id -> convLastRead(c)).toMap)

    def update(times: Map[ConvId, Instant], updates: Seq[ConversationData]) =
      times ++ updates.map(c => c.id -> convLastRead(c))(breakOut)

    new AggregatingSignal(timeUpdates, loadAll(), update)
  }

  // remove older notifications when lastRead or uiTime is updated
  lastReadMap.zip(lastUiVisibleTime.signal).throttle(timeouts.notifications.clearThrottling) {
    case (lastRead, uiTime) => removeReadNotifications(lastRead, uiTime)
  }

  messages.onAdded { addForIncoming(_) }

  messages.onUpdated { updates =>
    // add notification when message sending fails
    val failedMsgs = updates collect {
      case (prev, msg) if prev.state != msg.state && msg.state == Message.Status.FAILED => msg
    }
    if (!uiActive && failedMsgs.nonEmpty) {
      storage.insert(failedMsgs map { msg => NotificationData(msg.id.str, msg.contentString, msg.convId, msg.userId, MESSAGE_SENDING_FAILED, Instant.EPOCH) })
    }

    // add notifications for uploaded assets
    val updatedAssets = updates collect {
      case (prev, msg) if msg.state == Message.Status.SENT && msg.msgType == Message.Type.ANY_ASSET => msg
    }
    if (updatedAssets.nonEmpty) addForIncoming(updatedAssets)
  }

  messages.onDeleted { ids =>
    storage.remove(ids.map(_.str))
  }

  private def addForIncoming(msgs: Seq[MessageData]) = {
    verbose(s"addForIncoming($msgs)")
    lastReadMap.head map { lastRead =>
      verbose(s"lastRead: $lastRead")
      msgs.filter(m => m.userId != selfUserId && lastRead.get(m.convId).forall(_.isBefore(m.time)) && m.localTime != Instant.EPOCH)
    } flatMap { ms =>
      verbose(s"filtered: $ms")
      storage.insert(ms flatMap notification)
    }
  }

  private def notification(msg: MessageData) = {
    import Message.Type._

    def data(tpe: NotificationType) = Some(NotificationData(msg.id.str, msg.contentString, msg.convId, msg.userId, tpe, msg.time, msg.localTime, mentions = msg.mentions.keys.toSeq, hotKnock = msg.hotKnock))

    msg.msgType match {
      case TEXT | TEXT_EMOJI_ONLY | RICH_MEDIA => data(NotificationType.TEXT)
      case KNOCK        => data(NotificationType.KNOCK)
      case ASSET        => data(NotificationType.ASSET)
      case LOCATION     => data(NotificationType.LOCATION)
      case RENAME       => data(NotificationType.RENAME)
      case MISSED_CALL  => data(NotificationType.MISSED_CALL)
      case ANY_ASSET    =>
        msg.protos.lastOption match {
          case Some(GenericMessage(_, Asset(Some(Original(Mime.Video(), _, _, _, _)), _, UploadDone(_)))) => data(NotificationType.VIDEO_ASSET)
          case Some(GenericMessage(_, Asset(Some(Original(Mime.Audio(), _, _, _, _)), _, UploadDone(_)))) => data(NotificationType.AUDIO_ASSET)
          case Some(GenericMessage(_, Asset(_, _, UploadDone(_)))) => data(NotificationType.ANY_ASSET)
          case _ => None
        }
      case AUDIO_ASSET  => data(NotificationType.AUDIO_ASSET)
      case VIDEO_ASSET  => data(NotificationType.VIDEO_ASSET)
      case MEMBER_JOIN  =>
        if (msg.members == Set(msg.userId)) None // ignoring auto-generated member join event when user accepts connection
        else data(NotificationType.MEMBER_JOIN)
      case MEMBER_LEAVE => data(NotificationType.MEMBER_LEAVE)
      case _ => None
    }
  }

  reactionStorage.onChanged { reactions =>
    val reactionsFromOthers = reactions.filterNot(_.user == selfUserId)

    messages.getAll(reactionsFromOthers.map(_.message)).flatMap { msgs =>
      val convsByMsg = msgs.iterator.flatten.by[MessageId, Map](_.id).mapValues(_.convId)
      val myMsgs = msgs.collect { case Some(m) if m.userId == selfUserId => m.id }(breakOut): Set[MessageId]
      val rs = reactionsFromOthers.filter(r => myMsgs contains r.message).sortBy(_.timestamp)
      val (toRemove, toAdd) = rs.foldLeft((Set.empty[(MessageId, UserId)], Map.empty[(MessageId, UserId), Liking])) {
        case ((rs, as), r @ Liking(m, u, t, Liking.Action.Like))  => (rs - r.id, as + (r.id -> r))
        case ((rs, as), r @ Liking(m, u, t, Liking.Action.Unlike)) => (rs + r.id, as - r.id)
      }

      storage.remove(toRemove.map(reactionID)).flatMap { _ =>
        if (! uiActive)
          add(toAdd.valuesIterator.map(r => NotificationData(reactionID(r.id), "", convsByMsg.getOrElse(r.message, ConvId(r.user.str)), r.user, LIKE, Instant.EPOCH, referencedMessage = Some(r.message))).toVector)
        else
          Future.successful(Nil)
      }
    }.logFailure(reportHockey = true)
  }

  reactionStorage.onDeleted { ids =>
    storage.remove(ids.map(reactionID))
  }

  private def reactionID(id: (MessageId, UserId)) = s"$LIKE-$id"

  def clearNotifications() =
    for {
      _ <- lastUiVisibleTime := Instant.now()
      // will execute removeReadNotifications as part of this call,
      // this ensures that it's actually done while wakeLock is acquired by caller
      lastRead <- lastReadMap.head
      _ <- removeReadNotifications(lastRead, Instant.now)
    } yield ()

  private def removeReadNotifications(lastRead: Map[ConvId, Instant], uiTime: Instant) = {
    verbose(s"removeRead($lastRead, $uiTime)")

    def isRead(notification: NotificationData) =
      (notification.serverTime == Instant.EPOCH && uiTime.isAfter(notification.localTime)) || lastRead.get(notification.conv).exists(!_.isBefore(notification.serverTime))

    storage.notifications.head flatMap { data =>
      val toRemove = data collect {
        case (id, notification) if isRead(notification) => id
      }
      verbose(s"toRemove on lastRead change: $toRemove")
      storage.remove(toRemove)
    }
  }

  private def add(notifications: Seq[NotificationData]) =
    for {
      lastRead <- lastReadMap.head
      res <- storage.insert(notifications filter { n =>
        // filter notifications for unread messages in un-muted conversations
        n.serverTime == Instant.EPOCH || lastRead.get(n.conv).forall(_.isBefore(n.serverTime))
      })
      _ = verbose(s"inserted: $res")
    } yield res

  private def createNotifications(ns: Seq[NotificationData]): Future[Seq[NotificationInfo]] = {
    Future.traverse(ns) { data =>
      usersStorage.get(data.user).flatMap { user =>
        val userName = user map (_.getDisplayName) filterNot (_.isEmpty) orElse data.userName
        data.msgType match {
          case CONNECT_REQUEST | CONNECT_ACCEPTED =>
            Future.successful(NotificationInfo(data.msgType, data.msg, data.hotKnock, data.conv, convName = userName, userName = userName, isGroupConv = false))
          case _ =>
            for {
              msg <- data.referencedMessage.fold2(Future.successful(None), messages.getMessage)
              conv <- convs.get(data.conv)
            } yield {
              val (g, t) =
                if (data.msgType == LIKE) (data.copy(msg = msg.fold("")(_.contentString)), msg.map(m => if (m.msgType == Message.Type.ASSET) LikedContent.PICTURE else LikedContent.TEXT_OR_URL))
                else (data, None)
              NotificationInfo(g.msgType, g.msg, g.hotKnock, g.conv, convName = conv.map(_.displayName), userName = userName, isGroupConv = conv.exists(_.convType == ConversationType.Group), isUserMentioned = data.mentions.contains(selfUserId), likedContent = t)
            }
        }
      }
    }
  }
}

object NotificationService {

  case class NotificationInfo(tpe: NotificationType,
                              message: String,
                              isPing: Boolean,
                              convId: ConvId,
                              convName: Option[String] = None,
                              userName: Option[String] = None,
                              isGroupConv: Boolean = false,
                              isUserMentioned: Boolean = false,
                              likedContent: Option[LikedContent] = None
                             )
}
