package dev.dsh.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.lang.reflect.InvocationTargetException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.Arrays
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object RootHelper {
    private const val PORT = 17322
    private val TOKEN = File("/data/user/0/dev.dsh.agent/files/token")
    private val CLIENTS: ExecutorService = Executors.newFixedThreadPool(4)
    private val UI = Ui()

    @Volatile
    private var token = ""

    @Volatile
    private var eventSequence = 0L

    @JvmStatic
    @Throws(Exception::class)
    fun main(args: Array<String>) {
        if (Process.myUid() != 0) throw IllegalStateException("root helper must run as root")
        token = read(TOKEN).trim()
        if (!token.matches(Regex("[0-9a-f]{32}"))) throw IllegalStateException("invalid agent token")
        Looper.prepareMainLooper()
        val listener = ServerSocket()
        listener.reuseAddress = true
        listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8)
        Thread({
            while (!listener.isClosed) {
                try {
                    CLIENTS.execute(Client(listener.accept()))
                } catch (_: Exception) {
                    if (listener.isClosed) return@Thread
                }
            }
        }, "dsh-root-listener").start()
        Thread({
            while (true) {
                SystemClock.sleep(1000)
                UI.releaseIdle()
            }
        }, "dsh-root-idle").start()
        Looper.loop()
    }

    private class Client(private val socket: Socket) : Runnable {
        override fun run() {
            try {
                socket.use {
                    socket.soTimeout = 15000
                    val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    val line = line(input)
                    val request = JSONObject(line)
                    val id: Any = request.opt("id") ?: 0
                    val response = JSONObject().put("id", id)
                    val expected = read(TOKEN).trim()
                    if (!MessageDigest.isEqual(
                            expected.toByteArray(StandardCharsets.UTF_8),
                            request.optString("token").toByteArray(StandardCharsets.UTF_8)
                        )
                    ) {
                        response.put("ok", false).put("error", error("UNAUTHORIZED", "unauthorized"))
                    } else if (request.optString("method") == "events.subscribe") {
                        watch(socket, request)
                    } else {
                        try {
                            val result = dispatch(
                                request.optString("method"),
                                request.optJSONObject("params") ?: JSONObject()
                            )
                            response.put("ok", true).put("result", result)
                        } catch (failure: Failure) {
                            response.put("ok", false).put("error", error(failure.code, failure.message ?: ""))
                        } catch (_: Exception) {
                            response.put("ok", false).put("error", error("INTERNAL", "root helper internal error"))
                        }
                    }
                    var text = response.toString()
                    if (text.length > 16 * 1024 * 1024) {
                        text = JSONObject().put("id", id).put("ok", false)
                            .put("error", error("OUTPUT_TOO_LARGE", "response exceeds limit")).toString()
                    }
                    val output = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                    output.write(text + "\n")
                    output.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    @Throws(Exception::class)
    private fun watch(socket: Socket, request: JSONObject) {
        val params = request.optJSONObject("params") ?: JSONObject()
        val types = params.optJSONArray("types")
        var crash = false
        if (types != null) {
            for (i in 0 until types.length()) if (types.getString(i) == "crash.anr") crash = true
        }
        if (!crash) {
            val output = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            output.write(JSONObject().put("id", request.opt("id")).put("ok", false)
                .put("error", error("UNAVAILABLE", "root event stream only supports crash.anr")).toString() + "\n")
            output.flush()
            return
        }
        val process = ProcessBuilder("logcat", "-b", "crash", "-v", "threadtime").start()
        val output = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
        val ack = JSONObject().put("id", request.opt("id")).put("ok", true)
            .put("result", JSONObject().put("subscribed", true).put("bootId", UUID.randomUUID().toString())
                .put("baseSeq", eventSequence).put("resetRequired", params.optLong("since") > eventSequence)
                .put("replaySupported", false))
        synchronized(socket) {
            output.write(ack.toString() + "\n")
            output.flush()
        }
        val expected = read(TOKEN).trim()
        val heartbeat = Thread({
            try {
                while (!socket.isClosed) {
                    Thread.sleep(10000)
                    if (expected != read(TOKEN).trim()) {
                        socket.close()
                        break
                    }
                    synchronized(socket) {
                        output.write(JSONObject().put("event", "events.heartbeat").put("seq", eventSequence)
                            .put("time", System.currentTimeMillis()).toString() + "\n")
                        output.flush()
                    }
                }
            } catch (_: Exception) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }, "dsh-crash-heartbeat")
        heartbeat.isDaemon = true
        heartbeat.start()
        try {
            BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.contains("FATAL EXCEPTION") && !line.contains("ANR in") && !line.contains("Fatal signal")) continue
                    if (params.optString("package").isNotEmpty() && !line.contains(params.getString("package"))) continue
                    val seq: Long
                    synchronized(RootHelper) { seq = ++eventSequence }
                    val event = JSONObject().put("event", "crash.anr").put("seq", seq)
                        .put("time", System.currentTimeMillis())
                        .put("data", JSONObject().put("line", line.substring(0, minOf(512, line.length))))
                    synchronized(socket) {
                        output.write(event.toString() + "\n")
                        output.flush()
                    }
                }
            }
        } finally {
            process.destroy()
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    @Throws(Exception::class)
    private fun dispatch(method: String, params: JSONObject): JSONObject {
        when (method) {
            "root.ping" -> return JSONObject().put("root", true).put("uid", Process.myUid()).put("via", "shell")
            "backend.status" -> return status()
            "shell.exec" -> {
                confirm(params)
                val command = params.getString("command")
                if (command.length > 4096) throw Failure("INVALID_PARAMS", "command is too long")
                return run(arrayOf("/system/bin/sh", "-c", command), params.optLong("timeoutMs", 30000))
            }
            "shot" -> return screenshot(params)
            "settings.get" -> {
                val namespace = namespace(params)
                val name = name(params)
                return success(arrayOf("settings", "get", namespace, name), 5000).put("via", "shell")
            }
            "settings.put" -> {
                confirm(params)
                val namespace = namespace(params)
                val name = name(params)
                val value = params.optString("value")
                val before = run(arrayOf("settings", "get", namespace, name), 5000)
                val result = success(arrayOf("settings", "put", namespace, name, value), 5000)
                val after = run(arrayOf("settings", "get", namespace, name), 5000)
                if (value != after.optString("stdout").trim()) throw Failure("WRITE_FAILED", "setting readback differs")
                return result.put("before", before.optString("stdout").trim())
                    .put("value", after.optString("stdout").trim())
            }
            "package.list" -> return run(arrayOf("pm", "list", "packages", "--show-versioncode"), 15000)
            "package.forceStop", "activity.forceStop" -> {
                confirm(params)
                return success(arrayOf("am", "force-stop", pkg(params)), 10000)
            }
            "package.clear" -> {
                confirm(params)
                return success(arrayOf("pm", "clear", pkg(params)), 20000)
            }
            "package.uninstall" -> {
                confirm(params)
                return success(arrayOf("pm", "uninstall", pkg(params)), 30000)
            }
            "package.enable" -> {
                confirm(params)
                return success(arrayOf("pm", "enable", pkg(params)), 10000)
            }
            "package.disable" -> {
                confirm(params)
                return success(arrayOf("pm", "disable", pkg(params)), 10000)
            }
            "package.install" -> {
                confirm(params)
                val apk = allowed(params.getString("path"), true)
                if (!apk.name.endsWith(".apk")) throw Failure("INVALID_PARAMS", "only APK files are accepted")
                return success(arrayOf("pm", "install", "-r", apk.path), 120000)
            }
            "perm.grant", "perm.revoke" -> {
                confirm(params)
                return success(arrayOf("pm", if (method.endsWith("grant")) "grant" else "revoke",
                    pkg(params), params.getString("permission")), 10000)
            }
            "activity.start" -> return activity(params)
            "user.list" -> return run(arrayOf("pm", "list", "users"), 5000)
            "task.list" -> return run(arrayOf("dumpsys", "activity", "recents"), 10000)
            "task.remove" -> return success(arrayOf("am", "task", "remove", params.getInt("taskId").toString()), 5000)
            "task.splitScreen", "task.pip" -> throw Failure("UNAVAILABLE", "this ROM has no verified task windowing command")
            "power.wake" -> return success(arrayOf("input", "keyevent", "KEYCODE_WAKEUP"), 5000)
            "power.sleep" -> return success(arrayOf("input", "keyevent", "KEYCODE_SLEEP"), 5000)
            "power.reboot" -> {
                confirm(params)
                return run(arrayOf("reboot"), 5000)
            }
            "lock.unlock" -> return unlock(params)
            "net.wifi", "net.data" -> {
                confirm(params)
                val enabled = params.optBoolean("enabled", true)
                return success(arrayOf("svc", if (method.endsWith("wifi")) "wifi" else "data",
                    if (enabled) "enable" else "disable"), 15000)
            }
            "net.airplane" -> {
                confirm(params)
                val enabled = params.optBoolean("enabled", true)
                success(arrayOf("settings", "put", "global", "airplane_mode_on", if (enabled) "1" else "0"), 5000)
                return success(arrayOf("am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE",
                    "--ez", "state", enabled.toString()), 5000)
            }
            "display.setRotation" -> {
                confirm(params)
                val rotation = params.getInt("rotation")
                if (rotation < 0 || rotation > 3) throw Failure("INVALID_PARAMS", "rotation must be 0..3")
                success(arrayOf("settings", "put", "system", "accelerometer_rotation", "0"), 5000)
                return success(arrayOf("settings", "put", "system", "user_rotation", rotation.toString()), 5000)
            }
            "display.setDensity" -> {
                confirm(params)
                val density = params.getInt("dpi")
                if (density < 120 || density > 640) throw Failure("INVALID_PARAMS", "density is outside the allowed range")
                val before = run(arrayOf("wm", "density"), 5000)
                val result = success(arrayOf("wm", "density", density.toString()), 5000)
                return result.put("before", before.optString("stdout").trim())
                    .put("readback", run(arrayOf("wm", "density"), 5000).optString("stdout").trim())
            }
            "prep.automation" -> return prep(params, false)
            "prep.restore" -> return prep(params, true)
            "dnd.set" -> {
                val mode = params.getString("mode")
                if (!mode.matches(Regex("all|priority|none|alarms"))) throw Failure("INVALID_PARAMS", "invalid dnd mode")
                val zen = when (mode) {
                    "all" -> "0"
                    "priority" -> "1"
                    "none" -> "2"
                    else -> "3"
                }
                success(arrayOf("settings", "put", "global", "zen_mode", zen), 5000)
                val result = run(arrayOf("settings", "get", "global", "zen_mode"), 5000)
                if (zen != result.optString("stdout").trim()) throw Failure("WRITE_FAILED", "dnd readback differs")
                return result.put("mode", mode).put("applied", true)
            }
            "location.mock" -> return mockLocation(params)
            "ime.set" -> return success(arrayOf("ime", "set", params.getString("id")), 10000)
            "ime.hide" -> return JSONObject().put("requested",
                run(arrayOf("input", "keyevent", "4"), 5000).optInt("exitCode") == 0)
                .put("confirmed", false).put("via", "shell")
            "ime.show" -> throw Failure("UNAVAILABLE", "no reliable shell-only IME show command")
            "media.play", "media.pause", "media.next", "media.prev", "media.stop" -> {
                val key = when (method) {
                    "media.next" -> "87"
                    "media.prev" -> "88"
                    "media.stop" -> "86"
                    else -> "85"
                }
                return success(arrayOf("input", "keyevent", key), 5000).put("requested", true).put("confirmed", false)
            }
            "screen.record" -> return record(params)
            "perf.fps" -> return success(arrayOf("dumpsys", "gfxinfo", pkg(params), "framestats"), 10000)
            "crash.anr" -> return run(arrayOf("logcat", "-b", "crash", "-d", "-t", "100"), 10000)
            "sms.list", "sms.search" -> return query("content://sms/inbox", "address,body,date,read", params)
            "contacts.search" -> return query("content://com.android.contacts/data/phones", "display_name,data1", params)
            "calendar.list" -> {
                val start = params.optLong("start")
                val end = params.optLong("end")
                if (start < 0 || end <= start) throw Failure("INVALID_PARAMS", "invalid calendar interval")
                return run(arrayOf("content", "query", "--uri",
                    "content://com.android.calendar/instances/when/" + start + "/" + end,
                    "--projection", "title,dtstart,dtend,eventLocation"), 15000)
            }
            "call.dial" -> {
                confirm(params)
                val number = params.getString("number")
                if (!number.matches(Regex("[0-9+*#]{1,32}"))) throw Failure("INVALID_PARAMS", "invalid phone number")
                return success(arrayOf("am", "start", "-a", "android.intent.action.CALL", "-d", "tel:" + number), 5000)
            }
            "sys.info" -> return JSONObject()
                .put("display", run(arrayOf("wm", "size"), 5000).optString("stdout").trim())
                .put("density", run(arrayOf("wm", "density"), 5000).optString("stdout").trim())
                .put("foregroundPackage", foregroundPackage()).put("via", "shell")
            else -> {
                if (method.startsWith("ui.")) {
                    try {
                        val result = UI.handle(method, params)
                        if (result != null) return result
                    } catch (failure: Failure) {
                        if (failure.code != "SLOT_BUSY" && failure.code != "UNAVAILABLE") throw failure
                    }
                    return shellUi(method, params)
                }
                throw Failure("UNKNOWN_METHOD", "unknown method: " + method)
            }
        }
    }

    @Throws(Exception::class)
    private fun foregroundPackage(): String {
        val output = run(arrayOf("dumpsys", "activity", "activities"), 10000).optString("stdout")
        for (line in output.split("\n".toRegex())) {
            if (!line.contains("topResumedActivity") && !line.contains("mResumedActivity")) continue
            val matcher = java.util.regex.Pattern.compile("([A-Za-z][A-Za-z0-9_.]+)/").matcher(line)
            if (matcher.find()) return matcher.group(1)
        }
        return ""
    }

    @Throws(Exception::class)
    private fun status(): JSONObject {
        val ui = UI.available()
        return JSONObject().put("root", JSONObject().put("ok", true).put("uid", Process.myUid()))
            .put("layers", JSONArray()
                .put(JSONObject().put("name", "uiautomation").put("ok", ui)
                    .put("reason", if (ui) JSONObject.NULL else UI.reason))
                .put(JSONObject().put("name", "shell").put("ok", true).put("reason", JSONObject.NULL)))
            .put("via", "shell").put("degraded", !ui)
    }

    @Throws(Exception::class)
    private fun activity(params: JSONObject): JSONObject {
        val command = arrayListOf("am", "start")
        if (params.has("user")) command.addAll(listOf("--user", params.getInt("user").toString()))
        if (params.optString("activity").isNotEmpty()) {
            command.addAll(listOf("-n", pkg(params) + "/" + params.getString("activity")))
        }
        if (params.optString("action").isNotEmpty()) command.addAll(listOf("-a", params.getString("action")))
        if (params.optString("data").isNotEmpty()) command.addAll(listOf("-d", params.getString("data")))
        if (params.optString("type").isNotEmpty()) command.addAll(listOf("-t", params.getString("type")))
        if (params.optString("package").isNotEmpty() && params.optString("activity").isEmpty()) {
            command.addAll(listOf("-p", pkg(params)))
        }
        if (params.has("flags") && params.get("flags") is Number) {
            command.addAll(listOf("-f", params.getInt("flags").toString()))
        }
        params.optJSONObject("extras")?.let { extras ->
            val iterator = extras.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                when (val value = extras.get(key)) {
                    is Boolean -> command.addAll(listOf("--ez", key, value.toString()))
                    is Int, is Long -> command.addAll(listOf("--el", key, value.toString()))
                    is Double, is Float -> command.addAll(listOf("--ef", key, value.toString()))
                    is String -> command.addAll(listOf("--es", key, value))
                    else -> throw Failure("INVALID_PARAMS", "unsupported intent extra type")
                }
            }
        }
        return success(command.toTypedArray(), 10000).put("launchRequested", true).put("confirmed", false)
    }

    @Throws(Exception::class)
    private fun unlock(params: JSONObject): JSONObject {
        confirm(params)
        val pin = params.optString("pin")
        if (!pin.matches(Regex("[0-9]{4,16}"))) {
            throw Failure("AUTH_REQUIRED", "secure unlock requires a numeric PIN; pattern/password is not supported")
        }
        success(arrayOf("input", "keyevent", "KEYCODE_WAKEUP"), 5000)
        success(arrayOf("input", "keyevent", "82"), 5000)
        success(arrayOf("input", "text", pin), 5000)
        return success(arrayOf("input", "keyevent", "KEYCODE_ENTER"), 5000)
            .put("requested", true).put("confirmed", false)
    }

    @Throws(Exception::class)
    private fun mockLocation(params: JSONObject): JSONObject {
        confirm(params)
        val provider = params.optString("provider", "gps")
        if (!provider.matches(Regex("gps|network"))) throw Failure("INVALID_PARAMS", "provider must be gps or network")
        if (!params.optBoolean("enabled", true)) {
            return success(arrayOf("cmd", "location", "providers", "remove-test-provider", provider), 5000)
                .put("requested", true).put("confirmed", false)
        }
        val latitude = params.getDouble("latitude")
        val longitude = params.getDouble("longitude")
        val accuracy = params.optDouble("accuracy", 1.0)
        if (!latitude.isFinite() || !longitude.isFinite() || !accuracy.isFinite() ||
            latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 ||
            accuracy <= 0 || accuracy > 100000
        ) {
            throw Failure("INVALID_PARAMS", "invalid mock location")
        }
        val added = run(arrayOf("cmd", "location", "providers", "add-test-provider", provider), 5000)
        if (added.optInt("exitCode") != 0 && !added.optString("stderr").lowercase().contains("already") &&
            !added.optString("stdout").lowercase().contains("already")
        ) {
            throw Failure("UNAVAILABLE", "cannot add mock location provider")
        }
        success(arrayOf("cmd", "location", "providers", "set-test-provider-enabled", provider, "true"), 5000)
        val result = success(arrayOf("cmd", "location", "providers", "set-test-provider-location", provider,
            String.format(Locale.US, "%.7f", latitude), String.format(Locale.US, "%.7f", longitude),
            String.format(Locale.US, "%.2f", accuracy)), 5000)
        return result.put("requested", true).put("confirmed", false).put("provider", provider)
    }

    @Throws(Exception::class)
    private fun prep(params: JSONObject, restore: Boolean): JSONObject {
        confirm(params)
        val namespaces = arrayOf("global", "global", "global", "global", "system", "system", "system", "system", "system")
        val names = arrayOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale",
            "stay_on_while_plugged_in", "screen_off_timeout", "screen_brightness", "screen_brightness_mode",
            "accelerometer_rotation", "user_rotation")
        val values = arrayOf("0", "0", "0", "7", "600000", "80", "0", "0", "0")
        val backup = File("/data/adb/dsh-agent/prep-backup.json")
        var old = JSONObject()
        if (restore) {
            old = JSONObject(read(backup))
            for (name in names) if (!old.has(name)) throw Failure("NOT_FOUND", "automation backup is incomplete")
        } else {
            for (name in names) {
                val namespace = if (name.startsWith("stay") || name.contains("animation") || name.contains("scale")) "global" else "system"
                old.put(name, run(arrayOf("settings", "get", namespace, name), 5000).optString("stdout").trim())
            }
            backup.parentFile?.mkdirs()
            write(backup, old.toString())
        }
        val applied = JSONObject()
        for (i in names.indices) {
            val value = if (restore) old.getString(names[i]) else values[i]
            if (restore && value == "null") success(arrayOf("settings", "delete", namespaces[i], names[i]), 5000)
            else success(arrayOf("settings", "put", namespaces[i], names[i], value), 5000)
            applied.put(names[i], value)
        }
        return JSONObject().put("restored", restore).put("applied", applied).put("via", "shell")
    }

    @Throws(Exception::class)
    private fun record(params: JSONObject): JSONObject {
        confirm(params)
        val seconds = params.optInt("durationMs", 10000) / 1000
        if (seconds < 1 || seconds > 180) throw Failure("INVALID_PARAMS", "recording duration must be 1..180 seconds")
        val output = File("/sdcard/.dsh/recordings/" + UUID.randomUUID() + ".mp4")
        output.parentFile?.mkdirs()
        val result = run(arrayOf("screenrecord", "--time-limit", seconds.toString(), output.path), (seconds + 15) * 1000L)
        if (!output.isFile) throw Failure("UNAVAILABLE", "screenrecord did not create a file")
        return result.put("path", output.path).put("bytes", output.length())
            .put("completed", result.optInt("exitCode") == 0)
    }

    @Throws(Exception::class)
    private fun query(uri: String, projection: String, params: JSONObject): JSONObject {
        val command = arrayListOf("content", "query", "--uri", uri, "--projection", projection)
        if (params.optString("where").isNotEmpty()) command.addAll(listOf("--where", params.getString("where")))
        return run(command.toTypedArray(), 15000)
    }

    @Throws(Exception::class)
    private fun shellUi(method: String, params: JSONObject): JSONObject {
        if (params.optBoolean("dryRun")) return JSONObject().put("executed", false).put("via", "shell")
        when (method) {
            "ui.click" -> {
                if (params.has("x") && params.has("y")) {
                    return success(arrayOf("input", "tap", params.get("x").toString(), params.get("y").toString()), 5000)
                }
            }
            "ui.longClick" -> {
                if (params.has("x") && params.has("y")) {
                    val x = params.get("x").toString()
                    val y = params.get("y").toString()
                    return success(arrayOf("input", "swipe", x, y, x, y, params.optLong("durationMs", 700).toString()), 15000)
                }
                return shellSwipe(params)
            }
            "ui.swipe", "ui.drag", "ui.fling" -> return shellSwipe(params)
            "ui.gesture" -> {
                val paths = params.getJSONArray("paths")
                if (paths.length() != 1) throw Failure("UNAVAILABLE", "multi-pointer gestures require UiAutomation")
                val path = paths.getJSONObject(0)
                val points = path.getJSONArray("points")
                val first = points.getJSONObject(0)
                val last = points.getJSONObject(points.length() - 1)
                return success(arrayOf("input", "swipe", first.get("x").toString(), first.get("y").toString(),
                    last.get("x").toString(), last.get("y").toString(), path.optLong("durationMs", 500).toString()), 15000)
            }
            "ui.doubleTap" -> {
                success(arrayOf("input", "tap", params.get("x").toString(), params.get("y").toString()), 5000)
                return success(arrayOf("input", "tap", params.get("x").toString(), params.get("y").toString()), 5000)
            }
            "ui.global" -> {
                return when (val action = params.getString("action")) {
                    "assistant" -> success(arrayOf("am", "start", "-a", "android.intent.action.ASSIST"), 5000)
                    "back" -> success(arrayOf("input", "keyevent", "4"), 5000)
                    "home" -> success(arrayOf("input", "keyevent", "3"), 5000)
                    "recents" -> success(arrayOf("input", "keyevent", "187"), 5000)
                    "notifications" -> success(arrayOf("cmd", "statusbar", "expand-notifications"), 5000)
                    "quickSettings" -> success(arrayOf("cmd", "statusbar", "expand-settings"), 5000)
                    "lock" -> success(arrayOf("input", "keyevent", "26"), 5000)
                    "powerDialog" -> success(arrayOf("input", "keyevent", "--longpress", "26"), 5000)
                    "screenshot" -> success(arrayOf("input", "keyevent", "120"), 5000)
                    else -> throw Failure("UNAVAILABLE", "global action is not available through shell")
                }
            }
            "ui.key" -> {
                if (params.optBoolean("ctrl") || params.optBoolean("shift") || params.optBoolean("alt")) {
                    throw Failure("UNAVAILABLE", "shell key meta requires UiAutomation")
                }
                val command = arrayListOf("input", "keyevent")
                if (params.optBoolean("longPress")) command.add("--longpress")
                command.add(params.getString("key"))
                return success(command.toTypedArray(), 5000)
            }
            "shot" -> return screenshot(params)
        }
        throw Failure("UNAVAILABLE", "this UI operation needs a live node or UiAutomation")
    }

    @Throws(Exception::class)
    private fun shellSwipe(params: JSONObject): JSONObject = success(
        arrayOf("input", "swipe", params.get("x1").toString(), params.get("y1").toString(),
            params.get("x2").toString(), params.get("y2").toString(),
            params.optLong("durationMs", 500).toString()), 15000
    )

    @Throws(Exception::class)
    private fun screenshot(params: JSONObject): JSONObject {
        var bytes: ByteArray = ByteArray(0)
        var via = "shell"
        try {
            val bitmap = UI.screenshot()
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            bytes = output.toByteArray()
            via = "uiautomation"
        } catch (_: Exception) {
            bytes = binary(arrayOf("screencap", "-p"), 15000)
        }
        if (bytes.isEmpty() || bytes.size > 12 * 1024 * 1024) throw Failure("UNAVAILABLE", "screenshot is empty or too large")
        val result = JSONObject().put("mimeType", "image/png")
            .put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).put("via", via)
            .put("width", ByteBuffer.wrap(bytes).getInt(16)).put("height", ByteBuffer.wrap(bytes).getInt(20))
            .put("capturedAt", System.currentTimeMillis())
        if (params.optString("dest").isNotEmpty()) {
            val target = allowed(params.getString("dest"), false)
            target.parentFile?.mkdirs()
            write(target, String(bytes, StandardCharsets.ISO_8859_1))
            result.put("path", target.path)
        }
        return result
    }

    private class Ui {
        private var automation: Any? = null
        var reason = "SLOT_BUSY"
        private var lastUse = 0L
        private var lastAttempt = 0L

        @Synchronized
        fun available(): Boolean {
            if (automation != null) return true
            if (SystemClock.uptimeMillis() - lastAttempt < 2000) return false
            return try {
                connect()
                true
            } catch (_: Exception) {
                reason = "SLOT_BUSY"
                false
            }
        }

        @Throws(Exception::class)
        private fun connect() {
            lastAttempt = SystemClock.uptimeMillis()
            val connectionClass = Class.forName("android.app.UiAutomationConnection")
            val connection = connectionClass.getDeclaredConstructor().newInstance()
            val type = Class.forName("android.app.IUiAutomationConnection")
            val uiClass = Class.forName("android.app.UiAutomation")
            val constructor = uiClass.getDeclaredConstructor(Looper::class.java, type)
            constructor.isAccessible = true
            automation = constructor.newInstance(Looper.getMainLooper(), connection)
            val connect = uiClass.getDeclaredMethod("connect", Int::class.javaPrimitiveType)
            connect.isAccessible = true
            connect.invoke(automation, 1)
            reason = ""
            lastUse = SystemClock.uptimeMillis()
        }

        @Synchronized
        @Throws(Exception::class)
        fun call(name: String, types: Array<Class<*>>, vararg args: Any?): Any? {
            if (!available()) throw Failure("SLOT_BUSY", "UiAutomation is unavailable")
            lastUse = SystemClock.uptimeMillis()
            val method = automation!!.javaClass.getMethod(name, *types)
            return try {
                method.invoke(automation, *args)
            } catch (error: InvocationTargetException) {
                val cause = error.cause
                if (cause is java.util.concurrent.TimeoutException) throw Failure("TIMEOUT", "UI wait timed out")
                if (cause is IllegalStateException) {
                    automation = null
                    throw Failure("SLOT_BUSY", "UiAutomation was disconnected")
                }
                throw Failure("UNAVAILABLE", "UiAutomation call failed")
            }
        }

        @Throws(Exception::class)
        fun root(): AccessibilityNodeInfo? = call("getRootInActiveWindow", emptyArray()) as AccessibilityNodeInfo?

        @Throws(Exception::class)
        fun windows(): List<AccessibilityWindowInfo> {
            @Suppress("UNCHECKED_CAST")
            return call("getWindows", emptyArray()) as List<AccessibilityWindowInfo>
        }

        @Throws(Exception::class)
        fun inject(event: InputEvent): Boolean = call("injectInputEvent",
            arrayOf(InputEvent::class.java, Boolean::class.javaPrimitiveType!!), event, true) as Boolean

        @Throws(Exception::class)
        fun screenshot(): Bitmap = call("takeScreenshot", emptyArray()) as Bitmap

        @Throws(Exception::class)
        fun handle(method: String, params: JSONObject): JSONObject? {
            if (!available()) throw Failure("SLOT_BUSY", "UiAutomation is unavailable")
            return when (method) {
                "ui.dump" -> dump(params)
                "ui.find" -> {
                    val nodes = dump(params).getJSONArray("nodes")
                    val hits = JSONArray()
                    for (i in 0 until nodes.length()) {
                        if (hits.length() >= params.optInt("limit", 30)) break
                        val node = nodes.getJSONObject(i)
                        if (matches(node, params)) hits.put(node)
                    }
                    JSONObject().put("nodes", hits).put("count", hits.length()).put("via", "uiautomation")
                }
                "ui.resolve" -> {
                    val node = find(params) ?: throw Failure("NOT_FOUND", "no live node matched")
                    try {
                        verify(node)
                        nodeJson(node, "n1", null, 0, 0, "", node.windowId)
                            .put("live", true).put("via", "uiautomation")
                    } finally {
                        node.recycle()
                    }
                }
                "ui.click", "ui.longClick", "ui.doubleTap", "ui.setText", "ui.appendText", "ui.clearText",
                "ui.selectAll", "ui.copy", "ui.paste", "ui.cut" -> action(method, params)
                "ui.gesture" -> gesture(params)
                "ui.scrollUntil" -> scrollUntil(params)
                "ui.swipe", "ui.drag", "ui.fling" -> {
                    var duration = params.optLong("durationMs", 500)
                    if (method == "ui.fling") {
                        duration = maxOf(50L, minOf(1500.0,
                            Math.hypot(params.getDouble("x2") - params.getDouble("x1"),
                                params.getDouble("y2") - params.getDouble("y1")) *
                                1000 / Math.max(100.0, params.optDouble("velocity", 1500.0))).toLong())
                    }
                    swipe(params.getDouble("x1").toFloat(), params.getDouble("y1").toFloat(),
                        params.getDouble("x2").toFloat(), params.getDouble("y2").toFloat(), duration)
                }
                "ui.key" -> key(params)
                "ui.wait" -> waitFor(params)
                "ui.waitIdle" -> {
                    call("waitForIdle", arrayOf(Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
                        100L, minOf(10000L, params.optLong("timeoutMs", 5000)))
                    JSONObject().put("idle", true).put("via", "uiautomation")
                }
                "ui.global" -> global(params)
                else -> null
            }
        }

        @Throws(Exception::class)
        private fun action(method: String, params: JSONObject): JSONObject {
            var node: AccessibilityNodeInfo? = null
            val bounds = Rect()
            if (params.has("x") && params.has("y")) {
                bounds.set(params.getDouble("x").toInt(), params.getDouble("y").toInt(),
                    params.getDouble("x").toInt(), params.getDouble("y").toInt())
            } else {
                node = find(params) ?: throw Failure("NOT_FOUND", "no live node matched")
                node.getBoundsInScreen(bounds)
                verify(node)
            }
            try {
                if (params.optBoolean("dryRun")) {
                    return JSONObject().put("executed", false)
                        .put("target", JSONObject().put("x", bounds.centerX()).put("y", bounds.centerY()))
                        .put("via", "uiautomation")
                }
                var result: Boolean
                when (method) {
                    "ui.click" -> result = (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) ||
                        tap(bounds.centerX().toFloat(), bounds.centerY().toFloat()).optBoolean("performed")
                    "ui.longClick" -> result = (node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true) ||
                        swipe(bounds.centerX().toFloat(), bounds.centerY().toFloat(),
                            bounds.centerX().toFloat(), bounds.centerY().toFloat(),
                            params.optLong("durationMs", 700)).optBoolean("performed")
                    "ui.doubleTap" -> {
                        result = tap(bounds.centerX().toFloat(), bounds.centerY().toFloat()).optBoolean("performed")
                        SystemClock.sleep(80)
                        result = tap(bounds.centerX().toFloat(), bounds.centerY().toFloat()).optBoolean("performed") && result
                    }
                    else -> {
                        val live = node ?: throw Failure("NOT_CLICKABLE", "target is not editable")
                        if (!live.isEditable) throw Failure("NOT_CLICKABLE", "target is not editable")
                        if (method == "ui.selectAll") {
                            val selection = Bundle()
                            selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                            selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, string(live.text).length)
                            result = live.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
                        } else if (method == "ui.copy" || method == "ui.paste" || method == "ui.cut") {
                            result = live.performAction(
                                if (method == "ui.copy") AccessibilityNodeInfo.ACTION_COPY
                                else if (method == "ui.paste") AccessibilityNodeInfo.ACTION_PASTE
                                else AccessibilityNodeInfo.ACTION_CUT
                            )
                        } else {
                            val arguments = Bundle()
                            val value = if (method == "ui.appendText") string(live.text) + params.optString("value")
                            else if (method == "ui.clearText") "" else params.optString("value", params.optString("text"))
                            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                            result = live.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                        }
                    }
                }
                if (!result) throw Failure("UNAVAILABLE", "UI action was rejected")
                return JSONObject().put("performed", true).put("via", "uiautomation")
            } finally {
                node?.recycle()
            }
        }

        @Throws(Exception::class)
        private fun gesture(params: JSONObject): JSONObject {
            if (params.optBoolean("dryRun")) return JSONObject().put("executed", false).put("via", "uiautomation")
            val paths = params.getJSONArray("paths")
            if (paths.length() < 1 || paths.length() > 10) throw Failure("INVALID_PARAMS", "one to ten paths are required")
            val durations = LongArray(paths.length())
            var maximum = 0L
            for (i in 0 until paths.length()) {
                val path = paths.getJSONObject(i)
                if (path.optLong("startMs") != 0L) throw Failure("UNAVAILABLE", "staggered pointers require the App gesture backend")
                val points = path.getJSONArray("points")
                if (points.length() < 2 || points.length() > 100) throw Failure("INVALID_PARAMS", "invalid pointer path")
                durations[i] = maxOf(50L, minOf(10000L, path.optLong("durationMs", 500)))
                maximum = maxOf(maximum, durations[i])
            }
            val downTime = SystemClock.uptimeMillis()
            val active = ArrayList<Int>()
            for (i in 0 until paths.length()) {
                active.add(i)
                val action = if (i == 0) MotionEvent.ACTION_DOWN
                else MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                emit(paths, durations, active, 0, action, downTime)
                SystemClock.sleep(16)
            }
            var elapsed = 16L
            while (elapsed <= maximum && active.isNotEmpty()) {
                val finished = ArrayList<Int>()
                for (id in active) if (elapsed >= durations[id]) finished.add(id)
                if (finished.isEmpty()) {
                    emit(paths, durations, active, elapsed, MotionEvent.ACTION_MOVE, downTime)
                } else {
                    for (id in finished) {
                        val index = active.indexOf(id)
                        val action = if (active.size == 1) MotionEvent.ACTION_UP
                        else MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                        emit(paths, durations, active, durations[id], action, downTime)
                        active.removeAt(index)
                        if (active.isEmpty()) break
                    }
                }
                SystemClock.sleep(16)
                elapsed += 16
            }
            return JSONObject().put("performed", true).put("completed", true).put("via", "uiautomation")
        }

        @Throws(Exception::class)
        private fun emit(paths: JSONArray, durations: LongArray, active: List<Int>, elapsed: Long, action: Int, downTime: Long) {
            val properties = arrayOfNulls<MotionEvent.PointerProperties>(active.size)
            val coordinates = arrayOfNulls<MotionEvent.PointerCoords>(active.size)
            for (i in active.indices) {
                val id = active[i]
                properties[i] = MotionEvent.PointerProperties()
                properties[i]!!.id = id
                properties[i]!!.toolType = MotionEvent.TOOL_TYPE_FINGER
                val point = pointAt(paths.getJSONObject(id), minOf(elapsed, durations[id]))
                coordinates[i] = MotionEvent.PointerCoords()
                coordinates[i]!!.x = point[0]
                coordinates[i]!!.y = point[1]
                coordinates[i]!!.pressure = 1f
                coordinates[i]!!.size = 1f
                if (point[0] < 0 || point[1] < 0) throw Failure("NOT_VISIBLE", "pointer is outside the screen")
            }
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, active.size,
                properties as Array<MotionEvent.PointerProperties>, coordinates as Array<MotionEvent.PointerCoords>,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0, 0, 0)
                ?: throw Failure("INTERNAL", "cannot create pointer event")
            try {
                if (!inject(event)) throw Failure("UNAVAILABLE", "multi-pointer injection was rejected")
            } finally {
                event.recycle()
            }
        }

        @Throws(Exception::class)
        private fun pointAt(path: JSONObject, elapsed: Long): FloatArray {
            val points = path.getJSONArray("points")
            val duration = path.optLong("durationMs", 500)
            for (i in 1 until points.length()) {
                val before = points.getJSONObject(i - 1)
                val after = points.getJSONObject(i)
                val start = before.optLong("timeMs",
                    before.optLong("time", duration * (i - 1) / (points.length() - 1)))
                val end = after.optLong("timeMs", after.optLong("time", duration * i / (points.length() - 1)))
                if (elapsed <= end || i == points.length() - 1) {
                    val fraction = if (end == start) 0f
                    else maxOf(0f, minOf(1f, (elapsed - start).toFloat() / (end - start).toFloat()))
                    return floatArrayOf(
                        (before.getDouble("x") + (after.getDouble("x") - before.getDouble("x")) * fraction).toFloat(),
                        (before.getDouble("y") + (after.getDouble("y") - before.getDouble("y")) * fraction).toFloat())
                }
            }
            val point = points.getJSONObject(0)
            return floatArrayOf(point.getDouble("x").toFloat(), point.getDouble("y").toFloat())
        }

        @Throws(Exception::class)
        private fun key(params: JSONObject): JSONObject {
            val value = params.getString("key")
            val code = if (value.matches(Regex("[0-9]+"))) Integer.parseInt(value) else KeyEvent.keyCodeFromString(value)
            if (code <= 0) throw Failure("INVALID_PARAMS", "unknown keycode")
            val meta = (if (params.optBoolean("ctrl")) KeyEvent.META_CTRL_ON else 0) or
                (if (params.optBoolean("shift")) KeyEvent.META_SHIFT_ON else 0) or
                (if (params.optBoolean("alt")) KeyEvent.META_ALT_ON else 0)
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, 0, 0,
                if (params.optBoolean("longPress")) KeyEvent.FLAG_LONG_PRESS else 0)
            var ok = inject(down)
            val up = KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta)
            ok = inject(up) && ok
            if (!ok) throw Failure("UNAVAILABLE", "key injection was rejected")
            return JSONObject().put("performed", true).put("via", "uiautomation")
        }

        @Throws(Exception::class)
        private fun tap(x: Float, y: Float): JSONObject {
            val now = SystemClock.uptimeMillis()
            if (!inject(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))) {
                throw Failure("UNAVAILABLE", "tap was rejected")
            }
            if (!inject(MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))) {
                throw Failure("UNAVAILABLE", "tap was rejected")
            }
            return JSONObject().put("performed", true).put("via", "uiautomation")
        }

        @Throws(Exception::class)
        private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): JSONObject {
            val start = SystemClock.uptimeMillis()
            if (!inject(MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x1, y1, 0))) {
                throw Failure("UNAVAILABLE", "swipe was rejected")
            }
            val steps = maxOf(1, minOf(100, (duration / 16).toInt()))
            for (i in 1..steps) {
                val fraction = i.toFloat() / steps
                val now = SystemClock.uptimeMillis()
                if (!inject(MotionEvent.obtain(start, now, MotionEvent.ACTION_MOVE,
                        x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction, 0))) {
                    throw Failure("UNAVAILABLE", "swipe was rejected")
                }
                SystemClock.sleep(maxOf(1L, duration / steps))
            }
            if (!inject(MotionEvent.obtain(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2, 0))) {
                throw Failure("UNAVAILABLE", "swipe was rejected")
            }
            return JSONObject().put("performed", true).put("via", "uiautomation")
        }

        @Throws(Exception::class)
        private fun find(params: JSONObject): AccessibilityNodeInfo? {
            val root = root() ?: return null
            return try {
                find(root, params, "", 0, 0, intArrayOf(2000))
            } finally {
                root.recycle()
            }
        }

        @Throws(Exception::class)
        private fun find(node: AccessibilityNodeInfo?, params: JSONObject, path: String, depth: Int,
                         index: Int, budget: IntArray): AccessibilityNodeInfo? {
            if (node == null || depth > 50 || budget[0]-- <= 0) return null
            var keep = false
            try {
                val snapshot = nodeJson(node, "n0", null, depth, index, path, node.windowId)
                if (node.isVisibleToUser && matches(snapshot, params)) {
                    keep = true
                    return node
                }
                val own = if (snapshot.optString("viewId").isEmpty()) "cls:" + snapshot.optString("cls") + "#" + index
                else "vid:" + snapshot.optString("viewId")
                val next = if (path.isEmpty()) own else path + "/" + own
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    val hit = find(child, params, next, depth + 1, i, budget)
                    if (hit != null) return hit
                }
                return null
            } finally {
                if (!keep) node.recycle()
            }
        }

        @Throws(Exception::class)
        private fun dump(params: JSONObject): JSONObject {
            val nodes = JSONArray()
            val counter = intArrayOf(1)
            val budget = intArrayOf(maxOf(1, minOf(2000, params.optInt("maxNodes", 1500))))
            for (window in windows()) {
                val root = window.root ?: continue
                try {
                    dump(root, nodes, counter, budget, null, 0, 0, "", window.id)
                } finally {
                    root.recycle()
                }
            }
            if (nodes.length() == 0) {
                val root = root()
                if (root != null) {
                    try {
                        dump(root, nodes, counter, budget, null, 0, 0, "", root.windowId)
                    } finally {
                        root.recycle()
                    }
                }
            }
            if (nodes.length() == 0) throw Failure("NOT_FOUND", "no readable active window")
            return JSONObject().put("available", true).put("nodes", nodes).put("via", "uiautomation")
                .put("timestamp", System.currentTimeMillis())
        }

        @Throws(Exception::class)
        private fun dump(nativeNode: AccessibilityNodeInfo, nodes: JSONArray, counter: IntArray, budget: IntArray,
                         parent: String?, depth: Int, index: Int, path: String, windowId: Int) {
            if (depth > 40 || budget[0]-- <= 0) return
            val id = "n" + counter[0]++
            val node = nodeJson(nativeNode, id, parent, depth, index, path, windowId)
            nodes.put(node)
            val own = if (node.optString("viewId").isEmpty()) "cls:" + node.getString("cls") + "#" + index
            else "vid:" + node.optString("viewId")
            val next = if (path.isEmpty()) own else path + "/" + own
            for (i in 0 until nativeNode.childCount) {
                val child = nativeNode.getChild(i)
                if (child != null) {
                    try {
                        dump(child, nodes, counter, budget, id, depth + 1, i, next, windowId)
                    } finally {
                        child.recycle()
                    }
                }
            }
        }

        @Throws(Exception::class)
        private fun nodeJson(nativeNode: AccessibilityNodeInfo, id: String, parent: String?, depth: Int,
                             index: Int, path: String, windowId: Int): JSONObject {
            val cls = string(nativeNode.className)
            val pkg = string(nativeNode.packageName)
            val viewId = string(nativeNode.viewIdResourceName)
            val text = if (nativeNode.isPassword) "***" else string(nativeNode.text)
            val desc = if (nativeNode.isPassword) "***" else string(nativeNode.contentDescription)
            val own = if (viewId.isEmpty()) "cls:" + cls + "#" + index else "vid:" + viewId
            val content = text + "|" + desc
            val matchKey = "v1:" + hash("1|" + pkg + "|app|" + path + "|" + own + "|" +
                content.substring(0, minOf(64, content.length)))
            val bounds = Rect()
            nativeNode.getBoundsInScreen(bounds)
            val actions = JSONArray()
            if (nativeNode.isClickable) actions.put("click")
            if (nativeNode.isLongClickable) actions.put("longClick")
            if (nativeNode.isEditable) actions.put("setText")
            if (nativeNode.isScrollable) actions.put("scrollForward").put("scrollBackward")
            val node = JSONObject().put("nodeId", id).put("matchKey", matchKey)
                .put("parentId", parent ?: JSONObject.NULL)
                .put("depth", depth).put("index", index).put("cls", cls).put("class", cls)
                .put("pkg", pkg).put("package", pkg).put("text", text).put("desc", desc)
                .put("viewId", if (viewId.isEmpty()) JSONObject.NULL else viewId)
                .put("isPassword", nativeNode.isPassword).put("windowId", windowId).put("windowType", "app")
                .put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.width()).put(bounds.height()))
                .put("clickable", nativeNode.isClickable).put("longClickable", nativeNode.isLongClickable)
                .put("editable", nativeNode.isEditable).put("enabled", nativeNode.isEnabled)
                .put("visible", nativeNode.isVisibleToUser).put("scrollable", nativeNode.isScrollable)
                .put("checkable", nativeNode.isCheckable).put("checked", nativeNode.isChecked)
                .put("selected", nativeNode.isSelected).put("focused", nativeNode.isFocused)
                .put("childCount", nativeNode.childCount).put("structurePath", path).put("actions", actions)
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                node.put("hint", if (nativeNode.isPassword) "***" else string(nativeNode.hintText))
                node.put("inputType", nativeNode.inputType)
            }
            return node
        }

        @Throws(Exception::class)
        private fun matches(node: JSONObject, params: JSONObject): Boolean {
            if (params.optString("matchKey").isNotEmpty() && params.getString("matchKey") != node.optString("matchKey")) return false
            val text = params.optString("text")
            if (text.isNotEmpty()) {
                val exact = params.optBoolean("exact")
                val actual = node.optString("text")
                val desc = node.optString("desc")
                if (exact) {
                    if (actual != text && desc != text) return false
                } else if (!actual.lowercase().contains(text.lowercase()) && !desc.lowercase().contains(text.lowercase())) return false
            }
            if (params.optString("viewId").isNotEmpty() && params.getString("viewId") != node.optString("viewId")) return false
            if (params.optString("pkg").isNotEmpty() && params.getString("pkg") != node.optString("pkg")) return false
            params.optJSONObject("where")?.let { where ->
                val iterator = where.keys()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    var field = key
                    var operator = "="
                    for (candidate in arrayOf("~=", "!=", "^=", "$=", ">=", "<=", ">", "<", "~")) {
                        if (key.endsWith(candidate)) {
                            field = key.substring(0, key.length - candidate.length)
                            operator = if (candidate == "~") "~=" else candidate
                            break
                        }
                    }
                    if (field == "class") field = "cls"
                    if (field == "package") field = "pkg"
                    val expected = where.get(key).toString()
                    val raw = node.opt(field)
                    val actual = if (raw == null || raw == JSONObject.NULL) "" else raw.toString()
                    val lower = actual.lowercase()
                    val wanted = expected.lowercase()
                    val match = when (operator) {
                        "=" -> lower == wanted || (field == "cls" && lower.endsWith("." + wanted))
                        "!=" -> lower != wanted
                        "~=" -> lower.contains(wanted)
                        "^=" -> lower.startsWith(wanted)
                        "$=" -> lower.endsWith(wanted)
                        else -> {
                            val left = actual.toDouble()
                            val right = expected.toDouble()
                            when (operator) {
                                ">" -> left > right
                                "<" -> left < right
                                ">=" -> left >= right
                                else -> left <= right
                            }
                        }
                    }
                    if (!match) return false
                }
            }
            if (params.optString("selector").isNotEmpty()) {
                val selector = params.getString("selector")
                if (selector.contains("||") || selector.contains("(") || selector.contains("!")) {
                    throw Failure("UNAVAILABLE", "complex selector requires the App session backend")
                }
                for (partRaw in selector.split(Regex("\\s*&&\\s*"))) {
                    var part = partRaw.trim()
                    if (part == "clickable" || part == "editable" || part == "visible") {
                        if (!node.optBoolean(part)) return false
                        continue
                    }
                    val pair = part.split(Regex("~?="), 2)
                    if (pair.size != 2) throw Failure("PARSE_ERROR", "invalid selector")
                    var field = pair[0].trim()
                    var expected = pair[1].trim()
                    val contains = field.endsWith("~")
                    if (contains) field = field.substring(0, field.length - 1)
                    if (expected.length >= 2 && (expected.startsWith("'") || expected.startsWith("\""))) {
                        expected = expected.substring(1, expected.length - 1)
                    }
                    val actual = node.optString(if (field == "class") "cls" else field)
                    if (if (contains) !actual.lowercase().contains(expected.lowercase()) else !actual.equals(expected, ignoreCase = true)) {
                        return false
                    }
                }
            }
            return true
        }

        @Throws(Exception::class)
        private fun verify(node: AccessibilityNodeInfo) {
            if (!node.refresh() || !node.isVisibleToUser) throw Failure("NOT_VISIBLE", "target is not visible")
            if (!node.isEnabled) throw Failure("NOT_CLICKABLE", "target is disabled")
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val size = run(arrayOf("wm", "size"), 5000).optString("stdout")
            val matcher = java.util.regex.Pattern.compile("([0-9]+)x([0-9]+)").matcher(size)
            var width = 0
            var height = 0
            while (matcher.find()) {
                width = Integer.parseInt(matcher.group(1))
                height = Integer.parseInt(matcher.group(2))
            }
            if (bounds.width() <= 0 || bounds.height() <= 0 || (width > 0 && !bounds.intersects(0, 0, width, height))) {
                throw Failure("NOT_VISIBLE", "target is outside the screen")
            }
        }

        @Throws(Exception::class)
        private fun scrollUntil(params: JSONObject): JSONObject {
            val action = if (params.optString("direction", "forward") == "backward")
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            for (i in 0..minOf(50, params.optInt("maxScrolls", 20))) {
                val found = find(params)
                if (found != null) {
                    try {
                        verify(found)
                        return nodeJson(found, "n1", null, 0, 0, "", found.windowId)
                            .put("scrolls", i).put("via", "uiautomation")
                    } finally {
                        found.recycle()
                    }
                }
                val root = root()
                if (root == null || !root.performAction(action)) {
                    root?.recycle()
                    throw Failure("NOT_FOUND", "target was not found and the container cannot scroll")
                }
                root.recycle()
                call("waitForIdle", arrayOf(Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!), 100L, 800L)
            }
            throw Failure("TIMEOUT", "target was not found within the scroll budget")
        }

        @Throws(Exception::class)
        private fun waitFor(params: JSONObject): JSONObject {
            val deadline = SystemClock.uptimeMillis() + minOf(120000L, params.optLong("timeoutMs", 10000))
            val gone = params.optString("until", "exists") == "gone"
            while (SystemClock.uptimeMillis() < deadline) {
                val node = find(params)
                if ((node != null) != gone) {
                    node?.recycle()
                    return JSONObject().put("matched", true).put("via", "uiautomation")
                }
                node?.recycle()
                SystemClock.sleep(100)
            }
            throw Failure("TIMEOUT", "UI condition was not met before timeout")
        }

        @Throws(Exception::class)
        private fun global(params: JSONObject): JSONObject {
            val action = params.getString("action")
            if (action == "assistant") {
                return success(arrayOf("am", "start", "-a", "android.intent.action.ASSIST"), 5000)
                    .put("requested", true).put("confirmed", false).put("via", "shell").put("degraded", true)
            }
            val value = when (action) {
                "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                "home" -> AccessibilityService.GLOBAL_ACTION_HOME
                "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                "quickSettings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                "lock" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                "splitScreen" -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                "powerDialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
                else -> -1
            }
            if (value < 0) throw Failure("UNAVAILABLE", "unknown global action")
            return JSONObject().put("performed",
                call("performGlobalAction", arrayOf(Int::class.javaPrimitiveType!!), value)).put("via", "uiautomation")
        }

        @Synchronized
        fun releaseIdle() {
            if (automation != null && SystemClock.uptimeMillis() - lastUse > 5000) {
                try {
                    val disconnect = automation!!.javaClass.getDeclaredMethod("disconnect")
                    disconnect.isAccessible = true
                    disconnect.invoke(automation)
                } catch (_: Exception) {
                }
                automation = null
            }
        }
    }

    private class Failure(val code: String, message: String) : Exception(message)

    @Throws(Exception::class)
    private fun run(command: Array<String>, timeoutMs: Long): JSONObject {
        val process = ProcessBuilder(*command).start()
        val stdout = Collector(process.inputStream, 262144)
        val stderr = Collector(process.errorStream, 262144)
        stdout.start()
        stderr.start()
        val timeout = maxOf(1000L, minOf(200000L, timeoutMs))
        if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            stdout.join(1000)
            stderr.join(1000)
            return JSONObject().put("exitCode", 124).put("stdout", stdout.text())
                .put("stderr", stderr.text()).put("via", "shell")
        }
        stdout.join(1000)
        stderr.join(1000)
        return JSONObject().put("exitCode", process.exitValue()).put("stdout", stdout.text())
            .put("stderr", stderr.text()).put("via", "shell")
    }

    @Throws(Exception::class)
    private fun success(command: Array<String>, timeoutMs: Long): JSONObject {
        val result = run(command, timeoutMs)
        if (result.optInt("exitCode") != 0) {
            var detail = result.optString("stderr")
            if (detail.isEmpty()) detail = result.optString("stdout")
            throw Failure("UNAVAILABLE", detail.substring(0, minOf(160, detail.length)))
        }
        return result
    }

    @Throws(Exception::class)
    private fun binary(command: Array<String>, timeoutMs: Long): ByteArray {
        val process = ProcessBuilder(*command).start()
        val output = ByteArrayOutputStream()
        val reader = Thread({
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0 || output.size() > 12 * 1024 * 1024) break
                        output.write(buffer, 0, count)
                    }
                }
            } catch (_: Exception) {
            }
        })
        reader.start()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        reader.join(2000)
        return output.toByteArray()
    }

    private class Collector(private val input: InputStream, private val limit: Int) : Thread() {
        private val bytes = ByteArrayOutputStream()

        override fun run() {
            try {
                input.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        if (bytes.size() < limit) bytes.write(buffer, 0, minOf(count, limit - bytes.size()))
                    }
                }
            } catch (_: Exception) {
            }
        }

        fun text(): String = String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    @Throws(Exception::class)
    private fun line(input: BufferedReader): String {
        val result = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) break
            if (c == '\n'.code) break
            if (c == '\r'.code) continue
            if (result.length > 1024 * 1024) throw Failure("INVALID_PARAMS", "request too large")
            result.append(c.toChar())
        }
        return result.toString()
    }

    @Throws(Exception::class)
    private fun read(file: File): String {
        FileInputStream(file).use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > 1024 * 1024) throw Failure("INVALID_PARAMS", "file is too large")
                bytes.write(buffer, 0, count)
            }
            return String(bytes.toByteArray(), StandardCharsets.UTF_8)
        }
    }

    @Throws(Exception::class)
    private fun write(file: File, value: String) {
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray(StandardCharsets.ISO_8859_1))
            output.fd.sync()
        }
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }

    @Throws(Exception::class)
    private fun allowed(value: String, mustExist: Boolean): File {
        val file = File(value).canonicalFile
        val path = file.path
        val download = File("/sdcard/Download").canonicalPath + File.separator
        val dsh = File("/sdcard/.dsh").canonicalPath + File.separator
        if ((!path.startsWith(download) && !path.startsWith(dsh)) || (mustExist && !file.isFile)) {
            throw Failure("PATH_NOT_ALLOWED", "path must be within Download or .dsh")
        }
        return file
    }

    @Throws(Failure::class)
    private fun pkg(params: JSONObject): String {
        val value = params.optString("package")
        if (!value.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
            throw Failure("INVALID_PARAMS", "invalid package name")
        }
        return value
    }

    @Throws(Failure::class)
    private fun namespace(params: JSONObject): String {
        val value = params.optString("namespace", "system")
        if (!value.matches(Regex("system|secure|global"))) throw Failure("INVALID_PARAMS", "invalid settings namespace")
        return value
    }

    @Throws(Failure::class)
    private fun name(params: JSONObject): String {
        val value = params.optString("name")
        if (!value.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) throw Failure("INVALID_PARAMS", "invalid setting name")
        return value
    }

    @Throws(Failure::class)
    private fun confirm(params: JSONObject) {
        if (params.optString("confirm") != "YES") throw Failure("CONFIRM_REQUIRED", "set confirm to YES")
    }

    @Throws(Exception::class)
    private fun error(code: String, message: String): JSONObject =
        JSONObject().put("code", code).put("message", message).put("detail", JSONObject())

    private fun string(value: CharSequence?): String = value?.toString() ?: ""

    @Throws(Exception::class)
    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        val result = StringBuilder()
        for (item in digest) result.append(String.format("%02x", item))
        return result.toString()
    }
}
