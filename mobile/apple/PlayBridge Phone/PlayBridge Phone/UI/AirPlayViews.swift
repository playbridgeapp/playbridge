import SwiftUI
import AVKit
import MediaPlayer

struct AirPlayRoutePicker: UIViewRepresentable {
    let controller: AirPlayController
    func makeCoordinator() -> Coordinator { Coordinator(controller) }
    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.prioritizesVideoDevices = true
        picker.tintColor = UIColor(Theme.onSurface)
        picker.activeTintColor = UIColor(Theme.primary)
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
    final class Coordinator: NSObject, AVRoutePickerViewDelegate {
        let controller: AirPlayController
        init(_ controller: AirPlayController) { self.controller = controller }
        func routePickerViewWillBeginPresentingRoutes(_ routePickerView: AVRoutePickerView) { controller.beginRouteSelection() }
        func routePickerViewDidEndPresentingRoutes(_ routePickerView: AVRoutePickerView) { controller.endRouteSelection() }
    }
}

struct AirPlayDestinationRow: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "airplayvideo").font(Theme.font(.title2)).foregroundStyle(Theme.primary)
            VStack(alignment: .leading, spacing: 3) {
                Text(vm.isAirPlay ? vm.airPlay.routeName : "AirPlay").font(Theme.font(.headline))
                Text(vm.isAirPlay ? (vm.airPlay.routeAvailable ? "Selected · Change output" : "Disconnected · Choose a device") : "Choose a TV or speaker")
                    .font(Theme.font(.caption)).foregroundStyle(Theme.onSurfaceVariant)
            }
            Spacer(minLength: 0)
            AirPlayRoutePicker(controller: vm.airPlay).frame(width: 48, height: 48)
                .accessibilityLabel("Choose AirPlay device")
        }
        .padding(12)
        .background(Theme.surfaceContainer, in: RoundedRectangle(cornerRadius: 14))
    }
}

/// Keep a rendering layer attached across screens so the same player can route video.
struct AirPlayPlayerHost: UIViewRepresentable {
    let player: AVPlayer
    final class Host: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
    }
    func makeUIView(context: Context) -> Host {
        let view = Host()
        (view.layer as? AVPlayerLayer)?.player = player
        return view
    }
    func updateUIView(_ view: Host, context: Context) { (view.layer as? AVPlayerLayer)?.player = player }
}

struct AirPlayVolumeControl: UIViewRepresentable {
    func makeUIView(context: Context) -> MPVolumeView {
        let view = MPVolumeView()
        view.showsRouteButton = false
        return view
    }
    func updateUIView(_ uiView: MPVolumeView, context: Context) {}
}

struct AirPlayQueueSheet: View {
    @ObservedObject var controller: AirPlayController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if let current = controller.current {
                    Section("Now playing") { Text(current.title).fontWeight(.semibold) }
                }
                Section("Up next") {
                    if controller.upcoming.isEmpty { Text("Nothing queued").foregroundStyle(.secondary) }
                    ForEach(controller.upcoming) { entry in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(entry.title)
                            if !entry.subtitles.isEmpty {
                                Text("\(entry.subtitles.count) subtitle tracks").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { indexes in
                        let ids = indexes.compactMap { controller.upcoming.indices.contains($0) ? controller.upcoming[$0].id : nil }
                        ids.forEach(controller.remove)
                    }
                    .onMove { controller.move(from: $0, to: $1) }
                }
                if !controller.upcoming.isEmpty { Button("Clear queue", role: .destructive) { controller.clearQueue() } }
            }
            .navigationTitle("Queue").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .primaryAction) { EditButton() }
            }
        }
    }
}
