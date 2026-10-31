package dev.chroot.console

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ConsoleStatus(
    val state: String = "",
    val pid: String = "",
    val url: String = "",
    val started: String = "",
    val note: String = "",
    val loop: String = "",
    val image: String = "",
    val lastGood: String = "",
    val healthAge: Int = -1,
    val diskFreeMb: Long = -1,
    val imageBytes: Long = -1,
    val snapshotCount: Int = -1,
    val autostart: Boolean = false,
    val guard: Boolean = false,
    val watchdogRunning: Boolean = false,
    val moduleVersion: String = "",
    val bootId: String = "",
    val pidValid: Boolean = false,
    val rootfsMounted: Boolean = false,
    val loopQuery: String = "",
    val residual: String = "",
)

data class DoctorCheck(val level: String, val message: String)

data class DoctorReport(
    val pass: Int = 0,
    val warn: Int = 0,
    val fail: Int = 0,
    val checks: List<DoctorCheck> = emptyList(),
) {
    val total: Int get() = pass + warn + fail
}

data class SnapshotEntry(
    val name: String,
    val bytes: Long,
    val created: String,
    val lastGood: Boolean,
)

data class PathEntry(val name: String, val path: String, val bytes: Long)

data class RuntimeInfo(
    val module: String = "",
    val moduleCode: String = "",
    val dshRuntime: String = "unknown",
    val hooksMode: String = "",
    val hooksReason: String = "",
    val imageMtime: String = "",
)

data class TtydInfo(
    val configured: String = "",
    val credentials: String = "",
    val running: Boolean = false,
    val state: String = "",
)

data class AppInfo(
    val dsh: String = "",
    val current: String = "",
    val previous: String = "",
    val node: String = "",
    val pnpm: String = "",
    val hooks: String = "",
    val versions: List<String> = emptyList(),
)

data class AppPlugin(val name: String, val kind: String, val active: Boolean)

object ConsoleRepo {
    private const val CTL = "/data/adb/modules/dsh_chroot/bin/dsh-ctl"

    private fun suBinary(): String =
        listOf("/system/bin/su", "/system/xbin/su", "su").firstOrNull { it == "su" || File(it).exists() } ?: "su"

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun ctl(vararg args: String, timeout: Long = 20): ConsoleSupport.Result {
        val command = (listOf(CTL) + args).joinToString(" ") { quote(it) }
        return ConsoleSupport.runCommand(timeout, suBinary(), "-c", command)
    }

    /** 需要额外环境变量的只读/低风险命令（例如 app exec 的 R1 确认）。 */
    fun ctlWithEnv(
        env: Map<String, String>,
        args: List<String>,
        timeout: Long = 20,
        onLine: ((String) -> Unit)? = null,
    ): ConsoleSupport.Result {
        val command = buildString {
            env.forEach { (key, value) -> append(key).append('=').append(quote(value)).append(' ') }
            append((listOf(CTL) + args).joinToString(" ") { quote(it) })
        }
        if (onLine != null) {
            return ConsoleSupport.runCommandStreaming(timeout, onLine, suBinary(), "-c", command)
        }
        return ConsoleSupport.runCommand(timeout, suBinary(), "-c", command)
    }

    /**
     * R3 operations need a one-time token. Issue it, then run the command with the
     * reason/impact recorded in the environment so the audit log keeps context.
     */
    fun ctlRisky(
        op: String,
        args: List<String>,
        reason: String,
        impact: String,
        timeout: Long = 180,
        onLine: ((String) -> Unit)? = null,
    ): ConsoleSupport.Result {
        val issue = ctl(
            "risk", "issue", "--op", op,
            "--reason", reason,
            "--impact", impact,
        )
        if (!issue.success()) return issue
        val token = issue.output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: ""
        if (token.length < 16) return ConsoleSupport.Result("无法获取一次性风险令牌", 1)
        val command = buildString {
            append("DSH_STEPS=1 DSH_RISK_REASON=").append(quote(reason)).append(' ')
            append("DSH_RISK_TOKEN=").append(quote(token)).append(' ')
            append((listOf(CTL) + args).joinToString(" ") { quote(it) })
        }
        if (onLine != null) {
            return ConsoleSupport.runCommandStreaming(timeout, onLine, suBinary(), "-c", command)
        }
        return ConsoleSupport.runCommand(timeout, suBinary(), "-c", command)
    }

