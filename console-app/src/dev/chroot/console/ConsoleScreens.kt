package dev.chroot.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.suqi8.coui.kmp.basic.Switch

// ---------------------------------------------------------------- lifecycle

private val lifecycleSteps = listOf("stopped", "starting", "running", "stopping")

@Composable
private fun LifecycleStrip(state: String) {
    val palette = consolePalette()
    val active = when (state) {
        "stopped", "failed" -> 0
        "starting" -> 1
        "running" -> 2
        "degraded" -> 2
        "stopping" -> 3
        else -> 2
    }
    val abnormal = state in listOf("degraded", "recovery-required", "rescue-needed", "stale", "failed")
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            lifecycleSteps.forEachIndexed { index, label ->
                val done = index <= active
                Box(
                    Modifier
                        .size(if (index == active) 10.dp else 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (done) palette.accent else palette.faint.copy(alpha = 0.25f)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when (label) {
                        "stopped" -> "已停止"
                        "starting" -> "启动中"
                        "running" -> "运行中"
                        else -> "停止中"
                    },
                    color = if (index == active) palette.text else palette.faint,
                    fontSize = ConsoleType.hint,
                    fontWeight = if (index == active) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (index < lifecycleSteps.lastIndex) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.weight(1f).height(1.dp)
                            .background(if (done) palette.accent.copy(alpha = 0.5f) else palette.faint.copy(alpha = 0.2f)),
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
        }
        if (abnormal) {
            Spacer(Modifier.height(8.dp))
            StatusPill("异常：" + ConsoleSupport.stateLabel(state), stateTint(state, palette))
        }
    }
}

// ------------------------------------------------------------ recommendations

internal data class Recommendation(
    val title: String,
    val detail: String,
    val request: RiskRequest? = null,
    val actionLabel: String = "去处理",
)

internal fun recommendations(status: ConsoleStatus, doctor: DoctorReport?): List<Recommendation> {
    val list = ArrayList<Recommendation>()
    when (status.state) {
        "recovery-required", "stale" -> list += Recommendation(
            "环境需要清理",
            "上一次运行没有正常收尾，先清理残留的挂载、loop 与进程，再启动。",
            RiskRequest(
                op = "recover",
                title = "清理残留",
                args = listOf("recover", "--apply"),
                reason = "控制台清理残留环境",
                impact = "结束卡住的 holder、卸载残留挂载并释放 loop",
                sections = listOf(
                    "是什么" to "强制结束上次运行留下的 holder 和相关进程，并按记录逆序卸载挂载、释放 loop。",
                    "什么时候用" to "状态显示“需要恢复/已过期”，或启动提示 previous runtime records remain 时。",
                    "会有什么后果" to "正在运行的 DSH 会被中断；不会删除镜像或用户数据。",
                    "怎么恢复" to "清理后会回到 stopped，可正常启动；失败会保留 recovery-required 与恢复脚本。",
                ),
            ),
        )
        "rescue-needed" -> list += Recommendation(
            "需要救援模式",
            "连续启动失败，自动启动已被抑制。先修复环境，再尝试救援 shell。",
            RiskRequest(
                op = "rescue",
                title = "进入救援模式",
                args = listOf("rescue", "--apply"),
                reason = "控制台进入救援模式",
                impact = "只读挂载 rootfs 并打开救援 shell",
                sections = listOf(
                    "是什么" to "停掉实例后，只读挂载 rootfs，打开一个不挂 /data 的救援 shell。",
                    "什么时候用" to "连续启动失败、需要手工检查或修复 rootfs 时。",
                    "会有什么后果" to "DSH 停止；救援 shell 内不能写 rootfs。",
                    "怎么恢复" to "退出 shell 后 holder 自动清理，再回到正常启动流程。",
                ),
            ),
        )
        "degraded" -> list += Recommendation(
            "DSH 未就绪",
            "holder 已在运行，但 DSH 地址或健康检查没有通过。先看日志，再重启 DSH 服务。",
            RiskRequest(
                op = "restart",
                title = "重启 DSH 服务",
                args = listOf("restart", "--apply"),
                reason = "控制台重启 DSH 服务",
                impact = "短暂中断 DSH，重新挂载并启动服务",
                sections = listOf(
                    "是什么" to "停止当前实例并按同样的配置重新启动。",
                    "什么时候用" to "DSH 打不开、健康检查连续失败、hooks 没有加载时。",
                    "会有什么后果" to "正在运行的会话会中断，镜像与 state 保留。",
                    "怎么恢复" to "重启失败会进入 degraded/recovery-required，可在本页继续处理。",
                ),
            ),
        )
    }
    doctor?.let { report ->
        if (report.fail > 0) {
            val first = report.checks.firstOrNull { it.level == "FAIL" }
            list += Recommendation(
                "体检发现 " + report.fail + " 项失败",
                first?.message ?: "运行体检查看明细。",
            )
        }
    }
    if (status.state == "running" && status.healthAge < 0) {
        list += Recommendation("健康心跳缺失", "DSH 可能在启动中或已停止响应，先打开日志确认。")
    }
    return list.take(2)
}

@Composable
internal fun RecommendationCard(item: Recommendation, onAction: (RiskRequest) -> Unit, onLogs: () -> Unit) {
    val palette = consolePalette()
    GlassCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(palette.warning))
                Spacer(Modifier.width(8.dp))
                Text(item.title, color = palette.text, fontSize = ConsoleType.label, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(item.detail, color = palette.sub, fontSize = ConsoleType.small)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (item.request != null) {
                    Box(Modifier.weight(1f)) { PrimaryButton(item.actionLabel, { onAction(item.request) }, tint = palette.warning) }
                }
                Box(Modifier.weight(1f)) { GhostButton("查看日志", onClick = onLogs) }
            }
        }
    }
}

