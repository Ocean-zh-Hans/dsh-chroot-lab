package dev.dsh.agent

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import dev.dsh.agent.core.Backends
import dev.dsh.agent.core.Change
import dev.dsh.agent.core.Diff
import dev.dsh.agent.core.Flags
import dev.dsh.agent.core.Node
import dev.dsh.agent.core.Query
import dev.dsh.agent.core.RefStore
import dev.dsh.agent.core.Snapshot
import dev.dsh.agent.core.SnapshotView
import dev.dsh.agent.core.ViewOptions
import dev.dsh.agent.core.ViewRenderer
import dev.dsh.agent.core.Where
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock

/**
 * v3 引擎：ref 句柄 + 服务端校验 + 动作后 settle/diff。
 * 消费者是 chroot 里的 LLM agent，所以读接口默认给"够用的文本视图"，写接口默认返回"我改完以后页面变了什么"。
 */
class UiEngine private constructor() {

    private val refs = RefStore()
    private val lock = ReentrantLock()
    private var g = 0L
    private var lastSignature = ""
    private var view: SnapshotView? = null
    private var previous: SnapshotView? = null
    private var handles: Map<Long, AccessibilityNodeInfo> = emptyMap()

    companion object {
        private val INSTANCE = UiEngine()
        private const val QUIET_MS = 120L
        private const val SETTLE_MAX_MS = 1500L
        private const val STALE_MS = 30_000L
        private val ROUTED = setOf(
            "ui.view", "ui.nodes", "ui.diff", "ui.act", "ui.batch", "ui.fill",
            "ui.scrollUntil", "ui.scroll", "ui.wait", "ui.refresh", "ui.press", "ui.ime", "ui.shot", "sys.status",
        )

        @JvmStatic
        fun handles(method: String): Boolean = ROUTED.contains(method)

        @JvmStatic
        fun get(): UiEngine = INSTANCE
    }

    fun call(method: String, params: JSONObject): Any = when (method) {
        "ui.view" -> view(params)
        "ui.nodes" -> nodes(params)
        "ui.diff" -> diff(params)
        "ui.act" -> act(params)
        "ui.batch" -> batch(params)
        "ui.fill" -> fill(params)
        "ui.scrollUntil" -> scrollUntil(params)
        "ui.scroll" -> scroll(params)
        "ui.ime" -> ime(params)
        "ui.wait" -> waitFor(params)
        "ui.refresh" -> locked { captureNow().let { pageJson(it) } }
        "ui.press" -> press(params)
        "ui.shot" -> shot(params)
        "sys.status" -> status()
        else -> throw err("BAD_REQUEST", "unknown method " + method)
    }

    // ---------------------------------------------------------------- 读

    private fun view(params: JSONObject): JSONObject = locked {
        val v = current(params)
        val opts = ViewOptions(
            mode = params.optString("mode", "auto"),
            maxChars = params.optInt("maxChars", 2000),
            maxDepth = params.optInt("maxDepth", 3),
            includeBounds = params.optBoolean("includeBounds", true),
        )
        val rendered = ViewRenderer.render(v, opts)
        JSONObject()
            .put("ok", true)
            .put("text", rendered.text)
            .put("shown", rendered.shown)
            .put("omitted", rendered.omitted)
            .put("truncated", rendered.truncated)
            .put("page", pageJson(v))
    }

    private fun nodes(params: JSONObject): JSONObject = locked {
        val v = current(params)
        val max = params.optInt("max", 400)
        val ids = if (params.has("where")) {
            Query.select(v, parseWhere(params.getJSONObject("where")))
        } else {
            v.snapshot.walk()
        }
        val out = JSONArray()
        for (id in ids) {
            if (out.length() >= max) break
            val n = v.snapshot.node(id)
            if (!n.has(Flags.VISIBLE)) continue
            if (n.label.isEmpty() && !n.clickable && !n.editable && !n.scrollable) continue
            val o = JSONObject()
                .put("ref", v.refOf(id) ?: "")
                .put("role", n.role)
                .put("cls", n.cls)
                .put("bounds", ints(n.bounds.l, n.bounds.t, n.bounds.w, n.bounds.h))
            if (n.text.isNotEmpty()) o.put("text", n.text)
            if (n.desc.isNotEmpty()) o.put("desc", n.desc)
            if (n.viewId.isNotEmpty()) o.put("id", n.shortId)
            val flags = flagNames(n)
            if (flags.length() > 0) o.put("flags", flags)
            out.put(o)
        }
        JSONObject().put("ok", true).put("count", out.length()).put("nodes", out).put("page", pageJson(v))
    }

