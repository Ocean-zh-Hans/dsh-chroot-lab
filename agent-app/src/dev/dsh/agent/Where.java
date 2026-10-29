package dev.dsh.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

final class Where {
    private static final Set<String> FIELDS = new HashSet<>(Arrays.asList(
            "text", "desc", "hint", "viewId", "cls", "class", "pkg", "package", "index", "depth",
            "clickable", "longClickable", "editable", "enabled", "checked", "focused", "visible",
            "scrollable", "checkable", "selected", "isPassword", "inputType", "windowId", "nodeId", "matchKey"));

    private Where() {
    }

    static Predicate<Map<String, Object>> predicate(JSONObject where, boolean caseSensitive) throws AgentError {
        if (where == null || where.length() == 0) return node -> true;
        try {
            java.util.List<Predicate<Map<String, Object>>> clauses = new java.util.ArrayList<>();
            for (java.util.Iterator<String> it = where.keys(); it.hasNext();) {
                String key = it.next();
                String operator = "=";
                String field = key;
                for (String candidate : new String[]{"~=", "!=", "^=", "$=", ">=", "<=", ">", "<", "~"}) {
                    if (key.endsWith(candidate)) {
                        field = key.substring(0, key.length() - candidate.length());
                        operator = candidate.equals("~") ? "~=" : candidate;
                        break;
                    }
                }
                if (!FIELDS.contains(field)) throw new IllegalArgumentException("unknown query field: " + field);
                final String fieldName = field;
                Object expected = where.get(key);
                if (expected instanceof JSONObject && ((JSONObject) expected).has("in")) {
                    expected = ((JSONObject) expected).get("in");
                    operator = "in";
                } else if (key.endsWith(" in")) {
                    field = key.substring(0, key.length() - 3);
                    operator = "in";
                }
                if (expected instanceof JSONArray) operator = "in";
                if (operator.equals("in")) {
                    JSONArray values = (JSONArray) expected;
                    if (values.length() > 20) throw new IllegalArgumentException("too many values");
                    java.util.List<String> options = new java.util.ArrayList<>();
                    for (int i = 0; i < values.length(); i++) options.add(String.valueOf(values.get(i)));
                    clauses.add(node -> {
                        for (String option : options) if (UiSelector.compareField(node.get(fieldName), "=", option, caseSensitive, fieldName)) return true;
                        return false;
                    });
                } else {
                    String value = String.valueOf(expected);
                    if (value.length() > 512) throw new IllegalArgumentException("query value is too long");
                    String op = operator;
                    clauses.add(node -> UiSelector.compareField(node.get(fieldName), op, value, caseSensitive, fieldName));
                }
            }
            return node -> {
                for (Predicate<Map<String, Object>> clause : clauses) if (!clause.test(node)) return false;
                return true;
            };
        } catch (IllegalArgumentException | org.json.JSONException e) {
            throw new AgentError("INVALID_PARAMS", e.getMessage());
        }
    }
}
