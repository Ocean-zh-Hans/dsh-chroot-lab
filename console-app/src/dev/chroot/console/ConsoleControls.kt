// 交互控件：滑动确认闸门。
//
// 设计稿 §8.2 要求 R3 用"滑动确认执行"而不是一次点击：手指必须真的推到右端，
// 误触（口袋里、单手持机、按键粘连）不会触发写盘操作。控件本身不做权限判断，
// 只负责"确认这件事需要付出一个连续动作"。
package dev.chroot.console

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp

/**
 * 磁盘构成条：把"镜像 / 快照 / state / 剩余"按字节比例画成一条。
 * 只用来给"是不是快满了"一个直觉，精确数字仍在下面的行里。
 */
@Composable
fun SpaceBar(segments: List<Triple<String, Long, Color>>) {
    val palette = consolePalette()
    val total = segments.sumOf { it.second }.coerceAtLeast(1L)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp))
                .background(palette.faint.copy(alpha = 0.18f)),
        ) {
            segments.forEach { (_, bytes, color) ->
                val weight = (bytes.toFloat() / total.toFloat()).coerceIn(0.004f, 1f)
                Box(Modifier.weight(weight).fillMaxSize().background(color))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            segments.forEach { (label, bytes, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        label + " " + ConsoleRepo.humanBytes(bytes),
                        color = palette.faint, fontSize = ConsoleType.hint, maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 从最左推到最右才算确认。推到 82% 松手即视为完成，其余情况弹回。
 *
 * @param text 轨道上的提示文案（一句话说清将要发生什么）
 * @param tint 危险动作用红，普通写操作用强调色
 */
@Composable
fun SlideToConfirm(text: String, tint: Color, enabled: Boolean = true, onConfirm: () -> Unit) {
    val palette = consolePalette()
    val density = LocalDensity.current
    val thumbPx = with(density) { 44.dp.toPx() }
    var trackPx by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    val maxOffset = (trackPx.toFloat() - thumbPx).coerceAtLeast(1f)
    val progress = (offset / maxOffset).coerceIn(0f, 1f)
    val active = if (enabled) tint else palette.faint

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(ConsoleShape.button))
            .background(active.copy(alpha = 0.14f))
            .onGloballyPositioned { trackPx = it.size.width }
            .semantics { contentDescription = "滑动确认：" + text }
            .pointerInput(enabled, maxOffset) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offset >= maxOffset * 0.82f) {
                            offset = maxOffset
                            onConfirm()
                        } else {
                            offset = 0f
                        }
                    },
                    onDragCancel = { offset = 0f },
                    onHorizontalDrag = { _, drag ->
                        offset = (offset + drag).coerceIn(0f, maxOffset)
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(ConsoleShape.chip))
                    .background(active),
                contentAlignment = Alignment.Center,
            ) {
                Text("→", color = Color.White, fontSize = ConsoleType.headline, fontWeight = FontWeight.Bold)
            }
        }
        Box(
            modifier = Modifier.fillMaxSize().padding(start = 44.dp + 6.dp, end = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = active.copy(alpha = 0.55f + 0.45f * (1f - progress)),
                fontSize = ConsoleType.label,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
        // 拇指滑块：位置由 offset 直接决定，不做动画，避免"看起来推到头了却没触发"。
        Box(
            modifier = Modifier
                .padding(start = if (offset <= 0f) 6.dp else 0.dp)
                .then(
                    if (offset <= 0f) {
                        Modifier
                    } else {
                        Modifier.padding(start = with(density) { offset.toDp() })
                    },
                ),
        ) {}
    }
}

// 安装新版本：来源是"选一个"，不是"填一个"——三个 chip 对应三种取包方式，
// 每种只暴露自己的必填项，避免用户面对一堆用不上的输入框。
private val installSources = listOf(
    Triple("registry", "镜像源", "从 npm/pnpm 源装指定版本"),
    Triple("tarball", "压缩包", "给一个 https 的 .tar.gz 地址"),
    Triple("local", "本地文件", "给 guest 内的 .tar.gz 或已解包目录"),
)

@Composable
fun InstallSheet(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (source: String, version: String, target: String, sha256: String) -> Unit,
) {
    val palette = consolePalette()
    var source by remember { mutableStateOf("registry") }
    var version by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    val hint = installSources.first { it.first == source }.third
    val ready = !busy && version.isNotBlank() && (source == "registry" || target.isNotBlank())
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        GlassCard {
            Column(Modifier.padding(18.dp)) {
                Text("安装 DSH 版本", color = palette.text, fontSize = ConsoleType.headline, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("装进 versions/<版本>，不会自动切换；装完可在版本列表里点切换。", color = palette.faint, fontSize = ConsoleType.small)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    installSources.forEach { entry ->
                        if (entry.first == source) {
                            PrimaryButton(entry.second, enabled = !busy, onClick = {})
                        } else {
                            GhostButton(entry.second, enabled = !busy) { source = entry.first }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(hint, color = palette.faint, fontSize = ConsoleType.hint)
                Spacer(Modifier.height(12.dp))
                SheetField("版本号", version, "例如 1.5.0", !busy) { version = it }
                when (source) {
                    "tarball" -> {
                        Spacer(Modifier.height(10.dp))
                        SheetField("下载地址", target, "https://…/dsh.tar.gz", !busy) { target = it }
                        Spacer(Modifier.height(10.dp))
                        SheetField("sha256（可选）", sha, "下载后校验，防中间篡改", !busy) { sha = it }
                    }
                    "local" -> {
                        Spacer(Modifier.height(10.dp))
                        SheetField("文件路径", target, "/data/media/0/Download/dsh.tar.gz", !busy) { target = it }
                        Spacer(Modifier.height(10.dp))
                        SheetField("sha256（可选）", sha, "只对压缩包生效", !busy) { sha = it }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GhostButton("取消", enabled = !busy, onClick = onDismiss)
                    PrimaryButton(
                        text = "下一步",
                        enabled = ready,
                        onClick = { onConfirm(source, version, target, sha) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetField(label: String, value: String, placeholder: String, enabled: Boolean, onChange: (String) -> Unit) {
    val palette = consolePalette()
    Column {
        Text(label, color = palette.sub, fontSize = ConsoleType.hint)
        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = palette.text, fontSize = ConsoleType.label),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(placeholder, color = palette.faint, fontSize = ConsoleType.label)
                    }
                    inner()
                },
            )
        }
    }
}

/** 改依赖版本范围：只填一个字符串，写入宿主上的 package.json。 */
@Composable
fun PinDialog(
    name: String,
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val palette = consolePalette()
    var range by remember(name) { mutableStateOf(current) }
    Dialog(onDismissRequest = onDismiss) {
        GlassCard {
            Column(Modifier.padding(20.dp)) {
                Text("改依赖版本", color = palette.text, fontSize = ConsoleType.headline, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(name, color = palette.sub, fontSize = ConsoleType.label)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = range,
                    onValueChange = { range = it },
                    singleLine = true,
                    label = { Text("版本范围", fontSize = ConsoleType.small) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前 " + current + "。只改构建清单，不装包；装包要在 chroot 运行时执行 pnpm install。",
                    color = palette.faint,
                    fontSize = ConsoleType.hint,
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { GhostButton("取消", onClick = onDismiss) }
                    Box(Modifier.weight(1f)) {
                        PrimaryButton("下一步", { onConfirm(range.trim()) }, palette.accent, range.isNotBlank())
                    }
                }
            }
        }
    }
}
