package dev.dsh.agent;

import java.util.HashSet;
import java.util.Set;

final class AgentMethods {
    static final int API_VERSION = 3;
    static final String VERSION = "0.3.0";
    static final String BUILD_TIME = "2026-10-29";

    private static final Set<String> WRITES = new HashSet<>(java.util.Arrays.asList(
            "ui.act", "ui.batch", "ui.fill", "ui.scrollUntil", "ui.scroll", "ui.press", "ui.ime",
            "policy.set", "dev.install", "dev.uninstall"));

    private AgentMethods() {
    }

    static boolean isMutation(String method) {
        return WRITES.contains(method);
    }
}
