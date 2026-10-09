//! HTTP-only DLNA transport. Prefer Android's selected network for every request,
//! falling back to the default network if socket binding or connection fails.
use http_body_util::{BodyExt, Full};
use hyper::{Request, body::Bytes, client::conn::http1};
use hyper_util::rt::TokioIo;
use reqwest::{Client, Method, StatusCode};
use tokio::net::{TcpSocket, TcpStream, lookup_host};
use url::Url;

use crate::{CastError, Result};

type SocketBinder = fn(&TcpSocket, u64) -> std::io::Result<()>;

#[derive(Debug)]
pub(crate) struct HttpClient {
    client: Client,
    network_handle: Option<u64>,
    binder: SocketBinder,
}

impl HttpClient {
    pub(crate) fn new(network_handle: Option<u64>) -> Result<Self> {
        Ok(Self {
            client: Client::builder()
                .timeout(super::upnp::UPNP_TIMEOUT)
                .redirect(reqwest::redirect::Policy::none())
                .build()?,
            network_handle: network_handle.filter(|handle| *handle != 0),
            binder: bind_network,
        })
    }

    pub(crate) async fn request(
        &self,
        method: Method,
        url: &Url,
        headers: &[(&str, &str)],
        body: String,
        max_bytes: usize,
    ) -> Result<(StatusCode, Vec<u8>)> {
        if let Some(handle) = self.network_handle {
            match self.connect_bound(url, handle).await {
                Ok(stream) => {
                    return self
                        .bound_request(method, url, headers, body, max_bytes, stream)
                        .await;
                }
                Err(bound_error) => {
                    // Retry only before sending HTTP: a SOAP action may have side effects.
                    return self
                        .default_request(method, url, headers, body, max_bytes)
                        .await
                        .map_err(|default_error| {
                            CastError::Transport(format!(
                                "DLNA HTTP connection on the Android local network failed: {bound_error}; \
                                 retrying with Android's default network also failed: {default_error}"
                            ))
                        });
                }
            }
        }
        self.default_request(method, url, headers, body, max_bytes)
            .await
    }

    async fn default_request(
        &self,
        method: Method,
        url: &Url,
        headers: &[(&str, &str)],
        body: String,
        max_bytes: usize,
    ) -> Result<(StatusCode, Vec<u8>)> {
        let mut request = self.client.request(method, url.clone()).body(body);
        for (name, value) in headers {
            request = request.header(*name, *value);
        }
        let mut response = request
            .send()
            .await
            .map_err(|error| CastError::Http(error.without_url()))?;
        let status = response.status();
        if response
            .content_length()
            .is_some_and(|length| length > max_bytes as u64)
        {
            return Err(CastError::Protocol(
                "UPnP HTTP response exceeds 512 KiB".into(),
            ));
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|error| CastError::Http(error.without_url()))?
        {
            append_bounded(&mut bytes, &chunk, max_bytes)?;
        }
        Ok((status, bytes))
    }

    async fn connect_bound(&self, url: &Url, handle: u64) -> Result<TcpStream> {
        // DLNA description/control URL validation restricts this backend to HTTP.
        let host = url.host_str().ok_or(CastError::MissingField("HTTP host"))?;
        let host = host.trim_start_matches('[').trim_end_matches(']');
        let port = url
            .port_or_known_default()
            .ok_or(CastError::MissingField("HTTP port"))?;
        let mut last_error = std::io::Error::from(std::io::ErrorKind::AddrNotAvailable);
        let mut connected = None;
        for address in lookup_host((host, port)).await? {
            let socket = if address.is_ipv4() {
                TcpSocket::new_v4()?
            } else {
                TcpSocket::new_v6()?
            };
            (self.binder)(&socket, handle)?;
            match socket.connect(address).await {
                Ok(stream) => {
                    connected = Some(stream);
                    break;
                }
                Err(error) => last_error = error,
            }
        }
        connected.ok_or(CastError::Network(last_error))
    }

