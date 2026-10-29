package dev.dsh.agent;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

public final class AgentService extends AccessibilityService {
    static volatile AgentService instance;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        AgentServer.start(this, this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        AgentServer.onAccessibilityEvent(event);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        AgentServer.detach(this);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        AgentServer.detach(this);
        super.onDestroy();
    }
}
