package com.playbridge.sender.data.nuvio

internal class PlayNuvioScraperEngine : NuvioScraperEngine {
    override val canExecute: Boolean = false

    override suspend fun getStreams(request: NuvioEvalRequest): NuvioEngineOutcome {
        return NuvioEngineOutcome(
            streams = emptyList(),
            warnings = listOf("${request.scraperName}: Native plugins are unavailable in Google Play builds.")
        )
    }

    override suspend fun getSettingsSchema(request: NuvioEvalRequest): String? = null
}
