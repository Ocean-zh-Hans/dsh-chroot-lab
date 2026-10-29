package dev.dsh.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewTest {

    private fun view(): SnapshotView = RefStore().bind(Fixture.settings())

    @Test
    fun rendersHeaderAndRefs() {
        val out = ViewRenderer.render(view(), ViewOptions(maxChars = 4000))
        val lines = out.text.trim().lines()
        assertTrue(lines[0].startsWith("# g=3 seq=42 com.android.settings/.Settings"), lines[0])
        assertTrue(out.text.contains("@e2  Text \"设置\""), out.text)
        assertTrue(out.text.contains("[editable]"), out.text)
        assertTrue(out.text.contains("[checked]"), out.text)
        assertTrue(out.shown > 0)
    }

    @Test
    fun textModeHasNoFlagsOrBounds() {
        val out = ViewRenderer.render(view(), ViewOptions(mode = "text", maxChars = 4000))
        assertTrue(out.text.contains("WLAN"))
        assertTrue(!out.text.contains("(") || out.text.contains("另有"))
        assertTrue(!out.text.contains("[editable]"))
    }

    @Test
    fun budgetTruncates() {
        val out = ViewRenderer.render(view(), ViewOptions(maxChars = 160))
        assertTrue(out.truncated, "should truncate")
        assertTrue(out.text.contains("未显示"), out.text)
    }

    @Test
    fun mergesIdenticalSiblings() {
        val b = SnapshotBuilder(g = 1, seq = 1, pkg = "p", activity = "a")
        val root = b.add("android.widget.LinearLayout", Rect(0, 0, 1080, 2400))
        repeat(3) { i ->
            b.add("android.widget.Button", Rect(100 + i * 300, 100, 340 + i * 300, 200), parent = root, text = "确定", flags = Flags.CLICKABLE)
        }
        val out = ViewRenderer.render(RefStore().bind(b.build()), ViewOptions(maxChars = 4000))
        assertTrue(out.text.contains("×3"), out.text)
        assertEquals(1, out.text.lines().count { it.contains("确定") })
    }
}
