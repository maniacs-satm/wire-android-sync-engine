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
package com.waz.model


import android.net.Uri
import android.util.Base64
import com.google.protobuf.nano.MessageNano
import com.waz.model.AssetMetaData.HasDimensions
import com.waz.model.AssetStatus.{UploadCancelled, UploadDone, UploadFailed, UploadInProgress}
import com.waz.model.nano.Messages
import com.waz.model.nano.Messages.MessageEdit
import com.waz.utils._
import org.json.JSONObject
import org.threeten.bp.{Duration, Instant}

import scala.PartialFunction.condOpt
import scala.collection.breakOut
import scala.concurrent.duration.FiniteDuration

trait GenericContent[-T] {
  def set(msg: GenericMessage): T => GenericMessage
}

object GenericContent {

  trait EphemeralContent[-T] {
    def set(eph: Ephemeral): T => Ephemeral
  }

  type Asset = Messages.Asset
  implicit object Asset extends GenericContent[Asset] {

    object Original {

      def apply(mime: Mime, size: Long, name: Option[String], meta: Option[AssetMetaData] = None, audioPreview: Option[AssetPreviewData.Loudness] = None): Messages.Asset.Original =
        returning(new Messages.Asset.Original()) { orig =>
          orig.mimeType = mime.str
          orig.size = size
          meta match {
            case Some(video: AssetMetaData.Video) => orig.setVideo(VideoMetaData(video))
            case Some(image: AssetMetaData.Image) => orig.setImage(ImageMetaData(image))
            case Some(audio: AssetMetaData.Audio) => orig.setAudio(AudioMetaData(Some(audio), audioPreview))
            case Some(AssetMetaData.Empty)        =>
            case None                             => orig.setAudio(AudioMetaData(None, audioPreview))
          }
          name foreach { orig.name = _ }
        }

      def apply(asset: AnyAssetData): Messages.Asset.Original = apply(asset.mimeType, asset.sizeInBytes, asset.name,
        asset.metaData, asset.preview.collect { case l: AssetPreviewData.Loudness => l })

      def unapply(proto: Messages.Asset.Original): Option[(Mime, Long, Option[String], Option[AssetMetaData], Option[AssetPreviewData.Loudness])] = {
        val name = Option(proto.name).filter(_.nonEmpty)
        val mime = Option(proto.mimeType).filter(_.nonEmpty).fold(name.map(Mime.fromFileName).getOrElse(Mime.Unknown))(Mime(_))
        Some((mime, proto.size, name, MetaData(proto), AudioMetaData.loudness(proto)))
      }
    }

    object Status {
      import Messages.Asset._

      def apply(a: Asset, id: Option[RAssetDataId]): AssetStatus =
        (a.getStatusCase, a.getUploaded, a.getNotUploaded, id) match {
          case (UPLOADED_FIELD_NUMBER, Asset.Uploaded(_, token, aes, sha), _, Some(id)) => UploadDone(AssetKey(Left(id), token, aes, sha))
          case (UPLOADED_FIELD_NUMBER, Asset.Uploaded(Some(key), token, aes, sha), _, None) => UploadDone(AssetKey(Right(key), token, aes, sha))
          case (NOT_UPLOADED_FIELD_NUMBER, _, CANCELLED, _) => UploadCancelled
          case (NOT_UPLOADED_FIELD_NUMBER, _, FAILED, _) => UploadFailed
          case _ => UploadInProgress
        }
    }

    object ImageMetaData {
      def apply(tag: Option[String], width: Int, height: Int) = returning(new Messages.Asset.ImageMetaData) { p =>
        tag.foreach(p.tag = _)
        p.width = width
        p.height = height
      }

      def apply(image: AssetMetaData.Image) = returning(new Messages.Asset.ImageMetaData) { p =>
        image.tag foreach { p.tag = _ }
        p.width = image.dimensions.width
        p.height = image.dimensions.height
      }

      def apply(p: Messages.Asset.Preview): Option[AssetMetaData.Image] = p.getMetaDataCase match {
        case Messages.Asset.Preview.IMAGE_FIELD_NUMBER => unapply(p.getImage)
        case _ => None
      }

      def apply(p: Messages.Asset.Original): Option[AssetMetaData.Image] = p.getMetaDataCase match {
        case Messages.Asset.Original.IMAGE_FIELD_NUMBER => unapply(p.getImage)
        case _ => None
      }

      def unapply(proto: Messages.Asset.ImageMetaData): Option[AssetMetaData.Image] =
        Some(AssetMetaData.Image(Dim2(proto.width, proto.height), Option(proto.tag).filter(_.nonEmpty)))
    }

    object VideoMetaData {
      def apply(md: AssetMetaData.Video) = returning(new Messages.Asset.VideoMetaData) { p =>
        p.width = md.dimensions.width
        p.height = md.dimensions.height
        p.durationInMillis = md.duration.toMillis
      }

      def apply(p: Messages.Asset.Original): Option[Messages.Asset.VideoMetaData] = p.getMetaDataCase match {
        case Messages.Asset.Original.VIDEO_FIELD_NUMBER => Option(p.getVideo)
        case _ => None
      }

      def unapply(proto: Messages.Asset.VideoMetaData): Option[AssetMetaData.Video] =
        Some(AssetMetaData.Video(Dim2(proto.width, proto.height), Duration.ofMillis(proto.durationInMillis)))
    }

    object AudioMetaData {
      def apply(mmd: Option[AssetMetaData.Audio], ml: Option[AssetPreviewData.Loudness]) = returning(new Messages.Asset.AudioMetaData) { p =>
        mmd.foreach(md => p.durationInMillis = md.duration.toMillis)
        ml.foreach(l => p.normalizedLoudness = bytify(l.levels))
      }

      def apply(p: Messages.Asset.Original): Option[AssetMetaData.Audio] = p.getMetaDataCase match {
        case Messages.Asset.Original.AUDIO_FIELD_NUMBER => unapply(p.getAudio)
        case _ => None
      }

      def loudness(original: Messages.Asset.Original): Option[AssetPreviewData.Loudness] =
        original.getMetaDataCase match {
          case Messages.Asset.Original.AUDIO_FIELD_NUMBER =>
            Option(original.getAudio.normalizedLoudness).filter(_.nonEmpty).map(arr => AssetPreviewData.Loudness(floatify(arr)))
          case _ =>
            None
        }

      def unapply(p: Messages.Asset.AudioMetaData): Option[AssetMetaData.Audio] =
        if (p.durationInMillis <= 0) None
        else Some(AssetMetaData.Audio(Duration.ofMillis(p.durationInMillis)))

      def bytify(ls: Iterable[Float]): Array[Byte] = ls.map(l => (l * 255f).toByte)(breakOut)
      def floatify(bs: Array[Byte]): Vector[Float] = bs.map(b => (b & 255) / 255f)(breakOut)
    }

    object MetaData {
      def image(md: AssetMetaData): Option[Messages.Asset.ImageMetaData] = condOpt(md) { case i: AssetMetaData.Image => ImageMetaData(i.tag, i.dimensions.width, i.dimensions.height) }
      def video(md: AssetMetaData): Option[Messages.Asset.VideoMetaData] = condOpt(md) { case v: AssetMetaData.Video => VideoMetaData(v) }

      def apply(proto: Messages.Asset): Option[AssetMetaData] = Option(proto.original).flatMap(apply)

      def apply(original: Messages.Asset.Original): Option[AssetMetaData] =
        original.getMetaDataCase match {
          case Messages.Asset.Original.IMAGE_FIELD_NUMBER =>
            ImageMetaData.unapply(original.getImage)
          case Messages.Asset.Original.VIDEO_FIELD_NUMBER =>
            VideoMetaData.unapply(original.getVideo)
          case Messages.Asset.Original.AUDIO_FIELD_NUMBER =>
            AudioMetaData.unapply(original.getAudio)
          case _ =>
            None
        }
    }

    type Preview = Messages.Asset.Preview
    object Preview {

      def apply(mime: Mime, size: Long, key: AESKey, sha: Sha256): Messages.Asset.Preview = returning(new Messages.Asset.Preview()) { p =>
        p.mimeType = mime.str
        p.size = size
        p.remote = new Messages.Asset.RemoteData
        p.remote.otrKey = key.bytes
        p.remote.sha256 = sha.bytes
      }

      def apply(mime: Mime, size: Long, key: AESKey, sha: Sha256, metaData: Messages.Asset.ImageMetaData): Messages.Asset.Preview =
        returning(apply(mime, size, key, sha)) { _.setImage(metaData) }

      def apply(image: ImageData, key: AESKey, sha: Sha256): Messages.Asset.Preview =
        apply(Mime(image.mime), image.size, key, sha, ImageMetaData(Some(image.tag), image.width, image.height))

      def unapply(prev: Messages.Asset.Preview): Option[(Mime, Long, AESKey, Sha256, Option[AssetMetaData.Image])] =
        Some((Mime(prev.mimeType), prev.size, AESKey(prev.remote.otrKey), Sha256(prev.remote.sha256), ImageMetaData(prev)))
    }

    object Uploaded {
      def unapply(u: Messages.Asset.RemoteData): Option[(Option[RemoteKey], Option[AssetToken], AESKey, Sha256)] =
        if (u.otrKey == null || u.sha256 == null) None
        else Some((Option(u.assetId).map(RemoteKey(_)), Option(u.assetToken).filter(_.nonEmpty).map(AssetToken(_)), AESKey(u.otrKey), Sha256(u.sha256)))

      def apply(key: AESKey, sha: Sha256) =
        returning(new Messages.Asset.RemoteData) { s =>
          s.otrKey = key.bytes
          s.sha256 = sha.bytes
        }

      def apply(id: RemoteKey, token: Option[AssetToken], key: AESKey, sha: Sha256) =
        returning(new Messages.Asset.RemoteData) { s =>
          s.assetId = id.str
          token foreach { t => s.assetToken = t.str }
          s.otrKey = key.bytes
          s.sha256 = sha.bytes
        }
    }

    override def set(msg: GenericMessage) = msg.setAsset

    def apply(orig: Messages.Asset.Original, uploaded: Messages.Asset.RemoteData): Asset = returning(new Messages.Asset) { a =>
      a.original = orig
      a.setUploaded(uploaded)
    }

    def apply(mime: Mime, size: Long, name: Option[String], key: AESKey, sha: Sha256): Asset =
      apply(Original(mime, size, name), UploadDone(AssetKey(Left(RAssetDataId()), None, key, sha)))

    def apply(orig: Messages.Asset.Original): Asset = returning(new Messages.Asset) { a =>
      a.original = orig
    }

    def apply(orig: Messages.Asset.Original, preview: Preview, status: AssetStatus = AssetStatus.UploadInProgress): Asset = returning(new Messages.Asset) { a =>
      a.original = orig
      a.preview = preview
      setStatus(a, status)
    }

    def apply(orig: Messages.Asset.Original, status: AssetStatus): Asset =
      returning(new Messages.Asset) { a =>
        a.original = orig
        setStatus(a, status)
      }

    def apply(status: AssetStatus): Asset =
      returning(new Messages.Asset) { setStatus(_, status) }

    def apply(bytes: Array[Byte]): Asset = Messages.Asset.parseFrom(bytes)

    private def setStatus(a: Asset, status: AssetStatus) =
      status match {
        case UploadCancelled => a.setNotUploaded(Messages.Asset.CANCELLED)
        case UploadFailed    => a.setNotUploaded(Messages.Asset.FAILED)
        case _ =>
          status.key foreach {
            case AssetKey(Left(_), _, key, sha) => a.setUploaded(Uploaded(key, sha))
            case AssetKey(Right(id), token, key, sha) => a.setUploaded(Uploaded(id, token, key, sha))
          }
      }

    def unapply(a: Asset): Option[(Option[Messages.Asset.Original], Option[Preview], AssetStatus)] = {
      val status = a.getStatusCase match {
        case Messages.Asset.UPLOADED_FIELD_NUMBER =>
          a.getUploaded match {
            case Uploaded(id, token, aesKey, sha) =>
              UploadDone(AssetKey(id.fold2(Left(RAssetDataId.Empty), Right(_)), token, aesKey, sha)) // XXX: we may not have access to remote asset id in here, so will use empty, need to remember not to use this one
            case _ => UploadFailed // this will happen if sender didn't include aes key or sha
          }
        case Messages.Asset.NOT_UPLOADED_FIELD_NUMBER =>
          a.getNotUploaded match {
            case Messages.Asset.CANCELLED => UploadCancelled
            case Messages.Asset.FAILED => UploadFailed
            case _ => UploadInProgress
          }
        case _ => UploadInProgress
      }

      Some((Option(a.original), Option(a.preview), status))
    }

    object WithDimensions {
      def unapply(asset: Asset): Option[Dim2] = asset match {
        case Asset(Some(Asset.Original(_, _, _, Some(HasDimensions(d)), _)), _, _) => Some(d)
        case _ => None
      }
    }
  }
  implicit object EphemeralAsset extends EphemeralContent[Asset] {
    override def set(eph: Ephemeral): (Asset) => Ephemeral = eph.setAsset
  }

