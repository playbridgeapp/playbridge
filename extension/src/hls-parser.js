class HlsParser {
    /**
     * Parses the given M3U8 URL and returns a comprehensive HlsPlaylist object.
     * @param {string} masterPlaylistUrl 
     * @returns {Promise<Object>}
     */
    static async parsePlaylist(masterPlaylistUrl) {
        try {
            const response = await fetch(masterPlaylistUrl);
            const content = await response.text();
            
            if (!content.startsWith("#EXTM3U")) {
                return { videoQualities: [], audioTracks: [], masterPlaylistUrl, segmentPrefixes: [] };
            }

            const videoQualities = [];
            const audioTracks = [];
            const segmentPrefixes = new Set();
            
            const lines = content.split('\n');
            let currentBandwidth = null;
            let currentAverageBandwidth = null;
            let currentResolution = null;
            let currentCodecs = null;
            let currentAudioGroup = null;
            let currentFrameRate = null;
            
            for (let i = 0; i < lines.length; i++) {
                const line = lines[i].trim();
                
                if (line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO")) {
                    const attributes = line.substring(line.indexOf(":") + 1);
                    
                    const groupIdMatch = attributes.match(/GROUP-ID="([^"]+)"/);
                    const nameMatch = attributes.match(/NAME="([^"]+)"/);
                    const languageMatch = attributes.match(/LANGUAGE="([^"]+)"/);
                    const uriMatch = attributes.match(/URI="([^"]+)"/);
                    const isDefault = /DEFAULT=YES/.test(attributes);
                    const autoselect = /AUTOSELECT=YES/.test(attributes);
                    const channelsMatch = attributes.match(/CHANNELS="([^"]+)"/);
                    
                    if (groupIdMatch && nameMatch) {
                        const groupId = groupIdMatch[1];
                        const name = nameMatch[1];
                        let resolvedUri = null;
                        if (uriMatch) {
                            try {
                                resolvedUri = new URL(uriMatch[1], masterPlaylistUrl).toString();
                            } catch (e) {}
                        }
                        
                        audioTracks.push({
                            groupId,
                            name,
                            language: languageMatch ? languageMatch[1] : null,
                            uri: resolvedUri,
                            isDefault,
                            autoselect,
                            channels: channelsMatch ? channelsMatch[1] : null
                        });
                    }
                } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    const attributes = line.substring(line.indexOf(":") + 1);
                    
                    const bwMatch = attributes.match(/BANDWIDTH=(\d+)/);
                    if (bwMatch) currentBandwidth = parseInt(bwMatch[1], 10);
                    
                    const avgBwMatch = attributes.match(/AVERAGE-BANDWIDTH=(\d+)/);
                    if (avgBwMatch) currentAverageBandwidth = parseInt(avgBwMatch[1], 10);
                    
                    const resMatch = attributes.match(/RESOLUTION=(\d+x\d+)/);
                    if (resMatch) currentResolution = resMatch[1];
                    
                    const codecsMatch = attributes.match(/CODECS="([^"]+)"/);
                    if (codecsMatch) currentCodecs = codecsMatch[1];
                    
                    const audioMatch = attributes.match(/AUDIO="([^"]+)"/);
                    if (audioMatch) currentAudioGroup = audioMatch[1];
                    
                    const frameRateMatch = attributes.match(/FRAME-RATE=([\d\.]+)/);
                    if (frameRateMatch) currentFrameRate = frameRateMatch[1];
                    
                } else if (!line.startsWith("#") && line.length > 0) {
                    if (currentBandwidth !== null) {
                        try {
                            const variantUrl = new URL(line, masterPlaylistUrl).toString();
                            
                            let resolutionLabel = "Unknown";
                            if (currentResolution) {
                                resolutionLabel = currentResolution.split("x")[1] + "p";
                            }
                            
                            videoQualities.push({
                                resolution: resolutionLabel,
                                bandwidth: currentBandwidth,
                                averageBandwidth: currentAverageBandwidth,
                                url: variantUrl,
                                codecs: currentCodecs,
                                audioGroupId: currentAudioGroup,
                                frameRate: currentFrameRate
                            });
                        } catch (e) {}
                        
                        currentBandwidth = null;
                        currentAverageBandwidth = null;
                        currentResolution = null;
                        currentCodecs = null;
                        currentAudioGroup = null;
                        currentFrameRate = null;
                    } else {
                        try {
                            const segmentUrl = new URL(line, masterPlaylistUrl).toString();
                            const prefix = segmentUrl.substring(0, segmentUrl.lastIndexOf("/") + 1);
                            if (prefix.startsWith("http")) {
                                segmentPrefixes.add(prefix);
                            }
                        } catch (e) {}
                    }
                }
            }
            
            // Sort by bandwidth descending
            videoQualities.sort((a, b) => b.bandwidth - a.bandwidth);
            
            return {
                videoQualities,
                audioTracks,
                masterPlaylistUrl,
                segmentPrefixes: Array.from(segmentPrefixes)
            };
            
        } catch (e) {
            console.error("[HlsParser] error:", e);
            return { videoQualities: [], audioTracks: [], masterPlaylistUrl, segmentPrefixes: [] };
        }
    }
}

// Support for importScripts in Chrome MV3 Service Worker or export if somehow used in module mode
if (typeof self !== 'undefined') {
    self.HlsParser = HlsParser;
}
if (typeof module !== 'undefined' && module.exports) {
    module.exports = HlsParser;
}
