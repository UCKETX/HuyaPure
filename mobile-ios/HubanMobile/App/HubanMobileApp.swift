import BackgroundTasks
import SwiftUI
import UIKit

enum HubanBackgroundTasks {
    static let refreshIdentifier = "com.huya.pure.mobile.refresh"

    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: refreshIdentifier, using: nil) { task in
            guard let appRefresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handleAppRefresh(task: appRefresh)
        }
    }

    static func scheduleNextRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: refreshIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 4 * 3600)
        try? BGTaskScheduler.shared.submit(request)
    }

    private static func handleAppRefresh(task: BGAppRefreshTask) {
        scheduleNextRefresh()

        let work = Task.detached(priority: .utility) {
            do {
                _ = try HubanCoreBridge.shared.ensureStarted()
            } catch {
                return false
            }
            guard let res = HubanCoreBridge.shared.runAllSync(trigger: "schedule", onlyEnabled: true), res.ok else {
                return false
            }
            for item in res.results ?? [] {
                let title = (item.title?.isEmpty == false) ? item.title! : "虎伴 · \(item.label)"
                let body = (item.body?.isEmpty == false) ? item.body! : item.message
                LocalNotificationManager.postImmediate(
                    title: title,
                    body: body,
                    identifier: "huban-bg-\(item.accountId)-\(Int(Date().timeIntervalSince1970))"
                )
            }
            return true
        }

        task.expirationHandler = {
            work.cancel()
        }

        Task {
            let success = await work.value
            task.setTaskCompleted(success: success)
        }
    }
}

final class HubanAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        HubanBackgroundTasks.register()
        Task { @MainActor in
            LocalNotificationManager.shared.configure()
        }
        return true
    }
}

@main
struct HubanMobileApp: App {
    @UIApplicationDelegateAdaptor(HubanAppDelegate.self) private var appDelegate
    @StateObject private var store = HubanStore()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .tint(HubanTheme.accent)
                .task {
                    await store.bootstrap()
                    await AppUpdater.shared.checkIfNeededOnLaunch()
                }
                .onChange(of: scenePhase) { phase in
                    switch phase {
                    case .active:
                        Task {
                            await LocalNotificationManager.shared.refreshStatus()
                            await store.refreshOnForeground()
                        }
                    case .background:
                        HubanBackgroundTasks.scheduleNextRefresh()
                    default:
                        break
                    }
                }
        }
    }
}
