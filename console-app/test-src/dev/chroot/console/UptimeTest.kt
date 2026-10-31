package dev.chroot.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UptimeTest {
    @Test
    fun `parses the offset without colon that dsh-ctl prints`() {
        // 2026-10-31T12:35:06+0800 = 2026-10-31T04:35:06Z
        assertEquals(1793421306L, parseEpochSeconds("2026-10-31T12:35:06+0800"))
    }

    @Test
    fun `parses the colon form too`() {
        assertEquals(1793421306L, parseEpochSeconds("2026-10-31T12:35:06+08:00"))
    }

    @Test
    fun `returns null instead of throwing on junk`() {
        assertNull(parseEpochSeconds(""))
        assertNull(parseEpochSeconds("not-a-time"))
        assertNull(parseEpochSeconds("2026-10-31T12:35:06"))
    }

    @Test
    fun `formats hours and minutes from a fixed now`() {
        val now = 1793421306L + 13 * 3600 + 7 * 60
        assertEquals("已运行 13 小时 7 分", formatUptime("2026-10-31T12:35:06+0800", now))
        assertEquals("已运行 42 分", formatUptime("2026-10-31T12:35:06+0800", 1793421306L + 42 * 60))
        assertEquals("刚刚启动", formatUptime("2026-10-31T12:35:06+0800", 1793421306L - 5))
        assertEquals("", formatUptime("junk", now))
    }
}
