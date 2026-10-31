package dev.chroot.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A gated write action, described in plain language before confirmation. */
data class RiskRequest(
    val op: String,
    val title: String,
    val args: List<String>,
    val reason: String,
    val impact: String,
    val destructive: Boolean = false,
    val sections: List<Pair<String, String>> = emptyList(),
)

private val tabs = listOf("device", "system", "software", "settings")

@Composable
fun ConsoleRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf("system") }
    var status by remember { mutableStateOf(ConsoleStatus()) }
    var runtime by remember { mutableStateOf(RuntimeInfo()) }
    var doctor by remember { mutableStateOf<DoctorReport?>(null) }
    var snaps by remember { mutableStateOf<List<SnapshotEntry>>(emptyList()) }
    var paths by remember { mutableStateOf<List<PathEntry>>(emptyList()) }
    var device by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var logs by remember { mutableStateOf("") }
    var logKind by remember { mutableStateOf("all") }
    var loading by remember { mutableStateOf(true) }
    var explain by remember { mutableStateOf<RiskRequest?>(null) }
    var task by remember { mutableStateOf<ConsoleTask?>(null) }
    var taskOpen by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var nextTaskId by remember { mutableIntStateOf(1) }
    var banner by remember { mutableStateOf("") }
    var pinTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var appInfo by remember { mutableStateOf(AppInfo()) }
    var appPlugins by remember { mutableStateOf<List<AppPlugin>>(emptyList()) }
    var ttyd by remember { mutableStateOf(TtydInfo()) }
    var packages by remember { mutableStateOf(AppPackages()) }

    // 同一时刻只允许一个写任务：其余会写盘的按钮全部置灰。
    val busy = task?.state == "running"

    fun route(value: String) {
        if (stack.lastOrNull() != value) stack.add(value)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.size - 1)
    }

    suspend fun refresh() {
        val loaded = withContext(Dispatchers.IO) { ConsoleRepo.status() to ConsoleRepo.runtime() }
        status = loaded.first
        runtime = loaded.second
        loading = false
        if (status.state == "running") {
            val app = withContext(Dispatchers.IO) {
                AppRefresh(
                    info = ConsoleRepo.appInfo(),
                    plugins = ConsoleRepo.appPlugins(),
                    ttyd = ConsoleRepo.ttyd(),
                    packages = ConsoleRepo.appPackages(),
                )
            }
            appInfo = app.info
            appPlugins = app.plugins
            ttyd = app.ttyd
            packages = app.packages
        }
    }

    suspend fun loadSnapshots() {
        snaps = withContext(Dispatchers.IO) { ConsoleRepo.snapshots() }
    }

    suspend fun loadPaths() {
        paths = withContext(Dispatchers.IO) { ConsoleRepo.paths() }
    }

    fun diagnostics(): String = diagnosticsText(status, doctor, paths, device)

    // §8.3：退到后台就停止拉取，回来立刻刷一次，避免锁屏时还在跑 su。
    var resumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (resumed) {
                refresh()
                if (snaps.isEmpty()) loadSnapshots()
            }
            delay(if (!resumed) 2000 else if (busy) 1000 else 3000)
        }
    }
    LaunchedEffect(Unit) {
        device = withContext(Dispatchers.IO) { ConsoleRepo.device() }
        loadPaths()
    }
    LaunchedEffect(task?.id, task?.state) {
        while (task?.state == "running") {
            now = System.currentTimeMillis()
            delay(1000)
        }
        now = System.currentTimeMillis()
    }

    /**
     * 写操作在后台跑，输出边跑边进任务条；用户离开这一页也不会中断或丢结果。
     */
    fun startTask(title: String, work: suspend ((String) -> Unit) -> ConsoleSupport.Result) {
        val id = nextTaskId++
        val started = System.currentTimeMillis()
        task = ConsoleTask(id = id, title = title, startedAt = started)
        taskOpen = true
        scope.launch {
            val channel = Channel<String>(Channel.UNLIMITED)
            val sink = launch {
                for (line in channel) {
                    val current = task ?: continue
                    if (current.id == id) task = current.copy(lines = current.lines + line)
                }
            }
            val result = work { line -> channel.trySend(line) }
            channel.close()
            sink.join()
            val current = task
            if (current != null && current.id == id) {
                val merged = if (current.lines.isEmpty() && result.output.isNotBlank()) {
                    result.output.lines().filter { it.isNotBlank() }
                } else {
                    current.lines
                }
                task = current.copy(
                    lines = merged,
                    state = if (result.exitCode == 0) "ok" else "failed",
                    exitCode = result.exitCode,
                )
            }
            refresh()
            loadSnapshots()
        }
    }

    fun runRisk(request: RiskRequest) = startTask(request.title) { send ->
        ConsoleRepo.ctlRisky(request.op, request.args, request.reason, request.impact) { line -> send(line) }
    }

    /** 终端：一条命令一个后台任务，输出进任务条，不阻塞页面。 */
    fun runGuestCommand(command: String) {
        if (command.isBlank()) return
        startTask("命令: " + command.take(40)) { send ->
            ConsoleRepo.appExec(command) { line -> send(line) }
        }
    }

    fun copy(text: String, label: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        banner = "已复制到剪贴板：" + label
    }

    // DSH 是完整的 Web 应用，塞进 WebView 会丢掉浏览器能力（多标签、下载、密码管理器、
    // 长按选择、桌面模式）。这里只负责把地址交给系统浏览器。
    fun openDsh() {
        if (!ConsoleSupport.isLoopbackHttpUrl(status.url)) {
            banner = "当前没有可用的 DSH 地址，先启动 chroot。"
            return
        }
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(status.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(view) }
            .onFailure { banner = "没有可打开网页的浏览器：已复制地址，可手动粘贴。" ; copy(status.url, "DSH 地址") }
    }

    val current = stack.last()
    BackHandler(enabled = stack.size > 1 || explain != null) {
        if (explain != null) explain = null else back()
    }

    ConsoleBackdrop {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            ConsoleHeader(
                route = current,
                status = status,
                loading = loading,
                onBack = { back() },
            )
            task?.let { running ->
                TaskBar(running, now, onOpen = { taskOpen = true })
            }
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                when (current) {
                    "system" -> SystemPage(
                        status = status,
                        loading = loading,
                        doctor = doctor,
                        snaps = snaps,
                        onStart = { explain = riskStart() },
                        onStop = { explain = riskStop() },
                        onOpenDsh = ::openDsh,
                        onSnapshots = { route("snapshots") },
                        onRestore = { name -> explain = riskSnapshotRestore(name) },
                        onLogs = { route("logs") },
                        onDoctor = {
                            scope.launch {
                                doctor = withContext(Dispatchers.IO) { ConsoleRepo.doctor() }
                                route("doctor")
                            }
                        },
                        onCopyDiagnostics = { copy(diagnostics(), "诊断包") },
                        onAction = { request -> explain = request },
                        busy = busy,
                    )
                    "software" -> SoftwarePage(
                        status = status,
                        loading = loading,
                        runtime = runtime,
                        app = appInfo,
                        plugins = appPlugins,
                        ttyd = ttyd,
                        packages = packages,
                        onOpenDsh = ::openDsh,
                        onAction = { request -> explain = request },
                        onLogs = { route("logs") },
                        onTerminal = { route("terminal") },
                        onSwitch = { version -> explain = riskAppSwitch(version) },
                        onRollback = { explain = riskAppRollback(appInfo.previous) },
                        onRefresh = { scope.launch { refresh() } },
                        onInstall = { source, version, target, sha -> explain = riskAppInstall(source, version, target, sha) },
                        onPin = { name, current -> pinTarget = name to current },
                    onPlugin = { name, on ->
                        explain = RiskRequest(
                            op = "app plugin",
                            title = (if (on) "启用 " else "停用 ") + name,
                            args = listOf("app", "plugin", name, if (on) "on" else "off"),
                            reason = "操作者请求" + (if (on) "启用" else "停用") + "控制面插件 " + name,
                            impact = "改的是 /opt/dsh-control/dsh 下的 patch 文件；对 hooks 模式的影响在下次 start/restart 生效",
                        )
                    },
                    onTtyd = { enable -> explain = riskTtyd(enable) },
                        onPending = { what -> banner = what + " 还没实现；这一步先只记录在 docs/console-design.md。" },
                        busy = busy,
                    )
                    "terminal" -> TerminalScreen(
                        live = status.state == "running",
                        busy = busy,
                        onSubmit = ::runGuestCommand,
                        lastTask = task,
                    )
                    "device" -> DevicePage(
                        status = status,
                        loading = loading,
                        paths = paths,
                        device = device,
                        onLoad = { scope.launch { loadPaths() } },
                        onCopy = ::copy,
                    )
                    "settings" -> SettingsScreen(
                        status = status,
                        runtime = runtime,
                        onAction = { request -> explain = request },
                        onAbout = { route("about") },
                        onCopyDiagnostics = { copy(diagnostics(), "诊断包") },
                    )
                    "doctor" -> DoctorScreen(doctor) { onReload ->
                        scope.launch {
                            doctor = withContext(Dispatchers.IO) { ConsoleRepo.doctor() }
                            onReload()
                        }
                    }
                    "logs" -> LogsScreen(
                        logs = logs,
                        kind = logKind,
                        onKind = { kind ->
                            logKind = kind
                            scope.launch { logs = withContext(Dispatchers.IO) { ConsoleRepo.logs(kind) } }
                        },
                        onRefresh = { scope.launch { logs = withContext(Dispatchers.IO) { ConsoleRepo.logs(logKind) } } },
                        onCopy = { copy(logs, "日志") },
                    )
                    "snapshots" -> SnapshotsScreen(
                        snaps = snaps,
                        onLoad = { scope.launch { loadSnapshots() } },
                        onRestore = { name -> explain = riskSnapshotRestore(name) },
                        onDelete = { name -> explain = riskSnapshotDelete(name) },
                        busy = busy,
                        onCreate = {
                            explain = riskSnapshotCreate("snap-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()))
                        },
                    )
                    "about" -> AboutScreen(status, runtime)
                    else -> SystemPage(
                        status = status,
                        loading = loading,
                        doctor = doctor,
                        snaps = snaps,
                        onStart = { explain = riskStart() },
                        onStop = { explain = riskStop() },
                        onOpenDsh = ::openDsh,
                        onSnapshots = { route("snapshots") },
                        onRestore = { name -> explain = riskSnapshotRestore(name) },
                        onLogs = { route("logs") },
                        onDoctor = { route("doctor") },
                        onCopyDiagnostics = { copy(diagnostics(), "诊断包") },
                        onAction = { request -> explain = request },
                    )
                }
            }
            if (current in tabs) {
                ConsoleBottomBar(current) { route(it) }
            }
        }
    }

    explain?.let { request ->
        RiskExplainScreen(
            request = request,
            onBack = { explain = null },
            onConfirm = { confirmed ->
                explain = null
                runRisk(confirmed)
            },
        )
    }
    if (taskOpen) {
        task?.let { running ->
            TaskSheet(
                task = running,
                now = now,
                onDismiss = {
                    taskOpen = false
                    if (running.done) task = null
                },
                onCopy = { text -> copy(text, "任务输出") },
            )
        }
    }
    pinTarget?.let { target ->
        PinDialog(
            name = target.first,
            current = target.second,
            onDismiss = { pinTarget = null },
            onConfirm = { range ->
                pinTarget = null
                explain = RiskRequest(
                    op = "app pin",
                    title = "改依赖 " + target.first + " → " + range,
                    args = listOf("app", "pin", target.first, range),
                    reason = "操作者请求调整 " + target.first + " 的版本范围",
                    impact = "只改 /opt/dsh/current/package.json 并留备份；不装包，下次 pnpm install 生效",
                    destructive = false,
                )
            },
        )
    }
    if (banner.isNotBlank()) {
        BannerDialog(banner) { banner = "" }
    }
}

