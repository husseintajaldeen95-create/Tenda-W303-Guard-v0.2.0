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
        android.content.SharedPreferences prefs = context.getSharedPreferences(RouterBackgroundService.PREFS_NAME,
                Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean(RouterBackgroundService.PREF_ENABLED, false)
                || prefs.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_ENABLED, false)
                || prefs.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_STOPPING, false)
                || hasPendingMacRestore(prefs.getString(RouterBackgroundService.PREF_MAC_AUTO_STATE, ""));
        if (!enabled) return;
        boolean pendingRestore = hasPendingMacRestore(prefs.getString(RouterBackgroundService.PREF_MAC_AUTO_STATE, ""));
        boolean macEnabled = prefs.getBoolean(RouterBackgroundService.PREF_MAC_AUTO_ENABLED, false);
        if (pendingRestore && !macEnabled) {
            prefs.edit().putBoolean(RouterBackgroundService.PREF_MAC_AUTO_STOPPING, true).apply();
        }
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

    private boolean hasPendingMacRestore(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        try {
            org.json.JSONObject state = new org.json.JSONObject(raw);
            org.json.JSONArray policies = state.optJSONArray("policies");
            if (policies == null) return false;
            for (int i = 0; i < policies.length(); i++) {
                org.json.JSONObject policy = policies.optJSONObject(i);
                if (policy != null && !policy.optBoolean("conflict", false)
                        && (policy.optBoolean("modified", false) || policy.optBoolean("pending", false))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }
}