  type ImageAsset = Messages.ImageAsset
  implicit object ImageAsset extends GenericContent[ImageAsset] {
    override def set(msg: GenericMessage) = msg.setImage

    //tag, width, height, origWidth, origHeight, mime, size, Some(key), sha
    def unapply(proto: ImageAsset): Option[(String, Int, Int, Int, Int, Mime, Int, Option[AESKey], Option[Sha256])] =
      Some((proto.tag, proto.width, proto.height, proto.originalWidth, proto.originalHeight, Mime(proto.mimeType), proto.size, Option(proto.otrKey).map(AESKey(_)), Option(proto.sha256).map(Sha256(_))))

    def apply(tag: String, width: Int, height: Int, origWidth: Int, origHeight: Int, mime: String, size: Int, key: Option[AESKey], sha: Option[Sha256]): ImageAsset =
      returning(new Messages.ImageAsset) { a =>
        a.tag = tag
        a.width = width
        a.height = height
        a.originalWidth = origWidth
        a.originalHeight = origHeight
        a.mimeType = mime
        a.size = size
        key foreach { key => a.otrKey = key.bytes }
        sha foreach { sha => a.sha256 = sha.bytes }
      }
  }
  implicit object EphemeralImageAsset extends EphemeralContent[ImageAsset] {
    override def set(eph: Ephemeral): (ImageAsset) => Ephemeral = eph.setImage
  }

