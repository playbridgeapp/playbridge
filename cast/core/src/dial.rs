use reqwest::{Client, StatusCode, header::HeaderMap};
use roxmltree::Document;
use url::Url;

use crate::{CastError, Result};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DialDevice {
    pub friendly_name: String,
    pub manufacturer: Option<String>,
    pub model_name: Option<String>,
    pub application_url: Option<Url>,
}

impl DialDevice {
    pub fn is_roku(&self) -> bool {
        self.manufacturer
            .as_deref()
            .is_some_and(|value| value.to_ascii_lowercase().contains("roku"))
            || self
                .model_name
                .as_deref()
                .is_some_and(|value| value.to_ascii_lowercase().contains("roku"))
    }

    pub async fn fetch(location: &str, http: &Client) -> Result<Self> {
        let response = http.get(location).send().await?;
        if !response.status().is_success() {
            return Err(CastError::ReceiverHttp {
                operation: "DIAL device description",
                status: response.status(),
            });
        }
        let application_url = response
            .headers()
            .get("application-url")
            .and_then(|value| value.to_str().ok())
            .and_then(|value| Url::parse(location).ok()?.join(value).ok());
        let xml = response.text().await?;
        let document = Document::parse(&xml)
            .map_err(|error| CastError::Protocol(format!("invalid DIAL description: {error}")))?;
        let field = |name: &str| {
            document
                .descendants()
                .find(|node| node.is_element() && node.tag_name().name().eq_ignore_ascii_case(name))
                .and_then(|node| node.text())
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(ToOwned::to_owned)
        };
        Ok(Self {
            friendly_name: field("friendlyName").unwrap_or_else(|| "DIAL receiver".into()),
            manufacturer: field("manufacturer"),
            model_name: field("modelName"),
            application_url,
        })
    }
}

#[derive(Debug, Clone)]
pub struct DialClient {
    http: Client,
    application_url: Url,
}

impl DialClient {
    pub fn from_description_headers(description_url: &str, headers: &HeaderMap) -> Result<Self> {
        let description_url = Url::parse(description_url)?;
        let raw = headers
            .get("application-url")
            .and_then(|value| value.to_str().ok())
            .ok_or(CastError::MissingField("Application-URL response header"))?;
        let application_url = description_url.join(raw)?;
        Ok(Self {
            http: Client::new(),
            application_url,
        })
    }

    pub fn new(application_url: Url, http: Client) -> Self {
        Self {
            http,
            application_url,
        }
    }

    pub async fn status(&self, app_name: &str) -> Result<String> {
        let response = self.http.get(self.app_url(app_name)?).send().await?;
        checked_text(response, "DIAL status").await
    }

    pub async fn launch(&self, app_name: &str, payload: Option<&str>) -> Result<Option<Url>> {
        let mut request = self.http.post(self.app_url(app_name)?);
        if let Some(payload) = payload {
            request = request
                .header("content-type", "text/plain; charset=utf-8")
                .body(payload.to_owned());
        }
        let response = request.send().await?;
        if !response.status().is_success() {
            return Err(CastError::ReceiverHttp {
                operation: "DIAL launch",
                status: response.status(),
            });
        }
        Ok(response
            .headers()
            .get(reqwest::header::LOCATION)
            .and_then(|value| value.to_str().ok())
            .and_then(|location| self.application_url.join(location).ok()))
    }

    pub async fn stop(&self, app_name: &str) -> Result<()> {
        let response = self.http.delete(self.app_url(app_name)?).send().await?;
        if response.status().is_success() || response.status() == StatusCode::NOT_FOUND {
            Ok(())
        } else {
            Err(CastError::ReceiverHttp {
                operation: "DIAL stop",
                status: response.status(),
            })
        }
    }

    fn app_url(&self, app_name: &str) -> Result<Url> {
        if app_name.is_empty() || app_name.contains('/') || app_name == "." || app_name == ".." {
            return Err(CastError::MissingField("valid DIAL application name"));
        }
        Ok(self.application_url.join(app_name)?)
    }
}

async fn checked_text(response: reqwest::Response, operation: &'static str) -> Result<String> {
    if !response.status().is_success() {
        return Err(CastError::ReceiverHttp {
            operation,
            status: response.status(),
        });
    }
    Ok(response.text().await?)
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::{
        Mock, MockServer, ResponseTemplate,
        matchers::{method, path},
    };

    #[test]
    fn resolves_relative_application_url() {
        let mut headers = HeaderMap::new();
        headers.insert("application-url", "/dial/apps/".parse().unwrap());
        let client = DialClient::from_description_headers(
            "http://192.0.2.1:8060/device-description.xml",
            &headers,
        )
        .unwrap();
        assert_eq!(
            client.app_url("PlayBridge").unwrap().as_str(),
            "http://192.0.2.1:8060/dial/apps/PlayBridge"
        );
    }

    #[test]
    fn rejects_path_traversal_as_an_application_name() {
        let client = DialClient::new(
            Url::parse("http://192.0.2.1/dial/apps/").unwrap(),
            Client::new(),
        );
        assert!(client.app_url("../admin").is_err());
    }

    #[tokio::test]
    async fn exercises_dial_launch_status_and_stop() {
        let server = MockServer::start().await;
        Mock::given(method("POST"))
            .and(path("/dial/apps/PlayBridge"))
            .respond_with(
                ResponseTemplate::new(201).insert_header("Location", "/dial/apps/PlayBridge/run"),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/dial/apps/PlayBridge"))
            .respond_with(ResponseTemplate::new(200).set_body_string("<state>running</state>"))
            .mount(&server)
            .await;
        Mock::given(method("DELETE"))
            .and(path("/dial/apps/PlayBridge"))
            .respond_with(ResponseTemplate::new(200))
            .mount(&server)
            .await;

        let client = DialClient::new(
            Url::parse(&format!("{}/dial/apps/", server.uri())).unwrap(),
            Client::new(),
        );
        let launched = client
            .launch("PlayBridge", Some("pair=1234"))
            .await
            .unwrap();
        assert_eq!(
            launched.unwrap().as_str(),
            format!("{}/dial/apps/PlayBridge/run", server.uri())
        );
        assert!(
            client
                .status("PlayBridge")
                .await
                .unwrap()
                .contains("running")
        );
        client.stop("PlayBridge").await.unwrap();
    }
}
