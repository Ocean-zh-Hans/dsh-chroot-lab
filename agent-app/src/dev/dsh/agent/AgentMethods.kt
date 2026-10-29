package dev.dsh.agent

object AgentMethods {
    const val API_VERSION: Int = 3
    const val VERSION: String = "0.3.0"
    const val BUILD_TIME: String = "2026-10-29"

    private val WRITES = hashSetOf(
        "ui.act", "ui.batch", "ui.fill", "ui.scrollUntil", "ui.scroll", "ui.press", "ui.ime",
        "policy.set", "dev.install", "dev.uninstall"
    )

    @JvmStatic
    fun isMutation(method: String): Boolean = WRITES.contains(method)
}
