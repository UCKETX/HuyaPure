package com.huya.pure;

import android.content.ContentProvider;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Runs inside com.duowan.kiwi (Huya main process).
 * Hooks InnerAuthLoginProvider's ContentProvider.call() so HuyaPure can cold-start and invoke
 * Huya headlessly via Binder IPC without opening any Activity or UI.
 *
 * Phase 1: Queries gift-bag reward-ad task (iAppId=8, rso="package") and invokes
 *          HyLotteryAdAwardManager's WUP award RPC in a loop until today's quota is claimed.
 * Phase 2 (optional): Queries all user fan badges via liveui.queryBadgeInfoList and submits
 *          daily fan-club check-in via wupui.setFansSign for every fan badge.
 */
final class AutoClaimEngine {

    private static final String TAG = "HuyaPure";
    private static final int HUYA_BAG_HULIANG_APP_ID = 8;
    private static final int HUYA_BAG_SOURCE = 4;
    private static final int MAX_ROUNDS_PER_TRIGGER = 40;
    private static final long ROUND_INTERVAL_MS = 1000L;
    private static final long CHECKIN_INTERVAL_MS = 350L;
    private static final long LOGIN_WAIT_POLL_MS = 500L;
    private static final long LOGIN_WAIT_TIMEOUT_MS = 15000L;
    private static final long RPC_TIMEOUT_MS = 12000L;
    private static final long SESSION_WAIT_TIMEOUT_MS = 120000L;

    private static volatile Handler sMainHandler;
    private static volatile ClassLoader sLatestClassLoader;
    private static volatile Context sAppContext;

    private static final AtomicBoolean sProviderHooked = new AtomicBoolean(false);
    private static final AtomicBoolean sRunning = new AtomicBoolean(false);
    private static volatile Object sBadgeModuleInstance;
    private static volatile Object sFansBadgeResMgrInstance;

