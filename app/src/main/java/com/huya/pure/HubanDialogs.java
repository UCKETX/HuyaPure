package com.huya.pure;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Custom Dialog & Bottom-Sheet UI Kit for HuyaPure (虎伴).
 * Built purely on Android SDK Views and {@link Dialog} with zero native
 * system dialog builders and zero AndroidX dependencies.
 */
public final class HubanDialogs {

    private static final int BACKDROP_COLOR = 0x8C000000;
    private static final long MODAL_ENTER_MS = 230L;
    private static final long MODAL_EXIT_MS = 160L;
    private static final long SHEET_ENTER_MS = 290L;
    private static final long SHEET_EXIT_MS = 190L;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private HubanDialogs() {
    }

    // ── Public Palette & Callback Contracts ──────────────────────────────────

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
        public final int trackBg;

        public Palette(
                boolean isDark,
                int bg,
                int card,
                int cardBorder,
                int subSurface,
                int primaryText,
                int secondaryText,
                int tertiaryText,
                int divider,
                int brandStart,
                int brandEnd,
                int brandTintBg,
                int brandText,
                int trackBg
        ) {
            this.isDark = isDark;
            this.bg = bg;
            this.card = card;
            this.cardBorder = cardBorder;
            this.subSurface = subSurface;
            this.primaryText = primaryText;
            this.secondaryText = secondaryText;
            this.tertiaryText = tertiaryText;
            this.divider = divider;
            this.brandStart = brandStart;
            this.brandEnd = brandEnd;
            this.brandTintBg = brandTintBg;
            this.brandText = brandText;
            this.trackBg = trackBg;
        }
    }

    public interface DialogHandle {
        void dismiss();

        boolean isShowing();
    }

    public interface ActionCallback {
        void onClick(DialogHandle handle);
    }

    public interface OnInputConfirm {
        boolean onConfirm(String text);
    }

    public interface OptionSelectListener {
        void onSelect(int index);
    }

    public interface TimeSelectListener {
        void onSelect(int hour, int minute);
    }

    public static final class MultiSelectOption {
        public final long id;
        public final String title;
        public final String subtitle;
        public final boolean isSuperFan;
        public boolean selected;
        public int weight;

        public MultiSelectOption(long id, String title, String subtitle, boolean isSuperFan, boolean selected, int weight) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle != null ? subtitle : "";
            this.isSuperFan = isSuperFan;
            this.selected = selected;
            this.weight = Math.max(1, weight);
        }
    }

    public interface MultiSelectConfirmListener {
        void onConfirm(List<MultiSelectOption> options);
    }

    public interface OnAddCustomListener {
        void onAddCustom(DialogHandle sheetHandle);
    }

    public static final class ProgressDialogHandle implements DialogHandle {
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
                        progressBar.setProgress(clamped, true);
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
                MAIN_HANDLER.post(apply);
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

    // ── 1. Custom Centered Modals ────────────────────────────────────────────

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
        if (isActivityInvalid(activity) || p == null) {
            return NO_OP_HANDLE;
        }
        View body = null;
        if (message != null && !message.isEmpty()) {
            TextView msgView = new TextView(activity);
            msgView.setText(message);
            msgView.setTextSize(14f);
            msgView.setTextColor(p.secondaryText);
            msgView.setLineSpacing(0f, 1.45f);
            msgView.setTextIsSelectable(false);
            body = msgView;
        }
        ActionCallback posCb = handle -> {
            handle.dismiss();
            if (onPositive != null) {
                onPositive.run();
            }
        };
        return showModalInternal(
                activity, p, title, body, true, false,
                positiveText, posCb, negativeText, onNegative, neutralText, onNeutral, null
        );
    }

    public static DialogHandle showCustomModal(
            Activity activity,
            Palette p,
            String title,
            View body,
            boolean cancelable,
            String positiveText,
            ActionCallback onPositive,
            String negativeText,
            Runnable onNegative,
            String neutralText,
            Runnable onNeutral
    ) {
        if (isActivityInvalid(activity) || p == null) {
            return NO_OP_HANDLE;
        }
        return showModalInternal(
                activity, p, title, body, cancelable, true,
                positiveText, onPositive, negativeText, onNegative, neutralText, onNeutral, null
        );
    }

    private static DialogHandle showModalInternal(
            Activity activity,
            Palette p,
            String title,
            View body,
            boolean cancelable,
            boolean adjustResize,
            String positiveText,
            ActionCallback onPositive,
            String negativeText,
            Runnable onNegative,
            String neutralText,
            Runnable onNeutral,
            Runnable onBeforeDismiss
    ) {
        final Dialog dialog = createBaseDialog(activity, adjustResize);
        final ModalHost host = buildModalHost(activity, p, dialog, cancelable, false, onBeforeDismiss);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        card.setFocusable(false);
        card.setBackground(createRoundedRect(p.card, dp(activity, 22), dp(activity, 1), p.cardBorder));
        card.setElevation(dp(activity, 16));
        applyRoundedOutline(card, dp(activity, 22));
        card.setPadding(dp(activity, 20), dp(activity, 20), dp(activity, 20), dp(activity, 18));

        if (title != null && !title.isEmpty()) {
            TextView titleTv = new TextView(activity);
            titleTv.setText(title);
            titleTv.setTextSize(17.5f);
            titleTv.setTypeface(Typeface.DEFAULT_BOLD);
            titleTv.setTextColor(p.primaryText);
            titleTv.setLineSpacing(0f, 1.18f);
            card.addView(titleTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
        }

        if (body != null) {
            MaxHeightScrollView scroll = new MaxHeightScrollView(activity);
            int maxBodyHeight = Math.round(getScreenHeight(activity) * 0.52f);
            scroll.setMaxHeightPx(maxBodyHeight);
            scroll.setVerticalScrollBarEnabled(true);
            scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            scroll.setFillViewport(false);
            detachFromParent(body);
            scroll.addView(body, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
            ));
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            bodyLp.topMargin = (title != null && !title.isEmpty()) ? dp(activity, 12) : 0;
            card.addView(scroll, bodyLp);
        }

        boolean hasPos = positiveText != null && !positiveText.isEmpty();
        boolean hasNeg = negativeText != null && !negativeText.isEmpty();
        boolean hasNeu = neutralText != null && !neutralText.isEmpty();

        if (hasPos || hasNeg || hasNeu) {
            LinearLayout btnBar = new LinearLayout(activity);
            btnBar.setOrientation(LinearLayout.HORIZONTAL);
            btnBar.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            barLp.topMargin = dp(activity, 20);
            btnBar.setLayoutParams(barLp);

            boolean compact = hasPos && hasNeg && hasNeu;
            int pillPadH = dp(activity, compact ? 13 : 18);
            int gap = dp(activity, compact ? 6 : 8);

            if (hasNeu) {
                TextView neuBtn = createNeutralButton(activity, p, neutralText, dp(activity, compact ? 8 : 10));
                neuBtn.setOnClickListener(v -> {
                    if (host.isDismissed()) {
                        return;
                    }
                    host.handle.dismiss();
                    if (onNeutral != null) {
                        onNeutral.run();
                    }
                });
                LinearLayout.LayoutParams neuLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                neuLp.rightMargin = gap;
                btnBar.addView(neuBtn, neuLp);
            }

            View spacer = new View(activity);
            btnBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

            if (hasNeg) {
                TextView negBtn = createSecondaryPillButton(activity, p, negativeText, pillPadH);
                negBtn.setOnClickListener(v -> {
                    if (host.isDismissed()) {
                        return;
                    }
                    host.handle.dismiss();
                    if (onNegative != null) {
                        onNegative.run();
                    }
                });
                LinearLayout.LayoutParams negLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                if (hasPos) {
                    negLp.rightMargin = gap;
                }
                btnBar.addView(negBtn, negLp);
            }

            if (hasPos) {
                TextView posBtn = createPrimaryPillButton(activity, p, positiveText, pillPadH);
                if (!hasNeg && !hasNeu) {
                    posBtn.setMinWidth(dp(activity, 96));
                }
                posBtn.setOnClickListener(v -> {
                    if (host.isDismissed()) {
                        return;
                    }
                    if (onPositive != null) {
                        onPositive.onClick(host.handle);
                    } else {
                        host.handle.dismiss();
                    }
                });
                btnBar.addView(posBtn, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));
            }

            card.addView(btnBar);
        }

        int screenW = getScreenWidth(activity);
        int sideMargin = dp(activity, 24);
        int maxCardW = dp(activity, 400);
        int targetW = Math.min(maxCardW, Math.max(dp(activity, 260), screenW - sideMargin * 2));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                targetW,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        cardLp.leftMargin = sideMargin;
        cardLp.rightMargin = sideMargin;
        cardLp.topMargin = dp(activity, 24);
        cardLp.bottomMargin = dp(activity, 24);

        host.setContentView(card, cardLp);
        host.show();
        return host.handle;
    }

    // ── 2. Custom Bottom Sheet Option Menu ───────────────────────────────────

    public static DialogHandle showOptionSheet(
            Activity activity,
            Palette p,
            String title,
            String subtitle,
            List<String> options,
            int selectedIndex,
            int trailingActionIndex,
            OptionSelectListener onSelect
    ) {
        if (isActivityInvalid(activity) || p == null) {
            return NO_OP_HANDLE;
        }

        final Dialog dialog = createBaseDialog(activity, false);
        final ModalHost host = buildModalHost(activity, p, dialog, true, true, null);
        final AtomicBoolean selectedOnce = new AtomicBoolean(false);

        LinearLayout sheet = createBottomSheetContainer(activity, p);

        sheet.addView(createDragHandle(activity, p));

        if (title != null && !title.isEmpty()) {
            TextView titleTv = new TextView(activity);
            titleTv.setText(title);
            titleTv.setTextSize(17.5f);
            titleTv.setTypeface(Typeface.DEFAULT_BOLD);
            titleTv.setTextColor(p.primaryText);
            LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            tLp.topMargin = dp(activity, 12);
            sheet.addView(titleTv, tLp);
        }

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subTv = new TextView(activity);
            subTv.setText(subtitle);
            subTv.setTextSize(12.5f);
            subTv.setTextColor(p.secondaryText);
            subTv.setLineSpacing(0f, 1.3f);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            sLp.topMargin = dp(activity, 4);
            sheet.addView(subTv, sLp);
        }

        MaxHeightScrollView scroll = new MaxHeightScrollView(activity);
        scroll.setMaxHeightPx(Math.round(getScreenHeight(activity) * 0.56f));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout listCol = new LinearLayout(activity);
        listCol.setOrientation(LinearLayout.VERTICAL);

        int count = options != null ? options.size() : 0;
        int rippleColor = p.isDark ? 0x26FFFFFF : 0x16000000;
        for (int i = 0; i < count; i++) {
            final int index = i;
            String label = options.get(i);
            boolean isSelected = (i == selectedIndex);
            boolean isTrailingAction = (i == trailingActionIndex);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setFocusable(true);
            row.setPadding(dp(activity, 14), dp(activity, 13), dp(activity, 14), dp(activity, 13));

            GradientDrawable rowShape;
            if (isSelected) {
                rowShape = createRoundedRect(p.brandTintBg, dp(activity, 12), dp(activity, 1), p.brandStart);
            } else {
                rowShape = createRoundedRect(p.subSurface, dp(activity, 12), 0, 0);
            }
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), rowShape, null));

            TextView labelTv = new TextView(activity);
            labelTv.setText(label != null ? label : "");
            labelTv.setTextSize(14.5f);
            labelTv.setTypeface(isSelected || isTrailingAction ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            if (isSelected) {
                labelTv.setTextColor(p.brandText);
            } else if (isTrailingAction) {
                labelTv.setTextColor(p.brandText);
            } else {
                labelTv.setTextColor(p.primaryText);
            }
            labelTv.setMaxLines(2);
            labelTv.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            );
            labelLp.rightMargin = dp(activity, 10);
            row.addView(labelTv, labelLp);

            if (isSelected) {
                ImageView checkIv = new ImageView(activity);
                checkIv.setImageDrawable(new CheckIconDrawable(p.brandStart));
                row.addView(checkIv, new LinearLayout.LayoutParams(dp(activity, 18), dp(activity, 18)));
            } else if (isTrailingAction) {
                ImageView chevronIv = new ImageView(activity);
                chevronIv.setImageDrawable(new ChevronRightDrawable(p.brandText));
                row.addView(chevronIv, new LinearLayout.LayoutParams(dp(activity, 16), dp(activity, 16)));
            }

            row.setOnClickListener(v -> {
                if (!selectedOnce.compareAndSet(false, true)) {
                    return;
                }
                host.handle.dismiss();
                if (onSelect != null) {
                    onSelect.onSelect(index);
                }
            });

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            if (i > 0) {
                rowLp.topMargin = dp(activity, 7);
            }
            listCol.addView(row, rowLp);
        }

        scroll.addView(listCol, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        scrollLp.topMargin = dp(activity, 14);
        sheet.addView(scroll, scrollLp);

        TextView cancelBtn = createSecondaryPillButton(activity, p, "取消", dp(activity, 16));
        cancelBtn.setOnClickListener(v -> host.handle.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(activity, 44)
        );
        cancelLp.topMargin = dp(activity, 14);
        sheet.addView(cancelBtn, cancelLp);

        host.setBottomSheetContent(sheet);
        host.show();
        return host.handle;
    }

    // ── 2b. Custom Bottom Sheet Multi-Select Target & Weight Picker ──────────

    private static final class MultiSelectRowHolder {
        final MultiSelectOption option;
        final LinearLayout row;
        final FrameLayout checkboxBox;
        final ImageView checkIcon;
        final TextView titleTv;
        final LinearLayout stepperPill;
        final FrameLayout minusBtn;
        final TextView weightLabelTv;
        final FrameLayout plusBtn;

        MultiSelectRowHolder(
                MultiSelectOption option,
                LinearLayout row,
                FrameLayout checkboxBox,
                ImageView checkIcon,
                TextView titleTv,
                LinearLayout stepperPill,
                FrameLayout minusBtn,
                TextView weightLabelTv,
                FrameLayout plusBtn
        ) {
            this.option = option;
            this.row = row;
            this.checkboxBox = checkboxBox;
            this.checkIcon = checkIcon;
            this.titleTv = titleTv;
            this.stepperPill = stepperPill;
            this.minusBtn = minusBtn;
            this.weightLabelTv = weightLabelTv;
            this.plusBtn = plusBtn;
        }
    }

    public static DialogHandle showMultiSelectTargetSheet(
            Activity activity,
            Palette p,
            String title,
            String subtitle,
            List<MultiSelectOption> options,
            boolean showWeights,
            OnAddCustomListener onAddCustom,
            MultiSelectConfirmListener onConfirm
    ) {
        if (isActivityInvalid(activity) || p == null) {
            return NO_OP_HANDLE;
        }

        final List<MultiSelectOption> workingCopy = new ArrayList<>();
        boolean hasSuperFan = false;
        if (options != null) {
            for (MultiSelectOption opt : options) {
                if (opt == null) {
                    continue;
                }
                MultiSelectOption copy = new MultiSelectOption(
                        opt.id,
                        opt.title,
                        opt.subtitle,
                        opt.isSuperFan,
                        opt.selected,
                        clamp(opt.weight, 1, 20)
                );
                workingCopy.add(copy);
                if (copy.isSuperFan) {
                    hasSuperFan = true;
                }
            }
        }

        final Dialog dialog = createBaseDialog(activity, false);
        final ModalHost host = buildModalHost(activity, p, dialog, true, true, null);
        final AtomicBoolean confirmedOnce = new AtomicBoolean(false);
        final int rippleColor = p.isDark ? 0x26FFFFFF : 0x16000000;

        LinearLayout sheet = createBottomSheetContainer(activity, p);
        sheet.addView(createDragHandle(activity, p));

        // Header row: title + live selection badge pill
        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        headerLp.topMargin = dp(activity, 12);
        sheet.addView(headerRow, headerLp);

        TextView titleTv = new TextView(activity);
        titleTv.setText(title != null && !title.isEmpty() ? title : "选择目标");
        titleTv.setTextSize(17.5f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(p.primaryText);
        titleTv.setMaxLines(1);
        titleTv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        titleLp.rightMargin = dp(activity, 10);
        headerRow.addView(titleTv, titleLp);

        final TextView badgeTv = new TextView(activity);
        badgeTv.setTextSize(11.5f);
        badgeTv.setTypeface(Typeface.DEFAULT_BOLD);
        badgeTv.setFontFeatureSettings("tnum");
        badgeTv.setSingleLine(true);
        badgeTv.setGravity(Gravity.CENTER);
        badgeTv.setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4));
        headerRow.addView(badgeTv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subTv = new TextView(activity);
            subTv.setText(subtitle);
            subTv.setTextSize(12.5f);
            subTv.setTextColor(p.secondaryText);
            subTv.setLineSpacing(0f, 1.3f);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            subLp.topMargin = dp(activity, 4);
            sheet.addView(subTv, subLp);
        }

        final Runnable[] refreshUiRef = new Runnable[1];

        // Quick-action chip row
        HorizontalScrollView chipScroll = new HorizontalScrollView(activity);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        chipScroll.setFillViewport(true);

        LinearLayout chipRow = new LinearLayout(activity);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);

        int chipGap = dp(activity, 8);
        boolean hasOptions = !workingCopy.isEmpty();

        TextView selectAllChip = createQuickActionChip(activity, p, "全选", false, rippleColor);
        selectAllChip.setEnabled(hasOptions);
        selectAllChip.setAlpha(hasOptions ? 1f : 0.45f);
        selectAllChip.setOnClickListener(v -> {
            for (MultiSelectOption opt : workingCopy) {
                opt.selected = true;
            }
            refreshUiRef[0].run();
        });
        chipRow.addView(selectAllChip);

        if (hasSuperFan) {
            TextView superFanChip = createQuickActionChip(activity, p, "仅超粉", false, rippleColor);
            superFanChip.setOnClickListener(v -> {
                for (MultiSelectOption opt : workingCopy) {
                    opt.selected = opt.isSuperFan;
                }
                refreshUiRef[0].run();
            });
            LinearLayout.LayoutParams sfLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            sfLp.leftMargin = chipGap;
            chipRow.addView(superFanChip, sfLp);
        }

        TextView clearChip = createQuickActionChip(activity, p, "清空", false, rippleColor);
        clearChip.setEnabled(hasOptions);
        clearChip.setAlpha(hasOptions ? 1f : 0.45f);
        clearChip.setOnClickListener(v -> {
            for (MultiSelectOption opt : workingCopy) {
                opt.selected = false;
            }
            refreshUiRef[0].run();
        });
        LinearLayout.LayoutParams clrLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        clrLp.leftMargin = chipGap;
        chipRow.addView(clearChip, clrLp);

        if (onAddCustom != null) {
            View chipSpacer = new View(activity);
            chipRow.addView(chipSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

            TextView addCustomChip = createQuickActionChip(activity, p, "+ 自定义 UID", true, rippleColor);
            attachPressScale(addCustomChip);
            addCustomChip.setOnClickListener(v -> {
                if (host.isDismissed()) {
                    return;
                }
                onAddCustom.onAddCustom(host.handle);
            });
            LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            addLp.leftMargin = chipGap;
            chipRow.addView(addCustomChip, addLp);
        }

        chipScroll.addView(chipRow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams chipScrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        chipScrollLp.topMargin = dp(activity, 12);
        sheet.addView(chipScroll, chipScrollLp);

        // Scrollable list of rounded option cards (capped at ~50% screen height)
        MaxHeightScrollView scroll = new MaxHeightScrollView(activity);
        scroll.setMaxHeightPx(Math.round(getScreenHeight(activity) * 0.50f));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        LinearLayout listCol = new LinearLayout(activity);
        listCol.setOrientation(LinearLayout.VERTICAL);

        final List<MultiSelectRowHolder> holders = new ArrayList<>(workingCopy.size());

        if (workingCopy.isEmpty()) {
            TextView emptyTv = new TextView(activity);
            emptyTv.setText("暂无可选目标，确定后将默认使用全部粉丝牌");
            emptyTv.setTextSize(13f);
            emptyTv.setTextColor(p.tertiaryText);
            emptyTv.setGravity(Gravity.CENTER);
            emptyTv.setPadding(dp(activity, 16), dp(activity, 20), dp(activity, 16), dp(activity, 20));
            emptyTv.setBackground(createRoundedRect(p.subSurface, dp(activity, 12), dp(activity, 1), p.cardBorder));
            listCol.addView(emptyTv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
        } else {
            int rowGap = dp(activity, 8);
            int checkboxSize = dp(activity, 20);
            int checkIconSize = dp(activity, 13);
            int stepperBtnSize = dp(activity, 26);

            for (int i = 0; i < workingCopy.size(); i++) {
                final MultiSelectOption opt = workingCopy.get(i);

                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setClickable(true);
                row.setFocusable(true);
                row.setPadding(dp(activity, 12), dp(activity, 11), dp(activity, 12), dp(activity, 11));

                FrameLayout checkboxBox = new FrameLayout(activity);
                ImageView checkIcon = new ImageView(activity);
                checkIcon.setImageDrawable(new CheckIconDrawable(Color.WHITE));
                checkboxBox.addView(checkIcon, new FrameLayout.LayoutParams(
                        checkIconSize, checkIconSize, Gravity.CENTER
                ));
                LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(checkboxSize, checkboxSize);
                cbLp.rightMargin = dp(activity, 11);
                row.addView(checkboxBox, cbLp);

                LinearLayout textCol = new LinearLayout(activity);
                textCol.setOrientation(LinearLayout.VERTICAL);

                TextView optTitleTv = new TextView(activity);
                optTitleTv.setText(opt.title != null ? opt.title : "");
                optTitleTv.setTextSize(14f);
                optTitleTv.setTypeface(Typeface.DEFAULT_BOLD);
                optTitleTv.setMaxLines(2);
                optTitleTv.setEllipsize(TextUtils.TruncateAt.END);
                textCol.addView(optTitleTv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                ));

                if (!opt.subtitle.isEmpty()) {
                    TextView optSubTv = new TextView(activity);
                    optSubTv.setText(opt.subtitle);
                    optSubTv.setTextSize(11.5f);
                    optSubTv.setTextColor(p.secondaryText);
                    optSubTv.setMaxLines(1);
                    optSubTv.setEllipsize(TextUtils.TruncateAt.END);
                    LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    subLp.topMargin = dp(activity, 2);
                    textCol.addView(optSubTv, subLp);
                }

                row.addView(textCol, new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ));

                LinearLayout stepperPill = null;
                FrameLayout minusBtn = null;
                TextView weightLabelTv = null;
                FrameLayout plusBtn = null;

                if (showWeights) {
                    stepperPill = new LinearLayout(activity);
                    stepperPill.setOrientation(LinearLayout.HORIZONTAL);
                    stepperPill.setGravity(Gravity.CENTER_VERTICAL);
                    stepperPill.setClickable(true);
                    stepperPill.setFocusable(false);
                    stepperPill.setPadding(dp(activity, 3), dp(activity, 3), dp(activity, 3), dp(activity, 3));
                    stepperPill.setBackground(createRoundedRect(p.card, dp(activity, 99), dp(activity, 1), p.cardBorder));

                    minusBtn = createWeightStepperButton(activity, p, false, rippleColor);
                    minusBtn.setOnClickListener(v -> {
                        if (opt.weight > 1) {
                            opt.weight = clamp(opt.weight - 1, 1, 20);
                            refreshUiRef[0].run();
                        }
                    });
                    stepperPill.addView(minusBtn, new LinearLayout.LayoutParams(stepperBtnSize, stepperBtnSize));

                    weightLabelTv = new TextView(activity);
                    weightLabelTv.setTextSize(11.5f);
                    weightLabelTv.setTypeface(Typeface.DEFAULT_BOLD);
                    weightLabelTv.setTextColor(p.brandText);
                    weightLabelTv.setFontFeatureSettings("tnum");
                    weightLabelTv.setSingleLine(true);
                    weightLabelTv.setGravity(Gravity.CENTER);
                    weightLabelTv.setPadding(dp(activity, 6), 0, dp(activity, 6), 0);
                    stepperPill.addView(weightLabelTv, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    ));

                    plusBtn = createWeightStepperButton(activity, p, true, rippleColor);
                    plusBtn.setOnClickListener(v -> {
                        if (opt.weight < 20) {
                            opt.weight = clamp(opt.weight + 1, 1, 20);
                            refreshUiRef[0].run();
                        }
                    });
                    stepperPill.addView(plusBtn, new LinearLayout.LayoutParams(stepperBtnSize, stepperBtnSize));

                    LinearLayout.LayoutParams stepLp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );
                    stepLp.leftMargin = dp(activity, 8);
                    row.addView(stepperPill, stepLp);
                }

                row.setOnClickListener(v -> {
                    opt.selected = !opt.selected;
                    refreshUiRef[0].run();
                });

                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                if (i > 0) {
                    rowLp.topMargin = rowGap;
                }
                listCol.addView(row, rowLp);

                holders.add(new MultiSelectRowHolder(
                        opt, row, checkboxBox, checkIcon, optTitleTv,
                        stepperPill, minusBtn, weightLabelTv, plusBtn
                ));
            }
        }

        final int rowRadius = dp(activity, 12);
        final int strokePx = Math.max(1, dp(activity, 1));
        final int checkRadius = dp(activity, 6);
        final int checkStroke = Math.max(1, Math.round(1.5f * activity.getResources().getDisplayMetrics().density));
        final int pillRadius = dp(activity, 99);

        refreshUiRef[0] = () -> {
            int selectedCount = 0;
            int totalWeight = 0;
            for (MultiSelectOption opt : workingCopy) {
                if (opt.selected) {
                    selectedCount++;
                    totalWeight += opt.weight;
                }
            }

            if (selectedCount == 0) {
                badgeTv.setText("全部粉丝牌");
                badgeTv.setTextColor(p.tertiaryText);
                badgeTv.setBackground(createRoundedRect(p.subSurface, pillRadius, strokePx, p.cardBorder));
            } else {
                badgeTv.setText("已选 " + selectedCount + " 位");
                badgeTv.setTextColor(p.brandText);
                badgeTv.setBackground(createRoundedRect(p.brandTintBg, pillRadius, strokePx, p.brandStart));
            }

            for (MultiSelectRowHolder h : holders) {
                boolean sel = h.option.selected;
                GradientDrawable rowShape = sel
                        ? createRoundedRect(p.brandTintBg, rowRadius, strokePx, p.brandStart)
                        : createRoundedRect(p.subSurface, rowRadius, strokePx, p.cardBorder);
                h.row.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), rowShape, null));

                if (sel) {
                    h.checkboxBox.setBackground(createRoundedRect(p.brandStart, checkRadius, 0, 0));
                    h.checkIcon.setVisibility(View.VISIBLE);
                    h.titleTv.setTextColor(p.brandText);
                } else {
                    h.checkboxBox.setBackground(createRoundedRect(Color.TRANSPARENT, checkRadius, checkStroke, p.tertiaryText));
                    h.checkIcon.setVisibility(View.GONE);
                    h.titleTv.setTextColor(p.primaryText);
                }

                if (showWeights && h.stepperPill != null) {
                    if (sel) {
                        h.stepperPill.setVisibility(View.VISIBLE);
                        int pct = totalWeight > 0 ? Math.round((h.option.weight * 100f) / totalWeight) : 0;
                        h.weightLabelTv.setText(h.option.weight + "份 · " + pct + "%");
                        h.minusBtn.setAlpha(h.option.weight <= 1 ? 0.36f : 1f);
                        h.plusBtn.setAlpha(h.option.weight >= 20 ? 0.36f : 1f);
                    } else {
                        h.stepperPill.setVisibility(View.GONE);
                    }
                }
            }
        };
        refreshUiRef[0].run();

        scroll.addView(listCol, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        scrollLp.topMargin = dp(activity, 12);
        sheet.addView(scroll, scrollLp);

        // Bottom action row: 取消 + 确定
        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actLp.topMargin = dp(activity, 16);
        sheet.addView(actionRow, actLp);

        TextView cancelBtn = createSecondaryPillButton(activity, p, "取消", dp(activity, 16));
        cancelBtn.setOnClickListener(v -> host.handle.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0, dp(activity, 44), 1f
        );
        cancelLp.rightMargin = dp(activity, 10);
        actionRow.addView(cancelBtn, cancelLp);

        TextView confirmBtn = createPrimaryPillButton(activity, p, "确定", dp(activity, 16));
        confirmBtn.setOnClickListener(v -> {
            if (host.isDismissed() || !confirmedOnce.compareAndSet(false, true)) {
                return;
            }
            host.handle.dismiss();
            if (onConfirm != null) {
                onConfirm.onConfirm(workingCopy);
            }
        });
        actionRow.addView(confirmBtn, new LinearLayout.LayoutParams(
                0, dp(activity, 44), 1f
        ));

        host.setBottomSheetContent(sheet);
        host.show();
        return host.handle;
    }

    private static TextView createQuickActionChip(
            Context ctx,
            Palette p,
            String text,
            boolean accent,
            int rippleColor
    ) {
        TextView chip = new TextView(ctx);
        chip.setText(text);
        chip.setTextSize(12f);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextColor(accent ? p.brandText : p.secondaryText);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6));

        GradientDrawable bg = accent
                ? createRoundedRect(p.brandTintBg, dp(ctx, 99), dp(ctx, 1), p.brandStart)
                : createRoundedRect(p.subSurface, dp(ctx, 99), dp(ctx, 1), p.cardBorder);
        chip.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null));
        return chip;
    }

    private static FrameLayout createWeightStepperButton(
            Context ctx,
            Palette p,
            boolean plus,
            int rippleColor
    ) {
        FrameLayout btn = new FrameLayout(ctx);
        btn.setClickable(true);
        btn.setFocusable(true);
        GradientDrawable bg = createRoundedRect(p.subSurface, dp(ctx, 99), dp(ctx, 1), p.cardBorder);
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null));

        ImageView icon = new ImageView(ctx);
        icon.setImageDrawable(new StepperSignDrawable(p.primaryText, plus));
        btn.addView(icon, new FrameLayout.LayoutParams(
                dp(ctx, 12), dp(ctx, 12), Gravity.CENTER
        ));
        return btn;
    }

    // ── 3. Custom Bottom Sheet Time Picker ───────────────────────────────────

    public static DialogHandle showTimePickerSheet(
            Activity activity,
            Palette p,
            String title,
            int initialHour,
            int initialMinute,
            TimeSelectListener onSelect
    ) {
        if (isActivityInvalid(activity) || p == null) {
            return NO_OP_HANDLE;
        }

        final int[] curHour = new int[]{clamp(initialHour, 0, 23)};
        final int[] curMinute = new int[]{clamp(initialMinute, 0, 59)};

        final Dialog dialog = createBaseDialog(activity, false);
        final ModalHost host = buildModalHost(activity, p, dialog, true, true, null);

        LinearLayout sheet = createBottomSheetContainer(activity, p);
        sheet.addView(createDragHandle(activity, p));

        // Header row: title + subtle 24h chip
        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        hLp.topMargin = dp(activity, 12);
        sheet.addView(headerRow, hLp);

        TextView titleTv = new TextView(activity);
        titleTv.setText(title != null && !title.isEmpty() ? title : "选择执行时间");
        titleTv.setTextSize(17.5f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        titleTv.setTextColor(p.primaryText);
        headerRow.addView(titleTv, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ));

        TextView badge24h = new TextView(activity);
        badge24h.setText("24 小时制");
        badge24h.setTextSize(11f);
        badge24h.setTypeface(Typeface.DEFAULT_BOLD);
        badge24h.setTextColor(p.tertiaryText);
        badge24h.setBackground(createRoundedRect(p.subSurface, dp(activity, 99), dp(activity, 1), p.cardBorder));
        badge24h.setPadding(dp(activity, 9), dp(activity, 4), dp(activity, 9), dp(activity, 4));
        headerRow.addView(badge24h);

        // Live digital readout card (HH : MM)
        LinearLayout readoutCard = new LinearLayout(activity);
        readoutCard.setOrientation(LinearLayout.VERTICAL);
        readoutCard.setGravity(Gravity.CENTER);
        readoutCard.setBackground(createRoundedRect(p.brandTintBg, dp(activity, 16), dp(activity, 1), p.brandStart));
        readoutCard.setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10));
        LinearLayout.LayoutParams roLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        roLp.topMargin = dp(activity, 12);
        sheet.addView(readoutCard, roLp);

        final TextView readoutTv = new TextView(activity);
        readoutTv.setTextSize(34f);
        readoutTv.setTypeface(Typeface.DEFAULT_BOLD);
        readoutTv.setTextColor(p.brandText);
        readoutTv.setGravity(Gravity.CENTER);
        readoutTv.setFontFeatureSettings("tnum");
        readoutCard.addView(readoutTv);

        // Quick preset chips row
        final int[][] presets = new int[][]{
                {0, 5}, {1, 0}, {8, 0}, {12, 0}, {20, 0}
        };
        LinearLayout presetRow = new LinearLayout(activity);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        prLp.topMargin = dp(activity, 12);
        sheet.addView(presetRow, prLp);

        final TextView[] presetChips = new TextView[presets.length];

        // Dual scrollable columns (Hour 00-23 | Minute 00-59)
        LinearLayout columnsRow = new LinearLayout(activity);
        columnsRow.setOrientation(LinearLayout.HORIZONTAL);
        columnsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        crLp.topMargin = dp(activity, 12);
        sheet.addView(columnsRow, crLp);

        final int itemHeightPx = dp(activity, 40);
        final int listHeightPx = dp(activity, 168);

        final TextView[] hourItems = new TextView[24];
        final TextView[] minuteItems = new TextView[60];
        final ScrollView[] scrollRefs = new ScrollView[2];
        final Runnable[] refreshUiRef = new Runnable[1];

        View hourCol = buildTimePickerColumn(
                activity, p, "时", 24, itemHeightPx, listHeightPx,
                hourItems, scrollRefs, 0,
                () -> {
                    curHour[0] = (curHour[0] + 23) % 24;
                    refreshUiRef[0].run();
                    scrollToItem(scrollRefs[0], curHour[0], itemHeightPx, listHeightPx, true);
                },
                () -> {
                    curHour[0] = (curHour[0] + 1) % 24;
                    refreshUiRef[0].run();
                    scrollToItem(scrollRefs[0], curHour[0], itemHeightPx, listHeightPx, true);
                },
                idx -> {
                    curHour[0] = idx;
                    refreshUiRef[0].run();
                    scrollToItem(scrollRefs[0], curHour[0], itemHeightPx, listHeightPx, true);
                }
        );
        LinearLayout.LayoutParams leftColLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        leftColLp.rightMargin = dp(activity, 5);
        columnsRow.addView(hourCol, leftColLp);

        TextView colonSep = new TextView(activity);
        colonSep.setText(":");
        colonSep.setTextSize(22f);
        colonSep.setTypeface(Typeface.DEFAULT_BOLD);
        colonSep.setTextColor(p.tertiaryText);
        colonSep.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams colonLp = new LinearLayout.LayoutParams(
                dp(activity, 14), LinearLayout.LayoutParams.WRAP_CONTENT
        );
        columnsRow.addView(colonSep, colonLp);

        View minCol = buildTimePickerColumn(
                activity, p, "分", 60, itemHeightPx, listHeightPx,
                minuteItems, scrollRefs, 1,
                () -> {
                    curMinute[0] = (curMinute[0] + 59) % 60;
                    refreshUiRef[0].run();
                    scrollToItem(scrollRefs[1], curMinute[0], itemHeightPx, listHeightPx, true);
                },
                () -> {
                    curMinute[0] = (curMinute[0] + 1) % 60;
                    refreshUiRef[0].run();
                    scrollToItem(scrollRefs[1], curMinute[0], itemHeightPx, listHeightPx, true);
                },
                idx -> {
                    curMinute[0] = idx;
                    refreshUiRef[0].run();
                    scrollToItem(scrollRefs[1], curMinute[0], itemHeightPx, listHeightPx, true);
                }
        );
        LinearLayout.LayoutParams rightColLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        rightColLp.leftMargin = dp(activity, 5);
        columnsRow.addView(minCol, rightColLp);

        int rippleColor = p.isDark ? 0x26FFFFFF : 0x16000000;
        for (int i = 0; i < presets.length; i++) {
            final int ph = presets[i][0];
            final int pm = presets[i][1];
            TextView chip = new TextView(activity);
            chip.setText(String.format(Locale.US, "%02d:%02d", ph, pm));
            chip.setTextSize(12f);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setGravity(Gravity.CENTER);
            chip.setFontFeatureSettings("tnum");
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setPadding(dp(activity, 4), dp(activity, 7), dp(activity, 4), dp(activity, 7));
            chip.setOnClickListener(v -> {
                curHour[0] = ph;
                curMinute[0] = pm;
                refreshUiRef[0].run();
                scrollToItem(scrollRefs[0], curHour[0], itemHeightPx, listHeightPx, true);
                scrollToItem(scrollRefs[1], curMinute[0], itemHeightPx, listHeightPx, true);
            });
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            );
            if (i > 0) {
                chipLp.leftMargin = dp(activity, 6);
            }
            presetRow.addView(chip, chipLp);
            presetChips[i] = chip;
        }

        refreshUiRef[0] = () -> {
            readoutTv.setText(String.format(Locale.US, "%02d : %02d", curHour[0], curMinute[0]));
            for (int i = 0; i < presets.length; i++) {
                boolean active = (curHour[0] == presets[i][0] && curMinute[0] == presets[i][1]);
                TextView chip = presetChips[i];
                GradientDrawable bg = active
                        ? createRoundedRect(p.brandTintBg, dp(activity, 99), dp(activity, 1), p.brandStart)
                        : createRoundedRect(p.subSurface, dp(activity, 99), dp(activity, 1), p.cardBorder);
                chip.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null));
                chip.setTextColor(active ? p.brandText : p.secondaryText);
            }
            updateTimeColumnHighlight(activity, p, hourItems, curHour[0], rippleColor);
            updateTimeColumnHighlight(activity, p, minuteItems, curMinute[0], rippleColor);
        };
        refreshUiRef[0].run();

        // Bottom action row: 取消 + 确定
        LinearLayout actionRow = new LinearLayout(activity);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actLp.topMargin = dp(activity, 16);
        sheet.addView(actionRow, actLp);

        TextView cancelBtn = createSecondaryPillButton(activity, p, "取消", dp(activity, 16));
        cancelBtn.setOnClickListener(v -> host.handle.dismiss());
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                0, dp(activity, 44), 1f
        );
        cancelLp.rightMargin = dp(activity, 10);
        actionRow.addView(cancelBtn, cancelLp);

        TextView confirmBtn = createPrimaryPillButton(activity, p, "确定", dp(activity, 16));
        confirmBtn.setOnClickListener(v -> {
            if (host.isDismissed()) {
                return;
            }
            host.handle.dismiss();
            if (onSelect != null) {
                onSelect.onSelect(curHour[0], curMinute[0]);
            }
        });
        actionRow.addView(confirmBtn, new LinearLayout.LayoutParams(
                0, dp(activity, 44), 1f
        ));

        host.setBottomSheetContent(sheet);
        host.show();

        // Auto-scroll both columns to center the initial hour & minute once laid out
        scrollRefs[0].post(() -> scrollToItem(scrollRefs[0], curHour[0], itemHeightPx, listHeightPx, false));
        scrollRefs[1].post(() -> scrollToItem(scrollRefs[1], curMinute[0], itemHeightPx, listHeightPx, false));

        return host.handle;
    }

    private static View buildTimePickerColumn(
            Activity activity,
            Palette p,
            String label,
            int count,
            int itemHeightPx,
            int listHeightPx,
            TextView[] outItems,
            ScrollView[] scrollRefs,
            int scrollSlot,
            Runnable onPrev,
            Runnable onNext,
            OptionSelectListener onPick
    ) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackground(createRoundedRect(p.subSurface, dp(activity, 16), dp(activity, 1), p.cardBorder));
        col.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 8));

        int rippleColor = p.isDark ? 0x26FFFFFF : 0x16000000;

        // Top header: label + ▲ stepper button
        LinearLayout topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(activity, 6), dp(activity, 2), dp(activity, 2), dp(activity, 6));

        TextView colLabel = new TextView(activity);
        colLabel.setText(label);
        colLabel.setTextSize(12f);
        colLabel.setTypeface(Typeface.DEFAULT_BOLD);
        colLabel.setTextColor(p.tertiaryText);
        topBar.addView(colLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ));

        View upBtn = createStepperArrowButton(activity, p, true, rippleColor);
        upBtn.setOnClickListener(v -> onPrev.run());
        topBar.addView(upBtn, new LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 24)));

        View downBtn = createStepperArrowButton(activity, p, false, rippleColor);
        downBtn.setOnClickListener(v -> onNext.run());
        LinearLayout.LayoutParams downLp = new LinearLayout.LayoutParams(dp(activity, 28), dp(activity, 24));
        downLp.leftMargin = dp(activity, 4);
        topBar.addView(downBtn, downLp);

        col.addView(topBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // Divider hairline
        View div = new View(activity);
        div.setBackgroundColor(p.divider);
        col.addView(div, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(activity, 1))
        ));

        // Scrollable list of values
        ScrollView sv = new ScrollView(activity);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setFadingEdgeLength(dp(activity, 18));
        sv.setVerticalFadingEdgeEnabled(true);
        scrollRefs[scrollSlot] = sv;

        LinearLayout itemsWrap = new LinearLayout(activity);
        itemsWrap.setOrientation(LinearLayout.VERTICAL);
        int edgePad = Math.max(0, (listHeightPx - itemHeightPx) / 2);
        itemsWrap.setPadding(0, edgePad, 0, edgePad);

        for (int i = 0; i < count; i++) {
            final int val = i;
            TextView tv = new TextView(activity);
            tv.setText(String.format(Locale.US, "%02d", val));
            tv.setTextSize(16.5f);
            tv.setGravity(Gravity.CENTER);
            tv.setFontFeatureSettings("tnum");
            tv.setClickable(true);
            tv.setFocusable(true);
            tv.setOnClickListener(v -> onPick.onSelect(val));
            itemsWrap.addView(tv, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, itemHeightPx
            ));
            outItems[i] = tv;
        }

        sv.addView(itemsWrap, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, listHeightPx
        );
        svLp.topMargin = dp(activity, 4);
        col.addView(sv, svLp);

        return col;
    }

    private static View createStepperArrowButton(
            Context ctx,
            Palette p,
            boolean up,
            int rippleColor
    ) {
        FrameLayout btn = new FrameLayout(ctx);
        btn.setClickable(true);
        btn.setFocusable(true);
        GradientDrawable bg = createRoundedRect(p.card, dp(ctx, 8), dp(ctx, 1), p.cardBorder);
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null));

        ImageView iv = new ImageView(ctx);
        iv.setImageDrawable(new ChevronVerticalDrawable(p.secondaryText, up));
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(
                dp(ctx, 12), dp(ctx, 12), Gravity.CENTER
        );
        btn.addView(iv, ivLp);
        return btn;
    }

    private static void updateTimeColumnHighlight(
            Context ctx,
            Palette p,
            TextView[] items,
            int selectedIndex,
            int rippleColor
    ) {
        int radius = dp(ctx, 10);
        int stroke = dp(ctx, 1);
        for (int i = 0; i < items.length; i++) {
            TextView tv = items[i];
            boolean sel = (i == selectedIndex);
            if (sel) {
                GradientDrawable bg = createRoundedRect(p.brandTintBg, radius, stroke, p.brandStart);
                tv.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null));
                tv.setTextColor(p.brandText);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setTextSize(18f);
            } else {
                GradientDrawable bg = createRoundedRect(Color.TRANSPARENT, radius, 0, 0);
                tv.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), bg, null));
                tv.setTextColor(p.secondaryText);
                tv.setTypeface(Typeface.DEFAULT);
                tv.setTextSize(15.5f);
            }
        }
    }

    private static void scrollToItem(
            ScrollView sv,
            int index,
            int itemHeightPx,
            int listHeightPx,
            boolean smooth
    ) {
        if (sv == null) {
            return;
        }
        // Because itemsWrap has topPadding = (listHeightPx - itemHeightPx) / 2,
        // scrolling to index * itemHeightPx centers item[index] inside the viewport.
        int targetY = Math.max(0, index * itemHeightPx);
        if (smooth) {
            sv.smoothScrollTo(0, targetY);
        } else {
            sv.scrollTo(0, targetY);
        }
    }

    // ── 4. Custom Styled Input Modal ─────────────────────────────────────────

    public static DialogHandle showInputModal(
            Activity activity,
            Palette p,
            String title,
            String subtitle,
            String hint,
            String initialValue,
            int inputType,
            String positiveText,
            OnInputConfirm onConfirm
    ) {
        if (isActivityInvalid(activity) || p == null) {
            return NO_OP_HANDLE;
        }

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subTv = new TextView(activity);
            subTv.setText(subtitle);
            subTv.setTextSize(13f);
            subTv.setTextColor(p.secondaryText);
            subTv.setLineSpacing(0f, 1.38f);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            subLp.bottomMargin = dp(activity, 12);
            body.addView(subTv, subLp);
        }

        final EditText editText = new EditText(activity);
        editText.setInputType(inputType);
        editText.setSingleLine(true);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setTextSize(15f);
        editText.setTextColor(p.primaryText);
        editText.setHintTextColor(p.tertiaryText);
        if (hint != null) {
            editText.setHint(hint);
        }
        if (initialValue != null && !initialValue.isEmpty()) {
            editText.setText(initialValue);
            editText.setSelection(initialValue.length());
        }
        editText.setPadding(dp(activity, 14), dp(activity, 13), dp(activity, 14), dp(activity, 13));
        editText.setBackground(createRoundedRect(p.subSurface, dp(activity, 12), dp(activity, 1), p.brandStart));
        body.addView(editText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        final DialogHandle[] handleRef = new DialogHandle[1];
        ActionCallback submitAction = handle -> {
            String raw = editText.getText() != null ? editText.getText().toString().trim() : "";
            boolean ok = (onConfirm == null) || onConfirm.onConfirm(raw);
            if (ok) {
                hideSoftKeyboard(activity, editText);
                handle.dismiss();
            } else {
                playShakeAnimation(editText);
            }
        };

        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                if (handleRef[0] != null) {
                    submitAction.onClick(handleRef[0]);
                }
                return true;
            }
            return false;
        });

        String posLabel = (positiveText != null && !positiveText.isEmpty()) ? positiveText : "确定";
        DialogHandle handle = showModalInternal(
                activity, p, title, body, true, true,
                posLabel, submitAction,
                "取消", null,
                null, null,
                () -> hideSoftKeyboard(activity, editText)
        );
        handleRef[0] = handle;

        editText.postDelayed(() -> {
            if (handle.isShowing()) {
                editText.requestFocus();
                showSoftKeyboard(activity, editText);
            }
        }, 120L);

        return handle;
    }

    // ── 5. Custom Download Progress Modal ────────────────────────────────────

    public static ProgressDialogHandle showProgressModal(
            Activity activity,
            Palette p,
            String title,
            String initialStatus,
            String initialDetail,
            Runnable onCancel
    ) {
        if (isActivityInvalid(activity) || p == null) {
            return new ProgressDialogHandle(NO_OP_HANDLE, null, null, null, null);
        }

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);

        // Status label + bold percentage pill
        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView statusTv = new TextView(activity);
        statusTv.setText(initialStatus != null ? initialStatus : "");
        statusTv.setTextSize(13.5f);
        statusTv.setTextColor(p.secondaryText);
        statusTv.setMaxLines(2);
        statusTv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
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
        percentPill.setBackground(createRoundedRect(p.brandTintBg, dp(activity, 99), dp(activity, 1), p.brandStart));
        topRow.addView(percentPill, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        body.addView(topRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // Sleek custom horizontal progress track (8dp height, pill corners)
        SleekProgressBar progressBar = new SleekProgressBar(activity, p.trackBg, p.brandStart, p.brandEnd);
        progressBar.setIndeterminate(true);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 8)
        );
        pbLp.topMargin = dp(activity, 14);
        body.addView(progressBar, pbLp);

        // Detail subtitle
        TextView detailTv = new TextView(activity);
        String detailStr = initialDetail != null ? initialDetail : "";
        detailTv.setText(detailStr);
        detailTv.setTextSize(12f);
        detailTv.setTextColor(p.tertiaryText);
        detailTv.setFontFeatureSettings("tnum");
        detailTv.setVisibility(detailStr.isEmpty() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams dtLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        dtLp.topMargin = dp(activity, 8);
        body.addView(detailTv, dtLp);

        String cancelLabel = onCancel != null ? "取消下载" : null;
        DialogHandle baseHandle = showModalInternal(
                activity, p, title, body, false, false,
                null, null,
                cancelLabel, onCancel,
                null, null,
                progressBar::stopAnimations
        );

        return new ProgressDialogHandle(baseHandle, progressBar, statusTv, percentPill, detailTv);
    }

    // ── Core Dialog Host & Animation Infrastructure ──────────────────────────

    private static Dialog createBaseDialog(Activity activity, boolean adjustResize) {
        Dialog dialog = new Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        Window w = dialog.getWindow();
        if (w != null) {
            configureDialogWindow(w, adjustResize);
        }
        return dialog;
    }

    @SuppressWarnings("deprecation")
    private static void configureDialogWindow(Window w, boolean adjustResize) {
        w.setBackgroundDrawableResource(android.R.color.transparent);
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            w.setNavigationBarContrastEnforced(false);
        }
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        WindowManager.LayoutParams lp = w.getAttributes();
        if (lp != null) {
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.dimAmount = 0f;
            lp.windowAnimations = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            w.setAttributes(lp);
        }
        if (adjustResize) {
            w.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            );
        }
    }

    private static ModalHost buildModalHost(
            Activity activity,
            Palette p,
            Dialog dialog,
            boolean cancelable,
            boolean isBottomSheet,
            Runnable onBeforeDismiss
    ) {
        return new ModalHost(activity, p, dialog, cancelable, isBottomSheet, onBeforeDismiss);
    }

    private static final class ModalHost {
        private final Activity activity;
        private final Dialog dialog;
        private final boolean cancelable;
        private final boolean isBottomSheet;
        private final Runnable onBeforeDismiss;
        private final AtomicBoolean dismissed = new AtomicBoolean(false);
        private final FrameLayout root;
        private final View backdrop;
        private View contentView;
        private int baseBottomPaddingPx;

        final DialogHandle handle = new DialogHandle() {
            @Override
            public void dismiss() {
                dismissAnimated();
            }

            @Override
            public boolean isShowing() {
                return !dismissed.get() && dialog.isShowing();
            }
        };

        ModalHost(
                Activity activity,
                Palette p,
                Dialog dialog,
                boolean cancelable,
                boolean isBottomSheet,
                Runnable onBeforeDismiss
        ) {
            this.activity = activity;
            this.dialog = dialog;
            this.cancelable = cancelable;
            this.isBottomSheet = isBottomSheet;
            this.onBeforeDismiss = onBeforeDismiss;

            this.root = new FrameLayout(activity);
            this.root.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));

            this.backdrop = new View(activity);
            this.backdrop.setBackgroundColor(BACKDROP_COLOR);
            this.backdrop.setAlpha(0f);
            this.backdrop.setClickable(true);
            this.backdrop.setOnClickListener(v -> {
                if (cancelable) {
                    dismissAnimated();
                }
            });
            this.root.addView(this.backdrop, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
            ));

            dialog.setOnKeyListener((d, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP && cancelable) {
                        dismissAnimated();
                    }
                    return true;
                }
                return false;
            });

            // Apply system bar insets cleanly to root or bottom sheet
            this.root.setOnApplyWindowInsetsListener((v, insets) -> {
                int topInset = getInsetTop(insets);
                int bottomInset = getInsetBottom(insets);
                if (isBottomSheet && contentView != null) {
                    contentView.setPadding(
                            contentView.getPaddingLeft(),
                            contentView.getPaddingTop(),
                            contentView.getPaddingRight(),
                            baseBottomPaddingPx + bottomInset
                    );
                } else {
                    v.setPadding(0, topInset, 0, bottomInset);
                }
                return insets;
            });
        }

        boolean isDismissed() {
            return dismissed.get();
        }

        void setContentView(View view, FrameLayout.LayoutParams lp) {
            this.contentView = view;
            this.baseBottomPaddingPx = view.getPaddingBottom();
            this.root.addView(view, lp);
        }

        void setBottomSheetContent(LinearLayout sheet) {
            this.contentView = sheet;
            this.baseBottomPaddingPx = sheet.getPaddingBottom();
            int screenW = getScreenWidth(activity);
            int maxSheetW = dp(activity, 480);
            int targetW = screenW > maxSheetW ? maxSheetW : FrameLayout.LayoutParams.MATCH_PARENT;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    targetW,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
            );
            this.root.addView(sheet, lp);
        }

        void show() {
            if (isActivityInvalid(activity) || contentView == null) {
                return;
            }
            dialog.setContentView(root);
            Window w = dialog.getWindow();
            if (w != null) {
                w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            }

            if (isBottomSheet) {
                contentView.setAlpha(1f);
                contentView.getViewTreeObserver().addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                contentView.getViewTreeObserver().removeOnPreDrawListener(this);
                                float startY = Math.max(contentView.getHeight(), dp(activity, 260));
                                contentView.setTranslationY(startY);
                                AnimatorSet set = new AnimatorSet();
                                ObjectAnimator bgIn = ObjectAnimator.ofFloat(backdrop, View.ALPHA, 0f, 1f);
                                bgIn.setDuration(SHEET_ENTER_MS - 50L);
                                bgIn.setInterpolator(new DecelerateInterpolator(1.6f));

                                ObjectAnimator slideIn = ObjectAnimator.ofFloat(
                                        contentView, View.TRANSLATION_Y, startY, 0f
                                );
                                slideIn.setDuration(SHEET_ENTER_MS);
                                slideIn.setInterpolator(new OvershootInterpolator(0.8f));

                                set.playTogether(bgIn, slideIn);
                                set.start();
                                return true;
                            }
                        }
                );
            } else {
                contentView.setAlpha(0f);
                contentView.setScaleX(0.90f);
                contentView.setScaleY(0.90f);
                contentView.setTranslationY(dp(activity, 14));
                contentView.getViewTreeObserver().addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                contentView.getViewTreeObserver().removeOnPreDrawListener(this);
                                AnimatorSet set = new AnimatorSet();
                                ObjectAnimator bgIn = ObjectAnimator.ofFloat(backdrop, View.ALPHA, 0f, 1f);
                                ObjectAnimator alphaIn = ObjectAnimator.ofFloat(contentView, View.ALPHA, 0f, 1f);
                                ObjectAnimator sxIn = ObjectAnimator.ofFloat(contentView, View.SCALE_X, 0.90f, 1f);
                                ObjectAnimator syIn = ObjectAnimator.ofFloat(contentView, View.SCALE_Y, 0.90f, 1f);
                                ObjectAnimator tyIn = ObjectAnimator.ofFloat(
                                        contentView, View.TRANSLATION_Y, dp(activity, 14), 0f
                                );
                                set.setDuration(MODAL_ENTER_MS);
                                set.setInterpolator(new DecelerateInterpolator(2.2f));
                                set.playTogether(bgIn, alphaIn, sxIn, syIn, tyIn);
                                set.start();
                                return true;
                            }
                        }
                );
            }

            try {
                dialog.show();
                root.requestApplyInsets();
            } catch (Throwable ignored) {
                dismissed.set(true);
            }
        }

        void dismissAnimated() {
            if (!dismissed.compareAndSet(false, true)) {
                return;
            }
            if (onBeforeDismiss != null) {
                try {
                    onBeforeDismiss.run();
                } catch (Throwable ignored) {
                }
            }
            if (!dialog.isShowing() || contentView == null || isActivityInvalid(activity)) {
                safeDismissDialog(dialog);
                return;
            }

            AnimatorSet set = new AnimatorSet();
            if (isBottomSheet) {
                float endY = Math.max(contentView.getHeight(), dp(activity, 260));
                ObjectAnimator bgOut = ObjectAnimator.ofFloat(backdrop, View.ALPHA, backdrop.getAlpha(), 0f);
                ObjectAnimator slideOut = ObjectAnimator.ofFloat(
                        contentView, View.TRANSLATION_Y, contentView.getTranslationY(), endY
                );
                set.setDuration(SHEET_EXIT_MS);
                set.setInterpolator(new AccelerateInterpolator(1.5f));
                set.playTogether(bgOut, slideOut);
            } else {
                ObjectAnimator bgOut = ObjectAnimator.ofFloat(backdrop, View.ALPHA, backdrop.getAlpha(), 0f);
                ObjectAnimator alphaOut = ObjectAnimator.ofFloat(contentView, View.ALPHA, contentView.getAlpha(), 0f);
                ObjectAnimator sxOut = ObjectAnimator.ofFloat(contentView, View.SCALE_X, contentView.getScaleX(), 0.94f);
                ObjectAnimator syOut = ObjectAnimator.ofFloat(contentView, View.SCALE_Y, contentView.getScaleY(), 0.94f);
                ObjectAnimator tyOut = ObjectAnimator.ofFloat(
                        contentView, View.TRANSLATION_Y, contentView.getTranslationY(), dp(activity, 10)
                );
                set.setDuration(MODAL_EXIT_MS);
                set.setInterpolator(new AccelerateInterpolator(1.4f));
                set.playTogether(bgOut, alphaOut, sxOut, syOut, tyOut);
            }

            set.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    safeDismissDialog(dialog);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    safeDismissDialog(dialog);
                }
            });
            set.start();
        }
    }

    // ── Shared Styled Components ─────────────────────────────────────────────

    private static LinearLayout createBottomSheetContainer(Activity activity, Palette p) {
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);
        sheet.setFocusable(false);

        float r = dp(activity, 24);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(p.card);
        bg.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
        bg.setStroke(dp(activity, 1), p.cardBorder);
        sheet.setBackground(bg);
        sheet.setElevation(dp(activity, 20));
        sheet.setPadding(dp(activity, 20), dp(activity, 10), dp(activity, 20), dp(activity, 20));
        return sheet;
    }

    private static View createDragHandle(Context ctx, Palette p) {
        View pill = new View(ctx);
        int handleColor = p.isDark ? 0x44FFFFFF : 0x26000000;
        pill.setBackground(createRoundedRect(handleColor, dp(ctx, 99), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 4));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(ctx, 2);
        pill.setLayoutParams(lp);
        return pill;
    }

    private static TextView createPrimaryPillButton(Context ctx, Palette p, String text, int padH) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(13.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setMinHeight(dp(ctx, 40));
        btn.setPadding(padH, dp(ctx, 10), padH, dp(ctx, 10));

        GradientDrawable grad = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{p.brandStart, p.brandEnd}
        );
        grad.setCornerRadius(dp(ctx, 99));
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), grad, null));
        attachPressScale(btn);
        return btn;
    }

    private static TextView createSecondaryPillButton(Context ctx, Palette p, String text, int padH) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(13.5f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(p.secondaryText);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setMinHeight(dp(ctx, 40));
        btn.setPadding(padH, dp(ctx, 10), padH, dp(ctx, 10));

        GradientDrawable shape = createRoundedRect(p.subSurface, dp(ctx, 99), dp(ctx, 1), p.cardBorder);
        int ripple = p.isDark ? 0x24FFFFFF : 0x14000000;
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape, null));
        attachPressScale(btn);
        return btn;
    }

    private static TextView createNeutralButton(Context ctx, Palette p, String text, int padH) {
        TextView btn = new TextView(ctx);
        btn.setText(text);
        btn.setTextSize(13f);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(p.brandText);
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setMinHeight(dp(ctx, 40));
        btn.setPadding(padH, dp(ctx, 10), padH, dp(ctx, 10));

        GradientDrawable mask = createRoundedRect(Color.WHITE, dp(ctx, 99), 0, 0);
        int ripple = p.isDark ? 0x24FFFFFF : 0x16FF7A18;
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), null, mask));
        return btn;
    }

    private static void attachPressScale(View view) {
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90L).start();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(110L).start();
            }
            return false;
        });
    }

    private static void playShakeAnimation(View view) {
        float d = dp(view.getContext(), 7);
        ObjectAnimator anim = ObjectAnimator.ofFloat(
                view, View.TRANSLATION_X, 0f, -d, d, -d * 0.65f, d * 0.65f, -d * 0.3f, 0f
        );
        anim.setDuration(300L);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.start();
    }

    // ── Custom Views & Crisp Vector Drawables ────────────────────────────────

    private static final class MaxHeightScrollView extends ScrollView {
        private int maxHeightPx = Integer.MAX_VALUE;

        MaxHeightScrollView(Context context) {
            super(context);
        }

        void setMaxHeightPx(int maxHeightPx) {
            this.maxHeightPx = Math.max(0, maxHeightPx);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (maxHeightPx > 0 && maxHeightPx < Integer.MAX_VALUE) {
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                int size = MeasureSpec.getSize(heightMeasureSpec);
                if (mode == MeasureSpec.UNSPECIFIED || size > maxHeightPx) {
                    heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private static final class SleekProgressBar extends View {
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF fillRect = new RectF();
        private final int brandStart;
        private final int brandEnd;

        private boolean indeterminate = true;
        private float progressFraction = 0f;
        private float shimmerPhase = 0f;
        private ValueAnimator indeterminateAnimator;
        private ValueAnimator progressAnimator;

        SleekProgressBar(Context context, int trackColor, int brandStart, int brandEnd) {
            super(context);
            this.brandStart = brandStart;
            this.brandEnd = brandEnd;
            trackPaint.setStyle(Paint.Style.FILL);
            trackPaint.setColor(trackColor);
            fillPaint.setStyle(Paint.Style.FILL);
        }

        void setIndeterminate(boolean indeterminate) {
            if (this.indeterminate == indeterminate) {
                if (indeterminate && indeterminateAnimator == null && isAttachedToWindow()) {
                    startIndeterminateAnimator();
                }
                return;
            }
            this.indeterminate = indeterminate;
            if (indeterminate) {
                cancelProgressAnimator();
                startIndeterminateAnimator();
            } else {
                cancelIndeterminateAnimator();
            }
            invalidate();
        }

        void setProgress(int percent, boolean animate) {
            float target = Math.max(0f, Math.min(1f, percent / 100f));
            if (!animate || !isAttachedToWindow()) {
                cancelProgressAnimator();
                progressFraction = target;
                invalidate();
                return;
            }
            cancelProgressAnimator();
            progressAnimator = ValueAnimator.ofFloat(progressFraction, target);
            progressAnimator.setDuration(180L);
            progressAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
            progressAnimator.addUpdateListener(anim -> {
                progressFraction = (Float) anim.getAnimatedValue();
                invalidate();
            });
            progressAnimator.start();
        }

        void stopAnimations() {
            cancelIndeterminateAnimator();
            cancelProgressAnimator();
        }

        private void startIndeterminateAnimator() {
            if (indeterminateAnimator != null) {
                return;
            }
            indeterminateAnimator = ValueAnimator.ofFloat(0f, 1f);
            indeterminateAnimator.setDuration(1250L);
            indeterminateAnimator.setRepeatCount(ValueAnimator.INFINITE);
            indeterminateAnimator.setRepeatMode(ValueAnimator.RESTART);
            indeterminateAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
            indeterminateAnimator.addUpdateListener(anim -> {
                shimmerPhase = (Float) anim.getAnimatedValue();
                invalidate();
            });
            indeterminateAnimator.start();
        }

        private void cancelIndeterminateAnimator() {
            if (indeterminateAnimator != null) {
                indeterminateAnimator.cancel();
                indeterminateAnimator = null;
            }
        }

        private void cancelProgressAnimator() {
            if (progressAnimator != null) {
                progressAnimator.cancel();
                progressAnimator = null;
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (indeterminate) {
                startIndeterminateAnimator();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            stopAnimations();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) {
                return;
            }
            float r = h * 0.5f;
            trackRect.set(0f, 0f, w, h);
            canvas.drawRoundRect(trackRect, r, r, trackPaint);

            if (indeterminate) {
                float segW = Math.max(h * 1.5f, w * 0.34f);
                float left = -segW + shimmerPhase * (w + segW);
                float right = left + segW;
                float clampedL = Math.max(0f, left);
                float clampedR = Math.min(w, right);
                if (clampedR > clampedL) {
                    fillPaint.setShader(new LinearGradient(
                            clampedL, 0f, clampedR, 0f,
                            brandStart, brandEnd, Shader.TileMode.CLAMP
                    ));
                    fillRect.set(clampedL, 0f, clampedR, h);
                    canvas.drawRoundRect(fillRect, r, r, fillPaint);
                }
            } else if (progressFraction > 0f) {
                float fillW = Math.max(h, w * progressFraction);
                fillW = Math.min(w, fillW);
                fillPaint.setShader(new LinearGradient(
                        0f, 0f, fillW, 0f,
                        brandStart, brandEnd, Shader.TileMode.CLAMP
                ));
                fillRect.set(0f, 0f, fillW, h);
                canvas.drawRoundRect(fillRect, r, r, fillPaint);
            }
        }
    }

    private static final class CheckIconDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CheckIconDrawable(int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0f) {
                return;
            }
            paint.setStrokeWidth(s * 0.135f);
            float cx = b.centerX();
            float cy = b.centerY();
            Path path = new Path();
            path.moveTo(cx - s * 0.30f, cy + s * 0.02f);
            path.lineTo(cx - s * 0.08f, cy + s * 0.25f);
            path.lineTo(cx + s * 0.32f, cy - s * 0.22f);
            canvas.drawPath(path, paint);
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

    private static final class ChevronRightDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ChevronRightDrawable(int color) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0f) {
                return;
            }
            paint.setStrokeWidth(s * 0.14f);
            float cx = b.centerX();
            float cy = b.centerY();
            Path path = new Path();
            path.moveTo(cx - s * 0.14f, cy - s * 0.28f);
            path.lineTo(cx + s * 0.16f, cy);
            path.lineTo(cx - s * 0.14f, cy + s * 0.28f);
            canvas.drawPath(path, paint);
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

    private static final class ChevronVerticalDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean up;

        ChevronVerticalDrawable(int color, boolean up) {
            this.up = up;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0f) {
                return;
            }
            paint.setStrokeWidth(s * 0.16f);
            float cx = b.centerX();
            float cy = b.centerY();
            Path path = new Path();
            if (up) {
                path.moveTo(cx - s * 0.30f, cy + s * 0.14f);
                path.lineTo(cx, cy - s * 0.16f);
                path.lineTo(cx + s * 0.30f, cy + s * 0.14f);
            } else {
                path.moveTo(cx - s * 0.30f, cy - s * 0.14f);
                path.lineTo(cx, cy + s * 0.16f);
                path.lineTo(cx + s * 0.30f, cy - s * 0.14f);
            }
            canvas.drawPath(path, paint);
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

    private static final class StepperSignDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean plus;

        StepperSignDrawable(int color, boolean plus) {
            this.plus = plus;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            if (s <= 0f) {
                return;
            }
            paint.setStrokeWidth(Math.max(1.5f, s * 0.15f));
            float cx = b.centerX();
            float cy = b.centerY();
            float half = s * 0.30f;
            canvas.drawLine(cx - half, cy, cx + half, cy, paint);
            if (plus) {
                canvas.drawLine(cx, cy - half, cx, cy + half, paint);
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

    // ── Utilities ────────────────────────────────────────────────────────────

    private static final DialogHandle NO_OP_HANDLE = new DialogHandle() {
        @Override
        public void dismiss() {
        }

        @Override
        public boolean isShowing() {
            return false;
        }
    };

    private static boolean isActivityInvalid(Activity activity) {
        return activity == null || activity.isFinishing() || activity.isDestroyed();
    }

    private static void safeDismissDialog(Dialog dialog) {
        if (dialog == null) {
            return;
        }
        try {
            if (dialog.isShowing()) {
                dialog.dismiss();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void detachFromParent(View view) {
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private static GradientDrawable createRoundedRect(int fillColor, int radiusPx, int strokeWidthPx, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        if (strokeWidthPx > 0) {
            d.setStroke(strokeWidthPx, strokeColor);
        }
        return d;
    }

    private static void applyRoundedOutline(View view, final int radiusPx) {
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radiusPx);
            }
        });
        view.setClipToOutline(true);
    }

    @SuppressWarnings("deprecation")
    private static int getInsetTop(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.systemBars()).top;
        }
        return insets.getSystemWindowInsetTop();
    }

    @SuppressWarnings("deprecation")
    private static int getInsetBottom(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    private static void showSoftKeyboard(Context ctx, EditText target) {
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(target, 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hideSoftKeyboard(Context ctx, View target) {
        if (target == null) {
            return;
        }
        try {
            InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(target.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }

    private static int getScreenWidth(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm != null ? dm.widthPixels : 1080;
    }

    private static int getScreenHeight(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        return dm != null ? dm.heightPixels : 1920;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
