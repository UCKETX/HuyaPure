package com.huya.pure.mobile;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ArgbEvaluator;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Scroller;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure-SDK Warm-Clay UI Kit and custom animated Dialog / Bottom-Sheet components.
 * Zero external dependencies, zero raw AlertDialog builders.
 */
public final class HubanUi {

    private HubanUi() {
    }

    public static final class Palette {
        public final boolean isDark;
        public final int bg;
        public final int card;
        public final int cardBorder;
        public final int subSurface;
        public final int primaryText;
        public final int secondaryText;
        public final int tertiaryText;
        public final int divider;
        public final int brandStart;
        public final int brandEnd;
        public final int brandTintBg;
        public final int brandText;
        public final int okText;
        public final int okBg;
        public final int warnText;
        public final int warnBg;
        public final int errText;
        public final int errBg;
        public final int infoText;
        public final int trackBg;

        private Palette(boolean isDark) {
            this.isDark = isDark;
            if (isDark) {
                // Exact module-app dark slate + warm orange palette
                bg = Color.parseColor("#0E1117");
                card = Color.parseColor("#171C24");
                cardBorder = Color.parseColor("#252C38");
                subSurface = Color.parseColor("#1E2530");
                primaryText = Color.parseColor("#F1F5F9");
                secondaryText = Color.parseColor("#94A3B8");
                tertiaryText = Color.parseColor("#64748B");
                divider = Color.parseColor("#242B37");
                brandStart = Color.parseColor("#FF8A1F");
                brandEnd = Color.parseColor("#F55800");
                brandTintBg = Color.parseColor("#2E2116");
                brandText = Color.parseColor("#FFA142");
                okText = Color.parseColor("#34D399");
                okBg = Color.parseColor("#14332A");
                warnText = Color.parseColor("#FBBF24");
                warnBg = Color.parseColor("#332814");
                errText = Color.parseColor("#F87171");
                errBg = Color.parseColor("#381B1D");
                infoText = Color.parseColor("#60A5FA");
                trackBg = Color.parseColor("#2A3342");
            } else {
                // Exact module-app crisp light slate + warm orange palette
                bg = Color.parseColor("#F3F5F9");
                card = Color.parseColor("#FFFFFF");
                cardBorder = Color.parseColor("#E6EAF2");
                subSurface = Color.parseColor("#F6F8FC");
                primaryText = Color.parseColor("#151923");
                secondaryText = Color.parseColor("#606C80");
                tertiaryText = Color.parseColor("#8D99AE");
                divider = Color.parseColor("#EDF0F6");
                brandStart = Color.parseColor("#FF8218");
                brandEnd = Color.parseColor("#FF5500");
                brandTintBg = Color.parseColor("#FFF1E6");
                brandText = Color.parseColor("#E85D00");
                okText = Color.parseColor("#0E9F6E");
                okBg = Color.parseColor("#E6F7F1");
                warnText = Color.parseColor("#B45309");
                warnBg = Color.parseColor("#FEF3C7");
                errText = Color.parseColor("#D92D20");
                errBg = Color.parseColor("#FEECEB");
                infoText = Color.parseColor("#2563EB");
                trackBg = Color.parseColor("#E8EDF5");
            }
        }

        public static Palette of(Context context) {
            boolean dark = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            return new Palette(dark);
        }
    }

    public interface DialogHandle {
        void dismiss();
        boolean isShowing();
    }

    public interface OptionListener {
        void onSelect(int index);
    }

    public interface TimeListener {
        void onSelect(int hour, int minute);
    }

    public interface InputListener {
        boolean onConfirm(String text);
    }

    public static final class TargetOption {
        public final long pid;
        public final String title;
        public final String subtitle;
        public final boolean isSuperFan;
        public boolean selected;
        public int weight;

        public TargetOption(long pid, String title, String subtitle, boolean isSuperFan, boolean selected, int weight) {
            this.pid = pid;
            this.title = title != null ? title : String.valueOf(pid);
            this.subtitle = subtitle != null ? subtitle : "";
            this.isSuperFan = isSuperFan;
            this.selected = selected;
            this.weight = Math.max(1, weight);
        }
    }

    public interface MultiTargetListener {
        void onConfirm(List<TargetOption> options);
    }

    public interface AddCustomUidListener {
        void onRequestAddCustom(DialogHandle sheetHandle);
    }

    // ── Sizing & Drawable Helpers ────────────────────────────────────────────

    public static int dp(Context ctx, float dp) {
        return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
    }

    public static GradientDrawable roundedRect(Context ctx, int fillColor, float radiusDp, float strokeDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(dp(ctx, radiusDp));
        if (strokeDp > 0 && strokeColor != 0) {
            d.setStroke(Math.max(1, dp(ctx, strokeDp)), strokeColor);
        }
        return d;
    }

    public static Drawable rippleRect(Context ctx, Palette p, int fillColor, float radiusDp, float strokeDp, int strokeColor) {
        GradientDrawable base = roundedRect(ctx, fillColor, radiusDp, strokeDp, strokeColor);
        int ripple = p.isDark ? 0x26FFFFFF : 0x16000000;
        return new RippleDrawable(ColorStateList.valueOf(ripple), base, null);
    }

