package dev.dsh.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Switch
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class AgentUiState(
    val listening: Boolean = false,
    val accessibility: Boolean = false,
    val notification: Boolean = false,
    val root: Boolean = false,
    val rootReason: String = "",
    val keepAlive: Boolean = false,
    val phase: PresencePhase = PresencePhase.IDLE,
    val paused: Boolean = false,
    val sessionActive: Boolean = false,
    val startedAt: Long = 0L,
    val lastMethod: String = "",
)

@Composable
fun AgentHomeScreen() {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val pageColor = if (dark) Color(0xFF0B0F14) else Color(0xFFF2F4F8)
    val tokenPath = remember { AgentServer.tokenFile(context).toString() }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(AgentUiState()) }
    var latestReceipt by remember { mutableStateOf<JSONObject?>(null) }
    var keepAlive by remember { mutableStateOf(AgentRuntimeService.enabled(context)) }
    var rootBusy by remember { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            AgentRuntimeService.setEnabled(context, true)
            keepAlive = true
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            state = withContext(Dispatchers.IO) {
                AgentUiState(
                    listening = AgentServer.status().startsWith("listening"),
                    accessibility = AgentServer.accessibility() != null,
                    notification = NotificationRelay.connected(),
                    root = RootClient.available(),
                    rootReason = RootClient.reason(),
                    keepAlive = AgentRuntimeService.enabled(context),
                    phase = PresenceState.phase(),
                    paused = PresenceState.isPaused(),
                    sessionActive = PresenceState.isSessionActive(),
                    startedAt = PresenceState.startedAt(),
                    lastMethod = PresenceState.lastMethod(),
                )
            }
            latestReceipt = withContext(Dispatchers.IO) { ReceiptStore.latest(context) }
            delay(1500)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pageColor)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF5AA7FF).copy(alpha = if (dark) 0.10f else 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.86f, size.height * 0.06f),
                        radius = size.width * 1.05f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF7BE0D0).copy(alpha = if (dark) 0.05f else 0.08f), Color.Transparent),
                        center = Offset(size.width * 0.05f, size.height * 0.88f),
                        radius = size.width * 0.85f,
                    ),
                )
            }
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(top = 16.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Header(state)
            Hero(
                state = state,
                onAccessibility = { openSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onNotification = { openSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onToggle = {
                    if (state.paused) PresenceState.resume(context) else PresenceState.pause(context, "手动暂停")
                },
            )
            HealthSection(
                state = state,
                keepAlive = keepAlive,
                onAccessibility = { openSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onNotification = { openSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onKeepAlive = { enabled ->
                    if (!enabled) {
                        AgentRuntimeService.setEnabled(context, false)
                        keepAlive = false
                    } else if (Build.VERSION.SDK_INT >= 33) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        AgentRuntimeService.setEnabled(context, true)
                        keepAlive = true
                    }
                },
                rootBusy = rootBusy,
                onRootHelper = {
                    if (!rootBusy) {
                        val stopping = state.root
                        rootBusy = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                if (stopping) RootHelperInstaller.stop(context) else RootHelperInstaller.start(context)
                            }
                            rootBusy = false
                            Toast.makeText(context, result, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onBattery = {
                    openSettings(
                        context,
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:dev.dsh.agent"),
                        ),
                    )
                },
            )
            ReceiptSection(latestReceipt)
            ConnectionFooter(tokenPath)
        }
    }
}

@Composable
private fun Header(state: AgentUiState) {
    val colors = COUITheme.colorScheme
    val typography = COUITheme.textStyles
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2F7CF6), Color(0xFF69C8FF)))),
            contentAlignment = Alignment.Center,
        ) {
            Text("D", style = typography.title3, color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("DSH Agent", style = typography.title2, color = colors.onBackground)
            Text(
                "把手机借给 AI 的一小段时间",
                style = typography.footnote1,
                color = colors.onBackgroundVariant,
            )
        }
        StatusPill(state)
    }
}

