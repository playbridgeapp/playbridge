//
//  PlayBridge_TVApp.swift
//  PlayBridge TV
//
//  Created by Atul Mehla on 2026-04-18.
//

import SwiftUI

@main
struct PlayBridge_TVApp: App {
    @StateObject private var server = WebSocketServer()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(server)
                .onAppear {
                    server.start()
                }
        }
    }
}
