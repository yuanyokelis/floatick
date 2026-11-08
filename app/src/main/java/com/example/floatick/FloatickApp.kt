package com.example.floatick

import android.app.Application
import com.example.floatick.system.Notifications
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FloatickApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}
