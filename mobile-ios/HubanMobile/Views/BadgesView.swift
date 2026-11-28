import SwiftUI
import UIKit

private extension Color {
    init(hex: UInt32) {
        let r = Double((hex >> 16) & 0xFF) / 255.0
        let g = Double((hex >> 8) & 0xFF) / 255.0
        let b = Double(hex & 0xFF) / 255.0
        self.init(red: r, green: g, blue: b)
    }
}

struct BadgesView: View {
    @EnvironmentObject private var store: HubanStore
    @Binding var showAddAccount: Bool
    var isTabActive: Bool = true

    @State private var selectedBadge: FanBadge?

    var body: some View {
        NavigationStack {
            Group {
                if let account = store.currentAccount {
                    let allBadges = account.state.badges
                    let checkedCnt = allBadges.filter(\.isCheckedInToday).count
                    let guardCnt = allBadges.filter(\.isGuard).count
                    let todayStr = Self.todayString()
                    let statDone = (account.state.todayCheckinDay == todayStr && account.state.todayCheckinTotal > 0)
                        ? account.state.todayCheckinCount
                        : checkedCnt
                    let statTotal = (account.state.todayCheckinDay == todayStr && account.state.todayCheckinTotal > 0)
                        ? account.state.todayCheckinTotal
                        : allBadges.count

                    ScrollView {
                        VStack(spacing: 0) {
                            // 1. Compact 3-column summary card (持有粉丝牌 / 今日打卡 / 最近同步)
                            HStack(spacing: 8) {
                                summaryTile(
                                    label: "持有粉丝牌",
                                    value: "\(allBadges.count) 枚",
                                    valueColor: .primary
                                )
                                summaryTile(
                                    label: "今日打卡",
                                    value: allBadges.isEmpty && account.state.badgesUpdateTime <= 0 ? "未同步" : "\(statDone)/\(statTotal)",
                                    valueColor: HubanTheme.success
                                )
                                summaryTile(
                                    label: "最近同步",
                                    value: Self.formatSyncHM(account.state.badgesUpdateTime),
                                    valueColor: .secondary
                                )
                            }
                            .warmCard(padding: 14, cornerRadius: 18)

                            // 2. Section header row: 我的粉丝牌 + 共 N 枚 · M 守护 + 同步刷新
                            HStack(spacing: 8) {
                                Text("我的粉丝牌")
                                    .font(.subheadline.bold())
                                    .foregroundColor(.secondary)

                                Text(Self.metaSummary(total: allBadges.count, guardCount: guardCnt, synced: account.state.badgesUpdateTime > 0))
                                    .font(.caption)
                                    .foregroundColor(.secondary.opacity(0.75))

                                Spacer(minLength: 0)

                                Button {
                                    Task { await store.refreshAccount(id: account.id) }
                                } label: {
                                    Text(store.isSyncing ? "同步中..." : "同步刷新")
                                        .font(.caption.weight(.semibold))
                                        .foregroundColor(.primary)
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 6)
                                        .background(HubanTheme.cardSecondary)
                                        .overlay(
                                            Capsule().stroke(HubanTheme.border, lineWidth: 1)
                                        )
                                        .clipShape(Capsule())
                                }
                                .buttonStyle(.plain)
                                .disabled(store.isSyncing)
                            }
                            .padding(.horizontal, 4)
                            .padding(.top, 18)
                            .padding(.bottom, 8)

                            // 3. Single unified card container for all fan badges
                            VStack(spacing: 0) {
                                if allBadges.isEmpty {
                                    Text(account.state.badgesUpdateTime > 0
                                         ? "当前虎牙账号暂无粉丝牌。"
                                         : "点击右上角「同步刷新」后，将展示虎牙原生/定制粉丝牌、守护权益与升级进度。")
                                        .font(.footnote)
                                        .foregroundColor(.secondary)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(.vertical, 16)
                                } else {
                                    ForEach(Array(allBadges.enumerated()), id: \.element.id) { index, badge in
                                        Button {
                                            Haptics.light()
                                            selectedBadge = badge
                                        } label: {
                                            BadgeModuleRow(
                                                badge: badge,
                                                staggerIndex: index,
                                                isTabActive: isTabActive
                                            )
                                        }
                                        .buttonStyle(.plain)

                                        if index < allBadges.count - 1 {
                                            Divider()
                                                .overlay(HubanTheme.border.opacity(0.6))
                                        }
                                    }
                                }
                            }
                            .padding(.horizontal, 16)
                            .padding(.vertical, 4)
                            .background(HubanTheme.card)
                            .overlay(
                                RoundedRectangle(cornerRadius: 18, style: .continuous)
                                    .stroke(HubanTheme.border, lineWidth: 1)
                            )
                            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                        .padding(.bottom, 24)
                    }
                    .background(HubanTheme.canvas.ignoresSafeArea())
                    .refreshable {
                        await store.refreshAccount(id: account.id)
                    }
                    .sheet(item: $selectedBadge) { badge in
                        BadgeDetailSheet(account: account, badge: badge)
                            .environmentObject(store)
                            .presentationDetents([.medium, .large])
                            .presentationDragIndicator(.visible)
                    }
                } else {
                    EmptyAccountPromptView {
                        showAddAccount = true
                    }
                }
            }
            .navigationTitle("粉丝牌")
            .navigationBarTitleDisplayMode(.large)
        }
    }

