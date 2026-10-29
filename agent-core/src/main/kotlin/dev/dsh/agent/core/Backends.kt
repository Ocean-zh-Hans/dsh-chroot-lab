package dev.dsh.agent.core

/**
 * 后端能力声明与选择。
 * 引擎不替调用方（AI）决定用哪个后端，只提供事实：能力表、auto 优先链、失败后的替代建议。
 */
object Backends {
    const val A11Y = "a11y"
    const val UIA = "uia"
    const val SHELL = "shell"
    const val AUTO = "auto"

    data class Cap(
        val name: String,
        val readTree: Boolean,
        val pointer: Boolean,
        val text: Boolean,
        val keys: Boolean,
        val shot: Boolean,
        val exclusive: Boolean,
        val needsRoot: Boolean,
        val latencyMs: Int,
        val note: String,
    )

    val table: List<Cap> = listOf(
        Cap(A11Y, readTree = true, pointer = true, text = true, keys = true, shot = true,
            exclusive = false, needsRoot = false, latencyMs = 50,
            note = "进程内；有稳定节点身份；中文输入可用"),
        Cap(UIA, readTree = true, pointer = true, text = false, keys = true, shot = true,
            exclusive = true, needsRoot = true, latencyMs = 95,
            note = "坐标注入最硬；无节点身份；占用全局单槽"),
        Cap(SHELL, readTree = false, pointer = true, text = false, keys = true, shot = true,
            exclusive = false, needsRoot = true, latencyMs = 300,
            note = "兜底；每次起进程；文本仅 ASCII"),
    )

    val names: List<String> = table.map { it.name }

    fun cap(name: String): Cap? = table.firstOrNull { it.name == name }

    fun supports(name: String, op: String): Boolean {
        val c = cap(name) ?: return false
        return when (op) {
            "read", "tree" -> c.readTree
            "tap", "long", "swipe", "scroll", "drag", "fling" -> c.pointer
            "setText", "clear", "append", "fill" -> c.text
            "press", "key", "global" -> c.keys
            "shot", "screenshot" -> c.shot
            else -> c.pointer
        }
    }

    /** auto 链：支持该操作的后端按"延迟优先"排序；显式指定则只返回那一个。 */
    fun chain(op: String, prefer: String = AUTO): List<String> {
        if (prefer != AUTO) return if (prefer in names) listOf(prefer) else emptyList()
        return table.filter { supports(it.name, op) }.sortedBy { it.latencyMs }.map { it.name }
    }

    /** 失败后的替代建议：还有哪些后端能做这件事，按延迟排。 */
    fun alternatives(failed: String, op: String): List<Pair<String, String>> =
        table.filter { it.name != failed && supports(it.name, op) }
            .sortedBy { it.latencyMs }
            .map { it.name to why(it.name, op) }

    private fun why(name: String, op: String): String = when {
        name == UIA && op in setOf("tap", "long", "swipe", "scroll", "drag") ->
            "手势未被接受时，坐标注入可绕过无障碍手势限制"
        name == UIA -> "UiAutomation 直接注入按键事件"
        name == SHELL && op in setOf("setText", "clear", "append") ->
            "input text 兜底（仅 ASCII）"
        name == SHELL -> "input 命令兜底，最慢但一定能发出去"
        name == A11Y -> "无障碍语义动作，带节点身份校验"
        else -> "可尝试"
    }
}
