import SwiftUI
import Combine

// MARK: - Countdown Ring

private struct CountdownRing: View {
    let total: Int
    let remaining: Int

    private var progress: Double {
        guard total > 0 else { return 0 }
        return Double(remaining) / Double(total)
    }

    var body: some View {
        ZStack {
            // Track
            Circle()
                .stroke(Color.white.opacity(0.15), lineWidth: 4)

            // Progress arc
            Circle()
                .trim(from: 0, to: progress)
                .stroke(
                    AngularGradient(
                        gradient: Gradient(colors: [Color.white.opacity(0.3), Color.white]),
                        center: .center,
                        startAngle: .degrees(-90),
                        endAngle: .degrees(270)
                    ),
                    style: StrokeStyle(lineWidth: 4, lineCap: .round)
                )
                .rotationEffect(.degrees(-90))
                .animation(.linear(duration: 1), value: progress)

            // Number
            Text("\(remaining)")
                .font(.system(size: 40, weight: .semibold, design: .rounded))
                .foregroundColor(.white)
                .contentTransition(.numericText())
        }
        .frame(width: 72, height: 72)
    }
}

// MARK: - Metadata Chip

private struct MetaChip: View {
    let label: String
    var accent: Color = .white

    var body: some View {
        Text(label)
            .font(.system(size: 26, weight: .semibold))
            .foregroundColor(accent)
            .padding(.horizontal, 14)
            .padding(.vertical, 6)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(accent.opacity(0.25), lineWidth: 1)
            )
    }
}

// MARK: - PrePlayView

struct PrePlayView: View {
    let metadata: Playbridge_VisualMetadata
    let onStart: () -> Void
    let onBack: () -> Void

    private let totalCountdown = 7
    private let maxLoadWait: UInt64 = 3_000_000_000  // 3 seconds

    @State private var isAnimating = false
    @State private var isReady = false
    @State private var backdropLoaded = false
    @State private var posterLoaded = false
    @State private var countdown = 7
    @State private var timerTask: Task<Void, Never>? = nil
    @State private var readinessTask: Task<Void, Never>? = nil

    /// True as soon as all expected images are loaded (or URLs are absent).
    private var imagesReady: Bool {
        let needsBackdrop = metadata.hasBackdropURL || metadata.hasPosterURL
        let needsPoster   = metadata.hasPosterURL
        let backdropOk    = !needsBackdrop || backdropLoaded
        let posterOk      = !needsPoster   || posterLoaded
        return backdropOk && posterOk
    }

    var body: some View {
        ZStack {
            // Black base — visible while images load
            Color.black.ignoresSafeArea()

            // ── Layer 1: Full-bleed backdrop ──────────────────────────────
            backdrop

            // ── Layer 2: Cinematic gradient scrim ─────────────────────────
            if isReady { scrim }

            // ── Layer 3: Main content ─────────────────────────────────────
            if isReady {
                HStack(alignment: .center, spacing: 0) {
                    contentColumn
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .opacity(isAnimating ? 1 : 0)
                        .offset(x: isAnimating ? 0 : -60)

                    posterColumn
                        .opacity(isAnimating ? 1 : 0)
                        .offset(x: isAnimating ? 0 : 60)
                }
            }
        }
        .ignoresSafeArea()
        .onAppear {
            // Fallback: reveal after maxLoadWait regardless of image state
            readinessTask = Task {
                try? await Task.sleep(nanoseconds: maxLoadWait)
                if !Task.isCancelled {
                    await MainActor.run { markReady() }
                }
            }
        }
        .onChange(of: imagesReady) { _, ready in
            if ready { markReady() }
        }
        .onDisappear {
            timerTask?.cancel()
            timerTask = nil
            readinessTask?.cancel()
            readinessTask = nil
        }
        .onExitCommand {
            onBack()
        }
    }

    private func markReady() {
        guard !isReady else { return }
        isReady = true
        readinessTask?.cancel()
        withAnimation(.spring(response: 0.9, dampingFraction: 0.8)) {
            isAnimating = true
        }
        startCountdown()
    }

    // MARK: - Backdrop

