package com.example.floatick.ui

import android.app.Activity
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.floatick.engine.ClickEngine
import com.example.floatick.system.FloatingConsole

@Composable
fun AppNav(engine: ClickEngine, console: FloatingConsole) {
    val vm: MainViewModel = hiltViewModel()
    val nav = rememberNavController()
    val context = LocalContext.current
    val engineState by engine.state.collectAsState()
    val message by vm.message.collectAsState()

    // 武装期间保持屏幕常亮：用 Activity flag，不需要 WAKE_LOCK 权限。
    val activity = context as? Activity
    LaunchedEffect(engineState.armed) {
        activity?.window?.let { window ->
            if (engineState.armed) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (Settings.canDrawOverlays(context)) {
            console.show(
                onStart = { vm.startFirstScheme() },
                onStop = { vm.stop() },
                onPick = { console.showPicker { x, y -> vm.setPicked(x, y) } }
            )
        }
    }
    LaunchedEffect(engineState) { console.update(engineState) }

    if (message.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { vm.clearMessage() },
            confirmButton = {
                TextButton(onClick = { vm.clearMessage() }) { Text("知道了") }
            },
            text = { Text(message) }
        )
    }

    NavHost(navController = nav, startDestination = "schemes") {
        composable("schemes") {
            SchemeListScreen(vm, nav)
        }
        composable(
            "edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            SchemeEditScreen(vm, nav, entry.arguments?.getLong("id") ?: 0L, console)
        }
        composable(
            "schedule/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            ScheduleScreen(vm, nav, entry.arguments?.getLong("id") ?: 0L)
        }
        composable("permissions") { PermissionScreen(nav) }
        composable("calibration") { CalibrationScreen(vm, nav) }
        composable("settings") { SettingsScreen(vm, nav) }
    }
}
