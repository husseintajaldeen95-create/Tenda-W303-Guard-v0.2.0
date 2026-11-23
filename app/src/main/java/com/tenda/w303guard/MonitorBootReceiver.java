package com.tenda.w303guard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Restarts the user-enabled router monitor after Android finishes booting. */
public class MonitorBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        boolean enabled = context.getSharedPreferences(RouterBackgroundService.PREFS_NAME,
                Context.MODE_PRIVATE).getBoolean(RouterBackgroundService.PREF_ENABLED, false);
        if (!enabled) return;
        Intent service = new Intent(context, RouterBackgroundService.class)
                .setAction(RouterBackgroundService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Exception ignored) {
            // The saved preference remains enabled; the service can be started
            // again when the user next opens the application.
        }
    }
}
