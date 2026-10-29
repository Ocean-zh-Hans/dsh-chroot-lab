package dev.dsh.agent;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

public final class NotificationRelay extends NotificationListenerService {
    private static volatile NotificationRelay instance;

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        AgentServer.onNotification("notification.posted", notification.getKey(), notification.getPackageName(), notification.getPostTime());
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification) {
        AgentServer.onNotification("notification.removed", notification.getKey(), notification.getPackageName(), notification.getPostTime());
    }

    static boolean connected() {
        return instance != null;
    }

    static StatusBarNotification find(String key) {
        NotificationRelay relay = instance;
        if (relay == null || key.isEmpty()) return null;
        StatusBarNotification[] active = relay.getActiveNotifications();
        if (active == null) return null;
        for (StatusBarNotification item : active) if (key.equals(item.getKey())) return item;
        return null;
    }

    static JSONArray snapshot() throws Exception {
        JSONArray out = new JSONArray();
        NotificationListenerService relay = instance;
        if (relay == null) return out;
        StatusBarNotification[] active = relay.getActiveNotifications();
        if (active == null) return out;
        for (StatusBarNotification sbn : active) {
            JSONObject item = new JSONObject();
            item.put("key", sbn.getKey());
            item.put("package", sbn.getPackageName());
            item.put("posted", sbn.getPostTime());
            item.put("ongoing", sbn.isOngoing());
            item.put("title", String.valueOf(sbn.getNotification().extras.getCharSequence("android.title")));
            item.put("text", String.valueOf(sbn.getNotification().extras.getCharSequence("android.text")));
            out.put(item);
        }
        return out;
    }

    static boolean dismiss(String key) {
        NotificationRelay relay = instance;
        if (relay == null || key.isEmpty()) return false;
        relay.cancelNotification(key);
        StatusBarNotification[] active = relay.getActiveNotifications();
        if (active == null) return false;
        for (StatusBarNotification item : active) {
            if (key.equals(item.getKey())) return false;
        }
        return true;
    }
}
