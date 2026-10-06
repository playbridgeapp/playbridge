//! A manifest reference is an exact URL or a bounded DASH template, never a URL prefix.
use regex::Regex;
use url::Url;

pub(crate) fn encode_target(value: &str) -> String {
    static FIELDS: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    let fields = FIELDS.get_or_init(|| {
        Regex::new(r"\$(?:RepresentationID|Bandwidth|Number|Time)(?:%0[0-9]{1,2}d)?\$")
            .expect("fixed regex")
    });
    let mut result = String::new();
    let mut offset = 0;
    for field in fields.find_iter(value) {
        result.push_str(&urlencoding::encode(&value[offset..field.start()]));
        result.push_str(field.as_str());
        offset = field.end();
    }
    result.push_str(&urlencoding::encode(&value[offset..]));
    result
}

pub(crate) fn authorized_target(template: &str, requested: &str) -> bool {
    if template.len() > 8192 || requested.len() > 8192 {
        return false;
    }
    let Ok(base) = Url::parse(template) else {
        return false;
    };
    let Ok(target) = Url::parse(requested) else {
        return false;
    };
    if !matches!(target.scheme(), "http" | "https")
        || target.origin() != base.origin()
        || target.as_str() != requested
        || !target.username().is_empty()
        || target.password().is_some()
        || base.host_str().is_some_and(|host| host.contains('$'))
    {
        return false;
    }
    if template == requested {
        return true;
    }
    let mut pattern = String::from("^");
    let mut tail = template;
    let mut placeholders = 0;
    while let Some(start) = tail.find('$') {
        pattern.push_str(&regex::escape(&tail[..start]));
        tail = &tail[start + 1..];
        let Some(end) = tail.find('$') else {
            return false;
        };
        let field = &tail[..end];
        let (name, format) = field
            .split_once('%')
            .map_or((field, None), |(name, format)| (name, Some(format)));
        if format.is_some_and(|format| !valid_format(format)) {
            return false;
        }
        match name {
            "Number" | "Time" | "Bandwidth" => pattern.push_str("[0-9]{1,20}"),
            "RepresentationID" if format.is_none() => {
                // Conservative: include `=` for real DASH IDs, but never `&`, `%`,
                // or path/query delimiters that could rewrite the URL structure.
                pattern.push_str(r"[A-Za-z0-9._~=-]{1,128}")
            }
            _ => return false,
        }
        placeholders += 1;
        if placeholders > 16 {
            return false;
        }
        tail = &tail[end + 1..];
    }
    if placeholders == 0 {
        return false;
    }
    pattern.push_str(&regex::escape(tail));
    pattern.push('$');
    Regex::new(&pattern).is_ok_and(|matcher| matcher.is_match(requested))
}

fn valid_format(format: &str) -> bool {
    let Some(width) = format
        .strip_prefix('0')
        .and_then(|value| value.strip_suffix('d'))
    else {
        return false;
    };
    width
        .parse::<usize>()
        .is_ok_and(|width| (1..=20).contains(&width))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn exact_targets_cannot_change_host_path_query_or_credentials() {
        let original = "https://cdn.example/a.ts?secret=token";
        assert!(authorized_target(original, original));
        for value in [
            "https://evil.example/a.ts?secret=token",
            "https://cdn.example/b.ts?secret=token",
            "https://cdn.example/a.ts?secret=other",
            "https://user@cdn.example/a.ts?secret=token",
        ] {
            assert!(!authorized_target(original, value));
        }
        let dollar = "https://cdn.example/price$1.ts";
        assert!(authorized_target(dollar, dollar));
    }
    #[test]
    fn dash_templates_allow_only_bounded_segment_values() {
        let template = "https://cdn.example/chunks/$RepresentationID$/$Number%05d$-$Time$.m4s";
        assert!(authorized_target(
            template,
            "https://cdn.example/chunks/video_1/00012-3456.m4s"
        ));
        assert!(authorized_target(
            template,
            "https://cdn.example/chunks/audio_eng=64008/00012-3456.m4s"
        ));
        for value in [
            "https://evil.example/chunks/video_1/00012-3456.m4s",
            "https://cdn.example/chunks/../00012-3456.m4s",
            "https://cdn.example/chunks/%2e%2e/00012-3456.m4s",
            "https://cdn.example/chunks/%2F/00012-3456.m4s",
            "https://cdn.example/chunks/video/secret-3456.m4s",
            "https://cdn.example/chunks/audio&x=1/00012-3456.m4s",
        ] {
            assert!(!authorized_target(template, value));
        }
        let query = "https://cdn.example/seg.m4s?id=$RepresentationID$";
        assert!(authorized_target(
            query,
            "https://cdn.example/seg.m4s?id=audio_eng=64008"
        ));
        assert!(!authorized_target(
            query,
            "https://cdn.example/seg.m4s?id=a&foo=bar"
        ));
        for value in [
            "a%2F..",
            "%2e%2e",
            "a?more",
            "a#fragment",
            "a&foo=bar",
            "a/b",
            "a%252F..",
        ] {
            assert!(!authorized_target(
                query,
                &format!("https://cdn.example/seg.m4s?id={value}")
            ));
        }
        assert!(!authorized_target(
            "https://$RepresentationID$.example/file",
            "https://evil.example/file"
        ));
    }
}
