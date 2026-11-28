import SwiftUI
import UIKit
import UserNotifications

struct SettingsView: View {
    @EnvironmentObject private var store: HubanStore
    @ObservedObject private var notifManager = LocalNotificationManager.shared
    @ObservedObject private var updater = AppUpdater.shared

    @State private var draftNotify = NotifyConfig()
    @State private var isSavingNotify = false
    @State private var isTestingNotify = false
    @State private var loadedNotifyOnce = false
    @State private var showUpdateSheet = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    shortcutsAutomationCard
                    externalNotifyCard
                    aboutCard
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 28)
            }
            .background(HubanTheme.canvas.ignoresSafeArea())
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.large)
            .onAppear {
                if !loadedNotifyOnce {
                    draftNotify = store.notifyConfig
                    loadedNotifyOnce = true
                }
                Task { await notifManager.refreshStatus() }
            }
            .onChange(of: store.notifyConfig) { newVal in
                draftNotify = newVal
            }
            .sheet(isPresented: $showUpdateSheet) {
                if let info = updater.latestRelease {
                    UpdateDetailSheet(info: info, currentVersion: updater.currentVersionName)
                }
            }
        }
    }

    // MARK: - iOS Shortcuts Automation & Local Notifications

    private var shortcutsAutomationCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("iOS 定时自动化（快捷指令）", systemImage: "bolt.badge.clock.fill")
                    .font(.headline)
                    .foregroundColor(HubanTheme.accent)
                Spacer()
            }

            Text("由于 iOS 系统会限制后台常驻进程，推荐使用系统自带「快捷指令」实现 100% 准时的每日自动领粮打卡：")
                .font(.footnote)
                .foregroundColor(.secondary)

            VStack(alignment: .leading, spacing: 6) {
                stepRow("1", "打开系统「快捷指令」App → 底部「自动化」→ 右上角「+」")
                stepRow("2", "选择「特定时间」（如每天 08:30）→ 选择「立即运行」")
                stepRow("3", "点击「新建空白自动化」→ 搜索并添加「执行虎伴每日任务」")
            }
            .padding(12)
            .background(HubanTheme.cardSecondary)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

            Divider()

            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("系统本地通知权限")
                        .font(.subheadline.weight(.medium))
                    Text(notificationStatusText)
                        .font(.caption)
                        .foregroundColor(notificationStatusColor)
                }
                Spacer()

                if notifManager.authorizationStatus == .notDetermined {
                    Button("开启通知") {
                        Task {
                            let granted = await notifManager.requestAuthorization()
                            store.showToast(granted ? "通知权限已开启" : "未授予通知权限", isError: !granted)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(HubanTheme.accent)
                    .controlSize(.small)
                } else if notifManager.authorizationStatus == .denied {
                    Button("前往设置") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
            }

            Button {
                Task {
                    let ok = await notifManager.postTestNotification()
                    if ok {
                        Haptics.success()
                        store.showToast("已发送本地测试通知")
                    } else {
                        store.showToast("请先在系统设置中允许虎伴发送通知", isError: true)
                    }
                }
            } label: {
                Label("发送本地测试通知", systemImage: "bell.badge.fill")
                    .font(.subheadline.bold())
                    .foregroundColor(HubanTheme.accent)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 11)
                    .background(HubanTheme.accent.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
        }
        .warmCard()
    }

    private func stepRow(_ num: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(num)
                .font(.caption2.bold())
                .foregroundColor(.white)
                .frame(width: 18, height: 18)
                .background(HubanTheme.accent)
                .clipShape(Circle())
            Text(text)
                .font(.caption)
                .foregroundColor(.primary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var notificationStatusText: String {
        switch notifManager.authorizationStatus {
        case .authorized, .provisional, .ephemeral: return "已开启 · 任务完成后将推送横幅提醒"
        case .denied: return "已关闭 · 请在系统设置中开启通知"
        case .notDetermined: return "尚未申请 · 建议开启以接收每日执行结果"
        @unknown default: return "未知状态"
        }
    }

    private var notificationStatusColor: Color {
        switch notifManager.authorizationStatus {
        case .authorized, .provisional, .ephemeral: return HubanTheme.success
        case .denied: return HubanTheme.danger
        default: return .secondary
        }
    }

    // MARK: - External Push Channels

    private var externalNotifyCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("外部推送通道（可选）", systemImage: "paperplane.circle.fill")
                .font(.headline)

            Text("除本机通知外，也可配置 Bark / Telegram / Server酱 / 钉钉 / 飞书 / 自定义 Webhook 同步接收报告。")
                .font(.caption)
                .foregroundColor(.secondary)

            VStack(spacing: 10) {
                notifyField("Bark 推送地址", placeholder: "https://api.day.app/YOUR_KEY", text: $draftNotify.barkUrl)
                notifyField("Telegram Bot Token", placeholder: "123456:ABC-DEF...", text: $draftNotify.telegramBotToken)
                notifyField("Telegram Chat ID", placeholder: "例如 123456789", text: $draftNotify.telegramChatId)
                notifyField("Server酱 SendKey", placeholder: "SCT...", text: $draftNotify.serverChanKey)
                notifyField("钉钉 Webhook", placeholder: "https://oapi.dingtalk.com/robot/send?...", text: $draftNotify.dingTalkWebhook)
                notifyField("飞书 Webhook", placeholder: "https://open.feishu.cn/open-apis/bot/v2/hook/...", text: $draftNotify.feishuWebhook)
                notifyField("自定义 Webhook URL", placeholder: "POST JSON {title, content}", text: $draftNotify.customWebhookUrl)
            }

            HStack(spacing: 10) {
                Button {
                    Task {
                        isSavingNotify = true
                        await store.saveNotify(draftNotify)
                        isSavingNotify = false
                    }
                } label: {
                    HStack {
                        if isSavingNotify { ProgressView().tint(.white) }
                        Text("保存通道配置")
                            .font(.subheadline.bold())
                    }
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(HubanTheme.accentGradient)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .disabled(isSavingNotify)

                Button {
                    Task {
                        isTestingNotify = true
                        await store.saveNotify(draftNotify)
                        await store.testNotify()
                        isTestingNotify = false
                    }
                } label: {
                    HStack {
                        if isTestingNotify { ProgressView().controlSize(.small) }
                        Text("发送测试")
                            .font(.subheadline.bold())
                    }
                    .foregroundColor(HubanTheme.accent)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(HubanTheme.accent.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .disabled(isTestingNotify)
            }
        }
        .warmCard()
    }

    private func notifyField(_ title: String, placeholder: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundColor(.secondary)
            TextField(placeholder, text: text)
                .font(.footnote)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(HubanTheme.cardSecondary)
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
    }

    // MARK: - About Card

    private var aboutCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image("BrandLogo")
                    .resizable()
                    .scaledToFill()
                    .frame(width: 38, height: 38)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .stroke(HubanTheme.border, lineWidth: 0.8)
                    )
                VStack(alignment: .leading, spacing: 2) {
                    Text("关于虎伴")
                        .font(.headline)
                    Text("v\(updater.currentVersionName)")
                        .font(.caption.monospacedDigit())
                        .foregroundColor(.secondary)
                }
                Spacer()
                Button {
                    if updater.hasUpdate {
                        showUpdateSheet = true
                    } else {
                        Task {
                            if let info = await updater.checkForUpdate(manual: true) {
                                if info.hasUpdate {
                                    Haptics.success()
                                    showUpdateSheet = true
                                } else {
                                    store.showToast("当前已是最新版本（v\(updater.currentVersionName)）")
                                }
                            } else if let err = updater.lastError {
                                store.showToast(err, isError: true)
                            }
                        }
                    }
                } label: {
                    HStack(spacing: 5) {
                        if updater.isChecking {
                            ProgressView()
                                .controlSize(.mini)
                                .tint(updater.hasUpdate ? .white : HubanTheme.accent)
                        }
                        Text(updater.isChecking ? "检查中..." : (updater.hasUpdate ? "立即更新" : "检查更新"))
                            .font(.caption.bold())
                    }
                    .foregroundColor(updater.hasUpdate ? .white : HubanTheme.brandText)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(
                        Group {
                            if updater.hasUpdate {
                                HubanTheme.accentGradient
                            } else {
                                HubanTheme.brandTint
                            }
                        }
                    )
                    .clipShape(Capsule())
                }
                .disabled(updater.isChecking)
            }

            VStack(spacing: 8) {
                aboutRow("版本", "v\(updater.currentVersionName)")
                aboutRow("核心版本", store.engineVersion)
                aboutRow(
                    "本机 Go 引擎",
                    store.enginePort > 0 ? "运行中 (127.0.0.1:\(store.enginePort))" : (store.engineError ?? "未启动"),
                    valueColor: store.enginePort > 0 ? HubanTheme.success : HubanTheme.danger
                )
            }

            Divider()

            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "shield.checkered")
                    .foregroundColor(HubanTheme.success)
                    .padding(.top, 2)
                Text("安全承诺：虎伴所有送礼请求均在协议层硬锁为免费包裹通道，绝不消耗任何金豆或虎牙币；账号凭证仅存于本机沙盒内，不上传至任何第三方服务器。")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if let githubURL = URL(string: "https://github.com/UCKETX/HuyaPure") {
                Link(destination: githubURL) {
                    HStack {
                        Label("GitHub 开源仓库 · UCKETX/HuyaPure", systemImage: "link")
                            .font(.subheadline.weight(.medium))
                        Spacer()
                        Image(systemName: "arrow.up.right")
                            .font(.caption.bold())
                    }
                    .foregroundColor(HubanTheme.accent)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(HubanTheme.accent.opacity(0.10))
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
            }
        }
        .warmCard()
    }

    private func aboutRow(_ label: String, _ value: String, valueColor: Color = .primary) -> some View {
        HStack {
            Text(label)
                .font(.subheadline)
                .foregroundColor(.secondary)
            Spacer()
            Text(value)
                .font(.subheadline.monospacedDigit().weight(.medium))
                .foregroundColor(valueColor)
        }
    }
}
