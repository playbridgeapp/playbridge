import PlayBridgeProtocol
import SwiftUI

struct PrePlayScreen: View {
    let payload: ContentPlayPayload
    let onStreamSelected: (ScoredStremioStream) -> Void

    @State private var streams: [ScoredStremioStream] = []
    @State private var isLoading = true
    @State private var countdown = 3
    @State private var autoPickActive = false

    var body: some View {
        ZStack {
            // Background Image
            if let backdrop = payload.backdropUrl, let url = URL(string: backdrop) {
                AsyncImage(url: url) { image in
                    image.resizable().aspectRatio(contentMode: .fill)
                } placeholder: {
                    Color.black
                }
                .overlay(Color.black.opacity(0.7))
                .ignoresSafeArea()
            } else {
                Color.black.ignoresSafeArea()
            }

            HStack(spacing: 60) {
                // Poster
                if let poster = payload.posterUrl, let url = URL(string: poster) {
                    AsyncImage(url: url) { image in
                        image.resizable().aspectRatio(contentMode: .fit)
                    } placeholder: {
                        Color.gray.opacity(0.3)
                    }
                    .frame(width: 400)
                    .cornerRadius(20)
                    .shadow(radius: 20)
                }

                // Metadata & Streams
                VStack(alignment: .leading, spacing: 20) {
                    Text(payload.title)
                        .font(.system(size: 70, weight: .bold))

                    HStack(spacing: 20) {
                        if let year = payload.year { Text(year) }
                        if let rating = payload.rating { Text("★ \(rating)") }
                        if let runtime = payload.runtime { Text(runtime) }
                    }
                    .font(.headline)
                    .foregroundColor(.secondary)

                    Text(payload.overview ?? "")
                        .font(.body)
                        .foregroundColor(.secondary)
                        .lineLimit(5)
                        .frame(maxWidth: 800, alignment: .leading)

                    Text("Available Sources")
                        .font(.title2)
                        .padding(.top, 20)

                    if isLoading {
                        ProgressView().scaleEffect(2)
                    } else if streams.isEmpty {
                        Text("No streams found.").foregroundColor(.red)
                    } else {
                        ScrollView {
                            VStack(spacing: 15) {
                                ForEach(streams) { stream in
                                    Button(action: { onStreamSelected(stream) }) {
                                        HStack {
                                            VStack(alignment: .leading) {
                                                Text(stream.addonName ?? "Unknown Addon")
                                                    .font(.caption)
                                                    .foregroundColor(.blue)
                                                Text(stream.name ?? stream.title ?? "Stream")
                                                    .font(.body)
                                            }
                                            Spacer()
                                            if stream.isTargetTier {
                                                Image(systemName: "star.fill").foregroundColor(
                                                    .yellow)
                                            }
                                        }
                                        .padding()
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .background(Color.white.opacity(0.1))
                                        .cornerRadius(10)
                                    }
                                    .buttonStyle(.card)
                                }
                            }
                        }
                    }
                }
            }
            .padding(80)

            // Auto-pick Overlay
            if autoPickActive && countdown > 0 {
                VStack {
                    Spacer()
                    HStack {
                        Text("Auto-playing best match in \(countdown)...")
                        Button("Cancel") {
                            autoPickActive = false
                        }
                        .buttonStyle(.bordered)
                    }
                    .padding()
                    .background(Color.black.opacity(0.8))
                    .cornerRadius(15)
                    .padding(.bottom, 40)
                }
            }
        }
        .task {
            await resolveStreams()
        }
    }

    private func resolveStreams() async {
        do {
            let resolved = try await StremioClient.shared.resolveStreamsByContentId(
                addonBaseUrls: payload.addonBaseUrls,
                addonNames: payload.addonNames,
                contentId: payload.contentId,
                contentType: payload.contentType,
                season: payload.season,
                episode: payload.episode,
                qualityPreference: payload.defaultVideoQuality,
                preferredAddonBaseUrl: payload.preferredAddonBaseUrl,
                preferredAddonName: payload.preferredAddonName
            )
            self.streams = resolved
            self.isLoading = false

            // Start auto-pick if enabled
            if payload.forcePicker != true, let first = streams.first {
                autoPickActive = true
                startCountdown(for: first)
            }
        } catch {
            print("Failed to resolve streams: \(error)")
            self.isLoading = false
        }
    }

    private func startCountdown(for stream: ScoredStremioStream) {
        Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { timer in
            if !autoPickActive {
                timer.invalidate()
                return
            }

            if countdown > 1 {
                countdown -= 1
            } else {
                timer.invalidate()
                onStreamSelected(stream)
            }
        }
    }
}