// -------------------------------------------------------------------- doctor

@Composable
fun DoctorScreen(report: DoctorReport?, onReload: (() -> Unit) -> Unit) {
    val palette = consolePalette()
    LaunchedEffect(Unit) { if (report == null) onReload {} }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            GlassCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            report?.let { it.pass.toString() + " 通过 · " + it.warn + " 注意 · " + it.fail + " 失败" } ?: "正在检查…",
                            color = palette.text, fontSize = ConsoleType.body, fontWeight = FontWeight.Medium,
                        )
                        Text("只读检查，不改系统", color = palette.faint, fontSize = ConsoleType.hint)
                    }
                    GhostButton("重查") { onReload {} }
                }
            }
        }
        val checks = report?.checks ?: emptyList()
        items(checks) { check ->
            val tint = when (check.level) {
                "FAIL" -> palette.danger
                "WARN" -> palette.warning
                else -> palette.success
            }
            GlassCard {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 5.dp).size(6.dp).clip(RoundedCornerShape(50)).background(tint))
                    Spacer(Modifier.width(8.dp))
                    Text(check.message, color = palette.sub, fontSize = ConsoleType.small)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- logs

@Composable
fun LogsScreen(logs: String, kind: String, onKind: (String) -> Unit, onRefresh: () -> Unit, onCopy: () -> Unit) {
    val palette = consolePalette()
    LaunchedEffect(Unit) { onRefresh() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "全部", "nsinit" to "nsinit", "dsh" to "DSH", "boot" to "boot", "watchdog" to "watchdog").forEach { (key, label) ->
                val active = key == kind
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (active) palette.accent.copy(alpha = 0.14f) else palette.text.copy(alpha = 0.05f))
                        .clickable { onKind(key) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) { Text(label, color = if (active) palette.accent else palette.sub, fontSize = ConsoleType.small) }
            }
        }
        GlassCard {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    logs.ifBlank { "点按刷新加载日志" },
                    color = palette.sub, fontSize = ConsoleType.hint, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { GhostButton("刷新", onClick = onRefresh) }
            Box(Modifier.weight(1f)) { GhostButton("复制", onClick = onCopy) }
        }
    }
}

// ----------------------------------------------------------------- snapshots

