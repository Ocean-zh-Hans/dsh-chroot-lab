package dev.dsh.agent

import org.json.JSONObject

/**
 * APK 独有、但不属于“读/写 UI 树”的设备能力：通知动作与剪贴板。
 *
 * 这些能力 Ubuntu 侧没有对应系统调用（cmd notification 不能 dismiss/reply，
 * cmd clipboard 没有实现），因此留在 APK；通知列表/历史仍可由 APK 直接给出，
 * history 只存在进程内，方便按 key 操作。
 */
internal object DeviceBridge {
    private val ROUTED = setOf(
        "notif.list", "notif.history", "notif.dismiss", "notif.reply", "ui.clipboard",
    )

    @JvmStatic
    fun handles(method: String): Boolean = ROUTED.contains(method)

    @JvmStatic
    fun call(method: String, params: JSONObject): Any = when (method) {
        "notif.list" -> notificationList()
        "notif.history" -> notificationHistory(params)
        "notif.dismiss" -> notificationDismiss(params)
        "notif.reply" -> notificationReply(params)
        "ui.clipboard" -> clipboard(params)
        else -> throw err("BAD_REQUEST", "unknown device method " + method)
    }

    private fun notificationList(): JSONObject {
        requireRelay()
        val items = NotificationRelay.snapshot()
        return JSONObject().put("ok", true).put("via", "notification")
            .put("count", items.length()).put("notifications", items)
    }

    private fun notificationHistory(params: JSONObject): JSONObject {
        requireRelay()
        val items = NotificationRelay.history(params.optInt("limit", 50))
        return JSONObject().put("ok", true).put("via", "notification")
            .put("count", items.length()).put("notifications", items)
    }

    private fun notificationDismiss(params: JSONObject): JSONObject {
        requireRelay()
        val key = params.optString("key", "")
        if (key.isEmpty()) throw err("BAD_REQUEST", "key is required")
        val notification = NotificationRelay.find(key)
            ?: throw err("NOT_FOUND", "notification is no longer active").detail("key", key)
        if (notification.isOngoing || !notification.isClearable) {
            throw err("BLOCKED", "notification is ongoing and cannot be dismissed")
                .detail("key", key)
                .detail("package", notification.packageName)
        }
        if (!NotificationRelay.dismiss(key)) {
            throw err("UNAVAILABLE", "notification dismiss was rejected or timed out")
                .detail("key", key)
        }
        return JSONObject().put("ok", true).put("via", "notification")
            .put("dismissed", true).put("key", key).put("package", notification.packageName)
    }

    private fun notificationReply(params: JSONObject): JSONObject {
        requireRelay()
        val key = params.optString("key", "")
        if (key.isEmpty()) throw err("BAD_REQUEST", "key is required")
        val text = params.optString("text", "")
        if (text.isEmpty()) throw err("BAD_REQUEST", "text is required")
        if (text.length > 8192) throw err("BAD_REQUEST", "reply is too long")
        val result = MediaBridge.call("notifications.reply", params) as? JSONObject
            ?: throw err("INTERNAL", "notification reply returned no result")
        return JSONObject().put("ok", true).put("via", "notification")
            .put("key", key)
            .put("requested", result.optBoolean("requested", true))
            .put("confirmed", result.optBoolean("confirmed", false))
    }

    private fun requireRelay() {
        if (!NotificationRelay.connected()) {
            throw err("NOTIFICATION_ACCESS_OFF", "notification listener is not connected")
        }
    }

    private fun clipboard(params: JSONObject): JSONObject {
        val op = params.optString("op", "get")
        val result = when (op) {
            "get" -> MediaBridge.call("clipboard.get", params)
            "set" -> {
                if (!params.has("text")) throw err("BAD_REQUEST", "text is required for clipboard set")
                MediaBridge.call("clipboard.set", params)
            }
            "clear" -> MediaBridge.call("clipboard.clear", params)
            "history" -> MediaBridge.call("clipboard.history", params)
            else -> throw err("BAD_REQUEST", "op must be get|set|clear|history")
        } as? JSONObject ?: throw err("INTERNAL", "clipboard returned no result")
        result.put("ok", true).put("via", "clipboard").put("op", op)
        return result
    }

    private fun err(code: String, message: String): AgentError = AgentError(code, message)
}
