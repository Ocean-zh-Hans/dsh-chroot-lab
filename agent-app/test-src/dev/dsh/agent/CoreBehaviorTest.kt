package dev.dsh.agent

import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CoreBehaviorTest {
    @Test
    fun cronComputesNextWeekdaySlot() {
        val cron = Cron.parse("*/15 9 * * 1-5")
        val zone = ZoneId.of("Asia/Shanghai")
        val start = ZonedDateTime.of(2026, 10, 28, 8, 50, 0, 0, zone)
        val next = ZonedDateTime.of(2026, 10, 28, 9, 0, 0, 0, zone)
        assertEquals(next.toInstant().toEpochMilli(), cron.next(start.toInstant().toEpochMilli(), zone))
        assertTrue(Cron.parse("0 0 * * 0").next(0, ZoneId.of("UTC")) > 0)
    }

    @Test
    fun cronRejectsInvalidExpressions() {
        for (expression in listOf("61 * * * *", "* * * *", "x * * * *", "* 24 * * *")) {
            try {
                Cron.parse(expression)
                fail("accepted invalid cron: " + expression)
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun safePathsStaysInsideRootAndRemovesTrees() {
        val root = Files.createTempDirectory("dsh-agent-paths").toFile()
        try {
            val child = SafePaths.resolve(root, "a/b.txt", false)
            assertTrue(child.path.startsWith(root.canonicalPath + File.separator))
            child.parentFile.mkdirs()
            Files.write(child.toPath(), byteArrayOf(1))
            assertEquals(listOf("a"), SafePaths.list(root).map { it.name })
            assertFalse(SafePaths.list(child.parentFile).isEmpty())
            SafePaths.removeTree(child.parentFile)
            assertFalse(child.exists())
        } finally {
            SafePaths.removeTree(root)
        }
    }

    @Test
    fun safePathsRejectsEscapesAndAbsolutePaths() {
        val root = Files.createTempDirectory("dsh-agent-paths").toFile()
        try {
            for (path in listOf("../escape", "/absolute", "a/../../escape")) {
                try {
                    SafePaths.resolve(root, path, false)
                    fail("accepted escaping path: " + path)
                } catch (_: java.io.IOException) {
                }
            }
        } finally {
            SafePaths.removeTree(root)
        }
    }

    @Test
    fun onlyMutatingMethodsAreGated() {
        val writes = listOf(
            "ui.act", "ui.batch", "ui.fill", "ui.scrollUntil", "ui.scroll", "ui.press", "ui.ime",
            "policy.set", "dev.install", "dev.uninstall"
        )
        for (method in writes) assertTrue(method, AgentMethods.isMutation(method))
        for (method in listOf("ui.view", "ui.nodes", "ui.diff", "ui.wait", "sys.status", "session.open")) {
            assertFalse(method, AgentMethods.isMutation(method))
        }
        assertEquals(3, AgentMethods.API_VERSION)
    }
}
