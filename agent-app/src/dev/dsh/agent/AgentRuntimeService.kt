package dev.dsh.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class AgentRuntimeService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("agent_runtime", "DSH Agent", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val notification: Notification = Notification.Builder(this, "agent_runtime")
            .setContentTitle("DSH Agent")
            .setContentText("本机控制面已启用")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(17321, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(17321, notification)
        }
        AgentServer.start(this, AgentServer.accessibility())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent): IBinder? = null

    companion object {
        @JvmStatic
        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("agent", 0).edit().putBoolean("autoStart", enabled).apply()
            val intent = Intent(context, AgentRuntimeService::class.java)
            if (enabled) context.startForegroundService(intent) else context.stopService(intent)
        }

        @JvmStatic
        fun enabled(context: Context): Boolean =
            context.getSharedPreferences("agent", 0).getBoolean("autoStart", false)
    }
}
