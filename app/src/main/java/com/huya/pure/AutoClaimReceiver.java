package com.huya.pure;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Receives daily AlarmManager triggers, cooldown retries, and system reboot/time-change events.
 */
public class AutoClaimReceiver extends BroadcastReceiver {

    private static final String TAG = "HuyaPure";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        Log.i(TAG, "[AutoClaimReceiver] onReceive action=" + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            AutoClaimScheduler.syncScheduleFromPrefs(context);
            return;
        }

        if (AutoClaimProtocol.ACTION_AUTO_CLAIM_ALARM.equals(action)) {
            // Reschedule tomorrow's daily alarm immediately.
            AutoClaimScheduler.syncScheduleFromPrefs(context);
            SharedPreferences prefs = ModulePrefs.openForApp(context).prefs;
            if (prefs.getBoolean(ModulePrefs.KEY_AUTO_CLAIM_ENABLED, ModulePrefs.DEFAULT_AUTO_CLAIM_ENABLED)) {
                PendingResult pending = goAsync();
                AutoClaimScheduler.triggerNow(context, false, pending::finish);
            }
            return;
        }

        if (AutoClaimProtocol.ACTION_AUTO_CLAIM_RETRY.equals(action)) {
            SharedPreferences prefs = ModulePrefs.openForApp(context).prefs;
            if (prefs.getBoolean(ModulePrefs.KEY_AUTO_CLAIM_ENABLED, ModulePrefs.DEFAULT_AUTO_CLAIM_ENABLED)) {
                PendingResult pending = goAsync();
                AutoClaimScheduler.triggerNow(context, false, true, pending::finish);
            }
            return;
        }

        if (AutoClaimProtocol.ACTION_EXPIRY_RESCUE_ALARM.equals(action)) {
            ModulePrefs.openStatePrefs(context).edit()
                    .putLong(ModulePrefs.STATE_NEXT_RESCUE_TIME, 0L)
                    .apply();
            SharedPreferences prefs = ModulePrefs.openForApp(context).prefs;
            if (prefs.getBoolean(ModulePrefs.KEY_AUTO_SEND_HULIANG, ModulePrefs.DEFAULT_AUTO_SEND_HULIANG)) {
                Log.i(TAG, "[AutoClaimReceiver] Triggering expiry rescue task for soon-to-expire 虎粮");
                PendingResult pending = goAsync();
                AutoClaimScheduler.triggerNow(context, false, pending::finish);
            }
        }
    }
}
