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

    fun capture(service: AccessibilityService, g: Long, seq: Long): Captured {
        val metrics = service.resources.displayMetrics
        val screen = DRect(0, 0, metrics.widthPixels, metrics.heightPixels)
        val builder = SnapshotBuilder(g = g, seq = seq, at = SystemClock.uptimeMillis(), pkg = "", activity = "", screen = screen)
        val handles = HashMap<Long, AccessibilityNodeInfo>(256)
        val budget = intArrayOf(MAX_NODES)
        var keyboard = false
        var pkg = ""
        val windows: List<AccessibilityWindowInfo> = try {
            service.windows ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        val ordered = windows.sortedBy { it.layer }
        for (w in ordered) {
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
            if (pkg.isEmpty()) pkg = root.packageName?.toString() ?: ""
            walk(builder, handles, root, -1, 0, w.id, w.layer, false, budget)
            root.recycle()
        }
        builder.keyboard(keyboard).packageName(pkg)
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
        val id = builder.add(
            cls = node.className?.toString() ?: "",
            bounds = DRect(rect.left, rect.top, rect.right, rect.bottom),
            parent = parent,
            text = node.text?.toString() ?: "",
            desc = node.contentDescription?.toString() ?: "",
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
            walk(builder, handles, child, id, depth + 1, window, layer, nowClickable, budget)
            child.recycle()
        }
    }
}

/** 手势派发：没有可点节点的兜底路径。 */
internal object Gestures {
    fun tap(service: AccessibilityService, x: Int, y: Int, durationMs: Long = 60): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        return dispatch(service, GestureDescription.Builder().addStroke(stroke).build())
    }

    fun swipe(service: AccessibilityService, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        return dispatch(service, GestureDescription.Builder().addStroke(stroke).build())
    }

    private fun dispatch(service: AccessibilityService, gesture: GestureDescription): Boolean {
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
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
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
