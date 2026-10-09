use std::collections::HashMap;

use crate::upnp_http::HttpClient;
use reqwest::{Method, StatusCode};
use roxmltree::{Document, Node};
use serde::Serialize;
use url::Url;

use crate::{CastError, Result};

const PREFLIGHT_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(3);
const MAX_DESCRIPTION_BYTES: usize = 512 * 1024;
pub(crate) const UPNP_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(8);

#[derive(Debug, Clone, Serialize)]
pub struct ActionFailure {
    pub action: String,
    pub code: Option<u16>,
    pub http_status: Option<u16>,
    pub description: String,
    #[serde(skip)]
    pub transport_failure: bool,
}

impl std::fmt::Display for ActionFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "UPnP {}", self.action)?;
        if let Some(code) = self.code {
            write!(f, " error {code}:")?;
        }
        write!(f, " {}", self.description)
    }
}

pub(crate) fn action_failure(
    action: &str,
    code: Option<u16>,
    http_status: Option<u16>,
    description: &str,
    transport_failure: bool,
) -> CastError {
    CastError::UpnpAction(Box::new(ActionFailure {
        action: action.into(),
        code,
        http_status,
        description: sanitize_description(description),
        transport_failure,
    }))
}

/// Request-scoped deadline failure, including commands cancelled by the host budget.
pub fn action_timeout(action: &str) -> CastError {
    action_failure(action, None, None, "action timed out", true)
}

pub(crate) fn action_code(error: &CastError) -> Option<u16> {
    match error {
        CastError::UpnpAction(failure) => failure.code,
        _ => None,
    }
}

fn sanitize_description(description: &str) -> String {
    // Whitespace/control normalization prevents multiline diagnostics. Redact any
    // URL scheme, including embedded URLs without whitespace before them.
    let normalized = description
        .chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect::<String>();
    normalized
        .split_whitespace()
        .map(|word| {
            if let Some(index) = word.find("://") {
                let start = word[..index]
                    .rfind(|c: char| !(c.is_ascii_alphanumeric() || matches!(c, '+' | '-' | '.')))
                    .map_or(0, |i| i + 1);
                format!("{}[URL]", &word[..start])
            } else {
                word.to_owned()
            }
        })
        .collect::<Vec<_>>()
        .join(" ")
        .chars()
        .take(120)
        .collect()
}

#[derive(Debug, Default)]
pub(crate) struct MediaFacts {
    pub loaded: bool,
    pub is_live: bool,
    pub is_screen_mirror: bool,
    pub supplied_duration: f64,
    pub media_duration: f64,
    pub media_info_attempts: u8,
}

#[derive(Debug)]
struct Service {
    service_type: String,
    control_url: Url,
}

#[derive(Debug)]
pub struct Renderer {
    client: HttpClient,
    pub(crate) facts: MediaFacts,
    location: String,
    friendly_name: String,
    av_transport: Box<Service>,
    connection_manager: Option<Box<Service>>,
    rendering_control: Option<Box<Service>>,
}

impl Renderer {
    pub async fn load(location: &str) -> Result<Self> {
        Self::load_on_network(location, None).await
    }

    pub async fn load_on_network(location: &str, network_handle: Option<u64>) -> Result<Self> {
        let location_url = Url::parse(location)?;
        if location_url.scheme() != "http" || location_url.host().is_none() {
            return Err(CastError::Protocol(
                "UPnP LOCATION must use HTTP with a host".into(),
            ));
        }
        let client = HttpClient::new(network_handle)?;
        let (status, bytes) = tokio::time::timeout(
            UPNP_TIMEOUT,
            client.request(
                Method::GET,
                &location_url,
                &[],
                String::new(),
                MAX_DESCRIPTION_BYTES,
            ),
        )
        .await
        .map_err(|_| CastError::Transport("UPnP device description timed out".into()))??;
        if !status.is_success() {
            return Err(CastError::Protocol(format!(
                "UPnP device description returned HTTP {status}"
            )));
        }
        let text = String::from_utf8_lossy(&bytes);
        let document = Document::parse(&text).map_err(|error| {
            CastError::Protocol(format!("Invalid UPnP device description: {error}"))
        })?;
        let base = document
            .descendants()
            .find(|node| node.has_tag_name("URLBase"))
            .and_then(|node| node.text())
            .filter(|value| !value.trim().is_empty())
            .and_then(|value| Url::parse(value.trim()).ok())
            .filter(|base| same_renderer_host(base, &location_url))
            .unwrap_or_else(|| location_url.clone());
        let find_service = |name: &str| -> Result<Option<Box<Service>>> {
            let prefix = format!("urn:schemas-upnp-org:service:{name}:");
            for node in document
                .descendants()
                .filter(|node| node.has_tag_name("service"))
            {
                let service_type = child_text(node, "serviceType").unwrap_or_default().trim();
                if service_type.starts_with(&prefix) {
                    let control_url = child_text(node, "controlURL")
                        .ok_or(CastError::MissingField("UPnP controlURL"))?;
                    let control_url = base.join(control_url.trim())?;
                    if !same_renderer_host(&control_url, &location_url) {
                        return Err(CastError::Protocol(format!(
                            "UPnP {name} control URL must use HTTP on the LOCATION host"
                        )));
                    }
                    return Ok(Some(Box::new(Service {
                        service_type: service_type.into(),
                        control_url,
                    })));
                }
            }
            Ok(None)
        };
        Ok(Self {
            client,
            facts: MediaFacts::default(),
            location: location.into(),
            friendly_name: document
                .descendants()
                .find(|node| node.has_tag_name("friendlyName"))
                .and_then(|node| node.text())
                .unwrap_or("DLNA renderer")
                .into(),
            av_transport: find_service("AVTransport")?
                .ok_or(CastError::MissingField("AVTransport service"))?,
            // Optional services must not block playback if their description is malformed.
            connection_manager: find_service("ConnectionManager").ok().flatten(),
            rendering_control: find_service("RenderingControl").ok().flatten(),
        })
    }

