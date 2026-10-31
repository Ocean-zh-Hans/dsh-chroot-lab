package dev.dsh.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RefTest {

    @Test
    fun refsAreStableAcrossSnapshots() {
        val store = RefStore()
        val v1 = store.bind(Fixture.settings())
        val ref = v1.refOf(1)!!

        // 同一棵树，但标题文本变了
        val b = SnapshotBuilder(g = 4, seq = 43, pkg = "com.android.settings", activity = ".Settings")
        val root = b.add("android.widget.FrameLayout", Rect(0, 0, 1080, 2400))
        b.add("android.widget.TextView", Rect(40, 100, 500, 180), parent = root, text = "设置2")
        val v2 = store.bind(b.build())
        assertEquals(ref, v2.refOf(1), "同一位置的标题应复用 ref")
    }

    @Test
    fun relocatesWhenLayoutShifts() {
        val store = RefStore()
        val v1 = store.bind(Fixture.settings())
        val target = v1.byRef.entries.first { it.value == 4 }.key // WLAN 文本

        // 整页下移 100px，其余不变
        val b = SnapshotBuilder(g = 5, seq = 44, pkg = "com.android.settings", activity = ".Settings")
        val root = b.add("android.widget.FrameLayout", Rect(0, 0, 1080, 2400))
        b.add("android.widget.TextView", Rect(40, 200, 500, 280), parent = root, text = "设置")
        val list = b.add("androidx.recyclerview.widget.RecyclerView", Rect(0, 300, 1080, 2300), parent = root, flags = Flags.SCROLLABLE)
        val row = b.add("android.widget.LinearLayout", Rect(0, 300, 1080, 460), parent = list, flags = Flags.CLICKABLE)
        b.add("android.widget.TextView", Rect(40, 320, 400, 400), parent = row, text = "WLAN")
        b.add("android.widget.Switch", Rect(900, 340, 1040, 420), parent = row, text = "已开启", flags = Flags.CHECKABLE or Flags.CHECKED)
        val v2 = store.bind(b.build())

        val res = store.resolve(v2, target)
        assertTrue(res.ok, "应能重定位，实际 score=" + res.score)
        assertTrue(res.relocated)
        assertEquals("WLAN", v2.snapshot.node(res.nodeId).text)
        assertTrue(res.score >= RefStore.RELOCATE_THRESHOLD)
    }

    @Test
    fun doesNotReportLowScoreCandidates() {
        val store = RefStore()
        val v1 = store.bind(Fixture.settings())
        val target = v1.byRef.entries.first { it.value == 4 }.key

        val b = SnapshotBuilder(g = 6, seq = 45, pkg = "com.android.settings", activity = ".Settings")
        val root = b.add("android.widget.FrameLayout", Rect(0, 0, 1080, 2400))
        b.add("android.widget.TextView", Rect(0, 1100, 1080, 1300), parent = root, text = "完全不同的页面")
        val v2 = store.bind(b.build())

        val res = store.resolve(v2, target)
        assertFalse(res.ok, "不应在无关页面上命中")
        assertTrue(res.candidates.isEmpty(), "低分候选应视为 NOT_FOUND，而不是 AMBIGUOUS")
    }

    @Test
    fun unknownRefIsEmpty() {
        val store = RefStore()
        val v = store.bind(Fixture.settings())
        val res = store.resolve(v, "@e9999")
        assertFalse(res.ok)
        assertTrue(res.candidates.isEmpty())
    }
}