@Composable
fun SnapshotsScreen(
    snaps: List<SnapshotEntry>,
    onLoad: () -> Unit,
    onRestore: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: () -> Unit,
    busy: Boolean = false,
) {
    val palette = consolePalette()
    LaunchedEffect(Unit) { onLoad() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            GlassCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("快照", color = palette.text, fontSize = ConsoleType.label, fontWeight = FontWeight.Medium)
                        Text("镜像 + state 的一份副本；恢复前自动保留上一份", color = palette.faint, fontSize = ConsoleType.hint)
                    }
                    PrimaryButton("新建快照", onCreate, enabled = !busy)
                }
            }
        }
        if (snaps.isEmpty()) {
            item { GlassCard { Text("还没有快照", color = palette.faint, fontSize = ConsoleType.small, modifier = Modifier.padding(16.dp)) } }
        }
        items(snaps) { snap ->
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(snap.name, color = palette.text, fontSize = ConsoleType.label, fontWeight = FontWeight.Medium)
                        if (snap.lastGood) { Spacer(Modifier.width(8.dp)); StatusPill("last-good", palette.success) }
                    }
                    Text(snap.created.ifBlank { "—" } + " · " + ConsoleRepo.humanBytes(snap.bytes), color = palette.faint, fontSize = ConsoleType.hint)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("恢复", enabled = !busy) { onRestore(snap.name) }
                        GhostButton("删除", enabled = !busy && !snap.lastGood) { onDelete(snap.name) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ settings

@Composable
fun SettingsScreen(
    status: ConsoleStatus,
    runtime: RuntimeInfo,
    onAction: (RiskRequest) -> Unit,
    onAbout: () -> Unit,
    onCopyDiagnostics: () -> Unit,
) {
    val palette = consolePalette()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    Text("开机与确认", color = palette.text, fontSize = ConsoleType.label, fontWeight = FontWeight.Medium)
                    ToggleRow("开机自动启动", status.autostart) { checked ->
                        onAction(
                            RiskRequest(
                                op = "autostart",
                                title = "切换开机自启",
                                args = listOf("autostart", if (checked) "on" else "off"),
                                reason = "控制台修改设置项",
                                impact = "改变下次开机的自动启动行为",
                                sections = listOf(
                                    "是什么" to "决定手机开机后是否自动启动 chroot 与 DSH。",
                                    "什么时候用" to "希望开机即用时打开；排障期间建议关闭。",
                                    "会有什么后果" to if (checked) {
                                        "以后每次开机都会占用 loop 与端口，开机耗时会略增。"
                                    } else {
                                        "以后开机不再自动启动；重启后需要手动 start 才有 DSH。"
                                    },
                                    "怎么恢复" to if (checked) {
                                        "随时可以关掉，立即生效到下次启动。"
                                    } else {
                                        "随时可以再打开，立即生效到下次启动。"
                                    },
                                ),
                            ),
                        )
                    }
                    ToggleRow("新会话逐项确认", status.guard) { checked ->
                        onAction(
                            RiskRequest(
                                op = "guard",
                                title = "切换确认模式",
                                args = listOf("guard", if (checked) "on" else "off"),
                                reason = "控制台修改设置项",
                                impact = "改变新会话的默认权限预设",
                                sections = listOf(
                                    "是什么" to "新会话默认使用逐项批准（approval-guard）或自动执行（approval-free）。",
                                    "什么时候用" to "需要更谨慎地批准敏感操作时打开。",
                                    "会有什么后果" to "打开后每个敏感操作都会请求批准。",
                                    "怎么恢复" to "关闭即可恢复自动执行；已开会话需要用 /permission 切换。",
                                ),
                            ),
                        )
                    }
                }
            }
        }
        item {
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    Text("运行时能力", color = palette.text, fontSize = ConsoleType.label, fontWeight = FontWeight.Medium)
                    Text("hooks：" + hooksLabel(runtime), color = palette.sub, fontSize = ConsoleType.small)
                    Text("原因：" + runtime.hooksReason.ifBlank { "—" }, color = palette.faint, fontSize = ConsoleType.hint)
                    Text("DSH：不读取、不修改 SELinux；块设备默认只读视图。", color = palette.faint, fontSize = ConsoleType.hint)
                }
            }
        }
        item {
            GlassCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onCopyDiagnostics)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("复制诊断包", color = palette.text, fontSize = ConsoleType.label, fontWeight = FontWeight.Medium)
                            Text("机型 / 内核 / 模块 / loop / 挂载 / 体检，贴 issue 用", color = palette.faint, fontSize = ConsoleType.hint)
                        }
                        Text("复制", color = palette.accent, fontSize = ConsoleType.small)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onAbout)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("关于", color = palette.text, fontSize = ConsoleType.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("›", color = palette.faint, fontSize = ConsoleType.body)
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------- about

@Composable
fun AboutScreen(status: ConsoleStatus, runtime: RuntimeInfo) {
    val palette = consolePalette()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassCard {
            Column(Modifier.padding(16.dp)) {
                Text("版本", color = palette.text, fontSize = ConsoleType.label, fontWeight = FontWeight.Medium)
                InfoRow("模块", runtime.module.ifBlank { status.moduleVersion.ifBlank { "—" } })
                InfoRow("DSH", runtime.dshRuntime)
                InfoRow("镜像", ConsoleRepo.humanBytes(status.imageBytes))
                InfoRow("boot_id", status.bootId.take(8))
            }
        }
        GlassCard {
            Column(Modifier.padding(16.dp)) {
                Text("Console 0.3.0 · 读全开、写分级", color = palette.faint, fontSize = ConsoleType.hint)
            }
        }
    }
}

@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    hint: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val palette = consolePalette()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) palette.text else palette.faint, fontSize = ConsoleType.label)
            if (hint != null) Text(hint, color = palette.faint, fontSize = ConsoleType.hint)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val palette = consolePalette()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = palette.faint, fontSize = ConsoleType.small, modifier = Modifier.width(72.dp))
        Text(value, color = palette.text, fontSize = ConsoleType.label, modifier = Modifier.weight(1f))
    }
}

