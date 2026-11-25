package com.huya.pure;

/**
 * Shared IPC constants between HuyaPure's scheduler/receiver and HookEntry running
 * inside com.duowan.kiwi for headless scheduled 虎粮 auto-claiming.
 */
public final class AutoClaimProtocol {

    public static final String TARGET_PACKAGE = "com.duowan.kiwi";
    public static final String TARGET_PROVIDER_AUTHORITY = "com.duowan.kiwi.InnerAuthLoginProvider";
    public static final String TARGET_PROVIDER_URI = "content://" + TARGET_PROVIDER_AUTHORITY;
    public static final String TARGET_PROVIDER_CLASS = "com.huyaudbunify.provider.InnerAuthLoginProvider";
    public static final String METHOD_AUTO_CLAIM = "huya_pure_auto_claim_v1";
    public static final String METHOD_SEND_MANUAL_GIFT = "huya_pure_manual_send_v1";

    public static final String MODULE_PACKAGE = ModulePrefs.MODULE_PACKAGE;

    public static final String ACTION_AUTO_CLAIM_ALARM = "com.huya.pure.ACTION_AUTO_CLAIM_ALARM";
    public static final String ACTION_AUTO_CLAIM_RETRY = "com.huya.pure.ACTION_AUTO_CLAIM_RETRY";
    public static final String ACTION_EXPIRY_RESCUE_ALARM = "com.huya.pure.ACTION_EXPIRY_RESCUE_ALARM";
    public static final String ACTION_UI_STATE_UPDATED = "com.huya.pure.ACTION_UI_STATE_UPDATED";

    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_MANUAL = "manual";
    public static final String EXTRA_DO_FANS_CHECKIN = "do_fans_checkin";
    public static final String EXTRA_DO_CLAIM_WELFARE = "do_claim_welfare";
    public static final String EXTRA_DO_SEND_HULIANG = "do_send_huliang";
    public static final String EXTRA_DO_SEND_SUPER_HULIANG = "do_send_super_huliang";
    public static final String EXTRA_SMART_SUPERFAN_DAY_SEND = "smart_superfan_day_send";
    public static final String EXTRA_SEND_TARGET_PID = "send_target_pid";
    public static final String EXTRA_SEND_TARGETS_SPEC = "send_targets_spec";
    public static final String EXTRA_SEND_DISTRIBUTE_MODE = "send_distribute_mode";
    public static final String EXTRA_SEND_MAX_COUNT = "send_max_count";
    public static final String EXTRA_SEND_GIFT_KIND = "send_gift_kind";
    public static final String EXTRA_SEND_COUNT = "send_count";
    public static final String EXTRA_HANDLED = "handled";
    public static final String EXTRA_SUCCESS = "success";
    public static final String EXTRA_CLAIMED_COUNT = "claimed_count";
    public static final String EXTRA_WELFARE_CLAIMED_COUNT = "welfare_claimed_count";
    public static final String EXTRA_EXHAUSTED = "exhausted";
    public static final String EXTRA_COOLDOWN_SECONDS = "cooldown_seconds";
    public static final String EXTRA_CHECKIN_TOTAL = "checkin_total";
    public static final String EXTRA_CHECKIN_SUCCESS = "checkin_success";
    public static final String EXTRA_CHECKIN_ALREADY = "checkin_already";
    public static final String EXTRA_CHECKIN_FAILED = "checkin_failed";
    public static final String EXTRA_FREE_HULIANG_COUNT = "free_huliang_count";
    public static final String EXTRA_FREE_EXPIRING_24H = "free_expiring_24h";
    public static final String EXTRA_FREE_EARLIEST_EXPIRE = "free_earliest_expire";
    public static final String EXTRA_SF_DAY_ACTIVE_SUMMARY = "sf_day_active_summary";
    public static final String EXTRA_SUPER_HULIANG_COUNT = "super_huliang_count";
    public static final String EXTRA_SUPER_HULIANG_NAME = "super_huliang_name";
    public static final String EXTRA_SENT_HULIANG_COUNT = "sent_huliang_count";
    public static final String EXTRA_SENT_SUPER_HULIANG_COUNT = "sent_super_huliang_count";
    public static final String EXTRA_BADGES_JSON = "badges_json";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_RESULT_JSON_B64 = "result_b64";

    private AutoClaimProtocol() {
    }
}