    private func summaryTile(label: String, value: String, valueColor: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption2)
                .foregroundColor(.secondary)
                .lineLimit(1)
            Text(value)
                .font(.headline.monospacedDigit().bold())
                .foregroundColor(valueColor)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 11)
        .padding(.vertical, 10)
        .background(HubanTheme.cardSecondary)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    private static func todayString() -> String {
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "yyyy-MM-dd"
        return df.string(from: Date())
    }

    private static func formatSyncHM(_ epochMs: Int64) -> String {
        guard epochMs > 0 else { return "未同步" }
        let df = DateFormatter()
        df.locale = Locale(identifier: "en_US_POSIX")
        df.dateFormat = "HH:mm"
        return df.string(from: Date(timeIntervalSince1970: Double(epochMs) / 1000.0))
    }

    private static func metaSummary(total: Int, guardCount: Int, synced: Bool) -> String {
        guard total > 0 else { return synced ? "暂无" : "等待同步" }
        if guardCount > 0 {
            return "共 \(total) 枚 · \(guardCount) 守护"
        }
        return "共 \(total) 枚"
    }
}

// MARK: - Module-App Style Flat Badge Row

private struct HorizontalProgressFill: Shape {
    var progress: CGFloat

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func path(in rect: CGRect) -> Path {
        guard progress > 0, rect.width > 0, rect.height > 0 else { return Path() }
        let clamped = min(1.0, max(0.0, progress))
        let width = min(rect.width, max(rect.height, rect.width * clamped))
        let fillRect = CGRect(x: rect.minX, y: rect.minY, width: width, height: rect.height)
        return Path(roundedRect: fillRect, cornerRadius: rect.height / 2.0)
    }
}

private struct BadgeModuleRow: View {
    let badge: FanBadge
    let staggerIndex: Int
    let isTabActive: Bool

    @Environment(\.colorScheme) private var colorScheme
    @State private var barProgress: CGFloat = 0

    private var isSuperFanAny: Bool {
        badge.superFan || badge.sfType > 0 || (badge.fansIdentity >= 1 && badge.fansIdentity <= 4)
    }

    private var resolvedGuardLabel: String {
        if let name = badge.guardName, !name.isEmpty { return name }
        switch badge.guardLevel {
        case 1: return "初爱守护"
        case 2: return "超级守护"
        case 3: return "至尊守护"
        default: return ""
        }
    }

    private var sfDayChipText: String {
        let times = badge.sfDayScoreTimes > 1 ? badge.sfDayScoreTimes : 4
        if badge.sfDayActive {
            return isSuperFanAny ? "超粉日 · \(times)倍" : "今日超粉日"
        }
        let nowSec = Int64(Date().timeIntervalSince1970)
        if badge.nextSfDayEpoch > nowSec {
            let days = max(1, (badge.nextSfDayEpoch - nowSec + 86399) / 86400)
            if days <= 7 {
                return "\(days)天后超粉日"
            }
        }
        if badge.sfDay >= 1 && badge.sfDay <= 31 {
            return "每月\(badge.sfDay)日超粉日"
        }
        return ""
    }

