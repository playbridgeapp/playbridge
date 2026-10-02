package com.playbridge.sender.data.nuvio

internal data class NuvioEvalRequest(
    val scraperName: String,
    val scraperCode: String,
    val tmdbId: String,
    val nuvioType: String,
    val season: Int?,
    val episode: Int?,
    val settingsJson: String,
    val fetch: suspend (String) -> String,
)

internal data class NuvioEngineOutcome(
    val streams: List<NuvioStreamResult> = emptyList(),
    val warnings: List<String> = emptyList(),
)

internal interface NuvioScraperEngine {
    val canExecute: Boolean

    suspend fun getStreams(request: NuvioEvalRequest): NuvioEngineOutcome

    suspend fun getSettingsSchema(request: NuvioEvalRequest): String?
}
