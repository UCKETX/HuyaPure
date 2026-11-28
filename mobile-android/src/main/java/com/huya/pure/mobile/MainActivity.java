package com.huya.pure.mobile;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone Pure-Native Android MainActivity for 虎伴 (HuyaPure Mobile).
 * Zero external dependencies, warm-clay design system, embedded Go core runtime.
 */
public class MainActivity extends Activity {

    private static final int TAB_TASKS = 0;
    private static final int TAB_BADGES = 1;
    private static final int TAB_ACCOUNTS = 2;
    private static final int TAB_SETTINGS = 3;
    private static final int TAB_COUNT = 4;

    private static final int REQ_POST_NOTIF = 3001;
    private static final String PREFS_UI = "huban_ui_prefs";
    private static final String KEY_ACTIVE_ACCOUNT = "active_account_id";
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check_time";
    private static final long AUTO_UPDATE_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final String GITHUB_URL = "https://github.com/" + AppUpdater.GITHUB_REPO;

    private static final String[] DISTRIBUTE_LABELS = new String[]{
            "平均分配",
            "优先补满今日亲密度上限",
            "优先离升级最近",
            "按自定义比例分配"
    };
    private static final String[] DISTRIBUTE_DESCS = new String[]{
            "将可送免费虎粮均匀分给所选目标主播",
            "按所选顺序优先填满每个主播的今日亲密度上限",
            "优先赠送距离下一等级所需亲密度最少的主播",
            "按各目标设置的份数权重比例进行分配"
    };

    private HubanUi.Palette p;
    private HubanApiClient api;
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences uiPrefs;

    // Live state from embedded Go core
    private Models.StatusResponse latestStatus = new Models.StatusResponse();
    private String activeAccountId = "";
    private boolean loadingStatus = false;
    private boolean syncingAccount = false;
    private boolean pollingRun = false;
    private Runnable runPollTask;
    private int currentTab = TAB_TASKS;

    // Top bar views
    private TextView accountSwitcherLabel;
    private TextView topSyncBtn;

    // Tab pages & bottom nav (module-app style animated icons & pills)
    private final View[] tabPages = new View[TAB_COUNT];
    private final View[] navPillViews = new View[TAB_COUNT];
    private final ImageView[] navIconViews = new ImageView[TAB_COUNT];
    private final TabIconDrawable[] navIconDrawables = new TabIconDrawable[TAB_COUNT];
    private final TextView[] navLabelViews = new TextView[TAB_COUNT];
    private final AnimatorSet[] navIconAnimators = new AnimatorSet[TAB_COUNT];
    private final ValueAnimator[] navColorAnimators = new ValueAnimator[TAB_COUNT];
    private boolean hasInitializedTab = false;
    private ObjectAnimator runPulseAnimator;
    private View aboutHeroIconView;
    private final List<View> badgeRowViews = new ArrayList<>();
    private final List<View> badgeFillViews = new ArrayList<>();

    // Tab containers (re-rendered cleanly on state updates)
    private LinearLayout tasksPageRoot;
    private TextView badgeStatCountView;
    private TextView badgeStatCheckinView;
    private TextView badgeStatSyncTimeView;
    private TextView badgesMetaView;
    private LinearLayout badgesContainer;
    private LinearLayout accountsListContainer;

    // Settings views
    private TextView notifPermStatusTv;
    private TextView notifPermBtn;
    private TextView batteryStatusTv;
    private TextView batteryOptBtn;
    private TextView exactAlarmStatusTv;
    private TextView exactAlarmBtn;
    private EditText etBarkUrl;
    private EditText etTgToken;
    private EditText etTgChatId;
    private EditText etServerChan;
    private EditText etDingTalk;
    private EditText etFeishu;
    private EditText etCustomWebhook;
    private boolean notifyFieldsInitialized = false;
    private TextView engineStatusTv;

    // Auto-updater state & views
    private AppUpdater.ReleaseInfo latestReleaseInfo;
    private boolean checkingUpdate = false;
    private LinearLayout updateBannerCard;
    private TextView updateBannerTitleView;
    private TextView updateBannerDescView;
    private View settingsTabBadgeDot;
    private TextView checkUpdateBtn;
    private HubanUi.ProgressDialogHandle activeDownloadDialog;
    private AtomicBoolean activeDownloadCancel;
    private File pendingInstallApk;

