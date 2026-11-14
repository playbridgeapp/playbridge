import SwiftUI

struct PrePlayView: View {
    let metadata: Playbridge_VisualMetadata
    let senderName: String?
    let savedPositionMs: Int64?
    let durationMs: Int64?
    let upNextTitle: String?
    let upNextSubtitle: String?
    let onStart: (Int64?) -> Void
    let onBack: () -> Void

    private let colors = TvColors.dark
    private let totalCountdown: Double = 5.0
    private let maxLoadWait: UInt64 = 3_000_000_000

    @State private var isReady = false
    @State private var backdropLoaded = false
    @State private var elapsed: Double = 0
    @State private var countdownPaused = false
    @State private var timerTask: Task<Void, Never>?
    @State private var readinessTask: Task<Void, Never>?

    @FocusState private var focusedButton: ResumeAction?

    private enum ResumeAction: Hashable {
        case resume
        case startOver
    }

    private var resumeInfo: (fraction: Double, stoppedText: String, buttonTitle: String)? {
        HomeFormatting.prePlayResumeInfo(positionMs: savedPositionMs, durationMs: durationMs)
    }

    private var imagesReady: Bool {
        let needsBackdrop = metadata.hasBackdropURL || metadata.hasPosterURL
        return !needsBackdrop || backdropLoaded
    }

    private var secondsRemaining: Int {
        max(0, Int(ceil(totalCountdown - elapsed)))
    }

    private var autoProgress: Double {
        min(1.0, max(0.0, elapsed / totalCountdown))
    }

    var body: some View {
        ZStack {
            colors.surface.ignoresSafeArea()

            backdrop

            if isReady {
                scrim

                ZStack(alignment: .topLeading) {
                    if let senderName, !senderName.isEmpty {
                        HStack(spacing: TvScale.pt(8)) {
                            Image(systemName: "iphone")
                                .font(.system(size: TvScale.pt(14), weight: .semibold))
                            Text("From \(senderName)")
                                .font(.system(size: TvScale.pt(16), weight: .medium))
                        }
                        .foregroundColor(colors.onAccentContainer)
                        .padding(.horizontal, TvScale.pt(16))
                        .frame(height: TvScale.pt(36))
                        .background(colors.accentContainer)
                        .clipShape(Capsule())
                        .padding(.leading, TvSpacing.screenGutter)
                        .padding(.top, TvScale.pt(40))
                    }

                    VStack(alignment: .leading, spacing: 0) {
                        Spacer()
                        metadataColumn
                        Spacer()
                        HStack(alignment: .bottom) {
                            bottomActionArea
                            Spacer()
                            if let upNextTitle {
                                upNextCard(title: upNextTitle, subtitle: upNextSubtitle)
                            }
                        }
                    }
                    .padding(.horizontal, TvSpacing.screenGutter)
                    .padding(.bottom, TvScale.pt(48))
                }
            }
        }
        .ignoresSafeArea()
        .onAppear {
            if imagesReady {
                markReady()
            } else {
                readinessTask = Task {
                    try? await Task.sleep(nanoseconds: maxLoadWait)
                    if !Task.isCancelled {
                        await MainActor.run { markReady() }
                    }
                }
            }
        }
        .onChange(of: imagesReady) { _, ready in
            if ready { markReady() }
        }
        .onChange(of: focusedButton) { _, newFocus in
            if newFocus == .startOver {
                countdownPaused = true
            }
        }
        .onDisappear {
            timerTask?.cancel()
            timerTask = nil
            readinessTask?.cancel()
            readinessTask = nil
        }
        .onExitCommand(perform: onBack)
    }

    private func markReady() {
        guard !isReady else { return }
        isReady = true
        readinessTask?.cancel()
        if resumeInfo != nil {
            focusedButton = .resume
        }
        startCountdown()
    }

