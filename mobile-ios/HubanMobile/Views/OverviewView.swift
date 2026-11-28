import SwiftUI

struct OverviewView: View {
    @EnvironmentObject private var store: HubanStore
    @Binding var selectedTab: HubanTab
    @Binding var showAddAccount: Bool
    @State private var showTargetsSheet = false
    @State private var showDistributeSheet = false
    @State private var showMaxCountSheet = false

    var body: some View {
        NavigationStack {
            Group {
                if let account = store.currentAccount {
                    ScrollView {
                        VStack(spacing: 14) {
                            if !account.state.sfDayActiveSummary.isEmpty {
                                superFanDayBanner(summary: account.state.sfDayActiveSummary)
                            }

                            statsGrid(account: account)
                            actionCard(account: account)
                            automationSettingsCard(account: account)
                            historySection(account: account)
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                        .padding(.bottom, 24)
                    }
                    .background(HubanTheme.canvas.ignoresSafeArea())
                    .refreshable {
                        await store.refreshAccount(id: account.id)
                    }
                    .sheet(isPresented: $showTargetsSheet) {
                        SendTargetsSheet(account: account)
                            .environmentObject(store)
                    }
                    .sheet(isPresented: $showDistributeSheet) {
                        DistributeModeSheet(
                            selected: DistributeMode(rawValue: account.config.sendDistributeMode) ?? .fillQuota,
                            onSelect: { mode in
                                var next = account.config
                                next.sendDistributeMode = mode.rawValue
                                Task { await store.updateConfig(accountId: account.id, config: next) }
                            }
                        )
                    }
                    .sheet(isPresented: $showMaxCountSheet) {
                        MaxSendCountSheet(
                            currentValue: account.config.sendMaxCount,
                            onSave: { newVal in
                                var next = account.config
                                next.sendMaxCount = max(0, newVal)
                                Task { await store.updateConfig(accountId: account.id, config: next) }
                            }
                        )
                    }
                } else if store.isLoading {
                    VStack(spacing: 12) {
                        ProgressView()
                            .tint(HubanTheme.accent)
                        Text("正在启动本机虎伴引擎...")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(HubanTheme.canvas.ignoresSafeArea())
                } else {
                    EmptyAccountPromptView {
                        showAddAccount = true
                    }
                }
            }
            .navigationTitle("虎伴")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                if let account = store.currentAccount {
                    ToolbarItem(placement: .navigationBarLeading) {
                        accountSwitcherMenu(current: account)
                    }
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button {
                            Task { await store.refreshAccount(id: account.id) }
                        } label: {
                            if store.isSyncing {
                                ProgressView()
                                    .controlSize(.small)
                            } else {
                                Label("同步", systemImage: "arrow.triangle.2.circlepath")
                                    .labelStyle(.iconOnly)
                            }
                        }
                        .disabled(store.isSyncing)
                    }
                } else {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button {
                            showAddAccount = true
                        } label: {
                            Image(systemName: "plus")
                        }
                    }
                }
            }
        }
    }

    // MARK: - Account Switcher

    private func accountSwitcherMenu(current: AccountPublicView) -> some View {
        Menu {
            ForEach(store.accounts) { acc in
                Button {
                    store.selectAccount(acc.id)
                } label: {
                    HStack {
                        Text(acc.displayName)
                        if acc.id == current.id {
                            Image(systemName: "checkmark")
                        }
                    }
                }
            }
            Divider()
            Button {
                showAddAccount = true
            } label: {
                Label("添加新账号", systemImage: "person.badge.plus")
            }
        } label: {
            if #available(iOS 26.0, *) {
                // On iOS 26+, ToolbarItem automatically wraps content in a native Liquid Glass capsule
                HStack(spacing: 6) {
                    Circle()
                        .fill(current.isMobileAuth ? HubanTheme.success : HubanTheme.warning)
                        .frame(width: 7, height: 7)
                    Text(current.displayName)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.primary)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption2.weight(.bold))
                        .foregroundColor(.secondary)
                }
                .padding(.horizontal, 4)
            } else {
                HStack(spacing: 6) {
                    Circle()
                        .fill(current.isMobileAuth ? HubanTheme.success : HubanTheme.warning)
                        .frame(width: 7, height: 7)
                    Text(current.displayName)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.primary)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption2.weight(.bold))
                        .foregroundColor(.secondary)
                }
                .padding(.horizontal, 11)
                .padding(.vertical, 6)
                .background(HubanTheme.card)
                .overlay(Capsule().stroke(HubanTheme.border, lineWidth: 1))
                .clipShape(Capsule())
            }
        }
    }

    // MARK: - SuperFan Day Banner

    private func superFanDayBanner(summary: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "sparkles")
                .font(.title3.weight(.bold))
                .foregroundColor(.white)
            VStack(alignment: .leading, spacing: 2) {
                Text("今日超粉日 · 4 倍亲密度")
                    .font(.subheadline.bold())
                    .foregroundColor(.white)
                Text(summary)
                    .font(.caption)
                    .foregroundColor(.white.opacity(0.92))
                    .lineLimit(2)
            }
            Spacer(minLength: 4)
            Button {
                Haptics.light()
                selectedTab = .badges
            } label: {
                Text("去送粮")
                    .font(.caption.bold())
                    .foregroundColor(HubanTheme.accentDeep)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.white)
                    .clipShape(Capsule())
            }
        }
        .padding(14)
        .background(HubanTheme.superFanGradient)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .shadow(color: HubanTheme.accent.opacity(0.28), radius: 10, x: 0, y: 4)
    }

    // MARK: - 3-Column Stats

    private func statsGrid(account: AccountPublicView) -> some View {
        let st = account.state
        let today = HubanFormat.todayKey
        let claimed = st.todayClaimedDay == today ? st.todayClaimedCount : 0
        let chkCount = st.todayCheckinDay == today ? st.todayCheckinCount : 0
        let chkTotal = st.todayCheckinTotal > 0 ? st.todayCheckinTotal : st.badges.count
        let freeText = st.freeHuliangCount >= 0 ? "\(st.freeHuliangCount)" : "--"
        let sentToday = st.todaySentDay == today ? st.todaySentCount : 0

        return HStack(spacing: 10) {
            statTile(
                title: "今日领虎粮",
                value: "\(claimed)",
                unit: "个",
                footnote: sentToday > 0 ? "今日已送 \(sentToday)" : "免费广告/福利",
                footnoteColor: .secondary,
                icon: "gift.fill",
                tint: HubanTheme.accent
            )

            statTile(
                title: "粉丝牌打卡",
                value: "\(chkCount)",
                unit: "/\(chkTotal)",
                footnote: chkTotal > 0 && chkCount >= chkTotal ? "今日已全勤" : "全牌自动打卡",
                footnoteColor: (chkTotal > 0 && chkCount >= chkTotal) ? HubanTheme.success : .secondary,
                icon: "checkmark.seal.fill",
                tint: HubanTheme.success
            )

            let freeFootnote: String = {
                if st.freeExpiring24h > 0 {
                    return "24h临期 \(st.freeExpiring24h)"
                }
                if st.superHuliangCount > 0 {
                    let sName = st.superHuliangName.isEmpty ? "超粉粮" : st.superHuliangName
                    return "\(sName) \(st.superHuliangCount)"
                }
                return "仅免费包裹"
            }()

            statTile(
                title: "免费虎粮",
                value: freeText,
                unit: st.freeHuliangCount >= 0 ? "个" : "",
                footnote: freeFootnote,
                footnoteColor: st.freeExpiring24h > 0 ? HubanTheme.warning : .secondary,
                icon: "shippingbox.fill",
                tint: HubanTheme.warning
            )
        }
    }

    private func statTile(
        title: String,
        value: String,
        unit: String,
        footnote: String,
        footnoteColor: Color,
        icon: String,
        tint: Color
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 4) {
                Image(systemName: icon)
                    .font(.caption2.weight(.bold))
                    .foregroundColor(tint)
                Text(title)
                    .font(.caption2.weight(.medium))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }

            HStack(alignment: .lastTextBaseline, spacing: 2) {
                Text(value)
                    .font(.system(size: 22, weight: .bold, design: .rounded))
                    .foregroundColor(.primary)
                    .minimumScaleFactor(0.65)
                    .lineLimit(1)
                if !unit.isEmpty {
                    Text(unit)
                        .font(.caption2.weight(.semibold))
                        .foregroundColor(.secondary)
                }
            }

            Text(footnote)
                .font(.system(size: 10.5, weight: .medium))
                .foregroundColor(footnoteColor)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .warmCard(padding: 12, cornerRadius: 16)
    }

    // MARK: - Primary Run Action Card

    private func actionCard(account: AccountPublicView) -> some View {
        let st = account.state
        return VStack(alignment: .leading, spacing: 12) {
            Button {
                Task { await store.runAccount(id: account.id) }
            } label: {
                HStack(spacing: 10) {
                    if st.running {
                        ProgressView()
                            .tint(.white)
                    } else {
                        Image(systemName: "play.fill")
                            .font(.subheadline.weight(.bold))
                    }
                    Text(st.running ? "任务执行中..." : "立即执行任务")
                        .font(.headline)
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(HubanTheme.accentGradient)
                .clipShape(Capsule())
                .shadow(color: HubanTheme.accent.opacity(0.28), radius: 8, x: 0, y: 4)
                .pulseWhenRunning(st.running)
            }
            .buttonStyle(HubanBounceButtonStyle(pressedScale: 0.97))
            .disabled(st.running)

            if st.lastRunTime > 0 || !st.lastRunMessage.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Image(systemName: st.lastRunSuccess ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                            .font(.caption)
                            .foregroundColor(st.lastRunSuccess ? HubanTheme.success : HubanTheme.warning)
                        Text("上次执行：\(HubanFormat.formatTimeMs(st.lastRunTime))")
                            .font(.caption.weight(.semibold))
                            .foregroundColor(.secondary)
                    }
                    if !st.lastRunMessage.isEmpty {
                        Text(st.lastRunMessage)
                            .font(.footnote)
                            .foregroundColor(.primary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }

            if st.nextScheduleTime > 0 || st.nextRescueTime > 0 || st.nextRetryTime > 0 {
                Divider()
                VStack(alignment: .leading, spacing: 4) {
                    if st.nextScheduleTime > 0 {
                        scheduleMetaRow(icon: "clock", label: "下次定时", value: HubanFormat.formatTimeMs(st.nextScheduleTime))
                    }
                    if st.nextRetryTime > 0 {
                        scheduleMetaRow(icon: "arrow.clockwise", label: "冷却重试", value: HubanFormat.formatTimeMs(st.nextRetryTime))
                    }
                    if st.nextRescueTime > 0 {
                        scheduleMetaRow(icon: "hourglass.bottomhalf.filled", label: "临期抢救", value: HubanFormat.formatTimeMs(st.nextRescueTime))
                    }
                }
            }
        }
        .warmCard()
    }

    private func scheduleMetaRow(icon: String, label: String, value: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon)
                .font(.caption)
                .foregroundColor(HubanTheme.accent)
                .frame(width: 16)
            Text(label)
                .font(.caption)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.caption.monospacedDigit().weight(.medium))
                .foregroundColor(.primary)
        }
    }

    // MARK: - Automation Settings Card

    private func automationSettingsCard(account: AccountPublicView) -> some View {
        let cfg = account.config

        func patch(_ mutate: (inout AccountConfig) -> Void) {
            var next = account.config
            mutate(&next)
            Task { await store.updateConfig(accountId: account.id, config: next) }
        }

        let scheduleTimeBinding = Binding<Date>(
            get: {
                var comps = Calendar.current.dateComponents([.year, .month, .day], from: Date())
                comps.hour = cfg.autoClaimHour
                comps.minute = cfg.autoClaimMinute
                return Calendar.current.date(from: comps) ?? Date()
            },
            set: { newDate in
                let comps = Calendar.current.dateComponents([.hour, .minute], from: newDate)
                patch {
                    $0.autoClaimHour = comps.hour ?? 1
                    $0.autoClaimMinute = comps.minute ?? 0
                }
            }
        )

        let currentDistMode = DistributeMode(rawValue: cfg.sendDistributeMode) ?? .fillQuota

        return VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("自动化任务配置", systemImage: "slider.horizontal.3")
                    .font(.headline)
                Spacer()
                if account.state.superHuliangCount > 0 {
                    Text("\(account.state.superHuliangName.isEmpty ? "超粉虎粮" : account.state.superHuliangName) \(account.state.superHuliangCount)")
                        .font(.caption2.bold())
                        .foregroundColor(HubanTheme.accent)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(HubanTheme.accent.opacity(0.12))
                        .clipShape(Capsule())
                }
            }

            VStack(spacing: 12) {
                Toggle(isOn: Binding(
                    get: { cfg.autoClaimEnabled },
                    set: { v in patch { $0.autoClaimEnabled = v } }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("启用每日定时任务")
                            .font(.subheadline.weight(.medium))
                        Text("iOS 建议配合「快捷指令」自动化准时触发")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                .tint(HubanTheme.accent)

                if cfg.autoClaimEnabled {
                    HStack {
                        Text("每日执行时间")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                        Spacer()
                        DatePicker("", selection: scheduleTimeBinding, displayedComponents: .hourAndMinute)
                            .labelsHidden()
                    }
                }

                Divider()

                settingToggle(
                    title: "看广告领虎粮",
                    subtitle: "自动领取每日免费包裹广告虎粮",
                    isOn: Binding(get: { cfg.autoAdHuliang }, set: { v in patch { $0.autoAdHuliang = v } })
                )

                settingToggle(
                    title: "全粉丝牌打卡",
                    subtitle: "自动为全部已拥有粉丝牌打卡续亲密度",
                    isOn: Binding(get: { cfg.autoFansCheckin }, set: { v in patch { $0.autoFansCheckin = v } })
                )

                settingToggle(
                    title: "每日福利领取",
                    subtitle: "自动领取粉丝团每日虎粮礼盒与通用福利",
                    isOn: Binding(get: { cfg.autoClaimWelfare }, set: { v in patch { $0.autoClaimWelfare = v } })
                )

                Divider()

                settingToggle(
                    title: "自动送免费虎粮",
                    subtitle: "仅使用免费包裹通道，绝不消耗金豆/虎牙币",
                    isOn: Binding(get: { cfg.autoSendHuliang }, set: { v in patch { $0.autoSendHuliang = v } })
                )

                settingToggle(
                    title: "自动送超粉虎粮",
                    subtitle: "同步送出背包内专属超粉免费虎粮",
                    isOn: Binding(get: { cfg.sendSuperHuliang }, set: { v in patch { $0.sendSuperHuliang = v } })
                )

                settingToggle(
                    title: "超粉日 4 倍智能囤粮",
                    subtitle: "非超粉日囤粮保临期，超粉日 4 倍亲密度集中送出",
                    isOn: Binding(get: { cfg.smartSuperFanDay }, set: { v in patch { $0.smartSuperFanDay = v } })
                )

                Divider()

                // Send targets selector button
                Button {
                    Haptics.light()
                    showTargetsSheet = true
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("赠送目标")
                                .font(.subheadline.weight(.medium))
                                .foregroundColor(.primary)
                            Text(targetsSummary(cfg.sendTargets))
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                        Spacer()
                        Text("\(cfg.sendTargets.count) 个")
                            .font(.caption.bold())
                            .foregroundColor(HubanTheme.accent)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(HubanTheme.accent.opacity(0.12))
                            .clipShape(Capsule())
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundColor(.secondary)
                    }
                }

                // Distribute Mode Selector Sheet
                Button {
                    Haptics.light()
                    showDistributeSheet = true
                } label: {
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("多目标分配策略")
                                .font(.subheadline.weight(.medium))
                                .foregroundColor(.primary)
                            Text(currentDistMode.subtitle)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 6)
                        Text(currentDistMode.shortTitle)
                            .font(.caption.bold())
                            .foregroundColor(HubanTheme.accent)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 4)
                            .background(HubanTheme.accent.opacity(0.12))
                            .clipShape(Capsule())
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundColor(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                // Max Send Count Selector Sheet
                Button {
                    Haptics.light()
                    showMaxCountSheet = true
                } label: {
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("单次送出上限")
                                .font(.subheadline.weight(.medium))
                                .foregroundColor(.primary)
                            Text("0 为不限，临期批次自动突破上限防过期")
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                        Spacer(minLength: 6)
                        Text(cfg.sendMaxCount == 0 ? "不限" : "\(cfg.sendMaxCount) 个")
                            .font(.caption.monospacedDigit().bold())
                            .foregroundColor(HubanTheme.accent)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 4)
                            .background(HubanTheme.accent.opacity(0.12))
                            .clipShape(Capsule())
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundColor(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .warmCard()
    }

    private func settingToggle(title: String, subtitle: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.medium))
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .tint(HubanTheme.accent)
    }

    private func targetsSummary(_ targets: [SendTarget]) -> String {
        if targets.isEmpty {
            return "未指定（点击选择主播或添加自定义 UID）"
        }
        let names = targets.prefix(3).map { $0.name.isEmpty ? "\($0.pid)" : $0.name }
        let suffix = targets.count > 3 ? " 等" : ""
        return names.joined(separator: "、") + suffix
    }

    // MARK: - Recent History

    private func historySection(account: AccountPublicView) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Label("最近执行记录", systemImage: "clock.arrow.circlepath")
                    .font(.headline)
                Spacer()
                if !account.state.history.isEmpty {
                    Text("最近 \(min(account.state.history.count, 10)) 条")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }

            if account.state.history.isEmpty {
                Text("暂无执行记录，点击上方「立即执行任务」体验全自动领粮与打卡。")
                    .font(.footnote)
                    .foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
            } else {
                ForEach(account.state.history.prefix(10)) { rec in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 8) {
                            Image(systemName: rec.success ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .foregroundColor(rec.success ? HubanTheme.success : HubanTheme.danger)
                                .font(.subheadline)
                            Text(rec.triggerLabel)
                                .font(.caption2.bold())
                                .padding(.horizontal, 7)
                                .padding(.vertical, 2)
                                .background(HubanTheme.cardSecondary)
                                .clipShape(Capsule())
                            Spacer()
                            Text(HubanFormat.formatShortDateTimeMs(rec.time))
                                .font(.caption2.monospacedDigit())
                                .foregroundColor(.secondary)
                        }

                        if !rec.message.isEmpty {
                            Text(rec.message)
                                .font(.footnote)
                                .foregroundColor(.primary)
                                .fixedSize(horizontal: false, vertical: true)
                        }

                        HStack(spacing: 12) {
                            if rec.adClaimed > 0 {
                                Label("领粮 \(rec.adClaimed)", systemImage: "gift")
                            }
                            if rec.checkinAll > 0 {
                                Label("打卡 \(rec.checkinOk)/\(rec.checkinAll)", systemImage: "checkmark.seal")
                            }
                            if rec.sentNormal > 0 || rec.sentSuper > 0 {
                                Label("送出 \(rec.sentNormal + rec.sentSuper)", systemImage: "paperplane")
                            }
                        }
                        .font(.caption2)
                        .foregroundColor(.secondary)
                    }
                    .padding(12)
                    .background(HubanTheme.cardSecondary.opacity(0.65))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
            }
        }
        .warmCard()
    }
}

