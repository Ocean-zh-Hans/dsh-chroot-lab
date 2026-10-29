package dev.dsh.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AgentScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AgentServer.attachActivity(this)
    }

    override fun onPause() {
        AgentServer.detachActivity(this)
        super.onPause()
    }
}

@Composable
fun AgentScreen() {
    val context = LocalContext.current
    val tokenPath = remember { AgentServer.tokenFile(context).toString() }
    var summary by remember { mutableStateOf(AgentServer.summary()) }
    var keepAlive by remember { mutableStateOf(AgentRuntimeService.enabled(context)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        AgentRuntimeService.setEnabled(context, true)
        keepAlive = true
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    LaunchedEffect(Unit) {
        while (isActive) {
            summary = withContext(Dispatchers.IO) { AgentServer.summary() }
            keepAlive = AgentRuntimeService.enabled(context)
            delay(1000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DSH Agent", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(summary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        SelectionContainer {
            Text("Token 文件（供本机 root 客户端读取）：\n$tokenPath", fontSize = 14.sp)
        }
        Text(
            "1）请在系统设置中允许 DSH Agent 的无障碍服务。\n" +
                "2）需要读取通知或媒体时，再允许通知使用权。\n" +
                "3）桥接仅监听 127.0.0.1:17321；App 自身不申请 root。",
            fontSize = 14.sp,
        )
        Button(onClick = { openSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
            Text("打开无障碍设置")
        }
        Button(onClick = { openSettings(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
            Text("打开通知使用权设置")
        }
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 30) {
                openSettings(context, Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:dev.dsh.agent")))
            } else {
                storagePermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("允许 Download 和 .dsh 文件访问")
        }
        Button(onClick = {
            if (keepAlive) {
                AgentRuntimeService.setEnabled(context, false)
                keepAlive = false
            } else if (Build.VERSION.SDK_INT >= 33) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                AgentRuntimeService.setEnabled(context, true)
                keepAlive = true
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (keepAlive) "关闭前台保活和开机启动" else "启用前台保活和开机启动")
        }
        Button(onClick = {
            openSettings(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:dev.dsh.agent")))
        }, modifier = Modifier.fillMaxWidth()) {
            Text("检查电池优化设置")
        }
    }
}

private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (error: android.content.ActivityNotFoundException) {
        Toast.makeText(context, "此系统没有对应的设置页面", Toast.LENGTH_SHORT).show()
    } catch (error: SecurityException) {
        Toast.makeText(context, "请在该应用的系统设置中完成授权", Toast.LENGTH_SHORT).show()
    }
}
