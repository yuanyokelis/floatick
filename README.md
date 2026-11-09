# Floatick · Android 定时点击器

自用 + 开源的 Android 自动点击工具。支持「单点重复」与「多步骤序列（点击 / 长按 / 滑动）」两类方案，一次性指定时刻定时，多方案保存与 JSON 备份，悬浮控制台与三通道急停。

**用途边界**：仅用于自己设备上合法的重复性操作辅助。禁止用于金融、支付、电商抢购、验证码、游戏作弊等自动化场景。项目不 root、不 hook、不隐藏无障碍服务、不规避任何应用风控；内置受保护应用名单仅作防误触。

---

## 1. 能承诺什么 / 不能承诺什么

| 项 | 结论 |
|---|---|
| 配置粒度 | 目标时刻可精确到毫秒（`yyyy-MM-dd HH:mm:ss.SSS`），间隔 1ms 起 |
| 时间基准 | 开启校时后对齐**外部 NTP / UTC**；关闭校时时退回本机墙上时钟 |
| 触发精度 | 前台 + 屏幕常亮下，GO 信号偏差 P95 ≤ ±30ms |
| 端到端精度 | 约 **±50~100ms** = 校时误差（±10~50ms，取决于网络 RTT）+ 触发抖动（±30ms） |
| 不承诺 | ±1ms 硬实时、后台/息屏唤醒、绝对 UTC 零偏差、进程被杀后仍精确 |
| 后台运行 | 前台服务 + 常驻通知防止执行期间被回收；Android 无法 100% 保证不被 ROM 强杀 |

Android 不是实时系统。「毫秒级可配置」「毫秒级显示」「毫秒级精确触发」是三件不同的事：状态栏只显示到分钟是系统 UI 的格式化选择；系统内部有 `currentTimeMillis()`（毫秒）与 `elapsedRealtimeNanos()`（纳秒单调时钟）。本项目把毫秒级的意义定位在**触发时刻可设定、可重复**，不是「看得见毫秒」。

---

## 2. 架构（v1.3）

```
表现层     Compose 界面 / 悬浮控制台 / 取点器（1px 微调）
调度执行层 SchemeRepository · Scheduler(兜底提醒) · ClickEngine · RunGuard
校时层     SntpClient · ClockModel(offset/drift) · ClockAnchor · PrecisionTimer · TriggerCalibration
系统能力层 AccessibilityService(dispatchGesture) · 前台服务 · AlarmManager · WindowManager
数据层     Room(方案/步骤/定时/日志) · DataStore(设置) · JSON 备份
```

触发链路：目标 UTC 时刻 → `ClockAnchor` 换算成 `elapsedRealtime()` 锚点 → `PrecisionTimer` 两阶段等待（Handler 粗等待 + 专用线程细等待）→ 发出 GO → 无障碍服务派发手势 → 写运行日志。

- **不用 Choreographer**：帧回调被 VSYNC 锁在 16.7ms / 8.3ms，比独立线程轮询更差。
- **不用精确闹钟权限**：`AlarmManager` 只提前 1 分钟发非唤醒提醒，不承担触发。
- **不用 root / 隐藏 API**：SNTP 自行实现 48 字节 UDP 报文，未使用 `android.net.SntpClient`。

---

## 3. 构建

本机（生成此工程的环境）没有 JDK / Gradle / Android SDK，因此**全部编译与验证都通过 GitHub Actions 完成**，每次改动的结论以 Actions 结果为准（含模拟器冒烟启动测试）。本地构建需要：

- JDK 17
- Android Studio（Koala 或更新）+ Android SDK Platform 34 + Build Tools
- Gradle 8.7（`gradle/wrapper/gradle-wrapper.properties` 已指定）

```bash
# 方式 A：Android Studio 打开根目录，Sync 后直接 Run
# 方式 B：命令行（仓库已附带 wrapper jar 与 gradlew / gradlew.bat）
./gradlew :app:assembleDebug     # Windows: gradlew.bat :app:assembleDebug
```

`gradle/wrapper/gradle-wrapper.jar` 取自 Gradle 8.7 官方发行版；`gradlew` / `gradlew.bat` 是精简启动脚本（官方脚本的等价最小实现）。产物在 `app/build/outputs/apk/debug/`。

### GitHub Actions 自动构建

