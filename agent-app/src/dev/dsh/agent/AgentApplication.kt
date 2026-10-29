package dev.dsh.agent

import android.app.Application

class AgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentServer.start(this, null)
    }
}
