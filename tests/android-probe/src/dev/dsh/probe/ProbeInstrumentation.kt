package dev.dsh.probe

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Bundle
import android.os.Process
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class ProbeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun read(input: InputStream, limit: Int): String {
        val out = StringBuilder()
        InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            while (out.length < limit) {
                val count = reader.read(buffer, 0, minOf(buffer.size, limit - out.length))
                if (count < 0) break
                out.append(buffer, 0, count)
            }
        }
        return out.toString()
    }

    private fun file(path: String, limit: Int): String =
        FileInputStream(path).use { input -> read(input, limit) }

    private fun readable(path: String): Boolean = try {
        FileInputStream(path).use { input -> input.read() >= 0 }
    } catch (_: Exception) {
        false
    }

    private fun property(name: String): String {
        var process: Process? = null
        return try {
            process = ProcessBuilder("/system/bin/getprop", name).start()
            val value = read(process.inputStream, 4096).trim()
            if (!process.waitFor(5, TimeUnit.SECONDS)) "timeout"
            else if (process.exitValue() == 0) value else "exit-" + process.exitValue()
        } catch (error: Exception) {
            error.javaClass.simpleName
        } finally {
            process?.destroy()
        }
    }

    override fun onStart() {
        super.onStart()
        val result = Bundle()
        try {
            val context: Context = targetContext
            val mountinfo = file("/proc/self/mountinfo", 4 * 1024 * 1024)
            result.putInt("uid", Process.myUid())
            result.putString("package_name", context.packageName)
            result.putString("selinux_context", file("/proc/self/attr/current", 4096).trim())
            result.putBoolean("mount_rootfs_visible", mountinfo.contains("/tmp/dsh-rootfs"))
            result.putBoolean("mount_chroot_visible", mountinfo.contains("/data/adb/chroot"))
            result.putBoolean("mount_image_path_visible", mountinfo.contains("ubuntu-rootfs"))
            result.putBoolean("module_readable", readable("/data/adb/modules/dsh_chroot/module.prop"))
            result.putBoolean("image_readable", readable("/data/media/0/ubuntu-rootfs/rootfs.img"))
            result.putBoolean("agent_token_readable", readable("/data/data/dev.dsh.agent/files/token"))
            result.putBoolean("enforce_readable", readable("/sys/fs/selinux/enforce"))
            result.putString("ro_debuggable", property("ro.debuggable"))
            result.putString("ro_secure", property("ro.secure"))
            val names = File("/storage/emulated/0").list()
            result.putBoolean("storage_listing", names != null)
            var markers = false
            if (names != null) {
                for (name in names) {
                    if (name.equals("magisk", ignoreCase = true) ||
                        name.equals("kernelsu", ignoreCase = true) ||
                        name.equals("su", ignoreCase = true)
                    ) markers = true
                }
            }
            result.putBoolean("storage_root_markers", markers)
        } catch (error: Throwable) {
            result.putString("probe_error", error.javaClass.name)
        }
        sendStatus(1, result)
        finish(if (result.getString("probe_error") == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
