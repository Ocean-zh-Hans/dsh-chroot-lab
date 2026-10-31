package dev.chroot.console

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    }

    suspend fun loadSnapshots() {
        snaps = withContext(Dispatchers.IO) { ConsoleRepo.snapshots() }
    }

    suspend fun loadPaths() {
        paths = withContext(Dispatchers.IO) { ConsoleRepo.paths() }
    }

    fun diagnostics(): String = diagnosticsText(status, doctor, paths, device)

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            if (snaps.isEmpty()) loadSnapshots()
            delay(5000)
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
    fun runRisk(request: RiskRequest) {
        val id = nextTaskId++
        val started = System.currentTimeMillis()
        task = ConsoleTask(id = id, title = request.title, startedAt = started)
        taskOpen = true
        scope.launch {
            val channel = Channel<String>(Channel.UNLIMITED)
            val sink = launch {
                for (line in channel) {
                    val current = task ?: continue
                    if (current.id == id) task = current.copy(lines = current.lines + line)
                }
            }
            val result = withContext(Dispatchers.IO) {
                ConsoleRepo.ctlRisky(request.op, request.args, request.reason, request.impact) { line ->
                    channel.trySend(line)
                }
            }
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

    fun openDsh() {
        if (!ConsoleSupport.isLoopbackHttpUrl(status.url)) {
            banner = "当前没有可用的 DSH 地址，先启动 chroot。"
            return
        }
        context.startActivity(Intent(context, WebActivity::class.java).putExtra("url", status.url))
    }

    fun copy(text: String, label: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(label, text))
        banner = "已复制到剪贴板：" + label
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
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                when (current) {
                    "system" -> SystemPage(
                        status = status,
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
                    )
                    "software" -> SoftwarePage(
                        status = status,
                        runtime = runtime,
                        onOpenDsh = ::openDsh,
                        onAction = { request -> explain = request },
                        onLogs = { route("logs") },
                        onPending = { what -> banner = what + " 还没实现；这一步先只记录在 docs/console-design.md。" },
                    )
                    "device" -> DevicePage(
                        status = status,
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
                        onCreate = {
                            explain = riskSnapshotCreate("snap-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()))
                        },
                    )
                    "about" -> AboutScreen(status, runtime)
                    else -> SystemPage(
                        status = status,
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
            task?.let { running ->
                TaskBar(running, now, onOpen = { taskOpen = true })
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
            ) { Text("‹", color = palette.text, fontSize = 22.sp) }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = palette.faint, fontSize = 11.sp)
            }
        }
        if (route == "system") {
            StatusPill(ConsoleSupport.stateLabel(status.state), stateTint(status.state, palette))
        }
        if (loading) {
            Spacer(Modifier.width(6.dp))
            Text("…", color = palette.faint, fontSize = 14.sp)
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
            .clip(RoundedCornerShape(26.dp))
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
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (active) palette.accent.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LineIcon(icon, if (active) palette.accent else palette.faint, 22)
                Spacer(Modifier.height(2.dp))
                Text(
                    label,
                    color = if (active) palette.accent else palette.faint,
                    fontSize = 11.sp,
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
                Text(text, color = consolePalette().text, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                PrimaryButton("好", onDismiss)
            }
        }
    }
}
