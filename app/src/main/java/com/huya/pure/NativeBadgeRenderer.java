package com.huya.pure;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Renders Huya's authentic native fan badges — including anchor custom fan badges (定制粉丝牌),
 * standard fan badges (粉丝牌 / 超粉牌 / 年费超粉 / 超粉Plus / 熄灭牌), and native guard shields
 * (初爱守护 / 超级守护 / 至尊守护) — dynamically at runtime inside com.duowan.kiwi.
 * Zero third-party artwork is bundled in HuyaPure's APK.
 */
final class NativeBadgeRenderer {

    private static final String TAG = "HuyaPure";
    private static final int MAX_FLOOR_LEVEL = 52;
    private static final int MIN_FLOOR_LEVEL = 1;
    private static final int HTTP_TIMEOUT_MS = 5000;

    private NativeBadgeRenderer() {
    }

    static final class Session implements AutoCloseable {
        private final Context context;
        private final ZipFile commonBadgeZip;
        private final ZipFile guardZip;
        private final File diskCacheDir;

        Session(Context context) {
            this.context = context;
            this.commonBadgeZip = openLatestZip(context, ".commonbadge/0");
            this.guardZip = openLatestZip(context, ".privilege/guard");
            File cache = null;
            if (context != null && context.getCacheDir() != null) {
                cache = new File(context.getCacheDir(), "huyapure_custom_badge_cache");
                if (!cache.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    cache.mkdirs();
                }
            }
            this.diskCacheDir = cache;
        }

        boolean hasCommonBadgeAssets() {
            return commonBadgeZip != null;
        }

