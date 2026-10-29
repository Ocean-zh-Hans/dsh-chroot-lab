package dev.dsh.agent.core

/** 一个仿"设置页"的小树，供所有测试共用。 */
object Fixture {

    fun settings(): Snapshot {
        val b = SnapshotBuilder(g = 3, seq = 42, pkg = "com.android.settings", activity = ".Settings")
        val root = b.add("android.widget.FrameLayout", Rect(0, 0, 1080, 2400))
        b.add("android.widget.TextView", Rect(40, 100, 500, 180), parent = root, text = "设置")
        val list = b.add("androidx.recyclerview.widget.RecyclerView", Rect(0, 200, 1080, 2200), parent = root, flags = Flags.SCROLLABLE)
        b.add(
            "android.widget.LinearLayout", Rect(0, 200, 1080, 360), parent = list,
            flags = Flags.CLICKABLE,
        )
        b.add("android.widget.TextView", Rect(40, 220, 400, 300), parent = 3, text = "WLAN")
        b.add("android.widget.Switch", Rect(900, 240, 1040, 320), parent = 3, text = "已开启", flags = Flags.CHECKABLE or Flags.CHECKED)
        b.add("android.widget.LinearLayout", Rect(0, 360, 1080, 520), parent = list, flags = Flags.CLICKABLE)
        b.add("android.widget.TextView", Rect(40, 380, 400, 460), parent = 6, text = "蓝牙")
        b.add("android.widget.Switch", Rect(900, 400, 1040, 480), parent = 6, text = "已关闭", flags = Flags.CHECKABLE)
        b.add("android.widget.EditText", Rect(40, 600, 900, 700), parent = list, hint = "搜索设置", flags = Flags.EDITABLE)
        return b.build()
    }
}
