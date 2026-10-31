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

@Composable
fun ConsoleRoot() {
    val palette = consolePalette()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf("chroot") }
    var status by remember { mutableStateOf(ConsoleStatus()) }
    var runtime by remember { mutableStateOf(RuntimeInfo()) }
    var doctor by remember { mutableStateOf<DoctorReport?>(null) }
    var snaps by remember { mutableStateOf<List<SnapshotEntry>>(emptyList()) }
    var paths by remember { mutableStateOf<List<PathEntry>>(emptyList()) }
    var logs by remember { mutableStateOf("") }
    var logKind by remember { mutableStateOf("all") }
    var loading by remember { mutableStateOf(true) }
    var explain by remember { mutableStateOf<RiskRequest?>(null) }
    var output by remember { mutableStateOf<Pair<String, String>?>(null) }
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

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(5000)
        }
    }

    fun runRisk(request: RiskRequest) {
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) {
                ConsoleRepo.ctlRisky(request.op, request.args, request.reason, request.impact)
            }
            loading = false
            output = request.title to result.output.ifBlank { "完成（退出码 " + result.exitCode + "）" }
            refresh()
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
                onMore = { route("more") },
            )
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                when (current) {
                    "chroot" -> ChrootScreen(
                        status = status,
                        doctor = doctor,
                        onOpenDsh = ::openDsh,
                        onHealth = {
                            scope.launch {
                                doctor = withContext(Dispatchers.IO) { ConsoleRepo.doctor() }
                                route("doctor")
                            }
                        },
                        onAction = { request -> explain = request },
                        onLogs = { route("logs") },
                    )
                    "dsh" -> DshScreen(
                        status = status,
                        runtime = runtime,
                        onOpenDsh = ::openDsh,
                        onCopy = { copy(status.url, "DSH 地址") },
                        onAction = { request -> explain = request },
                        onCapability = { route("settings") },
                    )
                    "storage" -> StorageScreen(
                        status = status,
                        paths = paths,
                        onLoad = {
                            scope.launch { paths = withContext(Dispatchers.IO) { ConsoleRepo.paths() } }
                        },
                        onOpenPath = { entry -> copy(entry.path, entry.name) },
                    )
                    "more" -> MoreScreen(
                        onOpen = { route(it) },
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
                        onLoad = { scope.launch { snaps = withContext(Dispatchers.IO) { ConsoleRepo.snapshots() } } },
                        onRestore = { name ->
                            explain = riskSnapshotRestore(name)
                        },
                    )
                    "settings" -> SettingsScreen(status, runtime) { request -> explain = request }
                    "about" -> AboutScreen(status, runtime)
                    else -> ChrootScreen(status, doctor, ::openDsh, {}, { request -> explain = request }, { route("logs") })
                }
            }
            if (current == "chroot" || current == "dsh" || current == "storage") {
                ConsoleBottomBar(current) { route(it) }
            }
        }
    }

    explain?.let { request ->
        RiskExplainScreen(
            request = request,
            onBack = { explain = null },
            onConfirm = {
                val request2 = it
                explain = null
                runRisk(request2)
            },
        )
    }
    output?.let { pair ->
        OutputDialog(pair.first, pair.second) { output = null }
    }
    if (banner.isNotBlank()) {
        BannerDialog(banner) { banner = "" }
    }
}

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
    onMore: () -> Unit,
) {
    val palette = consolePalette()
    val title = when (route) {
        "chroot" -> "Chroot"
        "dsh" -> "DSH"
        "storage" -> "存储"
        "more" -> "更多"
        "doctor" -> "系统体检"
        "logs" -> "日志"
        "snapshots" -> "快照"
        "settings" -> "设置与能力"
        "about" -> "关于"
        else -> route
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (route != "chroot" && route != "dsh" && route != "storage") {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50))
                    .background(palette.text.copy(alpha = 0.06f)).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("‹", color = palette.text, fontSize = 22.sp) }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = palette.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        }
        if (route == "chroot") {
            StatusPill(ConsoleSupport.stateLabel(status.state), stateTint(status.state, palette))
        }
        if (route == "chroot" || route == "dsh" || route == "storage") {
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(50))
                    .background(palette.text.copy(alpha = 0.06f)).clickable(onClick = onMore),
                contentAlignment = Alignment.Center,
            ) { Text("⋯", color = palette.text, fontSize = 18.sp) }
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
        Triple("chroot", "Chroot", "chroot"),
        Triple("dsh", "DSH", "dsh"),
        Triple("storage", "存储", "storage"),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(palette.card)
            .padding(horizontal = 8.dp, vertical = 8.dp),
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
