package dev.r2hack.agent;

import android.app.Application;
import android.content.Intent;

/** Persistent system app: the process starts at boot and is restarted by Android if it dies. */
public class AgentApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        startService(new Intent(this, AgentService.class));
    }
}
