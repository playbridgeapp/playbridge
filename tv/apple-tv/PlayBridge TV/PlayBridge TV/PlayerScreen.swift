//
//  PlayerScreen.swift
//  PlayBridge TV
//

import AVFoundation
import SwiftUI

struct PlayerScreen: View {
    let viewModel: PlayerViewModel
    var onExit: (() -> Void)? = nil
    @State private var showSettings = false
    @State private var showOverlay = true
    @State private var overlayTimer: Timer?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            // Video surface — AVPlayerLayer gives us pure rendering with no built-in
            // gesture handling, so all remote events flow to the SwiftUI ZStack.
            if let engine = viewModel.engine as? AVPlayerEngine {
                AVPlayerLayerRepresentable(player: engine.player)
                    .ignoresSafeArea()
            } else if let engine = viewModel.engine as? VLCPlayerEngine {
                VLCPlayerView(mediaPlayer: engine.mediaPlayer)
                    .ignoresSafeArea()
            }

            // Subtitles
            SubtitleOverlay(cues: viewModel.subtitleManager.activeCues)
                .ignoresSafeArea()

            // Buffering spinner
            if viewModel.state == .buffering || viewModel.state == .loading {
                ProgressView()
                    .progressViewStyle(CircularProgressViewStyle(tint: .white))
                    .scaleEffect(2)
            }

            // Transparent focus sink — always present so tvOS keeps delivering remote
            // events (exit, play/pause, move) to this view even when the overlay is hidden.
            Color.clear
                .focusable()
                .ignoresSafeArea()

            // Custom controls overlay
            if showOverlay && !showSettings {
                PlayerOverlayView(viewModel: viewModel) {
                    showSettings = true
                }
                .transition(.opacity)
            }

            // Settings panel
            if showSettings {
                Color.black.opacity(0.5)
                    .ignoresSafeArea()
                PlayerControlView(viewModel: viewModel)
            }
        }
        // D-pad left/right — unified for both engines
        .onMoveCommand { direction in
            resetOverlayTimer()
            switch direction {
            case .left:  viewModel.skipBackward()
            case .right: viewModel.skipForward()
            case .up:    withAnimation { showOverlay = true }
            default:     break
            }
        }
        .onPlayPauseCommand {
            resetOverlayTimer()
            viewModel.state == .playing ? viewModel.pause() : viewModel.play()
        }
        .onExitCommand {
            if showSettings {
                showSettings = false
                resetOverlayTimer()
            } else {
                onExit?()
            }
        }
        .onLongPressGesture {
            showSettings.toggle()
        }
        .onTapGesture {
            if !showOverlay {
                resetOverlayTimer()          // shows overlay via timer reset
            } else if !showSettings {
                viewModel.state == .playing ? viewModel.pause() : viewModel.play()
                resetOverlayTimer()
            }
        }
        .task { resetOverlayTimer() }
    }

    private func resetOverlayTimer() {
        withAnimation { showOverlay = true }
        overlayTimer?.invalidate()
        overlayTimer = Timer.scheduledTimer(withTimeInterval: 5.0, repeats: false) { _ in
            withAnimation {
                if !showSettings && viewModel.state == .playing {
                    showOverlay = false
                }
            }
        }
    }
}

// MARK: - Subtitle Overlay

struct SubtitleOverlay: View {
    let cues: [SubtitleCue]

    var body: some View {
        GeometryReader { geo in
            VStack(spacing: 4) {
                Spacer()
                ForEach(cues, id: \.startTime) { cue in
                    Text(cue.text)
                        .font(.system(size: 46, weight: .medium))
                        .foregroundColor(.white)
                        .multilineTextAlignment(.center)
                        .shadow(color: .black.opacity(0.85), radius: 3, x: 1, y: 1)
                        .padding(.horizontal, 60)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            .padding(.bottom, geo.size.height * 0.12)
        }
    }
}

// MARK: - AVPlayer surface

/// Renders AVPlayer video via a bare AVPlayerLayer embedded in a plain UIView.
/// Unlike AVPlayerViewController, this has no built-in gesture recognisers or
/// responder-chain involvement, so all remote/keyboard events reach the SwiftUI ZStack.
struct AVPlayerLayerRepresentable: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> AVPlayerLayerView {
        let view = AVPlayerLayerView()
        view.playerLayer.player = player
        view.playerLayer.videoGravity = .resizeAspect
        view.backgroundColor = .black
        view.isUserInteractionEnabled = false
        return view
    }

    func updateUIView(_ view: AVPlayerLayerView, context: Context) {
        view.playerLayer.player = player
    }
}

final class AVPlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}
