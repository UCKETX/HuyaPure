package com.huya.pure;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks GitHub Releases (UCKETX/HuyaPure) for newer versions and performs
 * in-app APK download + Android PackageInstaller invocation.
 */
public final class AppUpdater {

    private static final String TAG = "HuyaPure";
    public static final String GITHUB_REPO = "UCKETX/HuyaPure";
    public static final String RELEASES_PAGE_URL = "https://github.com/" + GITHUB_REPO + "/releases";
    private static final String LATEST_API_URL = "https://api.github.com/repos/" + GITHUB_REPO + "/releases/latest";
    private static final String LATEST_WEB_URL = "https://github.com/" + GITHUB_REPO + "/releases/latest";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int MAX_REDIRECTS = 6;

    private static final ExecutorService sExecutor = Executors.newCachedThreadPool();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private AppUpdater() {
    }

    public static final class ReleaseInfo {
        public final String tagName;
        public final String versionName;
        public final String title;
        public final String releaseNotes;
        public final String htmlUrl;
        public final String apkName;
        public final String apkDownloadUrl;
        public final long apkSizeBytes;
        public final boolean hasUpdate;

        public ReleaseInfo(
                String tagName,
                String versionName,
                String title,
                String releaseNotes,
                String htmlUrl,
                String apkName,
                String apkDownloadUrl,
                long apkSizeBytes,
                boolean hasUpdate
        ) {
            this.tagName = tagName;
            this.versionName = versionName;
            this.title = title;
            this.releaseNotes = releaseNotes;
            this.htmlUrl = htmlUrl;
            this.apkName = apkName;
            this.apkDownloadUrl = apkDownloadUrl;
            this.apkSizeBytes = apkSizeBytes;
            this.hasUpdate = hasUpdate;
        }
    }

    public interface CheckCallback {
        void onResult(ReleaseInfo info);
        void onError(String errorMessage);
    }

    public interface DownloadCallback {
        void onProgress(long downloadedBytes, long totalBytes);
        void onCompleted(File apkFile);
        void onCancelled();
        void onError(String errorMessage);
    }

