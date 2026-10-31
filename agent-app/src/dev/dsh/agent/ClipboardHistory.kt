package dev.dsh.agent

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/**
 * APK 侧剪贴板历史。只在本进程存活期间记录；不落盘，不跨重启。
 *
 * Android 10+ 对后台读剪贴板有限制，所以这里只是 best-effort：
 * - 本 App 自己 set 的文本一定记录；
 * - 监听器能拿到的外部复制也记录；
 * - 读不到时宁可少记，不报错。
 */
internal object ClipboardHistory {
    private const val MAX_ITEMS = 30
    private val items = ArrayDeque<JSONObject>()
    private val lock = Any()
    private var manager: ClipboardManager? = null
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { capture("changed") }

    @JvmStatic
    fun start(context: Context) {
        if (manager != null) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        manager = cm
        try {
            cm.addPrimaryClipChangedListener(listener)
        } catch (_: Exception) {
            // listener 只是增强；失败不影响 get/set。
        }
    }

    @JvmStatic
    fun record(text: String, source: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            val last = items.lastOrNull()
            if (last != null && last.optString("text") == text) return
            items.addLast(JSONObject()
                .put("text", text)
                .put("at", SystemClock.elapsedRealtime())
                .put("source", source))
            while (items.size > MAX_ITEMS) items.removeFirst()
        }
    }

    @JvmStatic
    fun history(limit: Int = 30): JSONArray {
        val n = limit.coerceIn(1, MAX_ITEMS)
        val out = JSONArray()
        synchronized(lock) {
            val all = items.toList()
            val from = (all.size - n).coerceAtLeast(0)
            for (i in all.indices.reversed()) {
                if (i < from) break
                out.put(JSONObject(all[i].toString()))
            }
        }
        return out
    }

    private fun capture(source: String) {
        val cm = manager ?: return
        try {
            val clip = if (cm.hasPrimaryClip()) cm.primaryClip else null
            if (clip == null || clip.itemCount == 0) return
            val text = StringBuilder()
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).coerceToText(AgentServer.context())?.let { text.append(it) }
            }
            record(text.toString(), source)
        } catch (_: Exception) {
            // 后台读取受限时静默；不把剪贴板错误带到 API。
        }
    }

    /** 供 MediaBridge.clipboardGet 在读取成功后补记，覆盖监听器拿不到的外部复制。 */
    @JvmStatic
    fun recordRead(text: String) {
        record(text, "read")
    }

    @JvmStatic
    fun clear() {
        synchronized(lock) { items.clear() }
    }
}
