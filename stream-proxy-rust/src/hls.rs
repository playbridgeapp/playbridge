use regex::Regex;
use url::Url;

pub struct HlsPlaylistRewriter;

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
                if uri_attr_regex.is_match(line) {
                    let rewritten_line =
                        uri_attr_regex.replace_all(line, |caps: &regex::Captures| {
                            let prefix = &caps[1];
                            let relative_uri = &caps[2];
                            let suffix = &caps[3];

                            match base_uri.join(relative_uri) {
                                Ok(resolved) => {
                                    let rewritten = rewrite_url(resolved.as_str());
                                    format!("{}{}{}", prefix, rewritten, suffix)
                                }
                                Err(_) => caps[0].to_string(),
                            }
                        });
                    rewritten_lines.push(rewritten_line.into_owned());
                } else {
                    rewritten_lines.push(line.to_string());
                }
            } else {
                // Segment or variant URI line
                match base_uri.join(trimmed) {
                    Ok(resolved) => {
                        let rewritten = rewrite_url(resolved.as_str());
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
}
