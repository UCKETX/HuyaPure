import Foundation
import SwiftUI

struct ToastBanner: Equatable {
    let id = UUID()
    let message: String
    let isError: Bool
}

@MainActor
final class HubanStore: ObservableObject {
    private static let selectedAccountDefaultsKey = "huban_selected_account_id"

    @Published private(set) var enginePort: Int = 0
    @Published private(set) var engineError: String?
    @Published private(set) var engineVersion: String = HubanCoreBridge.appVersion
    @Published private(set) var accounts: [AccountPublicView] = []
    @Published var selectedAccountId: String = "" {
        didSet {
            UserDefaults.standard.set(selectedAccountId, forKey: Self.selectedAccountDefaultsKey)
        }
    }
    @Published var notifyConfig: NotifyConfig = NotifyConfig()
    @Published private(set) var isLoading: Bool = false
    @Published private(set) var isSyncing: Bool = false
    @Published private(set) var toast: ToastBanner?

    private let api = HubanAPI()
    private var pollTask: Task<Void, Never>?
    private var toastDismissTask: Task<Void, Never>?
    private var bootstrapped = false

    init() {
        self.selectedAccountId = UserDefaults.standard.string(forKey: Self.selectedAccountDefaultsKey) ?? ""
    }

    var currentAccount: AccountPublicView? {
        if let found = accounts.first(where: { $0.id == selectedAccountId }) {
            return found
        }
        return accounts.first
    }

    var hasAnyRunningAccount: Bool {
        accounts.contains { $0.state.running }
    }

    // MARK: - Lifecycle

    func bootstrap() async {
        guard !bootstrapped else { return }
        bootstrapped = true
        isLoading = true
        defer { isLoading = false }

        do {
            let port = try await Task.detached(priority: .userInitiated) {
                try HubanCoreBridge.shared.ensureStarted()
            }.value
            enginePort = port
            engineError = nil
            await loadStatus(silent: true)
            await drainNotifications()
        } catch {
            engineError = error.localizedDescription
            showToast(error.localizedDescription, isError: true)
        }
    }

    func refreshOnForeground() async {
        if enginePort <= 0 {
            if let port = try? await Task.detached(priority: .userInitiated, operation: {
                try HubanCoreBridge.shared.ensureStarted()
            }).value {
                enginePort = port
                engineError = nil
            }
        }
        guard enginePort > 0 else { return }
        await loadStatus(silent: true)
        await drainNotifications()
    }

    func loadStatus(silent: Bool = false) async {
        guard enginePort > 0 else { return }
        do {
            let status = try await api.fetchStatus()
            engineVersion = status.version.isEmpty ? HubanCoreBridge.appVersion : status.version
            accounts = status.accounts
            notifyConfig = status.notify
            reconcileSelection()
            updatePollingLoop()
        } catch {
            if !silent {
                showToast(error.localizedDescription, isError: true)
            }
        }
    }

    private func reconcileSelection() {
        if accounts.isEmpty {
            selectedAccountId = ""
            return
        }
        if !accounts.contains(where: { $0.id == selectedAccountId }), let first = accounts.first {
            selectedAccountId = first.id
        }
    }

