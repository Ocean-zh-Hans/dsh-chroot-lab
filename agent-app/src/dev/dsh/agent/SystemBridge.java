package dev.dsh.agent;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class SystemBridge {
    private static final java.util.concurrent.ExecutorService INFO_EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
            0, 2, 30, TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(8), runnable -> {
        Thread thread = new Thread(runnable, "dsh-sys-info");
        thread.setDaemon(true);
        return thread;
    });

    private SystemBridge() {
    }

    private static JSONObject timedInfo(Context context) throws Exception {
        java.util.concurrent.Future<JSONObject> task = INFO_EXECUTOR.submit(() -> info(context));
        try {
            return task.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            task.cancel(true);
            throw new AgentError("TIMEOUT", "system information timed out");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof AgentError) throw (AgentError) e.getCause();
            throw new AgentError("UNAVAILABLE", "system information unavailable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentError("TIMEOUT", "system information interrupted");
        }
    }

    static Object call(String method, JSONObject params) throws Exception {
        Context context = AgentServer.context();
        switch (method) {
            case "sys.info": return timedInfo(context);
            case "settings.get": {
                String namespace = namespace(params);
                String name = params.getString("name");
                checkName(name);
                String value = namespace.equals("system") ? Settings.System.getString(context.getContentResolver(), name)
                        : namespace.equals("secure") ? Settings.Secure.getString(context.getContentResolver(), name)
                        : Settings.Global.getString(context.getContentResolver(), name);
                return new JSONObject().put("namespace", namespace).put("name", name)
                        .put("value", value == null ? JSONObject.NULL : value);
            }
            case "package.list": return packages(context, params);
            case "activity.start": return startActivity(context, params);
            case "app.openUrl": return openUrl(context, params);
            case "intent.send": {
                Intent intent = intent(params);
                if (!params.optString("package").isEmpty()) intent.setPackage(params.getString("package"));
                context.sendBroadcast(intent);
                return new JSONObject().put("sent", true);
            }
            case "activity.forceStop": case "package.clear": case "package.install":
                if (RootClient.available()) return RootClient.call(method.equals("activity.forceStop") ? "package.forceStop" : method, params);
                return new JSONObject().put("supported", false).put("reason", "requires system permission");
            case "power.state": return powerState(context);
            case "power.wake": {
                PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock lock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP, "dsh:agent");
                lock.acquire(1000);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (lock.isHeld()) lock.release();
                }, 1100);
                return new JSONObject().put("wakeRequested", true).put("unlocked", false);
            }
            case "lock.state": {
                KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                return new JSONObject().put("locked", keyguard.isKeyguardLocked()).put("secure", keyguard.isDeviceSecure())
                        .put("interactive", power.isInteractive());
            }
            case "lock.unlock": {
                if (params.has("pin") || RootClient.available()) return RootClient.call(method, params);
                Activity activity = AgentServer.activity();
                if (activity == null) throw new AgentError("ACTIVITY_REQUIRED", "open Agent to request the system unlock prompt");
                KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                boolean[] dismissed = {false};
                CountDownLatch latch = new CountDownLatch(1);
                activity.runOnUiThread(() -> keyguard.requestDismissKeyguard(activity, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissSucceeded() {
                        dismissed[0] = true;
                        latch.countDown();
                    }
                    @Override public void onDismissCancelled() { latch.countDown(); }
                    @Override public void onDismissError() { latch.countDown(); }
                }));
                if (!latch.await(5, TimeUnit.SECONDS)) throw new AgentError("TIMEOUT", "unlock prompt did not complete");
                return new JSONObject().put("requested", true).put("confirmed", dismissed[0]).put("dismissed", dismissed[0]);
            }
            case "ime.status": {
                InputMethodManager ime = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                return new JSONObject().put("defaultInputMethod", Settings.Secure.getString(context.getContentResolver(),
                        Settings.Secure.DEFAULT_INPUT_METHOD)).put("active", ime.isActive())
                        .put("acceptingText", ime.isAcceptingText()).put("scope", "agent");
            }
            case "ime.show": case "ime.hide": {
                Activity activity = AgentServer.activity();
                if (activity == null) {
                    if (RootClient.available() && method.equals("ime.hide")) return RootClient.call(method, params);
                    throw new AgentError("ACTIVITY_REQUIRED", "open the target application before changing IME visibility");
                }
                android.view.View focus = activity.getCurrentFocus();
                if (focus == null) throw new AgentError("NOT_FOUND", "no focused view for IME action");
                InputMethodManager ime = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
                boolean[] changed = {false};
                CountDownLatch latch = new CountDownLatch(1);
                activity.runOnUiThread(() -> {
                    if (method.equals("ime.show")) {
                        focus.requestFocus();
                        changed[0] = ime.showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT);
                    } else {
                        changed[0] = ime.hideSoftInputFromWindow(focus.getWindowToken(), 0);
                    }
                    latch.countDown();
                });
                if (!latch.await(5, TimeUnit.SECONDS)) throw new AgentError("TIMEOUT", "IME action timed out");
                return new JSONObject().put("requested", true).put("changed", changed[0]).put("scope", "agent");
            }
            case "ime.set": return RootClient.call(method, params);
            case "volume.get": return volumes(context, params);
            case "volume.set": return setVolume(context, params, false);
            case "volume.mute": return setVolume(context, params, true);
            case "dnd.set": {
                NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (!manager.isNotificationPolicyAccessGranted()) {
                    if (RootClient.available()) return RootClient.call(method, params);
                    throw new AgentError("DND_REQUIRED", "grant do-not-disturb access");
                }
                String mode = params.getString("mode");
                int filter = mode.equals("all") ? NotificationManager.INTERRUPTION_FILTER_ALL
                        : mode.equals("priority") ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        : mode.equals("none") ? NotificationManager.INTERRUPTION_FILTER_NONE
                        : mode.equals("alarms") ? NotificationManager.INTERRUPTION_FILTER_ALARMS : 0;
                if (filter == 0) throw new AgentError("INVALID_PARAMS", "unknown dnd mode");
                manager.setInterruptionFilter(filter);
                return new JSONObject().put("mode", mode).put("applied", true);
            }
            case "net.status": return network(context);
            case "sensor.read": return sensor(context, params);
            case "location.get": return location(context);
            case "task.list": {
                ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                JSONArray tasks = new JSONArray();
                for (ActivityManager.RecentTaskInfo task : manager.getRecentTasks(20, 0)) {
                    tasks.put(new JSONObject().put("taskId", task.taskId)
                            .put("baseActivity", task.baseActivity == null ? "" : task.baseActivity.flattenToShortString()));
                }
                return new JSONObject().put("tasks", tasks).put("limited", true);
            }
            default: return null;
        }
    }

    private static JSONObject info(Context context) throws Exception {
        WindowManager windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = windows.getDefaultDisplay();
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        JSONObject volumes = new JSONObject();
        for (String stream : new String[]{"music", "ring", "notification"}) {
            int value = stream(stream);
            volumes.put(stream, new JSONObject().put("value", audio.getStreamVolume(value)).put("max", audio.getStreamMaxVolume(value)));
        }
        Intent battery = context.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSONObject powerInfo = new JSONObject().put("interactive", power.isInteractive()).put("idle", power.isDeviceIdleMode())
                .put("powerSave", power.isPowerSaveMode());
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            powerInfo.put("level", level).put("scale", scale).put("status", battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1))
                    .put("plugged", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0));
        }
        JSONObject storage = new JSONObject();
        for (File path : new File[]{context.getFilesDir(), android.os.Environment.getExternalStorageDirectory()}) {
            StatFs stat = new StatFs(path.getPath());
            storage.put(path.getPath(), new JSONObject().put("totalBytes", stat.getTotalBytes()).put("freeBytes", stat.getAvailableBytes()));
        }
        ActivityManager activity = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        activity.getMemoryInfo(memory);
        return new JSONObject().put("width", metrics.widthPixels).put("height", metrics.heightPixels)
                .put("densityDpi", metrics.densityDpi).put("rotation", display.getRotation())
                .put("refreshRate", display.getRefreshRate()).put("interactive", power.isInteractive())
                .put("locked", keyguard.isKeyguardLocked()).put("secureLock", keyguard.isDeviceSecure())
                .put("foregroundPackage", AgentServer.events.foreground()).put("power", powerInfo)
                .put("volumes", volumes).put("network", network(context)).put("storage", storage)
                .put("ime", Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD))
                .put("memory", new JSONObject().put("availableBytes", memory.availMem).put("totalBytes", memory.totalMem)
                        .put("lowMemory", memory.lowMemory));
    }

    private static JSONArray packages(Context context, JSONObject params) throws Exception {
        JSONArray result = new JSONArray();
        String wanted = params.optString("package");
        List<PackageInfo> packages = context.getPackageManager().getInstalledPackages(0);
        for (PackageInfo info : packages) {
            if (!wanted.isEmpty() && !wanted.equals(info.packageName)) continue;
            if (result.length() >= 1000) break;
            result.put(new JSONObject().put("package", info.packageName).put("version", info.versionName == null ? "" : info.versionName)
                    .put("versionCode", info.getLongVersionCode()).put("enabled", info.applicationInfo.enabled)
                    .put("system", (info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0));
        }
        return result;
    }

    private static JSONObject startActivity(Context context, JSONObject params) throws Exception {
        if (params.has("user") && !params.optString("user").equals("0")) return RootClient.call("activity.start", params);
        Intent intent = intent(params);
        if (!params.optString("package").isEmpty() && !params.optString("activity").isEmpty()) {
            intent.setClassName(params.getString("package"), params.getString("activity"));
        } else if (!params.optString("package").isEmpty()) {
            intent.setPackage(params.getString("package"));
            if (intent.getAction() == null) {
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(params.getString("package"));
                if (launch == null) throw new AgentError("NOT_FOUND", "no launch activity for package");
                intent = launch;
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (SecurityException | android.content.ActivityNotFoundException e) {
            if (RootClient.available()) return RootClient.call("activity.start", params);
            throw new AgentError("START_REJECTED", "activity start was rejected by the system");
        }
        return new JSONObject().put("launchRequested", true).put("confirmed", false);
    }

    private static JSONObject openUrl(Context context, JSONObject params) throws Exception {
        Uri uri = Uri.parse(params.getString("url"));
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https") && !params.optBoolean("allowCustom")) {
            throw new AgentError("INVALID_PARAMS", "only http/https URLs are allowed by default");
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!params.optString("package").isEmpty()) intent.setPackage(params.getString("package"));
        try {
            context.startActivity(intent);
        } catch (SecurityException | android.content.ActivityNotFoundException e) {
            if (RootClient.available()) return RootClient.call("activity.start", new JSONObject()
                    .put("action", Intent.ACTION_VIEW).put("data", uri.toString()).put("package", params.optString("package")));
            throw new AgentError("START_REJECTED", "URL activity start was rejected");
        }
        return new JSONObject().put("launchRequested", true).put("confirmed", false);
    }

    private static Intent intent(JSONObject params) throws Exception {
        Intent intent = new Intent();
        if (!params.optString("action").isEmpty()) intent.setAction(params.getString("action"));
        if (!params.optString("data").isEmpty()) intent.setData(Uri.parse(params.getString("data")));
        if (params.has("flags")) {
            if (params.get("flags") instanceof Number) intent.addFlags(params.getInt("flags"));
            else {
                JSONArray flags = params.getJSONArray("flags");
                for (int i = 0; i < flags.length(); i++) {
                    String flag = flags.getString(i);
                    if (flag.equals("CLEAR_TOP")) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    else if (flag.equals("SINGLE_TOP")) intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    else if (flag.equals("NO_HISTORY")) intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
                    else throw new AgentError("INVALID_PARAMS", "unsupported intent flag");
                }
            }
        }
        JSONObject extras = params.optJSONObject("extras");
        if (extras != null) {
            for (java.util.Iterator<String> it = extras.keys(); it.hasNext();) {
                String name = it.next();
                Object value = extras.get(name);
                if (value instanceof Boolean) intent.putExtra(name, (Boolean) value);
                else if (value instanceof Integer) intent.putExtra(name, (Integer) value);
                else if (value instanceof Long) intent.putExtra(name, (Long) value);
                else if (value instanceof Double) intent.putExtra(name, (Double) value);
                else if (value instanceof String) intent.putExtra(name, (String) value);
                else if (value instanceof JSONArray) {
                    JSONArray array = (JSONArray) value;
                    ArrayList<String> values = new ArrayList<>();
                    for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
                    intent.putStringArrayListExtra(name, values);
                } else throw new AgentError("INVALID_PARAMS", "unsupported intent extra type");
            }
        }
        JSONArray categories = params.optJSONArray("categories");
        if (categories != null) for (int i = 0; i < categories.length(); i++) intent.addCategory(categories.getString(i));
        return intent;
    }

    private static JSONObject powerState(Context context) throws Exception {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return new JSONObject().put("interactive", power.isInteractive()).put("idle", power.isDeviceIdleMode())
                .put("powerSave", power.isPowerSaveMode()).put("locked", keyguard.isKeyguardLocked());
    }

    private static JSONObject volumes(Context context, JSONObject params) throws Exception {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int stream = stream(params.optString("stream", "music"));
        return new JSONObject().put("stream", params.optString("stream", "music"))
                .put("value", audio.getStreamVolume(stream)).put("max", audio.getStreamMaxVolume(stream))
                .put("muted", audio.getStreamVolume(stream) == 0);
    }

    private static JSONObject setVolume(Context context, JSONObject params, boolean mute) throws Exception {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int stream = stream(params.optString("stream", "music"));
        if (mute) audio.adjustStreamVolume(stream, params.optBoolean("muted", true) ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        else audio.setStreamVolume(stream, Math.max(0, Math.min(audio.getStreamMaxVolume(stream), params.getInt("value"))), 0);
        return new JSONObject().put("value", audio.getStreamVolume(stream)).put("applied", true);
    }

    private static int stream(String name) throws AgentError {
        switch (name) {
            case "music": return AudioManager.STREAM_MUSIC;
            case "ring": return AudioManager.STREAM_RING;
            case "notification": return AudioManager.STREAM_NOTIFICATION;
            case "system": return AudioManager.STREAM_SYSTEM;
            case "alarm": return AudioManager.STREAM_ALARM;
            case "voice_call": return AudioManager.STREAM_VOICE_CALL;
            default: throw new AgentError("INVALID_PARAMS", "unknown audio stream");
        }
    }

    private static JSONObject network(Context context) throws Exception {
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : connectivity.getNetworkCapabilities(active);
        JSONObject result = new JSONObject().put("connected", capabilities != null)
                .put("validated", capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                .put("metered", connectivity.isActiveNetworkMetered())
                .put("wifi", capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .put("cellular", capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .put("vpn", capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                .put("airplane", Settings.Global.getInt(context.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 1);
        WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        result.put("wifiEnabled", wifi != null && wifi.isWifiEnabled());
        TelephonyManager telephony = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        result.put("dataState", telephony == null ? "unavailable" : telephony.getDataState());
        return result;
    }

    private static JSONObject sensor(Context context, JSONObject params) throws Exception {
        String name = params.getString("type");
        int type;
        switch (name) {
            case "accelerometer": type = Sensor.TYPE_ACCELEROMETER; break;
            case "gyroscope": type = Sensor.TYPE_GYROSCOPE; break;
            case "light": type = Sensor.TYPE_LIGHT; break;
            case "proximity": type = Sensor.TYPE_PROXIMITY; break;
            case "magnetic": type = Sensor.TYPE_MAGNETIC_FIELD; break;
            case "steps": type = Sensor.TYPE_STEP_COUNTER; break;
            default: throw new AgentError("INVALID_PARAMS", "unknown sensor type");
        }
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor sensor = manager.getDefaultSensor(type);
        if (sensor == null) throw new AgentError("UNAVAILABLE", "sensor is not present");
        CountDownLatch latch = new CountDownLatch(1);
        SensorEvent[] event = new SensorEvent[1];
        SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent value) {
                event[0] = value;
                latch.countDown();
            }
            @Override public void onAccuracyChanged(Sensor value, int accuracy) {
            }
        };
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        try {
            if (!latch.await(Math.min(10000, params.optLong("timeoutMs", 3000)), TimeUnit.MILLISECONDS)) {
                throw new AgentError("TIMEOUT", "sensor did not produce a value");
            }
            JSONArray values = new JSONArray();
            for (float value : event[0].values) values.put(value);
            return new JSONObject().put("type", name).put("sensor", sensor.getName()).put("values", values)
                    .put("accuracy", event[0].accuracy).put("timestamp", event[0].timestamp);
        } finally {
            manager.unregisterListener(listener);
        }
    }

    private static JSONObject location(Context context) throws Exception {
        if (context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            throw new AgentError("LOCATION_PERMISSION", "grant location access in Android settings");
        }
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        String provider = Build.VERSION.SDK_INT >= 31 ? LocationManager.FUSED_PROVIDER : LocationManager.NETWORK_PROVIDER;
        if (!manager.isProviderEnabled(provider)) provider = LocationManager.GPS_PROVIDER;
        if (!manager.isProviderEnabled(provider)) throw new AgentError("UNAVAILABLE", "location providers are disabled");
        Location location;
        if (Build.VERSION.SDK_INT >= 30) {
            CountDownLatch latch = new CountDownLatch(1);
            Location[] result = new Location[1];
            manager.getCurrentLocation(provider, new CancellationSignal(), command -> new Handler(Looper.getMainLooper()).post(command), value -> {
                result[0] = value;
                latch.countDown();
            });
            if (!latch.await(8, TimeUnit.SECONDS)) throw new AgentError("TIMEOUT", "location request timed out");
            location = result[0];
        } else {
            location = manager.getLastKnownLocation(provider);
        }
        if (location == null) throw new AgentError("NOT_FOUND", "no current location");
        return new JSONObject().put("latitude", location.getLatitude()).put("longitude", location.getLongitude())
                .put("accuracy", location.getAccuracy()).put("time", location.getTime()).put("provider", location.getProvider())
                .put("mock", location.isFromMockProvider());
    }

    private static String namespace(JSONObject params) throws AgentError {
        String value = params.optString("namespace", "system");
        if (!value.equals("system") && !value.equals("secure") && !value.equals("global")) {
            throw new AgentError("INVALID_PARAMS", "namespace must be system, secure or global");
        }
        return value;
    }

    private static void checkName(String value) throws AgentError {
        if (!value.matches("[A-Za-z0-9_.-]{1,128}")) throw new AgentError("INVALID_PARAMS", "invalid setting name");
    }
}
