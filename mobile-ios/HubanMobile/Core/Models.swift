import Foundation

// MARK: - Distribute Mode

enum DistributeMode: Int, CaseIterable, Identifiable, Codable {
    case even = 0
    case fillQuota = 1
    case nearestUpgrade = 2
    case customRatio = 3

    var id: Int { rawValue }

    var title: String {
        switch self {
        case .even: return "平均分配"
        case .fillQuota: return "优先补满亲密度上限"
        case .nearestUpgrade: return "优先离升级最近"
        case .customRatio: return "自定义比例权重"
        }
    }

    var shortTitle: String {
        switch self {
        case .even: return "平均分配"
        case .fillQuota: return "补满上限"
        case .nearestUpgrade: return "冲刺升级"
        case .customRatio: return "按比例"
        }
    }

    var iconName: String {
        switch self {
        case .even: return "equal.circle.fill"
        case .fillQuota: return "gauge.with.dots.needle.67percent"
        case .nearestUpgrade: return "arrow.up.circle.fill"
        case .customRatio: return "slider.horizontal.3"
        }
    }

    var subtitle: String {
        switch self {
        case .even: return "按目标数量均分本次免费虎粮"
        case .fillQuota: return "优先送给今日剩余上限充足的主播（推荐）"
        case .nearestUpgrade: return "集中经验优先冲刺即将升级的粉丝牌"
        case .customRatio: return "按赠送目标中设置的权重份数分配"
        }
    }
}

// MARK: - Core Status Models (matching Go server JSON 100%)

struct StatusResponse: Codable {
    let ok: Bool
    let version: String
    let serverTime: Int64
    let accounts: [AccountPublicView]
    let notify: NotifyConfig
}

struct AccountPublicView: Codable, Identifiable, Equatable {
    let id: String
    var label: String
    var nickName: String?
    var avatar: String?
    let uid: Int64
    let loggedIn: Bool
    let authMode: String // "mobile" | "web"
    var config: AccountConfig
    var state: AccountState
    let createdAt: Int64

    var displayName: String {
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { return trimmed }
        if let nick = nickName?.trimmingCharacters(in: .whitespacesAndNewlines), !nick.isEmpty {
            return nick
        }
        return "虎牙 \(uid)"
    }

    var isMobileAuth: Bool {
        authMode == "mobile"
    }
}

struct SendTarget: Codable, Identifiable, Equatable, Hashable {
    let pid: Int64
    var name: String
    var weight: Int

    var id: Int64 { pid }
}

struct AccountConfig: Codable, Equatable {
    var autoClaimEnabled: Bool
    var autoClaimHour: Int
    var autoClaimMinute: Int
    var autoAdHuliang: Bool
    var autoFansCheckin: Bool
    var autoClaimWelfare: Bool
    var autoSendHuliang: Bool
    var sendSuperHuliang: Bool
    var smartSuperFanDay: Bool
    var sendTargets: [SendTarget]
    var sendDistributeMode: Int
    var sendMaxCount: Int
    var notifyOnRun: Bool
    var notifyOnSuperFanDay: Bool