  type Mention = Messages.Mention
  object Mention {
    def apply(user: UserId, name: String) = returning(new Messages.Mention) { m =>
      m.userId = user.str
      m.userName = name
    }
  }

  type LinkPreview = Messages.LinkPreview
  object LinkPreview {

    trait PreviewMeta[A] {
      def apply(preview: LinkPreview, meta: A): LinkPreview
    }

    implicit object TweetMeta extends PreviewMeta[Tweet] {
      override def apply(preview: LinkPreview, meta: Tweet): LinkPreview = returning(preview) { _.setTweet(meta) }
    }

    def apply(uri: Uri, offset: Int): LinkPreview = returning(new Messages.LinkPreview) { p =>
      p.url = uri.toString
      p.urlOffset = offset
    }

    def apply(uri: Uri, offset: Int, title: String, summary: String, image: Option[Asset], permanentUrl: Option[Uri]): LinkPreview =
      returning(new Messages.LinkPreview) { p =>
        p.url = uri.toString
        p.urlOffset = offset
        p.title = title
        p.summary = summary
        permanentUrl foreach { u => p.permanentUrl = u.toString }
        image foreach { p.image = _ }

        // set article for backward compatibility, we will stop sending it once all platforms switch to using LinkPreview properties
        p.setArticle(article(title, summary, image, permanentUrl))
      }

