import SwiftUI

/// The remote's timeline track: skip segments as soft bands, the unplayed and buffered
/// lines, the played wave easing into a round thumb, then chapter gaps cut through it.
/// Draws past its frame by `bleed` on every side so the held thumb's halo is not clipped.
struct RemoteTimelineTrack: View, Animatable {
    static let bleed = 18.0

    var fraction: Double
    var durationMs: Double
    var bufferedMs: Double?
    var chapters: [RemoteChapter]
    var segments: [RemoteSegment]
    /// Animated: grows while playing, flattens on pause, buffering and scrubbing.
    var amplitude: Double
    /// Animated: larger while the thumb is held.
    var thumbRadius: Double
    var held: Bool
    var shimmer: Bool
    /// Drives the drift clock; false removes the per-frame redraw entirely.
    var clockRuns: Bool
    var played: Color

    var animatableData: AnimatablePair<Double, Double> {
        get { AnimatablePair(amplitude, thumbRadius) }
        set { amplitude = newValue.first; thumbRadius = newValue.second }
    }

    var body: some View {
        GeometryReader { geometry in
            TimelineView(.animation(paused: !clockRuns)) { context in
                let phase = clockRuns
                    ? context.date.timeIntervalSinceReferenceDate
                        .truncatingRemainder(dividingBy: RemoteSeekStyle.drift) / RemoteSeekStyle.drift * 2 * .pi
                    : 0
                Canvas { graphics, size in
                    draw(in: &graphics, width: size.width - Self.bleed * 2, centerY: size.height / 2, phase: phase)
                }
            }
            .frame(width: geometry.size.width + Self.bleed * 2, height: geometry.size.height + Self.bleed * 2)
            .offset(x: -Self.bleed, y: -Self.bleed)
        }
    }

    private func draw(in graphics: inout GraphicsContext, width: Double, centerY: Double, phase: Double) {
        guard width > 0, durationMs > 0 else { return }
        graphics.translateBy(x: Self.bleed, y: 0)
        let thumbX = width * min(1, max(0, fraction))
        let wavelength = RemoteSeekStyle.wavelength
        func x(_ ms: Double) -> Double { min(1, max(0, ms / durationMs)) * width }
        func line(_ from: Double, _ to: Double, _ color: Color, _ lineWidth: Double) {
            var path = Path()
            path.move(to: CGPoint(x: from, y: centerY))
            path.addLine(to: CGPoint(x: to, y: centerY))
            graphics.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
        }

        for segment in segments {
            let from = x(Double(segment.startMs))
            let to = x(segment.endMs.map(Double.init) ?? durationMs)
            if to > from { line(from, to, Theme.tertiary.opacity(0.32), RemoteSeekStyle.segment) }
        }
        if thumbX < width {
            line(thumbX, width, Color.white.opacity(0.18), RemoteSeekStyle.track)
            if let bufferedMs, x(bufferedMs) > thumbX {
                line(thumbX, x(bufferedMs), Color.white.opacity(0.36), RemoteSeekStyle.track)
            }
        }
        if thumbX > 0 {
            var wave = Path()
            wave.move(to: CGPoint(x: 0, y: centerY))
            var px = 2.0
            while px < thumbX {
                let y = centerY + amplitude * RemoteSeekTimeline.waveEnvelope(x: px, thumbX: thumbX, wavelength: wavelength)
                    * sin(2 * .pi * px / wavelength - phase)
                wave.addLine(to: CGPoint(x: px, y: y))
                px += 2
            }
            wave.addLine(to: CGPoint(x: thumbX, y: centerY))
            let style = StrokeStyle(lineWidth: RemoteSeekStyle.stroke, lineCap: .round, lineJoin: .round)
            if shimmer {
                // A soft highlight sweeps the played line while the receiver buffers.
                let sweep = phase / (2 * .pi) * (thumbX + 2 * wavelength) - wavelength
                let gradient = Gradient(colors: [played.opacity(0.55), played, Color.white.opacity(0.9), played, played.opacity(0.55)])
                graphics.stroke(wave, with: .linearGradient(gradient,
                    startPoint: CGPoint(x: sweep - wavelength, y: centerY),
                    endPoint: CGPoint(x: sweep + wavelength, y: centerY)), style: style)
            } else {
                graphics.stroke(wave, with: .color(played), style: style)
            }
        }
        if held {
            let halo = thumbRadius * 1.8
            graphics.fill(Path(ellipseIn: CGRect(x: thumbX - halo, y: centerY - halo, width: halo * 2, height: halo * 2)),
                          with: .color(played.opacity(0.22)))
        }
        graphics.fill(Path(ellipseIn: CGRect(x: thumbX - thumbRadius, y: centerY - thumbRadius,
                                             width: thumbRadius * 2, height: thumbRadius * 2)), with: .color(played))

        let gap = RemoteSeekStyle.chapterGap
        graphics.blendMode = .clear
        for chapter in chapters where chapter.startMs > 0 {
            let cx = x(Double(chapter.startMs))
            // Gaps never cut the thumb, and the first chapter at 0 has nothing to separate.
            if abs(cx - thumbX) > thumbRadius + gap && cx < width - gap {
                graphics.fill(Path(CGRect(x: cx - gap / 2, y: 0, width: gap, height: centerY * 2)), with: .color(.black))
            }
        }
    }
}
