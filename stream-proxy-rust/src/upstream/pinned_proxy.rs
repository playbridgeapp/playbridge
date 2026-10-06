//! A per-origin native HTTP CONNECT gateway. Native stacks keep hostname-based TLS,
//! while Rust dials only the policy-checked addresses. Never a general LAN proxy.
use super::{resolve_checked_destination, NetworkPolicy};
use base64::{engine::general_purpose::STANDARD, Engine};
use rand::RngCore;
use serde::Serialize;
use std::{
    net::{Ipv4Addr, SocketAddr},
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    task::{JoinHandle, JoinSet},
    time::timeout,
};
use url::Url;

#[derive(Serialize)]
pub(crate) struct ProxyCredentials {
    host: &'static str,
    port: u16,
    username: &'static str,
    password: String,
}
pub(crate) struct PinnedProxy {
    pub credentials: ProxyCredentials,
    task: JoinHandle<()>,
}
impl Drop for PinnedProxy {
    fn drop(&mut self) {
        self.task.abort();
    }
}
impl PinnedProxy {
    pub async fn start(url: &str, policy: &NetworkPolicy) -> Result<Self, String> {
        let addresses = resolve_checked_destination(url, policy).await?;
        let url = Url::parse(url).map_err(|_| "invalid upstream URL")?;
        Self::start_at(url, addresses).await
    }
    async fn start_at(url: Url, addresses: Vec<SocketAddr>) -> Result<Self, String> {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0))
            .await
            .map_err(|_| "native origin gateway unavailable")?;
        let port = listener
            .local_addr()
            .map_err(|_| "native origin gateway unavailable")?
            .port();
        let mut secret = [0u8; 32];
        rand::thread_rng().fill_bytes(&mut secret);
        let password = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(secret);
        let authorization = format!(
            "Basic {}",
            STANDARD.encode(format!("playbridge:{password}"))
        );
        let task = tokio::spawn(async move {
            let mut connections = JoinSet::new();
            loop {
                tokio::select! {
                    accepted = listener.accept(), if connections.len() < 4 => {
                        let Ok((stream, peer)) = accepted else { break };
                        if !peer.ip().is_loopback() { continue; }
                        let (url, addresses, authorization) = (url.clone(), addresses.clone(), authorization.clone());
                        connections.spawn(async move { let _ = forward(stream, &url, &addresses, &authorization).await; });
                    }
                    _ = connections.join_next(), if !connections.is_empty() => {}
                }
            }
        });
        Ok(Self {
            credentials: ProxyCredentials {
                host: "127.0.0.1",
                port,
                username: "playbridge",
                password,
            },
            task,
        })
    }
    pub fn json(&self) -> Result<String, String> {
        serde_json::to_string(&self.credentials)
            .map_err(|_| "invalid native gateway credentials".into())
    }
}

