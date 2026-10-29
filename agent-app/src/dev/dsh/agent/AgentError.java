package dev.dsh.agent;

final class AgentError extends Exception {
    final String code;
    final String via;
    final boolean retryable;
    final org.json.JSONObject detail = new org.json.JSONObject();
    String resultState = "OK";
    boolean replayed;

    AgentError(String code, String message) {
        this(code, message, "none", false);
    }

    AgentError(String code, String message, String via, boolean retryable) {
        super(message);
        this.code = code;
        this.via = via;
        this.retryable = retryable;
    }

    AgentError detail(String key, Object value) {
        try {
            detail.put(key, value);
        } catch (Exception ignored) {
        }
        return this;
    }

    static AgentError unavailable(String message) {
        return new AgentError("UNAVAILABLE", message);
    }

    static void confirm(org.json.JSONObject params) throws AgentError {
        if (!"YES".equals(params.optString("confirm"))) {
            throw new AgentError("CONFIRM_REQUIRED", "set confirm to YES for this operation");
        }
    }
}
