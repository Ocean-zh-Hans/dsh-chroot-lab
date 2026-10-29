package dev.dsh.agent;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class AccessibilityBackend {
    private static final Object SHOT_LOCK = new Object();
    private static Bitmap previousShot;
    private static long previousShotTime;
    private static JSONObject cachedShot;
    private static long lastShotAt;

    private AccessibilityBackend() {
    }

    private static AccessibilityService service() throws AgentError {
        AccessibilityService service = AgentServer.accessibility();
        if (service == null) throw new AgentError("A11Y_OFF", "accessibility service is not connected", "a11y", false);
        return service;
    }

    static JSONObject dump(JSONObject params) throws Exception {
        JSONObject info = SessionStore.analyze(params);
        JSONObject result = SessionStore.legacyDump(new JSONObject().put("sessionId", info.getString("sessionId")));
        result.put("available", true).put("timestamp", System.currentTimeMillis())
                .put("snapshotId", info.getString("sessionId")).put("sessionId", info.getString("sessionId"))
                .put("epoch", info.getInt("epoch")).put("package", info.getString("pkg"));
        return result;
    }

    static JSONObject texts(JSONObject params) throws Exception {
        JSONArray nodes = dump(params).getJSONArray("nodes");
        JSONArray texts = new JSONArray();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            if (node.optBoolean("visible") && !node.optString("text").isEmpty()) {
                texts.put(new JSONObject().put("text", node.getString("text")).put("bounds", node.getJSONArray("bounds"))
                        .put("nodeId", node.getString("nodeId")));
            }
        }
        return new JSONObject().put("texts", texts).put("count", texts.length());
    }

    static JSONObject windows() throws Exception {
        JSONArray result = new JSONArray();
        for (AccessibilityWindowInfo window : service().getWindows()) {
            Rect bounds = new Rect();
            window.getBoundsInScreen(bounds);
            AccessibilityNodeInfo root = window.getRoot();
            String pkg = root == null ? "" : UiModel.string(root.getPackageName());
            if (root != null) root.recycle();
            result.put(new JSONObject().put("id", window.getId()).put("displayId", window.getDisplayId())
                    .put("type", window.getType()).put("package", pkg)
                    .put("bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
                    .put("focused", window.isFocused()).put("active", window.isActive()));
        }
        return new JSONObject().put("windows", result);
    }

    static JSONObject action(String method, JSONObject params) throws Exception {
        if (method.equals("ui.global")) return global(params.optString("action"));
        if (method.equals("ui.key")) return key(params);
        if (method.equals("ui.gesture")) {
            if (params.optBoolean("dryRun")) return new JSONObject().put("executed", false).put("paths", params.optJSONArray("paths"));
            return gesture(params);
        }
        if (method.equals("ui.swipe") || method.equals("ui.drag") || method.equals("ui.fling")) return swipe(method, params);
        if (params.has("x") && params.has("y") && !params.has("text") && !params.has("viewId")
                && !params.has("selector") && !params.has("nodeId") && !params.has("matchKey") && !params.has("flag")) {
            float x = (float) params.getDouble("x"), y = (float) params.getDouble("y");
            verifyPoint(x, y);
            if (params.optBoolean("dryRun")) return new JSONObject().put("executed", false)
                    .put("target", new JSONObject().put("x", x).put("y", y));
            if (!method.equals("ui.click") && !method.equals("ui.longClick") && !method.equals("ui.doubleTap")) {
                throw new AgentError("INVALID_PARAMS", "coordinates only support click gestures");
            }
            boolean performed = method.equals("ui.doubleTap") ? doubleTap(x, y)
                    : tap(x, y, method.equals("ui.longClick") ? params.optLong("durationMs", 700) : 60);
            return new JSONObject().put("performed", performed).put("x", x).put("y", y);
        }
        SessionStore.ensureLegacy(params);
        SessionStore.LiveTarget target = SessionStore.liveTarget(params);
        AccessibilityNodeInfo node = target.nativeNode;
        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (params.optBoolean("dryRun")) return new JSONObject().put("executed", false)
                    .put("target", new JSONObject().put("x", bounds.centerX()).put("y", bounds.centerY())
                            .put("matchKey", target.snapshot.matchKey).put("epoch", target.snapshot.epoch));
            boolean editable = method.equals("ui.setText") || method.equals("ui.appendText") || method.equals("ui.clearText")
                    || method.equals("ui.selectAll") || method.equals("ui.cut") || method.equals("ui.copy") || method.equals("ui.paste");
            if (editable && !node.isEditable()) throw new AgentError("NOT_CLICKABLE", "target is not editable");
            boolean result;
            switch (method) {
                case "ui.click": result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        || tap(bounds.centerX(), bounds.centerY(), 60); break;
                case "ui.longClick": result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                        || tap(bounds.centerX(), bounds.centerY(), params.optLong("durationMs", 700)); break;
                case "ui.doubleTap": result = doubleTap(bounds.centerX(), bounds.centerY()); break;
                case "ui.setText": result = setText(node, params.optString("value", params.optString("text"))); break;
                case "ui.appendText": result = setText(node, UiModel.string(node.getText()) + params.optString("value")); break;
                case "ui.clearText": result = setText(node, ""); break;
                case "ui.selectAll":
                    Bundle selection = new Bundle();
                    selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
                    selection.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, UiModel.string(node.getText()).length());
                    result = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection);
                    break;
                case "ui.copy": result = node.performAction(AccessibilityNodeInfo.ACTION_COPY); break;
                case "ui.paste": result = node.performAction(AccessibilityNodeInfo.ACTION_PASTE); break;
                case "ui.cut": result = node.performAction(AccessibilityNodeInfo.ACTION_CUT); break;
                default: throw new AgentError("INVALID_PARAMS", "unknown UI action");
            }
            if (!result) throw new AgentError("UNAVAILABLE", "action was rejected", "a11y", true);
            return new JSONObject().put("performed", true).put("x", bounds.centerX()).put("y", bounds.centerY());
        } finally {
            target.close();
        }
    }

    private static void verifyPoint(float x, float y) throws Exception {
        Point size = new Point();
        ((WindowManager) service().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
        if (x < 0 || y < 0 || x >= size.x || y >= size.y) throw new AgentError("NOT_VISIBLE", "point is outside the screen");
    }

    private static JSONObject swipe(String method, JSONObject params) throws Exception {
        float x1 = (float) params.optDouble("x1", Double.NaN), y1 = (float) params.optDouble("y1", Double.NaN);
        float x2 = (float) params.optDouble("x2", Double.NaN), y2 = (float) params.optDouble("y2", Double.NaN);
        if (!Float.isFinite(x1) || !Float.isFinite(y1) || !Float.isFinite(x2) || !Float.isFinite(y2)) {
            throw new AgentError("INVALID_PARAMS", "x1/y1/x2/y2 are required");
        }
        verifyPoint(x1, y1);
        verifyPoint(x2, y2);
        long duration = params.optLong("durationMs", params.optLong("duration", 300));
        if (method.equals("ui.fling")) duration = (long) Math.max(50, Math.min(1500,
                Math.hypot(x2 - x1, y2 - y1) * 1000 / Math.max(100, params.optDouble("velocity", 1500))));
        duration = Math.max(50, Math.min(10000, duration));
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        if (method.equals("ui.drag")) {
            Path hold = new Path();
            hold.moveTo(x1, y1);
            hold.lineTo(x1 + 0.01f, y1);
            dispatch(new android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(hold, 0,
                            Math.max(50, Math.min(10000, params.optLong("holdMs", 500))), true)).build());
        }
        return dispatch(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, duration,
                method.equals("ui.drag")));
    }

    private static JSONObject gesture(JSONObject params) throws Exception {
        JSONArray paths = params.getJSONArray("paths");
        if (paths.length() < 1 || paths.length() > 10) throw new AgentError("INVALID_PARAMS", "one to ten paths are required");
        android.accessibilityservice.GestureDescription.Builder builder = new android.accessibilityservice.GestureDescription.Builder();
        for (int i = 0; i < paths.length(); i++) {
            JSONObject item = paths.getJSONObject(i);
            JSONArray points = item.getJSONArray("points");
            if (points.length() < 2 || points.length() > 100) throw new AgentError("INVALID_PARAMS", "invalid path points");
            Path path = new Path();
            for (int j = 0; j < points.length(); j++) {
                JSONObject point = points.getJSONObject(j);
                float x = (float) point.getDouble("x"), y = (float) point.getDouble("y");
                verifyPoint(x, y);
                if (j == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            builder.addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path,
                    Math.max(0, item.optLong("startMs", 0)), Math.max(50, Math.min(10000, item.optLong("durationMs", 500)))));
        }
        return dispatch(builder.build());
    }

    private static JSONObject dispatch(android.accessibilityservice.GestureDescription.StrokeDescription stroke) throws Exception {
        return dispatch(new android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build());
    }

    private static JSONObject dispatch(android.accessibilityservice.GestureDescription gesture) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean();
        boolean requested = service().dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(android.accessibilityservice.GestureDescription value) {
                completed.set(true);
                latch.countDown();
            }
            @Override public void onCancelled(android.accessibilityservice.GestureDescription value) {
                latch.countDown();
            }
        }, new Handler(Looper.getMainLooper()));
        if (!requested || !latch.await(5, TimeUnit.SECONDS)) throw new AgentError("TIMEOUT", "gesture did not complete", "a11y", true);
        if (!completed.get()) throw new AgentError("UNAVAILABLE", "gesture was cancelled", "a11y", true);
        return new JSONObject().put("performed", true).put("completed", true);
    }

    private static boolean tap(float x, float y, long duration) throws Exception {
        android.content.SharedPreferences profile = AgentServer.context().getSharedPreferences("agent", 0);
        int delay = profile.getInt("delayMs", 0), jitter = profile.getInt("jitterPx", 0);
        if (delay > 0) android.os.SystemClock.sleep(delay);
        if (jitter > 0) {
            java.util.Random random = new java.util.Random();
            x += random.nextInt(jitter * 2 + 1) - jitter;
            y += random.nextInt(jitter * 2 + 1) - jitter;
            verifyPoint(x, y);
        }
        Path path = new Path();
        path.moveTo(x, y);
        return dispatch(new android.accessibilityservice.GestureDescription.StrokeDescription(path, 0,
                Math.max(50, Math.min(10000, duration)))).optBoolean("performed");
    }

    private static boolean doubleTap(float x, float y) throws Exception {
        Path first = new Path(), second = new Path();
        first.moveTo(x, y);
        second.moveTo(x, y);
        return dispatch(new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(first, 0, 60))
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(second, 110, 60)).build())
                .optBoolean("performed");
    }

    private static boolean setText(AccessibilityNodeInfo node, String value) {
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
    }

    private static JSONObject global(String action) throws Exception {
        int value;
        switch (action) {
            case "back": value = AccessibilityService.GLOBAL_ACTION_BACK; break;
            case "home": value = AccessibilityService.GLOBAL_ACTION_HOME; break;
            case "recents": value = AccessibilityService.GLOBAL_ACTION_RECENTS; break;
            case "notifications": value = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quickSettings": value = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock": value = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN; break;
            case "screenshot": value = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT; break;
            case "splitScreen": value = AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN; break;
            case "powerDialog": value = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG; break;
            default: throw new AgentError("UNAVAILABLE", "global action requires root helper or is unknown");
        }
        if (!service().performGlobalAction(value)) throw new AgentError("UNAVAILABLE", "global action was rejected");
        return new JSONObject().put("performed", true);
    }

    private static JSONObject key(JSONObject params) throws Exception {
        String key = params.optString("key");
        if (params.optBoolean("ctrl") || params.optBoolean("shift") || params.optBoolean("alt")) {
            throw new AgentError("UNAVAILABLE", "modified keys require root input injection");
        }
        if (key.equals("KEYCODE_BACK") || key.equals("4")) return global("back");
        if (key.equals("KEYCODE_HOME") || key.equals("3")) return global("home");
        if (key.equals("KEYCODE_APP_SWITCH") || key.equals("187")) return global("recents");
        throw new AgentError("UNAVAILABLE", "arbitrary keys require root input injection");
    }

    static JSONObject shot(JSONObject params) throws Exception {
        synchronized (SHOT_LOCK) {
            if (cachedShot != null && System.currentTimeMillis() - lastShotAt < 650) {
                return new JSONObject(cachedShot.toString()).put("cached", true).put("stale", true);
            }
            if (Build.VERSION.SDK_INT < 30) throw new AgentError("UNAVAILABLE", "accessibility screenshot requires Android 11");
            CountDownLatch latch = new CountDownLatch(1);
            Bitmap[] captured = new Bitmap[1];
            int[] failure = {0};
            service().takeScreenshot(Display.DEFAULT_DISPLAY, command -> new Handler(Looper.getMainLooper()).post(command),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override public void onSuccess(android.accessibilityservice.AccessibilityService.ScreenshotResult screenshot) {
                            captured[0] = Bitmap.wrapHardwareBuffer(screenshot.getHardwareBuffer(), screenshot.getColorSpace());
                            screenshot.getHardwareBuffer().close();
                            latch.countDown();
                        }
                        @Override public void onFailure(int error) {
                            failure[0] = error;
                            latch.countDown();
                        }
                    });
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AgentError("TIMEOUT", "screenshot timed out", "a11y", true);
            if (captured[0] == null) throw new AgentError("UNAVAILABLE", "screenshot failed with code " + failure[0]);
            Bitmap bitmap = captured[0];
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)) throw new AgentError("UNAVAILABLE", "PNG encoding failed");
                if (bytes.size() > 12 * 1024 * 1024) throw new AgentError("OUTPUT_TOO_LARGE", "screenshot is too large");
                String encoded = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
                lastShotAt = System.currentTimeMillis();
                JSONObject result = new JSONObject().put("mimeType", "image/png").put("base64", encoded)
                        .put("width", bitmap.getWidth()).put("height", bitmap.getHeight()).put("capturedAt", lastShotAt);
                cachedShot = new JSONObject(result.toString());
                if (!params.optString("dest").isEmpty()) result.put("path", FileBridge.saveBase64(params.getString("dest"), encoded));
                return result;
            } finally {
                bitmap.recycle();
            }
        }
    }

    static synchronized JSONObject diff(JSONObject params) throws Exception {
        JSONObject shot = shot(params);
        byte[] bytes = Base64.decode(shot.getString("base64"), Base64.DEFAULT);
        Bitmap current = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (current == null) throw new AgentError("INTERNAL", "cannot decode screenshot");
        long capturedAt = shot.getLong("capturedAt");
        JSONArray changed = new JSONArray();
        if (previousShot != null && previousShotTime == capturedAt) {
            current.recycle();
            return new JSONObject().put("unchanged", true).put("cached", true).put("rectangles", changed);
        }
        boolean first = previousShot == null;
        if (previousShot != null) {
            int tile = Math.max(16, Math.min(256, params.optInt("tileSize", 64)));
            for (int y = 0; y < current.getHeight(); y += tile) {
                for (int x = 0; x < current.getWidth(); x += tile) {
                    int width = Math.min(tile, current.getWidth() - x), height = Math.min(tile, current.getHeight() - y);
                    if (x + width > previousShot.getWidth() || y + height > previousShot.getHeight()
                            || !sameTile(previousShot, current, x, y, width, height)) {
                        changed.put(new JSONArray().put(x).put(y).put(x + width).put(y + height));
                    }
                }
            }
        }
        if (previousShot != null) previousShot.recycle();
        previousShot = current.copy(Bitmap.Config.ARGB_8888, false);
        previousShotTime = capturedAt;
        current.recycle();
        return new JSONObject().put("unchanged", !first && changed.length() == 0)
                .put("baseline", first).put("rectangles", changed).put("capturedAt", capturedAt);
    }

    private static boolean sameTile(Bitmap first, Bitmap second, int x, int y, int width, int height) {
        int[] a = new int[width * height], b = new int[width * height];
        first.getPixels(a, 0, width, x, y, width, height);
        second.getPixels(b, 0, width, x, y, width, height);
        return Arrays.equals(a, b);
    }

    static JSONObject wait(JSONObject params) throws Exception {
        long start = System.currentTimeMillis();
        long timeout = Math.max(1, Math.min(120000, params.optLong("timeoutMs", 10000)));
        long deadline = start + timeout;
        String pkg = params.optString("package");
        boolean gone = params.optString("until", "exists").equals("gone");
        while (System.currentTimeMillis() < deadline) {
            boolean found = false;
            JSONObject node = null;
            if (!pkg.isEmpty()) found = pkg.equals(AgentServer.events.foreground());
            else if (params.has("selector") || params.has("text") || params.has("viewId") || params.has("where")) {
                try {
                    JSONArray nodes = SessionStore.find(params).getJSONArray("hits");
                    found = nodes.length() > 0;
                    if (found) node = nodes.getJSONObject(0);
                } catch (AgentError e) {
                    if (!e.code.equals("NOT_FOUND")) throw e;
                }
            } else found = true;
            if (found != gone) {
                if (params.has("idleMs")) idle(params);
                return new JSONObject().put("matched", true).put("waitedMs", System.currentTimeMillis() - start)
                        .put("generation", AgentServer.events.generation()).put("node", node == null ? JSONObject.NULL : node);
            }
            long generation = AgentServer.events.generation();
            AgentServer.events.waitForChange(generation, Math.min(1000, deadline - System.currentTimeMillis()));
        }
        throw new AgentError("TIMEOUT", "UI condition was not met before timeout", "a11y", true);
    }

    static JSONObject idle(JSONObject params) throws Exception {
        long start = System.currentTimeMillis();
        long quiet = Math.max(50, Math.min(10000, params.optLong("idleMs", 500)));
        long deadline = start + Math.max(quiet, Math.min(120000, params.optLong("timeoutMs", 10000)));
        while (System.currentTimeMillis() < deadline) {
            long generation = AgentServer.events.generation();
            long remaining = quiet - (System.currentTimeMillis() - AgentServer.events.lastUiChange());
            if (remaining <= 0) return new JSONObject().put("idle", true).put("quietMs", quiet)
                    .put("waitedMs", System.currentTimeMillis() - start).put("generation", generation);
            AgentServer.events.waitForChange(generation, Math.min(remaining, deadline - System.currentTimeMillis()));
        }
        throw new AgentError("TIMEOUT", "UI did not become idle", "a11y", true);
    }

    static JSONObject scrollUntil(JSONObject params) throws Exception {
        int action = params.optString("direction", "forward").equals("backward")
                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        for (int i = 0; i <= Math.min(50, params.optInt("maxScrolls", 20)); i++) {
            JSONObject found = SessionStore.find(params);
            if (found.getJSONArray("hits").length() > 0) return found.put("scrolls", i);
            AccessibilityNodeInfo root = service().getRootInActiveWindow();
            if (root == null || !root.performAction(action)) {
                if (root != null) root.recycle();
                throw new AgentError("NOT_FOUND", "target not found and container cannot scroll");
            }
            root.recycle();
            SessionStore.afterAction(params);
            long generation = AgentServer.events.generation();
            AgentServer.events.waitForChange(generation, params.optLong("stepTimeoutMs", 800));
        }
        throw new AgentError("TIMEOUT", "target not found within scroll budget");
    }

    static JSONObject highlight(JSONObject params) throws Exception {
        SessionStore.ensureLegacy(params);
        SessionStore.LiveTarget target = SessionStore.liveTarget(params);
        Rect bounds = new Rect();
        target.nativeNode.getBoundsInScreen(bounds);
        target.close();
        AccessibilityService service = service();
        WindowManager manager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(8);
        View overlay = new View(service) {
            @Override protected void onDraw(Canvas canvas) {
                canvas.drawRect(bounds, paint);
            }
        };
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(() -> manager.addView(overlay, layout));
        handler.postDelayed(() -> manager.removeView(overlay), Math.max(100, Math.min(5000, params.optLong("durationMs", 2000))));
        return new JSONObject().put("highlighted", true).put("bounds", new JSONArray().put(bounds.left).put(bounds.top)
                .put(bounds.right).put(bounds.bottom));
    }
}
