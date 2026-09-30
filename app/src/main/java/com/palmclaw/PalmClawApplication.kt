package com.palmclaw

import android.app.Application
import com.palmclaw.runtime.alwayson.AlwaysOnRuntimeAccess
import com.palmclaw.runtime.alwayson.AlwaysOnTrigger
import com.palmclaw.phonecontrol.ApkMcpApp
import com.palmclaw.providers.duck.DuckWebViewBridge

class PalmClawApplication : Application() {
    val appContainer: AppContainer by lazy {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        appContainer
        ApkMcpApp.appContext = this
        DuckWebViewBridge.init(this)
        AlwaysOnRuntimeAccess.requestReconcile(AlwaysOnTrigger.INITIALIZE)
    }
}