        /**
         * Composites a custom anchor fan badge (定制粉丝牌) from Huya's official BadgeEntity layer URLs
         * (TYPE_FLOOR, TYPE_ICON, TYPE_LEVEL, TYPE_VFLAG) in the exact FansLabelView layering order.
         * Must be called off the main thread. Returns empty string on failure so caller can fall back.
         */
        String renderCustomFanBadgePngBase64(
                String badgeName,
                String floorUrl,
                String iconUrl,
                String levelUrl,
                String vFlagUrl,
                int fallbackSfIdentityId
        ) {
            if (floorUrl == null || floorUrl.isEmpty()) {
                return "";
            }
            Bitmap floorBmp = null;
            Bitmap iconBmp = null;
            Bitmap levelBmp = null;
            Bitmap vFlagBmp = null;
            Bitmap outBmp = null;
            try {
                floorBmp = fetchAndDecodeUrlBitmap(floorUrl);
                if (floorBmp == null) {
                    return "";
                }
                if (iconUrl != null && !iconUrl.isEmpty()) {
                    iconBmp = fetchAndDecodeUrlBitmap(iconUrl);
                }
                if (levelUrl != null && !levelUrl.isEmpty()) {
                    levelBmp = fetchAndDecodeUrlBitmap(levelUrl);
                }
                if (vFlagUrl != null && !vFlagUrl.isEmpty()) {
                    vFlagBmp = fetchAndDecodeUrlBitmap(vFlagUrl);
                }
                if (vFlagBmp == null && fallbackSfIdentityId >= 1 && fallbackSfIdentityId <= 4
                        && commonBadgeZip != null) {
                    vFlagBmp = decodeZipBitmap(commonBadgeZip, "identity/" + fallbackSfIdentityId + ".png");
                }

                int floorW = floorBmp.getWidth();
                int floorH = floorBmp.getHeight();
                if (floorW <= 0 || floorH <= 0) {
                    return "";
                }

                Log.i(TAG, "[NativeBadge] custom layers (" + badgeName + "): floor="
                        + floorW + "x" + floorH
                        + ", icon=" + bmpDim(iconBmp)
                        + ", level=" + bmpDim(levelBmp)
                        + ", vFlag=" + bmpDim(vFlagBmp));

                Paint imgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

                // Scale overlay layers proportionally to match floor height (FansLabelView uses MATCH_PARENT height).
                int outH = floorH;
                int outW = floorW;
                int vFlagDrawLeft = 0;
                int vFlagDrawW = 0;
                int vFlagDrawH = outH;
                boolean vFlagIsSeparateSuffix = false;

                if (vFlagBmp != null && vFlagBmp.getHeight() > 0) {
                    float scale = (float) outH / (float) vFlagBmp.getHeight();
                    vFlagDrawW = Math.max(1, Math.round(vFlagBmp.getWidth() * scale));
                    if (vFlagDrawW < floorW * 0.75f) {
                        // Separate suffix icon (like identity/1..4.png): hug right edge cleanly.
                        vFlagIsSeparateSuffix = true;
                        int[] bounds = findOpaqueHorizontalBounds(vFlagBmp);
                        int visLeftScaled = Math.round(bounds[0] * scale);
                        int visRightScaled = Math.round(bounds[1] * scale);
                        int visualOverlap = Math.round(floorH * 0.14f);
                        vFlagDrawLeft = Math.max(0, floorW - visualOverlap - visLeftScaled);
                        outW = Math.max(floorW, vFlagDrawLeft + visRightScaled + 2);
                    } else {
                        // Full-width badge overlay (FansLabelView TYPE_VFLAG with Gravity.END).
                        outW = Math.max(floorW, vFlagDrawW);
                        vFlagDrawLeft = outW - vFlagDrawW;
                    }
                }

                outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(outBmp);

                // Layer 1: Custom floor background
                canvas.drawBitmap(floorBmp, 0f, 0f, imgPaint);

                // Layer 2: Custom anchor emblem icon (left-aligned, full height)
                int iconDrawnWidth = Math.round(floorH * 0.94f);
                if (iconBmp != null && iconBmp.getHeight() > 0) {
                    float iconScale = (float) outH / (float) iconBmp.getHeight();
                    int iconW = Math.max(1, Math.round(iconBmp.getWidth() * iconScale));
                    canvas.drawBitmap(iconBmp, null, new Rect(0, 0, iconW, outH), imgPaint);
                    // Determine actual visual right edge of the left emblem so text never collides.
                    int[] iconBounds = findOpaqueHorizontalBounds(iconBmp);
                    int visRight = Math.round(iconBounds[1] * iconScale);
                    if (visRight > 0 && visRight < floorW * 0.65f) {
                        iconDrawnWidth = Math.max(Math.round(floorH * 0.88f), visRight + Math.round(floorH * 0.04f));
                    }
                }

                // Layer 3: Level number overlay (left-aligned, full height)
                if (levelBmp != null && levelBmp.getHeight() > 0) {
                    float lvScale = (float) outH / (float) levelBmp.getHeight();
                    int lvW = Math.max(1, Math.round(levelBmp.getWidth() * lvScale));
                    canvas.drawBitmap(levelBmp, null, new Rect(0, 0, lvW, outH), imgPaint);
                }

                // Layer 4: Badge name centered cleanly in the capsule text slot
                String safeName = badgeName != null ? badgeName.trim() : "";
                if (!safeName.isEmpty()) {
                    float slotLeft = Math.min(iconDrawnWidth, floorW * 0.52f);
                    float rightPad = vFlagIsSeparateSuffix ? (floorH * 0.24f) : (floorH * 0.16f);
                    float slotRight = Math.max(slotLeft + 10f, floorW - rightPad);
                    drawBadgeName(canvas, safeName, slotLeft, slotRight, outH * 0.5f, floorH, computeCharBucket(safeName));
                }

                // Layer 5: VFlag / SuperFan suffix
                if (vFlagBmp != null && vFlagDrawW > 0) {
                    canvas.drawBitmap(
                            vFlagBmp,
                            null,
                            new Rect(vFlagDrawLeft, 0, vFlagDrawLeft + vFlagDrawW, vFlagDrawH),
                            imgPaint
                    );
                }

                return encodePngBase64(outBmp);
            } catch (Throwable t) {
                Log.w(TAG, "[NativeBadge] renderCustomFanBadge failed: " + t.getMessage());
                return "";
            } finally {
                recycleQuietly(outBmp);
                recycleQuietly(vFlagBmp);
                recycleQuietly(levelBmp);
                recycleQuietly(iconBmp);
                recycleQuietly(floorBmp);
            }
        }

