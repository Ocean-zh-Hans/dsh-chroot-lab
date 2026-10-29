package dev.dsh.probe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class ProbeInstrumentation extends Instrumentation {
    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    private static String read(InputStream input, int limit) throws Exception {
        StringBuilder out = new StringBuilder();
        Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
        char[] buffer = new char[4096];
        while (out.length() < limit) {
            int count = reader.read(buffer, 0, Math.min(buffer.length, limit - out.length()));
            if (count < 0) break;
            out.append(buffer, 0, count);
        }
        return out.toString();
    }

    private static String file(String path, int limit) throws Exception {
        try (FileInputStream input = new FileInputStream(path)) {
            return read(input, limit);
        }
    }

    private static boolean readable(String path) {
        try (FileInputStream input = new FileInputStream(path)) {
            return input.read() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String property(String name) {
        java.lang.Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getprop", name).start();
            String value = read(process.getInputStream(), 4096).trim();
            if (!process.waitFor(5, TimeUnit.SECONDS)) return "timeout";
            return process.exitValue() == 0 ? value : "exit-" + process.exitValue();
        } catch (Exception e) {
            return e.getClass().getSimpleName();
        } finally {
            if (process != null) process.destroy();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            String mountinfo = file("/proc/self/mountinfo", 4 * 1024 * 1024);
            result.putInt("uid", Process.myUid());
            result.putString("package_name", context.getPackageName());
            result.putString("selinux_context", file("/proc/self/attr/current", 4096).trim());
            result.putBoolean("mount_rootfs_visible", mountinfo.contains("/tmp/dsh-rootfs"));
            result.putBoolean("mount_chroot_visible", mountinfo.contains("/data/adb/chroot"));
            result.putBoolean("mount_image_path_visible", mountinfo.contains("ubuntu-rootfs"));
            result.putBoolean("module_readable", readable("/data/adb/modules/dsh_chroot/module.prop"));
            result.putBoolean("image_readable", readable("/data/media/0/ubuntu-rootfs/rootfs.img"));
            result.putBoolean("agent_token_readable", readable("/data/data/dev.dsh.agent/files/token"));
            result.putBoolean("enforce_readable", readable("/sys/fs/selinux/enforce"));
            result.putString("ro_debuggable", property("ro.debuggable"));
            result.putString("ro_secure", property("ro.secure"));
            String[] names = new File("/storage/emulated/0").list();
            result.putBoolean("storage_listing", names != null);
            boolean markers = false;
            if (names != null) {
                for (String name : names) {
                    if (name.equalsIgnoreCase("magisk") || name.equalsIgnoreCase("kernelsu") || name.equalsIgnoreCase("su")) {
                        markers = true;
                    }
                }
            }
            result.putBoolean("storage_root_markers", markers);
        } catch (Throwable error) {
            result.putString("probe_error", error.getClass().getName());
        }
        sendStatus(1, result);
        finish(result.getString("probe_error") == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
