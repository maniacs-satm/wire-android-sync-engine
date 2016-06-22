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
package com.waz.mocked

import android.content.Context
import com.waz.media.manager.MediaManager
import com.waz.media.manager.context.IntensityLevel
import com.waz.service.{PlaybackRoute, PreferenceService, MediaManagerService}
import com.waz.utils.returning

class MockedMediaManagerService(context: Context, prefs: PreferenceService) extends MediaManagerService(context, prefs) {

  def changePlaybackRoute(route: PlaybackRoute): Unit = mediaManager foreach (_.onPlaybackRouteChanged(route.avsIndex))

  override lazy val mediaManager: Option[MediaManager] = {
    Some(returning(new MediaManager {
      override def setIntensity(level: IntensityLevel): Unit = ()
      override def EnableSpeaker(b: Boolean): Unit = ()
    })(_ addListener listener))
  }
}