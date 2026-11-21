package io.github.hiroshikazui.webblocker.core

import io.github.hiroshikazui.webblocker.core.Schedule.formatHhmm

/** 操作の結果。`changed` が true なら設定を保存する必要がある。 */
data class Outcome(val ok: Boolean, val message: String, val changed: Boolean = false)

/** 画面からの操作 (追加・削除・時間変更)。Windows 版の CLI 要求の処理と同じ規則。 */
object Commands {
    fun add(config: Config, inputs: List<String>, permanent: Boolean): Outcome {
        val added = mutableListOf<String>()
        val existing = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        for (input in inputs) {
            Sites.normalizeRule(input).fold(
                onSuccess = { r -> if (config.addSite(r, permanent)) added += r else existing += r },
                onFailure = { e -> invalid += e.message.orEmpty() },
            )
        }
        val msg = mutableListOf<String>()
        if (added.isNotEmpty()) {
            val kind = if (permanent) "恒久ブロック" else "時間帯ブロック"
            msg += "${kind}に追加しました: ${added.joinToString(", ")}"
        }
        if (existing.isNotEmpty()) msg += "登録済みです: ${existing.joinToString(", ")}"
        msg += invalid
        return Outcome(invalid.isEmpty(), msg.joinToString("\n"), changed = added.isNotEmpty())
    }

    fun remove(config: Config, inputs: List<String>, inBlockHours: Boolean): Outcome {
        if (inBlockHours) {
            return Outcome(false, blockedMessage(config, "サイトを外すことは") + "追加だけは可能です。")
        }
        val removed = mutableListOf<String>()
        val permanent = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (input in inputs) {
            Sites.normalizeRule(input).fold(
                onSuccess = { r ->
                    when (config.removeSite(r)) {
                        Removal.REMOVED -> removed += r
                        Removal.PERMANENT -> permanent += r
                        Removal.NOT_FOUND -> missing += r
                    }
                },
                onFailure = { e -> missing += e.message.orEmpty() },
            )
        }
        val msg = mutableListOf<String>()
        if (removed.isNotEmpty()) msg += "外しました: ${removed.joinToString(", ")}"
        if (permanent.isNotEmpty()) msg += "恒久ブロックのため外せません: ${permanent.joinToString(", ")}"
        if (missing.isNotEmpty()) msg += "登録されていません: ${missing.joinToString(", ")}"
        return Outcome(missing.isEmpty() && permanent.isEmpty(), msg.joinToString("\n"), changed = removed.isNotEmpty())
    }

    fun setHours(config: Config, start: String, end: String, inBlockHours: Boolean): Outcome {
        if (inBlockHours) return Outcome(false, blockedMessage(config, "ブロック時間の変更は"))
        val s = Schedule.parseHhmm(start).getOrElse { return Outcome(false, it.message.orEmpty()) }
        val e = Schedule.parseHhmm(end).getOrElse { return Outcome(false, it.message.orEmpty()) }
        if (s == e) return Outcome(false, "開始と終了が同じ時刻です。")
        config.blockStart = formatHhmm(s)
        config.blockEnd = formatHhmm(e)
        return Outcome(true, "ブロック時間を ${formatHhmm(s)}〜${formatHhmm(e)} に変更しました。", changed = true)
    }

    private fun blockedMessage(config: Config, what: String): String {
        val (s, e) = config.window()
        return "ブロック時間中 (${formatHhmm(s)}〜${formatHhmm(e)}) は${what}できません。"
    }
}