    def apply[Meta: PreviewMeta](uri: Uri, offset: Int, title: String, summary: String, image: Option[Asset], permanentUrl: Option[Uri], meta: Meta): LinkPreview =
      returning(apply(uri, offset, title, summary, image, permanentUrl)) { p =>
        implicitly[PreviewMeta[Meta]].apply(p, meta)
      }

    type Tweet = Messages.Tweet
    object Tweet {

    }

    private def article(title: String, summary: String, image: Option[Asset], uri: Option[Uri]) = returning(new Messages.Article) { p =>
      p.title = title
      p.summary = summary
      uri foreach { u => p.permanentUrl = u.toString }
      image foreach { p.image = _ }
    }

    implicit object JsDecoder extends JsonDecoder[LinkPreview] {
      override def apply(implicit js: JSONObject): LinkPreview = Messages.LinkPreview.parseFrom(Base64.decode(js.getString("proto"), Base64.DEFAULT))
    }

    implicit object JsEncoder extends JsonEncoder[LinkPreview] {
      override def apply(v: LinkPreview): JSONObject = JsonEncoder { o =>
        o.put("proto", Base64.encodeToString(MessageNano.toByteArray(v), Base64.NO_WRAP))
      }
    }

    object WithAsset {
      def unapply(lp: LinkPreview): Option[Asset] = Option(lp.image) orElse { if (lp.hasArticle) Option(lp.getArticle.image) else None }
    }

    object WithDescription {
      def unapply(lp: LinkPreview): Option[(String, String)] =
        if (lp.hasArticle) Some((lp.getArticle.title, lp.getArticle.summary))
        else Some((lp.title, lp.summary))
    }
  }

