package dev.dsh.agent.core

/** 一次树遍历的不可变结果。 */
data class Snapshot(
    val g: Long,
    val seq: Long,
    val at: Long,
    val pkg: String,
    val activity: String,
    val keyboard: Boolean,
    val screen: Rect,
    val nodes: List<Node>,
    val roots: List<Int>,
    /** 当前 active/focused 窗口 id；-1 表示未知。 */
    val activeWindow: Int = -1,
) {
    val size: Int get() = nodes.size
    fun node(id: Int): Node = nodes[id]
    fun nodeOrNull(id: Int): Node? = nodes.getOrNull(id)

    fun ancestors(id: Int): List<Int> {
        val out = ArrayList<Int>(8)
        var p = nodes[id].parent
        while (p >= 0) {
            out.add(p)
            p = nodes[p].parent
        }
        return out
    }

    /** 前序遍历，确定性顺序。 */
    fun walk(from: Int = -1): List<Int> {
        val out = ArrayList<Int>(nodes.size)
        val starts = if (from < 0) roots else listOf(from)
        for (s in starts) {
            val stack = ArrayDeque<Int>()
            stack.addLast(s)
            while (stack.isNotEmpty()) {
                val cur = stack.removeLast()
                out.add(cur)
                val ch = nodes[cur].children
                for (i in ch.indices.reversed()) stack.addLast(ch[i])
            }
        }
        return out
    }

    companion object {
        val EMPTY = Snapshot(0, 0, 0, "", "", false, Rect(0, 0, 0, 0), emptyList(), emptyList(), -1)
    }
}

/** 构建期的可变节点。 */
class NodeBuilder(
    val window: Int,
    val cls: String,
    val pkg: String,
    val viewId: String,
    val text: String,
    val desc: String,
    val hint: String,
    val bounds: Rect,
    val flags: Int,
    val nativeId: Long,
    val index: Int,
    val z: Int,
) {
    var parent: Int = -1
    val children = ArrayList<Int>(4)
}

/** 便捷构建器，供测试与 Android 适配层使用。 */
class SnapshotBuilder(
    private val g: Long = 1,
    private val seq: Long = 1,
    private val at: Long = 0L,
    pkg: String = "",
    activity: String = "",
    private val screen: Rect = Rect(0, 0, 1080, 2400),
) {
    private val nodes = ArrayList<Node>()
    private val roots = ArrayList<Int>()
    private var keyboard = false
    private var pkgName = pkg
    private var actName = activity
    private var activeWindow = -1

    fun keyboard(visible: Boolean): SnapshotBuilder { keyboard = visible; return this }
    fun packageName(value: String): SnapshotBuilder { pkgName = value; return this }
    fun activityName(value: String): SnapshotBuilder { actName = value; return this }
    fun activeWindow(value: Int): SnapshotBuilder { activeWindow = value; return this }

    fun add(
        cls: String,
        bounds: Rect,
        parent: Int = -1,
        text: String = "",
        desc: String = "",
        hint: String = "",
        viewId: String = "",
        flags: Int = 0,
        nativeId: Long = 0,
        window: Int = 0,
        z: Int = 0,
        nodePkg: String = "",
        /** 仅测试便捷构建默认可见/可用；真实 Capture 必须传 false，使用节点真实 flags。 */
        mergeDefaults: Boolean = true,
    ): Int {
        val id = nodes.size
        val depth = if (parent < 0) 0 else nodes[parent].depth + 1
        val index = if (parent < 0) roots.size else nodes[parent].children.size
        val path = if (parent < 0) id.toString() else nodes[parent].path + "/" + index
        val n = Node(
            id = id,
            parent = parent,
            children = emptyList(),
            window = window,
            depth = depth,
            cls = cls,
            pkg = nodePkg.ifEmpty { pkgName },
            viewId = viewId,
            text = text,
            desc = desc,
            hint = hint,
            bounds = bounds,
            flags = if (mergeDefaults) flags or Flags.ENABLED or Flags.VISIBLE else flags,
            nativeId = nativeId,
            path = path,
            index = index,
            z = z,
        )
        nodes.add(n)
        if (parent < 0) roots.add(id) else {
            val p = nodes[parent]
            nodes[parent] = p.copy(children = p.children + id)
        }
        return id
    }

    fun build(): Snapshot = Snapshot(g, seq, at, pkgName, actName, keyboard, screen, nodes.toList(), roots.toList(), activeWindow)
}
