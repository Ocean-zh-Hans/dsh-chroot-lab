package dev.dsh.agent;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Point;
import android.graphics.Rect;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

final class SessionStore {
    static final class Flag {
        JSONObject selector;
        long lastResolvedAt;
        long resolveCount;

        Flag(JSONObject selector) {
            this.selector = selector;
        }
    }

    static final class Session {
        final String id = "s-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String pkg = "";
        String activity = "";
        int epoch;
        long nextNode = 1;
        String source = "live";
        String backend = "a11y";
        boolean stale;
        boolean dirty;
        boolean includeInvisible;
        int maxDepth = 40;
        long createdAt = System.currentTimeMillis();
        long lastUsedAt = createdAt;
        long lastVerified;
        long patchCount;
        long patchMs;
        String fallbackReason;
        boolean reanalyzed;
        long changes;
        int pendingWindow;
        AccessibilityNodeInfo pending;
        boolean autosave;
        boolean includeText;
        int debounceMs = 2000;
        final List<SessionNode> nodes = new ArrayList<>();
        final Map<String, SessionNode> byId = new HashMap<>();
        final Map<String, List<SessionNode>> byMatch = new HashMap<>();
        final Map<String, Flag> flags = new HashMap<>();
        final JSONArray windowStack = new JSONArray();
        String pointer;
    }

    static final class LiveTarget {
        final AccessibilityNodeInfo nativeNode;
        final SessionNode snapshot;

        LiveTarget(AccessibilityNodeInfo nativeNode, SessionNode snapshot) {
            this.nativeNode = nativeNode;
            this.snapshot = snapshot;
        }

        void close() {
            nativeNode.recycle();
        }
    }

    private static final java.util.concurrent.ExecutorService ANALYSIS_EXECUTOR = new java.util.concurrent.ThreadPoolExecutor(
            0, 2, 30, java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(8), runnable -> {
        Thread thread = new Thread(runnable, "dsh-ui-analysis");
        thread.setDaemon(true);
        return thread;
    });
    private static final LinkedHashMap<String, Session> SESSIONS = new LinkedHashMap<>(4, 0.75f, true);
    private static String defaultId;
    private static String foregroundActivity = "";
    private static volatile String lastFallbackReason;

    private SessionStore() {
    }

    static synchronized JSONObject analyze(JSONObject params) throws Exception {
        String requested = params.optString("pkg");
        boolean forced = params.optBoolean("force");
        if (requested.isEmpty()) {
            requested = foregroundPackage();
            if (requested == null) {
                forced = true;
                requested = "";
            }
        }
        if (!forced) {
            for (Session session : SESSIONS.values()) {
                if ((requested.isEmpty() || requested.equals(session.pkg)) && !session.dirty && !session.stale
                        && session.source.equals("live") && System.currentTimeMillis() - session.lastVerified < 30000) {
                    session.lastUsedAt = System.currentTimeMillis();
                    defaultId = session.id;
                    return info(session, true, false);
                }
            }
        }
        Session session = null;
        if (!requested.isEmpty()) {
            for (Session candidate : SESSIONS.values()) if (requested.equals(candidate.pkg)) {
                session = candidate;
                break;
            }
        }
        if (session == null && !params.optBoolean("newSession") && defaultId != null) {
            Session current = SESSIONS.get(defaultId);
            if (current != null && (requested.isEmpty() || requested.equals(current.pkg))) session = current;
        }
        if (session == null) session = new Session();
        JSONObject result = fullAnalysis(session, params, null);
        SESSIONS.put(session.id, session);
        defaultId = session.id;
        trim();
        return result;
    }

    private static JSONObject fullAnalysis(Session session, JSONObject params, String fallback) throws Exception {
        synchronized (session) {
            long changes = session.changes;
            session.maxDepth = Math.max(1, Math.min(50, params.optInt("maxDepth", session.maxDepth)));
            session.includeInvisible = params.optBoolean("includeInvisible", session.includeInvisible);
            List<SessionNode> nodes = new ArrayList<>();
            JSONArray windows = new JSONArray();
            long[] counter = {session.nextNode};
            java.util.concurrent.Future<?> task = ANALYSIS_EXECUTOR.submit(() -> {
                readWindows(session, nodes, windows, counter, session.epoch + 1, params);
                return null;
            });
            try {
                task.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                task.cancel(true);
                session.dirty = true;
                session.stale = true;
                throw new AgentError("TIMEOUT", "UI analysis timed out");
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof AgentError) throw (AgentError) e.getCause();
                throw new AgentError("UNAVAILABLE", "UI analysis failed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AgentError("TIMEOUT", "UI analysis interrupted");
            }
            if (nodes.isEmpty()) throw new AgentError("NOT_FOUND", "no readable visible nodes");
            session.nextNode = counter[0];
            session.nodes.clear();
            session.nodes.addAll(nodes);
            while (session.windowStack.length() > 0) session.windowStack.remove(session.windowStack.length() - 1);
            for (int i = 0; i < windows.length(); i++) session.windowStack.put(windows.get(i));
            session.epoch++;
            session.source = "live";
            session.stale = false;
            session.dirty = changes != session.changes;
            session.lastVerified = System.currentTimeMillis();
            session.lastUsedAt = session.lastVerified;
            session.fallbackReason = fallback;
            session.reanalyzed = fallback != null;
            if (fallback != null) lastFallbackReason = fallback;
            if (fallback == null) session.patchMs = 0;
            if (!params.optString("pkg").isEmpty()) session.pkg = params.getString("pkg");
            else if (!foregroundPackage().isEmpty()) session.pkg = foregroundPackage();
            else if (session.pkg.isEmpty() && !session.nodes.isEmpty()) session.pkg = session.nodes.get(0).pkg;
            session.activity = foregroundActivity;
            if (!session.pkg.isEmpty() && !session.activity.isEmpty()
                    && !session.activity.startsWith(session.pkg + ".") && !session.activity.startsWith(".")) {
                session.activity = "";
            }
            index(session);
            SessionPersistence.autosave(session);
            return info(session, false, fallback != null);
        }
    }

