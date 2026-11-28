import SwiftUI
import UIKit

// MARK: - Module-App Matching Design System & Motion Helpers

enum HubanTheme {
    static let accent = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 1.0, green: 0.541, blue: 0.122, alpha: 1.0) // #FF8A1F
            : UIColor(red: 1.0, green: 0.510, blue: 0.094, alpha: 1.0) // #FF8218
    })
    static let accentDeep = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.961, green: 0.345, blue: 0.0, alpha: 1.0) // #F55800
            : UIColor(red: 1.0, green: 0.333, blue: 0.0, alpha: 1.0)   // #FF5500
    })
    static let brandText = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 1.0, green: 0.631, blue: 0.259, alpha: 1.0) // #FFA142
            : UIColor(red: 0.910, green: 0.365, blue: 0.0, alpha: 1.0) // #E85D00
    })
    static let brandTint = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.180, green: 0.129, blue: 0.086, alpha: 1.0) // #2E2116
            : UIColor(red: 1.0, green: 0.945, blue: 0.902, alpha: 1.0)   // #FFF1E6
    })

    static let canvas = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.055, green: 0.067, blue: 0.090, alpha: 1.0) // #0E1117
            : UIColor(red: 0.953, green: 0.961, blue: 0.976, alpha: 1.0) // #F3F5F9
    })

    static let card = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.090, green: 0.110, blue: 0.141, alpha: 1.0) // #171C24
            : UIColor(red: 1.0, green: 1.0, blue: 1.0, alpha: 1.0)       // #FFFFFF
    })

    static let cardSecondary = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.118, green: 0.145, blue: 0.188, alpha: 1.0) // #1E2530
            : UIColor(red: 0.965, green: 0.973, blue: 0.988, alpha: 1.0) // #F6F8FC
    })

    static let border = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.145, green: 0.173, blue: 0.220, alpha: 1.0) // #252C38
            : UIColor(red: 0.902, green: 0.918, blue: 0.949, alpha: 1.0) // #E6EAF2
    })

    static let success = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.204, green: 0.827, blue: 0.600, alpha: 1.0) // #34D399
            : UIColor(red: 0.055, green: 0.624, blue: 0.431, alpha: 1.0) // #0E9F6E
    })
    static let warning = Color(red: 0.96, green: 0.62, blue: 0.04)
    static let danger = Color(UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 0.973, green: 0.443, blue: 0.443, alpha: 1.0) // #F87171
            : UIColor(red: 0.851, green: 0.176, blue: 0.125, alpha: 1.0) // #D92D20
    })

    static let accentGradient = LinearGradient(
        colors: [
            Color(red: 1.0, green: 0.51, blue: 0.094),
            Color(red: 1.0, green: 0.333, blue: 0.0)
        ],
        startPoint: .leading,
        endPoint: .trailing
    )

    static let superFanGradient = LinearGradient(
        colors: [
            Color(red: 1.0, green: 0.51, blue: 0.094),
            Color(red: 0.96, green: 0.31, blue: 0.06)
        ],
        startPoint: .leading,
        endPoint: .trailing
    )
}

/// Snappy press-down + spring overshoot bounce matching the Android module app.
struct HubanBounceButtonStyle: ButtonStyle {
    var pressedScale: CGFloat = 0.96

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? pressedScale : 1.0)
            .animation(
                configuration.isPressed
                    ? .easeOut(duration: 0.09)
                    : .spring(response: 0.26, dampingFraction: 0.58),
                value: configuration.isPressed
            )
    }
}

/// Breathing alpha pulse used on active running task buttons.
struct PulseRunningModifier: ViewModifier {
    let active: Bool
    @State private var dimmed = false

    func body(content: Content) -> some View {
        content
            .opacity(active ? (dimmed ? 0.68 : 1.0) : 1.0)
            .onAppear { syncPulse() }
            .onChange(of: active) { _ in syncPulse() }
    }

    private func syncPulse() {
        if active {
            withAnimation(.easeInOut(duration: 0.72).repeatForever(autoreverses: true)) {
                dimmed = true
            }
        } else {
            withAnimation(.easeOut(duration: 0.15)) {
                dimmed = false
            }
        }
    }
}

extension View {
    func pulseWhenRunning(_ running: Bool) -> some View {
        modifier(PulseRunningModifier(active: running))
    }
}

struct WarmCardModifier: ViewModifier {
    var padding: CGFloat = 16
    var cornerRadius: CGFloat = 18

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(HubanTheme.card)
                    .overlay(
                        RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                            .stroke(HubanTheme.border, lineWidth: 1)
                    )
                    .shadow(color: Color.black.opacity(0.04), radius: 8, x: 0, y: 3)
            )
    }
}

extension View {
    func warmCard(padding: CGFloat = 16, cornerRadius: CGFloat = 18) -> some View {
        modifier(WarmCardModifier(padding: padding, cornerRadius: cornerRadius))
    }
}

