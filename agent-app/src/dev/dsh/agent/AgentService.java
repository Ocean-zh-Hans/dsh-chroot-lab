package dev.dsh.agent;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/** Accessibility service owns the UI tree and the local bridge. */
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
        // The tree is read on demand; events are intentionally not persisted.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        AgentServer.stop();
        super.onDestroy();
    }
}