    private BroadcastReceiver dataUpdatedReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);

        p = HubanUi.Palette.of(this);
        api = new HubanApiClient(this);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "huban-ui-io");
            t.setDaemon(true);
            return t;
        });
        uiPrefs = getSharedPreferences(PREFS_UI, MODE_PRIVATE);
        activeAccountId = uiPrefs.getString(KEY_ACTIVE_ACCOUNT, "");

        LocalNotifyHelper.ensureChannels(this);
        applySystemBarStyle();

        // Outer vertical shell: Top Bar + 4-Tab Content + Bottom Navigation
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setFitsSystemWindows(true);
        shell.setBackgroundColor(p.bg);

        shell.addView(buildTopBar());
        shell.addView(buildUpdateBanner());

        FrameLayout contentHost = new FrameLayout(this);
        contentHost.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tabPages[TAB_TASKS] = wrapScroll(buildTasksTab());
        tabPages[TAB_BADGES] = wrapScroll(buildBadgesTab());
        tabPages[TAB_ACCOUNTS] = wrapScroll(buildAccountsTab());
        tabPages[TAB_SETTINGS] = wrapScroll(buildSettingsTab());

        for (View page : tabPages) {
            contentHost.addView(page, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        shell.addView(contentHost);
        shell.addView(HubanUi.createDivider(this, p));
        shell.addView(buildBottomNavBar());

        setContentView(shell);
        selectTab(TAB_TASKS);
        bootCoreAndLoadStatus();
        scheduleSilentUpdateCheck();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstallApk != null && pendingInstallApk.exists() && AppUpdater.canInstallPackages(this)) {
            File toInstall = pendingInstallApk;
            pendingInstallApk = null;
            AppUpdater.launchApkInstaller(this, toInstall);
        }
        refreshSettingsPermissionStates();
        if (dataUpdatedReceiver == null) {
            dataUpdatedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    loadStatus(false);
                }
            };
        }
        IntentFilter filter = new IntentFilter(TaskRunService.ACTION_DATA_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(dataUpdatedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(dataUpdatedReceiver, filter);
        }
        if (HubanCoreManager.getInstance().isRunning()) {
            loadStatus(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (dataUpdatedReceiver != null) {
            try {
                unregisterReceiver(dataUpdatedReceiver);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) {
            LauncherIconHelper.syncIfNeeded(this);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (activeDownloadCancel != null) {
            activeDownloadCancel.set(true);
        }
        dismissDownloadDialog();
        stopRunPolling();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @SuppressWarnings("deprecation")
    private void applySystemBarStyle() {
        try {
            Window window = getWindow();
            window.setStatusBarColor(p.bg);
            window.setNavigationBarColor(p.card);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController ctrl = window.getInsetsController();
                if (ctrl != null) {
                    int flags = p.isDark ? 0 : (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                    ctrl.setSystemBarsAppearance(flags,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } else if (!p.isDark) {
                View decor = window.getDecorView();
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        } catch (Throwable ignored) {
        }
    }

    // ── Core Startup & Status Loading ────────────────────────────────────────

    private void bootCoreAndLoadStatus() {
        if (topSyncBtn != null) topSyncBtn.setText("启动中...");
        executor.execute(() -> {
            try {
                HubanCoreManager.getInstance().ensureStarted(this);
                Models.StatusResponse st = api.getStatus();
                ScheduleHelper.syncFromAccounts(this, st.accounts);
                mainHandler.post(() -> {
                    if (topSyncBtn != null) topSyncBtn.setText("同步");
                    applyStatus(st);
                    drainAndPostLocalNotifications();
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (topSyncBtn != null) topSyncBtn.setText("重试");
                    updateEngineStatusLabel();
                    renderAllTabs();
                    Toast.makeText(this, "引擎启动失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadStatus(boolean showToastOnErr) {
        if (loadingStatus) return;
        loadingStatus = true;
        executor.execute(() -> {
            try {
                Models.StatusResponse st = api.getStatus();
                ScheduleHelper.syncFromAccounts(this, st.accounts);
                mainHandler.post(() -> {
                    loadingStatus = false;
                    applyStatus(st);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    loadingStatus = false;
                    updateEngineStatusLabel();
                    if (showToastOnErr) {
                        Toast.makeText(this, "获取状态失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void applyStatus(Models.StatusResponse st) {
        if (st == null) return;
        this.latestStatus = st;

        // Ensure activeAccountId points to an existing account
        Models.Account active = findAccount(activeAccountId);
        if (active == null && !st.accounts.isEmpty()) {
            active = st.accounts.get(0);
            setActiveAccountId(active.id);
        } else if (st.accounts.isEmpty()) {
            setActiveAccountId("");
        }

        // Populate notify fields on first load
        if (!notifyFieldsInitialized && st.notify != null) {
            notifyFieldsInitialized = true;
            populateNotifyFields(st.notify);
        }

        // Start/stop run status polling if active account is running
        if (active != null && active.state != null && active.state.running) {
            startRunPolling();
        } else if (pollingRun) {
            stopRunPolling();
            drainAndPostLocalNotifications();
        }

        updateTopBarAccountPill();
        updateEngineStatusLabel();
        renderAllTabs();
    }

    private void setActiveAccountId(String id) {
        this.activeAccountId = id != null ? id : "";
        uiPrefs.edit().putString(KEY_ACTIVE_ACCOUNT, this.activeAccountId).apply();
    }

    private Models.Account getActiveAccount() {
        Models.Account a = findAccount(activeAccountId);
        if (a == null && !latestStatus.accounts.isEmpty()) {
            a = latestStatus.accounts.get(0);
        }
        return a;
    }

    private Models.Account findAccount(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Models.Account a : latestStatus.accounts) {
            if (id.equals(a.id)) return a;
        }
        return null;
    }

    private void startRunPolling() {
        if (pollingRun) return;
        pollingRun = true;
        runPollTask = new Runnable() {
            @Override
            public void run() {
                if (!pollingRun) return;
                executor.execute(() -> {
                    try {
                        Models.StatusResponse st = api.getStatus();
                        ScheduleHelper.syncFromAccounts(MainActivity.this, st.accounts);
                        mainHandler.post(() -> {
                            Models.Account active = null;
                            for (Models.Account a : st.accounts) {
                                if (a.id.equals(activeAccountId)) {
                                    active = a;
                                    break;
                                }
                            }
                            if (active == null && !st.accounts.isEmpty()) active = st.accounts.get(0);
                            boolean stillRunning = active != null && active.state != null && active.state.running;
                            latestStatus = st;
                            updateTopBarAccountPill();
                            renderAllTabs();
                            if (stillRunning) {
                                mainHandler.postDelayed(runPollTask, 1500L);
                            } else {
                                stopRunPolling();
                                drainAndPostLocalNotifications();
                            }
                        });
                    } catch (Throwable ignored) {
                        if (pollingRun) {
                            mainHandler.postDelayed(runPollTask, 2000L);
                        }
                    }
                });
            }
        };
        mainHandler.postDelayed(runPollTask, 1500L);
    }

    private void stopRunPolling() {
        pollingRun = false;
        if (runPollTask != null) {
            mainHandler.removeCallbacks(runPollTask);
            runPollTask = null;
        }
    }

    private void drainAndPostLocalNotifications() {
        executor.execute(() -> {
            try {
                List<Models.LocalNotification> notifs = api.drainNotifications();
                if (notifs != null) {
                    for (Models.LocalNotification n : notifs) {
                        LocalNotifyHelper.postTaskNotification(this, n.title, n.body);
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    // ── Top Bar ──────────────────────────────────────────────────────────────

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(p.bg);
        bar.setPadding(HubanUi.dp(this, 16), HubanUi.dp(this, 10), HubanUi.dp(this, 16), HubanUi.dp(this, 10));

        // Warm orange brand mark
        ImageView logoIv = new ImageView(this);
        logoIv.setImageResource(R.mipmap.ic_launcher_round);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(HubanUi.dp(this, 28), HubanUi.dp(this, 28));
        logoLp.rightMargin = HubanUi.dp(this, 8);
        bar.addView(logoIv, logoLp);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(p.primaryText);
        bar.addView(title);

        // Account switcher pill
        LinearLayout switcher = new LinearLayout(this);
        switcher.setOrientation(LinearLayout.HORIZONTAL);
        switcher.setGravity(Gravity.CENTER_VERTICAL);
        switcher.setBackground(HubanUi.rippleRect(this, p, p.card, 99, 1, p.cardBorder));
        switcher.setPadding(HubanUi.dp(this, 10), HubanUi.dp(this, 5), HubanUi.dp(this, 10), HubanUi.dp(this, 5));
        HubanUi.attachPressBounce(switcher, 0.95f);
        switcher.setOnClickListener(v -> showAccountSwitcherSheet());

        View dot = new View(this);
        dot.setBackground(HubanUi.roundedRect(this, p.brandStart, 99, 0, 0));
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(HubanUi.dp(this, 7), HubanUi.dp(this, 7));
        dLp.rightMargin = HubanUi.dp(this, 6);
        switcher.addView(dot, dLp);

        accountSwitcherLabel = new TextView(this);
        accountSwitcherLabel.setText("未添加账号");
        accountSwitcherLabel.setTextSize(12.5f);
        accountSwitcherLabel.setTypeface(Typeface.DEFAULT_BOLD);
        accountSwitcherLabel.setTextColor(p.primaryText);
        accountSwitcherLabel.setSingleLine(true);
        accountSwitcherLabel.setMaxWidth(HubanUi.dp(this, 128));
        accountSwitcherLabel.setEllipsize(TextUtils.TruncateAt.END);
        switcher.addView(accountSwitcherLabel);

        TextView chev = new TextView(this);
        chev.setText(" ▾");
        chev.setTextSize(11.5f);
        chev.setTextColor(p.secondaryText);
        switcher.addView(chev);

        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.leftMargin = HubanUi.dp(this, 10);
        bar.addView(switcher, swLp);

        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // Sync button
        topSyncBtn = HubanUi.createSecondaryButton(this, p, "同步");
        topSyncBtn.setTextSize(12.5f);
        topSyncBtn.setPadding(HubanUi.dp(this, 12), HubanUi.dp(this, 6), HubanUi.dp(this, 12), HubanUi.dp(this, 6));
        topSyncBtn.setOnClickListener(v -> triggerRefreshActiveAccount());
        bar.addView(topSyncBtn);

        return bar;
    }

    private void updateTopBarAccountPill() {
        Models.Account active = getActiveAccount();
        if (active == null) {
            accountSwitcherLabel.setText("添加账号");
        } else {
            accountSwitcherLabel.setText(active.displayName());
        }
    }

    private void showAccountSwitcherSheet() {
        List<String> labels = new ArrayList<>();
        List<String> descs = new ArrayList<>();
        int selectedIdx = -1;
        for (int i = 0; i < latestStatus.accounts.size(); i++) {
            Models.Account a = latestStatus.accounts.get(i);
            labels.add(a.displayName());
            String mode = a.isMobileAuth() ? "长期自动续期" : "网页登录";
            descs.add("UID " + a.uid + " · " + mode + " · " + a.state.badges.size() + " 个粉丝牌");
            if (a.id.equals(activeAccountId)) {
                selectedIdx = i;
            }
        }
        labels.add("+ 添加新虎牙账号");
        descs.add("支持手机短信（长期续期）、微信/QQ/虎牙扫码或 Cookie 导入");

        HubanUi.showOptionSheet(
                this, p,
                "切换账号",
                "选择当前管理的虎牙账号，或添加新账号",
                labels, descs, selectedIdx,
                idx -> {
                    if (idx >= latestStatus.accounts.size()) {
                        openAddAccountModal();
                    } else {
                        Models.Account picked = latestStatus.accounts.get(idx);
                        setActiveAccountId(picked.id);
                        updateTopBarAccountPill();
                        renderAllTabs();
                    }
                }
        );
    }

    private void triggerRefreshActiveAccount() {
        if (syncingAccount) return;
        Models.Account active = getActiveAccount();
        if (active == null) {
            bootCoreAndLoadStatus();
            return;
        }
        syncingAccount = true;
        topSyncBtn.setText("同步中...");
        executor.execute(() -> {
            try {
                api.refreshAccount(active.id);
                Models.StatusResponse st = api.getStatus();
                ScheduleHelper.syncFromAccounts(this, st.accounts);
                mainHandler.post(() -> {
                    syncingAccount = false;
                    topSyncBtn.setText("同步");
                    applyStatus(st);
                    Toast.makeText(this, "粉丝牌与背包虎粮已同步", Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    syncingAccount = false;
                    topSyncBtn.setText("同步");
                    Toast.makeText(this, "同步失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    // ── Bottom Navigation Bar (Module-App Style Animations) ──────────────────

    private View buildBottomNavBar() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackgroundColor(p.card);
        nav.setPadding(HubanUi.dp(this, 8), HubanUi.dp(this, 6), HubanUi.dp(this, 8), HubanUi.dp(this, 8));
        nav.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, HubanUi.dp(this, 64)));
        nav.setClipChildren(false);
        nav.setClipToPadding(false);

        String[] titles = {"任务", "粉丝牌", "账号", "设置"};
        for (int i = 0; i < TAB_COUNT; i++) {
            final int tabIdx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setClickable(true);
            item.setFocusable(true);
            item.setClipChildren(false);
            item.setClipToPadding(false);
            HubanUi.attachPressBounce(item, 0.92f);
            item.setOnClickListener(v -> selectTab(tabIdx));

            FrameLayout iconBox = new FrameLayout(this);
            iconBox.setClipChildren(false);
            iconBox.setClipToPadding(false);

            View pillBg = new View(this);
            pillBg.setBackground(HubanUi.roundedRect(this, p.brandTintBg, 99, 0, 0));
            pillBg.setAlpha(0f);
            pillBg.setScaleX(0.45f);
            iconBox.addView(pillBg, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            navPillViews[i] = pillBg;

            ImageView iv = new ImageView(this);
            TabIconDrawable dr = new TabIconDrawable(this, i, p.secondaryText, p.brandTintBg);
            iv.setImageDrawable(dr);
            iconBox.addView(iv, new FrameLayout.LayoutParams(
                    HubanUi.dp(this, 20), HubanUi.dp(this, 20), Gravity.CENTER));
            navIconViews[i] = iv;
            navIconDrawables[i] = dr;

            if (i == TAB_SETTINGS) {
                View dot = new View(this);
                dot.setBackground(HubanUi.roundedRect(this, p.brandEnd, 99, 1.5f, p.card));
                dot.setVisibility(View.GONE);
                FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(
                        HubanUi.dp(this, 9), HubanUi.dp(this, 9), Gravity.TOP | Gravity.END);
                dotLp.topMargin = HubanUi.dp(this, 2);
                dotLp.rightMargin = HubanUi.dp(this, 10);
                iconBox.addView(dot, dotLp);
                settingsTabBadgeDot = dot;
            }

            item.addView(iconBox, new LinearLayout.LayoutParams(HubanUi.dp(this, 56), HubanUi.dp(this, 30)));

            TextView tv = new TextView(this);
            tv.setText(titles[i]);
            tv.setTextSize(11.5f);
            tv.setTextColor(p.secondaryText);
            tv.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.topMargin = HubanUi.dp(this, 3);
            item.addView(tv, tLp);
            navLabelViews[i] = tv;

            nav.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        return nav;
    }

    private void selectTab(int index) {
        int prevTab = currentTab;
        boolean animate = hasInitializedTab;
        boolean reselect = animate && (prevTab == index);
        hasInitializedTab = true;
        currentTab = index;

        if (reselect) {
            playTabIconAnimation(index);
            if (index == TAB_BADGES) {
                playBadgesEntranceAnimation();
            } else if (index == TAB_SETTINGS) {
                playHeroIconBounce();
            }
            return;
        }

        int direction = index > prevTab ? 1 : -1;

        for (int i = 0; i < TAB_COUNT; i++) {
            boolean active = (i == index);
            View page = tabPages[i];
            if (page != null) {
                page.animate().cancel();
                if (active) {
                    page.setVisibility(View.VISIBLE);
                    if (animate) {
                        page.setAlpha(0f);
                        page.setTranslationX(direction * HubanUi.dp(this, 18));
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

            View pillBg = navPillViews[i];
            if (pillBg != null) {
                pillBg.animate().cancel();
                if (active) {
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

            if (navIconDrawables[i] != null) {
                navIconDrawables[i].setFilled(active);
            }
            animateNavTabColors(i, active, animate && (active || i == prevTab));
            if (navLabelViews[i] != null) {
                navLabelViews[i].setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
            if (active && animate) {
                playTabIconAnimation(i);
            }
        }

        if (index == TAB_SETTINGS) {
            refreshSettingsPermissionStates();
            updateEngineStatusLabel();
        }
        if (animate) {
            if (index == TAB_BADGES) {
                playBadgesEntranceAnimation();
            } else if (index == TAB_SETTINGS) {
                playHeroIconBounce();
            }
        }
    }

    private void animateNavTabColors(final int tabIdx, boolean selected, boolean animate) {
        if (navColorAnimators[tabIdx] != null) {
            navColorAnimators[tabIdx].cancel();
            navColorAnimators[tabIdx] = null;
        }
        int targetColor = selected ? p.brandText : p.secondaryText;
        if (!animate) {
            if (navIconDrawables[tabIdx] != null) {
                navIconDrawables[tabIdx].setTintColor(targetColor);
                navIconViews[tabIdx].invalidate();
            }
            if (navLabelViews[tabIdx] != null) {
                navLabelViews[tabIdx].setTextColor(targetColor);
            }
            return;
        }
        int fromColor = selected ? p.secondaryText : p.brandText;
        ValueAnimator va = ValueAnimator.ofObject(new ArgbEvaluator(), fromColor, targetColor);
        va.setDuration(200L);
        va.addUpdateListener(anim -> {
            int c = (Integer) anim.getAnimatedValue();
            if (navIconDrawables[tabIdx] != null) {
                navIconDrawables[tabIdx].setTintColor(c);
                navIconViews[tabIdx].invalidate();
            }
            if (navLabelViews[tabIdx] != null) {
                navLabelViews[tabIdx].setTextColor(c);
            }
        });
        navColorAnimators[tabIdx] = va;
        va.start();
    }

    private void playTabIconAnimation(int tabIdx) {
        ImageView icon = navIconViews[tabIdx];
        if (icon == null) return;
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
            ObjectAnimator sx = ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.80f, 1.22f, 0.96f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.80f, 1.22f, 0.96f, 1f);
            ObjectAnimator rot = ObjectAnimator.ofFloat(icon, View.ROTATION, 0f, -13f, 8f, 0f);
            set.setDuration(340L);
            set.setInterpolator(new DecelerateInterpolator(1.5f));
            set.playTogether(sx, sy, rot);
        } else if (tabIdx == TAB_BADGES) {
            ObjectAnimator sx = ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.86f, 1.26f, 0.94f, 1.13f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.86f, 1.26f, 0.94f, 1.13f, 1f);
            set.setDuration(400L);
            set.setInterpolator(new DecelerateInterpolator(1.4f));
            set.playTogether(sx, sy);
        } else if (tabIdx == TAB_ACCOUNTS) {
            ObjectAnimator sx = ObjectAnimator.ofFloat(icon, View.SCALE_X, 0.84f, 1.16f, 1f);
            ObjectAnimator sy = ObjectAnimator.ofFloat(icon, View.SCALE_Y, 0.84f, 1.16f, 1f);
            ObjectAnimator ty = ObjectAnimator.ofFloat(icon, View.TRANSLATION_Y, 0f, -HubanUi.dp(this, 3), 0f);
            set.setDuration(320L);
            set.setInterpolator(new OvershootInterpolator(1.4f));
            set.playTogether(sx, sy, ty);
        } else {
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
        if (aboutHeroIconView == null) return;
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
        int rowLimit = Math.min(badgeRowViews.size(), 12);
        for (int i = 0; i < rowLimit; i++) {
            View row = badgeRowViews.get(i);
            row.animate().cancel();
            row.setAlpha(0f);
            row.setTranslationY(HubanUi.dp(this, 10));
            row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(i * 45L)
                    .setDuration(260L)
                    .setInterpolator(new DecelerateInterpolator(2.0f))
                    .start();
        }
        int fillLimit = Math.min(badgeFillViews.size(), 12);
        for (int i = 0; i < fillLimit; i++) {
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

    private void renderAllTabs() {
        renderTasksTab();
        renderBadgesList();
        renderAccountsList();
        updateEngineStatusLabel();
    }

    // ── TAB 0: Tasks ─────────────────────────────────────────────────────────

    private LinearLayout buildTasksTab() {
        tasksPageRoot = createPageContainer();
        return tasksPageRoot;
    }

    private void renderTasksTab() {
        if (tasksPageRoot == null) return;
        tasksPageRoot.removeAllViews();

        Models.Account acc = getActiveAccount();
        if (acc == null) {
            LinearLayout emptyCard = HubanUi.createCard(this, p);
            emptyCard.setGravity(Gravity.CENTER_HORIZONTAL);
            emptyCard.setPadding(HubanUi.dp(this, 22), HubanUi.dp(this, 28), HubanUi.dp(this, 22), HubanUi.dp(this, 28));

            ImageView iv = new ImageView(this);
            iv.setImageResource(R.mipmap.ic_launcher_round);
            emptyCard.addView(iv, new LinearLayout.LayoutParams(HubanUi.dp(this, 56), HubanUi.dp(this, 56)));

            TextView title = new TextView(this);
            title.setText("欢迎使用虎伴");
            title.setTextSize(18f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(p.primaryText);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tLp.topMargin = HubanUi.dp(this, 12);
            emptyCard.addView(title, tLp);

            TextView desc = new TextView(this);
            desc.setText("添加虎牙账号后，即可支持多账号自动领免费虎粮、全粉丝牌打卡、每日福利与超粉日 4 倍智能囤粮送礼。");
            desc.setTextColor(p.secondaryText);
            desc.setTextSize(13.5f);
            desc.setGravity(Gravity.CENTER);
            desc.setLineSpacing(0f, 1.45f);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = HubanUi.dp(this, 8);
            emptyCard.addView(desc, dLp);

            TextView addBtn = HubanUi.createPrimaryButton(this, p, "添加第一个虎牙账号");
            addBtn.setOnClickListener(v -> openAddAccountModal());
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, HubanUi.dp(this, 46));
            bLp.topMargin = HubanUi.dp(this, 18);
            emptyCard.addView(addBtn, bLp);

            tasksPageRoot.addView(emptyCard);
            return;
        }

        final Models.AccountState st = acc.state;
        final Models.AccountConfig cfg = acc.config;

        // 1. SuperFan Day active banner
        if (st.sfDayActiveSummary != null && !st.sfDayActiveSummary.trim().isEmpty()) {
            LinearLayout sfCard = new LinearLayout(this);
            sfCard.setOrientation(LinearLayout.HORIZONTAL);
            sfCard.setGravity(Gravity.CENTER_VERTICAL);
            sfCard.setBackground(HubanUi.roundedRect(this, p.brandTintBg, 16, 1.2f, p.brandStart));
            sfCard.setPadding(HubanUi.dp(this, 14), HubanUi.dp(this, 12), HubanUi.dp(this, 14), HubanUi.dp(this, 12));
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.bottomMargin = HubanUi.dp(this, 12);
            sfCard.setLayoutParams(sLp);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView sfTitle = new TextView(this);
            sfTitle.setText("今日超粉日进行中（4倍亲密度）");
            sfTitle.setTextColor(p.brandText);
            sfTitle.setTextSize(14f);
            sfTitle.setTypeface(Typeface.DEFAULT_BOLD);
            col.addView(sfTitle);

            TextView sfSub = new TextView(this);
            sfSub.setText(st.sfDayActiveSummary);
            sfSub.setTextColor(p.secondaryText);
            sfSub.setTextSize(12.5f);
            LinearLayout.LayoutParams ssLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ssLp.topMargin = HubanUi.dp(this, 2);
            col.addView(sfSub, ssLp);
            sfCard.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView goBtn = HubanUi.createPrimaryButton(this, p, "去送粮");
            goBtn.setTextSize(12f);
            goBtn.setPadding(HubanUi.dp(this, 12), HubanUi.dp(this, 7), HubanUi.dp(this, 12), HubanUi.dp(this, 7));
            goBtn.setOnClickListener(v -> {
                renderBadgesList();
                selectTab(TAB_BADGES);
            });
            sfCard.addView(goBtn);
            tasksPageRoot.addView(sfCard);
        }

        // 2. Live 3-column stats + Primary Run CTA + Schedule info card
        LinearLayout heroCard = HubanUi.createCard(this, p);

        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int claimedToday = today.equals(st.todayClaimedDay) ? st.todayClaimedCount : 0;
        String checkinStr = today.equals(st.todayCheckinDay) && st.todayCheckinTotal > 0
                ? (st.todayCheckinCount + "/" + st.todayCheckinTotal)
                : (st.badges.isEmpty() ? "未同步" : ("0/" + st.badges.size()));
        String freeBagStr = st.freeHuliangCount >= 0 ? String.valueOf(st.freeHuliangCount) : "未同步";

        addStatTile(statsRow, "今日领虎粮", claimedToday + " 次", null, null, p.brandText, true, null);
        addStatTile(statsRow, "粉丝牌打卡", checkinStr, null, null, p.okText, true, v -> selectTab(TAB_BADGES));

        String expiringBadge = st.freeExpiring24h > 0 ? ("24h过期 " + st.freeExpiring24h) : null;
        String superSub = st.superHuliangCount >= 0
                ? ((st.superHuliangName.isEmpty() ? "超粉虎粮" : st.superHuliangName) + " " + st.superHuliangCount)
                : null;
        addStatTile(statsRow, "免费虎粮", freeBagStr, expiringBadge, superSub, p.infoText, false, null);
        heroCard.addView(statsRow);

        // Primary "立即执行任务" CTA (with breathing pulse animation while running)
        if (runPulseAnimator != null) {
            runPulseAnimator.cancel();
            runPulseAnimator = null;
        }
        TextView runBtn = HubanUi.createPrimaryButton(this, p, st.running ? "任务执行中，请稍候..." : "立即执行任务");
        runBtn.setEnabled(!st.running);
        runBtn.setOnClickListener(v -> triggerRunAccount(acc.id));
        if (st.running) {
            runPulseAnimator = ObjectAnimator.ofFloat(runBtn, View.ALPHA, 1f, 0.68f);
            runPulseAnimator.setDuration(720L);
            runPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
            runPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            runPulseAnimator.start();
        }
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, HubanUi.dp(this, 46));
        rLp.topMargin = HubanUi.dp(this, 14);
        heroCard.addView(runBtn, rLp);

        // Last run & upcoming schedule metadata
        LinearLayout metaBox = new LinearLayout(this);
        metaBox.setOrientation(LinearLayout.VERTICAL);
        metaBox.setBackground(HubanUi.roundedRect(this, p.subSurface, 12, 0, 0));
        metaBox.setPadding(HubanUi.dp(this, 12), HubanUi.dp(this, 10), HubanUi.dp(this, 12), HubanUi.dp(this, 10));
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = HubanUi.dp(this, 12);

        String lastLine;
        if (st.lastRunTime > 0) {
            lastLine = "上次执行：" + formatDateTime(st.lastRunTime)
                    + (st.lastRunSuccess ? "（成功）" : "（异常）")
                    + (st.lastRunMessage.isEmpty() ? "" : ("\n" + st.lastRunMessage));
        } else {
            lastLine = "尚未执行过任务，可点击上方按钮立即运行";
        }
        TextView lastTv = new TextView(this);
        lastTv.setText(lastLine);
        lastTv.setTextColor(st.lastRunTime > 0 && !st.lastRunSuccess ? p.errText : p.secondaryText);
        lastTv.setTextSize(12.2f);
        lastTv.setLineSpacing(0f, 1.35f);
        metaBox.addView(lastTv);

        StringBuilder schedSb = new StringBuilder();
        if (st.nextScheduleTime > 0) {
            schedSb.append("下次定时：").append(formatDateTime(st.nextScheduleTime));
        }
        if (st.nextRescueTime > 0) {
            if (schedSb.length() > 0) schedSb.append("  ·  ");
            schedSb.append("防过期抢救：").append(formatDateTime(st.nextRescueTime));
        }
        if (st.nextRetryTime > 0) {
            if (schedSb.length() > 0) schedSb.append("  ·  ");
            schedSb.append("冷却重试：").append(formatDateTime(st.nextRetryTime));
        }
        if (schedSb.length() > 0) {
            TextView schedTv = new TextView(this);
            schedTv.setText(schedSb.toString());
            schedTv.setTextColor(p.tertiaryText);
            schedTv.setTextSize(11.5f);
            LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scLp.topMargin = HubanUi.dp(this, 5);
            metaBox.addView(schedTv, scLp);
        }
        heroCard.addView(metaBox, mLp);
        tasksPageRoot.addView(heroCard);

        // 3. Task Settings Card
        tasksPageRoot.addView(HubanUi.createSectionLabel(this, p, "自动化任务配置"));
        LinearLayout settingsCard = HubanUi.createCard(this, p);
        settingsCard.setPadding(HubanUi.dp(this, 16), HubanUi.dp(this, 6), HubanUi.dp(this, 16), HubanUi.dp(this, 6));
        HubanUi.enableSmoothLayoutTransition(settingsCard);

        addSwitchRow(settingsCard, "每日定时执行", "到点通过系统闹钟自动唤醒并在后台执行", cfg.autoClaimEnabled, checked -> {
            Models.AccountConfig next = cfg.copy();
            next.autoClaimEnabled = checked;
            saveAccountConfig(acc.id, next);
        });
        settingsCard.addView(HubanUi.createDivider(this, p));

        String timeVal = String.format(Locale.US, "%02d:%02d", cfg.autoClaimHour, cfg.autoClaimMinute);
        addValuePickerRow(settingsCard, "定时执行时间", "建议设置在 08:00 - 10:00", timeVal, v ->
                HubanUi.showTimePickerSheet(this, p, cfg.autoClaimHour, cfg.autoClaimMinute, (h, m) -> {
                    Models.AccountConfig next = cfg.copy();
                    next.autoClaimHour = h;
                    next.autoClaimMinute = m;
                    saveAccountConfig(acc.id, next);
                }));
        settingsCard.addView(HubanUi.createDivider(this, p));

        addSwitchRow(settingsCard, "看广告领虎粮", "自动完成背包广告任务领取免费虎粮", cfg.autoAdHuliang, checked -> {
            Models.AccountConfig next = cfg.copy();
            next.autoAdHuliang = checked;
            saveAccountConfig(acc.id, next);
        });
        settingsCard.addView(HubanUi.createDivider(this, p));

        addSwitchRow(settingsCard, "全粉丝牌打卡", "自动为账号下所有粉丝牌执行每日打卡", cfg.autoFansCheckin, checked -> {
            Models.AccountConfig next = cfg.copy();
            next.autoFansCheckin = checked;
            saveAccountConfig(acc.id, next);
        });
        settingsCard.addView(HubanUi.createDivider(this, p));

        addSwitchRow(settingsCard, "每日福利领取", "自动领取粉丝团每日 10 虎粮宝箱及公共/超粉福利", cfg.autoClaimWelfare, checked -> {
            Models.AccountConfig next = cfg.copy();
            next.autoClaimWelfare = checked;
            saveAccountConfig(acc.id, next);
        });
        settingsCard.addView(HubanUi.createDivider(this, p));

        addSwitchRow(settingsCard, "自动送免费虎粮", "严格仅消耗免费背包虎粮，绝不花费金豆/虎牙币", cfg.autoSendHuliang, checked -> {
            Models.AccountConfig next = cfg.copy();
            next.autoSendHuliang = checked;
            saveAccountConfig(acc.id, next);
        });
        settingsCard.addView(HubanUi.createDivider(this, p));

        addSwitchRow(settingsCard, "自动送超粉虎粮", "优先将背包中的超粉专属虎粮送给超粉目标主播", cfg.sendSuperHuliang, checked -> {
            Models.AccountConfig next = cfg.copy();
            next.sendSuperHuliang = checked;
            saveAccountConfig(acc.id, next);
        });
        settingsCard.addView(HubanUi.createDivider(this, p));

        addSwitchRow(settingsCard, "超粉日 4 倍智能囤粮", "平时保留免费虎粮（临近 24h 过期自动抢救），超粉日集中爆发", cfg.smartSuperFanDay, checked -> {
            Models.AccountConfig next = cfg.copy();
            next.smartSuperFanDay = checked;
            saveAccountConfig(acc.id, next);
        });
        settingsCard.addView(HubanUi.createDivider(this, p));

        String targetsSummary = formatTargetsSummary(cfg.sendTargets);
        addValuePickerRow(settingsCard, "赠送目标主播", "支持多选、自定义 UID 及自定义权重份数", targetsSummary,
                v -> openTargetsPicker(acc));
        settingsCard.addView(HubanUi.createDivider(this, p));

        int dMode = Math.max(0, Math.min(DISTRIBUTE_LABELS.length - 1, cfg.sendDistributeMode));
        addValuePickerRow(settingsCard, "虎粮分配策略", DISTRIBUTE_DESCS[dMode], DISTRIBUTE_LABELS[dMode], v ->
                HubanUi.showOptionSheet(
                        this, p,
                        "选择虎粮分配策略",
                        "决定多目标赠送时的分配优先级",
                        Arrays.asList(DISTRIBUTE_LABELS),
                        Arrays.asList(DISTRIBUTE_DESCS),
                        dMode,
                        picked -> {
                            Models.AccountConfig next = cfg.copy();
                            next.sendDistributeMode = picked;
                            saveAccountConfig(acc.id, next);
                        }
                ));
        settingsCard.addView(HubanUi.createDivider(this, p));

        String maxText = cfg.sendMaxCount <= 0 ? "不限制" : (cfg.sendMaxCount + " 个");
        addValuePickerRow(settingsCard, "单次赠送上限", "每次自动任务最多送出的免费虎粮数量（0 为不限）", maxText, v ->
                HubanUi.showInputModal(
                        this, p,
                        "设置单次赠送上限",
                        "输入 0 表示不限制数量，留空同样为不限制",
                        cfg.sendMaxCount > 0 ? String.valueOf(cfg.sendMaxCount) : "0",
                        "例如 100",
                        InputType.TYPE_CLASS_NUMBER,
                        "保存",
                        text -> {
                            int val = 0;
                            if (!text.isEmpty()) {
                                try {
                                    val = Math.max(0, Integer.parseInt(text));
                                } catch (NumberFormatException e) {
                                    Toast.makeText(this, "请输入有效数字", Toast.LENGTH_SHORT).show();
                                    return false;
                                }
                            }
                            Models.AccountConfig next = cfg.copy();
                            next.sendMaxCount = val;
                            saveAccountConfig(acc.id, next);
                            return true;
                        }
                ));

        tasksPageRoot.addView(settingsCard);

        // 4. Recent Execution History
        tasksPageRoot.addView(HubanUi.createSectionLabel(this, p, "最近执行记录"));
        if (st.history.isEmpty()) {
            LinearLayout emptyHist = HubanUi.createCard(this, p);
            TextView tv = new TextView(this);
            tv.setText("暂无执行记录");
            tv.setTextColor(p.tertiaryText);
            tv.setTextSize(13f);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(0, HubanUi.dp(this, 8), 0, HubanUi.dp(this, 8));
            emptyHist.addView(tv);
            tasksPageRoot.addView(emptyHist);
        } else {
            int limit = Math.min(st.history.size(), 10);
            for (int i = 0; i < limit; i++) {
                Models.RunRecord rec = st.history.get(i);
                LinearLayout hCard = HubanUi.createCard(this, p);
                hCard.setPadding(HubanUi.dp(this, 14), HubanUi.dp(this, 12), HubanUi.dp(this, 14), HubanUi.dp(this, 12));

                LinearLayout topRow = new LinearLayout(this);
                topRow.setOrientation(LinearLayout.HORIZONTAL);
                topRow.setGravity(Gravity.CENTER_VERTICAL);

                TextView timeTv = new TextView(this);
                timeTv.setText(formatDateTime(rec.time));
                timeTv.setTextColor(p.primaryText);
                timeTv.setTextSize(13f);
                timeTv.setTypeface(Typeface.DEFAULT_BOLD);
                topRow.addView(timeTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView trigPill = HubanUi.createPillBadge(this, formatTriggerLabel(rec.trigger),
                        p.secondaryText, p.subSurface);
                LinearLayout.LayoutParams tpLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tpLp.rightMargin = HubanUi.dp(this, 6);
                topRow.addView(trigPill, tpLp);

                TextView stPill = HubanUi.createPillBadge(this,
                        rec.success ? "成功" : "异常",
                        rec.success ? p.okText : p.errText,
                        rec.success ? p.okBg : p.errBg);
                topRow.addView(stPill);
                hCard.addView(topRow);

                if (rec.message != null && !rec.message.isEmpty()) {
                    TextView msgTv = new TextView(this);
                    msgTv.setText(rec.message);
                    msgTv.setTextColor(p.secondaryText);
                    msgTv.setTextSize(12.5f);
                    msgTv.setLineSpacing(0f, 1.35f);
                    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    mp.topMargin = HubanUi.dp(this, 6);
                    hCard.addView(msgTv, mp);
                }

                StringBuilder sum = new StringBuilder();
                if (rec.adClaimed > 0) sum.append("广告领粮 ").append(rec.adClaimed).append("次");
                if (rec.checkinAll > 0) {
                    if (sum.length() > 0) sum.append(" · ");
                    sum.append("打卡 ").append(rec.checkinOk).append("/").append(rec.checkinAll);
                }
                if (rec.sentNormal > 0 || rec.sentSuper > 0) {
                    if (sum.length() > 0) sum.append(" · ");
                    sum.append("送出 免费").append(rec.sentNormal);
                    if (rec.sentSuper > 0) sum.append("+超粉").append(rec.sentSuper);
                }
                if (sum.length() > 0) {
                    TextView sumTv = new TextView(this);
                    sumTv.setText(sum.toString());
                    sumTv.setTextColor(p.tertiaryText);
                    sumTv.setTextSize(11.5f);
                    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    sp.topMargin = HubanUi.dp(this, 4);
                    hCard.addView(sumTv, sp);
                }
                tasksPageRoot.addView(hCard);
            }
        }
    }

    private void addStatTile(
            LinearLayout parent,
            String label,
            String value,
            String redBadge,
            String subText,
            int valueColor,
            boolean addRightGap,
            View.OnClickListener onClick
    ) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(HubanUi.roundedRect(this, p.subSurface, 14, 0, 0));
        tile.setPadding(HubanUi.dp(this, 10), HubanUi.dp(this, 10), HubanUi.dp(this, 10), HubanUi.dp(this, 10));
        if (onClick != null) {
            tile.setClickable(true);
            tile.setBackground(HubanUi.rippleRect(this, p, p.subSurface, 14, 0, 0));
            HubanUi.attachPressBounce(tile, 0.95f);
            tile.setOnClickListener(onClick);
        }

        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextColor(p.secondaryText);
        lb.setTextSize(11.5f);
        lb.setSingleLine(true);
        tile.addView(lb);

        TextView val = new TextView(this);
        val.setText(value);
        val.setTextColor(valueColor);
        val.setTextSize(18f);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        val.setFontFeatureSettings("tnum");
        val.setSingleLine(true);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = HubanUi.dp(this, 4);
        tile.addView(val, vLp);

        if (redBadge != null && !redBadge.isEmpty()) {
            TextView rb = HubanUi.createPillBadge(this, redBadge, p.errText, p.errBg);
            rb.setTextSize(10f);
            LinearLayout.LayoutParams rbLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rbLp.topMargin = HubanUi.dp(this, 4);
            tile.addView(rb, rbLp);
        }
        if (subText != null && !subText.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(subText);
            sub.setTextColor(p.tertiaryText);
            sub.setTextSize(10.5f);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            sLp.topMargin = HubanUi.dp(this, 3);
            tile.addView(sub, sLp);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (addRightGap) lp.rightMargin = HubanUi.dp(this, 8);
        parent.addView(tile, lp);
    }

    private void addSwitchRow(LinearLayout parent, String title, String desc, boolean checked, OnToggle onToggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, HubanUi.dp(this, 11), 0, HubanUi.dp(this, 11));

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(p.primaryText);
        tv.setTextSize(14.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        txt.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(this);
            dv.setText(desc);
            dv.setTextColor(p.secondaryText);
            dv.setTextSize(12f);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = HubanUi.dp(this, 2);
            txt.addView(dv, dLp);
        }
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.rightMargin = HubanUi.dp(this, 12);
        row.addView(txt, tLp);

        Switch sw = HubanUi.createStyledSwitch(this, p);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((btn, isChecked) -> onToggle.onChange(isChecked));
        row.addView(sw);

        row.setOnClickListener(v -> sw.toggle());
        parent.addView(row);
    }

    private void addValuePickerRow(LinearLayout parent, String title, String desc, String value, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, HubanUi.dp(this, 11), 0, HubanUi.dp(this, 11));
        row.setClickable(true);
        row.setOnClickListener(onClick);

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextColor(p.primaryText);
        tv.setTextSize(14.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        txt.addView(tv);

        if (desc != null && !desc.isEmpty()) {
            TextView dv = new TextView(this);
            dv.setText(desc);
            dv.setTextColor(p.secondaryText);
            dv.setTextSize(12f);
            dv.setMaxLines(2);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dLp.topMargin = HubanUi.dp(this, 2);
            txt.addView(dv, dLp);
        }
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tLp.rightMargin = HubanUi.dp(this, 10);
        row.addView(txt, tLp);

        TextView valTv = HubanUi.createPillBadge(this, value + " ›", p.brandText, p.brandTintBg);
        valTv.setTextSize(12f);
        valTv.setMaxWidth(HubanUi.dp(this, 150));
        valTv.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(valTv);

        parent.addView(row);
    }

    private void openTargetsPicker(final Models.Account acc) {
        Map<Long, Models.SendTarget> existing = new LinkedHashMap<>();
        for (Models.SendTarget t : acc.config.sendTargets) {
            existing.put(t.pid, t);
        }

        List<HubanUi.TargetOption> opts = new ArrayList<>();
        for (Models.FanBadge b : acc.state.badges) {
            Models.SendTarget matched = existing.remove(b.pid);
            String title = (b.nick.isEmpty() ? String.valueOf(b.pid) : b.nick)
                    + (b.badgeName.isEmpty() ? "" : (" [" + b.badgeName + " Lv." + b.level + "]"));
            String sub = "UID " + b.pid + (b.quota > 0 ? (" · 今日亲密度 " + b.dayScore + "/" + b.quota) : "");
            opts.add(new HubanUi.TargetOption(
                    b.pid, title, sub, b.superFan,
                    matched != null,
                    matched != null ? matched.weight : 1
            ));
        }
        // Include any custom UIDs not in fan badges list
        for (Models.SendTarget custom : existing.values()) {
            opts.add(new HubanUi.TargetOption(
                    custom.pid,
                    custom.name.isEmpty() ? ("自定义主播 " + custom.pid) : custom.name,
                    "自定义 UID " + custom.pid,
                    false,
                    true,
                    custom.weight
            ));
        }

        boolean showWeights = acc.config.sendDistributeMode == Models.DISTRIBUTE_CUSTOM_RATIO || !acc.config.sendTargets.isEmpty();
        HubanUi.showMultiSelectTargetSheet(
                this, p,
                "选择自动赠送目标",
                "可多选主播；策略选择「按自定义比例分配」时按份数权重分配",
                opts,
                showWeights,
                sheetHandle -> {
                    if (sheetHandle != null) sheetHandle.dismiss();
                    HubanUi.showInputModal(
                            this, p,
                            "添加自定义主播 UID",
                            "输入主播的虎牙 UID（数字），添加到自动赠送目标列表",
                            "",
                            "例如 123456789",
                            InputType.TYPE_CLASS_NUMBER,
                            "添加",
                            text -> {
                                long pid;
                                try {
                                    pid = Long.parseLong(text);
                                } catch (NumberFormatException e) {
                                    Toast.makeText(this, "请输入纯数字 UID", Toast.LENGTH_SHORT).show();
                                    return false;
                                }
                                if (pid <= 0) {
                                    Toast.makeText(this, "UID 必须大于 0", Toast.LENGTH_SHORT).show();
                                    return false;
                                }
                                Models.AccountConfig next = acc.config.copy();
                                boolean found = false;
                                for (Models.SendTarget st : next.sendTargets) {
                                    if (st.pid == pid) {
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    next.sendTargets.add(new Models.SendTarget(pid, "主播 " + pid, 1));
                                }
                                saveAccountConfig(acc.id, next);
                                return true;
                            }
                    );
                },
                confirmed -> {
                    Models.AccountConfig next = acc.config.copy();
                    next.sendTargets.clear();
                    for (HubanUi.TargetOption o : confirmed) {
                        if (o.selected) {
                            String cleanName = o.title;
                            int br = cleanName.indexOf(" [");
                            if (br > 0) cleanName = cleanName.substring(0, br);
                            next.sendTargets.add(new Models.SendTarget(o.pid, cleanName, o.weight));
                        }
                    }
                    saveAccountConfig(acc.id, next);
                }
        );
    }

    private void saveAccountConfig(String accountId, Models.AccountConfig cfg) {
        executor.execute(() -> {
            try {
                api.updateAccountConfig(accountId, null, cfg);
                Models.StatusResponse st = api.getStatus();
                ScheduleHelper.syncFromAccounts(this, st.accounts);
                mainHandler.post(() -> applyStatus(st));
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "保存配置失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
                    loadStatus(false);
                });
            }
        });
    }

    private void triggerRunAccount(String accountId) {
        executor.execute(() -> {
            try {
                String msg = api.runAccount(accountId);
                Models.StatusResponse st = api.getStatus();
                mainHandler.post(() -> {
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    applyStatus(st);
                    startRunPolling();
                });
            } catch (Throwable t) {
                mainHandler.post(() ->
                        Toast.makeText(this, "启动任务失败：" + t.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ── TAB 1: Badges (Module-App Layout: 3-KPI Summary + Unified Card) ────

    private LinearLayout buildBadgesTab() {
        LinearLayout page = createPageContainer();

        // Compact 3-column summary card (持有粉丝牌 / 今日打卡 / 最近同步)
        LinearLayout summaryCard = HubanUi.createCard(this, p);
        summaryCard.setPadding(HubanUi.dp(this, 16), HubanUi.dp(this, 14),
                HubanUi.dp(this, 16), HubanUi.dp(this, 14));

        LinearLayout summaryRow = new LinearLayout(this);
        summaryRow.setOrientation(LinearLayout.HORIZONTAL);
        summaryRow.setGravity(Gravity.CENTER_VERTICAL);

        badgeStatCountView = addBadgeSummaryTile(summaryRow, "持有粉丝牌", "0 枚", p.primaryText, true);
        badgeStatCheckinView = addBadgeSummaryTile(summaryRow, "今日打卡", "0/0", p.okText, true);
        badgeStatSyncTimeView = addBadgeSummaryTile(summaryRow, "最近同步", "未同步", p.secondaryText, false);
        summaryCard.addView(summaryRow);
        page.addView(summaryCard);

        // Section header row: 我的粉丝牌 + 共 N 枚
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bhLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bhLp.topMargin = HubanUi.dp(this, 18);
        bhLp.leftMargin = HubanUi.dp(this, 4);
        bhLp.rightMargin = HubanUi.dp(this, 4);
        bhLp.bottomMargin = HubanUi.dp(this, 8);
        headerRow.setLayoutParams(bhLp);

        TextView listTitle = new TextView(this);
        listTitle.setText("我的粉丝牌");
        listTitle.setTextSize(14f);
        listTitle.setTypeface(Typeface.DEFAULT_BOLD);
        listTitle.setTextColor(p.secondaryText);
        headerRow.addView(listTitle);

        badgesMetaView = new TextView(this);
        badgesMetaView.setTextSize(12f);
        badgesMetaView.setTextColor(p.tertiaryText);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        metaLp.leftMargin = HubanUi.dp(this, 8);
        badgesMetaView.setLayoutParams(metaLp);
        headerRow.addView(badgesMetaView);
        page.addView(headerRow);

        // Single unified card container for all fan badge rows
        badgesContainer = HubanUi.createCard(this, p);
        badgesContainer.setPadding(HubanUi.dp(this, 16), HubanUi.dp(this, 4),
                HubanUi.dp(this, 16), HubanUi.dp(this, 4));
        page.addView(badgesContainer);

        return page;
    }

    private TextView addBadgeSummaryTile(
            LinearLayout parent,
            String label,
            String value,
            int valueColor,
            boolean addRightGap
    ) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setBackground(HubanUi.roundedRect(this, p.subSurface, 12, 0, 0));
        tile.setPadding(HubanUi.dp(this, 11), HubanUi.dp(this, 10),
                HubanUi.dp(this, 11), HubanUi.dp(this, 10));

        TextView labelTv = new TextView(this);
        labelTv.setText(label);
        labelTv.setTextSize(11.5f);
        labelTv.setTextColor(p.tertiaryText);
        labelTv.setSingleLine(true);
        tile.addView(labelTv);

        TextView valueTv = new TextView(this);
        valueTv.setText(value);
        valueTv.setTextSize(17.5f);
        valueTv.setTypeface(Typeface.DEFAULT_BOLD);
        valueTv.setFontFeatureSettings("tnum");
        valueTv.setTextColor(valueColor);
        valueTv.setSingleLine(true);
        LinearLayout.LayoutParams vLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vLp.topMargin = HubanUi.dp(this, 4);
        tile.addView(valueTv, vLp);

        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (addRightGap) {
            tLp.rightMargin = HubanUi.dp(this, 8);
        }
        parent.addView(tile, tLp);
        return valueTv;
    }

    private void renderBadgesList() {
        if (badgesContainer == null || badgesMetaView == null) return;
        badgesContainer.removeAllViews();
        badgeRowViews.clear();
        badgeFillViews.clear();

        Models.Account acc = getActiveAccount();
        if (acc == null) {
            if (badgeStatCountView != null) badgeStatCountView.setText("0 枚");
            if (badgeStatCheckinView != null) badgeStatCheckinView.setText("未登录");
            if (badgeStatSyncTimeView != null) badgeStatSyncTimeView.setText("未同步");
            badgesMetaView.setText("请先添加账号");
            TextView empty = new TextView(this);
            empty.setText("暂无虎牙账号，请先在「账号」页登录添加账号。");
            empty.setTextSize(13f);
            empty.setLineSpacing(HubanUi.dp(this, 4), 1.0f);
            empty.setTextColor(p.secondaryText);
            empty.setPadding(0, HubanUi.dp(this, 16), 0, HubanUi.dp(this, 16));
            badgesContainer.addView(empty);
            return;
        }

        Models.AccountState st = acc.state;
        List<Models.FanBadge> all = st.badges;

        if (badgeStatSyncTimeView != null) {
            if (st.badgesUpdateTime > 0L) {
                badgeStatSyncTimeView.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(st.badgesUpdateTime)));
            } else {
                badgeStatSyncTimeView.setText("未同步");
            }
        }

        if (all.isEmpty()) {
            if (badgeStatCountView != null) badgeStatCountView.setText("0 枚");
            if (badgeStatCheckinView != null) badgeStatCheckinView.setText(st.badgesUpdateTime > 0L ? "0/0" : "未同步");
            badgesMetaView.setText(st.badgesUpdateTime > 0L ? "暂无" : "等待同步");
            TextView empty = new TextView(this);
            empty.setText(st.badgesUpdateTime > 0L
                    ? "当前虎牙账号暂无粉丝牌。"
                    : "点击右上角「同步」后，将展示虎牙原生/定制粉丝牌、守护权益与升级进度。");
            empty.setTextSize(13f);
            empty.setLineSpacing(HubanUi.dp(this, 4), 1.0f);
            empty.setTextColor(p.secondaryText);
            empty.setPadding(0, HubanUi.dp(this, 16), 0, HubanUi.dp(this, 16));
            badgesContainer.addView(empty);
            return;
        }

        int checkedCnt = 0;
        int guardCnt = 0;
        for (Models.FanBadge b : all) {
            if (b.isCheckedIn()) checkedCnt++;
            if (b.guardLevel > 0) guardCnt++;
        }

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int statDone = (today.equals(st.todayCheckinDay) && st.todayCheckinTotal > 0)
                ? st.todayCheckinCount : checkedCnt;
        int statTotal = (today.equals(st.todayCheckinDay) && st.todayCheckinTotal > 0)
                ? st.todayCheckinTotal : all.size();

        if (badgeStatCountView != null) badgeStatCountView.setText(all.size() + " 枚");
        if (badgeStatCheckinView != null) badgeStatCheckinView.setText(statDone + "/" + statTotal);

        StringBuilder metaSb = new StringBuilder();
        metaSb.append("共 ").append(all.size()).append(" 枚");
        if (guardCnt > 0) {
            metaSb.append(" · ").append(guardCnt).append(" 守护");
        }
        badgesMetaView.setText(metaSb.toString());

        for (int i = 0; i < all.size(); i++) {
            Models.FanBadge b = all.get(i);
            View rowView = buildBadgeItemView(acc, b);
            badgeRowViews.add(rowView);
            badgesContainer.addView(rowView);
            if (i < all.size() - 1) {
                badgesContainer.addView(HubanUi.createDivider(this, p));
            }
        }
        if (currentTab == TAB_BADGES) {
            playBadgesEntranceAnimation();
        }
    }

    private View buildBadgeItemView(final Models.Account acc, final Models.FanBadge b) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(0, HubanUi.dp(this, 13), 0, HubanUi.dp(this, 13));
        item.setClickable(true);
        item.setFocusable(true);
        item.setBackground(new RippleDrawable(
                ColorStateList.valueOf(p.isDark ? Color.parseColor("#18FFFFFF") : Color.parseColor("#10000000")),
                null,
                HubanUi.roundedRect(this, Color.WHITE, 10, 0, 0)));
        item.setOnClickListener(v -> showBadgeDetailAndSendSheet(acc, b));

        int[] tierColors = resolveBadgeTierColors(b.level, b.extinguished == 1);

        // Row 1: [Avatar] [Authentic FanBadgeView] AnchorNick [打卡状态]
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        View avatarView = buildAvatarCircle(b.avatar, b.nick, 30);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(
                HubanUi.dp(this, 30), HubanUi.dp(this, 30));
        avLp.rightMargin = HubanUi.dp(this, 8);
        topRow.addView(avatarView, avLp);

        FanBadgeView badgeView = new FanBadgeView(this, 23);
        badgeView.setBadge(b);
        topRow.addView(badgeView);

        TextView nickView = new TextView(this);
        nickView.setText(b.nick.isEmpty() ? (b.badgeName.isEmpty() ? ("UID " + b.pid) : b.badgeName) : b.nick);
        nickView.setTextSize(14.5f);
        nickView.setTypeface(Typeface.DEFAULT_BOLD);
        nickView.setTextColor(p.primaryText);
        nickView.setSingleLine(true);
        nickView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nickLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nickLp.leftMargin = HubanUi.dp(this, 8);
        nickLp.rightMargin = HubanUi.dp(this, 8);
        topRow.addView(nickView, nickLp);

        String stateLabel;
        int stateFg;
        int stateBg;
        if ("success".equals(b.checkinState)) {
            stateLabel = "本次已打卡";
            stateFg = p.okText;
            stateBg = p.okBg;
        } else if ("already".equals(b.checkinState)) {
            stateLabel = "今日已打卡";
            stateFg = p.okText;
            stateBg = p.okBg;
        } else if ("failed".equals(b.checkinState)) {
            stateLabel = "打卡失败";
            stateFg = p.errText;
            stateBg = p.errBg;
        } else {
            stateLabel = "待打卡";
            stateFg = p.secondaryText;
            stateBg = p.subSurface;
        }
        TextView statePill = HubanUi.createPillBadge(this, stateLabel, stateFg, stateBg);
        statePill.setPadding(HubanUi.dp(this, 8), HubanUi.dp(this, 3), HubanUi.dp(this, 8), HubanUi.dp(this, 3));
        topRow.addView(statePill);
        item.addView(topRow);

        // Row 2 (optional): 超粉日 / 守护 / 信仰 / 已熄灭
        boolean isSuperFanAny = b.superFan || b.sfType > 0 || (b.fansIdentity >= 1 && b.fansIdentity <= 4);
        String resolvedGuardLabel = resolveGuardLabel(b.guardLevel, b.guardName);
        String sfDayChipText = formatSuperFanDayChip(isSuperFanAny, b.sfDayActive, b.sfDay, b.sfDayScoreTimes, b.nextSfDayEpoch);
        boolean hasExtraChips = !sfDayChipText.isEmpty()
                || !resolvedGuardLabel.isEmpty()
                || !b.faithName.isEmpty()
                || b.extinguished == 1;

        if (hasExtraChips) {
            LinearLayout privRow = new LinearLayout(this);
            privRow.setOrientation(LinearLayout.HORIZONTAL);
            privRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            prLp.topMargin = HubanUi.dp(this, 7);

            if (!sfDayChipText.isEmpty()) {
                int sfFg;
                int sfBg;
                if (b.sfDayActive) {
                    sfFg = p.isDark ? Color.parseColor("#FDE68A") : Color.parseColor("#B45309");
                    sfBg = p.isDark ? Color.parseColor("#422A0E") : Color.parseColor("#FEF3C7");
                } else if (isSuperFanAny) {
                    sfFg = p.brandText;
                    sfBg = p.brandTintBg;
                } else {
                    sfFg = p.secondaryText;
                    sfBg = p.subSurface;
                }
                TextView sfDayPill = HubanUi.createPillBadge(this, sfDayChipText, sfFg, sfBg);
                sfDayPill.setPadding(HubanUi.dp(this, 7), HubanUi.dp(this, 2), HubanUi.dp(this, 7), HubanUi.dp(this, 2));
                if (b.sfDayActive) {
                    sfDayPill.setBackground(HubanUi.roundedRect(this, sfBg, 99, 1, p.brandStart));
                }
                LinearLayout.LayoutParams sfLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sfLp.rightMargin = HubanUi.dp(this, 6);
                privRow.addView(sfDayPill, sfLp);
            }

            if (!resolvedGuardLabel.isEmpty()) {
                LinearLayout guardChip = new LinearLayout(this);
                guardChip.setOrientation(LinearLayout.HORIZONTAL);
                guardChip.setGravity(Gravity.CENTER_VERTICAL);
                int guardFg;
                int guardBg;
                if (b.guardLevel == 3) {
                    guardFg = p.isDark ? Color.parseColor("#FB923C") : Color.parseColor("#C2410C");
                    guardBg = p.isDark ? Color.parseColor("#3B1E10") : Color.parseColor("#FFEDD5");
                } else if (b.guardLevel == 2) {
                    guardFg = p.isDark ? Color.parseColor("#C084FC") : Color.parseColor("#6D28D9");
                    guardBg = p.isDark ? Color.parseColor("#2E1B4B") : Color.parseColor("#F3E8FF");
                } else {
                    guardFg = p.isDark ? Color.parseColor("#F472B6") : Color.parseColor("#BE185D");
                    guardBg = p.isDark ? Color.parseColor("#3B182C") : Color.parseColor("#FCE7F3");
                }
                guardChip.setBackground(HubanUi.roundedRect(this, guardBg, 99, 0, 0));
                guardChip.setPadding(HubanUi.dp(this, 6), HubanUi.dp(this, 2), HubanUi.dp(this, 8), HubanUi.dp(this, 2));

                if (b.render != null && !b.render.guardIconUrl.isEmpty()) {
                    FanBadgeView.GuardIconView gIv = new FanBadgeView.GuardIconView(this);
                    gIv.loadGuardUrl(b.render.guardIconUrl);
                    LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                            HubanUi.dp(this, 16), HubanUi.dp(this, 16));
                    gLp.rightMargin = HubanUi.dp(this, 4);
                    guardChip.addView(gIv, gLp);
                }
                TextView gTv = new TextView(this);
                gTv.setText(b.guardAccompanyDay > 0
                        ? (resolvedGuardLabel + " · " + b.guardAccompanyDay + "天")
                        : resolvedGuardLabel);
                gTv.setTextSize(11f);
                gTv.setTypeface(Typeface.DEFAULT_BOLD);
                gTv.setTextColor(guardFg);
                guardChip.addView(gTv);

                LinearLayout.LayoutParams gcLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                gcLp.rightMargin = HubanUi.dp(this, 6);
                privRow.addView(guardChip, gcLp);
            }

            if (!b.faithName.isEmpty()) {
                TextView faithPill = HubanUi.createPillBadge(this, b.faithName,
                        p.isDark ? Color.parseColor("#93C5FD") : Color.parseColor("#1D4ED8"),
                        p.isDark ? Color.parseColor("#1E2D4A") : Color.parseColor("#DBEAFE"));
                faithPill.setPadding(HubanUi.dp(this, 7), HubanUi.dp(this, 2), HubanUi.dp(this, 7), HubanUi.dp(this, 2));
                LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                fLp.rightMargin = HubanUi.dp(this, 6);
                privRow.addView(faithPill, fLp);
            }

            if (b.extinguished == 1) {
                TextView extPill = HubanUi.createPillBadge(this, "已熄灭", p.secondaryText, p.subSurface);
                extPill.setPadding(HubanUi.dp(this, 7), HubanUi.dp(this, 2), HubanUi.dp(this, 7), HubanUi.dp(this, 2));
                privRow.addView(extPill);
            }

            item.addView(privRow, prLp);
        }

        // Row 3: Full-width experience progress bar with tier gradient
        int targetTotal;
        int remaining;
        if (b.nextScore > b.score) {
            targetTotal = b.nextScore;
            remaining = b.nextScore - b.score;
        } else if (b.nextScore > 0) {
            targetTotal = b.score + b.nextScore;
            remaining = b.nextScore;
        } else {
            targetTotal = Math.max(b.score, 1);
            remaining = 0;
        }
        float ratio = targetTotal > 0 ? Math.max(0f, Math.min(1f, (float) b.score / (float) targetTotal)) : 0f;

        LinearLayout barTrack = new LinearLayout(this);
        barTrack.setOrientation(LinearLayout.HORIZONTAL);
        barTrack.setBackground(HubanUi.roundedRect(this, p.trackBg, 99, 0, 0));
        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, HubanUi.dp(this, 6));
        trackLp.topMargin = HubanUi.dp(this, 10);

        float fillWeight = Math.max(ratio > 0f ? 0.03f : 0f, ratio);
        if (fillWeight > 0f) {
            View fillView = new View(this);
            GradientDrawable fillBg = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, tierColors);
            fillBg.setCornerRadius(HubanUi.dp(this, 99));
            fillView.setBackground(fillBg);
            badgeFillViews.add(fillView);
            barTrack.addView(fillView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, fillWeight));
        }
        if (fillWeight < 1f) {
            barTrack.addView(new View(this), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - fillWeight));
        }
        item.addView(barTrack, trackLp);

        // Row 4: Experience numbers (left) + Today's intimacy (right)
        LinearLayout expRow = new LinearLayout(this);
        expRow.setOrientation(LinearLayout.HORIZONTAL);
        expRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams expRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        expRowLp.topMargin = HubanUi.dp(this, 7);

        String expText;
        if (remaining > 0) {
            expText = "经验 " + formatNum(b.score) + " / " + formatNum(targetTotal)
                    + " · 还差 " + formatNum(remaining);
        } else if (b.score > 0) {
            expText = "经验 " + formatNum(b.score);
        } else {
            expText = "经验 0";
        }
        TextView expLeft = new TextView(this);
        expLeft.setText(expText);
        expLeft.setTextSize(12f);
        expLeft.setFontFeatureSettings("tnum");
        expLeft.setTextColor(p.secondaryText);
        expRow.addView(expLeft, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int todayIntimacy = b.dayScore + Math.max(0, b.quotaUsed);
        String todayText = b.quota > 0
                ? ("今日 " + formatNum(todayIntimacy) + "/" + formatNum(b.quota))
                : ("今日 +" + formatNum(todayIntimacy));
        TextView expRight = new TextView(this);
        expRight.setText(todayText);
        expRight.setTextSize(12f);
        expRight.setTypeface(Typeface.DEFAULT_BOLD);
        expRight.setFontFeatureSettings("tnum");
        expRight.setTextColor(todayIntimacy > 0 ? p.brandText : p.tertiaryText);
        expRow.addView(expRight);

        item.addView(expRow, expRowLp);
        return item;
    }

    private static String resolveGuardLabel(int guardLevel, String guardName) {
        if (guardName != null && !guardName.isEmpty()) return guardName;
        if (guardLevel == 1) return "初爱守护";
        if (guardLevel == 2) return "超级守护";
        if (guardLevel == 3) return "至尊守护";
        return "";
    }

    private static String formatSuperFanDayChip(
            boolean isSuperFan,
            boolean sfDayActive,
            int sfDay,
            int sfDayScoreTimes,
            long nextSfDayEpoch
    ) {
        int times = sfDayScoreTimes > 1 ? sfDayScoreTimes : 4;
        if (sfDayActive) {
            return isSuperFan ? ("超粉日 · " + times + "倍") : "今日超粉日";
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

    private static int[] resolveBadgeTierColors(int level, boolean extinguished) {
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

    private static String formatNum(int n) {
        return String.format(Locale.US, "%,d", n);
    }

    private void showBadgeDetailAndSendSheet(final Models.Account acc, final Models.FanBadge b) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        // Anchor info + Copy UID row
        LinearLayout infoRow = new LinearLayout(this);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);
        infoRow.setBackground(HubanUi.roundedRect(this, p.subSurface, 14, 0, 0));
        infoRow.setPadding(HubanUi.dp(this, 12), HubanUi.dp(this, 10), HubanUi.dp(this, 12), HubanUi.dp(this, 10));

        View av = buildAvatarCircle(b.avatar, b.nick, 42);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(HubanUi.dp(this, 42), HubanUi.dp(this, 42));
        avLp.rightMargin = HubanUi.dp(this, 10);
        infoRow.addView(av, avLp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        FanBadgeView bv = new FanBadgeView(this, 22);
        bv.setBadge(b);
        col.addView(bv);

        TextView uidTv = new TextView(this);
        uidTv.setText("UID: " + b.pid + (b.roomId > 0 ? (" · 房间 " + b.roomId) : ""));
        uidTv.setTextColor(p.secondaryText);
        uidTv.setTextSize(12f);
        LinearLayout.LayoutParams uLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        uLp.topMargin = HubanUi.dp(this, 4);
        col.addView(uidTv, uLp);
        infoRow.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyBtn = HubanUi.createSecondaryButton(this, p, "复制 UID");
        copyBtn.setTextSize(12f);
        copyBtn.setPadding(HubanUi.dp(this, 10), HubanUi.dp(this, 6), HubanUi.dp(this, 10), HubanUi.dp(this, 6));
        copyBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("HuyaUID", String.valueOf(b.pid)));
                Toast.makeText(this, "已复制 UID: " + b.pid, Toast.LENGTH_SHORT).show();
            }
        });
        infoRow.addView(copyBtn);
        body.addView(infoRow);

        // Gift type toggle: 免费虎粮 vs 超粉虎粮
        final boolean[] isSuper = new boolean[]{false};
        final int freeStock = Math.max(0, acc.state.freeHuliangCount);
        final int superStock = Math.max(0, acc.state.superHuliangCount);
        final String superName = acc.state.superHuliangName.isEmpty() ? "超粉虎粮" : acc.state.superHuliangName;

        LinearLayout typeRow = new LinearLayout(this);
        typeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.topMargin = HubanUi.dp(this, 14);

        TextView normalTab = new TextView(this);
        normalTab.setText("免费虎粮 (" + freeStock + ")");
        normalTab.setGravity(Gravity.CENTER);
        normalTab.setTextSize(13f);
        normalTab.setPadding(0, HubanUi.dp(this, 9), 0, HubanUi.dp(this, 9));

        TextView superTab = new TextView(this);
        superTab.setText(superName + " (" + superStock + ")");
        superTab.setGravity(Gravity.CENTER);
        superTab.setTextSize(13f);
        superTab.setPadding(0, HubanUi.dp(this, 9), 0, HubanUi.dp(this, 9));

        typeRow.addView(normalTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View tGap = new View(this);
        typeRow.addView(tGap, new LinearLayout.LayoutParams(HubanUi.dp(this, 8), 1));
        typeRow.addView(superTab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(typeRow, trLp);

        Runnable syncTabs = () -> {
            normalTab.setTypeface(!isSuper[0] ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            normalTab.setTextColor(!isSuper[0] ? p.brandText : p.secondaryText);
            normalTab.setBackground(HubanUi.roundedRect(this,
                    !isSuper[0] ? p.brandTintBg : p.subSurface, 12,
                    !isSuper[0] ? 1.2f : 1f, !isSuper[0] ? p.brandStart : p.cardBorder));

            superTab.setTypeface(isSuper[0] ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            superTab.setTextColor(isSuper[0] ? p.brandText : p.secondaryText);
            superTab.setBackground(HubanUi.roundedRect(this,
                    isSuper[0] ? p.brandTintBg : p.subSurface, 12,
                    isSuper[0] ? 1.2f : 1f, isSuper[0] ? p.brandStart : p.cardBorder));
        };
        normalTab.setOnClickListener(v -> { isSuper[0] = false; syncTabs.run(); });
        superTab.setOnClickListener(v -> { isSuper[0] = true; syncTabs.run(); });
        syncTabs.run();

        // Count input + quick chips 1 / 10 / 全部
        EditText countEt = HubanUi.createStyledInput(this, p, "输入赠送数量", InputType.TYPE_CLASS_NUMBER);
        countEt.setText("1");
        LinearLayout.LayoutParams ceLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ceLp.topMargin = HubanUi.dp(this, 12);
        body.addView(countEt, ceLp);

        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qrLp.topMargin = HubanUi.dp(this, 10);

        String[] qLabels = {"送 1 个", "送 10 个", "全部送出"};
        for (int i = 0; i < qLabels.length; i++) {
            final int qIdx = i;
            TextView qBtn = HubanUi.createSecondaryButton(this, p, qLabels[i]);
            qBtn.setTextSize(12.5f);
            qBtn.setOnClickListener(v -> {
                int stock = isSuper[0] ? superStock : freeStock;
                int val = (qIdx == 0) ? 1 : (qIdx == 1 ? 10 : Math.max(1, stock));
                countEt.setText(String.valueOf(val));
                countEt.setSelection(countEt.getText().length());
            });
            LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) qLp.leftMargin = HubanUi.dp(this, 8);
            quickRow.addView(qBtn, qLp);
        }
        body.addView(quickRow, qrLp);

        TextView safetyTv = new TextView(this);
        safetyTv.setText("安全保证：仅消耗免费包裹虎粮（iPayPloy=2, lPayCount=0），绝不消耗金豆或虎牙币。");
        safetyTv.setTextColor(p.tertiaryText);
        safetyTv.setTextSize(11.5f);
        LinearLayout.LayoutParams sfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sfLp.topMargin = HubanUi.dp(this, 10);
        body.addView(safetyTv, sfLp);

        final HubanUi.DialogHandle[] holder = new HubanUi.DialogHandle[1];
        TextView sendBtn = HubanUi.createPrimaryButton(this, p, "立即赠送免费虎粮");
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, HubanUi.dp(this, 46));
        sbLp.topMargin = HubanUi.dp(this, 14);
        body.addView(sendBtn, sbLp);

        sendBtn.setOnClickListener(v -> {
            int cnt;
            try {
                cnt = Integer.parseInt(countEt.getText().toString().trim());
            } catch (NumberFormatException e) {
                Toast.makeText(this, "请输入有效数量", Toast.LENGTH_SHORT).show();
                return;
            }
            if (cnt <= 0) {
                Toast.makeText(this, "数量必须大于 0", Toast.LENGTH_SHORT).show();
                return;
            }
            sendBtn.setEnabled(false);
            sendBtn.setText("正在赠送...");
            executor.execute(() -> {
                try {
                    Models.AccountActionResult res = api.manualSend(acc.id, b.pid, isSuper[0], cnt);
                    Models.StatusResponse st = api.getStatus();
                    mainHandler.post(() -> {
                        if (holder[0] != null) holder[0].dismiss();
                        applyStatus(st);
                        Toast.makeText(this, res.message.isEmpty() ? "赠送成功" : res.message, Toast.LENGTH_LONG).show();
                    });
                } catch (Throwable t) {
                    mainHandler.post(() -> {
                        sendBtn.setEnabled(true);
                        sendBtn.setText("立即赠送免费虎粮");
                        Toast.makeText(this, "赠送失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });

        holder[0] = HubanUi.showCustomSheet(
                this, p,
                b.nick.isEmpty() ? ("主播 " + b.pid) : b.nick,
                "手动送礼 · 升级进度 " + b.score + "/" + b.nextScore + " · 今日亲密度 " + b.dayScore + "/" + b.quota,
                body,
                false,
                null
        );
    }

    // ── TAB 2: Accounts ──────────────────────────────────────────────────────

    private LinearLayout buildAccountsTab() {
        LinearLayout page = createPageContainer();

        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = HubanUi.dp(this, 12);

        TextView title = new TextView(this);
        title.setText("多账号管理");
        title.setTextSize(17.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(p.primaryText);
        hdr.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView addBtn = HubanUi.createPrimaryButton(this, p, "+ 添加账号");
        addBtn.setTextSize(13f);
        addBtn.setPadding(HubanUi.dp(this, 14), HubanUi.dp(this, 8), HubanUi.dp(this, 14), HubanUi.dp(this, 8));
        addBtn.setOnClickListener(v -> openAddAccountModal());
        hdr.addView(addBtn);
        page.addView(hdr, hLp);

        accountsListContainer = new LinearLayout(this);
        accountsListContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(accountsListContainer);

        return page;
    }

    private void renderAccountsList() {
        if (accountsListContainer == null) return;
        accountsListContainer.removeAllViews();

        if (latestStatus.accounts.isEmpty()) {
            addEmptyHintCard(accountsListContainer, "还没有添加任何虎牙账号，点击右上角「+ 添加账号」开始使用。");
            return;
        }

        for (Models.Account acc : latestStatus.accounts) {
            boolean isActive = acc.id.equals(activeAccountId);
            LinearLayout card = HubanUi.createCard(this, p);
            card.setClickable(true);
            card.setFocusable(true);
            card.setBackground(HubanUi.rippleRect(this, p, p.card, 18,
                    isActive ? 1.5f : 1f,
                    isActive ? p.brandStart : p.cardBorder));
            HubanUi.attachPressBounce(card, 0.985f);
            card.setOnClickListener(v -> {
                if (!acc.id.equals(activeAccountId)) {
                    setActiveAccountId(acc.id);
                    updateTopBarAccountPill();
                    renderAllTabs();
                }
            });

            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            View av = buildAvatarCircle(acc.avatar, acc.displayName(), 44);
            LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(HubanUi.dp(this, 44), HubanUi.dp(this, 44));
            avLp.rightMargin = HubanUi.dp(this, 12);
            topRow.addView(av, avLp);

            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);

            TextView nameTv = new TextView(this);
            nameTv.setText(acc.displayName());
            nameTv.setTextColor(p.primaryText);
            nameTv.setTextSize(15.5f);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            nameTv.setSingleLine(true);
            nameTv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nLp.rightMargin = HubanUi.dp(this, 8);
            titleRow.addView(nameTv, nLp);

            TextView authPill = HubanUi.createPillBadge(this,
                    acc.isMobileAuth() ? "长期自动续期" : "网页登录",
                    acc.isMobileAuth() ? p.okText : p.warnText,
                    acc.isMobileAuth() ? p.okBg : p.warnBg);
            titleRow.addView(authPill);
            infoCol.addView(titleRow);

            TextView metaTv = new TextView(this);
            String freeStr = acc.state.freeHuliangCount >= 0 ? ("免费虎粮 " + acc.state.freeHuliangCount) : "虎粮未同步";
            metaTv.setText("UID " + acc.uid + " · " + acc.state.badges.size() + " 个粉丝牌 · " + freeStr);
            metaTv.setTextColor(p.secondaryText);
            metaTv.setTextSize(12.2f);
            LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mLp.topMargin = HubanUi.dp(this, 4);
            infoCol.addView(metaTv, mLp);

            topRow.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(topRow);

            // Footer row: selected badge / hint on left, delete action on right
            LinearLayout actRow = new LinearLayout(this);
            actRow.setOrientation(LinearLayout.HORIZONTAL);
            actRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            arLp.topMargin = HubanUi.dp(this, 12);

            if (isActive) {
                TextView activePill = HubanUi.createPillBadge(this, "✓ 当前选中", p.brandText, p.brandTintBg);
                actRow.addView(activePill);
            }

            actRow.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

            TextView delBtn = HubanUi.createPillBadge(this, "删除账号", p.errText, p.errBg);
            delBtn.setTextSize(12f);
            delBtn.setPadding(HubanUi.dp(this, 12), HubanUi.dp(this, 6), HubanUi.dp(this, 12), HubanUi.dp(this, 6));
            delBtn.setClickable(true);
            delBtn.setOnClickListener(v -> HubanUi.showMessageModal(
                    this, p,
                    "确认删除账号？",
                    "删除「" + acc.displayName() + "」（UID " + acc.uid + "）后，将清除该账号的本地登录凭证和定时配置。",
                    "确认删除",
                    () -> deleteAccount(acc.id),
                    "取消",
                    null
            ));
            actRow.addView(delBtn);

            card.addView(actRow, arLp);
            accountsListContainer.addView(card);
        }
    }

    private void openAddAccountModal() {
        AddAccountModal.show(this, p, api, executor, (account, message) -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            if (account != null && !account.id.isEmpty()) {
                setActiveAccountId(account.id);
            }
            loadStatus(false);
            // Re-poll after 3.2s to pick up initial background badge/bag sync
            mainHandler.postDelayed(() -> loadStatus(false), 3200L);
        });
    }

    private void deleteAccount(String id) {
        executor.execute(() -> {
            try {
                api.deleteAccount(id);
                Models.StatusResponse st = api.getStatus();
                ScheduleHelper.syncFromAccounts(this, st.accounts);
                mainHandler.post(() -> {
                    applyStatus(st);
                    Toast.makeText(this, "账号已删除", Toast.LENGTH_SHORT).show();
                });
            } catch (Throwable t) {
                mainHandler.post(() ->
                        Toast.makeText(this, "删除失败：" + t.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ── TAB 3: Settings ──────────────────────────────────────────────────────

    private LinearLayout buildSettingsTab() {
        LinearLayout page = createPageContainer();

        // 1. Local Notifications & Background Battery Whitelist Card
        page.addView(HubanUi.createSectionLabel(this, p, "系统通知与后台保活"));
        LinearLayout permCard = HubanUi.createCard(this, p);
        permCard.setPadding(HubanUi.dp(this, 16), HubanUi.dp(this, 6), HubanUi.dp(this, 16), HubanUi.dp(this, 6));

        notifPermStatusTv = new TextView(this);
        notifPermBtn = HubanUi.createSecondaryButton(this, p, "申请权限");
        notifPermBtn.setOnClickListener(v -> requestOrOpenNotificationSettings());
        permCard.addView(buildPermissionRow("本地任务通知", notifPermStatusTv, notifPermBtn));
        permCard.addView(HubanUi.createDivider(this, p));

        batteryStatusTv = new TextView(this);
        batteryOptBtn = HubanUi.createSecondaryButton(this, p, "加入白名单");
        batteryOptBtn.setOnClickListener(v -> requestIgnoreBatteryOptimization());
        permCard.addView(buildPermissionRow("电池优化白名单", batteryStatusTv, batteryOptBtn));
        permCard.addView(HubanUi.createDivider(this, p));

        exactAlarmStatusTv = new TextView(this);
        exactAlarmBtn = HubanUi.createSecondaryButton(this, p, "去开启");
        exactAlarmBtn.setOnClickListener(v -> openExactAlarmSettings());
        permCard.addView(buildPermissionRow("精确闹钟调度", exactAlarmStatusTv, exactAlarmBtn));
        page.addView(permCard);

        // 2. External Push Channels Card
        page.addView(HubanUi.createSectionLabel(this, p, "外部推送通道（可选）"));
        LinearLayout notifyCard = HubanUi.createCard(this, p);

        etBarkUrl = addLabeledInput(notifyCard, "Bark 推送地址", "例如 https://api.day.app/YOUR_KEY", false);
        etTgToken = addLabeledInput(notifyCard, "Telegram Bot Token", "例如 123456:ABC-DEF...", true);
        etTgChatId = addLabeledInput(notifyCard, "Telegram Chat ID", "例如 123456789", true);
        etServerChan = addLabeledInput(notifyCard, "Server酱 SendKey", "例如 SCT...", true);
        etDingTalk = addLabeledInput(notifyCard, "钉钉群机器人 Webhook", "https://oapi.dingtalk.com/robot/send?access_token=...", true);
        etFeishu = addLabeledInput(notifyCard, "飞书群机器人 Webhook", "https://open.feishu.cn/open-apis/bot/v2/hook/...", true);
        etCustomWebhook = addLabeledInput(notifyCard, "自定义 Webhook URL", "POST JSON {title, body}", true);

        LinearLayout notifyBtns = new LinearLayout(this);
        notifyBtns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams nbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nbLp.topMargin = HubanUi.dp(this, 14);

        TextView saveNotifyBtn = HubanUi.createPrimaryButton(this, p, "保存推送配置");
        saveNotifyBtn.setOnClickListener(v -> saveNotifyConfig());
        notifyBtns.addView(saveNotifyBtn, new LinearLayout.LayoutParams(0, HubanUi.dp(this, 44), 1f));

        notifyBtns.addView(new View(this), new LinearLayout.LayoutParams(HubanUi.dp(this, 10), 1));

        TextView testNotifyBtn = HubanUi.createSecondaryButton(this, p, "发送测试推送");
        testNotifyBtn.setOnClickListener(v -> sendTestNotify());
        notifyBtns.addView(testNotifyBtn, new LinearLayout.LayoutParams(0, HubanUi.dp(this, 44), 1f));

        notifyCard.addView(notifyBtns, nbLp);
        page.addView(notifyCard);

        // 3. About 虎伴 Card
        page.addView(HubanUi.createSectionLabel(this, p, "关于虎伴"));
        LinearLayout aboutCard = HubanUi.createCard(this, p);

        LinearLayout aboutHdr = new LinearLayout(this);
        aboutHdr.setOrientation(LinearLayout.HORIZONTAL);
        aboutHdr.setGravity(Gravity.CENTER_VERTICAL);

        ImageView iconIv = new ImageView(this);
        iconIv.setImageResource(R.mipmap.ic_launcher_round);
        aboutHeroIconView = iconIv;
        iconIv.setClickable(true);
        iconIv.setOnClickListener(v -> playHeroIconBounce());
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(HubanUi.dp(this, 42), HubanUi.dp(this, 42));
        iLp.rightMargin = HubanUi.dp(this, 12);
        aboutHdr.addView(iconIv, iLp);

        LinearLayout aCol = new LinearLayout(this);
        aCol.setOrientation(LinearLayout.VERTICAL);
        TextView aTitle = new TextView(this);
        aTitle.setText("虎伴  v" + AppUpdater.getCurrentVersionName(this));
        aTitle.setTextColor(p.primaryText);
        aTitle.setTextSize(15f);
        aTitle.setTypeface(Typeface.DEFAULT_BOLD);
        aCol.addView(aTitle);

        engineStatusTv = new TextView(this);
        engineStatusTv.setTextColor(p.secondaryText);
        engineStatusTv.setTextSize(12f);
        LinearLayout.LayoutParams esLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        esLp.topMargin = HubanUi.dp(this, 2);
        aCol.addView(engineStatusTv, esLp);
        aboutHdr.addView(aCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        checkUpdateBtn = new TextView(this);
        checkUpdateBtn.setTextSize(12.5f);
        checkUpdateBtn.setTypeface(Typeface.DEFAULT_BOLD);
        checkUpdateBtn.setGravity(Gravity.CENTER);
        checkUpdateBtn.setSingleLine(true);
        checkUpdateBtn.setPadding(HubanUi.dp(this, 13), HubanUi.dp(this, 7), HubanUi.dp(this, 13), HubanUi.dp(this, 7));
        checkUpdateBtn.setClickable(true);
        checkUpdateBtn.setFocusable(true);
        HubanUi.attachPressBounce(checkUpdateBtn, 0.95f);
        checkUpdateBtn.setOnClickListener(v -> {
            if (latestReleaseInfo != null && latestReleaseInfo.hasUpdate) {
                showUpdateDialog(latestReleaseInfo);
            } else {
                triggerUpdateCheck(true);
            }
        });
        updateCheckUi(latestReleaseInfo != null && latestReleaseInfo.hasUpdate);
        LinearLayout.LayoutParams cuLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cuLp.leftMargin = HubanUi.dp(this, 8);
        aboutHdr.addView(checkUpdateBtn, cuLp);

        aboutCard.addView(aboutHdr);

        TextView safeDecl = new TextView(this);
        safeDecl.setText("严格免费包裹送礼声明：所有自动与手动送礼均硬编码锁定包裹免费策略，绝对不会消耗任何金豆或虎牙币。");
        safeDecl.setTextColor(p.secondaryText);
        safeDecl.setTextSize(12.5f);
        safeDecl.setLineSpacing(0f, 1.4f);
        safeDecl.setBackground(HubanUi.roundedRect(this, p.subSurface, 12, 0, 0));
        safeDecl.setPadding(HubanUi.dp(this, 12), HubanUi.dp(this, 10), HubanUi.dp(this, 12), HubanUi.dp(this, 10));
        LinearLayout.LayoutParams sdLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sdLp.topMargin = HubanUi.dp(this, 12);
        aboutCard.addView(safeDecl, sdLp);

        TextView ghBtn = HubanUi.createSecondaryButton(this, p, "打开 GitHub 仓库 · " + AppUpdater.GITHUB_REPO);
        ghBtn.setOnClickListener(v -> openUrlInBrowser(GITHUB_URL));
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, HubanUi.dp(this, 42));
        gLp.topMargin = HubanUi.dp(this, 12);
        aboutCard.addView(ghBtn, gLp);

        page.addView(aboutCard);

        refreshSettingsPermissionStates();
        updateEngineStatusLabel();
        return page;
    }

    private LinearLayout buildPermissionRow(String title, TextView statusTv, TextView actionBtn) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, HubanUi.dp(this, 11), 0, HubanUi.dp(this, 11));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(p.primaryText);
        t.setTextSize(14.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);

        statusTv.setTextSize(12f);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.topMargin = HubanUi.dp(this, 2);
        col.addView(statusTv, sLp);

        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actionBtn.setTextSize(12f);
        actionBtn.setPadding(HubanUi.dp(this, 12), HubanUi.dp(this, 7), HubanUi.dp(this, 12), HubanUi.dp(this, 7));
        row.addView(actionBtn);
        return row;
    }

    private EditText addLabeledInput(LinearLayout parent, String label, String hint, boolean topGap) {
        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextColor(p.secondaryText);
        lb.setTextSize(12.5f);
        lb.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topGap) lLp.topMargin = HubanUi.dp(this, 10);
        parent.addView(lb, lLp);

        EditText et = HubanUi.createStyledInput(this, p, hint, InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eLp.topMargin = HubanUi.dp(this, 5);
        parent.addView(et, eLp);
        return et;
    }

    private void populateNotifyFields(Models.NotifyConfig n) {
        if (etBarkUrl != null) etBarkUrl.setText(n.barkUrl);
        if (etTgToken != null) etTgToken.setText(n.telegramBotToken);
        if (etTgChatId != null) etTgChatId.setText(n.telegramChatId);
        if (etServerChan != null) etServerChan.setText(n.serverChanKey);
        if (etDingTalk != null) etDingTalk.setText(n.dingTalkWebhook);
        if (etFeishu != null) etFeishu.setText(n.feishuWebhook);
        if (etCustomWebhook != null) etCustomWebhook.setText(n.customWebhookUrl);
    }

    private void saveNotifyConfig() {
        Models.NotifyConfig cfg = new Models.NotifyConfig();
        cfg.barkUrl = etBarkUrl.getText().toString().trim();
        cfg.telegramBotToken = etTgToken.getText().toString().trim();
        cfg.telegramChatId = etTgChatId.getText().toString().trim();
        cfg.serverChanKey = etServerChan.getText().toString().trim();
        cfg.dingTalkWebhook = etDingTalk.getText().toString().trim();
        cfg.feishuWebhook = etFeishu.getText().toString().trim();
        cfg.customWebhookUrl = etCustomWebhook.getText().toString().trim();

        executor.execute(() -> {
            try {
                api.saveNotify(cfg);
                mainHandler.post(() -> Toast.makeText(this, "外部推送配置已保存", Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                mainHandler.post(() -> Toast.makeText(this, "保存失败：" + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void sendTestNotify() {
        executor.execute(() -> {
            try {
                String msg = api.testNotify();
                mainHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                mainHandler.post(() -> Toast.makeText(this, "测试推送失败：" + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void refreshSettingsPermissionStates() {
        if (notifPermStatusTv != null) {
            boolean ok = LocalNotifyHelper.hasNotificationPermission(this);
            notifPermStatusTv.setText(ok ? "已授权 · 定时任务结果将直接弹出系统通知" : "未授权 · 建议开启以接收每日执行结果与超粉日提醒");
            notifPermStatusTv.setTextColor(ok ? p.okText : p.warnText);
            notifPermBtn.setText(ok ? "通知设置" : "立即开启");
        }
        if (batteryStatusTv != null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
            batteryStatusTv.setText(ignored ? "已加入白名单 · 保障后台定时任务不被系统查杀" : "未忽略优化 · 建议加入白名单确保定时准时执行");
            batteryStatusTv.setTextColor(ignored ? p.okText : p.warnText);
            batteryOptBtn.setText(ignored ? "已放行" : "加入白名单");
        }
        if (exactAlarmStatusTv != null) {
            boolean exact = ScheduleHelper.canScheduleExactAlarms(this);
            exactAlarmStatusTv.setText(exact ? "已允许精确闹钟调度" : "未授权精确闹钟，系统可能延迟几分钟唤醒");
            exactAlarmStatusTv.setTextColor(exact ? p.okText : p.warnText);
            exactAlarmBtn.setText(exact ? "已开启" : "去授权");
        }
    }

    private void updateEngineStatusLabel() {
        if (engineStatusTv == null) return;
        HubanCoreManager core = HubanCoreManager.getInstance();
        if (core.isRunning()) {
            engineStatusTv.setText("内置引擎运行正常 · 127.0.0.1:" + core.getPort());
            engineStatusTv.setTextColor(p.okText);
        } else {
            String err = core.getLastError();
            engineStatusTv.setText(err.isEmpty() ? "核心引擎未运行" : ("引擎异常：" + err));
            engineStatusTv.setTextColor(p.errText);
        }
    }

    private void requestOrOpenNotificationSettings() {
        if (Build.VERSION.SDK_INT >= 33 && !LocalNotifyHelper.hasNotificationPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIF);
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    @SuppressLint("BatteryLife")
    private void requestIgnoreBatteryOptimization() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignored) {
            }
        }
    }

    private void openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable ignored) {
            }
        } else {
            Toast.makeText(this, "当前 Android 版本默认支持精确闹钟", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIF) {
            refreshSettingsPermissionStates();
        }
    }

    // ── Shared UI & Formatting Helpers ───────────────────────────────────────

    private LinearLayout createPageContainer() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(HubanUi.dp(this, 16), HubanUi.dp(this, 8), HubanUi.dp(this, 16), HubanUi.dp(this, 20));
        return page;
    }

    private ScrollView wrapScroll(View child) {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(true);
        sv.addView(child, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private void addEmptyHintCard(LinearLayout parent, String text) {
        LinearLayout card = HubanUi.createCard(this, p);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(p.secondaryText);
        tv.setTextSize(13.5f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(HubanUi.dp(this, 12), HubanUi.dp(this, 24), HubanUi.dp(this, 12), HubanUi.dp(this, 24));
        card.addView(tv);
        parent.addView(card);
    }

    private View buildAvatarCircle(String avatarUrl, String name, int sizeDp) {
        FrameLayout box = new FrameLayout(this);
        box.setBackground(HubanUi.roundedRect(this, p.brandTintBg, 99, 0, 0));

        TextView initial = new TextView(this);
        String ch = (name != null && !name.trim().isEmpty()) ? name.trim().substring(0, 1) : "虎";
        initial.setText(ch);
        initial.setTextColor(p.brandText);
        initial.setTextSize(sizeDp >= 42 ? 16f : 14f);
        initial.setTypeface(Typeface.DEFAULT_BOLD);
        initial.setGravity(Gravity.CENTER);
        box.addView(initial, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (avatarUrl != null && !avatarUrl.trim().isEmpty()) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            final String tagUrl = avatarUrl;
            iv.setTag(tagUrl);
            BadgeImageLoader.getInstance().loadCircleAvatar(this, tagUrl, bmp -> {
                if (bmp != null && tagUrl.equals(iv.getTag())) {
                    iv.setImageBitmap(bmp);
                    initial.setVisibility(View.GONE);
                }
            });
        }
        return box;
    }

    private static String buildSuperFanDayText(Models.FanBadge b) {
        if (b.sfDayActive) {
            return "超粉日 " + (b.sfDayScoreTimes > 0 ? b.sfDayScoreTimes : 4) + "倍";
        }
        if (b.nextSfDayEpoch > 0) {
            long diffMs = b.nextSfDayEpoch * 1000L - System.currentTimeMillis();
            long days = Math.max(1L, (long) Math.ceil(diffMs / 86400000.0));
            return days + "天后超粉日";
        }
        if (b.sfDay > 0) {
            return "每月" + b.sfDay + "日超粉日";
        }
        return "";
    }

    private static String formatTargetsSummary(List<Models.SendTarget> targets) {
        if (targets == null || targets.isEmpty()) return "未选择";
        if (targets.size() == 1) {
            Models.SendTarget t = targets.get(0);
            return t.name.isEmpty() ? String.valueOf(t.pid) : t.name;
        }
        return "已选 " + targets.size() + " 位主播";
    }

    private static String formatDateTime(long epochMs) {
        if (epochMs <= 0) return "--";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(epochMs));
    }

    private static String formatTriggerLabel(String trigger) {
        if ("schedule".equals(trigger)) return "定时";
        if ("rescue".equals(trigger)) return "防过期";
        if ("retry".equals(trigger)) return "重试";
        return "手动";
    }

    private interface OnToggle {
        void onChange(boolean checked);
    }

    /**
     * Module-app-matching vector Canvas icons for the 4 bottom tabs:
     * hollow outline when unselected, solid filled when selected.
     */
    private static final class TabIconDrawable extends Drawable {
        private final int tab;
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cutoutPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rf = new RectF();
        private final Path path = new Path();
        private boolean filled = false;

        TabIconDrawable(Context ctx, int tab, int color, int cutoutColor) {
            this.tab = tab;
            float stroke = ctx.getResources().getDisplayMetrics().density * 1.85f;
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(stroke);
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            strokePaint.setStrokeJoin(Paint.Join.ROUND);
            strokePaint.setColor(color);

            fillPaint.setStyle(Paint.Style.FILL);
            fillPaint.setColor(color);

            cutoutPaint.setStyle(Paint.Style.FILL);
            cutoutPaint.setColor(cutoutColor);
        }

        void setFilled(boolean filled) {
            if (this.filled != filled) {
                this.filled = filled;
                invalidateSelf();
            }
        }

        void setTintColor(int color) {
            if (strokePaint.getColor() != color) {
                strokePaint.setColor(color);
                fillPaint.setColor(color);
                invalidateSelf();
            }
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0f) return;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();

            if (tab == TAB_TASKS) {
                // Lightning bolt (exact module-app silhouette)
                path.reset();
                path.moveTo(cx + s * 0.06f, cy - s * 0.42f);
                path.lineTo(cx - s * 0.28f, cy + s * 0.04f);
                path.lineTo(cx + s * 0.02f, cy + s * 0.04f);
                path.lineTo(cx - s * 0.06f, cy + s * 0.42f);
                path.lineTo(cx + s * 0.28f, cy - s * 0.04f);
                path.lineTo(cx - s * 0.02f, cy - s * 0.04f);
                path.close();
                if (filled) {
                    c.drawPath(path, fillPaint);
                    c.drawPath(path, strokePaint);
                } else {
                    c.drawPath(path, strokePaint);
                }
            } else if (tab == TAB_BADGES) {
                // Heart / fan badge icon (exact module-app silhouette)
                path.reset();
                path.moveTo(cx, cy + s * 0.36f);
                path.cubicTo(cx - s * 0.46f, cy + s * 0.08f, cx - s * 0.44f, cy - s * 0.36f, cx - s * 0.16f, cy - s * 0.36f);
                path.cubicTo(cx - s * 0.05f, cy - s * 0.36f, cx, cy - s * 0.24f, cx, cy - s * 0.20f);
                path.cubicTo(cx, cy - s * 0.24f, cx + s * 0.05f, cy - s * 0.36f, cx + s * 0.16f, cy - s * 0.36f);
                path.cubicTo(cx + s * 0.44f, cy - s * 0.36f, cx + s * 0.46f, cy + s * 0.08f, cx, cy + s * 0.36f);
                path.close();
                if (filled) {
                    c.drawPath(path, fillPaint);
                    c.drawPath(path, strokePaint);
                } else {
                    c.drawPath(path, strokePaint);
                }
            } else if (tab == TAB_ACCOUNTS) {
                // User head + shoulders (hollow -> solid filled)
                if (filled) {
                    c.drawCircle(cx, cy - s * 0.16f, s * 0.18f, fillPaint);
                    rf.set(cx - s * 0.32f, cy + s * 0.08f, cx + s * 0.32f, cy + s * 0.56f);
                    c.drawArc(rf, 180f, 180f, true, fillPaint);
                } else {
                    c.drawCircle(cx, cy - s * 0.16f, s * 0.17f, strokePaint);
                    rf.set(cx - s * 0.30f, cy + s * 0.10f, cx + s * 0.30f, cy + s * 0.52f);
                    c.drawArc(rf, 190f, 160f, false, strokePaint);
                }
            } else {
                // Sliders / Settings icon (hollow rings -> solid knobs)
                c.drawLine(cx - s * 0.32f, cy - s * 0.18f, cx + s * 0.32f, cy - s * 0.18f, strokePaint);
                c.drawLine(cx - s * 0.32f, cy + s * 0.18f, cx + s * 0.32f, cy + s * 0.18f, strokePaint);
                if (filled) {
                    c.drawCircle(cx - s * 0.12f, cy - s * 0.18f, s * 0.12f, fillPaint);
                    c.drawCircle(cx + s * 0.14f, cy + s * 0.18f, s * 0.12f, fillPaint);
                } else {
                    c.drawCircle(cx - s * 0.12f, cy - s * 0.18f, s * 0.10f, cutoutPaint);
                    c.drawCircle(cx - s * 0.12f, cy - s * 0.18f, s * 0.10f, strokePaint);
                    c.drawCircle(cx + s * 0.14f, cy + s * 0.18f, s * 0.10f, cutoutPaint);
                    c.drawCircle(cx + s * 0.14f, cy + s * 0.18f, s * 0.10f, strokePaint);
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
            strokePaint.setAlpha(alpha);
            fillPaint.setAlpha(alpha);
            cutoutPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            strokePaint.setColorFilter(cf);
            fillPaint.setColorFilter(cf);
            cutoutPaint.setColorFilter(cf);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ── Auto-Update Banner, Check, Download & Install ────────────────────────

    private View buildUpdateBanner() {
        updateBannerCard = new LinearLayout(this);
        updateBannerCard.setOrientation(LinearLayout.HORIZONTAL);
        updateBannerCard.setGravity(Gravity.CENTER_VERTICAL);
        updateBannerCard.setBackground(HubanUi.roundedRect(this, p.brandTintBg, 14, 1, p.brandStart));
        updateBannerCard.setPadding(HubanUi.dp(this, 14), HubanUi.dp(this, 10), HubanUi.dp(this, 12), HubanUi.dp(this, 10));
        LinearLayout.LayoutParams ubLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ubLp.leftMargin = HubanUi.dp(this, 14);
        ubLp.rightMargin = HubanUi.dp(this, 14);
        ubLp.bottomMargin = HubanUi.dp(this, 8);
        updateBannerCard.setLayoutParams(ubLp);
        updateBannerCard.setVisibility(View.GONE);

        LinearLayout ubTextCol = new LinearLayout(this);
        ubTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ubTextLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ubTextLp.rightMargin = HubanUi.dp(this, 10);
        ubTextCol.setLayoutParams(ubTextLp);

        updateBannerTitleView = new TextView(this);
        updateBannerTitleView.setTextSize(13.5f);
        updateBannerTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        updateBannerTitleView.setTextColor(p.brandText);
        ubTextCol.addView(updateBannerTitleView);

        updateBannerDescView = new TextView(this);
        updateBannerDescView.setTextSize(12f);
        updateBannerDescView.setTextColor(p.secondaryText);
        updateBannerDescView.setMaxLines(1);
        updateBannerDescView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ubDescLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ubDescLp.topMargin = HubanUi.dp(this, 2);
        updateBannerDescView.setLayoutParams(ubDescLp);
        ubTextCol.addView(updateBannerDescView);

        TextView ubAction = HubanUi.createPrimaryButton(this, p, "更新");
        ubAction.setTextSize(12.5f);
        ubAction.setPadding(HubanUi.dp(this, 14), HubanUi.dp(this, 6), HubanUi.dp(this, 14), HubanUi.dp(this, 6));
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

    private void scheduleSilentUpdateCheck() {
        long lastCheck = uiPrefs.getLong(KEY_LAST_UPDATE_CHECK, 0L);
        if (System.currentTimeMillis() - lastCheck < AUTO_UPDATE_INTERVAL_MS) {
            return;
        }
        mainHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                triggerUpdateCheck(false);
            }
        }, 1500L);
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
                uiPrefs.edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
                latestReleaseInfo = info;
                updateCheckUi(info.hasUpdate);
                updateBannerVisibility(info);

                if (info.hasUpdate) {
                    if (manual) {
                        showUpdateDialog(info);
                    }
                } else if (manual) {
                    Toast.makeText(
                            MainActivity.this,
                            "当前已是最新版本（v" + AppUpdater.getCurrentVersionName(MainActivity.this) + "）",
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
                updateCheckUi(latestReleaseInfo != null && latestReleaseInfo.hasUpdate);
                if (manual) {
                    HubanUi.showMessageModal(
                            MainActivity.this, p,
                            "检查更新失败",
                            errorMessage + "\n\n也可以直接前往 GitHub Releases 页面查看。",
                            "打开网页", () -> openUrlInBrowser(AppUpdater.RELEASES_PAGE_URL),
                            "关闭", null
                    );
                }
            }
        });
    }

    private void updateCheckUi(boolean hasUpdate) {
        if (settingsTabBadgeDot != null) {
            settingsTabBadgeDot.setVisibility(hasUpdate ? View.VISIBLE : View.GONE);
        }
        if (checkUpdateBtn == null) {
            return;
        }
        checkUpdateBtn.setEnabled(true);
        if (hasUpdate) {
            checkUpdateBtn.setText("立即更新");
            checkUpdateBtn.setTextColor(Color.WHITE);
            checkUpdateBtn.setBackground(HubanUi.primaryGradient(this, p, 99));
        } else {
            checkUpdateBtn.setText("检查更新");
            checkUpdateBtn.setTextColor(p.brandText);
            checkUpdateBtn.setBackground(HubanUi.rippleRect(this, p, p.brandTintBg, 99, 0, 0));
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
        msg.append("当前版本：v").append(AppUpdater.getCurrentVersionName(this))
                .append("  →  最新版本：v").append(info.versionName);
        if (info.apkSizeBytes > 0L) {
            msg.append("\n安装包大小：").append(AppUpdater.formatFileSize(info.apkSizeBytes));
        }
        if (info.releaseNotes != null && !info.releaseNotes.isEmpty()) {
            msg.append("\n\n更新内容：\n").append(info.releaseNotes);
        }

        boolean hasDirectApk = info.apkDownloadUrl != null && !info.apkDownloadUrl.isEmpty();
        HubanUi.showMessageModal(
                this, p,
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

        final HubanUi.ProgressDialogHandle progressHandle = HubanUi.showProgressModal(
                this, p,
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
                HubanUi.showMessageModal(
                        MainActivity.this, p,
                        "下载更新失败",
                        errorMessage + "\n\n是否改用浏览器下载？",
                        "浏览器下载", () -> openUrlInBrowser(
                                info.apkDownloadUrl != null && !info.apkDownloadUrl.isEmpty()
                                        ? info.apkDownloadUrl
                                        : info.htmlUrl
                        ),
                        "关闭", null
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
            HubanUi.showMessageModal(
                    this, p,
                    "需要允许安装应用",
                    "更新包已下载完成。请允许「虎伴」安装未知应用，返回后将自动继续安装。",
                    "前往授权", () -> {
                        try {
                            startActivity(AppUpdater.buildInstallPermissionIntent(this));
                        } catch (Throwable t) {
                            Toast.makeText(this, "无法打开安装权限设置页，请在系统设置中手动开启", Toast.LENGTH_LONG).show();
                        }
                    },
                    "取消", () -> pendingInstallApk = null
            );
            return;
        }
        boolean launched = AppUpdater.launchApkInstaller(this, apkFile);
        if (!launched) {
            Toast.makeText(this, "无法调起系统安装器，请尝试使用浏览器下载安装", Toast.LENGTH_LONG).show();
        }
    }

    private void openUrlInBrowser(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开浏览器", Toast.LENGTH_SHORT).show();
        }
    }
}
