package io.github.hiroshikazui.webblocker

import android.app.Application

class WebBlockerApp : Application() {
    lateinit var state: BlockerState
        private set

    override fun onCreate() {
        super.onCreate()
        state = BlockerState(this)
        state.startBackground()
        // 起動のたびに保護設定をかけ直す (Windows 版の定期的な再適用に相当)
        Protection.apply(this)
    }
}