    init(
        autoClaimEnabled: Bool = true,
        autoClaimHour: Int = 1,
        autoClaimMinute: Int = 0,
        autoAdHuliang: Bool = true,
        autoFansCheckin: Bool = true,
        autoClaimWelfare: Bool = true,
        autoSendHuliang: Bool = false,
        sendSuperHuliang: Bool = true,
        smartSuperFanDay: Bool = true,
        sendTargets: [SendTarget] = [],
        sendDistributeMode: Int = 1,
        sendMaxCount: Int = 0,
        notifyOnRun: Bool = true,
        notifyOnSuperFanDay: Bool = true
    ) {
        self.autoClaimEnabled = autoClaimEnabled
        self.autoClaimHour = autoClaimHour
        self.autoClaimMinute = autoClaimMinute
        self.autoAdHuliang = autoAdHuliang
        self.autoFansCheckin = autoFansCheckin
        self.autoClaimWelfare = autoClaimWelfare
        self.autoSendHuliang = autoSendHuliang
        self.sendSuperHuliang = sendSuperHuliang
        self.smartSuperFanDay = smartSuperFanDay
        self.sendTargets = sendTargets
        self.sendDistributeMode = sendDistributeMode
        self.sendMaxCount = sendMaxCount
        self.notifyOnRun = notifyOnRun
        self.notifyOnSuperFanDay = notifyOnSuperFanDay
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        autoClaimEnabled = try c.decodeIfPresent(Bool.self, forKey: .autoClaimEnabled) ?? true
        autoClaimHour = try c.decodeIfPresent(Int.self, forKey: .autoClaimHour) ?? 1
        autoClaimMinute = try c.decodeIfPresent(Int.self, forKey: .autoClaimMinute) ?? 0
        autoAdHuliang = try c.decodeIfPresent(Bool.self, forKey: .autoAdHuliang) ?? true
        autoFansCheckin = try c.decodeIfPresent(Bool.self, forKey: .autoFansCheckin) ?? true
        autoClaimWelfare = try c.decodeIfPresent(Bool.self, forKey: .autoClaimWelfare) ?? true
        autoSendHuliang = try c.decodeIfPresent(Bool.self, forKey: .autoSendHuliang) ?? false
        sendSuperHuliang = try c.decodeIfPresent(Bool.self, forKey: .sendSuperHuliang) ?? true
        smartSuperFanDay = try c.decodeIfPresent(Bool.self, forKey: .smartSuperFanDay) ?? true
        sendTargets = try c.decodeIfPresent([SendTarget].self, forKey: .sendTargets) ?? []
        sendDistributeMode = try c.decodeIfPresent(Int.self, forKey: .sendDistributeMode) ?? 1
        sendMaxCount = try c.decodeIfPresent(Int.self, forKey: .sendMaxCount) ?? 0
        notifyOnRun = try c.decodeIfPresent(Bool.self, forKey: .notifyOnRun) ?? true
        notifyOnSuperFanDay = try c.decodeIfPresent(Bool.self, forKey: .notifyOnSuperFanDay) ?? true
    }
}

struct RunRecord: Codable, Identifiable, Equatable {
    let time: Int64
    let date: String
    let trigger: String
    let success: Bool
    let message: String
    let adClaimed: Int
    let checkinOk: Int
    let checkinAll: Int
    let sentNormal: Int
    let sentSuper: Int

    var id: String { "\(time)-\(trigger)-\(message.prefix(16))" }

    var triggerLabel: String {
        switch trigger {
        case "manual": return "手动"
        case "schedule": return "定时"
        case "retry": return "重试"
        case "rescue": return "临期抢救"
        default: return trigger
        }
    }
}

struct AccountState: Codable, Equatable {
    let running: Bool
    let lastRunTime: Int64
    let lastRunDate: String
    let lastRunSuccess: Bool
    let lastRunMessage: String
    let todayClaimedDay: String
    let todayClaimedCount: Int
    let todayCheckinDay: String
    let todayCheckinCount: Int
    let todayCheckinTotal: Int
    let todaySentDay: String
    let todaySentCount: Int
    let freeHuliangCount: Int
    let freeExpiring24h: Int
    let freeEarliestExpire: Int64
    let superHuliangCount: Int
    let superHuliangName: String
    let sfDayActiveSummary: String
    let nextScheduleTime: Int64
    let nextRetryTime: Int64
    let nextRescueTime: Int64
    let badgesUpdateTime: Int64
    let badges: [FanBadge]
    let history: [RunRecord]

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        running = try c.decodeIfPresent(Bool.self, forKey: .running) ?? false
        lastRunTime = try c.decodeIfPresent(Int64.self, forKey: .lastRunTime) ?? 0
        lastRunDate = try c.decodeIfPresent(String.self, forKey: .lastRunDate) ?? ""
        lastRunSuccess = try c.decodeIfPresent(Bool.self, forKey: .lastRunSuccess) ?? false
        lastRunMessage = try c.decodeIfPresent(String.self, forKey: .lastRunMessage) ?? ""
        todayClaimedDay = try c.decodeIfPresent(String.self, forKey: .todayClaimedDay) ?? ""
        todayClaimedCount = try c.decodeIfPresent(Int.self, forKey: .todayClaimedCount) ?? 0
        todayCheckinDay = try c.decodeIfPresent(String.self, forKey: .todayCheckinDay) ?? ""
        todayCheckinCount = try c.decodeIfPresent(Int.self, forKey: .todayCheckinCount) ?? 0
        todayCheckinTotal = try c.decodeIfPresent(Int.self, forKey: .todayCheckinTotal) ?? 0
        todaySentDay = try c.decodeIfPresent(String.self, forKey: .todaySentDay) ?? ""
        todaySentCount = try c.decodeIfPresent(Int.self, forKey: .todaySentCount) ?? 0
        freeHuliangCount = try c.decodeIfPresent(Int.self, forKey: .freeHuliangCount) ?? -1
        freeExpiring24h = try c.decodeIfPresent(Int.self, forKey: .freeExpiring24h) ?? 0
        freeEarliestExpire = try c.decodeIfPresent(Int64.self, forKey: .freeEarliestExpire) ?? 0
        superHuliangCount = try c.decodeIfPresent(Int.self, forKey: .superHuliangCount) ?? -1
        superHuliangName = try c.decodeIfPresent(String.self, forKey: .superHuliangName) ?? ""
        sfDayActiveSummary = try c.decodeIfPresent(String.self, forKey: .sfDayActiveSummary) ?? ""
        nextScheduleTime = try c.decodeIfPresent(Int64.self, forKey: .nextScheduleTime) ?? 0
        nextRetryTime = try c.decodeIfPresent(Int64.self, forKey: .nextRetryTime) ?? 0
        nextRescueTime = try c.decodeIfPresent(Int64.self, forKey: .nextRescueTime) ?? 0
        badgesUpdateTime = try c.decodeIfPresent(Int64.self, forKey: .badgesUpdateTime) ?? 0
        badges = try c.decodeIfPresent([FanBadge].self, forKey: .badges) ?? []
        history = try c.decodeIfPresent([RunRecord].self, forKey: .history) ?? []
    }
}

