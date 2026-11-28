import SwiftUI

enum HubanTab: Int, CaseIterable, Hashable {
    case overview = 0
    case badges = 1
    case accounts = 2
    case settings = 3

    var title: String {
        switch self {
        case .overview: return "任务"
        case .badges: return "粉丝牌"
        case .accounts: return "账号"
        case .settings: return "设置"
        }
    }

    var outlineIcon: String {
        switch self {
        case .overview: return "bolt"
        case .badges: return "heart"
        case .accounts: return "person"
        case .settings: return "gearshape"
        }
    }

    var filledIcon: String {
        switch self {
        case .overview: return "bolt.fill"
        case .badges: return "heart.fill"
        case .accounts: return "person.fill"
        case .settings: return "gearshape.fill"
        }
    }
}

struct ContentView: View {
    @EnvironmentObject private var store: HubanStore
    @ObservedObject private var updater = AppUpdater.shared
    @State private var selectedTab: HubanTab = .overview
    @State private var showAddAccount = false
    @State private var showUpdateSheet = false

    var body: some View {
        ZStack(alignment: .top) {
            TabView(selection: $selectedTab) {
                OverviewView(selectedTab: $selectedTab, showAddAccount: $showAddAccount)
                    .tabItem {
                        Label(
                            HubanTab.overview.title,
                            systemImage: selectedTab == .overview
                                ? HubanTab.overview.filledIcon
                                : HubanTab.overview.outlineIcon
                        )
                    }
                    .tag(HubanTab.overview)

                BadgesView(showAddAccount: $showAddAccount, isTabActive: selectedTab == .badges)
                    .tabItem {
                        Label(
                            HubanTab.badges.title,
                            systemImage: selectedTab == .badges
                                ? HubanTab.badges.filledIcon
                                : HubanTab.badges.outlineIcon
                        )
                    }
                    .tag(HubanTab.badges)

                AccountsView(showAddAccount: $showAddAccount)
                    .tabItem {
                        Label(
                            HubanTab.accounts.title,
                            systemImage: selectedTab == .accounts
                                ? HubanTab.accounts.filledIcon
                                : HubanTab.accounts.outlineIcon
                        )
                    }
                    .tag(HubanTab.accounts)

                SettingsView()
                    .tabItem {
                        Label(
                            HubanTab.settings.title,
                            systemImage: selectedTab == .settings
                                ? HubanTab.settings.filledIcon
                                : HubanTab.settings.outlineIcon
                        )
                    }
                    .badge(updater.hasUpdate ? "新" : nil)
                    .tag(HubanTab.settings)
            }
            .onChange(of: selectedTab) { _ in
                Haptics.light()
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                if updater.shouldShowBanner, let info = updater.latestRelease {
                    UpdateBannerView(
                        info: info,
                        onOpen: { showUpdateSheet = true },
                        onDismiss: {
                            withAnimation(.easeOut(duration: 0.2)) {
                                updater.dismissBanner()
                            }
                        }
                    )
                    .padding(.horizontal, 14)
                    .padding(.top, 6)
                    .padding(.bottom, 4)
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
            }

            if let toast = store.toast {
                ToastView(banner: toast)
                    .padding(.top, 8)
                    .padding(.horizontal, 16)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .zIndex(100)
            }
        }
        .tint(HubanTheme.accent)
        .sheet(isPresented: $showAddAccount) {
            AddAccountSheet()
                .environmentObject(store)
        }
        .sheet(isPresented: $showUpdateSheet) {
            if let info = updater.latestRelease {
                UpdateDetailSheet(info: info, currentVersion: updater.currentVersionName)
            }
        }
    }
}

private struct ToastView: View {
    let banner: ToastBanner

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: banner.isError ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                .foregroundColor(banner.isError ? HubanTheme.danger : HubanTheme.success)
                .font(.system(size: 16, weight: .semibold))
            Text(banner.message)
                .font(.subheadline.weight(.medium))
                .foregroundColor(.primary)
                .multilineTextAlignment(.leading)
                .lineLimit(3)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(.ultraThinMaterial)
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(banner.isError ? HubanTheme.danger.opacity(0.35) : HubanTheme.border, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .shadow(color: Color.black.opacity(0.12), radius: 12, x: 0, y: 6)
    }
}

struct EmptyAccountPromptView: View {
    let onAddTap: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Spacer()
            Image("BrandLogo")
                .resizable()
                .scaledToFill()
                .frame(width: 84, height: 84)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .stroke(HubanTheme.border, lineWidth: 1)
                )
                .shadow(color: Color.black.opacity(0.14), radius: 12, x: 0, y: 6)