    private static final Set<Class<?>> sHookedPanelTaskClasses =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>()));
    private static final Set<Class<?>> sHookedClickTaskClasses =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>()));
    private static final Set<Class<?>> sHookedGenericWupClasses =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<Class<?>, Boolean>()));

    private static final Map<Object, PanelCallback> sPendingPanelCallbacks =
            Collections.synchronizedMap(new IdentityHashMap<Object, PanelCallback>());
    private static final Map<Object, Runnable> sPendingClickCallbacks =
            Collections.synchronizedMap(new IdentityHashMap<Object, Runnable>());
    private static final Map<Object, GenericWupCallback> sPendingWupCallbacks =
            Collections.synchronizedMap(new IdentityHashMap<Object, GenericWupCallback>());

    private interface PanelCallback {
        void onSuccess(Object rsp);
        void onError(String toast, String detail);
    }

    private interface GenericWupCallback {
        void onSuccess(Object rsp);
        void onError(Object dataException);
    }

    private static final class BadgeTarget {
        final long pid;
        final long badgeId;
        final String badgeName;
        final String presenterNick;
        String avatarUrl = "";
        int level;
        int score;
        int nextScore;
        int dayScore;
        int quota;
        int quotaUsed;
        int extinguished;
        int badgeType;
        int customBadgeFlag;
        boolean superFan;
        int sfType;          // 0=none, 1=超粉, 2=年费超粉
        int sfVariety;       // 0=normal, 1=超粉Plus
        int sfLevel;
        int fansIdentity;    // 1=超粉, 2=年费超粉, 3=超粉Plus, 4=年费超粉Plus, 11..13=守护
        int guardLevel;      // 0=none, 1=初爱守护, 2=超级守护, 3=至尊守护
        int guardType;
        int guardAccompanyDay;
        long guardEndTime;
        String faithName = "";
        Object externalObj;  // com.duowan.HUYA.CustomBadgeDynamicExternal (transient)
        boolean customRendered = false;
        String badgePngB64 = "";
        String guardPngB64 = "";
        String checkinState = "";
        String checkinDetail = "";
        boolean sfDayActive = false;
        int sfDay = 0;
        int sfDayScoreTimes = 0;
        int sfDayQuotaTimes = 0;
        int sfDayLeftSec = 0;
        long nextSfDayEpoch = 0L;

        BadgeTarget(
                long pid,
                long badgeId,
                String badgeName,
                String presenterNick,
                int level,
                int score,
                int nextScore,
                int dayScore,
                int quota,
                int quotaUsed,
                int extinguished,
                int badgeType,
                int customBadgeFlag,
                boolean superFan,
                int sfType,
                int sfVariety,
                int sfLevel,
                int fansIdentity,
                String faithName,
                Object externalObj
        ) {
            this.pid = pid;
            this.badgeId = badgeId;
            this.badgeName = badgeName;
            this.presenterNick = presenterNick;
            this.level = level;
            this.score = score;
            this.nextScore = nextScore;
            this.dayScore = dayScore;
            this.quota = quota;
            this.quotaUsed = quotaUsed;
            this.extinguished = extinguished;
            this.badgeType = badgeType;
            this.customBadgeFlag = customBadgeFlag;
            this.superFan = superFan;
            this.sfType = sfType;
            this.sfVariety = sfVariety;
            this.sfLevel = sfLevel;
            this.fansIdentity = fansIdentity;
            this.faithName = faithName != null ? faithName : "";
            this.externalObj = externalObj;
            if (fansIdentity >= 11 && fansIdentity <= 13) {
                this.guardLevel = fansIdentity - 10;
            }
        }

        BadgeTarget(long pid, String presenterNick) {
            this(pid, 0L, "", presenterNick != null && !presenterNick.isEmpty() ? presenterNick : ("UID:" + pid),
                    0, 0, 0, 0, 0, 0, 0, 0, 0,
                    false, 0, 0, 0, 0, "", null);
        }
    }

    private static final int EFGBOX_STATE_FINISH = 3;
    private static final int EFGBOX_STATE_RECEIVED = 4;
    private static final int EOP_FGBOX_RECIVE = 3;
    private static final int REQ_MASK_USER_WITH_POPULARITY = 3;
    private static final int COMM_WELFARE_STATUS_CAN_RECEIVE = 1;
    private static final int COMM_WELFARE_STATUS_RECEIVED = 2;
    private static final int COMM_WELFARE_OP_RECEIVE = 2;

    private static final class FreeHuliangBatch {
        final int count;
        final long expireSec;

        FreeHuliangBatch(int count, long expireSec) {
            this.count = count;
            this.expireSec = expireSec;
        }
    }

    private static final class FreeHuliangItem {
        final int itemType;
        final String itemName;
        final int freeCount;
        final boolean isSuper;
        final long expireSec;

        FreeHuliangItem(int itemType, String itemName, int freeCount, boolean isSuper, long expireSec) {
            this.itemType = itemType;
            this.itemName = itemName;
            this.freeCount = freeCount;
            this.isSuper = isSuper;
            this.expireSec = expireSec;
        }
    }

    private static final class BagHuliangSummary {
        final int normalItemType;
        final String normalName;
        final int normalFreeCount;
        final List<FreeHuliangBatch> normalBatches;
        final long serverTimeSec;
        final int normalExpiring24h;
        final long earliestNormalExpireSec;
        final long detailExpireInDay;
        final List<FreeHuliangItem> superItems;
        final int superFreeCount;
        final String superDisplayName;

        BagHuliangSummary(
                int normalItemType,
                String normalName,
                int normalFreeCount,
                List<FreeHuliangBatch> normalBatches,
                long serverTimeSec,
                int normalExpiring24h,
                long earliestNormalExpireSec,
                long detailExpireInDay,
                List<FreeHuliangItem> superItems,
                int superFreeCount,
                String superDisplayName
        ) {
            this.normalItemType = normalItemType;
            this.normalName = normalName;
            this.normalFreeCount = normalFreeCount;
            this.normalBatches = normalBatches != null ? normalBatches : Collections.<FreeHuliangBatch>emptyList();
            this.serverTimeSec = serverTimeSec;
            this.normalExpiring24h = normalExpiring24h;
            this.earliestNormalExpireSec = earliestNormalExpireSec;
            this.detailExpireInDay = detailExpireInDay;
            this.superItems = superItems;
            this.superFreeCount = superFreeCount;
            this.superDisplayName = superDisplayName;
        }

        int countNormalExpiringBy(long deadlineSec) {
            boolean hasAnyBatchExpire = false;
            long sum = 0L;
            for (FreeHuliangBatch batch : normalBatches) {
                if (batch == null || batch.count <= 0) {
                    continue;
                }
                if (batch.expireSec > 0L) {
                    hasAnyBatchExpire = true;
                    if (batch.expireSec <= deadlineSec) {
                        sum += batch.count;
                    }
                }
            }
            if (!hasAnyBatchExpire && detailExpireInDay > 0L && deadlineSec <= serverTimeSec + 48L * 3600L) {
                return (int) Math.min(normalFreeCount, detailExpireInDay);
            }
            return (int) Math.min(normalFreeCount, Math.max(0L, sum));
        }
    }

    private static final class SendPlanResult {
        final List<SendPlan> plans;
        final boolean smartSfDayActive;
        final int smartSfDayScoreTimes;
        final boolean smartHoarding;
        final int hoardedNormalCount;
        final long nearestSfDayEpoch;

        SendPlanResult(
                List<SendPlan> plans,
                boolean smartSfDayActive,
                int smartSfDayScoreTimes,
                boolean smartHoarding,
                int hoardedNormalCount,
                long nearestSfDayEpoch
        ) {
            this.plans = plans;
            this.smartSfDayActive = smartSfDayActive;
            this.smartSfDayScoreTimes = smartSfDayScoreTimes;
            this.smartHoarding = smartHoarding;
            this.hoardedNormalCount = hoardedNormalCount;
            this.nearestSfDayEpoch = nearestSfDayEpoch;
        }
    }

    private static final class CommonWelfareTarget {
        final long pid;
        final String anchorNick;
        final long businessId;
        final long welfareId;
        final String title;
        final String desc;
        final int status;

        CommonWelfareTarget(
                long pid,
                String anchorNick,
                long businessId,
                long welfareId,
                String title,
                String desc,
                int status
        ) {
            this.pid = pid;
            this.anchorNick = anchorNick;
            this.businessId = businessId;
            this.welfareId = welfareId;
            this.title = title;
            this.desc = desc;
            this.status = status;
        }
    }

    private static final class SendPlan {
        final int giftId;
        final String giftName;
        final boolean isSuper;
        final long pid;
        final String nick;
        final int count;

        SendPlan(int giftId, String giftName, boolean isSuper, long pid, String nick, int count) {
            this.giftId = giftId;
            this.giftName = giftName;
            this.isSuper = isSuper;
            this.pid = pid;
            this.nick = nick;
            this.count = count;
        }
    }

    private AutoClaimEngine() {
    }

    private static Handler mainHandler() {
        Handler h = sMainHandler;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            sMainHandler = h;
        }
        return h;
    }

    private static void log(String msg) {
        Log.i(TAG, "[AutoClaim] " + msg);
        XposedBridge.log("[" + TAG + "][AutoClaim] " + msg);
    }

    private static void logError(String msg, Throwable t) {
        Log.e(TAG, "[AutoClaim] " + msg, t);
        XposedBridge.log("[" + TAG + "][AutoClaim] ERROR: " + msg);
        if (t != null) {
            XposedBridge.log(t);
        }
    }

    static void updateAppContext(Context context) {
        if (context != null) {
            Context app = context.getApplicationContext();
            sAppContext = app != null ? app : context;
        }
    }

    static void installHooks(final ClassLoader cl) {
        if (cl == null) {
            return;
        }
        sLatestClassLoader = cl;
        hookProviderCallOnce();
        hookAdModuleWupTasks(cl);
        hookBadgeSingletons(cl);
    }

    private static void hookBadgeSingletons(final ClassLoader cl) {
        try {
            Class<?> bmCls = XposedHelpers.findClassIfExists("com.duowan.kiwi.badge.BadgeModule", cl);
            if (bmCls != null && sHookedGenericWupClasses.add(bmCls)) {
                XposedBridge.hookAllConstructors(bmCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject != null) {
                            sBadgeModuleInstance = param.thisObject;
                        }
                    }
                });
            }
            Class<?> fbrmCls = XposedHelpers.findClassIfExists("com.duowan.kiwi.res.sync.FansBadgeResourceManager", cl);
            if (fbrmCls != null && sHookedGenericWupClasses.add(fbrmCls)) {
                XposedBridge.hookAllConstructors(fbrmCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject != null) {
                            sFansBadgeResMgrInstance = param.thisObject;
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    private static Object lookupHuyaService(ClassLoader cl, String interfaceName) {
        if (cl == null || interfaceName == null) {
            return null;
        }
        try {
            Class<?> iface = XposedHelpers.findClassIfExists(interfaceName, cl);
            if (iface == null) {
                return null;
            }
            Class<?> fwa = XposedHelpers.findClassIfExists("ryxq.fwa", cl);
            if (fwa != null) {
                try {
                    Object svc = XposedHelpers.callStaticMethod(fwa, "i", iface);
                    if (svc != null) {
                        return svc;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void hookProviderCallOnce() {
        if (!sProviderHooked.compareAndSet(false, true)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    ContentProvider.class,
                    "call",
                    String.class,
                    String.class,
                    Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject == null) {
                                return;
                            }
                            if (!AutoClaimProtocol.TARGET_PROVIDER_CLASS.equals(
                                    param.thisObject.getClass().getName())) {
                                return;
                            }
                            String method = param.args != null && param.args.length > 0 && param.args[0] instanceof String
                                    ? (String) param.args[0] : "";
                            boolean isAutoClaim = AutoClaimProtocol.METHOD_AUTO_CLAIM.equals(method);
                            boolean isManualSend = AutoClaimProtocol.METHOD_SEND_MANUAL_GIFT.equals(method);
                            if (!isAutoClaim && !isManualSend) {
                                return;
                            }

                            ContentProvider provider = (ContentProvider) param.thisObject;
                            Context ctx = provider.getContext();
                            updateAppContext(ctx);
                            String requestId = param.args.length > 1 && param.args[1] instanceof String
                                    ? (String) param.args[1] : UUID.randomUUID().toString();
                            Bundle extras = param.args.length > 2 && param.args[2] instanceof Bundle
                                    ? (Bundle) param.args[2] : null;

                            if (isManualSend) {
                                long sendTargetPid = extras != null
                                        ? extras.getLong(AutoClaimProtocol.EXTRA_SEND_TARGET_PID, 0L) : 0L;
                                String giftKind = extras != null
                                        ? extras.getString(AutoClaimProtocol.EXTRA_SEND_GIFT_KIND, "normal") : "normal";
                                if (giftKind == null || giftKind.isEmpty()) {
                                    giftKind = "normal";
                                }
                                int sendCount = extras != null
                                        ? extras.getInt(AutoClaimProtocol.EXTRA_SEND_COUNT, 0) : 0;
                                log("Received headless manual gift send trigger (requestId="
                                        + requestId + ", targetPid=" + sendTargetPid
                                        + ", giftKind=" + giftKind + ", sendCount=" + sendCount + ")");
                                Bundle result = executeManualGiftSendBlocking(
                                        sAppContext != null ? sAppContext : ctx,
                                        requestId,
                                        sendTargetPid,
                                        giftKind,
                                        sendCount
                                );
                                param.setResult(result);
                                return;
                            }

                            boolean manual = extras != null && extras.getBoolean(AutoClaimProtocol.EXTRA_MANUAL, false);
                            boolean doFansCheckin = extras == null
                                    || extras.getBoolean(AutoClaimProtocol.EXTRA_DO_FANS_CHECKIN, true);
                            boolean doClaimWelfare = extras == null
                                    || extras.getBoolean(AutoClaimProtocol.EXTRA_DO_CLAIM_WELFARE, true);
                            boolean doSendHuliang = extras != null
                                    && extras.getBoolean(AutoClaimProtocol.EXTRA_DO_SEND_HULIANG, false);
                            boolean doSendSuperHuliang = extras == null
                                    || extras.getBoolean(AutoClaimProtocol.EXTRA_DO_SEND_SUPER_HULIANG, true);
                            boolean smartSuperFanDaySend = extras == null
                                    || extras.getBoolean(AutoClaimProtocol.EXTRA_SMART_SUPERFAN_DAY_SEND,
                                    ModulePrefs.DEFAULT_SMART_SUPERFAN_DAY_SEND);
                            long sendTargetPid = extras != null
                                    ? extras.getLong(AutoClaimProtocol.EXTRA_SEND_TARGET_PID, 0L) : 0L;
                            String sendTargetsSpec = extras != null
                                    ? extras.getString(AutoClaimProtocol.EXTRA_SEND_TARGETS_SPEC, "") : "";
                            if (sendTargetsSpec == null) {
                                sendTargetsSpec = "";
                            }
                            int sendDistributeMode = extras != null
                                    ? extras.getInt(AutoClaimProtocol.EXTRA_SEND_DISTRIBUTE_MODE,
                                    ModulePrefs.DEFAULT_SEND_HULIANG_DISTRIBUTE_MODE)
                                    : ModulePrefs.DEFAULT_SEND_HULIANG_DISTRIBUTE_MODE;
                            int sendMaxCount = extras != null
                                    ? extras.getInt(AutoClaimProtocol.EXTRA_SEND_MAX_COUNT, 0) : 0;

                            log("Received headless ContentProvider.call trigger (requestId="
                                    + requestId + ", manual=" + manual
                                    + ", doFansCheckin=" + doFansCheckin
                                    + ", doClaimWelfare=" + doClaimWelfare
                                    + ", doSendHuliang=" + doSendHuliang
                                    + ", doSendSuperHuliang=" + doSendSuperHuliang
                                    + ", smartSfDay=" + smartSuperFanDaySend
                                    + ", targetPid=" + sendTargetPid
                                    + ", targetsSpec=" + (sendTargetsSpec.isEmpty() ? "(all)" : sendTargetsSpec)
                                    + ", distMode=" + sendDistributeMode
                                    + ", maxCount=" + sendMaxCount + ")");
                            Bundle result = executeClaimBlocking(
                                    sAppContext != null ? sAppContext : ctx,
                                    requestId,
                                    manual,
                                    doFansCheckin,
                                    doClaimWelfare,
                                    doSendHuliang,
                                    doSendSuperHuliang,
                                    smartSuperFanDaySend,
                                    sendTargetPid,
                                    sendTargetsSpec,
                                    sendDistributeMode,
                                    sendMaxCount
                            );
                            param.setResult(result);
                        }
                    }
            );
            log("Hooked ContentProvider.call for " + AutoClaimProtocol.TARGET_PROVIDER_CLASS);
        } catch (Throwable t) {
            sProviderHooked.set(false);
            logError("hookProviderCallOnce failed", t);
        }
    }

    private static Bundle executeClaimBlocking(
            final Context context,
            final String requestId,
            final boolean manual,
            final boolean doFansCheckin,
            final boolean doClaimWelfare,
            final boolean doSendHuliang,
            final boolean doSendSuperHuliang,
            final boolean smartSuperFanDaySend,
            final long sendTargetPid,
            final String sendTargetsSpec,
            final int sendDistributeMode,
            final int sendMaxCount
    ) {
        if (!sRunning.compareAndSet(false, true)) {
            return buildResultBundle(requestId, manual, false, 0, false, 0,
                    0, 0, 0, 0, "已有后台领虎粮/打卡任务正在执行中，请稍候");
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bundle> resultRef = new AtomicReference<>();

        final long startTime = System.currentTimeMillis();
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                ClassLoader cl = resolveActiveClassLoader(context);
                if (cl != null) {
                    hookAdModuleWupTasks(cl);
                }
                if (isUserLoggedIn(cl)) {
                    log("Huya login session ready, starting auto-claim session");
                    new ClaimSession(
                            cl, requestId, manual,
                            doFansCheckin, doClaimWelfare, doSendHuliang, doSendSuperHuliang,
                            smartSuperFanDaySend, sendTargetPid, sendTargetsSpec, sendDistributeMode,
                            sendMaxCount,
                            bundle -> {
                                resultRef.set(bundle);
                                latch.countDown();
                            }
                    ).start();
                    return;
                }
                if (System.currentTimeMillis() - startTime >= LOGIN_WAIT_TIMEOUT_MS) {
                    log("Timed out waiting for Huya login session");
                    sRunning.set(false);
                    resultRef.set(buildResultBundle(
                            requestId, manual, false, 0, false, 0,
                            0, 0, 0, 0,
                            "未检测到虎牙登录状态（请先打开虎牙保持登录）"
                    ));
                    latch.countDown();
                    return;
                }
                mainHandler().postDelayed(this, LOGIN_WAIT_POLL_MS);
            }
        });

        try {
            boolean completed = latch.await(SESSION_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!completed) {
                log("executeClaimBlocking timed out waiting for ClaimSession");
                sRunning.set(false);
                return buildResultBundle(requestId, manual, false, 0, false, 0,
                        0, 0, 0, 0, "后台领虎粮/打卡执行超时，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sRunning.set(false);
            return buildResultBundle(requestId, manual, false, 0, false, 0,
                    0, 0, 0, 0, "后台任务被中断");
        }

        Bundle out = resultRef.get();
        return out != null
                ? out
                : buildResultBundle(requestId, manual, false, 0, false, 0,
                        0, 0, 0, 0, "后台任务未返回结果");
    }

    private interface ManualSendFinishCallback {
        void onFinished(Bundle result);
    }

    private static Bundle executeManualGiftSendBlocking(
            final Context context,
            final String requestId,
            final long sendTargetPid,
            final String giftKind,
            final int sendCount
    ) {
        if (sendTargetPid <= 0L) {
            return buildResultBundle(requestId, true, false, 0, false, 0,
                    0, 0, 0, 0, "送礼目标主播 UID 无效");
        }
        if (!sRunning.compareAndSet(false, true)) {
            return buildResultBundle(requestId, true, false, 0, false, 0,
                    0, 0, 0, 0, "已有后台任务正在执行中，请稍候");
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bundle> resultRef = new AtomicReference<>();
        final AtomicBoolean settled = new AtomicBoolean(false);
        final ManualSendFinishCallback finish = bundle -> {
            if (settled.compareAndSet(false, true)) {
                sRunning.set(false);
                resultRef.set(bundle);
                latch.countDown();
            }
        };

        final long startTime = System.currentTimeMillis();
        mainHandler().post(new Runnable() {
            @Override
            public void run() {
                if (settled.get()) {
                    return;
                }
                final ClassLoader cl = resolveActiveClassLoader(context);
                if (isUserLoggedIn(cl)) {
                    log("Huya login session ready, starting manual free gift send"
                            + " (targetPid=" + sendTargetPid
                            + ", giftKind=" + giftKind + ", sendCount=" + sendCount + ")");
                    startManualGiftSendOnMain(cl, requestId, sendTargetPid, giftKind, sendCount, settled, finish);
                    return;
                }
                if (System.currentTimeMillis() - startTime >= LOGIN_WAIT_TIMEOUT_MS) {
                    log("Manual gift send timed out waiting for Huya login session");
                    finish.onFinished(buildResultBundle(
                            requestId, true, false, 0, false, 0,
                            0, 0, 0, 0,
                            "未检测到虎牙登录状态（请先打开虎牙保持登录）"
                    ));
                    return;
                }
                mainHandler().postDelayed(this, LOGIN_WAIT_POLL_MS);
            }
        });

        try {
            boolean completed = latch.await(40000L, TimeUnit.MILLISECONDS);
            if (!completed) {
                log("executeManualGiftSendBlocking timed out waiting for manual send");
                if (settled.compareAndSet(false, true)) {
                    sRunning.set(false);
                }
                return buildResultBundle(requestId, true, false, 0, false, 0,
                        0, 0, 0, 0, "后台送礼执行超时，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (settled.compareAndSet(false, true)) {
                sRunning.set(false);
            }
            return buildResultBundle(requestId, true, false, 0, false, 0,
                    0, 0, 0, 0, "后台任务被中断");
        }

        if (settled.compareAndSet(false, true)) {
            sRunning.set(false);
        }
        Bundle out = resultRef.get();
        return out != null
                ? out
                : buildResultBundle(requestId, true, false, 0, false, 0,
                        0, 0, 0, 0, "后台任务未返回结果");
    }

    private static void startManualGiftSendOnMain(
            final ClassLoader cl,
            final String requestId,
            final long sendTargetPid,
            final String giftKind,
            final int sendCount,
            final AtomicBoolean settled,
            final ManualSendFinishCallback finish
    ) {
        queryBagHuliang(cl, sendTargetPid, new BagHuliangCallback() {
            @Override
            public void onSuccess(final BagHuliangSummary bag) {
                if (settled.get()) {
                    return;
                }
                log("ManualSend bag: normalFree=" + bag.normalFreeCount
                        + " (type=" + bag.normalItemType + ", name=" + bag.normalName
                        + "), superFree=" + bag.superFreeCount
                        + " (types=" + bag.superItems.size() + ", name=" + bag.superDisplayName + ")");

                if ("super".equals(giftKind)) {
                    if (bag.superFreeCount <= 0 || bag.superItems == null || bag.superItems.isEmpty()) {
                        finish.onFinished(buildResultBundle(
                                requestId, true, false, 0, 0, false, 0,
                                0, 0, 0, 0,
                                bag.normalFreeCount, bag.superFreeCount, bag.superDisplayName,
                                0, 0, "", "包裹内暂无可用的超粉虎粮"
                        ));
                        return;
                    }
                    int remaining = sendCount > 0
                            ? Math.min(sendCount, bag.superFreeCount)
                            : bag.superFreeCount;
                    final List<SendPlan> plans = new ArrayList<>();
                    for (FreeHuliangItem item : bag.superItems) {
                        if (item == null || item.freeCount <= 0 || remaining <= 0) {
                            continue;
                        }
                        int toSend = Math.min(item.freeCount, remaining);
                        if (toSend > 0) {
                            plans.add(new SendPlan(
                                    item.itemType, item.itemName, true,
                                    sendTargetPid, "", toSend));
                            remaining -= toSend;
                        }
                    }
                    if (plans.isEmpty()) {
                        finish.onFinished(buildResultBundle(
                                requestId, true, false, 0, 0, false, 0,
                                0, 0, 0, 0,
                                bag.normalFreeCount, bag.superFreeCount, bag.superDisplayName,
                                0, 0, "", "包裹内暂无可用的超粉虎粮"
                        ));
                        return;
                    }
                    executeManualSuperSendPlans(
                            cl, requestId, bag, plans, 0, 0, new ArrayList<>(), settled, finish);
                } else {
                    if (bag.normalFreeCount <= 0) {
                        finish.onFinished(buildResultBundle(
                                requestId, true, false, 0, 0, false, 0,
                                0, 0, 0, 0,
                                bag.normalFreeCount, bag.superFreeCount, bag.superDisplayName,
                                0, 0, "", "包裹内暂无免费虎粮"
                        ));
                        return;
                    }
                    final int toSend = sendCount > 0
                            ? Math.min(sendCount, bag.normalFreeCount)
                            : bag.normalFreeCount;
                    final String label = nonEmptyOr(bag.normalName, "虎粮");
                    sendFreeHuliangOnce(cl, bag.normalItemType, sendTargetPid, toSend, new SendGiftCallback() {
                        @Override
                        public void onResult(boolean ok, String detail) {
                            if (settled.get()) {
                                return;
                            }
                            log("ManualSend normal " + label + "(id=" + bag.normalItemType
                                    + ") -> pid=" + sendTargetPid + ", count=" + toSend
                                    + " -> ok=" + ok + " (" + detail + ")");
                            int sentNormal = ok ? toSend : 0;
                            int remainNormal = Math.max(0, bag.normalFreeCount - sentNormal);
                            int remainSuper = bag.superFreeCount;
                            String msg = ok
                                    ? ("已送出 " + sentNormal + " 个" + label)
                                    : ("送出" + label + "失败：" + nonEmptyOr(detail, "未知错误"));
                            finish.onFinished(buildResultBundle(
                                    requestId, true, ok, 0, 0, false, 0,
                                    0, 0, 0, 0,
                                    remainNormal, remainSuper, bag.superDisplayName,
                                    sentNormal, 0, "", msg
                            ));
                        }
                    });
                }
            }

            @Override
            public void onError(String reason) {
                if (settled.get()) {
                    return;
                }
                log("ManualSend queryBagHuliang failed: " + reason);
                finish.onFinished(buildResultBundle(
                        requestId, true, false, 0, false, 0,
                        0, 0, 0, 0,
                        "查询包裹失败：" + nonEmptyOr(reason, "未知错误")
                ));
            }
        });
    }

    private static void executeManualSuperSendPlans(
            final ClassLoader cl,
            final String requestId,
            final BagHuliangSummary bag,
            final List<SendPlan> plans,
            final int index,
            final int sentSuperAcc,
            final List<String> errors,
            final AtomicBoolean settled,
            final ManualSendFinishCallback finish
    ) {
        if (settled.get()) {
            return;
        }
        if (index >= plans.size()) {
            int remainNormal = bag.normalFreeCount;
            int remainSuper = Math.max(0, bag.superFreeCount - sentSuperAcc);
            String label = nonEmptyOr(bag.superDisplayName, "超粉虎粮");
            boolean success = sentSuperAcc > 0;
            String msg;
            if (success) {
                msg = "已送出 " + sentSuperAcc + " 个" + label;
                if (!errors.isEmpty()) {
                    msg = msg + "（部分失败：" + joinStrings(errors, "；") + "）";
                }
            } else {
                msg = "送出" + label + "失败："
                        + (errors.isEmpty() ? "未知错误" : joinStrings(errors, "；"));
            }
            finish.onFinished(buildResultBundle(
                    requestId, true, success, 0, 0, false, 0,
                    0, 0, 0, 0,
                    remainNormal, remainSuper, bag.superDisplayName,
                    0, sentSuperAcc, "", msg
            ));
            return;
        }

        final SendPlan plan = plans.get(index);
        sendFreeHuliangOnce(cl, plan.giftId, plan.pid, plan.count, new SendGiftCallback() {
            @Override
            public void onResult(boolean ok, String detail) {
                if (settled.get()) {
                    return;
                }
                log("ManualSend super [" + (index + 1) + "/" + plans.size() + "] "
                        + plan.giftName + "(id=" + plan.giftId + ") -> pid=" + plan.pid
                        + ", count=" + plan.count + " -> ok=" + ok + " (" + detail + ")");
                int nextSent = sentSuperAcc + (ok ? plan.count : 0);
                if (!ok) {
                    errors.add(nonEmptyOr(detail, "送礼失败"));
                }
                int nextIndex = index + 1;
                if (nextIndex < plans.size()) {
                    mainHandler().postDelayed(
                            () -> executeManualSuperSendPlans(
                                    cl, requestId, bag, plans, nextIndex, nextSent, errors, settled, finish),
                            CHECKIN_INTERVAL_MS
                    );
                } else {
                    executeManualSuperSendPlans(
                            cl, requestId, bag, plans, nextIndex, nextSent, errors, settled, finish);
                }
            }
        });
    }

    private static void hookAdModuleWupTasks(final ClassLoader cl) {
        Class<?> hyAdModuleClass = XposedHelpers.findClassIfExists("com.duowan.kiwi.ad.impl.HyAdModule", cl);
        if (hyAdModuleClass == null) {
            return;
        }
        Class<?> panelReqClass = XposedHelpers.findClassIfExists("com.duowan.HUYA.GetCommAdPannelReq", cl);
        Class<?> panelRspClass = XposedHelpers.findClassIfExists("com.duowan.HUYA.GetCommAdPannelRsp", cl);
        Class<?> clickReqClass = XposedHelpers.findClassIfExists("com.duowan.HUYA.OnClickCommRewardAdTaskEventReq", cl);
        Class<?> dataCallbackClass = XposedHelpers.findClassIfExists("com.duowan.biz.util.callback.DataCallback", cl);
        if (panelReqClass == null || panelRspClass == null || dataCallbackClass == null) {
            return;
        }

        Class<?> panelTaskClass = findInnerTaskClass3(cl, hyAdModuleClass, panelReqClass, dataCallbackClass);
        if (panelTaskClass != null && sHookedPanelTaskClasses.add(panelTaskClass)) {
            hookPanelTaskClass(panelTaskClass, panelRspClass);
        }

        if (clickReqClass != null) {
            Class<?> clickTaskClass = findInnerTaskClass3(cl, hyAdModuleClass, clickReqClass, dataCallbackClass);
            if (clickTaskClass != null && sHookedClickTaskClasses.add(clickTaskClass)) {
                hookClickTaskClass(clickTaskClass);
            }
        }
    }

    private static Class<?> findInnerTaskClass3(
            ClassLoader cl,
            Class<?> outerClass,
            Class<?> reqClass,
            Class<?> callbackClass
    ) {
        try {
            for (Class<?> inner : outerClass.getDeclaredClasses()) {
                if (matchesTaskCtor3(inner, outerClass, reqClass, callbackClass)) {
                    return inner;
                }
            }
        } catch (Throwable ignored) {
        }

        String baseName = outerClass.getName() + "$";
        for (char c = 'a'; c <= 'z'; c++) {
            Class<?> candidate = XposedHelpers.findClassIfExists(baseName + c, cl);
            if (candidate != null && matchesTaskCtor3(candidate, outerClass, reqClass, callbackClass)) {
                return candidate;
            }
        }
        for (int i = 1; i <= 30; i++) {
            Class<?> candidate = XposedHelpers.findClassIfExists(baseName + i, cl);
            if (candidate != null && matchesTaskCtor3(candidate, outerClass, reqClass, callbackClass)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean matchesTaskCtor3(
            Class<?> candidate,
            Class<?> outerClass,
            Class<?> reqClass,
            Class<?> callbackClass
    ) {
        try {
            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 3
                        && params[0] == outerClass
                        && params[1] == reqClass
                        && params[2] == callbackClass) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Class<?> findSingleArgInnerWupClass(ClassLoader cl, Class<?> outerClass, Class<?> reqClass) {
        try {
            for (Class<?> inner : outerClass.getDeclaredClasses()) {
                if (matchesSingleArgCtor(inner, reqClass)) {
                    return inner;
                }
            }
        } catch (Throwable ignored) {
        }

        // Probe common obfuscated inner names ($a..$z, $a0..$z9, $1..$99) as fallback.
        String baseName = outerClass.getName() + "$";
        for (char c = 'a'; c <= 'z'; c++) {
            Class<?> cand = XposedHelpers.findClassIfExists(baseName + c, cl);
            if (cand != null && matchesSingleArgCtor(cand, reqClass)) {
                return cand;
            }
            for (int d = 0; d <= 9; d++) {
                Class<?> cand2 = XposedHelpers.findClassIfExists(baseName + c + d, cl);
                if (cand2 != null && matchesSingleArgCtor(cand2, reqClass)) {
                    return cand2;
                }
            }
        }
        for (int i = 1; i <= 120; i++) {
            Class<?> cand = XposedHelpers.findClassIfExists(baseName + i, cl);
            if (cand != null && matchesSingleArgCtor(cand, reqClass)) {
                return cand;
            }
        }
        return null;
    }

    private static boolean matchesSingleArgCtor(Class<?> candidate, Class<?> reqClass) {
        try {
            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 1 && params[0] == reqClass) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Hooks onResponse / onError on the declaring superclass(es) of a concrete Huya WupFunction
     * without hardcoding obfuscated base class names. Only intercepts instances registered in
     * sPendingWupCallbacks.
     */
    private static void ensureGenericWupHooks(Class<?> taskClass) {
        Class<?> jceStructClass = XposedHelpers.findClassIfExists(
                "com.duowan.taf.jce.JceStruct", taskClass.getClassLoader());
        Class<?> c = taskClass;
        while (c != null && c != Object.class) {
            List<Method> respMethods = new ArrayList<>();
            List<Method> errMethods = new ArrayList<>();
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isBridge() || Modifier.isAbstract(m.getModifiers())) {
                        continue;
                    }
                    String name = m.getName();
                    if ("onResponse".equals(name)) {
                        respMethods.add(m);
                    } else if ("onError".equals(name)) {
                        errMethods.add(m);
                    }
                }
            } catch (Throwable ignored) {
            }

            if ((!respMethods.isEmpty() || !errMethods.isEmpty()) && sHookedGenericWupClasses.add(c)) {
                final Class<?> finalJce = jceStructClass;
                for (Method m : respMethods) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.thisObject == null || param.args == null || param.args.length < 1) {
                                    return;
                                }
                                Object rsp = param.args[0];
                                if (finalJce != null && rsp != null && !finalJce.isInstance(rsp)) {
                                    return;
                                }
                                GenericWupCallback cb = sPendingWupCallbacks.remove(param.thisObject);
                                if (cb == null) {
                                    return;
                                }
                                param.setResult(null);
                                cb.onSuccess(rsp);
                            }
                        });
                    } catch (Throwable t) {
                        logError("hook generic WUP onResponse failed on " + c.getName(), t);
                    }
                }
                for (Method m : errMethods) {
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                if (param.thisObject == null) {
                                    return;
                                }
                                GenericWupCallback cb = sPendingWupCallbacks.remove(param.thisObject);
                                if (cb == null) {
                                    return;
                                }
                                param.setResult(null);
                                Object ex = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                                cb.onError(ex);
                            }
                        });
                    } catch (Throwable t) {
                        logError("hook generic WUP onError failed on " + c.getName(), t);
                    }
                }
                log("Hooked generic WUP base callbacks on " + c.getName());
            }
            c = c.getSuperclass();
        }
    }

    private static void hookPanelTaskClass(Class<?> panelTaskClass, final Class<?> panelRspClass) {
        try {
            XposedBridge.hookAllMethods(panelTaskClass, "onResponse", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject == null || param.args == null || param.args.length < 1) {
                        return;
                    }
                    Object rsp = param.args[0];
                    if (rsp != null && !panelRspClass.isInstance(rsp)) {
                        return;
                    }
                    PanelCallback cb = sPendingPanelCallbacks.remove(param.thisObject);
                    if (cb == null) {
                        return;
                    }
                    param.setResult(null);
                    cb.onSuccess(rsp);
                }
            });

            XposedBridge.hookAllMethods(panelTaskClass, "onError", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject == null) {
                        return;
                    }
                    PanelCallback cb = sPendingPanelCallbacks.remove(param.thisObject);
                    if (cb == null) {
                        return;
                    }
                    param.setResult(null);

                    String toast = "";
                    String detail = "unknown error";
                    if (param.args != null && param.args.length > 0 && param.args[0] != null) {
                        Object ex = param.args[0];
                        detail = String.valueOf(ex);
                        try {
                            Object resp = XposedHelpers.getObjectField(ex, "mResponse");
                            if (resp != null && panelRspClass.isInstance(resp)) {
                                Object tObj = XposedHelpers.getObjectField(resp, "sToast");
                                if (tObj instanceof String) {
                                    toast = (String) tObj;
                                }
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    cb.onError(toast, detail);
                }
            });
            log("Hooked GetCommAdPannel WUP task class: " + panelTaskClass.getName());
        } catch (Throwable t) {
            logError("hookPanelTaskClass failed", t);
        }
    }

    private static void hookClickTaskClass(Class<?> clickTaskClass) {
        try {
            XC_MethodHook finishHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject == null) {
                        return;
                    }
                    Runnable cb = sPendingClickCallbacks.remove(param.thisObject);
                    if (cb == null) {
                        return;
                    }
                    param.setResult(null);
                    cb.run();
                }
            };
            XposedBridge.hookAllMethods(clickTaskClass, "onResponse", finishHook);
            XposedBridge.hookAllMethods(clickTaskClass, "onError", finishHook);
            log("Hooked OnClickCommRewardAdTaskEvent WUP task class: " + clickTaskClass.getName());
        } catch (Throwable t) {
            logError("hookClickTaskClass failed", t);
        }
    }

    private static ClassLoader resolveActiveClassLoader(Context context) {
        if (context != null && context.getClassLoader() != null) {
            ClassLoader appCl = context.getClassLoader();
            if (XposedHelpers.findClassIfExists("com.duowan.biz.videoad.impl.HyLotteryAdAwardManager", appCl) != null) {
                sLatestClassLoader = appCl;
                return appCl;
            }
        }
        return sLatestClassLoader;
    }

    private static Object getHuyaUserId(ClassLoader cl) {
        if (cl == null) {
            return null;
        }
        try {
            Class<?> wupHelper = XposedHelpers.findClassIfExists("com.duowan.biz.wup.WupHelper", cl);
            if (wupHelper == null) {
                return null;
            }
            return XposedHelpers.callStaticMethod(wupHelper, "getUserId");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isUserLoggedIn(ClassLoader cl) {
        try {
            Object userId = getHuyaUserId(cl);
            if (userId == null) {
                return false;
            }
            long uid = XposedHelpers.getLongField(userId, "lUid");
            String token = (String) XposedHelpers.getObjectField(userId, "sToken");
            String cookie = (String) XposedHelpers.getObjectField(userId, "sCookie");
            boolean ready = uid > 0 && ((token != null && !token.isEmpty()) || (cookie != null && !cookie.isEmpty()));
            if (ready) {
                log("Detected logged-in Huya user uid=" + uid);
            }
            return ready;
        } catch (Throwable t) {
            return false;
        }
    }

    private interface SessionFinishCallback {
        void onFinished(Bundle resultBundle);
    }

    private static final class ClaimSession {
        private final ClassLoader cl;
        private final String requestId;
        private final boolean manual;
        private final boolean doFansCheckin;
        private final boolean doClaimWelfare;
        private final boolean doSendHuliang;
        private final boolean doSendSuperHuliang;
        private final boolean smartSuperFanDaySend;
        private final long sendTargetPid;
        private final String sendTargetsSpec;
        private final int sendDistributeMode;
        private final LinkedHashMap<Long, Integer> targetWeights;
        private final int sendMaxCount;
        private final SessionFinishCallback finishCallback;

        // Phase 1: 虎粮 state
        private final List<String> rewardToasts = new ArrayList<>();
        private int claimedCount = 0;
        private int round = 0;
        private int transientRefreshRetries = 0;
        private boolean huliangSuccess = false;
        private boolean huliangExhausted = false;
        private int huliangCooldown = 0;
        private String huliangSummary = "";

        // Phase 2: 粉丝牌打卡 state
        private final List<BadgeTarget> badgeTargets = new ArrayList<>();
        private int checkinTotal = 0;
        private int checkinSuccess = 0;
        private int checkinAlready = 0;
        private int checkinFailed = 0;
        private String checkinSummary = "";

        // Phase 2.5: 粉丝团每日福利 & 超粉虎粮 & 其他通用福利 state
        private int welfareClaimedCount = 0;
        private int welfareAlreadyCount = 0;
        private final List<String> welfareClaimedLabels = new ArrayList<>();
        private final Set<String> attemptedCommWelfares = new HashSet<>();
        private String welfareSummary = "";

        // Phase 3: 自动送免费虎粮（普通 + 超粉虎粮）state
        private int freeHuliangCount = -1;
        private int freeExpiring24h = -1;
        private long freeEarliestExpire = -1L;
        private String sfDayActiveSummary = "";
        private int superHuliangCount = -1;
        private String superHuliangName = "";
        private int sentHuliangCount = 0;
        private int sentSuperHuliangCount = 0;
        private int smartHoardedCount = 0;
        private String sendSummary = "";

        private boolean finished = false;

        ClaimSession(
                ClassLoader cl,
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
                int sendMaxCount,
                SessionFinishCallback finishCallback
        ) {
            this.cl = cl;
            this.requestId = requestId;
            this.manual = manual;
            this.doFansCheckin = doFansCheckin;
            this.doClaimWelfare = doClaimWelfare;
            this.doSendHuliang = doSendHuliang;
            this.doSendSuperHuliang = doSendSuperHuliang;
            this.smartSuperFanDaySend = smartSuperFanDaySend;
            this.sendTargetPid = sendTargetPid;
            this.sendTargetsSpec = sendTargetsSpec != null ? sendTargetsSpec : "";
            this.sendDistributeMode = sendDistributeMode;
            this.targetWeights = parseTargetsSpec(this.sendTargetsSpec, sendTargetPid);
            this.sendMaxCount = Math.max(0, sendMaxCount);
            this.finishCallback = finishCallback;
        }

        private static LinkedHashMap<Long, Integer> parseTargetsSpec(String spec, long legacyPid) {
            LinkedHashMap<Long, Integer> map = new LinkedHashMap<>();
            if (spec != null && !spec.trim().isEmpty()) {
                String[] entries = spec.trim().split(",");
                for (String entry : entries) {
                    if (entry == null) {
                        continue;
                    }
                    String trimmed = entry.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    int colon = trimmed.indexOf(':');
                    String pidStr = colon >= 0 ? trimmed.substring(0, colon).trim() : trimmed;
                    String weightStr = colon >= 0 ? trimmed.substring(colon + 1).trim() : "1";
                    try {
                        long pid = Long.parseLong(pidStr);
                        if (pid <= 0L) {
                            continue;
                        }
                        int weight = 1;
                        if (!weightStr.isEmpty()) {
                            weight = Math.max(1, Integer.parseInt(weightStr));
                        }
                        map.put(pid, weight);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (map.isEmpty() && legacyPid > 0L) {
                map.put(legacyPid, 1);
            }
            return map;
        }

        void start() {
            runNextHuliangRound();
        }

        // ── Phase 1: Gift-bag 虎粮 reward-ad claiming ─────────────────────────

        private void runNextHuliangRound() {
            if (finished) {
                return;
            }
            if (round >= MAX_ROUNDS_PER_TRIGGER) {
                completeHuliangPhase(claimedCount > 0, false, 0,
                        buildHuliangSummary("已达到单次执行上限（" + MAX_ROUNDS_PER_TRIGGER + " 次）"));
                return;
            }
            round++;
            final String reqId = createReqId(cl);
            log("Round #" + round + ": requesting GetCommAdPannel (iAppId=" + HUYA_BAG_HULIANG_APP_ID
                    + ", reqId=" + reqId + ")");

            requestBagHuliangPanel(cl, reqId, new PanelCallback() {
                @Override
                public void onSuccess(Object rsp) {
                    if (finished) {
                        return;
                    }
                    handlePanelResponse(reqId, rsp);
                }

                @Override
                public void onError(String toast, String detail) {
                    if (finished) {
                        return;
                    }
                    log("Round #" + round + " GetCommAdPannel error: toast=" + toast + ", detail=" + detail);
                    if (toast != null && (toast.contains("已刷新") || toast.contains("重新点击"))
                            && transientRefreshRetries < 2) {
                        transientRefreshRetries++;
                        round--;
                        log("Transient refresh toast detected, retrying GetCommAdPannel (#"
                                + transientRefreshRetries + ") in 1000ms");
                        mainHandler().postDelayed(ClaimSession.this::runNextHuliangRound, ROUND_INTERVAL_MS);
                        return;
                    }
                    int cooldown = parseCooldownSeconds(toast);
                    boolean quotaDone = isExhaustedMessage(toast);
                    if (claimedCount > 0) {
                        completeHuliangPhase(true, quotaDone, cooldown,
                                buildHuliangSummary(nonEmptyOr(toast, "后续无可用任务")));
                    } else if (quotaDone) {
                        completeHuliangPhase(true, true, 0, nonEmptyOr(toast, "今日虎粮已全部领完"));
                    } else if (cooldown > 0) {
                        completeHuliangPhase(false, false, cooldown, nonEmptyOr(toast, "冷却中，稍后将自动重试"));
                    } else {
                        completeHuliangPhase(false, false, 0, nonEmptyOr(toast, "拉取虎粮任务失败：" + detail));
                    }
                }
            });
        }

        private void handlePanelResponse(final String reqId, Object rsp) {
            if (rsp == null) {
                completeHuliangPhase(claimedCount > 0, false, 0, buildHuliangSummary("服务端返回空任务响应"));
                return;
            }
            try {
                String panelToast = safeStringField(rsp, "sToast");
                Object taskInfo = XposedHelpers.getObjectField(rsp, "tAdTaskInfo");
                if (taskInfo == null) {
                    log("Round #" + round + ": tAdTaskInfo is null, sToast=" + panelToast);
                    int cooldown = parseCooldownSeconds(panelToast);
                    boolean exhausted = isExhaustedMessage(panelToast) || (cooldown == 0 && claimedCount > 0);
                    if (claimedCount > 0) {
                        completeHuliangPhase(true, exhausted, cooldown,
                                buildHuliangSummary(nonEmptyOr(panelToast, "今日虎粮已全部领完")));
                    } else if (exhausted) {
                        completeHuliangPhase(true, true, 0, nonEmptyOr(panelToast, "今日虎粮已全部领完"));
                    } else {
                        completeHuliangPhase(false, false, cooldown, nonEmptyOr(panelToast, "当前暂无可领取的虎粮任务"));
                    }
                    return;
                }

                final String adTaskId = safeStringField(taskInfo, "sAdTaskId");
                String slotCode = safeStringField(taskInfo, "sSlotCode");
                int maxReward = safeIntField(taskInfo, "iMaxRewardCount");
                int displayCount = safeIntField(taskInfo, "iDisplayCount");
                int needWatchTime = safeIntField(taskInfo, "iNeedWatchTime");
                Object mpExtObj = XposedHelpers.getObjectField(taskInfo, "mpExt");
                final Object slotAd = XposedHelpers.getObjectField(taskInfo, "tAd");

                log("Round #" + round + " taskInfo: sAdTaskId=" + adTaskId
                        + ", sSlotCode=" + slotCode
                        + ", maxReward=" + maxReward
                        + ", displayCount=" + displayCount
                        + ", needWatchTime=" + needWatchTime
                        + ", mpExt=" + mpExtObj
                        + ", panelToast=" + panelToast);

                if (adTaskId.isEmpty()) {
                    int cooldown = parseCooldownSeconds(panelToast);
                    completeHuliangPhase(claimedCount > 0, cooldown == 0, cooldown,
                            buildHuliangSummary(nonEmptyOr(panelToast, "当前无可用虎粮任务ID")));
                    return;
                }

                @SuppressWarnings("unchecked")
                final Map<String, String> mpExt = (mpExtObj instanceof Map)
                        ? new HashMap<>((Map<String, String>) mpExtObj)
                        : new HashMap<String, String>();
                final String extraJson = mapToJson(mpExt);
                final boolean useCommonRewardApi = shouldUseCommonRewardApi(cl, mpExt);
                final boolean needClickCheck = shouldReportClickEvent(cl, mpExt) && slotAd != null;

                Runnable doAward = () -> invokeAwardApi(adTaskId, extraJson, reqId, useCommonRewardApi);

                if (needClickCheck) {
                    log("Round #" + round + ": reporting OnClickCommRewardAdTaskEvent before award");
                    reportClickEvent(cl, reqId, mpExt, slotAd, doAward);
                } else {
                    doAward.run();
                }
            } catch (Throwable t) {
                logError("handlePanelResponse failed", t);
                completeHuliangPhase(claimedCount > 0, false, 0, buildHuliangSummary("解析任务失败：" + t.getMessage()));
            }
        }

        private void invokeAwardApi(String adTaskId, String extraJson, String reqId, boolean useCommonRewardApi) {
            try {
                Class<?> awardMgrClass = XposedHelpers.findClass(
                        "com.duowan.biz.videoad.impl.HyLotteryAdAwardManager", cl);
                Class<?> listenerInterface = XposedHelpers.findClass(
                        "com.duowan.kiwi.ad.api.IHyLotteryAdHelper$HyRewardAdListener", cl);

                Object companion = XposedHelpers.getStaticObjectField(awardMgrClass, "Companion");
                Object awardMgr = XposedHelpers.callMethod(companion, "getInstance");

                final AtomicBoolean callbackDone = new AtomicBoolean(false);
                final Runnable timeoutTask = () -> {
                    if (callbackDone.compareAndSet(false, true)) {
                        log("Round #" + round + " award RPC timed out");
                        completeHuliangPhase(claimedCount > 0, false, 0, buildHuliangSummary("领奖请求超时"));
                    }
                };
                mainHandler().postDelayed(timeoutTask, RPC_TIMEOUT_MS);

                Object listenerProxy = Proxy.newProxyInstance(
                        cl,
                        new Class<?>[]{listenerInterface},
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) {
                                String name = method.getName();
                                if ("equals".equals(name)) {
                                    return args != null && args.length == 1 && proxy == args[0];
                                }
                                if ("hashCode".equals(name)) {
                                    return System.identityHashCode(proxy);
                                }
                                if ("toString".equals(name)) {
                                    return "HuyaPureAutoClaimListener@" + Integer.toHexString(System.identityHashCode(proxy));
                                }
                                if ("onRewarded".equals(name) && args != null && args.length >= 3) {
                                    if (!callbackDone.compareAndSet(false, true)) {
                                        return null;
                                    }
                                    mainHandler().removeCallbacks(timeoutTask);
                                    boolean ok = Boolean.TRUE.equals(args[0]);
                                    Object respMap = args[1];
                                    String toast = args[2] instanceof String ? (String) args[2] : "";
                                    log("Round #" + round + " onRewarded: success=" + ok
                                            + ", toast=" + toast + ", mpExt=" + respMap);
                                    handleAwardResult(ok, toast);
                                }
                                return null;
                            }
                        }
                );

                Map<String, Object> sdkInfoMap = new HashMap<>();
                String methodName = useCommonRewardApi ? "onAwardCommonRewardAdTask" : "onAwardCommonAdTask";
                log("Round #" + round + ": invoking HyLotteryAdAwardManager." + methodName
                        + "(taskId=" + adTaskId + ", reqId=" + reqId + ")");
                XposedHelpers.callMethod(
                        awardMgr,
                        methodName,
                        adTaskId,
                        sdkInfoMap,
                        0,
                        extraJson,
                        reqId,
                        listenerProxy
                );
            } catch (Throwable t) {
                logError("invokeAwardApi failed", t);
                completeHuliangPhase(claimedCount > 0, false, 0, buildHuliangSummary("调用发奖接口失败：" + t.getMessage()));
            }
        }

        private void handleAwardResult(boolean ok, String toast) {
            if (finished) {
                return;
            }
            if (ok) {
                claimedCount++;
                if (toast != null && !toast.isEmpty()) {
                    rewardToasts.add(toast);
                }
                mainHandler().postDelayed(this::runNextHuliangRound, ROUND_INTERVAL_MS);
                return;
            }

            int cooldown = parseCooldownSeconds(toast);
            boolean exhausted = isExhaustedMessage(toast);
            if (claimedCount > 0) {
                completeHuliangPhase(true, exhausted, cooldown, buildHuliangSummary(nonEmptyOr(toast, "本次领取已完成")));
            } else if (exhausted) {
                completeHuliangPhase(true, true, 0, nonEmptyOr(toast, "今日虎粮已全部领完"));
            } else if (cooldown > 0) {
                completeHuliangPhase(false, false, cooldown, nonEmptyOr(toast, "冷却中，将在冷却结束后自动重试"));
            } else {
                completeHuliangPhase(false, false, 0, nonEmptyOr(toast, "领取失败，虎牙未发放奖励"));
            }
        }

        private String buildHuliangSummary(String tail) {
            StringBuilder sb = new StringBuilder();
            if (claimedCount > 0) {
                sb.append("成功领取 ").append(claimedCount).append(" 次");
                if (!rewardToasts.isEmpty()) {
                    sb.append("（").append(rewardToasts.get(rewardToasts.size() - 1)).append("）");
                }
                if (tail != null && !tail.isEmpty()) {
                    sb.append("，").append(tail);
                }
            } else {
                sb.append(tail != null && !tail.isEmpty() ? tail : "未领取到虎粮");
            }
            return sb.toString();
        }

        private void completeHuliangPhase(boolean success, boolean exhausted, int cooldownSeconds, String summary) {
            this.huliangSuccess = success;
            this.huliangExhausted = exhausted;
            this.huliangCooldown = cooldownSeconds;
            this.huliangSummary = summary;
            log("Phase 1 (虎粮) done: success=" + success + ", claimed=" + claimedCount
                    + ", exhausted=" + exhausted + ", cooldown=" + cooldownSeconds + "s, summary=" + summary);

            if (doFansCheckin) {
                startFansCheckinPhase();
            } else if (doClaimWelfare) {
                startWelfareClaimPhase();
            } else {
                startSuperFanDayEnrichThenFreeHuliangPhase();
            }
        }

        // ── Phase 2: Query all fan badges & submit daily check-in ────────────

        private void startFansCheckinPhase() {
            log("Phase 2 (粉丝牌打卡): querying user fan badge list via liveui.queryBadgeInfoList");
            queryAllFanBadges(cl, new BadgeListCallback() {
                @Override
                public void onSuccess(List<BadgeTarget> badges) {
                    if (finished) {
                        return;
                    }
                    badgeTargets.clear();
                    badgeTargets.addAll(badges);
                    checkinTotal = badgeTargets.size();
                    log("Phase 2: fetched " + checkinTotal + " fan badges");
                    if (badgeTargets.isEmpty()) {
                        checkinSummary = "无粉丝牌";
                        startWelfareOrBagPhase();
                        return;
                    }
                    runFansCheckinAt(0);
                }

                @Override
                public void onError(String reason) {
                    if (finished) {
                        return;
                    }
                    log("Phase 2: queryAllFanBadges failed: " + reason);
                    checkinSummary = "查询失败（" + reason + "）";
                    startWelfareOrBagPhase();
                }
            });
        }

        private void runFansCheckinAt(final int index) {
            if (finished) {
                return;
            }
            if (index >= badgeTargets.size()) {
                checkinSummary = formatCheckinSummary();
                log("Phase 2 (粉丝牌打卡) done: " + checkinSummary);
                startWelfareOrBagPhase();
                return;
            }

            final BadgeTarget target = badgeTargets.get(index);
            submitFansSign(cl, target, new FansSignCallback() {
                @Override
                public void onResult(String state, String detail) {
                    if (finished) {
                        return;
                    }
                    target.checkinState = state;
                    target.checkinDetail = detail != null ? detail : "";
                    if ("success".equals(state)) {
                        checkinSuccess++;
                    } else if ("already".equals(state)) {
                        checkinAlready++;
                    } else {
                        checkinFailed++;
                    }
                    log("FansSign [" + (index + 1) + "/" + badgeTargets.size() + "] "
                            + target.presenterNick + " (" + target.badgeName + " Lv." + target.level
                            + ", pid=" + target.pid + ") -> " + state + " (" + detail + ")");

                    if (index + 1 < badgeTargets.size()) {
                        mainHandler().postDelayed(() -> runFansCheckinAt(index + 1), CHECKIN_INTERVAL_MS);
                    } else {
                        runFansCheckinAt(index + 1);
                    }
                }
            });
        }

        private void mergeRefreshedBadges(List<BadgeTarget> refreshed) {
            if (refreshed == null || refreshed.isEmpty()) {
                return;
            }
            Map<Long, BadgeTarget> byPid = new HashMap<>();
            for (BadgeTarget b : refreshed) {
                byPid.put(b.pid, b);
            }
            for (BadgeTarget cur : badgeTargets) {
                BadgeTarget latest = byPid.get(cur.pid);
                if (latest != null) {
                    cur.level = latest.level;
                    cur.score = latest.score;
                    cur.nextScore = latest.nextScore;
                    cur.dayScore = latest.dayScore;
                    cur.quota = latest.quota;
                    cur.quotaUsed = latest.quotaUsed;
                    cur.extinguished = latest.extinguished;
                    cur.superFan = latest.superFan;
                    cur.sfType = latest.sfType;
                    cur.sfVariety = latest.sfVariety;
                    cur.sfLevel = latest.sfLevel;
                    cur.fansIdentity = latest.fansIdentity;
                    if (!latest.faithName.isEmpty()) {
                        cur.faithName = latest.faithName;
                    }
                    if (cur.guardLevel <= 0 && latest.guardLevel > 0) {
                        cur.guardLevel = latest.guardLevel;
                    }
                }
            }
        }

        private String formatCheckinSummary() {
            if (checkinTotal == 0) {
                return "无粉丝牌";
            }
            if (checkinSuccess == 0 && checkinFailed == 0 && checkinAlready == checkinTotal) {
                return "共 " + checkinTotal + " 枚（全部已打卡）";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("共 ").append(checkinTotal).append(" 枚（");
            List<String> parts = new ArrayList<>();
            if (checkinSuccess > 0) {
                parts.add("成功 " + checkinSuccess);
            }
            if (checkinAlready > 0) {
                parts.add("已打卡 " + checkinAlready);
            }
            if (checkinFailed > 0) {
                parts.add("失败 " + checkinFailed);
            }
            for (int i = 0; i < parts.size(); i++) {
                if (i > 0) {
                    sb.append("，");
                }
                sb.append(parts.get(i));
            }
            sb.append("）");
            return sb.toString();
        }

        private void startWelfareOrBagPhase() {
            if (doClaimWelfare) {
                startWelfareClaimPhase();
            } else {
                startSuperFanDayEnrichThenFreeHuliangPhase();
            }
        }

        // ── Phase 2.5: Fan-club daily welfare (10 虎粮) + SuperFan 虎粮 + common welfares ──

        private void startWelfareClaimPhase() {
            if (finished) {
                return;
            }
            if (badgeTargets.isEmpty()) {
                log("Phase 2.5 (每日福利): fetching fan badges first");
                queryAllFanBadges(cl, new BadgeListCallback() {
                    @Override
                    public void onSuccess(List<BadgeTarget> badges) {
                        if (finished) {
                            return;
                        }
                        badgeTargets.clear();
                        badgeTargets.addAll(badges);
                        if (badgeTargets.isEmpty()) {
                            welfareSummary = "无粉丝牌";
                            startSuperFanDayEnrichThenFreeHuliangPhase();
                            return;
                        }
                        beginWelfareScan();
                    }

                    @Override
                    public void onError(String reason) {
                        if (finished) {
                            return;
                        }
                        welfareSummary = "查询粉丝牌失败";
                        startSuperFanDayEnrichThenFreeHuliangPhase();
                    }
                });
                return;
            }
            beginWelfareScan();
        }

        private void beginWelfareScan() {
            // Prioritize SuperFan badges first so SuperFan daily welfares are discovered immediately.
            List<BadgeTarget> scanOrder = new ArrayList<>(badgeTargets);
            Collections.sort(scanOrder, (a, b) -> {
                if (a.superFan != b.superFan) {
                    return a.superFan ? -1 : 1;
                }
                return Integer.compare(b.level, a.level);
            });
            log("Phase 2.5 (每日福利): scanning " + scanOrder.size() + " fan badges for daily & superfan welfares");
            scanWelfareAt(scanOrder, 0);
        }

        private void scanWelfareAt(final List<BadgeTarget> scanOrder, final int index) {
            if (finished) {
                return;
            }
            if (index >= scanOrder.size()) {
                // Also execute Huya daily user sign-in (OldUserBackSignReq) and task-center box check.
                runDailyUserSignIn(() -> {
                    if (finished) {
                        return;
                    }
                    long firstPid = scanOrder.isEmpty() ? 0L : scanOrder.get(0).pid;
                    runLiveBoxAutoClaim(firstPid, () -> {
                        if (finished) {
                            return;
                        }
                        welfareSummary = formatWelfareSummary();
                        log("Phase 2.5 (每日福利) done: claimed=" + welfareClaimedCount
                                + ", already=" + welfareAlreadyCount + ", summary=" + welfareSummary);
                        startSuperFanDayEnrichThenFreeHuliangPhase();
                    });
                });
                return;
            }

            final BadgeTarget badge = scanOrder.get(index);
            queryFansGroupTaskInfo(cl, badge.pid, new FansGroupTaskCallback() {
                @Override
                public void onSuccess(int dayWelfareState, List<CommonWelfareTarget> commWelfares, String extraLog) {
                    if (finished) {
                        return;
                    }
                    log("WelfareTask [" + (index + 1) + "/" + scanOrder.size() + "] "
                            + badge.presenterNick + " (pid=" + badge.pid + ", sf=" + badge.superFan
                            + "): dayWelfareState=" + dayWelfareState
                            + ", commWelfares=" + commWelfares.size()
                            + (extraLog.isEmpty() ? "" : ", " + extraLog));

                    Runnable afterDayWelfare = () -> {
                        List<CommonWelfareTarget> claimable = new ArrayList<>();
                        for (CommonWelfareTarget item : commWelfares) {
                            log("  commWelfare: bizId=" + item.businessId
                                    + ", welfareId=" + item.welfareId
                                    + ", status=" + item.status
                                    + ", title=" + item.title
                                    + ", desc=" + item.desc);
                            if (item.status == COMM_WELFARE_STATUS_CAN_RECEIVE) {
                                String dedupKey = item.pid + ":" + item.businessId + ":" + item.welfareId;
                                if (attemptedCommWelfares.add(dedupKey)) {
                                    claimable.add(item);
                                }
                            } else if (item.status == COMM_WELFARE_STATUS_RECEIVED) {
                                welfareAlreadyCount++;
                            }
                        }
                        if (claimable.isEmpty()) {
                            scheduleNextWelfareScan(scanOrder, index + 1);
                        } else {
                            claimCommonWelfaresAt(claimable, 0, () -> scheduleNextWelfareScan(scanOrder, index + 1));
                        }
                    };

                    if (dayWelfareState == EFGBOX_STATE_FINISH) {
                        operateFansBoxReceive(cl, badge.pid, (ok, itemCount, detail) -> {
                            if (finished) {
                                return;
                            }
                            log("OperateFansBoxReceive (" + badge.presenterNick + ", pid=" + badge.pid
                                    + ") -> ok=" + ok + ", itemCount=" + itemCount + " (" + detail + ")");
                            if (ok) {
                                welfareClaimedCount++;
                                int cnt = itemCount > 0 ? itemCount : 10;
                                welfareClaimedLabels.add("粉丝团每日福利 +" + cnt + "个虎粮");
                            }
                            mainHandler().postDelayed(afterDayWelfare, CHECKIN_INTERVAL_MS);
                        });
                    } else {
                        if (dayWelfareState == EFGBOX_STATE_RECEIVED) {
                            welfareAlreadyCount++;
                        }
                        afterDayWelfare.run();
                    }
                }

                @Override
                public void onError(String reason) {
                    if (finished) {
                        return;
                    }
                    log("WelfareTask [" + (index + 1) + "/" + scanOrder.size() + "] "
                            + badge.presenterNick + " failed: " + reason);
                    scheduleNextWelfareScan(scanOrder, index + 1);
                }
            });
        }

        private void scheduleNextWelfareScan(final List<BadgeTarget> scanOrder, final int nextIndex) {
            if (nextIndex < scanOrder.size()) {
                mainHandler().postDelayed(() -> scanWelfareAt(scanOrder, nextIndex), CHECKIN_INTERVAL_MS);
            } else {
                scanWelfareAt(scanOrder, nextIndex);
            }
        }

        private void claimCommonWelfaresAt(
                final List<CommonWelfareTarget> items,
                final int index,
                final Runnable onDone
        ) {
            if (finished) {
                return;
            }
            if (index >= items.size()) {
                onDone.run();
                return;
            }
            final CommonWelfareTarget item = items.get(index);
            receiveCommonWelfare(cl, item, (ok, detail) -> {
                if (finished) {
                    return;
                }
                log("ReceiveCommWelfare (" + item.anchorNick + ", biz=" + item.businessId
                        + ", id=" + item.welfareId + ", title=" + item.title
                        + ") -> ok=" + ok + " (" + detail + ")");
                if (ok) {
                    welfareClaimedCount++;
                    String label = !item.title.isEmpty() ? item.title : "粉丝团福利";
                    welfareClaimedLabels.add(label);
                }
                if (index + 1 < items.size()) {
                    mainHandler().postDelayed(() -> claimCommonWelfaresAt(items, index + 1, onDone),
                            CHECKIN_INTERVAL_MS);
                } else {
                    onDone.run();
                }
            });
        }

        private void runDailyUserSignIn(final Runnable onDone) {
            submitDailyUserSignIn(cl, (state, detail) -> {
                if (finished) {
                    return;
                }
                log("DailyUserSignIn -> state=" + state + " (" + detail + ")");
                if ("success".equals(state)) {
                    welfareClaimedCount++;
                    welfareClaimedLabels.add(!detail.isEmpty() ? ("每日签到(" + detail + ")") : "每日签到");
                } else if ("already".equals(state)) {
                    welfareAlreadyCount++;
                }
                onDone.run();
            });
        }

        private void runLiveBoxAutoClaim(final long firstPid, final Runnable onDone) {
            queryAndClaimLiveBoxes(cl, firstPid, (claimedCnt, labels) -> {
                if (finished) {
                    return;
                }
                if (claimedCnt > 0) {
                    welfareClaimedCount += claimedCnt;
                    if (labels != null) {
                        welfareClaimedLabels.addAll(labels);
                    }
                }
                onDone.run();
            });
        }

        private String formatWelfareSummary() {
            if (welfareClaimedCount > 0) {
                return "已领 " + welfareClaimedCount + " 项"
                        + (welfareClaimedLabels.isEmpty()
                        ? "" : "（" + joinStrings(welfareClaimedLabels, "，") + "）");
            }
            if (welfareAlreadyCount > 0) {
                return "今日已全部领取";
            }
            return "暂无可领福利";
        }

        // ── Phase 2.8: Enrich SuperFan Day (超粉日) configs before Phase 3 ─────

        private void startSuperFanDayEnrichThenFreeHuliangPhase() {
            if (finished) {
                return;
            }
            if (badgeTargets.isEmpty()) {
                if (doSendHuliang) {
                    queryAllFanBadges(cl, new BadgeListCallback() {
                        @Override
                        public void onSuccess(List<BadgeTarget> badges) {
                            if (finished) {
                                return;
                            }
                            badgeTargets.clear();
                            badgeTargets.addAll(badges);
                            if (badgeTargets.isEmpty()) {
                                startFreeHuliangPhase();
                            } else {
                                beginSuperFanDayEnrich();
                            }
                        }

                        @Override
                        public void onError(String reason) {
                            if (finished) {
                                return;
                            }
                            log("Phase 2.8: queryAllFanBadges failed (continuing): " + reason);
                            startFreeHuliangPhase();
                        }
                    });
                } else {
                    startFreeHuliangPhase();
                }
                return;
            }
            beginSuperFanDayEnrich();
        }

        private void beginSuperFanDayEnrich() {
            List<BadgeTarget> order = new ArrayList<>(badgeTargets);
            Collections.sort(order, (a, b) -> {
                boolean aSel = targetWeights.containsKey(a.pid);
                boolean bSel = targetWeights.containsKey(b.pid);
                if (aSel != bSel) {
                    return aSel ? -1 : 1;
                }
                if (a.superFan != b.superFan) {
                    return a.superFan ? -1 : 1;
                }
                return Integer.compare(b.level, a.level);
            });
            int limit = Math.min(order.size(), 15);
            final List<BadgeTarget> capped = order.subList(0, limit);
            log("Phase 2.8 (超粉日): querying SuperFansConfig for " + capped.size()
                    + "/" + badgeTargets.size() + " fan badges");
            enrichSuperFanDayAt(capped, 0);
        }

        private void enrichSuperFanDayAt(final List<BadgeTarget> targets, final int index) {
            if (finished) {
                return;
            }
            if (index >= targets.size()) {
                sfDayActiveSummary = buildSfDayActiveSummary(badgeTargets);
                log("Phase 2.8 (超粉日) done: activeSummary="
                        + (sfDayActiveSummary.isEmpty() ? "(none)" : sfDayActiveSummary));
                startFreeHuliangPhase();
                return;
            }
            final BadgeTarget badge = targets.get(index);
            querySuperFansConfigForBadge(cl, badge, () -> {
                if (finished) {
                    return;
                }
                int nextIdx = index + 1;
                if (nextIdx < targets.size()) {
                    mainHandler().postDelayed(() -> enrichSuperFanDayAt(targets, nextIdx), 120L);
                } else {
                    enrichSuperFanDayAt(targets, nextIdx);
                }
            });
        }

        // ── Phase 3: Query backpack free 虎粮 & optionally auto-send ─────────

        private long firstSuperFanPid() {
            List<BadgeTarget> sfBadges = findSuperFanBadges();
            if (!sfBadges.isEmpty()) {
                return sfBadges.get(0).pid;
            }
            return 0L;
        }

        private void startFreeHuliangPhase() {
            if (finished) {
                return;
            }
            if (badgeTargets.isEmpty()) {
                // Load badges first so we know SuperFan anchor PIDs — Huya hides 超粉虎粮 (itemType=20699)
                // when wupui.getPackageGift is called with lPid=0.
                queryAllFanBadges(cl, new BadgeListCallback() {
                    @Override
                    public void onSuccess(List<BadgeTarget> badges) {
                        if (finished) {
                            return;
                        }
                        badgeTargets.clear();
                        badgeTargets.addAll(badges);
                        runBagQueryAndSend();
                    }

                    @Override
                    public void onError(String reason) {
                        if (finished) {
                            return;
                        }
                        runBagQueryAndSend();
                    }
                });
                return;
            }
            runBagQueryAndSend();
        }

        private void runBagQueryAndSend() {
            if (finished) {
                return;
            }
            long sfHintPid = firstSuperFanPid();
            log("Phase 3 (免费虎粮): querying backpack via wupui.getPackageGift (lPid=" + sfHintPid
                    + ", doSend=" + doSendHuliang + ", doSendSuper=" + doSendSuperHuliang
                    + ", smartSfDay=" + smartSuperFanDaySend + ")");
            queryBagHuliang(cl, sfHintPid, new BagHuliangCallback() {
                @Override
                public void onSuccess(BagHuliangSummary bag) {
                    if (finished) {
                        return;
                    }
                    freeHuliangCount = bag.normalFreeCount;
                    freeExpiring24h = bag.normalExpiring24h;
                    freeEarliestExpire = bag.earliestNormalExpireSec;
                    superHuliangCount = bag.superFreeCount;
                    superHuliangName = bag.superDisplayName;
                    log("Phase 3: bag normalFree=" + bag.normalFreeCount
                            + " (type=" + bag.normalItemType + ", name=" + bag.normalName
                            + ", expiring24h=" + bag.normalExpiring24h
                            + ", earliestExpire=" + bag.earliestNormalExpireSec
                            + ", batches=" + bag.normalBatches.size()
                            + "), superFree=" + bag.superFreeCount
                            + " (types=" + bag.superItems.size() + ", name=" + bag.superDisplayName + ")");
                    if (!doSendHuliang && !doSendSuperHuliang) {
                        refreshBadgesIfNeededAndFinalize();
                        return;
                    }
                    int availableToSend = (doSendHuliang ? bag.normalFreeCount : 0)
                            + (doSendSuperHuliang ? bag.superFreeCount : 0);
                    if (availableToSend <= 0) {
                        sendSummary = "背包暂无免费虎粮";
                        refreshBadgesIfNeededAndFinalize();
                        return;
                    }
                    if (!targetWeights.isEmpty() || !badgeTargets.isEmpty()) {
                        SendPlanResult planResult = buildAllSendPlans(bag);
                        smartHoardedCount = planResult.hoardedNormalCount;
                        if (planResult.plans.isEmpty()) {
                            if (planResult.smartHoarding && planResult.hoardedNormalCount > 0) {
                                sendSummary = "已囤 " + planResult.hoardedNormalCount + " 个等 "
                                        + formatMonthDay(planResult.nearestSfDayEpoch) + " 超粉日（4倍）";
                                log("Phase 3 (智能囤粮): " + sendSummary);
                            } else {
                                sendSummary = "无可用送礼目标";
                            }
                            refreshBadgesIfNeededAndFinalize();
                            return;
                        }
                        executeSendPlans(planResult, 0, new LinkedHashMap<>(), new ArrayList<>());
                    } else {
                        sendSummary = "无粉丝牌且未指定目标主播";
                        finalizeAll();
                    }
                }

                @Override
                public void onError(String reason) {
                    if (finished) {
                        return;
                    }
                    log("Phase 3: queryBagHuliang failed: " + reason);
                    if (doSendHuliang || doSendSuperHuliang) {
                        sendSummary = "查询背包失败（" + reason + "）";
                    }
                    refreshBadgesIfNeededAndFinalize();
                }
            });
        }

        private BadgeTarget findBadgeByPid(long pid) {
            for (BadgeTarget b : badgeTargets) {
                if (b != null && b.pid == pid) {
                    return b;
                }
            }
            return null;
        }

        private List<BadgeTarget> findSuperFanBadges() {
            List<BadgeTarget> list = new ArrayList<>();
            for (BadgeTarget b : badgeTargets) {
                if (b != null && b.superFan) {
                    list.add(b);
                }
            }
            return list;
        }

        private List<BadgeTarget> resolveSelectedTargets(boolean preferSuperFanWhenAll) {
            if (!targetWeights.isEmpty()) {
                List<BadgeTarget> result = new ArrayList<>();
                for (Long pidObj : targetWeights.keySet()) {
                    if (pidObj == null || pidObj <= 0L) {
                        continue;
                    }
                    long pid = pidObj;
                    BadgeTarget existing = findBadgeByPid(pid);
                    if (existing != null) {
                        result.add(existing);
                    } else {
                        result.add(new BadgeTarget(pid, "UID:" + pid));
                    }
                }
                return result;
            }
            if (preferSuperFanWhenAll) {
                List<BadgeTarget> sfBadges = findSuperFanBadges();
                if (!sfBadges.isEmpty()) {
                    return sfBadges;
                }
            }
            return new ArrayList<>(badgeTargets);
        }

        private static int intimacyPerGift(BadgeTarget b) {
            if (b == null) {
                return 1;
            }
            if (b.sfDayActive) {
                return Math.max(1, b.sfDayScoreTimes);
            }
            return b.superFan ? 2 : 1;
        }

        private static int remainingUpgradeExp(BadgeTarget b) {
            if (b == null) {
                return Integer.MAX_VALUE;
            }
            if (b.nextScore > b.score) {
                return b.nextScore - b.score;
            }
            return b.nextScore > 0 ? b.nextScore : Integer.MAX_VALUE;
        }

        private SendPlanResult buildAllSendPlans(BagHuliangSummary bag) {
            long nowSec = bag.serverTimeSec > 0L ? bag.serverTimeSec : (System.currentTimeMillis() / 1000L);
            List<BadgeTarget> normalTargets = resolveSelectedTargets(false);
            List<BadgeTarget> superTargets = resolveSelectedTargets(true);
            // Huya restricts 超粉虎粮 (itemType=20699) to anchors where the user is a SuperFan.
            // If selected targets contain no SuperFan anchor, fall back to the account's SuperFan badges.
            boolean hasSfInSuperTargets = false;
            for (BadgeTarget b : superTargets) {
                if (b != null && b.superFan) {
                    hasSfInSuperTargets = true;
                    break;
                }
            }
            if (!hasSfInSuperTargets) {
                List<BadgeTarget> sfBadges = findSuperFanBadges();
                if (!sfBadges.isEmpty()) {
                    superTargets = sfBadges;
                }
            }

            if (smartSuperFanDaySend && !normalTargets.isEmpty()) {
                List<BadgeTarget> activeSfBadges = new ArrayList<>();
                long nearestSfDayEpoch = 0L;
                int maxScoreTimes = 0;

                for (BadgeTarget b : normalTargets) {
                    if (b == null || !b.superFan) {
                        continue;
                    }
                    if (b.sfDayActive) {
                        activeSfBadges.add(b);
                        int st = b.sfDayScoreTimes > 0 ? b.sfDayScoreTimes : 4;
                        if (st > maxScoreTimes) {
                            maxScoreTimes = st;
                        }
                    } else if (b.nextSfDayEpoch > nowSec) {
                        if (nearestSfDayEpoch == 0L || b.nextSfDayEpoch < nearestSfDayEpoch) {
                            nearestSfDayEpoch = b.nextSfDayEpoch;
                        }
                    }
                }

                // Case 1: Today IS a SuperFan Day (4x intimacy) -> send ALL available free Huliang!
                if (!activeSfBadges.isEmpty()) {
                    List<SendPlan> plans = new ArrayList<>();
                    int remainingCap = sendMaxCount > 0 ? sendMaxCount : Integer.MAX_VALUE;
                    if (doSendSuperHuliang && bag.superItems != null && !bag.superItems.isEmpty() && remainingCap > 0) {
                        for (FreeHuliangItem item : bag.superItems) {
                            if (item.freeCount <= 0 || remainingCap <= 0) {
                                continue;
                            }
                            int countToSend = Math.min(item.freeCount, remainingCap);
                            plans.addAll(buildDistributedPlansForItem(
                                    item.itemType, item.itemName, true, activeSfBadges, countToSend));
                            remainingCap -= countToSend;
                        }
                    }
                    if (doSendHuliang && bag.normalFreeCount > 0 && remainingCap > 0) {
                        int countToSend = Math.min(bag.normalFreeCount, remainingCap);
                        plans.addAll(buildDistributedPlansForItem(
                                bag.normalItemType, bag.normalName, false, activeSfBadges, countToSend));
                    }
                    int times = maxScoreTimes > 0 ? maxScoreTimes : 4;
                    log("SmartSuperFanDay Case 1 (ACTIVE " + times + "x): targets=" + activeSfBadges.size()
                            + ", distMode=" + sendDistributeMode + ", plans=" + plans.size());
                    return new SendPlanResult(plans, true, times, false, 0, 0L);
                }

                // Case 2: Not SuperFan Day today, AND upcoming SuperFan Day known -> hoard non-expiring normal Huliang!
                if (nearestSfDayEpoch > nowSec) {
                    List<SendPlan> plans = new ArrayList<>();
                    long sfDayWindowSec = nearestSfDayEpoch + 12L * 3600L;

                    // 2a. SuperFan Huliang (~1 day validity): expiring items bypass sendMaxCount so zero SuperFan Huliang expires.
                    if (doSendSuperHuliang && bag.superItems != null && !bag.superItems.isEmpty() && !superTargets.isEmpty()) {
                        for (FreeHuliangItem item : bag.superItems) {
                            if (item.freeCount <= 0) {
                                continue;
                            }
                            boolean shouldSendSuperToday = (item.expireSec <= 0L || item.expireSec <= sfDayWindowSec);
                            if (!shouldSendSuperToday) {
                                continue;
                            }
                            plans.addAll(buildDistributedPlansForItem(
                                    item.itemType, item.itemName, true, superTargets, item.freeCount));
                        }
                    }

                    // 2b. Normal free Huliang: rescue ALL batches expiring within safety horizon (36h) AND before SF day (+12h),
                    //     bypassing sendMaxCount so expiring Huliang is never truncated and wasted.
                    int expiringToSend = 0;
                    int hoarded = 0;
                    if (doSendHuliang) {
                        long safetyHorizonSec = nowSec + 36L * 3600L;
                        long effectiveDeadline = Math.min(safetyHorizonSec, sfDayWindowSec);
                        expiringToSend = bag.countNormalExpiringBy(effectiveDeadline);
                        if (expiringToSend > 0) {
                            plans.addAll(buildDistributedPlansForItem(
                                    bag.normalItemType, bag.normalName, false, normalTargets, expiringToSend));
                        }
                        hoarded = Math.max(0, bag.normalFreeCount - expiringToSend);
                    }
                    log("SmartSuperFanDay Case 2 (HOARD): nearestSfDay=" + formatMonthDay(nearestSfDayEpoch)
                            + " (epoch=" + nearestSfDayEpoch + "), normalFree=" + bag.normalFreeCount
                            + ", expiringToSend=" + expiringToSend + ", hoarded=" + hoarded
                            + ", distMode=" + sendDistributeMode + ", plans=" + plans.size());
                    return new SendPlanResult(plans, false, 0, true, hoarded, nearestSfDayEpoch);
                }
            }

            // Case 3: No eligible SuperFan anchor with SuperFan Day config (or smart mode disabled).
            // Regular sends honor sendMaxCount, while expiring batches (<=36h) bypass the cap to prevent waste.
            List<SendPlan> plans = new ArrayList<>();
            int remainingCap = sendMaxCount > 0 ? sendMaxCount : Integer.MAX_VALUE;
            long urgentHorizonSec = nowSec + 36L * 3600L;

            // 1. Prioritize expiring 1-day SuperFan 虎粮 first when enabled.
            if (doSendSuperHuliang && bag.superItems != null && !bag.superItems.isEmpty() && !superTargets.isEmpty()) {
                for (FreeHuliangItem item : bag.superItems) {
                    if (item.freeCount <= 0) {
                        continue;
                    }
                    boolean urgentSuper = (item.expireSec <= 0L || item.expireSec <= urgentHorizonSec);
                    int countToSend = urgentSuper
                            ? item.freeCount
                            : (remainingCap > 0 ? Math.min(item.freeCount, remainingCap) : 0);
                    if (countToSend <= 0) {
                        continue;
                    }
                    plans.addAll(buildDistributedPlansForItem(
                            item.itemType, item.itemName, true, superTargets, countToSend));
                    remainingCap = Math.max(0, remainingCap - countToSend);
                }
            }

            // 2. Normal free 虎粮: send at least all batches expiring within 36h even if above sendMaxCount.
            if (doSendHuliang && bag.normalFreeCount > 0 && !normalTargets.isEmpty()) {
                int urgentNormal = bag.countNormalExpiringBy(urgentHorizonSec);
                int regularCap = remainingCap > 0 ? Math.min(bag.normalFreeCount, remainingCap) : 0;
                int countToSend = Math.min(bag.normalFreeCount, Math.max(regularCap, urgentNormal));
                if (countToSend > 0) {
                    plans.addAll(buildDistributedPlansForItem(
                            bag.normalItemType, bag.normalName, false, normalTargets, countToSend));
                }
            }
            return new SendPlanResult(plans, false, 0, false, 0, 0L);
        }

        private List<SendPlan> buildDistributedPlansForItem(
                int giftId,
                String giftName,
                boolean isSuper,
                List<BadgeTarget> targets,
                int totalToSend
        ) {
            List<SendPlan> plans = new ArrayList<>();
            if (targets == null || targets.isEmpty() || totalToSend <= 0) {
                return plans;
            }
            int n = targets.size();
            if (n == 1) {
                BadgeTarget only = targets.get(0);
                plans.add(new SendPlan(giftId, giftName, isSuper, only.pid, only.presenterNick, totalToSend));
                return plans;
            }

            int[] alloc = new int[n];
            switch (sendDistributeMode) {
                case ModulePrefs.DISTRIBUTE_FILL_QUOTA: {
                    int rem = totalToSend;
                    for (int i = 0; i < n && rem > 0; i++) {
                        BadgeTarget b = targets.get(i);
                        int per = intimacyPerGift(b);
                        int need;
                        if (b.quota > 0) {
                            int today = b.dayScore + Math.max(0, b.quotaUsed);
                            int roomIntimacy = Math.max(0, b.quota - today);
                            need = roomIntimacy <= 0 ? 0 : (int) (((long) roomIntimacy + per - 1L) / per);
                        } else {
                            need = rem;
                        }
                        int give = Math.min(rem, Math.max(0, need));
                        alloc[i] += give;
                        rem -= give;
                    }
                    if (rem > 0) {
                        int base = rem / n;
                        int extra = rem % n;
                        for (int i = 0; i < n; i++) {
                            alloc[i] += base + (i < extra ? 1 : 0);
                        }
                    }
                    break;
                }
                case ModulePrefs.DISTRIBUTE_NEAREST_UPGRADE: {
                    List<Integer> sorted = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        sorted.add(i);
                    }
                    Collections.sort(sorted, (i1, i2) -> Integer.compare(
                            remainingUpgradeExp(targets.get(i1)),
                            remainingUpgradeExp(targets.get(i2))
                    ));
                    int rem = totalToSend;
                    for (int k = 0; k < n && rem > 0; k++) {
                        int idx = sorted.get(k);
                        BadgeTarget b = targets.get(idx);
                        int per = intimacyPerGift(b);
                        int remExp = remainingUpgradeExp(b);
                        int needUpgrade = (remExp == Integer.MAX_VALUE)
                                ? rem
                                : (int) (((long) remExp + per - 1L) / per);
                        int need = needUpgrade;
                        if (b.quota > 0) {
                            int today = b.dayScore + Math.max(0, b.quotaUsed);
                            int roomIntimacy = Math.max(0, b.quota - today);
                            int quotaCap = roomIntimacy <= 0 ? 0 : (int) (((long) roomIntimacy + per - 1L) / per);
                            need = Math.min(need, quotaCap);
                        }
                        int give = Math.min(rem, Math.max(0, need));
                        alloc[idx] += give;
                        rem -= give;
                    }
                    if (rem > 0) {
                        alloc[sorted.get(0)] += rem;
                    }
                    break;
                }
                case ModulePrefs.DISTRIBUTE_CUSTOM_RATIO: {
                    int[] w = new int[n];
                    long sumW = 0L;
                    for (int i = 0; i < n; i++) {
                        Integer wObj = targetWeights.get(targets.get(i).pid);
                        w[i] = (wObj != null && wObj > 0) ? wObj : 1;
                        sumW += w[i];
                    }
                    long[] frac = new long[n];
                    int assigned = 0;
                    for (int i = 0; i < n; i++) {
                        long num = (long) totalToSend * (long) w[i];
                        alloc[i] = (int) (num / sumW);
                        frac[i] = num % sumW;
                        assigned += alloc[i];
                    }
                    int leftover = totalToSend - assigned;
                    if (leftover > 0) {
                        List<Integer> order = new ArrayList<>(n);
                        for (int i = 0; i < n; i++) {
                            order.add(i);
                        }
                        Collections.sort(order, (i1, i2) -> {
                            int cmp = Long.compare(frac[i2], frac[i1]);
                            if (cmp != 0) {
                                return cmp;
                            }
                            return Integer.compare(w[i2], w[i1]);
                        });
                        for (int k = 0; k < leftover && k < n; k++) {
                            alloc[order.get(k)]++;
                        }
                    }
                    break;
                }
                case ModulePrefs.DISTRIBUTE_EVEN:
                default: {
                    int base = totalToSend / n;
                    int extra = totalToSend % n;
                    for (int i = 0; i < n; i++) {
                        alloc[i] = base + (i < extra ? 1 : 0);
                    }
                    break;
                }
            }

            for (int i = 0; i < n; i++) {
                if (alloc[i] > 0) {
                    BadgeTarget b = targets.get(i);
                    plans.add(new SendPlan(giftId, giftName, isSuper, b.pid, b.presenterNick, alloc[i]));
                }
            }
            return plans;
        }

        private static void recordAnchorSent(LinkedHashMap<String, Integer> sentByAnchor, String nick, int count) {
            if (count <= 0) {
                return;
            }
            String key = nonEmptyOr(nick, "主播");
            Integer prev = sentByAnchor.get(key);
            sentByAnchor.put(key, (prev != null ? prev : 0) + count);
        }

        private static String formatConciseSendDetails(
                LinkedHashMap<String, Integer> sentByAnchor,
                List<String> failures
        ) {
            StringBuilder sb = new StringBuilder();
            int size = sentByAnchor.size();
            if (size > 0) {
                int maxDisplay = size > 4 ? 3 : size;
                int idx = 0;
                for (Map.Entry<String, Integer> e : sentByAnchor.entrySet()) {
                    if (idx >= maxDisplay) {
                        break;
                    }
                    if (idx > 0) {
                        sb.append("、");
                    }
                    sb.append(e.getKey()).append(" +").append(e.getValue());
                    idx++;
                }
                if (size > maxDisplay) {
                    sb.append(" 等 ").append(size).append(" 位主播");
                }
            }
            if (!failures.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append("；").append(failures.size()).append(" 项失败");
                } else {
                    sb.append(joinStrings(failures, "；"));
                }
            }
            return sb.toString();
        }

        private void executeSendPlans(
                final SendPlanResult planResult,
                final int index,
                final LinkedHashMap<String, Integer> sentByAnchor,
                final List<String> failures
        ) {
            if (finished) {
                return;
            }
            final List<SendPlan> plans = planResult.plans;
            if (index >= plans.size()) {
                int totalSent = sentHuliangCount + sentSuperHuliangCount;
                String detailStr = formatConciseSendDetails(sentByAnchor, failures);
                if (planResult.smartSfDayActive) {
                    int times = planResult.smartSfDayScoreTimes > 0 ? planResult.smartSfDayScoreTimes : 4;
                    if (totalSent > 0) {
                        sendSummary = "超粉日 " + times + " 倍！已送 " + totalSent + " 个"
                                + (detailStr.isEmpty() ? "" : "（" + detailStr + "）");
                    } else {
                        sendSummary = detailStr.isEmpty() ? "超粉日未送出免费虎粮" : detailStr;
                    }
                } else if (planResult.smartHoarding && planResult.hoardedNormalCount > 0) {
                    String dateLabel = formatMonthDay(planResult.nearestSfDayEpoch);
                    if (totalSent > 0) {
                        sendSummary = "已送临期/超粉 " + totalSent + " 个"
                                + (detailStr.isEmpty() ? "" : "（" + detailStr + "）")
                                + "，余 " + planResult.hoardedNormalCount
                                + " 个囤至 " + dateLabel + " 超粉日";
                    } else {
                        sendSummary = "已囤 " + planResult.hoardedNormalCount + " 个等 "
                                + dateLabel + " 超粉日（4倍）";
                    }
                } else if (totalSent > 0) {
                    sendSummary = "已送 " + totalSent + " 个"
                            + (detailStr.isEmpty() ? "" : "（" + detailStr + "）");
                } else {
                    sendSummary = detailStr.isEmpty() ? "未送出免费虎粮" : detailStr;
                }
                log("Phase 3 (免费虎粮) done: sentNormal=" + sentHuliangCount
                        + ", sentSuper=" + sentSuperHuliangCount + ", summary=" + sendSummary);
                if (totalSent > 0) {
                    // Refresh remaining free & super 虎粮 count + updated badge exp/levels.
                    queryBagHuliang(cl, firstSuperFanPid(), new BagHuliangCallback() {
                        @Override
                        public void onSuccess(BagHuliangSummary bag) {
                            freeHuliangCount = bag.normalFreeCount;
                            freeExpiring24h = bag.normalExpiring24h;
                            freeEarliestExpire = bag.earliestNormalExpireSec;
                            superHuliangCount = bag.superFreeCount;
                            if (!bag.superDisplayName.isEmpty()) {
                                superHuliangName = bag.superDisplayName;
                            }
                            refreshBadgesIfNeededAndFinalize();
                        }

                        @Override
                        public void onError(String reason) {
                            freeHuliangCount = Math.max(0, freeHuliangCount - sentHuliangCount);
                            if (freeExpiring24h >= 0) {
                                freeExpiring24h = Math.max(0, freeExpiring24h - sentHuliangCount);
                            }
                            superHuliangCount = Math.max(0, superHuliangCount - sentSuperHuliangCount);
                            refreshBadgesIfNeededAndFinalize();
                        }
                    });
                } else {
                    refreshBadgesIfNeededAndFinalize();
                }
                return;
            }

            final SendPlan plan = plans.get(index);
            sendFreeHuliangOnce(cl, plan.giftId, plan.pid, plan.count, new SendGiftCallback() {
                @Override
                public void onResult(boolean ok, String detail) {
                    if (finished) {
                        return;
                    }
                    log("SendFreeHuliang [" + (index + 1) + "/" + plans.size() + "] "
                            + plan.giftName + "(id=" + plan.giftId + ", super=" + plan.isSuper + ") -> "
                            + plan.nick + " (pid=" + plan.pid + ", count=" + plan.count
                            + ") -> ok=" + ok + " (" + detail + ")");
                    if (ok) {
                        if (plan.isSuper) {
                            sentSuperHuliangCount += plan.count;
                        } else {
                            sentHuliangCount += plan.count;
                        }
                        recordAnchorSent(sentByAnchor, plan.nick, plan.count);
                        advanceSendPlans(planResult, index + 1, sentByAnchor, failures);
                        return;
                    }

                    // If sending a SuperFan 虎粮 to a non-superfan anchor failed, retry on the first SuperFan badge.
                    if (plan.isSuper) {
                        List<BadgeTarget> superBadges = findSuperFanBadges();
                        if (!superBadges.isEmpty() && superBadges.get(0).pid != plan.pid) {
                            BadgeTarget sfAnchor = superBadges.get(0);
                            log("Retrying SuperFan gift " + plan.giftName + " on SuperFan anchor "
                                    + sfAnchor.presenterNick + " (pid=" + sfAnchor.pid + ")");
                            sendFreeHuliangOnce(cl, plan.giftId, sfAnchor.pid, plan.count, (ok2, detail2) -> {
                                if (finished) {
                                    return;
                                }
                                log("RetrySendSuperHuliang -> " + sfAnchor.presenterNick
                                        + " ok=" + ok2 + " (" + detail2 + ")");
                                if (ok2) {
                                    sentSuperHuliangCount += plan.count;
                                    recordAnchorSent(sentByAnchor, sfAnchor.presenterNick, plan.count);
                                } else {
                                    failures.add(plan.nick + " " + plan.giftName + "失败:" + detail2);
                                }
                                advanceSendPlans(planResult, index + 1, sentByAnchor, failures);
                            });
                            return;
                        }
                    }

                    failures.add(plan.nick + (plan.isSuper ? " " + plan.giftName : "") + " 失败:" + detail);
                    advanceSendPlans(planResult, index + 1, sentByAnchor, failures);
                }
            });
        }

        private void advanceSendPlans(
                final SendPlanResult planResult,
                final int nextIndex,
                final LinkedHashMap<String, Integer> sentByAnchor,
                final List<String> failures
        ) {
            if (nextIndex < planResult.plans.size()) {
                mainHandler().postDelayed(
                        () -> executeSendPlans(planResult, nextIndex, sentByAnchor, failures),
                        CHECKIN_INTERVAL_MS
                );
            } else {
                executeSendPlans(planResult, nextIndex, sentByAnchor, failures);
            }
        }

        private void refreshBadgesIfNeededAndFinalize() {
            if (finished) {
                return;
            }
            if ((checkinSuccess > 0 || sentHuliangCount > 0 || sentSuperHuliangCount > 0)
                    && !badgeTargets.isEmpty()) {
                queryAllFanBadges(cl, new BadgeListCallback() {
                    @Override
                    public void onSuccess(List<BadgeTarget> refreshed) {
                        mergeRefreshedBadges(refreshed);
                        enrichGuardsAndRenderThenFinalize();
                    }

                    @Override
                    public void onError(String reason) {
                        enrichGuardsAndRenderThenFinalize();
                    }
                });
            } else {
                enrichGuardsAndRenderThenFinalize();
            }
        }

        private void enrichGuardsAndRenderThenFinalize() {
            if (finished) {
                return;
            }
            if (badgeTargets.isEmpty()) {
                finalizeAll();
                return;
            }
            queryGuardsForBadges(cl, badgeTargets, 0, () -> {
                if (finished) {
                    return;
                }
                queryCustomBadgeBaseInfo(cl, customBadgeMap -> {
                    if (finished) {
                        return;
                    }
                    // Run native & custom badge rendering (which may fetch/cache CDN PNGs) off the main thread.
                    new Thread(() -> {
                        try {
                            renderNativeBadges(cl, sAppContext, badgeTargets, customBadgeMap);
                        } catch (Throwable t) {
                            logError("renderNativeBadges worker failed", t);
                        }
                        mainHandler().post(ClaimSession.this::finalizeAll);
                    }, "HuyaPure-BadgeRender").start();
                });
            });
        }

        private void finalizeAll() {
            if (finished) {
                return;
            }
            finished = true;
            sRunning.set(false);

            boolean overallSuccess = huliangSuccess
                    || (doFansCheckin && checkinTotal > 0 && (checkinSuccess > 0 || checkinAlready > 0))
                    || (doClaimWelfare && (welfareClaimedCount > 0 || welfareAlreadyCount > 0))
                    || (doSendHuliang && (sentHuliangCount > 0 || sentSuperHuliangCount > 0 || smartHoardedCount > 0));
            StringBuilder msgSb = new StringBuilder();
            msgSb.append("虎粮：").append(huliangSummary);
            if (doFansCheckin && !checkinSummary.isEmpty()) {
                msgSb.append("；粉丝牌打卡：").append(checkinSummary);
            }
            if (doClaimWelfare && !welfareSummary.isEmpty()) {
                msgSb.append("；每日福利：").append(welfareSummary);
            }
            if (doSendHuliang && !sendSummary.isEmpty()) {
                msgSb.append("；送虎粮：").append(sendSummary);
            }
            String fullMessage = msgSb.toString();

            if (sfDayActiveSummary.isEmpty() && !badgeTargets.isEmpty()) {
                sfDayActiveSummary = buildSfDayActiveSummary(badgeTargets);
            }

            log("ClaimSession finished: success=" + overallSuccess
                    + ", claimed=" + claimedCount
                    + ", welfareClaimed=" + welfareClaimedCount
                    + ", exhausted=" + huliangExhausted
                    + ", cooldown=" + huliangCooldown + "s"
                    + ", checkin=" + checkinSuccess + "/" + checkinAlready + "/" + checkinFailed
                    + " (total=" + checkinTotal + ")"
                    + ", freeBag=" + freeHuliangCount
                    + " (exp24h=" + freeExpiring24h + ", earliestExp=" + freeEarliestExpire + ")"
                    + ", sfDayActive=" + sfDayActiveSummary
                    + ", superBag=" + superHuliangCount + "(" + superHuliangName + ")"
                    + ", sentNormal=" + sentHuliangCount
                    + ", sentSuper=" + sentSuperHuliangCount
                    + ", msg=" + fullMessage);

            finishCallback.onFinished(buildResultBundle(
                    requestId,
                    manual,
                    overallSuccess,
                    claimedCount,
                    welfareClaimedCount,
                    huliangExhausted,
                    huliangCooldown,
                    checkinTotal,
                    checkinSuccess,
                    checkinAlready,
                    checkinFailed,
                    freeHuliangCount,
                    freeExpiring24h,
                    freeEarliestExpire,
                    sfDayActiveSummary,
                    superHuliangCount,
                    superHuliangName,
                    sentHuliangCount,
                    sentSuperHuliangCount,
                    serializeBadges(badgeTargets),
                    fullMessage
            ));
        }
    }

    private interface BadgeListCallback {
        void onSuccess(List<BadgeTarget> badges);
        void onError(String reason);
    }

    private interface FansSignCallback {
        void onResult(String state, String detail);
    }

    private interface FansGroupTaskCallback {
        void onSuccess(int dayWelfareState, List<CommonWelfareTarget> commWelfares, String extraLog);
        void onError(String reason);
    }

    private interface FansBoxReceiveCallback {
        void onResult(boolean ok, int itemCount, String detail);
    }

    private interface CommonWelfareReceiveCallback {
        void onResult(boolean ok, String detail);
    }

    private interface BagHuliangCallback {
        void onSuccess(BagHuliangSummary summary);
        void onError(String reason);
    }

    private interface SendGiftCallback {
        void onResult(boolean ok, String detail);
    }

    // ── Fan-club & SuperFan daily welfare raw WUP RPCs ───────────────────────

    private static void queryFansGroupTaskInfo(ClassLoader cl, long pid, final FansGroupTaskCallback cb) {
        try {
            Object userId = getHuyaUserId(cl);
            if (userId == null) {
                cb.onError("未获取登录态");
                return;
            }
            byte[] userIdBytes = RawWupClient.serializeHuyaJceStruct(cl, userId);
            RawWupClient.JceWriter reqW = new RawWupClient.JceWriter();
            reqW.writeStructRaw(0, userIdBytes);                 // tag 0: tUserId
            reqW.writeLong(1, pid);                              // tag 1: lPid
            reqW.writeInt(2, REQ_MASK_USER_WITH_POPULARITY);     // tag 2: iReqMask = 3

            RawWupClient.sendRawWup(
                    cl, mainHandler(),
                    "wupui", "queryFansGroupTaskInfo", "tReq",
                    reqW.toByteArray(), RPC_TIMEOUT_MS,
                    new RawWupClient.RawWupCallback() {
                        @Override
                        public void onSuccess(byte[] rspStructBody, String matchedKey) {
                            try {
                                RawWupClient.JceStructView rsp = RawWupClient.JceReader.parseFields(rspStructBody);
                                int retCode = rsp.getInt(0, 0);
                                int dayState = 0;
                                RawWupClient.JceStructView dayWelfare = rsp.getStruct(7); // tag 7: tDayWelfare
                                if (dayWelfare != null) {
                                    dayState = dayWelfare.getInt(0, 0); // tag 0: iState
                                }
                                List<CommonWelfareTarget> commList = new ArrayList<>();
                                List<Object> rawComm = rsp.getList(9); // tag 9: vFGCommWelfare
                                if (rawComm != null) {
                                    for (Object elem : rawComm) {
                                        if (!(elem instanceof byte[])) {
                                            continue;
                                        }
                                        RawWupClient.JceStructView item =
                                                RawWupClient.JceReader.parseFields((byte[]) elem);
                                        long bizId = item.getLong(0, 0L);
                                        long welfareId = item.getLong(1, 0L);
                                        String title = item.getString(2, "");
                                        String desc = item.getString(3, "");
                                        int status = item.getInt(5, 0);
                                        commList.add(new CommonWelfareTarget(
                                                pid, "", bizId, welfareId, title, desc, status));
                                    }
                                }
                                List<Object> vGroup = rsp.getList(3);
                                List<Object> vComTask = rsp.getList(5);
                                List<Object> vPeriodTask = rsp.getList(8);
                                List<Object> vPri = rsp.getList(11);
                                List<Object> vSpecial = rsp.getList(13);
                                String extra = "ret=" + retCode
                                        + ", key=" + matchedKey
                                        + ", vGroup=" + (vGroup != null ? vGroup.size() : 0)
                                        + ", vComTask=" + (vComTask != null ? vComTask.size() : 0)
                                        + ", vPeriod=" + (vPeriodTask != null ? vPeriodTask.size() : 0)
                                        + ", vPri=" + (vPri != null ? vPri.size() : 0)
                                        + ", vSpecial=" + (vSpecial != null ? vSpecial.size() : 0);
                                cb.onSuccess(dayState, commList, extra);
                            } catch (Throwable t) {
                                cb.onError("解析粉丝团任务失败：" + t.getMessage());
                            }
                        }

                        @Override
                        public void onError(String reason) {
                            cb.onError(reason);
                        }
                    }
            );
        } catch (Throwable t) {
            cb.onError(t.getMessage() != null ? t.getMessage() : "异常");
        }
    }

    private static void operateFansBoxReceive(ClassLoader cl, long pid, final FansBoxReceiveCallback cb) {
        try {
            Object userId = getHuyaUserId(cl);
            if (userId == null) {
                cb.onResult(false, 0, "未获取登录态");
                return;
            }
            byte[] userIdBytes = RawWupClient.serializeHuyaJceStruct(cl, userId);
            RawWupClient.JceWriter reqW = new RawWupClient.JceWriter();
            reqW.writeStructRaw(0, userIdBytes);      // tag 0: tUserId
            reqW.writeLong(1, pid);                   // tag 1: lPid
            reqW.writeInt(2, EOP_FGBOX_RECIVE);       // tag 2: iOpType = 3 (EOP_FGBOX_RECIVE)

            RawWupClient.sendRawWup(
                    cl, mainHandler(),
                    "wupui", "operateFansBox", "tReq",
                    reqW.toByteArray(), RPC_TIMEOUT_MS,
                    new RawWupClient.RawWupCallback() {
                        @Override
                        public void onSuccess(byte[] rspStructBody, String matchedKey) {
                            try {
                                RawWupClient.JceStructView rsp = RawWupClient.JceReader.parseFields(rspStructBody);
                                int retCode = rsp.getInt(0, -1);
                                int boxState = rsp.getInt(3, 0);
                                int itemType = rsp.getInt(4, 0);
                                int itemCount = rsp.getInt(5, 0);
                                int isFans = rsp.getInt(8, 0);
                                String detail = "iRetCode=" + retCode
                                        + ", boxState=" + boxState
                                        + ", itemType=" + itemType
                                        + ", itemCount=" + itemCount
                                        + ", isFans=" + isFans;
                                cb.onResult(retCode == 0, itemCount, detail);
                            } catch (Throwable t) {
                                cb.onResult(false, 0, "解析结果失败：" + t.getMessage());
                            }
                        }

                        @Override
                        public void onError(String reason) {
                            cb.onResult(false, 0, reason);
                        }
                    }
            );
        } catch (Throwable t) {
            cb.onResult(false, 0, t.getMessage() != null ? t.getMessage() : "异常");
        }
    }

    private static void receiveCommonWelfare(
            ClassLoader cl,
            CommonWelfareTarget item,
            final CommonWelfareReceiveCallback cb
    ) {
        try {
            Object userId = getHuyaUserId(cl);
            if (userId == null) {
                cb.onResult(false, "未获取登录态");
                return;
            }
            byte[] userIdBytes = RawWupClient.serializeHuyaJceStruct(cl, userId);
            RawWupClient.JceWriter reqW = new RawWupClient.JceWriter();
            reqW.writeStructRaw(0, userIdBytes);                // tag 0: tId
            reqW.writeLong(1, item.pid);                        // tag 1: lPid
            reqW.writeInt(2, COMM_WELFARE_OP_RECEIVE);          // tag 2: iOpType = 2
            reqW.writeLong(3, item.businessId);                 // tag 3: lBusinessId
            List<Long> ids = new ArrayList<>();
            ids.add(item.welfareId);
            reqW.writeLongList(4, ids);                         // tag 4: vWelfareId

            RawWupClient.sendRawWup(
                    cl, mainHandler(),
                    "wupui", "queryFGCommonWelfare", "tReq",
                    reqW.toByteArray(), RPC_TIMEOUT_MS,
                    new RawWupClient.RawWupCallback() {
                        @Override
                        public void onSuccess(byte[] rspStructBody, String matchedKey) {
                            try {
                                RawWupClient.JceStructView rsp = RawWupClient.JceReader.parseFields(rspStructBody);
                                int code = rsp.getInt(0, -1);
                                String msg = rsp.getString(1, "");
                                cb.onResult(code == 0, "iCode=" + code + (!msg.isEmpty() ? ", " + msg : ""));
                            } catch (Throwable t) {
                                cb.onResult(false, "解析结果失败：" + t.getMessage());
                            }
                        }

                        @Override
                        public void onError(String reason) {
                            cb.onResult(false, reason);
                        }
                    }
            );
        } catch (Throwable t) {
            cb.onResult(false, t.getMessage() != null ? t.getMessage() : "异常");
        }
    }

    private interface DailySignCallback {
        void onResult(String state, String detail);
    }

    private interface LiveBoxClaimCallback {
        void onDone(int claimedCount, List<String> labels);
    }

    private static void submitDailyUserSignIn(ClassLoader cl, final DailySignCallback cb) {
        try {
            Object userId = getHuyaUserId(cl);
            if (userId == null) {
                cb.onResult("skip", "未登录");
                return;
            }
            Class<?> wupOuter = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.treasurebox.impl.wupfunction.WupFunction$WupUIFunction", cl);
            Class<?> reqClass = XposedHelpers.findClassIfExists("com.duowan.HUYA.OldUserBackSignReq", cl);
            if (wupOuter == null || reqClass == null) {
                cb.onResult("skip", "未找到签到 WUP 类");
                return;
            }
            Class<?> taskClass = findSingleArgInnerWupClass(cl, wupOuter, reqClass);
            if (taskClass == null) {
                cb.onResult("skip", "未找到 OldUserBackSign 任务类");
                return;
            }
            ensureGenericWupHooks(taskClass);

            Object req = XposedHelpers.newInstance(reqClass);
            XposedHelpers.setObjectField(req, "tId", userId);
            XposedHelpers.setIntField(req, "iFrom", 2);

            Constructor<?> ctor = taskClass.getDeclaredConstructor(reqClass);
            ctor.setAccessible(true);
            final Object task = ctor.newInstance(req);

            final AtomicBoolean settled = new AtomicBoolean(false);
            final Runnable timeout = () -> {
                if (settled.compareAndSet(false, true)) {
                    sPendingWupCallbacks.remove(task);
                    cb.onResult("failed", "超时");
                }
            };
            mainHandler().postDelayed(timeout, 8000L);

            sPendingWupCallbacks.put(task, new GenericWupCallback() {
                @Override
                public void onSuccess(Object rsp) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    int udbCode = safeIntField(rsp, "iUdbCode");
                    int dayNum = safeIntField(rsp, "iDayNum");
                    int evenDay = safeIntField(rsp, "iEvenSignDay");
                    String des = safeStringField(rsp, "sDes");
                    String awardDesc = "";
                    try {
                        Object newAward = XposedHelpers.getObjectField(rsp, "tNewAward");
                        if (newAward != null) {
                            String name = safeStringField(newAward, "sName");
                            int num = safeIntField(newAward, "iNum");
                            if (!name.isEmpty()) {
                                awardDesc = num > 0 ? (name + "×" + num) : name;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    log("OldUserBackSignRsp: iUdbCode=" + udbCode + ", iDayNum=" + dayNum
                            + ", iEvenSignDay=" + evenDay + ", sDes=" + des + ", award=" + awardDesc);
                    if (udbCode == 0 && (dayNum > 0 || !awardDesc.isEmpty())) {
                        cb.onResult("success", !awardDesc.isEmpty() ? awardDesc : ("第" + dayNum + "天"));
                    } else if (des.contains("已") || des.contains("重复")) {
                        cb.onResult("already", des);
                    } else {
                        cb.onResult("skip", "code=" + udbCode + (!des.isEmpty() ? ", " + des : ""));
                    }
                }

                @Override
                public void onError(Object dataException) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    String errStr = String.valueOf(dataException);
                    if (errStr.contains("已") || errStr.contains("重复")) {
                        cb.onResult("already", errStr);
                    } else {
                        cb.onResult("skip", errStr);
                    }
                }
            });

            XposedHelpers.callMethod(task, "execute");
        } catch (Throwable t) {
            log("submitDailyUserSignIn error: " + t.getMessage());
            cb.onResult("skip", t.getMessage() != null ? t.getMessage() : "异常");
        }
    }

    private static void queryAndClaimLiveBoxes(ClassLoader cl, long pid, final LiveBoxClaimCallback cb) {
        try {
            Object userId = getHuyaUserId(cl);
            Class<?> wupOuter = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.treasurebox.impl.wupfunction.WupFunction$TreasureBoxUiWupFunction", cl);
            Class<?> reqClass = XposedHelpers.findClassIfExists("com.duowan.HUYA.GetLiveBoxPageReq", cl);
            if (userId == null || wupOuter == null || reqClass == null) {
                cb.onDone(0, Collections.emptyList());
                return;
            }
            Class<?> taskClass = findSingleArgInnerWupClass(cl, wupOuter, reqClass);
            if (taskClass == null) {
                cb.onDone(0, Collections.emptyList());
                return;
            }
            ensureGenericWupHooks(taskClass);

            Object req = XposedHelpers.newInstance(reqClass);
            XposedHelpers.setObjectField(req, "tId", userId);
            if (pid > 0L) {
                try {
                    XposedHelpers.setLongField(req, "lPid", pid);
                } catch (Throwable ignored) {
                }
            }

            Constructor<?> ctor = taskClass.getDeclaredConstructor(reqClass);
            ctor.setAccessible(true);
            final Object task = ctor.newInstance(req);

            final AtomicBoolean settled = new AtomicBoolean(false);
            final Runnable timeout = () -> {
                if (settled.compareAndSet(false, true)) {
                    sPendingWupCallbacks.remove(task);
                    cb.onDone(0, Collections.emptyList());
                }
            };
            mainHandler().postDelayed(timeout, 8000L);

            sPendingWupCallbacks.put(task, new GenericWupCallback() {
                @Override
                public void onSuccess(Object rsp) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    List<Object> readyBoxes = new ArrayList<>();
                    try {
                        for (String fieldName : new String[]{"vBox", "vLiveBox", "vBoxInfo", "vDailyBox", "vTaskBox"}) {
                            try {
                                Object val = XposedHelpers.getObjectField(rsp, fieldName);
                                if (val instanceof List) {
                                    List<?> list = (List<?>) val;
                                    log("GetLiveBoxPageRsp." + fieldName + " size=" + list.size());
                                    for (Object box : list) {
                                        if (box == null) {
                                            continue;
                                        }
                                        int boxId = safeIntField(box, "iBoxId");
                                        int status = safeIntField(box, "iBoxStatus");
                                        String title = safeStringField(box, "sTitle");
                                        if (title.isEmpty()) {
                                            title = safeStringField(box, "sBoxName");
                                        }
                                        log("  liveBox: id=" + boxId + ", status=" + status + ", title=" + title);
                                        // Status 2 in Huya LiveBoxInfo typically means finished & claimable (1=in-progress, 2=claimable, 3=claimed)
                                        if (boxId > 0 && status == 2) {
                                            readyBoxes.add(box);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    if (readyBoxes.isEmpty()) {
                        cb.onDone(0, Collections.emptyList());
                    } else {
                        claimLiveBoxAt(cl, pid, readyBoxes, 0, new ArrayList<>(), cb);
                    }
                }

                @Override
                public void onError(Object dataException) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    log("GetLiveBoxPage error: " + dataException);
                    cb.onDone(0, Collections.emptyList());
                }
            });

            XposedHelpers.callMethod(task, "execute");
        } catch (Throwable t) {
            log("queryAndClaimLiveBoxes error: " + t.getMessage());
            cb.onDone(0, Collections.emptyList());
        }
    }

    private static void claimLiveBoxAt(
            final ClassLoader cl,
            final long pid,
            final List<Object> boxes,
            final int index,
            final List<String> claimedLabels,
            final LiveBoxClaimCallback cb
    ) {
        if (index >= boxes.size()) {
            cb.onDone(claimedLabels.size(), claimedLabels);
            return;
        }
        Object box = boxes.get(index);
        final int boxId = safeIntField(box, "iBoxId");
        String t = safeStringField(box, "sTitle");
        if (t.isEmpty()) {
            t = safeStringField(box, "sBoxName");
        }
        final String boxTitle = !t.isEmpty() ? t : ("任务宝箱#" + boxId);
        try {
            Object userId = getHuyaUserId(cl);
            Class<?> wupOuter = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.treasurebox.impl.wupfunction.WupFunction$TreasureBoxUiWupFunction", cl);
            Class<?> reqClass = XposedHelpers.findClassIfExists("com.duowan.HUYA.AwardLiveBoxReq", cl);
            Class<?> taskClass = (wupOuter != null && reqClass != null)
                    ? findSingleArgInnerWupClass(cl, wupOuter, reqClass) : null;
            if (userId == null || taskClass == null) {
                cb.onDone(claimedLabels.size(), claimedLabels);
                return;
            }
            ensureGenericWupHooks(taskClass);

            Object req = XposedHelpers.newInstance(reqClass);
            XposedHelpers.setObjectField(req, "tId", userId);
            XposedHelpers.setIntField(req, "iBoxId", boxId);
            XposedHelpers.setLongField(req, "lPid", pid);

            Constructor<?> ctor = taskClass.getDeclaredConstructor(reqClass);
            ctor.setAccessible(true);
            final Object task = ctor.newInstance(req);

            final AtomicBoolean settled = new AtomicBoolean(false);
            final Runnable timeout = () -> {
                if (settled.compareAndSet(false, true)) {
                    sPendingWupCallbacks.remove(task);
                    claimLiveBoxAt(cl, pid, boxes, index + 1, claimedLabels, cb);
                }
            };
            mainHandler().postDelayed(timeout, 8000L);

            sPendingWupCallbacks.put(task, new GenericWupCallback() {
                @Override
                public void onSuccess(Object rsp) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    int st = safeIntField(rsp, "iBoxStatus");
                    String msg = safeStringField(rsp, "sMessage");
                    log("AwardLiveBox (" + boxTitle + ", id=" + boxId + ") -> status=" + st + ", msg=" + msg);
                    if (st == 3 || msg.contains("成功")) {
                        claimedLabels.add(boxTitle);
                    }
                    mainHandler().postDelayed(
                            () -> claimLiveBoxAt(cl, pid, boxes, index + 1, claimedLabels, cb),
                            CHECKIN_INTERVAL_MS);
                }

                @Override
                public void onError(Object dataException) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    log("AwardLiveBox (" + boxTitle + ", id=" + boxId + ") error: " + dataException);
                    mainHandler().postDelayed(
                            () -> claimLiveBoxAt(cl, pid, boxes, index + 1, claimedLabels, cb),
                            CHECKIN_INTERVAL_MS);
                }
            });

            XposedHelpers.callMethod(task, "execute");
        } catch (Throwable ex) {
            claimLiveBoxAt(cl, pid, boxes, index + 1, claimedLabels, cb);
        }
    }

    private static void queryBagHuliang(ClassLoader cl, final BagHuliangCallback cb) {
        queryBagHuliang(cl, 0L, cb);
    }

    private static void queryBagHuliang(ClassLoader cl, long hintPid, final BagHuliangCallback cb) {
        try {
            Object userId = getHuyaUserId(cl);
            if (userId == null) {
                cb.onError("未获取登录态");
                return;
            }

            Class<?> wupOuter = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.my.wupfunction.WupFunction$WupUIFunction", cl);
            Class<?> reqClass = XposedHelpers.findClassIfExists("com.duowan.HUYA.GetPackageGiftReq", cl);
            if (wupOuter == null || reqClass == null) {
                cb.onError("未找到包裹查询 WUP 类");
                return;
            }
            Class<?> taskClass = findSingleArgInnerWupClass(cl, wupOuter, reqClass);
            if (taskClass == null) {
                cb.onError("未找到 getPackageGift 任务类");
                return;
            }
            ensureGenericWupHooks(taskClass);

            Object req = XposedHelpers.newInstance(reqClass);
            XposedHelpers.setObjectField(req, "tId", userId);
            XposedHelpers.setLongField(req, "lPid", Math.max(0L, hintPid));

            Constructor<?> ctor = taskClass.getDeclaredConstructor(reqClass);
            ctor.setAccessible(true);
            final Object task = ctor.newInstance(req);

            final AtomicBoolean settled = new AtomicBoolean(false);
            final Runnable timeout = () -> {
                if (settled.compareAndSet(false, true)) {
                    sPendingWupCallbacks.remove(task);
                    cb.onError("查询包裹超时");
                }
            };
            mainHandler().postDelayed(timeout, RPC_TIMEOUT_MS);

            sPendingWupCallbacks.put(task, new GenericWupCallback() {
                @Override
                public void onSuccess(Object rsp) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    cb.onSuccess(extractBagHuliang(rsp));
                }

                @Override
                public void onError(Object dataException) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    cb.onError(String.valueOf(dataException));
                }
            });

            XposedHelpers.callMethod(task, "execute");
        } catch (Throwable t) {
            logError("queryBagHuliang failed", t);
            cb.onError(t.getMessage() != null ? t.getMessage() : "异常");
        }
    }

    private static BagHuliangSummary extractBagHuliang(Object packageGiftRsp) {
        int normalItemType = 4;
        String normalName = "虎粮";
        long normalTotal = 0L;
        List<FreeHuliangBatch> normalBatches = new ArrayList<>();
        long normalDetailExpireInDay = 0L;
        long earliestNormalExpireSec = 0L;
        List<FreeHuliangItem> superItems = new ArrayList<>();
        long superTotal = 0L;
        String superDisplayName = "";
        long serverTimeSec = System.currentTimeMillis() / 1000L;

        if (packageGiftRsp == null) {
            return new BagHuliangSummary(
                    normalItemType, normalName, 0, normalBatches, serverTimeSec, 0, 0L, 0L,
                    superItems, 0, superDisplayName);
        }
        long rspServerTime = safeLongOrIntField(packageGiftRsp, "lServiceTime");
        if (rspServerTime > 0L) {
            serverTimeSec = rspServerTime;
        }
        try {
            Object rawList = XposedHelpers.getObjectField(packageGiftRsp, "vDetail");
            if (!(rawList instanceof List)) {
                log("extractBagHuliang: vDetail is null or empty");
                return new BagHuliangSummary(
                        normalItemType, normalName, 0, normalBatches, serverTimeSec, 0, 0L, 0L,
                        superItems, 0, superDisplayName);
            }
            List<?> details = (List<?>) rawList;
            log("extractBagHuliang: bag gift types count=" + details.size()
                    + ", serverTimeSec=" + serverTimeSec);
            for (Object detail : details) {
                if (detail == null) {
                    continue;
                }
                Object simple = null;
                try {
                    simple = XposedHelpers.getObjectField(detail, "tSimepleInfo");
                } catch (Throwable ignored) {
                }
                int itemType = simple != null ? safeIntField(simple, "iItemType") : 0;
                int value = simple != null ? safeIntField(simple, "iValue") : -1;
                String name = simple != null ? safeStringField(simple, "sItemName") : "";
                long detailExpireInDay = Math.max(0L, safeLongOrIntField(detail, "lExpireCountInDay"));
                long detailMostEarlyExpire = readUnsignedExpireTimeSec(detail, "lMostEarlyExpireTime");

                Object subListObj = null;
                try {
                    subListObj = XposedHelpers.getObjectField(detail, "vList");
                } catch (Throwable ignored) {
                }
                long freeCnt = 0L;
                long paidCnt = 0L;
                List<FreeHuliangBatch> itemFreeBatches = new ArrayList<>();
                long earliestItemExpire = 0L;
                if (subListObj instanceof List) {
                    for (Object sub : (List<?>) subListObj) {
                        if (sub == null) {
                            continue;
                        }
                        if (itemType <= 0) {
                            itemType = safeIntField(sub, "iItemType");
                        }
                        int isPay = safeIntField(sub, "iIsPay");
                        long cnt = safeLongField(sub, "lItemCount");
                        if (isPay == 0) {
                            if (cnt > 0L) {
                                freeCnt += cnt;
                                long expireSec = readUnsignedExpireTimeSec(sub, "lExpireTime");
                                if (expireSec <= 0L) {
                                    expireSec = readUnsignedExpireTimeSec(sub, "lMinSecond");
                                }
                                itemFreeBatches.add(new FreeHuliangBatch(
                                        (int) Math.min(Integer.MAX_VALUE, cnt), expireSec));
                                if (expireSec > 0L
                                        && (earliestItemExpire == 0L || expireSec < earliestItemExpire)) {
                                    earliestItemExpire = expireSec;
                                }
                            }
                        } else {
                            paidCnt += Math.max(0L, cnt);
                        }
                    }
                }
                if (earliestItemExpire == 0L && detailMostEarlyExpire > 0L) {
                    earliestItemExpire = detailMostEarlyExpire;
                }
                log("  bag item: type=" + itemType + ", name=" + name + ", value=" + value
                        + ", freeCnt=" + freeCnt + ", paidCnt=" + paidCnt
                        + ", expireInDay=" + detailExpireInDay
                        + ", earliestExpire=" + earliestItemExpire
                        + ", freeBatches=" + itemFreeBatches.size());

                boolean isSuperHuliang = name != null
                        && (name.contains("超粉虎粮")
                        || name.contains("超级虎粮")
                        || (name.contains("虎粮") && itemType > 0 && itemType != 4));
                boolean isNormalHuliang = !isSuperHuliang
                        && (itemType == 4 || "虎粮".equals(name));

                if (isSuperHuliang && freeCnt > 0L && itemType > 0) {
                    int safeCnt = (int) Math.min(Integer.MAX_VALUE, freeCnt);
                    String label = !name.isEmpty() ? name : "超粉虎粮";
                    superItems.add(new FreeHuliangItem(itemType, label, safeCnt, true, earliestItemExpire));
                    superTotal += freeCnt;
                    if (superDisplayName.isEmpty()) {
                        superDisplayName = label;
                    }
                } else if (isNormalHuliang && freeCnt > 0L) {
                    normalItemType = itemType > 0 ? itemType : 4;
                    normalName = (name != null && !name.isEmpty()) ? name : "虎粮";
                    normalTotal += freeCnt;
                    normalBatches.addAll(itemFreeBatches);
                    normalDetailExpireInDay += detailExpireInDay;
                    if (earliestItemExpire > 0L
                            && (earliestNormalExpireSec == 0L || earliestItemExpire < earliestNormalExpireSec)) {
                        earliestNormalExpireSec = earliestItemExpire;
                    }
                }
            }
        } catch (Throwable t) {
            logError("extractBagHuliang failed", t);
        }

        Collections.sort(normalBatches, (a, b) -> {
            long ea = a != null && a.expireSec > 0L ? a.expireSec : Long.MAX_VALUE;
            long eb = b != null && b.expireSec > 0L ? b.expireSec : Long.MAX_VALUE;
            return Long.compare(ea, eb);
        });

        int safeNormal = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, normalTotal));
        int safeSuper = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, superTotal));
        BagHuliangSummary temp = new BagHuliangSummary(
                normalItemType, normalName, safeNormal, normalBatches, serverTimeSec,
                0, earliestNormalExpireSec, normalDetailExpireInDay,
                superItems, safeSuper, superDisplayName);
        int expiring24h = temp.countNormalExpiringBy(serverTimeSec + 24L * 3600L);
        return new BagHuliangSummary(
                normalItemType, normalName, safeNormal, normalBatches, serverTimeSec,
                expiring24h, earliestNormalExpireSec, normalDetailExpireInDay,
                superItems, safeSuper, superDisplayName);
    }

    /**
     * Sends FREE backpack 虎粮 ONLY using Huya's SendGiftPayPloyUtil.PACKAGE_PAY_PLOY
     * (iPayPloy=2, lPayCount=0, lNewCoinDeduction=0) so coins/beans are NEVER deducted.
     */
    private static void sendFreeHuliangOnce(
            ClassLoader cl,
            int giftId,
            long targetPid,
            int count,
            final SendGiftCallback cb
    ) {
        if (count <= 0 || targetPid <= 0L) {
            cb.onResult(false, "参数无效");
            return;
        }
        try {
            Class<?> entryClass = XposedHelpers.findClassIfExists("com.huya.gift.SendGiftEntry", cl);
            Class<?> builderClass = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.props.api.request.SendGiftParam$Builder", cl);
            Class<?> payPloyUtilClass = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.props.api.utils.SendGiftPayPloyUtil", cl);
            Class<?> serviceClass = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.props.impl.request.SendGiftService", cl);
            Class<?> continuationClass = XposedHelpers.findClassIfExists(
                    "kotlin.coroutines.Continuation", cl);
            Class<?> emptyCtxClass = XposedHelpers.findClassIfExists(
                    "kotlin.coroutines.EmptyCoroutineContext", cl);
            Class<?> intrinsicsClass = XposedHelpers.findClassIfExists(
                    "kotlin.coroutines.intrinsics.IntrinsicsKt", cl);
            if (entryClass == null || builderClass == null || payPloyUtilClass == null
                    || serviceClass == null || continuationClass == null || emptyCtxClass == null) {
                cb.onResult(false, "未找到送礼服务类");
                return;
            }

            Object pkgEntry = XposedHelpers.getStaticObjectField(entryClass, "GIFT_PANEL_PACKAGE");
            Object pkgPayPloy = XposedHelpers.getStaticObjectField(payPloyUtilClass, "PACKAGE_PAY_PLOY");
            if (pkgPayPloy == null) {
                cb.onResult(false, "未获取到包裹支付策略（安全中止）");
                return;
            }
            // Extra safety check: verify PACKAGE_PAY_PLOY has lPayCount==0 & lNewCoinDeduction==0.
            long payCount = safeLongField(pkgPayPloy, "lPayCount");
            long coinDeduct = safeLongField(pkgPayPloy, "lNewCoinDeduction");
            if (payCount != 0L || coinDeduct != 0L) {
                cb.onResult(false, "支付策略校验异常（安全中止）");
                return;
            }

            Constructor<?> bCtor = builderClass.getDeclaredConstructor(int.class, int.class, entryClass);
            bCtor.setAccessible(true);
            Object builder = bCtor.newInstance(giftId, count, pkgEntry);
            XposedHelpers.callMethod(builder, "receiveUid", targetPid);
            XposedHelpers.callMethod(builder, "roomUid", targetPid);
            XposedHelpers.callMethod(builder, "payPloy", pkgPayPloy);
            Object param = XposedHelpers.callMethod(builder, "build");

            Object service = XposedHelpers.newInstance(serviceClass);
            Method execMethod = serviceClass.getDeclaredMethod(
                    "executeSingleSend", param.getClass(), long.class, continuationClass);
            execMethod.setAccessible(true);

            final Object emptyCtx = XposedHelpers.getStaticObjectField(emptyCtxClass, "INSTANCE");
            final AtomicBoolean settled = new AtomicBoolean(false);
            final Runnable timeout = () -> {
                if (settled.compareAndSet(false, true)) {
                    cb.onResult(false, "送礼超时");
                }
            };
            mainHandler().postDelayed(timeout, RPC_TIMEOUT_MS);

            Object contProxy = Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{continuationClass},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if ("getContext".equals(mName)) {
                            return emptyCtx;
                        }
                        if ("resumeWith".equals(mName)) {
                            Object result = (args != null && args.length > 0) ? args[0] : null;
                            if (settled.compareAndSet(false, true)) {
                                mainHandler().removeCallbacks(timeout);
                                handleSendGiftCoroutineResult(result, cb);
                            }
                            return null;
                        }
                        if ("toString".equals(mName)) {
                            return "HuyaPureSendGiftContinuation";
                        }
                        return null;
                    }
            );

            Object immediate = execMethod.invoke(service, param, targetPid, contProxy);
            Object suspendedMarker = intrinsicsClass != null
                    ? XposedHelpers.callStaticMethod(intrinsicsClass, "getCOROUTINE_SUSPENDED")
                    : null;
            if (immediate != suspendedMarker && settled.compareAndSet(false, true)) {
                mainHandler().removeCallbacks(timeout);
                handleSendGiftCoroutineResult(immediate, cb);
            }
        } catch (Throwable t) {
            logError("sendFreeHuliangOnce failed for pid=" + targetPid, t);
            cb.onResult(false, t.getMessage() != null ? t.getMessage() : "异常");
        }
    }

    private static void handleSendGiftCoroutineResult(Object result, SendGiftCallback cb) {
        if (result == null) {
            cb.onResult(false, "返回为空");
            return;
        }
        try {
            String clsName = result.getClass().getName();
            if ("kotlin.Result$Failure".equals(clsName)) {
                Object ex = XposedHelpers.getObjectField(result, "exception");
                cb.onResult(false, ex != null ? String.valueOf(ex) : "请求失败");
                return;
            }
            Object rsp = result;
            if ("kotlin.Pair".equals(clsName)) {
                rsp = XposedHelpers.callMethod(result, "getFirst");
            }
            if (rsp == null) {
                cb.onResult(false, "响应为空");
                return;
            }
            int code = safeIntField(rsp, "iPayRespCode");
            String toastMsg = "";
            try {
                Object toastInfo = XposedHelpers.getObjectField(rsp, "tToastInfo");
                if (toastInfo != null) {
                    toastMsg = safeStringField(toastInfo, "sMessage");
                }
            } catch (Throwable ignored) {
            }
            if (code == 0) {
                cb.onResult(true, nonEmptyOr(toastMsg, "iPayRespCode=0"));
            } else {
                cb.onResult(false, "code=" + code + (!toastMsg.isEmpty() ? ", " + toastMsg : ""));
            }
        } catch (Throwable t) {
            cb.onResult(false, "解析结果失败：" + t.getMessage());
        }
    }

    private static String joinStrings(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    private static void queryAllFanBadges(ClassLoader cl, final BadgeListCallback cb) {
        try {
            Object userId = getHuyaUserId(cl);
            if (userId == null) {
                cb.onError("未获取登录态");
                return;
            }
            long myUid = XposedHelpers.getLongField(userId, "lUid");

            Class<?> gameLiveWupClass = XposedHelpers.findClassIfExists(
                    "com.duowan.biz.wup.gamelive.GameLiveWupFunction", cl);
            Class<?> reqClass = XposedHelpers.findClassIfExists("com.duowan.HUYA.BadgeInfoListReq", cl);
            if (gameLiveWupClass == null || reqClass == null) {
                cb.onError("未找到粉丝牌列表 WUP 类");
                return;
            }

            Class<?> taskClass = findSingleArgInnerWupClass(cl, gameLiveWupClass, reqClass);
            if (taskClass == null) {
                cb.onError("未找到 queryBadgeInfoList 任务类");
                return;
            }
            ensureGenericWupHooks(taskClass);

            Object req = XposedHelpers.newInstance(reqClass);
            XposedHelpers.setObjectField(req, "tUserId", userId);
            XposedHelpers.setLongField(req, "lToUid", myUid);
            XposedHelpers.setIntField(req, "iType", 0);
            XposedHelpers.setLongField(req, "lPid", 0L);
            XposedHelpers.setIntField(req, "iSFanFlag", 0);

            Constructor<?> ctor = taskClass.getDeclaredConstructor(reqClass);
            ctor.setAccessible(true);
            final Object task = ctor.newInstance(req);

            final AtomicBoolean settled = new AtomicBoolean(false);
            final Runnable timeout = () -> {
                if (settled.compareAndSet(false, true)) {
                    sPendingWupCallbacks.remove(task);
                    cb.onError("查询粉丝牌列表超时");
                }
            };
            mainHandler().postDelayed(timeout, RPC_TIMEOUT_MS);

            sPendingWupCallbacks.put(task, new GenericWupCallback() {
                @Override
                public void onSuccess(Object rsp) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    List<BadgeTarget> list = extractBadgeTargets(cl, rsp, myUid);
                    cb.onSuccess(list);
                }

                @Override
                public void onError(Object dataException) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    cb.onError(String.valueOf(dataException));
                }
            });

            XposedHelpers.callMethod(task, "execute");
        } catch (Throwable t) {
            logError("queryAllFanBadges failed", t);
            cb.onError(t.getMessage() != null ? t.getMessage() : "异常");
        }
    }

    private static List<BadgeTarget> extractBadgeTargets(ClassLoader cl, Object badgeListRsp, long myUid) {
        List<BadgeTarget> result = new ArrayList<>();
        if (badgeListRsp == null) {
            return result;
        }
        try {
            Object rawList = XposedHelpers.getObjectField(badgeListRsp, "vBadgeInfo");
            if (!(rawList instanceof List)) {
                log("extractBadgeTargets: vBadgeInfo is null or not a List: " + rawList);
                return result;
            }
            Class<?> superFansUtilClass = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.badge.view.utils.SuperFansUtil", cl);
            List<?> items = (List<?>) rawList;
            log("extractBadgeTargets: raw vBadgeInfo size=" + items.size() + ", myUid=" + myUid);
            Set<Long> seenPids = new HashSet<>();
            long nowSec = System.currentTimeMillis() / 1000L;
            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                long chUid = 0L;
                try {
                    Object ch = XposedHelpers.getObjectField(item, "tChannelInfo");
                    if (ch != null) {
                        chUid = safeLongField(ch, "lUid");
                    }
                } catch (Throwable ignored) {
                }
                long badgeUid = safeLongField(item, "lUid");
                long badgeId = safeLongField(item, "lBadgeId");
                long pid = chUid > 0L ? chUid : (badgeUid > 0L && badgeUid != myUid ? badgeUid : 0L);
                String badgeName = safeStringField(item, "sBadgeName");
                String nick = safeStringField(item, "sPresenterNickName");
                String avatarUrl = safeStringField(item, "sPresenterLogo");
                if (avatarUrl.startsWith("http://")) {
                    avatarUrl = "https://" + avatarUrl.substring(7);
                }
                int level = safeIntField(item, "iBadgeLevel");
                int score = safeIntField(item, "iScore");
                int nextScore = safeIntField(item, "iNextScore");
                int dayScore = safeIntField(item, "iDayScore");
                int quota = safeIntField(item, "iQuota");
                int quotaUsed = safeIntField(item, "iQuotaUsed");
                int baseQuota = safeIntField(item, "iBaseQuota");
                int badgeType = safeIntField(item, "iBadgeType");
                int customBadgeFlag = safeIntField(item, "iCustomBadgeFlag");
                int extinguished = safeIntField(item, "iExtinguished");

                int sfType = 0;
                int sfVariety = 0;
                int sfLevel = 0;
                int sfFlag = 0;
                long sfAnnualTs = 0L;
                long sfExpiredTs = 0L;
                int extIdentity = 0;
                String faithName = "";
                Object extObj = null;

                if (superFansUtilClass != null) {
                    try {
                        Object t = XposedHelpers.callStaticMethod(superFansUtilClass, "getSuperFansType", item);
                        if (t instanceof Integer) {
                            sfType = (Integer) t;
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        Object v = XposedHelpers.callStaticMethod(superFansUtilClass, "getSuperFansVariety", item);
                        if (v instanceof Integer) {
                            sfVariety = (Integer) v;
                        }
                    } catch (Throwable ignored) {
                    }
                }

                try {
                    Object sfInfo = XposedHelpers.getObjectField(item, "tSuperFansInfo");
                    if (sfInfo != null) {
                        sfFlag = safeIntField(sfInfo, "iSFFlag");
                        sfLevel = safeIntField(sfInfo, "iSFLevel");
                        int rawVariety = safeIntField(sfInfo, "iSFVariety");
                        sfAnnualTs = safeLongField(sfInfo, "lSFAnnualTS");
                        sfExpiredTs = safeLongField(sfInfo, "lSFExpiredTS");
                        if (sfVariety == 0 && rawVariety > 0) {
                            sfVariety = rawVariety;
                        }
                        if (sfType == 0) {
                            boolean activeByExpiry = sfExpiredTs == 0L || sfExpiredTs > nowSec;
                            if (activeByExpiry && (sfFlag > 0 || sfLevel > 0)) {
                                sfType = (sfAnnualTs > nowSec || sfFlag == 2) ? 2 : 1;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }

                try {
                    extObj = XposedHelpers.getObjectField(item, "tExternal");
                    if (extObj != null) {
                        extIdentity = safeIntField(extObj, "iFansIdentity");
                    }
                } catch (Throwable ignored) {
                }

                try {
                    Object faithObj = XposedHelpers.getObjectField(item, "tFaithInfo");
                    if (faithObj != null) {
                        faithName = safeStringField(faithObj, "sFaithName");
                    }
                } catch (Throwable ignored) {
                }

                // Compute native identity suffix ID (1=超粉, 2=年费超粉, 3=超粉Plus, 4=年费超粉Plus)
                int fansIdentity = 0;
                if (sfType == 1) {
                    fansIdentity = sfVariety != 0 ? 3 : 1;
                } else if (sfType == 2) {
                    fansIdentity = sfVariety != 0 ? 4 : 2;
                } else if (extIdentity > 0) {
                    fansIdentity = extIdentity;
                    if (extIdentity == 1 || extIdentity == 3) {
                        sfType = 1;
                        sfVariety = (extIdentity == 3) ? 1 : 0;
                    } else if (extIdentity == 2 || extIdentity == 4) {
                        sfType = 2;
                        sfVariety = (extIdentity == 4) ? 1 : 0;
                    }
                }
                boolean superFan = sfType > 0 || sfFlag > 0 || sfLevel > 0
                        || (fansIdentity >= 1 && fansIdentity <= 4);

                int effectiveQuota = quota > 0 ? quota : baseQuota;
                log("  badge raw: nick=" + nick + ", name=" + badgeName + ", lv=" + level
                        + ", badgeId=" + badgeId + ", customFlag=" + customBadgeFlag
                        + ", score=" + score + ", nextScore=" + nextScore
                        + ", dayScore=" + dayScore + ", quota=" + quota + ", quotaUsed=" + quotaUsed
                        + ", sf=" + superFan + " (type=" + sfType + ", variety=" + sfVariety
                        + ", flag=" + sfFlag + ", sfLv=" + sfLevel + ", id=" + fansIdentity
                        + ", extId=" + extIdentity + ", faith=" + faithName + ")"
                        + ", type=" + badgeType + ", ext=" + extinguished
                        + ", chUid=" + chUid + " -> pid=" + pid);
                if (pid <= 0L || !seenPids.add(pid)) {
                    continue;
                }
                BadgeTarget bt = new BadgeTarget(
                        pid,
                        badgeId,
                        badgeName,
                        nick.isEmpty() ? String.valueOf(pid) : nick,
                        level,
                        score,
                        nextScore,
                        dayScore,
                        effectiveQuota,
                        quotaUsed,
                        extinguished,
                        badgeType,
                        customBadgeFlag,
                        superFan,
                        sfType,
                        sfVariety,
                        sfLevel,
                        fansIdentity,
                        faithName,
                        extObj
                );
                bt.avatarUrl = avatarUrl;
                result.add(bt);
            }
            Collections.sort(result, (a, b) -> {
                if (a.extinguished != b.extinguished) {
                    return Integer.compare(a.extinguished, b.extinguished);
                }
                if (b.level != a.level) {
                    return Integer.compare(b.level, a.level);
                }
                return Integer.compare(b.score, a.score);
            });
        } catch (Throwable t) {
            logError("extractBadgeTargets failed", t);
        }
        return result;
    }

    private interface JceWupCallback {
        void onSuccess(Object rsp);
        void onError(String reason);
    }

    private static void sendJceWupRequest(
            final ClassLoader cl,
            String servantName,
            String funcName,
            Object reqStruct,
            final Class<?> rspClass,
            final JceWupCallback cb
    ) {
        try {
            byte[] reqBytes = RawWupClient.serializeHuyaJceStruct(cl, reqStruct);
            RawWupClient.sendRawWup(
                    cl, mainHandler(),
                    servantName, funcName, "tReq",
                    reqBytes, 8000L,
                    new RawWupClient.RawWupCallback() {
                        @Override
                        public void onSuccess(byte[] rspStructBody, String matchedKey) {
                            try {
                                Class<?> jisClass = XposedHelpers.findClass(
                                        "com.duowan.taf.jce.JceInputStream", cl);
                                Object jis = XposedHelpers.newInstance(
                                        jisClass, new Class<?>[]{byte[].class}, rspStructBody);
                                XposedHelpers.callMethod(jis, "setServerEncoding", "UTF-8");
                                Object rsp = XposedHelpers.newInstance(rspClass);
                                XposedHelpers.callMethod(rsp, "readFrom", jis);
                                cb.onSuccess(rsp);
                            } catch (Throwable t) {
                                cb.onError("解码 " + rspClass.getSimpleName() + " 失败：" + t.getMessage());
                            }
                        }

                        @Override
                        public void onError(String reason) {
                            cb.onError(reason);
                        }
                    }
            );
        } catch (Throwable t) {
            cb.onError(t.getMessage() != null ? t.getMessage() : "异常");
        }
    }

    private static void querySuperFansConfigForBadge(
            ClassLoader cl,
            final BadgeTarget badge,
            final Runnable onDone
    ) {
        if (badge == null || badge.pid <= 0L) {
            onDone.run();
            return;
        }
        try {
            Object userId = getHuyaUserId(cl);
            Class<?> reqClass = XposedHelpers.findClassIfExists(
                    "com.duowan.HUYA.GetLiveRoomProfileForAudienceReq", cl);
            Class<?> rspClass = XposedHelpers.findClassIfExists(
                    "com.duowan.HUYA.GetLiveRoomProfileForAudienceRsp", cl);
            if (userId == null || reqClass == null || rspClass == null) {
                log("SuperFansConfig (" + badge.presenterNick + "): skipped (missing WUP class or userId)");
                onDone.run();
                return;
            }
            Object req = XposedHelpers.newInstance(reqClass);
            XposedHelpers.setObjectField(req, "tId", userId);
            XposedHelpers.setLongField(req, "lPresenterUid", badge.pid);

            sendJceWupRequest(cl, "wupui", "getLiveRoomProfileForAudience", req, rspClass,
                    new JceWupCallback() {
                        @Override
                        public void onSuccess(Object rsp) {
                            try {
                                Object roomProfile = safeObjectField(rsp, "tRoomProfile");
                                Object badgeRsp = roomProfile != null
                                        ? safeObjectField(roomProfile, "tBadgeRsp") : null;
                                Object cfg = badgeRsp != null
                                        ? safeObjectField(badgeRsp, "tSuperFansConfig") : null;
                                if (cfg != null) {
                                    Object rawIsSfDay = safeObjectField(cfg, "iIsSFDay");
                                    int isSfDayInt = rawIsSfDay instanceof Number
                                            ? ((Number) rawIsSfDay).intValue()
                                            : (Boolean.TRUE.equals(rawIsSfDay)
                                            ? 1 : safeIntField(cfg, "iIsSFDay"));
                                    boolean bIsSfDay = safeBoolField(cfg, "bIsSFDay");
                                    int sfDayVal = safeIntField(cfg, "iSFDay");
                                    int scoreTimes = safeIntField(cfg, "iSFDayScoreTimes");
                                    int quotaTimes = safeIntField(cfg, "iSFDayQuotaTimes");
                                    int leftSec = safeIntField(cfg, "iBeLeftTime");

                                    badge.sfDayActive = (isSfDayInt == 1 || bIsSfDay);
                                    badge.sfDay = sfDayVal;
                                    badge.sfDayScoreTimes = scoreTimes > 0
                                            ? scoreTimes : (badge.sfDayActive ? 4 : 0);
                                    badge.sfDayQuotaTimes = quotaTimes;
                                    badge.sfDayLeftSec = Math.max(0, leftSec);
                                    badge.nextSfDayEpoch = badge.sfDayActive
                                            ? 0L : computeNextSfDayEpoch(sfDayVal);

                                    log("SuperFansConfig (" + badge.presenterNick + "): "
                                            + "active=" + badge.sfDayActive
                                            + " (iIsSFDay=" + isSfDayInt + ", bIsSFDay=" + bIsSfDay + ")"
                                            + ", iSFDay=" + sfDayVal
                                            + ", scoreTimes=" + badge.sfDayScoreTimes
                                            + ", quotaTimes=" + badge.sfDayQuotaTimes
                                            + ", leftSec=" + badge.sfDayLeftSec
                                            + ", nextSfDayEpoch=" + badge.nextSfDayEpoch);
                                } else {
                                    log("SuperFansConfig (" + badge.presenterNick + "): tSuperFansConfig is null");
                                }
                            } catch (Throwable t) {
                                log("SuperFansConfig (" + badge.presenterNick + ") parse error: " + t.getMessage());
                            }
                            onDone.run();
                        }

                        @Override
                        public void onError(String reason) {
                            log("SuperFansConfig (" + badge.presenterNick + ") error: " + reason);
                            onDone.run();
                        }
                    });
        } catch (Throwable t) {
            log("SuperFansConfig (" + badge.presenterNick + ") failed: " + t.getMessage());
            onDone.run();
        }
    }

    private static long computeNextSfDayEpoch(int sfDayVal) {
        if (sfDayVal >= 1 && sfDayVal <= 31) {
            Calendar todayCal = Calendar.getInstance();
            todayCal.set(Calendar.HOUR_OF_DAY, 0);
            todayCal.set(Calendar.MINUTE, 0);
            todayCal.set(Calendar.SECOND, 0);
            todayCal.set(Calendar.MILLISECOND, 0);

            Calendar targetCal = (Calendar) todayCal.clone();
            int maxDay = targetCal.getActualMaximum(Calendar.DAY_OF_MONTH);
            targetCal.set(Calendar.DAY_OF_MONTH, Math.min(sfDayVal, maxDay));
            if (targetCal.getTimeInMillis() <= todayCal.getTimeInMillis()) {
                targetCal.set(Calendar.DAY_OF_MONTH, 1);
                targetCal.add(Calendar.MONTH, 1);
                int nextMaxDay = targetCal.getActualMaximum(Calendar.DAY_OF_MONTH);
                targetCal.set(Calendar.DAY_OF_MONTH, Math.min(sfDayVal, nextMaxDay));
            }
            return targetCal.getTimeInMillis() / 1000L;
        }
        if (sfDayVal >= 20200101) {
            int year = sfDayVal / 10000;
            int month = (sfDayVal / 100) % 100;
            int day = sfDayVal % 100;
            if (year >= 2020 && year <= 2100 && month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                Calendar cal = Calendar.getInstance();
                cal.clear();
                cal.set(year, month - 1, day, 0, 0, 0);
                cal.set(Calendar.MILLISECOND, 0);
                return cal.getTimeInMillis() / 1000L;
            }
        }
        return 0L;
    }

    private static String buildSfDayActiveSummary(List<BadgeTarget> badges) {
        if (badges == null || badges.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (BadgeTarget b : badges) {
            if (b != null && b.superFan && b.sfDayActive) {
                int times = b.sfDayScoreTimes > 0 ? b.sfDayScoreTimes : 4;
                String nick = nonEmptyOr(b.presenterNick, "UID:" + b.pid);
                parts.add(nick + " (" + times + "倍)");
            }
        }
        return joinStrings(parts, ", ");
    }

    private static String formatMonthDay(long epochSec) {
        if (epochSec <= 0L) {
            return "";
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(epochSec * 1000L);
        return (c.get(Calendar.MONTH) + 1) + "月" + c.get(Calendar.DAY_OF_MONTH) + "日";
    }

    private static void queryGuardsForBadges(
            final ClassLoader cl,
            final List<BadgeTarget> badges,
            final int index,
            final Runnable onDone
    ) {
        final int maxQuery = Math.min(badges.size(), 20);
        if (index >= maxQuery) {
            onDone.run();
            return;
        }
        final BadgeTarget target = badges.get(index);
        querySingleGuardInfo(cl, target.pid, (level, guardType, accompanyDay, endTime) -> {
            if (level >= 1 && level <= 3) {
                long nowSec = System.currentTimeMillis() / 1000L;
                if (endTime <= 0L || endTime > nowSec) {
                    target.guardLevel = level;
                    target.guardType = guardType;
                    target.guardAccompanyDay = accompanyDay;
                    target.guardEndTime = endTime;
                }
            }
            log("GuardInfo [" + (index + 1) + "/" + maxQuery + "] " + target.presenterNick
                    + " (pid=" + target.pid + ") -> level=" + target.guardLevel
                    + " (" + guardLevelName(target.guardLevel) + "), type=" + guardType
                    + ", days=" + accompanyDay + ", endTime=" + endTime);
            queryGuardsForBadges(cl, badges, index + 1, onDone);
        });
    }

    private interface GuardInfoCallback {
        void onResult(int level, int guardType, int accompanyDay, long endTime);
    }

    private static void querySingleGuardInfo(ClassLoader cl, long pid, final GuardInfoCallback cb) {
        try {
            Class<?> taskClass = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.userinfo.base.api.wupfunction.WupFunction$GuardWupFunction$getGuardInfo", cl);
            if (taskClass == null) {
                cb.onResult(0, 0, 0, 0L);
                return;
            }
            ensureGenericWupHooks(taskClass);

            Constructor<?> ctor = taskClass.getDeclaredConstructor(long.class);
            ctor.setAccessible(true);
            final Object task = ctor.newInstance(pid);

            final AtomicBoolean settled = new AtomicBoolean(false);
            final Runnable timeout = () -> {
                if (settled.compareAndSet(false, true)) {
                    sPendingWupCallbacks.remove(task);
                    cb.onResult(0, 0, 0, 0L);
                }
            };
            mainHandler().postDelayed(timeout, 5000L);

            sPendingWupCallbacks.put(task, new GenericWupCallback() {
                @Override
                public void onSuccess(Object rsp) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    int lv = safeIntField(rsp, "iLevel");
                    int type = safeIntField(rsp, "iGuardType");
                    int days = safeIntField(rsp, "iAccompanyDay");
                    long endTime = safeLongField(rsp, "lEndTime");
                    cb.onResult(lv, type, days, endTime);
                }

                @Override
                public void onError(Object dataException) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    cb.onResult(0, 0, 0, 0L);
                }
            });

            XposedHelpers.callMethod(task, "execute");
        } catch (Throwable t) {
            log("querySingleGuardInfo failed for pid=" + pid + ": " + t.getMessage());
            cb.onResult(0, 0, 0, 0L);
        }
    }

    private interface CustomBadgeMapCallback {
        void onResult(Map<Long, Object> customBadgeMap);
    }

    /**
     * Loads Huya's custom fan badge catalog (定制粉丝牌, e.g. 陈子豪) from:
     * 1) Huya's live BadgeModule / UniformResourceService (GetResourceInfoRsp_Pref local cache), and
     * 2) BadgeWupFunction$GetCustomBadgeBaseInfo for URL templates (sNewFloorAstrict, sLevelUrl, sNewSFUrl).
     */
    private static void queryCustomBadgeBaseInfo(final ClassLoader cl, final CustomBadgeMapCallback cb) {
        // Wake Huya's UniformResourceService & BadgeModule so disk-cached GetResourceInfoRsp_Pref loads.
        Object uniformSvc = lookupHuyaService(cl, "com.duowan.kiwi.res.sync.IUniformResourceService");
        Object badgeComp = lookupHuyaService(cl, "com.duowan.kiwi.badge.IBadgeComponent");
        if (badgeComp != null && sBadgeModuleInstance == null) {
            try {
                Object bm = XposedHelpers.callMethod(badgeComp, "getBadgeModule");
                if (bm != null) {
                    sBadgeModuleInstance = bm;
                }
            } catch (Throwable ignored) {
            }
        }
        if (uniformSvc != null && sFansBadgeResMgrInstance == null) {
            try {
                Object fbrm = XposedHelpers.callMethod(uniformSvc, "getFansBadgeResourceManager");
                if (fbrm != null) {
                    sFansBadgeResMgrInstance = fbrm;
                }
            } catch (Throwable ignored) {
            }
        }

        final Map<Long, Object> preloaded = collectCustomBadgesFromHuyaCache(cl);
        if (!preloaded.isEmpty()) {
            log("CustomBadgeCache: hit " + preloaded.size() + " custom badges from Huya UniformResource/BadgeModule");
        }

        try {
            Object userId = getHuyaUserId(cl);
            Class<?> reqClass = XposedHelpers.findClassIfExists("com.duowan.HUYA.GetCustomBadgeBaseInfoReq", cl);
            Class<?> taskClass = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.badge.wupfunction.BadgeWupFunction$GetCustomBadgeBaseInfo", cl);
            if (userId == null || reqClass == null || taskClass == null) {
                finishCustomBadgeLookup(cl, preloaded, cb);
                return;
            }
            ensureGenericWupHooks(taskClass);

            Object req = XposedHelpers.newInstance(reqClass);
            XposedHelpers.setObjectField(req, "tId", userId);
            try {
                XposedHelpers.setIntField(req, "iUpgrades", 1);
            } catch (Throwable ignored) {
            }

            Constructor<?> ctor = taskClass.getDeclaredConstructor(reqClass);
            ctor.setAccessible(true);
            final Object task = ctor.newInstance(req);

            final AtomicBoolean settled = new AtomicBoolean(false);
            final Runnable timeout = () -> {
                if (settled.compareAndSet(false, true)) {
                    sPendingWupCallbacks.remove(task);
                    log("GetCustomBadgeBaseInfo timed out");
                    finishCustomBadgeLookup(cl, preloaded, cb);
                }
            };
            mainHandler().postDelayed(timeout, 6000L);

            sPendingWupCallbacks.put(task, new GenericWupCallback() {
                @Override
                public void onSuccess(Object rsp) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    Map<Long, Object> map = new HashMap<>(preloaded);
                    try {
                        Object customBase = XposedHelpers.getObjectField(rsp, "tCustomBadge");
                        if (customBase != null) {
                            String floorAstrict = safeStringField(customBase, "sNewFloorAstrict");
                            if (floorAstrict.isEmpty()) {
                                floorAstrict = safeStringField(customBase, "sFloorAstrict");
                            }
                            String levelUrl = safeStringField(customBase, "sLevelUrl");
                            String sfUrl = safeStringField(customBase, "sNewSFUrl");
                            if (sfUrl.isEmpty()) {
                                sfUrl = safeStringField(customBase, "sSFUrl");
                            }

                            Class<?> badgeEntityClass = XposedHelpers.findClassIfExists(
                                    "com.duowan.kiwi.badge.bean.BadgeEntity", cl);
                            if (badgeEntityClass != null) {
                                Object companion = XposedHelpers.getStaticObjectField(badgeEntityClass, "Companion");
                                if (companion != null) {
                                    String curFloor = "";
                                    try {
                                        Object cf = XposedHelpers.callMethod(companion, "getFloorAstrict");
                                        curFloor = cf instanceof String ? (String) cf : "";
                                    } catch (Throwable ignored) {
                                    }
                                    if (curFloor.isEmpty() && !floorAstrict.isEmpty()) {
                                        XposedHelpers.callMethod(companion, "setFloorAstrict", floorAstrict);
                                    }
                                    if (!levelUrl.isEmpty()) {
                                        XposedHelpers.callMethod(companion, "setLevelUrl", levelUrl);
                                    }
                                    if (!sfUrl.isEmpty()) {
                                        XposedHelpers.callMethod(companion, "setSFUrl", sfUrl);
                                    }
                                }
                            }

                            Object listObj = XposedHelpers.getObjectField(customBase, "vBadgeBase");
                            if (listObj instanceof List) {
                                for (Object item : (List<?>) listObj) {
                                    if (item == null) {
                                        continue;
                                    }
                                    long bId = safeLongField(item, "lBadgeId");
                                    if (bId > 0L) {
                                        map.put(bId, item);
                                    }
                                }
                            }
                            log("GetCustomBadgeBaseInfo: templates ready (floorTpl=" + !floorAstrict.isEmpty()
                                    + ", levelTpl=" + !levelUrl.isEmpty()
                                    + ", sfTpl=" + !sfUrl.isEmpty() + ")");
                        }
                    } catch (Throwable t) {
                        log("GetCustomBadgeBaseInfo parse error: " + t.getMessage());
                    }
                    finishCustomBadgeLookup(cl, map, cb);
                }

                @Override
                public void onError(Object dataException) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    log("GetCustomBadgeBaseInfo error: " + dataException);
                    finishCustomBadgeLookup(cl, preloaded, cb);
                }
            });

            XposedHelpers.callMethod(task, "execute");
        } catch (Throwable t) {
            log("queryCustomBadgeBaseInfo failed: " + t.getMessage());
            finishCustomBadgeLookup(cl, preloaded, cb);
        }
    }

    private static void finishCustomBadgeLookup(
            final ClassLoader cl,
            final Map<Long, Object> existing,
            final CustomBadgeMapCallback cb
    ) {
        Map<Long, Object> merged = new HashMap<>(existing);
        merged.putAll(collectCustomBadgesFromHuyaCache(cl));
        if (!merged.isEmpty()) {
            log("CustomBadgeCatalog: total available=" + merged.size());
            cb.onResult(merged);
            return;
        }
        // UniformResourceService parses GetResourceInfoRsp_Pref asynchronously on uniform-resource-thread;
        // wait briefly once on cold start if it hasn't published yet.
        mainHandler().postDelayed(() -> {
            Map<Long, Object> retry = new HashMap<>(existing);
            retry.putAll(collectCustomBadgesFromHuyaCache(cl));
            log("CustomBadgeCatalog (after async wait): total available=" + retry.size());
            cb.onResult(retry);
        }, 600L);
    }

    private static Map<Long, Object> collectCustomBadgesFromHuyaCache(ClassLoader cl) {
        Map<Long, Object> map = new HashMap<>();
        // Source A: BadgeModule.sCustomBadgeMap (Map<Long, BadgeEntity>)
        Object bm = sBadgeModuleInstance;
        if (bm != null) {
            try {
                Object rawMap = XposedHelpers.getObjectField(bm, "sCustomBadgeMap");
                if (rawMap instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) rawMap).entrySet()) {
                        if (e.getKey() instanceof Number && e.getValue() != null) {
                            long id = ((Number) e.getKey()).longValue();
                            if (id > 0L) {
                                map.put(id, e.getValue());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        // Source B: FansBadgeResourceManager.customFansBadgeItems (DependencyProperty<List<CustomFansBadgeResource>>)
        Object fbrm = sFansBadgeResMgrInstance;
        if (fbrm != null) {
            try {
                Object dp = XposedHelpers.getObjectField(fbrm, "customFansBadgeItems");
                Object listObj = extractDependencyPropertyValue(dp);
                if (listObj instanceof List) {
                    for (Object item : (List<?>) listObj) {
                        if (item == null) {
                            continue;
                        }
                        long id = safeLongField(item, "lBadgeId");
                        if (id > 0L && !map.containsKey(id)) {
                            map.put(id, item);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return map;
    }

    private static Object extractDependencyPropertyValue(Object dp) {
        if (dp == null) {
            return null;
        }
        for (String mName : new String[]{"getValue", "get", "value", "getData"}) {
            try {
                Method m = dp.getClass().getMethod(mName);
                Object v = m.invoke(dp);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        for (String fName : new String[]{"mValue", "value", "data", "a", "b"}) {
            try {
                Object v = XposedHelpers.getObjectField(dp, fName);
                if (v instanceof List) {
                    return v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void renderNativeBadges(
            ClassLoader cl,
            Context context,
            List<BadgeTarget> badges,
            Map<Long, Object> customBadgeMap
    ) {
        if (context == null || badges == null || badges.isEmpty()) {
            return;
        }
        Class<?> badgeEntityClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.badge.bean.BadgeEntity", cl);
        int rendered = 0;
        int customCount = 0;
        int limit = Math.min(badges.size(), 24);
        try (NativeBadgeRenderer.Session session = new NativeBadgeRenderer.Session(context)) {
            if (!session.hasCommonBadgeAssets()) {
                log("NativeBadgeRenderer: .commonbadge zip not cached yet, UI will use fallback pills");
            }
            for (int i = 0; i < limit; i++) {
                BadgeTarget b = badges.get(i);
                int sfIdentity = (b.fansIdentity >= 1 && b.fansIdentity <= 4) ? b.fansIdentity : 0;
                if (sfIdentity == 0 && b.superFan) {
                    sfIdentity = 1;
                }

                String pngB64 = "";
                Object customRes = (b.extinguished == 0 && b.badgeId > 0L && customBadgeMap != null)
                        ? customBadgeMap.get(b.badgeId) : null;
                if (customRes != null && badgeEntityClass != null) {
                    pngB64 = tryRenderCustomBadge(session, badgeEntityClass, b, customRes, sfIdentity);
                    if (!pngB64.isEmpty()) {
                        b.customRendered = true;
                        customCount++;
                    }
                }
                if (pngB64.isEmpty()) {
                    pngB64 = session.renderFanBadgePngBase64(
                            b.badgeName, b.level, b.extinguished == 1, sfIdentity);
                }
                if (!pngB64.isEmpty()) {
                    b.badgePngB64 = pngB64;
                    rendered++;
                }
                if (b.guardLevel >= 1 && b.guardLevel <= 3) {
                    b.guardPngB64 = session.renderGuardIconPngBase64(b.guardLevel);
                }
            }
        } catch (Throwable t) {
            logError("renderNativeBadges failed", t);
        }
        log("NativeBadgeRenderer: rendered " + rendered + "/" + limit
                + " native Huya fan badges (custom=" + customCount + ")");
    }

    private static String tryRenderCustomBadge(
            NativeBadgeRenderer.Session session,
            Class<?> badgeEntityClass,
            BadgeTarget b,
            Object customResOrEntity,
            int sfIdentity
    ) {
        try {
            Object entity = badgeEntityClass.isInstance(customResOrEntity)
                    ? customResOrEntity
                    : XposedHelpers.newInstance(badgeEntityClass, b.badgeId, customResOrEntity);
            Object urlListObj = XposedHelpers.callMethod(
                    entity,
                    "getUrlList",
                    b.badgeName,
                    b.sfType,
                    b.sfVariety,
                    b.level,
                    b.externalObj,
                    false
            );
            if (!(urlListObj instanceof List)) {
                return "";
            }
            String floorUrl = "";
            String iconUrl = "";
            String levelUrl = "";
            String vFlagUrl = "";
            for (Object pair : (List<?>) urlListObj) {
                if (pair == null) {
                    continue;
                }
                Object first = XposedHelpers.callMethod(pair, "getFirst");
                Object second = XposedHelpers.callMethod(pair, "getSecond");
                String u = first instanceof String ? (String) first : "";
                String typeName = second != null ? String.valueOf(second) : "";
                if (u.isEmpty()) {
                    continue;
                }
                if ("TYPE_FLOOR".equals(typeName)) {
                    floorUrl = u;
                } else if ("TYPE_ICON".equals(typeName)) {
                    iconUrl = u;
                } else if ("TYPE_LEVEL".equals(typeName)) {
                    levelUrl = u;
                } else if ("TYPE_VFLAG".equals(typeName)) {
                    vFlagUrl = u;
                }
            }
            log("CustomBadge [" + b.presenterNick + " / " + b.badgeName + " Lv." + b.level
                    + ", badgeId=" + b.badgeId + "]: floor=" + floorUrl
                    + ", icon=" + iconUrl + ", level=" + levelUrl + ", vFlag=" + vFlagUrl);
            if (floorUrl.isEmpty()) {
                return "";
            }
            return session.renderCustomFanBadgePngBase64(
                    b.badgeName, floorUrl, iconUrl, levelUrl, vFlagUrl, sfIdentity);
        } catch (Throwable t) {
            log("tryRenderCustomBadge failed for " + b.presenterNick + ": " + t.getMessage());
            return "";
        }
    }

    private static String guardLevelName(int guardLevel) {
        if (guardLevel == 1) {
            return "初爱守护";
        }
        if (guardLevel == 2) {
            return "超级守护";
        }
        if (guardLevel == 3) {
            return "至尊守护";
        }
        return "";
    }

    private static String serializeBadges(List<BadgeTarget> badges) {
        if (badges == null || badges.isEmpty()) {
            return "";
        }
        try {
            JSONArray arr = new JSONArray();
            for (BadgeTarget b : badges) {
                JSONObject obj = new JSONObject();
                obj.put("pid", b.pid);
                obj.put("nick", b.presenterNick != null ? b.presenterNick : "");
                if (b.avatarUrl != null && !b.avatarUrl.isEmpty()) {
                    obj.put("avatar", b.avatarUrl);
                }
                obj.put("badgeName", b.badgeName != null ? b.badgeName : "");
                obj.put("level", b.level);
                obj.put("score", b.score);
                obj.put("nextScore", b.nextScore);
                obj.put("dayScore", b.dayScore);
                obj.put("quota", b.quota);
                obj.put("quotaUsed", b.quotaUsed);
                obj.put("extinguished", b.extinguished);
                obj.put("superFan", b.superFan);
                obj.put("sfType", b.sfType);
                obj.put("sfVariety", b.sfVariety);
                obj.put("sfLevel", b.sfLevel);
                obj.put("fansIdentity", b.fansIdentity);
                obj.put("guardLevel", b.guardLevel);
                obj.put("guardName", guardLevelName(b.guardLevel));
                obj.put("guardAccompanyDay", b.guardAccompanyDay);
                if (b.faithName != null && !b.faithName.isEmpty()) {
                    obj.put("faithName", b.faithName);
                }
                if (b.badgePngB64 != null && !b.badgePngB64.isEmpty()) {
                    obj.put("badgePngB64", b.badgePngB64);
                }
                if (b.guardPngB64 != null && !b.guardPngB64.isEmpty()) {
                    obj.put("guardPngB64", b.guardPngB64);
                }
                obj.put("checkinState", b.checkinState != null ? b.checkinState : "");
                obj.put("checkinDetail", b.checkinDetail != null ? b.checkinDetail : "");
                obj.put("sfDayActive", b.sfDayActive);
                obj.put("sfDay", b.sfDay);
                obj.put("sfDayScoreTimes", b.sfDayScoreTimes);
                obj.put("sfDayQuotaTimes", b.sfDayQuotaTimes);
                obj.put("sfDayLeftSec", b.sfDayLeftSec);
                obj.put("nextSfDayEpoch", b.nextSfDayEpoch);
                arr.put(obj);
            }
            return arr.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void submitFansSign(ClassLoader cl, BadgeTarget target, final FansSignCallback cb) {
        try {
            Object userId = getHuyaUserId(cl);
            if (userId == null) {
                cb.onResult("failed", "未登录");
                return;
            }

            Class<?> reqClass = XposedHelpers.findClassIfExists("com.duowan.HUYA.FansSignReq", cl);
            Class<?> rspClass = XposedHelpers.findClassIfExists("com.duowan.HUYA.FansSignRsp", cl);
            Class<?> taskClass = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.inputbar.impl.wup.InputBarWupFunction$SetFansSign", cl);
            if (taskClass == null) {
                Class<?> outer = XposedHelpers.findClassIfExists(
                        "com.duowan.kiwi.inputbar.impl.wup.InputBarWupFunction", cl);
                if (outer != null && reqClass != null) {
                    taskClass = findSingleArgInnerWupClass(cl, outer, reqClass);
                }
            }
            if (reqClass == null || taskClass == null) {
                cb.onResult("failed", "未找到 SetFansSign WUP 类");
                return;
            }
            ensureGenericWupHooks(taskClass);

            Object req = XposedHelpers.newInstance(reqClass);
            XposedHelpers.setObjectField(req, "tId", userId);
            XposedHelpers.setLongField(req, "lPid", target.pid);

            Constructor<?> ctor = taskClass.getDeclaredConstructor(reqClass);
            ctor.setAccessible(true);
            final Object task = ctor.newInstance(req);

            final AtomicBoolean settled = new AtomicBoolean(false);
            final Runnable timeout = () -> {
                if (settled.compareAndSet(false, true)) {
                    sPendingWupCallbacks.remove(task);
                    cb.onResult("failed", "超时");
                }
            };
            mainHandler().postDelayed(timeout, RPC_TIMEOUT_MS);

            final Class<?> finalRspClass = rspClass;
            sPendingWupCallbacks.put(task, new GenericWupCallback() {
                @Override
                public void onSuccess(Object rsp) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    int code = safeIntField(rsp, "iCode");
                    String msg = safeStringField(rsp, "sMsg");
                    classifyFansSignResponse(code, msg, cb);
                }

                @Override
                public void onError(Object dataException) {
                    if (!settled.compareAndSet(false, true)) {
                        return;
                    }
                    mainHandler().removeCallbacks(timeout);
                    int code = -1;
                    String msg = "";
                    if (dataException != null) {
                        try {
                            Object resp = XposedHelpers.getObjectField(dataException, "mResponse");
                            if (resp != null && (finalRspClass == null || finalRspClass.isInstance(resp))) {
                                code = safeIntField(resp, "iCode");
                                msg = safeStringField(resp, "sMsg");
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (!msg.isEmpty()) {
                        classifyFansSignResponse(code, msg, cb);
                    } else {
                        cb.onResult("failed", String.valueOf(dataException));
                    }
                }
            });

            XposedHelpers.callMethod(task, "execute");
        } catch (Throwable t) {
            logError("submitFansSign failed for pid=" + target.pid, t);
            cb.onResult("failed", t.getMessage() != null ? t.getMessage() : "异常");
        }
    }

    private static void classifyFansSignResponse(int code, String msg, FansSignCallback cb) {
        if (code == 0) {
            cb.onResult("success", nonEmptyOr(msg, "iCode=0"));
        } else if (msg != null && (msg.contains("已") || msg.contains("重复"))) {
            cb.onResult("already", msg);
        } else {
            cb.onResult("failed", "code=" + code + (msg != null && !msg.isEmpty() ? ", msg=" + msg : ""));
        }
    }

    private static void requestBagHuliangPanel(final ClassLoader cl, String reqId, final PanelCallback cb) {
        try {
            Class<?> hyAdModuleClass = XposedHelpers.findClass("com.duowan.kiwi.ad.impl.HyAdModule", cl);
            Class<?> panelReqClass = XposedHelpers.findClass("com.duowan.HUYA.GetCommAdPannelReq", cl);
            Class<?> dataCallbackClass = XposedHelpers.findClass("com.duowan.biz.util.callback.DataCallback", cl);
            Class<?> panelTaskClass = findInnerTaskClass3(cl, hyAdModuleClass, panelReqClass, dataCallbackClass);
            if (panelTaskClass == null) {
                cb.onError("", "未找到 GetCommAdPannel WUP 请求类");
                return;
            }
            hookAdModuleWupTasks(cl);

            Object req = XposedHelpers.newInstance(panelReqClass);
            XposedHelpers.setIntField(req, "iAppId", HUYA_BAG_HULIANG_APP_ID);
            XposedHelpers.setIntField(req, "iItemId", 0);
            XposedHelpers.setIntField(req, "iSource", HUYA_BAG_SOURCE);
            XposedHelpers.setObjectField(req, "sReqId", reqId);
            Map<String, String> params = new HashMap<>();
            params.put("rso", "package");
            XposedHelpers.setObjectField(req, "mpParams", params);

            Constructor<?> ctor = panelTaskClass.getDeclaredConstructor(hyAdModuleClass, panelReqClass, dataCallbackClass);
            ctor.setAccessible(true);
            final Object task = ctor.newInstance(null, req, null);

            final AtomicBoolean settled = new AtomicBoolean(false);
            final Runnable timeout = () -> {
                if (settled.compareAndSet(false, true)) {
                    sPendingPanelCallbacks.remove(task);
                    cb.onError("", "拉取虎粮任务超时");
                }
            };
            mainHandler().postDelayed(timeout, RPC_TIMEOUT_MS);

            sPendingPanelCallbacks.put(task, new PanelCallback() {
                @Override
                public void onSuccess(Object rsp) {
                    if (settled.compareAndSet(false, true)) {
                        mainHandler().removeCallbacks(timeout);
                        cb.onSuccess(rsp);
                    }
                }

                @Override
                public void onError(String toast, String detail) {
                    if (settled.compareAndSet(false, true)) {
                        mainHandler().removeCallbacks(timeout);
                        cb.onError(toast, detail);
                    }
                }
            });

            XposedHelpers.callMethod(task, "execute");
        } catch (Throwable t) {
            logError("requestBagHuliangPanel failed", t);
            cb.onError("", "构造 GetCommAdPannel 请求失败：" + t.getMessage());
        }
    }

    private static void reportClickEvent(
            ClassLoader cl,
            String reqId,
            Map<String, String> mpExt,
            Object slotAd,
            final Runnable onDone
    ) {
        try {
            Class<?> hyAdModuleClass = XposedHelpers.findClass("com.duowan.kiwi.ad.impl.HyAdModule", cl);
            Class<?> clickReqClass = XposedHelpers.findClass("com.duowan.HUYA.OnClickCommRewardAdTaskEventReq", cl);
            Class<?> dataCallbackClass = XposedHelpers.findClass("com.duowan.biz.util.callback.DataCallback", cl);
            Class<?> clickTaskClass = findInnerTaskClass3(cl, hyAdModuleClass, clickReqClass, dataCallbackClass);
            if (clickTaskClass == null) {
                onDone.run();
                return;
            }
            Object req = XposedHelpers.newInstance(clickReqClass);
            XposedHelpers.setObjectField(req, "sReqId", reqId);
            XposedHelpers.setObjectField(req, "mpExt", mpExt);
            XposedHelpers.setObjectField(req, "tAd", slotAd);

            Constructor<?> ctor = clickTaskClass.getDeclaredConstructor(hyAdModuleClass, clickReqClass, dataCallbackClass);
            ctor.setAccessible(true);
            final Object task = ctor.newInstance(null, req, null);

            final AtomicBoolean settled = new AtomicBoolean(false);
            final Runnable doneOnce = () -> {
                if (settled.compareAndSet(false, true)) {
                    sPendingClickCallbacks.remove(task);
                    onDone.run();
                }
            };
            sPendingClickCallbacks.put(task, doneOnce);
            mainHandler().postDelayed(doneOnce, 4000L);
            XposedHelpers.callMethod(task, "execute");
        } catch (Throwable t) {
            logError("reportClickEvent failed (continuing to award)", t);
            onDone.run();
        }
    }

    private static boolean shouldUseCommonRewardApi(ClassLoader cl, Map<String, String> mpExt) {
        try {
            Class<?> lotteryAdCls = XposedHelpers.findClassIfExists("com.duowan.HYAction.LotteryAd", cl);
            if (lotteryAdCls == null) {
                return false;
            }
            Object inst = XposedHelpers.newInstance(lotteryAdCls);
            String key = safeStringField(inst, "adTaskRewardReqType");
            if (key.isEmpty()) {
                key = safeStringField(inst, "rewardreqtype");
            }
            if (!key.isEmpty() && mpExt.containsKey(key)) {
                return "2".equals(String.valueOf(mpExt.get(key)));
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean shouldReportClickEvent(ClassLoader cl, Map<String, String> mpExt) {
        try {
            Class<?> lotteryAdCls = XposedHelpers.findClassIfExists("com.duowan.HYAction.LotteryAd", cl);
            if (lotteryAdCls == null) {
                return false;
            }
            Object inst = XposedHelpers.newInstance(lotteryAdCls);
            String key = safeStringField(inst, "rewardclickcheck");
            if (!key.isEmpty() && mpExt.containsKey(key)) {
                return "1".equals(String.valueOf(mpExt.get(key)));
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String createReqId(ClassLoader cl) {
        try {
            Class<?> utils = XposedHelpers.findClassIfExists("com.duowan.kiwi.ad.utils.HyAdUtils", cl);
            if (utils != null) {
                Object id = XposedHelpers.callStaticMethod(utils, "createAdEnvReqUniqId");
                if (id instanceof String && !((String) id).isEmpty()) {
                    return (String) id;
                }
            }
        } catch (Throwable ignored) {
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String mapToJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    obj.put(e.getKey(), e.getValue());
                }
            }
            return obj.toString();
        } catch (Throwable ignored) {
            return "{}";
        }
    }

    private static boolean isExhaustedMessage(String toast) {
        if (toast == null || toast.isEmpty()) {
            return false;
        }
        return toast.contains("上限")
                || toast.contains("已领完")
                || toast.contains("已完成")
                || toast.contains("明日")
                || toast.contains("明天")
                || toast.contains("次数已用完")
                || toast.contains("无更多");
    }

    private static int parseCooldownSeconds(String toast) {
        if (toast == null || toast.isEmpty()) {
            return 0;
        }
        int total = 0;
        Matcher mMin = Pattern.compile("(\\d+)\\s*(?:分钟|分)").matcher(toast);
        if (mMin.find()) {
            try {
                total += Integer.parseInt(mMin.group(1)) * 60;
            } catch (NumberFormatException ignored) {
            }
        }
        Matcher mSec = Pattern.compile("(\\d+)\\s*秒").matcher(toast);
        if (mSec.find()) {
            try {
                total += Integer.parseInt(mSec.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        if (total == 0 && (toast.contains("频繁") || toast.contains("冷却") || toast.contains("稍后"))) {
            return 60;
        }
        return total;
    }

    private static Bundle buildResultBundle(
            String requestId,
            boolean manual,
            boolean success,
            int claimedCount,
            boolean exhausted,
            int cooldownSeconds,
            int checkinTotal,
            int checkinSuccess,
            int checkinAlready,
            int checkinFailed,
            String message
    ) {
        return buildResultBundle(
                requestId, manual, success, claimedCount, 0, exhausted, cooldownSeconds,
                checkinTotal, checkinSuccess, checkinAlready, checkinFailed,
                -1, -1, "", 0, 0, "", message
        );
    }

    private static Bundle buildResultBundle(
            String requestId,
            boolean manual,
            boolean success,
            int claimedCount,
            int welfareClaimedCount,
            boolean exhausted,
            int cooldownSeconds,
            int checkinTotal,
            int checkinSuccess,
            int checkinAlready,
            int checkinFailed,
            int freeHuliangCount,
            int superHuliangCount,
            String superHuliangName,
            int sentHuliangCount,
            int sentSuperHuliangCount,
            String badgesJson,
            String message
    ) {
        return buildResultBundle(
                requestId, manual, success, claimedCount, welfareClaimedCount, exhausted, cooldownSeconds,
                checkinTotal, checkinSuccess, checkinAlready, checkinFailed,
                freeHuliangCount, -1, -1L, "",
                superHuliangCount, superHuliangName, sentHuliangCount, sentSuperHuliangCount,
                badgesJson, message
        );
    }

    private static Bundle buildResultBundle(
            String requestId,
            boolean manual,
            boolean success,
            int claimedCount,
            int welfareClaimedCount,
            boolean exhausted,
            int cooldownSeconds,
            int checkinTotal,
            int checkinSuccess,
            int checkinAlready,
            int checkinFailed,
            int freeHuliangCount,
            int freeExpiring24h,
            long freeEarliestExpire,
            String sfDayActiveSummary,
            int superHuliangCount,
            String superHuliangName,
            int sentHuliangCount,
            int sentSuperHuliangCount,
            String badgesJson,
            String message
    ) {
        String safeMsg = message != null ? message : "";
        String safeBadges = badgesJson != null ? badgesJson : "";
        String safeSuperName = superHuliangName != null ? superHuliangName : "";
        String safeSfDaySummary = sfDayActiveSummary != null ? sfDayActiveSummary : "";
        Bundle b = new Bundle();
        b.putBoolean(AutoClaimProtocol.EXTRA_HANDLED, true);
        b.putString(AutoClaimProtocol.EXTRA_REQUEST_ID, requestId);
        b.putBoolean(AutoClaimProtocol.EXTRA_MANUAL, manual);
        b.putBoolean(AutoClaimProtocol.EXTRA_SUCCESS, success);
        b.putInt(AutoClaimProtocol.EXTRA_CLAIMED_COUNT, claimedCount);
        b.putInt(AutoClaimProtocol.EXTRA_WELFARE_CLAIMED_COUNT, welfareClaimedCount);
        b.putBoolean(AutoClaimProtocol.EXTRA_EXHAUSTED, exhausted);
        b.putInt(AutoClaimProtocol.EXTRA_COOLDOWN_SECONDS, cooldownSeconds);
        b.putInt(AutoClaimProtocol.EXTRA_CHECKIN_TOTAL, checkinTotal);
        b.putInt(AutoClaimProtocol.EXTRA_CHECKIN_SUCCESS, checkinSuccess);
        b.putInt(AutoClaimProtocol.EXTRA_CHECKIN_ALREADY, checkinAlready);
        b.putInt(AutoClaimProtocol.EXTRA_CHECKIN_FAILED, checkinFailed);
        b.putInt(AutoClaimProtocol.EXTRA_FREE_HULIANG_COUNT, freeHuliangCount);
        b.putInt(AutoClaimProtocol.EXTRA_FREE_EXPIRING_24H, freeExpiring24h);
        b.putLong(AutoClaimProtocol.EXTRA_FREE_EARLIEST_EXPIRE, freeEarliestExpire);
        b.putString(AutoClaimProtocol.EXTRA_SF_DAY_ACTIVE_SUMMARY, safeSfDaySummary);
        b.putInt(AutoClaimProtocol.EXTRA_SUPER_HULIANG_COUNT, superHuliangCount);
        b.putString(AutoClaimProtocol.EXTRA_SUPER_HULIANG_NAME, safeSuperName);
        b.putInt(AutoClaimProtocol.EXTRA_SENT_HULIANG_COUNT, sentHuliangCount);
        b.putInt(AutoClaimProtocol.EXTRA_SENT_SUPER_HULIANG_COUNT, sentSuperHuliangCount);
        b.putString(AutoClaimProtocol.EXTRA_BADGES_JSON, safeBadges);
        b.putString(AutoClaimProtocol.EXTRA_MESSAGE, safeMsg);
        try {
            JSONObject obj = new JSONObject();
            obj.put(AutoClaimProtocol.EXTRA_HANDLED, true);
            obj.put(AutoClaimProtocol.EXTRA_REQUEST_ID, requestId);
            obj.put(AutoClaimProtocol.EXTRA_MANUAL, manual);
            obj.put(AutoClaimProtocol.EXTRA_SUCCESS, success);
            obj.put(AutoClaimProtocol.EXTRA_CLAIMED_COUNT, claimedCount);
            obj.put(AutoClaimProtocol.EXTRA_WELFARE_CLAIMED_COUNT, welfareClaimedCount);
            obj.put(AutoClaimProtocol.EXTRA_EXHAUSTED, exhausted);
            obj.put(AutoClaimProtocol.EXTRA_COOLDOWN_SECONDS, cooldownSeconds);
            obj.put(AutoClaimProtocol.EXTRA_CHECKIN_TOTAL, checkinTotal);
            obj.put(AutoClaimProtocol.EXTRA_CHECKIN_SUCCESS, checkinSuccess);
            obj.put(AutoClaimProtocol.EXTRA_CHECKIN_ALREADY, checkinAlready);
            obj.put(AutoClaimProtocol.EXTRA_CHECKIN_FAILED, checkinFailed);
            obj.put(AutoClaimProtocol.EXTRA_FREE_HULIANG_COUNT, freeHuliangCount);
            obj.put(AutoClaimProtocol.EXTRA_FREE_EXPIRING_24H, freeExpiring24h);
            obj.put(AutoClaimProtocol.EXTRA_FREE_EARLIEST_EXPIRE, freeEarliestExpire);
            obj.put(AutoClaimProtocol.EXTRA_SF_DAY_ACTIVE_SUMMARY, safeSfDaySummary);
            obj.put(AutoClaimProtocol.EXTRA_SUPER_HULIANG_COUNT, superHuliangCount);
            obj.put(AutoClaimProtocol.EXTRA_SUPER_HULIANG_NAME, safeSuperName);
            obj.put(AutoClaimProtocol.EXTRA_SENT_HULIANG_COUNT, sentHuliangCount);
            obj.put(AutoClaimProtocol.EXTRA_SENT_SUPER_HULIANG_COUNT, sentSuperHuliangCount);
            obj.put(AutoClaimProtocol.EXTRA_BADGES_JSON, safeBadges);
            obj.put(AutoClaimProtocol.EXTRA_MESSAGE, safeMsg);
            String b64 = Base64.encodeToString(
                    obj.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.NO_WRAP
            );
            b.putString(AutoClaimProtocol.EXTRA_RESULT_JSON_B64, b64);
        } catch (Throwable ignored) {
        }
        return b;
    }

    private static Object safeObjectField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        try {
            return XposedHelpers.getObjectField(target, fieldName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean safeBoolField(Object target, String fieldName) {
        if (target == null) {
            return false;
        }
        try {
            return XposedHelpers.getBooleanField(target, fieldName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static long safeLongOrIntField(Object target, String fieldName) {
        if (target == null) {
            return 0L;
        }
        try {
            return XposedHelpers.getLongField(target, fieldName);
        } catch (Throwable ignored) {
        }
        try {
            return XposedHelpers.getIntField(target, fieldName);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long readUnsignedExpireTimeSec(Object target, String fieldName) {
        if (target == null) {
            return 0L;
        }
        long rawIntUnsigned = safeIntField(target, fieldName) & 0xFFFFFFFFL;
        if (rawIntUnsigned > 0L) {
            return rawIntUnsigned;
        }
        long rawLong = safeLongField(target, fieldName);
        return rawLong > 0L ? rawLong : 0L;
    }

    private static String safeStringField(Object target, String fieldName) {
        try {
            Object val = XposedHelpers.getObjectField(target, fieldName);
            return val instanceof String ? (String) val : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int safeIntField(Object target, String fieldName) {
        try {
            return XposedHelpers.getIntField(target, fieldName);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static long safeLongField(Object target, String fieldName) {
        try {
            return XposedHelpers.getLongField(target, fieldName);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static String nonEmptyOr(String val, String fallback) {
        return (val != null && !val.trim().isEmpty()) ? val.trim() : fallback;
    }
}
