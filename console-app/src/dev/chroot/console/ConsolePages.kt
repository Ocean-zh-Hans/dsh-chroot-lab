package dev.chroot.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------------- 系统层

/**
 * 系统层四件事：起停、物资、地盘、体检。
 * 判据：它坏的时候整机起不来 → 系统层；chroot 停止时仍需要可用 → 系统层。
 */
@Composable
fun SystemPage(
    status: ConsoleStatus,
    doctor: DoctorReport?,
    snaps: List<SnapshotEntry>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenDsh: () -> Unit,
    onSnapshots: () -> Unit,
    onRestore: (String) -> Unit,
    onLogs: () -> Unit,
    onDoctor: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    onAction: (RiskRequest) -> Unit,
) {
    val palette = consolePalette()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item {
            StateCard(
                status = status,
                onStart = onStart,
                onStop = onStop,
                onOpenDsh = onOpenDsh,
                onLogs = onLogs,
            )
        }
        val advice = recommendations(status, doctor)
        if (advice.isNotEmpty()) {
            item { SectionTitle("建议") }
            items(advice) { RecommendationCard(it, onAction, onLogs) }
        }
        item { SectionTitle("快照", snaps.size.toString() + " 个") }
        item {
            GlassCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (snaps.isEmpty()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text("还没有快照；升级或改系统文件前先做一个。", color = palette.faint, fontSize = 12.sp)
                        }
                    }
                    snaps.take(3).forEach { snap ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onRestore(snap.name) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    snap.name + if (snap.lastGood) "  · 上一份良好" else "",
                                    color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    ConsoleRepo.humanBytes(snap.bytes) + " · " + snap.created,
                                    color = palette.faint, fontSize = 11.sp,
                                )
                            }
                            Text("恢复 ›", color = palette.accent, fontSize = 12.sp)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onSnapshots)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("全部快照与新建", color = palette.sub, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("›", color = palette.faint, fontSize = 16.sp)
                    }
                }
            }
        }
        item { SectionTitle("运行时事实") }
        item { FactsGrid(factsOf(status)) }
        item { SectionTitle("诊断") }
        item {
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    val summary = doctor?.let { it.pass.toString() + " 通过 · " + it.warn + " 注意 · " + it.fail + " 失败" }
                        ?: "还没体检"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(summary, color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("体检只读；诊断包是可直接贴到 issue 的纯文本", color = palette.faint, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { GhostButton("运行体检", onDoctor) }
                        Box(Modifier.weight(1f)) { GhostButton("复制诊断包", onCopyDiagnostics) }
                    }
                }
            }
        }
        item {
            GlassCard(onClick = onLogs) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("最近日志", color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (status.note.isBlank()) "nsinit / boot / dsh / watchdog" else status.note,
                            color = palette.faint, fontSize = 11.sp,
                        )
                    }
                    Text("全部 ›", color = palette.sub, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun StateCard(
    status: ConsoleStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenDsh: () -> Unit,
    onLogs: () -> Unit,
) {
    val palette = consolePalette()
    val tint = stateTint(status.state, palette)
    val running = status.state == "running"
    GlassCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(tint))
                Spacer(Modifier.width(8.dp))
                Text(ConsoleSupport.stateLabel(status.state), color = palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                StatusPill(
                    if (status.moduleVersion.isBlank()) "模块" else "v" + status.moduleVersion,
                    palette.faint,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                statusLine(status),
                color = palette.sub, fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    if (running) PrimaryButton("停止", onStop, tint = palette.warning) else PrimaryButton("启动", onStart)
                }
                Box(Modifier.weight(1f)) {
                    if (running) GhostButton("打开 DSH", onOpenDsh)
                    else if (status.state == "stopped") GhostButton("看日志", onLogs)
                    else GhostButton("看日志", onLogs)
                }
            }
        }
    }
}

private fun statusLine(status: ConsoleStatus): String {
    val parts = ArrayList<String>(4)
    if (status.loop.isNotBlank()) parts += "loop " + status.loop
    if (status.pid.isNotBlank()) parts += "pid " + status.pid
    val uptime = formatStart(status.started)
    if (uptime.isNotBlank()) parts += uptime
    if (status.healthAge >= 0) parts += "健康 " + status.healthAge + "s 前"
    if (parts.isEmpty()) parts += "还没有启动过"
    return parts.joinToString(" · ")
}

private data class Fact(val label: String, val value: String, val warn: Boolean = false)

