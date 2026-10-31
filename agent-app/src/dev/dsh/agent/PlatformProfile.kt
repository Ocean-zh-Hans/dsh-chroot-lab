package dev.dsh.agent

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android 的平台契约：targetSdk 只改变行为兼容，SDK_INT 决定 API 是否存在，
 * 授权状态又可能是运行时权限、特殊访问或服务连接。所有判断集中在这里，
 * 业务代码不要再散落 checkSelfPermission / Build.VERSION。
 */
internal object PlatformProfile {
    const val LEGACY = "legacy"
    const val MODERN = "modern"
    const val SAF_ONLY = "saf_only"

    data class Capability(
        val id: String,
        val available: Boolean,
        val state: String,
        val reason: String = "",
        val fix: String = "",
    ) {
        fun json(): JSONObject = JSONObject()
            .put("id", id)
            .put("available", available)
            .put("state", state)
            .put("reason", reason)
            .put("fix", fix)
    }

    fun sdk(): Int = Build.VERSION.SDK_INT

    fun targetSdk(context: Context): Int = context.applicationInfo.targetSdkVersion

    fun storageMode(context: Context): String {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageLegacy()) return MODERN
        return LEGACY
    }

    fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun notificationsEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        return manager?.areNotificationsEnabled() ?: false
    }

    fun accessibilityEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return AgentServer.accessibility() != null
        val services = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName } ||
            AgentServer.accessibility() != null
    }

    fun notificationListenerEnabled(): Boolean = NotificationRelay.connected()

    fun clipboardAvailable(): Capability {
        // Android 10+ 对后台读取剪贴板有限制；这里只能声明“可能受限”，
        // 真正读取失败时 DeviceBridge 会返回 CLIPBOARD_RESTRICTED。
        return Capability(
            id = "clipboard",
            available = true,
            state = "limited",
            reason = "Android 10+ 后台读取剪贴板可能被系统限制",
            fix = "foreground_or_ime",
        )
    }

    fun capabilities(context: Context): List<Capability> {
        val a11y = accessibilityEnabled(context)
        val root = RootClient.available()
        return listOf(
            Capability("ui.read", a11y, if (a11y) "available" else "denied",
                if (a11y) "" else "无障碍服务未启用", "accessibility"),
            Capability("ui.act", a11y, if (a11y) "available" else "denied",
                if (a11y) "" else "无障碍服务未启用", "accessibility"),
            Capability("ui.shot", a11y && (Build.VERSION.SDK_INT >= 30 || root),
                if (a11y && (Build.VERSION.SDK_INT >= 30 || root)) "available" else "denied",
                if (a11y) "需要 Android 11+ 或 root helper" else "无障碍服务未启用", "accessibility"),
            Capability("notify.read", notificationListenerEnabled(),
                if (notificationListenerEnabled()) "available" else "denied",
                if (notificationListenerEnabled()) "" else "通知使用权未授权", "notification_listener"),
            Capability("notify.reply", notificationListenerEnabled(),
                if (notificationListenerEnabled()) "available" else "denied",
                if (notificationListenerEnabled()) "" else "通知使用权未授权", "notification_listener"),
            clipboardAvailable(),
            Capability("location", hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION),
                if (hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                    hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)) "available" else "denied",
                "位置权限未授权", "location"),
            Capability("root", root, if (root) "available" else "denied",
                if (root) "" else "root helper 未连接", "root"),
        )
    }

    fun status(context: Context): JSONObject {
        val caps = JSONArray()
        for (cap in capabilities(context)) caps.put(cap.json())
        return JSONObject()
            .put("sdk", sdk())
            .put("targetSdk", targetSdk(context))
            .put("storageMode", storageMode(context))
            .put("notifications", notificationsEnabled(context))
            .put("accessibility", accessibilityEnabled(context))
            .put("notificationListener", notificationListenerEnabled())
            .put("capabilities", caps)
    }

    fun summary(context: Context): String {
        return "SDK ${sdk()} / target ${targetSdk(context)} / 文件面 DSH shell" +
            " / 通知 " + (if (notificationsEnabled(context)) "允许" else "关闭")
    }

    fun fixIntent(context: Context, capability: String): android.content.Intent? = when (capability) {
        "accessibility" -> android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        "notification_listener" -> android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        "location" -> android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}"))
        "root" -> null
        else -> null
    }
}
