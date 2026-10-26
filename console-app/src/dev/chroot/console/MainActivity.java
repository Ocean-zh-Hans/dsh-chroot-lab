package dev.chroot.console;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional native control panel for the DSH Ubuntu Chroot module.
 * It executes only fixed commands through the device's own su/KSU prompt.
 */
public final class MainActivity extends Activity {
    private static final String URL_FILE = "/data/adb/chroot/state/guest/url";
    private static final String DSH_CTL = "/data/adb/modules/dsh_chroot/bin/dsh-ctl";
    private static final String PATH_PREFIX = "PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:"
            + "/data/adb/magisk:/data/adb/modules/dsh_chroot/bin:/data/adb/chroot/bin:$PATH; ";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private TextView statusTitle;
    private TextView statusDetail;
    private Switch autoSwitch;
    private Switch protectSwitch;
    private Switch guardSwitch;
    private Switch rwSwitch;
    private boolean refreshing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("chroot-console", MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 24, 28, 32);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Chroot Console");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        statusTitle = new TextView(this);
        statusTitle.setTextSize(18);
        statusTitle.setTextColor(Color.rgb(20, 90, 160));
        statusTitle.setPadding(0, 12, 0, 4);
        root.addView(statusTitle);

        statusDetail = new TextView(this);
        statusDetail.setTextSize(12);
        statusDetail.setTextIsSelectable(true);
        statusDetail.setPadding(0, 0, 0, 12);
        root.addView(statusDetail);

        root.addView(section("Lifecycle"));
        root.addView(row(
                button("Start", () -> startChroot()),
                button("Stop", () -> runCtl("stop", "Stop", true)),
                button("Restart", () -> runCtl("restart --apply", "Restart", true))));

        root.addView(row(
                button("Refresh", this::refreshStatus),
                button("Plan", () -> runCtl("plan", "Plan", false)),
                button("Doctor", () -> runCtl("doctor", "Doctor", false))));

        root.addView(row(
                button("Logs", () -> runCtl("log 200", "Logs", false)),
                button("Snapshots", () -> runCtl("snapshot list", "Snapshots", false)),
                button("DSH Web", this::openWeb)));

        root.addView(row(
                button("Snapshot", () -> runCtl("snapshot create app-" + System.currentTimeMillis(), "Snapshot", true)),
                button("Purge", this::confirmPurge)));

        root.addView(section("Settings"));
        autoSwitch = toggle("Autostart on boot", prefs.getBoolean("autostart", true), checked -> {
            prefs.edit().putBoolean("autostart", checked).apply();
            runCtl("autostart " + (checked ? "on" : "off"), "Autostart", true);
        });
        root.addView(autoSwitch);

        protectSwitch = toggle("Protect critical paths", prefs.getBoolean("protect", true), checked -> {
            prefs.edit().putBoolean("protect", checked).apply();
            toast("Applied on next Start");
        });
        root.addView(protectSwitch);

        guardSwitch = toggle("Guard deny/ask", false, checked -> {
            runCtl("guard " + (checked ? "on" : "off"), "Guard", true);
        });
        root.addView(guardSwitch);

        rwSwitch = toggle("System-RW /system", false, checked -> {
            runCtl("system-rw " + (checked ? "on" : "off") + " system --apply", "System-RW", true);
        });
        root.addView(rwSwitch);

        TextView note = new TextView(this);
        note.setTextSize(11);
        note.setText("This app is optional. It does not hide root. Start/Stop use the " +
                "device su prompt and fixed dsh-ctl commands. DSH itself opens in the Web tab.");
        note.setPadding(0, 18, 0, 0);
        root.addView(note);

        setContentView(scroll);
        refreshStatus();
    }

    private TextView section(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(14);
        v.setTextColor(Color.DKGRAY);
        v.setPadding(0, 18, 0, 4);
        return v;
    }

    private LinearLayout row(Button... buttons) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (Button b : buttons) {
            row.addView(b, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private Switch toggle(String text, boolean checked, ToggleCallback callback) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setChecked(checked);
        s.setOnCheckedChangeListener((view, value) -> {
            if (!refreshing) callback.onChanged(value);
        });
        return s;
    }

    private void startChroot() {
        String args = "start --apply";
        if (!protectSwitch.isChecked()) args += " --protect-critical=off";
        runCtl(args, "Start", true);
    }

    private void refreshStatus() {
        executor.execute(() -> {
            String raw = runRoot(PATH_PREFIX + DSH_CTL + " status --json");
            String auto = runRoot(PATH_PREFIX + DSH_CTL + " autostart status");
            String guard = runRoot(PATH_PREFIX + DSH_CTL + " guard status");
            String rw = runRoot(PATH_PREFIX + DSH_CTL + " system-rw status system");
            runOnUiThread(() -> {
                refreshing = true;
                try {
                    JSONObject j = new JSONObject(raw);
                    String state = j.optString("state", "unknown");
                    statusTitle.setText("state: " + state);
                    StringBuilder d = new StringBuilder();
                    d.append("pid: ").append(j.optString("pid", "none")).append('\n');
                    d.append("url: ").append(j.optString("url", "none")).append('\n');
                    d.append("loop: ").append(j.optString("loop", "none")).append('\n');
                    d.append("image: ").append(j.optString("image", "none"));
                    statusDetail.setText(d.toString());
                    lastUrl = j.optString("url", "");
                } catch (Exception e) {
                    statusTitle.setText("state: unknown");
                    statusDetail.setText(raw.isEmpty() ? "No root/status output" : raw);
                    lastUrl = "";
                }
                autoSwitch.setChecked(auto.contains("autostart: on"));
                guardSwitch.setChecked(guard.contains("guard: on"));
                rwSwitch.setChecked(rw.contains("overlay mounted"));
                refreshing = false;
            });
        });
    }

    private String lastUrl = "";

    private void openWeb() {
        if (lastUrl != null && !lastUrl.isEmpty()) {
            openWeb(lastUrl);
            return;
        }
        executor.execute(() -> {
            String url = runRoot("/system/bin/cat " + URL_FILE + " 2>/dev/null").trim();
            runOnUiThread(() -> openWeb(url));
        });
    }

    private void openWeb(String url) {
        if (!url.startsWith("http://127.0.0.1") && !url.startsWith("http://localhost")) {
            toast("No loopback DSH URL; start the chroot first");
            return;
        }
        startActivity(new Intent(this, WebActivity.class).putExtra("url", url));
    }

    private void runCtl(String args, String label, boolean refreshAfter) {
        toast(label + "...");
        executor.execute(() -> {
            String out = runRoot(PATH_PREFIX + DSH_CTL + " " + args);
            runOnUiThread(() -> {
                showText(label, out.isEmpty() ? "no output" : out);
                if (refreshAfter) refreshStatus();
            });
        });
    }

    private void confirmPurge() {
        new AlertDialog.Builder(this)
                .setTitle("Purge all chroot data?")
                .setMessage("This deletes /data/adb/chroot and /data/media/0/ubuntu-rootfs. " +
                        "It cannot be undone. Stop the chroot first.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Purge", (d, w) -> runCtl("purge --yes", "Purge", true))
                .show();
    }

    private void showText(String title, String text) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton("OK", null)
                .show();
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private String runRoot(String command) {
        StringBuilder out = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            p.waitFor();
        } catch (Exception e) {
            return "error: " + e.getMessage();
        } finally {
            if (p != null) p.destroy();
        }
        return out.toString().trim();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private interface ToggleCallback {
        void onChanged(boolean checked);
    }
}
