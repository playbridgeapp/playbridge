package com.playbridge.sender.data.library

import android.content.Context

object StreamingUtils {
    
    fun buildPlayUrl(
        addon: InstalledAddonEntity,
        type: String,
        id: String,
        context: Context,
        skip: Int = 0
    ): String {
        val template = addon.playEndpoint
        if (template.isBlank()) {
            // Fallback to standard stream resolution (this shouldn't happen if caller checks playEndpoint)
            return "" 
        }
        
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val quality = prefs.getString("default_video_quality", "") ?: ""
        val sourceTypes = prefs.getString("auto_stream_source_types", "") ?: ""
        val minSize = prefs.getString("auto_stream_min_gb", "") ?: ""
        val maxSize = prefs.getString("auto_stream_max_gb", "") ?: ""
        val minBitrate = prefs.getString("auto_stream_min_mbps", "") ?: ""
        val maxBitrate = prefs.getString("auto_stream_max_mbps", "") ?: ""
        val audioLang = prefs.getString("preferred_audio_lang", "") ?: ""

        val base = (addon.baseUrl + template)
            .replace("{type}", type)
            .replace("{id}", id)

        val queryParams = mutableListOf<String>()
        if (quality.isNotBlank() && quality != "Auto") queryParams.add("quality=$quality")
        if (sourceTypes.isNotBlank()) queryParams.add("sourceType=$sourceTypes")
        if (minSize.isNotBlank()) queryParams.add("minSize=$minSize")
        if (maxSize.isNotBlank()) queryParams.add("maxSize=$maxSize")
        if (minBitrate.isNotBlank()) queryParams.add("minBitrate=$minBitrate")
        if (maxBitrate.isNotBlank()) queryParams.add("maxBitrate=$maxBitrate")
        if (audioLang.isNotBlank()) queryParams.add("audioLang=$audioLang")
        if (skip > 0) queryParams.add("skip=$skip")

        return if (queryParams.isEmpty()) base else "$base?${queryParams.joinToString("&")}"
    }
}
