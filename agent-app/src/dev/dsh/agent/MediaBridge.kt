package dev.dsh.agent

import android.app.Notification
import android.app.RemoteInput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

internal object MediaBridge {
    @JvmStatic
    fun call(method: String, params: JSONObject): Any? {
        val context = AgentServer.context() ?: throw AgentError("INTERNAL", "agent is not initialized")
        return when (method) {
            "notifications.list" -> NotificationRelay.snapshot()
            "notifications.dismiss" -> JSONObject().put("dismissed", NotificationRelay.dismiss(params.optString("key")))
            "notifications.click" -> notificationClick(params)
            "notifications.reply" -> notificationReply(params)
            "clipboard.get" -> clipboardGet(context)
            "clipboard.set" -> clipboardSet(context, params.optString("text"))
            "clipboard.clear" -> clipboardClear(context)
            "media.nowPlaying" -> nowPlaying(context)
            "media.play", "media.pause", "media.next", "media.prev", "media.stop" -> mediaControl(context, method, params)
            "call.dial" -> {
                if (params.optBoolean("autoCall")) {
                    AgentError.confirm(params)
                    RootClient.call(method, params)
                } else {
                    val number = params.getString("number")
                    if (!number.matches(Regex("[0-9+*#]{1,32}"))) throw AgentError("INVALID_PARAMS", "invalid phone number")
                    try {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: SecurityException) {
                        if (RootClient.available()) return RootClient.call("activity.start", JSONObject().put("action", "android.intent.action.DIAL").put("data", "tel:" + number))
                        throw AgentError("START_REJECTED", "dialer start was rejected")
                    } catch (e: android.content.ActivityNotFoundException) {
                        if (RootClient.available()) return RootClient.call("activity.start", JSONObject().put("action", "android.intent.action.DIAL").put("data", "tel:" + number))
                        throw AgentError("START_REJECTED", "dialer start was rejected")
                    }
                    JSONObject().put("launchRequested", true).put("confirmed", false)
                }
            }
            "sms.send" -> throw AgentError("ROLE_REQUIRED", "sending SMS requires an explicit default SMS role")
            "sms.list", "sms.search", "contacts.search", "calendar.list", "screen.record" -> RootClient.call(method, params)
            else -> null
        }
    }

    private fun clipboardGet(context: Context): JSONObject {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        return try {
            if (manager == null || !manager.hasPrimaryClip() || manager.primaryClip == null) {
                JSONObject().put("text", "").put("hasClip", false).put("available", true)
            } else {
                val clip = manager.primaryClip!!
                val text = StringBuilder()
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).coerceToText(context)?.let { text.append(it) }
                }
                JSONObject().put("text", text.toString()).put("hasClip", true).put("available", true)
            }
        } catch (_: SecurityException) {
            JSONObject().put("hasClip", false).put("available", false).put("reason", "clipboard focus required")
        }
    }

    private fun clipboardSet(context: Context, text: String): JSONObject {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return JSONObject().put("set", false).put("verified", false)
        return try {
            manager.setPrimaryClip(ClipData.newPlainText("DSH", text))
            val clip = manager.primaryClip
            val verified = clip != null && clip.itemCount == 1 && text.contentEquals(clip.getItemAt(0).coerceToText(context))
            JSONObject().put("set", verified).put("verified", verified)
        } catch (_: SecurityException) {
            JSONObject().put("set", false).put("verified", false).put("reason", "clipboard focus required")
        }
    }

    private fun clipboardClear(context: Context): JSONObject {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return JSONObject().put("cleared", false).put("verified", false)
        return try {
            manager.clearPrimaryClip()
            val verified = !manager.hasPrimaryClip() || manager.primaryClip == null
            JSONObject().put("cleared", verified).put("verified", verified)
        } catch (_: SecurityException) {
            JSONObject().put("cleared", false).put("verified", false).put("reason", "clipboard focus required")
        }
    }

    private fun notificationClick(params: JSONObject): JSONObject {
        val notification = NotificationRelay.find(params.getString("key"))
        if (notification == null || notification.notification.contentIntent == null) throw AgentError("NOT_FOUND", "notification has no content intent")
        notification.notification.contentIntent.send()
        return JSONObject().put("requested", true).put("confirmed", false)
    }

    private fun notificationReply(params: JSONObject): JSONObject {
        val notification = NotificationRelay.find(params.getString("key")) ?: throw AgentError("NOT_FOUND", "notification not found")
        val value = params.getString("text")
        if (value.length > 8192) throw AgentError("INVALID_PARAMS", "reply is too long")
        val wanted = params.optInt("actionIndex", -1)
        var action: Notification.Action? = null
        val actions = notification.notification.actions
        if (actions != null) {
            for (i in actions.indices) {
                if (wanted >= 0 && wanted != i) continue
                val inputs = actions[i].remoteInputs
                if (inputs != null && inputs.isNotEmpty()) {
                    action = actions[i]
                    break
                }
            }
        }
        val selectedAction = action ?: throw AgentError("UNAVAILABLE", "notification has no RemoteInput reply action")
        val inputs = selectedAction.remoteInputs ?: throw AgentError("UNAVAILABLE", "notification has no RemoteInput reply action")
        var selected = inputs[0]
        if (params.optString("resultKey").isNotEmpty()) {
            for (input in inputs) if (input.resultKey == params.getString("resultKey")) selected = input
        }
        val results = Bundle()
        results.putCharSequence(selected.resultKey, value)
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        selectedAction.actionIntent.send(AgentServer.context(), 0, intent)
        return JSONObject().put("requested", true).put("confirmed", false)
    }

    private fun controllers(context: Context): List<MediaController> {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: throw AgentError("NOTIFICATION_ACCESS_OFF", "media sessions require notification access")
        return try {
            manager.getActiveSessions(ComponentName(context, NotificationRelay::class.java))
        } catch (_: SecurityException) {
            throw AgentError("NOTIFICATION_ACCESS_OFF", "media sessions require notification access")
        }
    }

    private fun nowPlaying(context: Context): JSONObject {
        val result = JSONArray()
        for (controller in controllers(context)) {
            val playback = controller.playbackState
            val metadata = controller.metadata
            val item = JSONObject().put("package", controller.packageName)
                .put("state", playback?.state ?: PlaybackState.STATE_NONE)
                .put("position", playback?.position ?: 0)
            if (metadata != null) {
                item.put("title", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
                    .put("artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
                    .put("duration", metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
            }
            result.put(item)
        }
        return JSONObject().put("sessions", result).put("available", true)
    }

    private fun mediaControl(context: Context, method: String, params: JSONObject): JSONObject {
        val pkg = params.optString("package")
        for (controller in controllers(context)) {
            if (pkg.isNotEmpty() && pkg != controller.packageName) continue
            val controls = controller.transportControls
            when (method) {
                "media.play" -> controls.play()
                "media.pause" -> controls.pause()
                "media.next" -> controls.skipToNext()
                "media.prev" -> controls.skipToPrevious()
                "media.stop" -> controls.stop()
                else -> throw AgentError("INVALID_PARAMS", "unknown media action")
            }
            return JSONObject().put("requested", true).put("confirmed", false).put("package", controller.packageName)
        }
        if (RootClient.available()) return RootClient.call(method, params)
        throw AgentError("NOT_FOUND", "no active media session")
    }
}