    private fun diff(params: JSONObject): JSONObject = locked {
        val v = current(params)
        val change = Diff.compute(previous, v, UiClock.quietMs(), true)
        JSONObject().put("ok", true).put("change", changeJson(change)).put("page", pageJson(v))
    }

    private fun waitFor(params: JSONObject): JSONObject = locked {
        val spec = params.optJSONObject("for") ?: JSONObject().put("idle", true)
        val timeout = params.optLong("timeoutMs", 8000L).coerceIn(500L, 60_000L)
        val started = SystemClock.uptimeMillis()
        var result: JSONObject? = null
        while (result == null) {
            val v = captureNow()
            if (satisfied(v, spec)) {
                result = JSONObject().put("ok", true)
                    .put("waitedMs", SystemClock.uptimeMillis() - started)
                    .put("page", pageJson(v))
            } else if (SystemClock.uptimeMillis() - started > timeout) {
                throw err("TIMEOUT", "condition not met in " + timeout + "ms").detail("page", pageJson(v))
            } else {
                Thread.sleep(100)
            }
        }
        result
    }

    private fun satisfied(v: SnapshotView, spec: JSONObject): Boolean {
        val text = spec.optString("text", "")
        if (text.isNotEmpty()) {
            val where = Where.text(text)
            if (Query.select(v, where).isEmpty()) return false
        }
        val gone = spec.optString("textGone", "")
        if (gone.isNotEmpty()) {
            val where = Where.text(gone)
            if (Query.select(v, where).isNotEmpty()) return false
        }
        val ref = spec.optString("gone", "")
        if (ref.isNotEmpty()) {
            val r = refs.resolve(v, ref)
            if (r.nodeId >= 0) return false
        }
        if (spec.optBoolean("idle", false)) {
            if (UiClock.quietMs() < 300) return false
        }
        return true
    }

    // ---------------------------------------------------------------- 写

    private fun act(params: JSONObject): JSONObject = locked {
        val started = SystemClock.uptimeMillis()
        val op = params.optString("op", "")
        if (op.isEmpty()) throw err("BAD_REQUEST", "op is required")
        val v = current(params)
        val target = resolve(v, params)
        val node = v.snapshot.node(target.nodeId)
        val via = exec(v, op, node, params)
        settle()
        val after = captureNow()
        val change = Diff.compute(v, after, UiClock.quietMs(), true)
        val out = JSONObject()
            .put("ok", true)
            .put("op", op)
            .put("via", via)
            .put("ref", target.ref ?: "")
            .put("relocated", target.relocated)
            .put("page", pageJson(after))
            .put("change", changeJson(change))
            .put("ms", SystemClock.uptimeMillis() - started)
        val next = indexOfNative(after, node)
        if (next >= 0) after.refOf(next)?.let { out.put("ref", it) }
        out
    }

    private fun batch(params: JSONObject): JSONObject = locked {
        val ops = params.optJSONArray("ops") ?: throw err("BAD_REQUEST", "ops is required")
        if (ops.length() == 0) throw err("BAD_REQUEST", "ops is empty")
        if (ops.length() > 20) throw err("BAD_REQUEST", "at most 20 ops per batch")
        val started = SystemClock.uptimeMillis()
        val results = JSONArray()
        var v = current(params)
        for (i in 0 until ops.length()) {
            val op = ops.getJSONObject(i)
            val name = op.optString("op", "")
            if (name == "press") {
                val pressed = try {
                    press(op)
                } catch (e: AgentError) {
                    e.detail("index", i)
                    throw e
                }
                results.put(JSONObject().put("index", i).put("op", name).put("ref", "")
                    .put("relocated", false).put("via", pressed.optString("via", "a11y")))
                if (i < ops.length() - 1) {
                    settle()
                    v = captureNow()
                }
                continue
            }
            val target = try {
                resolve(v, op)
            } catch (e: AgentError) {
                e.detail("index", i)
                throw e
            }
            val node = v.snapshot.node(target.nodeId)
            val via = try {
                exec(v, name, node, op)
            } catch (e: AgentError) {
                e.detail("index", i)
                throw e
            }
            results.put(JSONObject().put("index", i).put("op", name).put("ref", target.ref ?: "").put("relocated", target.relocated).put("via", via))
            if (i < ops.length() - 1) {
                settle()
                v = captureNow()
            }
        }
        val before = view
        settle()
        val after = captureNow()
        val change = Diff.compute(before, after, UiClock.quietMs(), true)
        JSONObject().put("ok", true).put("done", results.length()).put("results", results)
            .put("page", pageJson(after)).put("change", changeJson(change))
            .put("ms", SystemClock.uptimeMillis() - started)
    }