    private var checkinStyle: (label: String, fg: Color, bg: Color) {
        switch badge.checkinState {
        case "success":
            return ("本次已打卡", HubanTheme.success, HubanTheme.success.opacity(colorScheme == .dark ? 0.18 : 0.12))
        case "already":
            return ("今日已打卡", HubanTheme.success, HubanTheme.success.opacity(colorScheme == .dark ? 0.18 : 0.12))
        case "failed":
            return ("打卡失败", HubanTheme.danger, HubanTheme.danger.opacity(colorScheme == .dark ? 0.18 : 0.12))
        default:
            return ("待打卡", .secondary, HubanTheme.cardSecondary)
        }
    }

    private var tierGradient: LinearGradient {
        let colors: [Color]
        if badge.isExtinguished {
            colors = [Color(hex: 0x94A3B8), Color(hex: 0x64748B)]
        } else if badge.level >= 21 {
            colors = [Color(hex: 0xF59E0B), Color(hex: 0xEA580C)]
        } else if badge.level >= 16 {
            colors = [Color(hex: 0xEC4899), Color(hex: 0xDB2777)]
        } else if badge.level >= 11 {
            colors = [Color(hex: 0x8B5CF6), Color(hex: 0x6D28D9)]
        } else if badge.level >= 6 {
            colors = [Color(hex: 0x3B82F6), Color(hex: 0x2563EB)]
        } else {
            colors = [Color(hex: 0x06B6D4), Color(hex: 0x0284C7)]
        }
        return LinearGradient(colors: colors, startPoint: .leading, endPoint: .trailing)
    }

    private var expMetrics: (targetTotal: Int, remaining: Int, ratio: Double) {
        let targetTotal: Int
        let remaining: Int
        if badge.nextScore > badge.score {
            targetTotal = badge.nextScore
            remaining = badge.nextScore - badge.score
        } else if badge.nextScore > 0 {
            targetTotal = badge.score + badge.nextScore
            remaining = badge.nextScore
        } else {
            targetTotal = max(badge.score, 1)
            remaining = 0
        }
        let ratio = targetTotal > 0 ? min(1.0, max(0.0, Double(badge.score) / Double(targetTotal))) : 0.0
        return (targetTotal, remaining, ratio)
    }

    private var todayIntimacy: Int {
        badge.dayScore + max(0, badge.quotaUsed)
    }

    private var hasExtraChips: Bool {
        !sfDayChipText.isEmpty
            || !resolvedGuardLabel.isEmpty
            || !(badge.faithName ?? "").isEmpty
            || badge.isExtinguished
    }

