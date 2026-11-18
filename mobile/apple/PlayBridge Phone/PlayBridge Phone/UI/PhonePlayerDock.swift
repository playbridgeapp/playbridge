import SwiftUI

/// Keeps a phone-player video going after Back when "Keep playing" is chosen (Android #515/#530
/// without picture-in-picture). The docked session keeps its audio session and lock-screen
/// controls; the bar at the bottom of every screen, or Dashboard › ⋯ › Phone player, opens it
/// again. A new video replaces it.
@MainActor final class PhonePlayerDock: ObservableObject {
    static let shared = PhonePlayerDock()
    @Published private(set) var docked: PlayerItem?
    /// The docked player opened again, presented from the root.
    @Published var presented: PlayerItem?
    private var audioOwner: UUID?

    /// Back leaves a playing video playing when this video's choice is Keep playing.
    func shouldDock(_ session: PlaybackSession) -> Bool {
        session.keepPlayingInBackground && session.isPlaying && session.failure == nil
    }

    func dock(_ item: PlayerItem) {
        if let docked, docked.session !== item.session { close() }
        docked = item
        // Take the audio session before the player that is closing gives up its own.
        if audioOwner == nil { audioOwner = try? CastSystemPlayback.shared.beginLocalPlayback() }
        PhoneNowPlaying.shared.attach(item.session)
    }

    /// A full-screen player is opening: the same session takes over from the dock; any other
    /// video replaces the docked one.
    func playerWillAppear(_ session: PlaybackSession) {
        guard let docked else { return }
        if docked.session === session {
            self.docked = nil
            releaseAudio()
        } else {
            close()
        }
    }

    func playerDidAppear(_ session: PlaybackSession) {
        playerWillAppear(session)
        keepPlayingChanged(session)
    }

    /// Lock-screen controls follow the video's keep-playing choice.
    func keepPlayingChanged(_ session: PlaybackSession) {
        if session.keepPlayingInBackground { PhoneNowPlaying.shared.attach(session) }
        else { PhoneNowPlaying.shared.detach(session) }
    }

    /// Back: dock a playing video set to Keep playing; otherwise the player closes it.
    func leave(_ item: PlayerItem) -> Bool {
        if shouldDock(item.session) { dock(item); return true }
        PhoneNowPlaying.shared.detach(item.session)
        return false
    }

    /// A new player opened from the root (Dashboard › ⋯ › Phone player › Play a link).
    func open(_ item: PlayerItem) {
        presented = item
    }

    func reopen() {
        guard let docked else { return }
        presented = docked
    }

    func close() {
        guard let docked else { return }
        self.docked = nil
        if presented?.session === docked.session { presented = nil }
        PhoneNowPlaying.shared.detach(docked.session)
        docked.session.close()
        releaseAudio()
    }

    private func releaseAudio() {
        if let audioOwner { CastSystemPlayback.shared.endLocalPlayback(audioOwner) }
        audioOwner = nil
    }
}

/// "Playing on this phone": the docked video, shown above the bottom of every screen.
struct PhonePlayerDockBar: View {
    @ObservedObject var dock: PhonePlayerDock
    @ObservedObject var session: PlaybackSession

    var body: some View {
        HStack(spacing: 10) {
            Button { dock.reopen() } label: {
                HStack(spacing: 10) {
                    Image(systemName: "play.rectangle.fill")
                        .font(.system(size: 20)).foregroundStyle(Theme.primary)
                        .frame(width: 36, height: 36)
                        .background(Theme.surfaceContainerHighest, in: RoundedRectangle(cornerRadius: 8))
                    VStack(alignment: .leading, spacing: 1) {
                        Text(session.title.isEmpty ? "Video" : session.title)
                            .font(Theme.font(size: 14, weight: .semibold)).foregroundStyle(Theme.onSurface).lineLimit(1)
                        Text("Playing on this phone · \(PhonePlayerChrome.clock(session.positionSeconds))")
                            .font(Theme.font(size: 12)).foregroundStyle(Theme.onSurfaceVariant).lineLimit(1)
                            .monospacedDigit()
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Open the phone player, \(session.title)")
            .accessibilityIdentifier("phone-player-dock-open")
            Button { session.isPlaying ? session.pause() : session.play() } label: {
                Image(systemName: session.isPlaying ? "pause.fill" : "play.fill").frame(width: 44, height: 44)
            }
            .accessibilityLabel(session.isPlaying ? "Pause" : "Play")
            Button { dock.close() } label: { Image(systemName: "xmark").frame(width: 44, height: 44) }
                .accessibilityLabel("Stop the phone player")
                .accessibilityIdentifier("phone-player-dock-close")
        }
        .font(.system(size: 17, weight: .semibold))
        .foregroundStyle(Theme.onSurface)
        .padding(.leading, 10).padding(.trailing, 4).padding(.vertical, 6)
        .background(Theme.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.outlineVariant, lineWidth: 1))
        .padding(.horizontal, 12).padding(.bottom, 6)
    }
}

/// Dashboard › ⋯ › Phone player with nothing docked: open a file, or play a link.
struct PhonePlayerLauncherSheet: View {
    let onOpenFiles: () -> Void
    let onPlay: (URL) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var link = ""

    private var url: URL? { PhonePlayerLauncher.url(from: link) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button { dismiss(); onOpenFiles() } label: {
                        Label("Open a file", systemImage: "folder")
                    }
                } footer: {
                    Text("Videos and audio saved on this phone.")
                }
                Section {
                    TextField("https://…", text: $link)
                        .keyboardType(.URL).textContentType(.URL)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .submitLabel(.go)
                        .onSubmit { play() }
                        .accessibilityIdentifier("phone-player-link")
                    Button("Play") { play() }.disabled(url == nil)
                } header: {
                    Text("Play a link")
                } footer: {
                    Text("A direct link to a video or stream (MP4, MKV, HLS…). Links that need a website's sign-in won't play here; open the page in the browser instead.")
                }
            }
            .navigationTitle("Phone player")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func play() {
        guard let url else { return }
        dismiss()
        onPlay(url)
    }
}

enum PhonePlayerLauncher {
    /// A typed or pasted web link; a missing scheme means https.
    static func url(from text: String) -> URL? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.contains(" ") else { return nil }
        let candidate = trimmed.contains("://") ? trimmed : "https://" + trimmed
        guard let url = URL(string: candidate), let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http", let host = url.host, !host.isEmpty else { return nil }
        return url
    }

    /// The title shown for a played link: its file name, else its host.
    static func title(for url: URL) -> String {
        let name = url.deletingPathExtension().lastPathComponent
        return name.isEmpty || name == "/" ? (url.host ?? "Link") : name
    }
}
