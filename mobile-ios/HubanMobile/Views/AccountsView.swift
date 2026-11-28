import SwiftUI

struct AccountsView: View {
    @EnvironmentObject private var store: HubanStore
    @Binding var showAddAccount: Bool

    @State private var accountPendingDelete: AccountPublicView?

    var body: some View {
        NavigationStack {
            Group {
                if store.accounts.isEmpty {
                    EmptyAccountPromptView {
                        showAddAccount = true
                    }
                } else {
                    List {
                        Section {
                            ForEach(store.accounts) { account in
                                accountRow(account)
                                    .listRowBackground(HubanTheme.card)
                                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                                        Button(role: .destructive) {
                                            accountPendingDelete = account
                                        } label: {
                                            Label("删除", systemImage: "trash")
                                        }
                                    }
                            }
                        } header: {
                            Text("已登录账号（\(store.accounts.count)）")
                        } footer: {
                            Text("提示：手机短信登录支持长期自动续期；点击账号即可切换当前活跃视角，左滑可删除账号。")
                        }
                    }
                    .scrollContentBackground(.hidden)
                    .background(HubanTheme.canvas.ignoresSafeArea())
                    .refreshable {
                        await store.loadStatus()
                    }
                }
            }
            .navigationTitle("账号管理")
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Haptics.light()
                        showAddAccount = true
                    } label: {
                        Label("添加账号", systemImage: "plus")
                    }
                }
            }
            .alert("移除虎牙账号？", isPresented: Binding(
                get: { accountPendingDelete != nil },
                set: { if !$0 { accountPendingDelete = nil } }
            )) {
                Button("取消", role: .cancel) { accountPendingDelete = nil }
                Button("移除", role: .destructive) {
                    if let target = accountPendingDelete {
                        Task { await store.deleteAccount(id: target.id) }
                    }
                    accountPendingDelete = nil
                }
            } message: {
                if let target = accountPendingDelete {
                    Text("确定移除「\(target.displayName)」(UID: \(target.uid))？本地保存的登录凭证与任务配置将被清除。")
                }
            }
        }
    }

    private func accountRow(_ account: AccountPublicView) -> some View {
        let isSelected = account.id == store.currentAccount?.id
        return Button {
            store.selectAccount(account.id)
        } label: {
            HStack(spacing: 12) {
                avatarView(for: account)

                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 6) {
                        Text(account.displayName)
                            .font(.headline)
                            .foregroundColor(.primary)
                            .lineLimit(1)
                        authPill(isMobile: account.isMobileAuth)
                    }

                    HStack(spacing: 10) {
                        Text("UID \(account.uid)")
                            .font(.caption.monospacedDigit())
                            .foregroundColor(.secondary)
                        Text("粉丝牌 \(account.state.badges.count)")
                            .font(.caption)
                            .foregroundColor(.secondary)
                        if account.state.freeHuliangCount >= 0 {
                            Text("虎粮 \(account.state.freeHuliangCount)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                }

                Spacer()

                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.title3)
                        .foregroundColor(HubanTheme.accent)
                }
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }

    private func authPill(isMobile: Bool) -> some View {
        Text(isMobile ? "长期自动续期" : "网页登录")
            .font(.caption2.bold())
            .foregroundColor(isMobile ? HubanTheme.success : HubanTheme.warning)
            .padding(.horizontal, 7)
            .padding(.vertical, 2.5)
            .background((isMobile ? HubanTheme.success : HubanTheme.warning).opacity(0.14))
            .clipShape(Capsule())
    }

    private func avatarView(for account: AccountPublicView) -> some View {
        Group {
            if let urlStr = account.avatar, let url = URL(string: urlStr), !urlStr.isEmpty {
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let img): img.resizable().scaledToFill()
                    default: placeholderAvatar(account.displayName)
                    }
                }
            } else {
                placeholderAvatar(account.displayName)
            }
        }
        .frame(width: 46, height: 46)
        .clipShape(Circle())
        .overlay(Circle().stroke(HubanTheme.border, lineWidth: 1))
    }

    private func placeholderAvatar(_ name: String) -> some View {
        ZStack {
            Circle().fill(HubanTheme.accent.opacity(0.15))
            Text(String(name.prefix(1)))
                .font(.headline.bold())
                .foregroundColor(HubanTheme.accent)
        }
    }
}
