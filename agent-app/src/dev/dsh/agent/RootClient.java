package dev.dsh.agent;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

final class RootClient {
    private static final int PORT = 17322;
    private static long lastCheck;
    private static volatile boolean available;
    private static long failures;
    private static long cooldownUntil;
    private static long id;
    private static volatile String failureReason = "NO_ROOT";
    private static final java.util.concurrent.ExecutorService PROBE = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static boolean probeQueued;

    private RootClient() {
    }

    static synchronized boolean available() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < 2000) return available;
        if (now < cooldownUntil) return false;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            if (!probeQueued) {
                probeQueued = true;
                lastCheck = now;
                PROBE.execute(RootClient::probe);
            }
            return available;
        }
        probe();
        return available;
    }

    private static void probe() {
        try {
            JSONObject result = request("root.ping", new JSONObject());
            synchronized (RootClient.class) {
                available = result.optBoolean("root");
                failures = 0;
                failureReason = "";
                lastCheck = System.currentTimeMillis();
            }
        } catch (Exception e) {
            synchronized (RootClient.class) {
                available = false;
                failureReason = e instanceof AgentError ? ((AgentError) e).code : e.getClass().getSimpleName();
                if (++failures >= 3) cooldownUntil = System.currentTimeMillis() + 30000;
                lastCheck = System.currentTimeMillis();
            }
        } finally {
            synchronized (RootClient.class) {
                probeQueued = false;
            }
        }
    }

    private static String noRootReason() {
        return failureReason.isEmpty() ? "NO_ROOT" : failureReason;
    }

    static JSONObject status() throws Exception {
        if (!available()) {
            return new JSONObject().put("root", new JSONObject().put("ok", false).put("reason", noRootReason()))
                    .put("uiautomation", new JSONObject().put("name", "uiautomation").put("ok", false).put("reason", noRootReason()))
                    .put("shell", new JSONObject().put("name", "shell").put("ok", false).put("reason", noRootReason()));
        }
        try {
            JSONObject result = call("backend.status", new JSONObject());
            if (result.has("layers")) {
                JSONObject formatted = new JSONObject().put("root", result.getJSONObject("root"));
                for (int i = 0; i < result.getJSONArray("layers").length(); i++) {
                    JSONObject layer = result.getJSONArray("layers").getJSONObject(i);
                    if (layer.optString("name").equals("uiautomation")) formatted.put("uiautomation", layer);
                    if (layer.optString("name").equals("shell")) formatted.put("shell", layer);
                }
                return formatted;
            }
            return result;
        } catch (AgentError e) {
            return new JSONObject().put("root", new JSONObject().put("ok", false).put("reason", e.code))
                    .put("uiautomation", new JSONObject().put("name", "uiautomation").put("ok", false).put("reason", e.code))
                    .put("shell", new JSONObject().put("name", "shell").put("ok", false).put("reason", e.code));
        }
    }

    static JSONObject call(String method, JSONObject params) throws Exception {
        if (!available()) throw new AgentError("NO_ROOT", "root helper is not running", "none", false);
        return request(method, params == null ? new JSONObject() : params);
    }

    private static JSONObject request(String method, JSONObject params) throws Exception {
        String token = readToken();
        long started = System.nanoTime();
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            int timeout = method.equals("screen.record") ? 200000
                    : method.equals("shell.exec") || method.startsWith("prep.") ? 125000
                    : method.equals("root.ping") ? 3000 : method.equals("backend.status") ? 5000 : 15000;
            socket.setSoTimeout(timeout);
            JSONObject request = new JSONObject().put("token", token).put("id", ++id).put("method", method).put("params", params);
            OutputStreamWriter output = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            output.write(request.toString() + "\n");
            output.flush();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = boundedLine(input);
            if (line == null) throw new AgentError("TIMEOUT", "root helper returned no response", "shell", true);
            JSONObject response = new JSONObject(line);
            if (!response.optBoolean("ok")) {
                JSONObject error = response.optJSONObject("error");
                String code = error == null ? "INTERNAL" : error.optString("code", "INTERNAL");
                String message = error == null ? "root helper error" : error.optString("message", "root helper error");
                AgentError failure = new AgentError(code, message, "shell", code.equals("TIMEOUT") || code.equals("SLOT_BUSY"));
                if (error != null && error.optJSONObject("detail") != null) {
                    JSONObject detail = error.getJSONObject("detail");
                    for (Iterator<String> it = detail.keys(); it.hasNext();) {
                        String key = it.next();
                        failure.detail(key, detail.get(key));
                    }
                }
                throw failure;
            }
            JSONObject result = response.getJSONObject("result");
            if (!result.has("via")) result.put("via", "shell");
            if (!result.has("tookMs")) result.put("tookMs", (System.nanoTime() - started) / 1000000);
            if (!result.has("degraded") && (method.startsWith("ui.") || method.equals("shot"))) {
                result.put("degraded", result.optString("via").equals("shell"));
            }
            return result;
        } catch (java.io.IOException e) {
            available = false;
            failureReason = e.getClass().getSimpleName();
            throw new AgentError("NO_ROOT", "root helper connection failed", "none", false);
        }
    }

    static Socket eventSocket(JSONObject params) throws Exception {
        if (!available()) throw new AgentError("NO_ROOT", "root event stream is unavailable");
        Socket socket = new Socket("127.0.0.1", PORT);
        try {
            socket.setSoTimeout(15000);
            JSONObject request = new JSONObject().put("token", readToken()).put("id", ++id)
                    .put("method", "events.subscribe").put("params", params);
            OutputStreamWriter output = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            output.write(request.toString() + "\n");
            output.flush();
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line = boundedLine(input);
            if (line == null) throw new AgentError("TIMEOUT", "root event stream returned no acknowledgement");
            JSONObject response = new JSONObject(line);
            if (!response.optBoolean("ok")) {
                JSONObject error = response.optJSONObject("error");
                throw new AgentError(error == null ? "INTERNAL" : error.optString("code"), "root event subscription failed");
            }
            socket.setSoTimeout(0);
            return socket;
        } catch (Exception e) {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            if (e instanceof AgentError) throw e;
            throw new AgentError("NO_ROOT", "root event stream is unavailable");
        }
    }

    private static String readToken() throws Exception {
        try (java.io.FileInputStream input = new java.io.FileInputStream(AgentServer.tokenFile(AgentServer.context()))) {
            byte[] bytes = new byte[33];
            int count = input.read(bytes);
            String value = new String(bytes, 0, Math.max(0, count), StandardCharsets.UTF_8).trim();
            if (!value.matches("[0-9a-f]{32}")) throw new AgentError("UNAUTHORIZED", "invalid agent token");
            return value;
        }
    }

    private static String boundedLine(BufferedReader input) throws Exception {
        StringBuilder result = new StringBuilder();
        int c;
        while ((c = input.read()) != -1) {
            if (c == '\n') break;
            if (c == '\r') continue;
            if (result.length() >= 16 * 1024 * 1024) throw new AgentError("OUTPUT_TOO_LARGE", "root helper response is too large");
            result.append((char) c);
        }
        return result.toString();
    }
}