    pub fn friendly_name(&self) -> &str {
        &self.friendly_name
    }

    pub fn location(&self) -> String {
        self.location.clone()
    }

    pub async fn preflight(&self, mime: &str) -> Result<()> {
        let Some(service) = &self.connection_manager else {
            return Ok(());
        };
        if let Ok(Ok(info)) = tokio::time::timeout(
            PREFLIGHT_TIMEOUT,
            self.service_action(service, "GetProtocolInfo", ""),
        )
        .await
            && !sink_supports_category(
                info.get("Sink").map(String::as_str).unwrap_or_default(),
                mime,
            )
        {
            return Err(CastError::Protocol(format!(
                "DLNA renderer does not support {mime}"
            )));
        }
        Ok(())
    }

    pub async fn set_media_uri(&self, media_uri: &str, metadata: &str) -> Result<()> {
        self.set_media_uri_with_metadata_fallback(media_uri, metadata, false)
            .await
    }

    pub async fn set_media_uri_with_metadata_fallback(
        &self,
        media_uri: &str,
        metadata: &str,
        allow_empty_metadata: bool,
    ) -> Result<()> {
        self.set_media_uri_with_retry(media_uri, metadata, allow_empty_metadata, false)
            .await
    }

    pub(crate) async fn set_media_uri_with_retry(
        &self,
        media_uri: &str,
        metadata: &str,
        allow_empty_metadata: bool,
        hls_501_retry: bool,
    ) -> Result<()> {
        let arguments = |metadata: &str| {
            format!(
                "<InstanceID>0</InstanceID><CurrentURI>{}</CurrentURI><CurrentURIMetaData>{}</CurrentURIMetaData>",
                escape_xml(media_uri),
                escape_xml(metadata)
            )
        };
        let mut retried_metadata = false;
        let mut retried_transport = false;
        let mut retried_transition = false;
        loop {
            let metadata = if retried_metadata { "" } else { metadata };
            match self.action("SetAVTransportURI", &arguments(metadata)).await {
                Ok(_) => return Ok(()),
                Err(error) if is_transport_failure(&error) && !retried_transport => {
                    retried_transport = true;
                }
                Err(error) if is_transition_failure(&error) && !retried_transition => {
                    retried_transition = true;
                    self.stop().await?;
                }
                Err(error)
                    if !retried_metadata
                        && ((allow_empty_metadata && is_metadata_failure(&error))
                            || (hls_501_retry && action_code(&error) == Some(501))) =>
                {
                    retried_metadata = true;
                }
                Err(error) => return Err(error),
            }
        }
    }

