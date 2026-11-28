import Foundation
import UserNotifications

@MainActor
final class LocalNotificationManager: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = LocalNotificationManager()

    @Published private(set) var authorizationStatus: UNAuthorizationStatus = .notDetermined

    private override init() {
        super.init()
    }

    func configure() {
        UNUserNotificationCenter.current().delegate = self
        Task {
            await refreshStatus()
        }
    }

    func refreshStatus() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        authorizationStatus = settings.authorizationStatus
    }

    @discardableResult
    func requestAuthorization() async -> Bool {
        do {
            let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
            await refreshStatus()
            return granted
        } catch {
            await refreshStatus()
            return false
        }
    }

    nonisolated static func postImmediate(title: String, body: String, identifier: String = UUID().uuidString) {
        let content = UNMutableNotificationContent()
        content.title = title.isEmpty ? "虎伴通知" : title
        content.body = body
        content.sound = .default

        let request = UNNotificationRequest(identifier: identifier, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { _ in }
    }

    nonisolated func post(title: String, body: String, identifier: String = UUID().uuidString) {
        Self.postImmediate(title: title, body: body, identifier: identifier)
    }

    func postTestNotification() async -> Bool {
        if authorizationStatus == .notDetermined {
            let granted = await requestAuthorization()
            if !granted { return false }
        } else if authorizationStatus == .denied {
            return false
        }
        post(
            title: "虎伴本地通知测试",
            body: "✅ 通知权限已开启！定时任务结果与超粉日 4 倍提醒将通过系统通知及时送达。"
        )
        return true
    }

    // MARK: - UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound, .list])
    }
}