    public static Drawable primaryGradient(Context ctx, Palette p, float radiusDp) {
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{p.brandStart, p.brandEnd}
        );
        gd.setCornerRadius(dp(ctx, radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), gd, null);
    }

    public static LinearLayout createCard(Context ctx, Palette p) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedRect(ctx, p.card, 18, 1, p.cardBorder));
        card.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 12);
        card.setLayoutParams(lp);
        return card;
    }

    public static View createDivider(Context ctx, Palette p) {
        View v = new View(ctx);
        v.setBackgroundColor(p.divider);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.7f))));
        return v;
    }

    public static void enableSmoothLayoutTransition(ViewGroup group) {
        LayoutTransition lt = new LayoutTransition();
        lt.setDuration(220L);
        lt.enableTransitionType(LayoutTransition.CHANGING);
        group.setLayoutTransition(lt);
    }

    public static void attachPressBounce(final View view, final float pressedScale) {
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

    public static TextView createPrimaryButton(Context ctx, Palette p, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(dp(ctx, 18), dp(ctx, 11), dp(ctx, 18), dp(ctx, 11));
        tv.setBackground(primaryGradient(ctx, p, 99));
        tv.setClickable(true);
        tv.setFocusable(true);
        attachPressBounce(tv, 0.97f);
        return tv;
    }

    public static TextView createSecondaryButton(Context ctx, Palette p, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(p.primaryText);
        tv.setTextSize(13.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10));
        tv.setBackground(rippleRect(ctx, p, p.subSurface, 99, 1, p.cardBorder));
        tv.setClickable(true);
        tv.setFocusable(true);
        attachPressBounce(tv, 0.97f);
        return tv;
    }

    public static TextView createPillBadge(Context ctx, String text, int textColor, int bgColor) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(11f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
        tv.setBackground(roundedRect(ctx, bgColor, 99, 0, 0));
        return tv;
    }

    public static EditText createStyledInput(Context ctx, Palette p, String hint, int inputType) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setHintTextColor(p.tertiaryText);
        et.setTextColor(p.primaryText);
        et.setTextSize(14f);
        et.setInputType(inputType);
        et.setBackground(roundedRect(ctx, p.subSurface, 12, 1, p.cardBorder));
        et.setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11));
        return et;
    }

    public static Switch createStyledSwitch(Context ctx, Palette p) {
        Switch sw = new Switch(ctx);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{}
        };
        int[] thumbColors = new int[]{
                Color.WHITE,
                p.isDark ? Color.parseColor("#CBD5E1") : Color.WHITE
        };
        int[] trackColors = new int[]{
                p.brandStart,
                p.isDark ? Color.parseColor("#3B4252") : Color.parseColor("#D6CFC6")
        };
        sw.setThumbTintList(new ColorStateList(states, thumbColors));
        sw.setTrackTintList(new ColorStateList(states, trackColors));
        return sw;
    }

    public static TextView createSectionLabel(Context ctx, Palette p, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(p.secondaryText);
        tv.setTextSize(12.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(ctx, 4);
        lp.topMargin = dp(ctx, 6);
        lp.bottomMargin = dp(ctx, 8);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ── Custom Animated Bottom Sheet & Modal Engine ──────────────────────────

    private static final class SheetHost {
        final Dialog dialog;
        final FrameLayout root;
        final View backdrop;
        final FrameLayout container;
        final boolean isSheet;
        final AtomicBoolean dismissing = new AtomicBoolean(false);
        final DialogHandle handle;
        Runnable onDismissHook;

        SheetHost(Activity activity, boolean cancelable, boolean isSheet, boolean adjustResize) {
            this.isSheet = isSheet;
            dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCancelable(cancelable);
            dialog.setCanceledOnTouchOutside(cancelable);

            Window win = dialog.getWindow();
            if (win != null) {
                win.setBackgroundDrawableResource(android.R.color.transparent);
                win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                win.setDimAmount(0f);
                win.setSoftInputMode(adjustResize
                        ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                        : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    win.setDecorFitsSystemWindows(true);
                }
            }

            root = new FrameLayout(activity);
            backdrop = new View(activity);
            backdrop.setBackgroundColor(0x8A000000);
            backdrop.setAlpha(0f);
            root.addView(backdrop, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            container = new FrameLayout(activity);
            root.addView(container, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            handle = new DialogHandle() {
                @Override
                public void dismiss() {
                    dismissAnimated();
                }

                @Override
                public boolean isShowing() {
                    return dialog.isShowing() && !dismissing.get();
                }
            };

            if (cancelable) {
                backdrop.setOnClickListener(v -> dismissAnimated());
                container.setOnClickListener(v -> dismissAnimated());
            }
            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    if (cancelable) dismissAnimated();
                    return true;
                }
                return false;
            });
            dialog.setContentView(root);
        }

        void setContent(View child, FrameLayout.LayoutParams lp) {
            child.setClickable(true);
            container.removeAllViews();
            container.addView(child, lp);
        }

        void show() {
            if (dialog.isShowing()) return;
            dialog.show();
            Window win = dialog.getWindow();
            if (win != null) {
                win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }
            final View child = container.getChildCount() > 0 ? container.getChildAt(0) : null;
            if (child != null) {
                child.setAlpha(0f);
                child.post(() -> {
                    AnimatorSet set = new AnimatorSet();
                    ObjectAnimator bgAnim = ObjectAnimator.ofFloat(backdrop, View.ALPHA, 0f, 1f);
                    if (isSheet) {
                        float dy = Math.max(child.getHeight(), 400);
                        child.setTranslationY(dy);
                        child.setAlpha(1f);
                        ObjectAnimator trans = ObjectAnimator.ofFloat(child, View.TRANSLATION_Y, dy, 0f);
                        trans.setInterpolator(new DecelerateInterpolator(2.2f));
                        set.playTogether(bgAnim, trans);
                        set.setDuration(260);
                    } else {
                        child.setScaleX(0.93f);
                        child.setScaleY(0.93f);
                        ObjectAnimator sx = ObjectAnimator.ofFloat(child, View.SCALE_X, 0.93f, 1f);
                        ObjectAnimator sy = ObjectAnimator.ofFloat(child, View.SCALE_Y, 0.93f, 1f);
                        ObjectAnimator al = ObjectAnimator.ofFloat(child, View.ALPHA, 0f, 1f);
                        set.playTogether(bgAnim, sx, sy, al);
                        set.setInterpolator(new DecelerateInterpolator(2f));
                        set.setDuration(210);
                    }
                    set.start();
                });
            }
        }

        void dismissAnimated() {
            if (!dismissing.compareAndSet(false, true)) return;
            if (onDismissHook != null) {
                try {
                    onDismissHook.run();
                } catch (Throwable ignored) {
                }
            }
            final View child = container.getChildCount() > 0 ? container.getChildAt(0) : null;
            if (child == null || !dialog.isShowing()) {
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) {
                }
                return;
            }
            AnimatorSet set = new AnimatorSet();
            ObjectAnimator bgAnim = ObjectAnimator.ofFloat(backdrop, View.ALPHA, backdrop.getAlpha(), 0f);
            if (isSheet) {
                float dy = Math.max(child.getHeight(), 360);
                ObjectAnimator trans = ObjectAnimator.ofFloat(child, View.TRANSLATION_Y, child.getTranslationY(), dy);
                set.playTogether(bgAnim, trans);
                set.setDuration(180);
            } else {
                ObjectAnimator al = ObjectAnimator.ofFloat(child, View.ALPHA, child.getAlpha(), 0f);
                ObjectAnimator sx = ObjectAnimator.ofFloat(child, View.SCALE_X, child.getScaleX(), 0.94f);
                ObjectAnimator sy = ObjectAnimator.ofFloat(child, View.SCALE_Y, child.getScaleY(), 0.94f);
                set.playTogether(bgAnim, al, sx, sy);
                set.setDuration(150);
            }
            set.setInterpolator(new AccelerateInterpolator(1.6f));
            set.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignored) {
                    }
                }
            });
            set.start();
        }
    }

    private static LinearLayout createSheetContainer(Context ctx, Palette p) {
        LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(p.card);
        float r = dp(ctx, 24);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 20));

        // Drag handle pill
        View drag = new View(ctx);
        drag.setBackground(roundedRect(ctx, p.isDark ? 0x44FFFFFF : 0x26000000, 99, 0, 0));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 4));
        dlp.gravity = Gravity.CENTER_HORIZONTAL;
        dlp.bottomMargin = dp(ctx, 12);
        sheet.addView(drag, dlp);
        return sheet;
    }

    public static DialogHandle showCustomSheet(
            Activity activity,
            Palette p,
            String title,
            String subtitle,
            View body,
            boolean scrollable,
            Runnable onDismiss
    ) {
        if (activity == null || activity.isFinishing()) return NO_OP_HANDLE;
        SheetHost host = new SheetHost(activity, true, true, true);
        host.onDismissHook = onDismiss;
        LinearLayout sheet = createSheetContainer(activity, p);

        if (title != null && !title.isEmpty()) {
            TextView tv = new TextView(activity);
            tv.setText(title);
            tv.setTextColor(p.primaryText);
            tv.setTextSize(17.5f);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            sheet.addView(tv);
        }
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sv = new TextView(activity);
            sv.setText(subtitle);
            sv.setTextColor(p.secondaryText);
            sv.setTextSize(12.5f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(activity, 3);
            sheet.addView(sv, slp);
        }

        if (body != null) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(activity, 14);
            if (scrollable) {
                MaxHeightScrollView sv = new MaxHeightScrollView(activity);
                sv.setMaxHeightPx(Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.72f));
                sv.setVerticalScrollBarEnabled(false);
                sv.addView(body, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                sheet.addView(sv, blp);
            } else {
                sheet.addView(body, blp);
            }
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        host.setContent(sheet, lp);
        host.show();
        return host.handle;
    }

    public static DialogHandle showMessageModal(
            Activity activity,
            Palette p,
            String title,
            String message,
            String positiveText,
            Runnable onPositive,
            String negativeText,
            Runnable onNegative
    ) {
        return showMessageModal(activity, p, title, message, positiveText, onPositive, negativeText, onNegative, null, null);
    }

    public static DialogHandle showMessageModal(
            Activity activity,
            Palette p,
            String title,
            String message,
            String positiveText,
            Runnable onPositive,
            String negativeText,
            Runnable onNegative,
            String neutralText,
            Runnable onNeutral
    ) {
        if (activity == null || activity.isFinishing()) return NO_OP_HANDLE;
        SheetHost host = new SheetHost(activity, true, false, false);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedRect(activity, p.card, 22, 1, p.cardBorder));
        card.setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 18));

        if (title != null && !title.isEmpty()) {
            TextView tv = new TextView(activity);
            tv.setText(title);
            tv.setTextColor(p.primaryText);
            tv.setTextSize(17f);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(tv);
        }
        if (message != null && !message.isEmpty()) {
            MaxHeightScrollView sv = new MaxHeightScrollView(activity);
            sv.setMaxHeightPx(Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.48f));
            sv.setVerticalScrollBarEnabled(false);

            TextView mv = new TextView(activity);
            mv.setText(message);
            mv.setTextColor(p.secondaryText);
            mv.setTextSize(14f);
            mv.setLineSpacing(0f, 1.4f);
            sv.addView(mv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(activity, 10);
            card.addView(sv, mlp);
        }

        LinearLayout btns = new LinearLayout(activity);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(activity, 20);
        card.addView(btns, blp);

        if (neutralText != null && !neutralText.isEmpty()) {
            TextView neu = new TextView(activity);
            neu.setText(neutralText);
            neu.setTextColor(p.brandText);
            neu.setTextSize(13f);
            neu.setTypeface(Typeface.DEFAULT_BOLD);
            neu.setPadding(dp(activity, 6), dp(activity, 10), dp(activity, 10), dp(activity, 10));
            neu.setClickable(true);
            neu.setFocusable(true);
            neu.setOnClickListener(v -> {
                host.handle.dismiss();
                if (onNeutral != null) onNeutral.run();
            });
            LinearLayout.LayoutParams neLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            btns.addView(neu, neLp);
        }

        if (negativeText != null && !negativeText.isEmpty()) {
            TextView neg = createSecondaryButton(activity, p, negativeText);
            neg.setOnClickListener(v -> {
                host.handle.dismiss();
                if (onNegative != null) onNegative.run();
            });
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.rightMargin = dp(activity, 10);
            btns.addView(neg, nlp);
        }
        if (positiveText != null && !positiveText.isEmpty()) {
            TextView pos = createPrimaryButton(activity, p, positiveText);
            pos.setOnClickListener(v -> {
                host.handle.dismiss();
                if (onPositive != null) onPositive.run();
            });
            btns.addView(pos);
        }

        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int w = Math.min(dp(activity, 380), screenW - dp(activity, 48));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        host.setContent(card, lp);
        host.show();
        return host.handle;
    }

    public static final class ProgressDialogHandle implements DialogHandle {
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        private final DialogHandle delegate;
        private final SleekProgressBar progressBar;
        private final TextView statusView;
        private final TextView percentPill;
        private final TextView detailView;

        private ProgressDialogHandle(
                DialogHandle delegate,
                SleekProgressBar progressBar,
                TextView statusView,
                TextView percentPill,
                TextView detailView
        ) {
            this.delegate = delegate;
            this.progressBar = progressBar;
            this.statusView = statusView;
            this.percentPill = percentPill;
            this.detailView = detailView;
        }

        public void updateProgress(
                final int percent,
                final boolean indeterminate,
                final String statusText,
                final String detailText
        ) {
            Runnable apply = () -> {
                if (delegate == null || !delegate.isShowing()) {
                    return;
                }
                int clamped = Math.max(0, Math.min(100, percent));
                if (progressBar != null) {
                    progressBar.setIndeterminate(indeterminate);
                    if (!indeterminate) {
                        progressBar.setProgress(clamped);
                    }
                }
                if (percentPill != null) {
                    percentPill.setText(indeterminate ? "..." : (clamped + "%"));
                }
                if (statusView != null && statusText != null) {
                    statusView.setText(statusText);
                }
                if (detailView != null && detailText != null) {
                    detailView.setText(detailText);
                    detailView.setVisibility(detailText.isEmpty() ? View.GONE : View.VISIBLE);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                apply.run();
            } else {
                MAIN.post(apply);
            }
        }

        @Override
        public void dismiss() {
            if (progressBar != null) {
                progressBar.stopAnimations();
            }
            if (delegate != null) {
                delegate.dismiss();
            }
        }

        @Override
        public boolean isShowing() {
            return delegate != null && delegate.isShowing();
        }
    }

    public static ProgressDialogHandle showProgressModal(
            Activity activity,
            Palette p,
            String title,
            String initialStatus,
            String initialDetail,
            Runnable onCancel
    ) {
        if (activity == null || activity.isFinishing() || p == null) {
            return new ProgressDialogHandle(NO_OP_HANDLE, null, null, null, null);
        }
        SheetHost host = new SheetHost(activity, false, false, false);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedRect(activity, p.card, 22, 1, p.cardBorder));
        card.setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 18));

        if (title != null && !title.isEmpty()) {
            TextView tv = new TextView(activity);
            tv.setText(title);
            tv.setTextColor(p.primaryText);
            tv.setTextSize(17f);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(tv);
        }

        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams trLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        trLp.topMargin = dp(activity, 12);

        TextView statusTv = new TextView(activity);
        statusTv.setText(initialStatus != null ? initialStatus : "");
        statusTv.setTextSize(13.5f);
        statusTv.setTextColor(p.secondaryText);
        statusTv.setMaxLines(2);
        statusTv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stLp.rightMargin = dp(activity, 10);
        topRow.addView(statusTv, stLp);

        TextView percentPill = new TextView(activity);
        percentPill.setText("...");
        percentPill.setTextSize(12f);
        percentPill.setTypeface(Typeface.DEFAULT_BOLD);
        percentPill.setTextColor(p.brandText);
        percentPill.setFontFeatureSettings("tnum");
        percentPill.setGravity(Gravity.CENTER);
        percentPill.setMinWidth(dp(activity, 46));
        percentPill.setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4));
        percentPill.setBackground(roundedRect(activity, p.brandTintBg, 99, 1, p.brandStart));
        topRow.addView(percentPill);
        card.addView(topRow, trLp);

        SleekProgressBar progressBar = new SleekProgressBar(activity, p.trackBg, p.brandStart, p.brandEnd);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 8));
        pbLp.topMargin = dp(activity, 14);
        card.addView(progressBar, pbLp);

        TextView detailTv = new TextView(activity);
        String detailStr = initialDetail != null ? initialDetail : "";
        detailTv.setText(detailStr);
        detailTv.setTextSize(12f);
        detailTv.setTextColor(p.tertiaryText);
        detailTv.setFontFeatureSettings("tnum");
        detailTv.setVisibility(detailStr.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dtLp.topMargin = dp(activity, 8);
        card.addView(detailTv, dtLp);

        if (onCancel != null) {
            LinearLayout btns = new LinearLayout(activity);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setGravity(Gravity.END);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(activity, 18);
            TextView cancelBtn = createSecondaryButton(activity, p, "取消下载");
            cancelBtn.setOnClickListener(v -> {
                host.handle.dismiss();
                onCancel.run();
            });
            btns.addView(cancelBtn);
            card.addView(btns, blp);
        }

        host.onDismissHook = progressBar::stopAnimations;
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int w = Math.min(dp(activity, 380), screenW - dp(activity, 48));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        host.setContent(card, lp);
        host.show();
        return new ProgressDialogHandle(host.handle, progressBar, statusTv, percentPill, detailTv);
    }

    private static final class SleekProgressBar extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF fillRect = new RectF();
        private final int startColor;
        private final int endColor;
        private boolean indeterminate = true;
        private float progressFraction = 0f;
        private float indetPhase = 0f;
        private ValueAnimator indetAnim;

        SleekProgressBar(Context ctx, int trackColor, int startColor, int endColor) {
            super(ctx);
            this.startColor = startColor;
            this.endColor = endColor;
            trackPaint.setColor(trackColor);
            startIndeterminateAnim();
        }

        void setIndeterminate(boolean value) {
            if (this.indeterminate == value) return;
            this.indeterminate = value;
            if (value) {
                startIndeterminateAnim();
            } else {
                stopAnimations();
            }
            invalidate();
        }

        void setProgress(int percent) {
            this.progressFraction = Math.max(0f, Math.min(1f, percent / 100f));
            invalidate();
        }

        private void startIndeterminateAnim() {
            stopAnimations();
            indetAnim = ValueAnimator.ofFloat(0f, 1f);
            indetAnim.setDuration(1100L);
            indetAnim.setRepeatCount(ValueAnimator.INFINITE);
            indetAnim.setInterpolator(new LinearInterpolator());
            indetAnim.addUpdateListener(a -> {
                indetPhase = (float) a.getAnimatedValue();
                invalidate();
            });
            indetAnim.start();
        }

        void stopAnimations() {
            if (indetAnim != null) {
                indetAnim.cancel();
                indetAnim = null;
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopAnimations();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float r = h / 2f;
            trackRect.set(0f, 0f, w, h);
            canvas.drawRoundRect(trackRect, r, r, trackPaint);

            fillPaint.setShader(new LinearGradient(0f, 0f, w, 0f, startColor, endColor, Shader.TileMode.CLAMP));
            if (indeterminate) {
                float segW = w * 0.36f;
                float left = (w + segW) * indetPhase - segW;
                float right = left + segW;
                fillRect.set(Math.max(0f, left), 0f, Math.min(w, right), h);
                if (fillRect.width() > 2f) {
                    canvas.drawRoundRect(fillRect, r, r, fillPaint);
                }
            } else if (progressFraction > 0f) {
                float fw = Math.max(h, w * progressFraction);
                fillRect.set(0f, 0f, Math.min(w, fw), h);
                canvas.drawRoundRect(fillRect, r, r, fillPaint);
            }
        }
    }

    public static DialogHandle showInputModal(
            Activity activity,
            Palette p,
            String title,
            String message,
            String initialValue,
            String hint,
            int inputType,
            String positiveText,
            InputListener onConfirm
    ) {
        if (activity == null || activity.isFinishing()) return NO_OP_HANDLE;
        SheetHost host = new SheetHost(activity, true, false, true);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundedRect(activity, p.card, 22, 1, p.cardBorder));
        card.setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 18));

        TextView tv = new TextView(activity);
        tv.setText(title);
        tv.setTextColor(p.primaryText);
        tv.setTextSize(17f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(tv);

        if (message != null && !message.isEmpty()) {
            TextView mv = new TextView(activity);
            mv.setText(message);
            mv.setTextColor(p.secondaryText);
            mv.setTextSize(13f);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.topMargin = dp(activity, 6);
            card.addView(mv, mlp);
        }

        EditText et = createStyledInput(activity, p, hint, inputType);
        if (initialValue != null) {
            et.setText(initialValue);
            et.setSelection(initialValue.length());
        }
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(activity, 12);
        card.addView(et, elp);

        LinearLayout btns = new LinearLayout(activity);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.END);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(activity, 18);
        card.addView(btns, blp);

        TextView cancel = createSecondaryButton(activity, p, "取消");
        cancel.setOnClickListener(v -> host.handle.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.rightMargin = dp(activity, 10);
        btns.addView(cancel, clp);

        TextView ok = createPrimaryButton(activity, p, positiveText != null ? positiveText : "确定");
        ok.setOnClickListener(v -> {
            String text = et.getText() != null ? et.getText().toString().trim() : "";
            if (onConfirm == null || onConfirm.onConfirm(text)) {
                host.handle.dismiss();
            }
        });
        btns.addView(ok);

        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        int w = Math.min(dp(activity, 380), screenW - dp(activity, 48));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        host.setContent(card, lp);
        host.show();
        return host.handle;
    }

    public static DialogHandle showOptionSheet(
            Activity activity,
            Palette p,
            String title,
            String subtitle,
            List<String> labels,
            List<String> descriptions,
            int selectedIndex,
            OptionListener onSelect
    ) {
        if (activity == null || activity.isFinishing()) return NO_OP_HANDLE;
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);

        final DialogHandle[] holder = new DialogHandle[1];
        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            boolean sel = (i == selectedIndex);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(activity, 14), dp(activity, 12), dp(activity, 14), dp(activity, 12));
            row.setBackground(rippleRect(
                    activity, p,
                    sel ? p.brandTintBg : p.subSurface,
                    14,
                    sel ? 1.2f : 0f,
                    sel ? p.brandStart : 0
            ));
            row.setClickable(true);
            row.setFocusable(true);

            LinearLayout textCol = new LinearLayout(activity);
            textCol.setOrientation(LinearLayout.VERTICAL);
            TextView tv = new TextView(activity);
            tv.setText(labels.get(i));
            tv.setTextColor(sel ? p.brandText : p.primaryText);
            tv.setTextSize(14.5f);
            tv.setTypeface(sel ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            textCol.addView(tv);

            if (descriptions != null && i < descriptions.size() && descriptions.get(i) != null && !descriptions.get(i).isEmpty()) {
                TextView dv = new TextView(activity);
                dv.setText(descriptions.get(i));
                dv.setTextColor(p.secondaryText);
                dv.setTextSize(12f);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                dlp.topMargin = dp(activity, 2);
                textCol.addView(dv, dlp);
            }
            row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (sel) {
                TextView chk = new TextView(activity);
                chk.setText("✓");
                chk.setTextColor(p.brandStart);
                chk.setTextSize(16f);
                chk.setTypeface(Typeface.DEFAULT_BOLD);
                row.addView(chk);
            }

            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                if (onSelect != null) onSelect.onSelect(idx);
            });

            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) rlp.topMargin = dp(activity, 8);
            list.addView(row, rlp);
        }

        holder[0] = showCustomSheet(activity, p, title, subtitle, list, true, null);
        return holder[0];
    }

    public static DialogHandle showTimePickerSheet(
            Activity activity,
            Palette p,
            int initialHour,
            int initialMinute,
            TimeListener onSelect
    ) {
        if (activity == null || activity.isFinishing()) return NO_OP_HANDLE;
        final int initH = Math.max(0, Math.min(23, initialHour));
        final int initM = Math.max(0, Math.min(59, initialMinute));

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        // Unified vertical wheel picker container (iOS / ColorOS style)
        FrameLayout wheelCard = new FrameLayout(activity);
        wheelCard.setBackground(roundedRect(activity, p.subSurface, 18, 1, p.cardBorder));
        int wheelHeightPx = dp(activity, 210);
        int itemHeightPx = dp(activity, 42);
        LinearLayout.LayoutParams wcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, wheelHeightPx);

        // Center highlighted selection band spanning both wheels
        View centerBand = new View(activity);
        centerBand.setBackground(roundedRect(activity, p.brandTintBg, 13, 1, p.brandStart));
        FrameLayout.LayoutParams cbLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, itemHeightPx, Gravity.CENTER);
        cbLp.leftMargin = dp(activity, 12);
        cbLp.rightMargin = dp(activity, 12);
        wheelCard.addView(centerBand, cbLp);

        LinearLayout wheelsRow = new LinearLayout(activity);
        wheelsRow.setOrientation(LinearLayout.HORIZONTAL);
        wheelsRow.setGravity(Gravity.CENTER_VERTICAL);
        wheelsRow.setPadding(dp(activity, 18), 0, dp(activity, 18), 0);

        WheelPickerView hourWheel = new WheelPickerView(activity, p, 24, itemHeightPx, "时", null);
        hourWheel.scrollToValue(initH, false);
        wheelsRow.addView(hourWheel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView colonTv = new TextView(activity);
        colonTv.setText(":");
        colonTv.setTextSize(22f);
        colonTv.setTypeface(Typeface.DEFAULT_BOLD);
        colonTv.setTextColor(p.brandText);
        colonTv.setGravity(Gravity.CENTER);
        wheelsRow.addView(colonTv, new LinearLayout.LayoutParams(dp(activity, 22), ViewGroup.LayoutParams.MATCH_PARENT));

        WheelPickerView minWheel = new WheelPickerView(activity, p, 60, itemHeightPx, "分", null);
        minWheel.scrollToValue(initM, false);
        wheelsRow.addView(minWheel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        wheelCard.addView(wheelsRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        body.addView(wheelCard, wcLp);

        final DialogHandle[] holder = new DialogHandle[1];
        TextView confirm = createPrimaryButton(activity, p, "保存定时时间");
        confirm.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
            if (onSelect != null) onSelect.onSelect(hourWheel.getCurrentValue(), minWheel.getCurrentValue());
        });
        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46));
        confirmLp.topMargin = dp(activity, 16);
        body.addView(confirm, confirmLp);

        holder[0] = showCustomSheet(activity, p, "设置每日定时执行时间", "上下滑动选择小时和分钟", body, false, null);
        return holder[0];
    }

    private interface OnWheelValueListener {
        void onValueChanged(int value);
    }

    /**
     * Smooth infinite wrap-around vertical wheel picker with OverScroller fling physics,
     * magnetic item snapping, tap-to-scroll, haptic ticks, and depth scale/alpha falloff.
     */
    private static final class WheelPickerView extends View {
        private final Palette p;
        private final int count;
        private final int itemHeight;
        private final String unitSuffix;
        private final OnWheelValueListener listener;
        private final Scroller scroller;
        private final ArgbEvaluator colorEval = new ArgbEvaluator();
        private final Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float maxTextSizePx;
        private final float minTextSizePx;
        private final float unitTextSizePx;
        private final int touchSlop;
        private final int minFlingVelocity;
        private final int maxFlingVelocity;

        private VelocityTracker velocityTracker;
        private float scrollOffsetPx = 0f;
        private float downY = 0f;
        private float lastY = 0f;
        private boolean isDragging = false;
        private int lastReportedValue = -1;

        WheelPickerView(
                Context ctx,
                Palette p,
                int count,
                int itemHeightPx,
                String unitSuffix,
                OnWheelValueListener listener
        ) {
            super(ctx);
            this.p = p;
            this.count = Math.max(1, count);
            this.itemHeight = Math.max(1, itemHeightPx);
            this.unitSuffix = unitSuffix != null ? unitSuffix : "";
            this.listener = listener;
            this.scroller = new Scroller(ctx, new DecelerateInterpolator(2.0f));

            ViewConfiguration vc = ViewConfiguration.get(ctx);
            this.touchSlop = vc.getScaledTouchSlop();
            this.minFlingVelocity = vc.getScaledMinimumFlingVelocity();
            this.maxFlingVelocity = vc.getScaledMaximumFlingVelocity();

            float sp = ctx.getResources().getDisplayMetrics().scaledDensity;
            this.maxTextSizePx = 22f * sp;
            this.minTextSizePx = 15f * sp;
            this.unitTextSizePx = 12f * sp;

            numPaint.setTextAlign(Paint.Align.CENTER);
            numPaint.setTypeface(Typeface.DEFAULT_BOLD);
            numPaint.setFontFeatureSettings("tnum");

            unitPaint.setTextAlign(Paint.Align.LEFT);
            unitPaint.setTypeface(Typeface.DEFAULT_BOLD);
            unitPaint.setTextSize(unitTextSizePx);
            unitPaint.setColor(p.brandText);
        }

        private static int wrap(int idx, int n) {
            int r = idx % n;
            return r < 0 ? r + n : r;
        }

        int getCurrentValue() {
            return wrap(Math.round(scrollOffsetPx / itemHeight), count);
        }

        void scrollToValue(int targetVal, boolean animate) {
            int wrappedTarget = wrap(targetVal, count);
            if (!animate) {
                scroller.abortAnimation();
                scrollOffsetPx = wrappedTarget * (float) itemHeight;
                lastReportedValue = wrappedTarget;
                invalidate();
                return;
            }
            scroller.abortAnimation();
            int curSlot = Math.round(scrollOffsetPx / itemHeight);
            int curVal = wrap(curSlot, count);
            int delta = wrappedTarget - curVal;
            if (delta > count / 2) delta -= count;
            if (delta < -count / 2) delta += count;
            int startY = Math.round(scrollOffsetPx);
            int targetY = (curSlot + delta) * itemHeight;
            scroller.startScroll(0, startY, 0, targetY - startY, 300);
            postInvalidateOnAnimation();
        }

        private void checkValueTick(boolean haptic) {
            int cur = getCurrentValue();
            if (cur != lastReportedValue) {
                lastReportedValue = cur;
                if (haptic) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
                if (listener != null) {
                    listener.onValueChanged(cur);
                }
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (velocityTracker == null) {
                velocityTracker = VelocityTracker.obtain();
            }
            velocityTracker.addMovement(event);

            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    scroller.abortAnimation();
                    downY = event.getY();
                    lastY = downY;
                    isDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float y = event.getY();
                    if (!isDragging && Math.abs(y - downY) > touchSlop) {
                        isDragging = true;
                    }
                    float dy = y - lastY;
                    lastY = y;
                    scrollOffsetPx -= dy;
                    checkValueTick(true);
                    invalidate();
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(false);
                    }
                    int curY = Math.round(scrollOffsetPx);
                    if (!isDragging && action == MotionEvent.ACTION_UP) {
                        // Tap on an upper or lower row smoothly scrolls it to center
                        float centerY = getHeight() / 2f;
                        int stepDelta = Math.round((event.getY() - centerY) / itemHeight);
                        int targetSlot = Math.round(scrollOffsetPx / itemHeight) + stepDelta;
                        int targetY = targetSlot * itemHeight;
                        scroller.startScroll(0, curY, 0, targetY - curY, 240);
                    } else {
                        velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                        int vy = (int) velocityTracker.getYVelocity();
                        if (Math.abs(vy) >= minFlingVelocity) {
                            scroller.fling(
                                    0, curY,
                                    0, -vy,
                                    0, 0,
                                    Integer.MIN_VALUE / 4, Integer.MAX_VALUE / 4
                            );
                            int rawFinalY = scroller.getFinalY();
                            int snappedFinalY = Math.round((float) rawFinalY / itemHeight) * itemHeight;
                            scroller.setFinalY(snappedFinalY);
                        } else {
                            int snappedY = Math.round(scrollOffsetPx / itemHeight) * itemHeight;
                            scroller.startScroll(0, curY, 0, snappedY - curY, 180);
                        }
                    }
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    postInvalidateOnAnimation();
                    return true;
                }
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public void computeScroll() {
            if (scroller.computeScrollOffset()) {
                scrollOffsetPx = scroller.getCurrY();
                checkValueTick(true);
                postInvalidateOnAnimation();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            float centerY = h / 2f;
            float numCenterX = unitSuffix.isEmpty() ? (w / 2f) : (w / 2f - dp(getContext(), 7));
            int baseSlot = (int) Math.floor(scrollOffsetPx / itemHeight);
            float maxDist = itemHeight * 2.4f;

            for (int offset = -3; offset <= 4; offset++) {
                int slot = baseSlot + offset;
                float itemCenterY = centerY + (slot * (float) itemHeight - scrollOffsetPx);
                float dist = Math.abs(itemCenterY - centerY);
                if (dist > maxDist + itemHeight * 0.5f) continue;

                float t = Math.min(1f, dist / maxDist); // 0 at center, 1 at edge
                float ease = 1f - t;
                float textSize = minTextSizePx + (maxTextSizePx - minTextSizePx) * (ease * ease);
                numPaint.setTextSize(textSize);
                numPaint.setTypeface(t < 0.25f ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

                int baseColor = (int) colorEval.evaluate(Math.min(1f, t * 1.4f), p.brandText, p.secondaryText);
                int alpha = Math.round((0.18f + 0.82f * ease * ease) * 255f);
                numPaint.setColor((baseColor & 0x00FFFFFF) | (Math.max(25, Math.min(255, alpha)) << 24));

                Paint.FontMetrics fm = numPaint.getFontMetrics();
                float baseline = itemCenterY - (fm.ascent + fm.descent) / 2f;
                String text = String.format(Locale.US, "%02d", wrap(slot, count));
                canvas.drawText(text, numCenterX, baseline, numPaint);
            }

            if (!unitSuffix.isEmpty()) {
                Paint.FontMetrics ufm = unitPaint.getFontMetrics();
                float uBaseline = centerY - (ufm.ascent + ufm.descent) / 2f + dp(getContext(), 1);
                float unitX = numCenterX + maxTextSizePx * 0.72f;
                canvas.drawText(unitSuffix, unitX, uBaseline, unitPaint);
            }
        }
    }

    public static DialogHandle showMultiSelectTargetSheet(
            Activity activity,
            Palette p,
            String title,
            String subtitle,
            List<TargetOption> options,
            boolean showWeights,
            AddCustomUidListener onAddCustom,
            MultiTargetListener onConfirm
    ) {
        if (activity == null || activity.isFinishing()) return NO_OP_HANDLE;
        final List<TargetOption> copy = new ArrayList<>();
        boolean anySf = false;
        if (options != null) {
            for (TargetOption o : options) {
                copy.add(new TargetOption(o.pid, o.title, o.subtitle, o.isSuperFan, o.selected, o.weight));
                if (o.isSuperFan) anySf = true;
            }
        }

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        final DialogHandle[] holder = new DialogHandle[1];
        final Runnable[] refreshRef = new Runnable[1];

        // Quick chips row: 全选 / 仅超粉 / 清空 / + 自定义 UID
        HorizontalScrollView chipScroll = new HorizontalScrollView(activity);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(activity);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnAll = createSecondaryButton(activity, p, "全选");
        btnAll.setTextSize(12f);
        btnAll.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
        btnAll.setOnClickListener(v -> {
            for (TargetOption o : copy) o.selected = true;
            refreshRef[0].run();
        });
        chipRow.addView(btnAll);

        if (anySf) {
            TextView btnSf = createSecondaryButton(activity, p, "仅超粉");
            btnSf.setTextSize(12f);
            btnSf.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
            btnSf.setOnClickListener(v -> {
                for (TargetOption o : copy) o.selected = o.isSuperFan;
                refreshRef[0].run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(activity, 8);
            chipRow.addView(btnSf, lp);
        }

        TextView btnClear = createSecondaryButton(activity, p, "清空");
        btnClear.setTextSize(12f);
        btnClear.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
        btnClear.setOnClickListener(v -> {
            for (TargetOption o : copy) o.selected = false;
            refreshRef[0].run();
        });
        LinearLayout.LayoutParams clrLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clrLp.leftMargin = dp(activity, 8);
        chipRow.addView(btnClear, clrLp);

        if (onAddCustom != null) {
            TextView btnCustom = createPillBadge(activity, "+ 自定义 UID", p.brandText, p.brandTintBg);
            btnCustom.setTextSize(12f);
            btnCustom.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 12), dp(activity, 6));
            btnCustom.setOnClickListener(v -> onAddCustom.onRequestAddCustom(holder[0]));
            LinearLayout.LayoutParams cusLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cusLp.leftMargin = dp(activity, 8);
            chipRow.addView(btnCustom, cusLp);
        }
        chipScroll.addView(chipRow);
        body.addView(chipScroll);

        // Scrollable target list
        MaxHeightScrollView listScroll = new MaxHeightScrollView(activity);
        listScroll.setMaxHeightPx(Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.44f));
        listScroll.setVerticalScrollBarEnabled(false);
        LinearLayout listCol = new LinearLayout(activity);
        listCol.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(listCol);

        LinearLayout.LayoutParams lsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lsLp.topMargin = dp(activity, 12);
        body.addView(listScroll, lsLp);

        TextView confirmBtn = createPrimaryButton(activity, p, "保存赠送目标");
        LinearLayout.LayoutParams cfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46));
        cfLp.topMargin = dp(activity, 14);
        body.addView(confirmBtn, cfLp);

        refreshRef[0] = () -> {
            listCol.removeAllViews();
            int selCount = 0;
            if (copy.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText("暂无可选粉丝牌，请先同步粉丝牌或点击「+ 自定义 UID」添加。");
                empty.setTextColor(p.secondaryText);
                empty.setTextSize(13f);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(dp(activity, 12), dp(activity, 24), dp(activity, 12), dp(activity, 24));
                listCol.addView(empty);
            } else {
                for (int i = 0; i < copy.size(); i++) {
                    TargetOption opt = copy.get(i);
                    if (opt.selected) selCount++;

                    LinearLayout row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
                    row.setBackground(rippleRect(
                            activity, p,
                            opt.selected ? p.brandTintBg : p.subSurface,
                            13,
                            opt.selected ? 1.1f : 0f,
                            opt.selected ? p.brandStart : 0
                    ));

                    // Checkbox indicator
                    TextView box = new TextView(activity);
                    box.setText(opt.selected ? "✓" : "");
                    box.setTextColor(Color.WHITE);
                    box.setTextSize(12f);
                    box.setTypeface(Typeface.DEFAULT_BOLD);
                    box.setGravity(Gravity.CENTER);
                    box.setBackground(roundedRect(activity,
                            opt.selected ? p.brandStart : Color.TRANSPARENT,
                            6,
                            opt.selected ? 0 : 1.5f,
                            opt.selected ? 0 : p.tertiaryText));
                    LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20));
                    boxLp.rightMargin = dp(activity, 10);
                    row.addView(box, boxLp);

                    LinearLayout txt = new LinearLayout(activity);
                    txt.setOrientation(LinearLayout.VERTICAL);
                    TextView titleTv = new TextView(activity);
                    titleTv.setText(opt.title + (opt.isSuperFan ? " · 超粉" : ""));
                    titleTv.setTextColor(opt.selected ? p.brandText : p.primaryText);
                    titleTv.setTextSize(13.5f);
                    titleTv.setTypeface(opt.selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    titleTv.setSingleLine(true);
                    titleTv.setEllipsize(TextUtils.TruncateAt.END);
                    txt.addView(titleTv);

                    if (!opt.subtitle.isEmpty()) {
                        TextView subTv = new TextView(activity);
                        subTv.setText(opt.subtitle);
                        subTv.setTextColor(p.secondaryText);
                        subTv.setTextSize(11.5f);
                        subTv.setSingleLine(true);
                        subTv.setEllipsize(TextUtils.TruncateAt.END);
                        txt.addView(subTv);
                    }
                    row.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    if (showWeights && opt.selected) {
                        LinearLayout stepper = new LinearLayout(activity);
                        stepper.setOrientation(LinearLayout.HORIZONTAL);
                        stepper.setGravity(Gravity.CENTER_VERTICAL);
                        stepper.setBackground(roundedRect(activity, p.card, 99, 1, p.cardBorder));
                        stepper.setPadding(dp(activity, 4), dp(activity, 2), dp(activity, 4), dp(activity, 2));

                        TextView minus = new TextView(activity);
                        minus.setText("−");
                        minus.setTextColor(p.primaryText);
                        minus.setTextSize(14f);
                        minus.setTypeface(Typeface.DEFAULT_BOLD);
                        minus.setPadding(dp(activity, 7), dp(activity, 2), dp(activity, 7), dp(activity, 2));
                        minus.setOnClickListener(v -> {
                            opt.weight = Math.max(1, opt.weight - 1);
                            refreshRef[0].run();
                        });
                        stepper.addView(minus);

                        TextView wTv = new TextView(activity);
                        wTv.setText(opt.weight + "份");
                        wTv.setTextColor(p.brandText);
                        wTv.setTextSize(11.5f);
                        wTv.setTypeface(Typeface.DEFAULT_BOLD);
                        wTv.setPadding(dp(activity, 4), 0, dp(activity, 4), 0);
                        stepper.addView(wTv);

                        TextView plus = new TextView(activity);
                        plus.setText("+");
                        plus.setTextColor(p.primaryText);
                        plus.setTextSize(14f);
                        plus.setTypeface(Typeface.DEFAULT_BOLD);
                        plus.setPadding(dp(activity, 7), dp(activity, 2), dp(activity, 7), dp(activity, 2));
                        plus.setOnClickListener(v -> {
                            opt.weight = Math.min(20, opt.weight + 1);
                            refreshRef[0].run();
                        });
                        stepper.addView(plus);

                        row.addView(stepper);
                    }

                    row.setOnClickListener(v -> {
                        opt.selected = !opt.selected;
                        refreshRef[0].run();
                    });

                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (i > 0) rlp.topMargin = dp(activity, 7);
                    listCol.addView(row, rlp);
                }
            }
            confirmBtn.setText(selCount > 0 ? ("保存赠送目标（已选 " + selCount + " 个）") : "保存赠送目标（未选）");
        };

        confirmBtn.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
            if (onConfirm != null) onConfirm.onConfirm(copy);
        });

        refreshRef[0].run();
        holder[0] = showCustomSheet(activity, p, title, subtitle, body, false, null);
        return holder[0];
    }

    public static final class MaxHeightScrollView extends ScrollView {
        private int maxHeightPx = Integer.MAX_VALUE;

        public MaxHeightScrollView(Context context) {
            super(context);
        }

        public void setMaxHeightPx(int maxHeightPx) {
            this.maxHeightPx = maxHeightPx;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int maxSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
            super.onMeasure(widthMeasureSpec, maxSpec);
        }
    }

    private static final DialogHandle NO_OP_HANDLE = new DialogHandle() {
        @Override
        public void dismiss() {
        }

        @Override
        public boolean isShowing() {
            return false;
        }
    };
}