    @ViewBuilder
    private var backdrop: some View {
        GeometryReader { proxy in
            let urls = metadata.hasBackdropURL ? ArtworkURL.candidates(metadata.backdropURL, kind: .backdrop)
                : (metadata.hasPosterURL ? ArtworkURL.candidates(metadata.posterURL, kind: .poster) : [])
            ArtworkImage(urls: urls, onResult: { _ in backdropLoaded = true }) { image in
                image
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: proxy.size.width, height: proxy.size.height)
                    .clipped()
            } placeholder: {
                colors.container
            }
        }
        .ignoresSafeArea()
    }

    private var scrim: some View {
        LinearGradient(
            stops: [
                .init(color: colors.surface.opacity(0.95), location: 0.0),
                .init(color: colors.surface.opacity(0.92), location: 0.52),
                .init(color: colors.surface.opacity(0.60), location: 0.65),
                .init(color: colors.surface.opacity(0.25), location: 0.80),
                .init(color: .clear, location: 1.0),
            ],
            startPoint: .leading,
            endPoint: .trailing
        )
        .ignoresSafeArea()
    }

    private var metadataColumn: some View {
        VStack(alignment: .leading, spacing: TvScale.pt(18)) {
            VStack(alignment: .leading, spacing: TvScale.pt(8)) {
                if metadata.hasSeason && metadata.hasEpisode {
                    Text("Season \(metadata.season) · Episode \(metadata.episode)")
                        .font(.system(size: TvScale.pt(20), weight: .medium))
                        .foregroundColor(colors.accent)
                }

                Text(metadata.title)
                    .font(.system(size: TvScale.pt(64), weight: .bold))
                    .foregroundColor(colors.onSurface)
                    .lineLimit(2)
                    .minimumScaleFactor(0.7)

                if metadata.hasEpisodeTitle {
                    Text(metadata.episodeTitle)
                        .font(.system(size: TvScale.pt(26), weight: .semibold))
                        .foregroundColor(colors.onSurface)
                        .lineLimit(1)
                }
            }

            HStack(spacing: TvScale.pt(10)) {
                if metadata.hasYear {
                    chip(metadata.year)
                }
                if metadata.hasRuntime {
                    chip(metadata.runtime)
                }
                if metadata.hasRating {
                    chip("IMDb \(metadata.rating)")
                }
            }

            if metadata.hasOverview {
                Text(metadata.overview)
                    .font(.system(size: TvScale.pt(19)))
                    .foregroundColor(colors.onSurface)
                    .lineSpacing(TvScale.pt(6))
                    .lineLimit(4)
                    .frame(maxWidth: TvScale.pt(540), alignment: .leading)
            }
        }
        .frame(maxWidth: TvScale.pt(600), alignment: .leading)
    }

    private func chip(_ label: String) -> some View {
        Text(label)
            .font(.system(size: TvScale.pt(17), weight: .medium))
            .foregroundColor(colors.onSurface)
            .padding(.horizontal, TvScale.pt(14))
            .frame(height: TvSize.chip)
            .background(colors.containerHigh)
            .clipShape(Capsule())
    }

    @ViewBuilder
    private var bottomActionArea: some View {
        if let info = resumeInfo {
            VStack(alignment: .leading, spacing: TvScale.pt(16)) {
                VStack(alignment: .leading, spacing: TvScale.pt(8)) {
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(colors.bright)
                            Capsule()
                                .fill(colors.accent)
                                .frame(width: geo.size.width * info.fraction)
                        }
                    }
                    .frame(width: TvScale.pt(420), height: TvScale.pt(6))

                    Text(info.stoppedText)
                        .font(TvText.caption)
                        .foregroundColor(colors.onSurfaceMuted)
                }

                HStack(spacing: TvScale.pt(14)) {
                    Button(action: { onStart(nil) }) {
                        ZStack(alignment: .leading) {
                            if !countdownPaused {
                                GeometryReader { geo in
                                    colors.accent
                                        .frame(width: geo.size.width * autoProgress)
                                }
                            }
                            HStack(spacing: TvScale.pt(12)) {
                                Image(systemName: "play.fill")
                                    .font(.system(size: TvScale.pt(20)))
                                Text(info.buttonTitle)
                                    .font(.system(size: TvScale.pt(20), weight: .semibold))
                            }
                            .foregroundColor(focusedButton == .resume ? colors.onFocusFill : colors.onSurface)
                            .padding(.horizontal, TvScale.pt(28))
                        }
                        .frame(height: TvScale.pt(64))
                        .fixedSize(horizontal: true, vertical: false)
                        .background(focusedButton == .resume ? colors.onSurface : colors.containerHigh)
                        .clipShape(Capsule())
                        .overlay(
                            Capsule()
                                .stroke(focusedButton == .resume ? colors.focusRing : Color.clear, lineWidth: TvSize.focusRingWidth)
                                .padding(-TvSize.focusRingGap)
                        )
                    }
                    .buttonStyle(TvNoChromeButtonStyle())
                    .focusEffectDisabled()
                    .focused($focusedButton, equals: .resume)
                    .appRemoteAction { onStart(nil) }

                    Button(action: { onStart(0) }) {
                        HStack(spacing: TvScale.pt(10)) {
                            Image(systemName: "arrow.counterclockwise")
                                .font(.system(size: TvScale.pt(20), weight: .semibold))
                            Text("Start over")
                                .font(.system(size: TvScale.pt(20), weight: .medium))
                        }
                        .foregroundColor(focusedButton == .startOver ? colors.onFocusFill : colors.onSurface)
                        .padding(.horizontal, TvScale.pt(26))
                        .frame(height: TvScale.pt(64))
                        .background(focusedButton == .startOver ? colors.focusFill : colors.containerHigh)
                        .clipShape(Capsule())
                        .overlay(
                            Capsule()
                                .stroke(focusedButton == .startOver ? colors.focusRing : Color.clear, lineWidth: TvSize.focusRingWidth)
                                .padding(-TvSize.focusRingGap)
                        )
                    }
                    .buttonStyle(TvNoChromeButtonStyle())
                    .focusEffectDisabled()
                    .focused($focusedButton, equals: .startOver)
                    .appRemoteAction { onStart(0) }
                }

                Text(
                    countdownPaused
                        ? "Auto-resume paused"
                        : "Resuming in \(secondsRemaining) \(secondsRemaining == 1 ? "second" : "seconds"). Press OK to go now."
                )
                .font(TvText.caption)
                .foregroundColor(colors.onSurfaceMuted)
            }
        } else {
            VStack(alignment: .leading, spacing: TvScale.pt(14)) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Getting ready…")
                        .font(.system(size: TvScale.pt(22), weight: .semibold))
                        .foregroundColor(colors.onSurface)
                    Spacer()
                    Text("Starts by itself")
                        .font(TvText.caption)
                        .foregroundColor(colors.onSurfaceMuted)
                }
                .frame(width: TvScale.pt(600))

                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Capsule().fill(colors.bright)
                        Capsule()
                            .fill(colors.accent)
                            .frame(width: geo.size.width * autoProgress)
                    }
                }
                .frame(width: TvScale.pt(600), height: TvScale.pt(8))
            }
            .contentShape(Rectangle())
            .focusable()
            .appRemoteAction { onStart(nil) }
        }
    }

    private func upNextCard(title: String, subtitle: String?) -> some View {
        HStack(spacing: TvScale.pt(16)) {
            RoundedRectangle(cornerRadius: TvScale.pt(10))
                .fill(colors.bright)
                .frame(width: TvScale.pt(128), height: TvScale.pt(72))
                .overlay(
                    Image(systemName: "play.rectangle.fill")
                        .font(.system(size: TvScale.pt(24)))
                        .foregroundColor(colors.onSurfaceMuted)
                )

            VStack(alignment: .leading, spacing: TvScale.pt(2)) {
                Text(subtitle ?? "Up next")
                    .font(TvText.small)
                    .foregroundColor(colors.onSurfaceMuted)
                    .lineLimit(1)
                Text(title)
                    .font(.system(size: TvScale.pt(19), weight: .semibold))
                    .foregroundColor(colors.onSurface)
                    .lineLimit(1)
            }
        }
        .padding(TvScale.pt(16))
        .frame(width: TvScale.pt(360), alignment: .leading)
        .background(colors.container)
        .clipShape(RoundedRectangle(cornerRadius: TvScale.pt(18)))
    }

    private func startCountdown() {
        timerTask = Task {
            let step: Double = 0.2
            while elapsed < totalCountdown {
                try? await Task.sleep(nanoseconds: UInt64(step * 1_000_000_000))
                if Task.isCancelled { return }
                await MainActor.run {
                    if !countdownPaused {
                        elapsed += step
                    }
                }
            }
            if !Task.isCancelled {
                await MainActor.run {
                    if !countdownPaused {
                        onStart(nil)
                    }
                }
            }
        }
    }
}
