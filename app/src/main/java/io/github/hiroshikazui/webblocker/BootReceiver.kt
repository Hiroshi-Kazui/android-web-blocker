package io.github.hiroshikazui.webblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * 起動時・更新時に VPN を立ち上げる。
 * 旧版の保護が残っていれば外す。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Protection.release(context)
                // 利用許可済み (prepare が null) のときだけ起動できる
                if (VpnService.prepare(context) == null) {
                    context.startService(Intent(context, BlockerVpnService::class.java))
                }
            }
        }
    }
}
