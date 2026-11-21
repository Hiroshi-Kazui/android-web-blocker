package io.github.hiroshikazui.webblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SitesTest {
    @Test
    fun normalizesUrls() {
        assertEquals("www.example.com", Sites.normalizeRule("https://www.Example.com/").getOrThrow())
        assertEquals("example.com", Sites.normalizeRule("*.example.com").getOrThrow())
        assertEquals("example.com", Sites.normalizeRule("http://example.com?ref=x#top").getOrThrow())
        assertEquals("anime.example.net", Sites.normalizeRule("  anime.example.net  ").getOrThrow())
    }

    @Test
    fun rejectsInvalidAndPaths() {
        val bad = listOf(
            "", "localhost", "exa mple.com", "example..com", ".example.com",
            "example.com:8080", "例え.jp", "amazon.co.jp/gp/video",
        )
        for (b in bad) assertTrue("should reject '$b'", Sites.normalizeRule(b).isFailure)
    }

    @Test
    fun defaultSitesAreNormalizedAndDisjoint() {
        for (s in Sites.TIMED_DEFAULTS + Sites.PERMANENT_DEFAULTS) {
            assertEquals(s, Sites.normalizeRule(s).getOrThrow())
        }
        assertTrue(Sites.TIMED_DEFAULTS.none { it in Sites.PERMANENT_DEFAULTS })
    }

    @Test
    fun defaultsReflectAgreedScope() {
        // Amazon 本体は買い物に使うため入れない。dmm.com は全体を止める
        assertTrue(Sites.PERMANENT_DEFAULTS.none { it.contains("amazon") })
        assertTrue("dmm.com" in Sites.PERMANENT_DEFAULTS)
        assertTrue(Sites.matches("music.youtube.com", Sites.TIMED_DEFAULTS))
    }

    @Test
    fun matchesDomainAndSubdomainsOnly() {
        val rules = listOf("nicovideo.jp", "dmm.com")
        assertTrue(Sites.matches("nicovideo.jp", rules))
        assertTrue(Sites.matches("seiga.nicovideo.jp", rules))
        assertTrue(Sites.matches("BOOK.DMM.COM.", rules))
        assertFalse(Sites.matches("notnicovideo.jp", rules))
        assertFalse(Sites.matches("dmm.co.jp", rules))
        assertFalse(Sites.matches("amazon.co.jp", Sites.PERMANENT_DEFAULTS))
        assertTrue(Sites.matches("www.primevideo.com", Sites.PERMANENT_DEFAULTS))
    }
}

class ScheduleTest {
    private fun m(s: String) = Schedule.parseHhmm(s).getOrThrow()

    @Test
    fun overnightWindow() {
        val (s, e) = m("21:00") to m("07:00")
        assertFalse(Schedule.isBlocked(m("20:59"), s, e))
        assertTrue(Schedule.isBlocked(m("21:00"), s, e))
        assertTrue(Schedule.isBlocked(m("23:59"), s, e))
        assertTrue(Schedule.isBlocked(m("00:00"), s, e))
        assertTrue(Schedule.isBlocked(m("06:59"), s, e))
        assertFalse(Schedule.isBlocked(m("07:00"), s, e))
        assertFalse(Schedule.isBlocked(m("12:00"), s, e))
    }

    @Test
    fun sameDayWindow() {
        val (s, e) = m("09:00") to m("17:00")
        assertFalse(Schedule.isBlocked(m("08:59"), s, e))
        assertTrue(Schedule.isBlocked(m("09:00"), s, e))
        assertFalse(Schedule.isBlocked(m("17:00"), s, e))
    }

    @Test
    fun emptyWindowNeverBlocks() {
        assertFalse(Schedule.isBlocked(600, 600, 600))
    }

    @Test
    fun parseRejectsBadInput() {
        assertTrue(Schedule.parseHhmm("24:00").isFailure)
        assertTrue(Schedule.parseHhmm("7").isFailure)
        assertTrue(Schedule.parseHhmm("07:60").isFailure)
        assertEquals(425, m(" 7:05 "))
        assertEquals("07:05", Schedule.formatHhmm(425))
    }

    @Test
    fun minuteOfDayUsesFixedOffset() {
        // 2026-01-01T12:00:00Z は JST(+540) で 21:00
        val ms = 1_767_268_800_000L
        assertEquals(21 * 60, Schedule.minuteOfDay(ms, 540))
        assertEquals(12 * 60, Schedule.minuteOfDay(ms, 0))
        assertEquals(23 * 60, Schedule.minuteOfDay(0, -60))
    }
}

class ConfigTest {
    @Test
    fun timedSitesCanBeAddedAndRemoved() {
        val c = Config(utcOffsetMinutes = 540)
        val base = c.timedRules().size

        assertTrue(c.addSite("video.example.com", false))
        assertFalse(c.addSite("video.example.com", false))
        assertFalse(c.addSite("youtube.com", false))
        assertEquals(base + 1, c.timedRules().size)

        assertEquals(Removal.REMOVED, c.removeSite("youtube.com"))
        assertFalse("youtube.com" in c.timedRules())
        assertEquals(Removal.NOT_FOUND, c.removeSite("youtube.com"))
        assertTrue(c.addSite("youtube.com", false))
        assertTrue("youtube.com" in c.timedRules())

        assertEquals(Removal.REMOVED, c.removeSite("video.example.com"))
        assertEquals(Removal.NOT_FOUND, c.removeSite("unknown.example.com"))
        assertEquals(base, c.timedRules().size)
    }