struct BadgeRenderInfo: Codable, Equatable, Hashable {
    let custom: Bool?
    let size: Int
    let hasSuffix: Bool?
    let floorUrl: String
    let iconUrl: String?
    let levelUrl: String?
    let vFlagUrl: String?
    let guardIconUrl: String?

    var isCustom: Bool { custom ?? false }
    var showsSuffix: Bool { hasSuffix ?? false }
}

struct FanBadge: Codable, Identifiable, Equatable, Hashable {
    let pid: Int64
    let badgeId: Int64
    let badgeName: String
    let nick: String
    let avatar: String?
    let roomId: Int32?
    let level: Int
    let score: Int
    let nextScore: Int
    let dayScore: Int
    let quota: Int
    let quotaUsed: Int
    let extinguished: Int
    let badgeType: Int
    let customBadgeFlag: Int
    let superFan: Bool
    let sfType: Int
    let sfVariety: Int
    let sfLevel: Int
    let fansIdentity: Int
    let faithName: String?
    let guardLevel: Int
    let guardName: String?
    let guardType: Int?
    let guardAccompanyDay: Int?
    let guardEndTime: Int64?
    let sfDayActive: Bool
    let sfDay: Int
    let sfDayScoreTimes: Int
    let sfDayQuotaTimes: Int
    let sfDayLeftSec: Int
    let nextSfDayEpoch: Int64
    let checkinState: String?
    let render: BadgeRenderInfo?

    var id: Int64 { pid }

    var isExtinguished: Bool { extinguished != 0 }
    var isGuard: Bool { guardLevel > 0 }
    var isCheckedInToday: Bool { checkinState == "success" || checkinState == "already" }

    var upgradeProgress: Double {
        guard nextScore > 0 else { return 1.0 }
        return min(1.0, max(0.0, Double(score) / Double(nextScore)))
    }

    var quotaProgress: Double {
        guard quota > 0 else { return 0.0 }
        return min(1.0, max(0.0, Double(quotaUsed) / Double(quota)))
    }

    var superFanDayBadgeText: String? {
        if sfDayActive {
            let times = sfDayScoreTimes > 0 ? sfDayScoreTimes : 4
            return "超粉日 \(times)倍"
        }
        if nextSfDayEpoch > 0 {
            let nowMs = Date().timeIntervalSince1970 * 1000
            let diffDays = max(1, Int(ceil((Double(nextSfDayEpoch) * 1000 - nowMs) / 86_400_000)))
            return "\(diffDays)天后超粉日"
        }
        if sfDay > 0 {
            return "每月\(sfDay)日"
        }
        return nil
    }
}

struct NotifyConfig: Codable, Equatable {
    var barkUrl: String = ""
    var telegramBotToken: String = ""
    var telegramChatId: String = ""
    var serverChanKey: String = ""
    var dingTalkWebhook: String = ""
    var feishuWebhook: String = ""
    var customWebhookUrl: String = ""

