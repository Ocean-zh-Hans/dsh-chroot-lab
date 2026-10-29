package dev.dsh.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object Jobs {
    private class Task(val method: String, params: JSONObject?) {
        val id: String = UUID.randomUUID().toString()
        val created: Long = System.currentTimeMillis()
        val params: JSONObject = params ?: JSONObject()

        @Volatile var state: String = "queued"
        @Volatile var result: Any? = null
        @Volatile var running: Future<*>? = null
        @Volatile var scheduled: Future<*>? = null
        @Volatile var subscription: EventBus.Subscription? = null
        @Volatile var schedule: String? = null
        @Volatile var nextRun: Long = 0
    }

    private val TASKS = LinkedHashMap<String, Task>()
    private val EXECUTOR = ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS, ArrayBlockingQueue(64))
    private val SCHEDULER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var context: Context? = null

    @Synchronized
    @JvmStatic
    fun init(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        restore()
    }

    @JvmStatic
    fun start(method: String, params: JSONObject?): JSONObject {
        if (method.startsWith("job.") || method == "events.subscribe") {
            throw AgentError("INVALID_PARAMS", "job cannot contain another job or subscription")
        }
        val task = Task(method, params)
        synchronized(this) {
            trim()
            TASKS[task.id] = task
        }
        try {
            run(task)
            return status(task.id)
        } catch (e: Exception) {
            synchronized(this) { TASKS.remove(task.id) }
            throw e
        }
    }

    private fun run(task: Task) {
        task.state = "running"
        val future = EXECUTOR.submit {
            try {
                task.result = AgentServer.dispatch(task.method, task.params)
                task.state = "done"
            } catch (error: Throwable) {
                task.state = "failed"
                task.result = AgentServer.error(if (error is AgentError) error.code else "INTERNAL", "job failed")
            } finally {
                synchronized(this) { task.running = null }
            }
        }
        task.running = future
        if (task.state == "done" || task.state == "failed") task.running = null
    }

    @Synchronized
    @JvmStatic
    fun status(id: String): JSONObject {
        val task = TASKS[id] ?: throw AgentError("NOT_FOUND", "unknown job")
        val result = JSONObject().put("jobId", task.id).put("state", task.state)
            .put("method", task.method).put("created", task.created)
        if (task.schedule != null) result.put("schedule", task.schedule).put("nextRun", task.nextRun)
        if (task.result != null) result.put("result", task.result)
        return result
    }

    @Synchronized
    @JvmStatic
    fun cancel(id: String): JSONObject {
        val task = TASKS[id] ?: throw AgentError("NOT_FOUND", "unknown job")
        if (task.schedule == null && (task.state == "done" || task.state == "failed")) return status(id)
        task.running?.cancel(true)
        task.scheduled?.cancel(false)
        task.subscription?.close()
        task.state = "cancelled"
        task.nextRun = 0
        val persisted = persist()
        return status(id).put("persisted", persisted)
    }

    @JvmStatic
    fun schedule(method: String, params: JSONObject?, spec: JSONObject): JSONObject {
        if (method.startsWith("job.") || method == "events.subscribe") {
            throw AgentError("INVALID_PARAMS", "scheduled job cannot contain another job or subscription")
        }
        val task = Task(method, params)
        task.schedule = spec.toString()
        synchronized(this) {
            trim()
            TASKS[task.id] = task
        }
        try {
            val event = spec.optString("on")
            if (event.isNotEmpty()) {
                task.subscription = AgentServer.events.subscribe { message ->
                    if (message.optString("event") != event) return@subscribe
                    synchronized(this) {
                        if (task.state == "cancelled" || task.state == "running") return@synchronized
                        run(task)
                    }
                }
            } else {
                task.nextRun = if (spec.has("at")) spec.getLong("at")
                else Cron.parse(spec.getString("cron")).next(System.currentTimeMillis(), ZoneId.systemDefault())
                task.state = "scheduled"
                task.scheduled = SCHEDULER.schedule({
                    run(task)
                    if (spec.has("cron")) scheduleNext(task)
                }, maxOf(0, task.nextRun - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
            }
            if (!persist()) throw AgentError("PERSIST_FAILED", "cannot persist scheduled job")
            return status(task.id)
        } catch (e: Exception) {
            cancel(task.id)
            throw e
        }
    }

    private fun scheduleNext(task: Task) {
        synchronized(this) {
            if (task.state == "cancelled") return
            try {
                task.nextRun = Cron.parse(task.schedule?.let { JSONObject(it).getString("cron") } ?: "")
                    .next(System.currentTimeMillis(), ZoneId.systemDefault())
                task.state = "scheduled"
                task.scheduled = SCHEDULER.schedule({
                    run(task)
                    scheduleNext(task)
                }, maxOf(0, task.nextRun - System.currentTimeMillis()), TimeUnit.MILLISECONDS)
                persist()
            } catch (_: Exception) {
                task.state = "failed"
            }
        }
    }

    private fun trim() {
        val cutoff = System.currentTimeMillis() - 86400000
        val iterator = TASKS.entries.iterator()
        while (iterator.hasNext() && TASKS.size >= 100) {
            val task = iterator.next().value
            if (task.running == null && task.scheduled == null && task.subscription == null && task.created < cutoff) iterator.remove()
        }
        if (TASKS.size >= 100) throw IllegalStateException("job limit reached")
    }

    private fun file(): File = File(context!!.filesDir, "schedules.json")

    @Synchronized
    private fun persist(): Boolean {
        if (context == null) return false
        return try {
            val list = JSONArray()
            for (task in TASKS.values) {
                if (task.schedule == null || task.state == "cancelled") continue
                list.put(JSONObject().put("method", task.method).put("params", task.params).put("schedule", task.schedule))
            }
            val temp = File(context!!.filesDir, "schedules-" + UUID.randomUUID() + ".tmp")
            FileOutputStream(temp).use { output ->
                output.write(JSONObject().put("version", 1).put("jobs", list).toString().toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            temp.setReadable(false, false)
            temp.setReadable(true, true)
            Files.move(temp.toPath(), file().toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            file().setReadable(false, false)
            file().setReadable(true, true)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun restore() {
        val target = file()
        if (!target.isFile) return
        try {
            FileInputStream(target).use { input ->
                val bytes = ByteArray(minOf(target.length(), 1048576L).toInt())
                val count = input.read(bytes)
                val list = JSONObject(String(bytes, 0, maxOf(0, count), StandardCharsets.UTF_8)).getJSONArray("jobs")
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    schedule(item.getString("method"), item.optJSONObject("params"), JSONObject(item.getString("schedule")))
                }
            }
        } catch (_: Exception) {
        }
    }
}
