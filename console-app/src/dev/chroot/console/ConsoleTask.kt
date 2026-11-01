package dev.chroot.console

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * 一个正在跑（或刚跑完）的写操作。start 要 30–90 秒，
 * 所以结果不能只放在一个"完成"弹窗里：任务条一直在，输出边跑边看。
 */
data class ConsoleTask(
    val id: Int,
    val title: String,
    val startedAt: Long,
    val lines: List<String> = emptyList(),
    val state: String = "running",
    val exitCode: Int? = null,
) {
    val done: Boolean get() = state != "running"

    val steps: List<Triple<Int, String, Boolean>> get() {
        val all = lines.filter { it.startsWith("#step:") }.map { it.removePrefix("#step:").trim() }
        return all.mapIndexed { index, name -> Triple(index, name, done || index < all.size - 1) }
    }

    val lastLine: String
        get() = lines.lastOrNull { it.isNotBlank() && !it.startsWith("#step:") }?.take(60) ?: "已提交…"

    fun elapsedSeconds(now: Long): Long = ((now - startedAt) / 1000).coerceAtLeast(0)

    /** 不到两秒的操作显示 "0s" 只是噪音；空串让 UI 直接省掉这一格。 */
    fun elapsedLabel(now: Long): String {
        val seconds = elapsedSeconds(now)
        return if (seconds < 2) "" else seconds.toString() + "s"
    }
}

@Composable
fun TaskBar(task: ConsoleTask, now: Long, onOpen: () -> Unit) {
    val palette = consolePalette()
    val tint = when (task.state) {
        "ok" -> palette.success
        "failed" -> palette.danger
        else -> palette.accent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(ConsoleShape.card))
            .background(palette.card)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(tint))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.title + when (task.state) {
                    "ok" -> " · 完成"
                    "failed" -> " · 失败"
                    else -> " · 进行中"
                },
                color = palette.text, fontSize = ConsoleType.label, fontWeight = FontWeight.Medium,
            )
            Text(task.lastLine, color = palette.faint, fontSize = ConsoleType.hint, maxLines = 1)
        }
        Text(task.elapsedLabel(now), color = palette.faint, fontSize = ConsoleType.hint)
        Spacer(Modifier.width(8.dp))
        Text("›", color = palette.faint, fontSize = ConsoleType.body)
    }
}

@Composable
fun TaskSheet(task: ConsoleTask, now: Long, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    val palette = consolePalette()
    val list = rememberLazyListState()
    Dialog(onDismissRequest = onDismiss) {
        GlassCard(shape = 24) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(task.title, color = palette.text, fontSize = ConsoleType.body, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    StatusPill(
                        when (task.state) {
                            "ok" -> "完成"
                            "failed" -> "失败 " + (task.exitCode ?: -1)
                            else -> "进行中"
                        },
                        when (task.state) {
                            "ok" -> palette.success
                            "failed" -> palette.danger
                            else -> palette.accent
                        },
                    )
                    Spacer(Modifier.weight(1f))
                    Text(task.elapsedLabel(now), color = palette.faint, fontSize = ConsoleType.hint)
                }
                if (task.steps.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    task.steps.forEach { (index, name, finished) ->
                        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (finished) "✓" else "·", color = if (finished) palette.success else palette.faint, fontSize = ConsoleType.small)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                (index + 1).toString() + ". " + name,
                                color = if (finished) palette.sub else palette.text, fontSize = ConsoleType.small,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val tail = task.lines.filter { it.isNotBlank() }.takeLast(400)
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 260.dp)
                        .clip(RoundedCornerShape(ConsoleShape.chip))
                        .background(palette.text.copy(alpha = 0.04f)),
                ) {
                    if (tail.isEmpty()) {
                        Text("等待输出…", color = palette.faint, fontSize = ConsoleType.hint, modifier = Modifier.padding(12.dp))
                    } else {
                        LazyColumn(state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                            items(tail) { line ->
                                Text(
                                    if (line.startsWith("#step:")) line.removePrefix("#step:") else line,
                                    color = if (line.startsWith("#step:")) palette.accent else palette.sub,
                                    fontSize = ConsoleType.hint,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        LaunchedEffect(tail.size) { list.animateScrollToItem((tail.size - 1).coerceAtLeast(0)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        GhostButton("复制输出") { onCopy(task.lines.joinToString("\n")) }
                    }
                    Box(Modifier.weight(1f)) {
                        PrimaryButton(if (task.done) "关闭" else "后台继续", onDismiss)
                    }
                }
            }
        }
    }
}
