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

import com.waz.api
import com.waz.utils.{EnumCodec, JsonDecoder, JsonEncoder}
import org.json.{JSONException, JSONObject}

sealed abstract class AssetStatus(val status: api.AssetStatus, val key: Option[AssetKey] = None)
object AssetStatus {
  import JsonDecoder._
  import api.AssetStatus._

  sealed trait Sync
  type Syncable = AssetStatus with Sync

  case object UploadNotStarted extends AssetStatus(UPLOAD_NOT_STARTED)
  case object MetaDataSent extends AssetStatus(META_DATA_SENT)
  case object PreviewSent extends AssetStatus(PREVIEW_SENT)
  case object UploadInProgress extends AssetStatus(UPLOAD_IN_PROGRESS)
  case class UploadDone(uploaded: AssetKey) extends AssetStatus(UPLOAD_DONE, Some(uploaded))
  case object UploadCancelled extends AssetStatus(UPLOAD_CANCELLED) with Sync
  case object UploadFailed extends AssetStatus(UPLOAD_FAILED) with Sync
  case class DownloadFailed(uploaded: AssetKey) extends AssetStatus(DOWNLOAD_FAILED, Some(uploaded))

  implicit lazy val Order: Ordering[AssetStatus] = Ordering.by(_.status)

  def unapply(st: AssetStatus): Option[(api.AssetStatus, Option[AssetKey])] = Some((st.status, st.key))

  implicit lazy val AssetStatusDecoder: JsonDecoder[AssetStatus] = new JsonDecoder[AssetStatus] {
    override def apply(implicit js: JSONObject): AssetStatus = AssetStatusCodec.decode('status) match {
      case UPLOAD_NOT_STARTED   => UploadNotStarted
      case META_DATA_SENT       => MetaDataSent
      case PREVIEW_SENT         => PreviewSent
      case UPLOAD_IN_PROGRESS   => UploadInProgress
      case UPLOAD_DONE          => UploadDone(JsonDecoder[AssetKey]('key))
      case UPLOAD_CANCELLED     => UploadCancelled
      case UPLOAD_FAILED        => UploadFailed
      case DOWNLOAD_FAILED      => DownloadFailed(JsonDecoder[AssetKey]('key))
      case DOWNLOAD_DONE        => UploadDone(JsonDecoder[AssetKey]('key)) // this will never be used in AssetData
      case DOWNLOAD_IN_PROGRESS => UploadDone(JsonDecoder[AssetKey]('key)) // this will never be used in AssetData
    }
  }

  implicit lazy val AssetStatusEncoder: JsonEncoder[AssetStatus] = new JsonEncoder[AssetStatus] {
    override def apply(data: AssetStatus): JSONObject = JsonEncoder { o =>
      o.put("status", AssetStatusCodec.encode(data.status))
      data.key.foreach(c => o.put("key", JsonEncoder.encode(c)))
    }
  }

  implicit lazy val SyncableAssetStatusDecoder: JsonDecoder[Syncable] = AssetStatusDecoder.map {
    case a: AssetStatus.Syncable => a
    case other => throw new JSONException(s"not a syncable asset status: $other")
  }

  implicit lazy val SyncableAssetStatusEncoder: JsonEncoder[Syncable] = AssetStatusEncoder.comap(identity)

  implicit lazy val AssetStatusCodec: EnumCodec[api.AssetStatus, String] = EnumCodec.injective {
    case UPLOAD_NOT_STARTED   => "NotStarted"
    case META_DATA_SENT       => "MetaDataSent"
    case PREVIEW_SENT         => "PreviewSent"
    case UPLOAD_IN_PROGRESS   => "InProgress"
    case UPLOAD_DONE          => "Done"
    case UPLOAD_CANCELLED     => "Cancelled"
    case UPLOAD_FAILED        => "Failed"
    case DOWNLOAD_IN_PROGRESS => "DownloadInProgress"
    case DOWNLOAD_DONE        => "DownloadDone"
    case DOWNLOAD_FAILED      => "DownloadFailed"
  }
}