    public static String getCurrentVersionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "0.0.0";
        } catch (Throwable ignored) {
            return "0.0.0";
        }
    }

    public static void checkForUpdate(Context context, final CheckCallback callback) {
        final String currentVersion = getCurrentVersionName(context.getApplicationContext());
        sExecutor.execute(() -> {
            try {
                ReleaseInfo info = fetchLatestRelease(currentVersion);
                sMain.post(() -> callback.onResult(info));
            } catch (Throwable t) {
                Log.w(TAG, "[Updater] checkForUpdate failed: " + t.getMessage(), t);
                final String msg = t.getMessage() != null && !t.getMessage().isEmpty()
                        ? t.getMessage()
                        : "网络请求失败，请检查网络连接";
                sMain.post(() -> callback.onError(msg));
            }
        });
    }

    private static ReleaseInfo fetchLatestRelease(String currentVersion) throws Exception {
        try {
            return fetchViaGitHubApi(currentVersion);
        } catch (Exception apiErr) {
            Log.w(TAG, "[Updater] GitHub API check failed (" + apiErr.getMessage()
                    + "), trying web redirect fallback");
            try {
                return fetchViaWebRedirectFallback(currentVersion);
            } catch (Exception fallbackErr) {
                throw apiErr;
            }
        }
    }

    private static ReleaseInfo fetchViaGitHubApi(String currentVersion) throws Exception {
        HttpURLConnection conn = openConnectionWithRedirects(LATEST_API_URL, true);
        try {
            int code = conn.getResponseCode();
            if (code == 404) {
                throw new IOException("仓库暂无已发布的 Release");
            }
            if (code == 403) {
                throw new IOException("GitHub API 限流（403），请稍后重试");
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("请求失败（HTTP " + code + "）");
            }
            String body = readStreamToString(conn.getInputStream());
            JSONObject obj = new JSONObject(body);

            String tagName = obj.optString("tag_name", "").trim();
            if (tagName.isEmpty()) {
                throw new IOException("未解析到最新版本号");
            }
            String versionName = normalizeVersion(tagName);
            String title = obj.optString("name", tagName).trim();
            String notes = obj.optString("body", "").trim();
            String htmlUrl = obj.optString("html_url", RELEASES_PAGE_URL).trim();

            String apkName = "";
            String apkUrl = "";
            long apkSize = 0L;
            JSONArray assets = obj.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) {
                        continue;
                    }
                    String name = asset.optString("name", "");
                    String url = asset.optString("browser_download_url", "");
                    if (name.endsWith(".apk") && !url.isEmpty()) {
                        apkName = name;
                        apkUrl = url;
                        apkSize = asset.optLong("size", 0L);
                        // Prefer release-signed APK if multiple APKs exist
                        if (name.toLowerCase(Locale.US).contains("release")) {
                            break;
                        }
                    }
                }
            }

            boolean hasUpdate = compareVersions(versionName, currentVersion) > 0;
            return new ReleaseInfo(
                    tagName,
                    versionName,
                    title.isEmpty() ? tagName : title,
                    notes.isEmpty() ? "包含功能优化与问题修复。" : notes,
                    htmlUrl.isEmpty() ? RELEASES_PAGE_URL : htmlUrl,
                    apkName,
                    apkUrl,
                    apkSize,
                    hasUpdate
            );
        } finally {
            conn.disconnect();
        }
    }

    private static ReleaseInfo fetchViaWebRedirectFallback(String currentVersion) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_WEB_URL).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "HuyaPure-Android");
        try {
            int code = conn.getResponseCode();
            String location = conn.getHeaderField("Location");
            if ((code == 301 || code == 302 || code == 307 || code == 308)
                    && location != null && location.contains("/releases/tag/")) {
                String tag = location.substring(location.lastIndexOf('/') + 1).trim();
                int qIdx = tag.indexOf('?');
                if (qIdx >= 0) {
                    tag = tag.substring(0, qIdx);
                }
                if (tag.isEmpty()) {
                    throw new IOException("无法解析最新版本标签");
                }
                String ver = normalizeVersion(tag);
                String apkName = "HuyaPure-" + tag + "-release.apk";
                String apkUrl = "https://github.com/" + GITHUB_REPO + "/releases/download/" + tag + "/" + apkName;
                boolean hasUpdate = compareVersions(ver, currentVersion) > 0;
                return new ReleaseInfo(
                        tag,
                        ver,
                        "虎伴 " + tag,
                        "请查看 GitHub Release 页面了解完整更新说明。",
                        location,
                        apkName,
                        apkUrl,
                        0L,
                        hasUpdate
                );
            }
            throw new IOException("无法获取最新 Release 重定向（HTTP " + code + "）");
        } finally {
            conn.disconnect();
        }
    }

    public static AtomicBoolean downloadApk(
            Context context,
            final ReleaseInfo info,
            final DownloadCallback callback
    ) {
        final Context appCtx = context.getApplicationContext();
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        sExecutor.execute(() -> {
            if (info == null || info.apkDownloadUrl == null || info.apkDownloadUrl.isEmpty()) {
                sMain.post(() -> callback.onError("该版本未附带可下载的 APK 安装包"));
                return;
            }

            File updatesDir = UpdateFileProvider.getUpdatesDir(appCtx);
            cleanupOldApks(updatesDir);

            String safeFileName = sanitizeFileName(
                    info.apkName != null && info.apkName.endsWith(".apk")
                            ? info.apkName
                            : ("HuyaPure-" + info.tagName + ".apk")
            );
            File finalFile = new File(updatesDir, safeFileName);
            File partFile = new File(updatesDir, safeFileName + ".part");
            if (partFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                partFile.delete();
            }

            HttpURLConnection conn = null;
            try {
                conn = openConnectionWithRedirects(info.apkDownloadUrl, false);
                int code = conn.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) {
                    throw new IOException("下载失败（HTTP " + code + "）");
                }

                long totalBytes = conn.getContentLengthLong();
                if (totalBytes <= 0L) {
                    totalBytes = info.apkSizeBytes;
                }
                final long finalTotal = totalBytes;

                long downloaded = 0L;
                long lastNotifyMs = 0L;
                byte[] buffer = new byte[16 * 1024];

                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     FileOutputStream out = new FileOutputStream(partFile)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (cancelled.get()) {
                            //noinspection ResultOfMethodCallIgnored
                            partFile.delete();
                            sMain.post(callback::onCancelled);
                            return;
                        }
                        out.write(buffer, 0, read);
                        downloaded += read;
                        long now = System.currentTimeMillis();
                        if (now - lastNotifyMs >= 100L || (finalTotal > 0L && downloaded >= finalTotal)) {
                            lastNotifyMs = now;
                            final long curDownloaded = downloaded;
                            sMain.post(() -> callback.onProgress(curDownloaded, finalTotal));
                        }
                    }
                    out.flush();
                }

                if (cancelled.get()) {
                    //noinspection ResultOfMethodCallIgnored
                    partFile.delete();
                    sMain.post(callback::onCancelled);
                    return;
                }

                if (finalFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    finalFile.delete();
                }
                if (!partFile.renameTo(finalFile)) {
                    throw new IOException("保存安装包文件失败");
                }

                final File completedFile = finalFile;
                sMain.post(() -> callback.onCompleted(completedFile));
            } catch (Throwable t) {
                //noinspection ResultOfMethodCallIgnored
                partFile.delete();
                if (cancelled.get()) {
                    sMain.post(callback::onCancelled);
                    return;
                }
                Log.e(TAG, "[Updater] downloadApk failed", t);
                final String msg = t.getMessage() != null && !t.getMessage().isEmpty()
                        ? t.getMessage()
                        : "下载中断，请检查网络后重试";
                sMain.post(() -> callback.onError(msg));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });

        return cancelled;
    }

    public static boolean canInstallPackages(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        return context.getPackageManager().canRequestPackageInstalls();
    }

    public static Intent buildInstallPermissionIntent(Context context) {
        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        return intent;
    }

    public static boolean launchApkInstaller(Context context, File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            return false;
        }
        try {
            Uri contentUri = UpdateFileProvider.getUriForApk(apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(contentUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "[Updater] launchApkInstaller failed", t);
            return false;
        }
    }

    public static String normalizeVersion(String raw) {
        if (raw == null) {
            return "0.0.0";
        }
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1).trim();
        }
        int dash = s.indexOf('-');
        if (dash > 0) {
            s = s.substring(0, dash);
        }
        return s;
    }

    public static int compareVersions(String v1, String v2) {
        String[] p1 = normalizeVersion(v1).split("\\.");
        String[] p2 = normalizeVersion(v2).split("\\.");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < p1.length ? parseIntSafe(p1[i]) : 0;
            int n2 = i < p2.length ? parseIntSafe(p2[i]) : 0;
            if (n1 != n2) {
                return Integer.compare(n1, n2);
            }
        }
        return 0;
    }

    public static String formatFileSize(long bytes) {
        if (bytes <= 0L) {
            return "";
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    private static HttpURLConnection openConnectionWithRedirects(String urlStr, boolean isApi) throws IOException {
        String currentUrl = urlStr;
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(currentUrl).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "HuyaPure-Android");
            if (isApi) {
                conn.setRequestProperty("Accept", "application/vnd.github+json");
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307
                    || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new IOException("重定向目标为空");
                }
                currentUrl = new URL(new URL(currentUrl), loc).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("重定向次数过多");
    }

    private static String readStreamToString(InputStream in) throws IOException {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = stream.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void cleanupOldApks(File updatesDir) {
        File[] files = updatesDir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f != null && f.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static String sanitizeFileName(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!cleaned.endsWith(".apk")) {
            cleaned = cleaned + ".apk";
        }
        return cleaned;
    }

    private static int parseIntSafe(String part) {
        if (part == null) {
            return 0;
        }
        String digits = part.replaceAll("[^0-9].*$", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
