import SwiftUI

/// Phone-as-remote controls. Three modes mirroring the Android remote: transport (player
/// `control` commands), D-pad (`remote` keys), and a touchpad (binary mouse packets).
struct RemoteControlView: View {
    @EnvironmentObject private var vm: ConnectionViewModel
    @State private var mode: Mode = .transport

    enum Mode: String, CaseIterable, Identifiable {
        case transport = "Transport"
        case dpad = "D-Pad"
        case touchpad = "Touchpad"
        var id: String { rawValue }
    }

    var body: some View {
        VStack(spacing: 16) {
            Picker("Mode", selection: $mode) {
                ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)

            switch mode {
            case .transport: transport
            case .dpad: dpad
            case .touchpad: touchpad
            }
        }
    }

    // MARK: - Transport

    private var transport: some View {
        HStack(spacing: 14) {
            controlButton("gobackward.10") { vm.control("seek_back") }
            controlButton("play.fill") { vm.control("play") }
            controlButton("pause.fill") { vm.control("pause") }
            controlButton("stop.fill") { vm.control("stop") }
            controlButton("goforward.10") { vm.control("seek_forward") }
        }
    }

    private func controlButton(_ systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title2)
                .frame(width: 56, height: 56)
                .foregroundColor(Theme.onSurface)
                .background(Theme.surfaceContainerHigh)
                .clipShape(Circle())
        }
    }

    // MARK: - D-Pad

    private var dpad: some View {
        VStack(spacing: 12) {
            dpadButton("chevron.up") { vm.remote("dpad_up") }
            HStack(spacing: 12) {
                dpadButton("chevron.left") { vm.remote("dpad_left") }
                Button { vm.remote("dpad_center") } label: {
                    Text("OK").font(.headline).foregroundColor(Theme.onPrimary)
                        .frame(width: 72, height: 72)
                        .background(Theme.ctaGradient)
                        .clipShape(Circle())
                }
                dpadButton("chevron.right") { vm.remote("dpad_right") }
            }
            dpadButton("chevron.down") { vm.remote("dpad_down") }
            Button { vm.remote("back") } label: {
                Label("Back", systemImage: "arrow.uturn.backward")
                    .foregroundColor(Theme.onSurface)
                    .padding(.horizontal, 20).padding(.vertical, 10)
                    .background(Theme.surfaceContainerHigh)
                    .cornerRadius(12)
            }
            .padding(.top, 4)
        }
    }

    private func dpadButton(_ systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title2)
                .frame(width: 72, height: 72)
                .foregroundColor(Theme.onSurface)
                .background(Theme.surfaceContainerHigh)
                .clipShape(Circle())
        }
    }

    // MARK: - Touchpad

    @State private var lastDrag: CGSize = .zero
    @State private var moved: CGFloat = 0

    private var touchpad: some View {
        VStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 16)
                    .fill(Theme.surfaceContainerLow)
                Text("Drag to move · tap to click")
                    .font(.footnote)
                    .foregroundColor(Theme.onSurfaceVariant)
            }
            .frame(height: 240)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let dx = Float(value.translation.width - lastDrag.width)
                        let dy = Float(value.translation.height - lastDrag.height)
                        vm.mouse(event: "move", dx: dx, dy: dy)
                        lastDrag = value.translation
                        moved += CGFloat(abs(dx) + abs(dy))
                    }
                    .onEnded { _ in
                        if moved < 8 { vm.mouse(event: "click") }
                        lastDrag = .zero
                        moved = 0
                    }
            )
            Button { vm.mouse(event: "click") } label: {
                Text("Click")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .foregroundColor(Theme.onSurface)
                    .background(Theme.surfaceContainerHigh)
                    .cornerRadius(12)
            }
        }
    }
}
