package dev.dsh.agent.core

/** 屏幕矩形，右下开区间。 */
data class Rect(val l: Int, val t: Int, val r: Int, val b: Int) {
    val w: Int get() = (r - l).coerceAtLeast(0)
    val h: Int get() = (b - t).coerceAtLeast(0)
    val cx: Int get() = l + w / 2
    val cy: Int get() = t + h / 2
    val area: Long get() = w.toLong() * h.toLong()
    val isEmpty: Boolean get() = w <= 0 || h <= 0

    fun intersects(o: Rect): Boolean = l < o.r && o.l < r && t < o.b && o.t < b
    fun contains(x: Int, y: Int): Boolean = x >= l && x < r && y >= t && y < b
    fun covers(o: Rect): Boolean = o.l >= l && o.t >= t && o.r <= r && o.b <= b
    fun centerDistance(o: Rect): Double {
        val dx = (cx - o.cx).toDouble()
        val dy = (cy - o.cy).toDouble()
        return Math.sqrt(dx * dx + dy * dy)
    }

    override fun toString(): String = "$l,$t,$w,$h"
}

/** 节点标志位。默认值全部为 0（不可点、不可编辑、未选中）。 */
object Flags {
    const val CLICKABLE = 1
    const val LONG_CLICKABLE = 1 shl 1
    const val EDITABLE = 1 shl 2
    const val SCROLLABLE = 1 shl 3
    const val CHECKABLE = 1 shl 4
    const val CHECKED = 1 shl 5
    const val SELECTED = 1 shl 6
    const val ENABLED = 1 shl 7
    const val FOCUSED = 1 shl 8
    const val VISIBLE = 1 shl 9
    const val PASSWORD = 1 shl 10
    const val CLICKABLE_ANCESTOR = 1 shl 11
}

/**
 * 快照里的一个无障碍节点（不可变拷贝）。
 * [id] 是本快照内的稠密下标，父节点一定小于子节点。
 */
data class Node(
    val id: Int,
    val parent: Int,
    val children: List<Int>,
    val window: Int,
    val depth: Int,
    val cls: String,
    val pkg: String,
    val viewId: String,
    val text: String,
    val desc: String,
    val hint: String,
    val bounds: Rect,
    val flags: Int,
    val nativeId: Long,
    val path: String,
    val index: Int,
    val z: Int,
) {
    fun has(f: Int): Boolean = flags and f != 0
    val clickable: Boolean get() = has(Flags.CLICKABLE)
    val editable: Boolean get() = has(Flags.EDITABLE)
    val enabled: Boolean get() = has(Flags.ENABLED)
    val scrollable: Boolean get() = has(Flags.SCROLLABLE)
    val checked: Boolean get() = has(Flags.CHECKED)
    val focused: Boolean get() = has(Flags.FOCUSED)
    /** 展示文本：text ?: desc ?: hint */
    val label: String get() = text.ifEmpty { desc.ifEmpty { hint } }
    /** 去掉包名的短类名。 */
    val role: String get() = shortRole(cls)
    /** viewId 尾段。 */
    val shortId: String get() = viewId.substringAfterLast('/')
}

internal fun shortRole(cls: String): String {
    if (cls.isEmpty()) return "Node"
    val simple = cls.substringAfterLast('.')
    return when (simple) {
        "TextView" -> "Text"
        "EditText", "AutoCompleteTextView", "TextInputEditText" -> "Input"
        "Button", "MaterialButton", "AppCompatButton" -> "Button"
        "ImageButton" -> "IconButton"
        "ImageView", "AppCompatImageView" -> "Image"
        "FrameLayout" -> "Frame"
        "LinearLayout" -> "Linear"
        "RelativeLayout" -> "Relative"
        "ConstraintLayout" -> "Constraint"
        "View", "ViewGroup" -> "View"
        "RecyclerView" -> "List"
        "ListView" -> "List"
        "ScrollView", "NestedScrollView" -> "Scroll"
        "CheckBox" -> "Check"
        "Switch", "SwitchCompat", "MaterialSwitch" -> "Switch"
        "RadioButton" -> "Radio"
        "ProgressBar" -> "Progress"
        "SeekBar" -> "Slider"
        else -> simple.removeSuffix("Layout").removeSuffix("View").ifEmpty { simple }
    }
}