        /**
         * Composites a standard native Huya fan badge (floor capsule with baked level heart +
         * badge name + optional SuperFan/Annual/Plus V suffix) and returns a compact PNG Base64 string.
         */
        String renderFanBadgePngBase64(String badgeName, int level, boolean extinguished, int sfIdentityId) {
            if (commonBadgeZip == null) {
                return "";
            }
            Bitmap floorBmp = null;
            Bitmap suffixBmp = null;
            Bitmap outBmp = null;
            try {
                String safeName = badgeName != null ? badgeName.trim() : "";
                int charBucket = computeCharBucket(safeName);
                int clampedLevel = Math.max(MIN_FLOOR_LEVEL, Math.min(MAX_FLOOR_LEVEL, level));
                int extDir = extinguished ? 1 : 0;

                floorBmp = decodeZipBitmap(commonBadgeZip,
                        "floor/" + charBucket + "/" + extDir + "/" + clampedLevel + ".png");
                if (floorBmp == null && extDir == 1) {
                    floorBmp = decodeZipBitmap(commonBadgeZip,
                            "floor/" + charBucket + "/0/" + clampedLevel + ".png");
                }
                if (floorBmp == null && charBucket != 2) {
                    floorBmp = decodeZipBitmap(commonBadgeZip,
                            "floor/2/" + extDir + "/" + clampedLevel + ".png");
                }
                if (floorBmp == null) {
                    return "";
                }

                // Suffix identity 1..4 = 超粉(V), 年费超粉(V年), 超粉Plus(V+), 年费超粉Plus(V+年).
                if (sfIdentityId >= 1 && sfIdentityId <= 4) {
                    suffixBmp = decodeZipBitmap(commonBadgeZip, "identity/" + sfIdentityId + ".png");
                }

                int floorW = floorBmp.getWidth();
                int floorH = floorBmp.getHeight();
                int outW = floorW;
                int outH = floorH;
                int suffixLeft = floorW;
                int suffixTop = 0;

                if (suffixBmp != null) {
                    int[] bounds = findOpaqueHorizontalBounds(suffixBmp);
                    int visLeft = bounds[0];
                    int visRight = bounds[1];
                    int visualOverlap = Math.round(floorH * 0.14f);
                    suffixLeft = Math.max(0, floorW - visualOverlap - visLeft);
                    outW = Math.max(floorW, suffixLeft + visRight + 2);
                    outH = Math.max(floorH, suffixBmp.getHeight());
                    suffixTop = (outH - suffixBmp.getHeight()) / 2;
                }

                outBmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(outBmp);
                int floorTop = (outH - floorH) / 2;
                canvas.drawBitmap(floorBmp, 0f, floorTop, null);

                if (!safeName.isEmpty()) {
                    float slotLeft = floorH * 0.94f;
                    float rightPad = suffixBmp != null ? (floorH * 0.24f) : (floorH * 0.16f);
                    float slotRight = Math.max(slotLeft + 10f, floorW - rightPad);
                    drawBadgeName(canvas, safeName, slotLeft, slotRight, floorTop + floorH * 0.5f, floorH, charBucket);
                }

                if (suffixBmp != null) {
                    canvas.drawBitmap(suffixBmp, suffixLeft, suffixTop, null);
                }

                return encodePngBase64(outBmp);
            } catch (Throwable t) {
                Log.w(TAG, "[NativeBadge] renderFanBadge failed: " + t.getMessage());
                return "";
            } finally {
                recycleQuietly(outBmp);
                recycleQuietly(suffixBmp);
                recycleQuietly(floorBmp);
            }
        }

        /**
         * Returns Huya's native guard icon PNG as Base64 for guardLevel 1 (初爱守护),
         * 2 (超级守护), or 3 (至尊守护). Prefers the modern commonbadge identity/11..13 shields,
         * falling back to .privilege/guard/guard_1..3.png.
         */
        String renderGuardIconPngBase64(int guardLevel) {
            if (guardLevel < 1 || guardLevel > 3) {
                return "";
            }
            byte[] raw = null;
            if (commonBadgeZip != null) {
                raw = readZipEntryBytes(commonBadgeZip, "identity/" + (10 + guardLevel) + ".png");
            }
            if (raw == null && guardZip != null) {
                raw = readZipEntryBytes(guardZip, "guard_" + guardLevel + ".png");
            }
            if (raw == null || raw.length == 0) {
                return "";
            }
            return Base64.encodeToString(raw, Base64.NO_WRAP);
        }

        private Bitmap fetchAndDecodeUrlBitmap(String urlStr) {
            byte[] bytes = fetchUrlBytesCached(urlStr);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return decodeImageBytes(bytes);
        }

        private byte[] fetchUrlBytesCached(String urlStr) {
            if (urlStr == null || urlStr.isEmpty()) {
                return null;
            }
            String normalized = urlStr.startsWith("//") ? ("https:" + urlStr) : urlStr;
            if (normalized.startsWith("http://")) {
                normalized = "https://" + normalized.substring("http://".length());
            }

            File cacheFile = diskCacheDir != null ? new File(diskCacheDir, sha1Hex(normalized) + ".img") : null;
            if (cacheFile != null && cacheFile.isFile() && cacheFile.length() > 0L) {
                byte[] cached = readFileBytes(cacheFile);
                if (cached != null && cached.length > 0) {
                    return cached;
                }
            }

            HttpURLConnection conn = null;
            try {
                URL u = new URL(normalized);
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(HTTP_TIMEOUT_MS);
                conn.setReadTimeout(HTTP_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "HuyaApp");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    Log.w(TAG, "[NativeBadge] HTTP " + code + " for " + normalized);
                    return null;
                }
                try (InputStream in = conn.getInputStream();
                     ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                        if (bos.size() > 2 * 1024 * 1024) {
                            return null;
                        }
                    }
                    byte[] data = bos.toByteArray();
                    if (cacheFile != null && data.length > 0) {
                        writeFileBytesQuietly(cacheFile, data);
                    }
                    return data;
                }
            } catch (Throwable t) {
                Log.w(TAG, "[NativeBadge] fetch failed (" + normalized + "): " + t.getMessage());
                return null;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        @Override
        public void close() {
            closeQuietly(commonBadgeZip);
            closeQuietly(guardZip);
        }
    }