    pub async fn play(&self) -> Result<()> {
        self.action("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
            .await?;
        Ok(())
    }

    pub async fn pause(&self) -> Result<()> {
        self.action("Pause", "<InstanceID>0</InstanceID>").await?;
        Ok(())
    }

    pub async fn stop(&self) -> Result<()> {
        self.action("Stop", "<InstanceID>0</InstanceID>").await?;
        Ok(())
    }

    pub async fn seek(&self, target: &str) -> Result<()> {
        let arguments = format!(
            "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>{}</Target>",
            escape_xml(target)
        );
        self.action("Seek", &arguments).await?;
        Ok(())
    }

    pub async fn transport_info(&self) -> Result<HashMap<String, String>> {
        self.action("GetTransportInfo", "<InstanceID>0</InstanceID>")
            .await
    }

    pub async fn position_info(&self) -> Result<HashMap<String, String>> {
        self.action("GetPositionInfo", "<InstanceID>0</InstanceID>")
            .await
    }

    pub async fn media_info(&self) -> Result<HashMap<String, String>> {
        self.action("GetMediaInfo", "<InstanceID>0</InstanceID>")
            .await
    }

    pub fn supports_volume(&self) -> bool {
        self.rendering_control.is_some()
    }

    fn rendering_control(&self) -> Result<&Service> {
        self.rendering_control.as_deref().ok_or_else(|| {
            CastError::Protocol("DLNA volume control is not available for this session".into())
        })
    }

    pub async fn volume(&self) -> Result<f32> {
        let values = self
            .service_action(
                self.rendering_control()?,
                "GetVolume",
                "<InstanceID>0</InstanceID><Channel>Master</Channel>",
            )
            .await?;
        let value = values
            .get("CurrentVolume")
            .and_then(|value| value.trim().parse::<u8>().ok())
            .filter(|value| *value <= 100)
            .ok_or_else(|| {
                CastError::Protocol("DLNA renderer did not report a valid volume".into())
            })?;
        Ok(f32::from(value) / 100.0)
    }

    pub async fn set_volume(&self, level: f32) -> Result<()> {
        if !level.is_finite() {
            return Err(CastError::Protocol("volume level must be finite".into()));
        }
        let value = (level.clamp(0.0, 1.0) * 100.0).round() as u8;
        self.service_action(self.rendering_control()?, "SetVolume", &format!(
            "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>{value}</DesiredVolume>"
        )).await?;
        Ok(())
    }

    pub async fn set_muted(&self, muted: bool) -> Result<()> {
        self.service_action(
            self.rendering_control()?,
            "SetMute",
            &format!(
                "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>{}</DesiredMute>",
                u8::from(muted)
            ),
        )
        .await?;
        Ok(())
    }

    async fn action(&self, name: &str, arguments: &str) -> Result<HashMap<String, String>> {
        self.service_action(&self.av_transport, name, arguments)
            .await
    }

    async fn service_action(
        &self,
        service: &Service,
        name: &str,
        arguments: &str,
    ) -> Result<HashMap<String, String>> {
        let soap_action = format!("\"{}#{name}\"", service.service_type);
        let body = format!(
            r#"<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:{name} xmlns:u="{}">{arguments}</u:{name}></s:Body></s:Envelope>"#,
            service.service_type
        );
        let operation = async {
            let headers = [
                ("Content-Type", "text/xml; charset=\"utf-8\""),
                ("SOAPAction", soap_action.as_str()),
            ];
            let (mut status, mut bytes) = self
                .client
                .request(
                    Method::POST,
                    &service.control_url,
                    &headers,
                    body.clone(),
                    MAX_DESCRIPTION_BYTES,
                )
                .await?;
            if status == StatusCode::METHOD_NOT_ALLOWED {
                (status, bytes) = self
                    .client
                    .request(
                        Method::from_bytes(b"M-POST").expect("valid HTTP method"),
                        &service.control_url,
                        &[
                            ("Content-Type", "text/xml; charset=\"utf-8\""),
                            (
                                "MAN",
                                "\"http://schemas.xmlsoap.org/soap/envelope/\"; ns=01",
                            ),
                            ("01-SOAPACTION", soap_action.as_str()),
                        ],
                        body,
                        MAX_DESCRIPTION_BYTES,
                    )
                    .await?;
            }
            parse_soap_response(name, status, &String::from_utf8_lossy(&bytes))
        };
        match tokio::time::timeout(UPNP_TIMEOUT, operation).await {
            Ok(Ok(values)) => Ok(values),
            Ok(Err(error @ CastError::UpnpAction(_))) => Err(error),
            Ok(Err(error)) => Err(action_failure(
                name,
                None,
                None,
                if is_transport_failure(&error) {
                    "HTTP transport failed"
                } else {
                    "Invalid SOAP response"
                },
                is_transport_failure(&error),
            )),
            Err(_) => Err(action_failure(name, None, None, "action timed out", true)),
        }
    }
}

fn same_renderer_host(candidate: &Url, location: &Url) -> bool {
    candidate.scheme() == "http"
        && candidate.host().is_some()
        && candidate.host() == location.host()
}

fn is_metadata_failure(error: &CastError) -> bool {
    matches!(action_code(error), Some(714 | 716 | 501))
}

fn child_text<'a>(node: Node<'a, 'a>, name: &str) -> Option<&'a str> {
    node.children()
        .find(|child| child.has_tag_name(name))
        .and_then(|child| child.text())
}

fn parse_soap_response(
    name: &str,
    status: StatusCode,
    text: &str,
) -> Result<HashMap<String, String>> {
    let document = Document::parse(text).map_err(|_| {
        action_failure(
            name,
            None,
            Some(status.as_u16()),
            &format!("returned HTTP {status} with invalid SOAP XML"),
            false,
        )
    })?;
    if let Some(fault) = document
        .descendants()
        .find(|node| node.has_tag_name("Fault"))
    {
        let value = |tag| {
            fault
                .descendants()
                .find(|node| node.has_tag_name(tag))
                .and_then(|node| node.text())
                .unwrap_or_default()
                .trim()
        };
        return Err(action_failure(
            name,
            value("errorCode").parse().ok(),
            Some(status.as_u16()),
            if value("errorDescription").is_empty() {
                value("faultstring")
            } else {
                value("errorDescription")
            },
            false,
        ));
    }
    if !status.is_success() {
        return Err(action_failure(
            name,
            None,
            Some(status.as_u16()),
            &format!("returned HTTP {status}"),
            false,
        ));
    }
    let response_name = format!("{name}Response");
    let response = document
        .descendants()
        .find(|node| node.has_tag_name(response_name.as_str()))
        .ok_or_else(|| {
            action_failure(
                name,
                None,
                Some(status.as_u16()),
                &format!("response is missing {response_name}"),
                false,
            )
        })?;
    Ok(response
        .children()
        .filter(Node::is_element)
        .map(|node| {
            (
                node.tag_name().name().to_owned(),
                node.text().unwrap_or_default().to_owned(),
            )
        })
        .collect())
}

pub fn is_transport_failure(error: &CastError) -> bool {
    match error {
        CastError::UpnpAction(failure) => failure.transport_failure,
        CastError::Network(_) | CastError::Transport(_) => true,
        CastError::Http(error) => {
            error.is_timeout() || error.is_connect() || error.is_body() || error.is_request()
        }
        _ => false,
    }
}

fn is_transition_failure(error: &CastError) -> bool {
    match error {
        CastError::UpnpAction(failure) => {
            failure.code == Some(701)
                || failure
                    .description
                    .to_ascii_lowercase()
                    .contains("transition not available")
        }
        _ => false,
    }
}

fn sink_supports_category(sink: &str, mime: &str) -> bool {
    let category = mime
        .split('/')
        .next()
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    if !matches!(category.as_str(), "audio" | "video" | "image") {
        return true;
    }
    let mut parsable = false;
    for entry in sink.split(',').filter(|entry| !entry.trim().is_empty()) {
        let fields: Vec<_> = entry.trim().splitn(4, ':').map(str::trim).collect();
        if fields.len() != 4
            || fields[0].is_empty()
            || fields[1].is_empty()
            || fields[3].is_empty()
            || !(fields[2] == "*"
                || fields[2]
                    .split_once('/')
                    .is_some_and(|(a, b)| !a.is_empty() && !b.is_empty()))
        {
            // A malformed list is not reliable enough to reject playback.
            return true;
        }
        parsable = true;
        if (fields[0] == "*" || fields[0].eq_ignore_ascii_case("http-get"))
            && (fields[2] == "*"
                || fields[2]
                    .split('/')
                    .next()
                    .unwrap_or_default()
                    .eq_ignore_ascii_case(&category))
        {
            return true;
        }
    }
    !parsable
}

fn escape_xml(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use wiremock::{
        Mock, MockServer, ResponseTemplate,
        matchers::{body_string_contains, header, method, path},
    };

    #[test]
    fn soap_diagnostics_are_structured_redacted_and_bounded() {
        let error = parse_soap_response(
            "SetAVTransportURI",
            StatusCode::INTERNAL_SERVER_ERROR,
            &soap_fault(
                "501",
                &format!(
                    "Failed URL=(https://user:password@example.test/movie?token=secret) {}",
                    "é".repeat(150)
                ),
            ),
        )
        .unwrap_err();
        let CastError::UpnpAction(failure) = &error else {
            panic!("typed fault expected");
        };
        assert_eq!(failure.action, "SetAVTransportURI");
        assert_eq!(failure.code, Some(501));
        assert_eq!(failure.http_status, Some(500));
        assert!(failure.description.chars().count() <= 120);
        assert!(failure.description.contains("[URL]"));
        assert!(!error.to_string().contains("secret"));
        assert!(!error.to_string().contains("https://"));
        let error =
            parse_soap_response("Play", StatusCode::BAD_GATEWAY, "unparseable gateway error")
                .unwrap_err();
        let CastError::UpnpAction(failure) = error else {
            panic!("typed HTTP error expected");
        };
        assert_eq!(failure.code, None);
        assert_eq!(failure.http_status, Some(502));
        assert!(!failure.transport_failure);
        assert_eq!(sanitize_description("a\n b\t c"), "a b c");
    }

    #[tokio::test]
    async fn network_handle_transport_covers_description_preflight_and_both_services() {
        let server = MockServer::start().await;
        let ordinary = test_renderer(&server, true).await;
        let renderer = Renderer::load_on_network(&ordinary.location(), Some(42))
            .await
            .unwrap();
        for action in ["GetProtocolInfo", "Play", "SetVolume"] {
            Mock::given(header(
                "SOAPAction",
                format!(
                    "\"urn:schemas-upnp-org:service:{}:1#{action}\"",
                    match action {
                        "GetProtocolInfo" => "ConnectionManager",
                        "SetVolume" => "RenderingControl",
                        _ => "AVTransport",
                    }
                ),
            ))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_string(soap_response(action, "<Sink>*:*:video/mp4:*</Sink>")),
            )
            .expect(1)
            .mount(&server)
            .await;
        }
        renderer.preflight("video/mp4").await.unwrap();
        renderer.play().await.unwrap();
        renderer.set_volume(0.5).await.unwrap();
        server.verify().await;
    }

