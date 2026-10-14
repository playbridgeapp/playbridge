    private func handleAuthResponse(_ json: [String: Any]) {
        let serverName = target?.serverName ?? ""
        let success = (json["success"] as? Bool) ?? false
        if success {
            emitCapabilities(json)
            setState(.connected(serverName: serverName, secure: isSecure))
            // SEC-005: legacy echoed tokens in auth_response are ignored.
            startPing()
        } else {
            // Stale token — wipe and re-pair. Flag before close so teardown doesn't overwrite it.
            isUserDisconnect = true
            setState(.authFailed)
            task?.cancel(with: .normalClosure, reason: nil)
        }
    }

    private func emitCapabilities(_ json: [String: Any]) {
        let players = (json["players"] as? [String]) ?? []
        let browsers = (json["browsers"] as? [String]) ?? []
        let mediaKinds = (json["mediaKinds"] as? [String]) ?? []
        let features = (json["features"] as? [String]) ?? []
        if !players.isEmpty || !browsers.isEmpty || !mediaKinds.isEmpty || !features.isEmpty {
            onCapabilities?(TvCapabilities(players: players, browsers: browsers,
                                           mediaKinds: mediaKinds, features: features))
        }
    }

