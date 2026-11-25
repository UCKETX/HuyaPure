package com.huya.pure;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages daily AlarmManager scheduling, cooldown retries, headless ContentProvider.call()
 * execution against com.duowan.kiwi, and result notifications inside com.huya.pure.
 */
public final class AutoClaimScheduler {

    private static final String TAG = "HuyaPure";
    private static final String CHANNEL_ID = "huya_pure_auto_claim";
    private static final int NOTIFICATION_ID = 1042;
    private static final int REQ_DAILY_ALARM = 4001;
    private static final int REQ_RETRY_ALARM = 4002;
    private static final int REQ_EXPIRY_RESCUE_ALARM = 4003;
    private static final int MAX_RETRIES_PER_DAY = 10;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

    public interface ManualSendCallback {
        void onResult(boolean success, String message);
    }

    private AutoClaimScheduler() {
    }

    public static boolean canScheduleExactAlarms(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        return am != null && am.canScheduleExactAlarms();
    }

    public static long computeNextDailyTriggerMillis(int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, clamp(hour, 0, 23));
        target.set(Calendar.MINUTE, clamp(minute, 0, 59));
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (target.getTimeInMillis() <= now.getTimeInMillis() + 3000L) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }
        return target.getTimeInMillis();
    }

    public static void syncScheduleFromPrefs(Context context) {
        SharedPreferences prefs = ModulePrefs.openForApp(context).prefs;
        boolean enabled = prefs.getBoolean(ModulePrefs.KEY_AUTO_CLAIM_ENABLED, ModulePrefs.DEFAULT_AUTO_CLAIM_ENABLED);
        if (!enabled) {
            cancelAll(context);
        } else {
            int hour = prefs.getInt(ModulePrefs.KEY_AUTO_CLAIM_HOUR, ModulePrefs.DEFAULT_AUTO_CLAIM_HOUR);
            int minute = prefs.getInt(ModulePrefs.KEY_AUTO_CLAIM_MINUTE, ModulePrefs.DEFAULT_AUTO_CLAIM_MINUTE);
            scheduleNextDaily(context, hour, minute);
        }
        long earliestExp = ModulePrefs.openStatePrefs(context).getLong(ModulePrefs.STATE_FREE_EARLIEST_EXPIRE, 0L);
        syncExpiryRescueAlarm(context, earliestExp);
    }

    public static void scheduleNextDaily(Context context, int hour, int minute) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long triggerAt = computeNextDailyTriggerMillis(hour, minute);
        PendingIntent pi = buildReceiverPendingIntent(
                context, AutoClaimProtocol.ACTION_AUTO_CLAIM_ALARM, REQ_DAILY_ALARM);
        scheduleAlarm(context, am, triggerAt, pi);
        Log.i(TAG, "[AutoClaim] Scheduled daily alarm for " + formatDateTime(triggerAt));
    }

    public static boolean scheduleCooldownRetry(Context context, int cooldownSeconds) {
        SharedPreferences prefs = ModulePrefs.openForApp(context).prefs;
        if (!prefs.getBoolean(ModulePrefs.KEY_AUTO_CLAIM_ENABLED, ModulePrefs.DEFAULT_AUTO_CLAIM_ENABLED)) {
            return false;
        }
        SharedPreferences state = ModulePrefs.openStatePrefs(context);
        String today = todayDateString();
        String retryDay = state.getString(ModulePrefs.STATE_RETRY_DAY, "");
        int retryCount = today.equals(retryDay) ? state.getInt(ModulePrefs.STATE_RETRY_COUNT, 0) : 0;
        if (retryCount >= MAX_RETRIES_PER_DAY) {
            Log.i(TAG, "[AutoClaim] Reached max cooldown retries for today (" + MAX_RETRIES_PER_DAY + ")");
            return false;
        }

        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return false;
        }

        int safeSeconds = Math.max(15, Math.min(cooldownSeconds + 5, 3600));
        long triggerAt = System.currentTimeMillis() + safeSeconds * 1000L;
        PendingIntent pi = buildReceiverPendingIntent(
                context, AutoClaimProtocol.ACTION_AUTO_CLAIM_RETRY, REQ_RETRY_ALARM);
        scheduleAlarm(context, am, triggerAt, pi);

        state.edit()
                .putString(ModulePrefs.STATE_RETRY_DAY, today)
                .putInt(ModulePrefs.STATE_RETRY_COUNT, retryCount + 1)
                .putLong(ModulePrefs.STATE_NEXT_RETRY_TIME, triggerAt)
                .apply();
        Log.i(TAG, "[AutoClaim] Scheduled cooldown retry #" + (retryCount + 1)
                + " in " + safeSeconds + "s (" + formatDateTime(triggerAt) + ")");
        return true;
    }

    public static void syncExpiryRescueAlarm(Context context, long earliestExpireSec) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = buildReceiverPendingIntent(
                context, AutoClaimProtocol.ACTION_EXPIRY_RESCUE_ALARM, REQ_EXPIRY_RESCUE_ALARM);
        SharedPreferences prefs = ModulePrefs.openForApp(context).prefs;
        SharedPreferences state = ModulePrefs.openStatePrefs(context);
        boolean autoSend = prefs.getBoolean(
                ModulePrefs.KEY_AUTO_SEND_HULIANG, ModulePrefs.DEFAULT_AUTO_SEND_HULIANG);
        int freeCount = state.getInt(ModulePrefs.STATE_FREE_HULIANG_COUNT, 0);
        long nowMillis = System.currentTimeMillis();
        long expireMillis = earliestExpireSec > 0L ? earliestExpireSec * 1000L : 0L;

        if (!autoSend || freeCount <= 0 || expireMillis <= nowMillis + 60_000L) {
            if (am != null) {
                am.cancel(pi);
            }
            state.edit().putLong(ModulePrefs.STATE_NEXT_RESCUE_TIME, 0L).apply();
            return;
        }

        boolean dailyEnabled = prefs.getBoolean(
                ModulePrefs.KEY_AUTO_CLAIM_ENABLED, ModulePrefs.DEFAULT_AUTO_CLAIM_ENABLED);
        long nextDailyMillis = Long.MAX_VALUE;
        if (dailyEnabled) {
            int hour = prefs.getInt(ModulePrefs.KEY_AUTO_CLAIM_HOUR, ModulePrefs.DEFAULT_AUTO_CLAIM_HOUR);
            int minute = prefs.getInt(ModulePrefs.KEY_AUTO_CLAIM_MINUTE, ModulePrefs.DEFAULT_AUTO_CLAIM_MINUTE);
            nextDailyMillis = computeNextDailyTriggerMillis(hour, minute);
        }

        // Only need a dedicated rescue wake-up if Huliang expires BEFORE the next scheduled daily task (+10 min buffer).
        if (expireMillis < nextDailyMillis + 10L * 60_000L) {
            long triggerAt = Math.max(nowMillis + 60_000L, expireMillis - 30L * 60_000L);
            if (am != null) {
                scheduleAlarm(context, am, triggerAt, pi);
            }
            state.edit().putLong(ModulePrefs.STATE_NEXT_RESCUE_TIME, triggerAt).apply();
            Log.i(TAG, "[AutoClaim] Scheduled expiry rescue alarm for " + formatDateTime(triggerAt)
                    + " (earliestExpire=" + formatDateTime(expireMillis) + ")");
        } else {
            if (am != null) {
                am.cancel(pi);
            }
            state.edit().putLong(ModulePrefs.STATE_NEXT_RESCUE_TIME, 0L).apply();
        }
    }

    public static void cancelAll(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(buildReceiverPendingIntent(
                    context, AutoClaimProtocol.ACTION_AUTO_CLAIM_ALARM, REQ_DAILY_ALARM));
            am.cancel(buildReceiverPendingIntent(
                    context, AutoClaimProtocol.ACTION_AUTO_CLAIM_RETRY, REQ_RETRY_ALARM));
            am.cancel(buildReceiverPendingIntent(
                    context, AutoClaimProtocol.ACTION_EXPIRY_RESCUE_ALARM, REQ_EXPIRY_RESCUE_ALARM));
        }
        ModulePrefs.openStatePrefs(context).edit()
                .putLong(ModulePrefs.STATE_NEXT_RETRY_TIME, 0L)
                .putLong(ModulePrefs.STATE_NEXT_RESCUE_TIME, 0L)
                .apply();
        Log.i(TAG, "[AutoClaim] Cancelled all scheduled alarms");
    }

    public static void triggerNow(Context context, boolean manual) {
        triggerNow(context, manual, false, null);
    }

    public static void triggerNow(Context context, final boolean manual, final Runnable onComplete) {
        triggerNow(context, manual, false, onComplete);
    }

    public static void triggerNow(
            Context context,
            final boolean manual,
            final boolean isCooldownRetry,
            final Runnable onComplete
    ) {
        final Context appCtx = context.getApplicationContext();

        sExecutor.execute(() -> {
            PowerManager.WakeLock wl = acquireWakeLock(appCtx, 130000L);
            try {
                executeClaimInternal(appCtx, manual, isCooldownRetry);
            } finally {
                releaseWakeLock(wl);
                if (onComplete != null) {
                    try {
                        onComplete.run();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    public static void sendManualGift(
            Context context,
            final long targetPid,
            final String anchorNick,
            final String giftKind,
            final int count,
            final ManualSendCallback callback
    ) {
        final Context appCtx = context.getApplicationContext();
        final String normalizedKind = "super".equals(giftKind) ? "super" : "normal";

        sExecutor.execute(() -> {
            PowerManager.WakeLock wl = acquireWakeLock(appCtx, 45000L);
            try {
                executeManualGiftInternal(appCtx, targetPid, anchorNick, normalizedKind, count, callback);
            } finally {
                releaseWakeLock(wl);
            }
        });
    }

    private static void executeManualGiftInternal(
            Context appCtx,
            long targetPid,
            String anchorNick,
            String giftKind,
            int count,
            ManualSendCallback callback
    ) {
        if (targetPid <= 0L) {
            postManualSendCallback(callback, false, "送礼目标主播 UID 无效");
            return;
        }
        if (!isNetworkConnected(appCtx)) {
            postManualSendCallback(callback, false, "当前无网络连接，无法赠送");
            return;
        }
        if (!isHuyaInstalled(appCtx)) {
            postManualSendCallback(callback, false, "未安装虎牙直播 (com.duowan.kiwi)");
            return;
        }

        String requestId = UUID.randomUUID().toString();
        Bundle result = null;
        String directError = null;
        try {
            Bundle extras = new Bundle();
            extras.putLong(AutoClaimProtocol.EXTRA_SEND_TARGET_PID, targetPid);
            extras.putString(AutoClaimProtocol.EXTRA_SEND_GIFT_KIND, giftKind);
            extras.putInt(AutoClaimProtocol.EXTRA_SEND_COUNT, count);
            Log.i(TAG, "[AutoClaim] Calling ContentProvider " + AutoClaimProtocol.TARGET_PROVIDER_URI
                    + " method=" + AutoClaimProtocol.METHOD_SEND_MANUAL_GIFT
                    + " (requestId=" + requestId + ", targetPid=" + targetPid
                    + ", giftKind=" + giftKind + ", count=" + count + ")");
            result = appCtx.getContentResolver().call(
                    Uri.parse(AutoClaimProtocol.TARGET_PROVIDER_URI),
                    AutoClaimProtocol.METHOD_SEND_MANUAL_GIFT,
                    requestId,
                    extras
            );
        } catch (Throwable t) {
            directError = t.getMessage();
            Log.w(TAG, "[AutoClaim] Direct manual send ContentProvider.call failed (" + directError
                    + "), trying su fallback");
        }

        if (result == null || !result.getBoolean(AutoClaimProtocol.EXTRA_HANDLED, false)) {
            Bundle rootResult = callManualSendViaSu(requestId, targetPid, giftKind, count);
            if (rootResult != null && rootResult.getBoolean(AutoClaimProtocol.EXTRA_HANDLED, false)) {
                result = rootResult;
            }
        }

        if (result == null || !result.getBoolean(AutoClaimProtocol.EXTRA_HANDLED, false)) {
            String errMsg;
            if (directError != null && directError.contains("Unknown authority")) {
                errMsg = "后台唤醒被系统拦截：请允许「虎牙直播」关联启动，或授予「虎伴」Root 权限";
            } else if (directError != null && !directError.isEmpty()) {
                errMsg = "后台唤醒失败：" + directError;
            } else {
                errMsg = "虎牙未响应后台指令，请确认 LSPosed 已启用模块且重启过虎牙";
            }
            postManualSendCallback(callback, false, errMsg);
            return;
        }

        boolean success = result.getBoolean(AutoClaimProtocol.EXTRA_SUCCESS, false);
        int freeHuliangCount = result.getInt(AutoClaimProtocol.EXTRA_FREE_HULIANG_COUNT, -1);
        int freeExpiring24h = result.getInt(AutoClaimProtocol.EXTRA_FREE_EXPIRING_24H, -1);
        long freeEarliestExpire = result.getLong(AutoClaimProtocol.EXTRA_FREE_EARLIEST_EXPIRE, -1L);
        int superHuliangCount = result.getInt(AutoClaimProtocol.EXTRA_SUPER_HULIANG_COUNT, -1);
        String superHuliangName = result.getString(AutoClaimProtocol.EXTRA_SUPER_HULIANG_NAME, "");
        int sent = Math.max(0, result.getInt(AutoClaimProtocol.EXTRA_SENT_HULIANG_COUNT, 0))
                + Math.max(0, result.getInt(AutoClaimProtocol.EXTRA_SENT_SUPER_HULIANG_COUNT, 0));
        String message = result.getString(AutoClaimProtocol.EXTRA_MESSAGE, "");

        SharedPreferences state = ModulePrefs.openStatePrefs(appCtx);
        SharedPreferences.Editor editor = state.edit();
        boolean stateChanged = false;
        if (freeHuliangCount >= 0) {
            editor.putInt(ModulePrefs.STATE_FREE_HULIANG_COUNT, freeHuliangCount);
            if (freeExpiring24h >= 0) {
                editor.putInt(ModulePrefs.STATE_FREE_EXPIRING_24H, freeExpiring24h);
            }
            if (freeEarliestExpire >= 0L) {
                editor.putLong(ModulePrefs.STATE_FREE_EARLIEST_EXPIRE, freeEarliestExpire);
            }
            stateChanged = true;
        }
        if (superHuliangCount >= 0) {
            editor.putInt(ModulePrefs.STATE_SUPER_HULIANG_COUNT, superHuliangCount);
            if (superHuliangName != null && !superHuliangName.isEmpty()) {
                editor.putString(ModulePrefs.STATE_SUPER_HULIANG_NAME, superHuliangName);
            }
            stateChanged = true;
        }
        if (sent > 0) {
            String today = todayDateString();
            String prevSentDay = state.getString(ModulePrefs.STATE_TODAY_SENT_DAY, "");
            int prevSentCount = today.equals(prevSentDay)
                    ? state.getInt(ModulePrefs.STATE_TODAY_SENT_COUNT, 0) : 0;
            editor.putString(ModulePrefs.STATE_TODAY_SENT_DAY, today)
                    .putInt(ModulePrefs.STATE_TODAY_SENT_COUNT, prevSentCount + sent);
            stateChanged = true;
        }
        if (stateChanged) {
            editor.apply();
        }
        notifyUiStateUpdated(appCtx);

        if (message == null || message.isEmpty()) {
            message = success ? ("已送出 " + sent + " 个虎粮") : "送出失败";
        }
        String finalMessage = message;
        String cleanNick = anchorNick != null ? anchorNick.trim() : "";
        if (success && !cleanNick.isEmpty()) {
            if (message.startsWith("已送出")) {
                finalMessage = "已向「" + cleanNick + "」" + message.substring(1);
            } else {
                finalMessage = "已向「" + cleanNick + "」" + message;
            }
        }
        postManualSendCallback(callback, success, finalMessage);
    }

    private static void postManualSendCallback(
            final ManualSendCallback callback,
            final boolean success,
            final String message
    ) {
        if (callback == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                callback.onResult(success, message);
            } catch (Throwable ignored) {
            }
        });
    }

    private static Bundle callManualSendViaSu(
            String requestId,
            long targetPid,
            String giftKind,
            int count
    ) {
        Process proc = null;
        try {
            String safeReqId = requestId.replaceAll("[^A-Za-z0-9_-]", "");
            String safeKind = "super".equals(giftKind) ? "super" : "normal";
            String cmd = "content call --uri " + AutoClaimProtocol.TARGET_PROVIDER_URI
                    + " --method " + AutoClaimProtocol.METHOD_SEND_MANUAL_GIFT
                    + " --arg " + safeReqId
                    + " --extra " + AutoClaimProtocol.EXTRA_SEND_TARGET_PID + ":l:" + targetPid
                    + " --extra " + AutoClaimProtocol.EXTRA_SEND_GIFT_KIND + ":s:" + safeKind
                    + " --extra " + AutoClaimProtocol.EXTRA_SEND_COUNT + ":i:" + count;
            Log.i(TAG, "[AutoClaim] Invoking manual send su fallback: " + cmd);
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean finished = proc.waitFor(45, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                Log.w(TAG, "[AutoClaim] manual send su fallback timed out");
                return null;
            }
            int code = proc.exitValue();
            String output = out.toString();
            Log.i(TAG, "[AutoClaim] manual send su fallback exit=" + code + ", output=" + output.trim());
            if (code != 0 || output.isEmpty()) {
                return null;
            }
            return parseSuContentCallOutput(output);
        } catch (Throwable t) {
            Log.w(TAG, "[AutoClaim] manual send su fallback unavailable: " + t.getMessage());
            return null;
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static String buildTargetsSpecFromPrefs(SharedPreferences prefs) {
        if (prefs == null) {
            return "";
        }
        String rawJson = prefs.getString(ModulePrefs.KEY_SEND_HULIANG_TARGETS_JSON, "");
        if (rawJson != null && !rawJson.trim().isEmpty()) {
            try {
                JSONArray arr = new JSONArray(rawJson.trim());
                StringBuilder sb = new StringBuilder();
                Set<Long> seen = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) {
                        continue;
                    }
                    long pid = obj.optLong("pid", 0L);
                    if (pid <= 0L || !seen.add(pid)) {
                        continue;
                    }
                    int weight = Math.max(1, obj.optInt("weight", 1));
                    if (sb.length() > 0) {
                        sb.append(',');
                    }
                    sb.append(pid).append(':').append(weight);
                }
                if (sb.length() > 0) {
                    return sb.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        long legacyPid = prefs.getLong(
                ModulePrefs.KEY_SEND_HULIANG_TARGET_PID, ModulePrefs.DEFAULT_SEND_HULIANG_TARGET_PID);
        if (legacyPid > 0L) {
            return legacyPid + ":1";
        }
        return "";
    }

    private static void executeClaimInternal(Context appCtx, boolean manual, boolean isCooldownRetry) {
        if (!isNetworkConnected(appCtx)) {
            String msg = "当前无网络连接，跳过本次任务";
            recordResult(appCtx, false, 0, 0, 0, msg, manual);
            if (!manual) {
                scheduleCooldownRetry(appCtx, 300);
            }
            return;
        }

        if (!isHuyaInstalled(appCtx)) {
            recordResult(appCtx, false, 0, 0, 0, "未安装虎牙直播 (com.duowan.kiwi)", manual);
            return;
        }

        SharedPreferences prefs = ModulePrefs.openForApp(appCtx).prefs;
        boolean wantCheckin = prefs.getBoolean(
                ModulePrefs.KEY_AUTO_FANS_CHECKIN, ModulePrefs.DEFAULT_AUTO_FANS_CHECKIN);
        boolean wantClaimWelfare = prefs.getBoolean(
                ModulePrefs.KEY_AUTO_CLAIM_WELFARE, ModulePrefs.DEFAULT_AUTO_CLAIM_WELFARE);
        boolean wantSendHuliang = prefs.getBoolean(
                ModulePrefs.KEY_AUTO_SEND_HULIANG, ModulePrefs.DEFAULT_AUTO_SEND_HULIANG);
        boolean wantSendSuperHuliang = prefs.getBoolean(
                ModulePrefs.KEY_SEND_SUPER_HULIANG, ModulePrefs.DEFAULT_SEND_SUPER_HULIANG);
        boolean smartSuperFanDaySend = prefs.getBoolean(
                ModulePrefs.KEY_SMART_SUPERFAN_DAY_SEND, ModulePrefs.DEFAULT_SMART_SUPERFAN_DAY_SEND);
        long sendTargetPid = prefs.getLong(
                ModulePrefs.KEY_SEND_HULIANG_TARGET_PID, ModulePrefs.DEFAULT_SEND_HULIANG_TARGET_PID);
        String sendTargetsSpec = buildTargetsSpecFromPrefs(prefs);
        int sendDistributeMode = prefs.getInt(
                ModulePrefs.KEY_SEND_HULIANG_DISTRIBUTE_MODE, ModulePrefs.DEFAULT_SEND_HULIANG_DISTRIBUTE_MODE);
        int sendMaxCount = prefs.getInt(
                ModulePrefs.KEY_SEND_HULIANG_MAX_COUNT, ModulePrefs.DEFAULT_SEND_HULIANG_MAX_COUNT);
        boolean doFansCheckin = wantCheckin && !isCooldownRetry;
        boolean doClaimWelfare = wantClaimWelfare && !isCooldownRetry;
        boolean doSendHuliang = wantSendHuliang && !isCooldownRetry;
        boolean doSendSuperHuliang = wantSendSuperHuliang && doSendHuliang;

        String requestId = UUID.randomUUID().toString();
        String progressText;
        if (doFansCheckin && doSendHuliang) {
            progressText = "正在后台领虎粮、粉丝牌打卡、领福利并赠送免费虎粮...";
        } else if (doSendHuliang) {
            progressText = "正在后台领虎粮、领福利并赠送免费虎粮...";
        } else if (doFansCheckin || doClaimWelfare) {
            progressText = "正在后台领虎粮、粉丝牌打卡并领取每日福利...";
        } else {
            progressText = "正在后台领取虎粮...";
        }
        ModulePrefs.openStatePrefs(appCtx).edit()
                .putString(ModulePrefs.STATE_ACTIVE_REQUEST_ID, requestId)
                .putLong(ModulePrefs.STATE_NEXT_RETRY_TIME, 0L)
                .putLong(ModulePrefs.STATE_LAST_RUN_TIME, System.currentTimeMillis())
                .putString(ModulePrefs.STATE_LAST_RUN_DATE, todayDateString())
                .putString(ModulePrefs.STATE_LAST_RUN_MESSAGE, progressText)
                .apply();
        notifyUiStateUpdated(appCtx);

        Bundle result = null;
        String directError = null;
        try {
            Bundle extras = new Bundle();
            extras.putBoolean(AutoClaimProtocol.EXTRA_MANUAL, manual);
            extras.putBoolean(AutoClaimProtocol.EXTRA_DO_FANS_CHECKIN, doFansCheckin);
            extras.putBoolean(AutoClaimProtocol.EXTRA_DO_CLAIM_WELFARE, doClaimWelfare);
            extras.putBoolean(AutoClaimProtocol.EXTRA_DO_SEND_HULIANG, doSendHuliang);
            extras.putBoolean(AutoClaimProtocol.EXTRA_DO_SEND_SUPER_HULIANG, doSendSuperHuliang);
            extras.putBoolean(AutoClaimProtocol.EXTRA_SMART_SUPERFAN_DAY_SEND, smartSuperFanDaySend);
            extras.putLong(AutoClaimProtocol.EXTRA_SEND_TARGET_PID, sendTargetPid);
            extras.putString(AutoClaimProtocol.EXTRA_SEND_TARGETS_SPEC, sendTargetsSpec);
            extras.putInt(AutoClaimProtocol.EXTRA_SEND_DISTRIBUTE_MODE, sendDistributeMode);
            extras.putInt(AutoClaimProtocol.EXTRA_SEND_MAX_COUNT, sendMaxCount);
            Log.i(TAG, "[AutoClaim] Calling ContentProvider " + AutoClaimProtocol.TARGET_PROVIDER_URI
                    + " (requestId=" + requestId + ", manual=" + manual
                    + ", doFansCheckin=" + doFansCheckin
                    + ", doClaimWelfare=" + doClaimWelfare
                    + ", doSendHuliang=" + doSendHuliang
                    + ", doSendSuperHuliang=" + doSendSuperHuliang
                    + ", smartSfDay=" + smartSuperFanDaySend
                    + ", targetPid=" + sendTargetPid
                    + ", targetsSpec=" + (sendTargetsSpec.isEmpty() ? "(all)" : sendTargetsSpec)
                    + ", distMode=" + sendDistributeMode
                    + ", maxCount=" + sendMaxCount + ")");

            result = appCtx.getContentResolver().call(
                    Uri.parse(AutoClaimProtocol.TARGET_PROVIDER_URI),
                    AutoClaimProtocol.METHOD_AUTO_CLAIM,
                    requestId,
                    extras
            );
        } catch (Throwable t) {
            directError = t.getMessage();
            Log.w(TAG, "[AutoClaim] Direct ContentProvider.call failed (" + directError
                    + "), trying su fallback");
        }

        if (result == null || !result.getBoolean(AutoClaimProtocol.EXTRA_HANDLED, false)) {
            Bundle rootResult = callProviderViaSu(
                    requestId, manual, doFansCheckin, doClaimWelfare,
                    doSendHuliang, doSendSuperHuliang, smartSuperFanDaySend,
                    sendTargetPid, sendTargetsSpec, sendDistributeMode, sendMaxCount);
            if (rootResult != null && rootResult.getBoolean(AutoClaimProtocol.EXTRA_HANDLED, false)) {
                result = rootResult;
            }
        }

        if (result == null || !result.getBoolean(AutoClaimProtocol.EXTRA_HANDLED, false)) {
            String errMsg;
            if (directError != null && directError.contains("Unknown authority")) {
                errMsg = "后台唤醒被系统拦截：请允许「虎牙直播」关联启动，或授予「虎伴」Root 权限";
            } else if (directError != null && !directError.isEmpty()) {
                errMsg = "后台唤醒失败：" + directError;
            } else {
                errMsg = "虎牙未响应后台指令，请确认 LSPosed 已启用模块且重启过虎牙";
            }
            recordResult(appCtx, false, 0, 0, 0, errMsg, manual);
            return;
        }

        boolean success = result.getBoolean(AutoClaimProtocol.EXTRA_SUCCESS, false);
        int claimed = result.getInt(AutoClaimProtocol.EXTRA_CLAIMED_COUNT, 0);
        int welfareClaimed = result.getInt(AutoClaimProtocol.EXTRA_WELFARE_CLAIMED_COUNT, 0);
        boolean exhausted = result.getBoolean(AutoClaimProtocol.EXTRA_EXHAUSTED, false);
        int cooldownSec = result.getInt(AutoClaimProtocol.EXTRA_COOLDOWN_SECONDS, 0);
        int checkinTotal = result.getInt(AutoClaimProtocol.EXTRA_CHECKIN_TOTAL, 0);
        int checkinDone = result.getInt(AutoClaimProtocol.EXTRA_CHECKIN_SUCCESS, 0)
                + result.getInt(AutoClaimProtocol.EXTRA_CHECKIN_ALREADY, 0);
        int freeHuliangCount = result.getInt(AutoClaimProtocol.EXTRA_FREE_HULIANG_COUNT, -1);
        int freeExpiring24h = result.getInt(AutoClaimProtocol.EXTRA_FREE_EXPIRING_24H, -1);
        long freeEarliestExpire = result.getLong(AutoClaimProtocol.EXTRA_FREE_EARLIEST_EXPIRE, -1L);
        String sfDayActiveSummary = result.getString(AutoClaimProtocol.EXTRA_SF_DAY_ACTIVE_SUMMARY, "");
        int superHuliangCount = result.getInt(AutoClaimProtocol.EXTRA_SUPER_HULIANG_COUNT, -1);
        String superHuliangName = result.getString(AutoClaimProtocol.EXTRA_SUPER_HULIANG_NAME, "");
        int sentHuliangCount = result.getInt(AutoClaimProtocol.EXTRA_SENT_HULIANG_COUNT, 0)
                + result.getInt(AutoClaimProtocol.EXTRA_SENT_SUPER_HULIANG_COUNT, 0);
        String message = result.getString(AutoClaimProtocol.EXTRA_MESSAGE, "");
        String badgesJson = result.getString(AutoClaimProtocol.EXTRA_BADGES_JSON, "");
        if (message == null || message.isEmpty()) {
            message = success ? ("成功领取 " + claimed + " 次虎粮") : "执行未完成";
        }

        if (!exhausted && cooldownSec > 0) {
            boolean scheduled = scheduleCooldownRetry(appCtx, cooldownSec);
            if (scheduled) {
                message = message + "（已预约约 " + Math.max(1, (cooldownSec + 59) / 60) + " 分钟后自动补领虎粮）";
            }
        }

        recordResult(appCtx, success, claimed, welfareClaimed, checkinDone, checkinTotal,
                freeHuliangCount, freeExpiring24h, freeEarliestExpire, sfDayActiveSummary,
                superHuliangCount, superHuliangName, sentHuliangCount,
                badgesJson, message, manual);
    }

    private static Bundle callProviderViaSu(
            String requestId,
            boolean manual,
            boolean doFansCheckin,
            boolean doClaimWelfare,
            boolean doSendHuliang,
            boolean doSendSuperHuliang,
            boolean smartSuperFanDaySend,
            long sendTargetPid,
            String sendTargetsSpec,
            int sendDistributeMode,
            int sendMaxCount
    ) {
        Process proc = null;
        try {
            String safeReqId = requestId.replaceAll("[^A-Za-z0-9_-]", "");
            String safeSpec = sendTargetsSpec != null
                    ? sendTargetsSpec.replaceAll("[^0-9:,]", "")
                    : "";
            String cmd = "content call --uri " + AutoClaimProtocol.TARGET_PROVIDER_URI
                    + " --method " + AutoClaimProtocol.METHOD_AUTO_CLAIM
                    + " --arg " + safeReqId
                    + " --extra " + AutoClaimProtocol.EXTRA_MANUAL + ":b:" + (manual ? "true" : "false")
                    + " --extra " + AutoClaimProtocol.EXTRA_DO_FANS_CHECKIN + ":b:" + (doFansCheckin ? "true" : "false")
                    + " --extra " + AutoClaimProtocol.EXTRA_DO_CLAIM_WELFARE + ":b:" + (doClaimWelfare ? "true" : "false")
                    + " --extra " + AutoClaimProtocol.EXTRA_DO_SEND_HULIANG + ":b:" + (doSendHuliang ? "true" : "false")
                    + " --extra " + AutoClaimProtocol.EXTRA_DO_SEND_SUPER_HULIANG + ":b:" + (doSendSuperHuliang ? "true" : "false")
                    + " --extra " + AutoClaimProtocol.EXTRA_SMART_SUPERFAN_DAY_SEND + ":b:" + (smartSuperFanDaySend ? "true" : "false")
                    + " --extra " + AutoClaimProtocol.EXTRA_SEND_TARGET_PID + ":l:" + sendTargetPid
                    + " --extra " + AutoClaimProtocol.EXTRA_SEND_DISTRIBUTE_MODE + ":i:" + sendDistributeMode
                    + " --extra " + AutoClaimProtocol.EXTRA_SEND_MAX_COUNT + ":i:" + sendMaxCount;
            if (!safeSpec.isEmpty()) {
                cmd = cmd + " --extra " + AutoClaimProtocol.EXTRA_SEND_TARGETS_SPEC + ":s:" + safeSpec;
            }
            Log.i(TAG, "[AutoClaim] Invoking su fallback: " + cmd);
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            proc = pb.start();

            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            boolean finished = proc.waitFor(125, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                Log.w(TAG, "[AutoClaim] su fallback timed out");
                return null;
            }
            int code = proc.exitValue();
            String output = out.toString();
            Log.i(TAG, "[AutoClaim] su fallback exit=" + code + ", output=" + output.trim());
            if (code != 0 || output.isEmpty()) {
                return null;
            }
            return parseSuContentCallOutput(output);
        } catch (Throwable t) {
            Log.w(TAG, "[AutoClaim] su fallback unavailable: " + t.getMessage());
            return null;
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Bundle parseSuContentCallOutput(String output) {
        Matcher m = Pattern.compile(AutoClaimProtocol.EXTRA_RESULT_JSON_B64 + "=([A-Za-z0-9+/=_-]+)")
                .matcher(output);
        if (!m.find()) {
            return null;
        }
        try {
            byte[] raw = Base64.decode(m.group(1), Base64.DEFAULT);
            JSONObject obj = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            Bundle b = new Bundle();
            b.putBoolean(AutoClaimProtocol.EXTRA_HANDLED, obj.optBoolean(AutoClaimProtocol.EXTRA_HANDLED, false));
            b.putString(AutoClaimProtocol.EXTRA_REQUEST_ID, obj.optString(AutoClaimProtocol.EXTRA_REQUEST_ID, ""));
            b.putBoolean(AutoClaimProtocol.EXTRA_MANUAL, obj.optBoolean(AutoClaimProtocol.EXTRA_MANUAL, false));
            b.putBoolean(AutoClaimProtocol.EXTRA_SUCCESS, obj.optBoolean(AutoClaimProtocol.EXTRA_SUCCESS, false));
            b.putInt(AutoClaimProtocol.EXTRA_CLAIMED_COUNT, obj.optInt(AutoClaimProtocol.EXTRA_CLAIMED_COUNT, 0));
            b.putInt(AutoClaimProtocol.EXTRA_WELFARE_CLAIMED_COUNT, obj.optInt(AutoClaimProtocol.EXTRA_WELFARE_CLAIMED_COUNT, 0));
            b.putBoolean(AutoClaimProtocol.EXTRA_EXHAUSTED, obj.optBoolean(AutoClaimProtocol.EXTRA_EXHAUSTED, false));
            b.putInt(AutoClaimProtocol.EXTRA_COOLDOWN_SECONDS, obj.optInt(AutoClaimProtocol.EXTRA_COOLDOWN_SECONDS, 0));
            b.putInt(AutoClaimProtocol.EXTRA_CHECKIN_TOTAL, obj.optInt(AutoClaimProtocol.EXTRA_CHECKIN_TOTAL, 0));
            b.putInt(AutoClaimProtocol.EXTRA_CHECKIN_SUCCESS, obj.optInt(AutoClaimProtocol.EXTRA_CHECKIN_SUCCESS, 0));
            b.putInt(AutoClaimProtocol.EXTRA_CHECKIN_ALREADY, obj.optInt(AutoClaimProtocol.EXTRA_CHECKIN_ALREADY, 0));
            b.putInt(AutoClaimProtocol.EXTRA_CHECKIN_FAILED, obj.optInt(AutoClaimProtocol.EXTRA_CHECKIN_FAILED, 0));
            b.putInt(AutoClaimProtocol.EXTRA_FREE_HULIANG_COUNT, obj.optInt(AutoClaimProtocol.EXTRA_FREE_HULIANG_COUNT, -1));
            b.putInt(AutoClaimProtocol.EXTRA_FREE_EXPIRING_24H, obj.optInt(AutoClaimProtocol.EXTRA_FREE_EXPIRING_24H, -1));
            b.putLong(AutoClaimProtocol.EXTRA_FREE_EARLIEST_EXPIRE, obj.optLong(AutoClaimProtocol.EXTRA_FREE_EARLIEST_EXPIRE, -1L));
            b.putString(AutoClaimProtocol.EXTRA_SF_DAY_ACTIVE_SUMMARY, obj.optString(AutoClaimProtocol.EXTRA_SF_DAY_ACTIVE_SUMMARY, ""));
            b.putInt(AutoClaimProtocol.EXTRA_SUPER_HULIANG_COUNT, obj.optInt(AutoClaimProtocol.EXTRA_SUPER_HULIANG_COUNT, -1));
            b.putString(AutoClaimProtocol.EXTRA_SUPER_HULIANG_NAME, obj.optString(AutoClaimProtocol.EXTRA_SUPER_HULIANG_NAME, ""));
            b.putInt(AutoClaimProtocol.EXTRA_SENT_HULIANG_COUNT, obj.optInt(AutoClaimProtocol.EXTRA_SENT_HULIANG_COUNT, 0));
            b.putInt(AutoClaimProtocol.EXTRA_SENT_SUPER_HULIANG_COUNT, obj.optInt(AutoClaimProtocol.EXTRA_SENT_SUPER_HULIANG_COUNT, 0));
            b.putString(AutoClaimProtocol.EXTRA_BADGES_JSON, obj.optString(AutoClaimProtocol.EXTRA_BADGES_JSON, ""));
            b.putString(AutoClaimProtocol.EXTRA_MESSAGE, obj.optString(AutoClaimProtocol.EXTRA_MESSAGE, ""));
            return b;
        } catch (Throwable t) {
            Log.e(TAG, "[AutoClaim] Failed to decode su fallback result", t);
            return null;
        }
    }

    public static void recordResult(
            Context context,
            boolean success,
            int claimedCount,
            int checkinDoneCount,
            int checkinTotalCount,
            String message,
            boolean manual
    ) {
        recordResult(context, success, claimedCount, 0, checkinDoneCount, checkinTotalCount,
                -1, -1, -1L, "", -1, "", 0, "", message, manual);
    }

    public static void recordResult(
            Context context,
            boolean success,
            int claimedCount,
            int welfareClaimedCount,
            int checkinDoneCount,
            int checkinTotalCount,
            int freeHuliangCount,
            int superHuliangCount,
            String superHuliangName,
            int sentHuliangCount,
            String badgesJson,
            String message,
            boolean manual
    ) {
        recordResult(context, success, claimedCount, welfareClaimedCount, checkinDoneCount, checkinTotalCount,
                freeHuliangCount, -1, -1L, "", superHuliangCount, superHuliangName,
                sentHuliangCount, badgesJson, message, manual);
    }

    public static void recordResult(
            Context context,
            boolean success,
            int claimedCount,
            int welfareClaimedCount,
            int checkinDoneCount,
            int checkinTotalCount,
            int freeHuliangCount,
            int freeExpiring24h,
            long freeEarliestExpire,
            String sfDayActiveSummary,
            int superHuliangCount,
            String superHuliangName,
            int sentHuliangCount,
            String badgesJson,
            String message,
            boolean manual
    ) {
        SharedPreferences state = ModulePrefs.openStatePrefs(context);
        String today = todayDateString();
        String prevDay = state.getString(ModulePrefs.STATE_TODAY_CLAIMED_DAY, "");
        int prevTodayCount = today.equals(prevDay) ? state.getInt(ModulePrefs.STATE_TODAY_CLAIMED_COUNT, 0) : 0;
        int newTodayCount = prevTodayCount + Math.max(0, claimedCount);

        String prevSentDay = state.getString(ModulePrefs.STATE_TODAY_SENT_DAY, "");
        int prevSentCount = today.equals(prevSentDay) ? state.getInt(ModulePrefs.STATE_TODAY_SENT_COUNT, 0) : 0;
        int newSentCount = prevSentCount + Math.max(0, sentHuliangCount);

        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = state.edit()
                .putString(ModulePrefs.STATE_ACTIVE_REQUEST_ID, "")
                .putLong(ModulePrefs.STATE_LAST_RUN_TIME, now)
                .putString(ModulePrefs.STATE_LAST_RUN_DATE, today)
                .putBoolean(ModulePrefs.STATE_LAST_RUN_SUCCESS, success)
                .putInt(ModulePrefs.STATE_LAST_RUN_CLAIMED, claimedCount)
                .putString(ModulePrefs.STATE_LAST_RUN_MESSAGE, message != null ? message : "")
                .putString(ModulePrefs.STATE_TODAY_CLAIMED_DAY, today)
                .putInt(ModulePrefs.STATE_TODAY_CLAIMED_COUNT, newTodayCount)
                .putString(ModulePrefs.STATE_TODAY_SENT_DAY, today)
                .putInt(ModulePrefs.STATE_TODAY_SENT_COUNT, newSentCount);

        if (checkinTotalCount > 0) {
            editor.putString(ModulePrefs.STATE_TODAY_CHECKIN_DAY, today)
                    .putInt(ModulePrefs.STATE_TODAY_CHECKIN_COUNT, checkinDoneCount)
                    .putInt(ModulePrefs.STATE_TODAY_CHECKIN_TOTAL, checkinTotalCount);
        }
        if (freeHuliangCount >= 0) {
            editor.putInt(ModulePrefs.STATE_FREE_HULIANG_COUNT, freeHuliangCount);
            if (freeExpiring24h >= 0) {
                editor.putInt(ModulePrefs.STATE_FREE_EXPIRING_24H, freeExpiring24h);
            }
            if (freeEarliestExpire >= 0L) {
                editor.putLong(ModulePrefs.STATE_FREE_EARLIEST_EXPIRE, freeEarliestExpire);
            }
        }
        if (superHuliangCount >= 0) {
            editor.putInt(ModulePrefs.STATE_SUPER_HULIANG_COUNT, superHuliangCount);
            if (superHuliangName != null && !superHuliangName.isEmpty()) {
                editor.putString(ModulePrefs.STATE_SUPER_HULIANG_NAME, superHuliangName);
            }
        }
        boolean hasSfDaySummary = sfDayActiveSummary != null && !sfDayActiveSummary.trim().isEmpty();
        if (badgesJson != null && !badgesJson.isEmpty()) {
            editor.putString(ModulePrefs.STATE_BADGES_JSON, badgesJson)
                    .putLong(ModulePrefs.STATE_BADGES_UPDATE_TIME, now)
                    .putString(ModulePrefs.STATE_SF_DAY_ACTIVE_SUMMARY,
                            hasSfDaySummary ? sfDayActiveSummary.trim() : "");
        } else if (hasSfDaySummary) {
            editor.putString(ModulePrefs.STATE_SF_DAY_ACTIVE_SUMMARY, sfDayActiveSummary.trim());
        }
        editor.apply();

        long effectiveEarliest = freeEarliestExpire >= 0L
                ? freeEarliestExpire
                : state.getLong(ModulePrefs.STATE_FREE_EARLIEST_EXPIRE, 0L);
        syncExpiryRescueAlarm(context, effectiveEarliest);

        notifyUiStateUpdated(context);

        SharedPreferences prefs = ModulePrefs.openForApp(context).prefs;
        boolean notifyEnabled = prefs.getBoolean(
                ModulePrefs.KEY_AUTO_CLAIM_NOTIFY, ModulePrefs.DEFAULT_AUTO_CLAIM_NOTIFY);
        if (notifyEnabled && (!manual || claimedCount > 0 || welfareClaimedCount > 0
                || checkinDoneCount > 0 || sentHuliangCount > 0 || hasSfDaySummary || !success)) {
            postNotification(context, success, message, hasSfDaySummary ? sfDayActiveSummary.trim() : "");
        }
    }

    public static void notifyUiStateUpdated(Context context) {
        Intent uiIntent = new Intent(AutoClaimProtocol.ACTION_UI_STATE_UPDATED);
        uiIntent.setPackage(context.getPackageName());
        context.sendBroadcast(uiIntent);
    }

    public static void postNotification(Context context, boolean success, String message) {
        postNotification(context, success, message, "");
    }

    public static void postNotification(
            Context context,
            boolean success,
            String message,
            String sfDayActiveSummary
    ) {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && context.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        "定时自动领虎粮",
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                ch.setDescription("虎伴每日后台自动领虎粮结果通知");
                nm.createNotificationChannel(ch);
            }

            Intent openIntent = new Intent(context, MainActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                piFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent contentPi = PendingIntent.getActivity(context, 0, openIntent, piFlags);

            String cleanSfDay = sfDayActiveSummary != null ? sfDayActiveSummary.trim() : "";
            boolean hasSfDay = !cleanSfDay.isEmpty();
            String title = hasSfDay
                    ? "虎伴 · 今日超粉日 4 倍亲密度"
                    : (success ? "虎伴 · 自动任务完成" : "虎伴 · 自动任务提示");
            String baseMsg = message != null ? message : "";
            String content = hasSfDay
                    ? ("今日超粉日：" + cleanSfDay + (baseMsg.isEmpty() ? "" : "\n" + baseMsg))
                    : baseMsg;

            Notification.Builder builder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setStyle(new Notification.BigTextStyle().bigText(content))
                    .setAutoCancel(true)
                    .setContentIntent(contentPi);

            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable t) {
            Log.e(TAG, "[AutoClaim] postNotification failed", t);
        }
    }

    public static String formatTime(int hour, int minute) {
        return String.format(Locale.US, "%02d:%02d", clamp(hour, 0, 23), clamp(minute, 0, 59));
    }

    public static String formatDateTime(long millis) {
        if (millis <= 0L) {
            return "未执行";
        }
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(millis));
    }

    public static String formatNextRunLabel(int hour, int minute) {
        long nextMs = computeNextDailyTriggerMillis(hour, minute);
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(nextMs);
        boolean isToday = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR);
        return (isToday ? "今天 " : "明天 ") + formatTime(hour, minute);
    }

    public static String todayDateString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static void scheduleAlarm(Context context, AlarmManager am, long triggerAtMillis, PendingIntent pi) {
        if (canScheduleExactAlarms(context)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
                return;
            } catch (SecurityException ignored) {
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
    }

    private static PendingIntent buildReceiverPendingIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, AutoClaimReceiver.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    private static PowerManager.WakeLock acquireWakeLock(Context context, long timeoutMs) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "HuyaPure:AutoClaimWake"
                );
                wl.setReferenceCounted(false);
                wl.acquire(timeoutMs);
                return wl;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void releaseWakeLock(PowerManager.WakeLock wl) {
        try {
            if (wl != null && wl.isHeld()) {
                wl.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isNetworkConnected(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isHuyaInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(AutoClaimProtocol.TARGET_PACKAGE, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
