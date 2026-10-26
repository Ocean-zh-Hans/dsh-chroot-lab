package dev.dsh.agent;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Optional notification bridge; only active if the user grants notification access. */
public final class NotificationRelay extends NotificationListenerService {
    private static final List<StatusBarNotification> ITEMS = new ArrayList<>();
    private static volatile NotificationRelay instance;

    @Override
    public void onListenerConnected() {
        instance = this;
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        synchronized (ITEMS) {
            ITEMS.removeIf(old -> old.getKey().equals(sbn.getKey()));
            ITEMS.add(sbn);
            while (ITEMS.size() > 200) ITEMS.remove(0);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        synchronized (ITEMS) {
            ITEMS.removeIf(old -> old.getKey().equals(sbn.getKey()));
        }
    }

    static JSONArray snapshot() throws Exception {
        JSONArray out = new JSONArray();
        synchronized (ITEMS) {
            for (StatusBarNotification sbn : ITEMS) {
                JSONObject item = new JSONObject();
                item.put("key", sbn.getKey());
                item.put("package", sbn.getPackageName());
                item.put("posted", sbn.getPostTime());
                item.put("ongoing", sbn.isOngoing());
                item.put("title", String.valueOf(sbn.getNotification().extras.getCharSequence("android.title")));
                item.put("text", String.valueOf(sbn.getNotification().extras.getCharSequence("android.text")));
                out.put(item);
            }
        }
        return out;
    }

    static boolean dismiss(String key) {
        NotificationRelay relay = instance;
        if (relay == null) return false;
        synchronized (ITEMS) {
            for (StatusBarNotification sbn : ITEMS) {
                if (sbn.getKey().equals(key)) {
                    relay.cancelNotification(key);
                    return true;
                }
            }
        }
        return false;
    }
}
