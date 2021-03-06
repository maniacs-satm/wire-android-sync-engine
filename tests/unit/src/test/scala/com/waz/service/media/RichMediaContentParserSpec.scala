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
package com.waz.service.media

import com.waz.api.Message.Part.Type._
import com.waz.model.MessageContent
import org.scalacheck.Gen
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, TableDrivenPropertyChecks}
import org.scalatest.{FeatureSpec, Matchers, OptionValues, RobolectricTests}

import scala.io.Source

class RichMediaContentParserSpec extends FeatureSpec with Matchers with OptionValues with TableDrivenPropertyChecks with GeneratorDrivenPropertyChecks with RobolectricTests {
  lazy val parser = new RichMediaContentParser

  feature("match links") {

    scenario("match correct youtube links") {
      val links = List(
        "http://youtube.com/watch?v=c0KYU2j0TM4",
        "http://www.youtube.com/watch?v=c0KYU2j0TM4",
        "http://www.youtube.com/watch?v=c0KYU2j0TM4#t=100",
        "https://www.youtube.com/watch?v=c0KYU2j0TM4",
        "https://es.youtube.com/watch?v=c0KYU2j0TM4",
        "http://youtube.com/watch?v=c0KYU2j0TM4&wadsworth=1",
        "http://youtube.com/watch?v=c0KYU2j0TM4&wadsworth=1#t=100",
        "http://m.youtube.com/watch%3Fv%3D84zY33QZO5o",
        "https://www.youtube.com/watch?v=HuvLUhuo52w&feature=youtu.be",
        "http://youtu.be/c0KYU2j0TM4#t=100",
        "youtu.be/c0KYU2j0TM4#t=100"
      )

      links foreach { link =>
        parser.findMatches(link).toList shouldEqual List((0, link.length, YOUTUBE))
      }
    }

    scenario("don't match invalid youtube links") {
      val links = List(
        "http://m.youtube.com/watch?v=84zY33QZ&ved=0CB0QtwlwAA"
      )

      links foreach { link => parser.findMatches(link) should be(empty) }
    }

    scenario("match spotify link") {
      val link = "http://open.spotify.com/artist/5lsC3H1vh9YSRQckyGv0Up"
      parser.findMatches(link).toList shouldEqual List((0, link.length, SPOTIFY))
    }

    scenario("match soundcloud link") {
      val link = "https://soundcloud.com/majorlazer/major-lazer-dj-snake-lean-on-feat-mo"
      parser.findMatches(link).toList shouldEqual List((0, link.length, SOUNDCLOUD))
    }

    scenario("match weblinks") {
      val link = "https://www.google.de/url?sa=t&source=web&rct=j&ei=s-EzVMzyEoLT7Qb7loC4CQ&url=http://m.youtube.com/watch%3Fv%3D84zY33QZO5o&ved=0CB0QtwIwAA&usg=AFQjCNEgZ6mQSXLbKY1HAhVOEiAwHtTIvA"
      parser.findMatches(link, weblinkEnabled = true).toList shouldEqual List((0, link.length, WEB_LINK))
    }

    scenario("match weblinks with HTTP") {
      val link = "HTTP://Wire.com"
      parser.findMatches(link, weblinkEnabled = true).toList shouldEqual List((0, link.length, WEB_LINK))
    }

    scenario("match weblinks without http") {
      val link = "wire.com"
      parser.findMatches(link, weblinkEnabled = true).toList shouldEqual List((0, link.length, WEB_LINK))
    }

    scenario("ignore blacklisted weblinks") {
      val link = "giphy.com"
      parser.findMatches(link, weblinkEnabled = true).toList shouldBe empty
    }
  }

  feature("id parsing") {

    scenario("parse youtube id") {
      Map(
        "http://youtube.com/watch?v=c0KYU2j0TM4" -> "c0KYU2j0TM4",
        "http://www.youtube.com/watch?v=c0KYU2j0TM4" -> "c0KYU2j0TM4",
        "http://www.youtube.com/watch?v=c0KYU2j0TM4#t=100" -> "c0KYU2j0TM4",
        "https://www.youtube.com/watch?v=c0KYU2j0TM4" -> "c0KYU2j0TM4",
        "https://es.youtube.com/watch?v=c0KYU2j0TM4" -> "c0KYU2j0TM4",
        "http://youtube.com/watch?v=c0KYU2j0TM4&wadsworth=1" -> "c0KYU2j0TM4",
        "http://youtube.com/watch?wadsworth=1&v=c0KYU2j0TM4" -> "c0KYU2j0TM4",
        "http://youtube.com/watch?v=c0KYU2j0TM4&wadsworth=1#t=100" -> "c0KYU2j0TM4",
        "http://m.youtube.com/watch%3Fv%3D84zY33QZO5o" -> "84zY33QZO5o",
        "https://www.youtube.com/watch?v=HuvLUhuo52w&feature=youtu.be" -> "HuvLUhuo52w",
        "http://youtu.be/c0KYU2j0TM4#t=100" -> "c0KYU2j0TM4"
      ) foreach {
        case (url, id) => RichMediaContentParser.youtubeVideoId(url) shouldEqual Some(id)
      }
    }
  }