    private fun fill(params: JSONObject): JSONObject = locked {
        val started = SystemClock.uptimeMillis()
        val v = current(params)
        val target = resolve(v, params)
        val node = v.snapshot.node(target.nodeId)
        val text = params.optString("text", "")
        val via = setText(node, text, params.optBoolean("append", false), params)
        if (params.optBoolean("submit", false)) {
            Thread.sleep(80)
            val fresh = captureNow()
            val again = fresh.snapshot.nodes.firstOrNull { it.nativeId == node.nativeId } ?: node
            handle(again)?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            keyEnter(params)
        }
        settle()
        val after = captureNow()
        JSONObject().put("ok", true).put("ref", target.ref ?: "").put("text", text).put("via", via)
            .put("page", pageJson(after))
            .put("change", changeJson(Diff.compute(v, after, UiClock.quietMs(), true)))
            .put("ms", SystemClock.uptimeMillis() - started)
    }

    private fun scrollUntil(params: JSONObject): JSONObject = locked {
        val started = SystemClock.uptimeMillis()
        val want = params.optJSONObject("until") ?: throw err("BAD_REQUEST", "until is required")
        val where = parseWhere(want)
        val direction = params.optString("direction", "forward")
        val maxTimes = params.optInt("maxTimes", 8).coerceIn(1, 30)
        var v = current(params)
        var times = 0
        while (Query.select(v, where).isEmpty()) {
            if (times >= maxTimes) throw err("TIMEOUT", "target not found after " + times + " scrolls")
            val scrollable = v.snapshot.nodes.firstOrNull { it.scrollable && it.has(Flags.VISIBLE) }
                ?: throw err("NOT_FOUND", "no scrollable container")
            scrollNode(scrollable, direction, params)
            times++
            settle()
            v = captureNow()
        }
        val id = Query.select(v, where).first()
        JSONObject().put("ok", true).put("ref", v.refOf(id) ?: "").put("scrolls", times)
            .put("page", pageJson(v)).put("ms", SystemClock.uptimeMillis() - started)
    }

    private fun scroll(params: JSONObject): JSONObject = locked {
        val started = SystemClock.uptimeMillis()
        val v = current(params)
        val direction = params.optString("direction", "forward")
        val target = if (params.has("ref") || params.has("where")) resolve(v, params) else null
        val node = target?.let { v.snapshot.node(it.nodeId) }
            ?: v.snapshot.nodes.firstOrNull { it.scrollable && it.has(Flags.VISIBLE) }
            ?: throw err("NOT_FOUND", "no scrollable container")
        val via = scrollNode(node, direction, params)
        settle()
        val after = captureNow()
        JSONObject().put("ok", true).put("ref", target?.ref ?: "").put("direction", direction).put("via", via)
            .put("page", pageJson(after))
            .put("change", changeJson(Diff.compute(v, after, UiClock.quietMs(), true)))
            .put("ms", SystemClock.uptimeMillis() - started)
    }

