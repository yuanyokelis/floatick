package com.example.floatick.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.floatick.core.ActionType
import com.example.floatick.core.EngineState
import com.example.floatick.core.Scheme
import com.example.floatick.core.SchemeType
import com.example.floatick.core.Step
import com.example.floatick.system.FloatingConsole
import com.example.floatick.system.PermissionCheck
import com.example.floatick.system.PermissionState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemeListScreen(vm: MainViewModel, nav: NavController) {
    val schemes by vm.schemes.collectAsState()
    val engineState by vm.engineState.collectAsState()
    val context = LocalContext.current
    var permissions by remember { mutableStateOf(PermissionCheck.snapshot(context)) }

    LaunchedEffect(Unit) { permissions = PermissionCheck.snapshot(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("定时点击方案") },
                actions = {
                    TextButton(onClick = { nav.navigate("permissions") }) { Text("权限") }
                    TextButton(onClick = { nav.navigate("calibration") }) { Text("校准") }
                    TextButton(onClick = { nav.navigate("settings") }) { Text("设置") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nav.navigate("edit/0") }) { Text("+") }
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            StatusCard(
                engineState = engineState,
                permissions = permissions,
                synced = vm.isSynced(),
                onRefresh = { permissions = PermissionCheck.snapshot(context) },
                onGoPermissions = { nav.navigate("permissions") },
                onStop = { vm.stop() }
            )
            Spacer(Modifier.height(12.dp))
            if (schemes.isEmpty()) {
                Text("还没有方案，点右下角 + 新建。")
            }
            schemes.forEach { scheme ->
                SchemeCard(scheme, vm, nav)
            }
        }
    }
}

@Composable
private fun StatusCard(
    engineState: EngineState,
    permissions: PermissionState,
    synced: Boolean,
    onRefresh: () -> Unit,
    onGoPermissions: () -> Unit,
    onStop: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                when {
                    engineState.running -> "执行中 · 剩余 ${engineState.remainingCount} 次"
                    engineState.armed -> "已武装 · ${engineState.message}"
                    else -> engineState.message.ifBlank { "空闲" }
                },
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text("时间基准：${if (synced) "已校时（UTC）" else "未校时 · 本机时钟"}")
            Text(
                "权限：无障碍 ${if (permissions.accessibility) "已开" else "未开"}" +
                    " / 悬浮窗 ${if (permissions.overlay) "已开" else "未开"}" +
                    " / 通知 ${if (permissions.notifications) "已开" else "未开"}",
                style = MaterialTheme.typography.bodySmall
            )
            Row {
                TextButton(onClick = onRefresh) { Text("重新检测") }
                TextButton(onClick = onGoPermissions) { Text("权限页") }
                TextButton(onClick = onStop) { Text("停止") }
            }
        }
    }
}

