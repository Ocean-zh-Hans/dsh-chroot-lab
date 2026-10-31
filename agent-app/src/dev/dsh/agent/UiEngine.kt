package dev.dsh.agent

import android.accessibilityservice.AccessibilityService
import android.os.Build
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

    private var refs = RefStore()
    private val lock = ReentrantLock()
    private var g = 0L
    private var lastSignature = ""
    private var lastEventSeq = -1L
    private var view: SnapshotView? = null
    private var previous: SnapshotView? = null
    private var handles: Map<Long, AccessibilityNodeInfo> = emptyMap()

    companion object {
        private val INSTANCE = UiEngine()
        private const val QUIET_MS = 120L
        private const val SETTLE_MAX_MS = 1500L
        private const val STALE_MS = 30_000L
        private val ROUTED = setOf(
            "ui.view", "ui.nodes", "ui.diff", "ui.expect", "ui.act", "ui.batch", "ui.fill",
            "ui.gesture", "ui.scrollUntil", "ui.scroll", "ui.wait", "ui.refresh", "ui.invalidate",
            "ui.windows", "ui.press", "ui.ime", "ui.shot", "sys.status",
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
        "ui.expect" -> expect(params)
        "ui.act" -> act(params)
        "ui.batch" -> batch(params)
        "ui.fill" -> fill(params)
        "ui.gesture" -> gesture(params)
        "ui.scrollUntil" -> scrollUntil(params)
        "ui.scroll" -> scroll(params)
        "ui.ime" -> ime(params)
        "ui.wait" -> waitFor(params)
        "ui.windows" -> locked { windowsJson() }
        "ui.refresh" -> locked {
            val v = captureNow()
            val page = pageJson(v)
            page.put("ok", true).put("uiEffect", "none").put("page", JSONObject(page.toString()))
        }
        "ui.invalidate" -> invalidate()
        "ui.press" -> press(params)
        "ui.shot" -> shot(params)
        "sys.status" -> status()
        else -> throw err("BAD_REQUEST", "unknown method " + method)
    }

    /**
     * 外部 shell 动作后主动作废 APK 的页面缓存：旧 ref 一律失效，下一次观察重新绑定。
     * DSH 的 DeviceSession 会把 uiEffect=possible/certain 的 shell 动作自动映射到这里。
     */
    private fun invalidate(): JSONObject = locked {
        g += 1
        lastSignature = ""
        recycleHandles()
        view = null
        previous = null
        refs = RefStore()
        JSONObject().put("ok", true).put("invalidated", true).put("uiEffect", "none").put("g", g)
    }

    // ---------------------------------------------------------------- 读

    private fun view(params: JSONObject): JSONObject = locked {
        val v = current(params)
        val expected = params.optString("package", "")
        if (expected.isNotEmpty() && v.snapshot.pkg != expected) {
            throw err("WRONG_PACKAGE", "foreground package changed").detail("expected", expected).detail("actual", v.snapshot.pkg)
        }
        val opts = ViewOptions(
            mode = params.optString("mode", "auto"),
            maxChars = params.optInt("maxChars", 2000),
            maxDepth = params.optInt("maxDepth", 3),
            includeBounds = params.optBoolean("includeBounds", true),
        )
        val rendered = ViewRenderer.render(v, opts)
        val out = JSONObject()
            .put("ok", true)
            .put("text", rendered.text)
            .put("shown", rendered.shown)
            .put("omitted", rendered.omitted)
            .put("truncated", rendered.truncated)
            .put("page", pageJson(v))
        if (params.optBoolean("windows", false)) out.put("windows", windowsJson())
        if (params.optBoolean("screenshot", false) || params.optBoolean("shot", false)) {
            out.put("shot", captureJpeg(params))
        }
        out
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

    private fun waitFor(params: JSONObject): JSONObject {
        val spec = params.optJSONObject("for") ?: JSONObject().put("idle", true)
        val timeout = params.optLong("timeoutMs", 8000L).coerceIn(500L, 60_000L)
        val started = SystemClock.uptimeMillis()
        while (true) {
            // 只在一次 capture/判断期间持锁；sleep 不占锁，否则一个 ui.wait 会阻塞
            // 所有读请求和人类暂停/回执等旁路调用。
            val checked = locked {
                val v = captureNow()
                Pair(satisfied(v, spec), v)
            }
            if (checked.first) {
                return JSONObject().put("ok", true)
                    .put("waitedMs", SystemClock.uptimeMillis() - started)
                    .put("page", pageJson(checked.second))
            }
            if (SystemClock.uptimeMillis() - started > timeout) {
                throw err("TIMEOUT", "condition not met in " + timeout + "ms")
                    .detail("page", pageJson(checked.second))
            }
            Thread.sleep(100)
        }
    }

    /** 断言：wait 的 timeout=0 版本；失败时给候选，绝不等待。 */
    private fun expect(params: JSONObject): JSONObject = locked {
        val started = SystemClock.uptimeMillis()
        val spec = params.optJSONObject("for") ?: params
        if (spec.length() == 0) throw err("BAD_REQUEST", "expectation is empty")
        val v = captureNow()
        if (!satisfied(v, spec)) {
            val error = err("NOT_FOUND", "expectation not met")
                .detail("expect", JSONObject(spec.toString()))
                .detail("page", pageJson(v))
            val text = spec.optString("text", "")
            if (text.isNotEmpty()) {
                val candidates = candidatesFor(v, text)
                if (candidates.length() > 0) error.detail("candidates", candidates)
            }
            throw error
        }
        JSONObject().put("ok", true).put("matched", true)
            .put("page", pageJson(v)).put("ms", SystemClock.uptimeMillis() - started)
    }

    private fun candidatesFor(v: SnapshotView, text: String): JSONArray {
        val needle = text.lowercase()
        val arr = JSONArray()
        for (id in v.snapshot.walk()) {
            val n = v.snapshot.node(id)
            if (!n.has(Flags.VISIBLE)) continue
            val label = n.label
            if (label.isEmpty()) continue
            val hay = label.lowercase()
            val score = when {
                hay == needle -> 1.0
                hay.contains(needle) -> 0.8
                needle.length >= 2 && hay.contains(needle.substring(0, 2)) -> 0.45
                else -> 0.0
            }
            if (score <= 0.0) continue
            arr.put(JSONObject().put("ref", v.refOf(id) ?: "").put("text", label)
                .put("score", score).put("why", "visible text"))
            if (arr.length() >= 5) return arr
        }
        if (arr.length() == 0) {
            for (id in v.snapshot.walk()) {
                val n = v.snapshot.node(id)
                if (!n.has(Flags.VISIBLE) || n.label.isEmpty()) continue
                arr.put(JSONObject().put("ref", v.refOf(id) ?: "").put("text", n.label)
                    .put("score", 0.0).put("why", "visible candidate"))
                if (arr.length() >= 5) break
            }
        }
        return arr
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
        val present = spec.optString("ref", "")
        if (present.isNotEmpty()) {
            val r = refs.resolve(v, present)
            if (r.nodeId < 0) return false
        }
        // 跨后端等待：shell 执行 am start 后，DSH 只需等 package/activity，不用猜测 sleep 多久。
        val pkg = spec.optString("package", "")
        if (pkg.isNotEmpty() && v.snapshot.pkg != pkg) return false
        val activity = spec.optString("activity", "")
        if (activity.isNotEmpty() && !v.snapshot.activity.endsWith(activity)) return false
        if (spec.has("keyboard") && v.snapshot.keyboard != spec.optBoolean("keyboard")) return false
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
        if (op == "back" || op == "home" || op == "recents" || op == "notifications") {
            return press(JSONObject(params.toString()).put("key", op))
        }
        if (op == "press") return press(params)
        if (op == "global") return globalAct(params, started)
        if (op == "swipe") return gestureAct(params, started, drag = false)
        if (op == "drag") return gestureAct(params, started, drag = true)
        if (op == "dismiss" && !hasTarget(params)) {
            val before = currentForWrite(params)
            val via = global(AccessibilityService.GLOBAL_ACTION_BACK, "back", params)
            return actionResult(before, started, "dismiss", via, null, false)
        }
        val v = currentForWrite(params)
        val target = resolve(v, params)
        val node = v.snapshot.node(target.nodeId)
        val via = exec(v, op, node, params)
        return actionResult(v, started, op, via, target.ref, target.relocated, node)
    }

    private fun hasTarget(params: JSONObject): Boolean = params.has("ref") || params.has("where")

    private fun actionResult(
        before: SnapshotView,
        started: Long,
        op: String,
        via: String,
        ref: String?,
        relocated: Boolean,
        node: Node? = null,
    ): JSONObject {
        settle()
        val after = captureNow()
        val out = JSONObject()
            .put("ok", true)
            .put("op", op)
            .put("uiEffect", uiEffectFor(op))
            .put("via", via)
            .put("ref", ref ?: "")
            .put("relocated", relocated)
            .put("page", pageJson(after))
            .put("change", changeJson(Diff.compute(before, after, UiClock.quietMs(), true)))
            .put("ms", SystemClock.uptimeMillis() - started)
        if (node != null) {
            val next = indexOfNative(after, node)
            if (next >= 0) after.refOf(next)?.let { out.put("ref", it) }
        }
        return out
    }

    private fun globalAct(params: JSONObject, started: Long): JSONObject {
        val name = params.optString("action", params.optString("key", ""))
        if (name.isEmpty()) throw err("BAD_REQUEST", "action is required")
        val before = currentForWrite(params)
        val via = when (name) {
            "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "back", params)
            "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "home", params)
            "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "recents", params)
            "notifications" -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "notifications", params)
            else -> throw err("BAD_REQUEST", "global action must be back|home|recents|notifications")
        }
        return actionResult(before, started, "global", via, null, false)
    }

    private fun gestureAct(params: JSONObject, started: Long, drag: Boolean): JSONObject {
        val before = currentForWrite(params)
        val screen = screenRect()
        var x1 = params.optInt("x1", Int.MIN_VALUE)
        var y1 = params.optInt("y1", Int.MIN_VALUE)
        var x2 = params.optInt("x2", Int.MIN_VALUE)
        var y2 = params.optInt("y2", Int.MIN_VALUE)
        if (x1 == Int.MIN_VALUE || y1 == Int.MIN_VALUE) {
            if (!hasTarget(params)) throw err("BAD_REQUEST", "pass x1/y1/x2/y2 or ref/where")
            val target = resolve(before, params)
            val b = before.snapshot.node(target.nodeId).bounds
            x1 = b.cx
            y1 = b.cy
            if (x2 == Int.MIN_VALUE) x2 = x1 + params.optInt("dx", 0)
            if (y2 == Int.MIN_VALUE) y2 = y1 + params.optInt("dy", 0)
        }
        if (x2 == Int.MIN_VALUE || y2 == Int.MIN_VALUE) {
            throw err("BAD_REQUEST", "x2/y2 or dx/dy is required")
        }
        if (x1 !in 0 until screen.first || y1 !in 0 until screen.second ||
            x2 !in 0 until screen.first || y2 !in 0 until screen.second) {
            throw err("BAD_REQUEST", "gesture point is outside the screen")
        }
        if (x1 == x2 && y1 == y2) throw err("BAD_REQUEST", "gesture start and end are identical")
        val duration = params.optLong("durationMs", 300L).coerceIn(50L, 10_000L)
        val hold = params.optLong("holdMs", 500L).coerceIn(50L, 10_000L)
        val ok = if (drag) {
            Gestures.drag(service(), x1, y1, x2, y2, hold, duration)
        } else {
            Gestures.swipe(service(), x1, y1, x2, y2, duration)
        }
        if (!ok) throw err("UNAVAILABLE", "gesture was rejected or cancelled")
        return actionResult(before, started, if (drag) "drag" else "swipe", "a11y", null, false)
    }

    /**
     * 多指/多路径手势。支持两种写法：
     *   1) paths: [{points:[{x,y}...], startMs, durationMs}, ...]（最多 20 条）
     *   2) preset: pinchIn|pinchOut|pinch|twoFingerSwipe + center/radius/scale/direction
     * points 也可以是 [x,y] 数组；normalized=true 时按屏幕比例解释。
     */
    private fun gesture(params: JSONObject): JSONObject = locked {
        val started = SystemClock.uptimeMillis()
        val before = currentForWrite(params)
        val strokes = parseStrokes(params)
        if (strokes.isEmpty()) throw err("BAD_REQUEST", "gesture has no strokes")
        if (!Gestures.gesture(service(), strokes)) {
            throw err("UNAVAILABLE", "gesture was rejected or cancelled")
        }
        return actionResult(before, started, "gesture", "a11y", null, false)
    }

    private fun parseStrokes(params: JSONObject): List<Gestures.StrokeSpec> {
        val preset = params.optString("preset", "")
        if (preset.isNotEmpty()) return presetStrokes(preset, params)
        val raw = params.optJSONArray("paths")
            ?: params.optJSONArray("points")?.let { JSONArray().put(JSONObject().put("points", it)) }
            ?: throw err("BAD_REQUEST", "paths or preset is required")
        if (raw.length() < 1 || raw.length() > 20) {
            throw err("BAD_REQUEST", "gesture needs 1 to 20 paths")
        }
        val (w, h) = screenRect()
        val normalized = params.optBoolean("normalized", false)
        val out = ArrayList<Gestures.StrokeSpec>(raw.length())
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: throw err("BAD_REQUEST", "path " + i + " must be an object")
            val points = item.optJSONArray("points") ?: throw err("BAD_REQUEST", "path " + i + " has no points")
            if (points.length() < 2 || points.length() > 100) {
                throw err("BAD_REQUEST", "path " + i + " needs 2 to 100 points")
            }
            val path = ArrayList<Pair<Float, Float>>(points.length())
            for (j in 0 until points.length()) {
                val obj = points.optJSONObject(j)
                val x: Double
                val y: Double
                if (obj != null) {
                    x = obj.optDouble("x", Double.NaN)
                    y = obj.optDouble("y", Double.NaN)
                } else {
                    val pair = points.optJSONArray(j) ?: throw err("BAD_REQUEST", "point " + j + " is invalid")
                    if (pair.length() < 2) throw err("BAD_REQUEST", "point " + j + " is invalid")
                    x = pair.optDouble(0, Double.NaN)
                    y = pair.optDouble(1, Double.NaN)
                }
                if (!x.isFinite() || !y.isFinite()) throw err("BAD_REQUEST", "point " + j + " is invalid")
                val px = if (normalized) x * w else x
                val py = if (normalized) y * h else y
                if (px < 0 || py < 0 || px > w || py > h) {
                    throw err("BAD_REQUEST", "point " + j + " is outside the screen")
                }
                path.add(px.toFloat() to py.toFloat())
            }
            out.add(Gestures.StrokeSpec(
                points = path,
                startMs = item.optLong("startMs", 0L).coerceIn(0L, 30_000L),
                durationMs = item.optLong("durationMs", 300L).coerceIn(1L, 10_000L),
            ))
        }
        return out
    }

    private fun presetStrokes(name: String, params: JSONObject): List<Gestures.StrokeSpec> {
        val (w, h) = screenRect()
        val center = params.optJSONObject("center")
        val cx = center?.optDouble("x", w / 2.0) ?: params.optDouble("cx", w / 2.0)
        val cy = center?.optDouble("y", h / 2.0) ?: params.optDouble("cy", h / 2.0)
        if (cx < 0 || cy < 0 || cx > w || cy > h) throw err("BAD_REQUEST", "center is outside the screen")
        fun point(x: Double, y: Double): Pair<Float, Float> = Pair(
            x.coerceIn(0.0, (w - 1).toDouble()).toFloat(),
            y.coerceIn(0.0, (h - 1).toDouble()).toFloat(),
        )
        return when (name.lowercase()) {
            "pinch", "pinchout", "pinchin" -> {
                val defaultScale = if (name.equals("pinchIn", true)) 0.5 else 2.0
                val scale = params.optDouble("scale", defaultScale)
                if (!scale.isFinite() || scale < 0.05 || scale > 5.0) {
                    throw err("BAD_REQUEST", "scale must be between 0.05 and 5")
                }
                val radius = params.optDouble("radius", minOf(w, h) * 0.18)
                    .coerceIn(20.0, minOf(w, h) / 2.0)
                val inner = (radius * scale).coerceIn(2.0, maxOf(w, h).toDouble())
                val duration = params.optLong("durationMs", 600L).coerceIn(50L, 10_000L)
                listOf(
                    Gestures.StrokeSpec(listOf(point(cx - radius, cy), point(cx - inner, cy)), 0, duration),
                    Gestures.StrokeSpec(listOf(point(cx + radius, cy), point(cx + inner, cy)), 0, duration),
                )
            }
            "twofingerswipe" -> {
                val direction = params.optString("direction", "up").lowercase()
                val distance = params.optInt("distance", (if (direction == "up" || direction == "down") h else w) * 35 / 100)
                    .coerceIn(20, maxOf(w, h))
                val spread = params.optInt("spread", minOf(w, h) * 12 / 100)
                    .coerceIn(20, maxOf(20, minOf(w, h) / 2))
                val dx: Int
                val dy: Int
                when (direction) {
                    "up" -> { dx = 0; dy = -distance }
                    "down" -> { dx = 0; dy = distance }
                    "left" -> { dx = -distance; dy = 0 }
                    "right" -> { dx = distance; dy = 0 }
                    else -> throw err("BAD_REQUEST", "direction must be up|down|left|right")
                }
                val duration = params.optLong("durationMs", 400L).coerceIn(50L, 10_000L)
                if (dx != 0) {
                    listOf(
                        Gestures.StrokeSpec(listOf(point(cx, cy - spread), point(cx + dx, cy - spread)), 0, duration),
                        Gestures.StrokeSpec(listOf(point(cx, cy + spread), point(cx + dx, cy + spread)), 0, duration),
                    )
                } else {
                    listOf(
                        Gestures.StrokeSpec(listOf(point(cx - spread, cy), point(cx - spread, cy + dy)), 0, duration),
                        Gestures.StrokeSpec(listOf(point(cx + spread, cy), point(cx + spread, cy + dy)), 0, duration),
                    )
                }
            }
            else -> throw err("BAD_REQUEST", "unknown preset " + name)
        }
    }

    private fun screenRect(): Pair<Int, Int> {
        val screen = view?.snapshot?.screen
        if (screen != null && screen.w > 0 && screen.h > 0) return screen.w to screen.h
        val metrics = service().resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun batch(params: JSONObject): JSONObject = locked {
        val ops = params.optJSONArray("ops") ?: throw err("BAD_REQUEST", "ops is required")
        if (ops.length() == 0) throw err("BAD_REQUEST", "ops is empty")
        if (ops.length() > 20) throw err("BAD_REQUEST", "at most 20 ops per batch")
        val started = SystemClock.uptimeMillis()
        val results = JSONArray()
        var v = currentForWrite(params)
        val batchBefore = view
        for (i in 0 until ops.length()) {
            val op = ops.getJSONObject(i)
            if (!op.has("page") && params.has("page")) op.put("page", params.getJSONObject("page"))
            val name = op.optString("op", "")
            if (name == "press") {
                if (!op.has("page")) op.put("page", pageJson(v))
                val pressed = try {
                    press(op)
                } catch (e: AgentError) {
                    e.detail("index", i)
                    throw e
                }
                results.put(JSONObject().put("index", i).put("op", name).put("ref", "")
                    .put("relocated", false).put("via", pressed.optString("via", "a11y"))
                    .put("uiEffect", uiEffectFor(name)))
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
            results.put(JSONObject().put("index", i).put("op", name).put("ref", target.ref ?: "").put("relocated", target.relocated).put("via", via)
                .put("uiEffect", uiEffectFor(name)))
            if (i < ops.length() - 1) {
                settle()
                v = captureNow()
            }
        }
        settle()
        val after = captureNow()
        val change = Diff.compute(batchBefore, after, UiClock.quietMs(), true)
        JSONObject().put("ok", true).put("done", results.length()).put("results", results)
            .put("uiEffect", "certain")
            .put("page", pageJson(after)).put("change", changeJson(change))
            .put("ms", SystemClock.uptimeMillis() - started)
    }

    private fun fill(params: JSONObject): JSONObject = locked {
        val started = SystemClock.uptimeMillis()
        val v = currentForWrite(params)
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
            .put("uiEffect", "certain")
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
        var v = currentForWrite(params)
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
            .put("uiEffect", "certain")
            .put("page", pageJson(v)).put("ms", SystemClock.uptimeMillis() - started)
    }

    private fun scroll(params: JSONObject): JSONObject = locked {
        val started = SystemClock.uptimeMillis()
        val v = currentForWrite(params)
        val direction = params.optString("direction", "forward")
        val target = if (params.has("ref") || params.has("where")) resolve(v, params) else null
        val node = target?.let { v.snapshot.node(it.nodeId) }
            ?: v.snapshot.nodes.firstOrNull { it.scrollable && it.has(Flags.VISIBLE) }
            ?: throw err("NOT_FOUND", "no scrollable container")
        val via = scrollNode(node, direction, params)
        settle()
        val after = captureNow()
        JSONObject().put("ok", true).put("ref", target?.ref ?: "").put("direction", direction).put("via", via)
            .put("uiEffect", "certain")
            .put("page", pageJson(after))
            .put("change", changeJson(Diff.compute(v, after, UiClock.quietMs(), true)))
            .put("ms", SystemClock.uptimeMillis() - started)
    }

    private fun ime(params: JSONObject): JSONObject = locked {
        val started = SystemClock.uptimeMillis()
        val action = params.optString("op", "set")
        val uiEffect = if (action == "show" || action == "hide") "possible" else "certain"
        val v = currentForWrite(params)
        when (action) {
            "show" -> {
                val editable = v.snapshot.nodes.firstOrNull { it.editable && it.has(Flags.VISIBLE) }
                    ?: throw err("NOT_FOUND", "no editable field on screen")
                val h = handle(editable) ?: throw err("STALE", "node handle is gone")
                h.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                settle()
                JSONObject().put("ok", true).put("op", action).put("via", "a11y").put("uiEffect", uiEffect)
                    .put("page", pageJson(captureNow())).put("ms", SystemClock.uptimeMillis() - started)
            }
            "hide" -> {
                global(AccessibilityService.GLOBAL_ACTION_BACK, "back", params)
                settle()
                JSONObject().put("ok", true).put("op", action).put("via", "a11y").put("uiEffect", uiEffect)
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
                    .put("via", via).put("uiEffect", uiEffect).put("page", pageJson(after))
                    .put("change", changeJson(Diff.compute(v, after, UiClock.quietMs(), true)))
                    .put("ms", SystemClock.uptimeMillis() - started)
            }
            else -> throw err("BAD_REQUEST", "op must be set|clear|submit|show|hide")
        }
    }

    private fun press(params: JSONObject): JSONObject = locked {
        val before = currentForWrite(params)
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
        JSONObject().put("ok", true).put("op", key).put("key", key).put("via", via)
            .put("uiEffect", "certain").put("page", pageJson(after))
            .put("change", changeJson(Diff.compute(before, after, UiClock.quietMs(), true)))
    }

    // ---------------------------------------------------------------- 动作实现

    private fun exec(v: SnapshotView, op: String, node: Node, params: JSONObject): String {
        verify(v, node)
        return when (op) {
        "tap", "click" -> click(node, params)
        "doubleTap" -> doubleClick(node, params)
        "longClick" -> longClick(node, params)
        "setText" -> setText(node, params.optString("text", ""), params.optBoolean("append", false), params)
        "clearText" -> setText(node, "", false, params)
        "focus" -> {
            val h = handle(node) ?: throw err("STALE", "node handle is gone")
            if (!h.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) throw err("UNAVAILABLE", "focus was rejected")
            Backends.A11Y
        }
        "scroll" -> scrollNode(node, params.optString("direction", "forward"), params)
        "scrollIntoView" -> nodeAction(node, AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id, "scrollIntoView")
        "setSelection" -> setSelection(node, params)
        "copy" -> nodeAction(node, AccessibilityNodeInfo.ACTION_COPY, "copy")
        "cut" -> nodeAction(node, AccessibilityNodeInfo.ACTION_CUT, "cut")
        "paste" -> nodeAction(node, AccessibilityNodeInfo.ACTION_PASTE, "paste")
        "dismiss" -> nodeAction(node, AccessibilityNodeInfo.ACTION_DISMISS, "dismiss")
        "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "back", params)
        "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "home", params)
        "recents" -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "recents", params)
        else -> throw err("BAD_REQUEST", "unknown op " + op)
        }
    }

    private fun verify(v: SnapshotView, node: Node) {
        if (!node.has(Flags.VISIBLE)) throw err("NOT_VISIBLE", "target is not visible").detail("ref", v.refOf(node.id) ?: "")
        if (!node.enabled) throw err("DISABLED", "target is disabled").detail("ref", v.refOf(node.id) ?: "")
        if (node.bounds.isEmpty || !node.bounds.intersects(v.snapshot.screen)) {
            throw err("OUT_OF_BOUNDS", "target bounds are outside the screen").detail("bounds", node.bounds.toString())
        }
        if (v.snapshot.pkg.isNotEmpty() && node.pkg.isNotEmpty() && node.pkg != v.snapshot.pkg &&
            node.window != v.snapshot.activeWindow) {
            throw err("WRONG_PACKAGE", "target belongs to another window/package")
                .detail("expected", v.snapshot.pkg).detail("actual", node.pkg)
        }
    }

    private fun nodeAction(node: Node, action: Int, name: String): String {
        val h = handle(node) ?: throw err("STALE", "node handle is gone")
        if (!h.isEnabled) throw err("DISABLED", "target is disabled")
        if (!h.performAction(action)) throw err("UNAVAILABLE", name + " was rejected")
        return Backends.A11Y
    }

    private fun setSelection(node: Node, params: JSONObject): String {
        val h = handle(node) ?: throw err("STALE", "node handle is gone")
        if (!h.isEditable) throw err("NOT_EDITABLE", "target is not editable")
        var start = params.optInt("start", 0)
        var end = params.optInt("end", start)
        if (start < 0 || end < 0) throw err("BAD_REQUEST", "selection must be non-negative")
        if (start > end) { val t = start; start = end; end = t }
        val bundle = Bundle()
        bundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        bundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        if (!h.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, bundle)) {
            throw err("UNAVAILABLE", "setSelection was rejected")
        }
        return Backends.A11Y
    }

    private fun doubleClick(node: Node, params: JSONObject): String {
        if (!node.enabled) throw err("DISABLED", "target is disabled")
        val p = tapPoint(node) ?: throw err("BLOCKED", "target is covered by another window")
        if (!Gestures.doubleTap(service(), p.first, p.second, params.optLong("gapMs", 100L).coerceIn(40L, 400L))) {
            throw err("UNAVAILABLE", "double tap gesture failed")
        }
        return Backends.A11Y
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
        // Shell fallback is internal to the engine; RootHelper still requires its confirm fence.
        if (method == "shell.exec") params.put("confirm", "YES")
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
        val chain = Backends.chain("press", prefer(params))
        if (chain.isEmpty()) throw err("BAD_REQUEST", "no backend supports press")
        var last: AgentError? = null
        for (backend in chain) {
            try {
                when (backend) {
                    Backends.A11Y -> {
                        val root = service().rootInActiveWindow
                            ?: throw err("UNAVAILABLE", "no active window")
                        val focus = try {
                            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        } catch (_: Throwable) {
                            null
                        }
                        val target = focus ?: root
                        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                        } else false
                        if (focus != null && focus !== root) focus.recycle()
                        root.recycle()
                        if (!ok) throw err("UNAVAILABLE", "a11y cannot send ENTER")
                    }
                    else -> root("ui.key", JSONObject().put("key", "KEYCODE_ENTER"))
                }
                return backend
            } catch (e: AgentError) {
                last = e
            } catch (e: Exception) {
                last = err("INTERNAL", backend + ": " + (e.message ?: e.javaClass.simpleName))
            }
        }
        throw last ?: err("UNAVAILABLE", "ENTER was rejected")
    }

    /** 找到不被遮盖的点击点。同窗口按绘制顺序（id 越大越晚绘制）判断，跨窗口按 z。 */
    private fun tapPoint(node: Node): Pair<Int, Int>? {
        val v = view ?: return null
        val b = node.bounds
        if (b.isEmpty) return null
        val overlays = v.snapshot.nodes.filter { other ->
            if (other.id == node.id || !other.has(Flags.VISIBLE) || !other.bounds.intersects(b)) return@filter false
            if (other.window == node.window) {
                other.id > node.id && !isDescendant(v.snapshot, other.id, node.id)
            } else {
                other.z > node.z
            }
        }
        if (overlays.none { it.bounds.contains(b.cx, b.cy) }) return Pair(b.cx, b.cy)
        val step = 8
        var y = b.t + b.h / 4
        while (y < b.b) {
            var x = b.l + b.w / 4
            while (x < b.r) {
                if (overlays.none { it.bounds.contains(x, y) }) return Pair(x, y)
                x += step
            }
            y += step
        }
        return null
    }

    private fun isDescendant(snapshot: dev.dsh.agent.core.Snapshot, childId: Int, parentId: Int): Boolean {
        var current = snapshot.node(childId).parent
        var guard = 0
        while (current >= 0 && guard++ < 128) {
            if (current == parentId) return true
            current = snapshot.node(current).parent
        }
        return false
    }

    // ---------------------------------------------------------------- 截图 / 状态

    private fun shot(params: JSONObject): JSONObject = locked {
        val started = SystemClock.uptimeMillis()
        val payload = captureJpeg(params)
        payload.put("ok", true).put("via", "a11y")
            .put("ms", SystemClock.uptimeMillis() - started)
    }

    /** 截图并可选标注 ref；ui.shot 与 ui.view(screenshot:true) 共用，保证同一套缩放/编码参数。 */
    private fun captureJpeg(params: JSONObject): JSONObject {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw err("UNSUPPORTED", "accessibility screenshot requires Android 11+")
                .detail("hint", "use the root helper's shot method on Android 10")
        }
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
        var out = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        var annotated = false
        if (params.optBoolean("annotate", false) || params.optString("ref", "").isNotEmpty()) {
            val marked = annotate(out, params)
            if (marked !== out) {
                out = marked
                annotated = true
            }
        }
        val bytes = java.io.ByteArrayOutputStream()
        out.compress(android.graphics.Bitmap.CompressFormat.JPEG, params.optInt("quality", 70), bytes)
        val data = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
        return JSONObject().put("format", "jpeg")
            .put("width", out.width).put("height", out.height).put("bytes", bytes.size())
            .put("annotated", annotated).put("data", data)
    }

    /** 在截图上画当前 view 的 ref 框；ref 参数只画一个，否则画可见节点。 */
    private fun annotate(src: android.graphics.Bitmap, params: JSONObject): android.graphics.Bitmap {
        val v = view ?: return src
        val dst = try { src.copy(android.graphics.Bitmap.Config.ARGB_8888, true) } catch (_: Throwable) { null } ?: return src
        val canvas = android.graphics.Canvas(dst)
        val sx = dst.width.toFloat() / v.snapshot.screen.w.coerceAtLeast(1)
        val sy = dst.height.toFloat() / v.snapshot.screen.h.coerceAtLeast(1)
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = (dst.width / 360f).coerceAtLeast(2f)
            color = android.graphics.Color.RED
        }
        val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (dst.width / 48f).coerceAtLeast(14f)
            color = android.graphics.Color.WHITE
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val labelBg = android.graphics.Paint().apply { color = 0xCC000000.toInt() }
        val wanted = params.optString("ref", "")
        val ids = if (wanted.isNotEmpty()) {
            val r = refs.resolve(v, wanted)
            if (r.nodeId < 0) emptyList() else listOf(r.nodeId)
        } else {
            v.snapshot.walk().filter { id ->
                val n = v.snapshot.node(id)
                n.has(Flags.VISIBLE) && n.bounds.w > 0 && n.bounds.h > 0 && v.refOf(id) != null
            }
        }
        var count = 0
        val max = params.optInt("maxBoxes", 40).coerceIn(1, 100)
        for (id in ids) {
            if (count++ >= max) break
            val n = v.snapshot.node(id)
            val left = n.bounds.l * sx
            val top = n.bounds.t * sy
            val right = n.bounds.r * sx
            val bottom = n.bounds.b * sy
            if (right <= left || bottom <= top) continue
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), stroke)
            val ref = v.refOf(id) ?: continue
            val ty = (top - 3f).coerceAtLeast(label.textSize)
            val tw = label.measureText(ref)
            canvas.drawRect(left, ty - label.textSize, left + tw + 6f, ty + 2f, labelBg)
            canvas.drawText(ref, left + 3f, ty - 1f, label)
        }
        return dst
    }

    /**
     * 当前窗口栈。用于识别系统弹窗、IME、分屏和遮挡；不替代 ui.view 的节点树。
     * 每个窗口带 id/layer/type/bounds/package/active/focused，按 layer 从底到顶。
     */
    private fun windowsJson(): JSONObject {
        val wins = try {
            service().windows.orEmpty().sortedBy { it.layer }
        } catch (_: Exception) {
            emptyList()
        }
        val arr = JSONArray()
        for (w in wins) {
            val root = try { w.root } catch (_: Exception) { null }
            val b = android.graphics.Rect()
            w.getBoundsInScreen(b)
            arr.put(JSONObject()
                .put("id", w.id)
                .put("layer", w.layer)
                .put("type", windowType(w.type))
                .put("bounds", "${b.left},${b.top},${b.width()},${b.height()}")
                .put("package", root?.packageName?.toString() ?: "")
                .put("class", root?.className?.toString() ?: "")
                .put("active", w.isActive)
                .put("focused", w.isFocused)
                .put("accessibilityFocused", w.isAccessibilityFocused))
            try { root?.recycle() } catch (_: Exception) {}
        }
        return JSONObject().put("ok", true).put("uiEffect", "none")
            .put("count", arr.length()).put("windows", arr)
    }

    private fun windowType(type: Int): String = when (type) {
        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split"
        else -> "type_" + type
    }

    private fun status(): JSONObject {
        val root = RootClient.available()
        val a11y = AgentServer.accessibility() != null
        val caps = JSONArray()
        for (c in Backends.table) {
            val usable = if (c.name == Backends.A11Y) a11y else root
            caps.put(JSONObject().put("name", c.name).put("readTree", c.readTree).put("pointer", c.pointer)
                .put("text", c.text).put("keys", c.keys).put("shot", c.shot)
                .put("exclusive", c.exclusive).put("needsRoot", c.needsRoot)
                .put("latencyMs", c.latencyMs).put("note", c.note)
                .put("available", usable)
                .put("reason", when {
                    usable -> ""
                    c.name == Backends.A11Y -> "accessibility service is not connected"
                    else -> "root helper is not connected"
                }))
        }
        val v = view
        return JSONObject().put("ok", true)
            .put("version", AgentMethods.VERSION)
            .put("api", AgentMethods.API_VERSION)
            .put("buildTime", AgentMethods.BUILD_TIME)
            .put("port", 17321)
            .put("g", if (v == null) g else v.snapshot.g)
            .put("seq", v?.snapshot?.seq ?: 0)
            .put("page", if (v == null) JSONObject.NULL else pageJson(v))
            .put("foreground", JSONObject().put("package", AgentServer.events.foreground())
                .put("activity", v?.snapshot?.activity ?: ""))
            .put("a11y", AgentServer.accessibility() != null)
            .put("root", root)
            .put("notification", NotificationRelay.connected())
            .put("paused", PresenceState.isPaused())
            .put("phase", PresenceState.phase().name.lowercase())
            .put("phaseLabel", PresenceState.phase().label)
            .put("clipboard", true)
            .put("platform", PlatformProfile.status(AgentServer.context() ?: service().applicationContext))
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
        if (v == null || UiClock.events != lastEventSeq) return captureNow()
        val age = SystemClock.uptimeMillis() - v.snapshot.at
        if (age > STALE_MS || params?.optBoolean("fresh", false) == true) return captureNow()
        return v
    }

    /** 写动作必须携带上一张 page；只拿旧 ref 直接点击会被拒绝，避免跨后端/缓存假执行。 */
    private fun currentForWrite(params: JSONObject): SnapshotView {
        val v = current(params)
        val page = params.optJSONObject("page")
            ?: throw err("STALE_PAGE", "page is required; call ui.view first")
        if (page.optLong("g", -1L) != v.snapshot.g || page.optLong("seq", -1L) != v.snapshot.seq) {
            throw err("STALE_PAGE", "page changed; call ui.view again")
                .detail("expected", page)
                .detail("current", pageJson(v))
        }
        return v
    }

    /** AccessibilityService 断开时由 AgentServer 调用，清掉可能持有失效节点句柄的缓存。 */
    fun serviceDetached() = locked {
        recycleHandles()
        lastSignature = ""
        lastEventSeq = -1L
        view = null
        previous = null
        g += 1
        refs = RefStore()
    }

    private fun captureNow(): SnapshotView {
        val svc = service()
        val cap = Capture.capture(svc, g, UiClock.events)
        lastEventSeq = UiClock.events
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
            // 状态栏时钟/网速每秒都变；它们不属于 App 页面，不能把 g 代刷爆。
            if (n.pkg == "com.android.systemui") continue
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
        // 动作后的首个无障碍事件可能晚到；先固定等一个 quiet 窗口，再等真正静默。
        try {
            Thread.sleep(QUIET_MS)
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

    private fun uiEffectFor(op: String): String = when (op) {
        "copy", "cut", "paste", "setSelection" -> "possible"
        else -> "certain"
    }

    private fun pageJson(v: SnapshotView): JSONObject = JSONObject()
        .put("g", v.snapshot.g)
        .put("seq", v.snapshot.seq)
        .put("pkg", v.snapshot.pkg)
        .put("activity", v.snapshot.activity)
        .put("kb", v.snapshot.keyboard)
        .put("nodes", v.snapshot.size)

    private fun changeJson(c: Change): JSONObject {
        val texts = JSONArray()
        val changed = JSONArray()
        for (item in c.changed) {
            if (item.field == "text" && texts.length() < 5) texts.put(item.after)
            if (changed.length() < 10) {
                changed.put(JSONObject().put("ref", item.ref).put("field", item.field)
                    .put("before", item.before).put("after", item.after))
            }
        }
        val added = JSONArray()
        for (item in c.added.take(10)) added.put(item)
        val removed = JSONArray()
        for (item in c.removed.take(10)) removed.put(item)
        return JSONObject()
            .put("added", c.added.size).put("addedItems", added)
            .put("removed", c.removed.size).put("removedItems", removed)
            .put("changed", c.changed.size).put("changedItems", changed)
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
