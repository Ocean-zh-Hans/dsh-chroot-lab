package dev.dsh.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayList
import java.util.Collections
import java.util.HashSet
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

class EventBus {
    interface Subscription : AutoCloseable {
        @Throws(InterruptedException::class)
        fun take(): JSONObject?
        override fun close()
    }

    class Replay(@JvmField val events: JSONArray, @JvmField val baseSeq: Long, @JvmField val resetRequired: Boolean)

    private val entries = arrayOfNulls<JSONObject>(LIMIT)
    private val listeners = ArrayList<QueueSubscription>()
    private val callbacks: ExecutorService = Executors.newCachedThreadPool()
    val bootId: String = UUID.randomUUID().toString()
    private var sequence: Long = 0
    private var generation: Long = 0
    private var lastUiChange: Long = 0
    private var windowStarted: Long = 0
    private var windowEvents: Long = 0
    private var foreground: String = ""

    @Synchronized
    fun generation(): Long = generation

    @Synchronized
    fun lastUiChange(): Long = lastUiChange

    @Synchronized
    fun foreground(): String = foreground

    @Synchronized
    fun sequence(): Long = sequence

    @Synchronized
    fun foreground(value: String?) {
        foreground = value ?: ""
    }

    @Synchronized
    fun uiChanged(ignored: String?) {
        generation++
        lastUiChange = System.currentTimeMillis()
        (this as Object).notifyAll()
    }

    @Synchronized
    @Throws(InterruptedException::class)
    fun waitForChange(previous: Long, timeoutMs: Long): Long {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (generation == previous) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            (this as Object).wait(minOf(remaining, 1000))
        }
        return generation
    }

    private fun frame(type: String, data: JSONObject?): JSONObject? {
        sequence++
        val now = System.currentTimeMillis()
        if (windowStarted == 0L || now - windowStarted >= 1000) {
            windowStarted = now
            windowEvents = 0
        }
        windowEvents++
        return try {
            val event = JSONObject().put("event", type).put("seq", sequence).put("time", now)
                .put("ts", now).put("processId", bootId)
                .put("data", data ?: JSONObject())
            entries[(sequence % LIMIT).toInt()] = event
            event
        } catch (_: Exception) {
            null
        }
    }

    fun publish(type: String, data: JSONObject?) {
        synchronized(this) {
            val event = frame(type, data) ?: return
            (this as Object).notifyAll()
            for (target in ArrayList(listeners)) target.offer(event)
        }
    }

    @Synchronized
    fun queueDepth(): Long {
        var depth = 0L
        for (listener in listeners) depth += listener.queue.size
        return depth
    }

    @Synchronized
    fun eventsPerSec(): Long = windowEvents

    private fun replay(since: Long, processId: String?, types: Set<String>, packages: Set<String>): Replay {
        val current = sequence
        if (since <= 0) return Replay(JSONArray(), current, false)
        if (bootId != processId || since > current || current - since > LIMIT) {
            return Replay(JSONArray(), current, true)
        }
        val result = JSONArray()
        var seq = maxOf(1L, since + 1)
        while (seq <= current) {
            val event = entries[(seq % LIMIT).toInt()]
            if (event != null && event.optLong("seq") == seq && wanted(event, types, packages)) result.put(event)
            seq++
        }
        return Replay(result, current, false)
    }

    @Synchronized
    fun subscribe(subscription: Subscription, since: Long, processId: String?, types: Set<String>, packages: Set<String>): Replay {
        if (listeners.size >= 8) throw IllegalStateException("event subscription limit reached")
        val replay = replay(since, processId, types, packages)
        listeners.add(subscription as QueueSubscription)
        return replay
    }

    @Synchronized
    fun subscribe(): Subscription {
        if (listeners.size >= 8) throw IllegalStateException("event subscription limit reached")
        val subscription = QueueSubscription()
        listeners.add(subscription)
        return subscription
    }

    fun subscribe(listener: Consumer<JSONObject>): Subscription {
        val subscription = subscribe() as QueueSubscription
        callbacks.execute {
            try {
                while (true) {
                    val event = subscription.take() ?: break
                    listener.accept(event)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return subscription
    }

    fun subscription(types: Set<String>, packages: Set<String>): Subscription = QueueSubscription(types, packages)

    @Synchronized
    private fun remove(subscription: QueueSubscription) {
        listeners.remove(subscription)
    }

    private inner class QueueSubscription(
        private val types: Set<String> = emptySet(),
        private val packages: Set<String> = emptySet()
    ) : Subscription {
        val queue = ArrayBlockingQueue<JSONObject>(256)

        @Volatile
        private var closed = false
        private var dropped = 0L

        fun offer(event: JSONObject) {
            if (closed || !wanted(event, types, packages)) return
            if (queue.offer(event)) return
            queue.poll()
            dropped++
            val notice = try {
                frame("events.dropped", JSONObject().put("count", dropped))
            } catch (_: Exception) {
                null
            }
            if (notice != null) queue.offer(notice)
            if (!queue.offer(event)) {
                queue.poll()
                dropped++
                queue.offer(event)
            }
            dropped = 0
        }

        @Throws(InterruptedException::class)
        override fun take(): JSONObject? {
            while (!closed || queue.isNotEmpty()) {
                val result = queue.poll(250, TimeUnit.MILLISECONDS)
                if (result != null) return result
            }
            return null
        }

        override fun close() {
            if (closed) return
            closed = true
            remove(this)
        }
    }

    companion object {
        private const val LIMIT = 1000

        private fun wanted(event: JSONObject, types: Set<String>, packages: Set<String>): Boolean {
            val type = event.optString("event")
            if (type.startsWith("events.")) return true
            if (types.isNotEmpty() && !types.contains(type)) return false
            val data = event.optJSONObject("data")
            return packages.isEmpty() || data != null && packages.contains(data.optString("package"))
        }
    }
}
