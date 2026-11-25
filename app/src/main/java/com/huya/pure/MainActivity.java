package com.huya.pure;

import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {

    private static final int REQ_POST_NOTIFICATIONS = 2001;
    private static final long AUTO_UPDATE_CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final int TAB_TASKS = 0;
    private static final int TAB_BADGES = 1;
    private static final int TAB_PURIFY = 2;
    private static final int TAB_ABOUT = 3;
    private static final int TAB_COUNT = 4;
    private static final String GITHUB_REPO_URL = "https://github.com/" + AppUpdater.GITHUB_REPO;
    private static final String GITHUB_ISSUES_URL = GITHUB_REPO_URL + "/issues";

    private final Map<String, Switch> switchMap = new LinkedHashMap<>();
    private final Map<String, Boolean> defaultMap = new LinkedHashMap<>();
    private final Map<String, Bitmap> nativeBadgeBitmapCache = new LinkedHashMap<>();
    private boolean updatingUi = false;
    private boolean isDarkTheme = false;
    private int currentTab = TAB_TASKS;

    // Theme palette
    private int cBg;
    private int cCard;
    private int cCardBorder;
    private int cSubSurface;
    private int cPrimaryText;
    private int cSecondaryText;
    private int cTertiaryText;
    private int cDivider;
    private int cBrandStart;
    private int cBrandEnd;
    private int cBrandTintBg;
    private int cBrandText;
    private int cOkText;
    private int cOkBg;
    private int cErrText;
    private int cErrBg;
    private int cNeutralBg;
    private int cTrackBg;

    private SharedPreferences appPrefs;

    // Tab views & nav bar refs
    private final View[] tabPages = new View[TAB_COUNT];
    private final View[] navPillBgs = new View[TAB_COUNT];
    private final ImageView[] navIcons = new ImageView[TAB_COUNT];
    private final TextView[] navLabels = new TextView[TAB_COUNT];
    private final TabIconDrawable[] navIconDrawables = new TabIconDrawable[TAB_COUNT];
    private final AnimatorSet[] navIconAnimators = new AnimatorSet[TAB_COUNT];
    private final ValueAnimator[] navColorAnimators = new ValueAnimator[TAB_COUNT];
    private View aboutTabBadgeDot;
    private View aboutHeroIconView;
    private final List<View> badgeFillViews = new ArrayList<>();
    private final List<View> badgeRowViews = new ArrayList<>();
    private boolean hasInitializedTab = false;

    // Task tab views
    private LinearLayout sfDayBannerCard;
    private TextView sfDayBannerTitleView;
    private TextView sfDayBannerDescView;
    private TextView sfDayBannerActionBtn;
    private long activeSfDayAnchorPid = 0L;
    private String activeSfDayAnchorNick = "";
    private LinearLayout scheduleTimeContainer;
    private TextView autoClaimTimeValueView;
    private TextView autoClaimScheduleView;
    private TextView autoClaimLastTimeView;
    private View autoClaimStatusDivider;
    private LinearLayout autoClaimResultList;
    private TextView statHuliangValueView;
    private TextView statCheckinValueView;
    private TextView statFreeBagValueView;
    private TextView statFreeBagSuperView;
    private LinearLayout sendConfigContainer;
    private View sendConfigDivider;
    private TextView sendTargetValueView;
    private LinearLayout sendDistributeContainer;
    private TextView sendDistributeValueView;
    private TextView sendMaxValueView;
    private TextView claimNowBtn;
    private ObjectAnimator claimPulseAnimator;
    private TextView exactAlarmBtn;

    // Purify tab views
    private static final String[] PURIFY_PREF_KEYS = new String[]{
            ModulePrefs.KEY_REWARD_AD_SKIP,
            ModulePrefs.KEY_SPLASH_AD_BLOCK,
            ModulePrefs.KEY_LIVE_ROOM_AD_BLOCK,
            ModulePrefs.KEY_BG_P2P_BLOCK,
            ModulePrefs.KEY_LIVE_P2P_BLOCK
    };
    private static final boolean[] PURIFY_PREF_DEFAULTS = new boolean[]{
            ModulePrefs.DEFAULT_REWARD_AD_SKIP,
            ModulePrefs.DEFAULT_SPLASH_AD_BLOCK,
            ModulePrefs.DEFAULT_LIVE_ROOM_AD_BLOCK,
            ModulePrefs.DEFAULT_BG_P2P_BLOCK,
            ModulePrefs.DEFAULT_LIVE_P2P_BLOCK
    };
    private final Map<String, Boolean> purifyBaselineStates = new LinkedHashMap<>();
    private LinearLayout purifyRestartBanner;
    private boolean restartingHuya = false;
    private HubanDialogs.Palette dialogPalette;

    // Badges tab views
    private TextView badgeStatCountView;
    private TextView badgeStatCheckinView;
    private TextView badgeStatSyncTimeView;
    private TextView badgesMetaView;
    private TextView badgesRefreshBtn;
    private LinearLayout badgesContainer;

    // Header & update views
    private TextView checkUpdateBtn;
    private LinearLayout updateBannerCard;
    private TextView updateBannerTitleView;
    private TextView updateBannerDescView;
    private AppUpdater.ReleaseInfo latestReleaseInfo;
    private boolean checkingUpdate = false;
    private File pendingInstallApk;
    private AtomicBoolean activeDownloadCancel;
    private HubanDialogs.ProgressDialogHandle activeDownloadDialog;
    private BroadcastReceiver stateUpdateReceiver;

    private static boolean isModuleActive() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        boolean active = isModuleActive();
        ModulePrefs.AppPrefsHandle handle = ModulePrefs.openForApp(this);
        SharedPreferences prefs = handle.prefs;
        this.appPrefs = prefs;

        if (handle.worldReadable && !prefs.contains(ModulePrefs.KEY_REWARD_AD_SKIP)) {
            ModulePrefs.resetToDefaults(prefs);
        }

        AutoClaimScheduler.syncScheduleFromPrefs(this);

        initThemePalette();
        applySystemBarStyle();

        // ── Outer Shell: Sticky Top Bar + Update Banner + 3-Tab Content + Bottom Nav ──
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setFitsSystemWindows(true);
        shell.setBackgroundColor(cBg);

        shell.addView(buildTopBar(active, handle.worldReadable));
        shell.addView(buildUpdateBanner());

        FrameLayout contentHost = new FrameLayout(this);
        contentHost.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        tabPages[TAB_TASKS] = wrapInScroll(buildTasksPage(prefs));
        tabPages[TAB_BADGES] = wrapInScroll(buildBadgesPage());
        tabPages[TAB_PURIFY] = buildPurifyTabContainer(prefs);
        tabPages[TAB_ABOUT] = wrapInScroll(buildAboutPage(prefs, active, handle.worldReadable));

        for (View page : tabPages) {
            contentHost.addView(page, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        shell.addView(contentHost);

        shell.addView(createDivider());
        shell.addView(buildBottomNavBar());

        setContentView(shell);
        getWindow().getDecorView().post(this::applySystemBarStyle);
        selectTab(TAB_TASKS);
        refreshAutoClaimStatus();
        maybeAutoCheckForUpdate();
    }

    // ── Top Bar & Update Banner ──────────────────────────────────────────────

    private View buildTopBar(final boolean active, final boolean worldReadable) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(cBg);
        bar.setPadding(dp(16), dp(12), dp(16), dp(10));

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(cPrimaryText);
        bar.addView(title);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        bar.addView(spacer);

        // Compact module active status chip on right side of top bar
        LinearLayout statusChip = new LinearLayout(this);
        statusChip.setOrientation(LinearLayout.HORIZONTAL);
        statusChip.setGravity(Gravity.CENTER_VERTICAL);
        statusChip.setBackground(createRoundedRect(active ? cOkBg : cErrBg, 99, 0, 0));
        statusChip.setPadding(dp(9), dp(4), dp(10), dp(4));

        View dot = new View(this);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(active ? cOkText : cErrText);
        dot.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(5);
        dot.setLayoutParams(dotLp);
        statusChip.addView(dot);

        TextView statusText = new TextView(this);
        statusText.setText(active ? "模块已激活" : "模块未激活");
        statusText.setTextSize(11.5f);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setTextColor(active ? cOkText : cErrText);
        statusChip.addView(statusText);

        statusChip.setOnClickListener(v -> showModuleStatusDialog(active, worldReadable));
        bar.addView(statusChip);
        return bar;
    }

    private void showModuleStatusDialog(boolean active, boolean worldReadable) {
        String msg;
        if (active && worldReadable) {
            msg = "LSPosed 模块运行正常，跨进程配置同步正常。\n修改「净化」页的广告或网络开关后，可一键重启虎牙生效。";
        } else if (active) {
            msg = "模块已激活，但跨进程配置受限。请确认 LSPosed 已开启 XSharedPreferences 支持。";
        } else {
            msg = "模块尚未激活。\n请在 LSPosed 管理器中启用「虎伴」，并勾选「虎牙直播（com.duowan.kiwi）」作用域后重启虎牙。";
        }
        HubanDialogs.showMessageModal(
                this, dialogPalette,
                active ? "模块已激活" : "模块未激活",
                msg,
                "知道了", null,
                null, null,
                null, null
        );
    }

    private View buildUpdateBanner() {
        updateBannerCard = new LinearLayout(this);
        updateBannerCard.setOrientation(LinearLayout.HORIZONTAL);
        updateBannerCard.setGravity(Gravity.CENTER_VERTICAL);
        updateBannerCard.setBackground(createRoundedRect(cBrandTintBg, 14, 1, cBrandStart));
        updateBannerCard.setPadding(dp(14), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams ubLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ubLp.leftMargin = dp(16);
        ubLp.rightMargin = dp(16);
        ubLp.bottomMargin = dp(8);
        updateBannerCard.setLayoutParams(ubLp);
        updateBannerCard.setVisibility(View.GONE);

        LinearLayout ubTextCol = new LinearLayout(this);
        ubTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ubTextLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ubTextLp.rightMargin = dp(10);
        ubTextCol.setLayoutParams(ubTextLp);

        updateBannerTitleView = new TextView(this);
        updateBannerTitleView.setTextSize(13.5f);
        updateBannerTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        updateBannerTitleView.setTextColor(cBrandText);
        ubTextCol.addView(updateBannerTitleView);

        updateBannerDescView = new TextView(this);
        updateBannerDescView.setTextSize(12f);
        updateBannerDescView.setTextColor(cSecondaryText);
        updateBannerDescView.setMaxLines(1);
        updateBannerDescView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ubDescLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ubDescLp.topMargin = dp(2);
        updateBannerDescView.setLayoutParams(ubDescLp);
        ubTextCol.addView(updateBannerDescView);

        TextView ubAction = createPrimaryButton("更新");
        ubAction.setTextSize(12.5f);
        ubAction.setPadding(dp(14), dp(6), dp(14), dp(6));
        ubAction.setOnClickListener(v -> {
            if (latestReleaseInfo != null) {
                showUpdateDialog(latestReleaseInfo);
            }
        });

        updateBannerCard.addView(ubTextCol);
        updateBannerCard.addView(ubAction);
        updateBannerCard.setOnClickListener(v -> {
            if (latestReleaseInfo != null) {
                showUpdateDialog(latestReleaseInfo);
            }
        });
        return updateBannerCard;
    }

    // ── Page 0: Tasks (Hero Overview + Schedule + Auto-Send) ─────────────────

    private LinearLayout buildTasksPage(SharedPreferences prefs) {
        LinearLayout page = createPageRoot();

        // SuperFan Day active highlight banner (visible when today is a 4x SuperFan Day)
        sfDayBannerCard = new LinearLayout(this);
        sfDayBannerCard.setOrientation(LinearLayout.HORIZONTAL);
        sfDayBannerCard.setGravity(Gravity.CENTER_VERTICAL);
        sfDayBannerCard.setBackground(createRoundedRect(cBrandTintBg, 16, 1, cBrandStart));
        sfDayBannerCard.setPadding(dp(14), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams sfbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sfbLp.bottomMargin = dp(12);
        sfDayBannerCard.setLayoutParams(sfbLp);
        sfDayBannerCard.setVisibility(View.GONE);

        LinearLayout sfbTextCol = new LinearLayout(this);
        sfbTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sfbTextLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        sfbTextLp.rightMargin = dp(10);
        sfbTextCol.setLayoutParams(sfbTextLp);

        sfDayBannerTitleView = new TextView(this);
        sfDayBannerTitleView.setTextSize(14f);
        sfDayBannerTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        sfDayBannerTitleView.setTextColor(cBrandText);
        sfbTextCol.addView(sfDayBannerTitleView);

        sfDayBannerDescView = new TextView(this);
        sfDayBannerDescView.setTextSize(12f);
        sfDayBannerDescView.setTextColor(cSecondaryText);
        LinearLayout.LayoutParams sfbDescLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sfbDescLp.topMargin = dp(2);
        sfDayBannerDescView.setLayoutParams(sfbDescLp);
        sfbTextCol.addView(sfDayBannerDescView);
        sfDayBannerCard.addView(sfbTextCol);

        sfDayBannerActionBtn = createPrimaryButton("去送粮");
        sfDayBannerActionBtn.setTextSize(12.5f);
        sfDayBannerActionBtn.setPadding(dp(13), dp(7), dp(13), dp(7));
        sfDayBannerActionBtn.setOnClickListener(v -> {
            if (activeSfDayAnchorPid > 0L && !activeSfDayAnchorNick.isEmpty()) {
                showManualSendGiftDialog(activeSfDayAnchorPid, activeSfDayAnchorNick);
            } else {
                selectTab(TAB_BADGES);
            }
        });
        sfDayBannerCard.addView(sfDayBannerActionBtn);
        page.addView(sfDayBannerCard);

        // Hero Dashboard Card
        LinearLayout heroCard = createCard();
        heroCard.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams statsRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statsRow.setLayoutParams(statsRowLp);

        statHuliangValueView = addStatTile(statsRow, "今日领虎粮", "0 次", cBrandText, true);
        LinearLayout checkinTile = addStatTileContainer(statsRow, "粉丝牌打卡", "未同步", cOkText, true);
        statCheckinValueView = (TextView) checkinTile.getChildAt(1);
        checkinTile.setOnClickListener(v -> selectTab(TAB_BADGES));

        LinearLayout freeBagTile = addStatTileContainer(statsRow, "免费虎粮", "未同步",
                isDarkTheme ? Color.parseColor("#60A5FA") : Color.parseColor("#2563EB"), false);
        statFreeBagValueView = (TextView) freeBagTile.getChildAt(1);
        statFreeBagSuperView = new TextView(this);
        statFreeBagSuperView.setTextSize(10.5f);
        statFreeBagSuperView.setTypeface(Typeface.DEFAULT_BOLD);
        statFreeBagSuperView.setSingleLine(true);
        statFreeBagSuperView.setEllipsize(TextUtils.TruncateAt.END);
        statFreeBagSuperView.setTextColor(
                isDarkTheme ? Color.parseColor("#FBBF24") : Color.parseColor("#B45309"));
        LinearLayout.LayoutParams superLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        superLp.topMargin = dp(2);
        statFreeBagSuperView.setLayoutParams(superLp);
        statFreeBagSuperView.setVisibility(View.GONE);
        freeBagTile.addView(statFreeBagSuperView);
        heroCard.addView(statsRow);

        // Primary CTA
        claimNowBtn = createPrimaryButton("立即执行");
        LinearLayout.LayoutParams claimNowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        claimNowLp.topMargin = dp(14);
        claimNowBtn.setLayoutParams(claimNowLp);
        claimNowBtn.setOnClickListener(v -> triggerManualSync());
        heroCard.addView(claimNowBtn);

        exactAlarmBtn = createSecondaryButton("开启精确闹钟权限（提升准点触发率）");
        LinearLayout.LayoutParams exactLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
        exactLp.topMargin = dp(8);
        exactAlarmBtn.setLayoutParams(exactLp);
        exactAlarmBtn.setOnClickListener(v -> openExactAlarmSettings());
        heroCard.addView(exactAlarmBtn);

        // Structured status summary panel
        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setBackground(createRoundedRect(cSubSurface, 12, 0, 0));
        statusBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams statusBoxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusBoxLp.topMargin = dp(12);
        statusBox.setLayoutParams(statusBoxLp);

        LinearLayout statusHeader = new LinearLayout(this);
        statusHeader.setOrientation(LinearLayout.HORIZONTAL);
        statusHeader.setGravity(Gravity.CENTER_VERTICAL);

        autoClaimScheduleView = new TextView(this);
        autoClaimScheduleView.setTextSize(12f);
        autoClaimScheduleView.setTypeface(Typeface.DEFAULT_BOLD);
        autoClaimScheduleView.setTextColor(cSecondaryText);
        autoClaimScheduleView.setSingleLine(true);
        autoClaimScheduleView.setEllipsize(TextUtils.TruncateAt.END);
        autoClaimScheduleView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusHeader.addView(autoClaimScheduleView);

        autoClaimLastTimeView = new TextView(this);
        autoClaimLastTimeView.setTextSize(11.5f);
        autoClaimLastTimeView.setTextColor(cTertiaryText);
        autoClaimLastTimeView.setSingleLine(true);
        LinearLayout.LayoutParams lastTimeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lastTimeLp.leftMargin = dp(8);
        autoClaimLastTimeView.setLayoutParams(lastTimeLp);
        statusHeader.addView(autoClaimLastTimeView);
        statusBox.addView(statusHeader);

        autoClaimStatusDivider = createDivider();
        LinearLayout.LayoutParams statusDivLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        statusDivLp.topMargin = dp(8);
        statusDivLp.bottomMargin = dp(8);
        autoClaimStatusDivider.setLayoutParams(statusDivLp);
        statusBox.addView(autoClaimStatusDivider);

        autoClaimResultList = new LinearLayout(this);
        autoClaimResultList.setOrientation(LinearLayout.VERTICAL);
        statusBox.addView(autoClaimResultList);

        heroCard.addView(statusBox);

        page.addView(heroCard);

        // Section 1: Daily Claim & Check-in Settings
        page.addView(createSectionHeader("每日领取与打卡", "免开虎牙静默执行"));
        LinearLayout scheduleCard = createCard();
        scheduleCard.setPadding(dp(16), dp(4), dp(16), dp(4));
        enableSmoothLayoutTransition(scheduleCard);

        addSwitchRow(
                scheduleCard, prefs,
                ModulePrefs.KEY_AUTO_CLAIM_ENABLED,
                ModulePrefs.DEFAULT_AUTO_CLAIM_ENABLED,
                "定时自动领虎粮",
                "每日到点后台领取包裹看广告虎粮",
                true,
                isChecked -> {
                    if (isChecked) {
                        requestNotificationPermissionIfNeeded();
                    }
                    AutoClaimScheduler.syncScheduleFromPrefs(this);
                    refreshAutoClaimStatus();
                }
        );

        scheduleTimeContainer = new LinearLayout(this);
        scheduleTimeContainer.setOrientation(LinearLayout.VERTICAL);
        autoClaimTimeValueView = addOptionRow(
                scheduleTimeContainer,
                "每日执行时间",
                "遇冷却将自动预约补领",
                "08:00 ▾",
                true,
                this::showTimePickerDialog
        );
        scheduleCard.addView(scheduleTimeContainer);

        addSwitchRow(
                scheduleCard, prefs,
                ModulePrefs.KEY_AUTO_FANS_CHECKIN,
                ModulePrefs.DEFAULT_AUTO_FANS_CHECKIN,
                "粉丝牌自动打卡",
                "为账号下全部粉丝牌完成每日亲密度打卡",
                true,
                isChecked -> refreshAutoClaimStatus()
        );

        addSwitchRow(
                scheduleCard, prefs,
                ModulePrefs.KEY_AUTO_CLAIM_WELFARE,
                ModulePrefs.DEFAULT_AUTO_CLAIM_WELFARE,
                "领取每日专属福利",
                "自动领取粉丝团 10 个虎粮、超粉虎粮与每日签到",
                true,
                isChecked -> refreshAutoClaimStatus()
        );

        addSwitchRow(
                scheduleCard, prefs,
                ModulePrefs.KEY_AUTO_CLAIM_NOTIFY,
                ModulePrefs.DEFAULT_AUTO_CLAIM_NOTIFY,
                "任务完成通知",
                "后台执行结束后在状态栏推送结果摘要",
                false,
                isChecked -> {
                    if (isChecked) {
                        requestNotificationPermissionIfNeeded();
                    }
                }
        );
        page.addView(scheduleCard);

        // Section 2: Dedicated Auto-Send Card
        page.addView(createSectionHeader("自动送免费虎粮", "仅走包裹通道 · 绝不扣币"));
        LinearLayout sendCard = createCard();
        sendCard.setPadding(dp(16), dp(4), dp(16), dp(4));
        enableSmoothLayoutTransition(sendCard);

        addSwitchRow(
                sendCard, prefs,
                ModulePrefs.KEY_AUTO_SEND_HULIANG,
                ModulePrefs.DEFAULT_AUTO_SEND_HULIANG,
                "自动赠送免费虎粮",
                "仅送包裹内免费虎粮，绝不消耗金豆与虎牙币",
                false,
                isChecked -> {
                    AutoClaimScheduler.syncScheduleFromPrefs(this);
                    refreshAutoClaimStatus();
                }
        );

        sendConfigDivider = createDivider();
        sendCard.addView(sendConfigDivider);

        sendConfigContainer = new LinearLayout(this);
        sendConfigContainer.setOrientation(LinearLayout.VERTICAL);
        enableSmoothLayoutTransition(sendConfigContainer);

        addSwitchRow(
                sendConfigContainer, prefs,
                ModulePrefs.KEY_SEND_SUPER_HULIANG,
                ModulePrefs.DEFAULT_SEND_SUPER_HULIANG,
                "优先赠送超粉虎粮",
                "同步赠送 1 天有效期的超粉 / 超级虎粮",
                true,
                isChecked -> refreshAutoClaimStatus()
        );

        addSwitchRow(
                sendConfigContainer, prefs,
                ModulePrefs.KEY_SMART_SUPERFAN_DAY_SEND,
                ModulePrefs.DEFAULT_SMART_SUPERFAN_DAY_SEND,
                "超粉日智能囤粮",
                "平时仅送临期与超粉虎粮，其余留到超粉日吃 4 倍亲密度",
                true,
                isChecked -> refreshAutoClaimStatus()
        );

        sendTargetValueView = addOptionRow(
                sendConfigContainer,
                "赠送目标",
                "支持多选主播、仅超粉、自定义 UID 或全部粉丝牌",
                "全部粉丝牌 ▾",
                true,
                () -> showSendTargetDialog(false)
        );

        sendDistributeContainer = new LinearLayout(this);
        sendDistributeContainer.setOrientation(LinearLayout.VERTICAL);
        sendDistributeValueView = addOptionRow(
                sendDistributeContainer,
                "分配方式",
                "多位主播间的虎粮分配策略",
                "平均分配 ▾",
                true,
                this::showSendDistributeModeDialog
        );
        sendConfigContainer.addView(sendDistributeContainer);

        sendMaxValueView = addOptionRow(
                sendConfigContainer,
                "单次赠送上限",
                "控制每次自动赠送的最大虎粮个数（临期虎粮自动突破上限）",
                "全部赠送 ▾",
                false,
                this::showSendMaxCountDialog
        );
        sendCard.addView(sendConfigContainer);
        page.addView(sendCard);

        return page;
    }

    // ── Page 1: Fan Badges (Native & Custom Badges + Guard / SuperFan Icons) ──

    private LinearLayout buildBadgesPage() {
        LinearLayout page = createPageRoot();

        // Compact overview card with quick refresh action
        LinearLayout summaryCard = createCard();
        summaryCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout summaryRow = new LinearLayout(this);
        summaryRow.setOrientation(LinearLayout.HORIZONTAL);
        summaryRow.setGravity(Gravity.CENTER_VERTICAL);

        badgeStatCountView = addStatTile(summaryRow, "持有粉丝牌", "0 枚", cPrimaryText, true);
        badgeStatCheckinView = addStatTile(summaryRow, "今日打卡", "0/0", cOkText, true);
        badgeStatSyncTimeView = addStatTile(summaryRow, "最近同步", "未同步", cSecondaryText, false);
        summaryCard.addView(summaryRow);
        page.addView(summaryCard);

        // Badges list header with refresh button
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bhLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bhLp.topMargin = dp(18);
        bhLp.leftMargin = dp(4);
        bhLp.rightMargin = dp(4);
        bhLp.bottomMargin = dp(8);
        headerRow.setLayoutParams(bhLp);

        TextView listTitle = new TextView(this);
        listTitle.setText("我的粉丝牌");
        listTitle.setTextSize(14f);
        listTitle.setTypeface(Typeface.DEFAULT_BOLD);
        listTitle.setTextColor(cSecondaryText);
        headerRow.addView(listTitle);

        badgesMetaView = new TextView(this);
        badgesMetaView.setTextSize(12f);
        badgesMetaView.setTextColor(cTertiaryText);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        metaLp.leftMargin = dp(8);
        badgesMetaView.setLayoutParams(metaLp);
        headerRow.addView(badgesMetaView);

        badgesRefreshBtn = createHeaderActionPill("同步刷新");
        badgesRefreshBtn.setTextSize(12f);
        badgesRefreshBtn.setPadding(dp(11), dp(5), dp(11), dp(5));
        badgesRefreshBtn.setOnClickListener(v -> triggerManualSync());
        headerRow.addView(badgesRefreshBtn);
        page.addView(headerRow);

        badgesContainer = createCard();
        badgesContainer.setPadding(dp(16), dp(4), dp(16), dp(4));
        page.addView(badgesContainer);

        return page;
    }

    // ── Page 2: Purify (Ad Blocking & Network Optimization + Floating Restart Popup) ─

    private FrameLayout buildPurifyTabContainer(SharedPreferences prefs) {
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(cBg);

        ScrollView scroll = wrapInScroll(buildPurifyPage(prefs));
        host.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        // Floating bottom toast/snackbar-style restart card overlaid above page content
        purifyRestartBanner = new LinearLayout(this);
        purifyRestartBanner.setOrientation(LinearLayout.HORIZONTAL);
        purifyRestartBanner.setGravity(Gravity.CENTER_VERTICAL);
        int floatBg = isDarkTheme ? Color.parseColor("#232B38") : Color.parseColor("#FFFFFF");
        purifyRestartBanner.setBackground(createRoundedRect(floatBg, 16, 1, cBrandStart));
        purifyRestartBanner.setElevation(dp(10));
        purifyRestartBanner.setPadding(dp(14), dp(11), dp(10), dp(11));
        FrameLayout.LayoutParams rbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        rbLp.gravity = Gravity.BOTTOM;
        rbLp.leftMargin = dp(16);
        rbLp.rightMargin = dp(16);
        rbLp.bottomMargin = dp(14);
        purifyRestartBanner.setLayoutParams(rbLp);
        purifyRestartBanner.setVisibility(View.GONE);

        LinearLayout rbTextCol = new LinearLayout(this);
        rbTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rbTextLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rbTextLp.rightMargin = dp(8);
        rbTextCol.setLayoutParams(rbTextLp);

        TextView rbTitle = new TextView(this);
        rbTitle.setText("净化设置已保存");
        rbTitle.setTextSize(13.5f);
        rbTitle.setTypeface(Typeface.DEFAULT_BOLD);
        rbTitle.setTextColor(cPrimaryText);
        rbTextCol.addView(rbTitle);

        TextView rbSub = new TextView(this);
        rbSub.setText("重启虎牙直播后立即生效");
        rbSub.setTextSize(11.5f);
        rbSub.setTextColor(cSecondaryText);
        rbTextCol.addView(rbSub);
        purifyRestartBanner.addView(rbTextCol);

        TextView rbAction = createPrimaryButton("立即重启");
        rbAction.setTextSize(12.5f);
        rbAction.setPadding(dp(13), dp(7), dp(13), dp(7));
        rbAction.setOnClickListener(v -> triggerRestartHuya());
        purifyRestartBanner.addView(rbAction);

        TextView rbClose = new TextView(this);
        rbClose.setText("×");
        rbClose.setTextSize(18f);
        rbClose.setTextColor(cTertiaryText);
        rbClose.setGravity(Gravity.CENTER);
        rbClose.setPadding(dp(8), dp(4), dp(6), dp(4));
        rbClose.setOnClickListener(v -> hidePurifyRestartBanner());
        purifyRestartBanner.addView(rbClose);

        host.addView(purifyRestartBanner);
        return host;
    }

    private LinearLayout buildPurifyPage(final SharedPreferences prefs) {
        LinearLayout page = createPageRoot();
        page.setPadding(dp(16), dp(4), dp(16), dp(88));
        snapshotPurifyBaseline(prefs);

        final OnToggleCallback onPurifyChanged = isChecked -> updatePurifyRestartBannerState(prefs);

        // Section 1: Ad Blocking
        page.addView(createSectionHeader("广告净化", "修改后重启虎牙生效", 2));
        LinearLayout adCard = createCard();
        adCard.setPadding(dp(16), dp(4), dp(16), dp(4));

        addSwitchRow(
                adCard, prefs,
                ModulePrefs.KEY_REWARD_AD_SKIP,
                ModulePrefs.DEFAULT_REWARD_AD_SKIP,
                "跳过激励视频广告",
                "免看激励视频，直接领取包裹虎粮奖励",
                true, onPurifyChanged
        );
        addSwitchRow(
                adCard, prefs,
                ModulePrefs.KEY_SPLASH_AD_BLOCK,
                ModulePrefs.DEFAULT_SPLASH_AD_BLOCK,
                "屏蔽开屏广告",
                "拦截冷热启动开屏与联动横幅，秒进首页",
                true, onPurifyChanged
        );
        addSwitchRow(
                adCard, prefs,
                ModulePrefs.KEY_LIVE_ROOM_AD_BLOCK,
                ModulePrefs.DEFAULT_LIVE_ROOM_AD_BLOCK,
                "净化直播间界面",
                "隐藏浮窗、角标、商业风车与推广弹幕，不影响领虎粮",
                false, onPurifyChanged
        );
        page.addView(adCard);

        // Section 2: Network & Playback
        page.addView(createSectionHeader("网络与播放优化", "省流省电"));
        LinearLayout netCard = createCard();
        netCard.setPadding(dp(16), dp(4), dp(16), dp(4));

        addSwitchRow(
                netCard, prefs,
                ModulePrefs.KEY_BG_P2P_BLOCK,
                ModulePrefs.DEFAULT_BG_P2P_BLOCK,
                "禁用后台 P2P 上传",
                "关闭 MiniPCDN 做种、预载与局域网探测，省流省电",
                true, onPurifyChanged
        );
        addSwitchRow(
                netCard, prefs,
                ModulePrefs.KEY_LIVE_P2P_BLOCK,
                ModulePrefs.DEFAULT_LIVE_P2P_BLOCK,
                "强制 HTTP-FLV 播放",
                "关闭直播间 P2P 与 IPv6 打洞，线路卡顿时可关闭",
                false, onPurifyChanged
        );
        page.addView(netCard);

        return page;
    }

    private void snapshotPurifyBaseline(SharedPreferences prefs) {
        if (prefs == null) {
            return;
        }
        purifyBaselineStates.clear();
        for (int i = 0; i < PURIFY_PREF_KEYS.length; i++) {
            purifyBaselineStates.put(
                    PURIFY_PREF_KEYS[i],
                    prefs.getBoolean(PURIFY_PREF_KEYS[i], PURIFY_PREF_DEFAULTS[i])
            );
        }
    }

    private boolean hasPurifyChanges(SharedPreferences prefs) {
        if (prefs == null || purifyBaselineStates.isEmpty()) {
            return false;
        }
        for (int i = 0; i < PURIFY_PREF_KEYS.length; i++) {
            String key = PURIFY_PREF_KEYS[i];
            boolean baseVal = Boolean.TRUE.equals(purifyBaselineStates.get(key));
            boolean curVal = prefs.getBoolean(key, PURIFY_PREF_DEFAULTS[i]);
            if (curVal != baseVal) {
                return true;
            }
        }
        return false;
    }

    private void updatePurifyRestartBannerState(SharedPreferences prefs) {
        if (hasPurifyChanges(prefs)) {
            showPurifyRestartBanner();
        } else {
            hidePurifyRestartBanner();
        }
    }

    private void showPurifyRestartBanner() {
        if (purifyRestartBanner == null) {
            return;
        }
        purifyRestartBanner.animate().cancel();
        if (purifyRestartBanner.getVisibility() != View.VISIBLE) {
            purifyRestartBanner.setVisibility(View.VISIBLE);
            purifyRestartBanner.setAlpha(0f);
            purifyRestartBanner.setTranslationY(dp(26));
            purifyRestartBanner.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(260L)
                    .setInterpolator(new OvershootInterpolator(1.05f))
                    .start();
        } else {
            // Subtle attention nudge if already visible
            purifyRestartBanner.animate()
                    .scaleX(1.02f)
                    .scaleY(1.02f)
                    .setDuration(100L)
                    .withEndAction(() -> purifyRestartBanner.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(140L)
                            .start())
                    .start();
        }
    }

    private void hidePurifyRestartBanner() {
        if (purifyRestartBanner == null || purifyRestartBanner.getVisibility() != View.VISIBLE) {
            return;
        }
        purifyRestartBanner.animate().cancel();
        purifyRestartBanner.animate()
                .alpha(0f)
                .translationY(dp(20))
                .setDuration(180L)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    purifyRestartBanner.setVisibility(View.GONE);
                    purifyRestartBanner.setAlpha(1f);
                    purifyRestartBanner.setTranslationY(0f);
                })
                .start();
    }

    private void triggerRestartHuya() {
        if (restartingHuya) {
            return;
        }
        restartingHuya = true;
        Toast.makeText(this, "正在重启虎牙直播...", Toast.LENGTH_SHORT).show();
        HuyaAppHelper.restartHuyaApp(this, (result, message) -> {
            restartingHuya = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (result == HuyaAppHelper.RestartResult.RESTARTED_WITH_ROOT) {
                snapshotPurifyBaseline(appPrefs);
                hidePurifyRestartBanner();
            }
            if (message != null && !message.isEmpty()) {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ── Page 3: About (Brand Hero + Version/Update + GitHub + Diagnostics) ───

    private LinearLayout buildAboutPage(SharedPreferences prefs, boolean active, boolean worldReadable) {
        LinearLayout page = createPageRoot();

        // Hero Brand Card
        LinearLayout heroCard = createCard();
        heroCard.setGravity(Gravity.CENTER_HORIZONTAL);
        heroCard.setPadding(dp(20), dp(22), dp(20), dp(20));
        heroCard.setClipChildren(false);
        heroCard.setClipToPadding(false);

        // Soft framed rounded icon container (animate frame as one unit so bounce/tilt never clips)
        FrameLayout iconFrame = new FrameLayout(this);
        iconFrame.setBackground(createRoundedRect(
                Color.TRANSPARENT, 19, 1,
                isDarkTheme ? Color.parseColor("#38FFFFFF") : Color.parseColor("#18000000")));
        iconFrame.setPadding(dp(1), dp(1), dp(1), dp(1));
        iconFrame.setClipChildren(false);
        iconFrame.setClipToPadding(false);
        iconFrame.setLayoutParams(new LinearLayout.LayoutParams(dp(74), dp(74)));

        ImageView heroImg = new ImageView(this);
        heroImg.setImageResource(R.mipmap.ic_launcher);
        heroImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
        heroImg.setClipToOutline(true);
        heroImg.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        heroImg.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        iconFrame.addView(heroImg);
        iconFrame.setOnClickListener(v -> playHeroIconBounce());
        aboutHeroIconView = iconFrame;
        heroCard.addView(iconFrame);

        TextView appTitle = new TextView(this);
        appTitle.setText(R.string.app_name);
        appTitle.setTextSize(20f);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        appTitle.setTextColor(cPrimaryText);
        LinearLayout.LayoutParams atLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        atLp.topMargin = dp(10);
        appTitle.setLayoutParams(atLp);
        heroCard.addView(appTitle);

        TextView appSub = new TextView(this);
        appSub.setText("虎牙直播纯净增强伴侣 · HuyaPure");
        appSub.setTextSize(12.5f);
        appSub.setTextColor(cSecondaryText);
        LinearLayout.LayoutParams asLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        asLp.topMargin = dp(3);
        appSub.setLayoutParams(asLp);
        heroCard.addView(appSub);

        LinearLayout badgeRow = new LinearLayout(this);
        badgeRow.setOrientation(LinearLayout.HORIZONTAL);
        badgeRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        brLp.topMargin = dp(10);
        badgeRow.setLayoutParams(brLp);

        String verName = getAppVersionName();
        TextView verPill = createPill(verName.isEmpty() ? "v1.5.0" : verName, cBrandText, cBrandTintBg, 11.5f, true);
        badgeRow.addView(verPill);

        TextView actPill = createPill(
                active ? "LSPosed 已激活" : "LSPosed 未激活",
                active ? cOkText : cErrText,
                active ? cOkBg : cErrBg,
                11.5f, true);
        LinearLayout.LayoutParams apLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        apLp.leftMargin = dp(8);
        actPill.setLayoutParams(apLp);
        actPill.setOnClickListener(v -> showModuleStatusDialog(active, worldReadable));
        badgeRow.addView(actPill);

        heroCard.addView(badgeRow);
        page.addView(heroCard);

        // Section 1: Version & Open Source Project
        page.addView(createSectionHeader("版本与项目", "开源免费"));
        LinearLayout projectCard = createCard();
        projectCard.setPadding(dp(16), dp(4), dp(16), dp(4));

        checkUpdateBtn = addOptionRow(
                projectCard,
                "检查更新",
                "从 GitHub Releases 检测新版本并支持应用内更新",
                "检查更新",
                true,
                () -> {
                    if (latestReleaseInfo != null && latestReleaseInfo.hasUpdate) {
                        showUpdateDialog(latestReleaseInfo);
                    } else {
                        triggerUpdateCheck(true);
                    }
                }
        );

        String verFull = getAppVersionName() + " (" + getAppVersionCode() + ")";
        addOptionRow(
                projectCard,
                "当前版本",
                "点击复制版本与环境信息，方便反馈问题",
                verFull,
                true,
                () -> copyEnvironmentInfo(active, worldReadable)
        );

        addOptionRow(
                projectCard,
                "GitHub 仓库",
                "github.com/" + AppUpdater.GITHUB_REPO,
                "前往 ↗",
                true,
                () -> openUrlInBrowser(GITHUB_REPO_URL)
        );

        addOptionRow(
                projectCard,
                "更新日志与历史版本",
                "查看 GitHub Releases 完整发行说明与安装包",
                "查看 ↗",
                true,
                () -> openUrlInBrowser(AppUpdater.RELEASES_PAGE_URL)
        );

        addOptionRow(
                projectCard,
                "问题反馈与建议",
                "遇到兼容问题或有新功能想法可提交 Issue",
                "反馈 ↗",
                false,
                () -> openUrlInBrowser(GITHUB_ISSUES_URL)
        );
        page.addView(projectCard);

        // Section 2: Module Diagnostics & Maintenance
        page.addView(createSectionHeader("模块维护与诊断", "本地运行"));
        LinearLayout maintCard = createCard();
        maintCard.setPadding(dp(16), dp(4), dp(16), dp(4));

        String huyaVer = getHuyaAppVersion();
        addOptionRow(
                maintCard,
                "目标应用（虎牙直播）",
                AutoClaimProtocol.TARGET_PACKAGE,
                huyaVer,
                true,
                () -> Toast.makeText(this, "虎牙直播版本：" + huyaVer, Toast.LENGTH_SHORT).show()
        );

        addOptionRow(
                maintCard,
                "清理粉丝牌缓存",
                "清除本地同步数据与定制牌图标缓存，下次将重新拉取",
                "清理",
                true,
                () -> HubanDialogs.showMessageModal(
                        this, dialogPalette,
                        "清理粉丝牌缓存",
                        "清理后将移除本地缓存的粉丝牌列表与定制牌图片，下次点击「同步刷新」时会重新从虎牙拉取最新数据。",
                        "确定清理", () -> {
                            nativeBadgeBitmapCache.clear();
                            HuyaAppHelper.clearFanBadgeCache(this, (clearedDisk, summary) -> {
                                if (isFinishing() || isDestroyed()) {
                                    return;
                                }
                                refreshAutoClaimStatus();
                                Toast.makeText(MainActivity.this, summary, Toast.LENGTH_SHORT).show();
                            });
                        },
                        "取消", null,
                        null, null
                )
        );

        addSwitchRow(
                maintCard, prefs,
                ModulePrefs.KEY_DEBUG_LOG,
                ModulePrefs.DEFAULT_DEBUG_LOG,
                "详细调试日志",
                "向 LSPosed 输出完整拦截与任务日志（平时建议关闭）",
                true, null
        );

        addOptionRow(
                maintCard,
                "恢复默认设置",
                "将全部开关与定时选项重置为推荐配置",
                "重置",
                false,
                () -> HubanDialogs.showMessageModal(
                        this, dialogPalette,
                        "恢复默认设置",
                        "确定要将所有开关与任务设置恢复为默认值吗？",
                        "确定恢复", () -> resetAllSettings(prefs),
                        "取消", null,
                        null, null
                )
        );
        page.addView(maintCard);

        // Section 3: Usage Notes & Safety Statement
        LinearLayout tipCard = new LinearLayout(this);
        tipCard.setOrientation(LinearLayout.VERTICAL);
        tipCard.setBackground(createRoundedRect(cSubSurface, 14, 1, cCardBorder));
        tipCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams tipCardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tipCardLp.topMargin = dp(16);
        tipCard.setLayoutParams(tipCardLp);

        TextView tipTitle = new TextView(this);
        tipTitle.setText("使用说明与安全声明");
        tipTitle.setTextSize(13f);
        tipTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tipTitle.setTextColor(cPrimaryText);
        tipCard.addView(tipTitle);

        TextView tipBody = new TextView(this);
        tipBody.setText("• 更改「净化」页的广告或网络开关后，请强行停止并重启虎牙生效。\n"
                + "• 定时后台任务需在虎牙保持登录；如国产系统拦截后台唤醒，请允许虎牙关联启动或授予「虎伴」Root 权限。\n"
                + "• 自动送礼严格锁定包裹免费通道，绝不消耗金豆与虎牙币；本软件开源免费，不收集任何个人隐私数据。");
        tipBody.setTextSize(12f);
        tipBody.setLineSpacing(dp(4), 1.0f);
        tipBody.setTextColor(cSecondaryText);
        LinearLayout.LayoutParams tipBodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tipBodyLp.topMargin = dp(6);
        tipBody.setLayoutParams(tipBodyLp);
        tipCard.addView(tipBody);
        page.addView(tipCard);

        return page;
    }

    // ── Bottom Navigation Bar (4 Tabs · Material 3 Pill Indicator) ───────────

    private View buildBottomNavBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(cCard);
        bar.setPadding(dp(8), dp(6), dp(8), dp(8));
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)));

        String[] titles = new String[]{"任务", "粉丝牌", "净化", "关于"};
        for (int i = 0; i < TAB_COUNT; i++) {
            final int tabIdx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setFocusable(true);
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

            FrameLayout pillHost = new FrameLayout(this);
            pillHost.setClipChildren(false);
            pillHost.setClipToPadding(false);
            pillHost.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(30)));

            // Dedicated animated pill background (scales independently without stretching the icon)
            View pillBg = new View(this);
            pillBg.setBackground(createRoundedRect(cBrandTintBg, 99, 0, 0));
            pillBg.setAlpha(0f);
            pillBg.setScaleX(0.45f);
            pillBg.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            pillHost.addView(pillBg);

            TabIconDrawable iconDrawable = new TabIconDrawable(tabIdx, cSecondaryText, cBrandTintBg);
            ImageView iconView = new ImageView(this);
            iconView.setImageDrawable(iconDrawable);
            FrameLayout.LayoutParams icLp = new FrameLayout.LayoutParams(dp(20), dp(20));
            icLp.gravity = Gravity.CENTER;
            iconView.setLayoutParams(icLp);
            pillHost.addView(iconView);

            if (tabIdx == TAB_ABOUT) {
                aboutTabBadgeDot = new View(this);
                GradientDrawable dotBg = new GradientDrawable();
                dotBg.setShape(GradientDrawable.OVAL);
                dotBg.setColor(cBrandEnd);
                aboutTabBadgeDot.setBackground(dotBg);
                FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(7), dp(7));
                dotLp.gravity = Gravity.TOP | Gravity.END;
                dotLp.topMargin = dp(2);
                dotLp.rightMargin = dp(11);
                aboutTabBadgeDot.setLayoutParams(dotLp);
                aboutTabBadgeDot.setVisibility(View.GONE);
                pillHost.addView(aboutTabBadgeDot);
            }

            TextView labelView = new TextView(this);
            labelView.setText(titles[i]);
            labelView.setTextSize(11.5f);
            labelView.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lLp.topMargin = dp(3);
            labelView.setLayoutParams(lLp);

            item.setClipChildren(false);
            item.setClipToPadding(false);
            item.addView(pillHost);
            item.addView(labelView);
            attachPressBounce(item, 0.92f);
            item.setOnClickListener(v -> selectTab(tabIdx));

            navPillBgs[i] = pillBg;
            navIcons[i] = iconView;
            navLabels[i] = labelView;
            navIconDrawables[i] = iconDrawable;
            bar.addView(item);
        }
        bar.setClipChildren(false);
        bar.setClipToPadding(false);
        return bar;
    }

    private void selectTab(int index) {
        int prevTab = currentTab;
        boolean animate = hasInitializedTab;
        boolean reselect = animate && (prevTab == index);
        hasInitializedTab = true;
        currentTab = index;

        if (reselect) {
            // Playful re-tap micro-bounce on the active tab icon
            playTabIconAnimation(index);
            if (index == TAB_ABOUT) {
                playHeroIconBounce();
            } else if (index == TAB_BADGES) {
                playBadgesEntranceAnimation();
            }
            return;
        }

        int direction = index > prevTab ? 1 : -1;

        for (int i = 0; i < TAB_COUNT; i++) {
            boolean selected = (i == index);
            View page = tabPages[i];
            if (page != null) {
                page.animate().cancel();
                if (selected) {
                    page.setVisibility(View.VISIBLE);
                    if (animate) {
                        page.setAlpha(0f);
                        page.setTranslationX(direction * dp(18));
                        page.animate()
                                .alpha(1f)
                                .translationX(0f)
                                .setDuration(230L)
                                .setInterpolator(new DecelerateInterpolator(2.0f))
                                .start();
                    } else {
                        page.setAlpha(1f);
                        page.setTranslationX(0f);
                    }
                } else {
                    page.setVisibility(View.GONE);
                    page.setAlpha(1f);
                    page.setTranslationX(0f);
                }
            }

            // Pill expand / collapse animation
            View pillBg = navPillBgs[i];
            if (pillBg != null) {
                pillBg.animate().cancel();
                if (selected) {
                    if (animate) {
                        pillBg.setScaleX(0.45f);
                        pillBg.setAlpha(0.25f);
                        pillBg.animate()
                                .scaleX(1f)
                                .alpha(1f)
                                .setDuration(260L)
                                .setInterpolator(new OvershootInterpolator(1.35f))
                                .start();
                    } else {
                        pillBg.setScaleX(1f);
                        pillBg.setAlpha(1f);
                    }
                } else {
                    if (animate && i == prevTab) {
                        pillBg.animate()
                                .scaleX(0.45f)
                                .alpha(0f)
                                .setDuration(160L)
                                .setInterpolator(new DecelerateInterpolator())
                                .start();
                    } else {
                        pillBg.setScaleX(0.45f);
                        pillBg.setAlpha(0f);
                    }
                }
            }

            // Switch icon between hollow outline (unselected) and solid filled (selected)
            if (navIconDrawables[i] != null) {
                navIconDrawables[i].setFilled(selected);
            }

            // Smooth icon & label color cross-fade
            animateNavTabColors(i, selected, animate && (selected || i == prevTab));

            if (navLabels[i] != null) {
                navLabels[i].setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
            if (selected && animate) {
                playTabIconAnimation(i);
            }
        }

        if (animate) {
            if (index == TAB_BADGES) {
                playBadgesEntranceAnimation();
            } else if (index == TAB_ABOUT) {
                playHeroIconBounce();
            }
        }
    }

    private void animateNavTabColors(final int tabIdx, boolean selected, boolean animate) {
        if (navColorAnimators[tabIdx] != null) {
            navColorAnimators[tabIdx].cancel();
            navColorAnimators[tabIdx] = null;
        }
        int targetColor = selected ? cBrandText : cSecondaryText;
        if (!animate) {
            if (navIconDrawables[tabIdx] != null) {
                navIconDrawables[tabIdx].setTintColor(targetColor);
                navIcons[tabIdx].invalidate();
            }
            if (navLabels[tabIdx] != null) {
                navLabels[tabIdx].setTextColor(targetColor);
            }
            return;
        }
        int fromColor = selected ? cSecondaryText : cBrandText;
        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), fromColor, targetColor);
        va.setDuration(200L);
        va.addUpdateListener(anim -> {
            int c = (Integer) anim.getAnimatedValue();
            if (navIconDrawables[tabIdx] != null) {
                navIconDrawables[tabIdx].setTintColor(c);
                navIcons[tabIdx].invalidate();
            }
            if (navLabels[tabIdx] != null) {
                navLabels[tabIdx].setTextColor(c);
            }
        });
        navColorAnimators[tabIdx] = va;
        va.start();
    }

    /**
     * Distinct expressive micro-animation for each bottom navigation icon.
     */
    private void playTabIconAnimation(int tabIdx) {
        ImageView icon = navIcons[tabIdx];
        if (icon == null) {
            return;
        }
        if (navIconAnimators[tabIdx] != null) {
            navIconAnimators[tabIdx].cancel();
            navIconAnimators[tabIdx] = null;
        }
        icon.setScaleX(1f);
        icon.setScaleY(1f);
        icon.setRotation(0f);
        icon.setTranslationY(0f);

        AnimatorSet set = new AnimatorSet();
        if (tabIdx == TAB_TASKS) {
            // Lightning bolt: snappy electric pop + subtle zap tilt
            ObjectAnimator sx = ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.80f, 1.22f, 0.96f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.80f, 1.22f, 0.96f, 1f);
            ObjectAnimator rot = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, -13f, 8f, 0f);
            set.setDuration(340L);
            set.setInterpolator(new DecelerateInterpolator(1.5f));
            set.playTogether(sx, sy, rot);
        } else if (tabIdx == TAB_BADGES) {
            // Heart: lively double heartbeat pulse
            ObjectAnimator sx = ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.86f, 1.26f, 0.94f, 1.13f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.86f, 1.26f, 0.94f, 1.13f, 1f);
            set.setDuration(400L);
            set.setInterpolator(new DecelerateInterpolator(1.4f));
            set.playTogether(sx, sy);
        } else if (tabIdx == TAB_PURIFY) {
            // Shield: confident upward lift & bounce settle
            ObjectAnimator sx = ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.84f, 1.16f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.84f, 1.16f, 1f);
            ObjectAnimator ty = ObjectAnimator.ofFloat(icon, View.TRANSLATION_Y, 0f, -dp(3), 0f);
            set.setDuration(320L);
            set.setInterpolator(new OvershootInterpolator(1.4f));
            set.playTogether(sx, sy, ty);
        } else {
            // About info icon: gentle pop + friendly nod tilt
            ObjectAnimator sx = ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.84f, 1.18f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.84f, 1.18f, 1f);
            ObjectAnimator rot = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, 14f, -8f, 0f);
            set.setDuration(340L);
            set.setInterpolator(new DecelerateInterpolator(1.5f));
            set.playTogether(sx, sy, rot);
        }
        navIconAnimators[tabIdx] = set;
        set.start();
    }

    private void playHeroIconBounce() {
        if (aboutHeroIconView == null) {
            return;
        }
        aboutHeroIconView.animate().cancel();
        AnimatorSet set = new AnimatorSet();
        ObjectAnimator sx = ObjectAnimator.ofFloat(aboutHeroIconView, View.SCALE_X, 0.86f, 1.08f, 0.98f, 1f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(aboutHeroIconView, View.SCALE_Y, 0.86f, 1.08f, 0.98f, 1f);
        ObjectAnimator rot = ObjectAnimator.ofFloat(aboutHeroIconView, View.ROTATION, 0f, -6f, 4f, 0f);
        set.setDuration(440L);
        set.setInterpolator(new DecelerateInterpolator(1.6f));
        set.playTogether(sx, sy, rot);
        set.start();
    }

    private void playBadgesEntranceAnimation() {
        for (int i = 0; i < badgeRowViews.size(); i++) {
            View row = badgeRowViews.get(i);
            row.animate().cancel();
            row.setAlpha(0f);
            row.setTranslationY(dp(10));
            row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(i * 45L)
                    .setDuration(260L)
                    .setInterpolator(new DecelerateInterpolator(2.0f))
                    .start();
        }
        for (int i = 0; i < badgeFillViews.size(); i++) {
            final View fill = badgeFillViews.get(i);
            final long delay = 90L + i * 55L;
            fill.animate().cancel();
            fill.setPivotX(0f);
            fill.setScaleX(0f);
            fill.post(() -> {
                fill.setPivotX(0f);
                fill.animate()
                        .scaleX(1f)
                        .setStartDelay(delay)
                        .setDuration(460L)
                        .setInterpolator(new DecelerateInterpolator(2.2f))
                        .start();
            });
        }
    }

    private void enableSmoothLayoutTransition(ViewGroup group) {
        LayoutTransition lt = new LayoutTransition();
        lt.setDuration(220L);
        lt.enableTransitionType(LayoutTransition.CHANGING);
        group.setLayoutTransition(lt);
    }

    private void attachPressBounce(final View view, final float pressedScale) {
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().cancel();
                v.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(90L)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().cancel();
                v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180L)
                        .setInterpolator(new OvershootInterpolator(1.6f))
                        .start();
            }
            return false;
        });
    }

    private void copyEnvironmentInfo(boolean active, boolean worldReadable) {
        String info = "虎伴 (HuyaPure) " + getAppVersionName() + " (" + getAppVersionCode() + ")\n"
                + "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "设备：" + Build.MANUFACTURER + " " + Build.MODEL + "\n"
                + "虎牙直播：" + getHuyaAppVersion() + "\n"
                + "模块状态：" + (active ? (worldReadable ? "已激活（配置同步正常）" : "已激活（配置受限）") : "未激活");
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("HuyaPure Info", info));
                Toast.makeText(this, "已复制版本与环境信息到剪贴板", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
        }
    }

    private String getHuyaAppVersion() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(AutoClaimProtocol.TARGET_PACKAGE, 0);
            return pi.versionName != null ? ("v" + pi.versionName) : "已安装";
        } catch (Throwable ignored) {
            return "未安装";
        }
    }

    @SuppressWarnings("deprecation")
    private long getAppVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private ScrollView wrapInScroll(View child) {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        sv.setBackgroundColor(cBg);
        sv.addView(child);
        return sv;
    }

    private LinearLayout createPageRoot() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(4), dp(16), dp(24));
        return page;
    }

    private void triggerManualSync() {
        requestNotificationPermissionIfNeeded();
        AutoClaimScheduler.triggerNow(this, true);
        refreshAutoClaimStatus();
        Toast.makeText(this, "已开始后台执行，正在同步虎粮与粉丝牌...", Toast.LENGTH_SHORT).show();
    }

    private void resetAllSettings(SharedPreferences prefs) {
        ModulePrefs.resetToDefaults(prefs);
        AutoClaimScheduler.syncScheduleFromPrefs(this);
        updatingUi = true;
        try {
            for (Map.Entry<String, Switch> entry : switchMap.entrySet()) {
                Boolean def = defaultMap.get(entry.getKey());
                entry.getValue().setChecked(def != null && def);
            }
        } finally {
            updatingUi = false;
        }
        refreshAutoClaimStatus();
        updatePurifyRestartBannerState(prefs);
        Toast.makeText(this, "已恢复默认设置，重启虎牙后生效", Toast.LENGTH_SHORT).show();
    }

    // ── Theme & Lifecycle ────────────────────────────────────────────────────

    private void initThemePalette() {
        isDarkTheme = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (isDarkTheme) {
            cBg = Color.parseColor("#0E1117");
            cCard = Color.parseColor("#171C24");
            cCardBorder = Color.parseColor("#252C38");
            cSubSurface = Color.parseColor("#1E2530");
            cPrimaryText = Color.parseColor("#F1F5F9");
            cSecondaryText = Color.parseColor("#94A3B8");
            cTertiaryText = Color.parseColor("#64748B");
            cDivider = Color.parseColor("#242B37");
            cBrandStart = Color.parseColor("#FF8A1F");
            cBrandEnd = Color.parseColor("#F55800");
            cBrandTintBg = Color.parseColor("#2E2116");
            cBrandText = Color.parseColor("#FFA142");
            cOkText = Color.parseColor("#34D399");
            cOkBg = Color.parseColor("#14332A");
            cErrText = Color.parseColor("#F87171");
            cErrBg = Color.parseColor("#381B1D");
            cNeutralBg = Color.parseColor("#252D3A");
            cTrackBg = Color.parseColor("#2A3342");
        } else {
            cBg = Color.parseColor("#F3F5F9");
            cCard = Color.parseColor("#FFFFFF");
            cCardBorder = Color.parseColor("#E6EAF2");
            cSubSurface = Color.parseColor("#F6F8FC");
            cPrimaryText = Color.parseColor("#151923");
            cSecondaryText = Color.parseColor("#606C80");
            cTertiaryText = Color.parseColor("#8D99AE");
            cDivider = Color.parseColor("#EDF0F6");
            cBrandStart = Color.parseColor("#FF8218");
            cBrandEnd = Color.parseColor("#FF5500");
            cBrandTintBg = Color.parseColor("#FFF1E6");
            cBrandText = Color.parseColor("#E85D00");
            cOkText = Color.parseColor("#0E9F6E");
            cOkBg = Color.parseColor("#E6F7F1");
            cErrText = Color.parseColor("#D92D20");
            cErrBg = Color.parseColor("#FEECEB");
            cNeutralBg = Color.parseColor("#EEF1F6");
            cTrackBg = Color.parseColor("#E8EDF5");
        }
        dialogPalette = new HubanDialogs.Palette(
                isDarkTheme,
                cBg, cCard, cCardBorder, cSubSurface,
                cPrimaryText, cSecondaryText, cTertiaryText, cDivider,
                cBrandStart, cBrandEnd, cBrandTintBg, cBrandText, cTrackBg
        );
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarStyle() {
        try {
            Window window = getWindow();
            window.setStatusBarColor(cBg);
            window.setNavigationBarColor(cCard);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    int flags = isDarkTheme ? 0 : (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                    controller.setSystemBarsAppearance(
                            flags,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                    );
                }
            } else if (!isDarkTheme) {
                View decor = window.getDecorView();
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAutoClaimStatus();
        if (pendingInstallApk != null && pendingInstallApk.exists() && AppUpdater.canInstallPackages(this)) {
            File toInstall = pendingInstallApk;
            pendingInstallApk = null;
            AppUpdater.launchApkInstaller(this, toInstall);
        }
        if (stateUpdateReceiver == null) {
            stateUpdateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    refreshAutoClaimStatus();
                }
            };
        }
        IntentFilter filter = new IntentFilter(AutoClaimProtocol.ACTION_UI_STATE_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateUpdateReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (stateUpdateReceiver != null) {
            try {
                unregisterReceiver(stateUpdateReceiver);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (claimPulseAnimator != null) {
            claimPulseAnimator.cancel();
            claimPulseAnimator = null;
        }
        if (activeDownloadCancel != null) {
            activeDownloadCancel.set(true);
            activeDownloadCancel = null;
        }
        if (activeDownloadDialog != null && activeDownloadDialog.isShowing()) {
            try {
                activeDownloadDialog.dismiss();
            } catch (Throwable ignored) {
            }
            activeDownloadDialog = null;
        }
    }

    // ── State Refresh ────────────────────────────────────────────────────────

    private void showTimePickerDialog() {
        int curHour = appPrefs.getInt(ModulePrefs.KEY_AUTO_CLAIM_HOUR, ModulePrefs.DEFAULT_AUTO_CLAIM_HOUR);
        int curMinute = appPrefs.getInt(ModulePrefs.KEY_AUTO_CLAIM_MINUTE, ModulePrefs.DEFAULT_AUTO_CLAIM_MINUTE);
        HubanDialogs.showTimePickerSheet(
                this,
                dialogPalette,
                "每日执行时间",
                curHour,
                curMinute,
                (hourOfDay, minute) -> {
                    appPrefs.edit()
                            .putInt(ModulePrefs.KEY_AUTO_CLAIM_HOUR, hourOfDay)
                            .putInt(ModulePrefs.KEY_AUTO_CLAIM_MINUTE, minute)
                            .apply();
                    AutoClaimScheduler.syncScheduleFromPrefs(this);
                    refreshAutoClaimStatus();
                    Toast.makeText(
                            this,
                            "已将每日执行时间设为 " + AutoClaimScheduler.formatTime(hourOfDay, minute),
                            Toast.LENGTH_SHORT
                    ).show();
                }
        );
    }

    private void refreshAutoClaimStatus() {
        if (appPrefs == null || autoClaimTimeValueView == null || autoClaimScheduleView == null) {
            return;
        }
        boolean enabled = appPrefs.getBoolean(
                ModulePrefs.KEY_AUTO_CLAIM_ENABLED, ModulePrefs.DEFAULT_AUTO_CLAIM_ENABLED);
        boolean autoSend = appPrefs.getBoolean(
                ModulePrefs.KEY_AUTO_SEND_HULIANG, ModulePrefs.DEFAULT_AUTO_SEND_HULIANG);
        long sendTargetPid = appPrefs.getLong(
                ModulePrefs.KEY_SEND_HULIANG_TARGET_PID, ModulePrefs.DEFAULT_SEND_HULIANG_TARGET_PID);
        String sendTargetName = appPrefs.getString(ModulePrefs.KEY_SEND_HULIANG_TARGET_NAME, "");
        int sendMaxCount = appPrefs.getInt(
                ModulePrefs.KEY_SEND_HULIANG_MAX_COUNT, ModulePrefs.DEFAULT_SEND_HULIANG_MAX_COUNT);
        int hour = appPrefs.getInt(ModulePrefs.KEY_AUTO_CLAIM_HOUR, ModulePrefs.DEFAULT_AUTO_CLAIM_HOUR);
        int minute = appPrefs.getInt(ModulePrefs.KEY_AUTO_CLAIM_MINUTE, ModulePrefs.DEFAULT_AUTO_CLAIM_MINUTE);
        autoClaimTimeValueView.setText(AutoClaimScheduler.formatTime(hour, minute) + " ▾");
        if (scheduleTimeContainer != null) {
            scheduleTimeContainer.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }

        if (sendConfigContainer != null) {
            sendConfigContainer.setVisibility(autoSend ? View.VISIBLE : View.GONE);
        }
        if (sendConfigDivider != null) {
            sendConfigDivider.setVisibility(autoSend ? View.VISIBLE : View.GONE);
        }
        int distributeMode = appPrefs.getInt(
                ModulePrefs.KEY_SEND_HULIANG_DISTRIBUTE_MODE,
                ModulePrefs.DEFAULT_SEND_HULIANG_DISTRIBUTE_MODE);
        List<SavedSendTarget> savedTargets = readSavedSendTargets();
        if (sendTargetValueView != null) {
            String targetLabel;
            if (savedTargets.isEmpty()) {
                targetLabel = "全部粉丝牌 ▾";
            } else if (savedTargets.size() == 1) {
                SavedSendTarget only = savedTargets.get(0);
                targetLabel = (!only.name.isEmpty() ? only.name : ("UID:" + only.pid)) + " ▾";
            } else {
                SavedSendTarget first = savedTargets.get(0);
                String firstName = !first.name.isEmpty() ? first.name : ("UID:" + first.pid);
                if (firstName.length() > 5) {
                    firstName = firstName.substring(0, 5) + "…";
                }
                targetLabel = firstName + " 等 " + savedTargets.size() + " 人 ▾";
            }
            sendTargetValueView.setText(targetLabel);
        }
        if (sendDistributeContainer != null) {
            // Show distribution mode when targeting multiple anchors or all fan badges.
            sendDistributeContainer.setVisibility(savedTargets.size() == 1 ? View.GONE : View.VISIBLE);
        }
        if (sendDistributeValueView != null) {
            sendDistributeValueView.setText(formatDistributeModeShort(distributeMode) + " ▾");
        }
        if (sendMaxValueView != null) {
            sendMaxValueView.setText(sendMaxCount <= 0
                    ? "全部赠送 ▾"
                    : ("最多 " + sendMaxCount + " 个 ▾"));
        }

        SharedPreferences state = ModulePrefs.openStatePrefs(this);
        String activeReq = state.getString(ModulePrefs.STATE_ACTIVE_REQUEST_ID, "");
        long lastRunTime = state.getLong(ModulePrefs.STATE_LAST_RUN_TIME, 0L);
        String lastMsg = state.getString(ModulePrefs.STATE_LAST_RUN_MESSAGE, "");
        long nextRetryTime = state.getLong(ModulePrefs.STATE_NEXT_RETRY_TIME, 0L);
        long nextRescueTime = state.getLong(ModulePrefs.STATE_NEXT_RESCUE_TIME, 0L);
        String today = AutoClaimScheduler.todayDateString();
        String claimedDay = state.getString(ModulePrefs.STATE_TODAY_CLAIMED_DAY, "");
        int todayClaimed = today.equals(claimedDay)
                ? state.getInt(ModulePrefs.STATE_TODAY_CLAIMED_COUNT, 0) : 0;
        String checkinDay = state.getString(ModulePrefs.STATE_TODAY_CHECKIN_DAY, "");
        int todayCheckinDone = today.equals(checkinDay)
                ? state.getInt(ModulePrefs.STATE_TODAY_CHECKIN_COUNT, 0) : 0;
        int todayCheckinTotal = today.equals(checkinDay)
                ? state.getInt(ModulePrefs.STATE_TODAY_CHECKIN_TOTAL, 0) : 0;
        int freeBagCount = state.getInt(ModulePrefs.STATE_FREE_HULIANG_COUNT, -1);
        int superBagCount = state.getInt(ModulePrefs.STATE_SUPER_HULIANG_COUNT, 0);
        String superBagName = state.getString(ModulePrefs.STATE_SUPER_HULIANG_NAME, "");
        int freeExpiring24h = state.getInt(ModulePrefs.STATE_FREE_EXPIRING_24H, 0);
        String sfDayActiveSummary = state.getString(ModulePrefs.STATE_SF_DAY_ACTIVE_SUMMARY, "");

        if (statHuliangValueView != null) {
            if (todayClaimed > 0) {
                statHuliangValueView.setText(todayClaimed + " 次");
            } else if (lastMsg != null && (lastMsg.contains("上限") || lastMsg.contains("已全部领完"))) {
                statHuliangValueView.setText("今日已满");
            } else {
                statHuliangValueView.setText("0 次");
            }
        }
        if (statCheckinValueView != null) {
            if (todayCheckinTotal > 0) {
                statCheckinValueView.setText(todayCheckinDone + "/" + todayCheckinTotal + " 枚");
            } else {
                statCheckinValueView.setText("未同步");
            }
        }
        if (statFreeBagValueView != null) {
            if (freeBagCount >= 0) {
                statFreeBagValueView.setText(formatNum(freeBagCount) + " 个");
            } else {
                statFreeBagValueView.setText("未同步");
            }
        }
        if (statFreeBagSuperView != null) {
            if (superBagCount > 0) {
                String label = (superBagName != null && !superBagName.isEmpty())
                        ? superBagName : "超粉虎粮";
                if (label.length() > 4) {
                    label = "超粉";
                }
                statFreeBagSuperView.setText(label + " " + formatNum(superBagCount) + " 个");
                statFreeBagSuperView.setVisibility(View.VISIBLE);
            } else if (freeExpiring24h > 0) {
                statFreeBagSuperView.setText("临期 " + formatNum(freeExpiring24h) + " 个");
                statFreeBagSuperView.setVisibility(View.VISIBLE);
            } else {
                statFreeBagSuperView.setVisibility(View.GONE);
            }
        }

        updateSuperFanDayBanner(state, sfDayActiveSummary);

        boolean isRunning = activeReq != null && !activeReq.isEmpty()
                && (System.currentTimeMillis() - lastRunTime) < 120000L;
        if (claimNowBtn != null) {
            claimNowBtn.setEnabled(!isRunning);
            claimNowBtn.setText(isRunning ? "执行中..." : "立即执行");
            updateClaimButtonPulse(isRunning);
        }
        if (badgesRefreshBtn != null) {
            badgesRefreshBtn.setEnabled(!isRunning);
            badgesRefreshBtn.setText(isRunning ? "同步中..." : "同步刷新");
        }

        if (enabled) {
            StringBuilder sched = new StringBuilder();
            sched.append("下次定时 · ").append(AutoClaimScheduler.formatNextRunLabel(hour, minute));
            if (nextRetryTime > System.currentTimeMillis()) {
                String retryDt = AutoClaimScheduler.formatDateTime(nextRetryTime);
                int sp = retryDt.indexOf(' ');
                String retryShort = (sp >= 0 && retryDt.length() >= sp + 6)
                        ? retryDt.substring(sp + 1, sp + 6) : retryDt;
                sched.append("  (补领 ").append(retryShort).append(")");
            } else if (nextRescueTime > System.currentTimeMillis()) {
                String rescueDt = AutoClaimScheduler.formatDateTime(nextRescueTime);
                int sp = rescueDt.indexOf(' ');
                String rescueShort = (sp >= 0 && rescueDt.length() >= sp + 6)
                        ? rescueDt.substring(sp + 1, sp + 6) : rescueDt;
                sched.append("  (临期抢救 ").append(rescueShort).append(")");
            }
            autoClaimScheduleView.setText(sched.toString());
        } else if (nextRescueTime > System.currentTimeMillis()) {
            String rescueDt = AutoClaimScheduler.formatDateTime(nextRescueTime);
            autoClaimScheduleView.setText("临期抢救 · " + rescueDt);
        } else {
            autoClaimScheduleView.setText("定时未开启");
        }

        if (autoClaimLastTimeView != null) {
            if (lastRunTime > 0L) {
                String dt = AutoClaimScheduler.formatDateTime(lastRunTime);
                // Format "MM-dd HH:mm:ss" -> compact "HH:mm 已执行"
                int sp = dt.indexOf(' ');
                String shortTime = (sp >= 0 && dt.length() >= sp + 6)
                        ? dt.substring(sp + 1, sp + 6) : dt;
                autoClaimLastTimeView.setText(isRunning ? "执行中..." : (shortTime + " 已执行"));
            } else {
                autoClaimLastTimeView.setText("尚未运行");
            }
        }

        renderAutoClaimResultRows(lastRunTime, lastMsg);

        if (exactAlarmBtn != null) {
            boolean showExactBtn = enabled
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !AutoClaimScheduler.canScheduleExactAlarms(this);
            exactAlarmBtn.setVisibility(showExactBtn ? View.VISIBLE : View.GONE);
        }

        refreshBadgesCard(state, todayCheckinDone, todayCheckinTotal);
    }

    private void updateSuperFanDayBanner(SharedPreferences state, String sfDayActiveSummary) {
        if (sfDayBannerCard == null || sfDayBannerTitleView == null || sfDayBannerDescView == null) {
            return;
        }
        activeSfDayAnchorPid = 0L;
        activeSfDayAnchorNick = "";
        int bestTimes = 4;
        StringBuilder activeNames = new StringBuilder();
        int activeCount = 0;

        String badgesJson = state.getString(ModulePrefs.STATE_BADGES_JSON, "");
        if (badgesJson != null && !badgesJson.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(badgesJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) {
                        continue;
                    }
                    boolean isSf = obj.optBoolean("superFan", false)
                            || obj.optInt("sfType", 0) > 0
                            || (obj.optInt("fansIdentity", 0) >= 1 && obj.optInt("fansIdentity", 0) <= 4);
                    boolean sfDayActive = obj.optBoolean("sfDayActive", false);
                    if (isSf && sfDayActive) {
                        long pid = obj.optLong("pid", 0L);
                        String nick = obj.optString("nick", obj.optString("badgeName", ""));
                        int times = obj.optInt("sfDayScoreTimes", 4);
                        if (times > bestTimes) {
                            bestTimes = times;
                        }
                        if (activeCount == 0) {
                            activeSfDayAnchorPid = pid;
                            activeSfDayAnchorNick = nick;
                        } else {
                            activeSfDayAnchorPid = 0L;
                            activeSfDayAnchorNick = "";
                        }
                        if (activeNames.length() > 0) {
                            activeNames.append("、");
                        }
                        activeNames.append(nick);
                        activeCount++;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (activeCount > 0) {
            sfDayBannerTitleView.setText("今日超粉日 · " + bestTimes + " 倍亲密度");
            sfDayBannerDescView.setText(activeNames + " 正在超粉日中，送礼享 " + bestTimes + " 倍亲密度");
            if (sfDayBannerActionBtn != null) {
                sfDayBannerActionBtn.setText(activeCount == 1 ? "去送粮" : "查看");
            }
            sfDayBannerCard.setVisibility(View.VISIBLE);
        } else if (sfDayActiveSummary != null && !sfDayActiveSummary.trim().isEmpty()) {
            sfDayBannerTitleView.setText("今日超粉日 · 4 倍亲密度");
            sfDayBannerDescView.setText(sfDayActiveSummary.trim());
            if (sfDayBannerActionBtn != null) {
                sfDayBannerActionBtn.setText("查看");
            }
            sfDayBannerCard.setVisibility(View.VISIBLE);
        } else {
            sfDayBannerCard.setVisibility(View.GONE);
        }
    }

    private void renderAutoClaimResultRows(long lastRunTime, String lastMsg) {
        if (autoClaimResultList == null || autoClaimStatusDivider == null) {
            return;
        }
        autoClaimResultList.removeAllViews();
        String trimmed = lastMsg != null ? lastMsg.trim() : "";
        if (lastRunTime <= 0L && trimmed.isEmpty()) {
            autoClaimStatusDivider.setVisibility(View.GONE);
            autoClaimResultList.setVisibility(View.GONE);
            return;
        }
        autoClaimStatusDivider.setVisibility(View.VISIBLE);
        autoClaimResultList.setVisibility(View.VISIBLE);

        if (trimmed.isEmpty()) {
            addPlainStatusLine("暂无执行明细");
            return;
        }

        String[] parts = trimmed.split("[；;]");
        int added = 0;
        for (String rawPart : parts) {
            String part = rawPart != null ? rawPart.trim() : "";
            if (part.isEmpty()) {
                continue;
            }
            int colonIdx = part.indexOf('：');
            if (colonIdx < 0) {
                colonIdx = part.indexOf(':');
            }
            if (colonIdx > 0 && colonIdx < part.length() - 1) {
                String key = shortenStatusKey(part.substring(0, colonIdx).trim());
                String val = shortenStatusVal(part.substring(colonIdx + 1).trim());
                addStructuredStatusRow(key, val, added > 0);
            } else {
                addPlainStatusLine(part, added > 0);
            }
            added++;
        }
        if (added == 0) {
            addPlainStatusLine(trimmed);
        }
    }

    private String shortenStatusKey(String rawKey) {
        if ("粉丝牌打卡".equals(rawKey)) {
            return "打卡";
        }
        if ("每日福利".equals(rawKey)) {
            return "福利";
        }
        if ("送虎粮".equals(rawKey) || "自动送虎粮".equals(rawKey)) {
            return "送礼";
        }
        if ("领虎粮".equals(rawKey)) {
            return "虎粮";
        }
        return rawKey.length() > 3 ? rawKey.substring(0, 3) : rawKey;
    }

    private String shortenStatusVal(String rawVal) {
        if ("已达今日次数上限，明日再来".equals(rawVal)) {
            return "今日已领满";
        }
        return rawVal;
    }

    private void updateClaimButtonPulse(boolean running) {
        if (claimNowBtn == null) {
            return;
        }
        if (running) {
            if (claimPulseAnimator == null) {
                claimPulseAnimator = ObjectAnimator.ofFloat(claimNowBtn, View.ALPHA, 1.0f, 0.62f);
                claimPulseAnimator.setDuration(750L);
                claimPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
                claimPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
                claimPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
                claimPulseAnimator.start();
            }
        } else {
            if (claimPulseAnimator != null) {
                claimPulseAnimator.cancel();
                claimPulseAnimator = null;
            }
            claimNowBtn.setAlpha(1.0f);
        }
    }

    private void addStructuredStatusRow(String tag, String value, boolean addTopMargin) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        if (addTopMargin) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);
        }

        boolean isError = value.contains("失败") || value.contains("异常")
                || value.contains("未登录") || value.contains("超时")
                || value.contains("错误") || value.contains("无法");
        boolean isWarn = !isError && (value.contains("冷却") || value.contains("补领")
                || value.contains("稍后") || value.contains("等待") || value.contains("部分"));

        int tagBg;
        int tagStroke;
        int tagFg;
        int valFg;
        if (isError) {
            tagBg = cErrBg;
            tagStroke = cErrText;
            tagFg = cErrText;
            valFg = cErrText;
        } else if (isWarn) {
            tagBg = cBrandTintBg;
            tagStroke = cBrandStart;
            tagFg = cBrandText;
            valFg = cPrimaryText;
        } else {
            tagBg = isDarkTheme ? cNeutralBg : cCard;
            tagStroke = cCardBorder;
            tagFg = cSecondaryText;
            valFg = cPrimaryText;
        }

        TextView tagView = new TextView(this);
        tagView.setText(tag);
        tagView.setTextSize(10.5f);
        tagView.setTypeface(Typeface.DEFAULT_BOLD);
        tagView.setTextColor(tagFg);
        tagView.setGravity(Gravity.CENTER);
        tagView.setSingleLine(true);
        tagView.setBackground(createRoundedRect(tagBg, 6, 1, tagStroke));
        tagView.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT);
        tagLp.topMargin = dp(1);
        tagLp.rightMargin = dp(8);
        tagView.setLayoutParams(tagLp);
        row.addView(tagView);

        TextView valView = new TextView(this);
        valView.setText(value);
        valView.setTextSize(12.5f);
        valView.setTextColor(valFg);
        valView.setLineSpacing(dp(2), 1.0f);
        valView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(valView);

        autoClaimResultList.addView(row);
    }

    private void addPlainStatusLine(String text) {
        addPlainStatusLine(text, false);
    }

    private void addPlainStatusLine(String text, boolean addTopMargin) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12.5f);
        tv.setTextColor(cSecondaryText);
        tv.setLineSpacing(dp(2), 1.0f);
        if (addTopMargin) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(5);
            tv.setLayoutParams(lp);
        }
        autoClaimResultList.addView(tv);
    }

    // ── Fan Badges Rendering (Native + Custom + Icon-only SuperFan) ──────────

    private void refreshBadgesCard(SharedPreferences state, int todayCheckinDone, int todayCheckinTotal) {
        if (badgesContainer == null || badgesMetaView == null) {
            return;
        }
        badgesContainer.removeAllViews();
        badgeRowViews.clear();
        badgeFillViews.clear();

        String badgesJson = state.getString(ModulePrefs.STATE_BADGES_JSON, "");
        long updateTime = state.getLong(ModulePrefs.STATE_BADGES_UPDATE_TIME, 0L);

        if (badgeStatSyncTimeView != null) {
            if (updateTime > 0L) {
                String dt = AutoClaimScheduler.formatDateTime(updateTime);
                int sp = dt.indexOf(' ');
                badgeStatSyncTimeView.setText(sp > 0 ? dt.substring(sp + 1, Math.min(dt.length(), sp + 6)) : dt);
            } else {
                badgeStatSyncTimeView.setText("未同步");
            }
        }

        if (badgesJson == null || badgesJson.isEmpty()) {
            if (badgeStatCountView != null) {
                badgeStatCountView.setText("0 枚");
            }
            if (badgeStatCheckinView != null) {
                badgeStatCheckinView.setText("未同步");
            }
            badgesMetaView.setText("等待同步");
            TextView empty = new TextView(this);
            empty.setText("点击右上角「同步刷新」后，将展示虎牙原生/定制粉丝牌、守护权益与升级进度。");
            empty.setTextSize(13f);
            empty.setLineSpacing(dp(4), 1.0f);
            empty.setTextColor(cSecondaryText);
            empty.setPadding(0, dp(16), 0, dp(16));
            badgesContainer.addView(empty);
            return;
        }

        try {
            JSONArray arr = new JSONArray(badgesJson);
            if (badgeStatCountView != null) {
                badgeStatCountView.setText(arr.length() + " 枚");
            }
            if (badgeStatCheckinView != null) {
                int done = todayCheckinTotal > 0 ? todayCheckinDone : countCheckedIn(arr);
                int tot = todayCheckinTotal > 0 ? todayCheckinTotal : arr.length();
                badgeStatCheckinView.setText(done + "/" + tot);
            }
            if (arr.length() == 0) {
                badgesMetaView.setText("暂无");
                TextView empty = new TextView(this);
                empty.setText("当前虎牙账号暂无粉丝牌");
                empty.setTextSize(13f);
                empty.setTextColor(cSecondaryText);
                empty.setPadding(0, dp(16), 0, dp(16));
                badgesContainer.addView(empty);
                return;
            }

            int guardCount = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && obj.optInt("guardLevel", 0) > 0) {
                    guardCount++;
                }
            }

            StringBuilder metaSb = new StringBuilder();
            metaSb.append("共 ").append(arr.length()).append(" 枚");
            if (guardCount > 0) {
                metaSb.append(" · ").append(guardCount).append(" 守护");
            }
            badgesMetaView.setText(metaSb.toString());

            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                View rowView = buildBadgeItemView(obj);
                badgeRowViews.add(rowView);
                badgesContainer.addView(rowView);
                if (i < arr.length() - 1) {
                    badgesContainer.addView(createDivider());
                }
            }
            if (currentTab == TAB_BADGES) {
                playBadgesEntranceAnimation();
            }
        } catch (Throwable t) {
            badgesMetaView.setText("解析异常");
            TextView err = new TextView(this);
            err.setText("粉丝牌数据解析失败，请点击「同步刷新」重新拉取。");
            err.setTextSize(13f);
            err.setTextColor(cSecondaryText);
            err.setPadding(0, dp(16), 0, dp(16));
            badgesContainer.addView(err);
        }
    }

    private int countCheckedIn(JSONArray arr) {
        int c = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj != null) {
                String st = obj.optString("checkinState", "");
                if ("success".equals(st) || "already".equals(st)) {
                    c++;
                }
            }
        }
        return c;
    }

    private Bitmap decodeCachedBase64Bitmap(String b64) {
        if (b64 == null || b64.isEmpty()) {
            return null;
        }
        Bitmap cached = nativeBadgeBitmapCache.get(b64);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        try {
            byte[] raw = Base64.decode(b64, Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (bmp != null) {
                if (nativeBadgeBitmapCache.size() > 64) {
                    nativeBadgeBitmapCache.clear();
                }
                nativeBadgeBitmapCache.put(b64, bmp);
            }
            return bmp;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String resolveGuardLabel(int guardLevel, String guardName) {
        if (guardName != null && !guardName.isEmpty()) {
            return guardName;
        }
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

    private View buildBadgeItemView(JSONObject obj) {
        String nick = obj.optString("nick", "");
        String badgeName = obj.optString("badgeName", "粉丝牌");
        int level = obj.optInt("level", 0);
        int score = obj.optInt("score", 0);
        int nextScore = obj.optInt("nextScore", 0);
        int dayScore = obj.optInt("dayScore", 0);
        int quota = obj.optInt("quota", 0);
        int quotaUsed = obj.optInt("quotaUsed", 0);
        int todayIntimacy = dayScore + Math.max(0, quotaUsed);
        int extinguished = obj.optInt("extinguished", 0);
        boolean superFan = obj.optBoolean("superFan", false);
        int sfType = obj.optInt("sfType", 0);
        int sfVariety = obj.optInt("sfVariety", 0);
        int fansIdentity = obj.optInt("fansIdentity", 0);
        int guardLevel = obj.optInt("guardLevel", 0);
        String guardName = obj.optString("guardName", "");
        int guardAccompanyDay = obj.optInt("guardAccompanyDay", 0);
        String faithName = obj.optString("faithName", "");
        String badgePngB64 = obj.optString("badgePngB64", "");
        String guardPngB64 = obj.optString("guardPngB64", "");
        String checkinState = obj.optString("checkinState", "");
        boolean sfDayActive = obj.optBoolean("sfDayActive", false);
        int sfDay = obj.optInt("sfDay", 0);
        int sfDayScoreTimes = obj.optInt("sfDayScoreTimes", 0);
        long nextSfDayEpoch = obj.optLong("nextSfDayEpoch", 0L);
        long pid = obj.optLong("pid", 0L);
        long curTargetPid = appPrefs != null
                ? appPrefs.getLong(ModulePrefs.KEY_SEND_HULIANG_TARGET_PID, ModulePrefs.DEFAULT_SEND_HULIANG_TARGET_PID)
                : 0L;
        boolean isSendTarget = pid > 0L && pid == curTargetPid;

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(0, dp(13), 0, dp(13));
        item.setClickable(true);
        item.setFocusable(true);
        item.setBackground(new RippleDrawable(
                ColorStateList.valueOf(isDarkTheme ? Color.parseColor("#18FFFFFF") : Color.parseColor("#10000000")),
                null,
                createRoundedRect(Color.WHITE, 10, 0, 0)));
        item.setOnClickListener(v -> showBadgeActionDialog(obj, isSendTarget));

        int[] tierColors = resolveBadgeTierColors(level, extinguished == 1);

        // Row 1: [Native/Custom Fan Badge (already includes golden V/V+/V年 icon when SuperFan)] AnchorNick [打卡状态]
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        Bitmap nativeBadgeBmp = decodeCachedBase64Bitmap(badgePngB64);
        boolean isSuperFanAny = superFan || sfType > 0 || (fansIdentity >= 1 && fansIdentity <= 4);
        if (nativeBadgeBmp != null) {
            ImageView badgeImg = new ImageView(this);
            badgeImg.setImageBitmap(nativeBadgeBmp);
            badgeImg.setAdjustViewBounds(true);
            badgeImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(23));
            badgeImg.setLayoutParams(imgLp);
            topRow.addView(badgeImg);
        } else {
            TextView badgePill = new TextView(this);
            badgePill.setText("Lv." + level + " " + badgeName);
            badgePill.setTextSize(12f);
            badgePill.setTypeface(Typeface.DEFAULT_BOLD);
            badgePill.setTextColor(Color.WHITE);
            badgePill.setPadding(dp(9), dp(3), dp(9), dp(3));
            GradientDrawable pillBg = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, tierColors);
            pillBg.setCornerRadius(dp(99));
            badgePill.setBackground(pillBg);
            topRow.addView(badgePill);

            // Fallback SuperFan icon-only badge (golden V / V+ vector icon, never writing "超粉" text).
            if (isSuperFanAny) {
                boolean isPlus = fansIdentity == 3 || fansIdentity == 4 || sfVariety != 0;
                ImageView sfIconView = new ImageView(this);
                sfIconView.setImageDrawable(new SuperFanIconDrawable(isPlus));
                LinearLayout.LayoutParams sfLp = new LinearLayout.LayoutParams(dp(18), dp(18));
                sfLp.leftMargin = dp(4);
                sfIconView.setLayoutParams(sfLp);
                topRow.addView(sfIconView);
            }
        }

        TextView nickView = new TextView(this);
        nickView.setText(nick.isEmpty() ? badgeName : nick);
        nickView.setTextSize(14.5f);
        nickView.setTypeface(Typeface.DEFAULT_BOLD);
        nickView.setTextColor(cPrimaryText);
        nickView.setSingleLine(true);
        nickView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nickLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nickLp.leftMargin = dp(8);
        nickLp.rightMargin = dp(8);
        nickView.setLayoutParams(nickLp);
        topRow.addView(nickView);

        String stateLabel;
        int stateFg;
        int stateBg;
        if ("success".equals(checkinState)) {
            stateLabel = "本次已打卡";
            stateFg = cOkText;
            stateBg = cOkBg;
        } else if ("already".equals(checkinState)) {
            stateLabel = "今日已打卡";
            stateFg = cOkText;
            stateBg = cOkBg;
        } else if ("failed".equals(checkinState)) {
            stateLabel = "打卡失败";
            stateFg = cErrText;
            stateBg = cErrBg;
        } else {
            stateLabel = "待打卡";
            stateFg = cSecondaryText;
            stateBg = cNeutralBg;
        }
        TextView statePill = createPill(stateLabel, stateFg, stateBg, 11f, true);
        statePill.setPadding(dp(8), dp(3), dp(8), dp(3));
        topRow.addView(statePill);
        item.addView(topRow);

        // Row 2 (optional): 超粉日, 守护 (初爱/超级/至尊), 信仰, or 已熄灭.
        // SuperFan / SuperFan Plus is represented purely by Huya's native golden V / V+ / V年 icon on Row 1.
        String resolvedGuardLabel = resolveGuardLabel(guardLevel, guardName);
        String sfDayChipText = formatSuperFanDayChip(isSuperFanAny, sfDayActive, sfDay, sfDayScoreTimes, nextSfDayEpoch);
        boolean hasExtraChips = !sfDayChipText.isEmpty()
                || !resolvedGuardLabel.isEmpty()
                || !faithName.isEmpty()
                || extinguished == 1;
        if (hasExtraChips) {
            LinearLayout privRow = new LinearLayout(this);
            privRow.setOrientation(LinearLayout.HORIZONTAL);
            privRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            prLp.topMargin = dp(7);
            privRow.setLayoutParams(prLp);

            if (!sfDayChipText.isEmpty()) {
                int sfFg;
                int sfBg;
                if (sfDayActive) {
                    sfFg = isDarkTheme ? Color.parseColor("#FDE68A") : Color.parseColor("#B45309");
                    sfBg = isDarkTheme ? Color.parseColor("#422A0E") : Color.parseColor("#FEF3C7");
                } else if (isSuperFanAny) {
                    sfFg = cBrandText;
                    sfBg = cBrandTintBg;
                } else {
                    sfFg = cSecondaryText;
                    sfBg = cNeutralBg;
                }
                TextView sfDayPill = createPill(sfDayChipText, sfFg, sfBg, 11f, true);
                sfDayPill.setPadding(dp(7), dp(2), dp(7), dp(2));
                if (sfDayActive) {
                    sfDayPill.setBackground(createRoundedRect(sfBg, 99, 1, cBrandStart));
                }
                LinearLayout.LayoutParams sfLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                sfLp.rightMargin = dp(6);
                sfDayPill.setLayoutParams(sfLp);
                privRow.addView(sfDayPill);
            }

            if (!resolvedGuardLabel.isEmpty()) {
                LinearLayout guardChip = new LinearLayout(this);
                guardChip.setOrientation(LinearLayout.HORIZONTAL);
                guardChip.setGravity(Gravity.CENTER_VERTICAL);
                int guardFg;
                int guardBg;
                if (guardLevel == 3) {
                    guardFg = isDarkTheme ? Color.parseColor("#FB923C") : Color.parseColor("#C2410C");
                    guardBg = isDarkTheme ? Color.parseColor("#3B1E10") : Color.parseColor("#FFEDD5");
                } else if (guardLevel == 2) {
                    guardFg = isDarkTheme ? Color.parseColor("#C084FC") : Color.parseColor("#6D28D9");
                    guardBg = isDarkTheme ? Color.parseColor("#2E1B4B") : Color.parseColor("#F3E8FF");
                } else {
                    guardFg = isDarkTheme ? Color.parseColor("#F472B6") : Color.parseColor("#BE185D");
                    guardBg = isDarkTheme ? Color.parseColor("#3B182C") : Color.parseColor("#FCE7F3");
                }
                guardChip.setBackground(createRoundedRect(guardBg, 99, 0, 0));
                guardChip.setPadding(dp(6), dp(2), dp(8), dp(2));

                Bitmap guardBmp = decodeCachedBase64Bitmap(guardPngB64);
                if (guardBmp != null) {
                    ImageView gImg = new ImageView(this);
                    gImg.setImageBitmap(guardBmp);
                    gImg.setAdjustViewBounds(true);
                    gImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(dp(16), dp(16));
                    gLp.rightMargin = dp(4);
                    gImg.setLayoutParams(gLp);
                    guardChip.addView(gImg);
                }
                TextView gTv = new TextView(this);
                gTv.setText(guardAccompanyDay > 0
                        ? (resolvedGuardLabel + " · " + guardAccompanyDay + "天")
                        : resolvedGuardLabel);
                gTv.setTextSize(11f);
                gTv.setTypeface(Typeface.DEFAULT_BOLD);
                gTv.setTextColor(guardFg);
                guardChip.addView(gTv);

                LinearLayout.LayoutParams gcLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                gcLp.rightMargin = dp(6);
                guardChip.setLayoutParams(gcLp);
                privRow.addView(guardChip);
            }

            if (!faithName.isEmpty()) {
                TextView faithPill = createPill(
                        faithName,
                        isDarkTheme ? Color.parseColor("#93C5FD") : Color.parseColor("#1D4ED8"),
                        isDarkTheme ? Color.parseColor("#1E2D4A") : Color.parseColor("#DBEAFE"),
                        11f, true);
                faithPill.setPadding(dp(7), dp(2), dp(7), dp(2));
                LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                fLp.rightMargin = dp(6);
                faithPill.setLayoutParams(fLp);
                privRow.addView(faithPill);
            }

            if (extinguished == 1) {
                TextView extPill = createPill("已熄灭", cSecondaryText, cNeutralBg, 11f, false);
                extPill.setPadding(dp(7), dp(2), dp(7), dp(2));
                privRow.addView(extPill);
            }

            item.addView(privRow);
        }

        // Experience progress bar
        int targetTotal;
        int remaining;
        if (nextScore > score) {
            targetTotal = nextScore;
            remaining = nextScore - score;
        } else if (nextScore > 0) {
            targetTotal = score + nextScore;
            remaining = nextScore;
        } else {
            targetTotal = Math.max(score, 1);
            remaining = 0;
        }
        float ratio = targetTotal > 0 ? Math.max(0f, Math.min(1f, (float) score / (float) targetTotal)) : 0f;

        LinearLayout barTrack = new LinearLayout(this);
        barTrack.setOrientation(LinearLayout.HORIZONTAL);
        barTrack.setBackground(createRoundedRect(cTrackBg, 99, 0, 0));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6));
        trackLp.topMargin = dp(10);
        barTrack.setLayoutParams(trackLp);

        float fillWeight = Math.max(ratio > 0f ? 0.03f : 0f, ratio);
        View fillView = new View(this);
        GradientDrawable fillBg = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, tierColors);
        fillBg.setCornerRadius(dp(99));
        fillView.setBackground(fillBg);
        fillView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, fillWeight));
        badgeFillViews.add(fillView);
        barTrack.addView(fillView);

        if (fillWeight < 1f) {
            View emptyView = new View(this);
            emptyView.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - fillWeight));
            barTrack.addView(emptyView);
        }
        item.addView(barTrack);

        // Experience numbers + Today's intimacy
        LinearLayout expRow = new LinearLayout(this);
        expRow.setOrientation(LinearLayout.HORIZONTAL);
        expRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams expRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        expRowLp.topMargin = dp(7);
        expRow.setLayoutParams(expRowLp);

        String expText;
        if (remaining > 0) {
            expText = "经验 " + formatNum(score) + " / " + formatNum(targetTotal)
                    + " · 还差 " + formatNum(remaining);
        } else if (score > 0) {
            expText = "经验 " + formatNum(score);
        } else {
            expText = "经验 0";
        }
        TextView expLeft = new TextView(this);
        expLeft.setText(expText);
        expLeft.setTextSize(12f);
        expLeft.setTextColor(cSecondaryText);
        expLeft.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        expRow.addView(expLeft);

        String todayText = quota > 0
                ? ("今日 " + formatNum(todayIntimacy) + "/" + formatNum(quota))
                : ("今日 +" + formatNum(todayIntimacy));
        TextView expRight = new TextView(this);
        expRight.setText(todayText);
        expRight.setTextSize(12f);
        expRight.setTypeface(Typeface.DEFAULT_BOLD);
        expRight.setTextColor(todayIntimacy > 0 ? cBrandText : cTertiaryText);
        expRow.addView(expRight);

        item.addView(expRow);
        return item;
    }

    private void showBadgeActionDialog(final JSONObject obj, final boolean isSendTarget) {
        final long pid = obj.optLong("pid", 0L);
        final String nick = obj.optString("nick", "");
        final String badgeName = obj.optString("badgeName", "粉丝牌");
        final int level = obj.optInt("level", 0);
        final int score = obj.optInt("score", 0);
        final int nextScore = obj.optInt("nextScore", 0);
        final int dayScore = obj.optInt("dayScore", 0);
        final int quota = obj.optInt("quota", 0);
        final int quotaUsed = obj.optInt("quotaUsed", 0);
        final int todayIntimacy = dayScore + Math.max(0, quotaUsed);
        final int guardLevel = obj.optInt("guardLevel", 0);
        final String guardName = obj.optString("guardName", "");
        final int guardAccompanyDay = obj.optInt("guardAccompanyDay", 0);
        final boolean isSuperFan = obj.optBoolean("superFan", false)
                || obj.optInt("sfType", 0) > 0
                || obj.optInt("fansIdentity", 0) > 0;
        final boolean sfDayActive = obj.optBoolean("sfDayActive", false);
        final int sfDay = obj.optInt("sfDay", 0);
        final int sfDayScoreTimes = obj.optInt("sfDayScoreTimes", 0);
        final int sfDayQuotaTimes = obj.optInt("sfDayQuotaTimes", 0);
        final long nextSfDayEpoch = obj.optLong("nextSfDayEpoch", 0L);
        final String displayName = !nick.isEmpty() ? nick : badgeName;

        int remaining = nextScore > score ? (nextScore - score) : (nextScore > 0 ? nextScore : 0);
        StringBuilder sb = new StringBuilder();
        sb.append("粉丝牌：Lv.").append(level).append(" ").append(badgeName);
        if (pid > 0L) {
            sb.append("\n主播 UID：").append(pid);
        }
        sb.append("\n当前经验：").append(formatNum(score));
        if (remaining > 0) {
            sb.append("（升级还差 ").append(formatNum(remaining)).append("）");
        }
        sb.append("\n今日亲密度：").append(formatNum(todayIntimacy));
        if (quota > 0) {
            sb.append(" / ").append(formatNum(quota));
        }
        String guardLabel = resolveGuardLabel(guardLevel, guardName);
        if (!guardLabel.isEmpty()) {
            sb.append("\n守护权益：").append(guardLabel);
            if (guardAccompanyDay > 0) {
                sb.append("（陪伴 ").append(guardAccompanyDay).append(" 天）");
            }
        }
        if (isSuperFan) {
            sb.append("\n超粉状态：已开通");
        }
        String sfDayDetail = formatSuperFanDayDetail(isSuperFan, sfDayActive, sfDay, sfDayScoreTimes, sfDayQuotaTimes, nextSfDayEpoch);
        if (!sfDayDetail.isEmpty()) {
            sb.append("\n超粉日：").append(sfDayDetail);
        }
        if (isSendTarget) {
            sb.append("\n自动送礼：当前已设为赠送目标");
        }

        HubanDialogs.showMessageModal(
                this, dialogPalette,
                displayName,
                sb.toString(),
                pid > 0L ? "赠送虎粮" : "知道了",
                pid > 0L ? () -> showManualSendGiftDialog(pid, displayName) : null,
                pid > 0L ? "关闭" : null,
                null,
                pid > 0L ? "复制 UID" : null,
                pid > 0L ? () -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("Huya UID", String.valueOf(pid)));
                            Toast.makeText(this, "已复制主播 UID：" + pid, Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable ignored) {
                    }
                } : null
        );
    }

    private void showManualSendGiftDialog(final long pid, final String anchorNick) {
        SharedPreferences state = ModulePrefs.openStatePrefs(this);
        final int freeBag = state.getInt(ModulePrefs.STATE_FREE_HULIANG_COUNT, -1);
        final int superBag = state.getInt(ModulePrefs.STATE_SUPER_HULIANG_COUNT, 0);
        String rawSuperName = state.getString(ModulePrefs.STATE_SUPER_HULIANG_NAME, "");
        final String superLabel = (rawSuperName != null && !rawSuperName.isEmpty()) ? rawSuperName : "超粉虎粮";

        final String[] selectedKind = new String[]{"normal"};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Gift type selector row: [免费虎粮] [超粉虎粮]
        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);

        final LinearLayout normalCard = new LinearLayout(this);
        normalCard.setOrientation(LinearLayout.VERTICAL);
        normalCard.setGravity(Gravity.CENTER);
        normalCard.setPadding(dp(10), dp(11), dp(10), dp(11));
        LinearLayout.LayoutParams ncLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ncLp.rightMargin = dp(8);
        normalCard.setLayoutParams(ncLp);

        final TextView normalTitle = new TextView(this);
        normalTitle.setText("免费虎粮");
        normalTitle.setTextSize(14f);
        normalTitle.setTypeface(Typeface.DEFAULT_BOLD);
        normalCard.addView(normalTitle);

        TextView normalSub = new TextView(this);
        normalSub.setText(freeBag >= 0 ? ("背包剩余 " + formatNum(freeBag) + " 个") : "剩余待同步");
        normalSub.setTextSize(11.5f);
        normalSub.setTextColor(cSecondaryText);
        LinearLayout.LayoutParams nsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nsLp.topMargin = dp(2);
        normalSub.setLayoutParams(nsLp);
        normalCard.addView(normalSub);
        typeRow.addView(normalCard);

        final LinearLayout superCard = new LinearLayout(this);
        superCard.setOrientation(LinearLayout.VERTICAL);
        superCard.setGravity(Gravity.CENTER);
        superCard.setPadding(dp(10), dp(11), dp(10), dp(11));
        superCard.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView superTitle = new TextView(this);
        superTitle.setText(superLabel);
        superTitle.setTextSize(14f);
        superTitle.setTypeface(Typeface.DEFAULT_BOLD);
        superCard.addView(superTitle);

        TextView superSub = new TextView(this);
        superSub.setText(superBag > 0 ? ("背包剩余 " + formatNum(superBag) + " 个") : "当前暂无");
        superSub.setTextSize(11.5f);
        superSub.setTextColor(cSecondaryText);
        LinearLayout.LayoutParams ssLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ssLp.topMargin = dp(2);
        superSub.setLayoutParams(ssLp);
        superCard.addView(superSub);
        typeRow.addView(superCard);

        root.addView(typeRow);

        final Runnable updateTypeStyles = () -> {
            boolean isNormal = "normal".equals(selectedKind[0]);
            normalCard.setBackground(createRoundedRect(
                    isNormal ? cBrandTintBg : cSubSurface, 12, 1, isNormal ? cBrandStart : cCardBorder));
            normalTitle.setTextColor(isNormal ? cBrandText : cPrimaryText);

            superCard.setBackground(createRoundedRect(
                    !isNormal ? cBrandTintBg : cSubSurface, 12, 1, !isNormal ? cBrandStart : cCardBorder));
            superTitle.setTextColor(!isNormal ? cBrandText : cPrimaryText);
        };
        updateTypeStyles.run();

        normalCard.setOnClickListener(v -> {
            selectedKind[0] = "normal";
            updateTypeStyles.run();
        });
        superCard.setOnClickListener(v -> {
            selectedKind[0] = "super";
            updateTypeStyles.run();
        });

        // Quantity input (custom rounded surface, zero native underline)
        TextView qtyLabel = new TextView(this);
        qtyLabel.setText("赠送数量（留空或填 0 表示全部送出）");
        qtyLabel.setTextSize(12.5f);
        qtyLabel.setTextColor(cSecondaryText);
        LinearLayout.LayoutParams qlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qlLp.topMargin = dp(14);
        qtyLabel.setLayoutParams(qlLp);
        root.addView(qtyLabel);

        final EditText countInput = new EditText(this);
        countInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        countInput.setHint("输入个数，默认 10");
        countInput.setTextColor(cPrimaryText);
        countInput.setHintTextColor(cTertiaryText);
        countInput.setTextSize(15f);
        countInput.setSingleLine(true);
        countInput.setBackground(createRoundedRect(cSubSurface, 12, 1, cCardBorder));
        countInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        countInput.setText("10");
        countInput.setSelection(countInput.getText().length());
        LinearLayout.LayoutParams ciLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ciLp.topMargin = dp(8);
        countInput.setLayoutParams(ciLp);
        root.addView(countInput);

        // Quick preset chips
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        prLp.topMargin = dp(10);
        presetRow.setLayoutParams(prLp);

        final int[] presetVals = new int[]{1, 10, 50, 0};
        final String[] presetLabels = new String[]{"1 个", "10 个", "50 个", "全部"};
        for (int i = 0; i < presetVals.length; i++) {
            final int val = presetVals[i];
            TextView chip = createPill(presetLabels[i], cPrimaryText, cSubSurface, 12f, true);
            chip.setBackground(createRoundedRect(cSubSurface, 99, 1, cCardBorder));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(10), dp(6), dp(10), dp(6));
            LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i < presetVals.length - 1) {
                chLp.rightMargin = dp(6);
            }
            chip.setLayoutParams(chLp);
            chip.setOnClickListener(v -> {
                if (val == 0) {
                    countInput.setText("");
                    countInput.setHint("全部免费数量");
                } else {
                    countInput.setText(String.valueOf(val));
                    countInput.setSelection(countInput.getText().length());
                }
            });
            presetRow.addView(chip);
        }
        root.addView(presetRow);

        TextView safeHint = new TextView(this);
        safeHint.setText("严格锁定包裹免费通道，绝不消耗金豆与虎牙币");
        safeHint.setTextSize(11.5f);
        safeHint.setTextColor(cTertiaryText);
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        shLp.topMargin = dp(10);
        safeHint.setLayoutParams(shLp);
        root.addView(safeHint);

        HubanDialogs.showCustomModal(
                this, dialogPalette,
                "向「" + anchorNick + "」送虎粮",
                root,
                true,
                "立即赠送",
                handle -> {
                    String raw = countInput.getText() != null ? countInput.getText().toString().trim() : "";
                    int count = 0;
                    if (!raw.isEmpty()) {
                        try {
                            count = Integer.parseInt(raw);
                            if (count < 0) {
                                Toast.makeText(this, "请输入有效的个数", Toast.LENGTH_SHORT).show();
                                return;
                            }
                        } catch (NumberFormatException e) {
                            Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show();
                            return;
                        }
                    }
                    handle.dismiss();
                    final String kind = selectedKind[0];
                    final String giftText = "super".equals(kind) ? superLabel : "虎粮";
                    Toast.makeText(this, "正在向「" + anchorNick + "」赠送" + giftText + "...", Toast.LENGTH_SHORT).show();
                    AutoClaimScheduler.sendManualGift(this, pid, anchorNick, kind, count, (ok, msg) -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        refreshAutoClaimStatus();
                        Toast.makeText(MainActivity.this,
                                (msg != null && !msg.isEmpty()) ? msg : (ok ? "赠送成功" : "赠送失败"),
                                Toast.LENGTH_LONG).show();
                    });
                },
                "取消", null,
                null, null
        );
    }

    private String formatSuperFanDayChip(
            boolean isSuperFan,
            boolean sfDayActive,
            int sfDay,
            int sfDayScoreTimes,
            long nextSfDayEpoch
    ) {
        int times = sfDayScoreTimes > 1 ? sfDayScoreTimes : 4;
        if (sfDayActive) {
            return isSuperFan ? ("超粉日 · " + times + "倍") : ("今日超粉日");
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        if (nextSfDayEpoch > nowSec) {
            long days = Math.max(1L, (nextSfDayEpoch - nowSec + 86399L) / 86400L);
            if (days <= 7L) {
                return days + "天后超粉日";
            }
        }
        if (sfDay >= 1 && sfDay <= 31) {
            return "每月" + sfDay + "日超粉日";
        }
        return "";
    }

    private String formatSuperFanDayDetail(
            boolean isSuperFan,
            boolean sfDayActive,
            int sfDay,
            int sfDayScoreTimes,
            int sfDayQuotaTimes,
            long nextSfDayEpoch
    ) {
        int times = sfDayScoreTimes > 1 ? sfDayScoreTimes : 4;
        if (sfDayActive) {
            StringBuilder s = new StringBuilder();
            s.append("今日生效（").append(times).append(" 倍亲密度");
            if (sfDayQuotaTimes > 1) {
                s.append(" / 上限 ").append(sfDayQuotaTimes).append(" 倍");
            }
            s.append("）");
            return s.toString();
        }
        if (sfDay >= 1 && sfDay <= 31) {
            StringBuilder s = new StringBuilder();
            s.append("每月 ").append(sfDay).append(" 日");
            long nowSec = System.currentTimeMillis() / 1000L;
            if (nextSfDayEpoch > nowSec) {
                long days = Math.max(1L, (nextSfDayEpoch - nowSec + 86399L) / 86400L);
                s.append("（还有 ").append(days).append(" 天");
                if (sfDayScoreTimes > 1) {
                    s.append("，").append(sfDayScoreTimes).append(" 倍亲密度");
                }
                s.append("）");
            }
            return s.toString();
        }
        if (sfDay >= 20200101) {
            int month = (sfDay / 100) % 100;
            int day = sfDay % 100;
            return month + " 月 " + day + " 日（" + times + " 倍亲密度）";
        }
        return isSuperFan ? "主播暂未设定固定超粉日" : "";
    }

    private int[] resolveBadgeTierColors(int level, boolean extinguished) {
        if (extinguished) {
            return new int[]{Color.parseColor("#94A3B8"), Color.parseColor("#64748B")};
        }
        if (level >= 21) {
            return new int[]{Color.parseColor("#F59E0B"), Color.parseColor("#EA580C")};
        }
        if (level >= 16) {
            return new int[]{Color.parseColor("#EC4899"), Color.parseColor("#DB2777")};
        }
        if (level >= 11) {
            return new int[]{Color.parseColor("#8B5CF6"), Color.parseColor("#6D28D9")};
        }
        if (level >= 6) {
            return new int[]{Color.parseColor("#3B82F6"), Color.parseColor("#2563EB")};
        }
        return new int[]{Color.parseColor("#06B6D4"), Color.parseColor("#0284C7")};
    }

    // ── Dialogs for Multi-Select Send Targets, Distribution & Max Count ──────

    private static final class SavedSendTarget {
        final long pid;
        final String name;
        final int weight;

        SavedSendTarget(long pid, String name, int weight) {
            this.pid = pid;
            this.name = name != null ? name : "";
            this.weight = Math.max(1, weight);
        }
    }

    private List<SavedSendTarget> readSavedSendTargets() {
        List<SavedSendTarget> list = new ArrayList<>();
        if (appPrefs == null) {
            return list;
        }
        String json = appPrefs.getString(ModulePrefs.KEY_SEND_HULIANG_TARGETS_JSON, "");
        if (json != null && !json.trim().isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) {
                        continue;
                    }
                    long pid = obj.optLong("pid", 0L);
                    if (pid <= 0L) {
                        continue;
                    }
                    String name = obj.optString("name", "");
                    int weight = Math.max(1, obj.optInt("weight", 1));
                    list.add(new SavedSendTarget(pid, name, weight));
                }
                return list;
            } catch (Throwable ignored) {
            }
        }
        // Backward-compatibility migration from legacy single-target prefs
        long legacyPid = appPrefs.getLong(
                ModulePrefs.KEY_SEND_HULIANG_TARGET_PID, ModulePrefs.DEFAULT_SEND_HULIANG_TARGET_PID);
        if (legacyPid > 0L) {
            String legacyName = appPrefs.getString(ModulePrefs.KEY_SEND_HULIANG_TARGET_NAME, "");
            list.add(new SavedSendTarget(legacyPid, legacyName, 1));
        }
        return list;
    }

    private void saveSendTargets(List<SavedSendTarget> targets) {
        if (appPrefs == null) {
            return;
        }
        JSONArray arr = new JSONArray();
        for (SavedSendTarget t : targets) {
            if (t == null || t.pid <= 0L) {
                continue;
            }
            try {
                JSONObject o = new JSONObject();
                o.put("pid", t.pid);
                o.put("name", t.name);
                o.put("weight", Math.max(1, t.weight));
                arr.put(o);
            } catch (Throwable ignored) {
            }
        }
        SharedPreferences.Editor ed = appPrefs.edit();
        if (arr.length() == 0) {
            ed.putString(ModulePrefs.KEY_SEND_HULIANG_TARGETS_JSON, "");
            ed.putLong(ModulePrefs.KEY_SEND_HULIANG_TARGET_PID, 0L);
            ed.putString(ModulePrefs.KEY_SEND_HULIANG_TARGET_NAME, "");
        } else {
            ed.putString(ModulePrefs.KEY_SEND_HULIANG_TARGETS_JSON, arr.toString());
            if (arr.length() == 1) {
                SavedSendTarget only = targets.get(0);
                ed.putLong(ModulePrefs.KEY_SEND_HULIANG_TARGET_PID, only.pid);
                ed.putString(ModulePrefs.KEY_SEND_HULIANG_TARGET_NAME, only.name);
            } else {
                ed.putLong(ModulePrefs.KEY_SEND_HULIANG_TARGET_PID, 0L);
                ed.putString(ModulePrefs.KEY_SEND_HULIANG_TARGET_NAME, "");
            }
        }
        ed.apply();
    }

    private String formatDistributeModeShort(int mode) {
        switch (mode) {
            case ModulePrefs.DISTRIBUTE_FILL_QUOTA:
                return "补满亲密度上限";
            case ModulePrefs.DISTRIBUTE_NEAREST_UPGRADE:
                return "优先离升级最近";
            case ModulePrefs.DISTRIBUTE_CUSTOM_RATIO:
                return "按自定义比例";
            case ModulePrefs.DISTRIBUTE_EVEN:
            default:
                return "平均分配";
        }
    }

    private void showSendDistributeModeDialog() {
        final int[] modes = new int[]{
                ModulePrefs.DISTRIBUTE_EVEN,
                ModulePrefs.DISTRIBUTE_FILL_QUOTA,
                ModulePrefs.DISTRIBUTE_NEAREST_UPGRADE,
                ModulePrefs.DISTRIBUTE_CUSTOM_RATIO
        };
        final List<String> labels = Arrays.asList(
                "平均分配（均匀分给选中的每位主播）",
                "优先补满今日亲密度上限（推荐 · 防溢出）",
                "优先送离升级最近的主播（最快升牌）",
                "按自定义比例分配（设置主播权重份数）"
        );
        int curMode = appPrefs.getInt(
                ModulePrefs.KEY_SEND_HULIANG_DISTRIBUTE_MODE,
                ModulePrefs.DEFAULT_SEND_HULIANG_DISTRIBUTE_MODE);
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == curMode) {
                checked = i;
                break;
            }
        }
        HubanDialogs.showOptionSheet(
                this, dialogPalette,
                "选择分配方式",
                "多位主播同时送礼时的虎粮分配策略",
                labels,
                checked,
                -1,
                which -> {
                    int newMode = modes[which];
                    appPrefs.edit()
                            .putInt(ModulePrefs.KEY_SEND_HULIANG_DISTRIBUTE_MODE, newMode)
                            .apply();
                    refreshAutoClaimStatus();
                    if (newMode == ModulePrefs.DISTRIBUTE_CUSTOM_RATIO) {
                        showSendTargetDialog(true);
                    }
                }
        );
    }

    private void showSendTargetDialog(final boolean forceShowWeights) {
        List<SavedSendTarget> saved = readSavedSendTargets();
        Map<Long, SavedSendTarget> savedMap = new LinkedHashMap<>();
        for (SavedSendTarget s : saved) {
            savedMap.put(s.pid, s);
        }

        List<HubanDialogs.MultiSelectOption> options = new ArrayList<>();
        SharedPreferences state = ModulePrefs.openStatePrefs(this);
        String badgesJson = state.getString(ModulePrefs.STATE_BADGES_JSON, "");
        if (badgesJson != null && !badgesJson.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(badgesJson);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.optJSONObject(i);
                    if (obj == null) {
                        continue;
                    }
                    long pid = obj.optLong("pid", 0L);
                    if (pid <= 0L) {
                        continue;
                    }
                    String nick = obj.optString("nick", String.valueOf(pid));
                    String badgeName = obj.optString("badgeName", "");
                    int lv = obj.optInt("level", 0);
                    boolean isSf = obj.optBoolean("superFan", false)
                            || obj.optInt("sfType", 0) > 0
                            || (obj.optInt("fansIdentity", 0) >= 1 && obj.optInt("fansIdentity", 0) <= 4);
                    int sfDay = obj.optInt("sfDay", 0);
                    StringBuilder sub = new StringBuilder();
                    sub.append("Lv.").append(lv).append(" ").append(badgeName);
                    if (isSf) {
                        sub.append(" · 超粉");
                    }
                    if (sfDay >= 1 && sfDay <= 31) {
                        sub.append(" · 每月").append(sfDay).append("日超粉日");
                    }
                    SavedSendTarget existing = savedMap.remove(pid);
                    boolean isSelected = existing != null;
                    int weight = existing != null ? existing.weight : 1;
                    options.add(new HubanDialogs.MultiSelectOption(
                            pid, nick, sub.toString(), isSf, isSelected, weight));
                }
            } catch (Throwable ignored) {
            }
        }

        // Preserve any previously added custom UIDs not present in synced fan badges
        for (SavedSendTarget custom : savedMap.values()) {
            String title = !custom.name.isEmpty() ? custom.name : ("UID:" + custom.pid);
            options.add(new HubanDialogs.MultiSelectOption(
                    custom.pid, title, "自定义主播 UID", false, true, custom.weight));
        }

        int curMode = appPrefs.getInt(
                ModulePrefs.KEY_SEND_HULIANG_DISTRIBUTE_MODE,
                ModulePrefs.DEFAULT_SEND_HULIANG_DISTRIBUTE_MODE);
        boolean showWeights = forceShowWeights || curMode == ModulePrefs.DISTRIBUTE_CUSTOM_RATIO;
        String subtitle = showWeights
                ? "勾选多位主播并调整右侧份数，未勾选则默认全部粉丝牌"
                : "可自由多选主播；不勾选任何项时默认赠送全部粉丝牌";

        HubanDialogs.showMultiSelectTargetSheet(
                this, dialogPalette,
                showWeights ? "选择赠送目标与比例" : "选择赠送目标",
                subtitle,
                options,
                showWeights,
                sheetHandle -> {
                    sheetHandle.dismiss();
                    showCustomTargetUidDialog(forceShowWeights);
                },
                result -> {
                    List<SavedSendTarget> next = new ArrayList<>();
                    for (HubanDialogs.MultiSelectOption opt : result) {
                        if (opt.selected) {
                            next.add(new SavedSendTarget(opt.id, opt.title, opt.weight));
                        }
                    }
                    saveSendTargets(next);
                    refreshAutoClaimStatus();
                }
        );
    }

    private void showCustomTargetUidDialog(final boolean reopenWithWeights) {
        HubanDialogs.showInputModal(
                this, dialogPalette,
                "添加自定义主播 UID",
                "请输入主播的虎牙 UID（非房间短号），将加入多选目标",
                "例如：2176998321",
                "",
                InputType.TYPE_CLASS_NUMBER,
                "添加",
                raw -> {
                    String s = raw != null ? raw.trim() : "";
                    try {
                        long pid = Long.parseLong(s);
                        if (pid <= 0L) {
                            throw new NumberFormatException();
                        }
                        List<SavedSendTarget> existing = readSavedSendTargets();
                        boolean found = false;
                        for (SavedSendTarget t : existing) {
                            if (t.pid == pid) {
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            existing.add(new SavedSendTarget(pid, "UID:" + pid, 1));
                            saveSendTargets(existing);
                        }
                        refreshAutoClaimStatus();
                        showSendTargetDialog(reopenWithWeights);
                        return true;
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入有效的主播 UID 数字", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                }
        );
    }

    private void showSendMaxCountDialog() {
        final int[] presets = new int[]{0, 1, 10, 30, 50, 100};
        final String[] labels = new String[]{
                "全部赠送（推荐）",
                "每次最多 1 个",
                "每次最多 10 个",
                "每次最多 30 个",
                "每次最多 50 个",
                "每次最多 100 个",
                "自定义个数..."
        };
        int cur = appPrefs.getInt(
                ModulePrefs.KEY_SEND_HULIANG_MAX_COUNT, ModulePrefs.DEFAULT_SEND_HULIANG_MAX_COUNT);
        int checked = -1;
        for (int i = 0; i < presets.length; i++) {
            if (presets[i] == cur) {
                checked = i;
                break;
            }
        }
        HubanDialogs.showOptionSheet(
                this, dialogPalette,
                "单次赠送上限",
                "控制每次自动赠送的最大虎粮个数",
                Arrays.asList(labels),
                checked,
                labels.length - 1,
                which -> {
                    if (which == labels.length - 1) {
                        showCustomSendCountDialog();
                        return;
                    }
                    appPrefs.edit()
                            .putInt(ModulePrefs.KEY_SEND_HULIANG_MAX_COUNT, presets[which])
                            .apply();
                    refreshAutoClaimStatus();
                }
        );
    }

    private void showCustomSendCountDialog() {
        int cur = appPrefs.getInt(ModulePrefs.KEY_SEND_HULIANG_MAX_COUNT, 0);
        HubanDialogs.showInputModal(
                this, dialogPalette,
                "自定义单次赠送上限",
                "填 0 表示将包裹内免费虎粮全部赠送",
                "输入非负整数",
                cur > 0 ? String.valueOf(cur) : "",
                InputType.TYPE_CLASS_NUMBER,
                "确定",
                raw -> {
                    String s = raw != null ? raw.trim() : "";
                    try {
                        int val = Integer.parseInt(s);
                        if (val < 0) {
                            throw new NumberFormatException();
                        }
                        appPrefs.edit()
                                .putInt(ModulePrefs.KEY_SEND_HULIANG_MAX_COUNT, val)
                                .apply();
                        refreshAutoClaimStatus();
                        return true;
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "请输入有效的非负整数", Toast.LENGTH_SHORT).show();
                        return false;
                    }
                }
        );
    }

    // ── Reusable UI Builders ─────────────────────────────────────────────────

    private TextView addOptionRow(
            LinearLayout parent,
            String titleText,
            String descText,
            String initialValue,
            boolean showDivider,
            Runnable onClick
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dp(10);
        textCol.setLayoutParams(colLp);

        TextView titleView = new TextView(this);
        titleView.setText(titleText);
        titleView.setTextSize(15f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(cPrimaryText);
        textCol.addView(titleView);

        TextView descView = new TextView(this);
        descView.setText(descText);
        descView.setTextSize(12.5f);
        descView.setTextColor(cSecondaryText);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dLp.topMargin = dp(3);
        descView.setLayoutParams(dLp);
        textCol.addView(descView);

        TextView valuePill = createPill(initialValue, cBrandText, cBrandTintBg, 13f, true);
        valuePill.setPadding(dp(10), dp(6), dp(10), dp(6));
        valuePill.setSingleLine(true);
        valuePill.setMaxWidth(dp(150));
        valuePill.setEllipsize(TextUtils.TruncateAt.END);

        row.addView(textCol);
        row.addView(valuePill);
        row.setOnClickListener(v -> onClick.run());
        parent.addView(row);
        if (showDivider) {
            parent.addView(createDivider());
        }
        return valuePill;
    }

    private TextView addStatTile(
            LinearLayout parent,
            String label,
            String initialValue,
            int valueColor,
            boolean addRightGap
    ) {
        LinearLayout tile = addStatTileContainer(parent, label, initialValue, valueColor, addRightGap);
        return (TextView) tile.getChildAt(1);
    }

    private LinearLayout addStatTileContainer(
            LinearLayout parent,
            String label,
            String initialValue,
            int valueColor,
            boolean addRightGap
    ) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(createRoundedRect(cSubSurface, 12, 0, 0));
        tile.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        if (addRightGap) {
            lp.rightMargin = dp(8);
        }
        tile.setLayoutParams(lp);

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(11.5f);
        labelView.setSingleLine(true);
        labelView.setTextColor(cSecondaryText);
        tile.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(initialValue);
        valueView.setTextSize(16f);
        valueView.setSingleLine(true);
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setTextColor(valueColor);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = dp(3);
        valueView.setLayoutParams(vLp);
        tile.addView(valueView);

        parent.addView(tile);
        return tile;
    }

    private LinearLayout createSectionHeader(String title, String rightNote) {
        return createSectionHeader(title, rightNote, 18);
    }

    private LinearLayout createSectionHeader(String title, String rightNote, int topMarginDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(14f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(cSecondaryText);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvTitle);

        if (rightNote != null && !rightNote.isEmpty()) {
            TextView tvNote = new TextView(this);
            tvNote.setText(rightNote);
            tvNote.setTextSize(12f);
            tvNote.setTextColor(cTertiaryText);
            row.addView(tvNote);
        }
        return row;
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createRoundedRect(cCard, 16, 1, cCardBorder));
        return card;
    }

    private GradientDrawable createRoundedRect(int fillColor, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fillColor);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            d.setStroke(Math.max(1, dp(strokeDp)), strokeColor);
        }
        return d;
    }

    private TextView createPill(String text, int fgColor, int bgColor, float sizeSp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sizeSp);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        tv.setTextColor(fgColor);
        tv.setPadding(dp(8), dp(3), dp(8), dp(3));
        tv.setBackground(createRoundedRect(bgColor, 99, 0, 0));
        return tv;
    }

    private TextView createPrimaryButton(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(15f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setFocusable(true);

        GradientDrawable grad = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{cBrandStart, cBrandEnd}
        );
        grad.setCornerRadius(dp(14));
        Drawable bg = new RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), grad, null);
        btn.setBackground(bg);
        attachPressBounce(btn, 0.97f);
        return btn;
    }

    private TextView createSecondaryButton(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(13.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(cPrimaryText);
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setFocusable(true);

        GradientDrawable shape = createRoundedRect(cSubSurface, 12, 1, cCardBorder);
        Drawable bg = new RippleDrawable(
                ColorStateList.valueOf(isDarkTheme ? Color.parseColor("#22FFFFFF") : Color.parseColor("#14000000")),
                shape, null);
        btn.setBackground(bg);
        return btn;
    }

    private View createDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(cDivider);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        return divider;
    }

    private interface OnToggleCallback {
        void onChanged(boolean isChecked);
    }

    private void addSwitchRow(
            LinearLayout parent,
            SharedPreferences prefs,
            String key,
            boolean defaultValue,
            String titleText,
            String descText,
            boolean showDivider,
            OnToggleCallback callback
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        colLp.rightMargin = dp(12);
        textCol.setLayoutParams(colLp);

        TextView titleView = new TextView(this);
        titleView.setText(titleText);
        titleView.setTextSize(15f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(cPrimaryText);
        textCol.addView(titleView);

        TextView descView = new TextView(this);
        descView.setText(descText);
        descView.setTextSize(12.5f);
        descView.setLineSpacing(dp(2), 1.0f);
        descView.setTextColor(cSecondaryText);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        descLp.topMargin = dp(3);
        descView.setLayoutParams(descLp);
        textCol.addView(descView);

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, defaultValue));
        applySwitchTint(sw);
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingUi) {
                return;
            }
            prefs.edit().putBoolean(key, isChecked).apply();
            if (callback != null) {
                callback.onChanged(isChecked);
            }
        });

        row.setOnClickListener(v -> sw.toggle());
        row.addView(textCol);
        row.addView(sw);
        parent.addView(row);

        switchMap.put(key, sw);
        defaultMap.put(key, defaultValue);

        if (showDivider) {
            parent.addView(createDivider());
        }
    }

    private void applySwitchTint(Switch sw) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        int[] thumbColors = new int[]{
                Color.WHITE,
                isDarkTheme ? Color.parseColor("#CBD5E1") : Color.parseColor("#FFFFFF")
        };
        int[] trackColors = new int[]{
                cBrandStart,
                isDarkTheme ? Color.parseColor("#334155") : Color.parseColor("#CBD5E1")
        };
        sw.setThumbTintList(new ColorStateList(states, thumbColors));
        sw.setTrackTintList(new ColorStateList(states, trackColors));
    }

    // ── In-App Update Flow ───────────────────────────────────────────────────

    private void maybeAutoCheckForUpdate() {
        SharedPreferences state = ModulePrefs.openStatePrefs(this);
        long lastCheck = state.getLong(ModulePrefs.STATE_LAST_UPDATE_CHECK_TIME, 0L);
        if (System.currentTimeMillis() - lastCheck < AUTO_UPDATE_CHECK_INTERVAL_MS) {
            return;
        }
        triggerUpdateCheck(false);
    }

    private void triggerUpdateCheck(final boolean manual) {
        if (checkingUpdate) {
            if (manual) {
                Toast.makeText(this, "正在检查更新，请稍候...", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        checkingUpdate = true;
        if (manual && checkUpdateBtn != null) {
            checkUpdateBtn.setText("检查中...");
            checkUpdateBtn.setEnabled(false);
        }

        AppUpdater.checkForUpdate(this, new AppUpdater.CheckCallback() {
            @Override
            public void onResult(AppUpdater.ReleaseInfo info) {
                checkingUpdate = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                ModulePrefs.openStatePrefs(MainActivity.this).edit()
                        .putLong(ModulePrefs.STATE_LAST_UPDATE_CHECK_TIME, System.currentTimeMillis())
                        .apply();

                latestReleaseInfo = info;
                updateHeaderCheckButton(info.hasUpdate);
                updateBannerVisibility(info);

                if (info.hasUpdate) {
                    if (manual) {
                        showUpdateDialog(info);
                    }
                } else if (manual) {
                    Toast.makeText(
                            MainActivity.this,
                            "当前已是最新版本（" + getAppVersionName() + "）",
                            Toast.LENGTH_SHORT
                    ).show();
                }
            }

            @Override
            public void onError(String errorMessage) {
                checkingUpdate = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                updateHeaderCheckButton(latestReleaseInfo != null && latestReleaseInfo.hasUpdate);
                if (manual) {
                    HubanDialogs.showMessageModal(
                            MainActivity.this, dialogPalette,
                            "检查更新失败",
                            errorMessage + "\n\n也可以直接前往 GitHub Releases 页面查看。",
                            "打开网页", () -> openUrlInBrowser(AppUpdater.RELEASES_PAGE_URL),
                            "关闭", null,
                            null, null
                    );
                }
            }
        });
    }

    private void updateHeaderCheckButton(boolean hasUpdate) {
        if (aboutTabBadgeDot != null) {
            aboutTabBadgeDot.setVisibility(hasUpdate ? View.VISIBLE : View.GONE);
        }
        if (checkUpdateBtn == null) {
            return;
        }
        checkUpdateBtn.setEnabled(true);
        if (hasUpdate) {
            checkUpdateBtn.setText("立即更新");
            checkUpdateBtn.setTextColor(Color.WHITE);
            GradientDrawable grad = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{cBrandStart, cBrandEnd}
            );
            grad.setCornerRadius(dp(99));
            checkUpdateBtn.setBackground(grad);
        } else {
            checkUpdateBtn.setText("检查更新");
            checkUpdateBtn.setTextColor(cBrandText);
            checkUpdateBtn.setBackground(createRoundedRect(cBrandTintBg, 99, 0, 0));
        }
    }

    private void updateBannerVisibility(AppUpdater.ReleaseInfo info) {
        if (updateBannerCard == null || updateBannerTitleView == null || updateBannerDescView == null) {
            return;
        }
        if (info == null || !info.hasUpdate) {
            updateBannerCard.setVisibility(View.GONE);
            return;
        }
        String sizePart = info.apkSizeBytes > 0L ? (" · " + AppUpdater.formatFileSize(info.apkSizeBytes)) : "";
        updateBannerTitleView.setText("发现新版本 v" + info.versionName + sizePart);
        String preview = info.releaseNotes != null ? info.releaseNotes.replace("\r", "").trim() : "";
        if (preview.isEmpty()) {
            preview = "点击立即应用内下载更新";
        }
        updateBannerDescView.setText(preview);
        updateBannerCard.setVisibility(View.VISIBLE);
    }

    private void showUpdateDialog(final AppUpdater.ReleaseInfo info) {
        if (info == null || isFinishing() || isDestroyed()) {
            return;
        }
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本：").append(getAppVersionName())
                .append("  →  最新版本：v").append(info.versionName);
        if (info.apkSizeBytes > 0L) {
            msg.append("\n安装包大小：").append(AppUpdater.formatFileSize(info.apkSizeBytes));
        }
        if (info.releaseNotes != null && !info.releaseNotes.isEmpty()) {
            msg.append("\n\n更新内容：\n").append(info.releaseNotes);
        }

        boolean hasDirectApk = info.apkDownloadUrl != null && !info.apkDownloadUrl.isEmpty();
        HubanDialogs.showMessageModal(
                this, dialogPalette,
                "发现新版本 v" + info.versionName,
                msg.toString(),
                hasDirectApk ? "应用内更新" : "前往下载",
                () -> {
                    if (hasDirectApk) {
                        startInAppDownload(info);
                    } else {
                        openUrlInBrowser(info.htmlUrl);
                    }
                },
                "稍后再说", null,
                "浏览器下载", () -> openUrlInBrowser(
                        info.htmlUrl != null && !info.htmlUrl.isEmpty()
                                ? info.htmlUrl
                                : AppUpdater.RELEASES_PAGE_URL
                )
        );
    }

    private void startInAppDownload(final AppUpdater.ReleaseInfo info) {
        if (activeDownloadCancel != null) {
            activeDownloadCancel.set(true);
        }

        final HubanDialogs.ProgressDialogHandle progressHandle = HubanDialogs.showProgressModal(
                this, dialogPalette,
                "正在下载 虎伴 v" + info.versionName,
                "正在连接下载服务器...",
                info.apkName != null && !info.apkName.isEmpty() ? info.apkName : "准备下载 APK...",
                () -> {
                    if (activeDownloadCancel != null) {
                        activeDownloadCancel.set(true);
                    }
                }
        );
        activeDownloadDialog = progressHandle;

        activeDownloadCancel = AppUpdater.downloadApk(this, info, new AppUpdater.DownloadCallback() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (totalBytes > 0L) {
                    int pct = (int) Math.min(100L, (downloadedBytes * 100L) / totalBytes);
                    progressHandle.updateProgress(
                            pct, false,
                            "正在下载更新包 (" + pct + "%)",
                            AppUpdater.formatFileSize(downloadedBytes)
                                    + " / " + AppUpdater.formatFileSize(totalBytes)
                    );
                } else {
                    progressHandle.updateProgress(
                            0, true,
                            "正在下载更新包...",
                            "已下载 " + AppUpdater.formatFileSize(downloadedBytes)
                    );
                }
            }

            @Override
            public void onCompleted(File apkFile) {
                dismissDownloadDialog();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                installDownloadedApk(apkFile);
            }

            @Override
            public void onCancelled() {
                dismissDownloadDialog();
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(MainActivity.this, "已取消下载", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String errorMessage) {
                dismissDownloadDialog();
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                HubanDialogs.showMessageModal(
                        MainActivity.this, dialogPalette,
                        "下载更新失败",
                        errorMessage + "\n\n是否改用浏览器下载？",
                        "浏览器下载", () -> openUrlInBrowser(
                                info.apkDownloadUrl != null && !info.apkDownloadUrl.isEmpty()
                                        ? info.apkDownloadUrl
                                        : info.htmlUrl
                        ),
                        "关闭", null,
                        null, null
                );
            }
        });
    }

    private void dismissDownloadDialog() {
        activeDownloadCancel = null;
        if (activeDownloadDialog != null) {
            try {
                if (activeDownloadDialog.isShowing()) {
                    activeDownloadDialog.dismiss();
                }
            } catch (Throwable ignored) {
            }
            activeDownloadDialog = null;
        }
    }

    private void installDownloadedApk(final File apkFile) {
        if (!AppUpdater.canInstallPackages(this)) {
            pendingInstallApk = apkFile;
            HubanDialogs.showMessageModal(
                    this, dialogPalette,
                    "需要允许安装应用",
                    "更新包已下载完成。请允许「虎伴」安装未知应用，返回后将自动继续安装。",
                    "前往授权", () -> {
                        try {
                            startActivity(AppUpdater.buildInstallPermissionIntent(this));
                        } catch (Throwable t) {
                            Toast.makeText(this, "无法打开安装权限设置页，请在系统设置中手动开启", Toast.LENGTH_LONG).show();
                        }
                    },
                    "取消", () -> pendingInstallApk = null,
                    null, null
            );
            return;
        }
        boolean launched = AppUpdater.launchApkInstaller(this, apkFile);
        if (!launched) {
            Toast.makeText(this, "无法调起系统安装器，请尝试使用浏览器下载安装", Toast.LENGTH_LONG).show();
        }
    }

    private TextView createHeaderActionPill(String text) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextSize(12f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(cPrimaryText);
        btn.setGravity(Gravity.CENTER);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setPadding(dp(11), dp(6), dp(11), dp(6));
        GradientDrawable shape = createRoundedRect(cCard, 99, 1, cCardBorder);
        btn.setBackground(new RippleDrawable(
                ColorStateList.valueOf(isDarkTheme ? Color.parseColor("#22FFFFFF") : Color.parseColor("#14000000")),
                shape, null));
        return btn;
    }

    private void openUrlInBrowser(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, "未找到可用浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_POST_NOTIFICATIONS);
        }
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable t) {
                Toast.makeText(this, "无法打开闹钟权限页，请在系统应用设置中手动开启", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String formatNum(int n) {
        return NumberFormat.getIntegerInstance(Locale.US).format(n);
    }

    private String getAppVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName != null ? "v" + info.versionName : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ── Crisp Custom Vector Drawables (Nav Icons & Fallback SuperFan V Icon) ─

    private static final class TabIconDrawable extends Drawable {
        private final int type;
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cutoutStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cutoutFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean filled = false;

        TabIconDrawable(int type, int color, int cutoutColor) {
            this.type = type;
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            strokePaint.setColor(color);

            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(color);

            cutoutStrokePaint.setStyle(Paint.Style.STROKE);
            cutoutStrokePaint.setStrokeCap(Paint.Cap.ROUND);
            cutoutStrokePaint.setStrokeJoin(Paint.Join.ROUND);
            cutoutStrokePaint.setColor(cutoutColor);

            cutoutFillPaint.setStyle(Paint.Style.FILL);
            cutoutFillPaint.setColor(cutoutColor);
        }

        void setTintColor(int color) {
            strokePaint.setColor(color);
            fillPaint.setColor(color);
            invalidateSelf();
        }

        void setFilled(boolean filled) {
            if (this.filled != filled) {
                this.filled = filled;
                invalidateSelf();
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            float s = Math.min(w, h);
            if (s <= 0f) {
                return;
            }
            float baseStroke = s * 0.095f;
            strokePaint.setStrokeWidth(filled ? s * 0.045f : baseStroke);
            cutoutStrokePaint.setStrokeWidth(s * 0.105f);
            float cx = b.centerX();
            float cy = b.centerY();

            if (type == TAB_TASKS) {
                // Lightning bolt / automation task icon
                Path p = new Path();
                p.moveTo(cx + s * 0.06f, cy - s * 0.42f);
                p.lineTo(cx - s * 0.28f, cy + s * 0.04f);
                p.lineTo(cx + s * 0.02f, cy + s * 0.04f);
                p.lineTo(cx - s * 0.06f, cy + s * 0.42f);
                p.lineTo(cx + s * 0.28f, cy - s * 0.04f);
                p.lineTo(cx - s * 0.02f, cy - s * 0.04f);
                p.close();
                if (filled) {
                    canvas.drawPath(p, fillPaint);
                    canvas.drawPath(p, strokePaint);
                } else {
                    canvas.drawPath(p, strokePaint);
                }
            } else if (type == TAB_BADGES) {
                // Heart / fan badge icon
                Path p = new Path();
                p.moveTo(cx, cy + s * 0.36f);
                p.cubicTo(cx - s * 0.46f, cy + s * 0.08f, cx - s * 0.44f, cy - s * 0.36f, cx - s * 0.16f, cy - s * 0.36f);
                p.cubicTo(cx - s * 0.05f, cy - s * 0.36f, cx, cy - s * 0.24f, cx, cy - s * 0.20f);
                p.cubicTo(cx, cy - s * 0.24f, cx + s * 0.05f, cy - s * 0.36f, cx + s * 0.16f, cy - s * 0.36f);
                p.cubicTo(cx + s * 0.44f, cy - s * 0.36f, cx + s * 0.46f, cy + s * 0.08f, cx, cy + s * 0.36f);
                p.close();
                if (filled) {
                    canvas.drawPath(p, fillPaint);
                    canvas.drawPath(p, strokePaint);
                } else {
                    canvas.drawPath(p, strokePaint);
                }
            } else if (type == TAB_PURIFY) {
                // Shield + purity check icon
                Path shield = new Path();
                shield.moveTo(cx, cy - s * 0.40f);
                shield.lineTo(cx + s * 0.34f, cy - s * 0.26f);
                shield.lineTo(cx + s * 0.34f, cy + s * 0.02f);
                shield.cubicTo(cx + s * 0.34f, cy + s * 0.24f, cx + s * 0.16f, cy + s * 0.36f, cx, cy + s * 0.42f);
                shield.cubicTo(cx - s * 0.16f, cy + s * 0.36f, cx - s * 0.34f, cy + s * 0.24f, cx - s * 0.34f, cy + s * 0.02f);
                shield.lineTo(cx - s * 0.34f, cy - s * 0.26f);
                shield.close();

                Path check = new Path();
                check.moveTo(cx - s * 0.14f, cy + s * 0.01f);
                check.lineTo(cx - s * 0.03f, cy + s * 0.12f);
                check.lineTo(cx + s * 0.16f, cy - s * 0.09f);

                if (filled) {
                    canvas.drawPath(shield, fillPaint);
                    canvas.drawPath(shield, strokePaint);
                    canvas.drawPath(check, cutoutStrokePaint);
                } else {
                    canvas.drawPath(shield, strokePaint);
                    canvas.drawPath(check, strokePaint);
                }
            } else {
                // Info circle (i) icon for About tab
                if (filled) {
                    canvas.drawCircle(cx, cy, s * 0.42f, fillPaint);
                    canvas.drawCircle(cx, cy - s * 0.16f, s * 0.06f, cutoutFillPaint);
                    canvas.drawLine(cx, cy - s * 0.01f, cx, cy + s * 0.20f, cutoutStrokePaint);
                } else {
                    canvas.drawCircle(cx, cy, s * 0.39f, strokePaint);
                    canvas.drawCircle(cx, cy - s * 0.16f, s * 0.055f, fillPaint);
                    canvas.drawLine(cx, cy - s * 0.02f, cx, cy + s * 0.19f, strokePaint);
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
            strokePaint.setAlpha(alpha);
            fillPaint.setAlpha(alpha);
            cutoutStrokePaint.setAlpha(alpha);
            cutoutFillPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            strokePaint.setColorFilter(colorFilter);
            fillPaint.setColorFilter(colorFilter);
            cutoutStrokePaint.setColorFilter(colorFilter);
            cutoutFillPaint.setColorFilter(colorFilter);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * Golden V / V+ vector emblem used only as a fallback when native badge assets aren't cached yet.
     */
    private static final class SuperFanIconDrawable extends Drawable {
        private final boolean isPlus;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SuperFanIconDrawable(boolean isPlus) {
            this.isPlus = isPlus;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float w = b.width();
            float h = b.height();
            if (w <= 0f || h <= 0f) {
                return;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(
                    0f, 0f, w, h,
                    Color.parseColor("#FFE259"),
                    Color.parseColor("#FF8C00"),
                    Shader.TileMode.CLAMP));

            Path v = new Path();
            v.moveTo(w * 0.14f, h * 0.16f);
            v.lineTo(w * 0.36f, h * 0.16f);
            v.lineTo(w * 0.36f, h * 0.56f);
            v.lineTo(w * 0.68f, h * 0.16f);
            v.lineTo(w * 0.88f, h * 0.16f);
            v.lineTo(w * 0.36f, h * 0.86f);
            v.lineTo(w * 0.22f, h * 0.86f);
            v.lineTo(w * 0.22f, h * 0.28f);
            v.lineTo(w * 0.14f, h * 0.28f);
            v.close();
            canvas.drawPath(v, paint);

            if (isPlus) {
                float px = w * 0.76f;
                float py = h * 0.72f;
                float r = w * 0.16f;
                float t = w * 0.055f;
                canvas.drawRoundRect(new RectF(px - r, py - t, px + r, py + t), t, t, paint);
                canvas.drawRoundRect(new RectF(px - t, py - r, px + t, py + r), t, t, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
