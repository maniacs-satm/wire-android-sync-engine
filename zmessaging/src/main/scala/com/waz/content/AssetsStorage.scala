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
package com.waz.content

import android.content.Context
import com.waz.model.AssetData.AssetDataDao
import com.waz.model.{AnyAssetData, _}
import com.waz.threading.Threading
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.events.EventStream
import com.waz.utils.{CachedStorage, TrimmingLruCache, _}

import scala.concurrent.Future

class AssetsStorage(context: Context, storage: Database) extends CachedStorage[AssetId, AssetData](new TrimmingLruCache(context, Fixed(100)), storage)(AssetDataDao, "AssetsStorage") {
  import Threading.Implicits.Background

  val onUploadFailed = EventStream[AssetData]()

  def getImageAsset(id: AssetId) = get(id) map {
    case Some(image: ImageAssetData) => Some(image)
    case _ => None
  }

  def getAsset(id: AssetId) = get(id) map {
    case Some(asset: AnyAssetData) => Some(asset)
    case _ => None
  }

  def updateAsset(id: AssetId, updater: AnyAssetData => AnyAssetData): Future[Option[AnyAssetData]] = update(id, {
    case a: AnyAssetData => updater(a)
    case other => other
  }).mapOpt {
    case (_, updated: AnyAssetData) => Some(updated)
    case _ => None
  }
}
