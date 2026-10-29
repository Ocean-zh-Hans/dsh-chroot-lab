package dev.dsh.agent

import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import org.json.JSONObject

internal object SystemEvents {
    private var context: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var clipboard: ClipboardManager.OnPrimaryClipChangedListener? = null

    @Synchronized
    @JvmStatic
    fun init(ctx: Context) {
        if (receiver != null) return
        val appContext = ctx.applicationContext
        context = appContext
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        val created = object : BroadcastReceiver() {
            override fun onReceive(ignored: Context?, intent: Intent?) {
                try {
                    val action = intent?.action ?: ""
                    val data = JSONObject()
                    when (action) {
                        Intent.ACTION_SCREEN_ON -> AgentServer.events.publish("screen.on", data)
                        Intent.ACTION_SCREEN_OFF -> AgentServer.events.publish("screen.off", data)
                        Intent.ACTION_USER_PRESENT -> AgentServer.events.publish("screen.unlock", data)
                        Intent.ACTION_BATTERY_CHANGED -> {
                            data.put("level", intent!!.getIntExtra(BatteryManager.EXTRA_LEVEL, -1))
                                .put("scale", intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
                                .put("status", intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
                                .put("plugged", intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
                            AgentServer.events.publish("battery", data)
                        }
                        Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED ->
                            AgentServer.events.publish("battery", data.put("plugged", if (action == Intent.ACTION_POWER_CONNECTED) 1 else 0))
                        Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                            AgentServer.events.publish("network", data.put("airplane", intent!!.getBooleanExtra("state", false)))
                        ConnectivityManager.CONNECTIVITY_ACTION -> {
                            @Suppress("DEPRECATION")
                            val info = intent?.getParcelableExtra<android.net.NetworkInfo>(ConnectivityManager.EXTRA_NETWORK_INFO)
                            AgentServer.events.publish("network", data.put("connected", info != null && info.isConnected))
                        }
                    }
                } catch (_: Exception) {
                    Diagnostics.record("system.event", "app", "EVENT_FAILED", 0)
                }
            }
        }
        receiver = created
        if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(created, filter, Context.RECEIVER_NOT_EXPORTED)
        else appContext.registerReceiver(created, filter)
        val manager = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager != null) {
            val listener = ClipboardManager.OnPrimaryClipChangedListener {
                AgentServer.events.publish("clipboard.changed", JSONObject())
            }
            clipboard = listener
            manager.addPrimaryClipChangedListener(listener)
        }
    }

    @Synchronized
    @JvmStatic
    fun close() {
        val current = context
        val oldReceiver = receiver
        if (oldReceiver != null && current != null) current.unregisterReceiver(oldReceiver)
        receiver = null
        val oldClipboard = clipboard
        if (oldClipboard != null && current != null) {
            val manager = current.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            manager?.removePrimaryClipChangedListener(oldClipboard)
        }
        clipboard = null
        context = null
    }
}