private fun riskStart(): RiskRequest = RiskRequest(
    op = "start",
    title = "启动 chroot",
    args = listOf("start", "--apply"),
    reason = "控制台启动 chroot",
    impact = "占用 loop 设备、挂载 rootfs 并启动 DSH 服务",
    sections = listOf(
        "是什么" to "挂载镜像为 rootfs，启动 nsinit 与 DSH 服务。",
        "什么时候用" to "开机后没有自动启动、或手动停止后需要重新进入时。",
        "会有什么后果" to "占用一个 loop 设备与本地端口；已运行的实例不会被重复启动。",
        "怎么恢复" to "随时停止；启动失败会留下 recovery 记录，可在本页恢复。",
    ),
)

private fun riskStop(): RiskRequest = RiskRequest(
    op = "stop",
    title = "停止 chroot",
    args = listOf("stop", "--apply"),
    reason = "控制台停止 chroot",
    impact = "结束 chroot 内所有进程并卸载 rootfs",
    destructive = true,
    sections = listOf(
        "是什么" to "停止 s6 服务、结束 namespace 内进程，然后卸载并释放 loop。",
        "什么时候用" to "需要更换镜像、排查占用、或长时间不用时。",
        "会有什么后果" to "chroot 内所有进程被结束；镜像与 state 保留。",
        "怎么恢复" to "重新启动即可；异常残留可用 recover 清理。",
    ),
)

