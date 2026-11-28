package com.huya.pure.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure-native 5-tab Add Account modal:
 * 1. Mobile SMS (recommended, long-term 5008 token)
 * 2. WeChat QR (with native WebView SafeAuth modal & code strategy fallback)
 * 3. QQ QR (with SafeAuth & code strategy fallback)
 * 4. Huya App QR
 * 5. Cookie Import
 */
public final class AddAccountModal {

    public interface OnAccountAddedListener {
        void onAccountAdded(Models.Account account, String message);
    }

    private static final int MODE_SMS = 0;
    private static final int MODE_WX = 1;
    private static final int MODE_QQ = 2;
    private static final int MODE_HUYA = 3;
    private static final int MODE_COOKIE = 4;

    private final Activity activity;
    private final HubanUi.Palette p;
    private final HubanApiClient api;
    private final ExecutorService executor;
    private final OnAccountAddedListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HubanUi.DialogHandle sheetHandle;
    private HubanUi.DialogHandle safeAuthHandle;

    private int currentMode = MODE_SMS;
    private final TextView[] tabChips = new TextView[5];
    private LinearLayout modeContentHost;

    // SMS state
    private String smsDeviceId = "";
    private int smsCooldown = 0;
    private Runnable smsTimerTask;

    // QR & Third-party state
    private String activeQrSessionId = "";
    private int qrPollSeq = 0;
    private Runnable qrPollTask;
    private Bitmap currentQrBitmap;
    private ImageView qrImageView;
    private TextView qrStatusView;
    private TextView qrRefreshBtn;
    private TextView qrQuickLaunchBtn;
    private LinearLayout qrPanel;
    private LinearLayout secondAuthPanel;
    private Models.AuthChallenge currentAuthChallenge;
    private int selectedAuthStrategy = 8;

    private AddAccountModal(
            Activity activity,
            HubanUi.Palette palette,
            HubanApiClient api,
            ExecutorService executor,
            OnAccountAddedListener listener
    ) {
        this.activity = activity;
        this.p = palette;
        this.api = api;
        this.executor = executor;
        this.listener = listener;
    }

    public static void show(
            Activity activity,
            HubanUi.Palette palette,
            HubanApiClient api,
            ExecutorService executor,
            OnAccountAddedListener listener
    ) {
        new AddAccountModal(activity, palette, api, executor, listener).open();
    }

