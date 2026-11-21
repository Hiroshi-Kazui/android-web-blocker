package io.github.hiroshikazui.webblocker.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** 時刻サーバー (NTP) への問い合わせ。 */
object Ntp {
    private val SERVERS = listOf("time.android.com", "ntp.nict.jp", "time.google.com", "pool.ntp.org")
    private const val NTP_UNIX_OFFSET_SECS = 2_208_988_800L

    /** 2024-01-01。これより前を返すサーバー応答は捨てる。 */
    private const val MIN_VALID_MS = 1_704_067_200_000L

    /** NTP 応答パケットから送信時刻 (Unix ミリ秒) を取り出す。 */
    fun parseResponse(buf: ByteArray, len: Int = buf.size): Long? {
        if (len < 48) return null
        val secs = u32(buf, 40)
        val frac = u32(buf, 44)
        val ms = (secs - NTP_UNIX_OFFSET_SECS) * 1000 + ((frac * 1000) ushr 32)
        return ms.takeIf { it >= MIN_VALID_MS }
    }

    /** いずれかのサーバーと同期できたら、その時刻 (Unix ミリ秒) を返す。 */
    fun queryAny(): Long? = SERVERS.firstNotNullOfOrNull { query(it) }

    private fun query(server: String): Long? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = 3000
            val req = ByteArray(48).also { it[0] = 0x1B } // LI=0, VN=3, Mode=3 (client)
            val sent = System.nanoTime()
            socket.send(DatagramPacket(req, req.size, InetAddress.getByName(server), 123))
            val buf = ByteArray(48)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            val rttHalfMs = (System.nanoTime() - sent) / 2_000_000
            parseResponse(buf, resp.length)?.plus(rttHalfMs)
        }
    } catch (e: Exception) {
        null
    }

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
            ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)
}

/**
 * 改ざんされにくい現在時刻。
 *
 * - NTP と同期できたら、以後は端末の時計ではなく「同期時刻 + 経過時間 (起動からの単調増加タイマー)」を使う
 * - 同期できないときは端末の時計を使うが、過去に観測した最新時刻より前には戻さない
 *
 * @param wallMs 端末の時計 (Unix ミリ秒)
 * @param elapsedMs 単調増加タイマー (ミリ秒)。Android では SystemClock.elapsedRealtime
 * @param ntp NTP 問い合わせ。null を渡すと同期しない
 */
class TrustedClock(
    floorMs: Long,
    private val wallMs: () -> Long,
    private val elapsedMs: () -> Long,
    private val ntp: (() -> Long?)?,
) {
    /** (基準時刻, その時点の単調タイマー) */
    private var anchor: Pair<Long, Long>? = null
    private var floor = floorMs
    private var lastAttempt: Long? = null

    @Volatile
    var isSynced = false
        private set

    /** 必要なら NTP と同期する。未同期の間は 1 分ごと、同期後は 15 分ごと。ネットワーク待ちの間はロックを持たない。 */
    fun tick() {
        val query = ntp ?: return
        synchronized(this) {
            val interval = if (isSynced) SYNCED_INTERVAL_MS else UNSYNCED_INTERVAL_MS
            val last = lastAttempt
            if (last != null && elapsedMs() - last < interval) return
            lastAttempt = elapsedMs()
        }
        val ms = query()
        synchronized(this) {
            if (ms != null) {
                anchor = ms to elapsedMs()
                isSynced = true
            } else if (anchor == null) {
                // 一度も同期できていない: 端末の時計 (ただし最新観測時刻以上) を基準にし、以後は単調タイマーで進める
                anchor = maxOf(wallMs(), floor) to elapsedMs()
            }
        }
    }

    @Synchronized
    fun nowMs(): Long {
        val a = anchor
        val now = if (a != null) a.first + (elapsedMs() - a.second) else wallMs()
        floor = maxOf(now, floor)
        return floor
    }

    companion object {
        const val UNSYNCED_INTERVAL_MS = 60_000L
        const val SYNCED_INTERVAL_MS = 15 * 60_000L
    }
}
