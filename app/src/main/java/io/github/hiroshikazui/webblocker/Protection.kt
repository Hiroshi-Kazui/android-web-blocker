package io.github.hiroshikazui.webblocker

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/** デバイスオーナーとして登録される受け口。登録された瞬間に保護をかける。 */
class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Protection.enforce(context)
    }
}

/**
 * デバイスオーナー権限で、このアプリと VPN を外せなくする。
 * Windows 版の「サービス保護」「暗号化 DNS の無効化」に相当する。
 * 解除する機能 (clearDeviceOwnerApp の呼び出し) は意図的に持たない。
 *
 * 保護を外す処理を持つ版を上書きインストールされると外れるため、ファイルや PC からのインストールも禁止する。
 * アプリを更新するときは、画面からブロック時間外に一時的に許可する ([BlockerState.openUpdateWindow])。
 */
object Protection {
    private const val TAG = "Protection"

    /**
     * かける制限。Android 9 (Fire OS 7) にない制限は、その版では使わない
     * (動いている端末の版を見て決めるので、新しい端末の制限はゆるくならない)。
     */
    private val RESTRICTIONS = buildList {
        // VPN の設定変更・解除
        add(UserManager.DISALLOW_CONFIG_VPN)
        // 設定画面からの初期化
        add(UserManager.DISALLOW_FACTORY_RESET)
        // セーフモード (他社アプリが止まり VPN も止まる)
        add(UserManager.DISALLOW_SAFE_BOOT)
        // 日時・タイムゾーンの変更
        add(UserManager.DISALLOW_CONFIG_DATE_TIME)
        // 別ユーザーではこの VPN が効かない
        add(UserManager.DISALLOW_ADD_USER)
        add(UserManager.DISALLOW_USER_SWITCH)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // プライベート DNS (DNS over TLS) で VPN の DNS を迂回されないようにする
            add(UserManager.DISALLOW_CONFIG_PRIVATE_DNS)
        }
    }

    /** 更新を許可している間だけ外す制限 */
    private val INSTALL_RESTRICTIONS = buildList {
        // ファイルからのアプリのインストール
        add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)
        }
        // adb (PC からの操作・インストール) と開発者向けオプション
        add(UserManager.DISALLOW_DEBUGGING_FEATURES)
    }

    /** ブラウザ内蔵の DNS over HTTPS を止める (Chromium 系の管理ポリシー)。 */
    private val CHROMIUM_BROWSERS = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.microsoft.emmx",
        "com.brave.browser",
        // Fire タブレットの Silk ブラウザ (Chromium 系。このポリシーが効くかは未確認)
        "com.amazon.cloud9",
    )

    fun isDeviceOwner(context: Context): Boolean =
        dpm(context).isDeviceOwnerApp(context.packageName)

    /** 現在の更新許可の状態に合わせて保護をかけ直す。 */
    fun enforce(context: Context) {
        val state = (context.applicationContext as WebBlockerApp).state
        apply(context, allowUpdate = state.updateRemainingMs() > 0)
    }

    fun apply(context: Context, allowUpdate: Boolean) {
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
        for (r in INSTALL_RESTRICTIONS) {
            if (allowUpdate) {
                step("制限 $r の一時解除") { dpm.clearUserRestriction(admin, r) }
            } else {
                step("制限 $r") { dpm.addUserRestriction(admin, r) }
            }
        }
        if (allowUpdate) {
            // adb を禁止したときに USB デバッグのスイッチも切られ、禁止を外しても戻らないため、ここで入れ直す
            step("USB デバッグを有効化") { dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1") }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            step("プライベート DNS を自動に固定") { dpm.setGlobalPrivateDnsModeOpportunistic(admin) }
        }
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
