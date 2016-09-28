package com.waz.service.messages

import com.waz.content.{MessagesStorage, ZmsDatabase}
import com.waz.model.MessageData.MessageDataDao
import com.waz.threading.CancellableFuture
import com.waz.utils._
import com.waz.utils.events.Signal
import org.threeten.bp.Instant
import scala.concurrent.duration._

class EphemeralMessagesService(messages: MessagesContentUpdater, storage: MessagesStorage, db: ZmsDatabase) {

  private val nextExpiryTime = Signal[Instant](Instant.MAX)

  val init = removeExpired()

  nextExpiryTime {
    case Instant.MAX => // nothing to expire
    case time => CancellableFuture.delayed((time.toEpochMilli - Instant.now.toEpochMilli).millis) { removeExpired() }
  }

  storage.onAdded { msgs =>
    updateNextExpiryTime(msgs.flatMap(_.expiryTime))
  }

  storage.onUpdated { updates =>
    updateNextExpiryTime(updates.flatMap(_._2.expiryTime))
  }

  private def updateNextExpiryTime(times: Seq[Instant]) = if (times.nonEmpty) {
    val time = times.min
    nextExpiryTime.mutate(_ min time)
  }

  private def removeExpired() = Serialized.future(this, "removeExpired") {
    nextExpiryTime ! Instant.MAX
    db.read { implicit db =>
      val time = Instant.now
      MessageDataDao.findExpiring() acquire { msgs =>
        val (toRemove, rest) = msgs.toStream.span(_.expiryTime.exists(_ <= time))
        rest.headOption.flatMap(_.expiryTime) foreach { time =>
          nextExpiryTime.mutate(_ min time)
        }
        toRemove.toVector
      }
    } flatMap { expired =>
      messages.deleteOnUserRequest(expired.map(_.id)) // TODO: according to current design we should post recall here
    }
  }
}
