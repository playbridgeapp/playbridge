import SwiftUI

/// Lists the streams detected on the current page; tap one to open the preview/cast sheet.
struct DetectedStreamsSheet: View {
    let videos: [DetectedVideo]
    let onSelect: (DetectedVideo) -> Void
    @Environment(\.dismiss) private var dismiss

    private var streams: [DetectedVideo] { videos.filter { !$0.isSubtitle } }
    private var subtitleCount: Int { videos.filter { $0.isSubtitle }.count }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 10) {
                    ForEach(streams) { video in
                        Button { onSelect(video) } label: { row(video) }
                            .buttonStyle(.plain)
                    }
                    if subtitleCount > 0 {
                        Text("\(subtitleCount) subtitle track\(subtitleCount == 1 ? "" : "s") available — attach in preview")
                            .font(.caption).foregroundColor(Theme.onSurfaceVariant)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.top, 4)
                    }
                }
                .padding(16)
            }
            .background(Theme.surface.ignoresSafeArea())
            .navigationTitle("Detected streams")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Close") { dismiss() } } }
        }
    }

    private func row(_ video: DetectedVideo) -> some View {
        HStack(spacing: 12) {
            Text(video.kind.badge)
                .font(.caption2.bold())
                .foregroundColor(Theme.onPrimary)
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(Theme.primaryDim)
                .cornerRadius(6)
            VStack(alignment: .leading, spacing: 2) {
                Text(video.displayTitle).foregroundColor(Theme.onSurface).font(.subheadline).lineLimit(1)
                Text(video.host).foregroundColor(Theme.onSurfaceVariant).font(.caption).lineLimit(1)
            }
            Spacer()
            Image(systemName: "chevron.right").foregroundColor(Theme.onSurfaceVariant)
        }
        .padding(12)
        .background(Theme.surfaceContainer)
        .cornerRadius(12)
    }
}
