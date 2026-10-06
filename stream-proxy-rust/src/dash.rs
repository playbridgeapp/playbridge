use roxmltree::{Document, Node};
use url::Url;

pub struct DashManifestRewriter;

#[derive(Debug)]
struct ProgressiveRepresentation {
    url: String,
    bandwidth: u64,
    preferred_codec: bool,
}

impl DashManifestRewriter {
    /// Resolves DASH media references against [base_uri] and maps every HTTP(S)
    /// request through [rewrite_url]. Keeping absolute URLs behind the proxy is
    /// important: browsers otherwise bypass captured Origin/Referer headers and
    /// can fail CORS or upstream origin checks.
    pub fn rewrite<F>(content: &str, base_uri: &Url, rewrite_url: F) -> String
    where
        F: Fn(&str) -> String,
    {
        let Ok(document) = Document::parse(content) else {
            return String::new();
        };
        let mut edits = Vec::new();
        for node in document.descendants().filter(|node| node.is_element()) {
            if node.has_tag_name("BaseURL") || node.has_tag_name("Location") {
                let Some(raw) = node.text() else {
                    continue;
                };
                let parent = node.parent_element().unwrap_or(node);
                let base = effective_base(parent, base_uri, false);
                let Ok(resolved) = base.join(raw.trim()) else {
                    continue;
                };
                if !matches!(resolved.scheme(), "http" | "https") {
                    continue;
                }
                if node.has_tag_name("BaseURL")
                    && resolved.path().ends_with('/')
                    && resolved.query().is_none()
                {
                    // Resolve child references against this directory, but never grant
                    // the client an unrestricted directory/prefix capability.
                    edits.push((node.range(), String::new()));
                } else if let Some(text) = node.children().find(|child| child.is_text()) {
                    edits.push((
                        text.range(),
                        encode_xml_url(&rewrite_url(resolved.as_str())),
                    ));
                }
            }
            let base = effective_base(node, base_uri, true);
            for attribute in node.attributes().filter(|attribute| {
                matches!(
                    attribute.name(),
                    "media" | "initialization" | "sourceURL" | "location" | "baseUrl"
                )
            }) {
                if let Ok(resolved) = base.join(attribute.value()) {
                    if matches!(resolved.scheme(), "http" | "https") {
                        edits.push((
                            attribute.range_value(),
                            encode_xml_url(&rewrite_url(resolved.as_str())),
                        ));
                    }
                }
            }
        }
        edits.sort_by_key(|(range, _)| std::cmp::Reverse(range.start));
        let mut result = content.to_string();
        let mut boundary = content.len();
        for (range, replacement) in edits {
            if range.end <= boundary {
                result.replace_range(range.clone(), &replacement);
                boundary = range.start;
            }
        }
        result
    }

    /// Converts a progressive/BaseURL DASH manifest into an mpv EDL containing
    /// one compatible video stream and one audio stream. This is used by
    /// Desktop because the bundled FFmpeg does not include the DASH demuxer,
    /// while mpv can still combine separate MP4 tracks through EDL.
    pub fn mpv_edl<F>(content: &str, base_uri: &Url, rewrite_url: F) -> Result<String, String>
    where
        F: Fn(&str) -> String,
    {
        let document =
            Document::parse(content).map_err(|error| format!("invalid DASH XML: {error}"))?;
        let mut audio = Vec::new();
        let mut video = Vec::new();

        for representation in document
            .descendants()
            .filter(|node| node.has_tag_name("Representation"))
        {
            let Some(base_url) = representation
                .children()
                .find(|node| node.has_tag_name("BaseURL"))
                .and_then(|node| node.text())
            else {
                continue;
            };
            let decoded = decode_xml_url(base_url.trim());
            let resolved = Url::parse(&decoded)
                .or_else(|_| base_uri.join(&decoded))
                .map_err(|error| format!("invalid representation URL: {error}"))?;
            if !matches!(resolved.scheme(), "http" | "https") {
                continue;
            }

            let mime_type = inherited_attribute(representation, "mimeType")
                .or_else(|| inherited_attribute(representation, "contentType"))
                .unwrap_or_default()
                .to_ascii_lowercase();
            let codecs = inherited_attribute(representation, "codecs")
                .unwrap_or_default()
                .to_ascii_lowercase();
            let bandwidth = representation
                .attribute("bandwidth")
                .and_then(|value| value.parse().ok())
                .unwrap_or_default();
            let candidate = ProgressiveRepresentation {
                url: rewrite_url(resolved.as_str()),
                bandwidth,
                preferred_codec: false,
            };

            if mime_type.contains("audio") {
                audio.push(ProgressiveRepresentation {
                    preferred_codec: codecs.starts_with("mp4a"),
                    ..candidate
                });
            } else if mime_type.contains("video") {
                video.push(ProgressiveRepresentation {
                    preferred_codec: codecs.starts_with("avc1") || codecs.starts_with("avc3"),
                    ..candidate
                });
            }
        }

        let video = best_representation(video);
        let audio = best_representation(audio);
        if video.is_none() && audio.is_none() {
            return Err(
                "DASH manifest has no progressive BaseURL audio/video representations".into(),
            );
        }

        let mut edl = String::from("# mpv EDL v0\n");
        if let Some(video) = video {
            edl.push_str("!new_stream\n");
            edl.push_str(&edl_value(&video.url));
            edl.push('\n');
        }
        if let Some(audio) = audio {
            edl.push_str("!new_stream\n");
            edl.push_str(&edl_value(&audio.url));
            edl.push('\n');
        }
        Ok(edl)
    }
}

