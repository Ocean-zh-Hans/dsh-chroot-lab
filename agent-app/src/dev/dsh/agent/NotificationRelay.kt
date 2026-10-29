package dev.dsh.agent

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
        AgentServer.onNotification("notification.posted", notification.key, notification.packageName, notification.postTime)
    }

    override fun onNotificationRemoved(notification: StatusBarNotification) {
        AgentServer.onNotification("notification.removed", notification.key, notification.packageName, notification.postTime)
    }

    companion object {
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
            for (sbn in active) {
                val item = JSONObject()
                item.put("key", sbn.key)
                item.put("package", sbn.packageName)
                item.put("posted", sbn.postTime)
                item.put("ongoing", sbn.isOngoing)
                item.put("title", sbn.notification.extras.getCharSequence("android.title").toString())
                item.put("text", sbn.notification.extras.getCharSequence("android.text").toString())
                out.put(item)
            }
            return out
        }

        @JvmStatic
        fun dismiss(key: String): Boolean {
            val relay = instance ?: return false
            if (key.isEmpty()) return false
            relay.cancelNotification(key)
            val active = relay.activeNotifications ?: return false
            return active.none { key == it.key }
        }
    }
}