async fn forward(
    mut client: TcpStream,
    destination: &Url,
    addresses: &[SocketAddr],
    authorization: &str,
) -> Result<(), ()> {
    let mut bytes = Vec::new();
    timeout(Duration::from_secs(30), async {
        loop {
            let mut byte = [0u8; 1];
            client.read_exact(&mut byte).await.map_err(|_| ())?;
            bytes.push(byte[0]);
            if bytes.ends_with(b"\r\n\r\n") {
                return Ok::<(), ()>(());
            }
            if bytes.len() >= 32 * 1024 {
                return Err(());
            }
        }
    })
    .await
    .map_err(|_| ())??;
    let text = std::str::from_utf8(&bytes).map_err(|_| ())?;
    let mut lines = text.split("\r\n");
    let request_line = lines.next().ok_or(())?;
    let parts: Vec<_> = request_line.split(' ').collect();
    if parts.len() != 3 || !matches!(parts[2], "HTTP/1.1" | "HTTP/1.0") {
        return Err(());
    }
    let mut headers = Vec::new();
    let mut authenticated = false;
    let mut authority = None;
    for line in lines.filter(|line| !line.is_empty()) {
        let (name, value) = line.split_once(':').ok_or(())?;
        if name.eq_ignore_ascii_case("proxy-authorization") {
            if authenticated
                || !constant_time_equal(value.trim().as_bytes(), authorization.as_bytes())
            {
                return Err(());
            }
            authenticated = true;
        } else if name.eq_ignore_ascii_case("proxy-connection")
            || name.eq_ignore_ascii_case("connection")
        {
            continue;
        } else if name.eq_ignore_ascii_case("host") {
            if authority.replace(value.trim()).is_some() {
                return Err(());
            }
        } else {
            if name.eq_ignore_ascii_case("transfer-encoding")
                || name.eq_ignore_ascii_case("content-length") && value.trim() != "0"
            {
                return Err(());
            }
            headers.push(line);
        }
    }
    if !authenticated {
        client.write_all(b"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"PlayBridge origin\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await.map_err(|_| ())?;
        return Ok(());
    }
    let host = destination.host_str().ok_or(())?;
    let port = destination.port_or_known_default().ok_or(())?;
    let connect_authority = format!("{host}:{port}");
    let tunnel = parts[0] == "CONNECT";
    if tunnel {
        if destination.scheme() != "https" || parts[1] != connect_authority {
            return Err(());
        }
    } else {
        if destination.scheme() != "http" || !matches!(parts[0], "GET" | "HEAD") {
            return Err(());
        }
        let request = Url::parse(parts[1]).map_err(|_| ())?;
        let mut expected = destination.clone();
        expected.set_fragment(None);
        if request != expected {
            return Err(());
        }
        let expected_host = &destination[url::Position::BeforeHost..url::Position::AfterPort];
        if authority != Some(expected_host) {
            return Err(());
        }
    }
    let mut remote = None;
    for address in addresses {
        if let Ok(Ok(stream)) = timeout(Duration::from_secs(10), TcpStream::connect(address)).await
        {
            remote = Some(stream);
            break;
        }
    }
    let mut remote = remote.ok_or(())?;
    if tunnel {
        client
            .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
            .await
            .map_err(|_| ())?;
    } else {
        let path = &destination[url::Position::BeforePath..url::Position::AfterQuery];
        let host = &destination[url::Position::BeforeHost..url::Position::AfterPort];
        let request = format!(
            "{} {} {}\r\nHost: {}\r\nConnection: close\r\n{}\r\n\r\n",
            parts[0],
            path,
            parts[2],
            host,
            headers.join("\r\n")
        );
        remote.write_all(request.as_bytes()).await.map_err(|_| ())?;
    }
    // One authenticated origin request per connection; no pipelined second fetch.
    if tunnel {
        // Native TLS may carry multiple origin HTTP requests; only the trusted native
        // task knows the gateway credentials and controls the original request URL.
        // TLS verification remains in the native stack, with the original hostname.
        tokio::io::copy_bidirectional(&mut client, &mut remote)
            .await
            .map_err(|_| ())?;
    } else {
        tokio::io::copy(&mut remote, &mut client)
            .await
            .map_err(|_| ())?;
    }
    Ok(())
}
fn constant_time_equal(a: &[u8], b: &[u8]) -> bool {
    use hmac::{Hmac, Mac};
    use sha2::Sha256;
    let mut mac =
        Hmac::<Sha256>::new_from_slice(b"PlayBridge gateway comparison").expect("fixed key");
    mac.update(a);
    let tag = mac.finalize().into_bytes();
    let mut expected =
        Hmac::<Sha256>::new_from_slice(b"PlayBridge gateway comparison").expect("fixed key");
    expected.update(b);
    expected.verify_slice(&tag).is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    async fn checked_tcp_addresses_not_host_dns_are_used_and_requests_are_scoped() {
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let address = origin.local_addr().unwrap();
        let source = tokio::spawn(async move {
            let (mut stream, _) = origin.accept().await.unwrap();
            let mut buffer = [0; 4096];
            let n = stream.read(&mut buffer).await.unwrap();
            let request = String::from_utf8_lossy(&buffer[..n]);
            assert!(request.starts_with("GET /video HTTP/1.1"));
            assert!(request.contains("Authorization: original-secret"));
            assert!(!request.to_lowercase().contains("proxy-authorization"));
            stream
                .write_all(
                    b"HTTP/1.1 200 OK\r\nContent-Length: 7\r\nConnection: close\r\n\r\nchecked",
                )
                .await
                .unwrap();
        });
        let url = Url::parse(&format!(
            "http://rebinding.invalid:{}/video",
            address.port()
        ))
        .unwrap();
        let gateway = PinnedProxy::start_at(url.clone(), vec![address])
            .await
            .unwrap();
        let creds = &gateway.credentials;
        let auth = STANDARD.encode(format!("{}:{}", creds.username, creds.password));
        let gateway_address = format!("127.0.0.1:{}", creds.port);
        let mut unauthorized = TcpStream::connect(&gateway_address).await.unwrap();
        unauthorized
            .write_all(
                format!(
                    "GET {url} HTTP/1.1\r\nHost: rebinding.invalid:{}\r\n\r\n",
                    address.port()
                )
                .as_bytes(),
            )
            .await
            .unwrap();
        let mut denied = Vec::new();
        unauthorized.read_to_end(&mut denied).await.unwrap();
        assert!(String::from_utf8_lossy(&denied).contains("407"));
        let mut escaped = TcpStream::connect(&gateway_address).await.unwrap();
        escaped.write_all(format!("GET http://another.invalid/private HTTP/1.1\r\nHost: another.invalid\r\nProxy-Authorization: Basic {auth}\r\n\r\n").as_bytes()).await.unwrap();
        let mut rejected = Vec::new();
        escaped.read_to_end(&mut rejected).await.unwrap();
        assert!(rejected.is_empty());
        let mut client = TcpStream::connect(&gateway_address).await.unwrap();
        client.write_all(format!("GET {url} HTTP/1.1\r\nHost: rebinding.invalid:{}\r\nProxy-Authorization: Basic {auth}\r\nAuthorization: original-secret\r\n\r\n", address.port()).as_bytes()).await.unwrap();
        let mut response = Vec::new();
        client.read_to_end(&mut response).await.unwrap();
        assert!(response.ends_with(b"checked"));
        source.await.unwrap();
        drop(gateway);
        tokio::task::yield_now().await;
        assert!(TcpStream::connect(gateway_address).await.is_err());
    }
    #[tokio::test]
    async fn connect_preserves_tls_bytes_and_rejects_other_authorities() {
        let origin = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).await.unwrap();
        let address = origin.local_addr().unwrap();
        let source = tokio::spawn(async move {
            let (mut stream, _) = origin.accept().await.unwrap();
            let mut byte = [0];
            stream.read_exact(&mut byte).await.unwrap();
            assert_eq!(byte[0], 0x16);
            stream.write_all(&[0x16, 0x03, 0x03]).await.unwrap();
        });
        let url = Url::parse(&format!(
            "https://tls-origin.invalid:{}/video",
            address.port()
        ))
        .unwrap();
        let gateway = PinnedProxy::start_at(url, vec![address]).await.unwrap();
        let creds = &gateway.credentials;
        let auth = STANDARD.encode(format!("{}:{}", creds.username, creds.password));
        let proxy_address = format!("127.0.0.1:{}", creds.port);
        let mut escaped = TcpStream::connect(&proxy_address).await.unwrap();
        escaped.write_all(format!("CONNECT other.invalid:443 HTTP/1.1\r\nProxy-Authorization: Basic {auth}\r\n\r\n").as_bytes()).await.unwrap();
        let mut empty = Vec::new();
        escaped.read_to_end(&mut empty).await.unwrap();
        assert!(empty.is_empty());
        let mut stream = TcpStream::connect(&proxy_address).await.unwrap();
        stream.write_all(format!("CONNECT tls-origin.invalid:{} HTTP/1.1\r\nProxy-Authorization: Basic {auth}\r\n\r\n", address.port()).as_bytes()).await.unwrap();
        let mut response = [0u8; 39];
        stream.read_exact(&mut response).await.unwrap();
        assert_eq!(&response, b"HTTP/1.1 200 Connection Established\r\n\r\n");
        stream.write_all(&[0x16]).await.unwrap();
        let mut records = [0u8; 3];
        stream.read_exact(&mut records).await.unwrap();
        assert_eq!(records, [0x16, 0x03, 0x03]);
        source.await.unwrap();
    }
}
