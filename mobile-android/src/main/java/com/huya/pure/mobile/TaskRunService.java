package com.huya.pure.mobile;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground dataSync service invoked by {@link TaskAlarmReceiver}.
 * Starts the embedded Go core, runs scheduled tasks for enabled accounts,
 * posts local notifications, reschedules the next alarm, and stops itself.
 */
public class TaskRunService extends Service {

    private static final String TAG = "HubanTaskService";
    private static final int FG_NOTIFICATION_ID = 2101;

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_RUN_SCHEDULED = "run";
    public static final String MODE_RESCHEDULE = "reschedule";
    public static final String ACTION_DATA_UPDATED = "com.huya.pure.mobile.ACTION_DATA_UPDATED";

    private ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "huban-bg-task");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String mode = intent != null ? intent.getStringExtra(EXTRA_MODE) : MODE_RUN_SCHEDULED;
        String statusText = MODE_RESCHEDULE.equals(mode)
                ? "正在恢复虎伴定时任务计划..."
                : "正在自动领取虎粮、粉丝牌打卡与送礼...";
        Notification fgNotif = LocalNotifyHelper.buildForegroundNotification(this, statusText);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FG_NOTIFICATION_ID, fgNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FG_NOTIFICATION_ID, fgNotif);
        }

        executor.execute(() -> runWork(mode, startId));
        return START_NOT_STICKY;
    }

    private void runWork(String mode, int startId) {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HuyaPureMobile:TaskRun");
                wakeLock.acquire(4 * 60 * 1000L);
            }

            HubanCoreManager.getInstance().ensureStarted(this);
            HubanApiClient api = new HubanApiClient(this);

            if (MODE_RESCHEDULE.equals(mode)) {
                Models.StatusResponse st = api.getStatus();
                ScheduleHelper.syncFromAccounts(this, st.accounts);
                Log.i(TAG, "Rescheduled alarms for " + st.accounts.size() + " accounts");
            } else {
                Models.RunAllResult res = api.runAllAccounts("schedule", true);
                Log.i(TAG, "RunAll finished: count=" + res.count);
                for (Models.RunAllItem item : res.results) {
                    String title = (item.title != null && !item.title.isEmpty())
                            ? item.title
                            : ("虎伴 · " + (item.label.isEmpty() ? item.accountId : item.label));
                    String body = (item.body != null && !item.body.isEmpty())
                            ? item.body
                            : item.message;
                    LocalNotifyHelper.postTaskNotification(this, title, body);
                }
                // Drain engine-queued notifications to avoid duplicates when MainActivity opens later.
                try {
                    List<Models.LocalNotification> drained = api.drainNotifications();
                    if (res.results.isEmpty() && drained != null) {
                        for (Models.LocalNotification ln : drained) {
                            LocalNotifyHelper.postTaskNotification(this, ln.title, ln.body);
                        }
                    }
                } catch (Throwable ignored) {
                }

                if (!res.accounts.isEmpty()) {
                    ScheduleHelper.syncFromAccounts(this, res.accounts);
                } else {
                    Models.StatusResponse st = api.getStatus();
                    ScheduleHelper.syncFromAccounts(this, st.accounts);
                }

                Intent updated = new Intent(ACTION_DATA_UPDATED);
                updated.setPackage(getPackageName());
                sendBroadcast(updated);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Background task failed: " + t.getMessage(), t);
            if (!MODE_RESCHEDULE.equals(mode)) {
                LocalNotifyHelper.postTaskNotification(this, "虎伴定时任务异常", String.valueOf(t.getMessage()));
            }
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                try {
                    wakeLock.release();
                } catch (Throwable ignored) {
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