    pub(crate) fn soap_response(action: &str, values: &str) -> String {
        format!(
            r#"<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:{action}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">{values}</u:{action}Response></s:Body></s:Envelope>"#
        )
    }

    pub(crate) fn soap_fault(code: &str, description: &str) -> String {
        format!(
            r#"<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>{code}</errorCode><errorDescription>{description}</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"#
        )
    }

    pub(crate) async fn test_renderer(server: &MockServer, optional_services: bool) -> Renderer {
        let mut services = vec!["AVTransport"];
        if optional_services {
            services.extend(["ConnectionManager", "RenderingControl"]);
        }
        let services = services.into_iter().map(|name| format!(r#"<service><serviceType>urn:schemas-upnp-org:service:{name}:1</serviceType><controlURL>{name}</controlURL></service>"#)).collect::<String>();
        Mock::given(method("GET")).and(path("/device.xml"))
            .respond_with(ResponseTemplate::new(200).set_body_string(format!(r#"<root><URLBase>{}/controls/</URLBase><device><friendlyName>Test TV</friendlyName><serviceList>{services}</serviceList></device></root>"#, server.uri())))
            .mount(server).await;
        Renderer::load(&format!("{}/device.xml", server.uri()))
            .await
            .unwrap()
    }

    async fn mount_description(server: &MockServer, base: Option<&str>, controls: &[(&str, &str)]) {
        let base = base
            .map(|base| format!("<URLBase>{base}</URLBase>"))
            .unwrap_or_default();
        let services = controls.iter().map(|(name, control)| format!(
            "<service><serviceType>urn:schemas-upnp-org:service:{name}:1</serviceType><controlURL>{control}</controlURL></service>"
        )).collect::<String>();
        Mock::given(method("GET")).and(path("/device.xml"))
            .respond_with(ResponseTemplate::new(200).set_body_string(format!(
                "<root>{base}<device><friendlyName>Test TV</friendlyName><serviceList>{services}</serviceList></device></root>"
            )))
            .mount(server).await;
    }

    #[test]
    fn control_urls_remain_http_on_the_location_host() {
        let location = Url::parse("http://192.0.2.12:1400/device.xml").unwrap();
        assert!(same_renderer_host(
            &Url::parse("http://192.0.2.12:1500/control").unwrap(),
            &location
        ));
        for value in [
            "http://127.0.0.1/control",
            "http://192.0.2.1/control",
            "http://example.com/control",
            "https://192.0.2.12/control",
        ] {
            assert!(!same_renderer_host(&Url::parse(value).unwrap(), &location));
        }
    }

    #[tokio::test]
    async fn foreign_urlbase_is_ignored() {
        let server = MockServer::start().await;
        mount_description(
            &server,
            Some("http://example.invalid/elsewhere/"),
            &[("AVTransport", "control")],
        )
        .await;
        let renderer = Renderer::load(&format!("{}/device.xml", server.uri()))
            .await
            .unwrap();
        assert_eq!(
            renderer.av_transport.control_url.as_str(),
            format!("{}/control", server.uri())
        );
        Mock::given(method("POST"))
            .and(path("/control"))
            .respond_with(ResponseTemplate::new(200).set_body_string(soap_response("Play", "")))
            .expect(1)
            .mount(&server)
            .await;
        renderer.play().await.unwrap();
        server.verify().await;
    }

    #[tokio::test]
    async fn foreign_or_non_http_avtransport_is_rejected_and_optional_services_dropped() {
        for control in [
            "http://example.invalid/control",
            "https://127.0.0.1/control",
        ] {
            let server = MockServer::start().await;
            mount_description(&server, None, &[("AVTransport", control)]).await;
            let error = Renderer::load(&format!("{}/device.xml", server.uri()))
                .await
                .unwrap_err();
            assert!(
                error
                    .to_string()
                    .contains("AVTransport control URL must use HTTP on the LOCATION host")
            );
            let server = MockServer::start().await;
            mount_description(
                &server,
                None,
                &[
                    ("AVTransport", "/control"),
                    ("ConnectionManager", control),
                    ("RenderingControl", control),
                ],
            )
            .await;
            let renderer = Renderer::load(&format!("{}/device.xml", server.uri()))
                .await
                .unwrap();
            assert!(renderer.connection_manager.is_none());
            assert!(renderer.rendering_control.is_none());
            assert!(!renderer.supports_volume());
            renderer.preflight("video/mp4").await.unwrap();
            assert_eq!(server.received_requests().await.unwrap().len(), 1);
        }
    }

    #[tokio::test]
    async fn same_host_control_url_may_use_a_different_port() {
        let description = MockServer::start().await;
        let control = MockServer::start().await;
        mount_description(
            &description,
            None,
            &[("AVTransport", &format!("{}/control", control.uri()))],
        )
        .await;
        Mock::given(method("POST"))
            .and(path("/control"))
            .respond_with(ResponseTemplate::new(200).set_body_string(soap_response("Play", "")))
            .expect(1)
            .mount(&control)
            .await;
        let renderer = Renderer::load(&format!("{}/device.xml", description.uri()))
            .await
            .unwrap();
        renderer.play().await.unwrap();
        control.verify().await;
    }

    #[tokio::test]
    async fn description_redirects_are_not_followed() {
        let server = MockServer::start().await;
        let target = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/redirect.xml"))
            .respond_with(
                ResponseTemplate::new(302)
                    .insert_header("Location", format!("{}/device.xml", target.uri())),
            )
            .mount(&server)
            .await;
        let error = Renderer::load(&format!("{}/redirect.xml", server.uri()))
            .await
            .unwrap_err();
        assert!(error.to_string().contains("302"));
        assert!(target.received_requests().await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn oversized_description_is_rejected() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/large.xml"))
            .respond_with(
                ResponseTemplate::new(200).set_body_bytes(vec![b' '; MAX_DESCRIPTION_BYTES + 1]),
            )
            .mount(&server)
            .await;
        let error = Renderer::load(&format!("{}/large.xml", server.uri()))
            .await
            .unwrap_err();
        assert!(error.to_string().contains("exceeds 512 KiB"));
    }

    #[tokio::test]
    async fn preflight_timeout_is_permissive_and_capped_at_three_seconds() {
        let server = MockServer::start().await;
        let renderer = test_renderer(&server, true).await;
        assert!(renderer.supports_volume());
        Mock::given(method("POST"))
            .and(path("/controls/ConnectionManager"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_delay(std::time::Duration::from_secs(5))
                    .set_body_string(soap_response(
                        "GetProtocolInfo",
                        "<Sink>http-get:*:audio/flac:*</Sink>",
                    )),
            )
            .mount(&server)
            .await;
        let start = std::time::Instant::now();
        renderer.preflight("video/mp4").await.unwrap();
        assert!(start.elapsed() < std::time::Duration::from_secs(4));
    }

    #[tokio::test]
    async fn metadata_faults_retry_once_with_empty_metadata_when_allowed() {
        for code in ["714", "716", "501"] {
            let server = MockServer::start().await;
            let renderer = test_renderer(&server, false).await;
            Mock::given(method("POST"))
                .and(body_string_contains("&lt;DIDL-Lite"))
                .respond_with(
                    ResponseTemplate::new(500)
                        .set_body_string(soap_fault(code, "Metadata rejected")),
                )
                .expect(1)
                .mount(&server)
                .await;
            Mock::given(method("POST"))
                .and(body_string_contains(
                    "<CurrentURIMetaData></CurrentURIMetaData>",
                ))
                .respond_with(
                    ResponseTemplate::new(200)
                        .set_body_string(soap_response("SetAVTransportURI", "")),
                )
                .expect(1)
                .mount(&server)
                .await;
            renderer
                .set_media_uri_with_metadata_fallback(
                    "http://example.test/movie.mp4",
                    "<DIDL-Lite/>",
                    true,
                )
                .await
                .unwrap();
            server.verify().await;
        }
    }

    #[tokio::test]
    async fn metadata_retry_is_bounded_and_disabled_for_explicit_metadata() {
        for allowed in [false, true] {
            let server = MockServer::start().await;
            let renderer = test_renderer(&server, false).await;
            Mock::given(method("POST"))
                .and(path("/controls/AVTransport"))
                .respond_with(
                    ResponseTemplate::new(500)
                        .set_body_string(soap_fault("714", "Illegal MIME-type")),
                )
                .expect(if allowed { 2 } else { 1 })
                .mount(&server)
                .await;
            let error = renderer
                .set_media_uri_with_metadata_fallback(
                    "http://example.test/movie.mp4",
                    "<DIDL-Lite/>",
                    allowed,
                )
                .await
                .unwrap_err();
            assert!(error.to_string().contains("714: Illegal MIME-type"));
            server.verify().await;
        }
    }

    #[test]
    fn protocol_info_is_permissive_but_rejects_clear_category_mismatches() {
        for sink in [
            "",
            "garbage",
            "http-get:*:audio/flac:*",
            "http-get:*:*:*",
            "HTTP-GET:*:VIDEO/MP4:*",
            "*:*:video/mp4:*",
            "*:*:*:*",
        ] {
            let mime = if sink.contains("audio") {
                "audio/wav"
            } else {
                "video/quicktime"
            };
            assert!(sink_supports_category(sink, mime), "{sink}");
        }
        assert!(!sink_supports_category(
            "http-get:*:audio/flac:*,http-get:*:image/png:*",
            "video/mp4"
        ));
        assert!(!sink_supports_category(
            "rtsp-rtp-udp:*:video/mp4:*",
            "video/mp4"
        ));
        assert!(sink_supports_category(
            "http-get:*:audio/mpeg:*,invalid",
            "video/mp4"
        ));
        assert!(sink_supports_category(
            "http-get:*:audio/mpeg:*",
            "application/x-mpegURL"
        ));
    }

    #[test]
    fn faults_preserve_details_and_only_retry_transport_or_transition_failures() {
        let error = parse_soap_response(
            "SetAVTransportURI",
            StatusCode::INTERNAL_SERVER_ERROR,
            &soap_fault(" 701 ", "Transition not available"),
        )
        .unwrap_err();
        assert!(error.to_string().contains("701: Transition not available"));
        assert!(is_transition_failure(&error));
        assert!(!is_transport_failure(&error));
        let error = parse_soap_response(
            "SetAVTransportURI",
            StatusCode::INTERNAL_SERVER_ERROR,
            &soap_fault("714", "Illegal MIME-type"),
        )
        .unwrap_err();
        assert!(error.to_string().contains("714: Illegal MIME-type"));
        assert!(!is_transition_failure(&error));
        assert!(!is_transport_failure(&error));
        assert!(is_transport_failure(&CastError::Transport(
            "timeout".into()
        )));
        assert!(is_transport_failure(&CastError::Network(
            std::io::Error::from(std::io::ErrorKind::ConnectionReset)
        )));
        assert!(!is_transport_failure(&CastError::Protocol(
            "HTTP 405".into()
        )));
    }

    #[tokio::test]
    async fn soap_405_uses_mpost_with_namespaced_action() {
        let server = MockServer::start().await;
        let renderer = test_renderer(&server, false).await;
        Mock::given(method("POST"))
            .and(path("/controls/AVTransport"))
            .respond_with(ResponseTemplate::new(405))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("M-POST"))
            .and(path("/controls/AVTransport"))
            .and(header(
                "MAN",
                "\"http://schemas.xmlsoap.org/soap/envelope/\"; ns=01",
            ))
            .and(header(
                "01-SOAPACTION",
                "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            ))
            .and(body_string_contains("a?x=1&amp;y=2"))
            .and(body_string_contains("&lt;DIDL-Lite"))
            .respond_with(
                ResponseTemplate::new(200).set_body_string(soap_response("SetAVTransportURI", "")),
            )
            .expect(1)
            .mount(&server)
            .await;
        renderer
            .set_media_uri("http://example.test/a?x=1&y=2", "<DIDL-Lite/>")
            .await
            .unwrap();
        let requests = server.received_requests().await.unwrap();
        assert!(!requests.last().unwrap().headers.contains_key("soapaction"));
        server.verify().await;
    }

    #[tokio::test]
    async fn transition_fault_stops_and_retries_once() {
        let server = MockServer::start().await;
        let renderer = test_renderer(&server, false).await;
        let action = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"";
        Mock::given(method("POST"))
            .and(header("SOAPAction", action))
            .respond_with(
                ResponseTemplate::new(500)
                    .set_body_string(soap_fault("701", "Transition not available")),
            )
            .up_to_n_times(1)
            .with_priority(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(header(
                "SOAPAction",
                "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"",
            ))
            .respond_with(ResponseTemplate::new(200).set_body_string(soap_response("Stop", "")))
            .expect(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(header("SOAPAction", action))
            .respond_with(
                ResponseTemplate::new(200).set_body_string(soap_response("SetAVTransportURI", "")),
            )
            .with_priority(2)
            .expect(1)
            .mount(&server)
            .await;
        renderer
            .set_media_uri("http://example.test/movie", "<DIDL-Lite/>")
            .await
            .unwrap();
        let requests = server.received_requests().await.unwrap();
        let actions: Vec<_> = requests
            .iter()
            .skip(1)
            .map(|request| request.headers["soapaction"].to_str().unwrap())
            .collect();
        assert!(actions[0].contains("SetAVTransportURI"));
        assert!(actions[1].contains("Stop"));
        assert!(actions[2].contains("SetAVTransportURI"));
        server.verify().await;
    }

    #[tokio::test]
    async fn repeated_transition_fault_is_bounded_and_preserves_fault() {
        let server = MockServer::start().await;
        let renderer = test_renderer(&server, false).await;
        Mock::given(method("POST"))
            .and(header(
                "SOAPAction",
                "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            ))
            .respond_with(
                ResponseTemplate::new(500)
                    .set_body_string(soap_fault("701", "Transition not available")),
            )
            .expect(2)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(header(
                "SOAPAction",
                "\"urn:schemas-upnp-org:service:AVTransport:1#Stop\"",
            ))
            .respond_with(ResponseTemplate::new(200).set_body_string(soap_response("Stop", "")))
            .expect(1)
            .mount(&server)
            .await;
        assert!(
            renderer
                .set_media_uri("http://example.test/movie", "<DIDL-Lite/>")
                .await
                .unwrap_err()
                .to_string()
                .contains("701: Transition not available")
        );
        server.verify().await;
    }

    #[tokio::test]
    async fn transport_timeout_retries_once() {
        let server = MockServer::start().await;
        let renderer = test_renderer(&server, false).await;
        Mock::given(method("POST"))
            .and(path("/controls/AVTransport"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_delay(UPNP_TIMEOUT + std::time::Duration::from_secs(1))
                    .set_body_string(soap_response("SetAVTransportURI", "")),
            )
            .up_to_n_times(1)
            .with_priority(1)
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/controls/AVTransport"))
            .respond_with(
                ResponseTemplate::new(200).set_body_string(soap_response("SetAVTransportURI", "")),
            )
            .with_priority(2)
            .expect(1)
            .mount(&server)
            .await;
        renderer
            .set_media_uri("http://example.test/movie", "<DIDL-Lite/>")
            .await
            .unwrap();
        server.verify().await;
    }

    #[tokio::test]
    async fn optional_preflight_and_volume_services() {
        let server = MockServer::start().await;
        let renderer = test_renderer(&server, true).await;
        Mock::given(method("POST"))
            .and(path("/controls/ConnectionManager"))
            .respond_with(ResponseTemplate::new(200).set_body_string(soap_response(
                "GetProtocolInfo",
                "<Sink>http-get:*:audio/flac:*</Sink>",
            )))
            .mount(&server)
            .await;
        renderer.preflight("audio/wav").await.unwrap();
        assert!(
            renderer
                .preflight("video/mp4")
                .await
                .unwrap_err()
                .to_string()
                .contains("video/mp4")
        );
        Mock::given(method("POST"))
            .and(path("/controls/RenderingControl"))
            .and(header(
                "SOAPAction",
                "\"urn:schemas-upnp-org:service:RenderingControl:1#GetVolume\"",
            ))
            .and(body_string_contains("<Channel>Master</Channel>"))
            .respond_with(ResponseTemplate::new(200).set_body_string(soap_response(
                "GetVolume",
                "<CurrentVolume>24</CurrentVolume>",
            )))
            .mount(&server)
            .await;
        assert_eq!(renderer.volume().await.unwrap(), 0.24);
        Mock::given(method("POST"))
            .and(path("/controls/RenderingControl"))
            .and(header(
                "SOAPAction",
                "\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"",
            ))
            .and(body_string_contains("<DesiredVolume>35</DesiredVolume>"))
            .respond_with(
                ResponseTemplate::new(200).set_body_string(soap_response("SetVolume", "")),
            )
            .expect(1)
            .mount(&server)
            .await;
        renderer.set_volume(0.35).await.unwrap();
        server.verify().await;
        let server = MockServer::start().await;
        let renderer = test_renderer(&server, false).await;
        renderer.preflight("video/mp4").await.unwrap();
        assert!(
            renderer
                .set_volume(0.5)
                .await
                .unwrap_err()
                .to_string()
                .contains("not available")
        );
        let server = MockServer::start().await;
        let renderer = test_renderer(&server, true).await;
        // ConnectionManager itself failing must never block playback.
        renderer.preflight("video/mp4").await.unwrap();
    }

    #[test]
    fn escapes_media_values_for_soap() {
        assert_eq!(
            escape_xml("https://example.test/a?x=1&y=<two>\"'"),
            "https://example.test/a?x=1&amp;y=&lt;two&gt;&quot;&apos;"
        );
    }

    #[tokio::test]
    async fn loads_renderer_and_executes_avtransport_action() {
        let server = MockServer::start().await;
        let description = r#"<?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Test Renderer</friendlyName>
                <serviceList><service>
                  <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                  <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                  <SCPDURL>/avtransport.xml</SCPDURL>
                  <controlURL>/control/avtransport</controlURL>
                  <eventSubURL>/event/avtransport</eventSubURL>
                </service></serviceList>
              </device>
            </root>"#;
        Mock::given(method("GET"))
            .and(path("/device.xml"))
            .respond_with(ResponseTemplate::new(200).set_body_string(description))
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/control/avtransport"))
            .and(header(
                "soapaction",
                "\"urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo\"",
            ))
            .and(body_string_contains("<InstanceID>0</InstanceID>"))
            .respond_with(ResponseTemplate::new(200).set_body_string(
                r#"<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
                <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <CurrentTransportState>PLAYING</CurrentTransportState>
                  <CurrentTransportStatus>OK</CurrentTransportStatus>
                  <CurrentSpeed>1</CurrentSpeed>
                </u:GetTransportInfoResponse></s:Body></s:Envelope>"#,
            ))
            .mount(&server)
            .await;

        let renderer = Renderer::load(&format!("{}/device.xml", server.uri()))
            .await
            .unwrap();
        assert_eq!(renderer.friendly_name(), "Test Renderer");
        assert_eq!(
            renderer
                .transport_info()
                .await
                .unwrap()
                .get("CurrentTransportState")
                .map(String::as_str),
            Some("PLAYING")
        );
    }
}
