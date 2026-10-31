package dev.dsh.agent

import android.app.Activity
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal object SystemBridge {
    private val INFO_EXECUTOR: ThreadPoolExecutor = ThreadPoolExecutor(
        0, 2, 30, TimeUnit.SECONDS, LinkedBlockingQueue(8)
    ) { runnable -> Thread(runnable, "dsh-sys-info").apply { isDaemon = true } }

    @Throws(Exception::class)
    private fun timedInfo(context: Context): JSONObject {
        val task: Future<JSONObject> = INFO_EXECUTOR.submit<JSONObject> { info(context) }
        return try {
            task.get(5, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            task.cancel(true)
            throw AgentError("TIMEOUT", "system information timed out")
        } catch (error: ExecutionException) {
            val cause = error.cause
            if (cause is AgentError) throw cause
            throw AgentError("UNAVAILABLE", "system information unavailable")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AgentError("TIMEOUT", "system information interrupted")
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun call(method: String, params: JSONObject): Any? {
        val context = AgentServer.context() ?: throw AgentError("INTERNAL", "agent is not initialized")
        return when (method) {
            "sys.info" -> timedInfo(context)
            "settings.get" -> settingsGet(context, params)
            "package.list" -> packages(context, params)
            "activity.start" -> startActivity(context, params)
            "app.openUrl" -> openUrl(context, params)
            "intent.send" -> {
                val intent = intent(params)
                if (params.optString("package").isNotEmpty()) intent.setPackage(params.getString("package"))
                context.sendBroadcast(intent)
                JSONObject().put("sent", true)
            }
            "activity.forceStop", "package.clear", "package.install" -> {
                if (RootClient.available()) {
                    RootClient.call(if (method == "activity.forceStop") "package.forceStop" else method, params)
                } else {
                    JSONObject().put("supported", false).put("reason", "requires system permission")
                }
            }
            "power.state" -> powerState(context)
            "power.wake" -> powerWake(context)
            "lock.state" -> lockState(context)
            "lock.unlock" -> lockUnlock(context, params)
            "ime.status" -> imeStatus(context)
            "ime.show", "ime.hide" -> imeVisibility(context, method, params)
            "ime.set" -> RootClient.call(method, params)
            "volume.get" -> volumes(context, params)
            "volume.set" -> setVolume(context, params, false)
            "volume.mute" -> setVolume(context, params, true)
            "dnd.set" -> dndSet(context, params)
            "net.status" -> network(context)
            "sensor.read" -> sensor(context, params)
            "location.get" -> location(context)
            "task.list" -> taskList(context)
            else -> null
        }
    }

    @Throws(Exception::class)
    private fun settingsGet(context: Context, params: JSONObject): JSONObject {
        val namespace = namespace(params)
        val name = params.getString("name")
        checkName(name)
        val value = when (namespace) {
            "system" -> Settings.System.getString(context.contentResolver, name)
            "secure" -> Settings.Secure.getString(context.contentResolver, name)
            else -> Settings.Global.getString(context.contentResolver, name)
        }
        return JSONObject().put("namespace", namespace).put("name", name)
            .put("value", value ?: JSONObject.NULL)
    }

    @Throws(Exception::class)
    private fun powerWake(context: Context): JSONObject {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "dsh:agent"
        )
        lock.acquire(1000)
        Handler(Looper.getMainLooper()).postDelayed({
            if (lock.isHeld) lock.release()
        }, 1100)
        return JSONObject().put("wakeRequested", true).put("unlocked", false)
    }

    @Throws(Exception::class)
    private fun lockState(context: Context): JSONObject {
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject().put("locked", keyguard.isKeyguardLocked).put("secure", keyguard.isDeviceSecure)
            .put("interactive", power.isInteractive)
    }

    @Throws(Exception::class)
    private fun lockUnlock(context: Context, params: JSONObject): Any? {
        if (params.has("pin") || RootClient.available()) return RootClient.call("lock.unlock", params)
        val activity = AgentServer.activity()
            ?: throw AgentError("ACTIVITY_REQUIRED", "open Agent to request the system unlock prompt")
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val dismissed = booleanArrayOf(false)
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            keyguard.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    dismissed[0] = true
                    latch.countDown()
                }

                override fun onDismissCancelled() {
                    latch.countDown()
                }

                override fun onDismissError() {
                    latch.countDown()
                }
            })
        }
        if (!latch.await(5, TimeUnit.SECONDS)) throw AgentError("TIMEOUT", "unlock prompt did not complete")
        return JSONObject().put("requested", true).put("confirmed", dismissed[0]).put("dismissed", dismissed[0])
    }

    @Throws(Exception::class)
    private fun imeStatus(context: Context): JSONObject {
        val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return JSONObject().put("defaultInputMethod", Settings.Secure.getString(
            context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
        )).put("active", ime.isActive).put("acceptingText", ime.isAcceptingText).put("scope", "agent")
    }

    @Throws(Exception::class)
    private fun imeVisibility(context: Context, method: String, params: JSONObject): Any? {
        val activity = AgentServer.activity()
        if (activity == null) {
            if (RootClient.available() && method == "ime.hide") return RootClient.call("ime.hide", params)
            throw AgentError("ACTIVITY_REQUIRED", "open the target application before changing IME visibility")
        }
        val focus = activity.currentFocus ?: throw AgentError("NOT_FOUND", "no focused view for IME action")
        val ime = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val changed = booleanArrayOf(false)
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            if (method == "ime.show") {
                focus.requestFocus()
                changed[0] = ime.showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT)
            } else {
                changed[0] = ime.hideSoftInputFromWindow(focus.windowToken, 0)
            }
            latch.countDown()
        }
        if (!latch.await(5, TimeUnit.SECONDS)) throw AgentError("TIMEOUT", "IME action timed out")
        return JSONObject().put("requested", true).put("changed", changed[0]).put("scope", "agent")
    }

    @Throws(Exception::class)
    private fun dndSet(context: Context, params: JSONObject): Any? {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.isNotificationPolicyAccessGranted) {
            if (RootClient.available()) return RootClient.call("dnd.set", params)
            throw AgentError("DND_REQUIRED", "grant do-not-disturb access")
        }
        val mode = params.getString("mode")
        val filter = when (mode) {
            "all" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "none" -> NotificationManager.INTERRUPTION_FILTER_NONE
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> 0
        }
        if (filter == 0) throw AgentError("INVALID_PARAMS", "unknown dnd mode")
        manager.setInterruptionFilter(filter)
        return JSONObject().put("mode", mode).put("applied", true)
    }

    @Throws(Exception::class)
    private fun taskList(context: Context): JSONObject {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = JSONArray()
        @Suppress("DEPRECATION")
        val recent = manager.getRecentTasks(20, 0)
        for (task in recent) {
            tasks.put(JSONObject().put("taskId", task.taskId)
                .put("baseActivity", task.baseActivity?.flattenToShortString() ?: ""))
        }
        return JSONObject().put("tasks", tasks).put("limited", true)
    }

    @Throws(Exception::class)
    private fun info(context: Context): JSONObject {
        val windows = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val display = windows.defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volumes = JSONObject()
        for (name in arrayOf("music", "ring", "notification")) {
            val value = stream(name)
            volumes.put(name, JSONObject().put("value", audio.getStreamVolume(value))
                .put("max", audio.getStreamMaxVolume(value)))
        }
        val battery = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val powerInfo = JSONObject().put("interactive", power.isInteractive).put("idle", power.isDeviceIdleMode)
            .put("powerSave", power.isPowerSaveMode)
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            powerInfo.put("level", level).put("scale", scale)
                .put("status", battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
                .put("plugged", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))
        }
        val storage = JSONObject()
        for (path in arrayOf(context.filesDir, android.os.Environment.getExternalStorageDirectory())) {
            val stat = StatFs(path.path)
            storage.put(path.path, JSONObject().put("totalBytes", stat.totalBytes).put("freeBytes", stat.availableBytes))
        }
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo()
        activity.getMemoryInfo(memory)
        return JSONObject().put("width", metrics.widthPixels).put("height", metrics.heightPixels)
            .put("densityDpi", metrics.densityDpi).put("rotation", display.rotation)
            .put("refreshRate", display.refreshRate).put("interactive", power.isInteractive)
            .put("locked", keyguard.isKeyguardLocked).put("secureLock", keyguard.isDeviceSecure)
            .put("foregroundPackage", AgentServer.events.foreground()).put("power", powerInfo)
            .put("volumes", volumes).put("network", network(context)).put("storage", storage)
            .put("ime", Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
            .put("memory", JSONObject().put("availableBytes", memory.availMem).put("totalBytes", memory.totalMem)
                .put("lowMemory", memory.lowMemory))
    }

    @Throws(Exception::class)
    private fun packages(context: Context, params: JSONObject): JSONArray {
        val result = JSONArray()
        val wanted = params.optString("package")
        val installed = context.packageManager.getInstalledPackages(0)
        for (info: PackageInfo in installed) {
            if (wanted.isNotEmpty() && wanted != info.packageName) continue
            if (result.length() >= 1000) break
            val app = info.applicationInfo ?: continue
            result.put(JSONObject().put("package", info.packageName)
                .put("version", info.versionName ?: "").put("versionCode", info.longVersionCode)
                .put("enabled", app.enabled)
                .put("system", (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0))
        }
        return result
    }

    @Throws(Exception::class)
    private fun startActivity(context: Context, params: JSONObject): Any? {
        if (params.has("user") && params.optString("user") != "0") return RootClient.call("activity.start", params)
        var intent = intent(params)
        if (params.optString("package").isNotEmpty() && params.optString("activity").isNotEmpty()) {
            intent.setClassName(params.getString("package"), params.getString("activity"))
        } else if (params.optString("package").isNotEmpty()) {
            intent.setPackage(params.getString("package"))
            if (intent.action == null) {
                val launch = context.packageManager.getLaunchIntentForPackage(params.getString("package"))
                    ?: throw AgentError("NOT_FOUND", "no launch activity for package")
                intent = launch
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: SecurityException) {
            if (RootClient.available()) return RootClient.call("activity.start", params)
            throw AgentError("START_REJECTED", "activity start was rejected by the system")
        } catch (_: android.content.ActivityNotFoundException) {
            if (RootClient.available()) return RootClient.call("activity.start", params)
            throw AgentError("START_REJECTED", "activity start was rejected by the system")
        }
        return JSONObject().put("launchRequested", true).put("confirmed", false)
    }

    @Throws(Exception::class)
    private fun openUrl(context: Context, params: JSONObject): Any? {
        val uri = Uri.parse(params.getString("url"))
        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme != "http" && scheme != "https" && !params.optBoolean("allowCustom")) {
            throw AgentError("INVALID_PARAMS", "only http/https URLs are allowed by default")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (params.optString("package").isNotEmpty()) intent.setPackage(params.getString("package"))
        try {
            context.startActivity(intent)
        } catch (_: SecurityException) {
            if (RootClient.available()) {
                return RootClient.call("activity.start", JSONObject().put("action", Intent.ACTION_VIEW)
                    .put("data", uri.toString()).put("package", params.optString("package")))
            }
            throw AgentError("START_REJECTED", "URL activity start was rejected")
        } catch (_: android.content.ActivityNotFoundException) {
            if (RootClient.available()) {
                return RootClient.call("activity.start", JSONObject().put("action", Intent.ACTION_VIEW)
                    .put("data", uri.toString()).put("package", params.optString("package")))
            }
            throw AgentError("START_REJECTED", "URL activity start was rejected")
        }
        return JSONObject().put("launchRequested", true).put("confirmed", false)
    }

    @Throws(Exception::class)
    private fun intent(params: JSONObject): Intent {
        val intent = Intent()
        if (params.optString("action").isNotEmpty()) intent.action = params.getString("action")
        if (params.optString("data").isNotEmpty()) intent.data = Uri.parse(params.getString("data"))
        if (params.has("flags")) {
            val raw = params.get("flags")
            if (raw is Number) {
                intent.addFlags(params.getInt("flags"))
            } else {
                val flags = params.getJSONArray("flags")
                for (i in 0 until flags.length()) {
                    when (val flag = flags.getString(i)) {
                        "CLEAR_TOP" -> intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        "SINGLE_TOP" -> intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        "NO_HISTORY" -> intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                        else -> throw AgentError("INVALID_PARAMS", "unsupported intent flag")
                    }
                }
            }
        }
        params.optJSONObject("extras")?.let { extras ->
            val iterator = extras.keys()
            while (iterator.hasNext()) {
                val name = iterator.next()
                when (val value = extras.get(name)) {
                    is Boolean -> intent.putExtra(name, value)
                    is Int -> intent.putExtra(name, value)
                    is Long -> intent.putExtra(name, value)
                    is Double -> intent.putExtra(name, value)
                    is String -> intent.putExtra(name, value)
                    is JSONArray -> {
                        val values = ArrayList<String>()
                        for (i in 0 until value.length()) values.add(value.getString(i))
                        intent.putStringArrayListExtra(name, values)
                    }
                    else -> throw AgentError("INVALID_PARAMS", "unsupported intent extra type")
                }
            }
        }
        params.optJSONArray("categories")?.let { categories ->
            for (i in 0 until categories.length()) intent.addCategory(categories.getString(i))
        }
        return intent
    }

    @Throws(Exception::class)
    private fun powerState(context: Context): JSONObject {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return JSONObject().put("interactive", power.isInteractive).put("idle", power.isDeviceIdleMode)
            .put("powerSave", power.isPowerSaveMode).put("locked", keyguard.isKeyguardLocked)
    }

    @Throws(Exception::class)
    private fun volumes(context: Context, params: JSONObject): JSONObject {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val name = params.optString("stream", "music")
        val stream = stream(name)
        return JSONObject().put("stream", name).put("value", audio.getStreamVolume(stream))
            .put("max", audio.getStreamMaxVolume(stream))
            .put("muted", audio.getStreamVolume(stream) == 0)
    }

    @Throws(Exception::class)
    private fun setVolume(context: Context, params: JSONObject, mute: Boolean): JSONObject {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = stream(params.optString("stream", "music"))
        if (mute) {
            audio.adjustStreamVolume(
                stream,
                if (params.optBoolean("muted", true)) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0
            )
        } else {
            audio.setStreamVolume(
                stream,
                maxOf(0, minOf(audio.getStreamMaxVolume(stream), params.getInt("value"))),
                0
            )
        }
        return JSONObject().put("value", audio.getStreamVolume(stream)).put("applied", true)
    }

    @Throws(AgentError::class)
    private fun stream(name: String): Int = when (name) {
        "music" -> AudioManager.STREAM_MUSIC
        "ring" -> AudioManager.STREAM_RING
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "system" -> AudioManager.STREAM_SYSTEM
        "alarm" -> AudioManager.STREAM_ALARM
        "voice_call" -> AudioManager.STREAM_VOICE_CALL
        else -> throw AgentError("INVALID_PARAMS", "unknown audio stream")
    }

    @Throws(Exception::class)
    private fun network(context: Context): JSONObject {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = connectivity.activeNetwork
        val capabilities = if (active == null) null else connectivity.getNetworkCapabilities(active)
        val result = JSONObject().put("connected", capabilities != null)
            .put("validated", capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            .put("metered", connectivity.isActiveNetworkMetered)
            .put("wifi", capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            .put("cellular", capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
            .put("vpn", capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            .put("airplane", Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        result.put("wifiEnabled", wifi != null && wifi.isWifiEnabled)
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        result.put("dataState", telephony?.dataState ?: "unavailable")
        return result
    }

    @Throws(Exception::class)
    private fun sensor(context: Context, params: JSONObject): JSONObject {
        val name = params.getString("type")
        val type = when (name) {
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "light" -> Sensor.TYPE_LIGHT
            "proximity" -> Sensor.TYPE_PROXIMITY
            "magnetic" -> Sensor.TYPE_MAGNETIC_FIELD
            "steps" -> Sensor.TYPE_STEP_COUNTER
            else -> throw AgentError("INVALID_PARAMS", "unknown sensor type")
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(type) ?: throw AgentError("UNAVAILABLE", "sensor is not present")
        val latch = CountDownLatch(1)
        val event = arrayOfNulls<SensorEvent>(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(value: SensorEvent) {
                event[0] = value
                latch.countDown()
            }

            override fun onAccuracyChanged(value: Sensor?, accuracy: Int) {
            }
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        try {
            if (!latch.await(minOf(10000L, params.optLong("timeoutMs", 3000)), TimeUnit.MILLISECONDS)) {
                throw AgentError("TIMEOUT", "sensor did not produce a value")
            }
            val value = event[0] ?: throw AgentError("UNAVAILABLE", "sensor produced no value")
            val values = JSONArray()
            for (item in value.values) values.put(item)
            return JSONObject().put("type", name).put("sensor", sensor.name).put("values", values)
                .put("accuracy", value.accuracy).put("timestamp", value.timestamp)
        } finally {
            manager.unregisterListener(listener)
        }
    }

    @Throws(Exception::class)
    private fun location(context: Context): JSONObject {
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            throw AgentError("LOCATION_PERMISSION", "grant location access in Android settings")
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var provider = if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else LocationManager.NETWORK_PROVIDER
        if (!manager.isProviderEnabled(provider)) provider = LocationManager.GPS_PROVIDER
        if (!manager.isProviderEnabled(provider)) throw AgentError("UNAVAILABLE", "location providers are disabled")
        val location: Location?
        if (Build.VERSION.SDK_INT >= 30) {
            val latch = CountDownLatch(1)
            val result = arrayOfNulls<Location>(1)
            manager.getCurrentLocation(
                provider, CancellationSignal(),
                { command -> Handler(Looper.getMainLooper()).post(command) },
                { value ->
                    result[0] = value
                    latch.countDown()
                }
            )
            if (!latch.await(8, TimeUnit.SECONDS)) throw AgentError("TIMEOUT", "location request timed out")
            location = result[0]
        } else {
            @Suppress("DEPRECATION")
            location = manager.getLastKnownLocation(provider)
        }
        if (location == null) throw AgentError("NOT_FOUND", "no current location")
        return JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
            .put("accuracy", location.accuracy).put("time", location.time).put("provider", location.provider)
            .put("mock", location.isFromMockProvider)
    }

    @Throws(AgentError::class)
    private fun namespace(params: JSONObject): String {
        val value = params.optString("namespace", "system")
        if (value != "system" && value != "secure" && value != "global") {
            throw AgentError("INVALID_PARAMS", "namespace must be system, secure or global")
        }
        return value
    }

    @Throws(AgentError::class)
    private fun checkName(value: String) {
        if (!value.matches(Regex("[A-Za-z0-9_.-]{1,128}"))) throw AgentError("INVALID_PARAMS", "invalid setting name")
    }
}
