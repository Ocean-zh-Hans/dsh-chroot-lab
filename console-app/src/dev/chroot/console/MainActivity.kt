package dev.chroot.console

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val urlFile = "/data/adb/chroot/state/runtime/url"
    private val legacyUrlFile = "/data/adb/chroot/state/guest/url"
    private val ctl = "/data/adb/modules/dsh_chroot/bin/dsh-ctl"
    private val pathPrefix = "PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:" +
        "/data/adb/magisk:/data/adb/modules/dsh_chroot/bin:/data/adb/chroot/bin:\$PATH; "
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private val statusTitle = mutableStateOf("")
    private val statusDetail = mutableStateOf("")
    private val auto = mutableStateOf(true)
    private val protect = mutableStateOf(true)
    private val guard = mutableStateOf(false)
    private val rw = mutableStateOf(false)
    private val dialog = mutableStateOf<Pair<String, String>?>(null)
    private val purgeStep = mutableStateOf(0)

    @Volatile
    private var refreshing = false
    private var lastUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("chroot-console", MODE_PRIVATE)
        auto.value = prefs.getBoolean("autostart", true)
        protect.value = prefs.getBoolean("protect", true)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConsoleScreen()
                }
            }
        }
        refreshStatus()
    }

    @Composable
    private fun ConsoleScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 28.dp, top = 24.dp, end = 28.dp, bottom = 32.dp),
        ) {
            Text("Chroot 控制台", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(statusTitle.value, fontSize = 18.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            SelectionContainer {
                Text(statusDetail.value, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))
            }
            Section("生命周期")
            ActionRow(listOf(
                "启动" to ::startChroot,
                "停止" to { runCtl("stop", "停止", true) },
                "重启" to { runCtl("restart --apply", "重启", true) },
            ))
            ActionRow(listOf(
                "刷新" to ::refreshStatus,
                "启动计划" to { runCtl("plan", "启动计划", false) },
                "环境检查" to { runCtl("doctor", "环境检查", false) },
            ))
            ActionRow(listOf(
                "日志" to { runCtl("log 200", "日志", false) },
                "快照列表" to { runCtl("snapshot list", "快照列表", false) },
                "DSH 页面" to ::openWeb,
            ))
            ActionRow(listOf("外部浏览器" to ::openBrowser))
            ActionRow(listOf(
                "创建快照" to { runCtl("snapshot create app-" + System.currentTimeMillis(), "创建快照", true) },
                "清理残留" to { runCtl("recover --apply", "清理残留", true) },
                "清除数据" to { purgeStep.value = 1 },
            ))
            Section("设置")
            SwitchRow("开机自动启动", auto.value) { checked ->
                auto.value = checked
                prefs.edit().putBoolean("autostart", checked).apply()
                runCtl("autostart " + if (checked) "on" else "off", "自动启动", true)
            }
            SwitchRow("保护关键路径", protect.value) { checked ->
                protect.value = checked
                prefs.edit().putBoolean("protect", checked).apply()
                toast("下次启动时生效")
            }
            SwitchRow("新会话需要操作确认", guard.value) { checked ->
                guard.value = checked
                runCtl("guard " + if (checked) "on" else "off", "操作确认", true)
            }
            SwitchRow("写入 /system（私有覆盖层）", rw.value) { checked ->
                rw.value = checked
                runCtl("system-rw " + if (checked) "on" else "off" + " system --apply", "写入 /system", true)
            }
            Text(
                "这是可选控制台，不提供 root 隐藏功能。启动和停止通过本机 su 授权执行固定 " +
                    "dsh-ctl 命令。若未获 root，请在 root 管理器中主动授权并彻底重启本应用。" +
                    "DSH 在网页标签中打开，命令输出保留原文（token 隐藏）以便排错。" +
                    "结束本应用不会停止 chroot。",
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 18.dp),
            )
        }
        Dialogs()
    }

    @Composable
    private fun Section(text: String) {
        Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
    }

    @Composable
    private fun ActionRow(actions: List<Pair<String, () -> Unit>>) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.forEach { (label, action) ->
                Button(onClick = action, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp)) {
                    Text(label)
                }
            }
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp)
            Switch(checked = checked, onCheckedChange = { if (!refreshing) onChange(it) })
        }
    }

    @Composable
    private fun Dialogs() {
        dialog.value?.let { (title, text) ->
            AlertDialog(
                onDismissRequest = { dialog.value = null },
                confirmButton = { TextButton(onClick = { dialog.value = null }) { Text("确定") } },
                title = { Text(title) },
                text = { SelectionContainer { Text(text) } },
            )
        }
        when (purgeStep.value) {
            1 -> AlertDialog(
                onDismissRequest = { purgeStep.value = 0 },
                confirmButton = { TextButton(onClick = { purgeStep.value = 2 }) { Text("继续") } },
                dismissButton = { TextButton(onClick = { purgeStep.value = 0 }) { Text("取消") } },
                title = { Text("清除全部 chroot 数据？") },
                text = { Text("将删除 /data/adb/chroot 和 /data/media/0/ubuntu-rootfs。此操作无法撤销，请先停止 chroot，并确认不再需要其中的数据。") },
            )
            2 -> AlertDialog(
                onDismissRequest = { purgeStep.value = 0 },
                confirmButton = {
                    TextButton(onClick = {
                        purgeStep.value = 0
                        runCtl("purge --yes", "清除数据", true)
                    }) { Text("永久清除") }
                },
                dismissButton = { TextButton(onClick = { purgeStep.value = 0 }) { Text("取消") } },
                title = { Text("最后确认") },
                text = { Text("镜像、快照和 DSH 数据都将永久删除。确定执行 purge？") },
            )
        }
    }

    private fun startChroot() {
        var args = "start --apply"
        if (!protect.value) args += " --protect-critical=off"
        runCtl(args, "启动", true)
    }

    private fun refreshStatus() {
        if (refreshing) return
        refreshing = true
        executor.execute {
            val marker = "__DSH_CONSOLE_"
            val command = pathPrefix + ctl + " status --json; printf '\\n" + marker + "AUTO\\n'; " +
                ctl + " autostart status; printf '\\n" + marker + "GUARD\\n'; " +
                ctl + " guard status; printf '\\n" + marker + "RW\\n'; " +
                ctl + " system-rw status system"
            val combined = runRoot(command, 90)
            val sections = combined.output.split(Regex(marker + "(AUTO|GUARD|RW)\\n"))
            val statusText = sections[0].trim()
            val statusExit = if (statusText.startsWith("{") && statusText.endsWith("}")) 0 else combined.exitCode
            val status = ConsoleSupport.Result(statusText, statusExit)
            val autoResult = if (sections.size > 1) sections[1].trim() else ""
            val guardResult = if (sections.size > 2) sections[2].trim() else ""
            val rwResult = if (sections.size > 3) sections[3].trim() else ""
            runOnUiThread {
                if (!status.success()) {
                    statusTitle.value = "状态：读取失败"
                    statusDetail.value = ConsoleSupport.redactToken(status.output)
                    lastUrl = ""
                } else {
                    try {
                        val json = JSONObject(status.output)
                        statusTitle.value = "状态：" + ConsoleSupport.stateLabel(json.optString("state", "unknown"))
                        statusDetail.value = buildString {
                            append("PID：").append(json.optString("pid", "none")).append('\n')
                            append("地址：").append(ConsoleSupport.redactToken(json.optString("url", "none"))).append('\n')
                            append("loop 设备：").append(json.optString("loop", "none")).append('\n')
                            append("镜像：").append(json.optString("image", "none"))
                            val note = json.optString("note", "")
                            if (note.isNotEmpty()) append('\n').append("说明：").append(note)
                        }
                        val url = json.optString("url", "")
                        lastUrl = if (ConsoleSupport.isLoopbackHttpUrl(url)) url else ""
                    } catch (error: Exception) {
                        statusTitle.value = "状态：返回内容无效"
                        statusDetail.value = ConsoleSupport.redactToken(status.output)
                        lastUrl = ""
                    }
                }
                auto.value = autoResult.contains("autostart: on")
                guard.value = guardResult.contains("guard: on")
                rw.value = rwResult.contains("overlay mounted")
                refreshing = false
            }
        }
    }

    private fun openWeb() = withUrl(::openWeb)

    private fun openBrowser() = withUrl(::openBrowser)

    private fun withUrl(action: (String) -> Unit) {
        if (lastUrl.isNotEmpty()) {
            action(lastUrl)
            return
        }
        executor.execute {
            val result = runRoot("/system/bin/cat $urlFile 2>/dev/null || /system/bin/cat $legacyUrlFile 2>/dev/null", 15)
            runOnUiThread {
                if (result.success()) action(result.output.trim())
                else toast("无法读取 DSH 地址：" + ConsoleSupport.redactToken(result.output))
            }
        }
    }

    private fun openWeb(url: String) {
        if (!ConsoleSupport.isLoopbackHttpUrl(url)) {
            toast("没有本机 DSH 地址，请先启动 chroot")
            return
        }
        startActivity(Intent(this, WebActivity::class.java).putExtra("url", url))
    }

    private fun openBrowser(url: String) {
        if (!ConsoleSupport.isLoopbackHttpUrl(url)) {
            toast("没有本机 DSH 地址，请先启动 chroot")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (error: Exception) {
            toast("没有可用的浏览器")
        }
    }

    private fun runCtl(args: String, label: String, refreshAfter: Boolean) {
        toast("$label…")
        executor.execute {
            val result = runRoot(pathPrefix + ctl + " " + args)
            runOnUiThread {
                val output = ConsoleSupport.redactToken(result.output.ifEmpty { "（无输出）" })
                if (!result.success()) dialog.value = "$label（失败）" to "退出码：${result.exitCode}\n$output"
                else dialog.value = label to output
                if (refreshAfter) refreshStatus()
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun findSu(): String {
        for (candidate in listOf("/data/adb/ksu/bin/su", "/data/adb/magisk/su", "/system/bin/su", "/system/xbin/su", "su")) {
            if (candidate == "su" || File(candidate).canExecute()) return candidate
        }
        return "su"
    }

    private fun runRoot(command: String, timeoutSeconds: Long = 180): ConsoleSupport.Result {
        return ConsoleSupport.runCommand(timeoutSeconds, findSu(), "-c", command)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
