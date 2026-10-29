package dev.dsh.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || !AgentRuntimeService.enabled(context)) return;
        try {
            context.startForegroundService(new Intent(context, AgentRuntimeService.class));
        } catch (RuntimeException e) {
            Diagnostics.record("runtime.boot", "app", "UNAVAILABLE", 0);
        }
    }
}