    fun status(): ConsoleStatus {
        val result = ctl("status", "--json", timeout = 20)
        if (!result.success()) return ConsoleStatus(note = result.output.take(200))
        return try {
            val json = JSONObject(result.output)
            ConsoleStatus(
                state = json.optString("state"),
                pid = if (json.isNull("pid")) "" else json.optString("pid"),
                url = json.optString("url"),
                started = json.optString("started"),
                note = json.optString("note"),
                loop = json.optString("loop"),
                image = json.optString("image"),
                lastGood = json.optString("last_good"),
                healthAge = json.optInt("health_age", -1),
                diskFreeMb = json.optLong("disk_free_mb", -1),
                imageBytes = json.optLong("image_bytes", -1),
                snapshotCount = json.optInt("snapshot_count", -1),
                autostart = json.optBoolean("autostart"),
                guard = json.optBoolean("guard"),
                watchdogRunning = json.optBoolean("watchdog_running"),
                moduleVersion = json.optString("module_version"),
                bootId = json.optString("boot_id"),
                pidValid = json.optBoolean("pid_valid"),
                rootfsMounted = json.optBoolean("rootfs_mounted"),
                loopQuery = json.optString("loop_query"),
                residual = json.optString("residual_resources"),
            )
        } catch (error: Exception) {
            ConsoleStatus(note = "状态解析失败：" + error.message)
        }
    }

    fun doctor(): DoctorReport {
        val result = ctl("doctor", "--json", timeout = 120)
        if (result.output.isBlank()) return DoctorReport()
        return try {
            val json = JSONObject(result.output)
            val summary = json.optJSONObject("summary") ?: JSONObject()
            val checks = json.optJSONArray("checks") ?: JSONArray()
            val list = ArrayList<DoctorCheck>(checks.length())
            for (index in 0 until checks.length()) {
                val item = checks.optJSONObject(index) ?: continue
                list += DoctorCheck(item.optString("level"), item.optString("message"))
            }
            DoctorReport(
                pass = summary.optInt("pass"),
                warn = summary.optInt("warn"),
                fail = summary.optInt("fail"),
                checks = list,
            )
        } catch (error: Exception) {
            DoctorReport(checks = listOf(DoctorCheck("FAIL", "体检结果解析失败：" + error.message)))
        }
    }