private fun factsOf(status: ConsoleStatus): List<Fact> {
    val facts = ArrayList<Fact>(4)
    facts += Fact(
        "镜像",
        if (status.image.isBlank()) "未安装" else status.image.substringAfterLast('/') + " · " + ConsoleRepo.humanBytes(status.imageBytes),
        warn = status.image.isBlank(),
    )
    facts += Fact(
        "loop",
        if (status.loop.isNotBlank()) status.loop else "未占用",
        warn = status.loop.isNotBlank() && status.state != "running",
    )
    facts += Fact(
        "rootfs",
        if (status.rootfsMounted) "已挂载 · ro" else "未挂载",
        warn = status.rootfsMounted != (status.state == "running"),
    )
    facts += Fact(
        "看门狗",
        when {
            status.watchdogRunning -> "运行中"
            status.autostart -> "未运行 · 自启开"
            else -> "未运行"
        },
        warn = status.autostart && !status.watchdogRunning,
    )
    return facts
}

@Composable
private fun FactsGrid(facts: List<Fact>) {
    val palette = consolePalette()
    GlassCard {
        Column(Modifier.padding(vertical = 4.dp)) {
            facts.forEachIndexed { index, fact ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.cardStroke))
                }
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fact.label, color = palette.faint, fontSize = 12.sp, modifier = Modifier.width(64.dp))
                    Text(
                        fact.value,
                        color = if (fact.warn) palette.warning else palette.text,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------- 软件层

/**
 * 软件层 = chroot 起来以后才需要的东西：终端、DSH 本体与依赖、插件、服务。
 * 系统层不碰这些；软件层的写操作一律先导出 lockfile 或做快照，再执行。
 */
@Composable
fun SoftwarePage(
    status: ConsoleStatus,
    runtime: RuntimeInfo,
    onOpenDsh: () -> Unit,
    onAction: (RiskRequest) -> Unit,
    onLogs: () -> Unit,
    onPending: (String) -> Unit,
) {
    val palette = consolePalette()
    val live = status.state == "running"
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item { DependencyBar(status, runtime) }
        item { SectionTitle("入口") }
        item {
            GlassCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    LinkRow("终端", if (live) "guest shell · Tab/Ctrl 工具条" else "需要先启动 chroot", live) {
                        if (live) onPending("终端") else onPending("先启动 chroot")
                    }
                    LinkRow("打开 DSH", if (live) status.url else "需要先启动 chroot", live, onOpenDsh)
                }
            }
        }
        item { SectionTitle("DSH") }
        item {
            GlassCard {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "版本 " + runtime.dshRuntime,
                                color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            )
                            Text(
                                "hooks " + runtime.hooksMode.ifBlank { "—" } +
                                    if (runtime.imageMtime.isBlank()) "" else " · 镜像 " + runtime.imageMtime,
                                color = palette.faint, fontSize = 11.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { GhostButton("升级 / 降级") { onPending("DSH 版本管理") } }
                        Box(Modifier.weight(1f)) { GhostButton("回滚上一版") { onPending("DSH 回滚") } }
                    }
                }
            }
        }
        item { SectionTitle("依赖版本") }
        item {
            GlassCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    LinkRow("node / pnpm", "在 guest 内读取版本", true) { onPending("依赖版本") }
                    LinkRow("hooks 插件", runtime.hooksMode.ifBlank { "未安装" }, live) { onPending("依赖版本") }
                }
            }
        }
        item { SectionTitle("插件") }
        item {
            GlassCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    LinkRow("ttyd", "Web 终端 · 状态待上报", live) { onPending("ttyd 开关") }
                    LinkRow("已安装插件", "查看 / 卸载 / 权限预设", true) { onPending("插件管理") }
                }
            }
        }
        item { SectionTitle("服务与日志") }
        item {
            GlassCard(onClick = onLogs) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("dsh.log · s6 服务", color = palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (status.healthAge >= 0) "健康心跳 " + status.healthAge + "s 前" else "没有健康心跳",
                            color = if (status.healthAge < 0) palette.warning else palette.faint, fontSize = 11.sp,
                        )
                    }
                    Text("全部 ›", color = palette.sub, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DependencyBar(status: ConsoleStatus, runtime: RuntimeInfo) {
    val palette = consolePalette()
    val ok = status.state == "running" && status.moduleVersion.isNotBlank()
    GlassCard {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).clip(RoundedCornerShape(50))
                        .background(if (ok) palette.success else palette.warning),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (ok) "下层健康：chroot 运行中 · 模块 v" + status.moduleVersion else "下层未就绪：" + ConsoleSupport.stateLabel(status.state),
                    color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "软件层的操作都发生在 chroot 里；下层不健康时先回系统页处理。",
                color = palette.faint, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, detail: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = consolePalette()
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) palette.text else palette.faint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = palette.faint, fontSize = 11.sp)
        }
        Text("›", color = palette.faint, fontSize = 16.sp)
    }
}

