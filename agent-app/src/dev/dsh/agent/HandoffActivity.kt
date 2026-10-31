package dev.dsh.agent

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.ThemeController

/**
 * 唯一的“人到场”页面：半透明、excludeFromRecents，出现即代表 agent 已暂停。
 * 它不是控制面板，不显示聊天、日志或任务列表。
 */
class HandoffActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        PresenceState.pause(this, "人正在接管", handoff = true)
        val reason = intent.getStringExtra(EXTRA_REASON).orEmpty()
        setContent {
            COUITheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(COUITheme.colorScheme.background),
                ) {
                    HandoffScreen(reason)
                }
            }
        }
    }

    companion object {
        private const val EXTRA_REASON = "reason"

        fun intent(context: Context, reason: String): Intent =
            Intent(context, HandoffActivity::class.java).putExtra(EXTRA_REASON, reason)
    }
}

@Composable
private fun HandoffScreen(reason: String) {
    val context = LocalContext.current
    val colors = COUITheme.colorScheme
    val textStyles = COUITheme.textStyles

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("DSH 需要你接手", style = textStyles.title2, color = colors.onBackground)
                Text(
                    if (reason.isBlank()) "请处理当前页面上的验证、授权或人工步骤。" else reason,
                    style = textStyles.body1,
                    color = colors.onBackgroundVariant,
                )
                Text(
                    "处理完成后点“完成并继续”；如果现在不方便，点“保持暂停”即可。",
                    style = textStyles.footnote1,
                    color = colors.onBackgroundVariant,
                )
                Button(
                    onClick = {
                        PresenceState.resume(context)
                        (context as? HandoffActivity)?.finish()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("完成并继续") }
                Text(
                    "保持暂停",
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable { (context as? HandoffActivity)?.finish() }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    style = textStyles.button,
                    color = colors.primary,
                )
            }
        }
    }
}
