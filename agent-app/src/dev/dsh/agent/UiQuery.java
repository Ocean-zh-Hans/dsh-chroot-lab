package dev.dsh.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

final class UiQuery {
    private static final class Stage {
        final String text;
        final int position;

        Stage(String text, int position) {
            this.text = text;
            this.position = position;
        }
    }

    private UiQuery() {
    }

    static JSONObject execute(JSONObject params) throws Exception {
        String expression = params.optString("expr");
        if (expression.isEmpty() || expression.length() > 2048) throw parse("expression length", 0);
        List<Stage> stages = split(expression);
        if (stages.size() > 20) throw parse("at most 20 stages", 0);
        List<JSONObject> hits = new ArrayList<>();
        JSONArray trace = new JSONArray();
        long started = System.nanoTime();
        long deadline = started + Math.max(1, Math.min(120000, params.optLong("timeoutMs", 5000))) * 1000000;
        String sessionId = params.optString("sessionId");
        int epoch = 0;
        int actionStage = -1;
        long count = -1;
        for (int i = 0; i < stages.size(); i++) {
            if (System.nanoTime() > deadline) throw new AgentError("TIMEOUT", "query exceeded its deadline");
            Stage stage = stages.get(i);
            long stageStarted = System.nanoTime();
            String op = stage.text.split("\\s+", 2)[0];
            String argument = stage.text.length() > op.length() ? stage.text.substring(op.length()).trim() : "";
            JSONObject step = new JSONObject().put("stage", i).put("op", op);
            try {
                switch (op) {
                    case "find": {
                        if (argument.isEmpty()) throw parse("selector", stage.position);
                        JSONObject request = new JSONObject().put("sessionId", sessionId).put("selector", argument)
                                .put("limit", 200).put("fields", new JSONArray().put("text").put("cls").put("bounds")
                                        .put("clickable").put("editable").put("visible").put("enabled"));
                        JSONObject result = SessionStore.find(request);
                        sessionId = result.getString("sessionId");
                        epoch = result.getInt("epoch");
                        hits = list(result.getJSONArray("hits"));
                        break;
                    }
                    case "where": {
                        Predicate<Map<String, Object>> predicate = predicate(argument, stage.position);
                        List<JSONObject> filtered = new ArrayList<>();
                        for (JSONObject hit : hits) if (predicate.test(UiModel.map(hit))) filtered.add(hit);
                        hits = filtered;
                        break;
                    }
                    case "first":
                        if (hits.size() > 1) hits = new ArrayList<>(hits.subList(0, 1));
                        break;
                    case "last":
                        if (hits.size() > 1) hits = new ArrayList<>(hits.subList(hits.size() - 1, hits.size()));
                        break;
                    case "nth": {
                        int index;
                        try {
                            index = Integer.parseInt(argument);
                        } catch (NumberFormatException e) {
                            throw parse("integer", stage.position);
                        }
                        if (index < 0 || index >= hits.size()) throw new AgentError("NOT_FOUND", "nth is outside the result set");
                        hits = new ArrayList<>(hits.subList(index, index + 1));
                        break;
                    }
                    case "count":
                        count = hits.size();
                        step.put("count", count);
                        break;
                    case "click": case "longClick": case "setText": case "clearText": {
                        if (hits.isEmpty()) throw new AgentError("NOT_FOUND", "no target for action");
                        if (hits.size() != 1) throw new AgentError("AMBIGUOUS_MATCH", "exactly one target is required for an action");
                        JSONObject hit = hits.get(0);
                        JSONObject request = new JSONObject().put("sessionId", sessionId).put("epoch", epoch)
                                .put("matchKey", hit.getString("matchKey"));
                        if (op.equals("setText")) request.put("value", literal(argument, stage.position));
                        if (op.equals("clearText")) request.put("value", "");
                        AgentServer.dispatch("ui." + op, request);
                        JSONObject refreshed = SessionStore.afterAction(new JSONObject().put("sessionId", sessionId));
                        epoch = refreshed.getInt("epoch");
                        actionStage = i;
                        hits = new ArrayList<>();
                        break;
                    }
                    case "scrollUntil": {
                        JSONObject request = new JSONObject().put("sessionId", sessionId).put("selector", argument);
                        JSONObject result = (JSONObject) AgentServer.dispatch("ui.scrollUntil", request);
                        epoch = result.optInt("epoch", epoch);
                        hits = list(result.getJSONArray("hits"));
                        actionStage = i;
                        break;
                    }
                    case "wait": {
                        String selector = argument;
                        int timeout = 3000;
                        int split = lastSpaceOutsideQuotes(argument);
                        if (split > 0) {
                            String suffix = argument.substring(split + 1);
                            if (suffix.matches("[0-9]+")) {
                                timeout = Math.min(120000, Integer.parseInt(suffix));
                                selector = argument.substring(0, split).trim();
                            }
                        }
                        JSONObject request = new JSONObject().put("sessionId", sessionId).put("selector", selector)
                                .put("timeoutMs", Math.min(timeout, Math.max(1, (deadline - System.nanoTime()) / 1000000)));
                        AccessibilityBackend.wait(request);
                        JSONObject result = SessionStore.find(new JSONObject().put("sessionId", sessionId)
                                .put("selector", selector).put("limit", 200));
                        epoch = result.getInt("epoch");
                        hits = list(result.getJSONArray("hits"));
                        break;
                    }
                    case "path": case "refs": {
                        if (hits.size() != 1) throw new AgentError("AMBIGUOUS_MATCH", "exactly one target is required");
                        JSONObject request = new JSONObject().put("sessionId", sessionId).put("nodeId", hits.get(0).getString("nodeId"));
                        JSONObject result = op.equals("path") ? SessionStore.path(request) : SessionStore.refs(request);
                        step.put("value", result);
                        break;
                    }
                    default: throw parse("valid stage", stage.position);
                }
                trace.put(step.put("hits", hits.size()).put("ms", (System.nanoTime() - stageStarted) / 1000000).put("ok", true));
            } catch (AgentError error) {
                trace.put(step.put("hits", hits.size()).put("ms", (System.nanoTime() - stageStarted) / 1000000).put("ok", false));
                if (actionStage >= 0 && i > actionStage && (error.code.equals("NOT_FOUND") || error.code.equals("STALE_HANDLE"))) {
                    error = new AgentError("POST_ACTION_STALE", "target changed after an earlier action");
                }
                error.detail("failedAt", i).detail("trace", trace);
                throw error;
            } catch (IllegalArgumentException error) {
                trace.put(step.put("ok", false));
                throw new AgentError("PARSE_ERROR", error.getMessage()).detail("failedAt", i)
                        .detail("position", stage.position).detail("expected", "valid stage").detail("trace", trace);
            }
        }
        JSONObject result = new JSONObject().put("sessionId", sessionId).put("epoch", epoch).put("completed", true)
                .put("hits", new JSONArray(hits)).put("trace", trace);
        if (count >= 0) result.put("count", count);
        return result;
    }

