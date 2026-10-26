package dev.dsh.console;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional WebView console for the DSH Ubuntu Chroot module.
 *
 * The app does not contain a root shell. It runs a small fixed set of
 * commands through the device's own su/KSU prompt; the user must grant root.
 * The URL is read from the module state file and can be entered manually.
 */
public final class MainActivity extends Activity {
    private static final String URL_FILE = "/data/adb/chroot/state/guest/url";
    private static final String DSH_CTL = "/data/adb/modules/dsh_chroot/bin/dsh-ctl";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private WebView web;
    private TextView status;
    private EditText manual;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("DSH Console");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(13);
        status.setPadding(0, 8, 0, 8);
        status.setTextIsSelectable(true);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        manual = new EditText(this);
        manual.setHint("or paste DSH URL manually");
        manual.setSingleLine(true);
        manual.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(manual, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row1);
        row1.addView(button("Open URL", this::openFromFile));
        row1.addView(button("Refresh URL", this::openFromFile));
        row1.addView(button("Status", () -> runCtl("status")));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row2);
        row2.addView(button("Start", () -> runCtl("start --apply")));
        row2.addView(button("Stop", () -> runCtl("stop")));
        row2.addView(button("Reload", () -> {
            if (web != null) web.reload();
        }));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        setStatus("Ready. Open URL reads the module URL; Start/Stop need root.");
    }

    private Button button(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void setStatus(String text) {
        status.setText(text);
    }

    private void runCtl(String args) {
        setStatus("Running: " + args);
        executor.execute(() -> {
            String prefix = "PATH=/system/bin:/system/xbin:/data/adb/ksu/bin:/data/adb/magisk:"
                    + "/data/adb/modules/dsh_chroot/bin:/data/adb/chroot/bin:$PATH; ";
            String out = runRoot(prefix + DSH_CTL + " " + args);
            runOnUiThread(() -> {
                setStatus(out.isEmpty() ? "command returned no output" : out);
                if (args.startsWith("start") || args.startsWith("status")) openFromFile();
            });
        });
    }

    private void openFromFile() {
        String typed = manual.getText().toString().trim();
        if (!typed.isEmpty()) {
            load(typed);
            return;
        }
        setStatus("Reading URL...");
        executor.execute(() -> {
            String url = runRoot("/system/bin/cat " + URL_FILE + " 2>/dev/null").trim();
            runOnUiThread(() -> {
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    load(url);
                } else {
                    setStatus("No URL. Start the chroot first or paste the URL above.\n" + url);
                }
            });
        });
    }

    private void load(String url) {
        if (!(url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost"))) {
            setStatus("Refusing non-loopback URL: " + url);
            return;
        }
        setStatus("Loading " + url);
        web.loadUrl(url);
    }

    private String runRoot(String command) {
        StringBuilder out = new StringBuilder();
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
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
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
