//
//  ContentView.swift
//  PlayBridge TV
//
//  Created by Atul Mehla on 2026-04-18.
//

import PlayBridgeProtocol
import SwiftUI

struct ContentView: View {
    @EnvironmentObject var server: WebSocketServer
    @State private var coordinator: ServerCoordinator?

    var body: some View {
        Group {
            if let coordinator = coordinator {
                switch coordinator.route {
                case .home:
                    HomeView(server: server)
                case .prePlay(let payload):
                    PrePlayScreen(payload: payload) { stream in
                        coordinator.selectStream(stream, from: payload)
                    }
                case .player:
                    PlayerScreen(viewModel: server.playerViewModel)
                        .onExitCommand {
                            coordinator.exitPlayer()
                        }
                }
            } else {
                ProgressView("Initializing...")
            }
        }
        .onAppear {
            if coordinator == nil {
                coordinator = ServerCoordinator(server: server)
            }
        }
    }
}

struct HomeView: View {
    @ObservedObject var server: WebSocketServer

    var body: some View {
        VStack(spacing: 20) {
            Image(
                systemName: server.isRunning
                    ? "antenna.radiowaves.left.and.right"
                    : "antenna.radiowaves.left.and.right.slash"
            )
            .imageScale(.large)
            .foregroundStyle(server.isRunning ? .green : .red)
            .font(.system(size: 60))

            Text("PlayBridge TV")
                .font(.title)

            Text("Server Status: \(server.connectionState.description)")
                .font(.headline)

            Text("Address: \(server.localIP):8765")
                .font(.subheadline)
                .foregroundColor(.secondary)

            if server.connectionCount == 0 {
                VStack(spacing: 10) {
                    Text("Pairing PIN")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(server.pairingPin)
                        .font(.system(size: 80, weight: .bold, design: .monospaced))
                        .padding()
                        .background(Color.gray.opacity(0.1))
                        .cornerRadius(12)
                }
                .padding(.vertical)
            }

            Text("Active Connections: \(server.connectionCount)")
                .font(.subheadline)

            Button(action: {
                let testPayload = PlayPayload(
                    url:
                        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
                    title: "Big Buck Bunny (Engine Test)",
                    headers: nil as [String: String]?,
                    contentType: nil as String?,
                    subtitles: nil as [String]?,
                    playerMode: "internal"
                )
                server.playVideo(payload: testPayload)
            }) {
                Text("Play Test Video (Auto Engine)")
                    .font(.caption)
            }
            .padding(.top)

            if !server.lastMessage.isEmpty {
                VStack(alignment: .leading) {
                    Text("Last Message:")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(server.lastMessage)
                        .padding()
                        .background(Color.gray.opacity(0.2))
                        .cornerRadius(8)
                }
                .padding(.top)
            }
        }
        .padding()
    }
}

#Preview {
    ContentView()
}
