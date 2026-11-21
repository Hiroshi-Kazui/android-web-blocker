package io.github.hiroshikazui.webblocker

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.hiroshikazui.webblocker.core.Commands
import io.github.hiroshikazui.webblocker.core.Config
import io.github.hiroshikazui.webblocker.core.Ntp
import io.github.hiroshikazui.webblocker.core.Outcome
import io.github.hiroshikazui.webblocker.core.Schedule
import io.github.hiroshikazui.webblocker.core.Sites
import io.github.hiroshikazui.webblocker.core.TrustedClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.TimeZone
import kotlin.concurrent.thread

/** 画面に出す状態。 */
data class Snapshot(
    val inBlockHours: Boolean,
    val blockStart: String,
    val blockEnd: String,
    val ntpSynced: Boolean,
    val permanent: List<String>,
    val timed: List<String>,
)

/** 設定と時計を持ち、VPN と画面の両方から使われる。 */
class BlockerState(context: Context) {
    private val configFile = File(context.filesDir, "config.json")
    private val prefs = context.getSharedPreferences("state", Context.MODE_PRIVATE)
    private val clock = TrustedClock(
        floorMs = prefs.getLong(KEY_LAST_SEEN, 0L),
        wallMs = System::currentTimeMillis,
        elapsedMs = SystemClock::elapsedRealtime,
        ntp = Ntp::queryAny,
    )
    private var config: Config = loadConfig()
    private val _snapshot = MutableStateFlow(buildSnapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    /** NTP 同期と、最新観測時刻の保存を続ける。 */
    fun startBackground() {
        thread(isDaemon = true, name = "trusted-clock") {
            while (true) {
                clock.tick()
                prefs.edit().putLong(KEY_LAST_SEEN, clock.nowMs()).apply()
                refresh()
                Thread.sleep(BACKGROUND_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    fun inBlockHours(): Boolean {
        val (start, end) = config.window()
        return Schedule.isBlocked(Schedule.minuteOfDay(clock.nowMs(), config.utcOffsetMinutes), start, end)
    }

    @Synchronized
    fun shouldBlock(host: String): Boolean = Sites.matches(host, config.activeRules(inBlockHours()))

    fun add(inputs: List<String>, permanent: Boolean): Outcome = mutate { Commands.add(config, inputs, permanent) }

    fun remove(inputs: List<String>): Outcome = mutate { Commands.remove(config, inputs, inBlockHours()) }

    fun setHours(start: String, end: String): Outcome = mutate { Commands.setHours(config, start, end, inBlockHours()) }

    fun refresh() {
        _snapshot.value = buildSnapshot()
    }

    private fun mutate(op: () -> Outcome): Outcome {
        val outcome = synchronized(this) {
            val o = op()
            if (o.changed) saveConfig(config)
            o
        }
        refresh()
        return outcome
    }

    @Synchronized
    private fun buildSnapshot() = Snapshot(
        inBlockHours = inBlockHours(),
        blockStart = config.blockStart,
        blockEnd = config.blockEnd,
        ntpSynced = clock.isSynced,
        permanent = config.permanentRules(),
        timed = config.timedRules(),
    )

    private fun loadConfig(): Config {
        if (configFile.exists()) {
            try {
                return Config.fromJson(configFile.readText())
            } catch (e: Exception) {
                // 壊れていても恒久ブロックの既定サイトは常に効く。追加分は失われる
                Log.e(TAG, "config.json を読めないため作り直します", e)
            }
        }
        val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        return Config(utcOffsetMinutes = offset).also { saveConfig(it) }
    }

    /** 一時ファイルに書いてから置き換える (書きかけのファイルを残さない)。 */
    private fun saveConfig(c: Config) {
        val tmp = File(configFile.parentFile, "config.json.tmp")
        tmp.writeText(c.toJson())
        if (!tmp.renameTo(configFile)) {
            Log.e(TAG, "config.json を保存できませんでした")
        }
    }

    companion object {
        private const val TAG = "BlockerState"
        private const val KEY_LAST_SEEN = "last_seen_ms"
        private const val BACKGROUND_INTERVAL_MS = 30_000L
    }
}
