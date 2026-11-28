import SwiftUI
import UIKit

/// Authentic 3× Retina official Huya fan badge rendered natively from `BadgeRenderInfo`,
/// with graceful styled fallback if CDN assets are unavailable.
struct FanBadgePillView: View {
    let badge: FanBadge
    var height: CGFloat = 22

    @State private var floorImg: UIImage?
    @State private var iconImg: UIImage?
    @State private var levelImg: UIImage?
    @State private var vFlagImg: UIImage?
    @State private var loadFailed = false

    private var scaleFactor: CGFloat { height / 22.0 }

    private var textLeadingInset: CGFloat {
        let base: CGFloat = (badge.render?.isCustom ?? false) ? 25 : 21
        return base * scaleFactor
    }

    private var textTrailingInset: CGFloat {
        let render = badge.render
        let hasSuffix = render?.showsSuffix ?? false
        let base: CGFloat
        if hasSuffix {
            base = 15
        } else {
            base = (render?.isCustom ?? false) ? 3 : 4
        }
        return base * scaleFactor
    }

    private var nameFontSize: CGFloat {
        let isLong = badge.badgeName.count >= 4
        let isSize3 = (badge.render?.size ?? 2) >= 3
        let base: CGFloat = isLong ? 9.0 : (isSize3 ? 10.0 : 10.5)
        return base * scaleFactor
    }

    var body: some View {
        Group {
            if let floor = floorImg, !loadFailed {
                let aspect = floor.size.height > 0 ? floor.size.width / floor.size.height : 3.2
                let targetWidth = height * aspect

                ZStack {
                    Image(uiImage: floor)
                        .resizable()
                        .interpolation(.high)
                        .frame(width: targetWidth, height: height)

                    if let icon = iconImg {
                        HStack(spacing: 0) {
                            Image(uiImage: icon)
                                .resizable()
                                .interpolation(.high)
                                .aspectRatio(contentMode: .fit)
                                .frame(height: height)
                            Spacer(minLength: 0)
                        }
                    }

                    if let lv = levelImg {
                        HStack(spacing: 0) {
                            Image(uiImage: lv)
                                .resizable()
                                .interpolation(.high)
                                .aspectRatio(contentMode: .fit)
                                .frame(height: height)
                            Spacer(minLength: 0)
                        }
                    }

                    Text(badge.badgeName)
                        .font(.system(size: nameFontSize, weight: .bold))
                        .foregroundColor(.white)
                        .shadow(color: .black.opacity(0.45), radius: 1.2, x: 0, y: 0.8)
                        .lineLimit(1)
                        .minimumScaleFactor(0.72)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
                        .padding(.leading, textLeadingInset)
                        .padding(.trailing, textTrailingInset)

                    if let vf = vFlagImg {
                        HStack(spacing: 0) {
                            Spacer(minLength: 0)
                            Image(uiImage: vf)
                                .resizable()
                                .interpolation(.high)
                                .aspectRatio(contentMode: .fit)
                                .frame(height: height)
                        }
                    }
                }
                .frame(width: targetWidth, height: height)
                .saturation(badge.isExtinguished ? 0.0 : 1.0)
                .opacity(badge.isExtinguished ? 0.78 : 1.0)
            } else {
                fallbackBadge
            }
        }
        .task(id: badge.render) {
            await loadAssets()
        }
    }

    private var fallbackBadge: some View {
        HStack(spacing: 3 * scaleFactor) {
            Text("\(badge.level)")
                .font(.system(size: 10 * scaleFactor, weight: .heavy, design: .rounded))
                .padding(.horizontal, 4.5 * scaleFactor)
                .padding(.vertical, 1.5 * scaleFactor)
                .background(Color.black.opacity(0.24))
                .clipShape(Capsule())

            Text(badge.badgeName.isEmpty ? "粉丝牌" : badge.badgeName)
                .font(.system(size: 10.5 * scaleFactor, weight: .bold))
                .lineLimit(1)

            if badge.superFan && !badge.isExtinguished {
                Text(badge.sfVariety != 0 ? "V+" : "V")
                    .font(.system(size: 9 * scaleFactor, weight: .black))
                    .foregroundColor(Color(red: 1.0, green: 0.95, blue: 0.55))
            }
        }
        .foregroundColor(.white)
        .padding(.horizontal, 6 * scaleFactor)
        .frame(height: height)
        .background(fallbackGradient)
        .clipShape(RoundedRectangle(cornerRadius: 6 * scaleFactor, style: .continuous))
        .saturation(badge.isExtinguished ? 0.0 : 1.0)
        .opacity(badge.isExtinguished ? 0.68 : 1.0)
    }

