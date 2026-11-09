package com.example.floatick.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.floatick.system.ClickAccessibilityService
import com.example.floatick.system.PermissionCheck

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(nav: NavController) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(PermissionCheck.snapshot(context)) }
    val serviceConnected by ClickAccessibilityService.connected.collectAsState()
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { state = PermissionCheck.snapshot(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限自检") },
                navigationIcon = {
                    TextButton(onClick = { nav.popBackStack() }) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("每次冷启动都会实时自检，状态不缓存。掉授权时所有定时任务自动暂停。")
            Spacer(Modifier.height(8.dp))
            Text(
                "服务连接：${if (serviceConnected) "已连接（可以执行点击）" else "未连接（不会执行任何点击）"}",
                style = MaterialTheme.typography.titleSmall,
                color = if (serviceConnected)
                    MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { state = PermissionCheck.snapshot(context) }) {
                Text("重新检测")
            }
            PermissionRow("无障碍服务 · 手势注入", state.accessibility) {
                PermissionCheck.openAccessibilitySettings(context)
            }
            PermissionRow("悬浮窗 · 控制台与取点器", state.overlay) {
                PermissionCheck.openOverlaySettings(context)
            }
            PermissionRow("通知 · 点「去设置」主动开启", state.notifications) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    PermissionCheck.openAppDetails(context)
                }
            }
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Android 13+ 侧载 APK 特别提示", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "如果无障碍开关是灰的，先到「应用详情 → 右上 ⋮ → 允许受限设置」" +
                            "完成本机身份验证，再回无障碍设置页开启。重装或换签名后需要重做一次。"
                    )
                    TextButton(onClick = { PermissionCheck.openAppDetails(context) }) {
                        Text("打开应用详情页")
                    }
                }
            }
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("开启步骤", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1. Android 13+ 侧载：设置 → 应用 → Floatick → 右上 ⋮ → 允许受限设置\n" +
                            "2. 设置 → 无障碍（或「辅助功能」）→ 已安装的应用 / 下载的服务 → Floatick → 打开\n" +
                            "3. 回到本页点「重新检测」，三项都显示已开；首页状态卡还应显示「服务 已连接」\n" +
                            "4. 若已打开却仍显示「服务 未连接」，把无障碍开关关闭再打开一次"
                    )
                }
            }
            Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("什么时候才需要重新开启？", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "授权是系统级持久状态：每次打开 App、重启手机都不用重开。" +
                            "只有卸载重装、换签名、系统升级、手动关闭，或个别 ROM 回收权限时才需要重新开启。"
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title)
                Text(if (granted) "已开启" else "未开启")
            }
            TextButton(onClick = onOpen) { Text("去设置") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(vm: MainViewModel, nav: NavController) {
    val sync by vm.syncStatus.collectAsState()
    val settings by vm.settings.collectAsState()
    val benchmark by vm.benchmarkResult.collectAsState()
    val logs by vm.logs.collectAsState()
    var advanceText by remember(settings.advanceMs) {
        mutableStateOf(settings.advanceMs.toString())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("校时与触发校准") },
                navigationIcon = {
                    TextButton(onClick = { nav.popBackStack() }) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("时间基准", style = MaterialTheme.typography.titleMedium)
            Text(sync.message)
            Text("offset ${sync.offsetMs}ms · RTT ${sync.rttMs}ms · 成功样本 ${sync.sampleCount}")
            Text("当前 UTC：${vm.nowUtcMillis()} ms")
            Text("状态：${if (vm.isSynced()) "校时有效" else "未校时（使用本机时钟）"}")
            if (!settings.syncEnabled) {
                Text("校时当前关闭，不会产生任何网络请求。可在设置页开启。")
            }
            Button(onClick = { vm.sync() }) { Text("立即校时") }
            TextButton(onClick = { nav.navigate("settings") }) {
                Text("修改 NTP 服务器 / 开关")
            }

            Spacer(Modifier.height(16.dp))
            Text("触发校准", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { vm.dryRun() }) { Text("空跑 20 次测本机触发抖动") }
            benchmark?.let {
                Text("空跑结果：P50 ${it.p50Ms}ms / P95 ${it.p95Ms}ms / ${it.count} 次")
            }
            val stats = vm.calibrationStats()
            Text("历史偏差：样本 ${stats.count} · P50 ${stats.p50Ms}ms · P95 ${stats.p95Ms}ms")
            LabeledField("提前量 advanceMs（补偿 Binder + 手势派发延迟）", advanceText) {
                advanceText = it
            }
            Button(onClick = {
                advanceText.toLongOrNull()?.let { vm.setAdvanceMs(it) }
            }) { Text("保存提前量") }

            Spacer(Modifier.height(8.dp))
            Text(
                "端到端误差 = 校时误差（±10~50ms，取决于网络 RTT）" +
                    " + GO 触发偏差（P95 ≤±30ms），合计约 ±50~100ms。" +
                    "这是相对外部 UTC 的承诺，不是硬实时，也不覆盖后台唤醒。"
            )

            Spacer(Modifier.height(16.dp))
            Text("最近运行日志", style = MaterialTheme.typography.titleMedium)
            logs.forEach { log ->
                Text(
                    "目标 ${log.targetUtcMillis} · 偏差 ${log.deviationMs}ms" +
                        " · 执行 ${log.executedCount} 次 · ${log.stopReason} · " +
                        if (log.synced) "已校时" else "未校时",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, nav: NavController) {
    val settings by vm.settings.collectAsState()
    val backupText by vm.backupText.collectAsState()
    var ntp by remember(settings.ntpServer) { mutableStateOf(settings.ntpServer) }
    var samples by remember(settings.sampleCount) {
        mutableStateOf(settings.sampleCount.toString())
    }
    var protectedText by remember(settings.protectedPackages) {
        mutableStateOf(settings.protectedPackages.joinToString("\n"))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    TextButton(onClick = { nav.popBackStack() }) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("时间校准", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("启用 SNTP 校时", Modifier.weight(1f))
                Switch(
                    checked = settings.syncEnabled,
                    onCheckedChange = { vm.setSyncEnabled(it) }
                )
            }
            Text(
                "默认关闭：关闭时 App 不产生任何网络请求。开启后只向下面填写的" +
                    " NTP 服务器发送 UDP 123 报文，不上传任何数据。"
            )
            LabeledField("NTP 服务器（仅连接此地址）", ntp) { ntp = it }
            Button(onClick = { vm.setNtpServer(ntp) }) { Text("保存服务器") }
            LabeledField("采样次数 (4-32)", samples) { samples = it }
            Button(onClick = {
                samples.toIntOrNull()?.let { vm.setSampleCount(it) }
            }) { Text("保存采样数") }
            Text("UDP 123 可能被部分运营商或企业 WiFi 拦截；此时请改用可达的时间源。")

            Spacer(Modifier.height(16.dp))
            Text("安全护栏（防误触）", style = MaterialTheme.typography.titleMedium)
            LabeledField("自定义受保护包名（每行一个）", protectedText) {
                protectedText = it
            }
            Button(onClick = {
                vm.setProtectedPackages(protectedText.lines())
            }) { Text("保存名单") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("一键全局停用", Modifier.weight(1f))
                Switch(
                    checked = settings.globalDisabled,
                    onCheckedChange = { vm.setGlobalDisabled(it) }
                )
            }
            Text("命中受保护应用或全局停用时，正在执行的任务会立即停止。")

            Spacer(Modifier.height(16.dp))
            Text("配置备份", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = backupText,
                onValueChange = { vm.onBackupTextChange(it) },
                label = { Text("方案 JSON") },
                modifier = Modifier.fillMaxWidth().height(140.dp)
            )
            Row {
                Button(onClick = { vm.exportBackup() }) { Text("导出到文本框") }
                Button(onClick = { vm.importBackup() }) { Text("从文本框导入") }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "隐私：默认不联网、不收集数据、不读取屏幕内容" +
                    "（无障碍配置中 canRetrieveWindowContent=false）。"
            )
        }
    }
}