    var body: some View {
        let state = checkinStyle
        let exp = expMetrics

        VStack(alignment: .leading, spacing: 0) {
            // Row 1: [Avatar] [Authentic FanBadge] AnchorNick [打卡状态]
            HStack(spacing: 8) {
                avatarView

                FanBadgePillView(badge: badge, height: 23)

                Text(badge.nick.isEmpty ? (badge.badgeName.isEmpty ? "UID \(badge.pid)" : badge.badgeName) : badge.nick)
                    .font(.system(size: 14.5, weight: .bold))
                    .foregroundColor(.primary)
                    .lineLimit(1)

                Spacer(minLength: 4)

                Text(state.label)
                    .font(.system(size: 11, weight: .bold))
                    .foregroundColor(state.fg)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(state.bg)
                    .clipShape(Capsule())
            }

            // Row 2 (optional): 超粉日 / 守护 / 信仰 / 已熄灭
            if hasExtraChips {
                HStack(spacing: 6) {
                    if !sfDayChipText.isEmpty {
                        let sfFg: Color = badge.sfDayActive
                            ? (colorScheme == .dark ? Color(hex: 0xFDE68A) : Color(hex: 0xB45309))
                            : (isSuperFanAny ? HubanTheme.accent : .secondary)
                        let sfBg: Color = badge.sfDayActive
                            ? (colorScheme == .dark ? Color(hex: 0x422A0E) : Color(hex: 0xFEF3C7))
                            : (isSuperFanAny ? HubanTheme.accent.opacity(0.14) : HubanTheme.cardSecondary)

                        Text(sfDayChipText)
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(sfFg)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(sfBg)
                            .overlay(
                                Capsule().stroke(badge.sfDayActive ? HubanTheme.accent : Color.clear, lineWidth: 1)
                            )
                            .clipShape(Capsule())
                    }

                    if !resolvedGuardLabel.isEmpty {
                        let guardColors = Self.guardPillColors(level: badge.guardLevel, isDark: colorScheme == .dark)
                        HStack(spacing: 4) {
                            GuardShieldBadgeView(badge: badge, height: 15)
                            Text((badge.guardAccompanyDay ?? 0) > 0
                                 ? "\(resolvedGuardLabel) · \(badge.guardAccompanyDay!)天"
                                 : resolvedGuardLabel)
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(guardColors.fg)
                        }
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(guardColors.bg)
                        .clipShape(Capsule())
                    }

                    if let faith = badge.faithName, !faith.isEmpty {
                        Text(faith)
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(colorScheme == .dark ? Color(hex: 0x93C5FD) : Color(hex: 0x1D4ED8))
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(colorScheme == .dark ? Color(hex: 0x1E2D4A) : Color(hex: 0xDBEAFE))
                            .clipShape(Capsule())
                    }

                    if badge.isExtinguished {
                        Text("已熄灭")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 2)
                            .background(HubanTheme.cardSecondary)
                            .clipShape(Capsule())
                    }
                }
                .padding(.top, 7)
            }

            // Row 3: Full-width experience progress bar with tier gradient
            // Uses a pure Animatable Shape so only horizontal fill width animates—
            // never position or frame inside ScrollView.
            let targetRatio: CGFloat = exp.ratio > 0 ? CGFloat(max(0.03, exp.ratio)) : 0
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(HubanTheme.cardSecondary)
                if targetRatio > 0 {
                    HorizontalProgressFill(progress: barProgress)
                        .fill(tierGradient)
                }
            }
            .frame(height: 6)
            .padding(.top, 10)

