import Foundation

enum HubanAPIError: LocalizedError {
    case engineNotStarted
    case invalidURL
    case serverError(String)
    case decodingError(String)

    var errorDescription: String? {
        switch self {
        case .engineNotStarted:
            return "本机虎伴引擎尚未启动"
        case .invalidURL:
            return "无效的本地接口地址"
        case .serverError(let msg):
            return msg
        case .decodingError(let msg):
            return "数据解析失败：\(msg)"
        }
    }
}

/// Async HTTP client talking to the embedded Go server on `http://127.0.0.1:<port>`.
struct HubanAPI {
    private let session: URLSession

    init() {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 45
        cfg.timeoutIntervalForResource = 180
        cfg.waitsForConnectivity = false
        self.session = URLSession(configuration: cfg)
    }

    private func makeURL(path: String, queryItems: [URLQueryItem] = []) throws -> URL {
        guard let base = HubanCoreBridge.shared.baseURL else {
            throw HubanAPIError.engineNotStarted
        }
        guard var comps = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false) else {
            throw HubanAPIError.invalidURL
        }
        if !queryItems.isEmpty {
            comps.queryItems = queryItems
        }
        guard let url = comps.url else {
            throw HubanAPIError.invalidURL
        }
        return url
    }

    private func request<T: Decodable>(
        method: String,
        path: String,
        queryItems: [URLQueryItem] = [],
        body: Encodable? = nil
    ) async throws -> T {
        let url = try makeURL(path: path, queryItems: queryItems)
        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
            req.httpBody = try JSONEncoder().encode(AnyEncodable(body))
        }

        let (data, response) = try await session.data(for: req)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0

        if statusCode >= 400 {
            if let env = try? JSONDecoder().decode(APIErrorEnvelope.self, from: data),
               let err = env.error ?? env.message, !err.isEmpty {
                throw HubanAPIError.serverError(err)
            }
            throw HubanAPIError.serverError("请求失败 (HTTP \(statusCode))")
        }

        do {
            let decoded = try JSONDecoder().decode(T.self, from: data)
            return decoded
        } catch {
            if let env = try? JSONDecoder().decode(APIErrorEnvelope.self, from: data),
               env.ok == false, let err = env.error ?? env.message, !err.isEmpty {
                throw HubanAPIError.serverError(err)
            }
            throw HubanAPIError.decodingError(error.localizedDescription)
        }
    }

    // MARK: - Endpoints

    func fetchStatus() async throws -> StatusResponse {
        try await request(method: "GET", path: "/api/status")
    }

    func sendSMS(mobile: String) async throws -> SendSMSResponse {
        struct Body: Encodable { let mobile: String }
        return try await request(method: "POST", path: "/api/mobile/sms/send", body: Body(mobile: mobile))
    }

    func loginSMS(mobile: String, code: String, deviceId: String, label: String = "") async throws -> AccountActionResponse {
        struct Body: Encodable {
            let mobile: String
            let code: String
            let deviceId: String
            let label: String
        }
        return try await request(
            method: "POST",
            path: "/api/mobile/sms/login",
            body: Body(mobile: mobile, code: code, deviceId: deviceId, label: label)
        )
    }

    func startQR() async throws -> QRStartResponse {
        try await request(method: "POST", path: "/api/qr/start")
    }

    func pollQR(sessionId: String) async throws -> QRPollResponse {
        try await request(
            method: "GET",
            path: "/api/qr/poll",
            queryItems: [URLQueryItem(name: "sessionId", value: sessionId)]
        )
    }

    func startThird(provider: String) async throws -> QRStartResponse {
        struct Body: Encodable { let provider: String }
        return try await request(method: "POST", path: "/api/third/start", body: Body(provider: provider))
    }

    func pollThird(sessionId: String) async throws -> QRPollResponse {
        try await request(
            method: "GET",
            path: "/api/third/poll",
            queryItems: [URLQueryItem(name: "sessionId", value: sessionId)]
        )
    }

    func sendThirdAuthCode(sessionId: String, strategy: Int) async throws -> ThirdAuthCodeResponse {
        struct Body: Encodable {
            let sessionId: String
            let strategy: Int
        }
        return try await request(
            method: "POST",
            path: "/api/third/auth/code",
            body: Body(sessionId: sessionId, strategy: strategy)
        )
    }

    func verifyThirdAuth(
        sessionId: String,
        strategy: Int = 0,
        code: String = "",
        authId: String = "",
        safeAuth: Bool = false
    ) async throws -> QRPollResponse {
        struct Body: Encodable {
            let sessionId: String
            let strategy: Int
            let code: String
            let authId: String
            let safeAuth: Bool
        }
        return try await request(
            method: "POST",
            path: "/api/third/auth/verify",
            body: Body(sessionId: sessionId, strategy: strategy, code: code, authId: authId, safeAuth: safeAuth)
        )
    }

    func addCookie(cookie: String, label: String = "") async throws -> AccountActionResponse {
        struct Body: Encodable {
            let label: String
            let cookie: String
        }
        return try await request(method: "POST", path: "/api/accounts/cookie", body: Body(label: label, cookie: cookie))
    }

    func updateConfig(accountId: String, label: String? = nil, config: AccountConfig) async throws -> AccountActionResponse {
        struct Body: Encodable {
            let label: String?
            let config: AccountConfig
        }
        return try await request(
            method: "PUT",
            path: "/api/accounts/\(accountId)/config",
            body: Body(label: label, config: config)
        )
    }

    func deleteAccount(accountId: String) async throws -> SimpleOKResponse {
        try await request(method: "DELETE", path: "/api/accounts/\(accountId)")
    }

    func runAccount(accountId: String) async throws -> SimpleOKResponse {
        try await request(method: "POST", path: "/api/accounts/\(accountId)/run")
    }

    func refreshAccount(accountId: String) async throws -> AccountActionResponse {
        try await request(method: "POST", path: "/api/accounts/\(accountId)/refresh")
    }

    func manualSend(accountId: String, pid: Int64, isSuper: Bool, count: Int) async throws -> AccountActionResponse {
        struct Body: Encodable {
            let pid: Int64
            let `super`: Bool
            let count: Int
        }
        return try await request(
            method: "POST",
            path: "/api/accounts/\(accountId)/send",
            body: Body(pid: pid, super: isSuper, count: count)
        )
    }

    func saveNotify(_ config: NotifyConfig) async throws -> NotifySaveResponse {
        try await request(method: "PUT", path: "/api/notify", body: config)
    }

    func testNotify() async throws -> SimpleOKResponse {
        try await request(method: "POST", path: "/api/notify/test")
    }

    func drainNotifications() async throws -> DrainNotificationsResponse {
        try await request(method: "GET", path: "/api/notifications/drain")
    }
}

private struct AnyEncodable: Encodable {
    private let _encode: (Encoder) throws -> Void

    init(_ wrapped: Encodable) {
        self._encode = wrapped.encode
    }

    func encode(to encoder: Encoder) throws {
        try _encode(encoder)
    }
}
