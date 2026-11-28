import Foundation
import SwiftUI

struct AppReleaseInfo: Equatable {
    let tagName: String
    let versionName: String
    let title: String
    let releaseNotes: String
    let htmlUrl: String
    let ipaName: String
    let ipaDownloadUrl: String
    let ipaSizeBytes: Int64
    let hasUpdate: Bool

    var formattedSize: String {
        guard ipaSizeBytes > 0 else { return "" }
        if ipaSizeBytes >= 1024 * 1024 {
            return String(format: "%.2f MB", Double(ipaSizeBytes) / (1024.0 * 1024.0))
        }
        return String(format: "%.0f KB", Double(ipaSizeBytes) / 1024.0)
    }

    var preferredDownloadURL: URL? {
        if !ipaDownloadUrl.isEmpty, let url = URL(string: ipaDownloadUrl) {
            return url
        }
        return URL(string: htmlUrl.isEmpty ? AppUpdater.releasesPageURL : htmlUrl)
    }

    var releasePageURL: URL? {
        URL(string: htmlUrl.isEmpty ? AppUpdater.releasesPageURL : htmlUrl)
    }
}

@MainActor
final class AppUpdater: ObservableObject {
    static let shared = AppUpdater()

    static let githubRepo = "UCKETX/HuyaPure"
    static let releasesPageURL = "https://github.com/\(githubRepo)/releases"
    private static let latestAPIURL = "https://api.github.com/repos/\(githubRepo)/releases/latest"
    private static let latestWebURL = "https://github.com/\(githubRepo)/releases/latest"
    private static let lastCheckKey = "huban_last_update_check_ts"
    private static let autoCheckInterval: TimeInterval = 6 * 3600

    @Published var latestRelease: AppReleaseInfo?
    @Published var isChecking = false
    @Published var lastError: String?
    @Published var bannerDismissedTag: String?

    private init() {}

    var currentVersionName: String {
        (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? "1.7.0"
    }

    var hasUpdate: Bool {
        latestRelease?.hasUpdate == true
    }

    var shouldShowBanner: Bool {
        guard let info = latestRelease, info.hasUpdate else { return false }
        return bannerDismissedTag != info.tagName
    }

    func dismissBanner() {
        if let tag = latestRelease?.tagName {
            bannerDismissedTag = tag
        }
    }

    func checkIfNeededOnLaunch() async {
        let lastTs = UserDefaults.standard.double(forKey: Self.lastCheckKey)
        if Date().timeIntervalSince1970 - lastTs < Self.autoCheckInterval {
            return
        }
        _ = await checkForUpdate(manual: false)
    }

    @discardableResult
    func checkForUpdate(manual: Bool) async -> AppReleaseInfo? {
        if isChecking { return latestRelease }
        isChecking = true
        lastError = nil
        defer { isChecking = false }

        let currentVer = currentVersionName
        do {
            let info = try await fetchLatestRelease(currentVersion: currentVer)
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.lastCheckKey)
            latestRelease = info
            if info.hasUpdate && bannerDismissedTag != info.tagName {
                bannerDismissedTag = nil
            }
            return info
        } catch {
            if manual {
                lastError = error.localizedDescription
            }
            return nil
        }
    }

    // MARK: - Networking

    private nonisolated func fetchLatestRelease(currentVersion: String) async throws -> AppReleaseInfo {
        do {
            return try await fetchViaGitHubAPI(currentVersion: currentVersion)
        } catch let apiError {
            if let fallback = try? await fetchViaWebRedirectFallback(currentVersion: currentVersion) {
                return fallback
            }
            throw apiError
        }
    }

    private nonisolated func fetchViaGitHubAPI(currentVersion: String) async throws -> AppReleaseInfo {
        guard let url = URL(string: Self.latestAPIURL) else {
            throw URLError(.badURL)
        }
        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("HuyaPure-Mobile-iOS", forHTTPHeaderField: "User-Agent")

        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 404 {
            throw NSError(domain: "AppUpdater", code: 404, userInfo: [NSLocalizedDescriptionKey: "仓库暂无已发布的 Release"])
        }
        if code == 403 {
            throw NSError(domain: "AppUpdater", code: 403, userInfo: [NSLocalizedDescriptionKey: "GitHub API 限流（403），请稍后重试"])
        }
        guard code == 200 else {
            throw NSError(domain: "AppUpdater", code: code, userInfo: [NSLocalizedDescriptionKey: "检查更新失败（HTTP \(code)）"])
        }

        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw NSError(domain: "AppUpdater", code: -1, userInfo: [NSLocalizedDescriptionKey: "返回数据格式无效"])
        }

