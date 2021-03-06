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
package com.waz.api.impl

import java.io.{File, InputStream}

import android.content.Context
import android.net.Uri
import com.waz.ZLog._
import com.waz.api
import com.waz.api.Asset.LoadCallback
import com.waz.api.AudioEffect
import com.waz.cache.CacheEntry
import com.waz.model.{AssetId, Mime}
import com.waz.service.ZMessaging
import com.waz.service.assets.AudioTranscoder
import com.waz.service.assets.GlobalRecordAndPlayService.{AssetMediaKey, PCMContent}
import com.waz.threading.Threading
import com.waz.utils.ContentURIs.queryContentUriMetaData
import com.waz.utils.events.Signal
import org.threeten.bp

import scala.concurrent.Future
import scala.concurrent.Future.successful
import scala.util.{Failure, Success}

abstract class AssetForUpload(val id: AssetId) extends api.AssetForUpload {
  def getId = id.str
  def name: Future[Option[String]]
  def mimeType: Future[Mime]
  def sizeInBytes: Future[Option[Long]]

  def cacheKey = id.str
  def openDataStream(context: Context): InputStream
}
object AssetForUpload {
  private implicit val Tag: LogTag = logTagFor[AssetForUpload]

  def apply(i: AssetId, n: Option[String], m: Mime, s: Option[Long])(f: Context => InputStream): AssetForUpload = new AssetForUpload(i) {
    override val name = successful(n)
    override val mimeType = successful(m)
    override val sizeInBytes = successful(s)
    override def openDataStream(context: Context) = f(context)
  }
}

case class TranscodedVideoAsset(fileName: Option[String], data: CacheEntry) extends AssetForUpload(AssetId()) {
  override def name         = successful(fileName)
  override def sizeInBytes  = successful(Some(data.length))
  override def mimeType     = successful(data.data.mimeType)
  override def openDataStream(context: Context) = data.inputStream
}

case class ContentUriAssetForUpload(override val id: AssetId, uri: Uri) extends AssetForUpload(id) {
  import Threading.Implicits.Background
  private lazy val info = queryContentUriMetaData(ZMessaging.context, uri)

  override lazy val name = info.map(_.name)
  override lazy val mimeType = info.map(_.mime)
  override lazy val sizeInBytes = info.map(_.size)

  override def openDataStream(context: Context): InputStream = context.getContentResolver.openInputStream(uri)
}

case class AudioAssetForUpload(override val id: AssetId, data: CacheEntry, duration: bp.Duration, fx: (AudioEffect, File) => Future[AudioAssetForUpload]) extends AssetForUpload(id) with api.AudioAssetForUpload {
  override def name         = successful(Some("recording.m4a"))
  override def sizeInBytes  = successful(Some(AudioTranscoder.estimatedSizeBasedOnBitrate(data.length)))
  override def mimeType     = successful(Mime.Audio.MP4)
  override def openDataStream(context: Context) = data.inputStream

  override def getPlaybackControls: api.PlaybackControls = new PlaybackControls(AssetMediaKey(id), PCMContent(data.cacheFile), _ => Signal.const(duration))(ZMessaging.currentUi)
  override def getDuration: bp.Duration = duration

  private implicit def logTag: LogTag = logTagFor[AudioAssetForUpload]

  override def delete(): Unit = {
    verbose(s"delete() $this")
    data.delete()
  }

  override def applyEffect(effect: api.AudioEffect, callback: LoadCallback[api.AudioAssetForUpload]): Unit = {
    verbose(s"applyEffect($effect) $this")
    fx(effect, data.cacheFile).onComplete {
      case Success(asset) =>
        callback.onLoaded(asset)
      case Failure(cause) =>
        error("effect application failed", cause)(logTagFor[AudioAssetForUpload])
        callback.onLoadFailed()
    }(Threading.Ui)
  }
}

object DoNothingAndProceed extends api.MessageContent.Asset.ErrorHandler {
  override def noWifiAndFileIsLarge(sizeInBytes: Long, net: api.NetworkMode, a: api.MessageContent.Asset.Answer): Unit = a.ok()
}

object CancelOnWarning extends api.MessageContent.Asset.ErrorHandler {
  override def noWifiAndFileIsLarge(sizeInBytes: Long, net: api.NetworkMode, a: api.MessageContent.Asset.Answer): Unit = a.cancel()
}
