package io.github.hiroshikazui.webblocker.core

import org.json.JSONArray
import org.json.JSONObject

enum class Removal { REMOVED, PERMANENT, NOT_FOUND }

/** 設定 (config.json)。項目は Windows 版 (web-blocker) と同じ。 */
class Config(
    var blockStart: String = "21:00",
    var blockEnd: String = "07:00",
    /** 時差 (分)。初回起動時の値で固定し、端末のタイムゾーン変更では動かない。 */
    val utcOffsetMinutes: Int,
    /** ユーザーが追加した時間帯ブロックのサイト */
    val customSites: MutableList<String> = mutableListOf(),
    /** ユーザーが外した時間帯ブロックの既定サイト (恒久ブロックの既定サイトはここに入れても外れない) */
    val disabledDefaults: MutableList<String> = mutableListOf(),
    /** ユーザーが追加した恒久ブロックのサイト */
    val permanentSites: MutableList<String> = mutableListOf(),
) {
    /** 設定の時刻が壊れていても安全側 (21:00-07:00) で判定する。 */
    fun window(): Pair<Int, Int> {
        val s = Schedule.parseHhmm(blockStart).getOrNull()
        val e = Schedule.parseHhmm(blockEnd).getOrNull()
        return if (s != null && e != null) s to e else (21 * 60) to (7 * 60)
    }

    fun isPermanent(rule: String): Boolean =
        rule in Sites.PERMANENT_DEFAULTS || rule in Sites.LINE_ADS || rule in permanentSites

    /** 恒久ブロックのサイト (既定 + LINE 広告 + 追加) */
    fun permanentRules(): List<String> = (Sites.PERMANENT_DEFAULTS + Sites.LINE_ADS + permanentSites).distinct()

    /** 時間帯ブロックのサイト (既定から外したものを除く + 追加。恒久ブロックと重なるものは除く) */
    fun timedRules(): List<String> =
        (Sites.TIMED_DEFAULTS.filter { it !in disabledDefaults } + customSites)
            .filter { !isPermanent(it) }
            .distinct()

    /** 今ブロックすべきサイト。恒久ブロックは常に、時間帯ブロックはブロック時間中だけ含む。 */
    fun activeRules(inBlockHours: Boolean): List<String> =
        if (inBlockHours) permanentRules() + timedRules() else permanentRules()

    /** サイトを追加する。戻り値は実際に増えたかどうか。時間帯ブロックのサイトを恒久として追加し直すと格上げされる。 */
    fun addSite(rule: String, permanent: Boolean): Boolean {
        if (isPermanent(rule)) return false
        if (permanent) {
            customSites.remove(rule)
            permanentSites.add(rule)
            return true
        }
        if (disabledDefaults.remove(rule)) return true
        if (rule in Sites.TIMED_DEFAULTS || rule in customSites) return false
        customSites.add(rule)
        return true
    }

    /** 時間帯ブロックのサイトを外す。恒久ブロックのサイトは外せない。 */
    fun removeSite(rule: String): Removal {
        if (isPermanent(rule)) return Removal.PERMANENT
        if (customSites.remove(rule)) return Removal.REMOVED
        if (rule in Sites.TIMED_DEFAULTS && rule !in disabledDefaults) {
            disabledDefaults.add(rule)
            return Removal.REMOVED
        }
        return Removal.NOT_FOUND
    }

    fun toJson(): String = JSONObject()
        .put("block_start", blockStart)
        .put("block_end", blockEnd)
        .put("utc_offset_minutes", utcOffsetMinutes)
        .put("custom_sites", JSONArray(customSites))
        .put("disabled_defaults", JSONArray(disabledDefaults))
        .put("permanent_sites", JSONArray(permanentSites))
        .toString(2)

    companion object {
        fun fromJson(text: String): Config {
            val o = JSONObject(text)
            fun list(key: String): MutableList<String> {
                val a = o.optJSONArray(key) ?: return mutableListOf()
                return MutableList(a.length()) { a.getString(it) }
            }
            return Config(
                blockStart = o.getString("block_start"),
                blockEnd = o.getString("block_end"),
                utcOffsetMinutes = o.getInt("utc_offset_minutes"),
                customSites = list("custom_sites"),
                disabledDefaults = list("disabled_defaults"),
                permanentSites = list("permanent_sites"),
            )
        }
    }
}
