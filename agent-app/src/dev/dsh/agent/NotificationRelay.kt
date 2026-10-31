package dev.dsh.agent

import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class NotificationRelay : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(notification: StatusBarNotification) {
        record(notification)
        AgentServer.onNotification("notification.posted", notification.key, notification.packageName, notification.postTime)
    }

    override fun onNotificationRemoved(notification: StatusBarNotification) {
        markRemoved(notification.key)
        AgentServer.onNotification("notification.removed", notification.key, notification.packageName, notification.postTime)
    }

    companion object {
        private const val MAX_HISTORY = 100
        private val history = LinkedHashMap<String, JSONObject>()
        private val historyLock = Any()

        @Volatile
        private var instance: NotificationRelay? = null

        @JvmStatic
        fun connected(): Boolean = instance != null

        @JvmStatic
        fun find(key: String): StatusBarNotification? {
            val relay = instance ?: return null
            if (key.isEmpty()) return null
            val active = relay.activeNotifications ?: return null
            return active.firstOrNull { key == it.key }
        }

        @JvmStatic
        fun snapshot(): JSONArray {
            val out = JSONArray()
            val relay = instance ?: return out
            val active = relay.activeNotifications ?: return out
            for (sbn in active) out.put(item(sbn, active = true))
            return out
        }

        /** 最近通知，按时间倒序；包含已移除项（active=false）。 */
        @JvmStatic
        fun history(limit: Int = 50): JSONArray {
            val n = limit.coerceIn(1, MAX_HISTORY)
            val out = JSONArray()
            synchronized(historyLock) {
                val values = history.values.toList()
                val from = (values.size - n).coerceAtLeast(0)
                for (i in values.indices.reversed()) {
                    if (i < from) break
                    out.put(JSONObject(values[i].toString()))
                }
            }
            return out
        }

        private fun item(sbn: StatusBarNotification, active: Boolean): JSONObject = JSONObject()
            .put("key", sbn.key)
            .put("package", sbn.packageName)
            .put("posted", sbn.postTime)
            .put("ongoing", sbn.isOngoing)
            .put("clearable", sbn.isClearable)
            .put("active", active)
            .put("title", sbn.notification.extras.getCharSequence("android.title")?.toString() ?: "")
            .put("text", sbn.notification.extras.getCharSequence("android.text")?.toString() ?: "")
            .put("replyable", hasRemoteInput(sbn))

        private fun record(sbn: StatusBarNotification) {
            synchronized(historyLock) {
                history.remove(sbn.key)
                history[sbn.key] = item(sbn, active = true)
                while (history.size > MAX_HISTORY) {
                    val oldest = history.keys.firstOrNull() ?: break
                    history.remove(oldest)
                }
            }
        }

        private fun markRemoved(key: String) {
            synchronized(historyLock) {
                history[key]?.put("active", false)?.put("removedAt", System.currentTimeMillis())
            }
        }

        @JvmStatic
        fun dismiss(key: String): Boolean {
            val relay = instance ?: return false
            if (key.isEmpty()) return false
            relay.cancelNotification(key)
            // cancelNotification 是异步的；立刻读 activeNotifications 会误报失败。
            val deadline = SystemClock.uptimeMillis() + 1_200
            while (SystemClock.uptimeMillis() < deadline) {
                val active = relay.activeNotifications ?: return false
                if (active.none { key == it.key }) return true
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return false
        }

        private fun hasRemoteInput(sbn: StatusBarNotification): Boolean {
            val actions = sbn.notification.actions ?: return false
            for (action in actions) {
                val inputs = action.remoteInputs
                if (inputs != null && inputs.isNotEmpty()) return true
            }
            return false
        }
    }
}