@Composable
private fun StatusPill(state: AgentUiState) {
    val colors = COUITheme.colorScheme
    val typography = COUITheme.textStyles
    val ready = state.listening && state.accessibility && state.notification && state.keepAlive
    val text = when (state.phase) {
        PresencePhase.IDLE -> if (ready) "就绪" else "待设置"
        else -> state.phase.label
    }
    val tint = when (state.phase) {
        PresencePhase.WORKING -> Color(0xFF3482FF)
        PresencePhase.WAITING_HUMAN, PresencePhase.PAUSED -> Color(0xFFFF9F0A)
        PresencePhase.FINISHED -> Color(0xFF22A06B)
        PresencePhase.DISCONNECTED -> Color(0xFFE5484D)
        PresencePhase.IDLE -> if (ready) Color(0xFF22A06B) else Color(0xFFFF9F0A)
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.11f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(5.dp))
        Text(text, style = typography.footnote2, color = tint)
    }
}

@Composable
private fun Hero(
    state: AgentUiState,
    onAccessibility: () -> Unit,
    onNotification: () -> Unit,
    onToggle: () -> Unit,
) {
    val typography = COUITheme.textStyles
    val active = state.listening && state.accessibility && state.notification && state.keepAlive &&
        state.phase != PresencePhase.DISCONNECTED
    val running = state.phase == PresencePhase.WORKING
    val waiting = state.phase == PresencePhase.WAITING_HUMAN || state.phase == PresencePhase.PAUSED
    val gradient = when (state.phase) {
        PresencePhase.WAITING_HUMAN, PresencePhase.PAUSED -> listOf(Color(0xFF2A1B12), Color(0xFF4A2A16), Color(0xFF6A3A1D))
        PresencePhase.FINISHED -> listOf(Color(0xFF092018), Color(0xFF103A2A), Color(0xFF15543B))
        PresencePhase.DISCONNECTED -> listOf(Color(0xFF211016), Color(0xFF3A1820), Color(0xFF551F2A))
        else -> listOf(Color(0xFF081326), Color(0xFF0D2340), Color(0xFF123F66))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.linearGradient(gradient))
            .drawBehind {
                drawCircle(
                    color = Color(0xFF5AC8FF).copy(alpha = 0.10f),
                    radius = size.minDimension * 0.75f,
                    center = Offset(size.width * 0.94f, size.height * 0.06f),
                )
                drawCircle(
                    color = Color.White.copy(alpha = 0.04f),
                    radius = size.minDimension * 0.55f,
                    center = Offset(size.width * 0.12f, size.height * 0.92f),
                )
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "DSH · PRESENCE",
                    style = typography.footnote2,
                    color = Color.White.copy(alpha = 0.48f),
                )
                Text(
                    when (state.phase) {
                        PresencePhase.WORKING -> "ACTIVE"
                        PresencePhase.WAITING_HUMAN -> "WAITING"
                        PresencePhase.PAUSED -> "PAUSED"
                        PresencePhase.FINISHED -> "DONE"
                        PresencePhase.DISCONNECTED -> "OFFLINE"
                        PresencePhase.IDLE -> if (active) "READY" else "OFFLINE"
                    },
                    style = typography.footnote2,
                    color = Color.White.copy(alpha = 0.48f),
                )
            }
            Spacer(Modifier.height(10.dp))
            PresenceOrb(active = running, paused = waiting)
            Spacer(Modifier.height(8.dp))
            Text(
                when (state.phase) {
                    PresencePhase.WORKING -> "AI 操作中"
                    PresencePhase.WAITING_HUMAN -> "待人工接管"
                    PresencePhase.PAUSED -> "暂停中"
                    PresencePhase.FINISHED -> "已结束"
                    PresencePhase.DISCONNECTED -> "已断开"
                    PresencePhase.IDLE -> if (active) "正在待命" else "尚未连接"
                },
                style = typography.title1,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when (state.phase) {
                    PresencePhase.WORKING -> "它正在替你操作这部手机。"
                    PresencePhase.WAITING_HUMAN -> "需要你处理一下，处理完可以继续。"
                    PresencePhase.PAUSED -> "手机仍在你手里。随时可以继续。"
                    PresencePhase.FINISHED -> "这次任务结束了，可以拿回手机。"
                    PresencePhase.DISCONNECTED -> "DSH 连接断开了，检查服务后重试。"
                    PresencePhase.IDLE -> if (active) "它已经能看见屏幕，也能替你动手。" else "打开无障碍服务，让它先看见你。"
                },
                style = typography.body2,
                color = Color.White.copy(alpha = 0.68f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                HeroMetric("无障碍", state.accessibility)
                HeroMetric("通知", state.notification)
                HeroMetric("保活", state.keepAlive)
            }
            Spacer(Modifier.height(18.dp))
            HeroAction(
                text = when {
                    !state.accessibility -> "开启无障碍"
                    !state.notification -> "开启通知使用权"
                    state.phase == PresencePhase.WAITING_HUMAN || state.phase == PresencePhase.PAUSED -> "继续"
                    else -> "暂停"
                },
                onClick = when {
                    !state.accessibility -> onAccessibility
                    !state.notification -> onNotification
                    else -> onToggle
                },
            )
        }
    }
}

