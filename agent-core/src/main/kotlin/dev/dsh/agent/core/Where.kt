package dev.dsh.agent.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 文本匹配：默认不区分大小写的子串匹配，regex=true 时按正则。 */
data class Match(
    val value: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = false,
) {
    private val pattern: Regex? = if (regex) {
        Regex(value, if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
    } else null

    fun test(s: String): Boolean {
        if (pattern != null) return pattern.containsMatchIn(s)
        return if (caseSensitive) s.contains(value) else s.contains(value, ignoreCase = true)
    }
}

/** 相对锚点：ref 或嵌套选择器。 */
data class Anchor(val ref: String? = null, val where: Where? = null)

/**
 * 结构化选择器。所有字段为"与"关系；相对锚点会把候选限制在锚点的某个方向。
 */
data class Where(
    val text: Match? = null,
    val desc: Match? = null,
    val id: Match? = null,
    val cls: Match? = null,
    val pkg: Match? = null,
    val clickable: Boolean? = null,
    val enabled: Boolean? = null,
    val editable: Boolean? = null,
    val scrollable: Boolean? = null,
    val checked: Boolean? = null,
    val focused: Boolean? = null,
    val hasText: Boolean? = null,
    val below: Anchor? = null,
    val above: Anchor? = null,
    val leftOf: Anchor? = null,
    val rightOf: Anchor? = null,
    val childOf: Anchor? = null,
    val anyOf: List<Where> = emptyList(),
) {
    fun match(n: Node): Boolean {
        if (text != null && !text.test(n.text)) return false
        if (desc != null && !(desc.test(n.desc) || desc.test(n.text))) return false
        if (id != null && !id.test(n.viewId.substringAfterLast('/'))) return false
        if (cls != null && !cls.test(n.cls)) return false
        if (pkg != null && !pkg.test(n.pkg)) return false
        if (clickable != null && n.clickable != clickable) return false
        if (enabled != null && n.enabled != enabled) return false
        if (editable != null && n.editable != editable) return false
        if (scrollable != null && n.scrollable != scrollable) return false
        if (checked != null && n.checked != checked) return false
        if (focused != null && n.focused != focused) return false
        if (hasText != null && (n.label.isNotEmpty()) != hasText) return false
        if (anyOf.isNotEmpty() && anyOf.none { it.match(n) }) return false
        return true
    }

    companion object {
        fun parse(obj: JsonObject): Where = Builder.parse(obj)

        fun text(value: String): Where = Where(text = Match(value))
        fun id(value: String): Where = Where(id = Match(value))
    }

    private object Builder {
        fun parse(obj: JsonObject): Where {
            var w = Where()
            for ((k, v) in obj) {
                w = when (k) {
                    "text" -> w.copy(text = matchOf(v))
                    "desc", "description" -> w.copy(desc = matchOf(v))
                    "id", "viewId" -> w.copy(id = matchOf(v))
                    "cls", "class" -> w.copy(cls = matchOf(v))
                    "pkg", "package" -> w.copy(pkg = matchOf(v))
                    "clickable" -> w.copy(clickable = boolOf(v))
                    "enabled" -> w.copy(enabled = boolOf(v))
                    "editable" -> w.copy(editable = boolOf(v))
                    "scrollable" -> w.copy(scrollable = boolOf(v))
                    "checked" -> w.copy(checked = boolOf(v))
                    "focused" -> w.copy(focused = boolOf(v))
                    "hasText" -> w.copy(hasText = boolOf(v))
                    "below" -> w.copy(below = anchorOf(v))
                    "above" -> w.copy(above = anchorOf(v))
                    "leftOf", "left_of" -> w.copy(leftOf = anchorOf(v))
                    "rightOf", "right_of" -> w.copy(rightOf = anchorOf(v))
                    "childOf", "child_of" -> w.copy(childOf = anchorOf(v))
                    "anyOf" -> w.copy(anyOf = (v as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> parse(o) } } ?: emptyList())
                    else -> w
                }
            }
            return w
        }

        private fun matchOf(v: JsonElement): Match {
            if (v is JsonObject) {
                return Match(
                    value = v["value"]?.jsonPrimitive?.contentOrNull ?: "",
                    regex = v["regex"]?.jsonPrimitive?.booleanOrNull ?: false,
                    caseSensitive = v["caseSensitive"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
            return Match(v.jsonPrimitive.contentOrNull ?: "")
        }

        private fun boolOf(v: JsonElement): Boolean? = v.jsonPrimitive.booleanOrNull

        private fun anchorOf(v: JsonElement): Anchor? = when (v) {
            is JsonPrimitive -> {
                val s = v.contentOrNull ?: return null
                if (s.startsWith("@")) Anchor(ref = s) else Anchor(where = Where.text(s))
            }
            is JsonObject -> Anchor(where = parse(v))
            else -> null
        }
    }
}

/** 在快照上求值选择器，返回按视觉顺序排列的节点 id。 */
object Query {

    fun select(view: SnapshotView, where: Where?): List<Int> {
        val snap = view.snapshot
        if (where == null) return snap.walk()

        val anchors = HashMap<String, List<Rect>>()
        fun anchorRects(anchor: Anchor?): List<Rect>? {
            if (anchor == null) return null
            val key = anchor.ref ?: anchor.where?.toString() ?: return null
            return anchors.getOrPut(key) {
                if (anchor.ref != null) {
                    val id = view.idOf(anchor.ref)
                    if (id != null) listOf(snap.node(id).bounds) else emptyList()
                } else {
                    snap.walk().filter { anchor.where!!.match(snap.node(it)) }.map { snap.node(it).bounds }
                }
            }
        }

        val below = anchorRects(where.below)
        val above = anchorRects(where.above)
        val leftOf = anchorRects(where.leftOf)
        val rightOf = anchorRects(where.rightOf)
        val childOf = anchorRects(where.childOf)

        val out = ArrayList<Int>(32)
        for (id in snap.walk()) {
            val n = snap.node(id)
            if (!where.match(n)) continue
            if (below != null && below.none { n.bounds.t >= it.b }) continue
            if (above != null && above.none { n.bounds.b <= it.t }) continue
            if (leftOf != null && leftOf.none { n.bounds.r <= it.l }) continue
            if (rightOf != null && rightOf.none { n.bounds.l >= it.r }) continue
            if (childOf != null && childOf.none { it.covers(n.bounds) }) continue
            out.add(id)
        }
        return out
    }

    /** 视觉顺序：先看行列，再看深度。 */
    fun visualOrder(view: SnapshotView, ids: List<Int>): List<Int> =
        ids.sortedWith(compareBy({ view.snapshot.node(it).bounds.t }, { view.snapshot.node(it).bounds.l }))
}