    @ViewBuilder
    private var backdrop: some View {
        GeometryReader { proxy in
            // Sharp, full-resolution backdrop as on Android TV; the scrim keeps text legible.
            let urls = metadata.hasBackdropURL ? ArtworkURL.candidates(metadata.backdropURL, kind: .backdrop)
                : (metadata.hasPosterURL ? ArtworkURL.candidates(metadata.posterURL, kind: .poster) : [])
            ArtworkImage(urls: urls, onResult: { _ in backdropLoaded = true }) { image in
                image
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: proxy.size.width, height: proxy.size.height)
                    .clipped()
            } placeholder: {
                Color.black
            }
        }
        .ignoresSafeArea()
    }

    // MARK: - Scrim

    /// Android TV's scrim: one left-to-right fade (98% → clear at ~73% width),
    /// no top/bottom vignettes, so the backdrop reads as clearly as on Android.
    private var scrim: some View {
        LinearGradient(
            stops: [
                .init(color: .black.opacity(0.98), location: 0.0),
                .init(color: .black.opacity(0.90), location: 0.243),
                .init(color: .black.opacity(0.60), location: 0.486),
                .init(color: .clear, location: 0.729)
            ],
            startPoint: .leading,
            endPoint: .trailing
        )
        .ignoresSafeArea()
    }

    // MARK: - Left Content Column

    private var contentColumn: some View {
        VStack(alignment: .leading, spacing: 0) {
            Spacer()

            // Logo or Title
            if metadata.hasLogoURL, !ArtworkURL.candidates(metadata.logoURL, kind: .logo).isEmpty {
                ArtworkImage(urls: ArtworkURL.candidates(metadata.logoURL, kind: .logo)) { image in
                    image
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(maxWidth: 520, maxHeight: 140)
                        .shadow(color: .black.opacity(0.6), radius: 16, x: 0, y: 8)
                } placeholder: {
                    titleText
                }
            } else {
                titleText
            }

            Spacer().frame(height: 24)

            // Metadata chips row
            metaChipsRow

            Spacer().frame(height: 20)

            // Episode info (series only)
            episodeInfo

            Spacer().frame(height: 22)

            // Overview
            if metadata.hasOverview {
                Text(metadata.overview)
                    .font(.system(size: 28))
                    .foregroundStyle(.white.opacity(0.75))
                    .lineLimit(5)
                    .lineSpacing(6)
                    .frame(maxWidth: 620, alignment: .leading)
            }

            Spacer()

            // Bottom: countdown
            countdownRow

            Spacer().frame(height: 70)
        }
        .padding(.leading, 90)
        .padding(.trailing, 40)
    }

    private var titleText: some View {
        Text(metadata.title)
            .font(.system(size: 104, weight: .black, design: .default))
            .foregroundStyle(.white)
            .shadow(color: .black.opacity(0.5), radius: 12, x: 0, y: 6)
            .lineLimit(2)
            .minimumScaleFactor(0.6)
    }

    @ViewBuilder
    private var metaChipsRow: some View {
        HStack(spacing: 10) {
            if metadata.hasYear {
                MetaChip(label: metadata.year)
            }
            if metadata.hasRating {
                MetaChip(label: "IMDb \(metadata.rating)", accent: Color(hue: 0.13, saturation: 0.9, brightness: 0.95))
            }
            if metadata.hasRuntime {
                MetaChip(label: metadata.runtime)
            }
        }
    }

    @ViewBuilder
    private var episodeInfo: some View {
        if metadata.hasSeason && metadata.hasEpisode {
            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 10) {
                    Text("S\(metadata.season)")
                        .font(.system(size: 25, weight: .bold))
                        .foregroundStyle(.white.opacity(0.5))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 6))

                    Text("E\(metadata.episode)")
                        .font(.system(size: 25, weight: .bold))
                        .foregroundStyle(.white.opacity(0.5))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 6))

                    if metadata.hasEpisodeTitle {
                        Text("·")
                            .foregroundStyle(.white.opacity(0.3))
                        Text(metadata.episodeTitle)
                            .font(.system(size: 30, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.9))
                            .lineLimit(1)
                    }
                }
            }
        }
    }

    private var countdownRow: some View {
        HStack(spacing: 20) {
            CountdownRing(total: totalCountdown, remaining: countdown)

            Text("Playing in \(countdown)s")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .background(.ultraThinMaterial.opacity(0.6), in: RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(Color.white.opacity(0.12), lineWidth: 1)
        )
    }

    // MARK: - Right Poster Column

    @ViewBuilder
    private var posterColumn: some View {
        if metadata.hasPosterURL {
            ArtworkImage(urls: ArtworkURL.candidates(metadata.posterURL, kind: .poster),
                         onResult: { _ in posterLoaded = true }) { image in
                image
                    .resizable()
                    .aspectRatio(2 / 3, contentMode: .fill)
                    .frame(width: 360, height: 540)
                    .clipShape(RoundedRectangle(cornerRadius: 20))
                    .shadow(color: .black.opacity(0.7), radius: 40, x: -10, y: 20)
                    .overlay(
                        RoundedRectangle(cornerRadius: 20)
                            .stroke(Color.white.opacity(0.08), lineWidth: 1)
                    )
            } placeholder: {
                RoundedRectangle(cornerRadius: 20)
                    .fill(Color.white.opacity(0.05))
                    .frame(width: 360, height: 540)
            }
            .padding(.trailing, 90)
        }
    }

    // MARK: - Timer

    private func startCountdown() {
        timerTask = Task {
            while countdown > 0 {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
                await MainActor.run {
                    countdown -= 1
                }
            }
            if !Task.isCancelled {
                await MainActor.run { onStart() }
            }
        }
    }
}
