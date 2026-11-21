package io.github.hiroshikazui.webblocker

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.Log

/** 旧版でデバイスオーナーとして登録された受け口。登録されてもすぐ解除する。 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Protection.release(context)
    }
}

/**
 * デバイスオーナーによる保護を解除する。
 *
 * 旧版 (0.1.0) はデバイスオーナー権限でアプリと VPN を外せなくしていたが、デバイスオーナーの端末には
 * 個人の Google アカウントを追加できず、端末が実用にならなかった。この版は起動時・更新時に
 * 旧版がかけた制限をすべて外し、デバイスオーナーを解除する。ブロック (VPN) 自体は続ける。
 */
object Protection {
    private const val TAG = "Protection"

    /** 旧版がかけていた制限 */
    private val LEGACY_RESTRICTIONS = listOf(
        UserManager.DISALLOW_CONFIG_VPN,
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_CONFIG_DATE_TIME,
        UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_USER_SWITCH,
        UserManager.DISALLOW_DEBUGGING_FEATURES,
    )

    /** 旧版が DNS over HTTPS を止めていたブラウザ */
    private val LEGACY_BROWSERS = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.microsoft.emmx",
        "com.brave.browser",
    )

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    fun release(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = ComponentName(context, AdminReceiver::class.java)
        val pkg = context.packageName

        step("アンインストール禁止の解除") { dpm.setUninstallBlocked(admin, pkg, false) }
        step("常時接続 VPN の解除") { dpm.setAlwaysOnVpnPackage(admin, null, false) }
        for (r in LEGACY_RESTRICTIONS) {
            step("制限 $r の解除") { dpm.clearUserRestriction(admin, r) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            step("強制停止禁止の解除") { dpm.setUserControlDisabledPackages(admin, emptyList()) }
        }
        for (browser in LEGACY_BROWSERS) {
            step("$browser のポリシー解除") { dpm.setApplicationRestrictions(admin, browser, Bundle()) }
        }
        step("デバイスオーナーの解除") {
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(pkg)
        }
        Log.i(TAG, "デバイスオーナーを解除しました: ${!isDeviceOwner(context)}")
    }

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(DevicePolicyManager::class.java)

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "$name に失敗", e)
        }
    }
}