`.github/workflows/android.yml` 在 push / PR / 手动触发时运行：JDK 17 → 安装 Android SDK 34 → `./gradlew :app:assembleDebug --no-daemon` → 上传 `floatick-debug-apk` 产物（保留 30 天，可直接下载安装）。构建失败时另上传 `build-diagnostics`（报告与日志）。

**CI 已验证通过**：首次构建暴露出 `DataStore.MutablePreferences` 类型写错（13 个编译错误），已修复；此后每次 `assembleDebug` 均通过并产出 APK，`smoke.yml` 的模拟器启动测试也稳定通过（`FATAL EXCEPTION` / `ANR` / `GrantPermissionsActivity` 抢占前台均为 0 次）。

`local.properties` 不入库，CI 通过 runner 预置的 `ANDROID_HOME` 找到 SDK；`.gitattributes` 保证 `gradlew` 在 Linux runner 上是 LF 且可执行、`gradle-wrapper.jar` 按二进制处理。

### 安装与覆盖安装

CI 产出的 debug APK 使用仓库内固定的 `app/ci-debug.keystore` 签名（PKCS12，alias `androiddebugkey`，密码 `android`）。**同一仓库此后每次构建的签名都一致，可以直接覆盖安装**，不会出现「更新包与已安装应用的签名不一致」。

之所以要显式固定：GitHub Actions 每次都是全新虚拟机，Android 默认的 `debug.keystore` 会在每台机器上随机生成，早期版本因此每个包的签名都不同。这个密钥**仅用于调试包**，按设计不是秘密（开源项目提交 debug keystore 是常见做法），**不能用于正式发布**。

> **从 2026-11-09 之前的旧包升级，必须先卸载一次**（签名不同，Android 会拒绝覆盖）：
> 1. 打开 App →「设置」→ 配置备份 → **导出到文本框**，长按复制 JSON 保存到别处；
> 2. 卸载旧版，安装新 APK；
> 3. 打开 App →「设置」→ 把 JSON 粘回文本框 → **从文本框导入**；
> 4. 卸载会清除无障碍授权，按第 4 节重新开启（含 Android 13+ 的「允许受限设置」）。
>
> 完成这一次之后，后续版本就不用再卸载了。

### 打开就消失 / 启动闪退

早期版本在 `MainActivity.onCreate` 里无条件请求 `POST_NOTIFICATIONS`，系统权限对话框会在界面刚渲染时把 Activity 压到后台；部分 ROM 上表现为「页面闪一下就没了，但后台还显示应用在运行」。现已改为**不在启动时自动弹权限框**，通知权限请在「权限」页点「去设置」主动开启（前台服务没有该权限也能运行，只是通知不显示）。

### 启动闪退时怎么拿堆栈

App 在 `Application.attachBaseContext` 就装了崩溃捕获：未捕获异常会写入 `filesDir/last_crash.log`、**复制到系统剪贴板**，并推送一条「Floatick 启动失败」通知；下次打开 App 也会把堆栈弹成对话框。把通知或剪贴板内容贴回来即可定位。悬浮窗 `addView` 失败不会让 App 闪退，错误会显示在首页状态卡。

CI 另有 `smoke.yml`：在 Android 模拟器里安装并启动 debug APK，把完整 logcat 上传为 `emulator-logcat` artifact，可在没有真机 logcat 的情况下复现启动崩溃。

### 推送到 GitHub

本地已完成 `git init -b main` 与首次提交，`.workbuddy/` 已加入 `.gitignore`。创建远程仓库需要你的 GitHub 授权，二选一：

```bash
# 方式 1：已安装并登录 GitHub CLI，一键建仓 + 推送
gh repo create floatick --public --source=. --remote=origin --push

# 方式 2：先在 GitHub 网页新建空仓库（不要初始化 README），然后
git remote add origin https://github.com/<你的用户名>/floatick.git
git push -u origin main
```

推送后 `main` 分支会自动触发 Actions 首次构建；到 Actions 页下载 `floatick-debug-apk` 即可安装。

**依赖**：Kotlin 2.0 + Compose(BOM 2024.06) + Room 2.6.1 + Hilt 2.51.1 + DataStore，注解处理统一用 `kapt`（避免 KSP 与 Kotlin 2.0 的版本错配）。

---

## 4. 首次运行配置

