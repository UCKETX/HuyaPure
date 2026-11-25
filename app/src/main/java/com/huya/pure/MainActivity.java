package com.huya.pure;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {

    private final Map<String, Switch> switchMap = new LinkedHashMap<>();
    private final Map<String, Boolean> defaultMap = new LinkedHashMap<>();
    private boolean updatingUi = false;

    private static boolean isModuleActive() {
        return false;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        boolean active = isModuleActive();
        ModulePrefs.AppPrefsHandle handle = ModulePrefs.openForApp(this);
        SharedPreferences prefs = handle.prefs;

        // Seed default values once so the world-readable prefs file exists immediately.
        if (handle.worldReadable && !prefs.contains(ModulePrefs.KEY_REWARD_AD_SKIP)) {
            ModulePrefs.resetToDefaults(prefs);
        }

        boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        int bgColor = isDark ? Color.parseColor("#121212") : Color.parseColor("#F5F6F8");
        int cardColor = isDark ? Color.parseColor("#1E1E1E") : Color.WHITE;
        int primaryText = isDark ? Color.parseColor("#F2F2F2") : Color.parseColor("#1A1A1A");
        int secondaryText = isDark ? Color.parseColor("#A8A8A8") : Color.parseColor("#666666");
        int dividerColor = isDark ? Color.parseColor("#2D2D2D") : Color.parseColor("#ECEEEF");
        int okColor = isDark ? Color.parseColor("#66BB6A") : Color.parseColor("#2E7D32");
        int errColor = isDark ? Color.parseColor("#EF5350") : Color.parseColor("#C62828");

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFitsSystemWindows(true);
        scrollView.setBackgroundColor(bgColor);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(28));

        // Header
        TextView title = new TextView(this);
        title.setText("HuyaPure");
        title.setTextSize(22f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(primaryText);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(getAppVersionName() + "  ·  虎牙纯净模块 (com.duowan.kiwi)");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(secondaryText);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subLp.topMargin = dp(4);
        subtitle.setLayoutParams(subLp);
        root.addView(subtitle);

        // Status Card
        LinearLayout statusCard = createCard(cardColor);
        LinearLayout.LayoutParams statusCardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusCardLp.topMargin = dp(16);
        statusCard.setLayoutParams(statusCardLp);
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView statusTitle = new TextView(this);
        statusTitle.setText(active ? "模块状态：已激活" : "模块状态：未激活（请在 LSPosed 中启用并勾选虎牙直播）");
        statusTitle.setTextSize(15f);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusTitle.setTextColor(active ? okColor : errColor);
        statusCard.addView(statusTitle);

        TextView syncStatus = new TextView(this);
        if (active && handle.worldReadable) {
            syncStatus.setText("跨进程配置同步：正常（修改开关后请强行停止并重启虎牙生效）");
        } else if (active) {
            syncStatus.setText("跨进程配置同步：受限（请确保 LSPosed 版本支持 XSharedPreferences）");
        } else {
            syncStatus.setText("配置将在模块激活并勾选虎牙直播作用域后生效");
        }
        syncStatus.setTextSize(12.5f);
        syncStatus.setTextColor(secondaryText);
        LinearLayout.LayoutParams syncLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        syncLp.topMargin = dp(6);
        syncStatus.setLayoutParams(syncLp);
        statusCard.addView(syncStatus);
        root.addView(statusCard);

        // Section title: Feature Toggles
        TextView sectionTitle = new TextView(this);
        sectionTitle.setText("功能开关");
        sectionTitle.setTextSize(14f);
        sectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        sectionTitle.setTextColor(secondaryText);
        LinearLayout.LayoutParams secLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        secLp.topMargin = dp(20);
        secLp.leftMargin = dp(4);
        secLp.bottomMargin = dp(8);
        sectionTitle.setLayoutParams(secLp);
        root.addView(sectionTitle);

        // Switches Card
        LinearLayout switchCard = createCard(cardColor);
        switchCard.setPadding(dp(16), dp(4), dp(16), dp(4));

        addSwitchRow(
                switchCard, prefs,
                ModulePrefs.KEY_REWARD_AD_SKIP,
                ModulePrefs.DEFAULT_REWARD_AD_SKIP,
                "激励视频跳广告领虎粮",
                "拦截穿山甲、优量汇与虎牙自建激励视频，跳过播放并自动完成奖励校验领取虎粮",
                primaryText, secondaryText, dividerColor, true
        );
        addSwitchRow(
                switchCard, prefs,
                ModulePrefs.KEY_SPLASH_AD_BLOCK,
                ModulePrefs.DEFAULT_SPLASH_AD_BLOCK,
                "屏蔽开屏广告",
                "拦截冷启动开屏、热启动开屏、延迟开屏及开屏联动横幅，启动直达首页",
                primaryText, secondaryText, dividerColor, true
        );
        addSwitchRow(
                switchCard, prefs,
                ModulePrefs.KEY_LIVE_ROOM_AD_BLOCK,
                ModulePrefs.DEFAULT_LIVE_ROOM_AD_BLOCK,
                "屏蔽直播间广告",
                "屏蔽角标/超级浮窗、横幅、商业小风车/插件、主播推广、看播赏金 RTB 与商业弹幕广告（不影响领虎粮）",
                primaryText, secondaryText, dividerColor, true
        );
        addSwitchRow(
                switchCard, prefs,
                ModulePrefs.KEY_BG_P2P_BLOCK,
                ModulePrefs.DEFAULT_BG_P2P_BLOCK,
                "关闭后台 P2P / MiniPCDN 上传",
                "关闭 MiniPCDN HTTP/3/WebRTC 上传服务、FileP2P 做种、HeatList 后台预下载、局域网 mDNS 与弹幕 P2P 中继",
                primaryText, secondaryText, dividerColor, true
        );
        addSwitchRow(
                switchCard, prefs,
                ModulePrefs.KEY_LIVE_P2P_BLOCK,
                ModulePrefs.DEFAULT_LIVE_P2P_BLOCK,
                "强制直播间走纯 HTTP-FLV CDN",
                "关闭直播间 P2P / XP2P 与 IPv6 节点打洞，强制拉流走纯 HTTP-FLV；如遇个别线路卡顿可单独关闭",
                primaryText, secondaryText, dividerColor, true
        );
        addSwitchRow(
                switchCard, prefs,
                ModulePrefs.KEY_DEBUG_LOG,
                ModulePrefs.DEFAULT_DEBUG_LOG,
                "详细调试日志",
                "在 LSPosed 日志中输出详细拦截与发奖明细，便于排查问题（平时建议关闭）",
                primaryText, secondaryText, dividerColor, false
        );

        root.addView(switchCard);

        // Reset button
        Button resetBtn = new Button(this);
        resetBtn.setText("恢复默认开关");
        resetBtn.setAllCaps(false);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnLp.topMargin = dp(16);
        resetBtn.setLayoutParams(btnLp);
        resetBtn.setOnClickListener(v -> {
            ModulePrefs.resetToDefaults(prefs);
            updatingUi = true;
            try {
                for (Map.Entry<String, Switch> entry : switchMap.entrySet()) {
                    Boolean def = defaultMap.get(entry.getKey());
                    entry.getValue().setChecked(def != null && def);
                }
            } finally {
                updatingUi = false;
            }
            Toast.makeText(this, "已恢复默认设置，请重启虎牙生效", Toast.LENGTH_SHORT).show();
        });
        root.addView(resetBtn);

        // Usage tip
        TextView tip = new TextView(this);
        tip.setText("使用提示：\n"
                + "• 修改任意开关后，请强行停止并重新启动虎牙直播以确保原生层配置完全生效。\n"
                + "• 进入直播间 → 礼物 → 包裹 → 点击「看广告赚虎粮」可直接跳过广告领取虎粮。");
        tip.setTextSize(12.5f);
        tip.setLineSpacing(dp(3), 1.0f);
        tip.setTextColor(secondaryText);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        tipLp.topMargin = dp(14);
        tipLp.leftMargin = dp(4);
        tipLp.rightMargin = dp(4);
        tip.setLayoutParams(tipLp);
        root.addView(tip);

        scrollView.addView(root);
        setContentView(scrollView);
    }

    private LinearLayout createCard(int bgColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        return card;
    }

    private void addSwitchRow(
            LinearLayout parent,
            SharedPreferences prefs,
            String key,
            boolean defaultValue,
            String titleText,
            String descText,
            int primaryText,
            int secondaryText,
            int dividerColor,
            boolean showDivider
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        colLp.rightMargin = dp(12);
        textCol.setLayoutParams(colLp);

        TextView titleView = new TextView(this);
        titleView.setText(titleText);
        titleView.setTextSize(15f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(primaryText);
        textCol.addView(titleView);

        TextView descView = new TextView(this);
        descView.setText(descText);
        descView.setTextSize(12.5f);
        descView.setLineSpacing(dp(2), 1.0f);
        descView.setTextColor(secondaryText);
        LinearLayout.LayoutParams descLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        descLp.topMargin = dp(3);
        descView.setLayoutParams(descLp);
        textCol.addView(descView);

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, defaultValue));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingUi) {
                return;
            }
            prefs.edit().putBoolean(key, isChecked).apply();
        });

        row.setOnClickListener(v -> sw.toggle());
        row.addView(textCol);
        row.addView(sw);
        parent.addView(row);

        switchMap.put(key, sw);
        defaultMap.put(key, defaultValue);

        if (showDivider) {
            View divider = new View(this);
            divider.setBackgroundColor(dividerColor);
            divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    Math.max(1, dp(1) / 2)
            ));
            parent.addView(divider);
        }
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
}