@Composable
private fun SchemeCard(scheme: Scheme, vm: MainViewModel, nav: NavController) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(scheme.name, style = MaterialTheme.typography.titleMedium)
            val type = if (scheme.type == SchemeType.SINGLE_REPEAT) "单点重复" else "多步骤序列"
            Text(
                "$type · ${scheme.loopCount} 轮 · ${scheme.steps.size} 步 · 间隔 ${scheme.intervalMs}ms",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { nav.navigate("edit/${scheme.id}") }) { Text("编辑") }
                TextButton(onClick = { nav.navigate("schedule/${scheme.id}") }) { Text("定时") }
                TextButton(onClick = { vm.startNow(scheme) }) { Text("立即执行") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.cancelArm(scheme.id) }) { Text("取消定时") }
                TextButton(onClick = { vm.stop() }) { Text("停止") }
                TextButton(onClick = { vm.delete(scheme.id) }) { Text("删除") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemeEditScreen(
    vm: MainViewModel,
    nav: NavController,
    schemeId: Long,
    console: FloatingConsole
) {
    var name by remember { mutableStateOf("新建方案") }
    var type by remember { mutableStateOf(SchemeType.SINGLE_REPEAT) }
    var loopText by remember { mutableStateOf("1") }
    var intervalText by remember { mutableStateOf("100") }
    var steps by remember { mutableStateOf(listOf(Step())) }
    var pickerTarget by remember { mutableStateOf(-1) }
    val picked by vm.picked.collectAsState()

    LaunchedEffect(schemeId) {
        if (schemeId != 0L) {
            vm.scheme(schemeId)?.let { s ->
                name = s.name
                type = s.type
                loopText = s.loopCount.toString()
                intervalText = s.intervalMs.toString()
                steps = s.steps.ifEmpty { listOf(Step()) }
            }
        }
    }

    LaunchedEffect(picked) {
        val point = picked ?: return@LaunchedEffect
        if (pickerTarget in steps.indices) {
            steps = steps.toMutableList().also { list ->
                list[pickerTarget] = list[pickerTarget].copy(
                    xRatio = point.first,
                    yRatio = point.second
                )
            }
        }
        vm.consumePicked()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (schemeId == 0L) "新建方案" else "编辑方案") },
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
            LabeledField("方案名称", name) { name = it }

            Text("方案类型", style = MaterialTheme.typography.labelLarge)
            Row {
                SchemeType.entries.forEach { t ->
                    TextButton(onClick = {
                        type = t
                        if (t == SchemeType.SINGLE_REPEAT) steps = steps.take(1)
                    }) {
                        val label = if (t == SchemeType.SINGLE_REPEAT) "单点重复" else "多步骤序列"
                        Text(if (type == t) "[$label]" else label)
                    }
                }
            }

            NumberField("重复轮数 (1-100000)", loopText) { loopText = it }
            NumberField("步骤间隔 ms (建议 >=50)", intervalText) { intervalText = it }

            Text("坐标以屏幕比例 0~1 存储；取点器支持 1px 微调")
            steps.forEachIndexed { index, step ->
                StepEditor(
                    index = index,
                    step = step,
                    onUpdate = { updated ->
                        steps = steps.toMutableList().also { it[index] = updated }
                    },
                    onPick = {
                        pickerTarget = index
                        console.showPicker { x, y -> vm.setPicked(x, y) }
                    }
                )
            }

            if (type == SchemeType.STEP_SEQUENCE) {
                Button(onClick = { steps = steps + Step(orderIndex = steps.size) }) {
                    Text("添加步骤")
                }
                if (steps.size > 1) {
                    TextButton(onClick = { steps = steps.dropLast(1) }) { Text("删除最后一步") }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val scheme = Scheme(
                        id = schemeId,
                        name = name.ifBlank { "未命名方案" },
                        type = type,
                        steps = if (type == SchemeType.SINGLE_REPEAT) steps.take(1) else steps,
                        loopCount = loopText.toIntOrNull()?.coerceIn(1, 100_000) ?: 1,
                        intervalMs = intervalText.toLongOrNull()?.coerceIn(50L, 3_600_000L) ?: 100L
                    )
                    vm.save(scheme) { nav.popBackStack() }
                }
            ) { Text("保存方案") }
        }
    }
}

