package com.huya.pure.mobile;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Synchronous HTTP client communicating with the embedded loopback Go core.
 * Always invoke methods from a background thread.
 */
public final class HubanApiClient {

    private static final int DEFAULT_TIMEOUT_MS = 30_000;
    private static final int LONG_TIMEOUT_MS = 65_000;
    private static final int RUN_ALL_TIMEOUT_MS = 300_000;

    private final Context appContext;

    public HubanApiClient(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public static final class ApiException extends IOException {
        public final int statusCode;

        public ApiException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    // ── Status & Notifications ───────────────────────────────────────────────

    public Models.StatusResponse getStatus() throws IOException {
        JSONObject obj = requestJson("GET", "/api/status", null, DEFAULT_TIMEOUT_MS);
        return Models.StatusResponse.fromJson(obj);
    }

    public List<Models.LocalNotification> drainNotifications() throws IOException {
        JSONObject obj = requestJson("GET", "/api/notifications/drain", null, DEFAULT_TIMEOUT_MS);
        List<Models.LocalNotification> out = new ArrayList<>();
        JSONArray arr = obj.optJSONArray("notifications");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject no = arr.optJSONObject(i);
                if (no != null) {
                    out.add(Models.LocalNotification.fromJson(no));
                }
            }
        }
        return out;
    }

    // ── Mobile SMS Login ─────────────────────────────────────────────────────

