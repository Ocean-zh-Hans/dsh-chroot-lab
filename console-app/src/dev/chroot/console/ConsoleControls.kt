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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
                        color = palette.faint, fontSize = 11.sp, maxLines = 1,
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
            .clip(RoundedCornerShape(18.dp))
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
                    .clip(RoundedCornerShape(14.dp))
                    .background(active),
                contentAlignment = Alignment.Center,
            ) {
                Text("→", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        Box(
            modifier = Modifier.fillMaxSize().padding(start = 44.dp + 6.dp, end = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = active.copy(alpha = 0.55f + 0.45f * (1f - progress)),
                fontSize = 13.sp,
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
