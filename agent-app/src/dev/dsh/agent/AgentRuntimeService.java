package dev.dsh.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public final class AgentRuntimeService extends Service {
    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("agent", 0).edit().putBoolean("autoStart", enabled).apply();
        Intent intent = new Intent(context, AgentRuntimeService.class);
        if (enabled) context.startForegroundService(intent);
        else context.stopService(intent);
    }

    static boolean enabled(Context context) {
        return context.getSharedPreferences("agent", 0).getBoolean("autoStart", false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel("agent_runtime", "DSH Agent", NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        Notification notification = new Notification.Builder(this, "agent_runtime")
                .setContentTitle("DSH Agent").setContentText("本机控制面已启用")
                .setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(17321, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(17321, notification);
        AgentServer.start(this, AgentServer.accessibility());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
