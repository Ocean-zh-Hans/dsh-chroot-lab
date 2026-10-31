package dev.dsh.agent

import org.json.JSONObject
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

internal object RootClient {
    private const val PORT = 17322
    private var lastCheck = 0L

    @Volatile
    private var available = false
    private var failures = 0L
    private var cooldownUntil = 0L
    private var id = 0L

    @Volatile
    private var failureReason = "NO_ROOT"
    private val PROBE = Executors.newSingleThreadExecutor()
    private var probeQueued = false

    @Synchronized
    @JvmStatic
    fun available(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastCheck < 2000) return available
        if (now < cooldownUntil) return false
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (!probeQueued) {
                probeQueued = true
                lastCheck = now
                PROBE.execute { probe() }
            }
            return available
        }
        probe()
        return available
    }

    private fun probe() {
        try {
            val result = request("root.ping", JSONObject())
            synchronized(this) {
                available = result.optBoolean("root")
                failures = 0
                failureReason = ""
                lastCheck = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            synchronized(this) {
                available = false
                failureReason = if (e is AgentError) e.code else e.javaClass.simpleName
                if (++failures >= 3) cooldownUntil = System.currentTimeMillis() + 30000
                lastCheck = System.currentTimeMillis()
            }
        } finally {
            synchronized(this) { probeQueued = false }
        }
    }

    /** helper 启动/停止后强制下一次探测，不要被 30s 失败冷却挡住。 */
    @JvmStatic
    fun invalidate() {
        synchronized(this) {
            lastCheck = 0L
            cooldownUntil = 0L
            failures = 0L
            available = false
            failureReason = "NO_ROOT"
            probeQueued = false
        }
    }

    private fun noRootReason(): String = if (failureReason.isEmpty()) "NO_ROOT" else failureReason

    /** 给 UI 用的短原因；root helper 是独立进程，不是 Android 运行时权限。 */
    @JvmStatic
    fun reason(): String {
        if (available) return ""
        return when {
            noRootReason() == "NO_ROOT" -> "root helper 未启动"
            noRootReason().contains("Connect", ignoreCase = true) -> "root helper 未启动（17322 未监听）"
            else -> "root helper 不可用（" + noRootReason() + "）"
        }
    }

    @JvmStatic
    fun status(): JSONObject {
        if (!available()) {
            return JSONObject().put("root", JSONObject().put("ok", false).put("reason", noRootReason()))
                .put("uiautomation", JSONObject().put("name", "uiautomation").put("ok", false).put("reason", noRootReason()))
                .put("shell", JSONObject().put("name", "shell").put("ok", false).put("reason", noRootReason()))
        }
        return try {
            val result = call("backend.status", JSONObject())
            if (result.has("layers")) {
                val formatted = JSONObject().put("root", result.getJSONObject("root"))
                val layers = result.getJSONArray("layers")
                for (i in 0 until layers.length()) {
                    val layer = layers.getJSONObject(i)
                    if (layer.optString("name") == "uiautomation") formatted.put("uiautomation", layer)
                    if (layer.optString("name") == "shell") formatted.put("shell", layer)
                }
                formatted
            } else result
        } catch (e: AgentError) {
            JSONObject().put("root", JSONObject().put("ok", false).put("reason", e.code))
                .put("uiautomation", JSONObject().put("name", "uiautomation").put("ok", false).put("reason", e.code))
                .put("shell", JSONObject().put("name", "shell").put("ok", false).put("reason", e.code))
        }
    }

    @JvmStatic
    fun call(method: String, params: JSONObject?): JSONObject {
        if (!available()) throw AgentError("NO_ROOT", "root helper is not running", "none", false)
        return request(method, params ?: JSONObject())
    }

    private fun request(method: String, params: JSONObject): JSONObject {
        val token = readToken()
        val started = System.nanoTime()
        try {
            Socket("127.0.0.1", PORT).use { socket ->
                val timeout = when {
                    method == "screen.record" -> 200000
                    method == "shell.exec" || method.startsWith("prep.") -> 125000
                    method == "root.ping" -> 3000
                    method == "backend.status" -> 5000
                    else -> 15000
                }
                socket.soTimeout = timeout
                val request = JSONObject().put("token", token).put("id", ++id).put("method", method).put("params", params)
                val output = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                output.write(request.toString() + "\n")
                output.flush()
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val line = boundedLine(input) ?: throw AgentError("TIMEOUT", "root helper returned no response", "shell", true)
                val response = JSONObject(line)
                if (!response.optBoolean("ok")) {
                    val error = response.optJSONObject("error")
                    val code = error?.optString("code", "INTERNAL") ?: "INTERNAL"
                    val message = error?.optString("message", "root helper error") ?: "root helper error"
                    val failure = AgentError(code, message, "shell", code == "TIMEOUT" || code == "SLOT_BUSY")
                    val detail = error?.optJSONObject("detail")
                    if (detail != null) {
                        val iterator = detail.keys()
                        while (iterator.hasNext()) { val key = iterator.next(); failure.detail(key, detail.get(key)) }
                    }
                    throw failure
                }
                val result = response.getJSONObject("result")
                if (!result.has("via")) result.put("via", "shell")
                if (!result.has("tookMs")) result.put("tookMs", (System.nanoTime() - started) / 1000000)
                if (!result.has("degraded") && (method.startsWith("ui.") || method == "shot")) {
                    result.put("degraded", result.optString("via") == "shell")
                }
                return result
            }
        } catch (e: java.io.IOException) {
            available = false
            failureReason = e.javaClass.simpleName
            throw AgentError("NO_ROOT", "root helper connection failed", "none", false)
        }
    }

    @JvmStatic
    fun eventSocket(params: JSONObject): Socket {
        if (!available()) throw AgentError("NO_ROOT", "root event stream is unavailable")
        val socket = Socket("127.0.0.1", PORT)
        try {
            socket.soTimeout = 15000
            val request = JSONObject().put("token", readToken()).put("id", ++id)
                .put("method", "events.subscribe").put("params", params)
            val output = OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
            output.write(request.toString() + "\n")
            output.flush()
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val line = boundedLine(input) ?: throw AgentError("TIMEOUT", "root event stream returned no acknowledgement")
            val response = JSONObject(line)
            if (!response.optBoolean("ok")) {
                val error = response.optJSONObject("error")
                throw AgentError(error?.optString("code") ?: "INTERNAL", "root event subscription failed")
            }
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            if (e is AgentError) throw e
            throw AgentError("NO_ROOT", "root event stream is unavailable")
        }
    }

    private fun readToken(): String {
        FileInputStream(AgentServer.tokenFile(AgentServer.context() ?: throw AgentError("INTERNAL", "agent is not initialized"))).use { input ->
            val bytes = ByteArray(33)
            val count = input.read(bytes)
            val value = String(bytes, 0, maxOf(0, count), StandardCharsets.UTF_8).trim()
            if (!value.matches(Regex("[0-9a-f]{32}"))) throw AgentError("UNAUTHORIZED", "invalid agent token")
            return value
        }
    }

    private fun boundedLine(input: BufferedReader): String? {
        val result = StringBuilder()
        var c: Int
        while (true) {
            c = input.read()
            if (c == -1) break
            if (c == '\n'.code) break
            if (c == '\r'.code) continue
            if (result.length >= 16 * 1024 * 1024) throw AgentError("OUTPUT_TOO_LARGE", "root helper response is too large")
            result.append(c.toChar())
        }
        return result.toString()
    }
}
