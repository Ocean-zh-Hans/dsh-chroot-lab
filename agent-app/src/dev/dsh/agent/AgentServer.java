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

    private static JSONObject ping() throws Exception {
        return new JSONObject().put("ok", true).put("via", "app")
                .put("version", AgentMethods.VERSION).put("apiVersion", AgentMethods.API_VERSION)
                .put("buildTime", AgentMethods.BUILD_TIME).put("service", service != null).put("port", PORT)
                .put("readonly", readonly());
    }

    private static JSONObject selftest() throws Exception {
        JSONArray checks = new JSONArray();
        checks.put(check("a11y", service != null, service != null ? "" : "accessibility service is not connected"));
        boolean root = RootClient.available();
        checks.put(check("root", root, root ? "" : "root helper is not connected"));
        boolean engine = false;
        String failure = "";
        try {
            UiEngine.get().call("ui.refresh", new JSONObject());
            engine = true;
        } catch (Throwable error) {
            failure = String.valueOf(error.getMessage());
        }
        checks.put(check("engine", engine, failure));
        return new JSONObject().put("ok", service != null && engine).put("via", "app").put("checks", checks);
    }

    private static final Map<String, JSONObject> SESSIONS = new LinkedHashMap<>();
    private static String currentSession = "";

    private static Object dispatchInternal(String method, JSONObject params) throws Exception {
        long started = System.nanoTime();
        String via = "app";
        String failure = "";
        try {
            if (EngineRouter.handles(method)) {
                Object result = EngineRouter.dispatch(method, withSession(params));
                if (result instanceof JSONObject) via = ((JSONObject) result).optString("via", "a11y");
                return result;
            }
            switch (method) {
                case "ping": return ping();
                case "session.open": return sessionOpen(params);
                case "session.list": return sessionList();
                case "session.use": return sessionUse(params);
                case "session.current": case "session.status": return sessionCurrent();
                case "session.close": case "session.drop": return sessionClose(params);
                case "policy.get": return policyGet();
                case "policy.set": return policySet(params);
                case "dev.log.tail": return new JSONObject().put("ok", true).put("via", via)
                        .put("entries", Diagnostics.tail(params.optInt("n", 50)));
                case "dev.log.clear": Diagnostics.clear(); return new JSONObject().put("ok", true).put("cleared", true);
                case "dev.selftest": return selftest();
                default:
                    throw new AgentError("BAD_REQUEST", "unknown method: " + method)
                            .detail("hint", "v3: ui.view/ui.nodes/ui.diff/ui.wait/ui.shot/ui.act/ui.batch/ui.fill/"
                                    + "ui.scrollUntil/ui.press/ui.ime/sys.status/session.*/events.*/policy.*/dev.*");
            }
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

    /** session 只承载"默认后端 + 计数"；页面状态由引擎按需抓取，不做跨请求缓存。 */
    private static JSONObject withSession(JSONObject params) throws Exception {
        String id = params.optString("session", "");
        JSONObject session = null;
        if (!id.isEmpty()) {
            synchronized (SESSIONS) {
                session = SESSIONS.get(id);
            }
            if (session == null) throw new AgentError("NOT_FOUND", "no such session: " + id).detail("sessions", sessionIds());
        } else if (!currentSession.isEmpty()) {
            synchronized (SESSIONS) {
                session = SESSIONS.get(currentSession);
            }
        }
        if (session != null) {
            session.put("calls", session.optInt("calls") + 1).put("lastUsedAt", System.currentTimeMillis());
            if (!params.has("backend")) {
                String backend = session.optString("backend", "auto");
                if (!backend.isEmpty() && !backend.equals("auto")) params.put("backend", backend);
            }
        }
        return params;
    }

    private static JSONArray sessionIds() {
        JSONArray ids = new JSONArray();
        synchronized (SESSIONS) {
            for (String id : SESSIONS.keySet()) ids.put(id);
        }
        return ids;
    }

    private static JSONObject sessionOpen(JSONObject params) throws Exception {
        String id = "s-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        JSONObject session = new JSONObject()
                .put("id", id)
                .put("name", params.optString("name", ""))
                .put("backend", params.optString("backend", "auto"))
                .put("createdAt", System.currentTimeMillis())
                .put("calls", 0);
        synchronized (SESSIONS) {
            if (SESSIONS.size() >= 8) throw new AgentError("UNAVAILABLE", "at most 8 sessions; close one first");
            SESSIONS.put(id, session);
        }
        currentSession = id;
        return new JSONObject().put("ok", true).put("via", "app").put("session", new JSONObject(session.toString()))
                .put("current", id);
    }

    private static JSONObject sessionList() throws Exception {
        JSONArray items = new JSONArray();
        synchronized (SESSIONS) {
            for (JSONObject session : SESSIONS.values()) items.put(new JSONObject(session.toString()));
        }
        return new JSONObject().put("ok", true).put("via", "app").put("sessions", items).put("current", currentSession);
    }

    private static JSONObject sessionUse(JSONObject params) throws Exception {
        String id = params.optString("id", params.optString("session", ""));
        synchronized (SESSIONS) {
            if (SESSIONS.get(id) == null) throw new AgentError("NOT_FOUND", "no such session: " + id).detail("sessions", sessionIds());
        }
        currentSession = id;
        return new JSONObject().put("ok", true).put("via", "app").put("current", id);
    }

    private static JSONObject sessionCurrent() throws Exception {
        JSONObject session = null;
        synchronized (SESSIONS) {
            if (!currentSession.isEmpty()) session = SESSIONS.get(currentSession);
        }
        return new JSONObject().put("ok", true).put("via", "app").put("current", currentSession)
                .put("session", session == null ? JSONObject.NULL : new JSONObject(session.toString()));
    }

    private static JSONObject sessionClose(JSONObject params) throws Exception {
        String id = params.optString("id", params.optString("session", currentSession));
        boolean removed;
        synchronized (SESSIONS) {
            removed = SESSIONS.remove(id) != null;
        }
        if (!removed) throw new AgentError("NOT_FOUND", "no such session: " + id);
        if (id.equals(currentSession)) currentSession = "";
        return new JSONObject().put("ok", true).put("via", "app").put("closed", id);
    }

    private static JSONObject policyGet() throws Exception {
        JSONObject policy = new JSONObject()
                .put("backend", context.getSharedPreferences("agent", 0).getString("backend", "auto"))
                .put("readonly", readonly());
        return new JSONObject().put("ok", true).put("via", "app").put("policy", policy);
    }

    private static JSONObject policySet(JSONObject params) throws Exception {
        if (params.has("readonly")) {
            boolean readonly = params.optBoolean("readonly", true);
            if (!readonly) AgentError.confirm(params);
            context.getSharedPreferences("agent", 0).edit().putBoolean("readonly", readonly).apply();
        }
        if (params.has("backend")) {
            String backend = params.optString("backend", "auto");
            if (!backend.equals("auto") && !backend.equals("a11y") && !backend.equals("uia") && !backend.equals("shell")) {
                throw new AgentError("BAD_REQUEST", "backend must be auto|a11y|uia|shell")
                        .detail("alternatives", new JSONArray().put("auto").put("a11y").put("uia").put("shell"));
            }
            context.getSharedPreferences("agent", 0).edit().putString("backend", backend).apply();
        }
        return policyGet();
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

    static void onAccessibilityEvent(AccessibilityEvent event) {
        UiClock.INSTANCE.note();
        try {
            String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
            if (!pkg.equals("com.android.systemui") && (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED || pkg.equals(events.foreground()))) {
                events.uiChanged(pkg);
            }
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
        return sha256(method + "\n" + params.optString("session") + "\n" + canonical(params));
    }

    private static String sha256(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b));
        return out.toString();
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