1. 侧载安装 APK。Android 13+ 对侧载应用启用「受限设置」：若无障碍开关是灰的，先到 **设置 → 应用 → Floatick → 右上 ⋮ → 允许受限设置**（需 PIN/指纹），再回无障碍页开启。重装或换签名后需重做一次。
2. 打开 App 的「权限」页，逐项确认：无障碍服务、悬浮窗、通知。
3. 授权是**系统级持久状态**：每次打开 App、重启手机都不用重开。只有卸载重装、换签名、系统升级、手动关闭或个别 ROM 回收权限时才需要重新开启。App 每次冷启动实时自检，掉授权时定时任务自动暂停。
4. 在「校准」页验证时间基准；如开启校时，先在「设置」页填好可达的 NTP 服务器并打开开关。

**银行 / 金融类 App 会限制**：它们通过 `getEnabledAccessibilityServiceList()`、`FLAG_SECURE`、overlay 检测与行为风控识别自动化，可能弹窗警告、禁用转账或中断会话。本项目不提供任何绕过方式；需要操作这类 App 时请临时关闭无障碍服务，用「一键全局停用」即可。

### 「立即」和「武装」不是一回事

| 操作 | 行为 |
|---|---|
| 方案卡片「立即执行」/ 悬浮框「立即」 | **立刻**注入手势，落在**当时的前台窗口**上。在 Floatick 里点它就点 Floatick 自己 |
| 悬浮框「武装」/ 定时页「武装定时」 | 等待已保存的目标时刻，到点才注入一次 |

所以要点其它 App（例如某个领券页面），正确流程是：**设一个未来几秒的时刻 → 武装 → 立刻切到目标 App → 保持亮屏等待触发**。悬浮控制台是跨 App 的，切走后仍在，可用它急停。

### 点击没反应时按顺序排查

1. **先看首页状态卡**：必须显示「无障碍 已开 / 服务 已连接」。悬浮框上出现「无障碍服务未连接」时，引擎会直接拒绝执行，一次手势都不会发。按「权限」页的四步开启（侧载需先「允许受限设置」；已开却未连接就关掉再打开一次）。
2. **确认前台窗口**：用「立即」时必须已经切到目标页面；否则用上面的定时流程。
3. **确认取点器已关闭**：点「确定」后取点器会自动消失；若它仍浮在屏幕上，手势会打到这个 overlay 而不是下面的应用。
4. **用「诊断」按钮定位**：点完 3 秒后在当前前台窗口发一次手势，并回报 `dispatchGesture` 返回值、像素坐标、屏幕尺寸与前台包名。`accepted=true` 说明手势已被系统接受但目标没响应；`false` 说明注入本身失败。
5. **加大按下时长**：`按下时长 ms` 默认 80ms（接近人手），最小 50ms。20~30ms 这种极短按下会被部分自定义 View / WebView 当成无效触摸。不需要模拟「手指面积」——`dispatchGesture` 注入的 MotionEvent 自带默认 pressure/size，普通 App 都能响应。
6. **目标 App 自身防护**：银行、支付及部分游戏会检测无障碍注入的事件来源并忽略，这属于应用侧防护，本项目不做绕过。

---

## 5. 校时与隐私

- `INTERNET` 是安装期普通权限，**默认关闭**指的是应用逻辑：开关未打开时代码不发起任何网络请求。
- 开启后仅向**用户填写的 NTP 服务器**发送 UDP 123 报文，不上传任何数据、不调用第三方 HTTP 时间接口。
- 采样 8 次（可配 4~32），取最小 RTT 样本 + 中位数离群保护，估计 `offset` 与本机晶振 `drift`；结果有效期 10 分钟，过期后 UI 显示「未校时」。
- 校时失败 / UDP 123 被运营商或企业 WiFi 拦截时，App 明确提示，且**开启校时但未成功时拒绝武装**，不会静默假装精确。
- 目标时刻存 **UTC epoch millis**，显示按本地时区；倒计时全程用单调时钟，用户改系统时间或 NTP 跳变都不会污染触发点。

---

## 6. P0：真机验证（必须先做）

源码里的精度是设计目标，**必须用目标机型实测后才能确认**：

> 「校准」页的**复制校准报告**按钮会把机型、系统版本、`offset`/`RTT`、空跑 P50/P95、`advanceMs`、无障碍连接状态拼成一段文本复制到剪贴板，实测完直接贴出来即可，不用手抄。

