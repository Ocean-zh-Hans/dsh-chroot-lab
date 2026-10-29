package dev.dsh.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !AgentRuntimeService.enabled(context)) return
        try {
            context.startForegroundService(Intent(context, AgentRuntimeService::class.java))
        } catch (_: RuntimeException) {
            Diagnostics.record("runtime.boot", "app", "UNAVAILABLE", 0)
        }
    }
}