private fun riskSnapshotCreate(name: String): RiskRequest = RiskRequest(
    op = "snapshot-create",
    title = "新建快照 " + name,
    args = listOf("snapshot", "create", name, "--apply"),
    reason = "控制台新建快照",
    impact = "复制当前镜像与 state，占用与镜像同等大小的空间",
    sections = listOf(
        "是什么" to "把当前镜像与 state 复制成快照 " + name + "，之后可以随时恢复。",
        "什么时候用" to "升级 DSH、改动插件、调系统文件之前。",
        "会有什么后果" to "占用一份镜像大小的空间；不会改动正在运行的实例。",
        "怎么恢复" to "在快照页随时恢复或删除。",
    ),
)

private fun riskSnapshotDelete(name: String): RiskRequest = RiskRequest(
    op = "snapshot-delete",
    title = "删除快照 " + name,
    args = listOf("snapshot", "delete", name, "--apply"),
    reason = "控制台删除快照",
    impact = "删除该快照的镜像、state 与 manifest；不可恢复",
    sections = listOf(
        "是什么" to "删掉快照 " + name + " 的三份文件（镜像、state、manifest）。",
        "什么时候用" to "占空间了，或这个回滚点已经不需要。",
        "会有什么后果" to "这个快照不能再用；last-good 指向的快照不能删除。",
        "怎么恢复" to "只能重新拍一个快照，旧内容无法找回。",
    ),
)

