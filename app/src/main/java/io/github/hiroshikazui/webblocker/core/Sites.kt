package io.github.hiroshikazui.webblocker.core

/**
 * ブロック対象サイトの既定リストと、ユーザー入力の正規化。
 *
 * ルールはドメイン名だけをとり、そのドメインとすべてのサブドメインをブロックする
 * (例: `nicovideo.jp` は `seiga.nicovideo.jp` も止める)。
 * DNS で止める方式なので、パス単位 (`amazon.co.jp/gp/video` など) では止められない。
 */
object Sites {
    /** 既定の時間帯ブロック対象 (ブロック時間中だけ止める)。music.youtube.com なども youtube.com に含まれる。 */
    val TIMED_DEFAULTS: List<String> = listOf(
        // YouTube
        "youtube.com",
        "youtu.be",
        "youtube-nocookie.com",
        "youtubei.googleapis.com",
        "googlevideo.com",
    )

    /** 既定の恒久ブロック対象 (時間帯に関係なく常に止める。外すこともできない)。 */
    val PERMANENT_DEFAULTS: List<String> = listOf(
        // dアニメストア / Lemino (docomo)
        "animestore.docomo.ne.jp",
        "lemino.docomo.ne.jp",
        // ABEMA
        "abema.tv",
        // Netflix
        "netflix.com",
        // Prime Video (amazon.co.jp 本体は買い物に使うため止めない)
        "primevideo.com",
        // Prime Video アプリが使う動画専用のドメイン (Fire HD の実機で再生時の問い合わせを記録して確認)
        "amazonvideo.com",
        "pv-cdn.net",
        "aiv-delivery.net",
        "prime-video.amazon.dev",
        // U-NEXT (書籍・マンガも同じドメインなので一緒に止まる)
        "unext.jp",
        // DMM (DMM TV だけでなく、ブックス・ゲームなど dmm.com 全体)
        "dmm.com",
        // Disney+
        "disneyplus.com",
        // Hulu (日本)
        "hulu.jp",
        // FOD / TVer / TELASA
        "fod.fujitv.co.jp",
        "tver.jp",
        "telasa.jp",
        // バンダイチャンネル
        "b-ch.com",
        // ニコニコ (静画・大百科なども同じドメインなので一緒に止まる)
        "nicovideo.jp",
        "nicochannel.jp",
        // 海外のアニメ配信 (bilibili は漫画も一緒に止まる)
        "crunchyroll.com",
        "hidive.com",
        "bilibili.com",
        "bilibili.tv",
        // 非公式のアニメ視聴サイト (ドメインを頻繁に変える)
        "anitube.biz",
        "anitube.pro",
        "anitube.vip",
        "gogoanime.by",
        "hianimetv.cfd",
        // 民放の見逃し配信 (TBS FREE / テレ朝動画 / ネットもテレ東)
        "cu.tbs.co.jp",
        "douga.tv-asahi.co.jp",
        "video.tv-tokyo.co.jp",
        // Rakuten Viki (韓国・中国ドラマ)
        "viki.com",
    )

    /** ユーザー入力をドメイン名に正規化する。失敗したら理由を返す。 */
    fun normalizeRule(input: String): Result<String> {
        var s = input.trim().lowercase()
        for (prefix in listOf("https://", "http://")) {
            s = s.removePrefix(prefix)
        }
        s = s.removePrefix("*.")
        // クエリ・フラグメントは捨てる
        val cut = s.indexOfAny(charArrayOf('?', '#'))
        if (cut >= 0) s = s.substring(0, cut)
        s = s.trimEnd('/')

        if (s.contains('/')) {
            return fail("パス単位ではブロックできません。ドメインだけを指定してください: $input")
        }
        if (s.contains(':')) {
            return fail("ポート番号は指定できません: $input")
        }
        val hostOk = s.isNotEmpty() &&
            s.contains('.') &&
            !s.startsWith('.') && !s.startsWith('-') &&
            !s.endsWith('.') && !s.endsWith('-') &&
            !s.contains("..") &&
            s.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }
        if (!hostOk) {
            return fail("ドメインとして解釈できません: $input")
        }
        return Result.success(s)
    }

    /** `host` がいずれかのルールそのもの、またはそのサブドメインならブロック対象。 */
    fun matches(host: String, rules: Collection<String>): Boolean {
        val h = host.lowercase().trimEnd('.')
        return rules.any { rule -> h == rule || h.endsWith(".$rule") }
    }

    private fun fail(message: String): Result<String> = Result.failure(IllegalArgumentException(message))
}
