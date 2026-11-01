package dev.chroot.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotListTest {
    @Test
    fun reportsWhyAnEmptyListIsEmpty() {
        // 旧模块把 JSON 吃掉时就是这样：退出码 0，stdout 空。
        val silent = parseSnapshotList("", 0, true)
        assertTrue(silent.entries.isEmpty())
        assertEquals("读取失败：dsh-ctl 没有输出（exit 0）", silent.error)

        val failed = parseSnapshotList("dsh-ctl: unknown subcommand\n", 2, false)
        assertEquals("读取失败：exit 2 · dsh-ctl: unknown subcommand", failed.error)
    }

    @Test
    fun surfacesUnparseableOutput() {
        val broken = parseSnapshotList("image snapshots: /data/media/0/ubuntu-rootfs/snapshots\n", 0, true)
        assertTrue(broken.entries.isEmpty())
        assertTrue(broken.error.startsWith("解析失败："))
    }

    @Test
    fun keepsLastGoodFirst() {
        val parsed = sortSnapshots(
            listOf(
                SnapshotEntry(name = "old", bytes = 10, usedBytes = 5, created = "2026-10-29T04:23:17+0800", lastGood = false),
                SnapshotEntry(name = "a", bytes = 20, usedBytes = 7, created = "2026-10-29T05:01:27+0800", lastGood = true),
            ),
        )
        assertEquals(listOf("a", "old"), parsed.map { it.name })
        assertEquals(7L, parsed.first().usedBytes)
    }
}
