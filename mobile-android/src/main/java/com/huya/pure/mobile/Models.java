package com.huya.pure.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Strongly-typed JSON data models matching server/internal/{web,store,huya}.
 */
public final class Models {

    public static final int DISTRIBUTE_EVEN = 0;
    public static final int DISTRIBUTE_FILL_QUOTA = 1;
    public static final int DISTRIBUTE_NEAREST_UPGRADE = 2;
    public static final int DISTRIBUTE_CUSTOM_RATIO = 3;

    private Models() {
    }

    public static final class StatusResponse {
        public boolean ok;
        public String version = "";
        public long serverTime;
        public final List<Account> accounts = new ArrayList<>();
        public NotifyConfig notify = new NotifyConfig();

        public static StatusResponse fromJson(JSONObject o) {
            StatusResponse r = new StatusResponse();
            if (o == null) return r;
            r.ok = o.optBoolean("ok", false);
            r.version = o.optString("version", "");
            r.serverTime = o.optLong("serverTime", 0L);
            JSONArray arr = o.optJSONArray("accounts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject ao = arr.optJSONObject(i);
                    if (ao != null) {
                        r.accounts.add(Account.fromJson(ao));
                    }
                }
            }
            r.notify = NotifyConfig.fromJson(o.optJSONObject("notify"));
            return r;
        }
    }

    public static final class Account {
        public String id = "";
        public String label = "";
        public String nickName = "";
        public String avatar = "";
        public long uid;
        public boolean loggedIn;
        public String authMode = "web"; // "mobile" | "web"
        public AccountConfig config = new AccountConfig();
        public AccountState state = new AccountState();
        public long createdAt;

        public String displayName() {
            if (label != null && !label.trim().isEmpty()) return label.trim();
            if (nickName != null && !nickName.trim().isEmpty()) return nickName.trim();
            return uid > 0 ? ("虎牙 " + uid) : id;
        }

        public boolean isMobileAuth() {
            return "mobile".equalsIgnoreCase(authMode);
        }

        public static Account fromJson(JSONObject o) {
            Account a = new Account();
            if (o == null) return a;
            a.id = o.optString("id", "");
            a.label = o.optString("label", "");
            a.nickName = o.optString("nickName", "");
            a.avatar = o.optString("avatar", "");
            a.uid = o.optLong("uid", 0L);
            a.loggedIn = o.optBoolean("loggedIn", false);
            a.authMode = o.optString("authMode", "web");
            a.config = AccountConfig.fromJson(o.optJSONObject("config"));
            a.state = AccountState.fromJson(o.optJSONObject("state"));
            a.createdAt = o.optLong("createdAt", 0L);
            return a;
        }
    }

    public static final class SendTarget {
        public long pid;
        public String name = "";
        public int weight = 1;

        public SendTarget() {
        }

        public SendTarget(long pid, String name, int weight) {
            this.pid = pid;
            this.name = name != null ? name : "";
            this.weight = Math.max(1, weight);
        }

        public SendTarget copy() {
            return new SendTarget(pid, name, weight);
        }

        public static SendTarget fromJson(JSONObject o) {
            SendTarget t = new SendTarget();
            if (o == null) return t;
            t.pid = o.optLong("pid", 0L);
            t.name = o.optString("name", "");
            t.weight = Math.max(1, o.optInt("weight", 1));
            return t;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("pid", pid);
            o.put("name", name != null ? name : "");
            o.put("weight", Math.max(1, weight));
            return o;
        }
    }

    public static final class AccountConfig {
        public boolean autoClaimEnabled = true;
        public int autoClaimHour = 1;
        public int autoClaimMinute = 0;
        public boolean autoAdHuliang = true;
        public boolean autoFansCheckin = true;
        public boolean autoClaimWelfare = true;
        public boolean autoSendHuliang = false;
        public boolean sendSuperHuliang = true;
        public boolean smartSuperFanDay = true;
        public final List<SendTarget> sendTargets = new ArrayList<>();
        public int sendDistributeMode = DISTRIBUTE_FILL_QUOTA;
        public int sendMaxCount = 0;
        public boolean notifyOnRun = true;
        public boolean notifyOnSuperFanDay = true;

        public AccountConfig copy() {
            AccountConfig c = new AccountConfig();
            c.autoClaimEnabled = autoClaimEnabled;
            c.autoClaimHour = autoClaimHour;
            c.autoClaimMinute = autoClaimMinute;
            c.autoAdHuliang = autoAdHuliang;
            c.autoFansCheckin = autoFansCheckin;
            c.autoClaimWelfare = autoClaimWelfare;
            c.autoSendHuliang = autoSendHuliang;
            c.sendSuperHuliang = sendSuperHuliang;
            c.smartSuperFanDay = smartSuperFanDay;
            for (SendTarget t : sendTargets) {
                c.sendTargets.add(t.copy());
            }
            c.sendDistributeMode = sendDistributeMode;
            c.sendMaxCount = sendMaxCount;
            c.notifyOnRun = notifyOnRun;
            c.notifyOnSuperFanDay = notifyOnSuperFanDay;
            return c;
        }

        public static AccountConfig fromJson(JSONObject o) {
            AccountConfig c = new AccountConfig();
            if (o == null) return c;
            c.autoClaimEnabled = o.optBoolean("autoClaimEnabled", true);
            c.autoClaimHour = o.optInt("autoClaimHour", 1);
            c.autoClaimMinute = o.optInt("autoClaimMinute", 0);
            c.autoAdHuliang = o.optBoolean("autoAdHuliang", true);
            c.autoFansCheckin = o.optBoolean("autoFansCheckin", true);
            c.autoClaimWelfare = o.optBoolean("autoClaimWelfare", true);
            c.autoSendHuliang = o.optBoolean("autoSendHuliang", false);
            c.sendSuperHuliang = o.optBoolean("sendSuperHuliang", true);
            c.smartSuperFanDay = o.optBoolean("smartSuperFanDay", true);
            JSONArray arr = o.optJSONArray("sendTargets");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject to = arr.optJSONObject(i);
                    if (to != null) {
                        SendTarget st = SendTarget.fromJson(to);
                        if (st.pid > 0) {
                            c.sendTargets.add(st);
                        }
                    }
                }
            }
            c.sendDistributeMode = o.optInt("sendDistributeMode", DISTRIBUTE_FILL_QUOTA);
            c.sendMaxCount = o.optInt("sendMaxCount", 0);
            c.notifyOnRun = o.optBoolean("notifyOnRun", true);
            c.notifyOnSuperFanDay = o.optBoolean("notifyOnSuperFanDay", true);
            return c;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("autoClaimEnabled", autoClaimEnabled);
            o.put("autoClaimHour", autoClaimHour);
            o.put("autoClaimMinute", autoClaimMinute);
            o.put("autoAdHuliang", autoAdHuliang);
            o.put("autoFansCheckin", autoFansCheckin);
            o.put("autoClaimWelfare", autoClaimWelfare);
            o.put("autoSendHuliang", autoSendHuliang);
            o.put("sendSuperHuliang", sendSuperHuliang);
            o.put("smartSuperFanDay", smartSuperFanDay);
            JSONArray arr = new JSONArray();
            for (SendTarget t : sendTargets) {
                arr.put(t.toJson());
            }
            o.put("sendTargets", arr);
            o.put("sendDistributeMode", sendDistributeMode);
            o.put("sendMaxCount", sendMaxCount);
            o.put("notifyOnRun", notifyOnRun);
            o.put("notifyOnSuperFanDay", notifyOnSuperFanDay);
            return o;
        }
    }

    public static final class AccountState {
        public boolean running;
        public long lastRunTime;
        public String lastRunDate = "";
        public boolean lastRunSuccess;
        public String lastRunMessage = "";
        public String todayClaimedDay = "";
        public int todayClaimedCount;
        public String todayCheckinDay = "";
        public int todayCheckinCount;
        public int todayCheckinTotal;
        public String todaySentDay = "";
        public int todaySentCount;
        public int freeHuliangCount = -1;
        public int freeExpiring24h;
        public long freeEarliestExpire;
        public int superHuliangCount = -1;
        public String superHuliangName = "";
        public String sfDayActiveSummary = "";
        public long nextScheduleTime;
        public long nextRetryTime;
        public long nextRescueTime;
        public long badgesUpdateTime;
        public final List<FanBadge> badges = new ArrayList<>();
        public final List<RunRecord> history = new ArrayList<>();

        public static AccountState fromJson(JSONObject o) {
            AccountState s = new AccountState();
            if (o == null) return s;
            s.running = o.optBoolean("running", false);
            s.lastRunTime = o.optLong("lastRunTime", 0L);
            s.lastRunDate = o.optString("lastRunDate", "");
            s.lastRunSuccess = o.optBoolean("lastRunSuccess", false);
            s.lastRunMessage = o.optString("lastRunMessage", "");
            s.todayClaimedDay = o.optString("todayClaimedDay", "");
            s.todayClaimedCount = o.optInt("todayClaimedCount", 0);
            s.todayCheckinDay = o.optString("todayCheckinDay", "");
            s.todayCheckinCount = o.optInt("todayCheckinCount", 0);
            s.todayCheckinTotal = o.optInt("todayCheckinTotal", 0);
            s.todaySentDay = o.optString("todaySentDay", "");
            s.todaySentCount = o.optInt("todaySentCount", 0);
            s.freeHuliangCount = o.optInt("freeHuliangCount", -1);
            s.freeExpiring24h = o.optInt("freeExpiring24h", 0);
            s.freeEarliestExpire = o.optLong("freeEarliestExpire", 0L);
            s.superHuliangCount = o.optInt("superHuliangCount", -1);
            s.superHuliangName = o.optString("superHuliangName", "");
            s.sfDayActiveSummary = o.optString("sfDayActiveSummary", "");
            s.nextScheduleTime = o.optLong("nextScheduleTime", 0L);
            s.nextRetryTime = o.optLong("nextRetryTime", 0L);
            s.nextRescueTime = o.optLong("nextRescueTime", 0L);
            s.badgesUpdateTime = o.optLong("badgesUpdateTime", 0L);

            JSONArray bArr = o.optJSONArray("badges");
            if (bArr != null) {
                for (int i = 0; i < bArr.length(); i++) {
                    JSONObject bo = bArr.optJSONObject(i);
                    if (bo != null) {
                        s.badges.add(FanBadge.fromJson(bo));
                    }
                }
            }
            JSONArray hArr = o.optJSONArray("history");
            if (hArr != null) {
                for (int i = 0; i < hArr.length(); i++) {
                    JSONObject ho = hArr.optJSONObject(i);
                    if (ho != null) {
                        s.history.add(RunRecord.fromJson(ho));
                    }
                }
            }
            return s;
        }
    }

    public static final class BadgeRenderInfo {
        public boolean custom;
        public int size = 2;
        public boolean hasSuffix;
        public String floorUrl = "";
        public String iconUrl = "";
        public String levelUrl = "";
        public String vFlagUrl = "";
        public String guardIconUrl = "";
        public String textColor = "";
        public String levelColor = "";

        public static BadgeRenderInfo fromJson(JSONObject o) {
            if (o == null) return null;
            BadgeRenderInfo r = new BadgeRenderInfo();
            r.custom = o.optBoolean("custom", false);
            r.size = o.optInt("size", 2);
            r.hasSuffix = o.optBoolean("hasSuffix", false);
            r.floorUrl = o.optString("floorUrl", "");
            if (r.floorUrl.isEmpty()) {
                r.floorUrl = o.optString("bgUrl", "");
            }
            r.iconUrl = o.optString("iconUrl", "");
            r.levelUrl = o.optString("levelUrl", "");
            r.vFlagUrl = o.optString("vFlagUrl", "");
            if (r.vFlagUrl.isEmpty()) {
                r.vFlagUrl = o.optString("sfUrl", "");
            }
            r.guardIconUrl = o.optString("guardIconUrl", "");
            r.textColor = o.optString("textColor", "");
            r.levelColor = o.optString("levelColor", "");
            if (r.floorUrl.isEmpty() && r.guardIconUrl.isEmpty()) {
                return null;
            }
            return r;
        }
    }

    public static final class FanBadge {
        public long pid;
        public long badgeId;
        public String badgeName = "";
        public String nick = "";
        public String avatar = "";
        public int roomId;
        public int level;
        public int score;
        public int nextScore;
        public int dayScore;
        public int quota;
        public int quotaUsed;
        public int extinguished;
        public int badgeType;
        public int customBadgeFlag;
        public boolean superFan;
        public int sfType;
        public int sfVariety;
        public int sfLevel;
        public int fansIdentity;
        public String faithName = "";
        public int guardLevel;
        public String guardName = "";
        public int guardType;
        public int guardAccompanyDay;
        public long guardEndTime;
        public boolean sfDayActive;
        public int sfDay;
        public int sfDayScoreTimes;
        public int sfDayQuotaTimes;
        public int sfDayLeftSec;
        public long nextSfDayEpoch;
        public String checkinState = "";
        public BadgeRenderInfo render;

        public boolean isCheckedIn() {
            return "success".equals(checkinState) || "already".equals(checkinState);
        }

        public static FanBadge fromJson(JSONObject o) {
            FanBadge b = new FanBadge();
            if (o == null) return b;
            b.pid = o.optLong("pid", 0L);
            b.badgeId = o.optLong("badgeId", 0L);
            b.badgeName = o.optString("badgeName", "");
            b.nick = o.optString("nick", "");
            b.avatar = o.optString("avatar", "");
            b.roomId = o.optInt("roomId", 0);
            b.level = o.optInt("level", 0);
            b.score = o.optInt("score", 0);
            b.nextScore = o.optInt("nextScore", 0);
            b.dayScore = o.optInt("dayScore", 0);
            b.quota = o.optInt("quota", 0);
            b.quotaUsed = o.optInt("quotaUsed", 0);
            b.extinguished = o.optInt("extinguished", 0);
            b.badgeType = o.optInt("badgeType", 0);
            b.customBadgeFlag = o.optInt("customBadgeFlag", 0);
            b.superFan = o.optBoolean("superFan", false);
            b.sfType = o.optInt("sfType", 0);
            b.sfVariety = o.optInt("sfVariety", 0);
            b.sfLevel = o.optInt("sfLevel", 0);
            b.fansIdentity = o.optInt("fansIdentity", 0);
            b.faithName = o.optString("faithName", "");
            b.guardLevel = o.optInt("guardLevel", 0);
            b.guardName = o.optString("guardName", "");
            b.guardType = o.optInt("guardType", 0);
            b.guardAccompanyDay = o.optInt("guardAccompanyDay", 0);
            b.guardEndTime = o.optLong("guardEndTime", 0L);
            b.sfDayActive = o.optBoolean("sfDayActive", false);
            b.sfDay = o.optInt("sfDay", 0);
            b.sfDayScoreTimes = o.optInt("sfDayScoreTimes", 0);
            b.sfDayQuotaTimes = o.optInt("sfDayQuotaTimes", 0);
            b.sfDayLeftSec = o.optInt("sfDayLeftSec", 0);
            b.nextSfDayEpoch = o.optLong("nextSfDayEpoch", 0L);
            b.checkinState = o.optString("checkinState", "");
            b.render = BadgeRenderInfo.fromJson(o.optJSONObject("render"));
            return b;
        }
    }

    public static final class RunRecord {
        public long time;
        public String date = "";
        public String trigger = "";
        public boolean success;
        public String message = "";
        public int adClaimed;
        public int checkinOk;
        public int checkinAll;
        public int sentNormal;
        public int sentSuper;

        public static RunRecord fromJson(JSONObject o) {
            RunRecord r = new RunRecord();
            if (o == null) return r;
            r.time = o.optLong("time", 0L);
            r.date = o.optString("date", "");
            r.trigger = o.optString("trigger", "");
            r.success = o.optBoolean("success", false);
            r.message = o.optString("message", "");
            r.adClaimed = o.optInt("adClaimed", 0);
            r.checkinOk = o.optInt("checkinOk", 0);
            r.checkinAll = o.optInt("checkinAll", 0);
            r.sentNormal = o.optInt("sentNormal", 0);
            r.sentSuper = o.optInt("sentSuper", 0);
            return r;
        }
    }

    public static final class NotifyConfig {
        public String barkUrl = "";
        public String telegramBotToken = "";
        public String telegramChatId = "";
        public String serverChanKey = "";
        public String dingTalkWebhook = "";
        public String feishuWebhook = "";
        public String customWebhookUrl = "";

        public static NotifyConfig fromJson(JSONObject o) {
            NotifyConfig n = new NotifyConfig();
            if (o == null) return n;
            n.barkUrl = o.optString("barkUrl", "");
            n.telegramBotToken = o.optString("telegramBotToken", "");
            n.telegramChatId = o.optString("telegramChatId", "");
            n.serverChanKey = o.optString("serverChanKey", "");
            n.dingTalkWebhook = o.optString("dingTalkWebhook", "");
            n.feishuWebhook = o.optString("feishuWebhook", "");
            n.customWebhookUrl = o.optString("customWebhookUrl", "");
            return n;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("barkUrl", barkUrl != null ? barkUrl.trim() : "");
            o.put("telegramBotToken", telegramBotToken != null ? telegramBotToken.trim() : "");
            o.put("telegramChatId", telegramChatId != null ? telegramChatId.trim() : "");
            o.put("serverChanKey", serverChanKey != null ? serverChanKey.trim() : "");
            o.put("dingTalkWebhook", dingTalkWebhook != null ? dingTalkWebhook.trim() : "");
            o.put("feishuWebhook", feishuWebhook != null ? feishuWebhook.trim() : "");
            o.put("customWebhookUrl", customWebhookUrl != null ? customWebhookUrl.trim() : "");
            return o;
        }
    }

    public static final class LocalNotification {
        public String accountId = "";
        public String title = "";
        public String body = "";
        public long time;

        public static LocalNotification fromJson(JSONObject o) {
            LocalNotification n = new LocalNotification();
            if (o == null) return n;
            n.accountId = o.optString("accountId", "");
            n.title = o.optString("title", "");
            n.body = o.optString("body", "");
            n.time = o.optLong("time", 0L);
            return n;
        }
    }

    public static final class SmsSendResult {
        public boolean ok;
        public String deviceId = "";
        public String message = "";

        public static SmsSendResult fromJson(JSONObject o) {
            SmsSendResult r = new SmsSendResult();
            if (o == null) return r;
            r.ok = o.optBoolean("ok", false);
            r.deviceId = o.optString("deviceId", "");
            r.message = o.optString("message", "");
            return r;
        }
    }

    public static final class QRStartResult {
        public boolean ok;
        public String sessionId = "";
        public String qrId = "";
        public String provider = "";
        public String imageData = "";

        public static QRStartResult fromJson(JSONObject o) {
            QRStartResult r = new QRStartResult();
            if (o == null) return r;
            r.ok = o.optBoolean("ok", false);
            r.sessionId = o.optString("sessionId", "");
            r.qrId = o.optString("qrId", "");
            r.provider = o.optString("provider", "");
            r.imageData = o.optString("imageData", "");
            return r;
        }
    }

    public static final class AuthStrategy {
        public int strategy;
        public int dataType;
        public String selectTitle = "";
        public String promptContent = "";
        public String imageData = "";
        public String safeAuthUrl = "";

        public static AuthStrategy fromJson(JSONObject o) {
            AuthStrategy s = new AuthStrategy();
            if (o == null) return s;
            s.strategy = o.optInt("strategy", 0);
            s.dataType = o.optInt("dataType", 0);
            s.selectTitle = o.optString("selectTitle", "");
            s.promptContent = o.optString("promptContent", "");
            s.imageData = o.optString("imageData", "");
            s.safeAuthUrl = o.optString("safeAuthUrl", "");
            return s;
        }
    }

    public static final class AuthChallenge {
        public String sessionId = "";
        public long uid;
        public String mode = "code"; // "safe_auth" | "code"
        public String message = "";
        public String safeAuthUrl = "";
        public String safeAuthTitle = "";
        public boolean hasCallback;
        public final List<AuthStrategy> strategies = new ArrayList<>();

        public static AuthChallenge fromJson(JSONObject o) {
            if (o == null) return null;
            AuthChallenge c = new AuthChallenge();
            c.sessionId = o.optString("sessionId", "");
            c.uid = o.optLong("uid", 0L);
            c.mode = o.optString("mode", "code");
            c.message = o.optString("message", "");
            c.safeAuthUrl = o.optString("safeAuthUrl", "");
            c.safeAuthTitle = o.optString("safeAuthTitle", "");
            c.hasCallback = o.optBoolean("hasCallback", false);
            JSONArray arr = o.optJSONArray("strategies");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject so = arr.optJSONObject(i);
                    if (so != null) {
                        c.strategies.add(AuthStrategy.fromJson(so));
                    }
                }
            }
            return c;
        }
    }

    public static final class PollResult {
        public boolean ok;
        public String status = ""; // pending | scanned | confirmed | expired | cancelled | need_auth | re_scan | error
        public String stage = "";
        public String message = "";
        public boolean created;
        public boolean keptMobile;
        public Account account;
        public AuthChallenge auth;

        public static PollResult fromJson(JSONObject o) {
            PollResult r = new PollResult();
            if (o == null) return r;
            r.ok = o.optBoolean("ok", false);
            r.status = o.optString("status", "");
            r.stage = o.optString("stage", "");
            r.message = o.optString("message", "");
            r.created = o.optBoolean("created", false);
            r.keptMobile = o.optBoolean("keptMobile", false);
            JSONObject ao = o.optJSONObject("account");
            if (ao != null) {
                r.account = Account.fromJson(ao);
            }
            r.auth = AuthChallenge.fromJson(o.optJSONObject("auth"));
            return r;
        }
    }

    public static final class AccountActionResult {
        public boolean ok;
        public String message = "";
        public boolean created;
        public boolean keptMobile;
        public Account account;

        public static AccountActionResult fromJson(JSONObject o) {
            AccountActionResult r = new AccountActionResult();
            if (o == null) return r;
            r.ok = o.optBoolean("ok", false);
            r.message = o.optString("message", "");
            r.created = o.optBoolean("created", false);
            r.keptMobile = o.optBoolean("keptMobile", false);
            JSONObject ao = o.optJSONObject("account");
            if (ao != null) {
                r.account = Account.fromJson(ao);
            }
            return r;
        }
    }

    public static final class RunAllItem {
        public String accountId = "";
        public String label = "";
        public boolean success;
        public String message = "";
        public String title = "";
        public String body = "";
        public int cooldownSeconds;

        public static RunAllItem fromJson(JSONObject o) {
            RunAllItem item = new RunAllItem();
            if (o == null) return item;
            item.accountId = o.optString("accountId", "");
            item.label = o.optString("label", "");
            item.success = o.optBoolean("success", false);
            item.message = o.optString("message", "");
            item.title = o.optString("title", "");
            item.body = o.optString("body", "");
            item.cooldownSeconds = o.optInt("cooldownSeconds", 0);
            return item;
        }
    }

    public static final class RunAllResult {
        public boolean ok;
        public int count;
        public final List<RunAllItem> results = new ArrayList<>();
        public final List<Account> accounts = new ArrayList<>();

        public static RunAllResult fromJson(JSONObject o) {
            RunAllResult r = new RunAllResult();
            if (o == null) return r;
            r.ok = o.optBoolean("ok", false);
            r.count = o.optInt("count", 0);
            JSONArray rArr = o.optJSONArray("results");
            if (rArr != null) {
                for (int i = 0; i < rArr.length(); i++) {
                    JSONObject ro = rArr.optJSONObject(i);
                    if (ro != null) r.results.add(RunAllItem.fromJson(ro));
                }
            }
            JSONArray aArr = o.optJSONArray("accounts");
            if (aArr != null) {
                for (int i = 0; i < aArr.length(); i++) {
                    JSONObject ao = aArr.optJSONObject(i);
                    if (ao != null) r.accounts.add(Account.fromJson(ao));
                }
            }
            return r;
        }
    }
}
