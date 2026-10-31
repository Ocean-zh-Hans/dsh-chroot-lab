package dev.dsh.agent

import android.content.Context
import android.os.SystemClock

/**
 * APK 侧最小的“存在状态”：只记录 agent 是否暂停、何时开始操作、最后动作。
 * 真正的任务/租约/交接状态在 DSH 的 DeviceSession 里；这里不复制那套状态机。
 */
internal object PresenceState {
    @Volatile private var paused = false
    @Volatile private var stopped = false
    @Volatile private var startedAt = 0L
    @Volatile private var sessionActive = false
    @Volatile private var handoff = false
    @Volatile private var disconnected = false
    @Volatile private var lastMethod = ""
    @Volatile private var lastActionAt = 0L
    @Volatile private var doneTitle = ""
    @Volatile private var doneOk = false
    @Volatile private var doneAt = 0L

    fun isPaused(): Boolean = paused
    fun isStopped(): Boolean = stopped
    fun startedAt(): Long = startedAt
    fun isSessionActive(): Boolean = sessionActive
    fun isHandoff(): Boolean = handoff
    fun lastMethod(): String = lastMethod

    fun phase(): PresencePhase {
        if (stopped) return PresencePhase.IDLE
        if (disconnected && doneTitle().isNotEmpty()) return PresencePhase.DISCONNECTED
        if (paused) return if (handoff) PresencePhase.WAITING_HUMAN else PresencePhase.PAUSED
        if (sessionActive) return PresencePhase.WORKING
        if (doneTitle().isNotEmpty()) return PresencePhase.FINISHED
        return PresencePhase.IDLE
    }
    fun lastActionAt(): Long = lastActionAt
    fun doneTitle(): String =
        if (doneAt > 0L && SystemClock.elapsedRealtime() - doneAt < DONE_HOLD_MS) doneTitle else ""

    fun doneOk(): Boolean = doneOk

    fun noteRequest(method: String) {
        // 一次任务内的请求会一直保持在场；只有 phone.done 或 stop 才结束。
        sessionActive = true
        handoff = false
        disconnected = false
        lastMethod = method
        lastActionAt = SystemClock.elapsedRealtime()
        doneTitle = ""
        doneOk = false
        doneAt = 0L
        if (startedAt == 0L && !stopped) startedAt = SystemClock.elapsedRealtime()
        PresenceOverlay.update()
    }

    fun markDone(title: String, status: String) {
        sessionActive = false
        handoff = false
        disconnected = false
        doneTitle = title.ifBlank { "任务" }
        doneOk = status == "ok" || status == "done" || status == "success"
        doneAt = SystemClock.elapsedRealtime()
        lastActionAt = doneAt
        paused = false
        PresenceOverlay.update()
    }

    fun noteForeground(pkg: String?) {
        if (!pkg.isNullOrEmpty() && !paused) {
            if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        }
    }

    fun pause(context: Context?, reason: String = "", handoff: Boolean = false) {
        if (stopped) return
        paused = true
        this.handoff = handoff
        PresenceNotification.update(context, reason)
        PresenceOverlay.update()
    }

    fun resume(context: Context?) {
        if (stopped) return
        paused = false
        handoff = false
        disconnected = false
        if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
        PresenceNotification.update(context)
        PresenceOverlay.update()
    }

    fun toggle(context: Context?) {
        if (paused) resume(context) else pause(context)
    }

    fun stop(context: Context?) {
        paused = true
        stopped = true
        sessionActive = false
        handoff = false
        disconnected = false
        startedAt = 0L
        PresenceNotification.cancel(context)
        PresenceOverlay.update()
        AgentRuntimeService.setEnabled(context ?: return, false)
        // 停止是“手机关掉这只手”，不能只停前台通知而继续在 17321 接受请求。
        try {
            AgentServer.stop()
        } catch (_: Throwable) {
            // Activity 销毁路径上也允许继续完成界面清理。
        }
    }

    /** 人打开 App 时调用；只有 agent 真的在跑过才暂停，避免首次设置就把自己锁死。 */
    fun pauseForHuman(context: Context?) {
        if (stopped) return
        if (startedAt == 0L && AgentServer.events.foreground().isEmpty()) return
        pause(context, "人已接管")
    }

    fun requestHandoff(context: Context?, reason: String) {
        if (stopped) return
        paused = true
        handoff = true
        PresenceNotification.showHandoff(context, reason)
        PresenceNotification.update(context, reason)
        PresenceOverlay.update()
    }

    fun markDisconnected() {
        if (stopped) return
        sessionActive = false
        paused = false
        handoff = false
        disconnected = true
        doneTitle = "连接断开"
        doneOk = false
        doneAt = SystemClock.elapsedRealtime()
        lastActionAt = doneAt
        PresenceOverlay.update()
    }

    private const val DONE_HOLD_MS = 8_000L

    fun reset(context: Context?) {
        paused = false
        stopped = false
        sessionActive = false
        handoff = false
        disconnected = false
        startedAt = 0L
        lastMethod = ""
        lastActionAt = 0L
        doneTitle = ""
        doneOk = false
        doneAt = 0L
        PresenceNotification.update(context)
        PresenceOverlay.update()
    }
}