private fun riskSnapshotRestore(name: String): RiskRequest = RiskRequest(
    op = "snapshot-restore",
    title = "恢复快照 " + name,
    args = listOf("snapshot", "restore", name, "--apply"),
    reason = "控制台恢复快照",
    impact = "用快照覆盖当前镜像与 state，工作文件可能被替换",
    destructive = true,
    sections = listOf(
        "是什么" to "把当前镜像和 state 替换成快照 " + name + " 的内容。",
        "什么时候用" to "升级失败、误改系统文件、需要回到已知良好状态时。",
        "会有什么后果" to "当前镜像与 state 被覆盖；当前工作文件可能被旧版本替换。",
        "怎么恢复" to "恢复前会自动保留上一份镜像（rootfs-pre-restore-*），可再用它回滚。",
    ),
)

@Composable
private fun ConsoleHeader(
    route: String,
    status: ConsoleStatus,
    loading: Boolean,
    onBack: () -> Unit,
) {
    val palette = consolePalette()
    val title = when (route) {
        "device" -> "设备"
        "system" -> "系统"
        "software" -> "软件"
        "settings" -> "设置"
        "doctor" -> "体检"
        "logs" -> "日志"
        "snapshots" -> "快照"
        "about" -> "关于"
        else -> route
    }
    val subtitle = when (route) {
        "system" -> "起停 · 物资 · 地盘 · 体检"
        "software" -> "终端 · DSH · 依赖 · 插件"
        "device" -> "机器事实与路径"
        "settings" -> "自启 · 确认模式 · 诊断"
        else -> ""
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (route !in tabs) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50))
                    .background(palette.text.copy(alpha = 0.06f)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = palette.text, fontSize = ConsoleType.title) }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.text, fontSize = ConsoleType.display, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = palette.faint, fontSize = ConsoleType.hint)
            }
        }
        if (route == "system") {
            StatusPill(ConsoleSupport.stateLabel(status.state), stateTint(status.state, palette))
        }
        if (loading) {
            Spacer(Modifier.width(6.dp))
            Text("…", color = palette.faint, fontSize = ConsoleType.label)
        }
    }
}

@Composable
private fun ConsoleBottomBar(selected: String, onSelect: (String) -> Unit) {
    val palette = consolePalette()
    val items = listOf(
        Triple("device", "设备", "storage"),
        Triple("system", "系统", "chroot"),
        Triple("software", "软件", "dsh"),
        Triple("settings", "设置", "gear"),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(ConsoleShape.card))
            .background(palette.card)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (key, label, icon) ->
            val active = key == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    // §10：点击区 ≥ 48dp、整条 ≥ 56dp，字体放大到 1.3× 也不截断。
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(ConsoleShape.button))
                    .background(if (active) palette.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(key) }
                    .semantics {
                        contentDescription = label
                        role = Role.Tab
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                LineIcon(icon, if (active) palette.accent else palette.faint, 22)
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    color = if (active) palette.accent else palette.faint,
                    fontSize = ConsoleType.hint,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun BannerDialog(text: String, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        GlassCard {
            Column(Modifier.padding(20.dp)) {
                Text(text, color = consolePalette().text, fontSize = ConsoleType.label)
                Spacer(Modifier.height(10.dp))
                PrimaryButton("好", onDismiss)
            }
        }
    }
}

