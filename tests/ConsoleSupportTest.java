package dev.chroot.console;

public final class ConsoleSupportTest {
    public static void main(String[] args) {
        check(ConsoleSupport.isLoopbackHttpUrl("http://127.0.0.1:1234/?token=test"), "IPv4 loopback");
        check(ConsoleSupport.isLoopbackHttpUrl("http://localhost:7681/"), "localhost");
        reject("http://127.0.0.1.evil.example:1234/");
        reject("http://127.0.0.1:80@evil.example/");
        reject("https://127.0.0.1:1234/");
        reject("http://127.0.0.1/");
        reject("http://127.0.0.1:70000/");
        reject("not a URL");
        check(new ConsoleSupport.Result("ok", 0).success(), "zero exit status");
        check(!new ConsoleSupport.Result("bad", 4).success(), "nonzero exit status");
        check(ConsoleSupport.stateLabel("running").equals("运行中"), "localized running state");
        check(ConsoleSupport.stateLabel("stopped").equals("已停止"), "localized stopped state");
        check(ConsoleSupport.stateLabel("recovery-required").equals("需要恢复"), "localized recovery state");
        check(ConsoleSupport.stateLabel("future-state").equals("future-state"), "unknown state preserved");
        check(ConsoleSupport.stateLabel(null).equals("未知"), "null state");
        String redacted = ConsoleSupport.redactToken("http://127.0.0.1:1234/?token=test-secret");
        check(redacted.equals("http://127.0.0.1:1234/?token=•••"), "display token redacted");
        check(!redacted.contains("test-secret"), "display token removed");
        ConsoleSupport.Result command = ConsoleSupport.runCommand(5, "sh", "-c", "printf command-ok");
        check(command.success(), "command execution");
        check(command.output.equals("command-ok"), "command output");
        long started = System.nanoTime();
        ConsoleSupport.Result timeout = ConsoleSupport.runCommand(
                5, "sh", "-c", "printf 'before-timeout\\n'; exec sleep 30");
        check(timeout.exitCode == 124, "command timeout");
        check(timeout.output.contains("before-timeout"), "partial timeout output");
        check(timeout.output.contains("后台操作可能仍在进行"), "timeout warning");
        check(System.nanoTime() - started < 15_000_000_000L, "bounded command wait");
        System.out.println("ConsoleSupportTest: ok");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void reject(String url) {
        check(!ConsoleSupport.isLoopbackHttpUrl(url), "accepted invalid URL: " + url);
    }
}