// MARK: - Distribute Mode Selection Sheet

private struct DistributeModeSheet: View {
    let selected: DistributeMode
    let onSelect: (DistributeMode) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(DistributeMode.allCases) { mode in
                        let isSelected = mode == selected
                        Button {
                            Haptics.light()
                            onSelect(mode)
                            dismiss()
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: mode.iconName)
                                    .font(.system(size: 18, weight: .semibold))
                                    .foregroundColor(isSelected ? HubanTheme.accent : .secondary)
                                    .frame(width: 34, height: 34)
                                    .background(isSelected ? HubanTheme.accent.opacity(0.14) : HubanTheme.cardSecondary)
                                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                                VStack(alignment: .leading, spacing: 3) {
                                    Text(mode.title)
                                        .font(.subheadline.weight(isSelected ? .bold : .semibold))
                                        .foregroundColor(isSelected ? HubanTheme.accent : .primary)
                                    Text(mode.subtitle)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }

                                Spacer(minLength: 8)

                                if isSelected {
                                    Image(systemName: "checkmark.circle.fill")
                                        .font(.system(size: 20))
                                        .foregroundColor(HubanTheme.accent)
                                }
                            }
                            .padding(14)
                            .background(isSelected ? HubanTheme.brandTint : HubanTheme.card)
                            .overlay(
                                RoundedRectangle(cornerRadius: 15, style: .continuous)
                                    .stroke(isSelected ? HubanTheme.accent : HubanTheme.border, lineWidth: isSelected ? 1.2 : 1)
                            )
                            .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(16)
            }
            .background(HubanTheme.canvas.ignoresSafeArea())
            .navigationTitle("多目标分配策略")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }
}