fn effective_base(node: Node<'_, '_>, base_uri: &Url, include_self: bool) -> Url {
    let mut ancestors: Vec<_> = node
        .ancestors()
        .filter(|ancestor| ancestor.is_element())
        .collect();
    if !include_self {
        ancestors.remove(0);
    }
    let mut base = base_uri.clone();
    for ancestor in ancestors.into_iter().rev() {
        if let Some(raw) = ancestor
            .children()
            .find(|child| child.has_tag_name("BaseURL"))
            .and_then(|child| child.text())
        {
            if let Ok(resolved) = base.join(raw.trim()) {
                base = resolved;
            }
        }
    }
    base
}

fn inherited_attribute<'a>(node: Node<'a, 'a>, attribute: &str) -> Option<&'a str> {
    node.ancestors()
        .find_map(|ancestor| ancestor.attribute(attribute))
}

fn best_representation(
    candidates: Vec<ProgressiveRepresentation>,
) -> Option<ProgressiveRepresentation> {
    candidates
        .into_iter()
        .max_by_key(|candidate| (candidate.preferred_codec, candidate.bandwidth))
}

fn edl_value(value: &str) -> String {
    format!("%{}%{}", value.len(), value)
}

fn decode_xml_url(value: &str) -> String {
    value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
}

fn encode_xml_url(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rewrites_root_relative_base_urls_through_the_session_proxy() {
        let xml = r#"<MPD><BaseURL>/companion/videoplayback?a=1&amp;b=2</BaseURL></MPD>"#;
        let base =
            Url::parse("https://receiver.example/companion/api/manifest/dash/id/video").unwrap();
        let rewritten = DashManifestRewriter::rewrite(xml, &base, |target| {
            format!("/s/session/media?uri={}", urlencoding::encode(target))
        });

        assert!(rewritten.contains(
            "/s/session/media?uri=https%3A%2F%2Freceiver.example%2Fcompanion%2Fvideoplayback%3Fa%3D1%26b%3D2"
        ));
        assert!(!rewritten.contains("_root_"));
    }

    #[test]
    fn rewrites_absolute_and_template_urls_without_hiding_dash_placeholders() {
        let xml = r#"<MPD><SegmentTemplate initialization="https://cdn.example/init-$RepresentationID$.mp4" media="chunks/$Number$.m4s"/></MPD>"#;
        let base = Url::parse("https://origin.example/path/manifest.mpd").unwrap();
        let rewritten = DashManifestRewriter::rewrite(xml, &base, |target| {
            format!(
                "/s/session/item?uri={}",
                urlencoding::encode(target).replace("%24", "$")
            )
        });

        assert!(rewritten.contains("init-$RepresentationID$.mp4"));
        assert!(rewritten.contains("chunks%2F$Number$.m4s"));
        assert!(!rewritten.contains("https://cdn.example"));
    }

    #[test]
    fn creates_mpv_edl_with_best_compatible_progressive_tracks() {
        let xml = r#"
            <MPD>
              <Period>
                <AdaptationSet mimeType="audio/mp4">
                  <Representation bandwidth="128000" codecs="mp4a.40.2">
                    <BaseURL>/audio.m4a?token=a&amp;b=c</BaseURL>
                  </Representation>
                </AdaptationSet>
                <AdaptationSet mimeType="video/mp4">
                  <Representation bandwidth="9000000" codecs="av01.0.08M.08">
                    <BaseURL>/video-av1.mp4</BaseURL>
                  </Representation>
                  <Representation bandwidth="4000000" codecs="avc1.64002a">
                    <BaseURL>/video-h264.mp4</BaseURL>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        "#;
        let base = Url::parse("https://origin.example/manifest.mpd").unwrap();
        let edl = DashManifestRewriter::mpv_edl(xml, &base, |target| {
            format!(
                "http://proxy.test/fetch?uri={}",
                urlencoding::encode(target)
            )
        })
        .unwrap();

        assert!(edl.starts_with("# mpv EDL v0\n!new_stream\n"));
        assert!(edl.contains("video-h264.mp4"));
        assert!(!edl.contains("video-av1.mp4"));
        assert!(edl.contains("audio.m4a"));
        assert_eq!(edl.matches("!new_stream").count(), 2);
    }
}
