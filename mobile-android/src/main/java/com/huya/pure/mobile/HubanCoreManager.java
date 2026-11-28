package com.huya.pure.mobile;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the lifecycle of the embedded Go core binary (libhubancore.so).
 * Launches the standalone loopback server, injects Android active DNS servers
 * and local timezone, waits for HUBAN_READY_PORT, verifies /api/health, and
 * restarts automatically if the child process exits.
 */
public final class HubanCoreManager {

    private static final String TAG = "HubanCore";
    private static final String READY_PREFIX = "HUBAN_READY_PORT=";
    private static final long START_TIMEOUT_SEC = 10L;

    private static final HubanCoreManager INSTANCE = new HubanCoreManager();

    private final Object lock = new Object();
    private Process process;
    private volatile int activePort = 0;
    private volatile String lastError = "";

    private HubanCoreManager() {
    }

    public static HubanCoreManager getInstance() {
        return INSTANCE;
    }

    public int getPort() {
        return activePort;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isRunning() {
        synchronized (lock) {
            return process != null && process.isAlive() && activePort > 0;
        }
    }

    /**
     * Ensures the embedded Go core process is running and healthy, starting or
     * restarting it as needed. Must be called off the main thread.
     *
     * @return Loopback base URL, e.g. {@code http://127.0.0.1:43210}
     */
    public String ensureStarted(Context context) throws IOException {
        Context appCtx = context.getApplicationContext();
        synchronized (lock) {
            if (process != null && process.isAlive() && activePort > 0) {
                return "http://127.0.0.1:" + activePort;
            }
            stopLocked();
            startLocked(appCtx);
            return "http://127.0.0.1:" + activePort;
        }
    }

    /**
     * Forces a clean restart of the Go core (e.g., if health check fails).
     */
    public String restart(Context context) throws IOException {
        Context appCtx = context.getApplicationContext();
        synchronized (lock) {
            stopLocked();
            startLocked(appCtx);
            return "http://127.0.0.1:" + activePort;
        }
    }

    public void stop() {
        synchronized (lock) {
            stopLocked();
        }
    }

    private void startLocked(Context appCtx) throws IOException {
        File nativeDir = new File(appCtx.getApplicationInfo().nativeLibraryDir);
        File binary = new File(nativeDir, "libhubancore.so");
        if (!binary.exists() || !binary.isFile()) {
            lastError = "未找到核心引擎文件: " + binary.getAbsolutePath();
            throw new IOException(lastError);
        }
        if (!binary.canExecute()) {
            //noinspection ResultOfMethodCallIgnored
            binary.setExecutable(true, false);
        }

        File dataDir = new File(appCtx.getFilesDir(), "huban_data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            lastError = "无法创建数据目录: " + dataDir.getAbsolutePath();
            throw new IOException(lastError);
        }

        ProcessBuilder pb = new ProcessBuilder(
                binary.getAbsolutePath(),
                "-addr", "127.0.0.1:0",
                "-data", dataDir.getAbsolutePath()
        );
        pb.directory(dataDir);
        pb.redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        TimeZone tz = TimeZone.getDefault();
        env.put("TZ", tz.getID());
        env.put("HUBAN_TZ_OFFSET_SEC", String.valueOf(tz.getOffset(System.currentTimeMillis()) / 1000));
        String dnsServers = collectDnsServers(appCtx);
        if (!dnsServers.isEmpty()) {
            env.put("HUBAN_DNS_SERVERS", dnsServers);
        }
        File caFile = exportAndroidCaBundle(dataDir);
        if (caFile != null && caFile.isFile() && caFile.length() > 0) {
            env.put("SSL_CERT_FILE", caFile.getAbsolutePath());
        }
        env.put("SSL_CERT_DIR", "/apex/com.android.conscrypt/cacerts:/system/etc/security/cacerts");

        Log.i(TAG, "Launching libhubancore.so (dns=" + dnsServers + ", tz=" + env.get("TZ")
                + ", caFile=" + (caFile != null ? caFile.length() + "B" : "none") + ")");
        final Process proc = pb.start();
        this.process = proc;

        final CountDownLatch readyLatch = new CountDownLatch(1);
        final AtomicInteger parsedPort = new AtomicInteger(0);
        final StringBuilder startupLogs = new StringBuilder();

        Thread drainer = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (readyLatch.getCount() > 0 && startupLogs.length() < 2048) {
                        startupLogs.append(line).append('\n');
                    }
                    int idx = line.indexOf(READY_PREFIX);
                    if (idx >= 0 && parsedPort.get() == 0) {
                        String rest = line.substring(idx + READY_PREFIX.length()).trim();
                        int sp = rest.indexOf(' ');
                        if (sp > 0) {
                            rest = rest.substring(0, sp);
                        }
                        try {
                            int p = Integer.parseInt(rest);
                            if (p > 0 && p <= 65535) {
                                parsedPort.set(p);
                                readyLatch.countDown();
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    Log.d(TAG, line);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Core stdout reader ended: " + t.getMessage());
            } finally {
                readyLatch.countDown();
                synchronized (lock) {
                    if (HubanCoreManager.this.process == proc) {
                        HubanCoreManager.this.activePort = 0;
                    }
                }
            }
        }, "huban-core-io");
        drainer.setDaemon(true);
        drainer.start();

        boolean signaled;
        try {
            signaled = readyLatch.await(START_TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            stopLocked();
            lastError = "核心引擎启动被中断";
            throw new IOException(lastError, ie);
        }

        int port = parsedPort.get();
        if (!signaled || port <= 0 || !proc.isAlive()) {
            String detail = startupLogs.toString().trim();
            stopLocked();
            lastError = "核心引擎启动超时或异常退出" + (detail.isEmpty() ? "" : (": " + detail));
            throw new IOException(lastError);
        }

        if (!verifyHealth(port)) {
            stopLocked();
            lastError = "核心引擎健康检查失败 (port=" + port + ")";
            throw new IOException(lastError);
        }

        this.activePort = port;
        this.lastError = "";
        Log.i(TAG, "Core ready on http://127.0.0.1:" + port);
    }

    private void stopLocked() {
        Process p = this.process;
        this.process = null;
        this.activePort = 0;
        if (p != null) {
            try {
                p.destroy();
                if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean verifyHealth(int port) {
        String urlStr = "http://127.0.0.1:" + port + "/api/health";
        for (int attempt = 0; attempt < 10; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                int code = conn.getResponseCode();
                if (code == 200) {
                    return true;
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static File exportAndroidCaBundle(File dataDir) {
        File outFile = new File(dataDir, "cacerts.pem");
        try {
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            ks.load(null, null);
            File tmp = new File(dataDir, "cacerts.pem.tmp");
            int count = 0;
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.US_ASCII)) {
                Enumeration<String> aliases = ks.aliases();
                while (aliases.hasMoreElements()) {
                    String alias = aliases.nextElement();
                    Certificate cert = ks.getCertificate(alias);
                    if (cert == null) {
                        continue;
                    }
                    String b64 = Base64.encodeToString(cert.getEncoded(), Base64.DEFAULT);
                    w.write("-----BEGIN CERTIFICATE-----\n");
                    w.write(b64);
                    if (!b64.endsWith("\n")) {
                        w.write('\n');
                    }
                    w.write("-----END CERTIFICATE-----\n");
                    count++;
                }
            }
            if (count > 0) {
                if (outFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                }
                if (tmp.renameTo(outFile)) {
                    Log.i(TAG, "Exported " + count + " Android system CA certs to " + outFile.getAbsolutePath());
                    return outFile;
                }
                return tmp;
            }
        } catch (Throwable t) {
            Log.w(TAG, "exportAndroidCaBundle failed: " + t.getMessage());
        }
        return outFile.isFile() ? outFile : null;
    }

    private static String collectDnsServers(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return "";
            }
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return "";
            }
            LinkProperties lp = cm.getLinkProperties(active);
            if (lp == null) {
                return "";
            }
            List<InetAddress> servers = lp.getDnsServers();
            if (servers == null || servers.isEmpty()) {
                return "";
            }
            List<String> out = new ArrayList<>();
            for (InetAddress addr : servers) {
                if (addr == null) {
                    continue;
                }
                String host = addr.getHostAddress();
                if (host != null && !host.isEmpty()) {
                    // Strip IPv6 scope id (e.g. fe80::1%wlan0) if present.
                    int pct = host.indexOf('%');
                    if (pct > 0) {
                        host = host.substring(0, pct);
                    }
                    out.add(host);
                }
            }
            return String.join(",", out);
        } catch (Throwable t) {
            Log.w(TAG, "collectDnsServers failed: " + t.getMessage());
            return "";
        }
    }
}
