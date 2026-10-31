package dev.dsh.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PresenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "dev.dsh.agent.PRESENCE_PAUSE" -> PresenceState.pause(context)
            "dev.dsh.agent.PRESENCE_RESUME" -> PresenceState.resume(context)
            "dev.dsh.agent.PRESENCE_STOP" -> PresenceState.stop(context)
        }
    }
}
