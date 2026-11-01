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
            schema = 1,
            imageUsedBytes = 512L * 1024 * 1024,
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
        assertTrue(text.contains("模块: 0.2.2  schema=1 (控制台要求 1)"))
        assertTrue(text.contains("控制台: " + BuildConfig.VERSION_NAME))
        // 报的是已用空间，不是 apparent size；否则 128 GiB 的稀疏镜像会盖住真实占用。
        assertTrue(text.contains("(已用 512 MB / 2.0 GB)"))
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
