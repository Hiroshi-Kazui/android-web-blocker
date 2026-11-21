package io.github.hiroshikazui.webblocker.core

/**
 * VPN のトンネルを流れる DNS パケットの読み書き。
 * 扱うのは IPv4 / UDP の DNS 問い合わせだけ (このアプリは IPv4 の DNS サーバーだけを端末に配るため)。
 */
object Dns {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    private const val BLOCK_TTL_SECONDS = 60

    /** トンネルから読んだ IPv4/UDP パケットの要点。 */
    class UdpPacket(
        val srcAddr: ByteArray,
        val dstAddr: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    class Query(val name: String, val type: Int, val questionEnd: Int)

    fun parseUdp(buf: ByteArray, len: Int): UdpPacket? {
        if (len < 20 || (buf[0].toInt() ushr 4) != 4) return null
        val ihl = (buf[0].toInt() and 0x0f) * 4
        val total = u16(buf, 2)
        if (ihl < 20 || total > len || total < ihl + 8) return null
        if (buf[9].toInt() != 17) return null
        // 断片化したパケットは扱わない
        val fragment = u16(buf, 6) and 0x3fff
        if (fragment != 0) return null
        val udpLen = u16(buf, ihl + 4)
        if (udpLen < 8 || ihl + udpLen > total) return null
        return UdpPacket(
            srcAddr = buf.copyOfRange(12, 16),
            dstAddr = buf.copyOfRange(16, 20),
            srcPort = u16(buf, ihl),
            dstPort = u16(buf, ihl + 2),
            payload = buf.copyOfRange(ihl + 8, ihl + udpLen),
        )
    }

    /** IPv4/UDP パケットを組み立てる (UDP チェックサムは IPv4 では省略可なので 0)。 */
    fun buildUdp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        p[6] = 0x40 // Don't Fragment
        p[8] = 64 // TTL
        p[9] = 17 // UDP
        src.copyInto(p, 12)
        dst.copyInto(p, 16)
        put16(p, 10, ipChecksum(p, 0, 20))
        put16(p, 20, srcPort)
        put16(p, 22, dstPort)
        put16(p, 24, 8 + payload.size)
        payload.copyInto(p, 28)
        return p
    }

    fun ipChecksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += u16(b, i)
            i += 2
        }
        if (len % 2 == 1) sum += (b[off + len - 1].toInt() and 0xff) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return sum.inv().toInt() and 0xffff
    }

    /** DNS 問い合わせの最初の質問を読む。応答や圧縮付きの名前など、想定外の形なら null。 */
    fun parseQuery(msg: ByteArray): Query? {
        if (msg.size < 12) return null
        val isResponse = (msg[2].toInt() and 0x80) != 0
        if (isResponse || u16(msg, 4) < 1) return null
        val labels = mutableListOf<String>()
        var i = 12
        while (true) {
            if (i >= msg.size) return null
            val n = msg[i].toInt() and 0xff
            if (n == 0) {
                i += 1
                break
            }
            if (n > 63 || i + 1 + n > msg.size) return null
            labels += String(msg, i + 1, n, Charsets.US_ASCII).lowercase()
            i += 1 + n
        }
        if (i + 4 > msg.size) return null
        return Query(name = labels.joinToString("."), type = u16(msg, i), questionEnd = i + 4)
    }

    /** ブロックしたときの応答。A なら 0.0.0.0、AAAA なら :: を返し、それ以外は答えなしで返す。 */
    fun blockedResponse(msg: ByteArray, q: Query): ByteArray {
        val rdLen = when (q.type) {
            TYPE_A -> 4
            TYPE_AAAA -> 16
            else -> 0
        }
        val answerLen = if (rdLen > 0) 12 + rdLen else 0
        val out = ByteArray(q.questionEnd + answerLen)
        msg.copyInto(out, 0, 0, q.questionEnd)
        out[2] = (0x80 or (msg[2].toInt() and 0x01)).toByte() // 応答 + 再帰要求をそのまま返す
        out[3] = 0x80.toByte() // 再帰可能, エラーなし
        put16(out, 4, 1)
        put16(out, 6, if (rdLen > 0) 1 else 0)
        put16(out, 8, 0)
        put16(out, 10, 0)
        if (rdLen > 0) {
            val i = q.questionEnd
            put16(out, i, 0xc00c) // 名前は質問部 (先頭から 12 バイト目) を参照
            put16(out, i + 2, q.type)
            put16(out, i + 4, 1) // IN
            put16(out, i + 6, 0)
            put16(out, i + 8, BLOCK_TTL_SECONDS)
            put16(out, i + 10, rdLen)
            // アドレス部はすべて 0 (ByteArray の初期値)
        }
        return out
    }

    private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v ushr 8).toByte()
        b[i + 1] = v.toByte()
    }
}