            VStack(spacing: 8) {
                Text("欢迎使用虎伴")
                    .font(.title2.bold())
                Text("添加虎牙账号后，即可在本机自动领免费虎粮、全粉丝牌打卡、领每日福利并按超粉日 4 倍策略智能囤粮送粮。")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 28)
            }

            Button(action: onAddTap) {
                Label("添加虎牙账号", systemImage: "plus.circle.fill")
                    .font(.headline)
                    .foregroundColor(.white)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 13)
                    .background(HubanTheme.accentGradient)
                    .clipShape(Capsule())
                    .shadow(color: HubanTheme.accent.opacity(0.32), radius: 10, x: 0, y: 5)
            }
            .padding(.top, 4)

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(HubanTheme.canvas.ignoresSafeArea())
    }
}

private struct UpdateBannerView: View {
    let info: AppReleaseInfo
    let onOpen: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            Button(action: onOpen) {
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 6) {
                            Text("发现新版本 v\(info.versionName)")
                                .font(.subheadline.bold())
                                .foregroundColor(HubanTheme.brandText)
                            if !info.formattedSize.isEmpty {
                                Text("· \(info.formattedSize)")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                        Text(info.releaseNotes.replacingOccurrences(of: "\r", with: ""))
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    Text("更新")
                        .font(.caption.bold())
                        .foregroundColor(.white)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(HubanTheme.accentGradient)
                        .clipShape(Capsule())
                }
            }
            .buttonStyle(.plain)

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.caption2.bold())
                    .foregroundColor(.secondary)
                    .padding(6)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(HubanTheme.brandTint)
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(HubanTheme.accent.opacity(0.65), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

struct UpdateDetailSheet: View {
    let info: AppReleaseInfo
    let currentVersion: String
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 12) {
                        ZStack {
                            Circle()
                                .fill(HubanTheme.accent.opacity(0.14))
                                .frame(width: 48, height: 48)
                            Image(systemName: "arrow.down.circle.fill")
                                .font(.system(size: 24, weight: .semibold))
                                .foregroundStyle(HubanTheme.accentGradient)
                        }
                        VStack(alignment: .leading, spacing: 3) {
                            Text("发现新版本 v\(info.versionName)")
                                .font(.headline)
                            Text("当前版本 v\(currentVersion) → v\(info.versionName)" + (info.formattedSize.isEmpty ? "" : " · \(info.formattedSize)"))
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                    }
                    .padding(14)
                    .background(HubanTheme.card)
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(HubanTheme.border, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

                    VStack(alignment: .leading, spacing: 8) {
                        Text("更新内容")
                            .font(.subheadline.bold())
                        Text(info.releaseNotes)
                            .font(.footnote)
                            .foregroundColor(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(HubanTheme.card)
                    .overlay(
                        RoundedRectangle(cornerRadius: 16, style: .continuous)
                            .stroke(HubanTheme.border, lineWidth: 1)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))

                    Text("iOS 端请在浏览器下载最新 IPA 后，通过 TrollStore / AltStore / SideStore 等自签工具覆盖安装即可保留本地数据。")
                        .font(.caption)
                        .foregroundColor(.secondary)
                        .padding(.horizontal, 4)

                    VStack(spacing: 10) {
                        if let dlUrl = info.preferredDownloadURL {
                            Button {
                                openURL(dlUrl)
                            } label: {
                                Label(info.ipaDownloadUrl.isEmpty ? "前往 Release 下载" : "下载 IPA 安装包", systemImage: "arrow.down.to.line")
                                    .font(.subheadline.bold())
                                    .foregroundColor(.white)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 13)
                                    .background(HubanTheme.accentGradient)
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                        }

                        if let pageUrl = info.releasePageURL {
                            Button {
                                openURL(pageUrl)
                            } label: {
                                Label("在浏览器打开 GitHub Release", systemImage: "safari")
                                    .font(.subheadline.weight(.medium))
                                    .foregroundColor(HubanTheme.accent)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 11)
                                    .background(HubanTheme.accent.opacity(0.10))
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            }
                        }
                    }
                }
                .padding(16)
            }
            .background(HubanTheme.canvas.ignoresSafeArea())
            .navigationTitle("版本更新")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("稍后") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