    init(
        barkUrl: String = "",
        telegramBotToken: String = "",
        telegramChatId: String = "",
        serverChanKey: String = "",
        dingTalkWebhook: String = "",
        feishuWebhook: String = "",
        customWebhookUrl: String = ""
    ) {
        self.barkUrl = barkUrl
        self.telegramBotToken = telegramBotToken
        self.telegramChatId = telegramChatId
        self.serverChanKey = serverChanKey
        self.dingTalkWebhook = dingTalkWebhook
        self.feishuWebhook = feishuWebhook
        self.customWebhookUrl = customWebhookUrl
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        barkUrl = try c.decodeIfPresent(String.self, forKey: .barkUrl) ?? ""
        telegramBotToken = try c.decodeIfPresent(String.self, forKey: .telegramBotToken) ?? ""
        telegramChatId = try c.decodeIfPresent(String.self, forKey: .telegramChatId) ?? ""
        serverChanKey = try c.decodeIfPresent(String.self, forKey: .serverChanKey) ?? ""
        dingTalkWebhook = try c.decodeIfPresent(String.self, forKey: .dingTalkWebhook) ?? ""
        feishuWebhook = try c.decodeIfPresent(String.self, forKey: .feishuWebhook) ?? ""
        customWebhookUrl = try c.decodeIfPresent(String.self, forKey: .customWebhookUrl) ?? ""
    }
}

// MARK: - Auth & Login API Responses

struct APIErrorEnvelope: Codable {
    let ok: Bool?
    let error: String?
    let message: String?
}

struct SendSMSResponse: Codable {
    let ok: Bool
    let deviceId: String?
    let message: String?
    let error: String?
}

struct AccountActionResponse: Codable {
    let ok: Bool
    let message: String?
    let error: String?
    let created: Bool?
    let keptMobile: Bool?
    let account: AccountPublicView?
}

struct QRStartResponse: Codable {
    let ok: Bool
    let sessionId: String?
    let qrId: String?
    let provider: String?
    let imageData: String?
    let error: String?
}

struct ThirdAuthStrategy: Codable, Identifiable, Equatable {
    let strategy: Int
    let dataType: Int?
    let selectTitle: String?
    let promptContent: String?
    let imageData: String?
    let safeAuthUrl: String?

    var id: Int { strategy }

    var displayTitle: String {
        switch strategy {
        case 8: return "短信验证"
        case 4: return "图形验证"
        case 16: return "安全令牌"
        default: return selectTitle?.isEmpty == false ? selectTitle! : "方式 \(strategy)"
        }
    }
}

struct ThirdAuthChallenge: Codable, Equatable, Identifiable {
    let sessionId: String
    let uid: Int64?
    let mode: String // "safe_auth" | "code"
    let message: String?
    let safeAuthUrl: String?
    let safeAuthTitle: String?
    let hasCallback: Bool?
    let strategies: [ThirdAuthStrategy]?

    var id: String { "\(sessionId)-\(mode)-\(safeAuthUrl ?? "")" }
}

struct QRPollResponse: Codable {
    let ok: Bool
    let status: String? // waiting | scanned | need_auth | confirmed | expired | cancelled | failed | re_scan
    let stage: Int?
    let message: String?
    let error: String?
    let created: Bool?
    let keptMobile: Bool?
    let account: AccountPublicView?
    let auth: ThirdAuthChallenge?
}

struct ThirdAuthCodeResponse: Codable {
    let ok: Bool
    let message: String?
    let imageData: String?
    let error: String?
}

struct SimpleOKResponse: Codable {
    let ok: Bool
    let message: String?
    let error: String?
}

struct NotifySaveResponse: Codable {
    let ok: Bool
    let notify: NotifyConfig?
    let error: String?
}

struct LocalNotificationItem: Codable, Identifiable {
    let accountId: String
    let title: String
    let body: String
    let time: Int64

    var id: String { "\(accountId)-\(time)-\(title)" }
}

struct DrainNotificationsResponse: Codable {
    let ok: Bool
    let notifications: [LocalNotificationItem]
}

// MARK: - Synchronous Run-All Response (HubanRunAllSync & POST /api/accounts/run-all)

struct RunAllItemResult: Codable, Identifiable {
    let accountId: String
    let label: String
    let success: Bool
    let message: String
    let title: String?
    let body: String?
    let cooldownSeconds: Int

    var id: String { accountId }
}

struct RunAllResponse: Codable {
    let ok: Bool
    let count: Int?
    let results: [RunAllItemResult]?
    let error: String?
}