            // Row 4: Experience numbers (left) + Today's intimacy (right)
            HStack {
                Text(Self.formatExpText(score: badge.score, targetTotal: exp.targetTotal, remaining: exp.remaining))
                    .font(.system(size: 12).monospacedDigit())
                    .foregroundColor(.secondary)
                    .lineLimit(1)

                Spacer(minLength: 6)

                Text(badge.quota > 0
                     ? "今日 \(Self.formatNum(todayIntimacy))/\(Self.formatNum(badge.quota))"
                     : "今日 +\(Self.formatNum(todayIntimacy))")
                    .font(.system(size: 12, weight: .bold).monospacedDigit())
                    .foregroundColor(todayIntimacy > 0 ? HubanTheme.accent : .secondary.opacity(0.75))
                    .lineLimit(1)
            }
            .padding(.top, 7)
        }
        .padding(.vertical, 13)
        .contentShape(Rectangle())
        .onAppear {
            if isTabActive {
                animateProgress(to: exp.ratio > 0 ? CGFloat(max(0.03, exp.ratio)) : 0)
            }
        }
        .onChange(of: isTabActive) { active in
            let target: CGFloat = exp.ratio > 0 ? CGFloat(max(0.03, exp.ratio)) : 0
            if active {
                animateProgress(to: target)
            } else {
                var tx = Transaction()
                tx.disablesAnimations = true
                withTransaction(tx) {
                    barProgress = 0
                }
            }
        }
        .onChange(of: badge.score) { _ in
            if isTabActive {
                let target: CGFloat = exp.ratio > 0 ? CGFloat(max(0.03, exp.ratio)) : 0
                withAnimation(.easeOut(duration: 0.35)) {
                    barProgress = target
                }
            }
        }
    }

    private func animateProgress(to target: CGFloat) {
        var resetTx = Transaction()
        resetTx.disablesAnimations = true
        withTransaction(resetTx) {
            barProgress = 0
        }
        let delay = Double(min(staggerIndex, 10)) * 0.04
        withAnimation(.easeOut(duration: 0.42).delay(delay)) {
            barProgress = target
        }
    }

    private var avatarView: some View {
        Group {
            if let avatar = badge.avatar, let url = URL(string: avatar), !avatar.isEmpty {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFill()
                    default:
                        placeholderAvatar
                    }
                }
            } else {
                placeholderAvatar
            }
        }
        .frame(width: 30, height: 30)
        .clipShape(Circle())
        .overlay(Circle().stroke(HubanTheme.border, lineWidth: 1))
    }

    private var placeholderAvatar: some View {
        ZStack {
            Circle().fill(HubanTheme.accent.opacity(0.16))
            Text(String((badge.nick.isEmpty ? badge.badgeName : badge.nick).prefix(1)))
                .font(.caption.bold())
                .foregroundColor(HubanTheme.accent)
        }
    }

    private static func guardPillColors(level: Int, isDark: Bool) -> (fg: Color, bg: Color) {
        switch level {
        case 3:
            return (isDark ? Color(hex: 0xFB923C) : Color(hex: 0xC2410C),
                    isDark ? Color(hex: 0x3B1E10) : Color(hex: 0xFFEDD5))
        case 2:
            return (isDark ? Color(hex: 0xC084FC) : Color(hex: 0x6D28D9),
                    isDark ? Color(hex: 0x2E1B4B) : Color(hex: 0xF3E8FF))
        default:
            return (isDark ? Color(hex: 0xF472B6) : Color(hex: 0xBE185D),
                    isDark ? Color(hex: 0x3B182C) : Color(hex: 0xFCE7F3))
        }
    }

    private static func formatNum(_ n: Int) -> String {
        let nf = NumberFormatter()
        nf.numberStyle = .decimal
        nf.locale = Locale(identifier: "en_US_POSIX")
        return nf.string(from: NSNumber(value: n)) ?? "\(n)"
    }

    private static func formatExpText(score: Int, targetTotal: Int, remaining: Int) -> String {
        if remaining > 0 {
            return "经验 \(formatNum(score)) / \(formatNum(targetTotal)) · 还差 \(formatNum(remaining))"
        } else if score > 0 {
            return "经验 \(formatNum(score))"
        } else {
            return "经验 0"
        }
    }
}

// MARK: - Badge Detail & Manual Send Sheet

private struct BadgeDetailSheet: View {
    @EnvironmentObject private var store: HubanStore
    @Environment(\.dismiss) private var dismiss

    let account: AccountPublicView
    let badge: FanBadge

    @State private var sendSuper = false
    @State private var countText = "1"
    @State private var isSending = false
    @State private var copiedUID = false

    private var latestAccount: AccountPublicView {
        store.accounts.first(where: { $0.id == account.id }) ?? account
    }

    private var availableNormal: Int {
        max(0, latestAccount.state.freeHuliangCount)
    }

    private var availableSuper: Int {
        max(0, latestAccount.state.superHuliangCount)
    }

    private var currentAvailable: Int {
        sendSuper ? availableSuper : availableNormal
    }

