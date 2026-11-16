package io.github.tgwsproxy.android

import android.app.Application
import io.github.tgwsproxy.android.service.ProxyController

class TgWsProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProxyController.init(this)
    }
}
