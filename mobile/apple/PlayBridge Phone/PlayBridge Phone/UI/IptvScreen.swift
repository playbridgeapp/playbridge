import SwiftUI
import UniformTypeIdentifiers

struct IptvScreen: View {
    @EnvironmentObject private var nav: NavigationViewModel
    @EnvironmentObject private var iptv: IptvStore

    @State private var showAdd = false

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 16) {
                header

                if iptv.playlists.isEmpty {
                    emptyState
                } else {
                    ScrollView {
                        VStack(spacing: 10) {
                            ForEach(iptv.playlists) { pl in
                                playlistRow(pl)
                            }
                        }
                        .padding(.bottom, 24)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(20)
        }
        .sheet(isPresented: $showAdd) {
            IptvAddSheet().environmentObject(iptv)
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button { nav.navigate(to: .dashboard) } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("IPTV").font(.system(size: 22, weight: .bold)).foregroundColor(Theme.onSurface)
                Text("Live channels from your playlists").font(.system(size: 12)).foregroundColor(Theme.onSurfaceVariant)
            }
            Spacer()
            Button { showAdd = true } label: {
                Image(systemName: "plus")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Theme.primary)
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "tv").font(.system(size: 44)).foregroundColor(Theme.onSurfaceVariant)
            Text("No playlists yet").font(.system(size: 16, weight: .semibold)).foregroundColor(Theme.onSurface)
            Text("Add an M3U playlist (URL or file) to browse and cast live channels.")
                .font(.system(size: 13)).foregroundColor(Theme.onSurfaceVariant)
                .multilineTextAlignment(.center).padding(.horizontal, 24)
            Button { showAdd = true } label: {
                Text("Add IPTV playlist")
                    .font(.system(size: 15, weight: .semibold)).foregroundColor(.white)
                    .padding(.horizontal, 20).padding(.vertical, 12)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Theme.primary))
            }
            .buttonStyle(.plain)
            Spacer(); Spacer()
        }
        .frame(maxWidth: .infinity)
    }

    private func playlistRow(_ pl: IptvPlaylist) -> some View {
        Button { nav.navigate(to: .iptvDetail(pl.id)) } label: {
            HStack(spacing: 12) {
                Image(systemName: pl.sourceType == .url ? "globe" : "doc")
                    .font(.system(size: 18)).foregroundColor(Theme.primary).frame(width: 26)
                VStack(alignment: .leading, spacing: 3) {
                    Text(pl.name).font(.system(size: 15, weight: .semibold))
                        .foregroundColor(Theme.onSurface).lineLimit(1)
                    Text("\(pl.channelCount) channels • updated \(relative(pl.updatedAt ?? pl.addedAt))")
                        .font(.system(size: 11)).foregroundColor(Theme.onSurfaceVariant).lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Theme.onSurfaceVariant)
            }
            .padding(14)
            .background(RoundedRectangle(cornerRadius: 14).fill(Theme.surfaceContainer))
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button(role: .destructive) { iptv.delete(pl.id) } label: {
                Label("Remove", systemImage: "trash")
            }
        }
    }

    private func relative(_ date: Date) -> String {
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .short
        return f.localizedString(for: date, relativeTo: Date())
    }
}

// MARK: - Add sheet

struct IptvAddSheet: View {
    @EnvironmentObject private var iptv: IptvStore
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var urlString = ""
    @State private var pickedFile: URL?
    @State private var showImporter = false
    @State private var isSaving = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("My playlist", text: $name)
                        .foregroundColor(Theme.onSurface)
                }
                Section {
                    TextField("https://example.com/playlist.m3u", text: $urlString)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .foregroundColor(Theme.onSurface)
                        .disabled(pickedFile != nil)
                } header: { Text("Playlist URL") }

                Section {
                    Button { showImporter = true } label: {
                        HStack {
                            Image(systemName: "doc.badge.plus")
                            Text(pickedFile?.lastPathComponent ?? "Select .m3u file")
                            Spacer()
                            if pickedFile != nil {
                                Button { pickedFile = nil } label: { Image(systemName: "xmark.circle.fill") }
                                    .foregroundColor(Theme.onSurfaceVariant)
                            }
                        }
                    }
                    .disabled(!urlString.trimmingCharacters(in: .whitespaces).isEmpty)
                } header: { Text("Or a file") } footer: {
                    Text("Provide a playlist URL or pick a file — not both.")
                }

                if let errorMessage {
                    Section { Text(errorMessage).foregroundColor(Theme.danger).font(.system(size: 13)) }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle("Add playlist")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if isSaving { ProgressView() } else { Button("Save") { save() }.disabled(!canSave) }
                }
            }
            .fileImporter(isPresented: $showImporter,
                          allowedContentTypes: [UTType("public.m3u-playlist") ?? .plainText, .plainText, .data],
                          allowsMultipleSelection: false) { result in
                if case .success(let urls) = result, let url = urls.first { pickedFile = url }
            }
        }
    }

    private var canSave: Bool {
        let hasName = !name.trimmingCharacters(in: .whitespaces).isEmpty
        let hasURL = !urlString.trimmingCharacters(in: .whitespaces).isEmpty
        return hasName && (hasURL || pickedFile != nil)
    }

    private func save() {
        isSaving = true
        errorMessage = nil
        Task {
            do {
                if let file = pickedFile {
                    try await iptv.addFilePlaylist(name: name, fileURL: file)
                } else {
                    try await iptv.addURLPlaylist(name: name, urlString: urlString)
                }
                isSaving = false
                dismiss()
            } catch {
                isSaving = false
                errorMessage = error.localizedDescription
            }
        }
    }
}
