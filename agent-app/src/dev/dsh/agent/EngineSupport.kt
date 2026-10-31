package dev.dsh.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.dsh.agent.core.Flags
import dev.dsh.agent.core.Node
import dev.dsh.agent.core.Rect as DRect
import dev.dsh.agent.core.Snapshot
import dev.dsh.agent.core.SnapshotBuilder
import dev.dsh.agent.core.SnapshotView
import dev.dsh.agent.core.RefStore
import java.lang.reflect.Method

/** 反射拿 AccessibilityNodeInfo 的私有 sourceNodeId：这是系统内唯一的节点身份（同 Appium 的做法）。 */
internal object NodeIds {
    private var method: Method? = null
    private var tried = false

    @Synchronized
    fun of(node: AccessibilityNodeInfo): Long {
        if (!tried) {
            tried = true
            method = try {
                AccessibilityNodeInfo::class.java.getMethod("getSourceNodeId")
            } catch (t: Throwable) {
                null
            }
        }
        val m = method ?: return -1L
        return try {
            (m.invoke(node) as? Long) ?: -1L
        } catch (t: Throwable) {
            -1L
        }
    }
}

/** 一次抓取的结果：快照 + nativeId 到系统节点的句柄表。 */
internal class Captured(
    val snapshot: Snapshot,
    val handles: Map<Long, AccessibilityNodeInfo>,
)

/**
 * 把 AccessibilityService 的活树抓成不可变快照。
 * 规则取自调研：剪掉不可见且无子节点的子树、保留原始 child index、深度与节点数双重上限、防环。
 */
internal object Capture {
    private const val MAX_DEPTH = 50
    private const val MAX_NODES = 2000

    /** 密码字段从 Capture 层就脱敏；View/Diff/nodes 任何下游都拿不到明文。 */
    private const val PASSWORD_MASK = "••••"

    fun capture(service: AccessibilityService, g: Long, seq: Long): Captured {
        // 快路径：只抓当前活动窗口。ColorOS 的 service.windows 在后台线程上
        // 经常要等 5 秒超时；日常读屏不需要状态栏/导航栏，ui.windows 另走全量窗口。
        val root = try {
            service.rootInActiveWindow
        } catch (t: Throwable) {
            null
        } ?: return captureAll(service, g, seq)
        val metrics = service.resources.displayMetrics
        val screen = DRect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val builder = SnapshotBuilder(g = g, seq = seq, at = SystemClock.uptimeMillis(), pkg = "", activity = "", screen = screen)
        val handles = HashMap<Long, AccessibilityNodeInfo>(256)
        val budget = intArrayOf(MAX_NODES)
        val windowPkg = root.packageName?.toString() ?: ""
        walk(builder, handles, root, -1, 0, -1, 0, windowPkg, false, budget)
        root.recycle()
        builder.keyboard(false).packageName(windowPkg).activeWindow(-1)
            .activityName(if (windowPkg.isNotEmpty() && windowPkg == UiClock.pkg) UiClock.activity else "")
        return Captured(builder.build(), handles)
    }

    /** 兜底/显式全量窗口路径。 */
    fun captureAll(service: AccessibilityService, g: Long, seq: Long): Captured {
        val metrics = service.resources.displayMetrics
        val screen = DRect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val builder = SnapshotBuilder(g = g, seq = seq, at = SystemClock.uptimeMillis(), pkg = "", activity = "", screen = screen)
        val handles = HashMap<Long, AccessibilityNodeInfo>(256)
        val budget = intArrayOf(MAX_NODES)
        var keyboard = false
        var pkg = ""
        var activePkg = ""
        var activeWindow = -1
        val windows: List<AccessibilityWindowInfo> = try {
            service.windows ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        val ordered = windows.sortedBy { it.layer }
        for (w in ordered) {
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) keyboard = true
            val root = try {
                w.root
            } catch (t: Throwable) {
                null
            } ?: continue
            if (budget[0] <= 0) {
                root.recycle()
                continue
            }
            val windowPkg = root.packageName?.toString() ?: ""
            if (pkg.isEmpty()) pkg = windowPkg
            if (w.isActive) {
                activePkg = windowPkg
                activeWindow = w.id
            }
            walk(builder, handles, root, -1, 0, w.id, w.layer, windowPkg, false, budget)
            root.recycle()
        }
        if (activePkg.isNotEmpty()) pkg = activePkg
        builder.keyboard(keyboard).packageName(pkg).activeWindow(activeWindow)
            .activityName(if (pkg.isNotEmpty() && pkg == UiClock.pkg) UiClock.activity else "")
        return Captured(builder.build(), handles)
    }

