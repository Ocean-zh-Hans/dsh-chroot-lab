package dev.dsh.agent

object AgentMethods {
    const val API_VERSION: Int = 3
    const val VERSION: String = "0.4.0"
    const val BUILD_TIME: String = "2026-10-30"

    private val WRITES = hashSetOf(
        "ui.act", "ui.batch", "ui.fill", "ui.gesture", "ui.scrollUntil", "ui.scroll", "ui.press", "ui.ime",
        "notif.dismiss", "notif.reply", "ui.clipboard", "ui.invalidate",
        "handoff.request", "receipts.append", "dev.log.clear",
        "policy.set", "dev.install", "dev.uninstall"
    )

    @JvmStatic
    fun isMutation(method: String): Boolean = WRITES.contains(method)

    /** clipboard 的 get/history 是读操作，set/clear 才是写操作。 */
    @JvmStatic
    fun isMutation(method: String, op: String?): Boolean =
        if (method == "ui.clipboard") op != "get" && op != "history" else isMutation(method)

    @JvmStatic
    fun isMutation(method: String, params: org.json.JSONObject?): Boolean =
        isMutation(method, params?.optString("op", "get") ?: "get")
}
