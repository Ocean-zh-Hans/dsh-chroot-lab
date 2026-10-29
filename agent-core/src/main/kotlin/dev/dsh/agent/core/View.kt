package dev.dsh.agent.core

data class ViewOptions(
    val mode: String = "auto",
    val maxChars: Int = 2000,
    val maxDepth: Int = 3,
    val includeBounds: Boolean = true,
)

data class Rendered(
    val text: String,
    val shown: Int,
    val omitted: Int,
    val truncated: Boolean,
)

/**
 * 把快照渲染成"给人看的一屏文本"。
 * 目标：同样一屏内容，token 约为原始 JSON 的 1/5。
 */
object ViewRenderer {

    fun render(view: SnapshotView, opts: ViewOptions = ViewOptions()): Rendered {
        val snap = view.snapshot
        val sb = StringBuilder(2048)
        val header = "# g=" + snap.g + " seq=" + snap.seq + " " +
            (if (snap.activity.isEmpty()) snap.pkg else snap.pkg + "/" + snap.activity) +
            " kb:" + (if (snap.keyboard) "on" else "off") +
            " " + snap.screen.w + "x" + snap.screen.h
        sb.append(header).append('\n')

        val display = displayList(view, opts)
        var shown = 0
        var truncated = false
        var i = 0
        while (i < display.size) {
            val item = display[i]
            // 合并相邻同角色同文本
            var j = i + 1
            val group = ArrayList<DisplayItem>(1)
            group.add(item)
            if (opts.mode != "all" && item.node.label.isNotEmpty() && item.node.has(Flags.CLICKABLE)) {
                while (j < display.size) {
                    val nxt = display[j]
                    if (nxt.depth == item.depth && nxt.node.role == item.node.role &&
                        nxt.node.label == item.node.label && nxt.node.has(Flags.CLICKABLE)
                    ) {
                        group.add(nxt); j++
                    } else break
                }
            }
            val line = renderLine(view, group, opts)
            if (sb.length + line.length + 1 > opts.maxChars) {
                truncated = true
                break
            }
            sb.append(line).append('\n')
            shown += group.size
            i = j
        }

        val omitted = (display.size - shown).coerceAtLeast(0)
        if (truncated || omitted > 0) {
            sb.append("# 另有 ").append(omitted).append(" 个节点未显示")
            if (truncated) sb.append("（超出 maxChars=").append(opts.maxChars).append("，可加 where 过滤或调大预算）")
            sb.append('\n')
        }
        return Rendered(sb.toString(), shown, omitted, truncated)
    }

    private data class DisplayItem(val id: Int, val depth: Int, val node: Node)

    private fun displayList(view: SnapshotView, opts: ViewOptions): List<DisplayItem> {
        val snap = view.snapshot
        val keep = BooleanArray(snap.size)
        for (id in snap.walk()) {
            if (keep[id]) continue
            val n = snap.node(id)
            if (selects(n, opts.mode)) {
                keep[id] = true
                var p = n.parent
                while (p >= 0) {
                    keep[p] = true
                    p = snap.nodes[p].parent
                }
            }
        }
        val out = ArrayList<DisplayItem>(snap.size)
        fun visit(id: Int, depth: Int) {
            if (!keep[id]) return
            val n = snap.node(id)
            out.add(DisplayItem(id, depth, n))
            val kids = n.children.sortedWith(compareBy({ snap.node(it).bounds.t }, { snap.node(it).bounds.l }))
            for (k in kids) visit(k, depth + 1)
        }
        for (r in snap.roots.sortedWith(compareBy({ snap.node(it).bounds.t }, { snap.node(it).bounds.l }))) visit(r, 0)
        return out
    }

    private fun selects(n: Node, mode: String): Boolean = when (mode) {
        "all" -> true
        "text" -> n.label.isNotEmpty()
        else -> n.label.isNotEmpty() || n.clickable || n.editable || n.scrollable ||
            n.has(Flags.CHECKABLE) || n.focused
    }

    private fun renderLine(view: SnapshotView, group: List<DisplayItem>, opts: ViewOptions): String {
        val first = group[0]
        val n = first.node
        val sb = StringBuilder(96)
        val indent = minOf(first.depth, opts.maxDepth)
        repeat(indent) { sb.append("  ") }
        sb.append(view.refOf(n.id) ?: "?").append("  ")
        if (opts.mode != "text") sb.append(n.role).append(' ')
        val label = n.label
        if (label.isNotEmpty()) sb.append('"').append(Diff.truncate(label, 60)).append('"')
        if (group.size > 1) {
            sb.append(" ×").append(group.size).append(" [")
            sb.append(group.joinToString(",") { view.refOf(it.node.id) ?: "?" })
            sb.append(']')
        }
        if (opts.mode != "text") {
            if (opts.includeBounds) sb.append(' ').append('(').append(n.bounds).append(')')
            val flags = flagText(n)
            if (flags.isNotEmpty()) sb.append(" [").append(flags).append(']')
        }
        return sb.toString().trimEnd()
    }

    fun flagText(n: Node): String {
        val out = ArrayList<String>(3)
        if (n.editable) out.add("editable")
        if (!n.enabled) out.add("disabled")
        if (n.checked) out.add("checked")
        if (n.has(Flags.SELECTED)) out.add("selected")
        if (n.focused) out.add("focused")
        if (n.scrollable) out.add("scroll")
        if (n.has(Flags.PASSWORD)) out.add("password")
        return out.joinToString(",")
    }
}
