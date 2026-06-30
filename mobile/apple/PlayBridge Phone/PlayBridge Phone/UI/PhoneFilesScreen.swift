import SwiftUI
import UniformTypeIdentifiers
import PhotosUI

/// Pick a local video/audio file and cast it to the connected receiver. The file is
/// served over the LAN by `LocalFileServer`, and its URL is sent like any other cast.
struct PhoneFilesScreen: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @EnvironmentObject private var nav: NavigationViewModel

    @State private var showImporter = false
    @State private var showPhotoPicker = false
    @State private var pickedURL: URL?
    @State private var isPreparing = false
    @State private var statusMessage: String?
    @State private var isError = false

    private var pickedName: String { pickedURL?.lastPathComponent ?? "" }

    var body: some View {
        ZStack {
            Theme.surface.ignoresSafeArea()

            VStack(alignment: .leading, spacing: 20) {
                header

                if !vm.isConnected {
                    notice("Not connected to a TV. Open Connection first, then come back to cast.",
                           color: Color(hex: 0xFFA000))
                }

                pickCard

                if let pickedURL {
                    selectedCard(for: pickedURL)
                }

                if let statusMessage {
                    notice(statusMessage, color: isError ? Theme.danger : Color(hex: 0x4CAF50))
                }

                Text("Only cast files that you own or have the right to play.")
                    .font(.system(size: 12))
                    .foregroundColor(Theme.onSurfaceVariant)
                    .padding(.top, 4)

                Spacer()
            }
            .padding(20)
        }
        .fileImporter(
            isPresented: $showImporter,
            allowedContentTypes: [.audiovisualContent, .movie, .video, .audio],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    pickedURL = url
                    statusMessage = nil
                    isError = false
                }
            case .failure(let err):
                statusMessage = "Couldn't open file: \(err.localizedDescription)"
                isError = true
            }
        }
        .sheet(isPresented: $showPhotoPicker) {
            PhotoVideoPicker { url in handlePicked(url) }
                .ignoresSafeArea()
        }
    }

    private func handlePicked(_ url: URL?) {
        if let url {
            pickedURL = url
            statusMessage = nil
            isError = false
        } else {
            statusMessage = "Couldn't load that video from your library."
            isError = true
        }
    }

    // MARK: - Components

    private var header: some View {
        HStack(spacing: 12) {
            Button {
                nav.navigate(to: .dashboard)
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(Theme.onSurface)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text("Phone Files")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(Theme.onSurface)
                Text("Cast videos & audio from your phone")
                    .font(.system(size: 12))
                    .foregroundColor(Theme.onSurfaceVariant)
            }
            Spacer()
        }
    }

    private var pickCard: some View {
        VStack(spacing: 10) {
            pickButton(title: "Photo Library", icon: "photo.on.rectangle") { showPhotoPicker = true }
            pickButton(title: "Files (iCloud / Downloads)", icon: "folder") { showImporter = true }
        }
    }

    private func pickButton(title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                    .foregroundColor(.white)
                    .frame(width: 26)
                Text(title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(.white)
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(.white.opacity(0.7))
            }
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: 16)
                    .fill(LinearGradient(colors: [Color(hex: 0x4527A0), Color(hex: 0x5E35B1)],
                                         startPoint: .topLeading, endPoint: .bottomTrailing))
            )
        }
        .buttonStyle(.plain)
    }

    private func selectedCard(for url: URL) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "film")
                    .font(.system(size: 18))
                    .foregroundColor(Theme.primary)
                Text(pickedName)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(Theme.onSurface)
                    .lineLimit(2)
                Spacer()
            }

            Button {
                cast(url)
            } label: {
                HStack {
                    Spacer()
                    if isPreparing {
                        ProgressView().tint(.white).padding(.trailing, 8)
                        Text("Preparing…")
                    } else {
                        Image(systemName: "play.tv.fill")
                        Text("Cast to TV")
                    }
                    Spacer()
                }
                .font(.system(size: 15, weight: .semibold))
                .foregroundColor(.white)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: 14)
                        .fill(vm.isConnected ? Theme.primary : Theme.onSurfaceVariant.opacity(0.4))
                )
            }
            .buttonStyle(.plain)
            .disabled(!vm.isConnected || isPreparing)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 16).fill(Theme.surfaceContainer))
    }

    private func notice(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: 13))
            .foregroundColor(color)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(color.opacity(0.12)))
    }

    // MARK: - Cast

    private func cast(_ url: URL) {
        isPreparing = true
        statusMessage = nil
        Task {
            let served = await LocalFileServer.shared.serve(fileURL: url)
            await MainActor.run {
                isPreparing = false
                guard let served else {
                    isError = true
                    statusMessage = "Couldn't start the local server. Make sure Wi-Fi is on and try again."
                    return
                }
                vm.cast(urlString: served, title: url.deletingPathExtension().lastPathComponent)
                isError = false
                statusMessage = "Casting \(pickedName) to your TV."
            }
        }
    }
}

// MARK: - Photo library picker

/// Wraps `PHPickerViewController` to pick a video from the Photos library. PHPicker
/// runs out-of-process, so it needs no photo-library permission. The selected item is
/// copied out of its temporary location into our caches so the LAN server can serve it.
struct PhotoVideoPicker: UIViewControllerRepresentable {
    var onPick: (URL?) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .videos
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPick: (URL?) -> Void
        init(onPick: @escaping (URL?) -> Void) { self.onPick = onPick }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            guard let provider = results.first?.itemProvider else { onPick(nil); return }
            let movieType = UTType.movie.identifier
            let typeID = provider.hasItemConformingToTypeIdentifier(movieType)
                ? movieType
                : (provider.registeredTypeIdentifiers.first ?? movieType)

            provider.loadFileRepresentation(forTypeIdentifier: typeID) { url, _ in
                // The provided URL is only valid inside this closure — copy it out now.
                let copied = url.flatMap { PhoneFilesCopier.copyToCaches($0) }
                DispatchQueue.main.async { self.onPick(copied) }
            }
        }
    }
}

/// Copies a picked file into a dedicated caches subfolder (clearing prior copies) so
/// it has a stable, app-owned URL for the lifetime of the cast.
enum PhoneFilesCopier {
    static func copyToCaches(_ src: URL) -> URL? {
        let fm = FileManager.default
        guard let caches = fm.urls(for: .cachesDirectory, in: .userDomainMask).first else { return nil }
        let dir = caches.appendingPathComponent("phone-files", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        if let old = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) {
            for f in old { try? fm.removeItem(at: f) }
        }
        let ext = src.pathExtension.isEmpty ? "mov" : src.pathExtension
        let dest = dir.appendingPathComponent("media-\(UUID().uuidString).\(ext)")
        do {
            try fm.copyItem(at: src, to: dest)
            return dest
        } catch {
            return nil
        }
    }
}