// ------------------------------------------------------------- risk explainer

@Composable
fun RiskExplainScreen(request: RiskRequest, onBack: () -> Unit, onConfirm: (RiskRequest) -> Unit) {
    val palette = consolePalette()
    Box(Modifier.fillMaxSize().background(palette.background).systemBarsPadding().padding(horizontal = 16.dp)) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(request.title, color = palette.text, fontSize = ConsoleType.display, fontWeight = FontWeight.SemiBold)
            Text("执行前请先读完下面的说明。", color = palette.faint, fontSize = ConsoleType.small)
            Spacer(Modifier.height(14.dp))
            request.sections.forEach { (heading, body) ->
                GlassCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(heading, color = palette.sub, fontSize = ConsoleType.small, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(body, color = palette.text, fontSize = ConsoleType.label)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    Text("将记录", color = palette.faint, fontSize = ConsoleType.hint)
                    Text("原因：" + request.reason, color = palette.sub, fontSize = ConsoleType.small)
                    Text("影响：" + request.impact, color = palette.sub, fontSize = ConsoleType.small)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        if (request.destructive) {
            SlideToConfirm(request.confirmHint, palette.danger) { onConfirm(request) }
            Spacer(Modifier.height(8.dp))
            GhostButton("取消", onClick = onBack)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { GhostButton("取消", onClick = onBack) }
                Box(Modifier.weight(1f)) { PrimaryButton("确认执行", { onConfirm(request) }) }
            }
        }
    }
    }
}

// -------------------------------------------------------------------- output

/** hooks 状态是"DSH 能不能用"的直接指标，措辞要能一眼分辨。 */
internal fun hooksLabel(runtime: RuntimeInfo): String = when (runtime.hooksMode) {
    "full" -> "已加载（完整）"
    "partial" -> "部分加载（功能可能缺）"
    "none", "off", "" -> "未加载（插件不生效）"
    else -> runtime.hooksMode
}

// dsh-ctl prints started as 2026-10-31T12:35:06+0800 (offset without colon),
// so OffsetDateTime.parse cannot be used as-is. Never throw: an unparsable
// timestamp must only hide the uptime, not crash the page.
internal fun parseEpochSeconds(value: String): Long? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val iso = if (text.length > 5 && (text[text.length - 5] == '+' || text[text.length - 5] == '-')) {
        text.dropLast(2) + ":" + text.takeLast(2)
    } else {
        text
    }
    return try {
        java.time.OffsetDateTime.parse(iso).toEpochSecond()
    } catch (error: Exception) {
        try {
            java.time.LocalDateTime.parse(iso.substringBefore('+').substringBeforeLast('-', missingDelimiterValue = iso))
                .atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
        } catch (error2: Exception) {
            null
        }
    }
}

internal fun formatUptime(value: String, now: Long = java.time.Instant.now().epochSecond): String {
    val epoch = parseEpochSeconds(value) ?: return ""
    val seconds = now - epoch
    if (seconds <= 0) return "刚刚启动"
    val minutes = seconds / 60
    return when {
        minutes < 1 -> "已运行 " + seconds + " 秒"
        minutes < 60 -> "已运行 " + minutes + " 分"
        minutes < 60 * 24 -> "已运行 " + (minutes / 60) + " 小时 " + (minutes % 60) + " 分"
        else -> "已运行 " + (minutes / 60 / 24) + " 天 " + (minutes / 60 % 24) + " 小时"
    }
}

internal fun formatStart(value: String): String {
    if (value.isBlank()) return "没有启动记录"
    val date = value.substringBefore('T')
    val time = value.substringAfter('T', "").take(5)
    return if (date.length >= 10) "启动于 " + date.substring(5) + " " + time else "启动于 " + value
}

internal fun pathLabel(name: String): String = when (name) {
    "image" -> "镜像"
    "snapshots" -> "快照目录"
    "state" -> "状态目录"
    "workspace" -> "工作区"
    "dsh-home" -> "DSH home"
    "logs" -> "日志"
    "control" -> "控制面"
    "sdcard-dsh" -> "/sdcard/DSH"
    else -> name
}

private fun reasonLabel(reason: String): String = when (reason) {
    "requested" -> "已请求"
    "full-patch-missing" -> "缺少完整 patch"
    "permission-presets-unavailable" -> "权限预设不可用"
    "audit-hooks-unavailable" -> "审计插件不可用"
    "runtime-unsupported" -> "运行时版本不支持"
    else -> reason
}
