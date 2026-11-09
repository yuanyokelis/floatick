package com.example.floatick

import android.app.Application
import android.content.Context
import com.example.floatick.system.Notifications
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FloatickApp : Application() {

    // attachBaseContext 早于 Hilt 生成的 onCreate 注入逻辑，
    // 这样连依赖图构造阶段的异常也能被捕获。
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        CrashReporter.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        Notifications.createChannels(this)
    }
}
