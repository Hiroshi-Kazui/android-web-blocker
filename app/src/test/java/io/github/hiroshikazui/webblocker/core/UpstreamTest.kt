package io.github.hiroshikazui.webblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpstreamTest {
    private fun c(name: String, validated: Boolean, cellular: Boolean, vararg dns: String) =
        Upstream.Candidate(name, validated, cellular, dns.toList())

    @Test
    fun wifiIsPreferredOverCellular() {
        // 実機 (Pixel) の構成: モバイル回線が先に並び、その DNS は Wi-Fi からは届かない
        val chosen = Upstream.choose(
            listOf(
                c("cell", true, true, "240b:c000::cafe:1", "172.16.206.49"),
                c("wifi", true, false, "8.8.8.8", "208.67.222.222"),
            ),
        )
        assertEquals("wifi", chosen?.network)
    }

    @Test
    fun validatedIsPreferredOverUnvalidated() {
        val chosen = Upstream.choose(
            listOf(c("wifi", false, false, "192.168.0.1"), c("cell", true, true, "172.16.0.1")),
        )
        assertEquals("cell", chosen?.network)
    }

    @Test
    fun networksWithoutDnsAreSkipped() {
        assertEquals("cell", Upstream.choose(listOf(c("wifi", true, false), c("cell", true, true, "1.2.3.4")))?.network)
        assertNull(Upstream.choose(listOf(c("wifi", true, false))))
        assertNull(Upstream.choose(emptyList<Upstream.Candidate<String, String>>()))
    }

    @Test
    fun serversAppendFallbackWithoutDuplicates() {
        assertEquals(
            listOf("8.8.8.8", "208.67.222.222", "1.1.1.1"),
            Upstream.servers(listOf("8.8.8.8", "208.67.222.222"), listOf("1.1.1.1", "8.8.8.8")),
        )
    }

    @Test
    fun sameIdComparesFirstTwoBytes() {
        val query = byteArrayOf(0x12, 0x34, 1, 0)
        assertTrue(Upstream.sameId(query, byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte()), 4))
        assertFalse(Upstream.sameId(query, byteArrayOf(0x12, 0x35, 0, 0), 4))
        assertFalse(Upstream.sameId(query, byteArrayOf(0x12, 0x34), 1))
    }
}
