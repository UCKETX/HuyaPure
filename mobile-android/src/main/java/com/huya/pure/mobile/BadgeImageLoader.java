package com.huya.pure.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight asynchronous image loader with in-memory {@link LruCache} and disk cache
 * for Huya 3x Retina official fan badge assets, right-cropped Guard shield PNGs, and avatars.
 */
public final class BadgeImageLoader {

    public interface Callback {
        void onLoaded(Bitmap bitmap);
    }

    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    private static final BadgeImageLoader INSTANCE = new BadgeImageLoader();

    private final LruCache<String, Bitmap> memCache;
    private final ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BadgeImageLoader() {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024L);
        int cacheKb = Math.max(4096, Math.min(16384, maxKb / 8));
        memCache = new LruCache<String, Bitmap>(cacheKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "huban-img");
            t.setDaemon(true);
            return t;
        });
    }

    public static BadgeImageLoader getInstance() {
        return INSTANCE;
    }

    public Bitmap getCached(String key) {
        if (key == null || key.isEmpty()) return null;
        return memCache.get(key);
    }

    public void load(Context context, String rawUrl, Callback callback) {
        String url = normalizeUrl(rawUrl);
        if (url.isEmpty()) {
            if (callback != null) callback.onLoaded(null);
            return;
        }
        Bitmap hit = memCache.get(url);
        if (hit != null && !hit.isRecycled()) {
            if (callback != null) callback.onLoaded(hit);
            return;
        }
        final Context appCtx = context.getApplicationContext();
        executor.execute(() -> {
            Bitmap bmp = fetchAndDecode(appCtx, url);
            if (bmp != null) {
                memCache.put(url, bmp);
            }
            if (callback != null) {
                mainHandler.post(() -> callback.onLoaded(bmp));
            }
        });
    }

    /**
     * Loads Huya's 180x60 right-aligned Guard shield PNG and right-crops it to
     * {@code x = width * 132 / 180 .. width} when the image has the wide strip aspect ratio.
     */
    public void loadGuardIcon(Context context, String rawUrl, Callback callback) {
        String url = normalizeUrl(rawUrl);
        if (url.isEmpty()) {
            if (callback != null) callback.onLoaded(null);
            return;
        }
        final String cacheKey = "guard_crop::" + url;
        Bitmap hit = memCache.get(cacheKey);
        if (hit != null && !hit.isRecycled()) {
            if (callback != null) callback.onLoaded(hit);
            return;
        }
        final Context appCtx = context.getApplicationContext();
        executor.execute(() -> {
            Bitmap raw = memCache.get(url);
            if (raw == null || raw.isRecycled()) {
                raw = fetchAndDecode(appCtx, url);
                if (raw != null) {
                    memCache.put(url, raw);
                }
            }
            Bitmap cropped = cropRightGuardShield(raw);
            if (cropped != null) {
                memCache.put(cacheKey, cropped);
            }
            if (callback != null) {
                mainHandler.post(() -> callback.onLoaded(cropped));
            }
        });
    }

    public void loadCircleAvatar(Context context, String rawUrl, Callback callback) {
        String url = normalizeUrl(rawUrl);
        if (url.isEmpty()) {
            if (callback != null) callback.onLoaded(null);
            return;
        }
        final String cacheKey = "circle::" + url;
        Bitmap hit = memCache.get(cacheKey);
        if (hit != null && !hit.isRecycled()) {
            if (callback != null) callback.onLoaded(hit);
            return;
        }
        final Context appCtx = context.getApplicationContext();
        executor.execute(() -> {
            Bitmap raw = fetchAndDecode(appCtx, url);
            Bitmap circled = toCircleBitmap(raw);
            if (circled != null) {
                memCache.put(cacheKey, circled);
            }
            if (callback != null) {
                mainHandler.post(() -> callback.onLoaded(circled));
            }
        });
    }

    private static Bitmap cropRightGuardShield(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        // Official Huya guard overlay PNGs are 180x60 (3:1) with the shield in the rightmost 48px.
        if (w >= h * 2) {
            int startX = (w * 132) / 180;
            int cropW = w - startX;
            if (cropW > 4 && startX >= 0 && startX + cropW <= w) {
                try {
                    return Bitmap.createBitmap(src, startX, 0, cropW, h);
                } catch (Throwable ignored) {
                }
            }
        }
        return src;
    }

    private static Bitmap toCircleBitmap(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        int size = Math.min(w, h);
        if (size <= 0) return null;
        int target = Math.min(size, 144);
        Bitmap squared = Bitmap.createBitmap(src, (w - size) / 2, (h - size) / 2, size, size);
        Bitmap scaled = (size == target) ? squared : Bitmap.createScaledBitmap(squared, target, target, true);
        Bitmap out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float r = target * 0.5f;
        canvas.drawCircle(r, r, r, paint);
        return out;
    }

    private static Bitmap fetchAndDecode(Context appCtx, String urlStr) {
        File cacheDir = new File(appCtx.getCacheDir(), "badge_imgs");
        if (!cacheDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            cacheDir.mkdirs();
        }
        File cacheFile = new File(cacheDir, sha1(urlStr) + ".bin");
        if (cacheFile.isFile() && cacheFile.length() > 0L) {
            byte[] cached = readFile(cacheFile);
            Bitmap decoded = decodeBytes(cached);
            if (decoded != null) {
                return decoded;
            }
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > MAX_IMAGE_BYTES) {
                        return null;
                    }
                }
                byte[] bytes = bos.toByteArray();
                Bitmap bmp = decodeBytes(bytes);
                if (bmp != null) {
                    writeFileQuietly(cacheFile, bytes);
                }
                return bmp;
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static Bitmap decodeBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(ByteBuffer.wrap(bytes));
                return ImageDecoder.decodeBitmap(src, (decoder, info, source) ->
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
            } catch (Throwable ignored) {
            }
        }
        try {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String normalizeUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("//")) return "https:" + s;
        if (s.startsWith("http://")) return "https://" + s.substring(7);
        return s;
    }

    private static byte[] readFile(File f) {
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

    private static void writeFileQuietly(File f, byte[] data) {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        } catch (Throwable ignored) {
        }
    }

    private static String sha1(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return String.valueOf(input.hashCode());
        }
    }
}
