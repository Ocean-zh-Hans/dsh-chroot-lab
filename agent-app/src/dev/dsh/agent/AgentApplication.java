package dev.dsh.agent;

import android.app.Application;

public final class AgentApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AgentServer.start(this, null);
    }
}
