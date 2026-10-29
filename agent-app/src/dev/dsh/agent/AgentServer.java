package dev.dsh.agent;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.view.accessibility.AccessibilityEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class AgentServer {
    private static final int PORT = 17321;
    private static final int MAX_LINE = 1024 * 1024;
    private static final int MAX_RESPONSE = 16 * 1024 * 1024;
    private static final Set<String> CONFIRMS = new HashSet<>(java.util.Arrays.asList(
            "settings.put", "prep.automation", "prep.restore", "power.reboot", "lock.unlock", "net.wifi", "net.data",
            "net.airplane", "display.setRotation", "display.setDensity", "location.mock", "shell.exec", "fs.rm",
            "package.install", "package.uninstall", "package.clear", "package.forceStop", "package.enable",
            "package.disable", "perm.grant", "perm.revoke", "activity.forceStop"));
    static final EventBus events = new EventBus();
    private static final List<Connection> CONNECTIONS = new CopyOnWriteArrayList<>();
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor();
    private static final ExecutorService EVENT_WRITERS = Executors.newCachedThreadPool();
    private static final Map<String, Idempotent> IDEMPOTENT = new LinkedHashMap<>();
    private static volatile Context context;
    private static volatile AccessibilityService service;
    private static volatile ServerSocket server;
    private static volatile Thread thread;
    private static volatile String lastError;
    private static boolean tokenInitialized;
    private static volatile ExecutorService clients;
    private static long lastUiEvent;
    private static boolean recording;
    private static boolean recordingIncludeSensitive;
    private static JSONArray recordingSteps = new JSONArray();

    private AgentServer() {
    }

    public static synchronized void start(Context ctx, AccessibilityService svc) {
        context = ctx.getApplicationContext();
        service = svc;
        Jobs.init(context);
        SystemEvents.init(context);
        if (!tokenInitialized) {
            if (context.getSharedPreferences("agent", 0).getBoolean("rotateOnStart", false)
                    && tokenFile(context).isFile() && rotateToken().isEmpty()) {
                lastError = "token rotation failed";
                return;
            }
            tokenInitialized = true;
        }
        if (token(context).isEmpty()) {
            lastError = "token initialization failed";
            return;
        }
        if (thread != null && thread.isAlive()) return;
        clients = new ThreadPoolExecutor(4, 16, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), runnable -> {
            Thread worker = new Thread(runnable, "dsh-agent-client");
            worker.setDaemon(true);
            return worker;
        });
        thread = new Thread(AgentServer::acceptLoop, "dsh-agent-server");
        thread.setDaemon(true);
        thread.start();
        HEARTBEAT.scheduleWithFixedDelay(() -> {
            for (Connection connection : CONNECTIONS) connection.heartbeat();
        }, 15, 15, TimeUnit.SECONDS);
    }

    public static synchronized void stop() {
        SystemEvents.close();
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
        for (Connection connection : CONNECTIONS) connection.close();
        if (clients != null) clients.shutdownNow();
        clients = null;
        Thread old = thread;
        if (old != null && old != Thread.currentThread()) {
            try {
                old.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        service = null;
    }

    static synchronized void detach(AccessibilityService svc) {
        if (service == svc) service = null;
    }

    static AccessibilityService accessibility() {
        return service;
    }

    static Context context() {
        return context;
    }

    private static volatile android.app.Activity foregroundActivity;

    static void attachActivity(android.app.Activity activity) {
        foregroundActivity = activity;
    }

    static void detachActivity(android.app.Activity activity) {
        if (foregroundActivity == activity) foregroundActivity = null;
    }

    static android.app.Activity activity() {
        return foregroundActivity;
    }

    static String summary() {
        return status() + "\n无障碍：" + (service != null ? "已连接" : "未连接")
                + "\n通知使用权：" + (NotificationRelay.connected() ? "已连接" : "未连接")
                + "\n文件访问：" + (FileBridge.storageAvailable() ? "已授权" : "未授权")
                + "\nroot helper：" + (RootClient.available() ? "已连接" : "未连接")
                + "\n前台保活：" + (AgentRuntimeService.enabled(context) ? "开启" : "关闭");
    }

    static String status() {
        if (server != null) return "listening on 127.0.0.1:17321";
        return lastError == null ? "stopped" : lastError;
    }

    static File tokenFile(Context ctx) {
        return new File(ctx.getFilesDir(), "token");
    }

    private static synchronized String token(Context ctx) {
        File file = tokenFile(ctx);
        try {
            if (file.isFile()) {
                String existing = read(file).trim();
                if (existing.matches("[0-9a-f]{32}")) return existing;
            }
            String value = UUID.randomUUID().toString().replace("-", "");
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(value.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            file.setReadable(false, false);
            file.setReadable(true, true);
            file.setWritable(false, false);
            file.setWritable(true, true);
            return value;
        } catch (Exception e) {
            return "";
        }
    }

    private static synchronized String rotateToken() {
        if (tokenFile(context).delete()) {
            String value = token(context);
            return value.isEmpty() ? "" : "rotated";
        }
        return "";
    }

    private static String read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) Math.min(file.length(), 4096)];
            int count = input.read(buffer);
            return count <= 0 ? "" : new String(buffer, 0, count, StandardCharsets.UTF_8);
        }
    }

    private static void acceptLoop() {
        lastError = null;
        try {
            ServerSocket listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 16);
            server = listener;
            while (!listener.isClosed()) {
                try {
                    Socket socket = listener.accept();
                    Connection connection = new Connection(socket);
                    CONNECTIONS.add(connection);
                    clients.execute(connection);
                } catch (java.net.SocketException e) {
                    if (!listener.isClosed()) lastError = "socket accept failed";
                } catch (Exception e) {
                    lastError = "client limit reached";
                }
            }
        } catch (Exception e) {
            lastError = "loopback listener unavailable";
        }
    }

    static Object dispatch(String method, JSONObject params) throws Exception {
        if (params == null) params = new JSONObject();
        if (context == null) throw new AgentError("INTERNAL", "agent is not initialized");
        if (readonly() && isMutation(method)) throw new AgentError("READONLY", "read-only mode rejects device mutation");
        if (readonly() && method.equals("shot") && params.has("dest")) throw new AgentError("READONLY", "read-only mode rejects file writes");
        if (CONFIRMS.contains(method) || method.startsWith("package.") && !method.equals("package.list")
                || method.startsWith("perm.")) AgentError.confirm(params);
        String key = params.optString("idempotencyKey");
        if (key.isEmpty() || !isMutation(method)) return dispatchInternal(method, params);
        if (key.length() > 128) throw new AgentError("INVALID_PARAMS", "idempotencyKey is too long");
        String digest = digest(method, params);
        String mapKey = method + "\n" + key;
        Idempotent owner;
        boolean mine = false;
        synchronized (IDEMPOTENT) {
            pruneIdempotency();
            Idempotent old = IDEMPOTENT.get(mapKey);
            if (old != null) {
                if (!old.digest.equals(digest)) throw new AgentError("CONFLICT", "idempotencyKey has different parameters");
                if (!old.pending) return replay(old);
                owner = old;
            } else {
                if (IDEMPOTENT.size() >= 100) throw new AgentError("TOO_MANY_REQUESTS", "too many pending idempotent requests");
                owner = new Idempotent(digest);
                IDEMPOTENT.put(mapKey, owner);
                mine = true;
            }
        }
        if (!mine) {
            if (!owner.done.await(35, TimeUnit.SECONDS)) {
                throw new AgentError("TIMEOUT", "outcome is unknown; verify with a read request").detail("resultState", "UNKNOWN");
            }
            return replay(owner);
        }
        try {
            Object result = dispatchInternal(method, params);
            synchronized (IDEMPOTENT) {
                owner.complete(result);
            }
            return result;
        } catch (AgentError error) {
            if (error.code.equals("TIMEOUT")) error.resultState = "UNKNOWN";
            synchronized (IDEMPOTENT) {
                owner.fail(error);
            }
            throw error;
        } catch (Exception error) {
            synchronized (IDEMPOTENT) {
                owner.fail(new AgentError("INTERNAL", "internal error"));
            }
            throw error;
        }
    }

    private static Object replay(Idempotent old) throws Exception {
        if (old.error != null) {
            AgentError error = new AgentError(old.error, old.errorMessage);
            error.replayed = true;
            if (old.error.equals("TIMEOUT")) error.resultState = "UNKNOWN";
            throw error;
        }
        if (old.result instanceof JSONObject) return new JSONObject(old.result.toString()).put("replayed", true);
        return old.result;
    }

    static boolean isMutation(String method) {
        return AgentMethods.isMutation(method);
    }

    static boolean isReadonly() {
        return readonly();
    }

    private static Object dispatchInternal(String method, JSONObject params) throws Exception {
        long started = System.nanoTime();
        Object result;
        String via = "app";
        String failure = "";
        try {
            switch (method) {
                case "ping": {
                    JSONObject ping = backend(false, params);
                    ping.put("service", service != null).put("port", PORT).put("apiVersion", AgentMethods.API_VERSION)
                            .put("version", AgentMethods.VERSION).put("buildTime", AgentMethods.BUILD_TIME).put("via", via)
                            .put("backend", new JSONObject().put("a11y", service != null ? "on" : "off")
                                    .put("root", ping.getJSONObject("root").optBoolean("ok") ? "yes" : "no")
                                    .put("uiautomation", ping.getJSONArray("layers").getJSONObject(0).optBoolean("ok")
                                            ? "available" : "unavailable"));
                    return ping;
                }
                case "backend.status": case "backend.selftest": case "selftest":
                    return backend(method.endsWith("selftest"), params);
                case "macro": return macro(params);
                case "ui.query": return UiQuery.execute(params);
                case "job.start":
                    JSONObject request = params.optJSONObject("request");
                    return Jobs.start(request == null ? params.getString("method") : request.getString("method"),
                            request == null ? params.optJSONObject("params") : request.optJSONObject("params"));
                case "job.status": return Jobs.status(params.getString("jobId"));
                case "job.cancel": return Jobs.cancel(params.getString("jobId"));
                case "job.schedule":
                    return Jobs.schedule(params.getString("method"), params.optJSONObject("params"), params.getJSONObject("schedule"));
                case "session.analyze": result = SessionStore.analyze(params); via = "a11y"; break;
                case "session.current": case "session.status": result = SessionStore.status(params); break;
                case "session.list": result = SessionStore.list(); break;
                case "session.use": result = SessionStore.use(params); break;
                case "session.drop": result = SessionStore.drop(params); break;
                case "session.autosave": result = SessionStore.autosave(params); break;
                case "session.save": result = SessionStore.save(params); break;
                case "session.persist": result = SessionStore.persist(params); break;
                case "session.load": result = SessionStore.load(params); break;
                case "session.restoreLast": result = SessionStore.load(new JSONObject().put("name", "_last")); break;
                case "session.saved.list": result = SessionStore.savedList(); break;
                case "session.saved.delete": result = SessionStore.savedDelete(params); break;
                case "ui.resolve": return routeUi(method, params);
                case "ui.path": result = SessionStore.path(params); break;
                case "ui.refs": result = SessionStore.refs(params); break;
                case "ui.seek": result = SessionStore.seek(params); break;
                case "ui.flag": result = SessionStore.flag(params, false); break;
                case "ui.unflag": result = SessionStore.flag(params, true); break;
                case "ui.flags.list": result = SessionStore.flags(params); break;
                case "recording.start":
                    recording = true;
                    recordingIncludeSensitive = params.optBoolean("includeSensitive", false);
                    recordingSteps = new JSONArray();
                    result = new JSONObject().put("recording", true).put("includeSensitive", recordingIncludeSensitive);
                    break;
                case "recording.stop": return stopRecording();
                case "recording.replay":
                    return macro(new JSONObject().put("steps", new JSONObject(readRecording(new File(context.getFilesDir(), "recording.json"))).getJSONArray("steps")));
                case "diag.log": case "diag.tail": case "backend.diag.tail":
                    result = new JSONObject().put("entries", Diagnostics.tail(params.optInt("n", 50)));
                    break;
                case "diag.clear": result = new JSONObject().put("cleared", true); Diagnostics.clear(); break;
                case "security.setMode":
                    if (!params.optBoolean("readonly", true)) AgentError.confirm(params);
                    context.getSharedPreferences("agent", 0).edit().putBoolean("readonly", params.optBoolean("readonly", true)).apply();
                    result = new JSONObject().put("readonly", readonly());
                    break;
                case "security.tokenRotate":
                    AgentError.confirm(params);
                    if (rotateToken().isEmpty()) throw new AgentError("INTERNAL", "token rotation failed");
                    result = new JSONObject().put("rotated", true);
                    break;
                case "security.tokenPolicy":
                    if (params.has("rotateOnStart")) {
                        AgentError.confirm(params);
                        context.getSharedPreferences("agent", 0).edit()
                                .putBoolean("rotateOnStart", params.getBoolean("rotateOnStart")).apply();
                    }
                    result = new JSONObject().put("loopbackOnly", true)
                            .put("rotateOnStart", context.getSharedPreferences("agent", 0)
                                    .getBoolean("rotateOnStart", false));
                    break;
                case "input.profile":
                    int delay = Math.max(0, Math.min(1000, params.optInt("delayMs")));
                    int jitter = Math.max(0, Math.min(4, params.optInt("jitterPx")));
                    context.getSharedPreferences("agent", 0).edit().putInt("delayMs", delay).putInt("jitterPx", jitter).apply();
                    result = new JSONObject().put("delayMs", delay).put("jitterPx", jitter);
                    break;
                default:
                    if (method.startsWith("ui.") || method.equals("shot") || method.equals("screen.diff")) {
                        return routeUi(method, params);
                    }
                    result = FileBridge.call(method, params);
                    if (result == null) result = MediaBridge.call(method, params);
                    if (result == null) result = SystemBridge.call(method, params);
                    if (result == null && (method.startsWith("root.") || method.startsWith("shell.") || method.startsWith("settings.")
                            || method.startsWith("package.") || method.startsWith("perm.") || method.startsWith("task.")
                            || method.startsWith("power.") || method.startsWith("net.") || method.startsWith("display.")
                            || method.startsWith("user.") || method.startsWith("location.") || method.startsWith("perf.")
                            || method.startsWith("prep.") || method.startsWith("ime.") || method.startsWith("dnd.")
                            || method.startsWith("crash."))) {
                        result = RootClient.call(method, params);
                    }
                    if (result == null) throw new AgentError("UNKNOWN_METHOD", "unknown method: " + method);
                    if (result instanceof JSONObject && ((JSONObject) result).has("via")) via = ((JSONObject) result).getString("via");
                    break;
            }
            if (result instanceof JSONArray) return result;
            JSONObject object = (JSONObject) result;
            if (!object.has("via")) object.put("via", via);
            if (!object.has("degraded")) object.put("degraded", false);
            if (recording && !method.startsWith("recording.")) {
                JSONObject safe = new JSONObject(params.toString());
                safe.remove("token");
                safe.remove("pin");
                if (!recordingIncludeSensitive && (method.equals("ui.setText") || method.equals("ui.appendText"))) {
                    safe.put("value", "***").put("redacted", true);
                } else if (!recordingIncludeSensitive && method.equals("ui.query")) {
                    safe.put("expr", "").put("redacted", true);
                }
                recordingSteps.put(new JSONObject().put("method", method).put("params", safe));
            }
            return object;
        } catch (AgentError error) {
            failure = error.code;
            throw error;
        } catch (Exception error) {
            failure = "INTERNAL";
            throw error;
        } finally {
            Diagnostics.record(method, via, failure, (System.nanoTime() - started) / 1000000);
        }
    }

    private static JSONObject routeUi(String method, JSONObject params) throws Exception {
        if (!params.optString("tier", "auto").equals("a11y") && !method.equals("ui.dump") && !method.equals("ui.find")
                && !method.equals("ui.query") && !method.equals("ui.path")
                && !method.equals("ui.refs") && !method.equals("ui.seek") && !method.equals("ui.flag")
                && !method.equals("ui.unflag") && !method.equals("ui.flags.list")
                && (!method.equals("ui.waitIdle") || service == null)) {
            if (isMutation(method) || method.equals("ui.resolve")) SessionStore.checkWrite(params);
            boolean degraded = false;
            boolean rootExpected = RootClient.available();
            try {
                JSONObject root = RootClient.call(method, params);
                if (root != null) return root;
            } catch (AgentError error) {
                if (params.optString("tier").equals("uiautomation")) throw error;
                if (!error.code.equals("NO_ROOT") && !error.code.equals("SLOT_BUSY") && !error.code.equals("UNAVAILABLE")
                        && !error.code.equals("A11Y_OFF") && !error.code.equals("TIMEOUT")
                        && !error.code.equals("UNKNOWN_METHOD")) throw error;
                degraded = rootExpected;
            }
            if (degraded) {
                JSONObject fallback;
                switch (method) {
                    case "ui.dump": fallback = AccessibilityBackend.dump(params); break;
                    case "ui.find": fallback = SessionStore.find(params); break;
                    case "ui.texts": fallback = AccessibilityBackend.texts(params); break;
                    case "ui.windows": fallback = AccessibilityBackend.windows(); break;
                    case "ui.wait": fallback = AccessibilityBackend.wait(params); break;
                    case "ui.waitIdle": fallback = AccessibilityBackend.idle(params); break;
                    case "ui.scrollUntil": fallback = AccessibilityBackend.scrollUntil(params); break;
                    case "shot": fallback = AccessibilityBackend.shot(params); break;
                    case "screen.diff": fallback = AccessibilityBackend.diff(params); break;
                    case "ui.explain": fallback = SessionStore.explain(params); break;
                    case "ui.highlight": fallback = AccessibilityBackend.highlight(params); break;
                    case "ui.resolve": fallback = SessionStore.resolve(params); break;
                    default: fallback = AccessibilityBackend.action(method, params); break;
                }
                return fallback.put("degraded", true);
            }
        }
        JSONObject result;
        switch (method) {
            case "ui.dump": result = AccessibilityBackend.dump(params); break;
            case "ui.find": result = SessionStore.find(params); break;
            case "ui.texts": result = AccessibilityBackend.texts(params); break;
            case "ui.windows": result = AccessibilityBackend.windows(); break;
            case "ui.click": case "ui.longClick": case "ui.doubleTap": case "ui.gesture":
            case "ui.setText": case "ui.appendText": case "ui.clearText": case "ui.selectAll":
            case "ui.copy": case "ui.paste": case "ui.cut": case "ui.key": case "ui.global":
            case "ui.swipe": case "ui.drag": case "ui.fling":
                result = AccessibilityBackend.action(method, params); break;
            case "shot": result = AccessibilityBackend.shot(params); break;
            case "screen.diff": result = AccessibilityBackend.diff(params); break;
            case "ui.snapshot":
                result = new JSONObject().put("tree", AccessibilityBackend.dump(params)).put("shot", AccessibilityBackend.shot(params));
                break;
            case "ui.wait": result = AccessibilityBackend.wait(params); break;
            case "ui.waitIdle": result = AccessibilityBackend.idle(params); break;
            case "ui.scrollUntil": result = AccessibilityBackend.scrollUntil(params); break;
            case "ui.explain": result = SessionStore.explain(params); break;
            case "ui.highlight": result = AccessibilityBackend.highlight(params); break;
            case "ui.resolve": result = SessionStore.resolve(params); break;
            case "ui.path": result = SessionStore.path(params); break;
            case "ui.refs": result = SessionStore.refs(params); break;
            case "ui.seek": result = SessionStore.seek(params); break;
            case "ui.flag": result = SessionStore.flag(params, false); break;
            case "ui.unflag": result = SessionStore.flag(params, true); break;
            case "ui.flags.list": result = SessionStore.flags(params); break;
            default: throw new AgentError("UNKNOWN_METHOD", "unknown method: " + method);
        }
        if (!result.has("via")) result.put("via", "a11y");
        if (!result.has("degraded")) result.put("degraded", false);
        return result;
    }

    private static JSONObject macro(JSONObject params) throws Exception {
        JSONArray steps = params.getJSONArray("steps");
        if (steps.length() > 100) throw new AgentError("INVALID_PARAMS", "macro exceeds 100 steps");
        JSONArray trace = new JSONArray();
        JSONObject retry = params.optJSONObject("retry");
        int retries = retry == null ? params.optInt("retry") : Math.min(3, retry.optInt("times"));
        long backoff = retry == null ? 0 : Math.min(5000, retry.optLong("backoffMs", retry.optLong("backoff", 0)));
        retries = Math.max(0, Math.min(3, retries));
        long deadline = System.currentTimeMillis() + Math.max(1000, Math.min(300000, params.optLong("maxMs", 60000)));
        for (int i = 0; i < steps.length(); i++) {
            if (System.currentTimeMillis() > deadline) throw new AgentError("TIMEOUT", "macro exceeded its deadline");
            JSONObject step = steps.getJSONObject(i);
            if (step.optBoolean("redacted")) throw new AgentError("INVALID_PARAMS", "redacted recording step cannot be replayed");
            JSONObject output = new JSONObject().put("stage", i).put("method", step.getString("method"));
            for (int attempt = 0; ; attempt++) {
                output.put("attempt", attempt + 1);
                try {
                    output.put("ok", true).put("result", dispatch(step.getString("method"), step.optJSONObject("params")));
                    break;
                } catch (AgentError error) {
                    output.put("ok", false).put("error", error(error.code, error.getMessage()));
                    if (error.retryable && error.resultState.equals("OK") && attempt < retries) {
                        if (backoff > 0) Thread.sleep(backoff);
                        continue;
                    }
                    if (params.optBoolean("stopOnError", true)) return new JSONObject().put("completed", false)
                            .put("failedAt", i).put("trace", trace.put(output));
                    throw error;
                }
            }
            trace.put(output);
        }
        return new JSONObject().put("completed", true).put("trace", trace);
    }

    private static JSONObject backend(boolean selftest, JSONObject params) throws Exception {
        JSONObject a11y = new JSONObject().put("name", "a11y").put("ok", service != null)
                .put("bound", service != null).put("reason", service == null ? "A11Y_OFF" : JSONObject.NULL);
        JSONObject root = RootClient.status();
        JSONArray layers = new JSONArray().put(root.getJSONObject("uiautomation")).put(a11y).put(root.getJSONObject("shell"));
        JSONObject result = new JSONObject().put("layers", layers).put("a11y", a11y)
                .put("root", root.getJSONObject("root")).put("socket", server != null)
                .put("readonly", readonly()).put("bootId", events.bootId())
                .put("eventsPerSec", events.eventsPerSec()).put("eventQueueDepth", events.queueDepth())
                .put("lastFallbackReason", SessionStore.lastFallbackReason() == null
                        ? JSONObject.NULL : SessionStore.lastFallbackReason())
                .put("cooldownMs", Diagnostics.cooldownRemaining());
        if (!selftest) return result;
        JSONArray checks = new JSONArray();
        checks.put(check("socket", server != null, server != null ? "" : "SOCKET_OFF"));
        checks.put(check("token", !token(context).isEmpty(), "TOKEN_INVALID"));
        checks.put(check("a11y", service != null, service == null ? "A11Y_OFF" : ""));
        if (service != null) {
            long started = System.nanoTime();
            try {
                JSONObject analyzed = SessionStore.analyze(new JSONObject());
                checks.put(new JSONObject().put("item", "dump").put("ok", true)
                        .put("ms", (System.nanoTime() - started) / 1000000).put("nodeCount", analyzed.getInt("nodeCount")));
            } catch (Exception e) {
                checks.put(check("dump", false, e instanceof AgentError ? ((AgentError) e).code : "INTERNAL"));
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    JSONObject shot = AccessibilityBackend.shot(new JSONObject());
                    checks.put(new JSONObject().put("item", "shot").put("ok", true).put("width", shot.getInt("width")));
                } catch (Exception e) {
                    checks.put(check("shot", false, e instanceof AgentError ? ((AgentError) e).code : "INTERNAL"));
                }
            }
        }
        checks.put(new JSONObject().put("item", "input").put("ok", service != null)
                .put("executed", false).put("reason", "selftest never injects input"));
        return result.put("checks", checks).put("via", "app").put("degraded", false);
    }

    private static JSONObject check(String item, boolean ok, String error) throws Exception {
        boolean valid = ok && (error == null || error.isEmpty());
        return new JSONObject().put("item", item).put("ok", valid)
                .put("err", valid ? "" : error == null || error.isEmpty() ? "FAILED" : error);
    }

    private static boolean readonly() {
        return context != null && context.getSharedPreferences("agent", 0).getBoolean("readonly", false);
    }

    static JSONObject error(String code, String message) {
        try {
            return new JSONObject().put("code", code).put("message", message).put("detail", new JSONObject());
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject stopRecording() throws Exception {
        recording = false;
        File file = new File(context.getFilesDir(), "recording.json");
        File temp = new File(context.getFilesDir(), "recording-" + UUID.randomUUID() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(new JSONObject().put("version", 1).put("steps", recordingSteps).toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        file.setReadable(false, false);
        file.setReadable(true, true);
        file.setWritable(false, false);
        file.setWritable(true, true);
        return new JSONObject().put("recording", false).put("steps", recordingSteps);
    }

    private static String readRecording(File file) throws Exception {
        if (file.length() > 1024 * 1024) throw new AgentError("INVALID_PARAMS", "recording is too large");
        try (FileInputStream input = new FileInputStream(file)) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
            if (!pkg.equals("com.android.systemui") && (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED || pkg.equals(events.foreground()))) {
                events.uiChanged(pkg);
            }
            SessionStore.event(event);
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                String previous = events.foreground();
                events.foreground(pkg);
                if (!previous.equals(pkg)) events.publish("app.foreground", new JSONObject().put("package", pkg));
            }
            long now = System.currentTimeMillis();
            if (now - lastUiEvent >= 100) {
                lastUiEvent = now;
                events.publish("ui.changed", new JSONObject().put("package", pkg).put("windowId", event.getWindowId())
                        .put("eventType", event.getEventType()));
            }
            if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                events.publish("toast", new JSONObject().put("package", pkg).put("text", event.getText().toString()));
            }
        } catch (Exception e) {
            Diagnostics.record("ui.event", "a11y", "EVENT_FAILED", 0);
        }
    }

    static void onNotification(String type, String key, String pkg, long posted) {
        try {
            events.publish(type, new JSONObject().put("key", key).put("package", pkg).put("posted", posted));
        } catch (Exception ignored) {
        }
    }

    private static String canonical(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Iterator<String> it = object.keys(); it.hasNext();) {
                String key = it.next();
                sorted.put(key, object.get(key));
            }
            StringBuilder result = new StringBuilder("{");
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (result.length() > 1) result.append(',');
                result.append(JSONObject.quote(entry.getKey())).append(':').append(canonical(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            StringBuilder result = new StringBuilder("[");
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) result.append(',');
                result.append(canonical(array.get(i)));
            }
            return result.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }

    private static String digest(String method, JSONObject params) throws Exception {
        return SessionNode.hash(method + "\n" + params.optString("sessionId") + "\n" + canonical(params));
    }

    private static void pruneIdempotency() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Idempotent>> iterator = IDEMPOTENT.entrySet().iterator();
        while (iterator.hasNext()) {
            Idempotent value = iterator.next().getValue();
            if (!value.pending && value.expires < now) iterator.remove();
        }
        if (IDEMPOTENT.size() >= 100) {
            iterator = IDEMPOTENT.entrySet().iterator();
            while (iterator.hasNext()) {
                if (!iterator.next().getValue().pending) {
                    iterator.remove();
                    break;
                }
            }
        }
    }

    private static final class Idempotent {
        final String digest;
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        volatile Object result;
        volatile String error;
        volatile String errorMessage;
        volatile boolean pending = true;
        volatile long expires = System.currentTimeMillis() + 120000;

        Idempotent(String digest) {
            this.digest = digest;
        }

        void complete(Object value) throws org.json.JSONException {
            result = value instanceof JSONObject ? new JSONObject(value.toString()) : value;
            pending = false;
            expires = System.currentTimeMillis() + 30000;
            done.countDown();
        }

        void fail(AgentError failure) {
            error = failure.code;
            errorMessage = failure.getMessage();
            pending = false;
            expires = System.currentTimeMillis() + 30000;
            done.countDown();
        }
    }

    private static final class Connection implements Runnable {
        final Socket socket;
        volatile PrintWriter output;
        volatile EventBus.Subscription subscription;
        volatile Object subscriptionId;
        volatile Socket rootEvents;
        volatile boolean closed;

        Connection(Socket socket) {
            this.socket = socket;
        }

        @Override public void run() {
            try {
                socket.setSoTimeout(15000);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                output = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                while (!closed) {
                    String line = readBoundedLine(input);
                    if (line == null) break;
                    JSONObject request = new JSONObject(line);
                    Object id = request.opt("id") == null ? 0 : request.opt("id");
                    long started = System.nanoTime();
                    String expected = token(context);
                    boolean authorized = !expected.isEmpty() && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                            request.optString("token").getBytes(StandardCharsets.UTF_8));
                    if (!authorized) {
                        send(new JSONObject().put("id", id).put("ok", false).put("error", error("UNAUTHORIZED", "unauthorized")));
                        break;
                    }
                    String method = request.optString("method");
                    JSONObject params = request.optJSONObject("params") == null ? new JSONObject() : request.getJSONObject("params");
                    if (method.equals("events.subscribe")) {
                        subscribe(id, params);
                        continue;
                    }
                    if (method.equals("events.unsubscribe")) {
                        unsubscribe();
                        send(new JSONObject().put("id", id).put("ok", true).put("result", new JSONObject().put("unsubscribed", true)));
                        continue;
                    }
                    try {
                        Object result = dispatch(method, params);
                        JSONObject metadata = result instanceof JSONObject ? (JSONObject) result : null;
                        send(new JSONObject().put("id", id).put("ok", true)
                                .put("via", metadata == null ? "app" : metadata.optString("via", "app"))
                                .put("degraded", metadata != null && metadata.optBoolean("degraded"))
                                .put("tookMs", (System.nanoTime() - started) / 1000000)
                                .put("result", result));
                        if (method.equals("security.tokenRotate")) disconnectExcept(this);
                    } catch (AgentError error) {
                        send(new JSONObject().put("id", id).put("ok", false).put("via", error.via)
                                .put("resultState", error.resultState).put("replayed", error.replayed)
                                .put("error", new JSONObject().put("code", error.code).put("message", error.getMessage())
                                        .put("detail", error.detail)));
                    } catch (IllegalArgumentException error) {
                        send(new JSONObject().put("id", id).put("ok", false).put("error", error("INVALID_PARAMS", error.getMessage())));
                    } catch (Exception error) {
                        send(new JSONObject().put("id", id).put("ok", false).put("error", error("INTERNAL", "internal error")));
                    }
                }
            } catch (Exception ignored) {
            } finally {
                close();
            }
        }

        void subscribe(Object id, JSONObject params) throws Exception {
            unsubscribe();
            subscriptionId = id;
            Set<String> types = new HashSet<>();
            JSONArray requested = params.optJSONArray("types");
            if (requested != null) for (int i = 0; i < requested.length(); i++) types.add(requested.getString(i));
            Set<String> packages = new HashSet<>();
            JSONArray requestedPackages = params.optJSONArray("packages");
            if (requestedPackages != null) {
                for (int i = 0; i < requestedPackages.length(); i++) packages.add(requestedPackages.getString(i));
            }
            long since = params.optLong("since", 0);
            String processId = params.optString("processId", params.optString("bootId"));
            EventBus.Subscription created = events.subscription(types, packages);
            EventBus.Replay replay = events.subscribe(created, since, processId, types, packages);
            subscription = created;
            socket.setSoTimeout(0);
            send(new JSONObject().put("id", id).put("ok", true).put("result", new JSONObject()
                    .put("subscribed", true).put("bootId", events.bootId()).put("processId", events.bootId())
                    .put("baseSeq", replay.baseSeq).put("resetRequired", replay.resetRequired)));
            for (int i = 0; i < replay.events.length(); i++) event(replay.events.getJSONObject(i));
            if (types.contains("crash.anr")) {
                try {
                    Socket root = RootClient.eventSocket(new JSONObject().put("types", new JSONArray().put("crash.anr")));
                    rootEvents = root;
                    EVENT_WRITERS.execute(() -> {
                        try {
                            BufferedReader input = new BufferedReader(new InputStreamReader(root.getInputStream(), StandardCharsets.UTF_8));
                            while (!closed && rootEvents == root) {
                                String line = readBoundedLine(input);
                                if (line == null) break;
                                JSONObject event = new JSONObject(line);
                                JSONObject data = event.optJSONObject("data") == null ? new JSONObject() : event.getJSONObject("data");
                                data.put("rootSeq", event.optLong("seq"));
                                events.publish(event.optString("event", "crash.anr"), data);
                            }
                        } catch (Exception ignored) {
                        } finally {
                            try {
                                root.close();
                            } catch (Exception ignored) {
                            }
                        }
                    });
                } catch (AgentError error) {
                    event(new JSONObject().put("event", "events.backend")
                            .put("data", new JSONObject().put("reason", error.code)));
                }
            }
            EVENT_WRITERS.execute(() -> {
                try {
                    while (!closed && subscription == created) {
                        JSONObject event = created.take();
                        if (event == null) break;
                        if (types.isEmpty() || types.contains(event.optString("event"))) event(event);
                    }
                } catch (Exception ignored) {
                }
            });
        }

        void event(JSONObject source) throws Exception {
            send(new JSONObject(source.toString()));
        }

        void heartbeat() {
            if (subscription == null || closed) return;
            try {
                send(new JSONObject().put("event", "events.heartbeat").put("seq", events.sequence())
                        .put("processId", events.bootId()).put("time", System.currentTimeMillis()));
            } catch (Exception ignored) {
            }
        }

        void unsubscribe() {
            EventBus.Subscription old = subscription;
            subscription = null;
            if (old != null) old.close();
            Socket root = rootEvents;
            rootEvents = null;
            if (root != null) {
                try {
                    root.close();
                } catch (Exception ignored) {
                }
            }
            try {
                socket.setSoTimeout(15000);
            } catch (Exception ignored) {
            }
        }

        synchronized void send(JSONObject value) {
            if (closed || output == null) return;
            String text = value.toString();
            if (text.length() > MAX_RESPONSE) {
                try {
                    text = new JSONObject().put("ok", false).put("error", error("OUTPUT_TOO_LARGE", "response exceeds limit")).toString();
                } catch (org.json.JSONException e) {
                    text = "{}";
                }
            }
            output.println(text);
            if (output.checkError()) close();
        }

        void close() {
            if (closed) return;
            closed = true;
            unsubscribe();
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            CONNECTIONS.remove(this);
        }
    }

    private static String readBoundedLine(BufferedReader input) throws Exception {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') break;
            if (c == '\r') continue;
            if (line.length() >= MAX_LINE) return null;
            line.append((char) c);
        }
        return line.toString();
    }

    private static void disconnectExcept(Connection current) {
        for (Connection connection : CONNECTIONS) if (connection != current) connection.close();
    }
}
