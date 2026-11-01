package dev.chroot.console

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object ConsoleSupport {
    fun isLoopbackHttpUrl(value: String?): Boolean {
        if (value == null || value.length > 4096) return false
        return try {
            val uri = URI(value)
            val host = uri.host
            val loopback = "127.0.0.1" == host || "localhost".equals(host, ignoreCase = true)
            "http".equals(uri.scheme, ignoreCase = true) && loopback &&
                uri.userInfo == null && uri.port in 1..65535
        } catch (_: Exception) {
            false
        }
    }

    fun stateLabel(state: String?): String = when (state) {
        null, "" -> "未知"
        "running" -> "运行中"
        "stopped" -> "已停止"
        "starting" -> "启动中"
        "stopping" -> "停止中"
        "degraded" -> "运行异常"
        "failed" -> "启动失败"
        "stale" -> "状态过期"
        "recovery-required" -> "需要恢复"
        "rescue-needed" -> "需要救援"
        "rescue" -> "救援模式"
        else -> state
    }

    fun redactToken(value: String?): String {
        if (value == null) return ""
        return value.replace(Regex("([?&]token=)[^&\\s]*")) { match ->
            match.groupValues[1] + "•••"
        }
    }

    fun runCommand(timeoutSeconds: Long, vararg command: String): Result = run(timeoutSeconds, null, command)

    /** Same as [runCommand], but每读到一行就回调，用于把 start/purge 的进度显示出来。 */
    fun runCommandStreaming(timeoutSeconds: Long, onLine: (String) -> Unit, vararg command: String): Result =
        run(timeoutSeconds, onLine, command)

    private fun run(timeoutSeconds: Long, onLine: ((String) -> Unit)?, command: Array<out String>): Result {
        if (timeoutSeconds <= 0) return Result("无效的超时时间", 2)
        val maxOutput = 256 * 1024
        val out = StringBuilder()
        var process: Process? = null
        return try {
            process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val running = process
            val reader = Thread {
                try {
                    BufferedReader(InputStreamReader(running.inputStream, StandardCharsets.UTF_8)).use { input ->
                        while (true) {
                            val line = input.readLine() ?: break
                            val remaining = maxOutput - out.length
                            if (remaining <= 0) continue
                            out.append(line, 0, minOf(line.length, remaining))
                            if (out.length < maxOutput) out.append('\n')
                            if (onLine != null) {
                                try {
                                    onLine(line)
                                } catch (ignored: Exception) {
                                    // 回调里的 UI 异常不能影响命令本身
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    out.append("读取命令输出失败：").append(error.message).append('\n')
                }
            }
            reader.isDaemon = true
            reader.start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy()
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
                reader.join(2000)
                val warning = "命令超时（" + timeoutSeconds + " 秒）；后台操作可能仍在进行，请刷新状态后再试"
                val partial = out.toString().trim()
                Result(if (partial.isEmpty()) warning else partial + "\n" + warning, 124)
            } else {
                reader.join(2000)
                Result(out.toString().trim(), process.exitValue())
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Result("命令被中断；后台操作可能仍在进行", 130)
        } catch (error: Exception) {
            Result("命令执行失败：" + error.message, -1)
        } finally {
            process?.destroy()
        }
    }

    class Result(val output: String, val exitCode: Int) {
        fun success(): Boolean = exitCode == 0
    }
}

/**
 * Version strings arrive in two shapes from the guest: `node` reports `v24.18.0`
 * while `pnpm` reports `10.34.9`. Show them the same way — a stray missing `v`
 * reads like a truncated string, not like a formatting choice.
 */
internal fun versionLabel(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return "—"
    return if (trimmed.first().isDigit()) "v" + trimmed else trimmed
}
