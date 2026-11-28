import SwiftUI

struct SendTargetsSheet: View {
    @EnvironmentObject private var store: HubanStore
    @Environment(\.dismiss) private var dismiss

    let account: AccountPublicView

    @State private var selectedTargets: [SendTarget] = []
    @State private var customUIDText = ""
    @State private var customNameText = ""
    @State private var searchText = ""

    private var badges: [FanBadge] { account.state.badges }

    private var filteredBadges: [FanBadge] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return badges }
        return badges.filter {
            $0.nick.lowercased().contains(q)
                || $0.badgeName.lowercased().contains(q)
                || "\($0.pid)".contains(q)
        }
    }

    private var customTargets: [SendTarget] {
        let badgePIDs = Set(badges.map(\.pid))
        return selectedTargets.filter { !badgePIDs.contains($0.pid) }
    }

    var body: some View {
        NavigationStack {
            List {
                // Quick Actions
                Section {
                    HStack(spacing: 8) {
                        quickChip("全选") {
                            selectedTargets = badges.map { b in
                                let existing = selectedTargets.first { $0.pid == b.pid }
                                return SendTarget(pid: b.pid, name: b.nick, weight: max(1, existing?.weight ?? 1))
                            }
                            Haptics.light()
                        }
                        quickChip("仅超粉") {
                            selectedTargets = badges.filter(\.superFan).map { b in
                                let existing = selectedTargets.first { $0.pid == b.pid }
                                return SendTarget(pid: b.pid, name: b.nick, weight: max(1, existing?.weight ?? 1))
                            }
                            Haptics.light()
                        }
                        quickChip("清空") {
                            selectedTargets = []
                            Haptics.light()
                        }
                    }
                    .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
                }

                // Add Custom UID
                Section("添加自定义主播 UID") {
                    HStack(spacing: 8) {
                        TextField("主播 UID（数字）", text: $customUIDText)
                            .keyboardType(.numberPad)
                        TextField("备注名（可选）", text: $customNameText)
                        Button("添加") {
                            addCustomTarget()
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(HubanTheme.accent)
                        .disabled(Int64(customUIDText.trimmingCharacters(in: .whitespaces)) == nil)
                    }
                }

                if !customTargets.isEmpty {
                    Section("已添加自定义目标") {
                        ForEach(customTargets) { target in
                            targetRow(pid: target.pid, title: target.name.isEmpty ? "UID \(target.pid)" : target.name, badge: nil)
                        }
                    }
                }

                Section("我的粉丝牌（已选 \(selectedTargets.count) 个）") {
                    if filteredBadges.isEmpty {
                        Text("暂无匹配的粉丝牌")
                            .font(.footnote)
                            .foregroundColor(.secondary)
                    } else {
                        ForEach(filteredBadges) { badge in
                            targetRow(pid: badge.pid, title: badge.nick, badge: badge)
                        }
                    }
                }
            }
            .searchable(text: $searchText, prompt: "搜索主播名、牌子名或 UID")
            .navigationTitle("选择赠送目标")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        saveAndDismiss()
                    }
                    .bold()
                }
            }
            .onAppear {
                selectedTargets = account.config.sendTargets
            }
        }
    }

    private func quickChip(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.caption.bold())
                .foregroundColor(HubanTheme.accent)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(HubanTheme.accent.opacity(0.12))
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private func targetRow(pid: Int64, title: String, badge: FanBadge?) -> some View {
        let selectedIdx = selectedTargets.firstIndex { $0.pid == pid }
        let isSelected = selectedIdx != nil
        let weight = selectedIdx.map { max(1, selectedTargets[$0].weight) } ?? 1

        return VStack(alignment: .leading, spacing: 8) {
            Button {
                toggleTarget(pid: pid, name: title)
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundColor(isSelected ? HubanTheme.accent : .secondary)

                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 6) {
                            Text(title)
                                .font(.subheadline.weight(.semibold))
                                .foregroundColor(.primary)
                            if let badge {
                                FanBadgePillView(badge: badge, height: 18)
                            }
                        }
                        Text("UID: \(pid)")
                            .font(.caption2.monospacedDigit())
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                }
            }
            .buttonStyle(.plain)

            if isSelected {
                HStack {
                    Text("分配权重")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Spacer()
                    Stepper(value: Binding(
                        get: { weight },
                        set: { newWeight in
                            if let idx = selectedTargets.firstIndex(where: { $0.pid == pid }) {
                                selectedTargets[idx].weight = max(1, newWeight)
                            }
                        }
                    ), in: 1...50) {
                        Text("\(weight) 份")
                            .font(.caption.monospacedDigit().bold())
                            .foregroundColor(HubanTheme.accent)
                    }
                    .fixedSize()
                }
                .padding(.leading, 32)
            }
        }
        .padding(.vertical, 2)
    }

    private func toggleTarget(pid: Int64, name: String) {
        Haptics.light()
        if let idx = selectedTargets.firstIndex(where: { $0.pid == pid }) {
            selectedTargets.remove(at: idx)
        } else {
            selectedTargets.append(SendTarget(pid: pid, name: name, weight: 1))
        }
    }

    private func addCustomTarget() {
        let raw = customUIDText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let pid = Int64(raw), pid > 0 else { return }
        let name = customNameText.trimmingCharacters(in: .whitespacesAndNewlines)
        if !selectedTargets.contains(where: { $0.pid == pid }) {
            selectedTargets.append(SendTarget(pid: pid, name: name.isEmpty ? "主播 \(pid)" : name, weight: 1))
        }
        customUIDText = ""
        customNameText = ""
        Haptics.success()
    }

    private func saveAndDismiss() {
        var nextCfg = account.config
        nextCfg.sendTargets = selectedTargets
        Task {
            await store.updateConfig(accountId: account.id, config: nextCfg, showFeedback: true)
            dismiss()
        }
    }
}
