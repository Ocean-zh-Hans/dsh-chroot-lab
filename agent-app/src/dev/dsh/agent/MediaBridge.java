package dev.dsh.agent;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

final class MediaBridge {
    private MediaBridge() {
    }

    static Object call(String method, JSONObject params) throws Exception {
        Context context = AgentServer.context();
        switch (method) {
            case "notifications.list": return NotificationRelay.snapshot();
            case "notifications.dismiss": return new JSONObject().put("dismissed", NotificationRelay.dismiss(params.optString("key")));
            case "notifications.click": return notificationClick(params);
            case "notifications.reply": return notificationReply(params);
            case "clipboard.get": return clipboardGet(context);
            case "clipboard.set": return clipboardSet(context, params.optString("text"));
            case "clipboard.clear": return clipboardClear(context);
            case "media.nowPlaying": return nowPlaying(context);
            case "media.play": case "media.pause": case "media.next": case "media.prev": case "media.stop":
                return mediaControl(context, method, params);
            case "call.dial": {
                if (params.optBoolean("autoCall")) {
                    AgentError.confirm(params);
                    return RootClient.call(method, params);
                }
                String number = params.getString("number");
                if (!number.matches("[0-9+*#]{1,32}")) throw new AgentError("INVALID_PARAMS", "invalid phone number");
                try {
                    context.startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (SecurityException | android.content.ActivityNotFoundException e) {
                    if (RootClient.available()) return RootClient.call("activity.start", new JSONObject()
                            .put("action", "android.intent.action.DIAL").put("data", "tel:" + number));
                    throw new AgentError("START_REJECTED", "dialer start was rejected");
                }
                return new JSONObject().put("launchRequested", true).put("confirmed", false);
            }
            case "sms.send":
                throw new AgentError("ROLE_REQUIRED", "sending SMS requires an explicit default SMS role");
            case "sms.list": case "sms.search": case "contacts.search": case "calendar.list": case "screen.record":
                return RootClient.call(method, params);
            default: return null;
        }
    }

    private static JSONObject clipboardGet(Context context) throws Exception {
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        try {
            if (manager == null || !manager.hasPrimaryClip() || manager.getPrimaryClip() == null) {
                return new JSONObject().put("text", "").put("hasClip", false).put("available", true);
            }
            ClipData clip = manager.getPrimaryClip();
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < clip.getItemCount(); i++) {
                CharSequence value = clip.getItemAt(i).coerceToText(context);
                if (value != null) text.append(value);
            }
            return new JSONObject().put("text", text.toString()).put("hasClip", true).put("available", true);
        } catch (SecurityException e) {
            return new JSONObject().put("hasClip", false).put("available", false).put("reason", "clipboard focus required");
        }
    }

    private static JSONObject clipboardSet(Context context, String text) throws Exception {
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return new JSONObject().put("set", false).put("verified", false);
        try {
            manager.setPrimaryClip(ClipData.newPlainText("DSH", text));
            ClipData clip = manager.getPrimaryClip();
            boolean verified = clip != null && clip.getItemCount() == 1 && text.contentEquals(clip.getItemAt(0).coerceToText(context));
            return new JSONObject().put("set", verified).put("verified", verified);
        } catch (SecurityException e) {
            return new JSONObject().put("set", false).put("verified", false).put("reason", "clipboard focus required");
        }
    }

