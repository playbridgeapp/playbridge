use std::collections::HashMap;

use m3u8_rs::Playlist;
use url::Url;

use crate::Result;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PlaylistFacts {
    pub is_master: bool,
    pub is_live: bool,
    pub duration_ms: Option<u64>,
}

pub fn inspect(body: &[u8]) -> Option<PlaylistFacts> {
    let playlist = m3u8_rs::parse_playlist_res(body).ok()?;
    Some(match playlist {
        Playlist::MasterPlaylist(_) => PlaylistFacts {
            is_master: true,
            is_live: false,
            duration_ms: None,
        },
        Playlist::MediaPlaylist(media) => {
            let is_live = !media.end_list;
            let duration_ms = (!is_live).then(|| {
                (media
                    .segments
                    .iter()
                    .map(|segment| segment.duration as f64)
                    .sum::<f64>()
                    * 1000.0)
                    .round() as u64
            });
            PlaylistFacts {
                is_master: false,
                is_live,
                duration_ms,
            }
        }
    })
}

/// Rewrites media lines and quoted `URI` attributes through a caller-provided
/// registrar. The registrar can store inherited headers and return an opaque,
/// tokenized local proxy URL.
pub fn rewrite_urls(
    body: &str,
    base_url: &Url,
    mut register: impl FnMut(Url) -> String,
) -> Result<String> {
    let mut cache = HashMap::<String, String>::new();
    let mut proxify = |reference: &str| -> String {
        if let Some(saved) = cache.get(reference) {
            return saved.clone();
        }
        let rewritten = base_url
            .join(reference)
            .map(&mut register)
            .unwrap_or_else(|_| reference.to_owned());
        cache.insert(reference.to_owned(), rewritten.clone());
        rewritten
    };

    let mut output = Vec::new();
    for raw in body.lines() {
        let line = raw.trim_end_matches('\r');
        if line.starts_with('#') {
            output.push(rewrite_uri_attributes(line, &mut proxify));
        } else if line.trim().is_empty() {
            output.push(line.to_owned());
        } else {
            output.push(proxify(line.trim()));
        }
    }
    Ok(output.join("\n"))
}

fn rewrite_uri_attributes(line: &str, proxify: &mut impl FnMut(&str) -> String) -> String {
    let mut remaining = line;
    let mut output = String::new();
    while let Some(start) = remaining.find("URI=\"") {
        let value_start = start + 5;
        output.push_str(&remaining[..value_start]);
        let tail = &remaining[value_start..];
        let Some(end) = tail.find('"') else {
            output.push_str(tail);
            return output;
        };
        output.push_str(&proxify(&tail[..end]));
        output.push('"');
        remaining = &tail[end + 1..];
    }
    output.push_str(remaining);
    output
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_vod_duration_using_m3u8_parser() {
        let body = b"#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\na.ts\n#EXTINF:4.5,Chapter\nb.ts\n#EXT-X-ENDLIST\n";
        assert_eq!(
            inspect(body),
            Some(PlaylistFacts {
                is_master: false,
                is_live: false,
                duration_ms: Some(14_500),
            })
        );
    }

    #[test]
    fn detects_live_media_playlist() {
        let body = b"#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n";
        assert!(inspect(body).unwrap().is_live);
        assert_eq!(inspect(body).unwrap().duration_ms, None);
    }

    #[test]
    fn rewrites_segments_keys_and_maps() {
        let body = "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"\n#EXT-X-MAP:URI=\"init.mp4\"\nseg.ts";
        let base = Url::parse("https://example.test/path/index.m3u8").unwrap();
        let rewritten = rewrite_urls(body, &base, |url| {
            format!("http://192.0.2.2/proxy/{}", url.path().replace('/', "_"))
        })
        .unwrap();
        assert!(rewritten.contains("URI=\"http://192.0.2.2/proxy/_path_key.bin\""));
        assert!(rewritten.contains("URI=\"http://192.0.2.2/proxy/_path_init.mp4\""));
        assert!(rewritten.ends_with("http://192.0.2.2/proxy/_path_seg.ts"));
    }
}