private fun riskAppSwitch(version: String): RiskRequest = RiskRequest(
    op = "app",
    title = "切换 DSH 到 " + version,
    args = listOf("app", "switch", version, "--apply"),
    reason = "控制台切换 DSH 版本",
    impact = "改写 guest 内 /opt/dsh/current，影响之后启动的 DSH",
    destructive = true,
    sections = listOf(
        "是什么" to "把 /opt/dsh/current 指向已安装的 " + version + "。",
        "什么时候用" to "升级或降级 DSH 本体，需要停在指定版本时。",
        "会有什么后果" to "下一次启动的 DSH 换成该版本；旧版本保留，可回滚。",
        "怎么恢复" to "用「回滚上一版」切回当前版本。",
    ),
)

private data class AppRefresh(
    val info: AppInfo = AppInfo(),
    val plugins: List<AppPlugin> = emptyList(),
    val ttyd: TtydInfo = TtydInfo(),
    val packages: AppPackages = AppPackages(),
)

private fun riskTtyd(enable: Boolean): RiskRequest = RiskRequest(
    op = "ttyd",
    title = if (enable) "启用 ttyd" else "禁用 ttyd",
    args = listOf("ttyd", if (enable) "on" else "off", "--apply"),
    reason = if (enable) "控制台启用 ttyd" else "控制台禁用 ttyd",
    impact = if (enable) "在 127.0.0.1:7681 暴露一个需要密码的 Web 终端" else "停止 ttyd 服务并保留凭据文件",
    destructive = true,
    sections = listOf(
        "是什么" to (if (enable) "启动 s6 里的 ttyd 服务，只监听 127.0.0.1，密码从 state 里读取。" else "停掉 ttyd 服务，并把 down 标记写回控制目录。"),
        "什么时候用" to (if (enable) "想在电脑上开一个浏览器里的 guest shell 时。" else "不再需要 Web 终端，或怀疑凭据泄露时。"),
        "会有什么后果" to (if (enable) "本机任意能访问 7681 的进程都能看到登录页；密码不显示在控制台。" else "Web 终端立刻断开；凭据文件保留，重新启用还是同一个密码。"),
        "怎么恢复" to (if (enable) "随时禁用。" else "重新启用即可。"),
    ),
)

private fun riskAppInstall(source: String, version: String, target: String, sha256: String): RiskRequest {
    val args = mutableListOf("app", "install", "--from", source, "--version", version)
    when (source) {
        "tarball" -> {
            args += "--url"
            args += target
        }
        "local" -> {
            args += "--file"
            args += target
        }
    }
    if (sha256.isNotBlank()) {
        args += "--sha256"
        args += sha256
    }
    args += "--apply"
    return RiskRequest(
        op = "app",
        title = "安装 DSH " + version,
        args = args,
        reason = "控制台安装 DSH 版本",
        impact = "往 /data/adb 下的 versions/ 写入新版本，不动正在使用的版本",
        destructive = true,
        sections = listOf(
            "是什么" to "把 " + version + " 取到 versions/" + version + "，校验通过后才发布。",
            "什么时候用" to "想升级或试用新版本时。安装不会切换版本。",
            "会有什么后果" to "占用一份安装体积；current 不变，正在用的版本不受影响。",
            "怎么恢复" to "装完不满意，删掉 versions/" + version + " 即可；切换过的用「回滚上一版」。",
        ),
    )
}

private fun riskAppRollback(previous: String): RiskRequest = RiskRequest(
    op = "app",
    title = "回滚 DSH 到 " + previous.ifBlank { "上一版" },
    args = listOf("app", "rollback", "--apply"),
    reason = "控制台回滚 DSH 版本",
    impact = "交换 current 与 previous，影响之后启动的 DSH",
    destructive = true,
    sections = listOf(
        "是什么" to "把 current 与 previous 交换，回到上一个版本。",
        "什么时候用" to "刚切换的版本有问题，或想回到之前的版本时。",
        "会有什么后果" to "下一次启动的 DSH 换成 previous 记录的版本。",
        "怎么恢复" to "再点一次回滚即可换回。",
    ),
)
