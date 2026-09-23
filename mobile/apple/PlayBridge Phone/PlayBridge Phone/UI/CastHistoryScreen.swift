import SwiftUI

struct CastHistoryScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel

    var body: some View {
        CastHistoryList(store: vm.castHistory)
            .environmentObject(vm)
            .environmentObject(nav)
    }
}

private struct CastHistoryList: View {
    @ObservedObject var store: CastHistoryStore
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel
    @AppStorage("cast_prevent_receiver_history") private var preventReceiverHistory = false
    @AppStorage("cast_save_history") private var saveHistory = true
    @State private var confirmClear = false
    @State private var replaying = false
    @State private var search = ""

    private var entries: [CastHistoryStore.Entry] {
        store.entries.filter { search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.host.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("Save cast history on this phone", isOn: $saveHistory)
                    Toggle("Prevent receiver history", isOn: $preventReceiverHistory)
                } footer: {
                    Text("Receiver preference applies to future casts on supporting PlayBridge receivers. Other receivers may ignore it. Existing history is unchanged.")
                }
                if let error = store.persistenceError { Text(error).foregroundStyle(.red) }
                if store.entries.isEmpty {
                    Text("No cast history yet").foregroundStyle(.secondary)
                }
                ForEach(entries) { entry in
                    Button {
                        replaying = true
                        Task { await vm.replayCast(entry); replaying = false }
                    } label: {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(entry.title).lineLimit(2)
                            Text(entry.host).font(Theme.font(.caption)).foregroundStyle(.secondary)
                            HStack {
                                Text(entry.date, style: .date)
                                Text(entry.date, style: .time)
                                if let receiver = entry.receiver { Text(receiver).lineLimit(1) }
                            }
                            .font(Theme.font(.caption2)).foregroundStyle(.secondary)
                        }
                    }
                    .disabled(replaying || !vm.isConnected)
                    .swipeActions {
                        Button("Delete", role: .destructive) { store.delete(entry.id) }
                    }
                }
                if !vm.isConnected && !store.entries.isEmpty {
                    Button("Connect a device to replay") { nav.navigate(to: .connection) }
                }
            }
            .font(Theme.font(.body))
            .searchable(text: $search, prompt: "Search casts")
            .navigationTitle("Cast History")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    DashboardNavigationButton()
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Clear", role: .destructive) { confirmClear = true }.disabled(store.entries.isEmpty)
                }
            }
            .confirmationDialog("Clear all cast history?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear history", role: .destructive) { store.clear() }
            }
        }
    }
}
