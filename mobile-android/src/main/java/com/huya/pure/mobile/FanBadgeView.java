package com.huya.pure.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Custom View that renders Huya's authentic 3x Retina official fan badges
 * (floor + optional custom emblem icon + level overlay + centered badge name + SuperFan V-flag)
 * with graceful warm-clay fallback pills and grayscale support for extinguished badges.
 */
public final class FanBadgeView extends View {

    private final Paint imgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint fallbackBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackLvPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF fallbackRect = new RectF();
    private final Rect dstRect = new Rect();
    private final ColorMatrixColorFilter grayscaleFilter;

    private Models.FanBadge badge;
    private int targetHeightPx;
    private int bindSeq = 0;

    private Bitmap floorBmp;
    private Bitmap iconBmp;
    private Bitmap levelBmp;
    private Bitmap vFlagBmp;

    public FanBadgeView(Context context) {
        this(context, 22);
    }

    public FanBadgeView(Context context, int heightDp) {
        super(context);
        this.targetHeightPx = Math.round(heightDp * context.getResources().getDisplayMetrics().density);

        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);

        fallbackLvPaint.setTypeface(Typeface.DEFAULT_BOLD);
        fallbackLvPaint.setTextAlign(Paint.Align.CENTER);
        fallbackLvPaint.setColor(Color.WHITE);

        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0f);
        grayscaleFilter = new ColorMatrixColorFilter(cm);
    }

    public void setBadge(Models.FanBadge badge) {
        this.badge = badge;
        this.bindSeq++;
        final int seq = this.bindSeq;

        this.floorBmp = null;
        this.iconBmp = null;
        this.levelBmp = null;
        this.vFlagBmp = null;

        if (badge != null && badge.render != null && !badge.render.floorUrl.isEmpty()) {
            Models.BadgeRenderInfo r = badge.render;
            BadgeImageLoader loader = BadgeImageLoader.getInstance();
            loader.load(getContext(), r.floorUrl, bmp -> {
                if (seq != bindSeq) return;
                floorBmp = bmp;
                requestLayout();
                invalidate();
            });
            if (!r.iconUrl.isEmpty()) {
                loader.load(getContext(), r.iconUrl, bmp -> {
                    if (seq != bindSeq) return;
                    iconBmp = bmp;
                    invalidate();
                });
            }
            if (!r.levelUrl.isEmpty()) {
                loader.load(getContext(), r.levelUrl, bmp -> {
                    if (seq != bindSeq) return;
                    levelBmp = bmp;
                    invalidate();
                });
            }
            if (!r.vFlagUrl.isEmpty()) {
                loader.load(getContext(), r.vFlagUrl, bmp -> {
                    if (seq != bindSeq) return;
                    vFlagBmp = bmp;
                    requestLayout();
                    invalidate();
                });
            }
        }
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = targetHeightPx;
        int w;
        if (floorBmp != null && !floorBmp.isRecycled() && floorBmp.getHeight() > 0) {
            float scale = (float) h / (float) floorBmp.getHeight();
            w = Math.max(h, Math.round(floorBmp.getWidth() * scale));
            if (vFlagBmp != null && !vFlagBmp.isRecycled() && vFlagBmp.getHeight() > 0) {
                int vw = Math.round(vFlagBmp.getWidth() * ((float) h / (float) vFlagBmp.getHeight()));
                if (vw > w) {
                    w = vw;
                }
            }
        } else {
            String fallbackText = buildFallbackText();
            fallbackLvPaint.setTextSize(h * 0.50f);
            float tw = fallbackLvPaint.measureText(fallbackText);
            w = Math.max(Math.round(h * 2.7f), Math.round(tw + h * 0.9f));
        }
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (badge == null) return;

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (floorBmp != null && !floorBmp.isRecycled() && floorBmp.getHeight() > 0) {
            drawAuthenticBadge(canvas, w, h);
        } else {
            drawFallbackPill(canvas, w, h);
        }
    }

    private void drawAuthenticBadge(Canvas canvas, int w, int h) {
        Models.BadgeRenderInfo r = badge.render;
        boolean custom = r != null && r.custom;
        boolean hasSuffix = r != null && r.hasSuffix;
        int size = (r != null && r.size >= 3) ? 3 : 2;
        float density = getResources().getDisplayMetrics().density;

        imgPaint.setColorFilter(badge.extinguished != 0 && custom ? grayscaleFilter : null);

        // Layer 1: Official floor PNG
        float floorScale = (float) h / (float) floorBmp.getHeight();
        int floorW = Math.min(w, Math.max(1, Math.round(floorBmp.getWidth() * floorScale)));
        dstRect.set(0, 0, floorW, h);
        canvas.drawBitmap(floorBmp, null, dstRect, imgPaint);

        // Layer 2: Custom anchor icon (left-aligned)
        if (iconBmp != null && !iconBmp.isRecycled() && iconBmp.getHeight() > 0) {
            int iconW = Math.max(1, Math.round(iconBmp.getWidth() * ((float) h / (float) iconBmp.getHeight())));
            dstRect.set(0, 0, iconW, h);
            canvas.drawBitmap(iconBmp, null, dstRect, imgPaint);
        }

        // Layer 3: Custom level digits overlay (left-aligned)
        if (levelBmp != null && !levelBmp.isRecycled() && levelBmp.getHeight() > 0) {
            int lvW = Math.max(1, Math.round(levelBmp.getWidth() * ((float) h / (float) levelBmp.getHeight())));
            dstRect.set(0, 0, lvW, h);
            canvas.drawBitmap(levelBmp, null, dstRect, imgPaint);
        }

        // Layer 4: Badge name centered inside the exact Huya capsule slot
        String name = badge.badgeName != null ? badge.badgeName.trim() : "";
        if (!name.isEmpty()) {
            float scaleRatio = h / (22f * density);
            float leftDp = custom ? 25f : 21f;
            float rightDp = hasSuffix ? 15f : (custom ? 3f : 4f);
            float slotLeft = leftDp * density * scaleRatio;
            float slotRight = Math.max(slotLeft + 6f * density, floorW - rightDp * density * scaleRatio);
            float slotWidth = Math.max(6f * density, slotRight - slotLeft);

            float baseSizeSp = (name.length() >= 4) ? 9.2f : (size >= 3 ? 10.0f : 10.5f);
            float basePx = baseSizeSp * density * scaleRatio;
            textPaint.setTextSize(basePx);
            int txtColor = parseColorOrDefault(r != null ? r.textColor : "", Color.WHITE);
            textPaint.setColor(txtColor);
            textPaint.setShadowLayer(1.5f * density, 0f, 0.8f * density, Color.argb(120, 0, 0, 0));

            float measured = textPaint.measureText(name);
            if (measured > slotWidth && measured > 0f) {
                textPaint.setTextSize(basePx * (slotWidth / measured));
            }
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            float baseline = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
            canvas.drawText(name, (slotLeft + slotRight) * 0.5f, baseline, textPaint);
        }

        // Layer 5: SuperFan V-flag overlay (right-aligned)
        if (vFlagBmp != null && !vFlagBmp.isRecycled() && vFlagBmp.getHeight() > 0) {
            int vw = Math.max(1, Math.round(vFlagBmp.getWidth() * ((float) h / (float) vFlagBmp.getHeight())));
            dstRect.set(w - vw, 0, w, h);
            canvas.drawBitmap(vFlagBmp, null, dstRect, imgPaint);
        }

        imgPaint.setColorFilter(null);
    }

    private void drawFallbackPill(Canvas canvas, int w, int h) {
        fallbackRect.set(0, 0, w, h);
        float radius = h * 0.5f;

        if (badge.extinguished != 0) {
            fallbackBgPaint.setShader(null);
            fallbackBgPaint.setColor(0xFF7B8494);
        } else if (badge.superFan) {
            fallbackBgPaint.setShader(new LinearGradient(
                    0, 0, w, 0, 0xFFFF8A1F, 0xFFFF4D2D, Shader.TileMode.CLAMP));
        } else {
            fallbackBgPaint.setShader(new LinearGradient(
                    0, 0, w, 0, 0xFFF59E0B, 0xFFEA580C, Shader.TileMode.CLAMP));
        }
        canvas.drawRoundRect(fallbackRect, radius, radius, fallbackBgPaint);
        fallbackBgPaint.setShader(null);

        String txt = buildFallbackText();
        fallbackLvPaint.setTextSize(h * 0.50f);
        int textColor = parseColorOrDefault(
                badge.render != null ? badge.render.levelColor : "", Color.WHITE);
        fallbackLvPaint.setColor(textColor);
        Paint.FontMetrics fm = fallbackLvPaint.getFontMetrics();
        float baseline = h * 0.5f - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(txt, w * 0.5f, baseline, fallbackLvPaint);
    }

    private String buildFallbackText() {
        if (badge == null) return "";
        String name = badge.badgeName != null ? badge.badgeName.trim() : "";
        return "Lv." + badge.level + (name.isEmpty() ? "" : (" " + name));
    }

    private static int parseColorOrDefault(String hex, int fallback) {
        if (hex == null || hex.trim().isEmpty()) return fallback;
        try {
            String s = hex.trim();
            if (!s.startsWith("#")) s = "#" + s;
            return Color.parseColor(s);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /**
     * Compact view for rendering Huya's native 3-tier Guard shield icon (right-cropped from 180x60 PNG).
     */
    public static final class GuardIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Rect dst = new Rect();
        private final int widthPx;
        private final int heightPx;
        private Bitmap bitmap;
        private int seq = 0;

        public GuardIconView(Context context) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            this.widthPx = Math.round(19f * d);
            this.heightPx = Math.round(22f * d);
        }

        public void loadGuardUrl(String guardIconUrl) {
            seq++;
            final int cur = seq;
            bitmap = null;
            if (guardIconUrl == null || guardIconUrl.trim().isEmpty()) {
                setVisibility(GONE);
                return;
            }
            setVisibility(VISIBLE);
            BadgeImageLoader.getInstance().loadGuardIcon(getContext(), guardIconUrl, bmp -> {
                if (cur != seq) return;
                bitmap = bmp;
                invalidate();
            });
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(widthPx, heightPx);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null || bitmap.isRecycled()) return;
            dst.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(bitmap, null, dst, paint);
        }
    }
}
