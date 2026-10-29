package dev.dsh.agent;

import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

final class UiSelector {
    private final String input;
    private int offset;
    private int depth;

    private UiSelector(String input) {
        this.input = input;
    }

    static Predicate<Map<String, Object>> parse(String input) {
        if (input == null || input.trim().isEmpty() || input.length() > 2048) throw new IllegalArgumentException("invalid selector length");
        UiSelector parser = new UiSelector(input);
        Predicate<Map<String, Object>> result = parser.or();
        parser.space();
        if (parser.offset != input.length()) throw parser.error();
        return result;
    }

    private Predicate<Map<String, Object>> or() {
        Predicate<Map<String, Object>> left = and();
        while (take("||")) {
            Predicate<Map<String, Object>> right = and(), previous = left;
            left = node -> previous.test(node) || right.test(node);
        }
        return left;
    }

    private Predicate<Map<String, Object>> and() {
        Predicate<Map<String, Object>> left = unary();
        while (take("&&")) {
            Predicate<Map<String, Object>> right = unary(), previous = left;
            left = node -> previous.test(node) && right.test(node);
        }
        return left;
    }

    private Predicate<Map<String, Object>> unary() {
        space();
        if (take("!")) {
            Predicate<Map<String, Object>> inner = unary();
            return node -> !inner.test(node);
        }
        if (take("(")) {
            if (++depth > 8) throw error();
            Predicate<Map<String, Object>> inner = or();
            depth--;
            if (!take(")")) throw error();
            return inner;
        }
        String field = token();
        if (!field.matches("[A-Za-z][A-Za-z0-9]*")) throw error();
        int saved = offset;
        String operator;
        try {
            operator = operator();
        } catch (IllegalArgumentException e) {
            offset = saved;
            return node -> {
                Object value = node.get(field);
                return value instanceof Boolean ? (Boolean) value : value instanceof Number
                        ? ((Number) value).doubleValue() != 0 : "true".equals(value);
            };
        }
        String expected = quotedOrToken();
        return node -> compareField(node.get(field), operator, expected, false, field);
    }

    static boolean compareField(Object actual, String operator, String expected, boolean caseSensitive, String field) {
        if ((field.equals("class") || field.equals("cls")) && actual != null && (operator.equals("=") || operator.equals("==") || operator.equals("!="))) {
            String value = String.valueOf(actual), wanted = caseSensitive ? expected : expected.toLowerCase(Locale.ROOT);
            String lower = caseSensitive ? value : value.toLowerCase(Locale.ROOT);
            boolean match = lower.equals(wanted) || lower.endsWith("." + wanted);
            return operator.equals("!=") ? !match : match;
        }
        return compare(actual, operator, expected, caseSensitive);
    }

    static boolean compare(Object actual, String operator, String expected, boolean caseSensitive) {
        if (actual == null || actual.getClass().getName().equals("org.json.JSONObject$Null")) return operator.equals("!=");
        String value = String.valueOf(actual);
        if (operator.equals(">") || operator.equals("<") || operator.equals(">=") || operator.equals("<=")) {
            double left = actual instanceof Number ? ((Number) actual).doubleValue() : Double.parseDouble(value);
            double right = Double.parseDouble(expected);
            int comparison = Double.compare(left, right);
            return operator.equals(">") ? comparison > 0 : operator.equals("<") ? comparison < 0
                    : operator.equals(">=") ? comparison >= 0 : comparison <= 0;
        }
        if (actual instanceof Boolean || actual instanceof Number) {
            boolean equal = value.equals(expected);
            return operator.equals("!=") ? !equal : equal && (operator.equals("=") || operator.equals("=="));
        }
        String lower = caseSensitive ? value : value.toLowerCase(Locale.ROOT);
        String wanted = caseSensitive ? expected : expected.toLowerCase(Locale.ROOT);
        switch (operator) {
            case "=": case "==": return lower.equals(wanted);
            case "!=": return !lower.equals(wanted);
            case "~": case "~=": return lower.contains(wanted);
            case "^=": return lower.startsWith(wanted);
            case "$=": return lower.endsWith(wanted);
            default: return false;
        }
    }

    private String operator() {
        space();
        for (String candidate : new String[]{"!=", "^=", "$=", "~=", ">=", "<=", "==", ">", "<", "=", "~"}) {
            if (take(candidate)) return candidate;
        }
        throw error();
    }

    private String token() {
        space();
        int start = offset;
        while (offset < input.length()) {
            char c = input.charAt(offset);
            if (Character.isWhitespace(c) || "()!~=^$<>".indexOf(c) >= 0) break;
            offset++;
        }
        if (start == offset) throw error();
        return input.substring(start, offset);
    }

    private String quotedOrToken() {
        space();
        if (offset >= input.length() || (input.charAt(offset) != '"' && input.charAt(offset) != '\'')) return token();
        char quote = input.charAt(offset++);
        StringBuilder out = new StringBuilder();
        while (offset < input.length()) {
            char c = input.charAt(offset++);
            if (c == quote) return out.toString();
            if (c == '\\' && offset < input.length()) {
                char escaped = input.charAt(offset++);
                if (escaped == 'n') c = '\n';
                else if (escaped == 'r') c = '\r';
                else if (escaped == 't') c = '\t';
                else c = escaped;
            }
            out.append(c);
            if (out.length() > 512) throw error();
        }
        throw error();
    }

    private boolean take(String text) {
        space();
        if (!input.startsWith(text, offset)) return false;
        offset += text.length();
        return true;
    }

    private void space() {
        while (offset < input.length() && Character.isWhitespace(input.charAt(offset))) offset++;
    }

    private IllegalArgumentException error() {
        return new IllegalArgumentException("invalid selector at offset " + offset);
    }
}
