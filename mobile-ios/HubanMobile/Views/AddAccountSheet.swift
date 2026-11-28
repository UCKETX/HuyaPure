import SwiftUI
import UIKit

enum AddAccountMode: String, CaseIterable, Identifiable {
    case sms = "手机短信"
    case wechat = "微信扫码"
    case qq = "QQ扫码"
    case huyaQR = "虎牙扫码"
    case cookie = "Cookie"

    var id: String { rawValue }

    var isQR: Bool {
        switch self {
        case .wechat, .qq, .huyaQR: return true
        default: return false
        }
    }
}

struct AddAccountSheet: View {
    @EnvironmentObject private var store: HubanStore
    @Environment(\.dismiss) private var dismiss

    @State private var mode: AddAccountMode = .sms

    // SMS state
    @State private var phoneNumber = ""
    @State private var smsCode = ""
    @State private var smsDeviceId = ""
    @State private var smsCooldown = 0
    @State private var smsSending = false
    @State private var smsLoggingIn = false
    @State private var smsTimer: Task<Void, Never>?

    // QR state (WeChat / QQ / Huya)
    @State private var qrSessionId = ""
    @State private var qrImage: UIImage?
    @State private var qrStatusText = "正在获取登录二维码..."
    @State private var qrIsExpired = false
    @State private var qrLoading = false
    @State private var qrPollTask: Task<Void, Never>?
    @State private var shareFileURL: URL?
    @State private var showShareSheet = false

    // Second-factor auth (SafeAuth WKWebView or code strategy fallback)
    @State private var activeChallenge: ThirdAuthChallenge?
    @State private var showSafeAuthSheet = false
    @State private var selectedAuthStrategy = 8
    @State private var authCodeInput = ""
    @State private var authImageData: String?
    @State private var authStatusMessage = ""
    @State private var authCooldown = 0
    @State private var authBusy = false
    @State private var authTimer: Task<Void, Never>?

    // Cookie state
    @State private var cookieText = ""
    @State private var cookieLabel = ""
    @State private var cookieSubmitting = false

    // Inline error
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    modeSelector

                    if let errorMessage, !errorMessage.isEmpty {
                        HStack(spacing: 8) {
                            Image(systemName: "exclamationmark.triangle.fill")
                                .foregroundColor(HubanTheme.danger)
                            Text(errorMessage)
                                .font(.footnote)
                                .foregroundColor(HubanTheme.danger)
                            Spacer()
                        }
                        .padding(12)
                        .background(HubanTheme.danger.opacity(0.1))
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }

                    if let challenge = activeChallenge, !showSafeAuthSheet {
                        secondAuthCodeCard(challenge)
                    } else {
                        switch mode {
                        case .sms:
                            smsLoginCard
                        case .wechat, .qq, .huyaQR:
                            qrLoginCard
                        case .cookie:
                            cookieLoginCard
                        }
                    }
                }
                .padding(16)
            }
            .background(HubanTheme.canvas.ignoresSafeArea())
            .navigationTitle("添加虎牙账号")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") {
                        cleanupTasks()
                        dismiss()
                    }
                }
            }
            .onChange(of: mode) { newMode in
                errorMessage = nil
                activeChallenge = nil
                stopQRPolling()
                if newMode.isQR {
                    Task { await startQRFlow(for: newMode) }
                }
            }
            .onDisappear {
                cleanupTasks()
            }
            .sheet(isPresented: $showSafeAuthSheet) {
                if let ch = activeChallenge, let url = ch.safeAuthUrl, !url.isEmpty {
                    SafeAuthSheetView(
                        urlString: url,
                        title: ch.safeAuthTitle ?? "虎牙安全验证",
                        promptMessage: ch.message ?? "请完成虎牙官方安全验证，通过后将自动登录",
                        onAuthSuccess: { authId in
                            showSafeAuthSheet = false
                            Task { await completeSafeAuth(sessionId: ch.sessionId, authId: authId) }
                        },
                        onCancel: {
                            showSafeAuthSheet = false
                            activeChallenge = nil
                            qrStatusText = "已取消安全验证，可刷新二维码重试"
                        }
                    )
                }
            }
            .sheet(isPresented: $showShareSheet) {
                if let url = shareFileURL {
                    ActivityShareSheet(items: [url])
                }
            }
        }
    }

    // MARK: - Mode Picker

    private var modeSelector: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(AddAccountMode.allCases) { item in
                    let active = mode == item
                    Button {
                        withAnimation(.easeInOut(duration: 0.16)) {
                            mode = item
                        }
                        Haptics.light()
                    } label: {
                        HStack(spacing: 4) {
                            Text(item.rawValue)
                                .font(.subheadline.weight(active ? .bold : .medium))
                            if item == .sms {
                                Text("推荐")
                                    .font(.system(size: 9.5, weight: .bold))
                                    .padding(.horizontal, 5)
                                    .padding(.vertical, 1.5)
                                    .background(active ? Color.white.opacity(0.24) : HubanTheme.success.opacity(0.16))
                                    .foregroundColor(active ? .white : HubanTheme.success)
                                    .clipShape(Capsule())
                            }
                        }
                        .foregroundColor(active ? .white : .primary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(active ? AnyShapeStyle(HubanTheme.accentGradient) : AnyShapeStyle(HubanTheme.card))
                        .overlay(Capsule().stroke(active ? Color.clear : HubanTheme.border, lineWidth: 1))
                        .clipShape(Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - Mode 1: SMS Login (Recommended)

    private var smsLoginCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.shield.fill")
                    .foregroundColor(HubanTheme.success)
                Text("手机短信登录支持长期自动续期，无需频繁重新登录。")
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }

            VStack(spacing: 10) {
                TextField("请输入 11 位手机号", text: $phoneNumber)
                    .keyboardType(.phonePad)
                    .textContentType(.telephoneNumber)
                    .padding(12)
                    .background(HubanTheme.cardSecondary)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                HStack(spacing: 10) {
                    TextField("短信验证码", text: $smsCode)
                        .keyboardType(.numberPad)
                        .textContentType(.oneTimeCode)
                        .padding(12)
                        .background(HubanTheme.cardSecondary)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                    Button {
                        Task { await sendSMS() }
                    } label: {
                        Group {
                            if smsSending {
                                ProgressView().controlSize(.small)
                            } else {
                                Text(smsCooldown > 0 ? "\(smsCooldown)s" : "发送验证码")
                                    .font(.subheadline.bold())
                            }
                        }
                        .frame(minWidth: 96)
                        .padding(.vertical, 12)
                        .background(smsCooldown > 0 || phoneNumber.trimmingCharacters(in: .whitespaces).count < 6 ? HubanTheme.cardSecondary : HubanTheme.accent.opacity(0.15))
                        .foregroundColor(smsCooldown > 0 ? .secondary : HubanTheme.accent)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .disabled(smsSending || smsCooldown > 0 || phoneNumber.trimmingCharacters(in: .whitespaces).count < 6)
                }
            }

            Button {
                Task { await submitSMSLogin() }
            } label: {
                HStack {
                    if smsLoggingIn { ProgressView().tint(.white) }
                    Text(smsLoggingIn ? "登录中..." : "登录并保存账号")
                        .font(.headline)
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(canSubmitSMS ? HubanTheme.accentGradient : LinearGradient(colors: [Color.gray.opacity(0.45), Color.gray.opacity(0.45)], startPoint: .leading, endPoint: .trailing))
                .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
            }
            .disabled(!canSubmitSMS || smsLoggingIn)
        }
        .warmCard()
    }

    private var canSubmitSMS: Bool {
        !phoneNumber.trimmingCharacters(in: .whitespaces).isEmpty
            && !smsCode.trimmingCharacters(in: .whitespaces).isEmpty
            && !smsDeviceId.isEmpty
    }

    // MARK: - Modes 2/3/4: QR Login (WeChat / QQ / Huya)

    private var qrLoginCard: some View {
        VStack(spacing: 14) {
            Text(qrPromptTitle)
                .font(.subheadline.weight(.medium))
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)

            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color.white)
                    .frame(width: 224, height: 224)
                    .shadow(color: Color.black.opacity(0.06), radius: 10, x: 0, y: 4)

                if qrLoading {
                    ProgressView()
                        .tint(HubanTheme.accent)
                } else if let img = qrImage {
                    Image(uiImage: img)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .frame(width: 196, height: 196)
                        .opacity(qrIsExpired ? 0.25 : 1.0)
                } else {
                    Image(systemName: "qrcode")
                        .font(.system(size: 56))
                        .foregroundColor(.gray.opacity(0.4))
                }

                if qrIsExpired {
                    Button {
                        Task { await startQRFlow(for: mode) }
                    } label: {
                        Label("点击刷新二维码", systemImage: "arrow.clockwise")
                            .font(.subheadline.bold())
                            .foregroundColor(.white)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 10)
                            .background(HubanTheme.accentGradient)
                            .clipShape(Capsule())
                    }
                }
            }

            Text(qrStatusText)
                .font(.footnote.weight(.medium))
                .foregroundColor(qrIsExpired ? HubanTheme.warning : .secondary)
                .multilineTextAlignment(.center)

            if mode == .huyaQR || mode == .qq {
                Button {
                    saveQRAndLaunchTargetApp(for: mode)
                } label: {
                    Label(
                        mode == .huyaQR ? "保存二维码并打开虎牙" : "保存二维码并打开 QQ",
                        systemImage: "qrcode.viewfinder"
                    )
                    .font(.subheadline.bold())
                    .foregroundColor(.white)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(HubanTheme.accentGradient)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .disabled(qrImage == nil)
            }

            HStack(spacing: 10) {
                Button {
                    shareCurrentQR()
                } label: {
                    Label(mode == .wechat ? "分享到其他设备扫码" : "分享二维码", systemImage: "square.and.arrow.up")
                        .font(.subheadline.bold())
                        .foregroundColor(HubanTheme.accent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                        .background(HubanTheme.accent.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .disabled(qrImage == nil)

                Button {
                    Task { await startQRFlow(for: mode) }
                } label: {
                    Label("刷新", systemImage: "arrow.clockwise")
                        .font(.subheadline.bold())
                        .foregroundColor(.primary)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 11)
                        .background(HubanTheme.cardSecondary)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .disabled(qrLoading)
            }

            Text(singlePhoneHintText)
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
        .warmCard()
    }

    private var singlePhoneHintText: String {
        switch mode {
        case .wechat:
            return "微信限制该二维码仅支持摄像头扫一扫（不支持相册识别），请用另一台设备扫码，或改用「手机短信登录」。"
        case .huyaQR:
            return "单台 iPhone 提示：点上方按钮会自动存图并打开虎牙 App，在扫一扫中选择相册即可。"
        case .qq:
            return "单台 iPhone 提示：点上方按钮会自动存图并打开手机 QQ，在扫一扫中选择相册即可。"
        default:
            return ""
        }
    }

    private var qrPromptTitle: String {
        switch mode {
        case .wechat: return "请使用微信扫一扫下方二维码登录虎牙"
        case .qq: return "请使用手机 QQ 扫一扫下方二维码登录虎牙"
        case .huyaQR: return "请使用虎牙直播 App 扫一扫下方二维码登录"
        default: return ""
        }
    }

    // MARK: - Second-Factor Code Fallback Card

    private func secondAuthCodeCard(_ challenge: ThirdAuthChallenge) -> some View {
        let strategies = challenge.strategies ?? []
        let currentStrat = strategies.first(where: { $0.strategy == selectedAuthStrategy }) ?? strategies.first

        return VStack(alignment: .leading, spacing: 14) {
            HStack {
                Label("虎牙安全二次验证", systemImage: "lock.shield.fill")
                    .font(.headline)
                    .foregroundColor(HubanTheme.accent)
                Spacer()
                Button("返回扫码") {
                    activeChallenge = nil
                    Task { await startQRFlow(for: mode) }
                }
                .font(.caption.bold())
            }

            if strategies.count > 1 {
                Picker("验证方式", selection: $selectedAuthStrategy) {
                    ForEach(strategies) { s in
                        Text(s.displayTitle).tag(s.strategy)
                    }
                }
                .pickerStyle(.segmented)
            }

            Text(currentStrat?.promptContent ?? challenge.message ?? "请完成虎牙安全验证以继续登录")
                .font(.footnote)
                .foregroundColor(.secondary)

            if selectedAuthStrategy == 4 {
                if let img = HubanFormat.decodeDataURLImage(authImageData ?? currentStrat?.imageData) {
                    HStack {
                        Spacer()
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFit()
                            .frame(height: 54)
                            .clipShape(RoundedRectangle(cornerRadius: 8))
                            .onTapGesture {
                                Task { await sendSecondAuthCode(sessionId: challenge.sessionId) }
                            }
                        Spacer()
                    }
                }
            }

            HStack(spacing: 10) {
                TextField(selectedAuthStrategy == 8 ? "输入短信验证码" : (selectedAuthStrategy == 4 ? "输入图形验证码" : "输入动态口令"), text: $authCodeInput)
                    .padding(12)
                    .background(HubanTheme.cardSecondary)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                if selectedAuthStrategy == 8 || selectedAuthStrategy == 4 {
                    Button {
                        Task { await sendSecondAuthCode(sessionId: challenge.sessionId) }
                    } label: {
                        Text(selectedAuthStrategy == 8 ? (authCooldown > 0 ? "\(authCooldown)s" : "发送短信") : "换一张")
                            .font(.subheadline.bold())
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                            .background(HubanTheme.accent.opacity(0.14))
                            .foregroundColor(HubanTheme.accent)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .disabled(authBusy || (selectedAuthStrategy == 8 && authCooldown > 0))
                }
            }

            if !authStatusMessage.isEmpty {
                Text(authStatusMessage)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Button {
                Task { await submitSecondAuthCode(sessionId: challenge.sessionId) }
            } label: {
                HStack {
                    if authBusy { ProgressView().tint(.white) }
                    Text(authBusy ? "验证中..." : "提交验证")
                        .font(.headline)
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(HubanTheme.accentGradient)
                .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
            }
            .disabled(authBusy || authCodeInput.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .warmCard()
    }

    // MARK: - Mode 5: Cookie Import

    private var cookieLoginCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("粘贴包含 yyuid / udb_n / udb_biztoken 等字段的虎牙网页 Cookie 字符串。")
                .font(.footnote)
                .foregroundColor(.secondary)

            TextField("账号备注（可选）", text: $cookieLabel)
                .padding(12)
                .background(HubanTheme.cardSecondary)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

            TextEditor(text: $cookieText)
                .font(.footnote.monospaced())
                .frame(minHeight: 130)
                .padding(8)
                .scrollContentBackground(.hidden)
                .background(HubanTheme.cardSecondary)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

            Button {
                Task { await submitCookie() }
            } label: {
                HStack {
                    if cookieSubmitting { ProgressView().tint(.white) }
                    Text(cookieSubmitting ? "导入中..." : "导入 Cookie")
                        .font(.headline)
                }
                .foregroundColor(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(cookieText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? LinearGradient(colors: [Color.gray.opacity(0.45), Color.gray.opacity(0.45)], startPoint: .leading, endPoint: .trailing) : HubanTheme.accentGradient)
                .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
            }
            .disabled(cookieSubmitting || cookieText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .warmCard()
    }

    // MARK: - Actions

    private func cleanupTasks() {
        stopQRPolling()
        smsTimer?.cancel()
        smsTimer = nil
        authTimer?.cancel()
        authTimer = nil
    }

    private func stopQRPolling() {
        qrPollTask?.cancel()
        qrPollTask = nil
    }

    private func sendSMS() async {
        errorMessage = nil
        smsSending = true
        defer { smsSending = false }
        do {
            let res = try await store.sendSMS(mobile: phoneNumber.trimmingCharacters(in: .whitespaces))
            smsDeviceId = res.deviceId ?? ""
            Haptics.success()
            store.showToast(res.message ?? "短信验证码已发送")
            startSMSCooldown()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func startSMSCooldown() {
        smsTimer?.cancel()
        smsCooldown = 60
        smsTimer = Task {
            while smsCooldown > 0 && !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if !Task.isCancelled {
                    smsCooldown = max(0, smsCooldown - 1)
                }
            }
        }
    }

    private func submitSMSLogin() async {
        errorMessage = nil
        smsLoggingIn = true
        defer { smsLoggingIn = false }
        do {
            _ = try await store.loginSMS(
                mobile: phoneNumber.trimmingCharacters(in: .whitespaces),
                code: smsCode.trimmingCharacters(in: .whitespaces),
                deviceId: smsDeviceId,
                label: ""
            )
            cleanupTasks()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func startQRFlow(for targetMode: AddAccountMode) async {
        stopQRPolling()
        errorMessage = nil
        qrLoading = true
        qrIsExpired = false
        qrImage = nil
        qrStatusText = "正在获取登录二维码..."

        do {
            let startRes: QRStartResponse
            switch targetMode {
            case .wechat:
                startRes = try await store.startThird(provider: "wx")
            case .qq:
                startRes = try await store.startThird(provider: "qq")
            case .huyaQR:
                startRes = try await store.startQR()
            default:
                qrLoading = false
                return
            }

            qrLoading = false
            guard let sid = startRes.sessionId, !sid.isEmpty else {
                qrStatusText = "获取二维码会话失败"
                qrIsExpired = true
                return
            }
            qrSessionId = sid
            qrImage = HubanFormat.decodeDataURLImage(startRes.imageData)
            qrStatusText = "等待扫码..."

            qrPollTask = Task {
                await pollQRLoop(mode: targetMode, sessionId: sid)
            }
        } catch {
            qrLoading = false
            qrIsExpired = true
            qrStatusText = "获取二维码失败"
            errorMessage = error.localizedDescription
        }
    }

    private func pollQRLoop(mode targetMode: AddAccountMode, sessionId: String) async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled else { return }

            do {
                let res: QRPollResponse
                if targetMode == .huyaQR {
                    res = try await store.pollQR(sessionId: sessionId)
                } else {
                    res = try await store.pollThird(sessionId: sessionId)
                }

                guard !Task.isCancelled else { return }

                switch res.status {
                case "waiting", "pending":
                    qrStatusText = res.message ?? "等待扫码..."
                case "scanned":
                    qrStatusText = res.message ?? "已扫码，请在手机上确认登录..."
                case "confirmed":
                    cleanupTasks()
                    dismiss()
                    return
                case "need_auth":
                    stopQRPolling()
                    if let auth = res.auth {
                        presentSecondAuth(auth)
                    } else {
                        errorMessage = res.message ?? "需要安全验证"
                    }
                    return
                case "expired", "cancelled", "failed":
                    qrIsExpired = true
                    qrStatusText = res.message ?? "二维码已失效，请刷新"
                    return
                default:
                    if let msg = res.message, !msg.isEmpty {
                        qrStatusText = msg
                    }
                }
            } catch {
                if Task.isCancelled { return }
                qrIsExpired = true
                qrStatusText = "轮询失败，请点击刷新"
                errorMessage = error.localizedDescription
                return
            }
        }
    }

    private func presentSecondAuth(_ auth: ThirdAuthChallenge) {
        activeChallenge = auth
        authCodeInput = ""
        authStatusMessage = ""
        if auth.mode == "safe_auth", let url = auth.safeAuthUrl, !url.isEmpty {
            showSafeAuthSheet = true
        } else {
            let preferred = auth.strategies?.first(where: { $0.strategy == 8 }) ?? auth.strategies?.first
            selectedAuthStrategy = preferred?.strategy ?? 8
            authImageData = preferred?.imageData
            showSafeAuthSheet = false
        }
    }

    private func completeSafeAuth(sessionId: String, authId: String) async {
        authBusy = true
        qrStatusText = "安全验证已通过，正在完成虎牙登录..."
        defer { authBusy = false }
        do {
            let res = try await store.verifyThirdAuth(sessionId: sessionId, authId: authId, safeAuth: true)
            handleAuthVerifyResult(res)
        } catch {
            errorMessage = error.localizedDescription
            activeChallenge = nil
        }
    }

    private func sendSecondAuthCode(sessionId: String) async {
        authBusy = true
        defer { authBusy = false }
        do {
            let res = try await store.sendThirdAuthCode(sessionId: sessionId, strategy: selectedAuthStrategy)
            authStatusMessage = res.message ?? "已发送"
            if let img = res.imageData, !img.isEmpty {
                authImageData = img
            }
            if selectedAuthStrategy == 8 {
                authTimer?.cancel()
                authCooldown = 60
                authTimer = Task {
                    while authCooldown > 0 && !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 1_000_000_000)
                        if !Task.isCancelled { authCooldown = max(0, authCooldown - 1) }
                    }
                }
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func submitSecondAuthCode(sessionId: String) async {
        authBusy = true
        errorMessage = nil
        defer { authBusy = false }
        do {
            let res = try await store.verifyThirdAuth(
                sessionId: sessionId,
                strategy: selectedAuthStrategy,
                code: authCodeInput.trimmingCharacters(in: .whitespaces),
                safeAuth: false
            )
            handleAuthVerifyResult(res)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func handleAuthVerifyResult(_ res: QRPollResponse) {
        switch res.status {
        case "confirmed":
            cleanupTasks()
            dismiss()
        case "re_scan":
            activeChallenge = nil
            store.showToast(res.message ?? "安全验证已通过，请重新扫码完成登录")
            Task { await startQRFlow(for: mode) }
        case "need_auth":
            if let nextAuth = res.auth {
                presentSecondAuth(nextAuth)
            } else {
                errorMessage = res.message ?? "请继续完成安全验证"
            }
        default:
            errorMessage = res.message ?? "安全验证未通过，请重试"
        }
    }

    private func saveQRAndLaunchTargetApp(for targetMode: AddAccountMode) {
        guard let img = qrImage else { return }
        UIImageWriteToSavedPhotosAlbum(img, nil, nil, nil)
        Haptics.success()

        let candidates: [String]
        let fallbackTip: String
        switch targetMode {
        case .wechat:
            candidates = ["weixin://scanqrcode", "weixin://"]
            fallbackTip = "二维码已存入相册，请打开微信扫一扫从相册选图"
        case .huyaQR:
            candidates = ["yykiwi://homepage", "yykiwi://"]
            fallbackTip = "二维码已存入相册，请打开虎牙 App 扫一扫从相册选图"
        case .qq:
            candidates = ["mqq://", "mqqapi://"]
            fallbackTip = "二维码已存入相册，请打开手机 QQ 扫一扫从相册选图"
        default:
            candidates = ["mqq://"]
            fallbackTip = "二维码已存入相册"
        }

        store.showToast("二维码已保存到相册")
        openFirstURL(candidates, index: 0, fallbackTip: fallbackTip)
    }

    private func openFirstURL(_ urls: [String], index: Int, fallbackTip: String) {
        guard index < urls.count, let url = URL(string: urls[index]) else {
            store.showToast(fallbackTip)
            return
        }
        UIApplication.shared.open(url, options: [:]) { ok in
            if !ok {
                openFirstURL(urls, index: index + 1, fallbackTip: fallbackTip)
            }
        }
    }

    private func shareCurrentQR() {
        guard let img = qrImage, let pngData = img.pngData() else { return }
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("huban-login-qr.png")
        do {
            try pngData.write(to: fileURL, options: .atomic)
            shareFileURL = fileURL
            showShareSheet = true
        } catch {
            errorMessage = "导出二维码失败：\(error.localizedDescription)"
        }
    }

    private func submitCookie() async {
        errorMessage = nil
        cookieSubmitting = true
        defer { cookieSubmitting = false }
        do {
            _ = try await store.addCookie(
                cookie: cookieText.trimmingCharacters(in: .whitespacesAndNewlines),
                label: cookieLabel.trimmingCharacters(in: .whitespacesAndNewlines)
            )
            cleanupTasks()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

private struct ActivityShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