  type Reaction = (Liking.Action, MessageId)
  implicit object Reaction extends GenericContent[Reaction] {

    override def set(msg: GenericMessage) = {
      case (l, id) => msg.setReaction(returning(new Messages.Reaction()) { r =>
        r.emoji = l match {
          case Liking.Action.Like   => heavyBlackHeart
          case Liking.Action.Unlike => ""
        }
        r.messageId = id.str
      })
    }

    val heavyBlackHeart = "\u2764\uFE0F"

    def apply(r: String, id: String): Reaction = (if (r == heavyBlackHeart) Liking.Action.Like else Liking.Action.Unlike, MessageId(id))

    def unapply(proto: Messages.Reaction): Option[Reaction] = Some(Reaction(proto.emoji, proto.messageId))
  }

  type Knock = Messages.Knock
  implicit object Knock extends GenericContent[Knock] {
    override def set(msg: GenericMessage) = msg.setKnock

    def apply(hotKnock: Boolean) = returning(new Messages.Knock())(_.hotKnock = hotKnock)

    def unapply(arg: Knock): Option[Boolean] = Some(arg.hotKnock)
  }
  implicit object EphemeralKnock extends EphemeralContent[Knock] {
    override def set(eph: Ephemeral): (Knock) => Ephemeral = eph.setKnock
  }

  type Text = Messages.Text
  implicit object Text extends GenericContent[Text] {
    override def set(msg: GenericMessage) = msg.setText

    def apply(content: String): Text = apply(content, Map.empty, Nil)

    def apply(content: String, links: Seq[LinkPreview]): Text = apply(content, Map.empty, links)

    def apply(content: String, mentions: Map[UserId, String], links: Seq[LinkPreview]): Text = returning(new Messages.Text()) { t =>
      t.content = content
      t.mention = mentions.map { case (user, name) => Mention(user, name) }(breakOut)
      t.linkPreview = links.toArray
    }

    def unapply(proto: Text): Option[(String, Map[UserId, String], Seq[LinkPreview])] =
      Some((proto.content, proto.mention.map(m => UserId(m.userId) -> m.userName).toMap, proto.linkPreview.toSeq))
  }
  implicit object EphemeralText extends EphemeralContent[Text] {
    override def set(eph: Ephemeral): (Text) => Ephemeral = eph.setText
  }

  type MsgEdit = Messages.MessageEdit
  implicit object MsgEdit extends GenericContent[MsgEdit] {
    override def set(msg: GenericMessage) = msg.setEdited

    def apply(ref: MessageId, content: Text) = returning(new MessageEdit) { c =>
      c.replacingMessageId = ref.str
      c.setText(content)
    }

    def unapply(arg: MsgEdit): Option[(MessageId, Text)] =
      arg.getContentCase match {
        case Messages.MessageEdit.TEXT_FIELD_NUMBER =>
          Some((MessageId(arg.replacingMessageId), arg.getText))
        case _ =>
          None
      }
  }

  type Cleared = Messages.Cleared
  implicit object Cleared extends GenericContent[Cleared] {
    override def set(msg: GenericMessage) = msg.setCleared

    def apply(conv: RConvId, time: Instant) = returning(new Messages.Cleared) { c =>
      c.conversationId = conv.str
      c.clearedTimestamp = time.toEpochMilli
    }

    def unapply(arg: Cleared): Option[(RConvId, Instant)] =
      for {
        conv <- Option(arg.conversationId)
        time <- Option(arg.clearedTimestamp)
      } yield (RConvId(conv), Instant.ofEpochMilli(time))
  }

  type LastRead = Messages.LastRead
  implicit object LastRead extends GenericContent[LastRead] {
    override def set(msg: GenericMessage) = msg.setLastRead

    def apply(conv: RConvId, time: Instant) = returning(new Messages.LastRead) { l =>
      l.conversationId = conv.str
      l.lastReadTimestamp = time.toEpochMilli
    }

    def unapply(arg: LastRead): Option[(RConvId, Instant)] =
      Some((RConvId(arg.conversationId), Instant.ofEpochMilli(arg.lastReadTimestamp)))
  }

