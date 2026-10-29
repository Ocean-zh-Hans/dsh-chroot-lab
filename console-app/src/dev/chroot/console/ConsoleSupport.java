package dev.chroot.console;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class ConsoleSupport {
    private ConsoleSupport() {
    }

    static boolean isLoopbackHttpUrl(String value) {
        if (value == null || value.length() > 4096) return false;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            boolean loopback = "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
            return "http".equalsIgnoreCase(uri.getScheme()) && loopback
                    && uri.getUserInfo() == null && uri.getPort() >= 1 && uri.getPort() <= 65535;
        } catch (Exception e) {
            return false;
        }
    }

    static String stateLabel(String state) {
        if (state == null) return "未知";
        switch (state) {
            case "running":
                return "运行中";
            case "stopped":
                return "已停止";
            case "starting":
                return "启动中";
            case "stopping":
                return "停止中";
            case "degraded":
                return "运行异常";
            case "failed":
                return "启动失败";
            case "stale":
                return "状态过期";
            case "recovery-required":
                return "需要恢复";
            case "rescue-needed":
                return "需要救援";
            case "rescue":
                return "救援模式";
            case "":
                return "未知";
            default:
                return state;
        }
    }

    static String redactToken(String value) {
        if (value == null) return "";
        return value.replaceAll("([?&]token=)[^&\\s]*", "$1•••");
    }

    static Result runCommand(long timeoutSeconds, String... command) {
        if (timeoutSeconds <= 0) return new Result("无效的超时时间", 2);
        final int maxOutput = 256 * 1024;
        StringBuffer out = new StringBuffer();
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int remaining = maxOutput - out.length();
                        if (remaining <= 0) continue;
                        out.append(line, 0, Math.min(line.length(), remaining));
                        if (out.length() < maxOutput) out.append('\n');
                    }
                } catch (Exception e) {
                    out.append("读取命令输出失败：").append(e.getMessage()).append('\n');
                }
            });
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
                reader.join(2000);
                String warning = "命令超时（" + timeoutSeconds + " 秒）；后台操作可能仍在进行，请刷新状态后再试";
                String partial = out.toString().trim();
                return new Result(partial.isEmpty() ? warning : partial + "\n" + warning, 124);
            }
            reader.join(2000);
            return new Result(out.toString().trim(), process.exitValue());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("命令被中断；后台操作可能仍在进行", 130);
        } catch (Exception e) {
            return new Result("命令执行失败：" + e.getMessage(), -1);
        } finally {
            if (process != null) process.destroy();
        }
    }

    static final class Result {
        final String output;
        final int exitCode;

        Result(String output, int exitCode) {
            this.output = output == null ? "" : output;
            this.exitCode = exitCode;
        }

        boolean success() {
            return exitCode == 0;
        }
    }
}
