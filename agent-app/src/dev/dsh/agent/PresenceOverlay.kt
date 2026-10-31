package dev.dsh.agent

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * “在场胶囊”：画在状态栏下方的无障碍浮层。
 *
 * 它不是悬浮窗权限，不接收触摸，不进入无障碍树，也不创建 App 面板。
 * 目的只有一个：人抬眼就能知道 DSH 是在用手机、待命、还是刚完成。
 * 系统通知仍保留，作为胶囊被系统禁止/关闭时的兜底。
 */
internal object PresenceOverlay {
    private var host: AccessibilityService? = null
    private var view: PillView? = null
    private var params: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(service: AccessibilityService) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { attach(service) }
            return
        }
        if (view != null) return
        val context = service
        val pill = PillView(context)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(context, 6)
            x = -dp(context, 30)
        }
        try {
            wm.addView(pill, lp)
            host = service
            view = pill
            params = lp
            update()
        } catch (_: Throwable) {
            view = null
            params = null
            host = null
        }
    }

    fun detach() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { detach() }
            return
        }
        val wm = host?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val v = view
        val lp = params
        view = null
        params = null
        host = null
        try {
            if (wm != null && v != null && lp != null) wm.removeViewImmediate(v)
        } catch (_: Throwable) {
        }
    }

    fun update() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { update() }
            return
        }
        val pill = view ?: return
        val ctx = pill.context
        if (!AgentRuntimeService.enabled(ctx)) {
            pill.visibility = View.GONE
            return
        }
        val phase = PresenceState.phase()
        if (!phase.visible || PresenceState.isStopped()) {
            pill.visibility = View.GONE
            return
        }
        val title = when (phase) {
            PresencePhase.WORKING -> "AI 操作中"
            PresencePhase.WAITING_HUMAN -> "待人工接管"
            PresencePhase.PAUSED -> "暂停中"
            PresencePhase.FINISHED -> "已结束"
            PresencePhase.DISCONNECTED -> "已断开"
            PresencePhase.IDLE -> ""
        }
        val color = when (phase) {
            PresencePhase.WORKING -> Color.rgb(76, 157, 255)
            PresencePhase.WAITING_HUMAN, PresencePhase.PAUSED -> Color.rgb(255, 180, 95)
            PresencePhase.FINISHED -> if (PresenceState.doneOk()) Color.rgb(78, 214, 168) else Color.rgb(255, 122, 122)
            PresencePhase.DISCONNECTED -> Color.rgb(255, 104, 104)
            PresencePhase.IDLE -> Color.rgb(122, 135, 148)
        }
        pill.render(title, color, phase == PresencePhase.WORKING || phase == PresencePhase.WAITING_HUMAN)
    }


    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private class PillView(context: Context) : LinearLayout(context) {
        private val dot = View(context)
        private val label = TextView(context)
        private var pulse: ValueAnimator? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(6), dp(13), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(50).toFloat()
                setColor(Color.argb(220, 14, 20, 28))
                setStroke(dp(1), Color.argb(46, 255, 255, 255))
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            isFocusable = false
            isClickable = false
            dot.layoutParams = LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) }
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(76, 157, 255))
            }
            addView(dot)
            label.setTextColor(Color.WHITE)
            label.textSize = 11.5f
            label.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            label.includeFontPadding = false
            label.isSingleLine = true
            label.maxWidth = dp(120)
            label.ellipsize = TextUtils.TruncateAt.END
            addView(label)
        }

        fun render(text: String, color: Int, active: Boolean) {
            visibility = View.VISIBLE
            label.text = text
            (dot.background as? GradientDrawable)?.setColor(color)
            dot.alpha = 1f
            if (active && pulse == null) {
                pulse = ValueAnimator.ofFloat(0.38f, 1f).apply {
                    duration = 900L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { dot.alpha = it.animatedValue as Float }
                    start()
                }
            } else if (!active) {
                pulse?.cancel()
                pulse = null
            }
        }

        private fun dp(value: Int): Int =
            (value * resources.displayMetrics.density + 0.5f).toInt()
    }
}