    @Test
    fun permanentSitesCannotBeRemoved() {
        val c = Config(utcOffsetMinutes = 540)
        assertEquals(Removal.PERMANENT, c.removeSite("primevideo.com"))
        assertTrue(c.addSite("anime.example.com", true))
        assertFalse(c.addSite("anime.example.com", true))
        assertFalse(c.addSite("anime.example.com", false))
        assertEquals(Removal.PERMANENT, c.removeSite("anime.example.com"))
        // 設定ファイルで既定サイトを外していても、恒久ブロックの既定サイトは残る
        c.disabledDefaults += "netflix.com"
        assertTrue("netflix.com" in c.permanentRules())
    }

    @Test
    fun timedSiteCanBePromotedToPermanent() {
        val c = Config(utcOffsetMinutes = 540)
        assertTrue(c.addSite("video.example.com", false))
        assertTrue(c.addSite("video.example.com", true))
        assertFalse("video.example.com" in c.timedRules())
        assertEquals(Removal.PERMANENT, c.removeSite("video.example.com"))
        assertTrue(c.addSite("youtube.com", true))
        assertFalse("youtube.com" in c.timedRules())
    }

    @Test
    fun activeRulesFollowBlockHours() {
        val c = Config(utcOffsetMinutes = 540)
        val day = c.activeRules(false)
        val night = c.activeRules(true)
        assertTrue("primevideo.com" in day)
        assertFalse("youtube.com" in day)
        assertTrue("primevideo.com" in night)
        assertTrue("youtube.com" in night)
    }

    @Test
    fun brokenHoursFallBackToSafeWindow() {
        val c = Config(blockStart = "xx", utcOffsetMinutes = 540)
        assertEquals(21 * 60 to 7 * 60, c.window())
    }

    @Test
    fun jsonRoundTrip() {
        val c = Config(blockStart = "22:00", blockEnd = "06:30", utcOffsetMinutes = 540)
        c.addSite("a.example.com", false)
        c.addSite("b.example.com", true)
        c.removeSite("youtu.be")
        val r = Config.fromJson(c.toJson())
        assertEquals("22:00", r.blockStart)
        assertEquals("06:30", r.blockEnd)
        assertEquals(540, r.utcOffsetMinutes)
        assertEquals(listOf("a.example.com"), r.customSites)
        assertEquals(listOf("b.example.com"), r.permanentSites)
        assertEquals(listOf("youtu.be"), r.disabledDefaults)
    }
}

class CommandsTest {
    @Test
    fun removalAndHourChangeAreRefusedDuringBlockHours() {
        val c = Config(utcOffsetMinutes = 540)
        val r = Commands.remove(c, listOf("youtube.com"), inBlockHours = true)
        assertFalse(r.ok)
        assertFalse(r.changed)
        assertTrue("youtube.com" in c.timedRules())
        val h = Commands.setHours(c, "22:00", "06:00", inBlockHours = true)
        assertFalse(h.ok)
        assertEquals("21:00", c.blockStart)
    }

    @Test
    fun addIsAlwaysAllowed() {
        val c = Config(utcOffsetMinutes = 540)
        val r = Commands.add(c, listOf("https://Video.Example.com/"), permanent = false)
        assertTrue(r.ok)
        assertTrue(r.changed)
        assertTrue("video.example.com" in c.timedRules())
    }

    @Test
    fun mixedInputsReportEachKind() {
        val c = Config(utcOffsetMinutes = 540)
        val r = Commands.add(c, listOf("new.example.com", "youtube.com", "bad"), permanent = false)
        assertFalse(r.ok)
        assertTrue(r.changed)
        assertTrue(r.message.contains("時間帯ブロックに追加しました: new.example.com"))
        assertTrue(r.message.contains("登録済みです: youtube.com"))
        assertTrue(r.message.contains("ドメインとして解釈できません"))
    }

    @Test
    fun permanentRemovalIsRefusedOutsideBlockHours() {
        val c = Config(utcOffsetMinutes = 540)
        val r = Commands.remove(c, listOf("netflix.com"), inBlockHours = false)
        assertFalse(r.ok)
        assertTrue(r.message.contains("恒久ブロックのため外せません"))
    }

    @Test
    fun hoursChangeValidates() {
        val c = Config(utcOffsetMinutes = 540)
        assertFalse(Commands.setHours(c, "10:00", "10:00", false).ok)
        assertFalse(Commands.setHours(c, "25:00", "10:00", false).ok)
        val ok = Commands.setHours(c, "9:5", "17:00", false)
        assertTrue(ok.ok)
        assertEquals("09:05", c.blockStart)
    }
}
