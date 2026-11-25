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
    public static final String STATE_PREFS_NAME = "huya_pure_auto_claim_state";

    public static final String KEY_REWARD_AD_SKIP = "enable_reward_ad_skip";
    public static final String KEY_SPLASH_AD_BLOCK = "enable_splash_ad_block";
    public static final String KEY_LIVE_ROOM_AD_BLOCK = "enable_live_room_ad_block";
    public static final String KEY_BG_P2P_BLOCK = "enable_bg_p2p_block";
    public static final String KEY_LIVE_P2P_BLOCK = "enable_live_p2p_block";
    public static final String KEY_DEBUG_LOG = "enable_debug_log";

    public static final String KEY_AUTO_CLAIM_ENABLED = "enable_auto_claim_huliang";
    public static final String KEY_AUTO_FANS_CHECKIN = "enable_auto_fans_checkin";
    public static final String KEY_AUTO_CLAIM_WELFARE = "enable_auto_claim_welfare";
    public static final String KEY_AUTO_SEND_HULIANG = "enable_auto_send_huliang";
    public static final String KEY_SEND_SUPER_HULIANG = "enable_send_super_huliang";
    public static final String KEY_SMART_SUPERFAN_DAY_SEND = "enable_smart_superfan_day_send";
    public static final String KEY_SEND_HULIANG_TARGET_PID = "send_huliang_target_pid";
    public static final String KEY_SEND_HULIANG_TARGET_NAME = "send_huliang_target_name";
    public static final String KEY_SEND_HULIANG_TARGETS_JSON = "send_huliang_targets_json";
    public static final String KEY_SEND_HULIANG_DISTRIBUTE_MODE = "send_huliang_distribute_mode";
    public static final String KEY_SEND_HULIANG_MAX_COUNT = "send_huliang_max_count";
    public static final String KEY_AUTO_CLAIM_HOUR = "auto_claim_hour";
    public static final String KEY_AUTO_CLAIM_MINUTE = "auto_claim_minute";
    public static final String KEY_AUTO_CLAIM_NOTIFY = "auto_claim_notify";

    public static final boolean DEFAULT_REWARD_AD_SKIP = true;
    public static final boolean DEFAULT_SPLASH_AD_BLOCK = true;
    public static final boolean DEFAULT_LIVE_ROOM_AD_BLOCK = true;
    public static final boolean DEFAULT_BG_P2P_BLOCK = true;
    public static final boolean DEFAULT_LIVE_P2P_BLOCK = true;
    public static final boolean DEFAULT_DEBUG_LOG = false;

    public static final boolean DEFAULT_AUTO_CLAIM_ENABLED = false;
    public static final boolean DEFAULT_AUTO_FANS_CHECKIN = true;
    public static final boolean DEFAULT_AUTO_CLAIM_WELFARE = true;
    public static final boolean DEFAULT_AUTO_SEND_HULIANG = false;
    public static final boolean DEFAULT_SEND_SUPER_HULIANG = true;
    public static final boolean DEFAULT_SMART_SUPERFAN_DAY_SEND = true;
    /** 0 = split evenly across all fan badges; >0 = specific presenter UID. */
    public static final long DEFAULT_SEND_HULIANG_TARGET_PID = 0L;
    public static final int DISTRIBUTE_EVEN = 0;
    public static final int DISTRIBUTE_FILL_QUOTA = 1;
    public static final int DISTRIBUTE_NEAREST_UPGRADE = 2;
    public static final int DISTRIBUTE_CUSTOM_RATIO = 3;
    public static final int DEFAULT_SEND_HULIANG_DISTRIBUTE_MODE = DISTRIBUTE_EVEN;
    /** 0 = send all available free 虎粮; >0 = cap per trigger. */
    public static final int DEFAULT_SEND_HULIANG_MAX_COUNT = 0;
    public static final int DEFAULT_AUTO_CLAIM_HOUR = 8;
    public static final int DEFAULT_AUTO_CLAIM_MINUTE = 0;
    public static final boolean DEFAULT_AUTO_CLAIM_NOTIFY = true;

    public static final String STATE_LAST_RUN_TIME = "last_run_time";
    public static final String STATE_LAST_RUN_DATE = "last_run_date";
    public static final String STATE_LAST_RUN_SUCCESS = "last_run_success";
    public static final String STATE_LAST_RUN_CLAIMED = "last_run_claimed";
    public static final String STATE_LAST_RUN_MESSAGE = "last_run_message";
    public static final String STATE_ACTIVE_REQUEST_ID = "active_request_id";
    public static final String STATE_NEXT_RETRY_TIME = "next_retry_time";
    public static final String STATE_NEXT_RESCUE_TIME = "next_expiry_rescue_time";
    public static final String STATE_RETRY_DAY = "retry_day";
    public static final String STATE_RETRY_COUNT = "retry_count";
    public static final String STATE_TODAY_CLAIMED_DAY = "today_claimed_day";
    public static final String STATE_TODAY_CLAIMED_COUNT = "today_claimed_count";
    public static final String STATE_TODAY_CHECKIN_DAY = "today_checkin_day";
    public static final String STATE_TODAY_CHECKIN_COUNT = "today_checkin_count";
    public static final String STATE_TODAY_CHECKIN_TOTAL = "today_checkin_total";
    public static final String STATE_BADGES_JSON = "fans_badges_json";
    public static final String STATE_BADGES_UPDATE_TIME = "fans_badges_update_time";
    public static final String STATE_FREE_HULIANG_COUNT = "free_huliang_count";
    public static final String STATE_FREE_EXPIRING_24H = "free_huliang_expiring_24h";
    public static final String STATE_FREE_EARLIEST_EXPIRE = "free_huliang_earliest_expire";
    public static final String STATE_SF_DAY_ACTIVE_SUMMARY = "sf_day_active_summary";
    public static final String STATE_SUPER_HULIANG_COUNT = "super_huliang_count";
    public static final String STATE_SUPER_HULIANG_NAME = "super_huliang_name";
    public static final String STATE_TODAY_SENT_DAY = "today_sent_huliang_day";
    public static final String STATE_TODAY_SENT_COUNT = "today_sent_huliang_count";
    public static final String STATE_LAST_UPDATE_CHECK_TIME = "last_update_check_time";

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

    public static SharedPreferences openStatePrefs(Context context) {
        return context.getSharedPreferences(STATE_PREFS_NAME, Context.MODE_PRIVATE);
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
                .putBoolean(KEY_AUTO_CLAIM_ENABLED, DEFAULT_AUTO_CLAIM_ENABLED)
                .putBoolean(KEY_AUTO_FANS_CHECKIN, DEFAULT_AUTO_FANS_CHECKIN)
                .putBoolean(KEY_AUTO_CLAIM_WELFARE, DEFAULT_AUTO_CLAIM_WELFARE)
                .putBoolean(KEY_AUTO_SEND_HULIANG, DEFAULT_AUTO_SEND_HULIANG)
                .putBoolean(KEY_SEND_SUPER_HULIANG, DEFAULT_SEND_SUPER_HULIANG)
                .putBoolean(KEY_SMART_SUPERFAN_DAY_SEND, DEFAULT_SMART_SUPERFAN_DAY_SEND)
                .putLong(KEY_SEND_HULIANG_TARGET_PID, DEFAULT_SEND_HULIANG_TARGET_PID)
                .putString(KEY_SEND_HULIANG_TARGET_NAME, "")
                .putString(KEY_SEND_HULIANG_TARGETS_JSON, "")
                .putInt(KEY_SEND_HULIANG_DISTRIBUTE_MODE, DEFAULT_SEND_HULIANG_DISTRIBUTE_MODE)
                .putInt(KEY_SEND_HULIANG_MAX_COUNT, DEFAULT_SEND_HULIANG_MAX_COUNT)
                .putInt(KEY_AUTO_CLAIM_HOUR, DEFAULT_AUTO_CLAIM_HOUR)
                .putInt(KEY_AUTO_CLAIM_MINUTE, DEFAULT_AUTO_CLAIM_MINUTE)
                .putBoolean(KEY_AUTO_CLAIM_NOTIFY, DEFAULT_AUTO_CLAIM_NOTIFY)
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