    async fn bound_request(
        &self,
        method: Method,
        url: &Url,
        headers: &[(&str, &str)],
        body: String,
        max_bytes: usize,
        stream: TcpStream,
    ) -> Result<(StatusCode, Vec<u8>)> {
        let (mut sender, connection) = http1::handshake(TokioIo::new(stream))
            .await
            .map_err(http_error)?;
        let connection = tokio::spawn(async move {
            let _ = connection.await;
        });
        // Abort also when the enclosing SOAP/description timeout cancels this future.
        let _connection = AbortConnection(connection);
        let mut request = Request::builder()
            .method(method)
            .uri(&url[url::Position::BeforePath..url::Position::AfterQuery]);
        request = request.header(
            "Host",
            &url[url::Position::BeforeHost..url::Position::AfterPort],
        );
        for (name, value) in headers {
            request = request.header(*name, *value);
        }
        let request = request
            .body(Full::new(Bytes::from(body)))
            .map_err(|_| CastError::Protocol("Invalid DLNA HTTP request".into()))?;
        let mut response = sender.send_request(request).await.map_err(http_error)?;
        let status = response.status();
        let mut bytes = Vec::new();
        while let Some(frame) = response.body_mut().frame().await {
            let frame = frame.map_err(http_error)?;
            if let Some(data) = frame.data_ref() {
                append_bounded(&mut bytes, data, max_bytes)?;
            }
        }
        Ok((status, bytes))
    }
}

fn append_bounded(bytes: &mut Vec<u8>, chunk: &[u8], max_bytes: usize) -> Result<()> {
    if chunk.len() > max_bytes - bytes.len() {
        return Err(CastError::Protocol(
            "UPnP HTTP response exceeds 512 KiB".into(),
        ));
    }
    bytes.extend_from_slice(chunk);
    Ok(())
}

fn http_error(_: hyper::Error) -> CastError {
    CastError::Transport("DLNA HTTP transport failed".into())
}

fn bind_network(socket: &TcpSocket, handle: u64) -> std::io::Result<()> {
    #[cfg(target_os = "android")]
    {
        crate::net::bind_android_network(socket, handle)
    }
    #[cfg(not(target_os = "android"))]
    {
        let _ = (socket, handle);
        Ok(())
    }
}