    private func updatePollingLoop() {
        if hasAnyRunningAccount {
            guard pollTask == nil else { return }
            pollTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    guard let self, !Task.isCancelled else { break }
                    await self.loadStatus(silent: true)
                    await self.drainNotifications()
                    if !self.hasAnyRunningAccount {
                        break
                    }
                }
                self?.pollTask = nil
            }
        } else {
            pollTask?.cancel()
            pollTask = nil
        }
    }

    func selectAccount(_ id: String) {
        guard selectedAccountId != id else { return }
        selectedAccountId = id
        Haptics.light()
    }

    func showToast(_ message: String, isError: Bool = false) {
        let banner = ToastBanner(message: message, isError: isError)
        withAnimation(.spring(response: 0.32, dampingFraction: 0.85)) {
            toast = banner
        }
        if isError {
            Haptics.error()
        }
        toastDismissTask?.cancel()
        toastDismissTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.25)) {
                if self?.toast?.id == banner.id {
                    self?.toast = nil
                }
            }
        }
    }

    // MARK: - Account & Task Operations

    func runAccount(id: String) async {
        do {
            let res = try await api.runAccount(accountId: id)
            Haptics.medium()
            showToast(res.message ?? "任务已启动")
            await loadStatus(silent: true)
            // Kick off immediate short poll in case Go sets running slightly asynchronously
            Task {
                try? await Task.sleep(nanoseconds: 500_000_000)
                await self.loadStatus(silent: true)
                await self.drainNotifications()
            }
        } catch {
            showToast(error.localizedDescription, isError: true)
        }
    }

    func refreshAccount(id: String) async {
        isSyncing = true
        defer { isSyncing = false }
        do {
            let res = try await api.refreshAccount(accountId: id)
            if let acc = res.account {
                upsertAccountLocally(acc)
            } else {
                await loadStatus(silent: true)
            }
            Haptics.success()
            showToast("粉丝牌与背包虎粮已同步")
        } catch {
            showToast(error.localizedDescription, isError: true)
        }
    }

    func updateConfig(accountId: String, label: String? = nil, config: AccountConfig, showFeedback: Bool = false) async {
        // Optimistic update
        if let idx = accounts.firstIndex(where: { $0.id == accountId }) {
            accounts[idx].config = config
            if let label, !label.isEmpty {
                accounts[idx].label = label
            }
        }
        do {
            let res = try await api.updateConfig(accountId: accountId, label: label, config: config)
            if let acc = res.account {
                upsertAccountLocally(acc)
            }
            if showFeedback {
                Haptics.success()
                showToast("配置已保存")
            }
        } catch {
            showToast(error.localizedDescription, isError: true)
            await loadStatus(silent: true)
        }
    }

    func deleteAccount(id: String) async {
        do {
            _ = try await api.deleteAccount(accountId: id)
            accounts.removeAll { $0.id == id }
            reconcileSelection()
            Haptics.success()
            showToast("已移除账号")
        } catch {
            showToast(error.localizedDescription, isError: true)
        }
    }

    func manualSend(accountId: String, pid: Int64, isSuper: Bool, count: Int) async throws -> String {
        let res = try await api.manualSend(accountId: accountId, pid: pid, isSuper: isSuper, count: count)
        if let acc = res.account {
            upsertAccountLocally(acc)
        } else {
            await loadStatus(silent: true)
        }
        let msg = res.message ?? "送礼成功"
        Haptics.success()
        showToast(msg)
        return msg
    }

    // MARK: - Auth Flows

    func sendSMS(mobile: String) async throws -> SendSMSResponse {
        try await api.sendSMS(mobile: mobile)
    }

    func loginSMS(mobile: String, code: String, deviceId: String, label: String = "") async throws -> AccountActionResponse {
        let res = try await api.loginSMS(mobile: mobile, code: code, deviceId: deviceId, label: label)
        handleAuthAccountSuccess(res.account, message: res.message)
        return res
    }

    func startQR() async throws -> QRStartResponse {
        try await api.startQR()
    }

    func pollQR(sessionId: String) async throws -> QRPollResponse {
        let res = try await api.pollQR(sessionId: sessionId)
        if res.status == "confirmed" {
            handleAuthAccountSuccess(res.account, message: res.message)
        }
        return res
    }

    func startThird(provider: String) async throws -> QRStartResponse {
        try await api.startThird(provider: provider)
    }

    func pollThird(sessionId: String) async throws -> QRPollResponse {
        let res = try await api.pollThird(sessionId: sessionId)
        if res.status == "confirmed" {
            handleAuthAccountSuccess(res.account, message: res.message)
        }
        return res
    }

    func sendThirdAuthCode(sessionId: String, strategy: Int) async throws -> ThirdAuthCodeResponse {
        try await api.sendThirdAuthCode(sessionId: sessionId, strategy: strategy)
    }

    func verifyThirdAuth(
        sessionId: String,
        strategy: Int = 0,
        code: String = "",
        authId: String = "",
        safeAuth: Bool = false
    ) async throws -> QRPollResponse {
        let res = try await api.verifyThirdAuth(
            sessionId: sessionId,
            strategy: strategy,
            code: code,
            authId: authId,
            safeAuth: safeAuth
        )
        if res.status == "confirmed" {
            handleAuthAccountSuccess(res.account, message: res.message)
        }
        return res
    }

    func addCookie(cookie: String, label: String = "") async throws -> AccountActionResponse {
        let res = try await api.addCookie(cookie: cookie, label: label)
        handleAuthAccountSuccess(res.account, message: res.message)
        return res
    }

    private func handleAuthAccountSuccess(_ account: AccountPublicView?, message: String?) {
        if let account {
            upsertAccountLocally(account)
            selectedAccountId = account.id
        }
        Haptics.success()
        showToast(message ?? "账号已添加，正在同步数据...")
        // Initial badge & bag sync runs asynchronously in Go; refresh a couple of times.
        Task {
            await self.loadStatus(silent: true)
            try? await Task.sleep(nanoseconds: 2_800_000_000)
            await self.loadStatus(silent: true)
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            await self.loadStatus(silent: true)
        }
    }

    private func upsertAccountLocally(_ account: AccountPublicView) {
        if let idx = accounts.firstIndex(where: { $0.id == account.id }) {
            accounts[idx] = account
        } else {
            accounts.append(account)
        }
        reconcileSelection()
        updatePollingLoop()
    }

    // MARK: - Notifications

    func saveNotify(_ config: NotifyConfig) async {
        do {
            let res = try await api.saveNotify(config)
            notifyConfig = res.notify ?? config
            Haptics.success()
            showToast("推送通道配置已保存")
        } catch {
            showToast(error.localizedDescription, isError: true)
        }
    }

    func testNotify() async {
        do {
            let res = try await api.testNotify()
            Haptics.success()
            showToast(res.message ?? "测试推送已发送")
        } catch {
            showToast(error.localizedDescription, isError: true)
        }
    }

    func drainNotifications() async {
        guard enginePort > 0 else { return }
        guard let res = try? await api.drainNotifications(), !res.notifications.isEmpty else { return }
        for item in res.notifications {
            LocalNotificationManager.shared.post(
                title: item.title,
                body: item.body,
                identifier: "huban-drain-\(item.id)"
            )
        }
    }
}
