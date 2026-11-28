package com.huya.pure.mobile;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages Android local notification channels and rich BigTextStyle notifications
 * for task results and background foreground service execution.
 */
public final class LocalNotifyHelper {

    public static final String CHANNEL_TASKS = "huban_tasks";
    public static final String CHANNEL_FG = "huban_fg";

    private static final AtomicInteger NOTIF_SEQ = new AtomicInteger(3000);

    private LocalNotifyHelper() {
    }

    public static void ensureChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }

        NotificationChannel taskCh = new NotificationChannel(
                CHANNEL_TASKS,
                "任务执行结果",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        taskCh.setDescription("每日自动领虎粮、粉丝牌打卡、自动送礼及超粉日提醒");
        taskCh.enableVibration(true);
        taskCh.setShowBadge(true);
        nm.createNotificationChannel(taskCh);

        NotificationChannel fgCh = new NotificationChannel(
                CHANNEL_FG,
                "后台任务同步",
                NotificationManager.IMPORTANCE_LOW
        );
        fgCh.setDescription("定时任务执行期间的静默状态提示");
        fgCh.setSound(null, null);
        fgCh.enableVibration(false);
        fgCh.setShowBadge(false);
        nm.createNotificationChannel(fgCh);
    }

    public static boolean hasNotificationPermission(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && !nm.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static void postTaskNotification(Context context, String title, String body) {
        ensureChannels(context);
        if (!hasNotificationPermission(context)) {
            return;
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }

        String safeTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "虎伴任务通知";
        String safeBody = (body != null && !body.trim().isEmpty()) ? body.trim() : "任务已执行完成";

        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                context,
                1001,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notif = new Notification.Builder(context, CHANNEL_TASKS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(safeTitle)
                .setContentText(firstLine(safeBody))
                .setStyle(new Notification.BigTextStyle().bigText(safeBody))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build();

        nm.notify(NOTIF_SEQ.incrementAndGet(), notif);
    }

    public static Notification buildForegroundNotification(Context context, String statusText) {
        ensureChannels(context);
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                context,
                1002,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new Notification.Builder(context, CHANNEL_FG)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("虎伴正在执行后台任务")
                .setContentText(statusText != null ? statusText : "正在同步数据并执行定时任务...")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build();
    }

    private static String firstLine(String s) {
        int idx = s.indexOf('\n');
        return idx > 0 ? s.substring(0, idx) : s;
    }
}
