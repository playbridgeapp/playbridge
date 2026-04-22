import SwiftUI

struct PairingView: View {
    @EnvironmentObject var server: WebSocketServer

    var body: some View {
        VStack(spacing: 60) {
            VStack(spacing: 10) {
                Text(server.serverState.uppercased()).font(.system(size: 20, weight: .black))
                    .foregroundColor(Theme.accent)
                Text(server.deviceName).font(.system(size: 70, weight: .black)).foregroundColor(
                    .white)
            }

            HStack(spacing: 25) {
                ForEach(Array(server.pairingPin.enumerated()), id: \.offset) { index, char in
                    Text(String(char))
                        .font(.system(size: 100, weight: .bold, design: .monospaced))
                        .foregroundColor(.white)
                        .frame(width: 120, height: 160)
                        .background(RoundedRectangle(cornerRadius: 24).fill(.ultraThinMaterial))
                        .overlay(
                            RoundedRectangle(cornerRadius: 24).stroke(
                                Color.white.opacity(0.2), lineWidth: 1))
                }
            }

            Text("\(server.localIP):8765").font(.system(size: 32, design: .monospaced))
                .foregroundColor(.white.opacity(0.6))
        }
    }
}