    private static void readWindows(Session session, List<SessionNode> nodes, JSONArray stack, long[] counter,
            long epoch, JSONObject params) throws Exception {
        if (AgentServer.accessibility() == null) {
            importRoot(session, nodes, stack, counter, epoch, params);
            return;
        }
        session.backend = "a11y";
        AccessibilityService service = service();
        List<AccessibilityWindowInfo> windows = service.getWindows();
        int[] budget = {Math.max(1, Math.min(3000, params.optInt("maxNodes", 2000)))};
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            try {
                String type = windowType(window.getType());
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                stack.put(new JSONObject().put("windowId", window.getId()).put("type", type)
                        .put("pkg", SessionNode.value(root.getPackageName())).put("focused", window.isFocused())
                        .put("bounds", new JSONArray().put(bounds.left).put(bounds.top).put(bounds.width()).put(bounds.height())));
                if (!wantedWindow(params.opt("window"), window.getId(), type)) continue;
                readTree(root, null, 0, 0, "", window.getId(), type, session.includeInvisible,
                        session.maxDepth, budget, nodes, counter, epoch, null, null, null);
            } finally {
                root.recycle();
            }
        }
        if (stack.length() == 0) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) throw new AgentError("NOT_FOUND", "no active window");
            try {
                stack.put(new JSONObject().put("windowId", root.getWindowId()).put("type", "app")
                        .put("pkg", SessionNode.value(root.getPackageName())).put("focused", true));
                readTree(root, null, 0, 0, "", root.getWindowId(), "app", session.includeInvisible,
                        session.maxDepth, budget, nodes, counter, epoch, null, null, null);
            } finally {
                root.recycle();
            }
        }
    }

    private static void importRoot(Session session, List<SessionNode> nodes, JSONArray stack, long[] counter,
            long epoch, JSONObject params) throws Exception {
        JSONObject root = RootClient.call("ui.dump", params);
        JSONArray imported = root.getJSONArray("nodes");
        Map<String, JSONObject> original = new HashMap<>();
        Map<String, String> retained = new HashMap<>();
        for (int i = 0; i < imported.length(); i++) {
            JSONObject node = imported.getJSONObject(i);
            original.put(node.getString("nodeId"), node);
        }
        for (int i = 0; i < imported.length(); i++) {
            SessionNode node = SessionNode.fromJson(imported.getJSONObject(i));
            if (!session.includeInvisible && !node.visible) continue;
            String oldId = node.nodeId;
            String parent = node.parentId;
            while (parent != null && !retained.containsKey(parent)) {
                JSONObject parentNode = original.get(parent);
                parent = parentNode == null || parentNode.isNull("parentId") ? null : parentNode.optString("parentId");
            }
            node.parentId = retained.get(parent);
            node.nodeId = "n" + counter[0]++;
            node.epoch = epoch;
            retained.put(oldId, node.nodeId);
            nodes.add(node);
            boolean present = false;
            for (int j = 0; j < stack.length(); j++) if (stack.getJSONObject(j).optInt("windowId") == node.windowId) present = true;
            if (!present) {
                stack.put(new JSONObject().put("windowId", node.windowId).put("type", node.windowType)
                        .put("pkg", node.pkg).put("focused", stack.length() == 0).put("bounds", node.bounds()));
            }
        }
        session.backend = root.optString("via", "uiautomation");
    }

    private static void readTree(AccessibilityNodeInfo nativeNode, String parent, int depth, int index, String path,
            int windowId, String windowType, boolean includeInvisible, int maxDepth, int[] budget,
            List<SessionNode> nodes, long[] counter, long epoch, Predicate<Map<String, Object>> predicate,
            List<LiveTarget> matches, String pick) throws Exception {
        if (depth > maxDepth || budget[0]-- <= 0) return;
        SessionNode snapshot = SessionNode.read(nativeNode, "n" + counter[0]++, epoch, parent, depth, index, path, windowId, windowType);
        if (includeInvisible || snapshot.visible) {
            if (matches == null) nodes.add(snapshot);
            else if (predicate.test(snapshot.map()) && (pick != null || matches.size() < 50)) {
                matches.add(new LiveTarget(AccessibilityNodeInfo.obtain(nativeNode), snapshot));
            }
        }
        String childPath = path.isEmpty() ? SessionNode.own(snapshot.cls, snapshot.viewId, index)
                : path + "/" + SessionNode.own(snapshot.cls, snapshot.viewId, index);
        for (int i = 0; i < nativeNode.getChildCount() && budget[0] > 0; i++) {
            AccessibilityNodeInfo child = nativeNode.getChild(i);
            if (child == null) continue;
            try {
                readTree(child, includeInvisible || snapshot.visible ? snapshot.nodeId : parent, depth + 1, i,
                        childPath, windowId, windowType, includeInvisible, maxDepth, budget, nodes, counter, epoch,
                        predicate, matches, pick);
            } finally {
                child.recycle();
            }
        }
    }

    static synchronized void ensureLegacy(JSONObject params) throws Exception {
        if (defaultId == null && !params.has("sessionId")) analyze(params);
    }

    static synchronized JSONObject legacyDump(JSONObject params) throws Exception {
        Session session = require(params);
        JSONArray nodes = new JSONArray();
        for (SessionNode node : session.nodes) nodes.put(node.legacyJson());
        return new JSONObject().put("nodes", nodes).put("sessionId", session.id).put("epoch", session.epoch)
                .put("via", session.backend);
    }

    static synchronized JSONObject explain(JSONObject params) throws Exception {
        Session session = require(params);
        double rawX = params.optDouble("x", Double.NaN), rawY = params.optDouble("y", Double.NaN);
        if (!Double.isFinite(rawX) || !Double.isFinite(rawY)) throw new AgentError("INVALID_ARGUMENT", "numeric x/y are required");
        int x = (int) Math.round(rawX), y = (int) Math.round(rawY);
        SessionNode best = null;
        long area = Long.MAX_VALUE;
        for (SessionNode node : session.nodes) {
            long size = (long) node.width * node.height;
            if (node.visible && x >= node.x && x < node.x + node.width && y >= node.y && y < node.y + node.height && size <= area) {
                best = node;
                area = size;
            }
        }
        if (best == null) throw new AgentError("NOT_FOUND", "no visible node contains the point");
        JSONArray ancestry = new JSONArray();
        while (best != null) {
            ancestry.put(best.json());
            best = best.parentId == null ? null : session.byId.get(best.parentId);
        }
        return new JSONObject().put("x", x).put("y", y).put("ancestry", ancestry);
    }

    static synchronized JSONObject current(JSONObject params) throws Exception {
        return status(params);
    }

    static synchronized JSONObject list() throws Exception {
        JSONArray result = new JSONArray();
        for (Session session : SESSIONS.values()) result.put(info(session, false, false));
        return new JSONObject().put("sessions", result).put("defaultSessionId", defaultId == null ? JSONObject.NULL : defaultId);
    }

    static synchronized JSONObject use(JSONObject params) throws Exception {
        Session session = require(params);
        defaultId = session.id;
        session.lastUsedAt = System.currentTimeMillis();
        return info(session, false, false);
    }

    static synchronized JSONObject drop(JSONObject params) throws Exception {
        Session session = require(params);
        SESSIONS.remove(session.id);
        recyclePending(session);
        if (session.id.equals(defaultId)) defaultId = null;
        return new JSONObject().put("dropped", true).put("sessionId", session.id);
    }

    static synchronized JSONObject status(JSONObject params) throws Exception {
        Session session = require(params);
        session.lastUsedAt = System.currentTimeMillis();
        return info(session, false, false).put("patchCount", session.patchCount).put("windowStack", session.windowStack);
    }

    static synchronized JSONObject autosave(JSONObject params) throws Exception {
        Session session = require(params);
        session.autosave = params.optBoolean("enabled");
        session.debounceMs = Math.max(100, Math.min(10000, params.optInt("debounceMs", 2000)));
        session.includeText = params.optBoolean("includeText")
                && session.pkg.equals(params.optString("allowTextPackage"));
        SessionPersistence.autosave(session);
        return new JSONObject().put("enabled", session.autosave).put("includeText", session.includeText)
                .put("debounceMs", session.debounceMs);
    }

    static synchronized JSONObject save(JSONObject params) throws Exception {
        Session session = require(params);
        JSONObject request = new JSONObject(params.toString());
        request.put("sessionId", session.id);
        return Jobs.start("session.persist", request);
    }

    static synchronized JSONObject persist(JSONObject params) throws Exception {
        return SessionPersistence.saveNow(require(params), params.getString("name"),
                params.optBoolean("includeText") && require(params).pkg.equals(params.optString("allowTextPackage")));
    }

    static synchronized JSONObject load(JSONObject params) throws Exception {
        SessionPersistence.Saved saved = SessionPersistence.load(params.getString("name"));
        Session session = new Session();
        session.pkg = saved.pkg;
        session.activity = saved.activity;
        session.epoch = saved.epoch + 1;
        session.source = "disk";
        session.stale = true;
        for (int i = 0; i < saved.nodes.length(); i++) {
            SessionNode node = SessionNode.fromJson(saved.nodes.getJSONObject(i));
            node.epoch = session.epoch;
            session.nodes.add(node);
            String number = node.nodeId.replace("n", "");
            try {
                session.nextNode = Math.max(session.nextNode, Long.parseLong(number) + 1);
            } catch (NumberFormatException ignored) {
            }
        }
        for (int i = 0; i < saved.flags.length(); i++) {
            JSONObject flag = saved.flags.getJSONObject(i);
            session.flags.put(flag.getString("name"), new Flag(flag.getJSONObject("selector")));
        }
        index(session);
        SESSIONS.put(session.id, session);
        defaultId = session.id;
        trim();
        return info(session, false, false);
    }

    static synchronized JSONObject savedList() throws Exception {
        return SessionPersistence.list();
    }

    static synchronized JSONObject savedDelete(JSONObject params) throws Exception {
        return SessionPersistence.delete(params.getString("name"));
    }

    static synchronized JSONObject find(JSONObject params) throws Exception {
        Session session = require(params);
        session.lastUsedAt = System.currentTimeMillis();
        if (session.source.equals("live") && session.lastUsedAt - session.lastVerified > 30000) session.stale = true;
        checkEpoch(session, params);
        if (session.source.equals("live") && (session.dirty || session.stale)) refresh(session, params);
        Predicate<Map<String, Object>> predicate = predicate(session, params);
        JSONArray hits = new JSONArray();
        int limit = Math.max(1, Math.min(200, params.optInt("limit", 20)));
        int offset = Math.max(0, params.optInt("offset"));
        int total = 0;
        int skipped = 0;
        boolean truncated = false;
        int maxBytes = Math.max(1024, Math.min(1048576, params.optInt("maxBytes", 65536)));
        for (SessionNode node : session.nodes) {
            if (!wantedWindow(params.opt("window"), node.windowId, node.windowType)) continue;
            if (!predicate.test(node.map())) continue;
            total++;
            if (skipped++ < offset || hits.length() >= limit) continue;
            JSONObject hit = project(session, node, params.optJSONArray("fields"));
            JSONObject candidate = new JSONObject().put("sessionId", session.id).put("epoch", session.epoch)
                    .put("total", total).put("hits", new JSONArray(hits.toString()).put(hit));
            if (candidate.toString().getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                truncated = true;
                break;
            }
            hits.put(hit);
        }
        if (total > offset + hits.length()) truncated = true;
        return new JSONObject().put("sessionId", session.id).put("epoch", session.epoch)
                .put("source", session.source).put("stale", session.stale).put("total", total)
                .put("hits", hits).put("truncated", truncated)
                .put("nextOffset", total > offset + hits.length() ? offset + hits.length() : JSONObject.NULL)
                .put("via", session.backend).put("degraded", false);
    }

    private static JSONObject project(Session session, SessionNode node, JSONArray fields) throws Exception {
        JSONObject full = node.json();
        if (fields == null || fields.length() == 0) {
            full.put("path", path(session, node));
            full.put("textLen", node.text.length());
            if (node.text.length() > 256) full.put("text", node.text.substring(0, 256));
            return full;
        }
        JSONObject result = new JSONObject().put("nodeId", node.nodeId).put("matchKey", node.matchKey)
                .put("epoch", node.epoch).put("path", path(session, node));
        for (int i = 0; i < fields.length(); i++) {
            String field = fields.getString(i);
            if (full.has(field)) result.put(field, full.get(field));
        }
        return result;
    }

    static synchronized LiveTarget liveTarget(JSONObject params) throws Exception {
        Session session = require(params);
        if (!session.source.equals("live")) throw new AgentError("SNAPSHOT_READONLY", "analyze a live session before input");
        checkEpoch(session, params);
        if (params.has("nodeId") && !params.has("epoch")) throw new AgentError("INVALID_ARGUMENT", "epoch is required for nodeId input");
        if (session.dirty || session.stale) refresh(session, params);
        String matchKey = params.optString("matchKey");
        if (matchKey.isEmpty() && !params.optString("nodeId").isEmpty()) {
            SessionNode node = session.byId.get(params.getString("nodeId"));
            if (node == null) throw new AgentError("STALE_HANDLE", "nodeId is not present in the current epoch");
            matchKey = node.matchKey;
        }
        if (!matchKey.isEmpty() && !matchKey.startsWith("v1:")) throw new AgentError("KEY_VERSION_CHANGED", "unsupported matchKey version");
        JSONObject request = new JSONObject(params.toString());
        if (!matchKey.isEmpty()) request.put("matchKey", matchKey);
        if (!params.optString("flag").isEmpty()) {
            Flag flag = session.flags.get(params.getString("flag"));
            if (flag == null) throw new AgentError("FLAG_UNRESOLVED", "unknown flag");
            if (flag.selector.has("expression")) request.put("selector", flag.selector.getString("expression"));
            else request.put("where", flag.selector);
            request.remove("matchKey");
        }
        Predicate<Map<String, Object>> predicate = predicate(session, request);
        List<LiveTarget> matches = new ArrayList<>();
        String pick = request.optString("pick");
        List<AccessibilityWindowInfo> windows = service().getWindows();
        int[] budget = {2500};
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            try {
                readTree(root, null, 0, 0, "", window.getId(), windowType(window.getType()), false,
                        50, budget, new ArrayList<>(), new long[]{1}, session.epoch, predicate, matches, pick);
            } finally {
                root.recycle();
            }
        }
        if (matches.isEmpty()) {
            if (request.has("flag")) throw new AgentError("FLAG_UNRESOLVED", "flag does not resolve in the live tree");
            throw new AgentError("NOT_FOUND", "no live node matched");
        }
        LiveTarget selected = choose(matches, request);
        for (LiveTarget target : matches) if (target != selected) target.close();
        if (!request.optString("flag").isEmpty()) {
            Flag flag = session.flags.get(request.getString("flag"));
            flag.lastResolvedAt = System.currentTimeMillis();
            flag.resolveCount++;
        }
        verify(request, selected);
        return selected;
    }

    private static LiveTarget choose(List<LiveTarget> matches, JSONObject params) throws Exception {
        String pick = params.optString("pick");
        if (matches.size() > 1 && pick.isEmpty()) {
            for (LiveTarget target : matches) target.close();
            throw new AgentError("AMBIGUOUS_MATCH", "multiple live nodes matched; specify pick");
        }
        LiveTarget selected = matches.get(0);
        if (pick.equals("nearest")) {
            int x = params.optInt("x"), y = params.optInt("y");
            long distance = Long.MAX_VALUE;
            for (LiveTarget target : matches) {
                long dx = target.snapshot.x + target.snapshot.width / 2 - x;
                long dy = target.snapshot.y + target.snapshot.height / 2 - y;
                if (dx * dx + dy * dy < distance) {
                    distance = dx * dx + dy * dy;
                    selected = target;
                }
            }
        } else if (!pick.isEmpty() && !pick.equals("first")) {
            for (LiveTarget target : matches) target.close();
            throw new AgentError("INVALID_PARAMS", "pick must be first or nearest");
        }
        return selected;
    }

    private static void verify(JSONObject params, LiveTarget target) throws Exception {
        AccessibilityNodeInfo node = target.nativeNode;
        if (!node.refresh() || !node.isVisibleToUser()) {
            target.close();
            throw new AgentError("NOT_VISIBLE", "target is not visible in the current window");
        }
        if (!node.isEnabled()) {
            target.close();
            throw new AgentError("NOT_CLICKABLE", "target is disabled");
        }
        if (!params.optString("pkg").isEmpty() && !params.getString("pkg").equals(SessionNode.value(node.getPackageName()))) {
            target.close();
            throw new AgentError("NOT_FOUND", "target package changed");
        }
        Set<Integer> windowIds = new HashSet<>();
        for (AccessibilityWindowInfo window : service().getWindows()) windowIds.add(window.getId());
        if (!windowIds.contains(node.getWindowId())) {
            target.close();
            throw new AgentError("STALE_HANDLE", "target window is no longer current");
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        Point size = new Point();
        ((WindowManager) AgentServer.context().getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(size);
        if (bounds.width() <= 0 || bounds.height() <= 0 || !bounds.intersects(0, 0, size.x, size.y)) {
            target.close();
            throw new AgentError("NOT_VISIBLE", "target bounds are outside the screen");
        }
    }

    private static Predicate<Map<String, Object>> predicate(Session session, JSONObject params) throws Exception {
        if (params.has("where")) return Where.predicate(params.getJSONObject("where"), params.optBoolean("caseSensitive"));
        Object selector = params.opt("selector");
        if (selector instanceof JSONObject) {
            JSONObject value = (JSONObject) selector;
            try {
                if (value.has("expression")) return UiSelector.parse(value.getString("expression"));
                return Where.predicate(value, params.optBoolean("caseSensitive"));
            } catch (IllegalArgumentException e) {
                throw new AgentError("PARSE_ERROR", e.getMessage());
            }
        }
        if (selector instanceof String && !((String) selector).isEmpty()) {
            try {
                return UiSelector.parse((String) selector);
            } catch (IllegalArgumentException e) {
                throw new AgentError("PARSE_ERROR", e.getMessage());
            }
        }
        if (!params.optString("matchKey").isEmpty()) {
            String key = params.getString("matchKey");
            return node -> key.equals(node.get("matchKey"));
        }
        JSONObject where = new JSONObject();
        if (!params.optString("text").isEmpty()) where.put(params.optBoolean("exact") ? "text" : "text~", params.getString("text"));
        if (!params.optString("desc").isEmpty()) where.put("desc~", params.getString("desc"));
        if (!params.optString("viewId").isEmpty()) where.put("viewId", params.getString("viewId"));
        if (!params.optString("pkg").isEmpty()) where.put("pkg", params.getString("pkg"));
        if (!params.optString("flag").isEmpty()) where = session.flags.get(params.getString("flag")).selector;
        return Where.predicate(where, params.optBoolean("caseSensitive"));
    }

    private static void refresh(Session session, JSONObject params) throws Exception {
        synchronized (session) {
            if (session.source.equals("disk") || (!session.dirty && !session.stale)) return;
            AccessibilityNodeInfo pending = session.pending;
            session.pending = null;
            if (pending == null) {
                session.patchMs = 0;
                fullAnalysis(session, params, "SOURCE_UNAVAILABLE");
                return;
            }
            long started = System.currentTimeMillis();
            long changes = session.changes;
            List<AccessibilityNodeInfo> chain = new ArrayList<>();
            AccessibilityNodeInfo replacement = null;
            try {
                AccessibilityNodeInfo cursor = pending;
                while (cursor != null) {
                    chain.add(AccessibilityNodeInfo.obtain(cursor));
                    AccessibilityNodeInfo parent = cursor.getParent();
                    cursor = parent;
                }
                SessionNode old = null;
                String path = "";
                String currentType = "app";
                for (SessionNode node : session.nodes) if (node.windowId == pending.getWindowId()) {
                    currentType = node.windowType;
                    break;
                }
                for (int i = chain.size() - 1; i >= 0; i--) {
                    AccessibilityNodeInfo nativeNode = chain.get(i);
                    int actualIndex = 0;
                    if (i + 1 < chain.size()) {
                        actualIndex = -1;
                        AccessibilityNodeInfo parent = chain.get(i + 1);
                        for (int j = 0; j < parent.getChildCount(); j++) {
                            AccessibilityNodeInfo child = parent.getChild(j);
                            if (child != null && nativeNode.equals(child)) actualIndex = j;
                            if (child != null) child.recycle();
                            if (actualIndex >= 0) break;
                        }
                    }
                    if (actualIndex < 0) {
                        fullAnalysis(session, params, "PARENT_MAPPING_NOT_FOUND");
                        return;
                    }
                    SessionNode candidate = SessionNode.read(nativeNode, "n0", session.epoch + 1, null,
                            chain.size() - 1 - i, actualIndex, path, pending.getWindowId(), currentType);
                    int matches = 0;
                    for (SessionNode node : session.nodes) {
                        if (node.matchKey.equals(candidate.matchKey) && node.windowId == candidate.windowId
                                && node.depth == candidate.depth && node.index == candidate.index) {
                            old = node;
                            matches++;
                        }
                    }
                    if (matches > 1) {
                        fullAnalysis(session, params, "PARENT_MAPPING_AMBIGUOUS");
                        return;
                    }
                    if (old != null) {
                        replacement = AccessibilityNodeInfo.obtain(nativeNode);
                        break;
                    }
                    path = path.isEmpty() ? SessionNode.own(candidate.cls, candidate.viewId, candidate.index)
                            : path + "/" + SessionNode.own(candidate.cls, candidate.viewId, candidate.index);
                }
                if (old == null) {
                    fullAnalysis(session, params, "PARENT_MAPPING_NOT_FOUND");
                    return;
                }
                List<SessionNode> subtree = new ArrayList<>();
                long[] counter = {session.nextNode};
                int[] budget = {2000};
                readTree(replacement, old.parentId, old.depth, old.index, old.path, old.windowId, old.windowType,
                        session.includeInvisible, session.maxDepth, budget, subtree, counter, session.epoch + 1,
                        null, null, null);
                if (Math.abs(session.nodes.size() - subtree.size()) > session.nodes.size() * 0.4) {
                    fullAnalysis(session, params, "NODE_COUNT_CHANGED");
                    return;
                }
                Set<String> removed = new HashSet<>();
                removed.add(old.nodeId);
                for (int i = 0; i < session.nodes.size(); i++) {
                    SessionNode node = session.nodes.get(i);
                    if (node.parentId != null && removed.contains(node.parentId)) removed.add(node.nodeId);
                }
                session.nodes.removeIf(node -> removed.contains(node.nodeId));
                session.nodes.addAll(subtree);
                session.nextNode = counter[0];
                session.epoch++;
                session.patchCount++;
                session.patchMs = System.currentTimeMillis() - started;
                session.fallbackReason = null;
                session.dirty = changes != session.changes;
                session.stale = session.dirty;
                session.lastVerified = System.currentTimeMillis();
                index(session);
                SessionPersistence.autosave(session);
            } finally {
                if (session.fallbackReason != null) session.patchMs = System.currentTimeMillis() - started;
                pending.recycle();
                if (replacement != null) replacement.recycle();
                for (AccessibilityNodeInfo node : chain) node.recycle();
            }
        }
    }

    static synchronized JSONObject afterAction(JSONObject params) throws Exception {
        Session session = require(params);
        session.patchMs = 0;
        return fullAnalysis(session, params, "POST_ACTION");
    }

    static synchronized JSONObject resolve(JSONObject params) throws Exception {
        LiveTarget target = liveTarget(params);
        String matchKey = target.snapshot.matchKey;
        int windowId = target.snapshot.windowId;
        target.close();
        Session session = require(params);
        session.patchMs = 0;
        fullAnalysis(session, params, "LIVE_RESOLVE");
        SessionNode match = null;
        for (SessionNode node : session.nodes) {
            if (node.matchKey.equals(matchKey) && node.windowId == windowId) {
                if (match != null) throw new AgentError("AMBIGUOUS_MATCH", "matchKey has multiple live candidates");
                match = node;
            }
        }
        if (match == null) throw new AgentError("NOT_FOUND", "node disappeared during live resolve");
        return match.json().put("sessionId", session.id).put("live", true);
    }

    static synchronized JSONObject path(JSONObject params) throws Exception {
        Session session = require(params);
        SessionNode node = node(session, params);
        JSONArray result = new JSONArray();
        while (node != null) {
            result.put(node.json());
            node = node.parentId == null ? null : session.byId.get(node.parentId);
        }
        return new JSONObject().put("sessionId", session.id).put("epoch", session.epoch).put("path", result);
    }

    static synchronized JSONObject refs(JSONObject params) throws Exception {
        Session session = require(params);
        SessionNode node = node(session, params);
        JSONArray same = new JSONArray(), children = new JSONArray();
        for (SessionNode candidate : session.nodes) {
            if (!node.viewId.isEmpty() && node.viewId.equals(candidate.viewId)) same.put(candidate.nodeId);
            if (node.nodeId.equals(candidate.parentId)) children.put(candidate.json());
        }
        return new JSONObject().put("nodeId", node.nodeId).put("sameViewId", same).put("children", children)
                .put("matchCount", session.byMatch.getOrDefault(node.matchKey, new ArrayList<>()).size());
    }

    static synchronized JSONObject seek(JSONObject params) throws Exception {
        Session session = require(params);
        SessionNode node = node(session, params);
        session.pointer = node.nodeId;
        return node.json();
    }

    static synchronized JSONObject flag(JSONObject params, boolean remove) throws Exception {
        Session session = require(params);
        String name = params.optString("name");
        if (!name.matches("[A-Za-z0-9_.-]{1,64}")) throw new AgentError("INVALID_PARAMS", "invalid flag name");
        if (remove) return new JSONObject().put("removed", session.flags.remove(name) != null);
        JSONObject selector;
        Object requested = params.opt("selector");
        if (requested instanceof JSONObject) selector = (JSONObject) requested;
        else if (requested instanceof String) selector = new JSONObject().put("expression", requested);
        else if (params.opt("where") instanceof JSONObject) selector = params.getJSONObject("where");
        else throw new AgentError("INVALID_ARGUMENT", "selector object or expression is required");
        if (selector.length() == 0) throw new AgentError("INVALID_ARGUMENT", "selector must not be empty");
        JSONObject live = new JSONObject().put("sessionId", session.id);
        if (selector.has("expression")) live.put("selector", selector.getString("expression"));
        else live.put("where", selector);
        if (params.has("pick")) live.put("pick", params.get("pick"));
        if (session.source.equals("disk")) throw new AgentError("SNAPSHOT_READONLY", "analyze a live session before setting flags");
        if (AgentServer.accessibility() == null) RootClient.call("ui.resolve", live);
        else {
            LiveTarget target = liveTarget(live);
            target.close();
        }
        session.flags.put(name, new Flag(selector));
        SessionPersistence.autosave(session);
        return new JSONObject().put("saved", true).put("name", name).put("selector", selector);
    }

    static synchronized JSONObject flags(JSONObject params) throws Exception {
        Session session = require(params);
        JSONArray result = new JSONArray();
        for (Map.Entry<String, Flag> entry : session.flags.entrySet()) {
            Flag flag = entry.getValue();
            result.put(new JSONObject().put("name", entry.getKey()).put("selector", flag.selector)
                    .put("lastResolvedAt", flag.lastResolvedAt).put("resolveCount", flag.resolveCount));
        }
        return new JSONObject().put("flags", result);
    }

    static void event(AccessibilityEvent event) {
        String pkg = SessionNode.value(event.getPackageName());
        if (pkg.equals("com.android.systemui")) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            String className = SessionNode.value(event.getClassName());
            foregroundActivity = className.startsWith(pkg + ".") || className.startsWith(".") ? className : "";
        }
        List<Session> sessions;
        synchronized (SessionStore.class) {
            sessions = new ArrayList<>(SESSIONS.values());
        }
        for (Session session : sessions) {
            if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && !pkg.equals(session.pkg)) continue;
            synchronized (session) {
                session.dirty = true;
                session.changes++;
                if (session.pending != null) session.pending.recycle();
                AccessibilityNodeInfo source = event.getSource();
                session.pending = source == null ? null : AccessibilityNodeInfo.obtain(source);
                if (source != null) source.recycle();
                session.pendingWindow = event.getWindowId();
            }
        }
    }

    static void trim() {
        while (SESSIONS.size() > 3) {
            Iterator<Map.Entry<String, Session>> iterator = SESSIONS.entrySet().iterator();
            Session removed = iterator.next().getValue();
            iterator.remove();
            recyclePending(removed);
            if (removed.autosave) SessionPersistence.autosave(removed);
            if (removed.id.equals(defaultId)) defaultId = null;
        }
    }

    private static void recyclePending(Session session) {
        synchronized (session) {
            if (session.pending != null) session.pending.recycle();
            session.pending = null;
        }
    }

    private static Session require(JSONObject params) throws AgentError {
        String id = params == null ? "" : params.optString("sessionId");
        if (id.isEmpty()) id = defaultId == null ? "" : defaultId;
        Session session = SESSIONS.get(id);
        if (session == null) throw new AgentError("SESSION_NOT_FOUND", "call session.analyze first");
        session.lastUsedAt = System.currentTimeMillis();
        return session;
    }

    static synchronized void checkWrite(JSONObject params) throws Exception {
        if (!params.has("sessionId") && !params.has("epoch") && !params.has("nodeId")
                && !params.has("matchKey") && !params.has("flag")) return;
        Session session = require(params);
        if (!session.source.equals("live")) throw new AgentError("SNAPSHOT_READONLY", "analyze a live session before input");
        checkEpoch(session, params);
        if (params.has("nodeId") && !params.has("epoch")) throw new AgentError("INVALID_ARGUMENT", "epoch is required for nodeId input");
    }

    private static void checkEpoch(Session session, JSONObject params) throws Exception {
        if (!params.has("epoch")) return;
        if (params.getInt("epoch") == session.epoch) return;
        if (params.optString("onStale").equals("reanalyze")) {
            session.patchMs = 0;
            fullAnalysis(session, params, "EPOCH_MISMATCH");
            return;
        }
        throw new AgentError("STALE_HANDLE", "session epoch " + session.epoch + ", got " + params.getInt("epoch"));
    }

    private static SessionNode node(Session session, JSONObject params) throws Exception {
        if (!params.optString("nodeId").isEmpty()) {
            SessionNode node = session.byId.get(params.getString("nodeId"));
            if (node == null) throw new AgentError("STALE_HANDLE", "unknown nodeId");
            return node;
        }
        if (!params.optString("matchKey").isEmpty()) {
            List<SessionNode> nodes = session.byMatch.get(params.getString("matchKey"));
            if (nodes == null || nodes.isEmpty()) throw new AgentError("NOT_FOUND", "matchKey not found");
            if (nodes.size() > 1 && !params.has("pick")) throw new AgentError("AMBIGUOUS_MATCH", "matchKey has multiple candidates");
            return nodes.get(0);
        }
        if (session.pointer != null) {
            SessionNode current = session.byId.get(session.pointer);
            if (current == null) throw new AgentError("STALE_HANDLE", "current node pointer is stale");
            return current;
        }
        JSONArray hits = find(params).getJSONArray("hits");
        if (hits.length() == 0) throw new AgentError("NOT_FOUND", "no current node");
        return session.byId.get(hits.getJSONObject(0).getString("nodeId"));
    }

    private static String path(Session session, SessionNode node) {
        StringBuilder result = new StringBuilder();
        while (node != null) {
            if (result.length() > 0) result.insert(0, "/");
            result.insert(0, node.cls + "[" + node.index + "]");
            node = node.parentId == null ? null : session.byId.get(node.parentId);
        }
        return result.toString();
    }

    private static JSONObject info(Session session, boolean reused, boolean reanalyzed) throws Exception {
        return new JSONObject().put("sessionId", session.id).put("epoch", session.epoch).put("pkg", session.pkg)
                .put("activity", session.activity).put("nodeCount", session.nodes.size()).put("dirty", session.dirty)
                .put("source", session.source).put("stale", session.stale).put("createdAt", session.createdAt)
                .put("lastUsedAt", session.lastUsedAt).put("reused", reused).put("patchMs", session.patchMs)
                .put("fallbackReason", session.fallbackReason == null ? JSONObject.NULL : session.fallbackReason)
                .put("reanalyzed", reanalyzed || session.reanalyzed).put("via", session.backend).put("degraded", false);
    }

    static String lastFallbackReason() {
        return lastFallbackReason;
    }

    private static void index(Session session) {
        session.byId.clear();
        session.byMatch.clear();
        for (SessionNode node : session.nodes) {
            session.byId.put(node.nodeId, node);
            session.byMatch.computeIfAbsent(node.matchKey, ignored -> new ArrayList<>()).add(node);
        }
    }

    private static AccessibilityService service() throws AgentError {
        AccessibilityService service = AgentServer.accessibility();
        if (service == null) throw new AgentError("A11Y_OFF", "accessibility service is not connected");
        return service;
    }

    private static String foregroundPackage() {
        AccessibilityService service = AgentServer.accessibility();
        if (service != null) {
            java.util.concurrent.Future<String> task = ANALYSIS_EXECUTOR.submit(() -> {
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root == null) return "";
                try {
                    return SessionNode.value(root.getPackageName());
                } finally {
                    root.recycle();
                }
            });
            try {
                return task.get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                task.cancel(true);
                return null;
            } catch (Exception e) {
                return "";
            }
        } else {
            try {
                String value = RootClient.call("sys.info", new JSONObject()).optString("foregroundPackage");
                if (!value.isEmpty()) return value;
            } catch (Exception ignored) {
            }
        }
        String value = AgentServer.events.foreground();
        return value == null ? "" : value;
    }

    private static String windowType(int type) {
        switch (type) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "app";
            case AccessibilityWindowInfo.TYPE_INPUT_METHOD: return "ime";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "system";
            case AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY: return "overlay";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "divider";
            default: return "unknown";
        }
    }

    private static boolean wantedWindow(Object filter, int id, String type) {
        if (filter == null || filter == JSONObject.NULL) return true;
        String value = String.valueOf(filter);
        if (value.equals("all") || value.isEmpty()) return true;
        if (value.equals("app")) return type.equals("app");
        try {
            return Integer.parseInt(value) == id;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
