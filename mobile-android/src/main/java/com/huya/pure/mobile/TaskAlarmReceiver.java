package com.huya.pure.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receives AlarmManager wakeups and boot/time-change broadcasts to trigger
 * scheduled tasks or restore upcoming alarms via {@link TaskRunService}.
 */
public class TaskAlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "HubanAlarmReceiver";
    public static final String ACTION_RUN_SCHEDULED = "com.huya.pure.mobile.ACTION_RUN_SCHEDULED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        String action = intent.getAction();
        Log.i(TAG, "Received broadcast: " + action);

        Intent svc = new Intent(context, TaskRunService.class);
        if (ACTION_RUN_SCHEDULED.equals(action)) {
            svc.putExtra(TaskRunService.EXTRA_MODE, TaskRunService.MODE_RUN_SCHEDULED);
        } else {
            // BOOT_COMPLETED / MY_PACKAGE_REPLACED / TIME_SET / TIMEZONE_CHANGED
            svc.putExtra(TaskRunService.EXTRA_MODE, TaskRunService.MODE_RESCHEDULE);
        }
        try {
            context.startForegroundService(svc);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start TaskRunService: " + t.getMessage(), t);
        }
    }
}
