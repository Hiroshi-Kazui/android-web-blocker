package io.github.hiroshikazui.webblocker

import android.app.Application

class WebBlockerApp : Application() {
    lateinit var state: BlockerState
        private set

    override fun onCreate() {
        super.onCreate()
        state = BlockerState(this)
        state.startBackground()
        // 旧版でかけたデバイスオーナーの保護が残っていれば外す
        Protection.release(this)
    }
}
