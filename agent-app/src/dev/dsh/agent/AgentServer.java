package dev.dsh.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

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
import java.util.List;
import java.util.UUID;

/**
 * Loopback JSON bridge. The socket is not exposed to the network; the token
 * file lives in the app private directory and is readable by root only.
 */
public final class AgentServer {
    private static final int PORT = 17321;
    private static final int MAX_LINE = 1024 * 1024;
    private static volatile ServerSocket server;
    private static volatile Thread thread;
    private static volatile AccessibilityService service;
    private static volatile Context context;

    private AgentServer() {
    }

    static File tokenFile(Context ctx) {
        return new File(ctx.getFilesDir(), "token");
    }

    static synchronized void start(Context ctx, AccessibilityService svc) {
        context = ctx.getApplicationContext();
        service = svc;
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(AgentServer::acceptLoop, "dsh-agent-server");
        thread.setDaemon(true);
        thread.start();
    }

    static synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
        thread = null;
        service = null;
    }

    private static String token(Context ctx) {
        File f = tokenFile(ctx);
        try {
            if (f.isFile()) return read(f).trim();
            String value = UUID.randomUUID().toString().replace("-", "");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(value.getBytes(StandardCharsets.UTF_8));
            }
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
            return value;
        } catch (Exception e) {
            return "";
        }
    }

    private static String read(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 4096)];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8);
        }
    }

    private static void acceptLoop() {
        try {
            ServerSocket s = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
            s.setReuseAddress(true);
            server = s;
            while (!s.isClosed()) {
                try (Socket client = s.accept()) {
                    client.setSoTimeout(10000);
                    handle(client);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void handle(Socket client) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String line = in.readLine();
        if (line == null || line.length() > MAX_LINE) return;
        JSONObject response;
        try {
            JSONObject req = new JSONObject(line);
            String expected = token(context);
            if (expected.isEmpty() || !expected.equals(req.optString("token"))) {
                response = error("unauthorized");
            } else {
                response = dispatch(req.optString("method"), req.optJSONObject("params"), req.optInt("id", 0));
            }
        } catch (Exception e) {
            response = error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        PrintWriter out = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true);
        out.println(response.toString());
    }

    private static JSONObject error(String message) throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", false);
        out.put("error", message == null ? "error" : message);
        return out;
    }

    private static JSONObject ok(Object result) throws Exception {
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("result", result == null ? JSONObject.NULL : result);
        return out;
    }

    private static JSONObject dispatch(String method, JSONObject p, int id) throws Exception {
        if (p == null) p = new JSONObject();
        AccessibilityService svc = service;
        switch (method == null ? "" : method) {
            case "ping":
                return ok(new JSONObject().put("service", svc != null).put("port", PORT));
            case "ui.dump":
                return ok(dump(svc));
            case "ui.click":
                return ok(new JSONObject().put("clicked", click(svc, p)));
            case "ui.setText":
                return ok(new JSONObject().put("set", setText(svc, p)));
            case "ui.swipe":
                return ok(new JSONObject().put("swiped", swipe(svc, p)));
            case "notifications.list":
                return ok(NotificationRelay.snapshot());
            case "notifications.dismiss":
                return ok(new JSONObject().put("dismissed", NotificationRelay.dismiss(p.optString("key"))));
            case "clipboard.get":
                return ok(clipboardGet());
            case "clipboard.set":
                return ok(clipboardSet(p.optString("text")));
            case "package.list":
                return ok(packages());
            case "activity.start":
                return ok(startActivity(p));
            case "activity.forceStop":
                return ok(new JSONObject().put("supported", false).put("reason", "requires system permission"));
            case "package.clear":
                return ok(new JSONObject().put("supported", false).put("reason", "requires system permission"));
            case "package.install":
                return ok(new JSONObject().put("supported", false).put("reason", "use adb/pm session outside this bridge"));
            default:
                return error("unknown method: " + method);
        }
    }

    private static JSONObject dump(AccessibilityService svc) throws Exception {
        JSONObject out = new JSONObject();
        if (svc == null) {
            out.put("available", false);
            out.put("reason", "accessibility service not connected");
            return out;
        }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) {
            out.put("available", false);
            out.put("reason", "no active window");
            return out;
        }
        JSONArray nodes = new JSONArray();
        int[] budget = {2000};
        walk(root, nodes, 0, budget);
        out.put("available", true);
        out.put("package", root.getPackageName());
        out.put("nodes", nodes);
        return out;
    }

    private static void walk(AccessibilityNodeInfo n, JSONArray out, int depth, int[] budget) throws Exception {
        if (n == null || depth > 30 || budget[0]-- <= 0) return;
        JSONObject item = new JSONObject();
        item.put("text", str(n.getText()));
        item.put("desc", str(n.getContentDescription()));
        item.put("viewId", n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName());
        item.put("class", str(n.getClassName()));
        item.put("package", str(n.getPackageName()));
        item.put("clickable", n.isClickable());
        item.put("editable", n.isEditable());
        item.put("enabled", n.isEnabled());
        item.put("checked", n.isChecked());
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        item.put("bounds", new JSONArray().put(r.left).put(r.top).put(r.right).put(r.bottom));
        out.put(item);
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), out, depth + 1, budget);
    }

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString();
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, JSONObject p) {
        if (root == null) return null;
        String text = p.optString("text", "");
        String viewId = p.optString("viewId", "");
        return find(root, text, viewId, null);
    }

    private static AccessibilityNodeInfo find(AccessibilityNodeInfo n, String text, String viewId, int[] seen) {
        if (n == null) return null;
        String nodeText = str(n.getText());
        String desc = str(n.getContentDescription());
        String id = n.getViewIdResourceName() == null ? "" : n.getViewIdResourceName();
        if ((!text.isEmpty() && (text.equals(nodeText) || text.equals(desc) || nodeText.contains(text)))
                || (!viewId.isEmpty() && viewId.equals(id))) {
            return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = find(n.getChild(i), text, viewId, seen);
            if (hit != null) return hit;
        }
        return null;
    }

    private static boolean click(AccessibilityService svc, JSONObject p) {
        if (svc == null) return false;
        AccessibilityNodeInfo node = find(svc.getRootInActiveWindow(), p);
        if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        int x = p.optInt("x", -1);
        int y = p.optInt("y", -1);
        if (x >= 0 && y >= 0) return tap(svc, x, y);
        return false;
    }

    private static boolean setText(AccessibilityService svc, JSONObject p) {
        if (svc == null) return false;
        AccessibilityNodeInfo node = find(svc.getRootInActiveWindow(), p);
        if (node == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p.optString("text", ""));
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private static boolean tap(AccessibilityService svc, int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 60);
        return svc.dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private static boolean swipe(AccessibilityService svc, JSONObject p) {
        if (svc == null) return false;
        Path path = new Path();
        path.moveTo(p.optInt("x1", 0), p.optInt("y1", 0));
        path.lineTo(p.optInt("x2", 0), p.optInt("y2", 0));
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, p.optInt("duration", 300));
        return svc.dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private static JSONObject clipboardGet() throws Exception {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) return new JSONObject().put("text", "");
        ClipData clip = cm.getPrimaryClip();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < clip.getItemCount(); i++) {
            CharSequence item = clip.getItemAt(i).coerceToText(context);
            if (item != null) text.append(item);
        }
        return new JSONObject().put("text", text.toString());
    }

    private static JSONObject clipboardSet(String text) throws Exception {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return new JSONObject().put("set", false);
        cm.setPrimaryClip(ClipData.newPlainText("DSH", text));
        return new JSONObject().put("set", true);
    }

    private static JSONArray packages() throws Exception {
        JSONArray out = new JSONArray();
        List<PackageInfo> list = context.getPackageManager().getInstalledPackages(0);
        int limit = Math.min(list.size(), 1000);
        for (int i = 0; i < limit; i++) {
            PackageInfo info = list.get(i);
            out.put(new JSONObject().put("package", info.packageName).put("version", info.versionName == null ? "" : info.versionName));
        }
        return out;
    }

    private static JSONObject startActivity(JSONObject p) throws Exception {
        Intent intent = new Intent();
        String action = p.optString("action", "");
        String pkg = p.optString("package", "");
        String cls = p.optString("activity", "");
        if (!action.isEmpty()) intent.setAction(action);
        if (!pkg.isEmpty() && !cls.isEmpty()) intent.setClassName(pkg, cls);
        else if (!pkg.isEmpty()) intent.setPackage(pkg);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return new JSONObject().put("started", true);
    }
}
