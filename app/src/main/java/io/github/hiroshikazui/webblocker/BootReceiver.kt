package io.github.hiroshikazui.webblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * 起動時・更新時に保護をかけ直し、VPN を立ち上げる。
 * デバイスオーナー設定済みなら OS の常時接続 VPN が先に起動するが、未設定の端末でも動くようにする。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Protection.enforce(context)
                // 利用許可済み (prepare が null) のときだけ起動できる
                if (VpnService.prepare(context) == null) {
                    try {
                        context.startService(Intent(context, BlockerVpnService::class.java))
                    } catch (e: IllegalStateException) {
                        // Fire OS などは起動直後の裏からのサービス開始を禁止している。
                        // その場合は OS の常時接続 VPN (保護、または設定画面) に起動を任せる
                        Log.w("BootReceiver", "VPN を起動できませんでした (常時接続 VPN の設定が必要): ${e.message}")
                    }
                }
            }
        }
    }
}
