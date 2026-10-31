package dev.dsh.agent

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class AgentRuntimeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            PresenceNotification.update(this@AgentRuntimeService)
            PresenceOverlay.update()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        PresenceNotification.ensureChannels(this)
        val notification: Notification = PresenceNotification.build(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(PresenceNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(PresenceNotification.ID, notification)
        }
        handler.post(ticker)
        AgentServer.start(this, AgentServer.accessibility())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PresenceNotification.update(this)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        PresenceNotification.cancel(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    companion object {
        @JvmStatic
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("agent", 0).edit().putBoolean("autoStart", enabled).apply()
            val intent = Intent(context, AgentRuntimeService::class.java)
            if (enabled) {
                PresenceState.reset(context)
                context.startForegroundService(intent)
            } else {
                context.stopService(intent)
            }
        }

        @JvmStatic
        fun enabled(context: Context): Boolean =
            context.getSharedPreferences("agent", 0).getBoolean("autoStart", false)
    }
}