    private var fallbackGradient: LinearGradient {
        if badge.isExtinguished {
            return LinearGradient(colors: [Color.gray.opacity(0.75), Color.gray.opacity(0.55)], startPoint: .leading, endPoint: .trailing)
        }
        switch badge.level {
        case 21...:
            return LinearGradient(colors: [Color(red: 0.90, green: 0.27, blue: 0.36), Color(red: 0.99, green: 0.49, blue: 0.14)], startPoint: .leading, endPoint: .trailing)
        case 14...20:
            return LinearGradient(colors: [Color(red: 0.56, green: 0.29, blue: 0.87), Color(red: 0.85, green: 0.30, blue: 0.62)], startPoint: .leading, endPoint: .trailing)
        case 8...13:
            return LinearGradient(colors: [Color(red: 0.20, green: 0.53, blue: 0.92), Color(red: 0.37, green: 0.41, blue: 0.91)], startPoint: .leading, endPoint: .trailing)
        default:
            return LinearGradient(colors: [Color(red: 0.96, green: 0.45, blue: 0.14), Color(red: 0.98, green: 0.62, blue: 0.18)], startPoint: .leading, endPoint: .trailing)
        }
    }

    private func loadAssets() async {
        loadFailed = false
        guard let render = badge.render, !render.floorUrl.isEmpty else {
            floorImg = nil
            return
        }

        async let f = HubanImageCache.shared.image(for: render.floorUrl)
        async let i: UIImage? = {
            if let u = render.iconUrl, !u.isEmpty { return await HubanImageCache.shared.image(for: u) }
            return nil
        }()
        async let l: UIImage? = {
            if let u = render.levelUrl, !u.isEmpty { return await HubanImageCache.shared.image(for: u) }
            return nil
        }()
        async let v: UIImage? = {
            if let u = render.vFlagUrl, !u.isEmpty { return await HubanImageCache.shared.image(for: u) }
            return nil
        }()

        let (loadedFloor, loadedIcon, loadedLevel, loadedVFlag) = await (f, i, l, v)
        if let loadedFloor {
            floorImg = loadedFloor
            iconImg = loadedIcon
            levelImg = loadedLevel
            vFlagImg = loadedVFlag
        } else {
            loadFailed = true
        }
    }
}

/// Native right-cropped 3× Retina Huya Guard shield icon (from the 180×60 CDN sprite, cropping `x = 132/180..1.0`).
struct GuardShieldBadgeView: View {
    let badge: FanBadge
    var height: CGFloat = 20

    @State private var shieldImg: UIImage?

    var body: some View {
        if badge.isGuard {
            HStack(spacing: 3) {
                if let img = shieldImg {
                    Image(uiImage: img)
                        .resizable()
                        .interpolation(.high)
                        .aspectRatio(contentMode: .fit)
                        .frame(height: height)
                } else {
                    Image(systemName: "shield.lefthalf.filled")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(HubanTheme.accent)
                }

                if let days = badge.guardAccompanyDay, days > 0 {
                    Text("\(days)天")
                        .font(.system(size: 10.5, weight: .semibold, design: .rounded))
                        .foregroundColor(.secondary)
                } else if let name = badge.guardName, !name.isEmpty, shieldImg == nil {
                    Text(name)
                        .font(.system(size: 10.5, weight: .semibold))
                        .foregroundColor(.secondary)
                }
            }
            .padding(.horizontal, 6)
            .frame(height: height + 2)
            .background(HubanTheme.cardSecondary)
            .overlay(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .stroke(HubanTheme.border, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .task(id: badge.render?.guardIconUrl) {
                guard let url = badge.render?.guardIconUrl, !url.isEmpty else { return }
                shieldImg = await HubanImageCache.shared.guardShieldImage(for: url)
            }
        }
    }
}
