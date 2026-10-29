package dev.dsh.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class AgentService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AgentServer.start(this, this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) AgentServer.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        AgentServer.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        AgentServer.detach(this)
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: AgentService? = null
    }
}
