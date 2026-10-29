package dev.dsh.agent;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class RootHelper {
    private static final int PORT = 17322;
    private static final File TOKEN = new File("/data/user/0/dev.dsh.agent/files/token");
    private static final ExecutorService CLIENTS = Executors.newFixedThreadPool(4);
    private static final Ui UI = new Ui();
    private static String token;

    private RootHelper() {
    }

    public static void main(String[] args) throws Exception {
        if (Process.myUid() != 0) throw new IllegalStateException("root helper must run as root");
        token = read(TOKEN).trim();
        if (!token.matches("[0-9a-f]{32}")) throw new IllegalStateException("invalid agent token");
        Looper.prepareMainLooper();
        ServerSocket listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8);
        new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    CLIENTS.execute(new Client(listener.accept()));
                } catch (Exception e) {
                    if (listener.isClosed()) return;
                }
            }
        }, "dsh-root-listener").start();
        new Thread(() -> {
            while (true) {
                SystemClock.sleep(1000);
                UI.releaseIdle();
            }
        }, "dsh-root-idle").start();
        Looper.loop();
    }

    private static final class Client implements Runnable {
        final Socket socket;

        Client(Socket socket) {
            this.socket = socket;
        }

        @Override public void run() {
            try (Socket closeable = socket) {
                socket.setSoTimeout(15000);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String line = line(input);
                JSONObject request = new JSONObject(line);
                Object id = request.opt("id");
                JSONObject response = new JSONObject().put("id", id == null ? 0 : id);
                String expected = read(TOKEN).trim();
                if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), request.optString("token").getBytes(StandardCharsets.UTF_8))) {
                    response.put("ok", false).put("error", error("UNAUTHORIZED", "unauthorized"));
                } else if (request.optString("method").equals("events.subscribe")) {
                    watch(socket, request);
                } else {
                    try {
                        JSONObject result = dispatch(request.optString("method"), request.optJSONObject("params") == null ? new JSONObject() : request.getJSONObject("params"));
                        response.put("ok", true).put("result", result);
                    } catch (Failure e) {
                        response.put("ok", false).put("error", error(e.code, e.getMessage()));
                    } catch (Exception e) {
                        response.put("ok", false).put("error", error("INTERNAL", "root helper internal error"));
                    }
                }
                String text = response.toString();
                if (text.length() > 16 * 1024 * 1024) text = new JSONObject().put("id", id == null ? 0 : id)
                        .put("ok", false).put("error", error("OUTPUT_TOO_LARGE", "response exceeds limit")).toString();
                OutputStreamWriter output = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                output.write(text + "\n");
                output.flush();
            } catch (Exception ignored) {
            }
        }
    }

    private static long eventSequence;

    private static void watch(Socket socket, JSONObject request) throws Exception {
        JSONObject params = request.optJSONObject("params") == null ? new JSONObject() : request.getJSONObject("params");
        JSONArray types = params.optJSONArray("types");
        boolean crash = false;
        if (types != null) for (int i = 0; i < types.length(); i++) if (types.getString(i).equals("crash.anr")) crash = true;
        if (!crash) {
            OutputStreamWriter output = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            output.write(new JSONObject().put("id", request.opt("id")).put("ok", false)
                    .put("error", error("UNAVAILABLE", "root event stream only supports crash.anr")).toString() + "\n");
            output.flush();
            return;
        }
        java.lang.Process process = new ProcessBuilder("logcat", "-b", "crash", "-v", "threadtime").start();
        OutputStreamWriter output = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        JSONObject ack = new JSONObject().put("id", request.opt("id")).put("ok", true)
                .put("result", new JSONObject().put("subscribed", true).put("bootId", UUID.randomUUID().toString())
                        .put("baseSeq", eventSequence).put("resetRequired", params.optLong("since") > eventSequence)
                        .put("replaySupported", false));
        synchronized (socket) {
            output.write(ack.toString() + "\n");
            output.flush();
        }
        String expected = read(TOKEN).trim();
        Thread heartbeat = new Thread(() -> {
            try {
                while (!socket.isClosed()) {
                    Thread.sleep(10000);
                    if (!expected.equals(read(TOKEN).trim())) {
                        socket.close();
                        break;
                    }
                    synchronized (socket) {
                        output.write(new JSONObject().put("event", "events.heartbeat").put("seq", eventSequence)
                                .put("time", System.currentTimeMillis()).toString() + "\n");
                        output.flush();
                    }
                }
            } catch (Exception ignored) {
                try {
                    socket.close();
                } catch (Exception ignoredClose) {
                }
            }
        }, "dsh-crash-heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("FATAL EXCEPTION") && !line.contains("ANR in") && !line.contains("Fatal signal")) continue;
                if (!params.optString("package").isEmpty() && !line.contains(params.getString("package"))) continue;
                long seq;
                synchronized (RootHelper.class) {
                    seq = ++eventSequence;
                }
                JSONObject event = new JSONObject().put("event", "crash.anr").put("seq", seq)
                        .put("time", System.currentTimeMillis())
                        .put("data", new JSONObject().put("line", line.substring(0, Math.min(512, line.length()))));
                synchronized (socket) {
                    output.write(event.toString() + "\n");
                    output.flush();
                }
            }
        } finally {
            process.destroy();
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static JSONObject dispatch(String method, JSONObject params) throws Exception {
        switch (method) {
            case "root.ping": return new JSONObject().put("root", true).put("uid", Process.myUid()).put("via", "shell");
            case "backend.status": return status();
            case "shell.exec":
                confirm(params);
                String command = params.getString("command");
                if (command.length() > 4096) throw new Failure("INVALID_PARAMS", "command is too long");
                return run(new String[]{"/system/bin/sh", "-c", command}, params.optLong("timeoutMs", 30000));
            case "shot": return screenshot(params);
            case "settings.get": {
                String namespace = namespace(params), name = name(params);
                return success(new String[]{"settings", "get", namespace, name}, 5000).put("via", "shell");
            }
            case "settings.put": {
                confirm(params);
                String namespace = namespace(params), name = name(params), value = params.optString("value");
                JSONObject before = run(new String[]{"settings", "get", namespace, name}, 5000);
                JSONObject result = success(new String[]{"settings", "put", namespace, name, value}, 5000);
                JSONObject after = run(new String[]{"settings", "get", namespace, name}, 5000);
                if (!value.equals(after.optString("stdout").trim())) throw new Failure("WRITE_FAILED", "setting readback differs");
                return result.put("before", before.optString("stdout").trim()).put("value", after.optString("stdout").trim());
            }
            case "package.list": return run(new String[]{"pm", "list", "packages", "--show-versioncode"}, 15000);
            case "package.forceStop": case "activity.forceStop": confirm(params); return success(new String[]{"am", "force-stop", pkg(params)}, 10000);
            case "package.clear": confirm(params); return success(new String[]{"pm", "clear", pkg(params)}, 20000);
            case "package.uninstall": confirm(params); return success(new String[]{"pm", "uninstall", pkg(params)}, 30000);
            case "package.enable": confirm(params); return success(new String[]{"pm", "enable", pkg(params)}, 10000);
            case "package.disable": confirm(params); return success(new String[]{"pm", "disable", pkg(params)}, 10000);
            case "package.install": {
                confirm(params);
                File apk = allowed(params.getString("path"), true);
                if (!apk.getName().endsWith(".apk")) throw new Failure("INVALID_PARAMS", "only APK files are accepted");
                return success(new String[]{"pm", "install", "-r", apk.getPath()}, 120000);
            }
            case "perm.grant": case "perm.revoke":
                confirm(params);
                return success(new String[]{"pm", method.endsWith("grant") ? "grant" : "revoke", pkg(params), params.getString("permission")}, 10000);
            case "activity.start": return activity(params);
            case "user.list": return run(new String[]{"pm", "list", "users"}, 5000);
            case "task.list": return run(new String[]{"dumpsys", "activity", "recents"}, 10000);
            case "task.remove": return success(new String[]{"am", "task", "remove", Integer.toString(params.getInt("taskId"))}, 5000);
            case "task.splitScreen": case "task.pip": throw new Failure("UNAVAILABLE", "this ROM has no verified task windowing command");
            case "power.wake": return success(new String[]{"input", "keyevent", "KEYCODE_WAKEUP"}, 5000);
            case "power.sleep": return success(new String[]{"input", "keyevent", "KEYCODE_SLEEP"}, 5000);
            case "power.reboot": confirm(params); return run(new String[]{"reboot"}, 5000);
            case "lock.unlock": return unlock(params);
            case "net.wifi": case "net.data": {
                confirm(params);
                boolean enabled = params.optBoolean("enabled", true);
                return success(new String[]{"svc", method.endsWith("wifi") ? "wifi" : "data", enabled ? "enable" : "disable"}, 15000);
            }
            case "net.airplane": {
                confirm(params);
                boolean enabled = params.optBoolean("enabled", true);
                success(new String[]{"settings", "put", "global", "airplane_mode_on", enabled ? "1" : "0"}, 5000);
                return success(new String[]{"am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE", "--ez", "state", Boolean.toString(enabled)}, 5000);
            }
            case "display.setRotation": {
                confirm(params);
                int rotation = params.getInt("rotation");
                if (rotation < 0 || rotation > 3) throw new Failure("INVALID_PARAMS", "rotation must be 0..3");
                success(new String[]{"settings", "put", "system", "accelerometer_rotation", "0"}, 5000);
                return success(new String[]{"settings", "put", "system", "user_rotation", Integer.toString(rotation)}, 5000);
            }
            case "display.setDensity": {
                confirm(params);
                int density = params.getInt("dpi");
                if (density < 120 || density > 640) throw new Failure("INVALID_PARAMS", "density is outside the allowed range");
                JSONObject before = run(new String[]{"wm", "density"}, 5000);
                JSONObject result = success(new String[]{"wm", "density", Integer.toString(density)}, 5000);
                return result.put("before", before.optString("stdout").trim()).put("readback", run(new String[]{"wm", "density"}, 5000).optString("stdout").trim());
            }
            case "prep.automation": return prep(params, false);
            case "prep.restore": return prep(params, true);
            case "dnd.set": {
                String mode = params.getString("mode");
                if (!mode.matches("all|priority|none|alarms")) throw new Failure("INVALID_PARAMS", "invalid dnd mode");
                String zen = mode.equals("all") ? "0" : mode.equals("priority") ? "1" : mode.equals("none") ? "2" : "3";
                success(new String[]{"settings", "put", "global", "zen_mode", zen}, 5000);
                JSONObject result = run(new String[]{"settings", "get", "global", "zen_mode"}, 5000);
                if (!zen.equals(result.optString("stdout").trim())) throw new Failure("WRITE_FAILED", "dnd readback differs");
                return result.put("mode", mode).put("applied", true);
            }
            case "location.mock": return mockLocation(params);
            case "ime.set": return success(new String[]{"ime", "set", params.getString("id")}, 10000);
            case "ime.hide": return new JSONObject().put("requested", run(new String[]{"input", "keyevent", "4"}, 5000).optInt("exitCode") == 0)
                    .put("confirmed", false).put("via", "shell");
            case "ime.show": throw new Failure("UNAVAILABLE", "no reliable shell-only IME show command");
            case "media.play": case "media.pause": case "media.next": case "media.prev": case "media.stop": {
                String key = method.equals("media.next") ? "87" : method.equals("media.prev") ? "88" : method.equals("media.stop") ? "86" : "85";
                return success(new String[]{"input", "keyevent", key}, 5000).put("requested", true).put("confirmed", false);
            }
            case "screen.record": return record(params);
            case "perf.fps": return success(new String[]{"dumpsys", "gfxinfo", pkg(params), "framestats"}, 10000);
            case "crash.anr": return run(new String[]{"logcat", "-b", "crash", "-d", "-t", "100"}, 10000);
            case "sms.list": case "sms.search": return query("content://sms/inbox", "address,body,date,read", params);
            case "contacts.search": return query("content://com.android.contacts/data/phones", "display_name,data1", params);
            case "calendar.list": {
                long start = params.optLong("start"), end = params.optLong("end");
                if (start < 0 || end <= start) throw new Failure("INVALID_PARAMS", "invalid calendar interval");
                return run(new String[]{"content", "query", "--uri", "content://com.android.calendar/instances/when/" + start + "/" + end,
                        "--projection", "title,dtstart,dtend,eventLocation"}, 15000);
            }
            case "call.dial": {
                confirm(params);
                String number = params.getString("number");
                if (!number.matches("[0-9+*#]{1,32}")) throw new Failure("INVALID_PARAMS", "invalid phone number");
                return success(new String[]{"am", "start", "-a", "android.intent.action.CALL", "-d", "tel:" + number}, 5000);
            }
            case "sys.info": return new JSONObject().put("display", run(new String[]{"wm", "size"}, 5000).optString("stdout").trim())
                    .put("density", run(new String[]{"wm", "density"}, 5000).optString("stdout").trim())
                    .put("foregroundPackage", foregroundPackage()).put("via", "shell");
            default:
                if (method.startsWith("ui.")) {
                    try {
                        JSONObject result = UI.handle(method, params);
                        if (result != null) return result;
                    } catch (Failure e) {
                        if (!e.code.equals("SLOT_BUSY") && !e.code.equals("UNAVAILABLE")) throw e;
                    }
                    return shellUi(method, params);
                }
                throw new Failure("UNKNOWN_METHOD", "unknown method: " + method);
        }
    }

    private static String foregroundPackage() throws Exception {
        String output = run(new String[]{"dumpsys", "activity", "activities"}, 10000).optString("stdout");
        for (String line : output.split("\\n")) {
            if (!line.contains("topResumedActivity") && !line.contains("mResumedActivity")) continue;
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([A-Za-z][A-Za-z0-9_.]+)/").matcher(line);
            if (matcher.find()) return matcher.group(1);
        }
        return "";
    }

    private static JSONObject status() throws Exception {
        boolean ui = UI.available();
        return new JSONObject().put("root", new JSONObject().put("ok", true).put("uid", Process.myUid()))
                .put("layers", new JSONArray()
                        .put(new JSONObject().put("name", "uiautomation").put("ok", ui).put("reason", ui ? JSONObject.NULL : UI.reason))
                        .put(new JSONObject().put("name", "shell").put("ok", true).put("reason", JSONObject.NULL)))
                .put("via", "shell").put("degraded", !ui);
    }

    private static JSONObject activity(JSONObject params) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("am", "start"));
        if (params.has("user")) command.addAll(Arrays.asList("--user", Integer.toString(params.getInt("user"))));
        if (!params.optString("activity").isEmpty()) command.addAll(Arrays.asList("-n", pkg(params) + "/" + params.getString("activity")));
        if (!params.optString("action").isEmpty()) command.addAll(Arrays.asList("-a", params.getString("action")));
        if (!params.optString("data").isEmpty()) command.addAll(Arrays.asList("-d", params.getString("data")));
        if (!params.optString("type").isEmpty()) command.addAll(Arrays.asList("-t", params.getString("type")));
        if (!params.optString("package").isEmpty() && params.optString("activity").isEmpty()) command.addAll(Arrays.asList("-p", pkg(params)));
        if (params.has("flags") && params.get("flags") instanceof Number) command.addAll(Arrays.asList("-f", Integer.toString(params.getInt("flags"))));
        JSONObject extras = params.optJSONObject("extras");
        if (extras != null) for (java.util.Iterator<String> it = extras.keys(); it.hasNext();) {
            String key = it.next();
            Object value = extras.get(key);
            if (value instanceof Boolean) command.addAll(Arrays.asList("--ez", key, value.toString()));
            else if (value instanceof Integer || value instanceof Long) command.addAll(Arrays.asList("--el", key, value.toString()));
            else if (value instanceof Double || value instanceof Float) command.addAll(Arrays.asList("--ef", key, value.toString()));
            else if (value instanceof String) command.addAll(Arrays.asList("--es", key, value.toString()));
            else throw new Failure("INVALID_PARAMS", "unsupported intent extra type");
        }
        return success(command.toArray(new String[0]), 10000).put("launchRequested", true).put("confirmed", false);
    }

    private static JSONObject unlock(JSONObject params) throws Exception {
        confirm(params);
        String pin = params.optString("pin");
        if (!pin.matches("[0-9]{4,16}")) throw new Failure("AUTH_REQUIRED", "secure unlock requires a numeric PIN; pattern/password is not supported");
        success(new String[]{"input", "keyevent", "KEYCODE_WAKEUP"}, 5000);
        success(new String[]{"input", "keyevent", "82"}, 5000);
        success(new String[]{"input", "text", pin}, 5000);
        return success(new String[]{"input", "keyevent", "KEYCODE_ENTER"}, 5000).put("requested", true).put("confirmed", false);
    }

    private static JSONObject mockLocation(JSONObject params) throws Exception {
        confirm(params);
        String provider = params.optString("provider", "gps");
        if (!provider.matches("gps|network")) throw new Failure("INVALID_PARAMS", "provider must be gps or network");
        if (!params.optBoolean("enabled", true)) {
            return success(new String[]{"cmd", "location", "providers", "remove-test-provider", provider}, 5000)
                    .put("requested", true).put("confirmed", false);
        }
        double latitude = params.getDouble("latitude"), longitude = params.getDouble("longitude");
        double accuracy = params.optDouble("accuracy", 1);
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || !Double.isFinite(accuracy)
                || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180 || accuracy <= 0 || accuracy > 100000) {
            throw new Failure("INVALID_PARAMS", "invalid mock location");
        }
        JSONObject added = run(new String[]{"cmd", "location", "providers", "add-test-provider", provider}, 5000);
        if (added.optInt("exitCode") != 0 && !added.optString("stderr").toLowerCase().contains("already")
                && !added.optString("stdout").toLowerCase().contains("already")) {
            throw new Failure("UNAVAILABLE", "cannot add mock location provider");
        }
        success(new String[]{"cmd", "location", "providers", "set-test-provider-enabled", provider, "true"}, 5000);
        JSONObject result = success(new String[]{"cmd", "location", "providers", "set-test-provider-location", provider,
                String.format(java.util.Locale.US, "%.7f", latitude), String.format(java.util.Locale.US, "%.7f", longitude),
                String.format(java.util.Locale.US, "%.2f", accuracy)}, 5000);
        return result.put("requested", true).put("confirmed", false).put("provider", provider);
    }

    private static JSONObject prep(JSONObject params, boolean restore) throws Exception {
        confirm(params);
        String[] namespaces = {"global", "global", "global", "global", "system", "system", "system", "system", "system"};
        String[] names = {"window_animation_scale", "transition_animation_scale", "animator_duration_scale", "stay_on_while_plugged_in",
                "screen_off_timeout", "screen_brightness", "screen_brightness_mode", "accelerometer_rotation", "user_rotation"};
        String[] values = {"0", "0", "0", "7", "600000", "80", "0", "0", "0"};
        File backup = new File("/data/adb/dsh-agent/prep-backup.json");
        JSONObject old = new JSONObject();
        if (restore) {
            old = new JSONObject(read(backup));
            for (String name : names) if (!old.has(name)) throw new Failure("NOT_FOUND", "automation backup is incomplete");
        } else {
            for (String name : names) old.put(name, run(new String[]{"settings", "get", name.startsWith("stay") || name.contains("animation") || name.contains("scale") ? "global" : "system", name}, 5000).optString("stdout").trim());
            backup.getParentFile().mkdirs();
            write(backup, old.toString());
        }
        JSONObject applied = new JSONObject();
        for (int i = 0; i < names.length; i++) {
            String value = restore ? old.getString(names[i]) : values[i];
            if (restore && value.equals("null")) success(new String[]{"settings", "delete", namespaces[i], names[i]}, 5000);
            else success(new String[]{"settings", "put", namespaces[i], names[i], value}, 5000);
            applied.put(names[i], value);
        }
        return new JSONObject().put("restored", restore).put("applied", applied).put("via", "shell");
    }

    private static JSONObject record(JSONObject params) throws Exception {
        confirm(params);
        int seconds = params.optInt("durationMs", 10000) / 1000;
        if (seconds < 1 || seconds > 180) throw new Failure("INVALID_PARAMS", "recording duration must be 1..180 seconds");
        File output = new File("/sdcard/.dsh/recordings/" + UUID.randomUUID() + ".mp4");
        output.getParentFile().mkdirs();
        JSONObject result = run(new String[]{"screenrecord", "--time-limit", Integer.toString(seconds), output.getPath()}, (seconds + 15) * 1000L);
        if (!output.isFile()) throw new Failure("UNAVAILABLE", "screenrecord did not create a file");
        return result.put("path", output.getPath()).put("bytes", output.length()).put("completed", result.optInt("exitCode") == 0);
    }

    private static JSONObject query(String uri, String projection, JSONObject params) throws Exception {
        List<String> command = new ArrayList<>(Arrays.asList("content", "query", "--uri", uri, "--projection", projection));
        if (!params.optString("where").isEmpty()) command.addAll(Arrays.asList("--where", params.getString("where")));
        return run(command.toArray(new String[0]), 15000);
    }

    private static JSONObject shellUi(String method, JSONObject params) throws Exception {
        if (params.optBoolean("dryRun")) return new JSONObject().put("executed", false).put("via", "shell");
        switch (method) {
            case "ui.click":
                if (params.has("x") && params.has("y")) return success(new String[]{"input", "tap", params.get("x").toString(), params.get("y").toString()}, 5000);
                break;
            case "ui.longClick":
                if (params.has("x") && params.has("y")) {
                    String x = params.get("x").toString(), y = params.get("y").toString();
                    return success(new String[]{"input", "swipe", x, y, x, y, Long.toString(params.optLong("durationMs", 700))}, 15000);
                }
                //$FALL-THROUGH$
            case "ui.swipe": case "ui.drag": case "ui.fling": {
                String[] args = {"input", "swipe", params.get("x1").toString(), params.get("y1").toString(),
                        params.get("x2").toString(), params.get("y2").toString(), Long.toString(params.optLong("durationMs", 500))};
                return success(args, 15000);
            }
            case "ui.gesture": {
                JSONArray paths = params.getJSONArray("paths");
                if (paths.length() != 1) throw new Failure("UNAVAILABLE", "multi-pointer gestures require UiAutomation");
                JSONObject path = paths.getJSONObject(0);
                JSONArray points = path.getJSONArray("points");
                JSONObject first = points.getJSONObject(0), last = points.getJSONObject(points.length() - 1);
                return success(new String[]{"input", "swipe", first.get("x").toString(), first.get("y").toString(),
                        last.get("x").toString(), last.get("y").toString(), Long.toString(path.optLong("durationMs", 500))}, 15000);
            }
            case "ui.doubleTap":
                success(new String[]{"input", "tap", params.get("x").toString(), params.get("y").toString()}, 5000);
                return success(new String[]{"input", "tap", params.get("x").toString(), params.get("y").toString()}, 5000);
            case "ui.global": {
                String action = params.getString("action");
                if (action.equals("assistant")) return success(new String[]{"am", "start", "-a", "android.intent.action.ASSIST"}, 5000);
                if (action.equals("back")) return success(new String[]{"input", "keyevent", "4"}, 5000);
                if (action.equals("home")) return success(new String[]{"input", "keyevent", "3"}, 5000);
                if (action.equals("recents")) return success(new String[]{"input", "keyevent", "187"}, 5000);
                if (action.equals("notifications")) return success(new String[]{"cmd", "statusbar", "expand-notifications"}, 5000);
                if (action.equals("quickSettings")) return success(new String[]{"cmd", "statusbar", "expand-settings"}, 5000);
                if (action.equals("lock")) return success(new String[]{"input", "keyevent", "26"}, 5000);
                if (action.equals("powerDialog")) return success(new String[]{"input", "keyevent", "--longpress", "26"}, 5000);
                if (action.equals("screenshot")) return success(new String[]{"input", "keyevent", "120"}, 5000);
                throw new Failure("UNAVAILABLE", "global action is not available through shell");
            }
            case "ui.key":
                if (params.optBoolean("ctrl") || params.optBoolean("shift") || params.optBoolean("alt")) throw new Failure("UNAVAILABLE", "shell key meta requires UiAutomation");
                List<String> command = new ArrayList<>(Arrays.asList("input", "keyevent"));
                if (params.optBoolean("longPress")) command.add("--longpress");
                command.add(params.getString("key"));
                return success(command.toArray(new String[0]), 5000);
            case "shot": return screenshot(params);
            default: break;
        }
        throw new Failure("UNAVAILABLE", "this UI operation needs a live node or UiAutomation");
    }

    private static JSONObject screenshot(JSONObject params) throws Exception {
        byte[] bytes;
        String via;
        try {
            Bitmap bitmap = UI.screenshot();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
            bitmap.recycle();
            bytes = output.toByteArray();
            via = "uiautomation";
        } catch (Exception e) {
            bytes = binary(new String[]{"screencap", "-p"}, 15000);
            via = "shell";
        }
        if (bytes.length == 0 || bytes.length > 12 * 1024 * 1024) throw new Failure("UNAVAILABLE", "screenshot is empty or too large");
        JSONObject result = new JSONObject().put("mimeType", "image/png")
                .put("base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).put("via", via)
                .put("width", java.nio.ByteBuffer.wrap(bytes).getInt(16)).put("height", java.nio.ByteBuffer.wrap(bytes).getInt(20))
                .put("capturedAt", System.currentTimeMillis());
        if (!params.optString("dest").isEmpty()) {
            File target = allowed(params.getString("dest"), false);
            target.getParentFile().mkdirs();
            write(target, new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1));
            result.put("path", target.getPath());
        }
        return result;
    }

    private static final class Ui {
        Object automation;
        String reason = "SLOT_BUSY";
        long lastUse;
        long lastAttempt;

        synchronized boolean available() {
            if (automation != null) return true;
            if (SystemClock.uptimeMillis() - lastAttempt < 2000) return false;
            try {
                connect();
                return true;
            } catch (Exception e) {
                reason = "SLOT_BUSY";
                return false;
            }
        }

        void connect() throws Exception {
            lastAttempt = SystemClock.uptimeMillis();
            Class<?> connectionClass = Class.forName("android.app.UiAutomationConnection");
            Object connection = connectionClass.getDeclaredConstructor().newInstance();
            Class<?> type = Class.forName("android.app.IUiAutomationConnection");
            Class<?> uiClass = Class.forName("android.app.UiAutomation");
            java.lang.reflect.Constructor<?> constructor = uiClass.getDeclaredConstructor(Looper.class, type);
            constructor.setAccessible(true);
            automation = constructor.newInstance(Looper.getMainLooper(), connection);
            Method connect = uiClass.getDeclaredMethod("connect", int.class);
            connect.setAccessible(true);
            connect.invoke(automation, 1);
            reason = "";
            lastUse = SystemClock.uptimeMillis();
        }

        synchronized Object call(String name, Class<?>[] types, Object... args) throws Exception {
            if (!available()) throw new Failure("SLOT_BUSY", "UiAutomation is unavailable");
            lastUse = SystemClock.uptimeMillis();
            Method method = automation.getClass().getMethod(name, types);
            try {
                return method.invoke(automation, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof java.util.concurrent.TimeoutException) throw new Failure("TIMEOUT", "UI wait timed out");
                if (cause instanceof IllegalStateException) {
                    automation = null;
                    throw new Failure("SLOT_BUSY", "UiAutomation was disconnected");
                }
                throw new Failure("UNAVAILABLE", "UiAutomation call failed");
            }
        }

        AccessibilityNodeInfo root() throws Exception {
            return (AccessibilityNodeInfo) call("getRootInActiveWindow", new Class<?>[0]);
        }

        List<AccessibilityWindowInfo> windows() throws Exception {
            return (List<AccessibilityWindowInfo>) call("getWindows", new Class<?>[0]);
        }

        boolean inject(android.view.InputEvent event) throws Exception {
            return (Boolean) call("injectInputEvent", new Class<?>[]{android.view.InputEvent.class, boolean.class}, event, true);
        }

        Bitmap screenshot() throws Exception {
            return (Bitmap) call("takeScreenshot", new Class<?>[0]);
        }

        JSONObject handle(String method, JSONObject params) throws Exception {
            if (!available()) throw new Failure("SLOT_BUSY", "UiAutomation is unavailable");
            switch (method) {
                case "ui.dump": return dump(params);
                case "ui.find": {
                    JSONArray nodes = dump(params).getJSONArray("nodes"), hits = new JSONArray();
                    for (int i = 0; i < nodes.length() && hits.length() < params.optInt("limit", 30); i++) {
                        JSONObject node = nodes.getJSONObject(i);
                        if (matches(node, params)) hits.put(node);
                    }
                    return new JSONObject().put("nodes", hits).put("count", hits.length()).put("via", "uiautomation");
                }
                case "ui.resolve": {
                    AccessibilityNodeInfo node = find(params);
                    if (node == null) throw new Failure("NOT_FOUND", "no live node matched");
                    try {
                        verify(node);
                        return nodeJson(node, "n1", null, 0, 0, "", node.getWindowId()).put("live", true).put("via", "uiautomation");
                    } finally {
                        node.recycle();
                    }
                }
                case "ui.click": case "ui.longClick": case "ui.doubleTap": case "ui.setText":
                case "ui.appendText": case "ui.clearText": case "ui.selectAll": case "ui.copy": case "ui.paste": case "ui.cut": {
                    AccessibilityNodeInfo node = null;
                    Rect bounds = new Rect();
                    if (params.has("x") && params.has("y")) bounds.set((int) params.getDouble("x"), (int) params.getDouble("y"),
                            (int) params.getDouble("x"), (int) params.getDouble("y"));
                    else {
                        node = find(params);
                        if (node == null) throw new Failure("NOT_FOUND", "no live node matched");
                        node.getBoundsInScreen(bounds);
                        verify(node);
                    }
                    try {
                        if (params.optBoolean("dryRun")) return new JSONObject().put("executed", false)
                                .put("target", new JSONObject().put("x", bounds.centerX()).put("y", bounds.centerY())).put("via", "uiautomation");
                        boolean result;
                        if (method.equals("ui.click")) result = node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                || tap(bounds.centerX(), bounds.centerY()).optBoolean("performed");
                        else if (method.equals("ui.longClick")) result = node != null && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                                || swipe(bounds.centerX(), bounds.centerY(), bounds.centerX(), bounds.centerY(), params.optLong("durationMs", 700)).optBoolean("performed");
                        else if (method.equals("ui.doubleTap")) {
                            result = tap(bounds.centerX(), bounds.centerY()).optBoolean("performed");
                            SystemClock.sleep(80);
                            result = tap(bounds.centerX(), bounds.centerY()).optBoolean("performed") && result;
                        } else {
                            if (node == null || !node.isEditable()) throw new Failure("NOT_CLICKABLE", "target is not editable");
                            if (method.equals("ui.selectAll")) {
                                android.os.Bundle selection = new android.os.Bundle();
                                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
                                selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, string(node.getText()).length());
                                result = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
                            } else if (method.equals("ui.copy") || method.equals("ui.paste") || method.equals("ui.cut")) {
                                result = node.performAction(method.equals("ui.copy") ? AccessibilityNodeInfo.ACTION_COPY
                                        : method.equals("ui.paste") ? AccessibilityNodeInfo.ACTION_PASTE : AccessibilityNodeInfo.ACTION_CUT);
                            } else {
                                android.os.Bundle arguments = new android.os.Bundle();
                                String value = method.equals("ui.appendText") ? string(node.getText()) + params.optString("value")
                                        : method.equals("ui.clearText") ? "" : params.optString("value", params.optString("text"));
                                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                                result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
                            }
                        }
                        if (!result) throw new Failure("UNAVAILABLE", "UI action was rejected");
                        return new JSONObject().put("performed", true).put("via", "uiautomation");
                    } finally {
                        if (node != null) node.recycle();
                    }
                }
                case "ui.gesture": return gesture(params);
                case "ui.scrollUntil": return scrollUntil(params);
                case "ui.swipe": case "ui.drag": case "ui.fling": {
                    long duration = params.optLong("durationMs", 500);
                    if (method.equals("ui.fling")) duration = (long) Math.max(50, Math.min(1500,
                            Math.hypot(params.getDouble("x2") - params.getDouble("x1"), params.getDouble("y2") - params.getDouble("y1"))
                                    * 1000 / Math.max(100, params.optDouble("velocity", 1500))));
                    return swipe((float) params.getDouble("x1"), (float) params.getDouble("y1"),
                            (float) params.getDouble("x2"), (float) params.getDouble("y2"), duration);
                }
                case "ui.key": return key(params);
                case "ui.wait": return waitFor(params);
                case "ui.waitIdle":
                    call("waitForIdle", new Class<?>[]{long.class, long.class}, 100L, Math.min(10000, params.optLong("timeoutMs", 5000)));
                    return new JSONObject().put("idle", true).put("via", "uiautomation");
                case "ui.global": {
                    String action = params.getString("action");
                    if (action.equals("assistant")) return success(new String[]{"am", "start", "-a", "android.intent.action.ASSIST"}, 5000)
                            .put("requested", true).put("confirmed", false).put("via", "shell").put("degraded", true);
                    int value = action.equals("back") ? AccessibilityService.GLOBAL_ACTION_BACK
                            : action.equals("home") ? AccessibilityService.GLOBAL_ACTION_HOME
                            : action.equals("recents") ? AccessibilityService.GLOBAL_ACTION_RECENTS
                            : action.equals("notifications") ? AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                            : action.equals("quickSettings") ? AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                            : action.equals("lock") ? AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                            : action.equals("screenshot") ? AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                            : action.equals("splitScreen") ? AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                            : action.equals("powerDialog") ? AccessibilityService.GLOBAL_ACTION_POWER_DIALOG : -1;
                    if (value < 0) throw new Failure("UNAVAILABLE", "unknown global action");
                    return new JSONObject().put("performed", call("performGlobalAction", new Class<?>[]{int.class}, value)).put("via", "uiautomation");
                }
                default: return null;
            }
        }

        private JSONObject gesture(JSONObject params) throws Exception {
            if (params.optBoolean("dryRun")) return new JSONObject().put("executed", false).put("via", "uiautomation");
            JSONArray paths = params.getJSONArray("paths");
            if (paths.length() < 1 || paths.length() > 10) throw new Failure("INVALID_PARAMS", "one to ten paths are required");
            long[] durations = new long[paths.length()];
            long maximum = 0;
            for (int i = 0; i < paths.length(); i++) {
                JSONObject path = paths.getJSONObject(i);
                if (path.optLong("startMs") != 0) throw new Failure("UNAVAILABLE", "staggered pointers require the App gesture backend");
                JSONArray points = path.getJSONArray("points");
                if (points.length() < 2 || points.length() > 100) throw new Failure("INVALID_PARAMS", "invalid pointer path");
                durations[i] = Math.max(50, Math.min(10000, path.optLong("durationMs", 500)));
                maximum = Math.max(maximum, durations[i]);
            }
            long downTime = SystemClock.uptimeMillis();
            List<Integer> active = new ArrayList<>();
            for (int i = 0; i < paths.length(); i++) {
                active.add(i);
                int action = i == 0 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_POINTER_DOWN | (i << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
                emit(paths, durations, active, 0, action, downTime);
                SystemClock.sleep(16);
            }
            for (long elapsed = 16; elapsed <= maximum && !active.isEmpty(); elapsed += 16) {
                List<Integer> finished = new ArrayList<>();
                for (int id : active) if (elapsed >= durations[id]) finished.add(id);
                if (finished.isEmpty()) {
                    emit(paths, durations, active, elapsed, MotionEvent.ACTION_MOVE, downTime);
                } else {
                    for (int id : finished) {
                        int index = active.indexOf(id);
                        int action = active.size() == 1 ? MotionEvent.ACTION_UP
                                : MotionEvent.ACTION_POINTER_UP | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
                        emit(paths, durations, active, durations[id], action, downTime);
                        active.remove(index);
                        if (active.isEmpty()) break;
                    }
                }
                SystemClock.sleep(16);
            }
            return new JSONObject().put("performed", true).put("completed", true).put("via", "uiautomation");
        }

        private void emit(JSONArray paths, long[] durations, List<Integer> active, long elapsed, int action, long downTime) throws Exception {
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[active.size()];
            MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[active.size()];
            for (int i = 0; i < active.size(); i++) {
                int id = active.get(i);
                properties[i] = new MotionEvent.PointerProperties();
                properties[i].id = id;
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                float[] point = pointAt(paths.getJSONObject(id), Math.min(elapsed, durations[id]));
                coordinates[i] = new MotionEvent.PointerCoords();
                coordinates[i].x = point[0];
                coordinates[i].y = point[1];
                coordinates[i].pressure = 1;
                coordinates[i].size = 1;
                if (point[0] < 0 || point[1] < 0) throw new Failure("NOT_VISIBLE", "pointer is outside the screen");
            }
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, active.size(),
                    properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0, 0, 0);
            try {
                if (!inject(event)) throw new Failure("UNAVAILABLE", "multi-pointer injection was rejected");
            } finally {
                event.recycle();
            }
        }

        private float[] pointAt(JSONObject path, long elapsed) throws Exception {
            JSONArray points = path.getJSONArray("points");
            long duration = path.optLong("durationMs", 500);
            for (int i = 1; i < points.length(); i++) {
                JSONObject before = points.getJSONObject(i - 1), after = points.getJSONObject(i);
                long start = before.optLong("timeMs", before.optLong("time", duration * (i - 1) / (points.length() - 1)));
                long end = after.optLong("timeMs", after.optLong("time", duration * i / (points.length() - 1)));
                if (elapsed <= end || i == points.length() - 1) {
                    float fraction = end == start ? 0 : Math.max(0, Math.min(1, (elapsed - start) / (float) (end - start)));
                    return new float[]{(float) (before.getDouble("x") + (after.getDouble("x") - before.getDouble("x")) * fraction),
                            (float) (before.getDouble("y") + (after.getDouble("y") - before.getDouble("y")) * fraction)};
                }
            }
            JSONObject point = points.getJSONObject(0);
            return new float[]{(float) point.getDouble("x"), (float) point.getDouble("y")};
        }

        private JSONObject key(JSONObject params) throws Exception {
            String value = params.getString("key");
            int code = value.matches("[0-9]+") ? Integer.parseInt(value) : KeyEvent.keyCodeFromString(value);
            if (code <= 0) throw new Failure("INVALID_PARAMS", "unknown keycode");
            int meta = (params.optBoolean("ctrl") ? KeyEvent.META_CTRL_ON : 0)
                    | (params.optBoolean("shift") ? KeyEvent.META_SHIFT_ON : 0) | (params.optBoolean("alt") ? KeyEvent.META_ALT_ON : 0);
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, 0, 0,
                    params.optBoolean("longPress") ? KeyEvent.FLAG_LONG_PRESS : 0);
            boolean ok = inject(down);
            KeyEvent up = new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta);
            ok = inject(up) && ok;
            if (!ok) throw new Failure("UNAVAILABLE", "key injection was rejected");
            return new JSONObject().put("performed", true).put("via", "uiautomation");
        }

        private JSONObject tap(float x, float y) throws Exception {
            long now = SystemClock.uptimeMillis();
            if (!inject(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))) throw new Failure("UNAVAILABLE", "tap was rejected");
            if (!inject(MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))) throw new Failure("UNAVAILABLE", "tap was rejected");
            return new JSONObject().put("performed", true).put("via", "uiautomation");
        }

        private JSONObject swipe(float x1, float y1, float x2, float y2, long duration) throws Exception {
            long start = SystemClock.uptimeMillis();
            if (!inject(MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x1, y1, 0))) throw new Failure("UNAVAILABLE", "swipe was rejected");
            int steps = (int) Math.max(1, Math.min(100, duration / 16));
            for (int i = 1; i <= steps; i++) {
                float fraction = i / (float) steps;
                long now = SystemClock.uptimeMillis();
                if (!inject(MotionEvent.obtain(start, now, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * fraction, y1 + (y2 - y1) * fraction, 0))) {
                    throw new Failure("UNAVAILABLE", "swipe was rejected");
                }
                SystemClock.sleep(Math.max(1, duration / steps));
            }
            if (!inject(MotionEvent.obtain(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x2, y2, 0))) throw new Failure("UNAVAILABLE", "swipe was rejected");
            return new JSONObject().put("performed", true).put("via", "uiautomation");
        }

        private AccessibilityNodeInfo find(JSONObject params) throws Exception {
            AccessibilityNodeInfo root = root();
            if (root == null) return null;
            try {
                return find(root, params, "", 0, 0, new int[]{2000});
            } finally {
                root.recycle();
            }
        }

        private AccessibilityNodeInfo find(AccessibilityNodeInfo node, JSONObject params, String path, int depth, int index, int[] budget) throws Exception {
            if (node == null || depth > 50 || budget[0]-- <= 0) return null;
            boolean keep = false;
            try {
                JSONObject snapshot = nodeJson(node, "n0", null, depth, index, path, node.getWindowId());
                if (node.isVisibleToUser() && matches(snapshot, params)) {
                    keep = true;
                    return node;
                }
                String own = snapshot.optString("viewId").isEmpty() ? "cls:" + snapshot.optString("cls") + "#" + index
                        : "vid:" + snapshot.optString("viewId");
                String next = path.isEmpty() ? own : path + "/" + own;
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo hit = find(node.getChild(i), params, next, depth + 1, i, budget);
                    if (hit != null) return hit;
                }
                return null;
            } finally {
                if (!keep) node.recycle();
            }
        }

        private JSONObject dump(JSONObject params) throws Exception {
            JSONArray nodes = new JSONArray();
            int[] counter = {1};
            int[] budget = {Math.max(1, Math.min(2000, params.optInt("maxNodes", 1500)))};
            for (AccessibilityWindowInfo window : windows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    dump(root, nodes, counter, budget, null, 0, 0, "", window.getId());
                } finally {
                    root.recycle();
                }
            }
            if (nodes.length() == 0) {
                AccessibilityNodeInfo root = root();
                if (root != null) {
                    try {
                        dump(root, nodes, counter, budget, null, 0, 0, "", root.getWindowId());
                    } finally {
                        root.recycle();
                    }
                }
            }
            if (nodes.length() == 0) throw new Failure("NOT_FOUND", "no readable active window");
            return new JSONObject().put("available", true).put("nodes", nodes).put("via", "uiautomation")
                    .put("timestamp", System.currentTimeMillis());
        }

        private void dump(AccessibilityNodeInfo nativeNode, JSONArray nodes, int[] counter, int[] budget,
                String parent, int depth, int index, String path, int windowId) throws Exception {
            if (depth > 40 || budget[0]-- <= 0) return;
            String id = "n" + counter[0]++;
            JSONObject node = nodeJson(nativeNode, id, parent, depth, index, path, windowId);
            nodes.put(node);
            String own = node.optString("viewId").isEmpty() ? "cls:" + node.getString("cls") + "#" + index : "vid:" + node.optString("viewId");
            String next = path.isEmpty() ? own : path + "/" + own;
            for (int i = 0; i < nativeNode.getChildCount(); i++) {
                AccessibilityNodeInfo child = nativeNode.getChild(i);
                if (child != null) {
                    try {
                        dump(child, nodes, counter, budget, id, depth + 1, i, next, windowId);
                    } finally {
                        child.recycle();
                    }
                }
            }
        }

        private JSONObject nodeJson(AccessibilityNodeInfo nativeNode, String id, String parent, int depth,
                int index, String path, int windowId) throws Exception {
            String cls = string(nativeNode.getClassName()), pkg = string(nativeNode.getPackageName());
            String viewId = string(nativeNode.getViewIdResourceName());
            String text = nativeNode.isPassword() ? "***" : string(nativeNode.getText());
            String desc = nativeNode.isPassword() ? "***" : string(nativeNode.getContentDescription());
            String own = viewId.isEmpty() ? "cls:" + cls + "#" + index : "vid:" + viewId;
            String content = text + "|" + desc;
            String matchKey = "v1:" + hash("1|" + pkg + "|app|" + path + "|" + own + "|" + content.substring(0, Math.min(64, content.length())));
            Rect bounds = new Rect();
            nativeNode.getBoundsInScreen(bounds);
            JSONArray actions = new JSONArray();
            if (nativeNode.isClickable()) actions.put("click");
            if (nativeNode.isLongClickable()) actions.put("longClick");
            if (nativeNode.isEditable()) actions.put("setText");
            if (nativeNode.isScrollable()) actions.put("scrollForward").put("scrollBackward");
            JSONObject node = new JSONObject().put("nodeId", id).put("matchKey", matchKey).put("parentId", parent == null ? JSONObject.NULL : parent)
                    .put("depth", depth).put("index", index).put("cls", cls).put("class", cls).put("pkg", pkg).put("package", pkg)
                    .put("text", text).put("desc", desc).put("viewId", viewId.isEmpty() ? JSONObject.NULL : viewId)
                    .put("isPassword", nativeNode.isPassword()).put("windowId", windowId).put("windowType", "app")
                    .put("bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.width()).put(bounds.height()))
                    .put("clickable", nativeNode.isClickable()).put("longClickable", nativeNode.isLongClickable())
                    .put("editable", nativeNode.isEditable()).put("enabled", nativeNode.isEnabled())
                    .put("visible", nativeNode.isVisibleToUser()).put("scrollable", nativeNode.isScrollable())
                    .put("checkable", nativeNode.isCheckable()).put("checked", nativeNode.isChecked())
                    .put("selected", nativeNode.isSelected()).put("focused", nativeNode.isFocused())
                    .put("childCount", nativeNode.getChildCount()).put("structurePath", path).put("actions", actions);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                node.put("hint", nativeNode.isPassword() ? "***" : string(nativeNode.getHintText()));
                node.put("inputType", nativeNode.getInputType());
            }
            return node;
        }

        private boolean matches(JSONObject node, JSONObject params) throws Exception {
            if (!params.optString("matchKey").isEmpty() && !params.getString("matchKey").equals(node.optString("matchKey"))) return false;
            String text = params.optString("text");
            if (!text.isEmpty()) {
                boolean exact = params.optBoolean("exact");
                String actual = node.optString("text"), desc = node.optString("desc");
                if (!(exact ? actual.equals(text) || desc.equals(text)
                        : actual.toLowerCase().contains(text.toLowerCase()) || desc.toLowerCase().contains(text.toLowerCase()))) return false;
            }
            if (!params.optString("viewId").isEmpty() && !params.getString("viewId").equals(node.optString("viewId"))) return false;
            if (!params.optString("pkg").isEmpty() && !params.getString("pkg").equals(node.optString("pkg"))) return false;
            JSONObject where = params.optJSONObject("where");
            if (where != null) {
                for (java.util.Iterator<String> it = where.keys(); it.hasNext();) {
                    String key = it.next(), field = key, operator = "=";
                    for (String candidate : new String[]{"~=", "!=", "^=", "$=", ">=", "<=", ">", "<", "~"}) {
                        if (key.endsWith(candidate)) {
                            field = key.substring(0, key.length() - candidate.length());
                            operator = candidate.equals("~") ? "~=" : candidate;
                            break;
                        }
                    }
                    if (field.equals("class")) field = "cls";
                    if (field.equals("package")) field = "pkg";
                    String expected = String.valueOf(where.get(key));
                    String actual = node.opt(field) == null || node.opt(field) == JSONObject.NULL ? "" : String.valueOf(node.opt(field));
                    String lower = actual.toLowerCase(), wanted = expected.toLowerCase();
                    boolean match;
                    switch (operator) {
                        case "=": match = lower.equals(wanted) || field.equals("cls") && lower.endsWith("." + wanted); break;
                        case "!=": match = !lower.equals(wanted); break;
                        case "~=": match = lower.contains(wanted); break;
                        case "^=": match = lower.startsWith(wanted); break;
                        case "$=": match = lower.endsWith(wanted); break;
                        default:
                            double left = Double.parseDouble(actual), right = Double.parseDouble(expected);
                            match = operator.equals(">") ? left > right : operator.equals("<") ? left < right
                                    : operator.equals(">=") ? left >= right : left <= right;
                    }
                    if (!match) return false;
                }
            }
            if (!params.optString("selector").isEmpty()) {
                String selector = params.getString("selector");
                if (selector.contains("||") || selector.contains("(") || selector.contains("!")) {
                    throw new Failure("UNAVAILABLE", "complex selector requires the App session backend");
                }
                for (String part : selector.split("\\s*&&\\s*")) {
                    part = part.trim();
                    if (part.equals("clickable") || part.equals("editable") || part.equals("visible")) {
                        if (!node.optBoolean(part)) return false;
                        continue;
                    }
                    String[] pair = part.split("~?=", 2);
                    if (pair.length != 2) throw new Failure("PARSE_ERROR", "invalid selector");
                    String field = pair[0].trim(), expected = pair[1].trim();
                    boolean contains = field.endsWith("~");
                    if (contains) field = field.substring(0, field.length() - 1);
                    if (expected.length() >= 2 && (expected.startsWith("'") || expected.startsWith("\""))) expected = expected.substring(1, expected.length() - 1);
                    String actual = node.optString(field.equals("class") ? "cls" : field);
                    if (contains ? !actual.toLowerCase().contains(expected.toLowerCase()) : !actual.equalsIgnoreCase(expected)) return false;
                }
            }
            return true;
        }

        private void verify(AccessibilityNodeInfo node) throws Exception {
            if (!node.refresh() || !node.isVisibleToUser()) throw new Failure("NOT_VISIBLE", "target is not visible");
            if (!node.isEnabled()) throw new Failure("NOT_CLICKABLE", "target is disabled");
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String size = run(new String[]{"wm", "size"}, 5000).optString("stdout");
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([0-9]+)x([0-9]+)").matcher(size);
            int width = 0, height = 0;
            while (matcher.find()) {
                width = Integer.parseInt(matcher.group(1));
                height = Integer.parseInt(matcher.group(2));
            }
            if (bounds.width() <= 0 || bounds.height() <= 0 || width > 0 && !bounds.intersects(0, 0, width, height)) {
                throw new Failure("NOT_VISIBLE", "target is outside the screen");
            }
        }

        private JSONObject scrollUntil(JSONObject params) throws Exception {
            int action = params.optString("direction", "forward").equals("backward")
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            for (int i = 0; i <= Math.min(50, params.optInt("maxScrolls", 20)); i++) {
                AccessibilityNodeInfo found = find(params);
                if (found != null) {
                    try {
                        verify(found);
                        return nodeJson(found, "n1", null, 0, 0, "", found.getWindowId())
                                .put("scrolls", i).put("via", "uiautomation");
                    } finally {
                        found.recycle();
                    }
                }
                AccessibilityNodeInfo root = root();
                if (root == null || !root.performAction(action)) {
                    if (root != null) root.recycle();
                    throw new Failure("NOT_FOUND", "target was not found and the container cannot scroll");
                }
                root.recycle();
                call("waitForIdle", new Class<?>[]{long.class, long.class}, 100L, 800L);
            }
            throw new Failure("TIMEOUT", "target was not found within the scroll budget");
        }

        private JSONObject waitFor(JSONObject params) throws Exception {
            long deadline = SystemClock.uptimeMillis() + Math.min(120000, params.optLong("timeoutMs", 10000));
            boolean gone = params.optString("until", "exists").equals("gone");
            while (SystemClock.uptimeMillis() < deadline) {
                AccessibilityNodeInfo node = find(params);
                if ((node != null) != gone) {
                    if (node != null) node.recycle();
                    return new JSONObject().put("matched", true).put("via", "uiautomation");
                }
                if (node != null) node.recycle();
                SystemClock.sleep(100);
            }
            throw new Failure("TIMEOUT", "UI condition was not met before timeout");
        }

        synchronized void releaseIdle() {
            if (automation != null && SystemClock.uptimeMillis() - lastUse > 5000) {
                try {
                    Method disconnect = automation.getClass().getDeclaredMethod("disconnect");
                    disconnect.setAccessible(true);
                    disconnect.invoke(automation);
                } catch (Exception ignored) {
                }
                automation = null;
            }
        }
    }

    private static final class Failure extends Exception {
        final String code;

        Failure(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static JSONObject run(String[] command, long timeoutMs) throws Exception {
        java.lang.Process process = new ProcessBuilder(command).start();
        Collector stdout = new Collector(process.getInputStream(), 262144);
        Collector stderr = new Collector(process.getErrorStream(), 262144);
        stdout.start();
        stderr.start();
        long timeout = Math.max(1000, Math.min(200000, timeoutMs));
        if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            stdout.join(1000);
            stderr.join(1000);
            return new JSONObject().put("exitCode", 124).put("stdout", stdout.text()).put("stderr", stderr.text()).put("via", "shell");
        }
        stdout.join(1000);
        stderr.join(1000);
        return new JSONObject().put("exitCode", process.exitValue()).put("stdout", stdout.text()).put("stderr", stderr.text()).put("via", "shell");
    }

    private static JSONObject success(String[] command, long timeoutMs) throws Exception {
        JSONObject result = run(command, timeoutMs);
        if (result.optInt("exitCode") != 0) {
            String detail = result.optString("stderr");
            if (detail.isEmpty()) detail = result.optString("stdout");
            throw new Failure("UNAVAILABLE", detail.substring(0, Math.min(160, detail.length())));
        }
        return result;
    }

    private static byte[] binary(String[] command, long timeoutMs) throws Exception {
        java.lang.Process process = new ProcessBuilder(command).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                while (true) {
                    int count = input.read(buffer);
                    if (count < 0 || output.size() > 12 * 1024 * 1024) break;
                    output.write(buffer, 0, count);
                }
            } catch (Exception ignored) {
            }
        });
        reader.start();
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.destroyForcibly();
        reader.join(2000);
        return output.toByteArray();
    }

    private static final class Collector extends Thread {
        final InputStream input;
        final int limit;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Collector(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
        }

        @Override public void run() {
            try (InputStream stream = input) {
                byte[] buffer = new byte[4096];
                while (true) {
                    int count = stream.read(buffer);
                    if (count < 0) break;
                    if (bytes.size() < limit) bytes.write(buffer, 0, Math.min(count, limit - bytes.size()));
                }
            } catch (Exception ignored) {
            }
        }

        String text() {
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String line(BufferedReader input) throws Exception {
        StringBuilder result = new StringBuilder();
        int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') break;
            if (c == '\r') continue;
            if (result.length() > 1024 * 1024) throw new Failure("INVALID_PARAMS", "request too large");
            result.append((char) c);
        }
        return result.toString();
    }

    private static String read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                if (bytes.size() + count > 1024 * 1024) throw new Failure("INVALID_PARAMS", "file is too large");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void write(File file, String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.ISO_8859_1));
            output.getFD().sync();
        }
        file.setReadable(false, false);
        file.setReadable(true, true);
        file.setWritable(false, false);
        file.setWritable(true, true);
    }

    private static File allowed(String value, boolean mustExist) throws Exception {
        File file = new File(value).getCanonicalFile();
        String path = file.getPath();
        String download = new File("/sdcard/Download").getCanonicalPath() + File.separator;
        String dsh = new File("/sdcard/.dsh").getCanonicalPath() + File.separator;
        if ((!path.startsWith(download) && !path.startsWith(dsh)) || (mustExist && !file.isFile())) {
            throw new Failure("PATH_NOT_ALLOWED", "path must be within Download or .dsh");
        }
        return file;
    }

    private static String pkg(JSONObject params) throws Failure {
        String value = params.optString("package");
        if (!value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) throw new Failure("INVALID_PARAMS", "invalid package name");
        return value;
    }

    private static String namespace(JSONObject params) throws Failure {
        String value = params.optString("namespace", "system");
        if (!value.matches("system|secure|global")) throw new Failure("INVALID_PARAMS", "invalid settings namespace");
        return value;
    }

    private static String name(JSONObject params) throws Failure {
        String value = params.optString("name");
        if (!value.matches("[A-Za-z0-9_.-]{1,128}")) throw new Failure("INVALID_PARAMS", "invalid setting name");
        return value;
    }

    private static void confirm(JSONObject params) throws Failure {
        if (!"YES".equals(params.optString("confirm"))) throw new Failure("CONFIRM_REQUIRED", "set confirm to YES");
    }

    private static JSONObject error(String code, String message) throws Exception {
        return new JSONObject().put("code", code).put("message", message).put("detail", new JSONObject());
    }

    private static String string(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : digest) result.append(String.format("%02x", item));
        return result.toString();
    }
}