    private var parsedCount: Int {
        max(0, Int(countText.trimmingCharacters(in: .whitespaces)) ?? 0)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // Header Info
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(badge.nick)
                                .font(.title3.bold())
                            HStack(spacing: 8) {
                                FanBadgePillView(badge: badge, height: 26)
                                GuardShieldBadgeView(badge: badge, height: 22)
                            }
                        }
                        Spacer()
                        Button {
                            UIPasteboard.general.string = "\(badge.pid)"
                            Haptics.light()
                            copiedUID = true
                            Task {
                                try? await Task.sleep(nanoseconds: 1_500_000_000)
                                copiedUID = false
                            }
                        } label: {
                            Label(copiedUID ? "已复制" : "复制 UID", systemImage: copiedUID ? "checkmark" : "doc.on.doc")
                                .font(.caption.bold())
                                .foregroundColor(HubanTheme.accent)
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .background(HubanTheme.accent.opacity(0.12))
                                .clipShape(Capsule())
                        }
                    }
                    .warmCard(padding: 14, cornerRadius: 16)

                    // Metrics
                    VStack(spacing: 8) {
                        detailRow("主播 UID", "\(badge.pid)")
                        if let roomId = badge.roomId, roomId > 0 {
                            detailRow("房间号", "\(roomId)")
                        }
                        detailRow("当前等级", "Lv.\(badge.level)（\(badge.score) / \(badge.nextScore)）")
                        detailRow("今日亲密度", "\(badge.quotaUsed) / \(badge.quota)")
                        if let sfText = badge.superFanDayBadgeText {
                            detailRow("超粉日", sfText)
                        }
                        if let guardName = badge.guardName, !guardName.isEmpty {
                            let days = (badge.guardAccompanyDay ?? 0) > 0 ? " · 陪伴 \(badge.guardAccompanyDay!) 天" : ""
                            detailRow("守护身份", guardName + days)
                        }
                    }
                    .warmCard(padding: 14, cornerRadius: 16)

                    // Manual Send Gift Card
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            Label("手动送免费虎粮", systemImage: "paperplane.fill")
                                .font(.headline)
                            Spacer()
                            Text("零消费通道")
                                .font(.caption2.bold())
                                .foregroundColor(HubanTheme.success)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 3)
                                .background(HubanTheme.success.opacity(0.12))
                                .clipShape(Capsule())
                        }

                        Picker("虎粮类型", selection: $sendSuper) {
                            Text("免费虎粮 (\(availableNormal))").tag(false)
                            Text("\(latestAccount.state.superHuliangName.isEmpty ? "超粉虎粮" : latestAccount.state.superHuliangName) (\(availableSuper))").tag(true)
                        }
                        .pickerStyle(.segmented)

                        HStack(spacing: 8) {
                            quickCountChip("1 个", value: 1)
                            quickCountChip("10 个", value: 10)
                            quickCountChip("全部 (\(currentAvailable))", value: max(1, currentAvailable))
                        }

                        HStack(spacing: 10) {
                            Text("自定义数量")
                                .font(.subheadline)
                                .foregroundColor(.secondary)
                            TextField("数量", text: $countText)
                                .keyboardType(.numberPad)
                                .multilineTextAlignment(.trailing)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(HubanTheme.cardSecondary)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }

                        Button {
                            Task { await performSend() }
                        } label: {
                            HStack {
                                if isSending {
                                    ProgressView().tint(.white)
                                } else {
                                    Image(systemName: "paperplane.fill")
                                }
                                Text(isSending ? "正在送出..." : "确认送出 \(parsedCount) 个")
                                    .font(.headline)
                            }
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 13)
                            .background(canSend ? HubanTheme.accentGradient : LinearGradient(colors: [Color.gray.opacity(0.45), Color.gray.opacity(0.45)], startPoint: .leading, endPoint: .trailing))
                            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                        }
                        .disabled(!canSend || isSending)
                    }
                    .warmCard(padding: 14, cornerRadius: 16)
                }
                .padding(16)
            }
            .background(HubanTheme.canvas.ignoresSafeArea())
            .navigationTitle("粉丝牌详情")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private var canSend: Bool {
        parsedCount > 0 && currentAvailable >= parsedCount
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .font(.subheadline)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.subheadline.monospacedDigit().weight(.medium))
                .foregroundColor(.primary)
        }
    }

    private func quickCountChip(_ title: String, value: Int) -> some View {
        Button {
            countText = "\(value)"
            Haptics.light()
        } label: {
            Text(title)
                .font(.caption.bold())
                .foregroundColor(HubanTheme.accent)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(HubanTheme.accent.opacity(0.12))
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private func performSend() async {
        guard parsedCount > 0 else { return }
        isSending = true
        defer { isSending = false }
        do {
            _ = try await store.manualSend(
                accountId: latestAccount.id,
                pid: badge.pid,
                isSuper: sendSuper,
                count: parsedCount
            )
            dismiss()
        } catch {
            store.showToast(error.localizedDescription, isError: true)
        }
    }
}