// MARK: - Haptics

enum Haptics {
    static func light() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    static func medium() {
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
    }

    static func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }

    static func error() {
        UINotificationFeedbackGenerator().notificationOccurred(.error)
    }
}

// MARK: - Date & Number Formatting Helpers

enum HubanFormat {
    static var todayKey: String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "zh_CN")
        return f.string(from: Date())
    }

    static func formatTimeMs(_ ms: Int64) -> String {
        guard ms > 0 else { return "--" }
        let date = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        let cal = Calendar.current
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        if cal.isDateInToday(date) {
            f.dateFormat = "今天 HH:mm"
        } else if cal.isDateInTomorrow(date) {
            f.dateFormat = "明天 HH:mm"
        } else if cal.isDateInYesterday(date) {
            f.dateFormat = "昨天 HH:mm"
        } else {
            f.dateFormat = "MM-dd HH:mm"
        }
        return f.string(from: date)
    }

    static func formatShortDateTimeMs(_ ms: Int64) -> String {
        guard ms > 0 else { return "--" }
        let date = Date(timeIntervalSince1970: Double(ms) / 1000.0)
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "MM-dd HH:mm:ss"
        return f.string(from: date)
    }

    static func formatExpireSec(_ sec: Int64) -> String {
        guard sec > 0 else { return "" }
        let remain = Int(sec) - Int(Date().timeIntervalSince1970)
        if remain <= 0 { return "即将过期" }
        let hours = remain / 3600
        let mins = (remain % 3600) / 60
        if hours >= 24 {
            return "\(hours / 24)天\(hours % 24)小时后过期"
        }
        if hours > 0 {
            return "\(hours)小时\(mins)分后过期"
        }
        return "\(max(1, mins))分钟后过期"
    }

    static func decodeDataURLImage(_ dataURL: String?) -> UIImage? {
        guard let raw = dataURL, !raw.isEmpty else { return nil }
        let base64Str: String
        if let commaIdx = raw.firstIndex(of: ",") {
            base64Str = String(raw[raw.index(after: commaIdx)...])
        } else {
            base64Str = raw
        }
        guard let data = Data(base64Encoded: base64Str, options: .ignoreUnknownCharacters) else {
            return nil
        }
        return UIImage(data: data)
    }
}

// MARK: - In-Memory Remote Image & Guard Sprite Cache

final class HubanImageCache: @unchecked Sendable {
    static let shared = HubanImageCache()

    private let imageCache = NSCache<NSString, UIImage>()
    private let guardCropCache = NSCache<NSString, UIImage>()
    private let session: URLSession

    private init() {
        imageCache.countLimit = 300
        guardCropCache.countLimit = 64
        let cfg = URLSessionConfiguration.default
        cfg.requestCachePolicy = .returnCacheDataElseLoad
        cfg.urlCache = URLCache(memoryCapacity: 32 * 1024 * 1024, diskCapacity: 64 * 1024 * 1024)
        cfg.timeoutIntervalForRequest = 15
        self.session = URLSession(configuration: cfg)
    }

    func image(for urlString: String) async -> UIImage? {
        let key = urlString as NSString
        if let cached = imageCache.object(forKey: key) {
            return cached
        }
        guard let url = URL(string: urlString) else { return nil }
        var req = URLRequest(url: url)
        req.setValue("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)", forHTTPHeaderField: "User-Agent")
        guard let (data, resp) = try? await session.data(for: req),
              (resp as? HTTPURLResponse)?.statusCode ?? 200 < 400,
              let img = UIImage(data: data) else {
            return nil
        }
        imageCache.setObject(img, forKey: key)
        return img
    }

    /// Loads Huya's official 180×60 Guard shield sprite and right-crops `x = 132/180 .. 1.0`
    /// using CoreGraphics, caching the cropped 3× Retina UIImage.
    func guardShieldImage(for urlString: String) async -> UIImage? {
        let key = "guard:\(urlString)" as NSString
        if let cached = guardCropCache.object(forKey: key) {
            return cached
        }
        guard let rawImg = await image(for: urlString),
              let cg = rawImg.cgImage else {
            return nil
        }
        let w = cg.width
        let h = cg.height
        guard w > 0, h > 0 else { return nil }

        // Crop rightmost region x = 132/180 .. 1.0 (48x60 on 180x60 3x Retina sprite)
        let startX = Int((Double(w) * (132.0 / 180.0)).rounded(.down))
        let cropWidth = max(1, w - startX)
        let cropRect = CGRect(x: startX, y: 0, width: cropWidth, height: h)
        guard let cropped = cg.cropping(to: cropRect) else {
            return nil
        }
        let result = UIImage(cgImage: cropped, scale: 3.0, orientation: .up)
        guardCropCache.setObject(result, forKey: key)
        return result
    }
}
