package dev.dsh.agent;

import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;

import org.json.JSONObject;

final class SystemEvents {
    private static Context context;
    private static BroadcastReceiver receiver;
    private static ClipboardManager.OnPrimaryClipChangedListener clipboard;

    private SystemEvents() {
    }

    static synchronized void init(Context ctx) {
        if (receiver != null) return;
        context = ctx.getApplicationContext();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                try {
                    String action = intent.getAction();
                    JSONObject data = new JSONObject();
                    switch (action == null ? "" : action) {
                        case Intent.ACTION_SCREEN_ON:
                            AgentServer.events.publish("screen.on", data);
                            break;
                        case Intent.ACTION_SCREEN_OFF:
                            AgentServer.events.publish("screen.off", data);
                            break;
                        case Intent.ACTION_USER_PRESENT:
                            AgentServer.events.publish("screen.unlock", data);
                            break;
                        case Intent.ACTION_BATTERY_CHANGED:
                            data.put("level", intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
                                    .put("scale", intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
                                    .put("status", intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
                                    .put("plugged", intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
                            AgentServer.events.publish("battery", data);
                            break;
                        case Intent.ACTION_POWER_CONNECTED:
                        case Intent.ACTION_POWER_DISCONNECTED:
                            AgentServer.events.publish("battery", data.put("plugged", action.equals(Intent.ACTION_POWER_CONNECTED) ? 1 : 0));
                            break;
                        case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                            AgentServer.events.publish("network", data.put("airplane", intent.getBooleanExtra("state", false)));
                            break;
                        case ConnectivityManager.CONNECTIVITY_ACTION:
                            NetworkInfo info = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
                            AgentServer.events.publish("network", data.put("connected", info != null && info.isConnected()));
                            break;
                        default: break;
                    }
                } catch (Exception e) {
                    Diagnostics.record("system.event", "app", "EVENT_FAILED", 0);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, filter);
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager != null) {
            clipboard = () -> AgentServer.events.publish("clipboard.changed", new JSONObject());
            manager.addPrimaryClipChangedListener(clipboard);
        }
    }

    static synchronized void close() {
        if (receiver != null) context.unregisterReceiver(receiver);
        receiver = null;
        if (clipboard != null) {
            ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) manager.removePrimaryClipChangedListener(clipboard);
        }
        clipboard = null;
        context = null;
    }
}
