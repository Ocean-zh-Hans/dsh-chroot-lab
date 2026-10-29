package dev.dsh.agent;

import java.io.File;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

public final class AgentCoreTest {
    public static void main(String[] args) throws Exception {
        Map<String, Object> node = new HashMap<>();
        node.put("text", "搜索设置");
        node.put("viewId", "dev.dsh.agent:id/search_button");
        node.put("class", "android.widget.Button");
        node.put("clickable", true);
        node.put("index", 2);
        Predicate<Map<String, Object>> selector = UiSelector.parse(
                "text~=\"搜索\" && viewId~=\"search\" && class=Button && index=2 && clickable=true");
        check(selector.test(node), "selector should match");
        check(!UiSelector.parse("text=\"搜索\" || clickable=false").test(node), "exact text must not match substring");
        check(UiSelector.parse("!(text=\"其他\") && (clickable=true || index=0)").test(node), "boolean precedence");
        check(UiSelector.parse("clickable && text~='搜索'").test(node), "boolean shorthand and single quotes");
        node.put("text", "com.example");
        check(!UiSelector.parse("text=\"example\"").test(node), "exact text must not match a package suffix");
        node.put("text", "说\"你好\"\n");
        check(UiSelector.parse("text=\"说\\\"你好\\\"\\n\"").test(node), "quoted escapes");
        expectInvalid("text=");
        expectInvalid("text=\"x\" &&");
        expectInvalid("clickable=true nonsense");

        Cron cron = Cron.parse("*/15 9 * * 1-5");
        ZonedDateTime start = ZonedDateTime.of(2026, 10, 28, 8, 50, 0, 0, ZoneId.of("Asia/Shanghai"));
        ZonedDateTime next = ZonedDateTime.of(2026, 10, 28, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
        check(cron.next(start.toInstant().toEpochMilli(), ZoneId.of("Asia/Shanghai")) == next.toInstant().toEpochMilli(), "cron next");
        check(Cron.parse("0 0 * * 0").next(0, ZoneId.of("UTC")) > 0, "Sunday cron");
        expectInvalid("61 * * * *");
        expectInvalid("* * * *");

        File root = Files.createTempDirectory("dsh-agent-paths").toFile();
        File child = SafePaths.resolve(root, "a/b.txt", false);
        check(child.getPath().startsWith(root.getCanonicalPath() + File.separator), "child path");
        expectInvalidPath(root, "../escape");
        expectInvalidPath(root, "/absolute");
        child.getParentFile().mkdirs();
        Files.write(child.toPath(), new byte[]{1});
        check(SafePaths.list(child.getParentFile()).size() == 1, "list files");
        SafePaths.removeTree(child.getParentFile());
        check(!child.exists(), "recursive remove");
        SafePaths.removeTree(root);

        for (String method : new String[]{"ui.click", "ui.scrollUntil", "ime.show", "ime.hide", "ime.set", "shell.exec", "security.tokenPolicy"}) {
            check(AgentMethods.isMutation(method), method + " must be gated by read-only mode");
        }
        for (String method : new String[]{"ui.query", "ui.find", "sys.info", "job.start", "macro"}) {
            check(!AgentMethods.isMutation(method), method + " must dispatch nested reads and writes separately");
        }
        System.out.println("AgentCoreTest: ok");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void expectInvalid(String selector) {
        try {
            UiSelector.parse(selector);
            throw new AssertionError("selector should be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void expectInvalidPath(File root, String path) throws Exception {
        try {
            SafePaths.resolve(root, path, false);
            throw new AssertionError("path should be rejected");
        } catch (java.io.IOException expected) {
        }
    }
}