    private void open() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);

        // 5-tab pill switcher
        HorizontalScrollView tabScroll = new HorizontalScrollView(activity);
        tabScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout tabRow = new LinearLayout(activity);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);

        String[] names = {"手机短信(推荐)", "微信扫码", "QQ扫码", "虎牙扫码", "Cookie导入"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView chip = new TextView(activity);
            chip.setText(names[i]);
            chip.setTextSize(12.5f);
            chip.setSingleLine(true);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(HubanUi.dp(activity, 13), HubanUi.dp(activity, 8),
                    HubanUi.dp(activity, 13), HubanUi.dp(activity, 8));
            chip.setOnClickListener(v -> switchMode(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.leftMargin = HubanUi.dp(activity, 7);
            tabRow.addView(chip, lp);
            tabChips[i] = chip;
        }
        tabScroll.addView(tabRow);
        root.addView(tabScroll);

        modeContentHost = new LinearLayout(activity);
        modeContentHost.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hostLp.topMargin = HubanUi.dp(activity, 14);
        root.addView(modeContentHost, hostLp);

        sheetHandle = HubanUi.showCustomSheet(
                activity, p,
                "添加虎牙账号",
                "推荐「手机短信」登录，支持长期自动续期；扫码也支持多账号管理",
                root,
                true,
                this::cleanup
        );
        switchMode(MODE_SMS);
    }

    private void cleanup() {
        stopQrPolling();
        if (smsTimerTask != null) {
            mainHandler.removeCallbacks(smsTimerTask);
            smsTimerTask = null;
        }
        if (safeAuthHandle != null && safeAuthHandle.isShowing()) {
            safeAuthHandle.dismiss();
        }
    }

    private void switchMode(int mode) {
        currentMode = mode;
        stopQrPolling();
        for (int i = 0; i < tabChips.length; i++) {
            boolean active = (i == mode);
            tabChips[i].setTypeface(active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabChips[i].setTextColor(active ? Color.WHITE : p.secondaryText);
            tabChips[i].setBackground(active
                    ? HubanUi.primaryGradient(activity, p, 99)
                    : HubanUi.roundedRect(activity, p.subSurface, 99, 1, p.cardBorder));
        }
        if (mode >= 0 && mode < tabChips.length && tabChips[mode] != null) {
            final TextView targetChip = tabChips[mode];
            targetChip.post(() -> {
                if (targetChip.getParent() != null && targetChip.getParent().getParent() instanceof HorizontalScrollView) {
                    HorizontalScrollView hsv = (HorizontalScrollView) targetChip.getParent().getParent();
                    int scrollX = Math.max(0, targetChip.getLeft() - (hsv.getWidth() - targetChip.getWidth()) / 2);
                    hsv.smoothScrollTo(scrollX, 0);
                }
            });
        }
        modeContentHost.removeAllViews();

        switch (mode) {
            case MODE_SMS:
                buildSmsPane();
                break;
            case MODE_WX:
                buildQrPane("wx", "请使用微信扫一扫下方二维码登录虎牙");
                break;
            case MODE_QQ:
                buildQrPane("qq", "请使用手机 QQ 扫描下方二维码登录虎牙");
                break;
            case MODE_HUYA:
                buildQrPane("huya", "请使用虎牙直播 App 扫描下方二维码");
                break;
            case MODE_COOKIE:
                buildCookiePane();
                break;
        }
    }

    // ── 1. Mobile SMS Login Pane ─────────────────────────────────────────────

    private void buildSmsPane() {
        LinearLayout pane = new LinearLayout(activity);
        pane.setOrientation(LinearLayout.VERTICAL);

        TextView tip = new TextView(activity);
        tip.setText("使用手机短信登录将开启长期自动续期，稳定不掉线，完整支持全部任务。");
        tip.setTextColor(p.okText);
        tip.setTextSize(12.5f);
        tip.setPadding(HubanUi.dp(activity, 12), HubanUi.dp(activity, 9),
                HubanUi.dp(activity, 12), HubanUi.dp(activity, 9));
        tip.setBackground(HubanUi.roundedRect(activity, p.okBg, 12, 0, 0));
        pane.addView(tip);

        EditText mobileEt = HubanUi.createStyledInput(activity, p, "请输入 11 位手机号", InputType.TYPE_CLASS_PHONE);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = HubanUi.dp(activity, 12);
        pane.addView(mobileEt, mLp);

        LinearLayout codeRow = new LinearLayout(activity);
        codeRow.setOrientation(LinearLayout.HORIZONTAL);
        codeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        crLp.topMargin = HubanUi.dp(activity, 10);

        EditText codeEt = HubanUi.createStyledInput(activity, p, "请输入短信验证码", InputType.TYPE_CLASS_NUMBER);
        codeRow.addView(codeEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView sendBtn = HubanUi.createSecondaryButton(activity, p, "发送验证码");
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.leftMargin = HubanUi.dp(activity, 8);
        codeRow.addView(sendBtn, sbLp);
        pane.addView(codeRow, crLp);

        TextView statusTv = new TextView(activity);
        statusTv.setTextSize(12.5f);
        statusTv.setTextColor(p.secondaryText);
        statusTv.setVisibility(View.GONE);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = HubanUi.dp(activity, 8);
        pane.addView(statusTv, stLp);

        sendBtn.setOnClickListener(v -> {
            if (smsCooldown > 0) return;
            String mobile = mobileEt.getText().toString().trim();
            if (mobile.length() < 6) {
                Toast.makeText(activity, "请输入正确的手机号", Toast.LENGTH_SHORT).show();
                return;
            }
            sendBtn.setEnabled(false);
            sendBtn.setText("发送中...");
            statusTv.setVisibility(View.VISIBLE);
            statusTv.setTextColor(p.secondaryText);
            statusTv.setText("正在向虎牙请求短信验证码...");
            executor.execute(() -> {
                try {
                    Models.SmsSendResult res = api.sendMobileSms(mobile);
                    mainHandler.post(() -> {
                        smsDeviceId = res.deviceId;
                        statusTv.setTextColor(p.okText);
                        statusTv.setText(res.message.isEmpty() ? "验证码已发送，请注意查收" : res.message);
                        startSmsCountdown(sendBtn);
                    });
                } catch (Throwable t) {
                    mainHandler.post(() -> {
                        sendBtn.setEnabled(true);
                        sendBtn.setText("发送验证码");
                        statusTv.setTextColor(p.errText);
                        statusTv.setText("发送失败：" + t.getMessage());
                    });
                }
            });
        });

        TextView loginBtn = HubanUi.createPrimaryButton(activity, p, "短信登录并添加账号");
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, HubanUi.dp(activity, 46));
        lbLp.topMargin = HubanUi.dp(activity, 14);
        pane.addView(loginBtn, lbLp);

        loginBtn.setOnClickListener(v -> {
            String mobile = mobileEt.getText().toString().trim();
            String code = codeEt.getText().toString().trim();
            if (mobile.isEmpty() || code.isEmpty()) {
                Toast.makeText(activity, "请填写手机号和验证码", Toast.LENGTH_SHORT).show();
                return;
            }
            loginBtn.setEnabled(false);
            loginBtn.setText("正在登录...");
            statusTv.setVisibility(View.VISIBLE);
            statusTv.setTextColor(p.secondaryText);
            statusTv.setText("正在验证并同步账号信息...");
            executor.execute(() -> {
                try {
                    Models.AccountActionResult res = api.loginMobileSms(mobile, code, smsDeviceId, "");
                    mainHandler.post(() -> finishSuccess(res.account, res.message));
                } catch (Throwable t) {
                    mainHandler.post(() -> {
                        loginBtn.setEnabled(true);
                        loginBtn.setText("短信登录并添加账号");
                        statusTv.setTextColor(p.errText);
                        statusTv.setText("登录失败：" + t.getMessage());
                    });
                }
            });
        });

        modeContentHost.addView(pane);
    }

    private void startSmsCountdown(TextView sendBtn) {
        if (smsTimerTask != null) mainHandler.removeCallbacks(smsTimerTask);
        smsCooldown = 60;
        smsTimerTask = new Runnable() {
            @Override
            public void run() {
                if (smsCooldown <= 0) {
                    sendBtn.setEnabled(true);
                    sendBtn.setText("重新发送");
                    return;
                }
                sendBtn.setEnabled(false);
                sendBtn.setText(smsCooldown + "s 后重发");
                smsCooldown--;
                mainHandler.postDelayed(this, 1000L);
            }
        };
        mainHandler.post(smsTimerTask);
    }

    // ── 2 / 3 / 4. WeChat / QQ / Huya QR Login Pane ──────────────────────────

    private void buildQrPane(final String provider, String subtitle) {
        qrPanel = new LinearLayout(activity);
        qrPanel.setOrientation(LinearLayout.VERTICAL);
        qrPanel.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView tip = new TextView(activity);
        tip.setText(subtitle);
        tip.setTextColor(p.secondaryText);
        tip.setTextSize(13f);
        tip.setGravity(Gravity.CENTER);
        qrPanel.addView(tip);

        FrameLayout qrFrame = new FrameLayout(activity);
        qrFrame.setBackground(HubanUi.roundedRect(activity, Color.WHITE, 18, 1, p.cardBorder));
        int boxSize = HubanUi.dp(activity, 206);
        int pad = HubanUi.dp(activity, 10);
        qrFrame.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams qfLp = new LinearLayout.LayoutParams(boxSize, boxSize);
        qfLp.topMargin = HubanUi.dp(activity, 14);
        qrPanel.addView(qrFrame, qfLp);

        qrImageView = new ImageView(activity);
        qrImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qrFrame.addView(qrImageView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        qrStatusView = new TextView(activity);
        qrStatusView.setText("正在获取二维码...");
        qrStatusView.setTextColor(p.brandText);
        qrStatusView.setTextSize(13.5f);
        qrStatusView.setTypeface(Typeface.DEFAULT_BOLD);
        qrStatusView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = HubanUi.dp(activity, 12);
        qrPanel.addView(qrStatusView, stLp);

        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arLp.topMargin = HubanUi.dp(activity, 12);

        String quickText = "wx".equals(provider)
                ? "分享到其他设备扫码"
                : ("huya".equals(provider) ? "保存并打开虎牙" : "保存并打开 QQ");
        qrQuickLaunchBtn = "wx".equals(provider)
                ? HubanUi.createSecondaryButton(activity, p, quickText)
                : HubanUi.createPrimaryButton(activity, p, quickText);
        qrQuickLaunchBtn.setTextSize(13f);
        qrQuickLaunchBtn.setPadding(HubanUi.dp(activity, 14), HubanUi.dp(activity, 11),
                HubanUi.dp(activity, 14), HubanUi.dp(activity, 11));
        qrQuickLaunchBtn.setEnabled(false);
        qrQuickLaunchBtn.setAlpha(0.55f);
        qrQuickLaunchBtn.setOnClickListener(v -> {
            if ("wx".equals(provider)) {
                shareQrToOtherDevice();
            } else {
                saveQrAndOpenApp(provider);
            }
        });
        LinearLayout.LayoutParams qlLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        actionRow.addView(qrQuickLaunchBtn, qlLp);

        qrRefreshBtn = HubanUi.createSecondaryButton(activity, p, "刷新");
        qrRefreshBtn.setTextSize(13f);
        qrRefreshBtn.setPadding(HubanUi.dp(activity, 16), HubanUi.dp(activity, 11),
                HubanUi.dp(activity, 16), HubanUi.dp(activity, 11));
        qrRefreshBtn.setOnClickListener(v -> startQrSession(provider));
        LinearLayout.LayoutParams rfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rfLp.leftMargin = HubanUi.dp(activity, 10);
        actionRow.addView(qrRefreshBtn, rfLp);

        qrPanel.addView(actionRow, arLp);

        TextView singlePhoneHint = new TextView(activity);
        singlePhoneHint.setText("wx".equals(provider)
                ? "微信限制该二维码仅支持摄像头扫一扫（不支持相册识别），请用另一台设备扫码，或改用「手机短信登录」。"
                : ("huya".equals(provider)
                ? "单机提示：点上方按钮会自动存图并打开虎牙，在「扫一扫」里选相册即可。"
                : "单机提示：点上方按钮会自动存图并打开 QQ，在「扫一扫」里选相册即可。"));
        singlePhoneHint.setTextColor(p.tertiaryText);
        singlePhoneHint.setTextSize(11.5f);
        singlePhoneHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams shLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shLp.topMargin = HubanUi.dp(activity, 8);
        qrPanel.addView(singlePhoneHint, shLp);

        secondAuthPanel = new LinearLayout(activity);
        secondAuthPanel.setOrientation(LinearLayout.VERTICAL);
        secondAuthPanel.setVisibility(View.GONE);

        modeContentHost.addView(qrPanel);
        modeContentHost.addView(secondAuthPanel);

        startQrSession(provider);
    }

    private void startQrSession(final String provider) {
        stopQrPolling();
        final int seq = ++qrPollSeq;
        currentQrBitmap = null;
        if (qrPanel != null) qrPanel.setVisibility(View.VISIBLE);
        if (secondAuthPanel != null) secondAuthPanel.setVisibility(View.GONE);
        if (qrImageView != null) qrImageView.setImageBitmap(null);
        if (qrQuickLaunchBtn != null) {
            qrQuickLaunchBtn.setEnabled(false);
            qrQuickLaunchBtn.setAlpha(0.55f);
        }
        if (qrStatusView != null) {
            qrStatusView.setTextColor(p.secondaryText);
            qrStatusView.setText("正在获取登录二维码...");
        }

        executor.execute(() -> {
            try {
                Models.QRStartResult res = "huya".equals(provider)
                        ? api.startQr()
                        : api.startThird(provider);
                Bitmap qrBmp = decodeDataUriBitmap(res.imageData);
                mainHandler.post(() -> {
                    if (seq != qrPollSeq) return;
                    activeQrSessionId = res.sessionId;
                    currentQrBitmap = qrBmp;
                    if (qrImageView != null && qrBmp != null) {
                        qrImageView.setImageBitmap(qrBmp);
                    }
                    if (qrQuickLaunchBtn != null && qrBmp != null) {
                        qrQuickLaunchBtn.setEnabled(true);
                        qrQuickLaunchBtn.setAlpha(1f);
                    }
                    if (qrStatusView != null) {
                        qrStatusView.setTextColor(p.brandText);
                        qrStatusView.setText("等待扫码，请在手机上确认登录...");
                    }
                    scheduleNextQrPoll(provider, seq);
                });
            } catch (Throwable t) {
                mainHandler.post(() -> {
                    if (seq != qrPollSeq) return;
                    if (qrStatusView != null) {
                        qrStatusView.setTextColor(p.errText);
                        qrStatusView.setText("获取二维码失败：" + t.getMessage());
                    }
                });
            }
        });
    }

    private void scheduleNextQrPoll(final String provider, final int seq) {
        qrPollTask = () -> {
            if (seq != qrPollSeq || activeQrSessionId.isEmpty()) return;
            final String sid = activeQrSessionId;
            executor.execute(() -> {
                try {
                    Models.PollResult res = "huya".equals(provider)
                            ? api.pollQr(sid)
                            : api.pollThird(sid);
                    mainHandler.post(() -> handleQrPollResult(provider, seq, res));
                } catch (Throwable t) {
                    mainHandler.post(() -> {
                        if (seq != qrPollSeq) return;
                        if (qrStatusView != null) {
                            qrStatusView.setTextColor(p.errText);
                            qrStatusView.setText("查询状态失败：" + t.getMessage());
                        }
                    });
                }
            });
        };
        mainHandler.postDelayed(qrPollTask, 1800L);
    }

    private void handleQrPollResult(String provider, int seq, Models.PollResult res) {
        if (seq != qrPollSeq || res == null) return;
        String st = res.status != null ? res.status : "";
        switch (st) {
            case "confirmed":
                stopQrPolling();
                finishSuccess(res.account, res.message);
                break;
            case "waiting":
            case "pending":
            case "scanned":
                if (qrStatusView != null) {
                    qrStatusView.setTextColor("scanned".equals(st) ? p.okText : p.brandText);
                    qrStatusView.setText(res.message.isEmpty()
                            ? ("scanned".equals(st) ? "已扫码，请在手机上确认..." : "等待扫码...")
                            : res.message);
                }
                scheduleNextQrPoll(provider, seq);
                break;
            case "need_auth":
                stopQrPolling();
                if (res.auth != null) {
                    if (qrStatusView != null) {
                        qrStatusView.setTextColor(p.brandText);
                        qrStatusView.setText(res.message.isEmpty() ? "请完成虎牙安全验证..." : res.message);
                    }
                    openSecondAuth(provider, res.auth);
                } else if (qrStatusView != null) {
                    qrStatusView.setTextColor(p.errText);
                    qrStatusView.setText(res.message.isEmpty() ? "需要二次安全验证" : res.message);
                }
                break;
            case "re_scan":
                Toast.makeText(activity, res.message.isEmpty() ? "验证通过，请重新扫码" : res.message, Toast.LENGTH_LONG).show();
                startQrSession(provider);
                break;
            default:
                stopQrPolling();
                if (qrStatusView != null) {
                    qrStatusView.setTextColor(p.errText);
                    qrStatusView.setText(res.message.isEmpty() ? "二维码已过期，请点击刷新" : res.message);
                }
                break;
        }
    }

    private void stopQrPolling() {
        qrPollSeq++;
        if (qrPollTask != null) {
            mainHandler.removeCallbacks(qrPollTask);
            qrPollTask = null;
        }
    }

    // ── Second-Factor Verification: Native WebView SafeAuth & Code Fallback ──

    private void openSecondAuth(final String provider, final Models.AuthChallenge auth) {
        this.currentAuthChallenge = auth;
        if ("safe_auth".equals(auth.mode) && auth.safeAuthUrl != null && !auth.safeAuthUrl.isEmpty()) {
            showSafeAuthWebViewModal(provider, auth);
            return;
        }
        renderCodeSecondAuthPane(provider, auth);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showSafeAuthWebViewModal(final String provider, final Models.AuthChallenge auth) {
        if (safeAuthHandle != null && safeAuthHandle.isShowing()) {
            safeAuthHandle.dismiss();
        }
        final AtomicBoolean submitted = new AtomicBoolean(false);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView statusTv = new TextView(activity);
        statusTv.setText(auth.message.isEmpty() ? "请完成虎牙安全验证，通过后将自动继续登录" : auth.message);
        statusTv.setTextColor(p.secondaryText);
        statusTv.setTextSize(12.5f);
        box.addView(statusTv);

        WebView webView = new WebView(activity);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        class SafeAuthBridge {
            @JavascriptInterface
            public void onSafeAuth(final String authId) {
                if (!submitted.compareAndSet(false, true)) return;
                mainHandler.post(() -> {
                    statusTv.setTextColor(p.okText);
                    statusTv.setText("安全验证已通过，正在完成虎牙登录...");
                    executor.execute(() -> {
                        try {
                            Models.PollResult res = api.verifyThirdSafeAuth(auth.sessionId, authId);
                            mainHandler.post(() -> {
                                if (safeAuthHandle != null) safeAuthHandle.dismiss();
                                if ("confirmed".equals(res.status)) {
                                    finishSuccess(res.account, res.message);
                                } else if ("re_scan".equals(res.status)) {
                                    Toast.makeText(activity,
                                            res.message.isEmpty() ? "安全验证已通过，请重新扫码完成登录" : res.message,
                                            Toast.LENGTH_LONG).show();
                                    startQrSession(provider);
                                } else if ("need_auth".equals(res.status) && res.auth != null) {
                                    openSecondAuth(provider, res.auth);
                                } else {
                                    Toast.makeText(activity,
                                            res.message.isEmpty() ? "验证未完成，请重试" : res.message,
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                        } catch (Throwable t) {
                            submitted.set(false);
                            mainHandler.post(() -> {
                                statusTv.setTextColor(p.errText);
                                statusTv.setText("提交验证失败：" + t.getMessage());
                            });
                        }
                    });
                });
            }
        }

        webView.addJavascriptInterface(new SafeAuthBridge(), "HubanSafeAuth");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Also attach a top-level listener in case SafeAuth redirects out of the iframe.
                String js = "(function(){"
                        + "if(window.__hubanSafeHooked)return;window.__hubanSafeHooked=true;"
                        + "window.addEventListener('message',function(ev){"
                        + "  try{"
                        + "    var m=ev.data;if(typeof m==='string'){m=JSON.parse(m);}"
                        + "    if(m&&m.eventType==='udbSafeAuth'){"
                        + "      var aid=(m.param&&m.param.data&&m.param.data.authId)?String(m.param.data.authId)"
                        + "        :((m.data&&m.data.authId)?String(m.data.authId):(m.authId?String(m.authId):''));"
                        + "      window.HubanSafeAuth.onSafeAuth(aid);"
                        + "    }"
                        + "  }catch(e){}"
                        + "});"
                        + "})();";
                view.evaluateJavascript(js, null);
            }
        });

        // Host SafeAuth inside a full-size iframe under https://www.huya.com/ so window.parent.postMessage
        // delivers udbSafeAuth reliably to our listener, matching the Web UI implementation.
        String safeUrlEscaped = auth.safeAuthUrl
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
        String wrapperHtml = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body,iframe{margin:0;padding:0;width:100%;height:100%;border:0;background:#fff;}</style>"
                + "</head><body>"
                + "<iframe id='sa' src=\"" + safeUrlEscaped + "\" allow='*'></iframe>"
                + "<script>"
                + "window.addEventListener('message',function(ev){"
                + "  try{"
                + "    var m=ev.data;if(typeof m==='string'){m=JSON.parse(m);}"
                + "    if(m&&m.eventType==='udbSafeAuth'){"
                + "      var aid=(m.param&&m.param.data&&m.param.data.authId)?String(m.param.data.authId)"
                + "        :((m.data&&m.data.authId)?String(m.data.authId):(m.authId?String(m.authId):''));"
                + "      if(window.HubanSafeAuth){window.HubanSafeAuth.onSafeAuth(aid);}"
                + "    }"
                + "  }catch(e){}"
                + "});"
                + "</script></body></html>";
        webView.loadDataWithBaseURL("https://www.huya.com/", wrapperHtml, "text/html", "utf-8", null);

        int webH = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.56f);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(HubanUi.dp(activity, 380), webH));
        wlp.topMargin = HubanUi.dp(activity, 10);
        box.addView(webView, wlp);

        // Only offer code verification fallback when actual code strategies (4=image, 8=SMS, 16=token) exist.
        boolean hasCodeStrategy = false;
        for (Models.AuthStrategy s : auth.strategies) {
            if (s.strategy == 4 || s.strategy == 8 || s.strategy == 16) {
                hasCodeStrategy = true;
                break;
            }
        }
        if (hasCodeStrategy) {
            TextView fallbackBtn = HubanUi.createSecondaryButton(activity, p, "切换验证码方式");
            LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fLp.topMargin = HubanUi.dp(activity, 10);
            fallbackBtn.setOnClickListener(v -> {
                if (safeAuthHandle != null) safeAuthHandle.dismiss();
                renderCodeSecondAuthPane(provider, auth);
            });
            box.addView(fallbackBtn, fLp);
        }

        safeAuthHandle = HubanUi.showCustomSheet(
                activity, p,
                auth.safeAuthTitle.isEmpty() ? "虎牙安全验证" : auth.safeAuthTitle,
                "完成后将自动提交验证并继续登录",
                box,
                false,
                () -> {
                    if (!submitted.get() && qrStatusView != null) {
                        qrStatusView.setTextColor(p.errText);
                        qrStatusView.setText("已取消安全验证，请点击刷新重新扫码");
                    }
                    try {
                        webView.stopLoading();
                        webView.destroy();
                    } catch (Throwable ignored) {
                    }
                }
        );
    }

    private void renderCodeSecondAuthPane(final String provider, final Models.AuthChallenge auth) {
        if (qrPanel != null) qrPanel.setVisibility(View.GONE);
        secondAuthPanel.setVisibility(View.VISIBLE);
        secondAuthPanel.removeAllViews();

        List<Models.AuthStrategy> strategies = auth.strategies;
        selectedAuthStrategy = 8;
        if (!strategies.isEmpty()) {
            selectedAuthStrategy = strategies.get(0).strategy;
            for (Models.AuthStrategy s : strategies) {
                if (s.strategy == 8) {
                    selectedAuthStrategy = 8;
                    break;
                }
            }
        }

        TextView title = new TextView(activity);
        title.setText("二次安全验证");
        title.setTextColor(p.primaryText);
        title.setTextSize(15.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        secondAuthPanel.addView(title);

        TextView promptTv = new TextView(activity);
        promptTv.setTextColor(p.secondaryText);
        promptTv.setTextSize(13f);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.topMargin = HubanUi.dp(activity, 6);
        secondAuthPanel.addView(promptTv, pLp);

        // Strategy selector chips if >1
        LinearLayout stratRow = new LinearLayout(activity);
        stratRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.topMargin = HubanUi.dp(activity, 10);

        ImageView captchaIv = new ImageView(activity);
        captchaIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        captchaIv.setVisibility(View.GONE);
        LinearLayout.LayoutParams capLp = new LinearLayout.LayoutParams(
                HubanUi.dp(activity, 180), HubanUi.dp(activity, 64));
        capLp.topMargin = HubanUi.dp(activity, 10);
        capLp.gravity = Gravity.CENTER_HORIZONTAL;

        EditText codeEt = HubanUi.createStyledInput(activity, p, "请输入验证码", InputType.TYPE_CLASS_TEXT);
        TextView sendCodeBtn = HubanUi.createSecondaryButton(activity, p, "发送短信");
        TextView statusTv = new TextView(activity);
        statusTv.setTextSize(12.5f);
        statusTv.setTextColor(p.secondaryText);

        Runnable syncStrategyUi = () -> {
            Models.AuthStrategy cur = null;
            for (Models.AuthStrategy s : strategies) {
                if (s.strategy == selectedAuthStrategy) {
                    cur = s;
                    break;
                }
            }
            if (selectedAuthStrategy == 8) {
                promptTv.setText(cur != null && !cur.promptContent.isEmpty()
                        ? cur.promptContent
                        : "虎牙要求短信二次验证，请点击「发送短信」获取验证码。");
                captchaIv.setVisibility(View.GONE);
                sendCodeBtn.setVisibility(View.VISIBLE);
                codeEt.setHint("请输入短信验证码");
            } else if (selectedAuthStrategy == 4) {
                promptTv.setText("请输入下方图形验证码中的字符：");
                sendCodeBtn.setVisibility(View.GONE);
                codeEt.setHint("请输入图形验证码");
                if (cur != null && !cur.imageData.isEmpty()) {
                    Bitmap bmp = decodeDataUriBitmap(cur.imageData);
                    captchaIv.setImageBitmap(bmp);
                    captchaIv.setVisibility(bmp != null ? View.VISIBLE : View.GONE);
                } else {
                    captchaIv.setVisibility(View.GONE);
                }
            } else {
                promptTv.setText(cur != null && !cur.promptContent.isEmpty()
                        ? cur.promptContent
                        : "请输入 YY 安全中心动态口令：");
                captchaIv.setVisibility(View.GONE);
                sendCodeBtn.setVisibility(View.GONE);
                codeEt.setHint("请输入动态口令");
            }
        };

        if (strategies.size() > 1) {
            for (int i = 0; i < strategies.size(); i++) {
                final Models.AuthStrategy s = strategies.get(i);
                String name = s.strategy == 8 ? "短信验证" : (s.strategy == 4 ? "图形验证" : "安全令牌");
                TextView btn = HubanUi.createSecondaryButton(activity, p, name);
                btn.setTextSize(12f);
                btn.setOnClickListener(v -> {
                    selectedAuthStrategy = s.strategy;
                    syncStrategyUi.run();
                });
                LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) bLp.leftMargin = HubanUi.dp(activity, 8);
                stratRow.addView(btn, bLp);
            }
            secondAuthPanel.addView(stratRow, srLp);
        }

        secondAuthPanel.addView(captchaIv, capLp);

        LinearLayout inputRow = new LinearLayout(activity);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams irLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        irLp.topMargin = HubanUi.dp(activity, 10);
        inputRow.addView(codeEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.leftMargin = HubanUi.dp(activity, 8);
        inputRow.addView(sendCodeBtn, scLp);
        secondAuthPanel.addView(inputRow, irLp);

        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = HubanUi.dp(activity, 8);
        secondAuthPanel.addView(statusTv, stLp);

        sendCodeBtn.setOnClickListener(v -> {
            sendCodeBtn.setEnabled(false);
            sendCodeBtn.setText("发送中...");
            executor.execute(() -> {
                try {
                    JSONObject out = api.sendThirdAuthCode(auth.sessionId, selectedAuthStrategy);
                    mainHandler.post(() -> {
                        sendCodeBtn.setEnabled(true);
                        sendCodeBtn.setText("重新发送");
                        statusTv.setTextColor(p.okText);
                        statusTv.setText(out.optString("message", "验证码已发送"));
                    });
                } catch (Throwable t) {
                    mainHandler.post(() -> {
                        sendCodeBtn.setEnabled(true);
                        sendCodeBtn.setText("发送短信");
                        statusTv.setTextColor(p.errText);
                        statusTv.setText("发送失败：" + t.getMessage());
                    });
                }
            });
        });

        TextView verifyBtn = HubanUi.createPrimaryButton(activity, p, "提交安全验证");
        LinearLayout.LayoutParams vbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, HubanUi.dp(activity, 46));
        vbLp.topMargin = HubanUi.dp(activity, 12);
        secondAuthPanel.addView(verifyBtn, vbLp);

        verifyBtn.setOnClickListener(v -> {
            String code = codeEt.getText().toString().trim();
            if (code.isEmpty()) {
                Toast.makeText(activity, "请输入验证码", Toast.LENGTH_SHORT).show();
                return;
            }
            verifyBtn.setEnabled(false);
            verifyBtn.setText("验证中...");
            executor.execute(() -> {
                try {
                    Models.PollResult res = api.verifyThirdAuthCode(auth.sessionId, selectedAuthStrategy, code);
                    mainHandler.post(() -> {
                        verifyBtn.setEnabled(true);
                        verifyBtn.setText("提交安全验证");
                        if ("confirmed".equals(res.status)) {
                            finishSuccess(res.account, res.message);
                        } else if ("re_scan".equals(res.status)) {
                            Toast.makeText(activity, res.message.isEmpty() ? "请重新扫码" : res.message, Toast.LENGTH_LONG).show();
                            startQrSession(provider);
                        } else if ("need_auth".equals(res.status) && res.auth != null) {
                            openSecondAuth(provider, res.auth);
                        } else {
                            statusTv.setTextColor(p.errText);
                            statusTv.setText(res.message.isEmpty() ? "验证失败，请重试" : res.message);
                        }
                    });
                } catch (Throwable t) {
                    mainHandler.post(() -> {
                        verifyBtn.setEnabled(true);
                        verifyBtn.setText("提交安全验证");
                        statusTv.setTextColor(p.errText);
                        statusTv.setText("验证异常：" + t.getMessage());
                    });
                }
            });
        });

        TextView backBtn = HubanUi.createSecondaryButton(activity, p, "返回重新扫码");
        LinearLayout.LayoutParams bkLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bkLp.topMargin = HubanUi.dp(activity, 8);
        backBtn.setOnClickListener(v -> startQrSession(provider));
        secondAuthPanel.addView(backBtn, bkLp);

        syncStrategyUi.run();
    }

    // ── 5. Cookie Import Pane ────────────────────────────────────────────────

    private void buildCookiePane() {
        LinearLayout pane = new LinearLayout(activity);
        pane.setOrientation(LinearLayout.VERTICAL);

        TextView tip = new TextView(activity);
        tip.setText("粘贴浏览器中的 huya.com Cookie（需包含 yyuid / udb_uid 与 udb_n / udb_biztoken / udb_oat 等凭证）。");
        tip.setTextColor(p.secondaryText);
        tip.setTextSize(12.5f);
        pane.addView(tip);

        EditText labelEt = HubanUi.createStyledInput(activity, p, "账号备注（可选）", InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams lLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lLp.topMargin = HubanUi.dp(activity, 10);
        pane.addView(labelEt, lLp);

        EditText cookieEt = HubanUi.createStyledInput(
                activity, p, "粘贴完整 Cookie 字符串...",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        cookieEt.setMinLines(4);
        cookieEt.setMaxLines(6);
        cookieEt.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = HubanUi.dp(activity, 10);
        pane.addView(cookieEt, cLp);

        TextView errTv = new TextView(activity);
        errTv.setTextColor(p.errText);
        errTv.setTextSize(12.5f);
        errTv.setVisibility(View.GONE);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        eLp.topMargin = HubanUi.dp(activity, 8);
        pane.addView(errTv, eLp);

        TextView importBtn = HubanUi.createPrimaryButton(activity, p, "导入 Cookie 并添加账号");
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, HubanUi.dp(activity, 46));
        bLp.topMargin = HubanUi.dp(activity, 14);
        pane.addView(importBtn, bLp);

        importBtn.setOnClickListener(v -> {
            String cookie = cookieEt.getText().toString().trim();
            String label = labelEt.getText().toString().trim();
            if (cookie.isEmpty()) {
                Toast.makeText(activity, "请粘贴 Cookie", Toast.LENGTH_SHORT).show();
                return;
            }
            importBtn.setEnabled(false);
            importBtn.setText("正在验证并导入...");
            errTv.setVisibility(View.GONE);
            executor.execute(() -> {
                try {
                    Models.AccountActionResult res = api.addCookieAccount(label, cookie);
                    mainHandler.post(() -> finishSuccess(res.account, res.message));
                } catch (Throwable t) {
                    mainHandler.post(() -> {
                        importBtn.setEnabled(true);
                        importBtn.setText("导入 Cookie 并添加账号");
                        errTv.setVisibility(View.VISIBLE);
                        errTv.setText("导入失败：" + t.getMessage());
                    });
                }
            });
        });

        modeContentHost.addView(pane);
    }

    private void shareQrToOtherDevice() {
        final Bitmap bmp = currentQrBitmap;
        if (bmp == null) {
            Toast.makeText(activity, "二维码尚未加载完成", Toast.LENGTH_SHORT).show();
            return;
        }
        executor.execute(() -> {
            Uri uri = saveBitmapToGalleryUri(bmp, "huban_qr_wx_" + System.currentTimeMillis() + ".png");
            mainHandler.post(() -> {
                if (uri == null) {
                    Toast.makeText(activity, "导出二维码失败", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("image/png");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(share, "发送到电脑/另一台设备以便微信摄像头扫码"));
                } catch (Throwable t) {
                    Toast.makeText(activity, "二维码已保存到相册，可发给另一台设备扫码", Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void saveQrAndOpenApp(final String provider) {
        final Bitmap bmp = currentQrBitmap;
        if (bmp == null) {
            Toast.makeText(activity, "二维码尚未加载完成", Toast.LENGTH_SHORT).show();
            return;
        }
        executor.execute(() -> {
            boolean saved = saveBitmapToGalleryUri(bmp, "huban_qr_" + provider + "_" + System.currentTimeMillis() + ".png") != null;
            mainHandler.post(() -> {
                String tip;
                if ("huya".equals(provider)) {
                    tip = saved ? "二维码已保存到相册，请在虎牙扫一扫选择相册" : "正在打开虎牙直播...";
                } else {
                    tip = saved ? "二维码已保存到相册，请在手机 QQ 扫一扫选图" : "正在打开手机 QQ...";
                }
                Toast.makeText(activity, tip, Toast.LENGTH_LONG).show();
                launchTargetApp(provider);
            });
        });
    }

    private Uri saveBitmapToGalleryUri(Bitmap bmp, String fileName) {
        try {
            ContentResolver resolver = activity.getContentResolver();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Huban");
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return null;
                try (OutputStream os = resolver.openOutputStream(uri)) {
                    if (os == null) return null;
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) return null;
                }
                return uri;
            } else {
                @SuppressWarnings("deprecation")
                String res = MediaStore.Images.Media.insertImage(resolver, bmp, fileName, "虎伴登录二维码");
                return res != null ? Uri.parse(res) : null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void launchTargetApp(String provider) {
        if ("wx".equals(provider)) {
            try {
                Intent scan = new Intent();
                scan.setComponent(new ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"));
                scan.putExtra("LauncherUI.From.Scaner.Shortcut", true);
                scan.setAction(Intent.ACTION_VIEW);
                scan.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                activity.startActivity(scan);
                return;
            } catch (Throwable ignored) {
            }
            launchPackageOrFallback("com.tencent.mm", "未检测到微信，请手动打开微信扫描相册中的二维码");
        } else if ("huya".equals(provider)) {
            launchPackageOrFallback("com.duowan.kiwi", "未检测到虎牙直播 App，请手动打开虎牙扫描相册中的二维码");
        } else if ("qq".equals(provider)) {
            launchPackageOrFallback("com.tencent.mobileqq", "未检测到手机 QQ，请手动打开 QQ 扫描相册中的二维码");
        }
    }

    private void launchPackageOrFallback(String pkg, String notFoundMsg) {
        try {
            Intent launch = activity.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(launch);
                return;
            }
        } catch (Throwable ignored) {
        }
        Toast.makeText(activity, notFoundMsg, Toast.LENGTH_LONG).show();
    }

    private void finishSuccess(Models.Account acc, String message) {
        cleanup();
        if (sheetHandle != null && sheetHandle.isShowing()) {
            sheetHandle.dismiss();
        }
        if (listener != null) {
            listener.onAccountAdded(acc, message != null && !message.isEmpty() ? message : "账号添加成功");
        }
    }

    private static Bitmap decodeDataUriBitmap(String dataUri) {
        if (dataUri == null || dataUri.isEmpty()) return null;
        try {
            String b64 = dataUri;
            int comma = dataUri.indexOf(',');
            if (comma >= 0) {
                b64 = dataUri.substring(comma + 1);
            }
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