        let tagName = (json["tag_name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !tagName.isEmpty else {
            throw NSError(domain: "AppUpdater", code: -1, userInfo: [NSLocalizedDescriptionKey: "未解析到最新版本号"])
        }
        let versionName = Self.normalizeVersion(tagName)
        let rawTitle = (json["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let rawNotes = (json["body"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let rawHTML = (json["html_url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        var ipaName = ""
        var ipaUrl = ""
        var ipaSize: Int64 = 0
        if let assets = json["assets"] as? [[String: Any]] {
            for asset in assets {
                let name = asset["name"] as? String ?? ""
                let lower = name.lowercased()
                let dl = asset["browser_download_url"] as? String ?? ""
                if lower.hasSuffix(".ipa") && !dl.isEmpty {
                    ipaName = name
                    ipaUrl = dl
                    ipaSize = (asset["size"] as? NSNumber)?.int64Value ?? 0
                    if lower.contains("ios") {
                        break
                    }
                }
            }
        }

        let hasUpdate = Self.compareVersions(versionName, currentVersion) > 0
        return AppReleaseInfo(
            tagName: tagName,
            versionName: versionName,
            title: rawTitle.isEmpty ? tagName : rawTitle,
            releaseNotes: rawNotes.isEmpty ? "包含功能优化与问题修复。" : rawNotes,
            htmlUrl: rawHTML.isEmpty ? Self.releasesPageURL : rawHTML,
            ipaName: ipaName,
            ipaDownloadUrl: ipaUrl,
            ipaSizeBytes: ipaSize,
            hasUpdate: hasUpdate
        )
    }

    private nonisolated func fetchViaWebRedirectFallback(currentVersion: String) async throws -> AppReleaseInfo {
        guard let url = URL(string: Self.latestWebURL) else {
            throw URLError(.badURL)
        }
        let delegate = NoRedirectDelegate()
        let session = URLSession(configuration: .ephemeral, delegate: delegate, delegateQueue: nil)
        defer { session.invalidateAndCancel() }

        var req = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        req.setValue("HuyaPure-Mobile-iOS", forHTTPHeaderField: "User-Agent")

        let (_, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse,
              (301...308).contains(http.statusCode),
              let loc = http.value(forHTTPHeaderField: "Location"),
              loc.contains("/releases/tag/") else {
            throw NSError(domain: "AppUpdater", code: -1, userInfo: [NSLocalizedDescriptionKey: "无法获取最新版本重定向"])
        }

        var tag = String(loc.split(separator: "/").last ?? "")
        if let qIdx = tag.firstIndex(of: "?") {
            tag = String(tag[..<qIdx])
        }
        tag = tag.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !tag.isEmpty else {
            throw NSError(domain: "AppUpdater", code: -1, userInfo: [NSLocalizedDescriptionKey: "无法解析最新版本标签"])
        }

        let ver = Self.normalizeVersion(tag)
        let ipaName = "HuyaPure-\(tag)-ios-unsigned.ipa"
        let ipaUrl = "https://github.com/\(Self.githubRepo)/releases/download/\(tag)/\(ipaName)"
        return AppReleaseInfo(
            tagName: tag,
            versionName: ver,
            title: "虎伴 \(tag)",
            releaseNotes: "请查看 GitHub Release 页面了解完整更新说明。",
            htmlUrl: loc,
            ipaName: ipaName,
            ipaDownloadUrl: ipaUrl,
            ipaSizeBytes: 0,
            hasUpdate: Self.compareVersions(ver, currentVersion) > 0
        )
    }

    // MARK: - Semver Comparison

    nonisolated static func normalizeVersion(_ raw: String) -> String {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("v") || s.hasPrefix("V") {
            s.removeFirst()
            s = s.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let dash = s.firstIndex(of: "-") {
            s = String(s[..<dash])
        }
        return s.isEmpty ? "0.0.0" : s
    }

    nonisolated static func compareVersions(_ v1: String, _ v2: String) -> Int {
        let p1 = normalizeVersion(v1).split(separator: ".").map { parseLeadingInt(String($0)) }
        let p2 = normalizeVersion(v2).split(separator: ".").map { parseLeadingInt(String($0)) }
        let len = max(p1.count, p2.count)
        for i in 0..<len {
            let n1 = i < p1.count ? p1[i] : 0
            let n2 = i < p2.count ? p2[i] : 0
            if n1 != n2 {
                return n1 > n2 ? 1 : -1
            }
        }
        return 0
    }

    private nonisolated static func parseLeadingInt(_ part: String) -> Int {
        let digits = part.prefix { $0.isNumber }
        return Int(digits) ?? 0
    }
}

private final class NoRedirectDelegate: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}
