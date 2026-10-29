package dev.dsh.agent;

import org.json.JSONArray;
import org.json.JSONObject;

final class Diagnostics {
    private static final int LIMIT = 512;
    private static final JSONObject[] ENTRIES = new JSONObject[LIMIT];
    private static long next;
    private static long failures;
    private static long cooldownUntil;

    private Diagnostics() {
    }

    static synchronized void record(String method, String via, String error, long latencyMs) {
        JSONObject item = new JSONObject();
        try {
            item.put("seq", ++next).put("time", System.currentTimeMillis()).put("method", method)
                    .put("via", via).put("latencyMs", latencyMs).put("error", error);
            ENTRIES[(int) (next % LIMIT)] = item;
        } catch (Exception ignored) {
        }
        if (error.isEmpty()) failures = 0;
        else {
            failures++;
            if (failures >= 3) cooldownUntil = System.currentTimeMillis() + 30000;
        }
    }

    static synchronized JSONArray tail(int requested) {
        int limit = Math.max(1, Math.min(100, requested));
        JSONArray result = new JSONArray();
        long first = Math.max(1, next - limit + 1);
        for (long seq = first; seq <= next; seq++) {
            JSONObject item = ENTRIES[(int) (seq % LIMIT)];
            if (item != null && item.optLong("seq") == seq) result.put(item);
        }
        return result;
    }

    static synchronized void clear() {
        for (int i = 0; i < LIMIT; i++) ENTRIES[i] = null;
    }

    static synchronized boolean coolingDown() {
        return System.currentTimeMillis() < cooldownUntil;
    }

    static synchronized long cooldownRemaining() {
        return Math.max(0, cooldownUntil - System.currentTimeMillis());
    }
}
