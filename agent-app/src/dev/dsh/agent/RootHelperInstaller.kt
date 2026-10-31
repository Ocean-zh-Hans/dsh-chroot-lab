package dev.dsh.agent

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 人的显式动作：把 APK 内置的 root-helper.jar 安装到 /data/adb/dsh-agent 并启动。
 * 不暴露为 Agent RPC；AI 不能自己提权，必须由用户在体检卡点“启动”。
 */
internal object RootHelperInstaller {
    @JvmStatic
    fun start(context: Context): String {
        val jar = File(context.cacheDir, "dsh-root-helper.jar")
        try {
            context.resources.openRawResource(R.raw.root_helper).use { input ->
                jar.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (t: Throwable) {
            return "内置 root helper 读取失败：" + (t.message ?: t.javaClass.simpleName)
        }
        val script = """
            BASE=/data/adb/dsh-agent
            mkdir -p "${'$'}BASE" || exit 11
            chmod 0700 "${'$'}BASE" || exit 12
            cp '${jar.absolutePath}' "${'$'}BASE/dsh-root-helper.jar.tmp" || exit 13
            chmod 0600 "${'$'}BASE/dsh-root-helper.jar.tmp" || exit 14
            mv "${'$'}BASE/dsh-root-helper.jar.tmp" "${'$'}BASE/dsh-root-helper.jar" || exit 15
            if [ -s "${'$'}BASE/helper.pid" ]; then
              OLD=${'$'}(cat "${'$'}BASE/helper.pid")
              kill -0 "${'$'}OLD" 2>/dev/null && exit 0
            fi
            nohup env ANDROID_DATA=/data ANDROID_ROOT=/system CLASSPATH="${'$'}BASE/dsh-root-helper.jar" \
              /system/bin/app_process -Djava.class.path="${'$'}BASE/dsh-root-helper.jar" / dev.dsh.agent.RootHelper \
              >"${'$'}BASE/helper.log" 2>&1 </dev/null &
            echo ${'$'}! >"${'$'}BASE/helper.pid"
            sleep 1
            kill -0 "${'$'}(cat "${'$'}BASE/helper.pid")" 2>/dev/null
        """.trimIndent()
        return try {
            val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroy()
                return "启动超时，请确认 root 授权后重试"
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.exitValue() == 0) {
                RootClient.invalidate()
                "root helper 已启动"
            } else {
                "启动失败（${process.exitValue()}）：" + (output.take(160).ifBlank { "无输出" })
            }
        } catch (t: Throwable) {
            "启动失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    /** 用户显式停止；只杀 root helper 进程，不卸载 jar。 */
    @JvmStatic
    fun stop(context: Context): String {
        val script = """
            BASE=/data/adb/dsh-agent
            if [ -s "${'$'}BASE/helper.pid" ]; then
              PID=${'$'}(cat "${'$'}BASE/helper.pid")
              kill "${'$'}PID" 2>/dev/null || true
              sleep 1
              kill -9 "${'$'}PID" 2>/dev/null || true
            fi
            rm -f "${'$'}BASE/helper.pid"
            exit 0
        """.trimIndent()
        return try {
            val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy()
                return "停止超时，请稍后重试"
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (process.exitValue() == 0) {
                RootClient.invalidate()
                "root helper 已停止"
            } else "停止失败（${process.exitValue()}）：" + (output.take(160).ifBlank { "无输出" })
        } catch (t: Throwable) {
            "停止失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }
}