    private static void drawBadgeName(
            Canvas canvas,
            String safeName,
            float slotLeft,
            float slotRight,
            float centerY,
            int floorH,
            int charBucket
    ) {
        float slotWidth = Math.max(10f, slotRight - slotLeft);
        float centerX = (slotLeft + slotRight) * 0.5f;

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(Color.WHITE);

        float baseSize = charBucket >= 3 ? (floorH * 0.44f) : (floorH * 0.47f);
        textPaint.setTextSize(baseSize);
        float measured = textPaint.measureText(safeName);
        float maxTextW = slotWidth - 4f;
        if (measured > maxTextW && measured > 0f) {
            textPaint.setTextSize(baseSize * (maxTextW / measured));
        }

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = centerY - (fm.ascent + fm.descent) * 0.5f;
        textPaint.setShadowLayer(1.8f, 0f, 1.0f, Color.argb(100, 0, 0, 0));
        canvas.drawText(safeName, centerX, baseline, textPaint);
    }

    private static Bitmap decodeImageBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        // Use ImageDecoder on Android 9+ so animated WebP custom badges decode cleanly to a software ARGB_8888 bitmap.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(ByteBuffer.wrap(bytes));
                return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } catch (Throwable ignored) {
            }
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private static int[] findOpaqueHorizontalBounds(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int minX = w;
        int maxX = 0;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if (((row[x] >>> 24) & 0xFF) > 20) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (x + 1 > maxX) {
                        maxX = x + 1;
                    }
                }
            }
        }
        if (minX >= maxX) {
            return new int[]{0, w};
        }
        return new int[]{minX, maxX};
    }

    private static int computeCharBucket(String text) {
        if (text == null || text.isEmpty()) {
            return 2;
        }
        float units = 0f;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            units += (c <= 0x7F) ? 0.6f : 1.0f;
        }
        return units > 2.25f ? 3 : 2;
    }

    private static String bmpDim(Bitmap b) {
        return b == null ? "null" : (b.getWidth() + "x" + b.getHeight());
    }

    private static ZipFile openLatestZip(Context context, String relativeDir) {
        if (context == null) {
            return null;
        }
        try {
            File filesDir = context.getFilesDir();
            if (filesDir == null) {
                return null;
            }
            File dir = new File(filesDir, relativeDir);
            if (!dir.isDirectory()) {
                return null;
            }
            File[] zips = dir.listFiles((d, name) -> name != null && name.endsWith(".zip"));
            if (zips == null || zips.length == 0) {
                return null;
            }
            Arrays.sort(zips, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    int lm = Long.compare(b.lastModified(), a.lastModified());
                    return lm != 0 ? lm : Long.compare(b.length(), a.length());
                }
            });
            for (File f : zips) {
                if (f.isFile() && f.length() > 0L) {
                    try {
                        return new ZipFile(f);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[NativeBadge] openLatestZip(" + relativeDir + ") failed: " + t.getMessage());
        }
        return null;
    }

    private static Bitmap decodeZipBitmap(ZipFile zip, String entryName) {
        byte[] bytes = readZipEntryBytes(zip, entryName);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private static byte[] readZipEntryBytes(ZipFile zip, String entryName) {
        if (zip == null || entryName == null) {
            return null;
        }
        try {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry);
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] readFileBytes(File f) {
        try (InputStream in = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeFileBytesQuietly(File f, byte[] data) {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        } catch (Throwable ignored) {
        }
    }

    private static String sha1Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return String.valueOf(s.hashCode());
        }
    }

    private static String encodePngBase64(Bitmap bmp) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)) {
            return "";
        }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    private static void recycleQuietly(Bitmap b) {
        if (b != null && !b.isRecycled()) {
            b.recycle();
        }
    }

    private static void closeQuietly(ZipFile z) {
        if (z != null) {
            try {
                z.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