@Composable
private fun PresenceOrb(active: Boolean, paused: Boolean) {
    val accent = when {
        paused -> Color(0xFFFFB15E)
        active -> Color(0xFF6FD4FF)
        else -> Color(0xFF8C9AAF)
    }
    val transition = rememberInfiniteTransition(label = "presence")
    val breathe by transition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(22000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    Box(
        modifier = Modifier.size(168.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(168.dp)
                .graphicsLayer {
                    scaleX = breathe
                    scaleY = breathe
                }
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.06f), Color.Transparent),
                    ),
                    CircleShape,
                ),
        )
        Canvas(
            modifier = Modifier
                .size(145.dp)
                .rotate(spin),
        ) {
            val stroke = 1.dp.toPx()
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = size.minDimension / 2f - stroke,
                style = Stroke(stroke),
            )
            drawArc(
                color = accent.copy(alpha = 0.78f),
                startAngle = 205f,
                sweepAngle = 76f,
                useCenter = false,
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
                style = Stroke(2.dp.toPx()),
            )
            drawArc(
                color = Color.White.copy(alpha = 0.32f),
                startAngle = 30f,
                sweepAngle = 42f,
                useCenter = false,
                topLeft = Offset.Zero,
                size = Size(size.width, size.height),
                style = Stroke(1.dp.toPx()),
            )
        }
        Box(
            modifier = Modifier
                .size(92.dp)
                .background(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = 0.82f), accent.copy(alpha = 0.55f), accent.copy(alpha = 0.15f)),
                    ),
                    CircleShape,
                ),
        )
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.92f)),
        )
    }
}

@Composable
private fun HeroMetric(label: String, ok: Boolean) {
    val typography = COUITheme.textStyles
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (ok) Color(0xFF7CE7B4) else Color.White.copy(alpha = 0.38f)),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, style = typography.footnote2, color = Color.White.copy(alpha = 0.68f))
    }
}

@Composable
private fun HeroAction(text: String, onClick: () -> Unit) {
    val typography = COUITheme.textStyles
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.96f))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = typography.button, color = Color(0xFF0B2342), textAlign = TextAlign.Center)
    }
}

