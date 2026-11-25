package com.huya.pure;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.json.JSONObject;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookEntry implements IXposedHookLoadPackage {

    private static final String TAG = "HuyaPure";
    private static final String TARGET_PACKAGE = "com.duowan.kiwi";
    private static final String MODULE_PACKAGE = "com.huya.pure";
    private static volatile Handler sMainHandler;
    private static final Set<ClassLoader> sHookedClassLoaders =
            Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<ClassLoader, Boolean>()));

    private static Handler mainHandler() {
        Handler h = sMainHandler;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            sMainHandler = h;
        }
        return h;
    }

    private static void log(String msg) {
        Log.i(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    private static void debugLog(String msg) {
        if (ModulePrefs.isDebugLogEnabled()) {
            Log.d(TAG, msg);
            XposedBridge.log("[" + TAG + "] " + msg);
        }
    }

    private static void logError(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        XposedBridge.log("[" + TAG + "] ERROR: " + msg);
        XposedBridge.log(t);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (MODULE_PACKAGE.equals(lpparam.packageName)) {
            hookSelfStatus(lpparam.classLoader);
            return;
        }
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        if (!TARGET_PACKAGE.equals(lpparam.processName)) {
            return;
        }

        log("Loaded in " + lpparam.packageName + " (process=" + lpparam.processName
                + ", " + ModulePrefs.summaryInHook() + ")");
        installHooksForClassLoader(lpparam.classLoader, "handleLoadPackage");

        // Huya uses Tinker hotfix (tinker_classN.apk) which may swap Application's ClassLoader
        // during attachBaseContext / Application creation. Watch lifecycle hooks so we re-hook
        // any newly replaced ClassLoader automatically.
        XC_MethodHook watchTinkerClassLoader = new XC_MethodHook() {
            private void check(MethodHookParam param) {
                try {
                    if (param.thisObject instanceof Context) {
                        ClassLoader c = ((Context) param.thisObject).getClassLoader();
                        if (c != null) {
                            installHooksForClassLoader(c, param.method.getName());
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Context) {
                        ClassLoader c = ((Context) param.args[0]).getClassLoader();
                        if (c != null) {
                            installHooksForClassLoader(c, param.method.getName());
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                check(param);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                check(param);
            }
        };
        try {
            XposedBridge.hookAllMethods(Application.class, "attach", watchTinkerClassLoader);
            XposedBridge.hookAllMethods(Application.class, "attachBaseContext", watchTinkerClassLoader);
            XposedBridge.hookAllMethods(Instrumentation.class, "callApplicationOnCreate", watchTinkerClassLoader);
        } catch (Throwable t) {
            logError("hook Tinker ClassLoader watcher failed", t);
        }
    }

    private void installHooksForClassLoader(ClassLoader cl, String source) {
        if (cl == null) {
            return;
        }
        synchronized (sHookedClassLoaders) {
            if (!sHookedClassLoaders.add(cl)) {
                return;
            }
        }
        log("Installing hooks on ClassLoader=" + cl.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(cl)) + " (via " + source + ")");

        ModulePrefs.reloadForHook();
        boolean enableRewardAd = ModulePrefs.isRewardAdSkipEnabled();
        boolean enableSplashAd = ModulePrefs.isSplashAdBlockEnabled();
        boolean enableLiveRoomAd = ModulePrefs.isLiveRoomAdBlockEnabled();
        boolean enableBgP2P = ModulePrefs.isBgP2PBlockEnabled();
        boolean enableLiveP2P = ModulePrefs.isLiveP2PBlockEnabled();

        if (enableRewardAd) {
            hookPangleRewardAd(cl);
            hookAmsRewardAd(cl);
            hookKaVideoAdDialog(cl);
        }
        if (ModulePrefs.isDebugLogEnabled()) {
            hookAwardManagerLogging(cl);
        }
        if (enableSplashAd) {
            hookSplashAds(cl);
        }
        if (enableLiveRoomAd) {
            hookLiveRoomAds(cl);
        }
        if (enableBgP2P || enableLiveP2P) {
            hookDisableP2PUpload(cl, enableBgP2P, enableLiveP2P);
        }
    }

    private void hookSelfStatus(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.huya.pure.MainActivity",
                    cl,
                    "isModuleActive",
                    XC_MethodReplacement.returnConstant(true)
            );
        } catch (Throwable t) {
            logError("hookSelfStatus failed", t);
        }
    }

    /**
     * Hook 1: CSJ / Pangle (穿山甲激励视频 & 商城激励视频)
     * Intercepts HyAdManagerInner.loadPangleRewardAd & loadPangleRewardShopMallAd so the ad
     * never downloads/displays, and immediately drives IPangleRewardAdCallBack through
     * onShowRewardVideo -> onAdPlay -> onVideoRewardVerify(true) -> onAdClose.
     */
    private void hookPangleRewardAd(final ClassLoader cl) {
        Class<?> managerClass = XposedHelpers.findClassIfExists(
                "com.huya.adbusiness.toolbox.HyAdManagerInner", cl);
        if (managerClass == null) {
            log("HyAdManagerInner not found in this process, skipping Pangle hook");
            return;
        }

        XC_MethodHook pangleHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 3) {
                    return;
                }
                final Object callback = param.args[2];
                if (callback == null) {
                    return;
                }
                final String methodName = param.method.getName();
                log("Intercepted " + methodName + " -> skipping Pangle ad & triggering reward");
                param.setResult(null);

                mainHandler().post(() -> {
                    try {
                        Object noOpFn0 = createFunction0Proxy(cl, () -> {
                            log("Pangle noOp showFn invoked (" + methodName + ")");
                        });
                        XposedHelpers.callMethod(callback, "onShowRewardVideo", noOpFn0);
                    } catch (Throwable t) {
                        logError("Pangle onShowRewardVideo failed", t);
                    }
                });

                mainHandler().postDelayed(() -> {
                    try {
                        XposedHelpers.callMethod(callback, "onAdPlay");
                        XposedHelpers.callMethod(callback, "onVideoRewardVerify", true);
                        log("Pangle onAdPlay + onVideoRewardVerify(true) dispatched");
                    } catch (Throwable t) {
                        logError("Pangle onAdPlay/onVideoRewardVerify failed", t);
                    }
                }, 150L);

                mainHandler().postDelayed(() -> {
                    try {
                        XposedHelpers.callMethod(callback, "onAdClose");
                        log("Pangle onAdClose dispatched");
                    } catch (Throwable t) {
                        logError("Pangle onAdClose failed", t);
                    }
                }, 350L);
            }
        };

        try {
            XposedBridge.hookAllMethods(managerClass, "loadPangleRewardAd", pangleHook);
            XposedBridge.hookAllMethods(managerClass, "loadPangleRewardShopMallAd", pangleHook);
            log("Hooked HyAdManagerInner Pangle reward ad loaders");
        } catch (Throwable t) {
            logError("hookPangleRewardAd failed", t);
        }
    }

    /**
     * Hook 2: AMS / GDT (腾讯优量汇 / TangramRewardAD)
     * Lets TangramRewardAD load metadata (so Huya receives real passThroughData & eCPM for
     * bidding and server award validation), then intercepts the show Function0 passed to
     * IAmsAdRewardADListener.onADLoad so TangramRewardAD.showAD() is never shown, and drives
     * onADShow -> onADExpose -> onReward(RewardResult) -> onADComplete -> onADClose.
     */
    private void hookAmsRewardAd(final ClassLoader cl) {
        Class<?> managerClass = XposedHelpers.findClassIfExists(
                "com.huya.adbusiness.toolbox.HyAdManagerInner", cl);
        final Class<?> listenerInterface = XposedHelpers.findClassIfExists(
                "com.huya.adbusiness.ams.IAmsAdRewardADListener", cl);
        if (managerClass == null || listenerInterface == null) {
            log("AMS classes not found in this process, skipping AMS hook");
            return;
        }

        try {
            XposedBridge.hookAllMethods(managerClass, "loadAmsRewardAd", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null) {
                        return;
                    }
                    int listenerIdx = -1;
                    for (int i = 0; i < param.args.length; i++) {
                        if (param.args[i] != null && listenerInterface.isInstance(param.args[i])) {
                            listenerIdx = i;
                            break;
                        }
                    }
                    if (listenerIdx < 0) {
                        return;
                    }

                    final Object realListener = param.args[listenerIdx];
                    log("Wrapping IAmsAdRewardADListener in loadAmsRewardAd");

                    Object wrappedListener = Proxy.newProxyInstance(
                            cl,
                            new Class<?>[]{listenerInterface},
                            new InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                    if ("onADLoad".equals(method.getName()) && args != null && args.length == 4) {
                                        log("AMS onADLoad received (ecpm=" + args[1] + ", aid=" + args[2] + ") -> replacing showFn");
                                        Object[] modifiedArgs = new Object[4];
                                        System.arraycopy(args, 0, modifiedArgs, 0, 4);
                                        modifiedArgs[3] = createFunction0Proxy(cl, () -> {
                                            log("AMS fake showFn invoked -> skipping TangramRewardAD.showAD() & triggering reward");
                                            dispatchAmsRewardSequence(cl, realListener);
                                        });
                                        return method.invoke(realListener, modifiedArgs);
                                    }
                                    return method.invoke(realListener, args);
                                }
                            }
                    );

                    param.args[listenerIdx] = wrappedListener;
                }
            });
            log("Hooked HyAdManagerInner.loadAmsRewardAd");
        } catch (Throwable t) {
            logError("hookAmsRewardAd failed", t);
        }
    }

    private void dispatchAmsRewardSequence(final ClassLoader cl, final Object realListener) {
        mainHandler().post(() -> {
            try {
                XposedHelpers.callMethod(realListener, "onADShow");
                XposedHelpers.callMethod(realListener, "onADExpose");
                log("AMS onADShow + onADExpose dispatched");
            } catch (Throwable t) {
                logError("AMS onADShow/onADExpose failed", t);
            }
        });

        mainHandler().postDelayed(() -> {
            try {
                Object rewardResult = createFakeRewardResult(cl);
                XposedHelpers.callMethod(realListener, "onReward", rewardResult);
                XposedHelpers.callMethod(realListener, "onADComplete");
                log("AMS onReward + onADComplete dispatched");
            } catch (Throwable t) {
                logError("AMS onReward/onADComplete failed", t);
            }
        }, 150L);

        mainHandler().postDelayed(() -> {
            try {
                XposedHelpers.callMethod(realListener, "onADClose");
                log("AMS onADClose dispatched");
            } catch (Throwable t) {
                logError("AMS onADClose failed", t);
            }
        }, 350L);
    }

    private Object createFakeRewardResult(ClassLoader cl) throws Throwable {
        Class<?> rewardResultClass = XposedHelpers.findClass("com.qq.e.tg.rewardAD.RewardResult", cl);
        Object rewardResult = XposedHelpers.newInstance(rewardResultClass);
        try {
            XposedHelpers.callMethod(rewardResult, "a", true);
        } catch (Throwable ignored) {
            try {
                XposedHelpers.setBooleanField(rewardResult, "a", true);
            } catch (Throwable ignored2) {
            }
        }
        try {
            XposedHelpers.callMethod(rewardResult, "setPassthrough", "");
        } catch (Throwable ignored) {
        }
        return rewardResult;
    }

    /**
     * Hook 3: KA (虎牙自建激励视频 VideoAdPlayFragmentDialog)
     * Hides the video ad container and immediately completes & dismisses the dialog so the
     * reward callback & server claim fire automatically.
     */
    private void hookKaVideoAdDialog(final ClassLoader cl) {
        Class<?> dialogClass = XposedHelpers.findClassIfExists(
                "com.duowan.biz.videoad.impl.view.VideoAdPlayFragmentDialog", cl);
        if (dialogClass == null) {
            log("VideoAdPlayFragmentDialog not found in this process, skipping KA hook");
            return;
        }

        try {
            XposedBridge.hookAllMethods(dialogClass, "showAd", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Object dialog = param.thisObject;
                    log("Intercepted VideoAdPlayFragmentDialog.showAd -> auto-completing KA ad");
                    try {
                        Object container = XposedHelpers.getObjectField(dialog, "mAdContainer");
                        if (container instanceof View) {
                            ((View) container).setVisibility(View.INVISIBLE);
                        }
                    } catch (Throwable ignored) {
                    }

                    mainHandler().postDelayed(() -> {
                        try {
                            XposedHelpers.callMethod(dialog, "setPlayFinish", true);
                            XposedHelpers.callMethod(dialog, "onAdPlayComplete");
                            log("KA VideoAdPlayFragmentDialog onAdPlayComplete dispatched");
                        } catch (Throwable t) {
                            logError("KA onAdPlayComplete failed", t);
                        }
                    }, 150L);

                    mainHandler().postDelayed(() -> {
                        try {
                            XposedHelpers.callMethod(dialog, "dismissAd");
                            log("KA VideoAdPlayFragmentDialog dismissAd dispatched");
                        } catch (Throwable t) {
                            logError("KA dismissAd failed", t);
                        }
                    }, 350L);
                }
            });
            log("Hooked VideoAdPlayFragmentDialog.showAd");
        } catch (Throwable t) {
            logError("hookKaVideoAdDialog failed", t);
        }
    }

    /**
     * Hook 4: Diagnostic logging for Huya's reward claim responses so we can confirm
     * AwardCommAdTask / AwardCommRewardAdTask / AwardADTask succeed on Huya's backend.
     */
    private void hookAwardManagerLogging(final ClassLoader cl) {
        Class<?> awardManagerClass = XposedHelpers.findClassIfExists(
                "com.duowan.biz.videoad.impl.HyLotteryAdAwardManager", cl);
        if (awardManagerClass == null) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(awardManagerClass, "awardAdTaskIfNeed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("awardAdTaskIfNeed called: scene=" + param.args[0]
                            + ", businessId=" + param.args[4]
                            + ", taskId=" + param.args[5]);
                }
            });

            XposedBridge.hookAllMethods(awardManagerClass, "onAwardCommonAdTask", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("onAwardCommonAdTask called: taskId=" + param.args[0]
                            + ", reqId=" + param.args[4]);
                    wrapRewardListenerForLogging(cl, param.args, 5, "AwardCommAdTask");
                }
            });

            XposedBridge.hookAllMethods(awardManagerClass, "onAwardCommonRewardAdTask", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    log("onAwardCommonRewardAdTask called: taskId=" + param.args[0]
                            + ", reqId=" + param.args[4]);
                    wrapRewardListenerForLogging(cl, param.args, 5, "AwardCommRewardAdTask");
                }
            });
        } catch (Throwable t) {
            logError("hookAwardManagerLogging failed", t);
        }
    }

    private void wrapRewardListenerForLogging(ClassLoader cl, Object[] args, int idx, final String tag) {
        if (args == null || args.length <= idx || args[idx] == null) {
            return;
        }
        try {
            Class<?> listenerInterface = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.ad.api.IHyLotteryAdHelper$HyRewardAdListener", cl);
            if (listenerInterface == null || !listenerInterface.isInstance(args[idx])) {
                return;
            }
            final Object orig = args[idx];
            args[idx] = Proxy.newProxyInstance(cl, new Class<?>[]{listenerInterface}, (proxy, method, mArgs) -> {
                if ("onRewarded".equals(method.getName()) && mArgs != null && mArgs.length >= 3) {
                    log(tag + " -> onRewarded(success=" + mArgs[0]
                            + ", toast=" + mArgs[2]
                            + ", mpExt=" + (mArgs[1] instanceof Map ? mArgs[1] : "null") + ")");
                }
                return method.invoke(orig, mArgs);
            });
        } catch (Throwable t) {
            logError("wrapRewardListenerForLogging failed", t);
        }
    }

    /**
     * Hook 5: Splash Ads (开屏广告：冷启动开屏、热启动开屏、延迟开屏、开屏联动横幅)
     */
    private void hookSplashAds(final ClassLoader cl) {
        int hookedCount = 0;

        // 5.1 AdRouter: shouldShowAdSplash -> false, isNeedShowAdSplash & tryStartAdSplashActivity -> 6 (NO_AD)
        Class<?> adRouterClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.adsplash.controller.AdRouter", cl);
        if (adRouterClass != null) {
            try {
                XposedBridge.hookAllMethods(adRouterClass, "shouldShowAdSplash", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        log("Blocked splash ad via AdRouter.shouldShowAdSplash -> false");
                        return false;
                    }
                });
                XC_MethodReplacement returnNoSplashCode = new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        log("Blocked splash ad via AdRouter." + param.method.getName() + " -> 6");
                        return 6;
                    }
                };
                XposedBridge.hookAllMethods(adRouterClass, "isNeedShowAdSplash", returnNoSplashCode);
                XposedBridge.hookAllMethods(adRouterClass, "getNeedShowAdSplashResult", returnNoSplashCode);
                XposedBridge.hookAllMethods(adRouterClass, "tryStartAdSplashActivity", returnNoSplashCode);
                hookedCount++;
            } catch (Throwable t) {
                logError("hook AdRouter failed", t);
            }
        }

        // 5.1b SplashShowStrategy.isNeedShow -> false
        Class<?> showStrategyClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.adsplash.view.SplashShowStrategy", cl);
        if (showStrategyClass != null) {
            try {
                XposedBridge.hookAllMethods(showStrategyClass, "isNeedShow", XC_MethodReplacement.returnConstant(false));
                hookedCount++;
            } catch (Throwable t) {
                logError("hook SplashShowStrategy.isNeedShow failed", t);
            }
        }

        // 5.2 HyAdSplashManager hot-start / delayed / resource preload guards
        Class<?> splashManagerClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.adsplash.controller.HyAdSplashManager", cl);
        if (splashManagerClass != null) {
            try {
                XC_MethodReplacement blockSplashAction = new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        log("Blocked HyAdSplashManager." + param.method.getName());
                        return null;
                    }
                };
                XposedBridge.hookAllMethods(splashManagerClass, "tryStartHotStartSplashAd", blockSplashAction);
                XposedBridge.hookAllMethods(splashManagerClass, "tryShowDelayedAdIfNeeded", blockSplashAction);
                XposedBridge.hookAllMethods(splashManagerClass, "fetchBusinessSplashResourceFromLaunch", blockSplashAction);
                hookedCount++;
            } catch (Throwable t) {
                logError("hook HyAdSplashManager failed", t);
            }
        }

        // 5.3 AdSplashModule.hasBannerAd -> false
        Class<?> splashModuleClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.adsplash.impl.AdSplashModule", cl);
        if (splashModuleClass != null) {
            try {
                XposedBridge.hookAllMethods(splashModuleClass, "hasBannerAd", XC_MethodReplacement.returnConstant(false));
                hookedCount++;
            } catch (Throwable t) {
                logError("hook AdSplashModule.hasBannerAd failed", t);
            }
        }

        // 5.4 Fallback: if AdSplashFragment ever opens, skip straight to homepage
        Class<?> splashFragmentClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.adsplash.view.AdSplashFragment", cl);
        if (splashFragmentClass != null) {
            try {
                XC_MethodReplacement skipToHomepage = new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        log("Fallback blocked AdSplashFragment." + param.method.getName() + " -> doStartHomepage()");
                        try {
                            XposedHelpers.callMethod(param.thisObject, "doStartHomepage");
                        } catch (Throwable t) {
                            logError("AdSplashFragment.doStartHomepage failed", t);
                        }
                        return null;
                    }
                };
                XposedBridge.hookAllMethods(splashFragmentClass, "displayAmsAdNew", skipToHomepage);
                XposedBridge.hookAllMethods(splashFragmentClass, "displayCsjAd", skipToHomepage);
                XposedBridge.hookAllMethods(splashFragmentClass, "displayHuyaAd", skipToHomepage);
                hookedCount++;
            } catch (Throwable t) {
                logError("hook AdSplashFragment failed", t);
            }
        }

        log("Splash ad hooks installed (" + hookedCount + " components)");
    }

    /**
     * Hook 6: Live-Room Ads (直播间广告：角标浮窗广告、横幅广告、商业插件/小风车广告、
     * 主播推广广告、看播赏金RTB广告、商业弹幕广告、下播视频广告、超级浮窗广告).
     * Strictly isolated from HyVideoAdModule / HyLotteryAdAwardManager so 「看广告赚虎粮」 keeps working.
     */
    private void hookLiveRoomAds(final ClassLoader cl) {
        int hookedCount = 0;

        // 6.1 Master switch: HyAdModule.isNeedShieldAdInLivingRoom() -> true
        // Suppresses AdxModule, AdBannerModule, RewardAdModule, and RevenueModule commercial barrage ads.
        Class<?> hyAdModuleClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.ad.impl.HyAdModule", cl);
        if (hyAdModuleClass != null) {
            try {
                XposedBridge.hookAllMethods(hyAdModuleClass, "isNeedShieldAdInLivingRoom", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        return true;
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook HyAdModule.isNeedShieldAdInLivingRoom failed", t);
            }
        }

        // 6.2 RevenueModule: live-room floating corner ads, big float ads, video float ads, super float ads,
        // recommend float ads, and commercial barrage notices (uses Huya's built-in "super noble without ads" path).
        Class<?> revenueModuleClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.livead.impl.adfloat.module.RevenueModule", cl);
        if (revenueModuleClass != null) {
            try {
                XposedBridge.hookAllMethods(revenueModuleClass, "isAdSwitchOpen", XC_MethodReplacement.returnConstant(false));
                XposedBridge.hookAllMethods(revenueModuleClass, "isAdDisable", XC_MethodReplacement.returnConstant(true));
                XposedBridge.hookAllMethods(revenueModuleClass, "setAdEnable", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0) {
                            param.args[0] = Boolean.FALSE;
                        }
                    }
                });
                XposedBridge.hookAllMethods(revenueModuleClass, "tryQueryAdInfo", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // Force mAdEnable=false before the (int, long, String, boolean) query so Huya
                        // takes its native "super noble without ads" branch (calls tryShowInterceptPopup(true)).
                        if (param.args != null && param.args.length == 4 && param.args[0] instanceof Integer) {
                            try {
                                Object adEnableProp = XposedHelpers.getObjectField(param.thisObject, "mAdEnable");
                                if (adEnableProp != null) {
                                    XposedHelpers.callMethod(adEnableProp, "set", Boolean.FALSE);
                                }
                            } catch (Throwable ignored) {
                            }
                            log("Blocked RevenueModule.tryQueryAdInfo (live-room float/video/super ad)");
                        }
                    }
                });
                XC_MethodReplacement blockRevenueAd = new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        log("Blocked RevenueModule." + param.method.getName());
                        return null;
                    }
                };
                XposedBridge.hookAllMethods(revenueModuleClass, "displayAdByMethod", blockRevenueAd);
                XposedBridge.hookAllMethods(revenueModuleClass, "tryRequestRecommendAd", blockRevenueAd);
                XposedBridge.hookAllMethods(revenueModuleClass, "handleAdRoomDmNotice", blockRevenueAd);
                hookedCount++;
            } catch (Throwable t) {
                logError("hook RevenueModule failed", t);
            }
        }

        // 6.3 PresenterAdModule: streamer / anchor ad cards & push ads
        Class<?> presenterAdClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.livead.impl.adplugin.module.PresenterAdModule", cl);
        if (presenterAdClass != null) {
            try {
                XC_MethodReplacement blockPresenterAd = new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        log("Blocked PresenterAdModule." + param.method.getName());
                        return null;
                    }
                };
                XposedBridge.hookAllMethods(presenterAdClass, "queryAdList", blockPresenterAd);
                XposedBridge.hookAllMethods(presenterAdClass, "onAdPushReceived", blockPresenterAd);
                XposedBridge.hookAllMethods(presenterAdClass, "showPushAd", blockPresenterAd);
                XposedBridge.hookAllMethods(presenterAdClass, "doSendPresentAdShowEvent", blockPresenterAd);
                hookedCount++;
            } catch (Throwable t) {
                logError("hook PresenterAdModule failed", t);
            }
        }

        // 6.4 AdxModule: live-room commercial plugin ads, push ads, and bullet ad scenes
        Class<?> adxModuleClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.livead.impl.adplugin.module.AdxModule", cl);
        if (adxModuleClass != null) {
            try {
                XC_MethodReplacement blockAdx = new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        log("Blocked AdxModule." + param.method.getName());
                        return null;
                    }
                };
                XposedBridge.hookAllMethods(adxModuleClass, "showCommercialAd", blockAdx);
                XposedBridge.hookAllMethods(adxModuleClass, "tryShowInitCommercialAd", blockAdx);
                XposedBridge.hookAllMethods(adxModuleClass, "showAd", blockAdx);
                XposedBridge.hookAllMethods(adxModuleClass, "sendAdSceneEvent", blockAdx);
                XposedBridge.hookAllMethods(adxModuleClass, "onCastPush", blockAdx);
                hookedCount++;
            } catch (Throwable t) {
                logError("hook AdxModule failed", t);
            }
        }

        // 6.5 RewardAdModule: live-room RTB bounty/bottom commercial ads (distinct from HyVideoAdModule)
        Class<?> rewardAdModuleClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.livead.impl.adplugin.module.RewardAdModule", cl);
        if (rewardAdModuleClass != null) {
            try {
                XC_MethodReplacement blockRewardRtb = new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        log("Blocked RewardAdModule." + param.method.getName());
                        return null;
                    }
                };
                XposedBridge.hookAllMethods(rewardAdModuleClass, "queryRtb", blockRewardRtb);
                XposedBridge.hookAllMethods(rewardAdModuleClass, "startPollQueryRtb", blockRewardRtb);
                XposedBridge.hookAllMethods(rewardAdModuleClass, "onGetPushRtbInstruction", blockRewardRtb);
                hookedCount++;
            } catch (Throwable t) {
                logError("hook RewardAdModule failed", t);
            }
        }

        // 6.6 AdBannerModule: live-room banner ads
        Class<?> adBannerClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.livead.impl.adbanner.module.AdBannerModule", cl);
        if (adBannerClass != null) {
            try {
                XC_MethodReplacement blockBanner = new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        log("Blocked AdBannerModule." + param.method.getName());
                        return null;
                    }
                };
                XposedBridge.hookAllMethods(adBannerClass, "requestShowAd", blockBanner);
                XposedBridge.hookAllMethods(adBannerClass, "getMobileBanner", blockBanner);
                hookedCount++;
            } catch (Throwable t) {
                logError("hook AdBannerModule failed", t);
            }
        }

        // 6.7 LiveRoomFloatSuperAd: super animated floating ad overlay in live room
        Class<?> superAdClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.ad.ui.splashlivestreamad.LiveRoomFloatSuperAd", cl);
        if (superAdClass != null) {
            try {
                XposedBridge.hookAllMethods(superAdClass, "start", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        log("Blocked LiveRoomFloatSuperAd.start");
                        if (param.thisObject instanceof View) {
                            ((View) param.thisObject).setVisibility(View.GONE);
                        }
                        try {
                            Object playEndInvoke = XposedHelpers.getObjectField(param.thisObject, "playEndInvoke");
                            if (playEndInvoke != null) {
                                XposedHelpers.callMethod(playEndInvoke, "invoke", Boolean.TRUE);
                            }
                        } catch (Throwable ignored) {
                        }
                        return null;
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook LiveRoomFloatSuperAd failed", t);
            }
        }

        log("Live-room ad hooks installed (" + hookedCount + " components)");
    }

    private static final ThreadLocal<Boolean> sInSelfParamsPush = new ThreadLocal<>();
    private static final Map<String, String> sLastKnownServerParams = new ConcurrentHashMap<>();

    private void loadCachedServerParamsIfEmpty(Class<?> signalClientClass, boolean blockBgP2P, boolean blockLiveP2P) {
        if (!sLastKnownServerParams.isEmpty() || signalClientClass == null) {
            return;
        }
        try {
            Object ctxObj = XposedHelpers.getStaticObjectField(signalClientClass, "mContext");
            if (!(ctxObj instanceof Context)) {
                return;
            }
            android.content.SharedPreferences prefs = ((Context) ctxObj)
                    .getSharedPreferences("paramsprefs-hysdk", Context.MODE_PRIVATE);
            if (prefs == null) {
                return;
            }
            Map<String, ?> all = prefs.getAll();
            if (all == null) {
                return;
            }
            for (Object val : all.values()) {
                if (val instanceof String) {
                    String s = ((String) val).trim();
                    if (s.startsWith("{") && s.endsWith("}")) {
                        JSONObject obj = new JSONObject(s);
                        Iterator<String> it = obj.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            String v = obj.optString(k, "");
                            sLastKnownServerParams.put(k, sanitizeDynamicP2PValue(k, v, blockBgP2P, blockLiveP2P));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void pushNativeDisableP2PParams(
            Class<?> signalClientClass,
            String trigger,
            boolean blockBgP2P,
            boolean blockLiveP2P
    ) {
        if (signalClientClass == null || Boolean.TRUE.equals(sInSelfParamsPush.get())) {
            return;
        }
        sInSelfParamsPush.set(Boolean.TRUE);
        try {
            loadCachedServerParamsIfEmpty(signalClientClass, blockBgP2P, blockLiveP2P);
            HashMap<String, String> disableMap = new HashMap<>(sLastKnownServerParams);
            if (blockBgP2P) {
                injectDisableBgP2PParams(disableMap);
            }
            if (blockLiveP2P) {
                injectDisableLiveP2PParams(disableMap);
            }
            Method m = signalClientClass.getDeclaredMethod("nativeOnParamsConfigResult", Map.class);
            m.setAccessible(true);
            m.invoke(null, disableMap);
            debugLog("Pushed native P2P-disable params (" + disableMap.size() + " keys) on " + trigger);
        } catch (Throwable t) {
            logError("pushNativeDisableP2PParams failed on " + trigger, t);
        } finally {
            sInSelfParamsPush.remove();
        }
    }

    /**
     * Hook 7: Disable Huya background P2P / MiniPCDN (MPS + H3/WebRTC server) / FileP2P / HeatList
     * and/or live-room P2P / XP2P upload & peer relay without affecting normal CDN HTTP-FLV playback
     * or WebSocket barrage.
     */
    private void hookDisableP2PUpload(final ClassLoader cl, final boolean blockBgP2P, final boolean blockLiveP2P) {
        int hookedCount = 0;

        // 7.0 SignalClient: master native dynamic config & WUP gateway for libYCloudLive / libhymediatrans.
        final Class<?> signalClientClass = XposedHelpers.findClassIfExists(
                "com.huya.sdk.live.SignalClient", cl);
        if (signalClientClass != null) {
            try {
                XC_MethodHook sanitizeNativeDynamicConfig = new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0) {
                            return;
                        }
                        if (Boolean.TRUE.equals(sInSelfParamsPush.get())) {
                            return;
                        }
                        HashMap<String, String> sanitized = new HashMap<>();
                        if (param.args[0] instanceof Map) {
                            Map<?, ?> orig = (Map<?, ?>) param.args[0];
                            for (Map.Entry<?, ?> entry : orig.entrySet()) {
                                if (entry.getKey() == null) {
                                    continue;
                                }
                                String k = String.valueOf(entry.getKey());
                                String v = entry.getValue() == null ? "" : String.valueOf(entry.getValue());
                                String sv = sanitizeDynamicP2PValue(k, v, blockBgP2P, blockLiveP2P);
                                sanitized.put(k, sv);
                                sLastKnownServerParams.put(k, sv);
                            }
                        }
                        if (blockBgP2P) {
                            injectDisableBgP2PParams(sanitized);
                        }
                        if (blockLiveP2P) {
                            injectDisableLiveP2PParams(sanitized);
                        }
                        param.args[0] = sanitized;
                        debugLog("Sanitized SignalClient." + param.method.getName()
                                + " (entries=" + sanitized.size() + ")");
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String mName = param.method.getName();
                        if ("access$900".equals(mName) || "onExperimentResult".equals(mName)) {
                            pushNativeDisableP2PParams(signalClientClass, mName, blockBgP2P, blockLiveP2P);
                        }
                    }
                };
                // Hook pure-Java synthetic bridges (avoid hooking native methods directly in LSPlant)
                XposedBridge.hookAllMethods(signalClientClass, "access$800", sanitizeNativeDynamicConfig);
                XposedBridge.hookAllMethods(signalClientClass, "access$900", sanitizeNativeDynamicConfig);

                // Also hook SignalClient$5 (IDataConfigListener) directly as belt-and-suspenders
                Class<?> signalDynamicListenerClass = XposedHelpers.findClassIfExists(
                        "com.huya.sdk.live.SignalClient$5", cl);
                if (signalDynamicListenerClass != null) {
                    XposedBridge.hookAllMethods(signalDynamicListenerClass, "onParamsConfigResult", sanitizeNativeDynamicConfig);
                    XposedBridge.hookAllMethods(signalDynamicListenerClass, "onExperimentResult", sanitizeNativeDynamicConfig);
                }

                // Proactively push P2P-off params into native SDK across SignalClient lifecycle
                XC_MethodHook pushDisableParams = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        pushNativeDisableP2PParams(
                                signalClientClass,
                                "SignalClient." + param.method.getName(),
                                blockBgP2P,
                                blockLiveP2P
                        );
                    }
                };
                XposedBridge.hookAllMethods(signalClientClass, "setupDynamicConfig", pushDisableParams);
                XposedBridge.hookAllMethods(signalClientClass, "queryMediaConfigs", pushDisableParams);
                XposedBridge.hookAllMethods(signalClientClass, "setUserInfo", pushDisableParams);
                XposedBridge.hookAllMethods(signalClientClass, "init", pushDisableParams);

                // Block P2P / FileP2P / HeatList / STUN / PCDN-schedule / raw getMediaConfig WUP RPCs
                // while keeping normal live stream & login RPCs (loginVerify, getAntiCodeForStream, etc.) intact.
                XposedBridge.hookAllMethods(signalClientClass, "request", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 9) {
                            return;
                        }
                        String cgi = param.args[7] instanceof String ? (String) param.args[7] : "";
                        byte[] body = param.args[8] instanceof byte[] ? (byte[]) param.args[8] : null;
                        String matched = matchBlockedP2PWupRequest(cgi, body, blockBgP2P, blockLiveP2P);
                        if (matched != null) {
                            debugLog("Blocked SignalClient.request P2P/PCDN RPC: " + matched);
                            param.setResult(-1);
                        }
                    }
                });

                // Hook pure-Java access$000([BII)V (delegates to nativeOnResponse) to drop any stray
                // SeedProxy / MiniPCDN Edge / P2P allocation responses.
                XposedBridge.hookAllMethods(signalClientClass, "access$000", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length < 1 || !(param.args[0] instanceof byte[])) {
                            return;
                        }
                        byte[] data = (byte[]) param.args[0];
                        if (data.length == 0) {
                            return;
                        }
                        int scanLen = Math.min(data.length, 1024);
                        String head = new String(data, 0, scanLen, StandardCharsets.ISO_8859_1);
                        boolean dropBg = blockBgP2P && (head.contains("UserAllocSeedProxy")
                                || head.contains("MiniPcdnMpsAllocEdgeNode")
                                || head.contains("Filep2pQuery")
                                || head.contains("GetStunServerInfo"));
                        boolean dropLive = blockLiveP2P && head.contains("GetP2PStreamInfo");
                        if (dropBg || dropLive) {
                            debugLog("Dropped SignalClient P2P/SeedProxy/MiniPCDN response (len=" + data.length + ")");
                            param.setResult(null);
                        }
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook SignalClient P2P failed", t);
            }
        }

        // 7.0b MediaInterface: immediately push P2P-disable params & disable ChannelSession P2P when created
        Class<?> mediaInterfaceClass = XposedHelpers.findClassIfExists(
                "com.huya.sdk.live.MediaInterface", cl);
        if (mediaInterfaceClass != null) {
            try {
                XposedBridge.hookAllMethods(mediaInterfaceClass, "getChannelSession", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object session = param.getResult();
                        if (session != null) {
                            disableChannelSessionP2P(session, "MediaInterface.getChannelSession", blockBgP2P, blockLiveP2P);
                            pushNativeDisableP2PParams(signalClientClass, "MediaInterface.getChannelSession", blockBgP2P, blockLiveP2P);
                        }
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook MediaInterface failed", t);
            }
        }

        // 7.1 ChannelSession (libhymediatrans / libYCloudLive native bridge)
        Class<?> channelSessionClass = XposedHelpers.findClassIfExists(
                "com.huya.sdk.live.ChannelSession", cl);
        if (channelSessionClass != null) {
            try {
                // Proactively turn off requested P2P flags on constructor, initDownload, and setUserInfo
                XC_MethodHook proactiveDisableChannelP2P = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        disableChannelSessionP2P(param.thisObject, param.method.getName(), blockBgP2P, blockLiveP2P);
                    }
                };
                XposedBridge.hookAllConstructors(channelSessionClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        disableChannelSessionP2P(param.thisObject, "<init>", blockBgP2P, blockLiveP2P);
                    }
                });
                XposedBridge.hookAllMethods(channelSessionClass, "setUserInfo", proactiveDisableChannelP2P);
                if (blockBgP2P) {
                    XposedBridge.hookAllMethods(channelSessionClass, "initDownload", proactiveDisableChannelP2P);
                    // Force FileP2P upload switch (ENABLE_UPLOAD = 0) off whenever updated
                    XposedBridge.hookAllMethods(channelSessionClass, "updateFileP2PPublishConfig", new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length > 0 && param.args[0] instanceof Map) {
                                Map<Integer, Integer> safeMap = new HashMap<>((Map<Integer, Integer>) param.args[0]);
                                safeMap.put(0, 0); // HYFileP2PPublishConfigKey.ENABLE_UPLOAD = 0
                                param.args[0] = safeMap;
                                debugLog("Forced ChannelSession.updateFileP2PPublishConfig ENABLE_UPLOAD=0");
                            }
                        }
                    });
                    // Sanitize JSON configs passed to ChannelSession.setJsonConfigs
                    XposedBridge.hookAllMethods(channelSessionClass, "setJsonConfigs", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length >= 2 && param.args[1] instanceof String) {
                                String json = (String) param.args[1];
                                String sanitized = sanitizeJsonP2PConfig(json);
                                if (sanitized != null && !sanitized.equals(json)) {
                                    param.args[1] = sanitized;
                                    debugLog("Sanitized ChannelSession.setJsonConfigs");
                                }
                            }
                        }
                    });
                }

                if (blockLiveP2P) {
                    // Sanitize CCK_ENABLE_P2P_MODE(315), CCK_THEORY_P2P_ENABLE(401), CCK_SWITCH_P2P_ENABLE(402)
                    XposedBridge.hookAllMethods(channelSessionClass, "setCommonConfig", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length >= 2 && param.args[0] instanceof Integer) {
                                int key = (Integer) param.args[0];
                                if (key == 315 || key == 401 || key == 402) {
                                    param.args[1] = 0;
                                }
                            }
                        }
                    });

                    XC_MethodHook sanitizeConfigMap = new XC_MethodHook() {
                        @Override
                        @SuppressWarnings("unchecked")
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null) {
                                return;
                            }
                            for (int i = 0; i < param.args.length; i++) {
                                if (param.args[i] instanceof Map) {
                                    Map<Object, Object> orig = (Map<Object, Object>) param.args[i];
                                    if (orig.containsKey(315) || orig.containsKey(401) || orig.containsKey(402)) {
                                        Map<Object, Object> copy = new HashMap<>(orig);
                                        if (copy.containsKey(315)) copy.put(315, 0);
                                        if (copy.containsKey(401)) copy.put(401, 0);
                                        if (copy.containsKey(402)) copy.put(402, 0);
                                        param.args[i] = copy;
                                    }
                                }
                            }
                        }
                    };
                    XposedBridge.hookAllMethods(channelSessionClass, "setConfigs", sanitizeConfigMap);
                    XposedBridge.hookAllMethods(channelSessionClass, "setStreamConfigs", sanitizeConfigMap);

                    // Block P2P peer configs, XP2P streams, and report NOT_SUPPORT_P2P (0L)
                    XposedBridge.hookAllMethods(channelSessionClass, "setP2PConfigs", new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            debugLog("Blocked ChannelSession.setP2PConfigs");
                            return false;
                        }
                    });
                    XposedBridge.hookAllMethods(channelSessionClass, "getConfigP2pSupportType", XC_MethodReplacement.returnConstant(0L));
                    XposedBridge.hookAllMethods(channelSessionClass, "restartXp2pStream", XC_MethodReplacement.returnConstant(false));
                    XposedBridge.hookAllMethods(channelSessionClass, "updateAutoXp2pUrl", XC_MethodReplacement.returnConstant(false));

                    // Strip P2P flags from HyStreamMessage and force isSupportP2P=0 in stream start/switch calls
                    // while keeping FLV/HLS CDN playback intact.
                    XposedBridge.hookAllMethods(channelSessionClass, "setStreamMessages", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length >= 3 && param.args[2] instanceof Map) {
                                Map<?, ?> map = (Map<?, ?>) param.args[2];
                                for (Object val : map.values()) {
                                    stripStreamMessageP2P(val);
                                }
                            }
                        }
                    });
                    XC_MethodHook stripDirectStreamArgs = new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null) {
                                return;
                            }
                            for (int i = 0; i < param.args.length; i++) {
                                Object arg = param.args[i];
                                if (arg == null) {
                                    continue;
                                }
                                if (arg.getClass().getName().endsWith("HyStreamMessage")) {
                                    stripStreamMessageP2P(arg);
                                } else if (arg instanceof Map) {
                                    param.args[i] = sanitizeStreamConfigMap((Map<?, ?>) arg);
                                }
                            }
                        }
                    };
                    XposedBridge.hookAllMethods(channelSessionClass, "startStream", stripDirectStreamArgs);
                    XposedBridge.hookAllMethods(channelSessionClass, "startStream2", stripDirectStreamArgs);
                    XposedBridge.hookAllMethods(channelSessionClass, "switchStream", stripDirectStreamArgs);
                    XposedBridge.hookAllMethods(channelSessionClass, "switchStream2", stripDirectStreamArgs);
                }
                hookedCount++;
            } catch (Throwable t) {
                logError("hook ChannelSession P2P failed", t);
            }
        }

        // 7.1b - 7.4: Live-room pure HTTP-FLV enforcement (gated by blockLiveP2P)
        if (blockLiveP2P) {
        Class<?> hyStreamConfigClass = XposedHelpers.findClassIfExists(
                "com.huya.sdk.newapi.HYPlayer.HYStreamConfig", cl);
        if (hyStreamConfigClass != null) {
            try {
                XposedBridge.hookAllMethods(hyStreamConfigClass, "setStreamType", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0) {
                            Object flvMode = toFlvStreamModeIfP2P(param.args[0]);
                            if (flvMode != null) {
                                log("HYStreamConfig.setStreamType forced " + param.args[0] + " -> " + flvMode);
                                param.args[0] = flvMode;
                            }
                        }
                    }
                });
                XposedBridge.hookAllMethods(hyStreamConfigClass, "getStreamType", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object flvMode = toFlvStreamModeIfP2P(param.getResult());
                        if (flvMode != null) {
                            param.setResult(flvMode);
                        }
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook HYStreamConfig failed", t);
            }
        }

        Class<?> hyLiveStreamConfigClass = XposedHelpers.findClassIfExists(
                "com.huya.sdk.newapi.HYPlayer.HYLiveStreamConfig", cl);
        if (hyLiveStreamConfigClass != null) {
            try {
                XposedBridge.hookAllMethods(hyLiveStreamConfigClass, "isForceFlv", XC_MethodReplacement.returnConstant(true));
                XposedBridge.hookAllMethods(hyLiveStreamConfigClass, "setForceFlv", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Boolean) {
                            param.args[0] = Boolean.TRUE;
                        }
                    }
                });
                XposedBridge.hookAllMethods(hyLiveStreamConfigClass, "setMap", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Map) {
                            param.args[0] = sanitizeStreamConfigMap((Map<?, ?>) param.args[0]);
                        }
                    }
                });
                XposedBridge.hookAllMethods(hyLiveStreamConfigClass, "setJsonConfig", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof String) {
                            param.args[0] = sanitizeJsonP2PConfig((String) param.args[0]);
                        }
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook HYLiveStreamConfig failed", t);
            }
        }

        Class<?> hyLivePlayerImpClass = XposedHelpers.findClassIfExists(
                "com.huya.sdk.newapi.internal.HYLivePlayerImp", cl);
        if (hyLivePlayerImpClass != null) {
            try {
                XposedBridge.hookAllMethods(hyLivePlayerImpClass, "setStreamConfig", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0) {
                            sanitizeLiveStreamConfig(param.args[0]);
                        }
                    }
                });
                XposedBridge.hookAllMethods(hyLivePlayerImpClass, "switchStream", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null) {
                            return;
                        }
                        for (Object arg : param.args) {
                            if (arg != null && arg.getClass().getName().endsWith("HYLiveStreamConfig")) {
                                sanitizeLiveStreamConfig(arg);
                            }
                        }
                    }
                });
                XposedBridge.hookAllMethods(hyLivePlayerImpClass, "setStreamMessages", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Map) {
                            for (Object val : ((Map<?, ?>) param.args[0]).values()) {
                                stripStreamMessageP2P(val);
                            }
                        }
                    }
                });
                XposedBridge.hookAllMethods(hyLivePlayerImpClass, "addPlayConfig", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null) {
                            return;
                        }
                        if (param.args.length >= 2) {
                            Object flvMode = toFlvStreamModeIfP2P(param.args[1]);
                            if (flvMode != null) {
                                param.args[1] = flvMode;
                            }
                        }
                        if (param.args.length >= 3) {
                            stripStreamMessageP2P(param.args[2]);
                        }
                    }

                    @Override
                    @SuppressWarnings("unchecked")
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length >= 1 && param.args[0] instanceof Map) {
                            try {
                                ((Map<Object, Object>) param.args[0]).put("isSupportP2P", "0");
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook HYLivePlayerImp failed", t);
            }
        }

        Class<?> streamMsgGenClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.live.multiline.data.HyStreamMessageGenerator", cl);
        if (streamMsgGenClass != null) {
            try {
                XposedBridge.hookAllMethods(streamMsgGenClass, "generateSingleStreamMessage", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        stripStreamMessageP2P(param.getResult());
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook HyStreamMessageGenerator failed", t);
            }
        }

        // 7.2 CDNLine, MultiLineConfig & ABSLine: force pure HTTP-FLV CDN stream instead of P2P / XP2P stream
        Class<?> cdnLineClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.live.multiline.module.lineinfo.CDNLine", cl);
        if (cdnLineClass != null) {
            try {
                XposedBridge.hookAllMethods(cdnLineClass, "switchTo", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length >= 4 && param.args[3] instanceof Boolean) {
                            if (Boolean.TRUE.equals(param.args[3])) {
                                param.args[3] = Boolean.FALSE;
                                log("CDNLine.switchTo forced useP2p=false (pure HTTP-FLV CDN)");
                            }
                        }
                    }
                });
                XposedBridge.hookAllMethods(cdnLineClass, "getP2PMap", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() instanceof Map) {
                            param.setResult(sanitizeStreamConfigMap((Map<?, ?>) param.getResult()));
                        }
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook CDNLine failed", t);
            }
        }

        Class<?> multiLineConfigClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.live.multiline.module.lineinfo.MultiLineConfig", cl);
        if (multiLineConfigClass != null) {
            try {
                XposedBridge.hookAllMethods(multiLineConfigClass, "getP2PHDControlParam", XC_MethodReplacement.returnConstant(0));
                hookedCount++;
            } catch (Throwable t) {
                logError("hook MultiLineConfig failed", t);
            }
        }

        Class<?> absLineClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.live.model.ABSLine", cl);
        if (absLineClass != null) {
            try {
                XposedBridge.hookAllMethods(absLineClass, "getIsP2pMode", XC_MethodReplacement.returnConstant(false));
                XposedBridge.hookAllMethods(absLineClass, "setP2pMode", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Boolean) {
                            param.args[0] = Boolean.FALSE;
                        }
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook ABSLine failed", t);
            }
        }

        // 7.3 P2PMonitor: never switch FLV back to P2P
        Class<?> p2pMonitorClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.live.p2p.P2PMonitor", cl);
        if (p2pMonitorClass != null) {
            try {
                XposedBridge.hookAllMethods(p2pMonitorClass, "changeFlvModeToP2P", XC_MethodReplacement.returnConstant(false));
                XposedBridge.hookAllMethods(p2pMonitorClass, "isTempEnableP2PMode", XC_MethodReplacement.returnConstant(false));
                XposedBridge.hookAllMethods(p2pMonitorClass, "setTempEnableP2PMode", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Boolean) {
                            param.args[0] = Boolean.FALSE;
                        }
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook P2PMonitor failed", t);
            }
        }

        // 7.4 CloudSdkDynamicConfigModule: block remote P2P / XP2P dynamic config enables
        Class<?> cloudSdkConfigClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.livemediaconfig.CloudSdkDynamicConfigModule", cl);
        if (cloudSdkConfigClass != null) {
            try {
                XposedBridge.hookAllMethods(cloudSdkConfigClass, "updateP2PConfig", XC_MethodReplacement.returnConstant(null));
                XposedBridge.hookAllMethods(cloudSdkConfigClass, "updateP2PABTestConfig", XC_MethodReplacement.returnConstant(null));
                XposedBridge.hookAllMethods(cloudSdkConfigClass, "updateXp2pConfig", XC_MethodReplacement.returnConstant(null));
                hookedCount++;
            } catch (Throwable t) {
                logError("hook CloudSdkDynamicConfigModule failed", t);
            }
        }
        } // end if (blockLiveP2P)

        // 7.5 - 7.6: Background download PCDN rewrite & barrage P2P relay (gated by blockBgP2P)
        if (blockBgP2P) {
        // 7.5 KiwiDownloadComponent: forbid PCDN rewriting so downloads use plain CDN
        Class<?> downloadCompClass = XposedHelpers.findClassIfExists(
                "com.duowan.kiwi.services.newdownloadservice.KiwiDownloadComponent", cl);
        if (downloadCompClass != null) {
            try {
                XposedBridge.hookAllMethods(downloadCompClass, "updateCdnUrlWithPcdn", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        try {
                            XposedHelpers.setBooleanField(param.thisObject, "forbiddenPcdn", true);
                        } catch (Throwable ignored) {
                        }
                        return null;
                    }
                });
                XposedBridge.hookAllMethods(downloadCompClass, "refreshPcdnConfig", new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        try {
                            XposedHelpers.setBooleanField(param.thisObject, "forbiddenPcdn", true);
                        } catch (Throwable ignored) {
                        }
                        return null;
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook KiwiDownloadComponent PCDN failed", t);
            }
        }

        // 7.6 HySignalWrapper & HalImpl: disable P2P barrage/signal relay (keeps normal WebSocket push intact)
        Class<?> signalWrapperClass = XposedHelpers.findClassIfExists(
                "com.huya.hysignal.wrapper.HySignalWrapper", cl);
        if (signalWrapperClass != null) {
            try {
                XC_MethodReplacement blockSignalP2p = new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) {
                        try {
                            XposedHelpers.setBooleanField(param.thisObject, "mEnableP2PPush", false);
                        } catch (Throwable ignored) {
                        }
                        debugLog("Blocked HySignalWrapper." + param.method.getName());
                        return null;
                    }
                };
                XposedBridge.hookAllMethods(signalWrapperClass, "tryEntryP2P", blockSignalP2p);
                XposedBridge.hookAllMethods(signalWrapperClass, "requestEnterP2p", blockSignalP2p);
                XposedBridge.hookAllMethods(signalWrapperClass, "setupP2P", blockSignalP2p);
                XposedBridge.hookAllMethods(signalWrapperClass, "onJoinP2pGroupFromNative", XC_MethodReplacement.returnConstant(null));
                XposedBridge.hookAllMethods(signalWrapperClass, "handleP2pPush", XC_MethodReplacement.returnConstant(null));
                XposedBridge.hookAllMethods(signalWrapperClass, "updateP2PEnableSwitch", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null) {
                            for (int i = 0; i < param.args.length; i++) {
                                if (param.args[i] instanceof Boolean) {
                                    param.args[i] = Boolean.FALSE;
                                }
                            }
                        }
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook HySignalWrapper P2P failed", t);
            }
        }

        Class<?> halImplClass = XposedHelpers.findClassIfExists("com.duowan.hal.HalImpl", cl);
        if (halImplClass != null) {
            try {
                XposedBridge.hookAllMethods(halImplClass, "setP2pPushDelegate", XC_MethodReplacement.returnConstant(null));
                XposedBridge.hookAllMethods(halImplClass, "updateP2PEnableSwitch", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null) {
                            for (int i = 0; i < param.args.length; i++) {
                                if (param.args[i] instanceof Boolean) {
                                    param.args[i] = Boolean.FALSE;
                                }
                            }
                        }
                    }
                });
                hookedCount++;
            } catch (Throwable t) {
                logError("hook HalImpl P2P failed", t);
            }
        }
        } // end if (blockBgP2P)

        log("P2P upload-block hooks installed (" + hookedCount + " components, bgP2p="
                + blockBgP2P + ", liveP2p=" + blockLiveP2P + ")");
    }

    private void disableChannelSessionP2P(Object channelSession, String trigger, boolean blockBgP2P, boolean blockLiveP2P) {
        if (channelSession == null) {
            return;
        }
        try {
            if (blockBgP2P) {
                Map<Integer, Integer> disableUpload = new HashMap<>();
                disableUpload.put(0, 0); // HYFileP2PPublishConfigKey.ENABLE_UPLOAD = 0
                XposedHelpers.callMethod(channelSession, "updateFileP2PPublishConfig", disableUpload);
            }
            if (blockLiveP2P) {
                XposedHelpers.callMethod(channelSession, "setCommonConfig", 315, 0); // CCK_ENABLE_P2P_MODE
                XposedHelpers.callMethod(channelSession, "setCommonConfig", 401, 0); // CCK_THEORY_P2P_ENABLE
                XposedHelpers.callMethod(channelSession, "setCommonConfig", 402, 0); // CCK_SWITCH_P2P_ENABLE
            }
            debugLog("Disabled native P2P in ChannelSession." + trigger);
        } catch (Throwable t) {
            logError("ChannelSession proactive P2P disable failed (" + trigger + ")", t);
        }
    }

    private Object toFlvStreamModeIfP2P(Object mode) {
        if (mode == null) {
            return null;
        }
        String name = mode.toString();
        try {
            if ("P2P_STREAM".equals(name) || "TX_XP2P_STREAM".equals(name)) {
                return XposedHelpers.getStaticObjectField(mode.getClass(), "FLV_STREAM");
            }
            if ("FLAC_IN_P2P_STREAM".equals(name)) {
                return XposedHelpers.getStaticObjectField(mode.getClass(), "FLAC_IN_FLV_STREAM");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> sanitizeStreamConfigMap(Map<?, ?> orig) {
        if (orig == null) {
            return null;
        }
        Map<Object, Object> copy = new HashMap<>(orig);
        if (copy.containsKey("isSupportP2P")) {
            copy.put("isSupportP2P", "0");
        }
        return copy;
    }

    private void sanitizeLiveStreamConfig(Object cfg) {
        if (cfg == null) {
            return;
        }
        try {
            Object curType = XposedHelpers.getObjectField(cfg, "streamType");
            Object flvMode = toFlvStreamModeIfP2P(curType);
            if (flvMode != null) {
                XposedHelpers.setObjectField(cfg, "streamType", flvMode);
                log("HYLiveStreamConfig forced streamType " + curType + " -> " + flvMode);
            }
        } catch (Throwable ignored) {
        }
        try {
            XposedHelpers.setBooleanField(cfg, "isForceFlv", true);
        } catch (Throwable ignored) {
        }
        try {
            Object mapObj = XposedHelpers.getObjectField(cfg, "map");
            if (mapObj instanceof Map) {
                XposedHelpers.setObjectField(cfg, "map", sanitizeStreamConfigMap((Map<?, ?>) mapObj));
            }
        } catch (Throwable ignored) {
        }
        try {
            Object jsonObj = XposedHelpers.getObjectField(cfg, "jsonConfig");
            if (jsonObj instanceof String && !((String) jsonObj).isEmpty()) {
                XposedHelpers.setObjectField(cfg, "jsonConfig", sanitizeJsonP2PConfig((String) jsonObj));
            }
        } catch (Throwable ignored) {
        }
    }

    private void stripStreamMessageP2P(Object streamMsg) {
        if (streamMsg == null) {
            return;
        }
        try {
            XposedHelpers.setIntField(streamMsg, "isP2pSupport", 0);
            XposedHelpers.setObjectField(streamMsg, "p2pUrl", "");
            XposedHelpers.setObjectField(streamMsg, "p2pUrlSuffix", "");
            XposedHelpers.setObjectField(streamMsg, "p2pAntiCode", "");
            XposedHelpers.setObjectField(streamMsg, "p2pIpList", Collections.emptyList());
        } catch (Throwable ignored) {
        }
    }

    private static void injectDisableBgP2PParams(Map<String, String> map) {
        // 1. MiniPCDN MPS / H3Server / WebRTCServer (background UDP edge upload server)
        map.put("miniPcdn/mps/enable", "0");
        map.put("miniPcdn/mps/enable/byNatType", "");
        map.put("miniPcdn/mps/enable/byProcess", "");
        map.put("miniPcdn/mps/requestEdgeInBackground", "0");
        map.put("miniPcdn/mpsCluster/enable", "0");
        map.put("miniPcdn/mpsCluster/maxActivePeerNum", "0");
        map.put("miniPcdn/h3server/maxConn", "0");
        map.put("miniPcdn/h3server/maxUpBw", "0");
        map.put("miniPcdn/webrtcServer/enable", "0");
        map.put("miniPcdn/webrtcServer/enableV6", "0");
        map.put("miniPcdn/player/enable_p2p", "0");
        map.put("miniPcdn/player/p2pFastPull", "0");

        // 2. FileP2P upload, SeedProxy, and background HeatList seeding
        map.put("filep2p/upload/enable", "0");
        map.put("filep2p/download/enable", "0");
        map.put("filep2p/svrSwitch/enable", "0");
        map.put("filep2p/seedProxy/ignoreUploadDisable", "0");
        map.put("filep2p/heatList/enable", "0");
        map.put("filep2p/heatList/enable/byProcess", "");
        map.put("filep2p/heatList/preDownloadWhenBackground", "0");
        map.put("filep2p/heatList/preDownloadWhenBusy", "0");
        map.put("filep2p/heatList/preDownloadWhenMobileNet", "0");
        map.put("filep2p/outsideCache/enable", "0");
        map.put("filep2p/checkNewUser/enable", "0");
        map.put("filep2p/upload/maxUploaderNum", "0");
        map.put("filep2p/upload/dynamicMaxUploaderNum", "0");
        map.put("filep2p/upload/targetBw", "0");
        map.put("filep2p/upload/busyTargetBw", "0");
        map.put("filep2p/upload/targetBwSingleUploader", "0");
        map.put("filep2p/node/maxActivePeerNum", "0");
        map.put("filep2p/node/maxPassivePeerNum", "0");
        map.put("filep2p/node/maxSparePeerNum", "0");
        map.put("filep2p/download/maxReceiverNum", "0");
        map.put("filep2p/download/receiverGlobalLimit", "0");
        map.put("filep2p/download/preStartNum", "0");
        map.put("filep2p/vodBitmap/tryPeerInPlaySoon", "0");
        map.put("fileP2PMaxUploadBw", "0");
        map.put("fileP2PMaxAvailPeerThreshold", "0");
        map.put("preheat/enable", "0");
        map.put("vod/preloadP2P/enable", "0");
        map.put("vod/preloadP2P/mp4/enable", "0");
        map.put("vod/filep2p/enableConfig",
                "{\"enable_hls\":0,\"enable_mp4\":0,\"enable_vod_scene\":[],\"enable_uid\":[],\"enable_vid\":[],\"enable_line\":[]}");

        // 3. LAN mDNS discovery & background P2P registration
        map.put("p2pweb/mdnsEnable", "0");
        map.put("p2pweb/webrtcMdnsUse", "0");
        map.put("p2pweb/enableP2pRegV2", "0");
        map.put("p2pweb/enableP2pRegV2SdkUse", "0");
        map.put("p2pweb/enableP2pRegV2Signal", "0");
        map.put("p2pweb/enableP2pRegV2SignalSdkUse", "0");
        map.put("p2p_lan_enable", "0");
        map.put("/hysdk/p2p_enable_lan", "0");
    }

    private static void injectDisableLiveP2PParams(Map<String, String> map) {
        // Live-room P2P / XP2P / WebRTC P2P / Signal P2P / PCDN / IPv6 peer punching
        map.put("p2pEnable", "0");
        map.put("p2pDefaultEnable", "0");
        map.put("p2pEnableForMobile", "0");
        map.put("enableP2PForFlac", "0");
        map.put("enableP2PForFreeData", "0");
        map.put("enableP2PForH265", "0");
        map.put("enableP2PForMobile", "0");
        map.put("enableP2pLargeAudio", "0");
        map.put("enableP2pPeerResend", "0");
        map.put("enableCdnpNotifyP2pRestart", "0");
        map.put("p2pMaxUploadBw", "0");
        map.put("signalP2PMaxUploadBw", "0");
        map.put("p2p/upload/minUplinkNum", "0");
        map.put("p2p_enable", "0");
        map.put("audio/enableHeAACP2P", "0");
        map.put("ob_enable_p2p_spts", "0");
        map.put("pcdn/enableAV1PcdnFlv", "0");
        map.put("p2pGetNodePerCount", "0");
        map.put("p2pGetFastPunchNodePerCount", "0");
        map.put("p2pGetMobileNodePerCount", "0");
        map.put("p2pTryPunchCntEachTime", "0");
        map.put("fastPunchTryTimes", "0");
        map.put("fastPunchThrehold", "0");
        map.put("ipv6_enable_peer", "0");
        map.put("ipv6_dump_peer_max_num", "0");
        map.put("ipv6_punch_early_times", "0");
        map.put("force_use_flv_for_ipstack_dual", "1");
        map.put("p2p/peerNewComeSendLimit", "0");
        map.put("p2p/signalPeerNewComeSendLimit", "0");
        map.put("p2p/peerResendMax", "0");
        map.put("p2p/peerResendPer", "0");
        map.put("p2pConfig", "[]");
        map.put("p2pMaxAvailPeerThreshold", "0");
        map.put("p2p_density_control", "0");
        map.put("p2pSupportSwitchAfterSubscribeRsp", "0");
        map.put("p2pSupportPcdnRequestOnMilli", "0");
        map.put("p2pSupportUrgentResend", "0");
        map.put("p2pSupportFixUpdateCdnSeq", "0");
        map.put("p2pSupportIgnoreCandidatesAdvace", "0");
        map.put("useMediaP2pVp", "0");
        map.put("supportPcdn", "0");
        map.put("supportDefaultPcdn", "0");
        map.put("supportCompetePcdn", "0");
        map.put("supportPcdnFlvHttp", "0");
        map.put("supportPcdnFlvSdk", "0");
        map.put("supportPcdnPPIOSdk", "0");
        map.put("supportPcdnXYSdk", "0");
        map.put("supportPcdnPreload302", "0");
        map.put("supportPcdnVod302", "0");
        map.put("supportPcdnFixRequestNode", "0");
        map.put("pcdnP2pSupportIpv6", "0");
        map.put("pcdnFlv302SupportIpv6", "0");
        map.put("pcdnVodSupportIpv6", "0");
        map.put("pcdnProtocolConfig", "{}");
        map.put("pcdnFlvProtocolConfig", "{}");
        map.put("pcdnQuicConfig", "{}");
        map.put("pcdnFlvQuicConfig", "{}");
        map.put("pcdn_density_p2p", "0");
        map.put("pcdn_density_flv", "0");
        map.put("pcdn_density_vod", "0");
        map.put("LiveRoomCanUseXP2PConfig", "");
        map.put("PcdnVodConfig", "");
        map.put("p2pQuicConfig", "");
        map.put("signalStreamConfig",
                "[{\"key\":\"supportSignalStream\",\"value\":[1]},{\"key\":\"supportSignalP2p\",\"value\":[0]},{\"key\":\"signalStreamForceUseHttps\",\"value\":[0]},{\"key\":\"signalStreamUseCompressed\",\"value\":[1]},{\"key\":\"signalP2pAnchoruidBlacklist\",\"value\":[]},{\"key\":\"signalStreamResendTime\",\"value\":[3000]}]");
        map.put("signalStreamStrConfig",
                "[{\"key\":\"signalStreamStreamNameBlacklist\",\"value\":[]},{\"key\":\"signalP2pStreamNamelist\",\"value\":[]}]");
    }

    private static String sanitizeDynamicP2PValue(String key, String value, boolean blockBgP2P, boolean blockLiveP2P) {
        String k = key.toLowerCase();
        if (blockBgP2P && (k.startsWith("minipcdn/mps/enable")
                || k.startsWith("minipcdn/mpscluster/enable")
                || k.startsWith("minipcdn/webrtcserver/enable")
                || k.startsWith("filep2p/upload/enable")
                || k.startsWith("filep2p/heatlist/enable")
                || k.startsWith("filep2p/download/enable")
                || k.contains("ignoreuploaddisable")
                || k.equals("filep2pmaxuploadbw"))) {
            return "0";
        }
        if (blockLiveP2P && k.contains("maxuploadbw") && !k.equals("filep2pmaxuploadbw")) {
            return "0";
        }
        return value;
    }

    private static String sanitizeJsonP2PConfig(String json) {
        if (json == null || json.isEmpty() || !json.trim().startsWith("{")) {
            return json;
        }
        try {
            JSONObject obj = new JSONObject(json);
            boolean modified = false;
            Iterator<String> it = obj.keys();
            while (it.hasNext()) {
                String key = it.next();
                String lk = key.toLowerCase();
                if (lk.contains("p2p") || lk.contains("pcdn") || lk.contains("xp2p") || lk.contains("minipcdn")) {
                    Object cur = obj.opt(key);
                    if (cur instanceof Boolean) {
                        obj.put(key, false);
                        modified = true;
                    } else if (cur instanceof Number) {
                        obj.put(key, 0);
                        modified = true;
                    } else if (cur instanceof String && ("1".equals(cur) || "true".equalsIgnoreCase((String) cur))) {
                        obj.put(key, "0");
                        modified = true;
                    }
                }
            }
            return modified ? obj.toString() : json;
        } catch (Throwable ignored) {
            return json;
        }
    }

    private static final String[] BLOCKED_BG_P2P_WUP_METHODS = new String[]{
            "filep2pQueryP2pSwitch",
            "filep2pQueryUserHeatList",
            "onClientGetStunAndPcdnProxyReq",
            "onClientGetStunServerInfo",
            "onClientGetNatCheckerSvrNodeInfo",
            "clientQueryPcdnSchedule",
            "onClientGetVodPcdnProxyReq",
            "UserAllocSeedProxy",
            "MiniPcdnMpsAllocEdgeNode"
    };

    private static final String[] BLOCKED_LIVE_P2P_WUP_METHODS = new String[]{
            "onClientGetPcdnFlvHttpFullstreamInfo",
            "onClientGetPcdnFlvOutsideSdkFullstreamInfo",
            "judgeP2PEnable",
            "getP2PStreamInfo",
            "getP2PStreamTokenInfoEx"
    };

    private static String matchBlockedP2PWupRequest(
            String cgi,
            byte[] body,
            boolean blockBgP2P,
            boolean blockLiveP2P
    ) {
        if (cgi != null && !cgi.isEmpty()) {
            String lcgi = cgi.toLowerCase();
            if (lcgi.contains("mediaconfig")) {
                return "cgi=" + cgi;
            }
            if (blockBgP2P && (lcgi.contains("filep2p") || lcgi.contains("stun"))) {
                return "cgi=" + cgi;
            }
            if (blockLiveP2P && (lcgi.contains("pcdn") || lcgi.contains("p2p"))) {
                return "cgi=" + cgi;
            }
            if (blockBgP2P && lcgi.contains("pcdn")) {
                return "cgi=" + cgi;
            }
        }
        if (body == null || body.length == 0) {
            return null;
        }
        // WUP UniPacket encodes servant & func names as plain ASCII bytes
        int scanLen = Math.min(body.length, 2048);
        String header = new String(body, 0, scanLen, StandardCharsets.ISO_8859_1);
        if (header.contains("getMediaConfig")) {
            return "getMediaConfig";
        }
        if (blockBgP2P) {
            for (String method : BLOCKED_BG_P2P_WUP_METHODS) {
                if (header.contains(method)) {
                    return method;
                }
            }
        }
        if (blockLiveP2P) {
            for (String method : BLOCKED_LIVE_P2P_WUP_METHODS) {
                if (header.contains(method)) {
                    return method;
                }
            }
        }
        return null;
    }

    private Object createFunction0Proxy(ClassLoader cl, final Runnable action) throws Throwable {
        Class<?> fn0Class = XposedHelpers.findClass("kotlin.jvm.functions.Function0", cl);
        return Proxy.newProxyInstance(cl, new Class<?>[]{fn0Class}, (proxy, method, args) -> {
            if ("invoke".equals(method.getName())) {
                action.run();
                return null;
            }
            if ("toString".equals(method.getName())) {
                return "HuyaPure$Function0Proxy";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(method.getName())) {
                return proxy == (args != null && args.length > 0 ? args[0] : null);
            }
            return null;
        });
    }
}