    public Models.SmsSendResult sendMobileSms(String mobile) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("mobile", mobile != null ? mobile.trim() : "");
            JSONObject obj = requestJson("POST", "/api/mobile/sms/send", body.toString(), DEFAULT_TIMEOUT_MS);
            return Models.SmsSendResult.fromJson(obj);
        } catch (JSONException e) {
            throw new IOException("构造请求失败", e);
        }
    }

    public Models.AccountActionResult loginMobileSms(String mobile, String code, String deviceId, String label) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("mobile", mobile != null ? mobile.trim() : "");
            body.put("code", code != null ? code.trim() : "");
            body.put("deviceId", deviceId != null ? deviceId.trim() : "");
            body.put("label", label != null ? label.trim() : "");
            JSONObject obj = requestJson("POST", "/api/mobile/sms/login", body.toString(), DEFAULT_TIMEOUT_MS);
            return Models.AccountActionResult.fromJson(obj);
        } catch (JSONException e) {
            throw new IOException("构造请求失败", e);
        }
    }

    // ── Huya QR Login ────────────────────────────────────────────────────────

    public Models.QRStartResult startQr() throws IOException {
        JSONObject obj = requestJson("POST", "/api/qr/start", "{}", DEFAULT_TIMEOUT_MS);
        return Models.QRStartResult.fromJson(obj);
    }

    public Models.PollResult pollQr(String sessionId) throws IOException {
        String enc = URLEncoder.encode(sessionId != null ? sessionId : "", "UTF-8");
        JSONObject obj = requestJson("GET", "/api/qr/poll?sessionId=" + enc, null, DEFAULT_TIMEOUT_MS);
        return Models.PollResult.fromJson(obj);
    }

    // ── Third-Party QR Login (WeChat / QQ) & Second-Factor Auth ──────────────

    public Models.QRStartResult startThird(String provider) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("provider", provider);
            JSONObject obj = requestJson("POST", "/api/third/start", body.toString(), DEFAULT_TIMEOUT_MS);
            return Models.QRStartResult.fromJson(obj);
        } catch (JSONException e) {
            throw new IOException("构造请求失败", e);
        }
    }

    public Models.PollResult pollThird(String sessionId) throws IOException {
        String enc = URLEncoder.encode(sessionId != null ? sessionId : "", "UTF-8");
        JSONObject obj = requestJson("GET", "/api/third/poll?sessionId=" + enc, null, DEFAULT_TIMEOUT_MS);
        return Models.PollResult.fromJson(obj);
    }

    public JSONObject sendThirdAuthCode(String sessionId, int strategy) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("sessionId", sessionId);
            body.put("strategy", strategy);
            return requestJson("POST", "/api/third/auth/code", body.toString(), DEFAULT_TIMEOUT_MS);
        } catch (JSONException e) {
            throw new IOException("构造请求失败", e);
        }
    }

    public Models.PollResult verifyThirdAuthCode(String sessionId, int strategy, String code) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("sessionId", sessionId);
            body.put("strategy", strategy);
            body.put("code", code != null ? code.trim() : "");
            JSONObject obj = requestJson("POST", "/api/third/auth/verify", body.toString(), DEFAULT_TIMEOUT_MS);
            return Models.PollResult.fromJson(obj);
        } catch (JSONException e) {
            throw new IOException("构造请求失败", e);
        }
    }

    public Models.PollResult verifyThirdSafeAuth(String sessionId, String authId) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("sessionId", sessionId);
            body.put("safeAuth", true);
            body.put("authId", authId != null ? authId.trim() : "");
            JSONObject obj = requestJson("POST", "/api/third/auth/verify", body.toString(), DEFAULT_TIMEOUT_MS);
            return Models.PollResult.fromJson(obj);
        } catch (JSONException e) {
            throw new IOException("构造请求失败", e);
        }
    }

    // ── Account Management & Actions ─────────────────────────────────────────

    public Models.AccountActionResult addCookieAccount(String label, String cookie) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("label", label != null ? label.trim() : "");
            body.put("cookie", cookie != null ? cookie.trim() : "");
            JSONObject obj = requestJson("POST", "/api/accounts/cookie", body.toString(), DEFAULT_TIMEOUT_MS);
            return Models.AccountActionResult.fromJson(obj);
        } catch (JSONException e) {
            throw new IOException("构造请求失败", e);
        }
    }

    public Models.Account updateAccountConfig(String accountId, String label, Models.AccountConfig config) throws IOException {
        try {
            JSONObject body = new JSONObject();
            if (label != null) {
                body.put("label", label);
            }
            if (config != null) {
                body.put("config", config.toJson());
            }
            String encId = URLEncoder.encode(accountId, "UTF-8");
            JSONObject obj = requestJson("PUT", "/api/accounts/" + encId + "/config", body.toString(), DEFAULT_TIMEOUT_MS);
            return Models.Account.fromJson(obj.optJSONObject("account"));
        } catch (JSONException e) {
            throw new IOException("构造配置请求失败", e);
        }
    }

    public void deleteAccount(String accountId) throws IOException {
        String encId = URLEncoder.encode(accountId, "UTF-8");
        requestJson("DELETE", "/api/accounts/" + encId, null, DEFAULT_TIMEOUT_MS);
    }

    public String runAccount(String accountId) throws IOException {
        String encId = URLEncoder.encode(accountId, "UTF-8");
        JSONObject obj = requestJson("POST", "/api/accounts/" + encId + "/run", "{}", DEFAULT_TIMEOUT_MS);
        return obj.optString("message", "任务已启动");
    }

    public Models.RunAllResult runAllAccounts(String trigger, boolean onlyEnabled) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("trigger", trigger != null ? trigger : "schedule");
            body.put("onlyEnabled", onlyEnabled);
            JSONObject obj = requestJson("POST", "/api/accounts/run-all", body.toString(), RUN_ALL_TIMEOUT_MS);
            return Models.RunAllResult.fromJson(obj);
        } catch (JSONException e) {
            throw new IOException("构造请求失败", e);
        }
    }

    public Models.Account refreshAccount(String accountId) throws IOException {
        String encId = URLEncoder.encode(accountId, "UTF-8");
        JSONObject obj = requestJson("POST", "/api/accounts/" + encId + "/refresh", "{}", LONG_TIMEOUT_MS);
        return Models.Account.fromJson(obj.optJSONObject("account"));
    }

    public Models.AccountActionResult manualSend(String accountId, long pid, boolean isSuper, int count) throws IOException {
        try {
            JSONObject body = new JSONObject();
            body.put("pid", pid);
            body.put("super", isSuper);
            body.put("count", count);
            String encId = URLEncoder.encode(accountId, "UTF-8");
            JSONObject obj = requestJson("POST", "/api/accounts/" + encId + "/send", body.toString(), DEFAULT_TIMEOUT_MS);
            return Models.AccountActionResult.fromJson(obj);
        } catch (JSONException e) {
            throw new IOException("构造送礼请求失败", e);
        }
    }

    // ── External Notify Channels ─────────────────────────────────────────────

    public Models.NotifyConfig saveNotify(Models.NotifyConfig cfg) throws IOException {
        try {
            JSONObject obj = requestJson("PUT", "/api/notify", cfg.toJson().toString(), DEFAULT_TIMEOUT_MS);
            return Models.NotifyConfig.fromJson(obj.optJSONObject("notify"));
        } catch (JSONException e) {
            throw new IOException("构造通知配置失败", e);
        }
    }

    public String testNotify() throws IOException {
        JSONObject obj = requestJson("POST", "/api/notify/test", "{}", DEFAULT_TIMEOUT_MS);
        return obj.optString("message", "测试推送已发送");
    }

    // ── Internal HTTP Transport ──────────────────────────────────────────────

    private JSONObject requestJson(String method, String path, String jsonBody, int readTimeoutMs) throws IOException {
        IOException lastConnErr = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            String baseUrl = (attempt == 0)
                    ? HubanCoreManager.getInstance().ensureStarted(appContext)
                    : HubanCoreManager.getInstance().restart(appContext);
            try {
                return doHttp(baseUrl + path, method, jsonBody, readTimeoutMs);
            } catch (ConnectException ce) {
                lastConnErr = ce;
            }
        }
        throw lastConnErr != null ? lastConnErr : new IOException("无法连接本地核心引擎");
    }

    private static JSONObject doHttp(String fullUrl, String method, String jsonBody, int readTimeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(readTimeoutMs);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");

            if (jsonBody != null) {
                byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bytes);
                }
            }

            int code = conn.getResponseCode();
            InputStream stream = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            String raw = readStream(stream);

            JSONObject obj;
            try {
                obj = raw.isEmpty() ? new JSONObject() : new JSONObject(raw);
            } catch (JSONException je) {
                if (code >= 200 && code < 300) {
                    throw new ApiException("响应格式异常", code);
                }
                throw new ApiException("请求失败 (HTTP " + code + ")", code);
            }

            if (code < 200 || code >= 300 || (obj.has("ok") && !obj.optBoolean("ok", true))) {
                String err = obj.optString("error", "");
                if (err.isEmpty()) {
                    err = obj.optString("message", "请求失败 (HTTP " + code + ")");
                }
                throw new ApiException(err, code);
            }
            return obj;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (InputStream in = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8").trim();
        }
    }
}
