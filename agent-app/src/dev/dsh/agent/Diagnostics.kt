package dev.dsh.agent

import org.json.JSONArray
import org.json.JSONObject

internal object Diagnostics {
    private const val LIMIT = 512
    private val ENTRIES = arrayOfNulls<JSONObject>(LIMIT)
    private var next = 0L
    private var failures = 0L
    private var cooldownUntil = 0L

    @Synchronized
    @JvmStatic
    fun record(method: String, via: String, error: String, latencyMs: Long) {
        try {
            val item = JSONObject()
            item.put("seq", ++next).put("time", System.currentTimeMillis()).put("method", method)
                .put("via", via).put("latencyMs", latencyMs).put("error", error)
            ENTRIES[(next % LIMIT).toInt()] = item
        } catch (_: Exception) {
        }
        if (error.isEmpty()) {
            failures = 0
        } else {
            failures++
            if (failures >= 3) cooldownUntil = System.currentTimeMillis() + 30000
        }
    }

    @Synchronized
    @JvmStatic
    fun tail(requested: Int): JSONArray {
        val limit = maxOf(1, minOf(100, requested))
        val result = JSONArray()
        val first = maxOf(1, next - limit + 1)
        var seq = first
        while (seq <= next) {
            val item = ENTRIES[(seq % LIMIT).toInt()]
            if (item != null && item.optLong("seq") == seq) result.put(item)
            seq++
        }
        return result
    }

    @Synchronized
    @JvmStatic
    fun clear() {
        for (i in ENTRIES.indices) ENTRIES[i] = null
    }

    @Synchronized
    @JvmStatic
    fun coolingDown(): Boolean = System.currentTimeMillis() < cooldownUntil

    @Synchronized
    @JvmStatic
    fun cooldownRemaining(): Long = maxOf(0, cooldownUntil - System.currentTimeMillis())
}
