package dev.dsh.agent

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.ArrayList
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object AgentServer {
    private const val PORT = 17321
    private const val MAX_LINE = 1024 * 1024
    private const val MAX_RESPONSE = 16 * 1024 * 1024

    @JvmField
    val events = EventBus()

    private val CONNECTIONS = CopyOnWriteArrayList<Connection>()
    @Volatile
    private var heartbeat: ScheduledExecutorService? = null
    private val EVENT_WRITERS: ExecutorService = Executors.newCachedThreadPool()
    private val IDEMPOTENT = LinkedHashMap<String, Idempotent>()

    @Volatile
    private var context: Context? = null

    @Volatile
    private var service: AccessibilityService? = null

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var lastError: String? = null
    private var tokenInitialized = false

    @Volatile
    private var clients: ExecutorService? = null

    @Volatile
    private var lastUiEvent: Long = 0

    @Volatile
    private var foregroundActivity: Activity? = null

    @JvmStatic
    @Synchronized
    fun start(ctx: Context, svc: AccessibilityService?) {
        val app = ctx.applicationContext
        context = app
        service = svc
        Jobs.init(app)
        SystemEvents.init(app)
        ClipboardHistory.start(app)
        if (!tokenInitialized) {
            if (app.getSharedPreferences("agent", 0).getBoolean("rotateOnStart", false)
                && tokenFile(app).isFile && rotateToken().isEmpty()
            ) {
                lastError = "token rotation failed"
                return
            }
            tokenInitialized = true
        }
        if (token(app).isEmpty()) {
            lastError = "token initialization failed"
            return
        }
        if (thread != null && thread!!.isAlive) return
        clients = ThreadPoolExecutor(4, 16, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32)) { runnable ->
            Thread(runnable, "dsh-agent-client").apply { isDaemon = true }
        }
        thread = Thread({ acceptLoop() }, "dsh-agent-server").apply { isDaemon = true }
        thread!!.start()
        if (heartbeat == null) {
            heartbeat = Executors.newSingleThreadScheduledExecutor().also { service ->
                service.scheduleWithFixedDelay({
                    for (connection in CONNECTIONS) connection.heartbeat()
                }, 15, 15, TimeUnit.SECONDS)
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun stop() {
        SystemEvents.close()
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        for (connection in CONNECTIONS) connection.close()
        clients?.shutdownNow()
        clients = null
        heartbeat?.shutdownNow()
        heartbeat = null
        val old = thread
        if (old != null && old !== Thread.currentThread()) {
            try {
                old.join(3000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
        service = null
    }

    @JvmStatic
    @Synchronized
    fun detach(svc: AccessibilityService?) {
        if (service === svc) {
            service = null
            UiEngine.get().serviceDetached()
            PresenceState.markDisconnected()
        }
    }

    @JvmStatic
    fun accessibility(): AccessibilityService? = service

    @JvmStatic
    fun context(): Context? = context

    @JvmStatic
    fun attachActivity(activity: Activity) {
        // 打开 App 查看状态不能暂停 AI；只有明确的接管/交接才暂停。
        foregroundActivity = activity
    }

    @JvmStatic
    fun detachActivity(activity: Activity) {
        if (foregroundActivity === activity) foregroundActivity = null
    }

    @JvmStatic
    fun activity(): Activity? = foregroundActivity

    @JvmStatic
    fun requestHandoff(reason: String) {
        PresenceState.requestHandoff(context, reason)
    }

    @JvmStatic
    fun summary(): String = status() + "\n" +
        (context?.let { PlatformProfile.summary(it) } ?: "平台信息未初始化") +
        "\n文件面：由 DSH shell 管理（APK 不持有共享存储）" +
        "\n无障碍：" + (if (service != null) "已连接" else "未连接") +
        "\n通知使用权：" + (if (NotificationRelay.connected()) "已连接" else "未连接") +
        "\nroot helper：" + (if (RootClient.available()) "已连接" else "未连接") +
        "\n前台保活：" + (if (context != null && AgentRuntimeService.enabled(context!!)) "开启" else "关闭") +
        "\nAgent：" + (if (PresenceState.isPaused()) "暂停" else "运行")

    @JvmStatic
    fun status(): String = if (server != null) "listening on 127.0.0.1:17321" else lastError ?: "stopped"

    @JvmStatic
    fun tokenFile(ctx: Context): File = File(ctx.filesDir, "token")

    @Synchronized
    private fun token(ctx: Context): String {
        val file = tokenFile(ctx)
        return try {
            if (file.isFile) {
                val existing = read(file).trim()
                if (existing.matches(Regex("[0-9a-f]{32}"))) return existing
            }
            val value = UUID.randomUUID().toString().replace("-", "")
            FileOutputStream(file).use { output ->
                output.write(value.toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            file.setReadable(false, false)
            file.setReadable(true, true)
            file.setWritable(false, false)
            file.setWritable(true, true)
            value
        } catch (_: Exception) {
            ""
        }
    }

    @Synchronized
    private fun rotateToken(): String {
        val ctx = context ?: return ""
        return if (tokenFile(ctx).delete()) {
            val value = token(ctx)
            if (value.isEmpty()) "" else "rotated"
        } else {
            ""
        }
    }

    private fun read(file: File): String {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(minOf(file.length(), 4096L).toInt())
            val count = input.read(buffer)
            return if (count <= 0) "" else String(buffer, 0, count, StandardCharsets.UTF_8)
        }
    }

    private fun acceptLoop() {
        lastError = null
        try {
            val listener = ServerSocket()
            listener.reuseAddress = true
            listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 16)
            server = listener
            while (!listener.isClosed) {
                try {
                    val socket = listener.accept()
                    val connection = Connection(socket)
                    CONNECTIONS.add(connection)
                    clients?.execute(connection)
                } catch (_: SocketException) {
                    if (!listener.isClosed) lastError = "socket accept failed"
                } catch (_: Exception) {
                    lastError = "client limit reached"
                }
            }
        } catch (_: Exception) {
            lastError = "loopback listener unavailable"
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun dispatch(method: String, params: JSONObject?): Any? {
        val actual = params ?: JSONObject()
        if (context == null) throw AgentError("INTERNAL", "agent is not initialized")
        if (method.startsWith("ui.") || method.startsWith("notif.") || method.startsWith("handoff.")) {
            PresenceState.noteRequest(method)
        }
        if (readonly() && isMutation(method, actual)) throw AgentError("READONLY", "read-only mode rejects device mutation")
        if (PresenceState.isPaused() && isMutation(method, actual)) {
            throw AgentError("PAUSED", "agent is paused: resume from the DSH notification or quick settings tile")
                .detail("hint", "notification action PRESENCE_RESUME or QS tile")
        }
        val key = actual.optString("idempotencyKey")
        if (key.isEmpty() || !isMutation(method, actual)) return dispatchInternal(method, actual)
        if (key.length > 128) throw AgentError("INVALID_PARAMS", "idempotencyKey is too long")
        val digest = digest(method, actual)
        val mapKey = method + "\n" + key
        var owner: Idempotent? = null
        var mine = false
        synchronized(IDEMPOTENT) {
            pruneIdempotency()
            val old = IDEMPOTENT[mapKey]
            if (old != null) {
                if (old.digest != digest) throw AgentError("CONFLICT", "idempotencyKey has different parameters")
                if (!old.pending) return replay(old)
                owner = old
            } else {
                if (IDEMPOTENT.size >= 100) throw AgentError("TOO_MANY_REQUESTS", "too many pending idempotent requests")
                owner = Idempotent(digest)
                IDEMPOTENT[mapKey] = owner!!
                mine = true
            }
        }
        val current = owner!!
        if (!mine) {
            if (!current.done.await(35, TimeUnit.SECONDS)) {
                throw AgentError("TIMEOUT", "outcome is unknown; verify with a read request").detail("resultState", "UNKNOWN")
            }
            return replay(current)
        }
        try {
            val result = dispatchInternal(method, actual)
            synchronized(IDEMPOTENT) { current.complete(result) }
            return result
        } catch (error: AgentError) {
            if (error.code == "TIMEOUT") error.resultState = "UNKNOWN"
            synchronized(IDEMPOTENT) { current.fail(error) }
            throw error
        } catch (error: Exception) {
            synchronized(IDEMPOTENT) { current.fail(AgentError("INTERNAL", "internal error")) }
            throw error
        }
    }

    @Throws(Exception::class)
    private fun replay(old: Idempotent): Any? {
        if (old.error != null) {
            val error = AgentError(old.error!!, old.errorMessage ?: "")
            error.replayed = true
            if (old.error == "TIMEOUT") error.resultState = "UNKNOWN"
            throw error
        }
        return if (old.result is JSONObject) JSONObject(old.result!!.toString()).put("replayed", true) else old.result
    }

    @JvmStatic
    fun isMutation(method: String): Boolean = AgentMethods.isMutation(method)

    @JvmStatic
    fun isMutation(method: String, params: JSONObject?): Boolean = AgentMethods.isMutation(method, params)

    @JvmStatic
    fun isReadonly(): Boolean = readonly()

    @Throws(Exception::class)
    private fun ping(): JSONObject = JSONObject().put("ok", true).put("via", "app")
        .put("version", AgentMethods.VERSION).put("apiVersion", AgentMethods.API_VERSION)
        .put("buildTime", AgentMethods.BUILD_TIME).put("service", service != null).put("port", PORT)
        .put("readonly", readonly())

    @Throws(Exception::class)
    private fun selftest(): JSONObject {
        val checks = JSONArray()
        checks.put(check("a11y", service != null, if (service != null) "" else "accessibility service is not connected"))
        val root = RootClient.available()
        checks.put(check("root", root, if (root) "" else "root helper is not connected"))
        var engine = false
        var failure = ""
        try {
            UiEngine.get().call("ui.refresh", JSONObject())
            engine = true
        } catch (error: Throwable) {
            failure = error.message ?: ""
        }
        checks.put(check("engine", engine, failure))
        return JSONObject().put("ok", service != null && engine).put("via", "app").put("checks", checks)
    }

    private val SESSIONS = LinkedHashMap<String, JSONObject>()
    private var currentSession = ""

    @Throws(Exception::class)
    private fun dispatchInternal(method: String, params: JSONObject): Any? {
        val started = System.nanoTime()
        var via = "app"
        var failure = ""
        try {
            if (EngineRouter.handles(method)) {
                val result = EngineRouter.dispatch(method, withSession(params))
                if (result is JSONObject) via = result.optString("via", "a11y")
                return result
            }
            return when (method) {
                "ping" -> ping()
                "session.open" -> sessionOpen(params)
                "session.list" -> sessionList()
                "session.use" -> sessionUse(params)
                "session.current", "session.status" -> sessionCurrent()
                "session.close", "session.drop" -> sessionClose(params)
                "policy.get" -> policyGet()
                "policy.set" -> policySet(params)
                "dev.log.tail" -> JSONObject().put("ok", true).put("via", via)
                    .put("entries", Diagnostics.tail(params.optInt("n", 50)))
                "dev.log.clear" -> {
                    Diagnostics.clear()
                    JSONObject().put("ok", true).put("cleared", true)
                }
                "dev.selftest" -> selftest()
                "handoff.request" -> {
                    PresenceState.requestHandoff(context, params.optString("reason", ""))
                    JSONObject().put("ok", true).put("via", "app").put("paused", true)
                }
                "receipts.append" -> {
                    val status = params.optString("status", "ok")
                    val title = params.optString("title", "")
                    val receipt = ReceiptStore.append(
                        context!!,
                        params.optString("taskId", ""),
                        title,
                        status,
                        params.optJSONArray("steps"),
                        params.optString("error", ""),
                    )
                    if (status != "running") {
                        PresenceState.markDone(title, status)
                        PresenceNotification.showCompletion(context!!, title, status)
                        PresenceNotification.update(context)
                    }
                    JSONObject().put("ok", true).put("via", "app").put("receipt", receipt)
                }
                "receipts.list" -> JSONObject().put("ok", true).put("via", "app")
                    .put("receipts", ReceiptStore.list(context!!, params.optInt("limit", 20)))
                else -> throw AgentError("BAD_REQUEST", "unknown method: " + method)
                    .detail("hint", "v3: ui.view/ui.nodes/ui.diff/ui.expect/ui.wait/ui.windows/ui.shot/ui.gesture/ui.act/ui.batch/" +
                        "ui.fill/ui.scrollUntil/ui.press/ui.ime/ui.clipboard/notif.list|history|dismiss|reply/sys.status/handoff.request/receipts.*/session.*/events.*/policy.*/dev.*")
            }
        } catch (error: AgentError) {
            failure = error.code
            throw error
        } catch (error: Exception) {
            failure = "INTERNAL"
            throw error
        } finally {
            Diagnostics.record(method, via, failure, (System.nanoTime() - started) / 1000000)
        }
    }

    @Throws(Exception::class)
    private fun withSession(params: JSONObject): JSONObject {
        val id = params.optString("session", "")
        var session: JSONObject? = null
        if (id.isNotEmpty()) {
            synchronized(SESSIONS) { session = SESSIONS[id] }
            if (session == null) throw AgentError("NOT_FOUND", "no such session: " + id).detail("sessions", sessionIds())
        } else if (currentSession.isNotEmpty()) {
            synchronized(SESSIONS) { session = SESSIONS[currentSession] }
        }
        if (session != null) {
            session!!.put("calls", session!!.optInt("calls") + 1).put("lastUsedAt", System.currentTimeMillis())
            if (!params.has("backend")) {
                val backend = session!!.optString("backend", "auto")
                if (backend.isNotEmpty() && backend != "auto") params.put("backend", backend)
            }
        }
        return params
    }

    private fun sessionIds(): JSONArray {
        val ids = JSONArray()
        synchronized(SESSIONS) {
            for (id in SESSIONS.keys) ids.put(id)
        }
        return ids
    }

    @Throws(Exception::class)
    private fun sessionOpen(params: JSONObject): JSONObject {
        val id = "s-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        val session = JSONObject().put("id", id).put("name", params.optString("name", ""))
            .put("backend", params.optString("backend", "auto"))
            .put("createdAt", System.currentTimeMillis()).put("calls", 0)
        synchronized(SESSIONS) {
            if (SESSIONS.size >= 8) throw AgentError("UNAVAILABLE", "at most 8 sessions; close one first")
            SESSIONS[id] = session
        }
        currentSession = id
        return JSONObject().put("ok", true).put("via", "app").put("session", JSONObject(session.toString())).put("current", id)
    }

    @Throws(Exception::class)
    private fun sessionList(): JSONObject {
        val items = JSONArray()
        synchronized(SESSIONS) {
            for (session in SESSIONS.values) items.put(JSONObject(session.toString()))
        }
        return JSONObject().put("ok", true).put("via", "app").put("sessions", items).put("current", currentSession)
    }

    @Throws(Exception::class)
    private fun sessionUse(params: JSONObject): JSONObject {
        val id = params.optString("id", params.optString("session", ""))
        synchronized(SESSIONS) {
            if (SESSIONS[id] == null) throw AgentError("NOT_FOUND", "no such session: " + id).detail("sessions", sessionIds())
        }
        currentSession = id
        return JSONObject().put("ok", true).put("via", "app").put("current", id)
    }

    @Throws(Exception::class)
    private fun sessionCurrent(): JSONObject {
        var session: JSONObject? = null
        synchronized(SESSIONS) {
            if (currentSession.isNotEmpty()) session = SESSIONS[currentSession]
        }
        return JSONObject().put("ok", true).put("via", "app").put("current", currentSession)
            .put("session", if (session == null) JSONObject.NULL else JSONObject(session!!.toString()))
    }

    @Throws(Exception::class)
    private fun sessionClose(params: JSONObject): JSONObject {
        val id = params.optString("id", params.optString("session", currentSession))
        var removed = false
        synchronized(SESSIONS) { removed = SESSIONS.remove(id) != null }
        if (!removed) throw AgentError("NOT_FOUND", "no such session: " + id)
        if (id == currentSession) currentSession = ""
        return JSONObject().put("ok", true).put("via", "app").put("closed", id)
    }

    @Throws(Exception::class)
    private fun policyGet(): JSONObject {
        val ctx = context ?: throw AgentError("INTERNAL", "agent is not initialized")
        val policy = JSONObject().put("backend", ctx.getSharedPreferences("agent", 0).getString("backend", "auto"))
            .put("readonly", readonly())
        return JSONObject().put("ok", true).put("via", "app").put("policy", policy)
    }

    @Throws(Exception::class)
    private fun policySet(params: JSONObject): JSONObject {
        val ctx = context ?: throw AgentError("INTERNAL", "agent is not initialized")
        if (params.has("readonly")) {
            val readonly = params.optBoolean("readonly", true)
            if (!readonly) AgentError.confirm(params)
            ctx.getSharedPreferences("agent", 0).edit().putBoolean("readonly", readonly).apply()
        }
        if (params.has("backend")) {
            val backend = params.optString("backend", "auto")
            if (backend != "auto" && backend != "a11y" && backend != "uia" && backend != "shell") {
                throw AgentError("BAD_REQUEST", "backend must be auto|a11y|uia|shell")
                    .detail("alternatives", JSONArray().put("auto").put("a11y").put("uia").put("shell"))
            }
            ctx.getSharedPreferences("agent", 0).edit().putString("backend", backend).apply()
        }
        return policyGet()
    }

    private fun check(item: String, ok: Boolean, error: String?): JSONObject {
        val valid = ok && (error == null || error.isEmpty())
        return JSONObject().put("item", item).put("ok", valid)
            .put("err", if (valid) "" else if (error == null || error.isEmpty()) "FAILED" else error)
    }

    private fun readonly(): Boolean = context != null &&
        context!!.getSharedPreferences("agent", 0).getBoolean("readonly", false)

    @JvmStatic
    fun error(code: String, message: String): JSONObject = try {
        JSONObject().put("code", code).put("message", message).put("detail", JSONObject())
    } catch (_: Exception) {
        JSONObject()
    }

    @JvmStatic
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        UiClock.note()
        try {
            val pkg = event.packageName?.toString() ?: ""
            if (pkg != "com.android.systemui" && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || pkg == events.foreground())
            ) {
                events.uiChanged(pkg)
            }
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                UiClock.page(pkg, event.className?.toString() ?: "")
                val previous = events.foreground()
                events.foreground(pkg)
                if (previous != pkg) events.publish("app.foreground", JSONObject().put("package", pkg))
            }
            val now = System.currentTimeMillis()
            if (now - lastUiEvent >= 100) {
                lastUiEvent = now
                events.publish("ui.changed", JSONObject().put("package", pkg).put("windowId", event.windowId)
                    .put("eventType", event.eventType))
            }
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                events.publish("toast", JSONObject().put("package", pkg).put("text", event.text.toString()))
            }
        } catch (_: Exception) {
            Diagnostics.record("ui.event", "a11y", "EVENT_FAILED", 0)
        }
    }

    @JvmStatic
    fun onNotification(type: String, key: String, pkg: String, posted: Long) {
        try {
            events.publish(type, JSONObject().put("key", key).put("package", pkg).put("posted", posted))
        } catch (_: Exception) {
        }
    }

    private fun canonical(value: Any?): String {
        if (value == null || value == JSONObject.NULL) return "null"
        if (value is JSONObject) {
            val objectValue = value
            val sorted = TreeMap<String, Any?>()
            val iterator = objectValue.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                sorted[key] = objectValue.get(key)
            }
            val result = StringBuilder("{")
            for ((key, item) in sorted) {
                if (result.length > 1) result.append(',')
                result.append(JSONObject.quote(key)).append(':').append(canonical(item))
            }
            return result.append('}').toString()
        }
        if (value is JSONArray) {
            val result = StringBuilder("[")
            for (i in 0 until value.length()) {
                if (i > 0) result.append(',')
                result.append(canonical(value.get(i)))
            }
            return result.append(']').toString()
        }
        return if (value is String) JSONObject.quote(value) else value.toString()
    }

    private fun digest(method: String, params: JSONObject): String =
        sha256(method + "\n" + params.optString("session") + "\n" + canonical(params))

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(StandardCharsets.UTF_8))
        val out = StringBuilder()
        for (b in bytes) out.append(String.format("%02x", b))
        return out.toString()
    }

    private fun pruneIdempotency() {
        val now = System.currentTimeMillis()
        val iterator = IDEMPOTENT.entries.iterator()
        while (iterator.hasNext()) {
            val value = iterator.next().value
            if (!value.pending && value.expires < now) iterator.remove()
        }
        if (IDEMPOTENT.size >= 100) {
            val second = IDEMPOTENT.entries.iterator()
            while (second.hasNext()) {
                if (!second.next().value.pending) {
                    second.remove()
                    break
                }
            }
        }
    }

    private class Idempotent(val digest: String) {
        val done = CountDownLatch(1)

        @Volatile
        var result: Any? = null

        @Volatile
        var error: String? = null

        @Volatile
        var errorMessage: String? = null

        @Volatile
        var pending: Boolean = true

        @Volatile
        var expires: Long = System.currentTimeMillis() + 120000

        fun complete(value: Any?) {
            result = if (value is JSONObject) JSONObject(value.toString()) else value
            pending = false
            expires = System.currentTimeMillis() + 30000
            done.countDown()
        }

        fun fail(failure: AgentError) {
            error = failure.code
            errorMessage = failure.message
            pending = false
            expires = System.currentTimeMillis() + 30000
            done.countDown()
        }
    }

    private class Connection(private val socket: Socket) : Runnable {
        @Volatile
        var output: PrintWriter? = null

        @Volatile
        var subscription: EventBus.Subscription? = null

        @Volatile
        var subscriptionId: Any? = null

        @Volatile
        var rootEvents: Socket? = null

        @Volatile
        var closed = false

        override fun run() {
            try {
                socket.soTimeout = 15000
                val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                output = PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                while (!closed) {
                    val line = readBoundedLine(input) ?: break
                    val request = JSONObject(line)
                    val id: Any = request.opt("id") ?: 0
                    val started = System.nanoTime()
                    val expected = token(context ?: break)
                    val authorized = expected.isNotEmpty() && MessageDigest.isEqual(
                        expected.toByteArray(StandardCharsets.UTF_8),
                        request.optString("token").toByteArray(StandardCharsets.UTF_8)
                    )
                    if (!authorized) {
                        send(JSONObject().put("id", id).put("ok", false).put("error", error("UNAUTHORIZED", "unauthorized")))
                        break
                    }
                    val method = request.optString("method")
                    val params = request.optJSONObject("params") ?: JSONObject()
                    if (method == "events.subscribe") {
                        subscribe(id, params)
                        continue
                    }
                    if (method == "events.unsubscribe") {
                        unsubscribe()
                        send(JSONObject().put("id", id).put("ok", true).put("result", JSONObject().put("unsubscribed", true)))
                        continue
                    }
                    try {
                        val result = dispatch(method, params)
                        val metadata = result as? JSONObject
                        send(JSONObject().put("id", id).put("ok", true)
                            .put("via", metadata?.optString("via", "app") ?: "app")
                            .put("degraded", metadata?.optBoolean("degraded") ?: false)
                            .put("tookMs", (System.nanoTime() - started) / 1000000)
                            .put("result", result))
                        if (method == "security.tokenRotate") disconnectExcept(this)
                    } catch (error: AgentError) {
                        send(JSONObject().put("id", id).put("ok", false).put("via", error.via)
                            .put("resultState", error.resultState).put("replayed", error.replayed)
                            .put("error", JSONObject().put("code", error.code).put("message", error.message)
                                .put("retryable", error.retryable)
                                .put("hint", error.detail.optString("hint", ""))
                                .put("detail", error.detail)))
                    } catch (error: IllegalArgumentException) {
                        send(JSONObject().put("id", id).put("ok", false).put("error", error("INVALID_PARAMS", error.message ?: "")))
                    } catch (error: Exception) {
                        send(JSONObject().put("id", id).put("ok", false).put("error", error("INTERNAL", "internal error")))
                    }
                }
            } catch (_: Exception) {
            } finally {
                close()
            }
        }

        @Throws(Exception::class)
        fun subscribe(id: Any, params: JSONObject) {
            unsubscribe()
            subscriptionId = id
            val types = HashSet<String>()
            params.optJSONArray("types")?.let { requested ->
                for (i in 0 until requested.length()) types.add(requested.getString(i))
            }
            val packages = HashSet<String>()
            params.optJSONArray("packages")?.let { requested ->
                for (i in 0 until requested.length()) packages.add(requested.getString(i))
            }
            val since = params.optLong("since", 0)
            val processId = params.optString("processId", params.optString("bootId"))
            val created = events.subscription(types, packages)
            val replay = events.subscribe(created, since, processId, types, packages)
            subscription = created
            socket.soTimeout = 0
            send(JSONObject().put("id", id).put("ok", true).put("result", JSONObject()
                .put("subscribed", true).put("bootId", events.bootId).put("processId", events.bootId)
                .put("baseSeq", replay.baseSeq).put("resetRequired", replay.resetRequired)))
            for (i in 0 until replay.events.length()) event(replay.events.getJSONObject(i))
            if (types.contains("crash.anr")) {
                try {
                    val root = RootClient.eventSocket(JSONObject().put("types", JSONArray().put("crash.anr")))
                    rootEvents = root
                    EVENT_WRITERS.execute {
                        try {
                            val input = BufferedReader(InputStreamReader(root.getInputStream(), StandardCharsets.UTF_8))
                            while (!closed && rootEvents === root) {
                                val line = readBoundedLine(input) ?: break
                                val event = JSONObject(line)
                                val data = event.optJSONObject("data") ?: JSONObject()
                                data.put("rootSeq", event.optLong("seq"))
                                events.publish(event.optString("event", "crash.anr"), data)
                            }
                        } catch (_: Exception) {
                        } finally {
                            try {
                                root.close()
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (error: AgentError) {
                    event(JSONObject().put("event", "events.backend")
                        .put("data", JSONObject().put("reason", error.code)))
                }
            }
            EVENT_WRITERS.execute {
                try {
                    while (!closed && subscription === created) {
                        val event = created.take() ?: break
                        if (types.isEmpty() || types.contains(event.optString("event"))) event(event)
                    }
                } catch (_: Exception) {
                }
            }
        }

        @Throws(Exception::class)
        fun event(source: JSONObject) {
            send(JSONObject(source.toString()))
        }

        fun heartbeat() {
            if (subscription == null || closed) return
            try {
                send(JSONObject().put("event", "events.heartbeat").put("seq", events.sequence())
                    .put("processId", events.bootId).put("time", System.currentTimeMillis()))
            } catch (_: Exception) {
            }
        }

        fun unsubscribe() {
            val old = subscription
            subscription = null
            old?.close()
            val root = rootEvents
            rootEvents = null
            if (root != null) {
                try {
                    root.close()
                } catch (_: Exception) {
                }
            }
            try {
                socket.soTimeout = 15000
            } catch (_: Exception) {
            }
        }

        @Synchronized
        fun send(value: JSONObject) {
            val output = output
            if (closed || output == null) return
            var text = value.toString()
            if (text.length > MAX_RESPONSE) {
                text = try {
                    JSONObject().put("ok", false).put("error", error("OUTPUT_TOO_LARGE", "response exceeds limit")).toString()
                } catch (_: Exception) {
                    "{}"
                }
            }
            output.println(text)
            if (output.checkError()) close()
        }

        fun close() {
            if (closed) return
            closed = true
            unsubscribe()
            try {
                socket.close()
            } catch (_: Exception) {
            }
            CONNECTIONS.remove(this)
        }
    }

    private fun readBoundedLine(input: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) break
            if (c == '\n'.code) break
            if (c == '\r'.code) continue
            if (line.length >= MAX_LINE) return null
            line.append(c.toChar())
        }
        return line.toString()
    }

    private fun disconnectExcept(current: Connection) {
        for (connection in CONNECTIONS) if (connection !== current) connection.close()
    }
}