struct AbortConnection(tokio::task::JoinHandle<()>);
impl Drop for AbortConnection {
    fn drop(&mut self) {
        self.0.abort();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use wiremock::{Mock, MockServer, ResponseTemplate, matchers::method};

    static BINDS: AtomicUsize = AtomicUsize::new(0);
    fn record_bind(socket: &TcpSocket, handle: u64) -> std::io::Result<()> {
        assert_eq!(handle, 42);
        assert_eq!(socket.local_addr()?.port(), 0, "must bind before connect");
        BINDS.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }

    #[tokio::test]
    async fn every_http_request_binds_before_connect_and_preserves_request() {
        let server = MockServer::start().await;
        for method_name in ["GET", "POST", "M-POST"] {
            Mock::given(method(method_name))
                .respond_with(ResponseTemplate::new(200).set_body_string("ok"))
                .mount(&server)
                .await;
        }
        let mut client = HttpClient::new(Some(42)).unwrap();
        client.binder = record_bind;
        for method_name in ["GET", "POST", "M-POST"] {
            let (status, bytes) = client
                .request(
                    Method::from_bytes(method_name.as_bytes()).unwrap(),
                    &Url::parse(&format!("{}/control?test=1", server.uri())).unwrap(),
                    &[("SOAPAction", "Play")],
                    "body".into(),
                    1024,
                )
                .await
                .unwrap();
            assert_eq!(status, StatusCode::OK);
            assert_eq!(bytes, b"ok");
        }
        assert_eq!(BINDS.load(Ordering::SeqCst), 3);
        for request in server.received_requests().await.unwrap() {
            assert_eq!(request.url.query(), Some("test=1"));
            assert_eq!(request.body, b"body");
            assert_eq!(request.headers["soapaction"], "Play");
        }
    }

    #[tokio::test]
    async fn failed_binding_uses_default_network() {
        let server = MockServer::start().await;
        let mut client = HttpClient::new(Some(42)).unwrap();
        client.binder = |_, _| Err(std::io::ErrorKind::PermissionDenied.into());
        let (status, _) = client
            .request(
                Method::GET,
                &Url::parse(&server.uri()).unwrap(),
                &[],
                String::new(),
                1024,
            )
            .await
            .unwrap();
        assert_eq!(status, StatusCode::NOT_FOUND);
        assert_eq!(server.received_requests().await.unwrap().len(), 1);
    }

    #[tokio::test]
    async fn bind_failure_succeeds_via_default_network_and_preserves_requests() {
        let server = MockServer::start().await;
        let mut client = HttpClient::new(Some(42)).unwrap();
        client.binder = |_, _| Err(std::io::Error::from_raw_os_error(1));
        for method_name in ["GET", "POST", "M-POST"] {
            Mock::given(method(method_name))
                .respond_with(ResponseTemplate::new(200).set_body_string("ok"))
                .expect(1)
                .mount(&server)
                .await;
            let (status, bytes) = client
                .request(
                    Method::from_bytes(method_name.as_bytes()).unwrap(),
                    &Url::parse(&format!("{}/control?test=1", server.uri())).unwrap(),
                    &[("SOAPAction", "Play")],
                    "body".into(),
                    1024,
                )
                .await
                .unwrap();
            assert_eq!(status, StatusCode::OK);
            assert_eq!(bytes, b"ok");
        }
        server.verify().await;
        for request in server.received_requests().await.unwrap() {
            assert_eq!(request.url.query(), Some("test=1"));
            assert_eq!(request.body, b"body");
            assert_eq!(request.headers["soapaction"], "Play");
        }
    }

    fn unavailable_endpoint() -> Url {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        Url::parse(&format!(
            "http://{}/control",
            listener.local_addr().unwrap()
        ))
        .unwrap()
    }

    #[tokio::test]
    async fn bound_connect_failure_succeeds_via_default_client() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(200).set_body_string("ok"))
            .expect(1)
            .mount(&server)
            .await;
        let mut client = HttpClient::new(Some(42)).unwrap();
        // The bound socket connects directly to a closed port. Only the default
        // client can reach the responder, through its configured HTTP proxy.
        client.client = Client::builder()
            .proxy(reqwest::Proxy::http(server.uri()).unwrap())
            .build()
            .unwrap();
        let (status, bytes) = client
            .request(
                Method::POST,
                &unavailable_endpoint(),
                &[("SOAPAction", "Play")],
                "body".into(),
                1024,
            )
            .await
            .unwrap();
        assert_eq!(status, StatusCode::OK);
        assert_eq!(bytes, b"ok");
        let requests = server.received_requests().await.unwrap();
        assert_eq!(requests.len(), 1);
        assert_eq!(requests[0].body, b"body");
        assert_eq!(requests[0].headers["soapaction"], "Play");
    }

    #[tokio::test]
    async fn binding_and_default_failures_are_both_preserved_without_urls() {
        let mut client = HttpClient::new(Some(42)).unwrap();
        client.binder = |_, _| Err(std::io::Error::from_raw_os_error(1));
        let mut url = unavailable_endpoint();
        url.set_query(Some("token=private-token"));
        let error = client
            .request(Method::GET, &url, &[], String::new(), 1024)
            .await
            .unwrap_err()
            .to_string();
        assert!(error.contains(&std::io::Error::from_raw_os_error(1).to_string()));
        assert!(error.contains("default network also failed"));
        assert!(error.contains("HTTP operation failed"));
        assert!(!error.contains("http://"));
        assert!(!error.contains("private-token"));
    }

    #[tokio::test]
    async fn bound_connect_and_default_failures_are_both_preserved() {
        let client = HttpClient::new(Some(42)).unwrap();
        let error = client
            .request(
                Method::GET,
                &unavailable_endpoint(),
                &[],
                String::new(),
                1024,
            )
            .await
            .unwrap_err()
            .to_string();
        assert!(error.contains("network operation failed"));
        assert!(error.contains("default network also failed"));
        assert!(error.contains("HTTP operation failed"));
    }

    #[tokio::test]
    async fn bound_response_failure_does_not_replay_http() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .respond_with(ResponseTemplate::new(200).set_body_string("too large"))
            .expect(1)
            .mount(&server)
            .await;
        let client = HttpClient::new(Some(42)).unwrap();
        let error = client
            .request(
                Method::POST,
                &Url::parse(&server.uri()).unwrap(),
                &[],
                "body".into(),
                1,
            )
            .await
            .unwrap_err();
        assert!(matches!(error, CastError::Protocol(_)));
        assert_eq!(server.received_requests().await.unwrap().len(), 1);
        server.verify().await;
    }
}
