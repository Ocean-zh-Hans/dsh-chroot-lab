package dev.dsh.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.util.Locale

/**
 * 人类接口只使用系统通知：常驻状态、暂停/继续/停止、交接。
 * 不创建任何 App 内面板，也不依赖悬浮窗。
 */
internal object PresenceNotification {
    const val CHANNEL = "agent_presence"
    const val HANDOFF_CHANNEL = "agent_handoff"
    const val RECEIPT_CHANNEL = "agent_receipts"
    const val ID = 17321
    const val HANDOFF_ID = 17322
    const val RECEIPT_ID = 17323

    private const val ACTION_PAUSE = "dev.dsh.agent.PRESENCE_PAUSE"
    private const val ACTION_RESUME = "dev.dsh.agent.PRESENCE_RESUME"
    private const val ACTION_STOP = "dev.dsh.agent.PRESENCE_STOP"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val presence = NotificationChannel(CHANNEL, "DSH 运行状态", NotificationManager.IMPORTANCE_LOW)
        presence.description = "显示 DSH 是否正在操作手机"
        presence.setShowBadge(false)
        manager.createNotificationChannel(presence)

        val handoff = NotificationChannel(HANDOFF_CHANNEL, "DSH 需要你接手", NotificationManager.IMPORTANCE_HIGH)
        handoff.description = "DSH 遇到需要人处理的步骤时提醒"
        handoff.setShowBadge(true)
        manager.createNotificationChannel(handoff)

        val receipt = NotificationChannel(RECEIPT_CHANNEL, "DSH 任务回执", NotificationManager.IMPORTANCE_LOW)
        receipt.description = "任务完成或失败后的简短回执"
        receipt.setShowBadge(false)
        manager.createNotificationChannel(receipt)
    }

    fun build(context: Context, reason: String = ""): Notification {
        ensureChannels(context)
        val phase = PresenceState.phase()
        val paused = PresenceState.isPaused()
        val done = PresenceState.doneTitle()
        val pkg = AgentServer.events.foreground()
        val title = when (phase) {
            PresencePhase.WORKING -> "AI 操作中"
            PresencePhase.WAITING_HUMAN -> "待人工接管"
            PresencePhase.PAUSED -> "暂停中"
            PresencePhase.FINISHED -> "已结束"
            PresencePhase.DISCONNECTED -> "已断开"
            PresencePhase.IDLE -> "DSH 就绪"
        }
        val text = when (phase) {
            PresencePhase.WORKING -> pkg.ifBlank { "正在操作手机" } + " · " + duration(PresenceState.startedAt()) + " · 点按查看"
            PresencePhase.WAITING_HUMAN -> reason.ifBlank { "需要你处理一下，点按查看" }
            PresencePhase.PAUSED -> reason.ifBlank { "点按继续，或打开 DSH Agent" }
            PresencePhase.FINISHED -> done + " · 点按查看回执"
            PresencePhase.DISCONNECTED -> "DSH 连接已断开，点按查看"
            PresencePhase.IDLE -> "本机控制面已启用"
        }
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(activityIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        val toggle = if (paused) {
            action(context, ACTION_RESUME, "继续", android.R.drawable.ic_media_play)
        } else {
            action(context, ACTION_PAUSE, "暂停", android.R.drawable.ic_media_pause)
        }
        builder.addAction(toggle)
        builder.addAction(action(context, ACTION_STOP, "停止", android.R.drawable.ic_menu_close_clear_cancel))
        if (Build.VERSION.SDK_INT >= 36) {
            // Android 16 的 promoted ongoing notification；ColorOS 16 会把它接进流体云/实时通知。
            builder.setShortCriticalText(
                when (phase) {
                    PresencePhase.WORKING -> "AI 操作中"
                    PresencePhase.WAITING_HUMAN -> "待人工接管"
                    PresencePhase.PAUSED -> "暂停中"
                    PresencePhase.FINISHED -> "已结束"
                    PresencePhase.DISCONNECTED -> "已断开"
                    PresencePhase.IDLE -> "DSH 就绪"
                }
            )
            builder.setRequestPromotedOngoing(true)
            val progress = Notification.ProgressStyle()
                .setProgressIndeterminate(true)
                .setProgressTrackerIcon(Icon.createWithResource(context, android.R.drawable.ic_dialog_info))
            builder.setStyle(progress)
        }
        return builder.build()
    }

    fun update(context: Context?, reason: String = "") {
        val ctx = context?.applicationContext ?: return
        if (!AgentRuntimeService.enabled(ctx)) return
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(ID, build(ctx, reason))
    }

    fun cancel(context: Context?) {
        val ctx = context?.applicationContext ?: return
        val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(ID)
        manager.cancel(HANDOFF_ID)
    }

    fun showCompletion(context: Context, title: String, status: String) {
        ensureChannels(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val ok = status == "ok" || status == "done" || status == "success"
        val text = if (ok) "已完成" else "未完成：" + status
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, RECEIPT_CHANNEL)
            .setContentTitle(title.ifBlank { "DSH 任务" })
            .setContentText(text)
            .setSmallIcon(if (ok) android.R.drawable.checkbox_on_background else android.R.drawable.stat_notify_error)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(RECEIPT_ID, notification)
    }

    fun showHandoff(context: Context?, reason: String) {
        val ctx = context?.applicationContext ?: return
        ensureChannels(ctx)
        val text = reason.ifBlank { "请打开处理，完成后回到 DSH 继续" }
        val notification = Notification.Builder(ctx, HANDOFF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("DSH 需要你接手")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(handoffIntent(ctx, text))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(HANDOFF_ID, notification)
    }

    private fun action(context: Context, action: String, title: String, icon: Int): Notification.Action {
        val intent = Intent(context, PresenceReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(icon, title, pending).build()
    }

    private fun activityIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 10, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun handoffIntent(context: Context, reason: String): PendingIntent {
        val intent = HandoffActivity.intent(context, reason)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 11, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun duration(startedAt: Long): String {
        if (startedAt == 0L) return "00:00"
        val seconds = ((SystemClock.elapsedRealtime() - startedAt) / 1000).coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }
}