  feature("split content") {
    scenario("single youtube link") {
      parser.splitContent("https://www.youtube.com/watch?v=MWdG413nNkI") shouldEqual List(MessageContent(YOUTUBE, "https://www.youtube.com/watch?v=MWdG413nNkI"))
    }

    scenario("text with youtube link") {
      parser.splitContent("Here is some text. https://www.youtube.com/watch?v=MWdG413nNkI") shouldEqual List(MessageContent(TEXT, "Here is some text."), MessageContent(YOUTUBE, "https://www.youtube.com/watch?v=MWdG413nNkI"))
    }

    scenario("don't split proper uri") {
      parser.splitContent("https://www.youtube.com/watch?v=HuvLUhuo52w&feature=youtu.be") shouldEqual List(MessageContent(YOUTUBE, "https://www.youtube.com/watch?v=HuvLUhuo52w&feature=youtu.be"))
    }

    scenario("don't extract embeded url") {
      parser.splitContent("https://www.google.de/url?sa=t&source=web&rct=j&ei=s-EzVMzyEoLT7Qb7loC4CQ&url=http://m.youtube.com/watch%3Fv%3D84zY33QZO5o&ved=0CB0QtwIwAA&usg=AFQjCNEgZ6mQSXLbKY1HAhVOEiAwHtTIvA", weblinkEnabled = true) shouldEqual
        List(MessageContent(WEB_LINK, "https://www.google.de/url?sa=t&source=web&rct=j&ei=s-EzVMzyEoLT7Qb7loC4CQ&url=http://m.youtube.com/watch%3Fv%3D84zY33QZO5o&ved=0CB0QtwIwAA&usg=AFQjCNEgZ6mQSXLbKY1HAhVOEiAwHtTIvA"))
    }

    scenario("text interleaved with multiple youtube links") {
      parser.splitContent("Here is some text. https://www.youtube.com/watch?v=MWdG413nNkI more text https://www.youtube.com/watch?v=c0KYU2j0TM4 and even more") shouldEqual List(
        MessageContent(TEXT, "Here is some text."),
        MessageContent(YOUTUBE, "https://www.youtube.com/watch?v=MWdG413nNkI"),
        MessageContent(TEXT, "more text"),
        MessageContent(YOUTUBE, "https://www.youtube.com/watch?v=c0KYU2j0TM4"),
        MessageContent(TEXT, "and even more")
      )
    }
  }

  //See this page for where the ranges were fetched from:
  //http://apps.timwhitlock.info/emoji/tables/unicode
  //TODO there are still some emojis missing - but there are no clean lists for the ranges of unicode characters
  feature("Emoji") {

    lazy val emojis = Source.fromInputStream(getClass.getResourceAsStream("/emojis.txt")).getLines().toSeq.filterNot(_.startsWith("#"))

    lazy val whitespaces = " \t\n\r".toCharArray.map(_.toString).toSeq

    scenario("Regular text") {
      parser.splitContent("Hello") shouldEqual List(MessageContent(TEXT, "Hello"))
    }

    scenario("Regular text containing an emoji") {
      parser.splitContent("Hello \uD83D\uDE01") shouldEqual List(MessageContent(TEXT, "Hello \uD83D\uDE01"))
    }

    scenario("single emoji within the range Emoticons (1F600 - 1F64F)") {
      parser.splitContent("\uD83D\uDE00") shouldEqual List(MessageContent(TEXT_EMOJI_ONLY, "\uD83D\uDE00"))
      parser.splitContent("\uD83D\uDE4F") shouldEqual List(MessageContent(TEXT_EMOJI_ONLY, "\uD83D\uDE4F"))
    }

    scenario("single emoji within the range Transport and map symbols ( 1F680 - 1F6C0 )") {
      parser.splitContent("\uD83D\uDE80") shouldEqual List(MessageContent(TEXT_EMOJI_ONLY, "\uD83D\uDE80"))
      parser.splitContent("\uD83D\uDEC0") shouldEqual List(MessageContent(TEXT_EMOJI_ONLY, "\uD83D\uDEC0"))
    }

    scenario("single emoji within the range Uncategorized ( 1F300 - 1F5FF )") {
      parser.splitContent("\uD83C\uDF00") shouldEqual List(MessageContent(TEXT_EMOJI_ONLY, "\uD83C\uDF00"))
      parser.splitContent("\uD83D\uDDFF") shouldEqual List(MessageContent(TEXT_EMOJI_ONLY, "\uD83D\uDDFF"))
    }

    scenario("multiple emojis without any whitespace") {
      parser.splitContent("\uD83D\uDE4F\uD83D\uDE00") shouldEqual List(MessageContent(TEXT_EMOJI_ONLY, "\uD83D\uDE4F\uD83D\uDE00"))
    }

    scenario("flag emoji (norwegian)") {
      parser.splitContent("\uD83C\uDDF3\uD83C\uDDF4") shouldEqual List(MessageContent(TEXT_EMOJI_ONLY, "\uD83C\uDDF3\uD83C\uDDF4"))
    }

    scenario("multiple emojis with whitespace") { //TODO, check how we should preserve this whitespace
      parser.splitContent("\uD83D\uDE4F    \uD83D\uDE00  \uD83D\uDE05") shouldEqual List(MessageContent(TEXT_EMOJI_ONLY, "\uD83D\uDE4F    \uD83D\uDE00  \uD83D\uDE05"))
    }

    scenario("check all known emojis") {
      val (_, failed) = emojis.partition(RichMediaContentParser.containsOnlyEmojis)
      failed foreach { str =>
        info(str.map(_.toInt.toHexString).mkString(", "))
      }
      failed.toVector shouldBe empty
    }

    scenario("random emoji with whitespace") {

      case class EmojiStr(str: String) {
        override def toString: String = str.map(_.toInt.toHexString).mkString(", ")
      }

      val gen = for {
        list <- Gen.listOf(Gen.frequency((2, Gen.oneOf(emojis)), (1, Gen.oneOf(whitespaces))))
      } yield EmojiStr(list.mkString("").trim)

      forAll(gen) { str =>
        if (str.str.nonEmpty)
          parser.splitContent(str.str) shouldEqual Seq(MessageContent(TEXT_EMOJI_ONLY, str.str))
      }
    }
  }
}
