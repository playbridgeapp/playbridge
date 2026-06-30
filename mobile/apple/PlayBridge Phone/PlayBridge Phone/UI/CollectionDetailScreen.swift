import SwiftUI

struct CollectionDetailScreen: View {
    let collectionId: UUID

    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var collections: CollectionsStore

    @State private var showAdd = false
    @State private var toast: String?

    private var collection: MediaCollection? { collections.collection(collectionId) }

    var body: some View {
        ZStack(alignment: .bottom) {
            Theme.surface.ignoresSafeArea()

            if let collection {
                VStack(spacing: 12) {
                    header(collection)
                    if collection.items.isEmpty {
                        emptyState
                    } else {
                        list(collection)
                    }
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
            } else {
                Text("Collection not found.").foregroundColor(Theme.onSurfaceVariant)
            }

            if let toast {
                Text(toast).font(.system(size: 13, weight: .medium)).foregroundColor(.white)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Capsule().fill(Theme.primaryDim)).padding(.bottom, 24)
            }
        }
        .sheet(isPresented: $showAdd) {
            AddManualItemSheet(collectionId: collectionId)
        }
    }

    private func header(_ c: MediaCollection) -> some View {
        HStack(spacing: 12) {
            Button { nav.navigate(to: .collections) } label: {
                Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundColor(Theme.onSurface)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(c.name).font(.system(size: 20, weight: .bold)).foregroundColor(Theme.onSurface).lineLimit(1)
                Text("\(c.itemCount) item\(c.itemCount == 1 ? "" : "s")").font(.system(size: 12)).foregroundColor(Theme.onSurfaceVariant)
            }
            Spacer()
            Button { showAdd = true } label: {
                Image(systemName: "plus").font(.system(size: 18, weight: .semibold)).foregroundColor(Theme.primary)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Text("No items yet").font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.onSurface)
            Text("Add a link here, or use “Add to Collection” from an IPTV channel.")
                .font(.system(size: 13)).foregroundColor(Theme.onSurfaceVariant).multilineTextAlignment(.center)
            Spacer(); Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private func list(_ c: MediaCollection) -> some View {
        ScrollView {
            VStack(spacing: 8) {
                ForEach(c.items.sorted { $0.order < $1.order }) { item in
                    row(item, in: c)
                }
            }
            .padding(.bottom, 80)
        }
    }

    private func row(_ item: CollectionItem, in c: MediaCollection) -> some View {
        Button { play(item) } label: {
            HStack(spacing: 10) {
                Image(systemName: item.sourceTag == "iptv" ? "play.tv" : "link")
                    .font(.system(size: 15)).foregroundColor(Theme.primary).frame(width: 24)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.title).font(.system(size: 14)).foregroundColor(Theme.onSurface).lineLimit(1)
                    Text(host(item.url)).font(.system(size: 11)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
                }
                Spacer()
            }
            .padding(.vertical, 9).padding(.horizontal, 10)
            .background(RoundedRectangle(cornerRadius: 12).fill(Theme.surfaceContainer))
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button { play(item) } label: { Label("Play", systemImage: "play.fill") }
            Button { collections.move(item.id, in: c.id, up: true) } label: { Label("Move up", systemImage: "arrow.up") }
            Button { collections.move(item.id, in: c.id, up: false) } label: { Label("Move down", systemImage: "arrow.down") }
            Button(role: .destructive) { collections.removeItem(item.id, from: c.id) } label: { Label("Remove", systemImage: "trash") }
        }
    }

    private func play(_ item: CollectionItem) {
        guard vm.isConnected else { showToast("Not connected — open Connection first"); return }
        vm.castMedia(url: item.url, title: item.title, headers: item.headers, contentType: item.mimeType)
        showToast("Casting \(item.title)")
    }

    private func host(_ url: String) -> String { URL(string: url)?.host ?? url }

    private func showToast(_ text: String) {
        toast = text
        Task { try? await Task.sleep(nanoseconds: 2_000_000_000); if toast == text { toast = nil } }
    }
}

// MARK: - Manual add

struct AddManualItemSheet: View {
    let collectionId: UUID

    @EnvironmentObject private var collections: CollectionsStore
    @Environment(\.dismiss) private var dismiss

    @State private var title = ""
    @State private var url = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Title") { TextField("My stream", text: $title).foregroundColor(Theme.onSurface) }
                Section("URL") {
                    TextField("https://…", text: $url)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .foregroundColor(Theme.onSurface)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle("Add item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        let t = title.trimmingCharacters(in: .whitespaces)
                        let u = url.trimmingCharacters(in: .whitespaces)
                        collections.addItem(to: collectionId, title: t.isEmpty ? u : t, url: u, sourceTag: "manual")
                        dismiss()
                    }
                    .disabled(url.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}
