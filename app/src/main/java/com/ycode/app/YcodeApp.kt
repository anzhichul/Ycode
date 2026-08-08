package com.ycode.app

import android.app.Application
import com.ycode.app.data.AppStore
import com.ycode.app.ui.crash.CrashHandler

class YcodeApp : Application() {
    lateinit var store: AppStore
        private set

    override fun onCreate() {
        super.onCreate()
        store = AppStore(this)
        if (CrashHandler.isMainProcess(this)) CrashHandler.install(this)
    }
}
