import SwiftUI

struct CollectionsScreen: View {
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var collections: CollectionsStore

    @State private var showNew = false
    @State private var newName = ""

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 16) {
                header
                if collections.collections.isEmpty {
                    emptyState
                } else {
                    ScrollView {
                        VStack(spacing: 10) {
                            ForEach(collections.collections) { c in row(c) }
                        }
                        .padding(.bottom, 24)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(20)
        }
        .alert("New collection", isPresented: $showNew) {
            TextField("Name", text: $newName)
            Button("Create") {
                let n = newName.trimmingCharacters(in: .whitespaces)
                if !n.isEmpty { collections.createCollection(name: n) }
                newName = ""
            }
            Button("Cancel", role: .cancel) { newName = "" }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button { nav.navigate(to: .dashboard) } label: {
                Image(systemName: "chevron.left").font(.system(size: 18, weight: .semibold)).foregroundColor(Theme.onSurface)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Collections").font(.system(size: 22, weight: .bold)).foregroundColor(Theme.onSurface)
                Text("Your saved playlists").font(.system(size: 12)).foregroundColor(Theme.onSurfaceVariant)
            }
            Spacer()
            Button { showNew = true } label: {
                Image(systemName: "plus").font(.system(size: 18, weight: .semibold)).foregroundColor(Theme.primary)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "play.rectangle.on.rectangle").font(.system(size: 44)).foregroundColor(Theme.onSurfaceVariant)
            Text("No collections yet").font(.system(size: 16, weight: .semibold)).foregroundColor(Theme.onSurface)
            Text("Create a collection, then add channels or links to it.")
                .font(.system(size: 13)).foregroundColor(Theme.onSurfaceVariant).multilineTextAlignment(.center)
            Button { showNew = true } label: {
                Text("New collection").font(.system(size: 15, weight: .semibold)).foregroundColor(.white)
                    .padding(.horizontal, 20).padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Theme.primary))
            }
            .buttonStyle(.plain)
            Spacer(); Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private func row(_ c: MediaCollection) -> some View {
        Button { nav.navigate(to: .collectionDetail(c.id)) } label: {
            HStack(spacing: 12) {
                Image(systemName: "play.rectangle.fill").font(.system(size: 18)).foregroundColor(Theme.primary).frame(width: 26)
                VStack(alignment: .leading, spacing: 3) {
                    Text(c.name).font(.system(size: 15, weight: .semibold)).foregroundColor(Theme.onSurface).lineLimit(1)
                    Text("\(c.itemCount) item\(c.itemCount == 1 ? "" : "s")").font(.system(size: 11)).foregroundColor(Theme.onSurfaceVariant)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundColor(Theme.onSurfaceVariant)
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 14).fill(Theme.surfaceContainer))
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(role: .destructive) { collections.deleteCollection(c.id) } label: { Label("Delete", systemImage: "trash") }
        }
    }
}

// MARK: - Add to Collection

/// A not-yet-saved item passed into `AddToCollectionSheet` (from IPTV, etc.).
struct CollectionDraft: Identifiable {
    let id = UUID()
    var title: String
    var url: String
    var headers: [String: String] = [:]
    var logo: String?
    var sourceTag: String?
}

struct AddToCollectionSheet: View {
    let draft: CollectionDraft

    @EnvironmentObject private var collections: CollectionsStore
    @Environment(\.dismiss) private var dismiss

    @State private var showNew = false
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        showNew = true
                    } label: {
                        Label("New collection…", systemImage: "plus.circle")
                    }
                }
                if !collections.collections.isEmpty {
                    Section("Add to") {
                        ForEach(collections.collections) { c in
                            Button {
                                add(to: c.id)
                            } label: {
                                HStack {
                                    Text(c.name).foregroundColor(Theme.onSurface)
                                    Spacer()
                                    Text("\(c.itemCount)").foregroundColor(Theme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle("Add \"\(draft.title)\"")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .alert("New collection", isPresented: $showNew) {
                TextField("Name", text: $newName)
                Button("Create") {
                    let n = newName.trimmingCharacters(in: .whitespaces)
                    if !n.isEmpty {
                        let id = collections.createCollection(name: n)
                        add(to: id)
                    }
                    newName = ""
                }
                Button("Cancel", role: .cancel) { newName = "" }
            }
        }
    }

    private func add(to id: UUID) {
        collections.addItem(to: id, title: draft.title, url: draft.url,
                            headers: draft.headers, logo: draft.logo, sourceTag: draft.sourceTag)
        dismiss()
    }
}