    private fun ime(params: JSONObject): JSONObject = locked {
        val started = SystemClock.uptimeMillis()
        val action = params.optString("op", "set")
        val v = current(params)
        when (action) {
            "show" -> {
                val editable = v.snapshot.nodes.firstOrNull { it.editable && it.has(Flags.VISIBLE) }
                    ?: throw err("NOT_FOUND", "no editable field on screen")
                val h = handle(editable) ?: throw err("STALE", "node handle is gone")
                h.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                settle()
                JSONObject().put("ok", true).put("op", action).put("via", "a11y")
                    .put("page", pageJson(captureNow())).put("ms", SystemClock.uptimeMillis() - started)
            }
            "hide" -> {
                global(AccessibilityService.GLOBAL_ACTION_BACK, "back", params)
                settle()
                JSONObject().put("ok", true).put("op", action).put("via", "a11y")
                    .put("page", pageJson(captureNow())).put("ms", SystemClock.uptimeMillis() - started)
            }
            "set", "clear", "submit" -> {
                val target = resolve(v, params)
                val node = v.snapshot.node(target.nodeId)
                val text = if (action == "clear") "" else params.optString("text", "")
                val via = if (action == "submit") keyEnter(params) else setText(node, text, params.optBoolean("append", false), params)
                settle()
                val after = captureNow()
                JSONObject().put("ok", true).put("op", action).put("ref", target.ref ?: "")
                    .put("via", via).put("page", pageJson(after))
                    .put("change", changeJson(Diff.compute(v, after, UiClock.quietMs(), true)))
                    .put("ms", SystemClock.uptimeMillis() - started)
            }
            else -> throw err("BAD_REQUEST", "op must be set|clear|submit|show|hide")
        }
    }

    private fun press(params: JSONObject): JSONObject = locked {
        val key = params.optString("key", "")
        val via = when (key) {
            "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "back", params)
            "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "home", params)
            "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "recents", params)
            "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "notifications", params)
            "enter" -> keyEnter(params)
            else -> throw err("BAD_REQUEST", "unknown key " + key)
        }
        settle()
        val after = captureNow()
        JSONObject().put("ok", true).put("key", key).put("via", via).put("page", pageJson(after))
    }

    // ---------------------------------------------------------------- 动作实现

    private fun exec(v: SnapshotView, op: String, node: Node, params: JSONObject): String = when (op) {
        "tap", "click" -> click(node, params)
        "longClick" -> longClick(node, params)
        "setText" -> setText(node, params.optString("text", ""), params.optBoolean("append", false), params)
        "clearText" -> setText(node, "", false, params)
        "focus" -> {
            val h = handle(node) ?: throw err("STALE", "node handle is gone")
            if (!h.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) throw err("UNAVAILABLE", "focus was rejected")
            Backends.A11Y
        }
        "scroll" -> scrollNode(node, params.optString("direction", "forward"), params)
        "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "back", params)
        "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "home", params)
        "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "recents", params)
        else -> throw err("BAD_REQUEST", "unknown op " + op)
    }

    // ---------------------------------------------------------------- 动作：多后端

    private fun prefer(params: JSONObject): String = params.optString("backend", Backends.AUTO)

