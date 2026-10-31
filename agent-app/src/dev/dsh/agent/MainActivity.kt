package dev.dsh.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import io.github.suqi8.coui.kmp.theme.COUITheme
import io.github.suqi8.coui.kmp.theme.ColorSchemeMode
import io.github.suqi8.coui.kmp.theme.ThemeController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            COUITheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
                AgentHomeScreen()
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