    private fun walk(
        builder: SnapshotBuilder,
        handles: MutableMap<Long, AccessibilityNodeInfo>,
        node: AccessibilityNodeInfo,
        parent: Int,
        depth: Int,
        window: Int,
        layer: Int,
        windowPkg: String,
        clickableAncestor: Boolean,
        budget: IntArray,
    ) {
        if (budget[0] <= 0 || depth > MAX_DEPTH) return
        val visible = try {
            node.isVisibleToUser
        } catch (t: Throwable) {
            false
        }
        val childCount = try {
            node.childCount
        } catch (t: Throwable) {
            0
        }
        if (!visible && childCount == 0) return
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return
        budget[0]--
        var flags = 0
        if (node.isClickable) flags = flags or Flags.CLICKABLE
        if (node.isLongClickable) flags = flags or Flags.LONG_CLICKABLE
        if (node.isEditable) flags = flags or Flags.EDITABLE
        if (node.isScrollable) flags = flags or Flags.SCROLLABLE
        if (node.isCheckable) flags = flags or Flags.CHECKABLE
        if (node.isChecked) flags = flags or Flags.CHECKED
        if (node.isSelected) flags = flags or Flags.SELECTED
        if (node.isEnabled) flags = flags or Flags.ENABLED
        if (node.isFocused) flags = flags or Flags.FOCUSED
        if (visible) flags = flags or Flags.VISIBLE
        if (node.isPassword) flags = flags or Flags.PASSWORD
        if (clickableAncestor && flags and Flags.CLICKABLE == 0) flags = flags or Flags.CLICKABLE_ANCESTOR
        val nativeId = NodeIds.of(node)
        val password = node.isPassword
        val rawText = node.text?.toString() ?: ""
        val rawDesc = node.contentDescription?.toString() ?: ""
        val id = builder.add(
            cls = node.className?.toString() ?: "",
            bounds = DRect(rect.left, rect.top, rect.right, rect.bottom),
            parent = parent,
            text = if (password && rawText.isNotEmpty()) PASSWORD_MASK else rawText,
            desc = if (password && rawDesc.isNotEmpty()) PASSWORD_MASK else rawDesc,
            hint = try {
                node.hintText?.toString() ?: ""
            } catch (t: Throwable) {
                ""
            },
            viewId = node.viewIdResourceName ?: "",
            flags = flags,
            nativeId = nativeId,
            window = window,
            z = layer,
            nodePkg = windowPkg,
            mergeDefaults = false,
        )
        if (nativeId >= 0) {
            try {
                handles[nativeId] = AccessibilityNodeInfo.obtain(node)
            } catch (t: Throwable) {
                // 忽略：句柄拿不到就只能靠指纹重定位
            }
        }
        val nowClickable = clickableAncestor || node.isClickable
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (t: Throwable) {
                null
            } ?: continue
            walk(builder, handles, child, id, depth + 1, window, layer, windowPkg, nowClickable, budget)
            child.recycle()
        }
    }
}

/** 手势派发：没有可点节点的兜底路径。 */
internal object Gestures {
    data class StrokeSpec(
        val points: List<Pair<Float, Float>>,
        val startMs: Long = 0L,
        val durationMs: Long = 300L,
    )

    fun tap(service: AccessibilityService, x: Int, y: Int, durationMs: Long = 60): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        return dispatch(service, GestureDescription.Builder().addStroke(stroke).build())
    }

    fun doubleTap(service: AccessibilityService, x: Int, y: Int, gapMs: Long = 100): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val first = GestureDescription.StrokeDescription(path, 0, 40)
        val second = GestureDescription.StrokeDescription(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, gapMs.coerceAtLeast(40), 40)
        return dispatch(service, GestureDescription.Builder().addStroke(first).addStroke(second).build(), gapMs + 1_500)
    }

    fun swipe(service: AccessibilityService, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        return dispatch(service, GestureDescription.Builder().addStroke(stroke).build())
    }

    fun drag(
        service: AccessibilityService,
        x1: Int, y1: Int, x2: Int, y2: Int,
        holdMs: Long = 500,
        durationMs: Long = 300,
    ): Boolean {
        val first = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x1 + 1f, y1.toFloat())
        }
        val initial = GestureDescription.StrokeDescription(first, 0, holdMs.coerceAtLeast(50), true)
        val moved = Path().apply {
            // 续接 stroke 必须从上一段终点开始，否则会跳到起点重新按下。
            moveTo(x1 + 1f, y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val continuation = initial.continueStroke(moved, 0, durationMs.coerceAtLeast(50), false)
        return dispatch(service, GestureDescription.Builder().addStroke(initial).addStroke(continuation).build(),
            holdMs + durationMs + 1_500)
    }

    /** 通用多指/多路径手势；最多 20 条 stroke（AOSP GestureDescription 上限）。 */
    fun gesture(service: AccessibilityService, strokes: List<StrokeSpec>): Boolean {
        if (strokes.isEmpty() || strokes.size > 20) return false
        val builder = GestureDescription.Builder()
        var maxEnd = 0L
        for (spec in strokes) {
            if (spec.points.size < 2) return false
            val path = Path()
            spec.points.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(point.first, point.second)
                else path.lineTo(point.first, point.second)
            }
            val duration = spec.durationMs.coerceIn(1L, 10_000L)
            val start = spec.startMs.coerceAtLeast(0L)
            builder.addStroke(GestureDescription.StrokeDescription(path, start, duration))
            maxEnd = maxOf(maxEnd, start + duration)
        }
        return dispatch(service, builder.build(), maxEnd + 1_500)
    }

    private fun dispatch(
        service: AccessibilityService,
        gesture: GestureDescription,
        timeoutMs: Long = 2_000,
    ): Boolean {
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) {
                ok[0] = true
                done.countDown()
            }

            override fun onCancelled(description: GestureDescription?) {
                ok[0] = false
                done.countDown()
            }
        }
        val accepted = service.dispatchGesture(gesture, callback, null)
        if (!accepted) return false
        if (!done.await(timeoutMs.coerceAtLeast(500), java.util.concurrent.TimeUnit.MILLISECONDS)) return false
        return ok[0]
    }
}

/** 无障碍事件的时间戳：用来判断界面是否已静默（settle）。 */
internal object UiClock {
    @Volatile
    var pkg: String = ""

    @Volatile
    var activity: String = ""

    @Volatile
    var lastEventAt: Long = 0L

    @Volatile
    var events: Long = 0L

    fun page(pkgName: String, activityName: String) {
        pkg = pkgName
        activity = activityName
    }

    fun note() {
        lastEventAt = SystemClock.uptimeMillis()
        events = events + 1
    }

    fun quietMs(): Long = SystemClock.uptimeMillis() - lastEventAt
}