    fun snapshots(): List<SnapshotEntry> {
        val result = ctl("snapshot", "list", "--json", timeout = 20)
        if (!result.success()) return emptyList()
        return try {
            val json = JSONObject(result.output)
            val array = json.optJSONArray("snapshots") ?: JSONArray()
            val list = ArrayList<SnapshotEntry>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                list += SnapshotEntry(
                    name = item.optString("name"),
                    bytes = item.optLong("bytes", 0),
                    created = item.optString("created"),
                    lastGood = item.optBoolean("last_good"),
                )
            }
            list.sortedWith(compareByDescending<SnapshotEntry> { it.lastGood }.thenByDescending { it.created })
        } catch (error: Exception) {
            emptyList()
        }
    }

    fun runtime(): RuntimeInfo {
        val result = ctl("version", "--json", timeout = 30)
        if (!result.success()) return RuntimeInfo()
        return try {
            val json = JSONObject(result.output)
            val hooks = json.optJSONObject("hooks_status")
            RuntimeInfo(
                module = json.optString("module"),
                moduleCode = json.optString("module_version_code"),
                dshRuntime = json.optString("dsh_runtime", "unknown"),
                hooksMode = hooks?.optString("mode") ?: "",
                hooksReason = hooks?.optString("reason") ?: "",
                imageMtime = json.optString("image_mtime"),
            )
        } catch (error: Exception) {
            RuntimeInfo()
        }
    }

    fun ttyd(): TtydInfo {
        val result = ctl("ttyd", "status", "--json", timeout = 20)
        if (!result.success()) return TtydInfo()
        return try {
            val json = JSONObject(result.output)
            TtydInfo(
                configured = json.optString("configured"),
                credentials = json.optString("credentials"),
                running = json.optBoolean("running"),
                state = json.optString("state"),
            )
        } catch (error: Exception) {
            TtydInfo()
        }
    }

    fun appInfo(): AppInfo {
        val result = ctl("app", "version", "--json", timeout = 30)
        if (!result.success()) return AppInfo()
        return try {
            val json = JSONObject(result.output)
            val versions = json.optJSONArray("versions")
            AppInfo(
                dsh = json.optString("dsh"),
                current = json.optString("current"),
                previous = json.optString("previous"),
                node = json.optString("node"),
                pnpm = json.optString("pnpm"),
                hooks = json.optString("hooks"),
                versions = buildList {
                    for (index in 0 until (versions?.length() ?: 0)) {
                        add(versions?.optString(index).orEmpty())
                    }
                },
            )
        } catch (error: Exception) {
            AppInfo()
        }
    }

    fun appPlugins(): List<AppPlugin> {
        val result = ctl("app", "plugins", "--json", timeout = 30)
        if (!result.success()) return emptyList()
        return try {
            val items = JSONObject(result.output).optJSONArray("items") ?: return emptyList()
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    add(
                        AppPlugin(
                            name = item.optString("name"),
                            kind = item.optString("kind"),
                            active = item.optBoolean("active"),
                        ),
                    )
                }
            }
        } catch (error: Exception) {
            emptyList()
        }
    }

    /** 一次性 guest 命令：R1，需要显式确认；输出按行回调。 */
    fun appExec(command: String, onLine: ((String) -> Unit)? = null): ConsoleSupport.Result =
        ctlWithEnv(mapOf("DSH_RISK_CONFIRM" to "yes"), listOf("app", "exec", "--", command), timeout = 300, onLine = onLine)

    fun paths(): List<PathEntry> {
        val result = ctl("paths", "--json", timeout = 30)
        if (!result.success()) return emptyList()
        return try {
            val json = JSONObject(result.output)
            val array = json.optJSONArray("paths") ?: JSONArray()
            val list = ArrayList<PathEntry>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                list += PathEntry(item.optString("name"), item.optString("path"), item.optLong("bytes", 0))
            }
            list
        } catch (error: Exception) {
            emptyList()
        }
    }

    /** Read-only device facts for the device tab: no writes, no root commands beyond getprop. */
    fun device(): List<Pair<String, String>> {
        val command = "getprop ro.product.model; getprop ro.build.version.release; " +
            "getprop ro.build.version.sdk; getprop ro.build.type; uname -r; getprop ro.boot.verifiedbootstate"
        val result = ConsoleSupport.runCommand(20, "su", "-c", command)
        if (!result.success()) return emptyList()
        val lines = result.output.trim().lines().map { it.trim() }
        val labels = listOf("机型", "Android", "API", "构建", "内核", "verifyboot")
        return labels.mapIndexedNotNull { index, label ->
            val value = lines.getOrNull(index).orEmpty()
            if (value.isEmpty()) null else label to value
        }
    }

    fun logs(kind: String): String {
        val result = ctl("log", "200", timeout = 30)
        if (!result.success() && result.output.isBlank()) return result.output
        if (kind == "all") return result.output
        val marker = when (kind) {
            "nsinit" -> "nsinit.log"
            "dsh" -> "dsh.log"
            "boot" -> "boot.log"
            "watchdog" -> "watchdog.log"
            else -> return result.output
        }
        val builder = StringBuilder()
        var keep = false
        result.output.lineSequence().forEach { line ->
            if (line.startsWith("== ")) keep = line.contains(marker)
            if (keep) builder.append(line).append('\n')
        }
        return builder.toString().ifBlank { "（没有匹配的日志）" }
    }

    fun humanBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        if (gib >= 1.0) return String.format("%.1f GB", gib)
        val mib = bytes.toDouble() / (1024.0 * 1024.0)
        if (mib >= 1.0) return String.format("%.0f MB", mib)
        return String.format("%.0f KB", bytes / 1024.0)
    }
}
