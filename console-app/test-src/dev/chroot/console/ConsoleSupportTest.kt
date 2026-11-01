package dev.chroot.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsoleSupportTest {
    @Test
    fun acceptsOnlyLoopbackHttpUrls() {
        assertTrue(ConsoleSupport.isLoopbackHttpUrl("http://127.0.0.1:1234/?token=test"))
        assertTrue(ConsoleSupport.isLoopbackHttpUrl("http://localhost:7681/"))
        for (url in listOf(
            "http://127.0.0.1.evil.example:1234/",
            "http://127.0.0.1:80@evil.example/",
            "https://127.0.0.1:1234/",
            "http://127.0.0.1/",
            "http://127.0.0.1:70000/",
            "not a URL"
        )) {
            assertFalse("accepted invalid URL: " + url, ConsoleSupport.isLoopbackHttpUrl(url))
        }
    }

    @Test
    fun labelsAndRedacts() {
        assertTrue(ConsoleSupport.Result("ok", 0).success())
        assertFalse(ConsoleSupport.Result("bad", 4).success())
        assertEquals("运行中", ConsoleSupport.stateLabel("running"))
        assertEquals("已停止", ConsoleSupport.stateLabel("stopped"))
        assertEquals("需要恢复", ConsoleSupport.stateLabel("recovery-required"))
        assertEquals("future-state", ConsoleSupport.stateLabel("future-state"))
        assertEquals("未知", ConsoleSupport.stateLabel(null))
        val redacted = ConsoleSupport.redactToken("http://127.0.0.1:1234/?token=test-secret")
        assertEquals("http://127.0.0.1:1234/?token=•••", redacted)
        assertFalse(redacted.contains("test-secret"))
    }

    @Test
    fun runsCommandsAndEnforcesTimeout() {
        val command = ConsoleSupport.runCommand(5, "sh", "-c", "printf command-ok")
        assertTrue(command.success())
        assertEquals("command-ok", command.output)
        val started = System.nanoTime()
        val timeout = ConsoleSupport.runCommand(5, "sh", "-c", "printf 'before-timeout\\n'; exec sleep 30")
        assertEquals(124, timeout.exitCode)
        assertTrue(timeout.output.contains("before-timeout"))
        assertTrue(timeout.output.contains("后台操作可能仍在进行"))
        assertTrue(System.nanoTime() - started < 15_000_000_000L)
    }

    @Test
    fun normalisesVersionLabels() {
        // node 报 v24.18.0，pnpm 报 10.34.9；同一张卡上必须长得一样。
        assertEquals("v24.18.0", versionLabel("v24.18.0"))
        assertEquals("v10.34.9", versionLabel("10.34.9"))
        assertEquals("v1.4.2", versionLabel(" 1.4.2 "))
        assertEquals("—", versionLabel(""))
    }
}
