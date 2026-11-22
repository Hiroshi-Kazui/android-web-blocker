package io.github.hiroshikazui.webblocker

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import io.github.hiroshikazui.webblocker.core.Dns
import io.github.hiroshikazui.webblocker.core.Upstream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

/**
 * 端末内だけで完結する VPN。端末の DNS サーバーを仮想アドレスに向け、届いた問い合わせのうち
 * ブロック対象は 0.0.0.0 を返し、それ以外は本来の DNS サーバーへ転送する。
 * DNS 以外の通信はトンネルを通らない (経路を仮想 DNS アドレスにしか張らないため)。
 */
class BlockerVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var pool: ExecutorService? = null

    private val state: BlockerState
        get() = (application as WebBlockerApp).state

    @Volatile
    private var routeCache: Pair<Long, Route>? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tun == null) start()
        return START_STICKY
    }

    override fun onRevoke() {
        stop()
        super.onRevoke()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun start() {
        val fd = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(DNS_ADDRESS)
            .addRoute(DNS_ADDRESS, 32)
            .setBlocking(true)
            .establish()
        if (fd == null) {
            // VPN の利用許可がまだない
            Log.w(TAG, "VPN を開始できません (未許可)")
            stopSelf()
            return
        }
        tun = fd
        pool = Executors.newFixedThreadPool(FORWARD_THREADS)
        running = true
        worker = thread(name = "vpn-reader") { readLoop(fd) }
    }

    private fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        pool?.shutdownNow()
        pool = null
        try {
            tun?.close()
        } catch (_: IOException) {
        }
        tun = null
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n > 0) handle(buf, n, output)
            }
        } catch (e: IOException) {
            // トンネルを閉じると read が失敗して抜ける
            Log.i(TAG, "トンネルの読み取りを終了: ${e.message}")
        }
    }

    private fun handle(buf: ByteArray, len: Int, output: FileOutputStream) {
        val pkt = Dns.parseUdp(buf, len) ?: return
        if (pkt.dstPort != 53 || !pkt.dstAddr.contentEquals(DNS_ADDRESS_BYTES)) return
        val query = Dns.parseQuery(pkt.payload) ?: return
        if (state.shouldBlock(query.name)) {
            reply(output, pkt, Dns.blockedResponse(pkt.payload, query))
            return
        }
        try {
            pool?.execute { forward(output, pkt) }
        } catch (_: RejectedExecutionException) {
            // 停止処理中
        }
    }

    /**
     * 選んだネットワークの DNS サーバー全部へ同時に問い合わせ、最初の答えを返す。
     * 一定時間答えがなければ公開 DNS にも問い合わせる。
     * 1 台ずつ順に待つと、応答しないサーバーが混じったときに問い合わせが詰まる。
     */
    private fun forward(output: FileOutputStream, pkt: Dns.UdpPacket) {
        val route = upstreamRoute()
        try {
            DatagramSocket().use { socket ->
                // このソケットの通信は VPN を通さず、選んだネットワークから出す
                protect(socket)
                route.network?.let { network ->
                    try {
                        network.bindSocket(socket)
                    } catch (e: IOException) {
                        Log.d(TAG, "ネットワークへの割り当てに失敗: ${e.message}")
                    }
                }
                val start = SystemClock.elapsedRealtime()
                var fallbackSent = false
                if (send(socket, pkt, route.primary) == 0) {
                    send(socket, pkt, route.fallback)
                    fallbackSent = true
                }
                val resp = ByteArray(4096)
                while (true) {
                    val elapsed = SystemClock.elapsedRealtime() - start
                    if (elapsed >= UPSTREAM_TIMEOUT_MS) break
                    val waitUntil = if (fallbackSent) UPSTREAM_TIMEOUT_MS else FALLBACK_AFTER_MS
                    socket.soTimeout = (waitUntil - elapsed).coerceAtLeast(1).toInt()
                    val dp = DatagramPacket(resp, resp.size)
                    try {
                        socket.receive(dp)
                    } catch (_: SocketTimeoutException) {
                        if (fallbackSent) break
                        send(socket, pkt, route.fallback)
                        fallbackSent = true
                        continue
                    }
                    if (Upstream.sameId(pkt.payload, resp, dp.length)) {
                        reply(output, pkt, resp.copyOf(dp.length))
                        return
                    }
                }
            }
            Log.d(TAG, "DNS 転送の応答なし")
        } catch (e: IOException) {
            Log.d(TAG, "DNS 転送に失敗: ${e.message}")
        }
        // ネットワークが切り替わった可能性があるので、次は選び直す
        routeCache = null
    }

    /** 送れた数を返す。 */
    private fun send(socket: DatagramSocket, pkt: Dns.UdpPacket, servers: List<InetAddress>): Int =
        servers.count { server ->
            try {
                socket.send(DatagramPacket(pkt.payload, pkt.payload.size, server, 53))
                true
            } catch (e: IOException) {
                Log.d(TAG, "DNS 問い合わせの送信に失敗 ($server): ${e.message}")
                false
            }
        }

    private fun reply(output: FileOutputStream, pkt: Dns.UdpPacket, payload: ByteArray) {
        val packet = Dns.buildUdp(DNS_ADDRESS_BYTES, pkt.srcAddr, 53, pkt.srcPort, payload)
        try {
            synchronized(output) { output.write(packet) }
        } catch (e: IOException) {
            Log.d(TAG, "応答の書き込みに失敗: ${e.message}")
        }
    }

    /** 転送に使うネットワークと、その DNS サーバー (primary)・予備の公開 DNS (fallback)。 */
    private class Route(val network: Network?, val primary: List<InetAddress>, val fallback: List<InetAddress>)

    /** VPN 以外のネットワークから 1 つ選ぶ (Wi-Fi があれば Wi-Fi)。取れなければ公開 DNS だけを使う。 */
    @Suppress("DEPRECATION")
    private fun upstreamRoute(): Route {
        val now = SystemClock.elapsedRealtime()
        routeCache?.let { (at, route) -> if (now - at < ROUTE_CACHE_MS) return route }
        val cm = getSystemService(ConnectivityManager::class.java)
        val candidates = cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                return@mapNotNull null
            }
            Upstream.Candidate(
                network = network,
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                dnsServers = cm.getLinkProperties(network)?.dnsServers.orEmpty(),
            )
        }
        val chosen = Upstream.choose(candidates)
        val primary = chosen?.dnsServers.orEmpty()
        val fallback = Upstream.servers(primary, FALLBACK_DNS.map { InetAddress.getByName(it) }) - primary.toSet()
        val route = Route(chosen?.network, primary, fallback)
        routeCache = now to route
        return route
    }

    companion object {
        private const val TAG = "BlockerVpn"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val DNS_ADDRESS = "10.111.222.2"
        private val DNS_ADDRESS_BYTES = byteArrayOf(10, 111, 222.toByte(), 2)
        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
        // 画像の多いページでは問い合わせが一度に数十件来る
        private const val FORWARD_THREADS = 32
        private const val UPSTREAM_TIMEOUT_MS = 4000L
        // この時間までに答えがなければ公開 DNS にも問い合わせる
        private const val FALLBACK_AFTER_MS = 1000L
        private const val ROUTE_CACHE_MS = 10_000L

        @Volatile
        var running = false
            private set
    }
}
