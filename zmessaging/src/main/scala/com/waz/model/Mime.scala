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

import android.webkit.MimeTypeMap
import com.waz.utils._

import scala.PartialFunction.cond

case class Mime(str: String) {

  def extension = Option(MimeTypeMap.getSingleton.getExtensionFromMimeType(str)).getOrElse(str.drop(str.indexOf('/') + 1))

  def isEmpty = str.isEmpty

  def orElse(default: => Mime) = if (isEmpty) default else this
  def orDefault = if (isEmpty) Mime.Default else this
}

object Mime {
  val Unknown = Mime("")
  val Default = Mime("application/octet-stream")

  def fromFileName(fileName: String) = extensionOf(fileName).fold2(Unknown, fromExtension)
  def fromExtension(ext: String) = Option(MimeTypeMap.getSingleton.getMimeTypeFromExtension(ext)).fold2(Unknown, Mime(_))
  def extensionOf(fileName: String): Option[String] = fileName.lastIndexOf(".") match {
    case -1 | 0 => None
    case n  => Some(fileName.substring(n + 1))
  }

  object Video {
    val MP4 = Mime("video/mp4")
    val `3GPP` = Mime("video/3gpp")

    def unapply(mime: Mime): Boolean = cond(mime) {
      case MP4 => true
      case `3GPP` => true
    }
  }

  object Image {
    val PNG = Mime("image/png")

    def unapply(mime: Mime): Boolean = mime.str.startsWith("image/")
  }

  object Audio {
    val MP3 = Mime("audio/mp3")
    val MP4 = Mime("audio/mp4")
    val AAC = Mime("audio/aac")
    val `3GPP` = Mime("audio/3gpp")
    val AMR_NB = Mime("audio/amr-nb")
    val AMR_WB = Mime("audio/amr-wb")
    val Ogg = Mime("audio/ogg")
    val FLAC = Mime("audio/flac")
    val WAV = Mime("audio/wav")
    val PCM = Mime("audio/pcm-s16le;rate=44100;channels=1")

    def unapply(mime: Mime): Boolean = supported(mime)

    val supported = Set(MP3, Mime("audio/mpeg3"), Mime("audio/mpeg"), MP4, Mime("audio/x-m4a"), AAC, `3GPP`, AMR_NB, AMR_WB, Ogg, FLAC, WAV)
  }
}
