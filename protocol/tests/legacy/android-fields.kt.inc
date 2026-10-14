    private fun emitCapabilities(json: JsonObject) {
        val players = parseStringArray(json, "players")
        val browsers = parseStringArray(json, "browsers")
        val mediaKinds = parseStringArray(json, "mediaKinds")
            .filter { it in setOf("video", "audio", "image") }
        val screenMirrorWebRtc = json["screenMirrorWebRtc"]?.jsonPrimitive?.contentOrNull == "true"
        val features = parseStringArray(json, "features").toSet()
        val capabilities = TvCapabilities(players, browsers, mediaKinds, screenMirrorWebRtc, features)
        // Always replace the previous receiver's capabilities. Otherwise connecting to an
        // older TV after a capable one can leave screen mirroring incorrectly enabled.
        _tvCapabilitiesState.value = capabilities
        scope.launch { _tvCapabilities.emit(capabilities) }
    }

    private fun parseStringArray(json: JsonObject, key: String): List<String> =
        (json[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

