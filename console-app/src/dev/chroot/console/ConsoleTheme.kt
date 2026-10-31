package dev.chroot.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme

data class ConsolePalette(
    val background: Color,
    val card: Color,
    val cardStroke: Color,
    val text: Color,
    val sub: Color,
    val faint: Color,
    val accent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val isDark: Boolean,
)

private val SuccessGreen = Color(0xFF22A06B)
private val WarningAmber = Color(0xFFFF9F0A)
private val DangerRed = Color(0xFFE5484D)
private val AccentBlue = Color(0xFF3482FF)

@Composable
fun consolePalette(): ConsolePalette {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        ConsolePalette(
            // §9：纯色背景 + 单层卡片，不再用大面积渐变光斑与玻璃拟态。
            background = Color(0xFF0A0D12),
            card = Color(0xFF121821),
            cardStroke = Color.White.copy(alpha = 0.07f),
            text = Color(0xFFF2F5F8),
            sub = Color(0xFF9AA6B2),
            faint = Color(0xFF6B7885),
            accent = Color(0xFF5C9DFF),
            success = SuccessGreen,
            warning = WarningAmber,
            danger = DangerRed,
            isDark = true,
        )
    } else {
        ConsolePalette(
            background = Color(0xFFF5F7FA),
            card = Color.White,
            cardStroke = Color(0xFF0B1B33).copy(alpha = 0.08f),
            text = Color(0xFF0B1B2B),
            sub = Color(0xFF5C6B7A),
            faint = Color(0xFF93A0AD),
            accent = AccentBlue,
            success = SuccessGreen,
            warning = WarningAmber,
            danger = DangerRed,
            isDark = false,
        )
    }
}

@Composable
fun ConsoleBackdrop(content: @Composable () -> Unit) {
    val palette = consolePalette()
    Box(modifier = Modifier.fillMaxSize().background(palette.background)) { content() }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Int = 22,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val palette = consolePalette()
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(shape.dp))
        .background(palette.card)
        .border(1.dp, palette.cardStroke, RoundedCornerShape(shape.dp))
    Box(
        modifier = if (onClick != null) modifier.then(base).clickable(onClick = onClick) else modifier.then(base),
    ) {
        content()
    }
}

@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    val palette = consolePalette()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            text = text,
            color = palette.sub,
            fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
        )
        if (trailing != null) {
            androidx.compose.material3.Text(
                text = trailing,
                color = palette.faint,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
fun StatusPill(text: String, tint: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(tint))
        androidx.compose.material3.Text(
            text = text,
            color = tint,
            fontSize = 12.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

@Composable
fun LineIcon(kind: String, color: Color, size: Int = 22) {
    Canvas(modifier = Modifier.size(size.dp)) {
        val stroke = Stroke(width = this.size.minDimension * 0.09f, cap = StrokeCap.Round)
        when (kind) {
            "chroot" -> {
                drawCircle(color, radius = this.size.minDimension * 0.34f, center = center, style = stroke)
                drawCircle(color, radius = this.size.minDimension * 0.10f, center = center)
            }
            "dsh" -> {
                val w = this.size.width
                val h = this.size.height
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.16f, h * 0.22f),
                    size = Size(w * 0.68f, h * 0.56f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.14f),
                    style = stroke,
                )
                drawLine(color, Offset(w * 0.32f, h * 0.42f), Offset(w * 0.46f, h * 0.52f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.46f, h * 0.52f), Offset(w * 0.32f, h * 0.62f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            "chevron" -> {
                val w = this.size.width
                val h = this.size.height
                drawLine(color, Offset(w * 0.38f, h * 0.28f), Offset(w * 0.62f, h * 0.5f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(color, Offset(w * 0.62f, h * 0.5f), Offset(w * 0.38f, h * 0.72f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            "storage" -> {
                val w = this.size.width
                val h = this.size.height
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.15f, h * 0.18f),
                    size = Size(w * 0.70f, h * 0.64f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.16f),
                    style = stroke,
                )
                drawLine(color, Offset(w * 0.15f, h * 0.46f), Offset(w * 0.85f, h * 0.46f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawCircle(color, radius = w * 0.05f, center = Offset(w * 0.34f, h * 0.66f))
            }
            "gear" -> {
                val r = this.size.minDimension * 0.20f
                drawCircle(color, radius = r, center = center, style = stroke)
                for (index in 0 until 8) {
                    val angle = index * (Math.PI / 4).toFloat()
                    val inner = this.size.minDimension * 0.30f
                    val outer = this.size.minDimension * 0.42f
                    drawLine(
                        color,
                        center + Offset(cos(angle) * inner, sin(angle) * inner),
                        center + Offset(cos(angle) * outer, sin(angle) * outer),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round,
                    )
                }
            }
            else -> {
                val w = this.size.width
                val h = this.size.height
                for (index in 0..2) {
                    val y = h * (0.30f + index * 0.20f)
                    drawLine(color, Offset(w * 0.20f, y), Offset(w * 0.80f, y), strokeWidth = stroke.width, cap = StrokeCap.Round)
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, tint: Color? = null, enabled: Boolean = true) {
    val palette = consolePalette()
    val color = tint ?: palette.accent
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (enabled) color else palette.faint.copy(alpha = 0.25f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = text,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun GhostButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = consolePalette()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(palette.text.copy(alpha = if (enabled) 0.06f else 0.03f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = text,
            color = if (enabled) palette.text else palette.faint,
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        )
    }
}

@Composable
fun BodyText(text: String, color: Color? = null) {
    val palette = consolePalette()
    Text(
        text = text,
        style = COUITheme.textStyles.body1,
        color = color ?: palette.text,
    )
}

fun stateTint(state: String, palette: ConsolePalette): Color = when (state) {
    "running" -> palette.success
    "degraded", "starting", "stopping", "stale" -> palette.warning
    "failed", "recovery-required", "rescue-needed" -> palette.danger
    else -> palette.faint
}