@Composable
private fun StepEditor(
    index: Int,
    step: Step,
    onUpdate: (Step) -> Unit,
    onPick: () -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text("步骤 ${index + 1}", style = MaterialTheme.typography.titleSmall)
            Row {
                ActionType.entries.forEach { a ->
                    TextButton(onClick = { onUpdate(step.copy(action = a)) }) {
                        val label = when (a) {
                            ActionType.CLICK -> "点击"
                            ActionType.LONG_PRESS -> "长按"
                            ActionType.SWIPE -> "滑动"
                        }
                        Text(if (step.action == a) "[$label]" else label)
                    }
                }
            }
            LabeledField(
                "起点 X (0~1)", step.xRatio.toString(), decimal = true
            ) { v -> v.toFloatOrNull()?.let { onUpdate(step.copy(xRatio = it.coerceIn(0f, 1f))) } }
            LabeledField(
                "起点 Y (0~1)", step.yRatio.toString(), decimal = true
            ) { v -> v.toFloatOrNull()?.let { onUpdate(step.copy(yRatio = it.coerceIn(0f, 1f))) } }
            if (step.action == ActionType.SWIPE) {
                LabeledField("终点 X (0~1)", step.endXRatio.toString(), decimal = true) { v ->
                    v.toFloatOrNull()?.let { onUpdate(step.copy(endXRatio = it.coerceIn(0f, 1f))) }
                }
                LabeledField("终点 Y (0~1)", step.endYRatio.toString(), decimal = true) { v ->
                    v.toFloatOrNull()?.let { onUpdate(step.copy(endYRatio = it.coerceIn(0f, 1f))) }
                }
            }
            LabeledField("手势时长 ms", step.durationMs.toString()) { v ->
                v.toLongOrNull()?.let { onUpdate(step.copy(durationMs = it.coerceAtLeast(20L))) }
            }
            LabeledField("本步后额外等待 ms", step.delayAfterMs.toString()) { v ->
                v.toLongOrNull()?.let { onUpdate(step.copy(delayAfterMs = it.coerceAtLeast(0L))) }
            }
            TextButton(onClick = onPick) { Text("用取点器定位（支持 1px 微调）") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(vm: MainViewModel, nav: NavController, schemeId: Long) {
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS") }
    var input by remember {
        mutableStateOf(LocalDateTime.now().plusMinutes(1).withNano(0).format(formatter))
    }
    var schemeName by remember { mutableStateOf("") }
    val engineState by vm.engineState.collectAsState()
    val sync by vm.syncStatus.collectAsState()
    val settings by vm.settings.collectAsState()

    LaunchedEffect(schemeId) {
        schemeName = vm.scheme(schemeId)?.name ?: ""
        vm.scheduleFor(schemeId)?.let { rule ->
            input = LocalDateTime
                .ofInstant(Instant.ofEpochMilli(rule.triggerUtcMillis), ZoneId.systemDefault())
                .format(formatter)
        }
    }

    fun parseUtc(): Long? = runCatching {
        LocalDateTime.parse(input, formatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("定时设置") },
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
            Text("方案：$schemeName", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "输入本地时间，精确到毫秒。内部按 UTC epoch millis 存储，" +
                    "再经由校时后的时钟模型换算成单调时钟触发点。"
            )
            LabeledField("目标时刻 yyyy-MM-dd HH:mm:ss.SSS", input) { input = it }

            val utc = parseUtc()
            Text(
                if (utc == null) "时间格式无效" else "目标 UTC：$utc ms"
            )
            Text(
                "当前 UTC：${vm.nowUtcMillis()} ms · " +
                    if (vm.isSynced()) "已校时" else "未校时（绝对精度不可保证）"
            )
            Text("校时状态：${sync.message}")
            if (settings.syncEnabled && !vm.isSynced()) {
                Text("已开启校时但未成功，请到校准页先校时，否则无法武装。")
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val target = parseUtc()
                    if (target == null) {
                        vm.notify("时间格式无效，请用 yyyy-MM-dd HH:mm:ss.SSS")
                    } else {
                        vm.schemes.value.firstOrNull { it.id == schemeId }?.let {
                            vm.arm(it, target)
                        }
                    }
                }) { Text("武装定时") }
                Button(onClick = { vm.cancelArm(schemeId) }) { Text("取消") }
                TextButton(onClick = { nav.navigate("calibration") }) { Text("去校时") }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "武装后请保持 App 在前台、屏幕常亮。" +
                    "切后台、息屏或进程被杀时毫秒级触发立即失效，" +
                    "AlarmManager 只会在提前 1 分钟发一条提醒。"
            )
            Text("当前状态：${engineState.message.ifBlank { "空闲" }}")
        }
    }
}

@Composable
internal fun LabeledField(
    label: String,
    value: String,
    decimal: Boolean = false,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number
        ),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    LabeledField(label, value, false, onChange)
}
