import Foundation
import HubanCore

/// Thread-safe Swift bridge to the embedded Go `HubanCore.xcframework` runtime.
final class HubanCoreBridge: @unchecked Sendable {
    static let shared = HubanCoreBridge()

    static let appVersion: String = {
        let short = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.6.0"
        return "\(short)-ios"
    }()

    private let lock = NSLock()
    private var startedPort: Int = 0

    private init() {}

    var dataDirectoryURL: URL {
        let fm = FileManager.default
        let appSupport = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? fm.temporaryDirectory
        let dir = appSupport.appendingPathComponent("HubanData", isDirectory: true)
        if !fm.fileExists(atPath: dir.path) {
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        return dir
    }

    var currentPort: Int {
        lock.lock()
        defer { lock.unlock() }
        if startedPort > 0 {
            return startedPort
        }
        let p = Int(HubanPort())
        if p > 0 {
            startedPort = p
        }
        return p
    }

    var baseURL: URL? {
        let p = currentPort
        guard p > 0 else { return nil }
        return URL(string: "http://127.0.0.1:\(p)")
    }

    /// Starts the embedded Go engine if not already running and returns the loopback TCP port.
    @discardableResult
    func ensureStarted() throws -> Int {
        lock.lock()
        defer { lock.unlock() }

        let existing = Int(HubanPort())
        if existing > 0 {
            startedPort = existing
            return existing
        }

        let dirPath = dataDirectoryURL.path
        let version = Self.appVersion

        let port = withMutableCString(dirPath) { dirPtr in
            withMutableCString(version) { verPtr in
                Int(HubanStart(dirPtr, verPtr))
            }
        }

        if port <= 0 {
            let errMsg = readLastError() ?? "启动虎伴核心引擎失败"
            throw HubanBridgeError.startFailed(errMsg)
        }
        startedPort = port
        return port
    }

    func stop() {
        lock.lock()
        defer { lock.unlock() }
        HubanStop()
        startedPort = 0
    }

    /// Synchronously executes tasks for all accounts via the embedded Go engine.
    /// Ideal for AppIntents / Shortcuts and BGAppRefreshTask.
    func runAllSync(trigger: String = "schedule", onlyEnabled: Bool = false) -> RunAllResponse? {
        _ = try? ensureStarted()
        let rawPtr: UnsafeMutablePointer<CChar>? = withMutableCString(trigger) { triggerPtr in
            HubanRunAllSync(triggerPtr, onlyEnabled ? 1 : 0)
        }
        guard let ptr = rawPtr else { return nil }
        defer { HubanFreeString(ptr) }

        let jsonStr = String(cString: ptr)
        guard let data = jsonStr.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(RunAllResponse.self, from: data)
    }

    private func readLastError() -> String? {
        guard let ptr = HubanLastError() else { return nil }
        defer { HubanFreeString(ptr) }
        let msg = String(cString: ptr).trimmingCharacters(in: .whitespacesAndNewlines)
        return msg.isEmpty ? nil : msg
    }

    private func withMutableCString<R>(_ string: String, _ body: (UnsafeMutablePointer<CChar>) -> R) -> R {
        var bytes = Array(string.utf8CString)
        return bytes.withUnsafeMutableBufferPointer { buf in
            body(buf.baseAddress!)
        }
    }
}

enum HubanBridgeError: LocalizedError {
    case startFailed(String)

    var errorDescription: String? {
        switch self {
        case .startFailed(let msg):
            return msg
        }
    }
}