1. 「校准」页点「空跑 20 次测本机触发抖动」，记录 P50 / P95。若 P95 > 30ms，先调 `FINE_SPIN_WINDOW_MS`、检查是否绑到了主线程、或降低后台负载。
2. 同一 WiFi 下多次校时，记录 offset 与 RTT；移动网络下再测一次，确认端到端偏差落在 ±100ms 内。
3. 用「立即执行」验证手势：按下时长保持默认 80ms（下限 50ms，更短会被部分 View 当成无效触摸或直接被系统合并），步骤间隔建议 ≥50ms。
4. 验证急停：悬浮窗 / 通知栏「停止」/ 页面「停止」都能在 500ms 内中断。
5. 验证护栏：切到受保护应用、撤销无障碍授权、切后台息屏，分别确认任务停止或明确降级提示。

---

## 7. 已知限制

- 精度只在 **App 前台 + 屏幕常亮**成立（`FLAG_KEEP_SCREEN_ON`，不需要 `WAKE_LOCK` 权限）。切后台、息屏、进程被杀后毫秒级立即失效，只保留分钟级提醒。
- 坐标按屏幕比例存储；取点与手势派发统一使用 `Resources.getSystem().displayMetrics`，但多显示器、折叠屏内外屏切换、部分分屏场景仍可能漂移。**Y 接近 1.0 时通常落在系统导航栏/手势区，点击不会传给应用**，建议取 0.85~0.92。
- 取点器是**两个独立 window**：一个 56dp 的十字（可拖到屏幕任意角落，含右下角）+ 一条固定在屏幕顶部的工具条（X/Y、确定、关闭、1px 微调）。离开编辑页会自动关闭，不会跨页面挡住内容。
- 悬浮控制台靠**拖动空白处或标题**移动，按钮仍可点击；`onResume` 与状态更新都会自动重建控制台，避免 ROM 回收 overlay 后「悬浮框不见了、错误提示也看不到」。引擎的错误消息会同步弹成对话框。
- 输入数值时允许保留 `0.` 这类中间态，只有解析成功才写回模型。
- 从悬浮窗在后台点击「开始」时，Android 12+ 可能拒绝启动前台服务；此时不会崩溃，任务仍执行但会提示回到 App 前台，否则进程可能被回收。
- `dispatchGesture` 单次有 10~40ms 派发开销，且极短手势会被系统合并；步骤间隔用协程 `delay`，抖动大于 GO 信号本身。
- 重启后不做后台自动拉起（Android 12+ 限制从 `BOOT_COMPLETED` 启动前台服务），仅提示用户回来重新武装——这与「亮屏 + 人手动打开」的设计前提一致。
- 受保护名单只做包名匹配，是尽力而为的防误触护栏，不能替代用户判断。

---

## 8. 目录

```
app/src/main/java/com/example/floatick/
  core/       领域模型与常量上限
  data/       Room 实体/DAO、Repository、DataStore、JSON 备份
  time/       SNTP、ClockModel、ClockAnchor、PrecisionTimer、校准与空跑
  system/     无障碍服务、前台服务、悬浮控制台、权限自检、通知、接收器
  engine/     ClickEngine、RunGuard
  ui/         Compose 界面、导航、ViewModel
  di/         Hilt 模块
```

许可证：MIT（见 `LICENSE`）。

---

## 9. 版本记录

| 版本 | 变更 |
|---|---|
| **0.2.0** | 修复「打开就闪一下消失」：移除启动时自动请求通知权限，改由「权限」页按需申请；引擎错误从悬浮框小字提升为 App 内对话框 + 首页状态卡；悬浮按钮改为「立即 / 武装 / 停止 / 取点」以免误解；取点器拆成可拖十字 + 固定顶栏（可到屏幕边缘）；`按下时长` 默认 80ms、下限 50ms；新增「延迟 5 秒」方案便于真机验证、`诊断` 按钮（回报 `dispatchGesture` 结果 / 像素坐标 / 前台包名）、权限页「服务连接」状态；CI 改用仓库内固定调试签名，可覆盖安装；设置页显示版本号；新增模拟器冒烟启动测试。 |
| 0.1.0 | 首个可构建版本：四层架构、Room/DataStore、SNTP 校时、两阶段精确定时、手势注入、悬浮控制台、JSON 备份。 |

> 版本号显示在「设置」页最底部；`versionCode` 每次发版递增，便于确认手机上装的是哪一版。
