package io.github.hiroshikazui.webblocker

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import io.github.hiroshikazui.webblocker.core.Dns
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
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
    private var upstreamCache: Pair<Long, List<InetAddress>>? = null

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

    private fun forward(output: FileOutputStream, pkt: Dns.UdpPacket) {
        for (server in upstreamServers()) {
            try {
                DatagramSocket().use { socket ->
                    // このソケットの通信は VPN を通さない
                    protect(socket)
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS
                    socket.send(DatagramPacket(pkt.payload, pkt.payload.size, server, 53))
                    val resp = ByteArray(4096)
                    val dp = DatagramPacket(resp, resp.size)
                    socket.receive(dp)
                    reply(output, pkt, resp.copyOf(dp.length))
                }
                return
            } catch (e: IOException) {
                Log.d(TAG, "DNS 転送に失敗 ($server): ${e.message}")
            }
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

    /** VPN 以外のネットワーク (Wi-Fi・モバイル回線) の DNS サーバー。取れなければ公開 DNS を使う。 */
    @Suppress("DEPRECATION")
    private fun upstreamServers(): List<InetAddress> {
        val now = SystemClock.elapsedRealtime()
        upstreamCache?.let { (at, list) -> if (now - at < UPSTREAM_CACHE_MS) return list }
        val cm = getSystemService(ConnectivityManager::class.java)
        val fromNetworks = cm.allNetworks.flatMap { network ->
            val caps = cm.getNetworkCapabilities(network)
            if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            ) {
                emptyList()
            } else {
                cm.getLinkProperties(network)?.dnsServers.orEmpty()
            }
        }
        val list = (fromNetworks + FALLBACK_DNS.map { InetAddress.getByName(it) }).distinct()
        upstreamCache = now to list
        return list
    }

    companion object {
        private const val TAG = "BlockerVpn"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val DNS_ADDRESS = "10.111.222.2"
        private val DNS_ADDRESS_BYTES = byteArrayOf(10, 111, 222.toByte(), 2)
        private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
        private const val FORWARD_THREADS = 8
        private const val UPSTREAM_TIMEOUT_MS = 3000
        private const val UPSTREAM_CACHE_MS = 30_000L

        @Volatile
        var running = false
            private set
    }
}
