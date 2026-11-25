package com.huya.pure;

import android.content.Context;
import android.content.SharedPreferences;

import de.robv.android.xposed.XSharedPreferences;

/**
 * Shared feature-toggle storage between MainActivity (LSPosed New XSharedPreferences)
 * and HookEntry (running inside com.duowan.kiwi).
 */
public final class ModulePrefs {

    public static final String MODULE_PACKAGE = "com.huya.pure";
    public static final String PREFS_NAME = "huya_pure_prefs";

    public static final String KEY_REWARD_AD_SKIP = "enable_reward_ad_skip";
    public static final String KEY_SPLASH_AD_BLOCK = "enable_splash_ad_block";
    public static final String KEY_LIVE_ROOM_AD_BLOCK = "enable_live_room_ad_block";
    public static final String KEY_BG_P2P_BLOCK = "enable_bg_p2p_block";
    public static final String KEY_LIVE_P2P_BLOCK = "enable_live_p2p_block";
    public static final String KEY_DEBUG_LOG = "enable_debug_log";

    public static final boolean DEFAULT_REWARD_AD_SKIP = true;
    public static final boolean DEFAULT_SPLASH_AD_BLOCK = true;
    public static final boolean DEFAULT_LIVE_ROOM_AD_BLOCK = true;
    public static final boolean DEFAULT_BG_P2P_BLOCK = true;
    public static final boolean DEFAULT_LIVE_P2P_BLOCK = true;
    public static final boolean DEFAULT_DEBUG_LOG = false;

    private static volatile XSharedPreferences sXPrefs;

    private ModulePrefs() {
    }

    public static final class AppPrefsHandle {
        public final SharedPreferences prefs;
        public final boolean worldReadable;

        AppPrefsHandle(SharedPreferences prefs, boolean worldReadable) {
            this.prefs = prefs;
            this.worldReadable = worldReadable;
        }
    }

    /**
     * Opens module preferences from MainActivity.
     * When LSPosed is active with xposedsharedprefs=true, MODE_WORLD_READABLE succeeds
     * and stores the file in LSPosed's cross-process readable directory.
     */
    @SuppressWarnings("deprecation")
    public static AppPrefsHandle openForApp(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
            return new AppPrefsHandle(sp, true);
        } catch (SecurityException ignored) {
            SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return new AppPrefsHandle(sp, false);
        }
    }

    public static void resetToDefaults(SharedPreferences prefs) {
        if (prefs == null) {
            return;
        }
        prefs.edit()
                .putBoolean(KEY_REWARD_AD_SKIP, DEFAULT_REWARD_AD_SKIP)
                .putBoolean(KEY_SPLASH_AD_BLOCK, DEFAULT_SPLASH_AD_BLOCK)
                .putBoolean(KEY_LIVE_ROOM_AD_BLOCK, DEFAULT_LIVE_ROOM_AD_BLOCK)
                .putBoolean(KEY_BG_P2P_BLOCK, DEFAULT_BG_P2P_BLOCK)
                .putBoolean(KEY_LIVE_P2P_BLOCK, DEFAULT_LIVE_P2P_BLOCK)
                .putBoolean(KEY_DEBUG_LOG, DEFAULT_DEBUG_LOG)
                .apply();
    }

    /**
     * Loads and reloads XSharedPreferences inside the hooked app process (com.duowan.kiwi).
     */
    private static XSharedPreferences getXPrefs() {
        XSharedPreferences xp = sXPrefs;
        if (xp == null) {
            synchronized (ModulePrefs.class) {
                xp = sXPrefs;
                if (xp == null) {
                    xp = new XSharedPreferences(MODULE_PACKAGE, PREFS_NAME);
                    xp.makeWorldReadable();
                    sXPrefs = xp;
                }
            }
        }
        return xp;
    }

    public static void reloadForHook() {
        try {
            XSharedPreferences xp = getXPrefs();
            if (xp.hasFileChanged()) {
                xp.reload();
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isReadableInHook() {
        try {
            return getXPrefs().getFile().canRead();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isEnabledInHook(String key, boolean defaultValue) {
        try {
            XSharedPreferences xp = getXPrefs();
            if (xp.hasFileChanged()) {
                xp.reload();
            }
            return xp.getBoolean(key, defaultValue);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    public static boolean isRewardAdSkipEnabled() {
        return isEnabledInHook(KEY_REWARD_AD_SKIP, DEFAULT_REWARD_AD_SKIP);
    }

    public static boolean isSplashAdBlockEnabled() {
        return isEnabledInHook(KEY_SPLASH_AD_BLOCK, DEFAULT_SPLASH_AD_BLOCK);
    }

    public static boolean isLiveRoomAdBlockEnabled() {
        return isEnabledInHook(KEY_LIVE_ROOM_AD_BLOCK, DEFAULT_LIVE_ROOM_AD_BLOCK);
    }

    public static boolean isBgP2PBlockEnabled() {
        return isEnabledInHook(KEY_BG_P2P_BLOCK, DEFAULT_BG_P2P_BLOCK);
    }

    public static boolean isLiveP2PBlockEnabled() {
        return isEnabledInHook(KEY_LIVE_P2P_BLOCK, DEFAULT_LIVE_P2P_BLOCK);
    }

    public static boolean isDebugLogEnabled() {
        return isEnabledInHook(KEY_DEBUG_LOG, DEFAULT_DEBUG_LOG);
    }

    public static String summaryInHook() {
        reloadForHook();
        return "rewardAd=" + isRewardAdSkipEnabled()
                + ", splashAd=" + isSplashAdBlockEnabled()
                + ", liveRoomAd=" + isLiveRoomAdBlockEnabled()
                + ", bgP2p=" + isBgP2PBlockEnabled()
                + ", liveP2p=" + isLiveP2PBlockEnabled()
                + ", debugLog=" + isDebugLogEnabled()
                + ", prefsReadable=" + isReadableInHook();
    }
}