// MARK: - Max Send Count Sheet

private struct MaxSendCountSheet: View {
    let currentValue: Int
    let onSave: (Int) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var inputText: String = ""

    private let quickOptions: [(title: String, value: Int)] = [
        ("不限", 0),
        ("10 个", 10),
        ("30 个", 30),
        ("50 个", 50),
        ("100 个", 100),
        ("200 个", 200),
    ]

    private var parsedValue: Int {
        max(0, min(99999, Int(inputText.trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0))
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("设置每次自动送礼最多送出的免费虎粮数量；设为 0 表示不限，临期即将过期的批次会自动突破上限以防浪费。")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                LazyVGrid(columns: [
                    GridItem(.flexible(), spacing: 10),
                    GridItem(.flexible(), spacing: 10),
                    GridItem(.flexible(), spacing: 10),
                ], spacing: 10) {
                    ForEach(quickOptions, id: \.value) { opt in
                        let active = parsedValue == opt.value
                        Button {
                            Haptics.light()
                            inputText = "\(opt.value)"
                        } label: {
                            Text(opt.title)
                                .font(.subheadline.weight(active ? .bold : .medium))
                                .foregroundColor(active ? HubanTheme.accent : .primary)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                                .background(active ? HubanTheme.brandTint : HubanTheme.card)
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .stroke(active ? HubanTheme.accent : HubanTheme.border, lineWidth: active ? 1.2 : 1)
                                )
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }

                HStack(spacing: 10) {
                    Text("自定义数量")
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(.secondary)
                    TextField("0 为不限", text: $inputText)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .font(.subheadline.monospacedDigit().weight(.semibold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(HubanTheme.card)
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(HubanTheme.border, lineWidth: 1)
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }

                Button {
                    Haptics.success()
                    onSave(parsedValue)
                    dismiss()
                } label: {
                    Text(parsedValue == 0 ? "保存（不限）" : "保存（单次上限 \(parsedValue) 个）")
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                        .background(HubanTheme.accentGradient)
                        .clipShape(Capsule())
                }
                .padding(.top, 4)

                Spacer(minLength: 0)
            }
            .padding(16)
            .background(HubanTheme.canvas.ignoresSafeArea())
            .navigationTitle("单次送出上限")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
            .onAppear {
                inputText = "\(max(0, currentValue))"
            }
        }
        .presentationDetents([.height(360)])
        .presentationDragIndicator(.visible)
    }
}
