/// Formats a receiver address for use as the host component of a URL.
///
/// IPv6 literals require brackets, and link-local scope identifiers must be
/// percent-encoded inside URLs.
pub fn host_for_url(address: &str) -> String {
    let address = address
        .strip_prefix('[')
        .and_then(|value| value.strip_suffix(']'))
        .unwrap_or(address);
    if address.contains(':') {
        let address = address.replace("%25", "%").replace('%', "%25");
        format!("[{address}]")
    } else {
        address.to_owned()
    }
}

/// Formats a receiver address and port for APIs that accept socket endpoints.
pub fn socket_endpoint(address: &str, port: u16) -> String {
    let address = address
        .strip_prefix('[')
        .and_then(|value| value.strip_suffix(']'))
        .unwrap_or(address);
    if address.contains(':') {
        format!("[{address}]:{port}")
    } else {
        format!("{address}:{port}")
    }
}

pub fn wss_endpoint(address: &str, port: u16) -> String {
    format!("wss://{}:{port}/", host_for_url(address))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn formats_ipv4_and_hostnames_without_brackets() {
        assert_eq!(socket_endpoint("192.0.2.1", 8009), "192.0.2.1:8009");
        assert_eq!(host_for_url("receiver.local"), "receiver.local");
    }

    #[test]
    fn formats_ipv6_for_sockets_and_urls() {
        assert_eq!(socket_endpoint("2001:db8::1", 8009), "[2001:db8::1]:8009");
        assert_eq!(host_for_url("2001:db8::1"), "[2001:db8::1]");
        assert_eq!(host_for_url("[2001:db8::1]"), "[2001:db8::1]");
    }

    #[test]
    fn preserves_socket_scopes_and_encodes_url_scopes_once() {
        assert_eq!(
            socket_endpoint("fe80::1234%en0", 8009),
            "[fe80::1234%en0]:8009"
        );
        assert_eq!(
            wss_endpoint("fe80::1234%en0", 8765),
            "wss://[fe80::1234%25en0]:8765/"
        );
        assert_eq!(host_for_url("[fe80::1234%25en0]"), "[fe80::1234%25en0]");
    }
}
