package io.github.hiroshikazui.webblocker.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsTest {
    /** 問い合わせパケット (ID=0x1234, 再帰要求あり)。 */
    private fun query(name: String, type: Int): ByteArray {
        val out = mutableListOf<Byte>()
        out += listOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0).map { it.toByte() }
        for (label in name.split('.')) {
            out += label.length.toByte()
            out += label.toByteArray().toList()
        }
        out += 0
        out += listOf((type shr 8).toByte(), type.toByte(), 0, 1)
        return out.toByteArray()
    }

    @Test
    fun parsesQuestion() {
        val q = Dns.parseQuery(query("Video.Example.com", Dns.TYPE_A))
        assertNotNull(q)
        assertEquals("video.example.com", q!!.name)
        assertEquals(Dns.TYPE_A, q.type)
    }

    @Test
    fun rejectsResponsesAndTruncated() {
        val resp = query("example.com", Dns.TYPE_A).also { it[2] = 0x81.toByte() }
        assertNull(Dns.parseQuery(resp))
        assertNull(Dns.parseQuery(query("example.com", Dns.TYPE_A).copyOf(20)))
        assertNull(Dns.parseQuery(ByteArray(5)))
    }

    @Test
    fun blockedAResponseReturnsZeroAddress() {
        val msg = query("netflix.com", Dns.TYPE_A)
        val q = Dns.parseQuery(msg)!!
        val r = Dns.blockedResponse(msg, q)
        assertEquals(0x12, r[0].toInt() and 0xff)
        assertEquals(0x34, r[1].toInt() and 0xff)
        assertEquals(0x81, r[2].toInt() and 0xff) // 応答 + 再帰要求
        assertEquals(0x80, r[3].toInt() and 0xff) // エラーなし
        assertEquals(1, r[7].toInt()) // 回答 1 件
        assertEquals(q.questionEnd + 16, r.size)
        assertArrayEquals(ByteArray(4), r.copyOfRange(r.size - 4, r.size))
    }

    @Test
    fun blockedAaaaAndOtherTypes() {
        val aaaa = query("netflix.com", Dns.TYPE_AAAA)
        val qa = Dns.parseQuery(aaaa)!!
        assertEquals(qa.questionEnd + 28, Dns.blockedResponse(aaaa, qa).size)
        val https = query("netflix.com", 65)
        val qh = Dns.parseQuery(https)!!
        val r = Dns.blockedResponse(https, qh)
        assertEquals(qh.questionEnd, r.size)
        assertEquals(0, r[7].toInt()) // 回答なし
    }

    @Test
    fun udpRoundTrip() {
        val payload = query("example.com", Dns.TYPE_A)
        val src = byteArrayOf(10, 111, 222.toByte(), 1)
        val dst = byteArrayOf(10, 111, 222.toByte(), 2)
        val pkt = Dns.buildUdp(src, dst, 40000, 53, payload)
        assertEquals(0, Dns.ipChecksum(pkt, 0, 20)) // 正しいヘッダならチェックサム込みで 0 になる
        val parsed = Dns.parseUdp(pkt, pkt.size)!!
        assertArrayEquals(src, parsed.srcAddr)
        assertArrayEquals(dst, parsed.dstAddr)
        assertEquals(40000, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun ignoresNonUdpAndIpv6() {
        val pkt = Dns.buildUdp(ByteArray(4), ByteArray(4), 1, 53, ByteArray(10))
        pkt[9] = 6 // TCP
        assertNull(Dns.parseUdp(pkt, pkt.size))
        val v6 = ByteArray(60).also { it[0] = 0x60 }
        assertNull(Dns.parseUdp(v6, v6.size))
    }
}

class ClockTest {
    @Test
    fun parsesNtpTransmitTimestamp() {
        val buf = ByteArray(48)
        val secs = 1_767_268_800L + 2_208_988_800L // 2026-01-01T12:00:00Z
        for (i in 0..3) buf[40 + i] = (secs shr (24 - 8 * i)).toByte()
        buf[44] = 0x80.toByte() // 0.5 秒
        assertEquals(1_767_268_800_500L, Ntp.parseResponse(buf))
    }

    @Test
    fun rejectsShortOrBogusNtpPackets() {
        assertNull(Ntp.parseResponse(ByteArray(10)))
        assertNull(Ntp.parseResponse(ByteArray(48)))
    }

    @Test
    fun neverGoesBeforeFloor() {
        val future = System.currentTimeMillis() + 86_400_000L
        val c = TrustedClock(future, System::currentTimeMillis, { 0L }, null)
        assertTrue(c.nowMs() >= future)
    }

    @Test
    fun syncedClockIgnoresWallClockChanges() {
        var wall = 1_000_000L
        var elapsed = 0L
        val c = TrustedClock(0, { wall }, { elapsed }, { 5_000_000L })
        c.tick()
        assertTrue(c.isSynced)
        wall += 999_999_999L // 端末の時計を大きく進める
        elapsed += 1_000L
        assertEquals(5_001_000L, c.nowMs())
    }

    @Test
    fun unsyncedClockUsesMonotonicTimerAfterFirstAnchor() {
        var wall = 2_000_000L
        var elapsed = 0L
        val c = TrustedClock(0, { wall }, { elapsed }, { null })
        c.tick()
        assertFalse(c.isSynced)
        wall = 0L // 時計を巻き戻しても
        elapsed += 500L
        assertEquals(2_000_500L, c.nowMs())
    }

    @Test
    fun ntpRetryIsRateLimited() {
        var calls = 0
        var elapsed = 0L
        val c = TrustedClock(0, { 0L }, { elapsed }, { calls++; null })
        c.tick()
        c.tick()
        assertEquals(1, calls)
        elapsed += TrustedClock.UNSYNCED_INTERVAL_MS
        c.tick()
        assertEquals(2, calls)
    }
}
