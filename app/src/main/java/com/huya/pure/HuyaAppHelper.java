package com.huya.pure;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Helper utilities for managing the target Huya app (com.duowan.kiwi), including
 * Root-assisted restart (with App Details fallback) and fan-badge cache cleanup.
 */
public final class HuyaAppHelper {

    private static final String TAG = "HuyaPure";
    private static final String CUSTOM_BADGE_CACHE_DIR_NAME = "huyapure_custom_badge_cache";
    private static final String REMOTE_CACHE_PATH_PRIMARY =
            "/data/data/" + AutoClaimProtocol.TARGET_PACKAGE + "/cache/" + CUSTOM_BADGE_CACHE_DIR_NAME;
    private static final String REMOTE_CACHE_PATH_USER0 =
            "/data/user/0/" + AutoClaimProtocol.TARGET_PACKAGE + "/cache/" + CUSTOM_BADGE_CACHE_DIR_NAME;
    private static final long SU_TIMEOUT_SECONDS = 5L;
    private static final long RESTART_DELAY_MS = 250L;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();
    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private HuyaAppHelper() {
    }

    public enum RestartResult {
        RESTARTED_WITH_ROOT,
        OPENED_APP_SETTINGS,
        TARGET_NOT_INSTALLED,
        FAILED
    }

    public interface RestartCallback {
        void onResult(RestartResult result, String message);
    }

    public interface ClearCacheCallback {
        void onComplete(boolean clearedRemoteDiskCache, String summary);
    }

    public static boolean isHuyaInstalled(Context context) {
        if (context == null) {
            return false;
        }
        try {
            context.getPackageManager().getPackageInfo(AutoClaimProtocol.TARGET_PACKAGE, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void restartHuyaApp(final Context context, final RestartCallback callback) {
        if (context == null) {
            postRestartResult(callback, RestartResult.FAILED, "上下文无效，无法重启虎牙直播");
            return;
        }
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;

        if (!isHuyaInstalled(appCtx)) {
            postRestartResult(
                    callback,
                    RestartResult.TARGET_NOT_INSTALLED,
                    "未安装虎牙直播 (" + AutoClaimProtocol.TARGET_PACKAGE + ")"
            );
            return;
        }

        sExecutor.execute(() -> {
            boolean stoppedWithRoot = runSuCommand(
                    "am force-stop " + AutoClaimProtocol.TARGET_PACKAGE,
                    SU_TIMEOUT_SECONDS
            );
            if (stoppedWithRoot) {
                try {
                    Thread.sleep(RESTART_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }

                sMain.post(() -> {
                    try {
                        Intent launchIntent = appCtx.getPackageManager()
                                .getLaunchIntentForPackage(AutoClaimProtocol.TARGET_PACKAGE);
                        if (launchIntent == null) {
                            deliverRestartResult(
                                    callback,
                                    RestartResult.FAILED,
                                    "已强行停止虎牙直播，但未找到启动入口，请手动打开"
                            );
                            return;
                        }
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        appCtx.startActivity(launchIntent);
                        deliverRestartResult(
                                callback,
                                RestartResult.RESTARTED_WITH_ROOT,
                                "已重启虎牙直播，净化设置已生效"
                        );
                    } catch (Throwable t) {
                        Log.w(TAG, "[HuyaAppHelper] Failed to relaunch Huya after force-stop", t);
                        deliverRestartResult(
                                callback,
                                RestartResult.FAILED,
                                "已强行停止虎牙直播，但重新启动失败，请手动打开"
                        );
                    }
                });
                return;
            }

            sMain.post(() -> {
                try {
                    Intent settingsIntent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    settingsIntent.setData(Uri.parse("package:" + AutoClaimProtocol.TARGET_PACKAGE));
                    settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appCtx.startActivity(settingsIntent);
                    deliverRestartResult(
                            callback,
                            RestartResult.OPENED_APP_SETTINGS,
                            "未获取到 Root，已打开虎牙应用信息页，请点「强行停止」后重新打开"
                    );
                } catch (Throwable t) {
                    Log.w(TAG, "[HuyaAppHelper] Failed to open Huya app details settings", t);
                    deliverRestartResult(
                            callback,
                            RestartResult.FAILED,
                            "未获取到 Root，且无法打开虎牙应用信息页"
                    );
                }
            });
        });
    }

    public static void clearFanBadgeCache(final Context context, final ClearCacheCallback callback) {
        if (context == null) {
            postClearCacheResult(callback, false, "清理缓存失败：上下文无效");
            return;
        }
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;

        ModulePrefs.openStatePrefs(appCtx).edit()
                .remove(ModulePrefs.STATE_BADGES_JSON)
                .remove(ModulePrefs.STATE_BADGES_UPDATE_TIME)
                .apply();

        sExecutor.execute(() -> {
            boolean clearedRemote = false;
            if (isHuyaInstalled(appCtx)) {
                String rmCmd = "rm -rf " + REMOTE_CACHE_PATH_PRIMARY + " " + REMOTE_CACHE_PATH_USER0;
                clearedRemote = runSuCommand(rmCmd, SU_TIMEOUT_SECONDS);
            }
            final boolean finalClearedRemote = clearedRemote;
            final String summary = finalClearedRemote
                    ? "已清理本地与定制粉丝牌缓存，点击「同步刷新」即可重新拉取"
                    : "已清理本地粉丝牌缓存，点击「同步刷新」即可重新拉取";
            postClearCacheResult(callback, finalClearedRemote, summary);
        });
    }

    private static boolean runSuCommand(String command, long timeoutSeconds) {
        Process proc = null;
        try {
            Log.i(TAG, "[HuyaAppHelper] Invoking su: " + command);
            ProcessBuilder pb = new ProcessBuilder("su", "-c", command);
            pb.redirectErrorStream(true);
            proc = pb.start();

            try {
                proc.getOutputStream().close();
            } catch (Throwable ignored) {
            }

            final InputStream inputStream = proc.getInputStream();
            final StringBuffer outputBuf = new StringBuffer();
            Thread drainer = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (outputBuf.length() < 1024) {
                            outputBuf.append(line).append('\n');
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "HuyaPure-SuDrain");
            drainer.setDaemon(true);
            drainer.start();

            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                Log.w(TAG, "[HuyaAppHelper] su command timed out after " + timeoutSeconds + "s: " + command);
                return false;
            }

            try {
                drainer.join(200L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            int exitCode = proc.exitValue();
            String output = outputBuf.toString().trim();
            Log.i(TAG, "[HuyaAppHelper] su exit=" + exitCode
                    + (output.isEmpty() ? "" : (", output=" + output)));
            return exitCode == 0;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "[HuyaAppHelper] su command interrupted: " + command);
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "[HuyaAppHelper] su unavailable or failed: " + t.getMessage());
            return false;
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void postRestartResult(
            final RestartCallback callback,
            final RestartResult result,
            final String message
    ) {
        if (callback == null) {
            return;
        }
        sMain.post(() -> deliverRestartResult(callback, result, message));
    }

    private static void deliverRestartResult(
            RestartCallback callback,
            RestartResult result,
            String message
    ) {
        if (callback != null) {
            callback.onResult(result, message);
        }
    }

    private static void postClearCacheResult(
            final ClearCacheCallback callback,
            final boolean clearedRemoteDiskCache,
            final String summary
    ) {
        if (callback == null) {
            return;
        }
        sMain.post(() -> callback.onComplete(clearedRemoteDiskCache, summary));
    }
}
