package com.example.floatick.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.floatick.engine.ClickEngine
import com.example.floatick.system.FloatingConsole
import com.example.floatick.ui.theme.FloatickTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var console: FloatingConsole

    @Inject
    lateinit var engine: ClickEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        setContent {
            FloatickTheme {
                AppNav(engine = engine, console = console)
            }
        }
    }

    override fun onDestroy() {
        console.hide()
        super.onDestroy()
    }
}
