package dev.chroot.console

import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun `diagnostics carries device facts and failing checks`() {
        val status = ConsoleStatus(
            state = "running",
            pid = "8123",
            image = "/data/media/0/ubuntu-rootfs/rootfs.img",
            imageBytes = 2L * 1024 * 1024 * 1024,
            loop = "loop46",
            loopQuery = "loop46",
            rootfsMounted = true,
            watchdogRunning = true,
            moduleVersion = "0.2.2",
            bootId = "abc",
        )
        val doctor = DoctorReport(
            pass = 43, warn = 1, fail = 1,
            checks = listOf(
                DoctorCheck("FAIL", "镜像目录不可写"),
                DoctorCheck("PASS", "state 目录存在"),
            ),
        )
        val text = diagnosticsText(status, doctor, listOf(PathEntry("image", "/data/media/0/ubuntu-rootfs", -1)), listOf("机型" to "PJZ110", "内核" to "6.6.66"))
        assertTrue(text.contains("机型: PJZ110"))
        assertTrue(text.contains("内核: 6.6.66"))
        assertTrue(text.contains("模块: 0.2.2"))
        assertTrue(text.contains("状态: running pid=8123"))
        assertTrue(text.contains("rootfs 挂载: yes"))
        assertTrue(text.contains("43 pass / 1 warn / 1 fail"))
        assertTrue(text.contains("FAIL 镜像目录不可写"))
        assertTrue(!text.contains("PASS state 目录存在"))
    }

    @Test
    fun `diagnostics says so when doctor has not run`() {
        val text = diagnosticsText(ConsoleStatus(state = "stopped"), null, emptyList(), emptyList())
        assertTrue(text.contains("体检: 未运行"))
    }
}