// ------------------------------------------------------------------- 设备层

@Composable
fun DevicePage(
    status: ConsoleStatus,
    paths: List<PathEntry>,
    device: List<Pair<String, String>>,
    onLoad: () -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val palette = consolePalette()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp)) {
        item { SectionTitle("这台机器") }
        item {
            GlassCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (device.isEmpty()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text("读取中…（只用 getprop / uname）", color = palette.faint, fontSize = 12.sp)
                        }
                    }
                    device.forEach { (label, value) ->
                        InfoLine(label, value)
                    }
                }
            }
        }
        item { SectionTitle("空间") }
        item {
            GlassCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    InfoLine("镜像文件", ConsoleRepo.humanBytes(status.imageBytes))
                    InfoLine("剩余空间", if (status.diskFreeMb < 0) "—" else status.diskFreeMb.toString() + " MB")
                    InfoLine("快照", if (status.snapshotCount < 0) "—" else status.snapshotCount.toString() + " 个")
                }
            }
        }
        item { SectionTitle("路径", "点按复制") }
        item {
            GlassCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    if (paths.isEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onLoad)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                        ) { Text("加载路径列表", color = palette.accent, fontSize = 13.sp) }
                    }
                    paths.forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onCopy(entry.path, entry.name) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(pathLabel(entry.name), color = palette.text, fontSize = 13.sp)
                                Text(
                                    entry.path + if (entry.bytes > 0) " · " + ConsoleRepo.humanBytes(entry.bytes) else "",
                                    color = palette.faint, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
        }
        item { SectionTitle("模块") }
        item {
            GlassCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    InfoLine("模块版本", status.moduleVersion.ifBlank { "—" })
                    InfoLine("自启", if (status.autostart) "开" else "关")
                    InfoLine("guarded start", if (status.guard) "开" else "关")
                    InfoLine("boot_id", status.bootId.ifBlank { "—" })
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val palette = consolePalette()
    Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = palette.faint, fontSize = 12.sp, modifier = Modifier.width(88.dp))
        Text(value, color = palette.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

// ---------------------------------------------------------------- diagnostics

/** 纯文本诊断包：机型/内核/模块/loop/挂载/体检，直接贴到 issue。 */
fun diagnosticsText(
    status: ConsoleStatus,
    doctor: DoctorReport?,
    paths: List<PathEntry>,
    device: List<Pair<String, String>>,
): String {
    val builder = StringBuilder()
    builder.append("== dsh-chroot 诊断包 ==\n")
    device.forEach { (label, value) -> builder.append(label).append(": ").append(value).append('\n') }
    builder.append("模块: ").append(status.moduleVersion.ifBlank { "?" }).append('\n')
    builder.append("状态: ").append(status.state).append(" pid=").append(status.pid.ifBlank { "-" })
        .append(" boot=").append(status.bootId.ifBlank { "-" }).append('\n')
    builder.append("镜像: ").append(status.image.ifBlank { "-" }).append(" (")
        .append(ConsoleRepo.humanBytes(status.imageBytes)).append(")\n")
    builder.append("loop: ").append(status.loop.ifBlank { "-" }).append(" query=")
        .append(status.loopQuery.ifBlank { "-" }).append('\n')
    builder.append("rootfs 挂载: ").append(if (status.rootfsMounted) "yes" else "no")
        .append(" 看门狗: ").append(if (status.watchdogRunning) "yes" else "no").append('\n')
    if (status.residual.isNotBlank()) builder.append("残留: ").append(status.residual).append('\n')
    if (status.note.isNotBlank()) builder.append("备注: ").append(status.note).append('\n')
    paths.forEach { builder.append("路径 ").append(it.name).append(": ").append(it.path).append('\n') }
    doctor?.let { report ->
        builder.append("体检: ").append(report.pass).append(" pass / ").append(report.warn)
            .append(" warn / ").append(report.fail).append(" fail\n")
        report.checks.filter { it.level != "PASS" }.forEach { builder.append("  ").append(it.level).append(' ').append(it.message).append('\n') }
    } ?: builder.append("体检: 未运行\n")
    return builder.toString()
}
