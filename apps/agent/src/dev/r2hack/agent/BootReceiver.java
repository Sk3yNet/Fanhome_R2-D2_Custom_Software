package dev.r2hack.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the agent on BOOT_COMPLETED (belt and braces: persistent apps start on their own),
 * on MY_PACKAGE_REPLACED (an over-the-air agent update kills the old process and nothing else
 * restarts it), and on dev.r2hack.agent.WAKE, which R2 Main sends when the bridge stays down.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startService(new Intent(context, AgentService.class));
    }
}
