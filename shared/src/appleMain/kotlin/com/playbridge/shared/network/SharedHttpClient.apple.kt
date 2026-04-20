package com.playbridge.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun createHttpClient(): HttpClient = HttpClient(Darwin) {
    engine {
        configureSession {
            timeoutIntervalForRequest = 15.0
            timeoutIntervalForResource = 15.0
        }
    }
}
