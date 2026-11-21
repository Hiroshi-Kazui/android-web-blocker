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

/** デバイスオーナーとして登録される受け口。登録された瞬間に保護をかける。 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Protection.apply(context)
    }
}

/**
 * デバイスオーナー権限で、このアプリと VPN を外せなくする。
 * Windows 版の「サービス保護」「暗号化 DNS の無効化」に相当する。
 * 解除する機能 (clearDeviceOwnerApp の呼び出し) は意図的に持たない。
 */
object Protection {
    private const val TAG = "Protection"

    private val RESTRICTIONS = listOf(
        // VPN の設定変更・解除
        UserManager.DISALLOW_CONFIG_VPN,
        // 設定画面からの初期化
        UserManager.DISALLOW_FACTORY_RESET,
        // セーフモード (他社アプリが止まり VPN も止まる)
        UserManager.DISALLOW_SAFE_BOOT,
        // 日時・タイムゾーンの変更
        UserManager.DISALLOW_CONFIG_DATE_TIME,
        // プライベート DNS (DNS over TLS) で VPN の DNS を迂回されないようにする
        UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
        // 別ユーザーではこの VPN が効かない
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_USER_SWITCH,
        // adb (PC からの操作) と開発者向けオプション
        UserManager.DISALLOW_DEBUGGING_FEATURES,
    )

    /** ブラウザ内蔵の DNS over HTTPS を止める (Chromium 系の管理ポリシー)。 */
    private val CHROMIUM_BROWSERS = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.microsoft.emmx",
        "com.brave.browser",
    )

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    fun apply(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = dpm(context)
        val admin = ComponentName(context, AdminReceiver::class.java)
        val pkg = context.packageName

        step("アンインストール禁止") { dpm.setUninstallBlocked(admin, pkg, true) }
        // lockdown=false: VPN が落ちた瞬間に全通信を止めることはしない (常時接続の再起動に任せる)
        step("常時接続 VPN") { dpm.setAlwaysOnVpnPackage(admin, pkg, false) }
        for (r in RESTRICTIONS) {
            step("制限 $r") { dpm.addUserRestriction(admin, r) }
        }
        step("プライベート DNS を自動に固定") { dpm.setGlobalPrivateDnsModeOpportunistic(admin) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 強制停止・データ消去を禁止
            step("強制停止の禁止") { dpm.setUserControlDisabledPackages(admin, listOf(pkg)) }
        }
        val browserPolicy = Bundle().apply { putString("DnsOverHttpsMode", "off") }
        for (browser in CHROMIUM_BROWSERS) {
            step("$browser の DoH 無効化") { dpm.setApplicationRestrictions(admin, browser, browserPolicy) }
        }
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