@Composable
private fun HealthSection(
    state: AgentUiState,
    keepAlive: Boolean,
    onAccessibility: () -> Unit,
    onNotification: () -> Unit,
    onKeepAlive: (Boolean) -> Unit,
    rootBusy: Boolean,
    onRootHelper: () -> Unit,
    onBattery: () -> Unit,
) {
    val colors = COUITheme.colorScheme
    val typography = COUITheme.textStyles
    val ready = listOf(state.accessibility, state.notification, state.keepAlive, state.root).count { it }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text("身体检查", style = typography.title3, color = colors.onBackground)
            Text("$ready / 4", style = typography.footnote1, color = colors.onBackgroundVariant)
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                HealthRow(
                    title = "无障碍服务",
                    detail = "读屏、点按、滑动、输入",
                    ok = state.accessibility,
                    action = if (state.accessibility) null else "去开启",
                    onClick = onAccessibility,
                )
                HealthDivider()
                HealthRow(
                    title = "通知使用权",
                    detail = "通知、回复、收起通知",
                    ok = state.notification,
                    action = if (state.notification) null else "去开启",
                    onClick = onNotification,
                )
                HealthDivider()
                HealthRow(
                    title = "前台保活",
                    detail = "锁屏后也保持待命",
                    ok = keepAlive,
                    action = null,
                    onClick = {},
                    trailing = {
                        Switch(checked = keepAlive, onCheckedChange = onKeepAlive)
                    },
                )
                HealthDivider()
                HealthRow(
                    title = "电池优化",
                    detail = "避免系统在后台冻结 Agent",
                    ok = true,
                    action = "去检查",
                    onClick = onBattery,
                )
                HealthDivider()
                HealthRow(
                    title = "Root helper",
                    detail = when {
                        state.root -> "已连接，可执行受控 shell"
                        rootBusy -> "正在处理 root helper，请留意 root 授权"
                        else -> state.rootReason.ifBlank { "点“启动”安装并拉起独立 root 进程" }
                    },
                    ok = state.root,
                    action = when {
                        rootBusy -> null
                        state.root -> "停止"
                        else -> "启动"
                    },
                    onClick = onRootHelper,
                )
            }
        }
    }
}

@Composable
private fun HealthRow(
    title: String,
    detail: String,
    ok: Boolean,
    action: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = COUITheme.colorScheme
    val typography = COUITheme.textStyles
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = action != null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background((if (ok) Color(0xFF22A06B) else colors.onBackgroundVariant).copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (ok) Color(0xFF22A06B) else colors.onBackgroundVariant.copy(alpha = 0.55f)),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = typography.body1, color = colors.onBackground)
            Text(detail, style = typography.footnote1, color = colors.onBackgroundVariant)
        }
        when {
            trailing != null -> trailing()
            action != null -> Text(action, style = typography.subtitle, color = colors.primary)
            else -> Text(if (ok) "就绪" else "未连接", style = typography.footnote1, color = colors.onBackgroundVariant)
        }
    }
}

@Composable
private fun HealthDivider() {
    val colors = COUITheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 62.dp)
            .height(1.dp)
            .background(colors.onBackground.copy(alpha = 0.06f)),
    )
}

@Composable
private fun ReceiptSection(receipt: JSONObject?) {
    val colors = COUITheme.colorScheme
    val typography = COUITheme.textStyles
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("最近回执", style = typography.title3, color = colors.onBackground)
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(
                            if (receipt == null) colors.onBackgroundVariant.copy(alpha = 0.10f)
                            else Color(0xFF22A06B).copy(alpha = 0.12f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(
                        if (receipt == null) colors.onBackgroundVariant.copy(alpha = 0.55f) else Color(0xFF22A06B),
                    ))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        receipt?.optString("title", "未命名任务") ?: "还没有回执",
                        style = typography.body1,
                        color = colors.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        receipt?.let { "任务完成，留下了一步一步的记录" } ?: "Agent 完成任务后，这里会留下它的足迹",
                        style = typography.footnote1,
                        color = colors.onBackgroundVariant,
                    )
                }
                if (receipt != null) {
                    Text(
                        receipt.optString("status", ""),
                        style = typography.footnote1,
                        color = if (receipt.optString("status") == "ok") Color(0xFF22A06B) else colors.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionFooter(tokenPath: String) {
    val colors = COUITheme.colorScheme
    val typography = COUITheme.textStyles
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("仅本机 · 127.0.0.1:17321", style = typography.footnote1, color = colors.onBackgroundVariant)
        SelectionContainer {
            Text(tokenPath, style = typography.footnote2, color = colors.onBackgroundVariant.copy(alpha = 0.55f))
        }
        Text("文件访问由 DSH shell 管理", style = typography.footnote2, color = colors.onBackgroundVariant.copy(alpha = 0.55f))
    }
}

private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        // Some OEM settings screens are not exported; leave the state untouched.
    }
}
