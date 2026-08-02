use regex::Regex;
use url::Url;

pub struct HlsPlaylistRewriter;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HlsResourceKind {
    /// A non-tag URI line: variant playlist or media segment.
    Media,
    /// A media resource carried in a URI attribute, such as EXT-X-MAP,
    /// EXT-X-PART, or an EXT-X-PRELOAD-HINT for a part.
    SegmentAttribute,
    /// A non-media URI attribute such as EXT-X-KEY, EXT-X-MEDIA, or a
    /// rendition report that points to another playlist.
    Attribute,
}

impl HlsPlaylistRewriter {
    /// Rewrites the URIs inside an HLS playlist (master or media) to point back to the local proxy.
    ///
    /// `content` is the raw string content of the playlist.
    /// `base_uri` is the effective URL of the playlist (after resolving any redirects).
    /// `rewrite_url` is a closure that takes a resolved absolute target URL and returns the local proxy URL.
    pub fn rewrite<F>(content: &str, base_uri: &Url, rewrite_url: F) -> String
    where
        F: Fn(&str) -> String,
    {
        Self::rewrite_with_context(content, base_uri, |url, _| rewrite_url(url))
    }

    /// Context-aware form used by the embedded proxy to distinguish playable
    /// segment lines from keys and other URI attributes.
    pub fn rewrite_with_context<F>(content: &str, base_uri: &Url, rewrite_url: F) -> String
    where
        F: Fn(&str, HlsResourceKind) -> String,
    {
        let lines = content.lines();
        let mut rewritten_lines = Vec::new();

        let uri_attr_regex = Regex::new(r#"(?i)(URI\s*=\s*")([^"]*)(")"#).expect("Valid regex");

        for line in lines {
            let trimmed = line.trim();
            if trimmed.is_empty() {
                rewritten_lines.push(line.to_string());
                continue;
            }

            if trimmed.starts_with('#') {
                // Make an explicitly default rendition selectable for strict
                // HLS clients before rewriting its URI.
                let normalized_line = normalize_default_rendition(line);
                let resource_kind = attribute_resource_kind(&normalized_line);
                if uri_attr_regex.is_match(&normalized_line) {
                    let rewritten_line =
                        uri_attr_regex.replace_all(&normalized_line, |caps: &regex::Captures| {
                            let prefix = &caps[1];
                            let relative_uri = &caps[2];
                            let suffix = &caps[3];

                            match base_uri.join(relative_uri) {
                                Ok(resolved) => {
                                    let rewritten = rewrite_url(resolved.as_str(), resource_kind);
                                    format!("{}{}{}", prefix, rewritten, suffix)
                                }
                                Err(_) => caps[0].to_string(),
                            }
                        });
                    rewritten_lines.push(rewritten_line.into_owned());
                } else {
                    rewritten_lines.push(normalized_line);
                }
            } else {
                // Segment or variant URI line
                match base_uri.join(trimmed) {
                    Ok(resolved) => {
                        let rewritten = rewrite_url(resolved.as_str(), HlsResourceKind::Media);
                        rewritten_lines.push(rewritten);
                    }
                    Err(_) => {
                        rewritten_lines.push(line.to_string());
                    }
                }
            }
        }

        rewritten_lines.join("\n")
    }
}

fn attribute_resource_kind(line: &str) -> HlsResourceKind {
    let upper = line.trim_start().to_ascii_uppercase();
    if upper.starts_with("#EXT-X-MAP:")
        || upper.starts_with("#EXT-X-PART:")
        || (upper.starts_with("#EXT-X-PRELOAD-HINT:") && upper.contains("TYPE=PART"))
    {
        HlsResourceKind::SegmentAttribute
    } else {
        HlsResourceKind::Attribute
    }
}

fn normalize_default_rendition(line: &str) -> String {
    let upper = line.to_ascii_uppercase();
    if upper.trim_start().starts_with("#EXT-X-MEDIA:")
        && upper.contains("DEFAULT=YES")
        && !upper.contains("AUTOSELECT=")
    {
        format!("{line},AUTOSELECT=YES")
    } else {
        line.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_hls_rewrite_relative_segments_and_keys() {
        let manifest = r#"#EXTM3U
#EXT-X-VERSION:3
#EXT-X-KEY:METHOD=AES-128,URI="enc.key"
#EXTINF:10.0,
segment1.ts
#EXTINF:10.0,
http://cdn.example.com/segment2.ts
"#;
        let base_url = Url::parse("https://stream.example.com/live/index.m3u8").unwrap();
        let rewritten = HlsPlaylistRewriter::rewrite(manifest, &base_url, |target| {
            format!(
                "http://127.0.0.1:8888/proxy?url={}",
                urlencoding::encode(target)
            )
        });

        assert!(rewritten.contains("URI=\"http://127.0.0.1:8888/proxy?url=https%3A%2F%2Fstream.example.com%2Flive%2Fenc.key\""));
        assert!(rewritten.contains(
            "http://127.0.0.1:8888/proxy?url=https%3A%2F%2Fstream.example.com%2Flive%2Fsegment1.ts"
        ));
        assert!(rewritten.contains(
            "http://127.0.0.1:8888/proxy?url=http%3A%2F%2Fcdn.example.com%2Fsegment2.ts"
        ));
    }

    #[test]
    fn rewrites_low_latency_hls_parts_hints_and_rendition_reports() {
        let manifest = r#"#EXTM3U
#EXT-X-PART:DURATION=0.333,URI="seg_12_part_1.m4s?session=test"
#EXT-X-PRELOAD-HINT:TYPE=PART,URI="seg_12_part_2.m4s?session=test"
#EXT-X-RENDITION-REPORT:URI="../audio/live?session=test",LAST-MSN=12,LAST-PART=1
"#;
        let base_url = Url::parse("https://cdn.example/video/live?session=test").unwrap();
        let rewritten = HlsPlaylistRewriter::rewrite(manifest, &base_url, |target| {
            format!(
                "http://phone.test/s/session/item?uri={}",
                urlencoding::encode(target)
            )
        });

        assert!(!rewritten.contains("URI=\"seg_"));
        assert!(!rewritten.contains("URI=\"../audio"));
        assert_eq!(
            rewritten
                .matches("http://phone.test/s/session/item?uri=")
                .count(),
            3
        );
        assert!(rewritten.contains("seg_12_part_1.m4s"));
        assert!(rewritten.contains("seg_12_part_2.m4s"));
        assert!(rewritten.contains("%2Faudio%2Flive"));
    }

    #[test]
    fn classifies_low_latency_media_attributes_separately_from_playlists() {
        let manifest = r#"#EXTM3U
#EXT-X-MAP:URI="init.jpg"
#EXT-X-PART:DURATION=0.333,URI="part.jpg"
#EXT-X-PRELOAD-HINT:TYPE=PART,URI="next.jpg"
#EXT-X-KEY:METHOD=AES-128,URI="enc.key"
#EXT-X-RENDITION-REPORT:URI="../audio/live"
"#;
        let base_url = Url::parse("https://cdn.example/video/live").unwrap();
        let segment_attributes = std::cell::Cell::new(0);
        let other_attributes = std::cell::Cell::new(0);

        HlsPlaylistRewriter::rewrite_with_context(manifest, &base_url, |target, kind| {
            match kind {
                HlsResourceKind::SegmentAttribute => {
                    segment_attributes.set(segment_attributes.get() + 1)
                }
                HlsResourceKind::Attribute => other_attributes.set(other_attributes.get() + 1),
                HlsResourceKind::Media => {}
            }
            target.to_owned()
        });

        assert_eq!(segment_attributes.get(), 3);
        assert_eq!(other_attributes.get(), 2);
    }

    #[test]
    fn makes_default_audio_rendition_explicitly_selectable() {
        let manifest = r#"#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="stereo",NAME="Japanese",DEFAULT=YES,URI="audio/playlist.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=1000000,AUDIO="stereo"
video/playlist.m3u8
"#;
        let base_url = Url::parse("https://stream.example.com/master.m3u8").unwrap();
        let rewritten = HlsPlaylistRewriter::rewrite(manifest, &base_url, str::to_owned);

        assert!(rewritten.contains("DEFAULT=YES"));
        assert!(rewritten.contains("AUTOSELECT=YES"));
        assert!(rewritten.contains("https://stream.example.com/audio/playlist.m3u8"));
    }
}