    private static JSONObject clipboardClear(Context context) throws Exception {
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) return new JSONObject().put("cleared", false).put("verified", false);
        try {
            manager.clearPrimaryClip();
            boolean verified = !manager.hasPrimaryClip() || manager.getPrimaryClip() == null;
            return new JSONObject().put("cleared", verified).put("verified", verified);
        } catch (SecurityException e) {
            return new JSONObject().put("cleared", false).put("verified", false).put("reason", "clipboard focus required");
        }
    }

    private static JSONObject notificationClick(JSONObject params) throws Exception {
        StatusBarNotification notification = NotificationRelay.find(params.getString("key"));
        if (notification == null || notification.getNotification().contentIntent == null) throw new AgentError("NOT_FOUND", "notification has no content intent");
        notification.getNotification().contentIntent.send();
        return new JSONObject().put("requested", true).put("confirmed", false);
    }

    private static JSONObject notificationReply(JSONObject params) throws Exception {
        StatusBarNotification notification = NotificationRelay.find(params.getString("key"));
        if (notification == null) throw new AgentError("NOT_FOUND", "notification not found");
        String value = params.getString("text");
        if (value.length() > 8192) throw new AgentError("INVALID_PARAMS", "reply is too long");
        Notification.Action action = null;
        int wanted = params.optInt("actionIndex", -1);
        Notification.Action[] actions = notification.getNotification().actions;
        if (actions != null) {
            for (int i = 0; i < actions.length; i++) {
                if (wanted >= 0 && wanted != i) continue;
                if (actions[i].getRemoteInputs() != null && actions[i].getRemoteInputs().length > 0) {
                    action = actions[i];
                    break;
                }
            }
        }
        if (action == null || action.actionIntent == null) throw new AgentError("UNAVAILABLE", "notification has no RemoteInput reply action");
        android.app.RemoteInput[] inputs = action.getRemoteInputs();
        android.app.RemoteInput selected = inputs[0];
        if (!params.optString("resultKey").isEmpty()) {
            for (android.app.RemoteInput input : inputs) if (input.getResultKey().equals(params.getString("resultKey"))) selected = input;
        }
        Bundle results = new Bundle();
        results.putCharSequence(selected.getResultKey(), value);
        Intent intent = new Intent();
        android.app.RemoteInput.addResultsToIntent(inputs, intent, results);
        action.actionIntent.send(AgentServer.context(), 0, intent);
        return new JSONObject().put("requested", true).put("confirmed", false);
    }

    private static List<MediaController> controllers(Context context) throws AgentError {
        MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            return manager.getActiveSessions(new ComponentName(context, NotificationRelay.class));
        } catch (SecurityException e) {
            throw new AgentError("NOTIFICATION_ACCESS_OFF", "media sessions require notification access");
        }
    }

    private static JSONObject nowPlaying(Context context) throws Exception {
        JSONArray result = new JSONArray();
        for (MediaController controller : controllers(context)) {
            PlaybackState playback = controller.getPlaybackState();
            android.media.MediaMetadata metadata = controller.getMetadata();
            JSONObject item = new JSONObject().put("package", controller.getPackageName())
                    .put("state", playback == null ? PlaybackState.STATE_NONE : playback.getState())
                    .put("position", playback == null ? 0 : playback.getPosition());
            if (metadata != null) {
                item.put("title", metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
                        .put("artist", metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST))
                        .put("duration", metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION));
            }
            result.put(item);
        }
        return new JSONObject().put("sessions", result).put("available", true);
    }

    private static JSONObject mediaControl(Context context, String method, JSONObject params) throws Exception {
        List<MediaController> controllers = controllers(context);
        String pkg = params.optString("package");
        for (MediaController controller : controllers) {
            if (!pkg.isEmpty() && !pkg.equals(controller.getPackageName())) continue;
            MediaController.TransportControls controls = controller.getTransportControls();
            switch (method) {
                case "media.play": controls.play(); break;
                case "media.pause": controls.pause(); break;
                case "media.next": controls.skipToNext(); break;
                case "media.prev": controls.skipToPrevious(); break;
                case "media.stop": controls.stop(); break;
                default: throw new AgentError("INVALID_PARAMS", "unknown media action");
            }
            return new JSONObject().put("requested", true).put("confirmed", false).put("package", controller.getPackageName());
        }
        if (RootClient.available()) return RootClient.call(method, params);
        throw new AgentError("NOT_FOUND", "no active media session");
    }
}
