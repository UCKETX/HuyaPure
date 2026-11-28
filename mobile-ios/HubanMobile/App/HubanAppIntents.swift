import AppIntents
import Foundation

struct RunHubanTasksIntent: AppIntent {
    static var title: LocalizedStringResource = "执行虎伴每日任务"
    static var description = IntentDescription("自动领虎粮、全粉丝牌打卡、领每日福利并按规则智能送粮。")
    static var openAppWhenRun: Bool = false

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let response: RunAllResponse? = await Task.detached(priority: .userInitiated) {
            do {
                _ = try HubanCoreBridge.shared.ensureStarted()
            } catch {
                return RunAllResponse(ok: false, count: 0, results: [], error: error.localizedDescription)
            }
            return HubanCoreBridge.shared.runAllSync(trigger: "schedule", onlyEnabled: false)
        }.value

        guard let response else {
            let msg = "虎伴核心引擎未返回结果"
            LocalNotificationManager.postImmediate(title: "虎伴每日任务失败", body: msg)
            return .result(dialog: IntentDialog(stringLiteral: msg))
        }

        if !response.ok {
            let errText = response.error ?? "任务执行失败"
            LocalNotificationManager.postImmediate(title: "虎伴每日任务失败", body: errText)
            return .result(dialog: IntentDialog(stringLiteral: errText))
        }

        let items = response.results ?? []
        if items.isEmpty {
            let emptyMsg = "暂无已登录的虎牙账号，请先在虎伴 App 中添加账号。"
            return .result(dialog: IntentDialog(stringLiteral: emptyMsg))
        }

        var okCount = 0
        var summaryLines: [String] = []
        for item in items {
            if item.success { okCount += 1 }
            let title = (item.title?.isEmpty == false)
                ? item.title!
                : "虎伴 · \(item.label) \(item.success ? "任务完成" : "执行异常")"
            let body = (item.body?.isEmpty == false) ? item.body! : item.message
            LocalNotificationManager.postImmediate(
                title: title,
                body: body,
                identifier: "huban-intent-\(item.accountId)-\(Int(Date().timeIntervalSince1970))"
            )
            summaryLines.append("\(item.label)：\(item.message)")
        }

        let header = "虎伴已完成 \(okCount)/\(items.count) 个账号任务"
        let dialogText = summaryLines.isEmpty ? header : "\(header)。\(summaryLines.joined(separator: "；"))"
        return .result(dialog: IntentDialog(stringLiteral: dialogText))
    }
}

struct HubanShortcutsProvider: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: RunHubanTasksIntent(),
            phrases: [
                "用\(.applicationName)执行每日任务",
                "\(.applicationName)领虎粮",
                "\(.applicationName)打卡"
            ],
            shortTitle: "执行虎伴每日任务",
            systemImageName: "flame.fill"
        )
    }
}
