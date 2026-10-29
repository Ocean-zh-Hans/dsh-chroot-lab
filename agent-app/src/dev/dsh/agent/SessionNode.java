package dev.dsh.agent;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

final class SessionNode {
    String nodeId;
    long epoch;
    String matchKey;
    String path = "";
    int windowId;
    String windowType = "app";
    String parentId;
    int depth;
    int index;
    String cls = "";
    String pkg = "";
    String text = "";
    String textHash = "";
    String desc = "";
    String hint = "";
    String viewId = "";
    boolean isPassword;
    int inputType;
    int x;
    int y;
    int width;
    int height;
    boolean clickable;
    boolean longClickable;
    boolean scrollable;
    boolean checkable;
    boolean checked;
    boolean selected;
    boolean focused;
    boolean enabled;
    boolean visible;
    boolean editable;
    int childCount;

    static SessionNode read(AccessibilityNodeInfo nativeNode, String id, long epoch, String parentId,
            int depth, int index, String path, int windowId, String windowType) {
        SessionNode node = new SessionNode();
        node.nodeId = id;
        node.epoch = epoch;
        node.parentId = parentId;
        node.depth = depth;
        node.index = index;
        node.path = path;
        node.windowId = windowId;
        node.windowType = windowType;
        node.pkg = value(nativeNode.getPackageName());
        node.cls = value(nativeNode.getClassName());
        node.viewId = value(nativeNode.getViewIdResourceName());
        node.isPassword = nativeNode.isPassword();
        node.text = node.isPassword ? "***" : value(nativeNode.getText());
        node.desc = node.isPassword ? "***" : value(nativeNode.getContentDescription());
        node.textHash = hash(node.text);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            node.hint = node.isPassword ? "***" : value(nativeNode.getHintText());
            node.inputType = nativeNode.getInputType();
        }
        Rect bounds = new Rect();
        nativeNode.getBoundsInScreen(bounds);
        node.x = bounds.left;
        node.y = bounds.top;
        node.width = Math.max(0, bounds.width());
        node.height = Math.max(0, bounds.height());
        node.clickable = nativeNode.isClickable();
        node.longClickable = nativeNode.isLongClickable();
        node.scrollable = nativeNode.isScrollable();
        node.checkable = nativeNode.isCheckable();
        node.checked = nativeNode.isChecked();
        node.selected = nativeNode.isSelected();
        node.focused = nativeNode.isFocused();
        node.enabled = nativeNode.isEnabled();
        node.visible = nativeNode.isVisibleToUser();
        node.editable = nativeNode.isEditable();
        node.childCount = nativeNode.getChildCount();
        node.matchKey = key(node.pkg, node.windowType, path, own(node.cls, node.viewId, index), node.text, node.desc);
        return node;
    }

    static String own(String cls, String viewId, int index) {
        return viewId == null || viewId.isEmpty() ? "cls:" + cls + "#" + index : "vid:" + viewId;
    }

    static String key(String pkg, String windowType, String path, String own, String text, String desc) {
        String content = (text == null ? "" : text) + "|" + (desc == null ? "" : desc);
        if (content.length() > 64) content = content.substring(0, 64);
        return "v1:" + hash("1|" + pkg + "|" + windowType + "|" + path + "|" + own + "|" + content);
    }

    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    JSONObject json() throws Exception {
        JSONObject result = new JSONObject();
        result.put("nodeId", nodeId).put("epoch", epoch).put("matchKey", matchKey)
                .put("windowId", windowId).put("windowType", windowType)
                .put("parentId", parentId == null ? JSONObject.NULL : parentId).put("depth", depth).put("index", index)
                .put("cls", cls).put("pkg", pkg).put("text", text).put("desc", desc).put("hint", hint)
                .put("viewId", viewId.isEmpty() ? JSONObject.NULL : viewId).put("isPassword", isPassword)
                .put("inputType", inputType).put("bounds", bounds())
                .put("clickable", clickable).put("longClickable", longClickable).put("scrollable", scrollable)
                .put("checkable", checkable).put("checked", checked).put("selected", selected)
                .put("focused", focused).put("enabled", enabled).put("visible", visible).put("editable", editable)
                .put("childCount", childCount).put("actions", actions()).put("textLen", text.length());
        return result;
    }

    JSONObject legacyJson() throws Exception {
        JSONObject result = json();
        result.put("class", cls).put("package", pkg).put("parent", parentId == null ? "" : parentId);
        result.put("bounds", new JSONArray().put(x).put(y).put(x + width).put(y + height));
        result.put("password", isPassword);
        return result;
    }

    JSONArray bounds() {
        return new JSONArray().put(x).put(y).put(width).put(height);
    }

    private JSONArray actions() {
        JSONArray result = new JSONArray();
        if (clickable) result.put("click");
        if (longClickable) result.put("longClick");
        if (editable) result.put("setText");
        if (scrollable) result.put("scrollForward").put("scrollBackward");
        return result;
    }

    Map<String, Object> map() throws Exception {
        Map<String, Object> result = new HashMap<>();
        JSONObject json = json();
        for (java.util.Iterator<String> it = json.keys(); it.hasNext();) {
            String key = it.next();
            result.put(key, json.opt(key));
        }
        result.put("class", cls);
        result.put("package", pkg);
        return result;
    }

    static SessionNode fromJson(JSONObject json) {
        SessionNode node = new SessionNode();
        node.nodeId = json.optString("nodeId");
        node.epoch = json.optLong("epoch");
        node.matchKey = json.optString("matchKey");
        node.path = json.optString("structurePath");
        node.windowId = json.optInt("windowId");
        node.windowType = json.optString("windowType", "app");
        node.parentId = json.isNull("parentId") ? null : json.optString("parentId");
        node.depth = json.optInt("depth");
        node.index = json.optInt("index");
        node.cls = json.optString("cls");
        node.pkg = json.optString("pkg");
        node.text = json.optString("text");
        node.textHash = json.optString("textHash", hash(node.text));
        node.desc = json.optString("desc");
        node.hint = json.optString("hint");
        node.viewId = json.isNull("viewId") ? "" : json.optString("viewId");
        node.isPassword = json.optBoolean("isPassword");
        node.inputType = json.optInt("inputType");
        JSONArray bounds = json.optJSONArray("bounds");
        if (bounds != null && bounds.length() == 4) {
            node.x = bounds.optInt(0);
            node.y = bounds.optInt(1);
            node.width = bounds.optInt(2);
            node.height = bounds.optInt(3);
        }
        node.clickable = json.optBoolean("clickable");
        node.longClickable = json.optBoolean("longClickable");
        node.scrollable = json.optBoolean("scrollable");
        node.checkable = json.optBoolean("checkable");
        node.checked = json.optBoolean("checked");
        node.selected = json.optBoolean("selected");
        node.focused = json.optBoolean("focused");
        node.enabled = json.optBoolean("enabled");
        node.visible = json.optBoolean("visible");
        node.editable = json.optBoolean("editable");
        node.childCount = json.optInt("childCount");
        return node;
    }

    JSONObject savedJson(boolean includeText) throws Exception {
        JSONObject result = json();
        result.put("structurePath", path);
        result.put("textHash", textHash);
        if (!includeText || isPassword) {
            result.put("text", isPassword ? "***" : "").put("desc", isPassword ? "***" : "").put("hint", "");
        }
        return result;
    }

    static String value(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
