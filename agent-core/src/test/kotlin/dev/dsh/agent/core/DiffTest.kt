package dev.dsh.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffTest {

    @Test
    fun detectsTextChange() {
        val store = RefStore()
        val v1 = store.bind(Fixture.settings())

        val b = SnapshotBuilder(g = 4, seq = 43, pkg = "com.android.settings", activity = ".Settings")
        val root = b.add("android.widget.FrameLayout", Rect(0, 0, 1080, 2400))
        b.add("android.widget.TextView", Rect(40, 100, 500, 180), parent = root, text = "设置")
        val list = b.add("androidx.recyclerview.widget.RecyclerView", Rect(0, 200, 1080, 2200), parent = root, flags = Flags.SCROLLABLE)
        val row = b.add("android.widget.LinearLayout", Rect(0, 200, 1080, 360), parent = list, flags = Flags.CLICKABLE)
        b.add("android.widget.TextView", Rect(40, 220, 400, 300), parent = row, text = "WLAN")
        b.add("android.widget.Switch", Rect(900, 240, 1040, 320), parent = row, text = "已关闭", flags = Flags.CHECKABLE)
        val v2 = store.bind(b.build())

        val ch = Diff.compute(v1, v2)
        assertTrue(ch.changed.any { it.field == "text" && it.after == "已关闭" }, ch.toString())
        assertTrue(ch.changed.any { it.field == "checked" }, ch.toString())
    }

    @Test
    fun detectsAddedAndRemoved() {
        val store = RefStore()
        val v1 = store.bind(Fixture.settings())

        val b = SnapshotBuilder(g = 4, seq = 43, pkg = "com.android.settings", activity = ".Settings")
        b.add("android.widget.Button", Rect(600, 1800, 900, 1900), text = "飞行模式", flags = Flags.CLICKABLE)
        val v2 = store.bind(b.build())

        val ch = Diff.compute(v1, v2)
        assertTrue(ch.added.isNotEmpty(), "应报告新增")
        assertTrue(ch.removed.isNotEmpty(), "应报告移除")
    }
}