  type MsgDeleted = Messages.MessageHide
  implicit object MsgDeleted extends GenericContent[MsgDeleted] {
    override def set(msg: GenericMessage) = msg.setHidden

    def apply(conv: RConvId, msg: MessageId) = returning(new Messages.MessageHide) { d =>
      d.conversationId = conv.str
      d.messageId = msg.str
    }

    def unapply(proto: MsgDeleted): Option[(RConvId, MessageId)] =
      Some((RConvId(proto.conversationId), MessageId(proto.messageId)))
  }

  type MsgRecall = Messages.MessageDelete
  implicit object MsgRecall extends GenericContent[MsgRecall] {
    override def set(msg: GenericMessage) = msg.setDeleted

    def apply(msg: MessageId) = returning(new Messages.MessageDelete) { d =>
      d.messageId = msg.str
    }

    def unapply(proto: MsgRecall): Option[MessageId] = Some(MessageId(proto.messageId))
  }

  type Location = Messages.Location
  implicit object Location extends GenericContent[Location] {
    override def set(msg: GenericMessage): (Location) => GenericMessage = msg.setLocation

    def apply(lon: Float, lat: Float, name: String, zoom: Int) = returning(new Messages.Location) { p =>
      p.longitude = lon
      p.latitude = lat
      p.name = name
      p.zoom = zoom
    }

    def unapply(l: Location): Option[(Float, Float, Option[String], Option[Int])] =
      Some((l.longitude, l.latitude, Option(l.name).filter(_.nonEmpty), Option(l.zoom).filter(_ != 0)))
  }
  implicit object EphemeralLocation extends EphemeralContent[Location] {
    override def set(eph: Ephemeral): (Location) => Ephemeral = eph.setLocation
  }

  type Receipt = Messages.Confirmation
  implicit object Receipt extends GenericContent[Receipt] {
    override def set(msg: GenericMessage) = msg.setConfirmation

    def apply(msg: MessageId) = returning(new Messages.Confirmation) { c =>
      c.messageId = msg.str
      c.`type` = Messages.Confirmation.DELIVERED
    }

    def unapply(proto: Receipt): Option[MessageId] = if (proto.`type` == Messages.Confirmation.DELIVERED) Some(MessageId(proto.messageId)) else None
  }

  type External = Messages.External
  implicit object External extends GenericContent[External] {
    override def set(msg: GenericMessage) = msg.setExternal

    def apply(key: AESKey, sha: Sha256) = returning(new Messages.External) { e =>
      e.otrKey = key.bytes
      e.sha256 = sha.bytes
    }

    def unapply(e: External): Option[(AESKey, Sha256)] =
      for {
        key <- Option(e.otrKey)
        sha <- Option(e.sha256)
      } yield (AESKey(key), Sha256(sha))
  }

  type Ephemeral = Messages.Ephemeral
  implicit object Ephemeral extends GenericContent[Ephemeral] {
    import scala.concurrent.duration._
    override def set(msg: GenericMessage): (Ephemeral) => GenericMessage = msg.setEphemeral

    def apply[Content: EphemeralContent](expiry: FiniteDuration, content: Content) = returning(new Messages.Ephemeral) { proto =>
      proto.expireAfterMillis = expiry.toMillis
      implicitly[EphemeralContent[Content]].set(proto)(content)
    }

    def unapply(proto: Ephemeral): Option[(FiniteDuration, Any)] = Some((proto.expireAfterMillis.millis, content(proto)))

    def content(e: Ephemeral) = e.getContentCase match {
      case Messages.Ephemeral.TEXT_FIELD_NUMBER => e.getText
      case Messages.Ephemeral.ASSET_FIELD_NUMBER => e.getAsset
      case Messages.Ephemeral.IMAGE_FIELD_NUMBER => e.getImage
      case Messages.Ephemeral.KNOCK_FIELD_NUMBER => e.getKnock
      case Messages.Ephemeral.LOCATION_FIELD_NUMBER => e.getLocation
      case _ => Unknown
    }
  }

  case object Unknown
  implicit object UnknownContent extends GenericContent[Unknown.type] {
    override def set(msg: GenericMessage) = { _ => msg }
  }

  sealed trait ClientAction {
    val value: Int
  }
  implicit object ClientAction extends GenericContent[ClientAction] {
    case object SessionReset extends ClientAction {
      override val value: Int = Messages.RESET_SESSION
    }
    case class UnknownAction(value: Int) extends ClientAction

    def apply(v: Int) = v match {
      case Messages.RESET_SESSION => SessionReset
      case other                  => UnknownAction(other)
    }

    override def set(msg: GenericMessage) = { action => msg.setClientAction(action.value) }
  }
}
