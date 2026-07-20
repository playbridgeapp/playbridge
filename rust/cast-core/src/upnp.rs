use std::collections::HashMap;

use rupnp::{Device, ssdp::URN};

use crate::{CastError, Result};

const AV_TRANSPORT: URN = URN::service("schemas-upnp-org", "AVTransport", 1);

#[derive(Debug)]
pub struct Renderer {
    device: Device,
}

impl Renderer {
    pub async fn load(location: &str) -> Result<Self> {
        let uri = location.parse()?;
        let device = Device::from_url(uri).await?;
        if device.find_service(&AV_TRANSPORT).is_none() {
            return Err(CastError::MissingField("AVTransport service"));
        }
        Ok(Self { device })
    }

    pub fn friendly_name(&self) -> &str {
        self.device.friendly_name()
    }

    pub fn location(&self) -> String {
        self.device.url().to_string()
    }

    pub async fn set_media_uri(&self, media_uri: &str, metadata: &str) -> Result<()> {
        let arguments = format!(
            "<InstanceID>0</InstanceID><CurrentURI>{}</CurrentURI><CurrentURIMetaData>{}</CurrentURIMetaData>",
            escape_xml(media_uri),
            escape_xml(metadata)
        );
        self.action("SetAVTransportURI", &arguments).await?;
        Ok(())
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

    async fn action(&self, name: &str, arguments: &str) -> Result<HashMap<String, String>> {
        let service = self
            .device
            .find_service(&AV_TRANSPORT)
            .ok_or(CastError::MissingField("AVTransport service"))?;
        Ok(service.action(self.device.url(), name, arguments).await?)
    }
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
mod tests {
    use super::*;
    use wiremock::{
        Mock, MockServer, ResponseTemplate,
        matchers::{body_string_contains, header, method, path},
    };

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
