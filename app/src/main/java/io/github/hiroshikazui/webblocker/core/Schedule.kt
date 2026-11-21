package io.github.hiroshikazui.webblocker.core

/** ブロック時間帯の判定。時刻はすべて「0:00 からの経過分」(0..1439) で扱う。 */
object Schedule {
    const val MINUTES_PER_DAY = 1440L

    /** "HH:MM" を経過分に変換する。 */
    fun parseHhmm(s: String): Result<Int> {
        val parts = s.trim().split(':')
        if (parts.size != 2) {
            return Result.failure(IllegalArgumentException("時刻は HH:MM 形式で指定してください: $s"))
        }
        val h = parts[0].toIntOrNull() ?: return Result.failure(IllegalArgumentException("時が不正です: $s"))
        val m = parts[1].toIntOrNull() ?: return Result.failure(IllegalArgumentException("分が不正です: $s"))
        if (h !in 0..23 || m !in 0..59) {
            return Result.failure(IllegalArgumentException("時刻が範囲外です: $s"))
        }
        return Result.success(h * 60 + m)
    }

    fun formatHhmm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

    /** 開始 <= 現在 < 終了 ならブロック中。開始 > 終了 のときは日付をまたぐ (例: 21:00-07:00)。開始 == 終了 はブロックしない。 */
    fun isBlocked(now: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> now in start until end
        else -> now >= start || now < end
    }

    /** UTC のミリ秒と UTC からのオフセット (分) から、その地域の経過分を求める。 */
    fun minuteOfDay(utcMs: Long, utcOffsetMinutes: Int): Int {
        val localMinutes = Math.floorDiv(utcMs, 60_000L) + utcOffsetMinutes
        return Math.floorMod(localMinutes, MINUTES_PER_DAY).toInt()
    }
}