    private fun <T> viaBackends(op: String, params: JSONObject, block: (String) -> T): Pair<String, T> {
        val chain = Backends.chain(op, prefer(params))
        if (chain.isEmpty()) throw err("BAD_REQUEST", "no backend supports " + op + " (backend=" + prefer(params) + ")")
        var failed = ""
        var last: AgentError? = null
        for (name in chain) {
            try {
                return Pair(name, block(name))
            } catch (e: AgentError) {
                failed = name
                last = e
            } catch (e: Exception) {
                failed = name
                last = err("INTERNAL", name + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
        val error = last ?: err("INTERNAL", "no backend")
        val alts = JSONArray()
        for (pair in Backends.alternatives(failed, op)) {
            alts.put(JSONObject().put("backend", pair.first).put("why", pair.second))
        }
        if (alts.length() > 0) error.detail("alternatives", alts)
        throw error
    }

    private fun root(method: String, params: JSONObject): JSONObject {
        if (!RootClient.available()) throw err("UNAVAILABLE", "root helper is not connected")
        return RootClient.call(method, params)
    }

    private fun click(node: Node, params: JSONObject): String {
        if (!node.enabled) throw err("DISABLED", "target is disabled")
        val p = tapPoint(node) ?: throw err("BLOCKED", "target is covered by another window")
        return viaBackends("tap", params) { backend ->
            when (backend) {
                Backends.A11Y -> {
                    val h = handle(node)
                    if (h == null || !h.isClickable || !h.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        if (!Gestures.tap(service(), p.first, p.second)) throw err("UNAVAILABLE", "gesture dispatch failed")
                    }
                }
                Backends.UIA -> { root("ui.click", JSONObject().put("x", p.first).put("y", p.second)); Unit }
                else -> { root("shell.exec", JSONObject().put("command", "input tap " + p.first + " " + p.second)); Unit }
            }
        }.first
    }

    private fun longClick(node: Node, params: JSONObject): String {
        val p = tapPoint(node) ?: throw err("BLOCKED", "target is covered by another window")
        return viaBackends("long", params) { backend ->
            when (backend) {
                Backends.A11Y -> {
                    val h = handle(node)
                    if (h == null || !h.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                        if (!Gestures.tap(service(), p.first, p.second, 700)) throw err("UNAVAILABLE", "gesture dispatch failed")
                    }
                }
                Backends.UIA -> { root("ui.longClick", JSONObject().put("x", p.first).put("y", p.second)); Unit }
                else -> {
                    root("shell.exec", JSONObject().put("command",
                        "input swipe " + p.first + " " + p.second + " " + p.first + " " + p.second + " 700"))
                    Unit
                }
            }
        }.first
    }

    private fun setText(node: Node, text: String, append: Boolean, params: JSONObject): String {
        if (!node.editable) throw err("NOT_EDITABLE", "target is not editable")
        return viaBackends("setText", params) { backend ->
            if (backend != Backends.A11Y) throw err("UNAVAILABLE", "text input needs the a11y backend")
            val h = handle(node) ?: throw err("STALE", "node handle is gone")
            h.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val bundle = Bundle()
            val value = if (append) (h.text?.toString() ?: "") + text else text
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            if (!h.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)) {
                throw err("INTERNAL", "ACTION_SET_TEXT was rejected")
            }
        }.first
    }

    private fun scrollNode(node: Node, direction: String, params: JSONObject): String {
        val b = node.bounds
        val dy = if (direction == "backward") b.h / 2 else -b.h / 2
        return viaBackends("scroll", params) { backend ->
            when (backend) {
                Backends.A11Y -> {
                    val h = handle(node)
                    val action = if (direction == "backward") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    if (h == null || !h.performAction(action)) {
                        if (!Gestures.swipe(service(), b.cx, b.cy, b.cx, b.cy + dy, 260)) {
                            throw err("UNAVAILABLE", "scroll gesture failed")
                        }
                    }
                }
                Backends.UIA -> {
                    root("ui.swipe", JSONObject().put("x1", b.cx).put("y1", b.cy)
                        .put("x2", b.cx).put("y2", b.cy + dy).put("durationMs", 260))
                    Unit
                }
                else -> {
                    root("shell.exec", JSONObject().put("command",
                        "input swipe " + b.cx + " " + b.cy + " " + b.cx + " " + (b.cy + dy) + " 260"))
                    Unit
                }
            }
        }.first
    }

    private fun global(action: Int, name: String, params: JSONObject): String =
        viaBackends("press", params) { backend ->
            when (backend) {
                Backends.A11Y -> if (!service().performGlobalAction(action)) {
                    throw err("UNAVAILABLE", "global action failed")
                } else Unit
                Backends.UIA -> { root("ui.global", JSONObject().put("action", name)); Unit }
                else -> {
                    val code = when (name) {
                        "back" -> 4
                        "home" -> 3
                        "recents" -> 187
                        "notifications" -> 83
                        else -> throw err("UNAVAILABLE", "shell cannot send " + name)
                    }
                    root("shell.exec", JSONObject().put("command", "input keyevent " + code))
                    Unit
                }
            }
        }.first

    private fun keyEnter(params: JSONObject): String {
        val h = service().rootInActiveWindow ?: throw err("UNAVAILABLE", "no active window")
        try {
            h.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } finally {
            h.recycle()
        }
        return if (prefer(params) == Backends.AUTO) Backends.A11Y else prefer(params)
    }

    /** 找到不被更高层窗口遮住的点击点；整块被盖住返回 null。 */
    private fun tapPoint(node: Node): Pair<Int, Int>? {
        val v = view ?: return null
        val b = node.bounds
        val blockers = v.snapshot.nodes.filter {
            it.id != node.id && it.z > node.z && it.has(Flags.VISIBLE) && it.bounds.covers(b)
        }
        if (blockers.isEmpty()) return Pair(b.cx, b.cy)
        val step = 8
        var y = b.t + b.h / 4
        while (y < b.b && y < b.t + b.h) {
            var x = b.l + b.w / 4
            while (x < b.r && x < b.l + b.w) {
                if (blockers.none { it.bounds.contains(x, y) }) return Pair(x, y)
                x += step
            }
            y += step
        }
        return null
    }

    // ---------------------------------------------------------------- 截图 / 状态

    private fun shot(params: JSONObject): JSONObject = locked {
        val started = SystemClock.uptimeMillis()
        val svc = service()
        val latch = java.util.concurrent.CountDownLatch(1)
        var bitmap: android.graphics.Bitmap? = null
        var failure: String? = null
        svc.takeScreenshot(android.view.Display.DEFAULT_DISPLAY,
            java.util.concurrent.Executor { it.run() },
            object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                    try {
                        val buffer = result.hardwareBuffer
                        bitmap = android.graphics.Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                        buffer.close()
                    } catch (t: Throwable) {
                        failure = t.message
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    failure = "error " + errorCode
                    latch.countDown()
                }
            })
        if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS) || bitmap == null) {
            throw err("TIMEOUT", "screenshot failed: " + (failure ?: "timeout"))
        }
        val src = bitmap!!
        val maxWidth = params.optInt("maxWidth", 720)
        val scale = if (src.width > maxWidth) maxWidth.toFloat() / src.width else 1f
        val out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val bytes = java.io.ByteArrayOutputStream()
        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, params.optInt("quality", 70), bytes)
        val data = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
        JSONObject().put("ok", true).put("via", "a11y").put("format", "jpeg")
            .put("width", out.width).put("height", out.height).put("bytes", bytes.size())
            .put("data", data).put("ms", SystemClock.uptimeMillis() - started)
    }

    private fun status(): JSONObject {
        val root = RootClient.available()
        val caps = JSONArray()
        for (c in Backends.table) {
            val usable = if (c.name == Backends.A11Y) true else root
            caps.put(JSONObject().put("name", c.name).put("readTree", c.readTree).put("pointer", c.pointer)
                .put("text", c.text).put("keys", c.keys).put("shot", c.shot)
                .put("exclusive", c.exclusive).put("needsRoot", c.needsRoot)
                .put("latencyMs", c.latencyMs).put("note", c.note)
                .put("available", usable)
                .put("reason", if (usable) "" else "root helper is not connected"))
        }
        val v = view
        return JSONObject().put("ok", true)
            .put("page", if (v == null) JSONObject.NULL else pageJson(v))
            .put("a11y", true)
            .put("root", root)
            .put("backends", caps)
            .put("conflicts", conflicts())
    }

    private fun conflicts(): JSONArray {
        val out = JSONArray()
        try {
            val ctx = service().applicationContext
            val enabled = android.provider.Settings.Secure.getString(ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            for (name in enabled.split(':')) {
                if (name.isEmpty() || name.startsWith("dev.dsh.agent/")) continue
                out.put(name)
            }
        } catch (t: Throwable) {
        }
        return out
    }

    // ---------------------------------------------------------------- 基础设施

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    private fun service(): AccessibilityService =
        AgentServer.accessibility() ?: throw err("UNAVAILABLE", "accessibility service is not connected")

    private fun current(params: JSONObject?): SnapshotView {
        val v = view
        if (v == null) return captureNow()
        val age = SystemClock.uptimeMillis() - v.snapshot.at
        if (age > STALE_MS || params?.optBoolean("fresh", false) == true) return captureNow()
        return v
    }

    private fun captureNow(): SnapshotView {
        val svc = service()
        val cap = Capture.capture(svc, g, UiClock.events)
        val sig = signature(cap.snapshot)
        if (sig != lastSignature) {
            g += 1
            lastSignature = sig
        }
        recycleHandles()
        handles = cap.handles
        val bound = refs.bind(cap.snapshot.copy(g = g))
        previous = view
        view = bound
        return bound
    }

    /** 页面指纹：只用来判断"这一代是否变化"，不参与 ref 绑定。 */
    private fun signature(s: Snapshot): String {
        val sb = StringBuilder(64 + s.nodes.size * 32)
        sb.append(s.pkg).append('|').append(s.activity).append('|').append(s.keyboard).append('|').append(s.nodes.size)
        for (n in s.nodes) {
            sb.append('|').append(n.cls.hashCode()).append(',').append(n.text.hashCode())
                .append(',').append(n.desc.hashCode()).append(',').append(n.bounds)
        }
        return sb.toString()
    }

    private fun recycleHandles() {
        for (h in handles.values) {
            try {
                h.recycle()
            } catch (t: Throwable) {
                // 已经失效的节点直接放弃
            }
        }
        handles = emptyMap()
    }

    private fun handle(node: Node): AccessibilityNodeInfo? =
        if (node.nativeId >= 0) handles[node.nativeId] else null

    private fun indexOfNative(v: SnapshotView, node: Node): Int {
        if (node.nativeId >= 0) {
            val i = v.snapshot.nodes.indexOfFirst { it.nativeId == node.nativeId }
            if (i >= 0) return i
        }
        return -1
    }

    private fun settle() {
        val start = SystemClock.uptimeMillis()
        try {
            Thread.sleep(24)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        while (SystemClock.uptimeMillis() - start < SETTLE_MAX_MS) {
            if (UiClock.quietMs() >= QUIET_MS) return
            try {
                Thread.sleep(16)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private class Target(val nodeId: Int, val ref: String?, val relocated: Boolean)

    private fun resolve(v: SnapshotView, params: JSONObject): Target {
        val ref = params.optString("ref", "")
        if (ref.isNotEmpty()) {
            val r = refs.resolve(v, ref)
            if (r.nodeId < 0) {
                val candidates = candidatesJson(r)
                if (r.candidates.isNotEmpty()) {
                    throw err("AMBIGUOUS_MATCH", "ref " + ref + " matches " + r.candidates.size + " candidates")
                        .detail("candidates", candidates)
                }
                throw err("NOT_FOUND", "ref " + ref + " is gone").detail("candidates", candidates)
            }
            return Target(r.nodeId, r.ref ?: ref, r.relocated)
        }
        if (params.has("where")) {
            val ids = Query.select(v, parseWhere(params.getJSONObject("where")))
            if (ids.isEmpty()) throw err("NOT_FOUND", "no node matches where")
            if (ids.size > 1) {
                val arr = JSONArray()
                for (id in ids.take(5)) arr.put(JSONObject().put("ref", v.refOf(id) ?: "").put("text", v.snapshot.node(id).label))
                throw err("AMBIGUOUS_MATCH", "where matches " + ids.size + " nodes").detail("candidates", arr)
            }
            return Target(ids[0], v.refOf(ids[0]), false)
        }
        throw err("BAD_REQUEST", "pass ref or where")
    }

    private fun candidatesJson(r: dev.dsh.agent.core.Resolution): JSONArray {
        val arr = JSONArray()
        for (c in r.candidates.take(5)) {
            arr.put(JSONObject().put("ref", c.ref ?: "").put("score", Math.round(c.score * 100) / 100.0).put("why", c.why))
        }
        return arr
    }

    private fun parseWhere(obj: JSONObject): Where =
        Where.parse(Json.parseToJsonElement(obj.toString()).jsonObject)

    private fun pageJson(v: SnapshotView): JSONObject = JSONObject()
        .put("g", v.snapshot.g)
        .put("seq", v.snapshot.seq)
        .put("pkg", v.snapshot.pkg)
        .put("activity", v.snapshot.activity)
        .put("kb", v.snapshot.keyboard)
        .put("nodes", v.snapshot.size)

    private fun changeJson(c: Change): JSONObject {
        val texts = JSONArray()
        for (item in c.changed) {
            if (item.field == "text" && texts.length() < 5) texts.put(item.after)
        }
        return JSONObject()
            .put("added", c.added.size)
            .put("removed", c.removed.size)
            .put("changed", c.changed.size)
            .put("text", texts)
            .put("stable", c.stable)
            .put("quietMs", c.quietMs)
    }

    private fun flagNames(n: Node): JSONArray {
        val arr = JSONArray()
        if (n.clickable) arr.put("clickable")
        if (n.has(Flags.EDITABLE)) arr.put("editable")
        if (n.has(Flags.SCROLLABLE)) arr.put("scrollable")
        if (n.has(Flags.CHECKED)) arr.put("checked")
        if (n.has(Flags.FOCUSED)) arr.put("focused")
        if (n.has(Flags.SELECTED)) arr.put("selected")
        if (!n.enabled) arr.put("disabled")
        if (n.has(Flags.PASSWORD)) arr.put("password")
        return arr
    }

    private fun ints(vararg values: Int): JSONArray {
        val arr = JSONArray()
        for (v in values) arr.put(v)
        return arr
    }

    private fun err(code: String, message: String): AgentError = AgentError(code, message)
}
