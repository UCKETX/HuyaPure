package com.huya.pure.mobile;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Schedules Android AlarmManager wakeups for the earliest upcoming
 * nextScheduleTime / nextRescueTime / nextRetryTime across all accounts.
 */
public final class ScheduleHelper {

    private static final String TAG = "HubanSchedule";
    private static final String PREFS = "huban_schedule_prefs";
    private static final String KEY_NEXT_ALARM_MS = "next_alarm_ms";
    private static final int ALARM_REQ_CODE = 5101;

    private ScheduleHelper() {
    }

    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    public static long getScheduledAlarmMs(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getLong(KEY_NEXT_ALARM_MS, 0L);
    }

    /**
     * Inspects all accounts and schedules an alarm for the earliest future
     * schedule, expiry-rescue, or cooldown-retry timestamp.
     */
    public static long syncFromAccounts(Context context, List<Models.Account> accounts) {
        long now = System.currentTimeMillis();
        long minFuture = Long.MAX_VALUE;

        if (accounts != null) {
            for (Models.Account acc : accounts) {
                if (acc == null || !acc.loggedIn || acc.state == null) {
                    continue;
                }
                long[] candidates = new long[]{
                        acc.state.nextScheduleTime,
                        acc.state.nextRescueTime,
                        acc.state.nextRetryTime
                };
                for (long ts : candidates) {
                    if (ts > now + 5_000L && ts < minFuture) {
                        minFuture = ts;
                    }
                }
            }
        }

        if (minFuture == Long.MAX_VALUE) {
            cancelAlarm(context);
            return 0L;
        }
        scheduleAt(context, minFuture);
        return minFuture;
    }

    public static void scheduleAt(Context context, long triggerAtMs) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        PendingIntent pi = buildAlarmIntent(context);
        try {
            if (canScheduleExactAlarms(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_NEXT_ALARM_MS, triggerAtMs)
                    .apply();
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA);
            Log.i(TAG, "Scheduled next background alarm at " + sdf.format(new Date(triggerAtMs)));
        } catch (SecurityException se) {
            Log.w(TAG, "Exact alarm denied, falling back to setAndAllowWhileIdle: " + se.getMessage());
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to schedule alarm: " + t.getMessage());
        }
    }

    public static void cancelAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(buildAlarmIntent(context));
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_NEXT_ALARM_MS)
                .apply();
    }

    private static PendingIntent buildAlarmIntent(Context context) {
        Intent intent = new Intent(context, TaskAlarmReceiver.class);
        intent.setAction(TaskAlarmReceiver.ACTION_RUN_SCHEDULED);
        return PendingIntent.getBroadcast(
                context,
                ALARM_REQ_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
