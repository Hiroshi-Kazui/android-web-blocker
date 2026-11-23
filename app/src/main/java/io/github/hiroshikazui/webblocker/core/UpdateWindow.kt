package io.github.hiroshikazui.webblocker.core

/**
 * アプリを更新できる時間の規則。
 * 保護中はファイルや PC からのインストールを禁止しているため、更新するときだけ一時的に許可する。
 * ブロック時間中は許可できず、許可中にブロック時間に入ったらその時点で閉じる。
 */
object UpdateWindow {
    const val DURATION_MS = 15 * 60_000L

    /** 許可の期限を返す。ブロック時間中は null (許可しない)。 */
    fun open(nowMs: Long, inBlockHours: Boolean): Long? = if (inBlockHours) null else nowMs + DURATION_MS

    /** 残り時間。期限切れ・ブロック時間中は 0。保存値が壊れていても DURATION_MS を超えない。 */
    fun remainingMs(nowMs: Long, untilMs: Long, inBlockHours: Boolean): Long =
        if (inBlockHours) 0L else (untilMs - nowMs).coerceIn(0L, DURATION_MS)
}
