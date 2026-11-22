package io.github.hiroshikazui.webblocker.core

/**
 * DNS の転送先にするネットワークの選び方。
 * 端末は Wi-Fi とモバイル回線に同時につながっていることがあり、モバイル回線の DNS サーバーは
 * Wi-Fi 経由では応答しないことが多い。全ネットワークの DNS サーバーを混ぜると応答待ちで詰まるため、
 * 1 つのネットワークだけを選ぶ。
 */
object Upstream {
    data class Candidate<N, A>(
        val network: N,
        /** OS がインターネット接続を確認済み */
        val validated: Boolean,
        /** モバイル回線 */
        val cellular: Boolean,
        val dnsServers: List<A>,
    )

    /** 確認済みを優先し、その中で Wi-Fi など (モバイル回線以外) を優先する。DNS サーバーがないものは選ばない。 */
    fun <N, A> choose(candidates: List<Candidate<N, A>>): Candidate<N, A>? =
        candidates
            .filter { it.dnsServers.isNotEmpty() }
            .sortedWith(compareBy({ !it.validated }, { it.cellular }))
            .firstOrNull()

    /** 選んだネットワークの DNS サーバーの後ろに公開 DNS を足す (重複は除く)。 */
    fun <A> servers(chosen: List<A>, fallback: List<A>): List<A> = (chosen + fallback).distinct()

    /** DNS 応答が問い合わせへの答えか (先頭 2 バイトの ID が一致するか)。 */
    fun sameId(query: ByteArray, response: ByteArray, responseLen: Int): Boolean =
        query.size >= 2 && responseLen >= 2 && query[0] == response[0] && query[1] == response[1]
}
