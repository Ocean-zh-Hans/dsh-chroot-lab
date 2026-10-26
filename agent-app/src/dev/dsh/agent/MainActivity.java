package dev.dsh.agent;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Minimal setup screen; no root or hidden APIs are used here. */
public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("DSH Agent");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView token = new TextView(this);
        token.setText("Token file:\n" + AgentServer.tokenFile(this));
        token.setTextSize(14);
        token.setPadding(0, 24, 0, 24);
        token.setTextIsSelectable(true);
        root.addView(token);

        TextView help = new TextView(this);
        help.setText("1) Enable Accessibility for DSH Agent.\n2) Enable Notification access if needed.\n3) The bridge listens on 127.0.0.1:17321; root can read the token file.");
        help.setTextSize(14);
        help.setPadding(0, 16, 0, 24);
        root.addView(help);

        Button access = new Button(this);
        access.setText("Open Accessibility settings");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(access, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button notify = new Button(this);
        notify.setText("Open Notification access settings");
        notify.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(notify, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }
}
