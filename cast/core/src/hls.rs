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

const INSPECTION_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(3);
const MAX_PLAYLIST_BYTES: usize = 512 * 1024;
const MAX_PLAYLIST_FETCHES: usize = 3;

/// Best-effort, bounded inspection for Cast loads without an explicit stream type.
/// Follow the first playable master variant using the final response URL as its base.
/// Failures deliberately return no facts and never expose authenticated URLs.
pub async fn inspect_url(url: &str) -> Option<PlaylistFacts> {
    tokio::time::timeout(INSPECTION_TIMEOUT, inspect_url_inner(url))
        .await
        .ok()
        .flatten()
}

async fn inspect_url_inner(url: &str) -> Option<PlaylistFacts> {
    let client = reqwest::Client::builder()
        .timeout(INSPECTION_TIMEOUT)
        .build()
        .ok()?;
    let mut url = Url::parse(url).ok()?;
    for _ in 0..MAX_PLAYLIST_FETCHES {
        let mut response = client.get(url).send().await.ok()?.error_for_status().ok()?;
        let base = response.url().clone();
        if response
            .content_length()
            .is_some_and(|length| length > MAX_PLAYLIST_BYTES as u64)
        {
            return None;
        }
        let mut body = Vec::new();
        while let Some(chunk) = response.chunk().await.ok()? {
            if chunk.len() > MAX_PLAYLIST_BYTES - body.len() {
                return None;
            }
            body.extend_from_slice(&chunk);
        }
        let facts = inspect(&body)?;
        if !facts.is_master {
            return Some(facts);
        }
        let Playlist::MasterPlaylist(master) = m3u8_rs::parse_playlist_res(&body).ok()? else {
            return None;
        };
        let variant = master.variants.iter().find(|variant| !variant.is_i_frame)?;
        url = base.join(&variant.uri).ok()?;
    }
    None
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

    #[tokio::test]
    async fn inspects_first_master_variant_relative_to_redirected_master() {
        use wiremock::{
            Mock, MockServer, ResponseTemplate,
            matchers::{method, path},
        };
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/master.m3u8"))
            .respond_with(
                ResponseTemplate::new(302).insert_header("Location", "/nested/master.m3u8"),
            )
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("GET")).and(path("/nested/master.m3u8"))
            .respond_with(ResponseTemplate::new(200).set_body_string("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nfirst.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nsecond.m3u8\n"))
            .expect(1).mount(&server).await;
        Mock::given(method("GET")).and(path("/nested/first.m3u8"))
            .respond_with(ResponseTemplate::new(200).set_body_string("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\na.ts\n#EXTINF:4.5,\nb.ts\n#EXT-X-ENDLIST\n"))
            .expect(1).mount(&server).await;
        assert_eq!(
            inspect_url(&format!("{}/master.m3u8", server.uri())).await,
            Some(PlaylistFacts {
                is_master: false,
                is_live: false,
                duration_ms: Some(14_500),
            })
        );
        assert_eq!(server.received_requests().await.unwrap().len(), 3);
        server.verify().await;
    }

    #[tokio::test]
    async fn inspection_rejects_oversized_and_invalid_playlists() {
        use wiremock::{
            Mock, MockServer, ResponseTemplate,
            matchers::{method, path},
        };
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/large.m3u8"))
            .respond_with(
                ResponseTemplate::new(200).set_body_bytes(vec![b' '; MAX_PLAYLIST_BYTES + 1]),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/invalid.m3u8"))
            .respond_with(ResponseTemplate::new(200).set_body_string("<html>upstream error</html>"))
            .mount(&server)
            .await;
        assert_eq!(
            inspect_url(&format!("{}/large.m3u8", server.uri())).await,
            None
        );
        assert_eq!(
            inspect_url(&format!("{}/invalid.m3u8", server.uri())).await,
            None
        );
    }

    #[tokio::test]
    async fn inspection_bounds_master_recursion() {
        use wiremock::{
            Mock, MockServer, ResponseTemplate,
            matchers::{method, path},
        };
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/master.m3u8"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_string("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nmaster.m3u8\n"),
            )
            .expect(MAX_PLAYLIST_FETCHES as u64)
            .mount(&server)
            .await;
        assert_eq!(
            inspect_url(&format!("{}/master.m3u8", server.uri())).await,
            None
        );
        server.verify().await;
    }

    #[tokio::test]
    async fn inspection_timeout_returns_no_facts() {
        use wiremock::{
            Mock, MockServer, ResponseTemplate,
            matchers::{method, path},
        };
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/slow.m3u8"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_delay(std::time::Duration::from_secs(5))
                    .set_body_string("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\na.ts\n"),
            )
            .mount(&server)
            .await;
        let start = std::time::Instant::now();
        assert_eq!(
            inspect_url(&format!("{}/slow.m3u8", server.uri())).await,
            None
        );
        assert!(start.elapsed() < std::time::Duration::from_secs(4));
    }

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
