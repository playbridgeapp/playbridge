use regex::Regex;

pub struct DashManifestRewriter;

impl DashManifestRewriter {
    pub fn rewrite(content: &str, token: Option<&str>) -> String {
        let append_token = |url: &str| -> String {
            if url.starts_with("//") || url.starts_with("http://") || url.starts_with("https://") {
                return url.to_string();
            }
            let clean_url = if url.starts_with('/') {
                format!("_root_{}", url)
            } else {
                url.to_string()
            };

            match token {
                Some(t) if !t.is_empty() && !clean_url.contains("token=") => {
                    let separator = if clean_url.contains('?') { "&amp;" } else { "?" };
                    format!("{}{}{}token={}", clean_url, separator, "", t)
                }
                _ => clean_url,
            }
        };

        let mut result = content.to_string();

        // 1. Rewrite <BaseURL>...</BaseURL>
        let base_url_re = Regex::new(r"(?i)<BaseURL([^>]*)>([^<]+)</BaseURL>").expect("Valid regex");
        result = base_url_re
            .replace_all(&result, |caps: &regex::Captures| {
                let attrs = &caps[1];
                let url = caps[2].trim();
                format!("<BaseURL{}>{}</BaseURL>", attrs, append_token(url))
            })
            .into_owned();

        // 2. Rewrite <Location>...</Location>
        let location_re = Regex::new(r"(?i)<Location([^>]*)>([^<]+)</Location>").expect("Valid regex");
        result = location_re
            .replace_all(&result, |caps: &regex::Captures| {
                let attrs = &caps[1];
                let url = caps[2].trim();
                format!("<Location{}>{}</Location>", attrs, append_token(url))
            })
            .into_owned();

        // 3. Rewrite attributes in double quotes
        let attr_dq_re = Regex::new(r#"(?i)\b(media|initialization|location|baseUrl)\s*=\s*"([^"]+)""#).expect("Valid regex");
        result = attr_dq_re
            .replace_all(&result, |caps: &regex::Captures| {
                let attr_name = &caps[1];
                let url = &caps[2];
                format!(r#"{}="{}""#, attr_name, append_token(url))
            })
            .into_owned();

        // 4. Rewrite attributes in single quotes
        let attr_sq_re = Regex::new(r#"(?i)\b(media|initialization|location|baseUrl)\s*=\s*'([^']+)'"#).expect("Valid regex");
        result = attr_sq_re
            .replace_all(&result, |caps: &regex::Captures| {
                let attr_name = &caps[1];
                let url = &caps[2];
                format!("{}='{}'", attr_name, append_token(url))
            })
            .into_owned();

        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_dash_rewrite() {
        let xml = r#"<MPD><BaseURL>/segment.mp4</BaseURL><SegmentTemplate media="chunk-$Number$.m4s"/></MPD>"#;
        let rewritten = DashManifestRewriter::rewrite(xml, Some("secret"));
        assert!(rewritten.contains("<BaseURL>_root_/segment.mp4?token=secret</BaseURL>"));
        assert!(rewritten.contains(r#"media="chunk-$Number$.m4s?token=secret""#));
    }
}
