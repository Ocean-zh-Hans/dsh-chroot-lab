package dev.dsh.agent.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotBuilderTest {

    @Test
    fun keepsRealVisibilityAndEnabledFlags() {
        val builder = SnapshotBuilder(g = 7, seq = 3, pkg = "app", activity = ".Main")
        val id = builder.add(
            cls = "android.widget.TextView",
            bounds = Rect(10, 20, 200, 80),
            text = "hidden",
            flags = Flags.CLICKABLE or Flags.SCROLLABLE,
            nodePkg = "app",
            mergeDefaults = false,
        )
        val snapshot = builder.build()
        val node = snapshot.node(id)
        assertFalse(node.has(Flags.VISIBLE), "Capture must not invent VISIBLE")
        assertFalse(node.has(Flags.ENABLED), "Capture must not invent ENABLED")
        assertTrue(node.has(Flags.CLICKABLE))
        assertTrue(node.has(Flags.SCROLLABLE))
        assertEquals("app", node.pkg)
    }

    @Test
    fun defaultsRemainForTestConvenience() {
        val builder = SnapshotBuilder()
        val id = builder.add(cls = "android.widget.Button", bounds = Rect(0, 0, 10, 10))
        val node = builder.build().node(id)
        assertTrue(node.has(Flags.VISIBLE))
        assertTrue(node.has(Flags.ENABLED))
    }
}
