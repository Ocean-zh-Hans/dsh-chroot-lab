package dev.dsh.agent;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

final class UiModel {
    private UiModel() {
    }

    static Map<String, Object> map(JSONObject json) {
        Map<String, Object> result = new HashMap<>();
        for (java.util.Iterator<String> it = json.keys(); it.hasNext();) {
            String key = it.next();
            result.put(key, json.opt(key));
        }
        return result;
    }

    static String string(CharSequence text) {
        return text == null ? "" : text.toString();
    }
}