    private static List<JSONObject> list(JSONArray array) {
        List<JSONObject> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.optJSONObject(i));
        return result;
    }

    private static Predicate<Map<String, Object>> predicate(String input, int position) throws AgentError {
        try {
            return UiSelector.parse(input);
        } catch (IllegalArgumentException e) {
            throw parse(e.getMessage(), position);
        }
    }

    private static String literal(String input, int position) throws AgentError {
        if (input.length() < 2 || (input.charAt(0) != '\'' && input.charAt(0) != '"')) throw parse("quoted string", position);
        char quote = input.charAt(0);
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < input.length(); i++) {
            char value = input.charAt(i);
            if (value == quote) {
                if (i != input.length() - 1) throw parse("end of string", position + i);
                return result.toString();
            }
            if (value == '\\' && i + 1 < input.length()) value = input.charAt(++i);
            result.append(value);
            if (result.length() > 512) throw parse("string no longer than 512 characters", position);
        }
        throw parse("closing quote", position);
    }

    private static List<Stage> split(String expression) throws AgentError {
        List<Stage> result = new ArrayList<>();
        char quote = 0;
        boolean escaped = false;
        int start = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && quote != 0) {
                escaped = true;
                continue;
            }
            if (c == '\'' || c == '"') {
                if (quote == 0) quote = c;
                else if (quote == c) quote = 0;
            } else if (c == '|' && quote == 0) {
                if (i + 1 < expression.length() && (expression.charAt(i + 1) == '|' || expression.charAt(i + 1) == '&')) {
                    i++;
                    continue;
                }
                add(result, expression.substring(start, i), start);
                start = i + 1;
            }
        }
        if (quote != 0) throw parse("closing quote", expression.length());
        add(result, expression.substring(start), start);
        return result;
    }

    private static void add(List<Stage> stages, String text, int position) throws AgentError {
        if (text.trim().isEmpty()) throw parse("stage", position);
        stages.add(new Stage(text.trim(), position));
    }

    private static int lastSpaceOutsideQuotes(String text) {
        char quote = 0;
        int result = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '\'' || c == '"') && (i == 0 || text.charAt(i - 1) != '\\')) quote = quote == 0 ? c : quote == c ? 0 : quote;
            if (Character.isWhitespace(c) && quote == 0) result = i;
        }
        return result;
    }

    private static AgentError parse(String expected, int position) {
        return new AgentError("PARSE_ERROR", "invalid query syntax").detail("position", position).detail("expected", expected);
    }
}
