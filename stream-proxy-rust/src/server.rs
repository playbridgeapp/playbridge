use crate::config::Config;
use crate::crypto::{EncryptionHandler, ProxyData, ResourceGrant};
use crate::dash::DashManifestRewriter;
use crate::epg::EpgCache;
use crate::hls::{HlsPlaylistRewriter, HlsResourceKind};
use crate::local_file::FileGrantManager;
use crate::resource::{authorized_target, encode_target};
use crate::session::SessionManager;
use crate::upstream::{filter_upstream_headers, ConnectionEngine, NetworkPolicy, UpstreamResponse};
use axum::{
    body::Body,
    extract::{Path as AxumPath, Query, State},
    http::{header, HeaderMap, HeaderValue, Method, Request, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use futures::StreamExt;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncSeekExt};
use tokio_util::io::ReaderStream;
use tower_http::cors::{Any, CorsLayer};
use tracing::info;
use url::Url;

const MAX_MANIFEST_BYTES: usize = 4 * 1024 * 1024;

#[derive(Clone)]
pub struct AppState {
    pub password: String,
    pub session_manager: SessionManager,
    pub file_grants: FileGrantManager,
    pub epg_cache: EpgCache,
    pub engine: Arc<ConnectionEngine>,
    pub encryption_handler: EncryptionHandler,
}

#[derive(Deserialize)]
pub struct RegisterRequest {
    pub url: String,
    #[serde(default)]
    pub headers: HashMap<String, String>,
    #[serde(default, alias = "contentType")]
    pub content_type: Option<String>,
}

#[derive(Serialize)]
pub struct RegisterResponse {
    pub proxy_url: String,
    pub encrypted_url: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RegisteredMedia {
    pub id: String,
    pub url: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub encrypted_url: Option<String>,
}

#[derive(Deserialize)]
pub struct EpgQuery {
    pub uri: Option<String>,
}

#[derive(Clone)]
pub struct ProxyService {
    state: AppState,
}

impl ProxyService {
    pub fn new(password: String, ffmpeg_path: Option<String>) -> Self {
        Self::with_engine(password, Arc::new(ConnectionEngine::new(ffmpeg_path)))
    }

    pub fn with_engine(password: String, engine: Arc<ConnectionEngine>) -> Self {
        let encryption_handler = EncryptionHandler::new(password.as_bytes());
        Self {
            state: AppState {
                password,
                session_manager: SessionManager::new(),
                file_grants: FileGrantManager::new(),
                epg_cache: EpgCache::new(Duration::from_secs(14400)),
                engine,
                encryption_handler,
            },
        }
    }

    pub fn router(&self) -> Router {
        self.router_with_demo(true)
    }

    pub fn router_with_demo(&self, enable_demo: bool) -> Router {
        // Browser receivers need CORS on bearer-scoped media, not management APIs.
        let cors = CorsLayer::new()
            .allow_origin(Any)
            .allow_methods(Any)
            .allow_headers(Any)
            .expose_headers([
                header::CONTENT_LENGTH,
                header::CONTENT_RANGE,
                header::ACCEPT_RANGES,
            ]);

        let mut management = Router::new();
        if enable_demo {
            management = management
                .route("/", get(demo_html_handler))
                .route("/demo.html", get(demo_html_handler));
        }
        let media = Router::new()
            .route("/s/*path", get(stateful_proxy_handler))
            .route("/proxy/*path", get(encrypted_proxy_handler))
            .route(
                "/media/*path",
                get(local_file_handler).head(local_file_handler),
            )
            .layer(cors);
        management
            .route("/health", get(health_handler))
            .route("/ping", get(health_handler))
            .route("/register", post(register_handler))
            .route("/epg", get(epg_handler))
            .merge(media)
            .layer(middleware::from_fn_with_state(
                self.state.clone(),
                auth_middleware,
            ))
            .with_state(self.state.clone())
    }

    pub fn register_remote(
        &self,
        base_url: &str,
        original_url: String,
        headers: HashMap<String, String>,
    ) -> Result<RegisteredMedia, String> {
        self.register_remote_with_content_type(base_url, original_url, headers, None)
    }

    pub fn register_remote_with_content_type(
        &self,
        base_url: &str,
        original_url: String,
        headers: HashMap<String, String>,
        content_type: Option<&str>,
    ) -> Result<RegisteredMedia, String> {
        self.register_remote_with_network_policy(
            base_url,
            original_url,
            headers,
            content_type,
            None,
        )
    }

    /// DNS-informed approval is available only to native callers, not HTTP routes.
    pub async fn register_native_remote_with_content_type(
        &self,
        base_url: &str,
        original_url: String,
        headers: HashMap<String, String>,
        content_type: Option<&str>,
    ) -> Result<RegisteredMedia, String> {
        let parsed = Url::parse(&original_url).map_err(|_| "invalid media URL")?;
        if original_url.len() > 8192
            || !matches!(parsed.scheme(), "http" | "https")
            || !parsed.username().is_empty()
            || parsed.password().is_some()
        {
            return Err("only bounded HTTP(S) media URLs without userinfo can be proxied".into());
        }
        let policy = NetworkPolicy::for_native_registered_media(&original_url).await?;
        self.register_remote_with_network_policy(
            base_url,
            original_url,
            headers,
            content_type,
            Some(policy),
        )
    }

    pub fn register_remote_with_policy(
        &self,
        base_url: &str,
        original_url: String,
        headers: HashMap<String, String>,
        content_type: Option<&str>,
        allowed_private_origins: Vec<String>,
    ) -> Result<RegisteredMedia, String> {
        self.register_remote_with_network_policy(
            base_url,
            original_url,
            headers,
            content_type,
            Some(NetworkPolicy::new(allowed_private_origins)?),
        )
    }

    fn register_remote_with_network_policy(
        &self,
        base_url: &str,
        original_url: String,
        headers: HashMap<String, String>,
        content_type: Option<&str>,
        network_policy: Option<NetworkPolicy>,
    ) -> Result<RegisteredMedia, String> {
        let parsed = Url::parse(&original_url).map_err(|error| error.to_string())?;
        if original_url.len() > 8_192
            || !matches!(parsed.scheme(), "http" | "https")
            || !parsed.username().is_empty()
            || parsed.password().is_some()
        {
            return Err("only bounded HTTP(S) media URLs without userinfo can be proxied".into());
        }
        let network_policy = match network_policy {
            Some(policy) => policy,
            None => NetworkPolicy::for_registered_media(&original_url)?,
        };
        let session = self.state.session_manager.register(
            original_url.clone(),
            headers.clone(),
            Some(network_policy.clone()),
        )?;
        let filename = registered_media_filename(&parsed, content_type);
        let proxy_url = format!(
            "{}/s/{}/{}",
            base_url.trim_end_matches('/'),
            session.id,
            urlencoding::encode(&filename)
        );
        let proxy_data = ProxyData {
            session_id: session.id.clone(),
            credential_url: original_url.clone(),
            network_policy,
            destination: original_url,
            request_headers: (!headers.is_empty()).then_some(headers),
            exp: None,
            ip: None,
        };
        let encrypted_token = self.state.encryption_handler.encrypt(&proxy_data)?;
        let route_kind = registered_media_route_kind(&filename);
        let encrypted_url = format!(
            "{}/proxy/{}/{}?token={}",
            base_url.trim_end_matches('/'),
            route_kind,
            urlencoding::encode(&filename),
            encrypted_token
        );
        Ok(RegisteredMedia {
            id: session.id,
            url: proxy_url,
            encrypted_url: Some(encrypted_url),
        })
    }

    pub fn register_file(
        &self,
        base_url: &str,
        path: impl AsRef<Path>,
        content_type: Option<String>,
        ttl: Duration,
    ) -> Result<RegisteredMedia, String> {
        let grant = self.state.file_grants.register(path, content_type, ttl)?;
        Ok(RegisteredMedia {
            id: grant.id.clone(),
            url: format!(
                "{}/media/{}/{}",
                base_url.trim_end_matches('/'),
                grant.id,
                urlencoding::encode(&grant.filename)
            ),
            encrypted_url: None,
        })
    }

    /// Native owner renewal, never performed by media requests.
    pub fn renew(&self, id: &str) -> bool {
        self.state.session_manager.renew(id) || self.state.file_grants.renew(id)
    }

    pub fn revoke(&self, id: &str) -> bool {
        self.state.session_manager.revoke(id) || self.state.file_grants.revoke(id)
    }

    pub fn clear(&self) {
        self.state.session_manager.clear();
        self.state.file_grants.clear();
    }
}

fn registered_media_filename(url: &Url, content_type: Option<&str>) -> String {
    let content_type = content_type.unwrap_or_default().to_ascii_lowercase();
    if content_type.contains("mpegurl") || content_type.contains("m3u8") {
        return "playlist.m3u8".to_string();
    }
    if content_type.contains("dash") || content_type.contains("mpd") {
        return "manifest.mpd".to_string();
    }
    if content_type.split(';').next().unwrap_or("").trim() == "video/mp4"
        || url
            .path()
            .trim_end_matches('/')
            .to_ascii_lowercase()
            .ends_with(".mp4")
    {
        return "media.mp4".to_string();
    }
    let lower = url.as_str().to_ascii_lowercase();
    if lower.contains(".mpd") || lower.contains("manifest/dash") {
        return "manifest.mpd".to_string();
    }
    if lower.contains(".m3u8") || lower.contains("manifest/hls") {
        return "playlist.m3u8".to_string();
    }
    url.path_segments()
        .and_then(|mut segments| segments.next_back())
        .filter(|value| !value.is_empty())
        .map(str::to_owned)
        .unwrap_or_else(|| "media".to_string())
}

fn registered_media_route_kind(filename: &str) -> &'static str {
    if filename.ends_with(".m3u8") {
        "hls"
    } else {
        "stream"
    }
}

pub fn create_router(config: Config) -> Result<(Router, String, u16), String> {
    let password = config.get_validated_password()?;
    let service = ProxyService::new(password, config.ffmpeg_path.clone());
    Ok((service.router(), config.address.clone(), config.port))
}

async fn demo_html_handler() -> axum::response::Html<&'static str> {
    axum::response::Html(include_str!("../demo.html"))
}

async fn health_handler() -> &'static str {
    "OK"
}

async fn auth_middleware(
    State(state): State<AppState>,
    req: Request<Body>,
    next: Next,
) -> Result<Response, StatusCode> {
    let path = req.uri().path();
    if path == "/"
        || path == "/demo.html"
        || path == "/health"
        || path == "/ping"
        || path.starts_with("/proxy")
        || path.starts_with("/s/")
        || path.starts_with("/media/")
    {
        return Ok(next.run(req).await);
    }

    let mut token = None;
    if let Some(query_str) = req.uri().query() {
        if let Ok(params) = serde_urlencoded::from_str::<HashMap<String, String>>(query_str) {
            token = params.get("token").cloned();
        }
    }

    if token.is_none() {
        if let Some(auth_header) = req.headers().get(header::AUTHORIZATION) {
            if let Ok(val) = auth_header.to_str() {
                token = Some(val.replace("Bearer ", "").trim().to_string());
            }
        }
    }

    match token {
        Some(t) if crate::crypto::same_secret(t.as_bytes(), state.password.as_bytes()) => {
            Ok(next.run(req).await)
        }
        _ => Err(StatusCode::FORBIDDEN),
    }
}

async fn register_handler(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<RegisterRequest>,
) -> Result<Json<RegisterResponse>, (StatusCode, String)> {
    let policy = NetworkPolicy::for_registered_media(&payload.url)
        .map_err(|_| (StatusCode::BAD_REQUEST, "Invalid media URL".to_string()))?;
    let parsed = Url::parse(&payload.url)
        .map_err(|_| (StatusCode::BAD_REQUEST, "Invalid media URL".to_string()))?;
    if !matches!(parsed.scheme(), "http" | "https")
        || !parsed.username().is_empty()
        || parsed.password().is_some()
    {
        return Err((StatusCode::BAD_REQUEST, "Invalid media URL".into()));
    }
    let session = state
        .session_manager
        .register(
            payload.url.clone(),
            payload.headers.clone(),
            Some(policy.clone()),
        )
        .map_err(|error| (StatusCode::TOO_MANY_REQUESTS, error))?;
    let host_str = headers
        .get(header::HOST)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("127.0.0.1:8888");

    let filename = Url::parse(&payload.url)
        .map(|url| registered_media_filename(&url, payload.content_type.as_deref()))
        .unwrap_or_else(|_| "media".to_string());
    let proxy_url = format!(
        "http://{host_str}/s/{}/{}",
        session.id,
        urlencoding::encode(&filename)
    );

    // Versioned authenticated playback capability; no unsigned-token fallback.
    let proxy_data = ProxyData {
        session_id: session.id.clone(),
        credential_url: payload.url.clone(),
        network_policy: policy,
        destination: payload.url,
        request_headers: if payload.headers.is_empty() {
            None
        } else {
            Some(payload.headers)
        },
        exp: None,
        ip: None,
    };

    let encrypted_token = state
        .encryption_handler
        .encrypt(&proxy_data)
        .map_err(|e| (StatusCode::INTERNAL_SERVER_ERROR, e))?;

    let route_kind = registered_media_route_kind(&filename);
    let encrypted_url = format!(
        "http://{}/proxy/{}/{}?token={}",
        host_str,
        route_kind,
        urlencoding::encode(&filename),
        encrypted_token
    );

    info!(
        "[stream-proxy] Registered stateful session {} for host {}",
        session.id, host_str
    );
    Ok(Json(RegisterResponse {
        proxy_url,
        encrypted_url,
    }))
}

async fn epg_handler(
    State(state): State<AppState>,
    Query(query): Query<EpgQuery>,
) -> Result<Response, (StatusCode, String)> {
    let uri_str = match query.uri {
        Some(ref u) if !u.trim().is_empty() => u.clone(),
        _ => {
            return Err((
                StatusCode::BAD_REQUEST,
                "Missing 'uri' parameter for EPG".to_string(),
            ))
        }
    };

    let policy = NetworkPolicy::for_registered_media(&uri_str)
        .map_err(|_| (StatusCode::BAD_REQUEST, "Invalid EPG destination".into()))?;
    crate::upstream::validate_http_destination(&uri_str, Some(&policy))
        .await
        .map_err(|_| (StatusCode::BAD_REQUEST, "Invalid EPG destination".into()))?;
    if let Some(cached) = state.epg_cache.get(&uri_str) {
        return Ok((
            [
                (header::CONTENT_TYPE, "application/xml"),
                (header::CACHE_CONTROL, "public, max-age=14400"),
            ],
            cached,
        )
            .into_response());
    }

    match state
        .engine
        .fetch_url_bytes_with_policy(&uri_str, &HashMap::new(), Some(policy))
        .await
    {
        Ok(bytes) => {
            state.epg_cache.insert(uri_str, bytes.clone());
            Ok((
                [
                    (header::CONTENT_TYPE, "application/xml"),
                    (header::CACHE_CONTROL, "public, max-age=14400"),
                ],
                bytes,
            )
                .into_response())
        }
        Err(e) => Err((
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Failed to fetch EPG: {}", e),
        )),
    }
}

/// Handler for Stateful Session Proxying (`/s/<session_id>/<filename>`)
async fn stateful_proxy_handler(
    State(state): State<AppState>,
    AxumPath(path_str): AxumPath<String>,
    Query(query_params): Query<Vec<(String, String)>>,
    incoming_headers: HeaderMap,
    req: Request<Body>,
) -> Result<Response, (StatusCode, String)> {
    let path_segments: Vec<&str> = path_str.split('/').filter(|s| !s.is_empty()).collect();
    if path_segments.is_empty() {
        return Err((StatusCode::NOT_FOUND, "Not Found".to_string()));
    }

    let session_id = path_segments[0];
    let session = match state.session_manager.get(session_id) {
        Some(s) => s,
        None => {
            return Err((
                StatusCode::FORBIDDEN,
                "Session expired or invalid".to_string(),
            ))
        }
    };

    if path_segments.len() != 2 {
        return Err(invalid_capability());
    }
    let target_url = match (
        single_query(&query_params, "uri")?,
        single_query(&query_params, "cap")?,
    ) {
        (None, None) => session.original_url.clone(),
        (Some(uri), Some(cap)) => {
            let grant = state
                .encryption_handler
                .decrypt_resource(cap)
                .map_err(|_| invalid_capability())?;
            if grant.session_id != session_id || !authorized_target(&grant.destination, uri) {
                return Err(invalid_capability());
            }
            uri.to_string()
        }
        _ => return Err(invalid_capability()),
    };

    let forward_headers = filter_upstream_headers(
        &session.headers,
        &incoming_headers,
        &target_url,
        &session.original_url,
        session_id,
    );
    let public_base_url = request_public_base_url(&incoming_headers);
    let wants_mpv_edl = req.uri().path().to_ascii_lowercase().ends_with(".edl");
    let is_hls =
        url_looks_like_hls(&target_url) || req.uri().path().to_lowercase().contains(".m3u8");
    let is_dash = is_dash_manifest(&target_url, req.uri().path());
    let hls_segment_mime = query_params
        .iter()
        .find(|(key, _)| key == "pb_hls")
        .and_then(|(_, value)| match value.as_str() {
            "ts" => Some("video/mp2t"),
            "fmp4" => Some("video/iso.segment"),
            _ => None,
        });

    if wants_mpv_edl {
        handle_stateful_dash_edl(
            &state,
            session_id,
            &target_url,
            &forward_headers,
            &public_base_url,
            session.network_policy,
        )
        .await
    } else if is_dash && !is_hls {
        handle_stateful_dash_manifest(
            &state,
            session_id,
            &target_url,
            &forward_headers,
            &public_base_url,
            session.network_policy,
        )
        .await
    } else {
        handle_stateful_unknown_or_segment(
            &state,
            session_id,
            &target_url,
            &forward_headers,
            &public_base_url,
            hls_segment_mime,
            session.network_policy,
        )
        .await
    }
}

/// Handler for MediaFlow AES-256 Encrypted Stateless Proxying (`/proxy/hls/...` or `/proxy/stream/...`)
async fn encrypted_proxy_handler(
    State(state): State<AppState>,
    AxumPath(_path_str): AxumPath<String>,
    Query(query_params): Query<Vec<(String, String)>>,
    incoming_headers: HeaderMap,
    req: Request<Body>,
) -> Result<Response, (StatusCode, String)> {
    if single_query(&query_params, "uri")?.is_some()
        || single_query(&query_params, "cap")?.is_some()
    {
        return Err(invalid_capability());
    }
    let token = match single_query(&query_params, "token")? {
        Some(val) if !val.is_empty() => val,
        _ => return Err(invalid_capability()),
    };

    let proxy_data = match state.encryption_handler.decrypt(token, None) {
        Ok(pd) => pd,
        Err(_) => return Err(invalid_capability()),
    };
    if state.session_manager.get(&proxy_data.session_id).is_none() {
        return Err(invalid_capability());
    }
    let target_url = match single_query(&query_params, "target")? {
        Some(target) if authorized_target(&proxy_data.destination, target) => target.to_string(),
        Some(_) => return Err(invalid_capability()),
        None if authorized_target(&proxy_data.destination, &proxy_data.destination) => {
            proxy_data.destination.clone()
        }
        None => return Err(invalid_capability()),
    };

    let session_headers = proxy_data.request_headers.clone().unwrap_or_default();
    let forward_headers = filter_upstream_headers(
        &session_headers,
        &incoming_headers,
        &target_url,
        &proxy_data.credential_url,
        "encrypted",
    );

    let is_hls =
        url_looks_like_hls(&target_url) || req.uri().path().to_lowercase().contains(".m3u8");
    let is_dash = is_dash_manifest(&target_url, req.uri().path());

    if is_dash && !is_hls {
        handle_encrypted_dash_manifest(&state, &target_url, &forward_headers, &proxy_data).await
    } else {
        // URL hints only trigger body inspection; non-manifests stay streamed.
        handle_encrypted_hls_playlist(&state, &target_url, &forward_headers, &proxy_data).await
    }
}

fn rewrite_stateful_hls(
    state: &AppState,
    session_id: &str,
    target_url: &str,
    public_base_url: &str,
    bytes: &[u8],
    network_policy: Option<NetworkPolicy>,
) -> Result<Response, (StatusCode, String)> {
    let content = String::from_utf8_lossy(strip_utf8_bom(bytes));
    // Guard against serving HTML/error pages as playlists (Brave demuxer parse errors).
    if !content.trim_start().starts_with("#EXTM3U") {
        return Err((
            StatusCode::BAD_GATEWAY,
            "Upstream did not return an HLS playlist (#EXTM3U)".to_string(),
        ));
    }
    let base_uri = Url::parse(target_url).map_err(|error| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Parse URL error: {error}"),
        )
    })?;

    // Absolute proxy URLs so VHS on the browser-receiver page (different port)
    // never resolves child playlists or LL-HLS parts against the CDN directly.
    let base = public_base_url.trim_end_matches('/');
    let playlist_uses_fmp4 = content.lines().any(|line| {
        line.trim_start()
            .to_ascii_uppercase()
            .starts_with("#EXT-X-MAP:")
    });
    // URI lines in a multivariant playlist point at child playlists, not
    // media segments. Only media-playlist URI lines should receive a segment
    // MIME/extension hint; otherwise a variant playlist can be made to look
    // like a .ts resource to strict HLS clients.
    let is_master_playlist = content.lines().any(|line| {
        let line = line.trim_start();
        line.starts_with("#EXT-X-STREAM-INF:") || line.starts_with("#EXT-X-MEDIA:")
    });
    let rewritten = HlsPlaylistRewriter::rewrite_with_context(
        &content,
        &base_uri,
        |resolved_target, resource_kind| {
            let hls_kind = match resource_kind {
                HlsResourceKind::Media if !is_master_playlist => {
                    hls_segment_kind(resolved_target, playlist_uses_fmp4)
                }
                HlsResourceKind::SegmentAttribute => {
                    hls_segment_kind(resolved_target, playlist_uses_fmp4)
                }
                HlsResourceKind::Media | HlsResourceKind::Attribute => None,
            };
            format!(
                "{}{}",
                base,
                stateful_item_url(state, session_id, resolved_target, hls_kind)
            )
        },
    );

    let prefetch_urls = crate::upstream::hls_media_segment_urls(&content, &base_uri, 3);
    if !prefetch_urls.is_empty() {
        if let Some(session) = state.session_manager.get(session_id) {
            state.engine.prefetch_segment_urls_with_policy(
                prefetch_urls,
                &session.headers,
                network_policy,
                &session.original_url,
            );
        }
    }

    Ok((
        [
            (header::CONTENT_TYPE, "application/vnd.apple.mpegurl"),
            (header::CACHE_CONTROL, "no-cache, no-store, must-revalidate"),
        ],
        rewritten,
    )
        .into_response())
}

/// Returns a Cast-safe container override only when the playlist or URI proves
/// it. Map-less playlists may contain packed audio or subtitles, so unknown and
/// known non-video extensions must retain their upstream MIME type. JPEG is the
/// explicit exception for supported CDNs that disguise MPEG-TS segment bytes.
fn hls_segment_kind(target: &str, playlist_uses_fmp4: bool) -> Option<&'static str> {
    if playlist_uses_fmp4 {
        return Some("fmp4");
    }
    let path = Url::parse(target)
        .ok()
        .map(|url| url.path().to_owned())
        .unwrap_or_else(|| target.split('?').next().unwrap_or(target).to_owned());
    let extension = Path::new(&path)
        .extension()
        .and_then(|value| value.to_str())
        .map(str::to_ascii_lowercase);
    match extension.as_deref() {
        Some("m4s" | "mp4" | "cmfv" | "cmfa") => Some("fmp4"),
        Some("ts" | "m2ts" | "mts" | "jpg" | "jpeg") => Some("ts"),
        _ => None,
    }
}

/// Extensionless HLS entry points are common on live CDNs. Inspect the upstream
/// response before treating an unknown URL as a media segment; otherwise the
/// unmodified master playlist sends the browser directly to authenticated CDN
/// child URLs and those requests fail with 403/CORS errors.
async fn handle_stateful_unknown_or_segment(
    state: &AppState,
    session_id: &str,
    target_url: &str,
    headers: &HashMap<String, String>,
    public_base_url: &str,
    hls_segment_mime: Option<&'static str>,
    network_policy: Option<NetworkPolicy>,
) -> Result<Response, (StatusCode, String)> {
    let mut upstream = state
        .engine
        .connect_upstream_with_policy(target_url, headers, network_policy.clone())
        .await
        .map_err(|error| {
            (
                StatusCode::BAD_GATEWAY,
                format!("Failed to fetch upstream media: {error}"),
            )
        })?;
    let effective = take_effective_url(&mut upstream.headers, target_url);

    // URL / content-type hints never decide: the body prefix does.
    let (is_hls, upstream) = sniff_hls(upstream).await?;
    if is_hls {
        let bytes = axum::body::to_bytes(upstream.body, MAX_MANIFEST_BYTES)
            .await
            .map_err(|error| {
                (
                    StatusCode::BAD_GATEWAY,
                    format!("Failed reading HLS playlist: {error}"),
                )
            })?;
        return rewrite_stateful_hls(
            state,
            session_id,
            &effective,
            public_base_url,
            &bytes,
            network_policy,
        );
    }

    Ok(upstream_into_response_with_content_type(
        target_url,
        upstream,
        hls_segment_mime,
    ))
}

fn strip_utf8_bom(bytes: &[u8]) -> &[u8] {
    bytes.strip_prefix(b"\xEF\xBB\xBF").unwrap_or(bytes)
}

/// Inspect only a bounded prefix, then replay every consumed chunk. Progressive
/// media/segments stay streamed. A response is HLS only when the upstream status
/// is successful and the body (after an optional UTF-8 BOM and whitespace)
/// starts with `#EXTM3U`; URL and content-type hints never replace this check.
async fn sniff_hls(
    mut upstream: UpstreamResponse,
) -> Result<(bool, UpstreamResponse), (StatusCode, String)> {
    if !upstream.status.is_success() {
        return Ok((false, upstream));
    }
    let mut stream = upstream.body.into_data_stream();
    let mut chunks = Vec::new();
    let mut probe = Vec::new();
    let mut is_hls = false;
    while let Some(chunk) = stream.next().await {
        let chunk =
            chunk.map_err(|_| (StatusCode::BAD_GATEWAY, "Upstream media unavailable".into()))?;
        if chunk.is_empty() {
            continue;
        }
        let count = chunk.len().min(4096usize.saturating_sub(probe.len()));
        probe.extend_from_slice(&chunk[..count]);
        chunks.push(chunk);
        let prefix = strip_utf8_bom(&probe);
        let prefix = prefix
            .iter()
            .position(|byte| !byte.is_ascii_whitespace())
            .map(|offset| &prefix[offset..])
            .unwrap_or_default();
        if prefix.len() >= 7 || probe.len() >= 4096 {
            is_hls = prefix.starts_with(b"#EXTM3U");
            break;
        }
    }
    upstream.body = Body::from_stream(
        futures::stream::iter(chunks.into_iter().map(Ok::<_, axum::Error>)).chain(stream),
    );
    Ok((is_hls, upstream))
}

fn take_effective_url(headers: &mut HeaderMap, fallback: &str) -> String {
    headers
        .remove(crate::upstream::EFFECTIVE_URL_HEADER)
        .and_then(|value| value.to_str().ok().map(str::to_owned))
        .unwrap_or_else(|| fallback.to_owned())
}

fn url_looks_like_hls(value: &str) -> bool {
    let Ok(url) = Url::parse(value) else {
        return value.to_ascii_lowercase().contains(".m3u8");
    };
    let path = url.path().to_ascii_lowercase();
    if path.ends_with(".m3u8") || path.ends_with(".m3u") {
        return true;
    }
    url.query_pairs().any(|(name, value)| {
        let name = name.to_ascii_lowercase();
        let value = value.to_ascii_lowercase();
        matches!(name.as_str(), "type" | "format" | "ext")
            && (value == "m3u8" || value == "m3u" || value.contains("mpegurl"))
    })
}

async fn handle_stateful_dash_manifest(
    state: &AppState,
    session_id: &str,
    target_url: &str,
    headers: &HashMap<String, String>,
    public_base_url: &str,
    network_policy: Option<NetworkPolicy>,
) -> Result<Response, (StatusCode, String)> {
    let (bytes, effective) = state
        .engine
        .fetch_manifest_with_policy(target_url, headers, network_policy.clone())
        .await
        .map_err(|error| {
            (
                StatusCode::BAD_GATEWAY,
                format!("Failed to fetch DASH manifest: {error}"),
            )
        })?;
    let base_uri = Url::parse(&effective).map_err(|error| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Failed to parse DASH manifest URL: {error}"),
        )
    })?;
    let content = String::from_utf8_lossy(&bytes);
    let base = public_base_url.trim_end_matches('/');
    let rewritten = DashManifestRewriter::rewrite(&content, &base_uri, |resolved_target| {
        format!(
            "{}{}",
            base,
            stateful_item_url(state, session_id, resolved_target, None)
        )
    });

    Ok((
        [
            (header::CONTENT_TYPE, "application/dash+xml"),
            (header::CACHE_CONTROL, "no-cache, no-store, must-revalidate"),
        ],
        rewritten,
    )
        .into_response())
}

async fn handle_stateful_dash_edl(
    state: &AppState,
    session_id: &str,
    target_url: &str,
    headers: &HashMap<String, String>,
    public_base_url: &str,
    network_policy: Option<NetworkPolicy>,
) -> Result<Response, (StatusCode, String)> {
    let (bytes, effective) = state
        .engine
        .fetch_manifest_with_policy(target_url, headers, network_policy.clone())
        .await
        .map_err(|error| {
            (
                StatusCode::BAD_GATEWAY,
                format!("Failed to fetch DASH manifest for mpv: {error}"),
            )
        })?;
    let base_uri = Url::parse(&effective).map_err(|error| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Failed to parse DASH manifest URL: {error}"),
        )
    })?;
    let content = String::from_utf8_lossy(&bytes);
    let edl = DashManifestRewriter::mpv_edl(&content, &base_uri, |resolved_target| {
        format!(
            "{}{}",
            public_base_url,
            stateful_item_url(state, session_id, resolved_target, None)
        )
    })
    .map_err(|error| (StatusCode::UNPROCESSABLE_ENTITY, error))?;

    Ok((
        [
            (header::CONTENT_TYPE, "application/x-mpv-edl"),
            (header::CACHE_CONTROL, "no-cache, no-store, must-revalidate"),
        ],
        edl,
    )
        .into_response())
}

async fn local_file_handler(
    State(state): State<AppState>,
    AxumPath(path_str): AxumPath<String>,
    method: Method,
    headers: HeaderMap,
) -> Response {
    let grant_id = path_str.split('/').next().unwrap_or_default();
    let Some(grant) = state.file_grants.get(grant_id) else {
        return StatusCode::NOT_FOUND.into_response();
    };
    let mut file = match tokio::fs::File::open(&grant.path).await {
        Ok(file) => file,
        Err(_) => return StatusCode::NOT_FOUND.into_response(),
    };
    let total = match file.metadata().await {
        Ok(metadata) => metadata.len(),
        Err(_) => return StatusCode::NOT_FOUND.into_response(),
    };
    let range = match headers
        .get(header::RANGE)
        .and_then(|value| value.to_str().ok())
    {
        Some(value) => match parse_byte_range(value, total) {
            Some(range) => Some(range),
            None => {
                return Response::builder()
                    .status(StatusCode::RANGE_NOT_SATISFIABLE)
                    .header(header::CONTENT_RANGE, format!("bytes */{total}"))
                    .body(Body::empty())
                    .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
            }
        },
        None => None,
    };
    let (status, start, end) = range
        .map(|(start, end)| (StatusCode::PARTIAL_CONTENT, start, end))
        .unwrap_or_else(|| (StatusCode::OK, 0, total.saturating_sub(1)));
    let length = if total == 0 { 0 } else { end - start + 1 };
    if start > 0 && file.seek(std::io::SeekFrom::Start(start)).await.is_err() {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    let mut builder = Response::builder()
        .status(status)
        .header(header::CONTENT_TYPE, grant.content_type)
        .header(header::CONTENT_LENGTH, length)
        .header(header::ACCEPT_RANGES, "bytes")
        .header(header::CACHE_CONTROL, "private, no-store");
    if status == StatusCode::PARTIAL_CONTENT {
        builder = builder.header(
            header::CONTENT_RANGE,
            format!("bytes {start}-{end}/{total}"),
        );
    }
    let body = if method == Method::HEAD || length == 0 {
        Body::empty()
    } else {
        Body::from_stream(ReaderStream::new(file.take(length)))
    };
    builder
        .body(body)
        .unwrap_or_else(|_| StatusCode::INTERNAL_SERVER_ERROR.into_response())
}

fn parse_byte_range(value: &str, total: u64) -> Option<(u64, u64)> {
    let raw = value.strip_prefix("bytes=")?;
    if raw.contains(',') || total == 0 {
        return None;
    }
    let (start, end) = raw.split_once('-')?;
    if start.is_empty() {
        let suffix = end.parse::<u64>().ok()?;
        if suffix == 0 {
            return None;
        }
        return Some((total.saturating_sub(suffix.min(total)), total - 1));
    }
    let start = start.parse::<u64>().ok()?;
    if start >= total {
        return None;
    }
    let end = if end.is_empty() {
        total - 1
    } else {
        end.parse::<u64>().ok()?.min(total - 1)
    };
    (start <= end).then_some((start, end))
}

async fn handle_encrypted_hls_playlist(
    state: &AppState,
    target_url: &str,
    headers: &HashMap<String, String>,
    proxy_data: &ProxyData,
) -> Result<Response, (StatusCode, String)> {
    let mut upstream = state
        .engine
        .connect_upstream_with_policy(target_url, headers, Some(proxy_data.network_policy.clone()))
        .await
        .map_err(|_| (StatusCode::BAD_GATEWAY, "Upstream media unavailable".into()))?;
    let effective = take_effective_url(&mut upstream.headers, target_url);
    // URL / content-type hints never decide: the body prefix does.
    let (is_hls, upstream) = sniff_hls(upstream).await?;
    if !is_hls {
        return Ok(upstream_into_response(target_url, upstream));
    }
    let bytes = axum::body::to_bytes(upstream.body, MAX_MANIFEST_BYTES)
        .await
        .map_err(|_| (StatusCode::BAD_GATEWAY, "Invalid upstream manifest".into()))?;
    let content = String::from_utf8_lossy(strip_utf8_bom(&bytes));
    if !content.trim_start().starts_with("#EXTM3U") {
        return Err((StatusCode::BAD_GATEWAY, "Invalid upstream manifest".into()));
    }
    let base_uri = Url::parse(&effective).map_err(|_| {
        (
            StatusCode::BAD_GATEWAY,
            "Invalid upstream manifest URL".into(),
        )
    })?;
    let rewritten = HlsPlaylistRewriter::rewrite(&content, &base_uri, |resolved_target| {
        let resolved_uri = match Url::parse(resolved_target) {
            Ok(u) => u,
            Err(_) => return resolved_target.to_string(),
        };

        let filename = resolved_uri
            .path_segments()
            .and_then(|mut s| s.next_back())
            .unwrap_or("item");

        let mut item_data = proxy_data.clone();
        item_data.destination = resolved_target.to_string();

        encrypted_item_url(state, &item_data, filename, "hls")
    });

    let prefetch_urls = crate::upstream::hls_media_segment_urls(&content, &base_uri, 3);
    if !prefetch_urls.is_empty() {
        let session_headers = proxy_data.request_headers.clone().unwrap_or_default();
        state.engine.prefetch_segment_urls_with_policy(
            prefetch_urls,
            &session_headers,
            Some(proxy_data.network_policy.clone()),
            &proxy_data.credential_url,
        );
    }

    Ok((
        [
            (header::CONTENT_TYPE, "application/vnd.apple.mpegurl"),
            (header::CACHE_CONTROL, "no-cache, no-store, must-revalidate"),
        ],
        rewritten,
    )
        .into_response())
}

async fn handle_encrypted_dash_manifest(
    state: &AppState,
    target_url: &str,
    headers: &HashMap<String, String>,
    proxy_data: &ProxyData,
) -> Result<Response, (StatusCode, String)> {
    let (bytes, effective) = state
        .engine
        .fetch_manifest_with_policy(target_url, headers, Some(proxy_data.network_policy.clone()))
        .await
        .map_err(|error| {
            (
                StatusCode::BAD_GATEWAY,
                format!("Failed to fetch DASH manifest: {error}"),
            )
        })?;
    let base_uri = Url::parse(&effective).map_err(|error| {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Failed to parse DASH manifest URL: {error}"),
        )
    })?;
    let content = String::from_utf8_lossy(&bytes);
    let rewritten = DashManifestRewriter::rewrite(&content, &base_uri, |resolved_target| {
        let mut item_data = proxy_data.clone();
        item_data.destination = resolved_target.to_string();
        encrypted_item_url(
            state,
            &item_data,
            &target_filename(resolved_target),
            "stream",
        )
    });

    Ok((
        [
            (header::CONTENT_TYPE, "application/dash+xml"),
            (header::CACHE_CONTROL, "no-cache, no-store, must-revalidate"),
        ],
        rewritten,
    )
        .into_response())
}

fn upstream_into_response(target_url: &str, upstream: UpstreamResponse) -> Response {
    upstream_into_response_with_content_type(target_url, upstream, None)
}

fn upstream_into_response_with_content_type(
    target_url: &str,
    upstream: UpstreamResponse,
    content_type_override: Option<&'static str>,
) -> Response {
    let mime = mime_for(target_url);
    let mut response_builder = Response::builder().status(upstream.status);

    if let Some(headers_map) = response_builder.headers_mut() {
        headers_map.insert(header::CONTENT_TYPE, HeaderValue::from_static(mime));
        headers_map.insert(
            header::CACHE_CONTROL,
            HeaderValue::from_static("public, max-age=3600"),
        );
        for (k, v) in &upstream.headers {
            if k.as_str() == crate::upstream::EFFECTIVE_URL_HEADER {
                continue;
            }
            headers_map.insert(k.clone(), v.clone());
        }
        if let Some(content_type) = content_type_override {
            headers_map.insert(header::CONTENT_TYPE, HeaderValue::from_static(content_type));
        }
    }

    response_builder.body(upstream.body).unwrap_or_else(|_| {
        (StatusCode::INTERNAL_SERVER_ERROR, "Build response error").into_response()
    })
}

fn is_dash_manifest(target_url: &str, request_path: &str) -> bool {
    let target = target_url.to_ascii_lowercase();
    let path = request_path.to_ascii_lowercase();
    target.contains(".mpd")
        || target.contains("manifest/dash")
        || path.contains(".mpd")
        || path.contains("manifest/dash")
}

fn request_public_base_url(headers: &HeaderMap) -> String {
    let host = headers
        .get(header::HOST)
        .and_then(|value| value.to_str().ok())
        .unwrap_or("127.0.0.1");
    let candidate = format!("http://{host}");
    Url::parse(&candidate)
        .ok()
        .filter(|url| url.host_str().is_some())
        .map(|url| url.as_str().trim_end_matches('/').to_string())
        .unwrap_or_else(|| "http://127.0.0.1".to_string())
}

fn encrypted_item_url(
    state: &AppState,
    item: &ProxyData,
    filename: &str,
    route_kind: &str,
) -> String {
    let Ok(token) = state.encryption_handler.encrypt(item) else {
        return "/invalid-capability".into();
    };
    let mut url = format!(
        "/proxy/{route_kind}/{}?token={}",
        urlencoding::encode(filename),
        token
    );
    if item.destination.contains('$') {
        url.push_str("&target=");
        url.push_str(&encode_target(&item.destination));
    }
    url
}

fn invalid_capability() -> (StatusCode, String) {
    (StatusCode::FORBIDDEN, "Invalid proxy capability".into())
}

fn single_query<'a>(
    query: &'a [(String, String)],
    key: &str,
) -> Result<Option<&'a str>, (StatusCode, String)> {
    let mut values = query.iter().filter(|(name, _)| name == key);
    let value = values.next().map(|(_, value)| value.as_str());
    if values.next().is_some() {
        return Err(invalid_capability());
    }
    Ok(value)
}

fn stateful_item_url(
    state: &AppState,
    session_id: &str,
    resolved_target: &str,
    hls_segment_kind: Option<&str>,
) -> String {
    let filename = hls_segment_kind.map_or_else(
        || target_filename(resolved_target),
        |kind| hls_segment_filename(resolved_target, kind),
    );
    let encoded_target = encode_target(resolved_target);
    let grant = ResourceGrant {
        session_id: session_id.to_string(),
        destination: resolved_target.to_string(),
    };
    let Ok(cap) = state.encryption_handler.encrypt_resource(&grant) else {
        return "/invalid-capability".into();
    };
    let mut url = format!(
        "/s/{}/{}?uri={}&cap={}",
        session_id,
        urlencoding::encode(&filename),
        encoded_target,
        cap,
    );
    if let Some(kind) = hls_segment_kind {
        url.push_str("&pb_hls=");
        url.push_str(kind);
    }
    url
}

fn hls_segment_filename(target: &str, kind: &str) -> String {
    let filename = target_filename(target);
    let extension = match kind {
        "ts" => "ts",
        "fmp4" => "m4s",
        _ => return filename,
    };
    let stem = filename
        .rsplit_once('.')
        .map(|(stem, _)| stem)
        .filter(|stem| !stem.is_empty())
        .unwrap_or(&filename);
    format!("{stem}.{extension}")
}

fn target_filename(target: &str) -> String {
    Url::parse(target)
        .ok()
        .and_then(|url| {
            let segments = url
                .path_segments()?
                .filter(|value| !value.is_empty())
                .collect::<Vec<_>>();
            let filename = *segments.last()?;
            let parent = segments
                .get(segments.len().saturating_sub(2))
                .copied()
                .filter(|value| *value != filename);
            Some(match parent {
                Some(parent) => format!(
                    "{}-{}",
                    cast_safe_filename_component(parent),
                    cast_safe_filename_component(filename)
                ),
                None => cast_safe_filename_component(filename),
            })
        })
        .unwrap_or_else(|| "item".to_string())
}

fn cast_safe_filename_component(value: &str) -> String {
    value
        .chars()
        .take(64)
        .map(|character| {
            if character.is_ascii_alphanumeric() || matches!(character, '.' | '-' | '_') {
                character
            } else {
                '_'
            }
        })
        .collect()
}

fn mime_for(path: &str) -> &'static str {
    let lower = path.to_lowercase();
    if lower.contains(".m3u8") {
        "application/vnd.apple.mpegurl"
    } else if lower.contains(".mpd") {
        "application/dash+xml"
    } else if lower.contains(".m4s") {
        "video/iso.segment"
    } else if lower.contains(".ts") {
        "video/mp2t"
    } else if lower.contains(".mp4") {
        "video/mp4"
    } else {
        "application/octet-stream"
    }
}

#[cfg(test)]
mod manifest_sniff_tests {
    use super::*;
    use bytes::Bytes;

    fn response(body: Body) -> UpstreamResponse {
        UpstreamResponse {
            status: StatusCode::OK,
            headers: HeaderMap::new(),
            body,
        }
    }

    #[tokio::test]
    async fn hls_prefix_split_across_chunks_is_detected_and_replayed() {
        let parts = [" \n#EX", "TM", "3U\n#EXTINF:1,\nsegment.ts\n"];
        let body = Body::from_stream(futures::stream::iter(
            parts
                .into_iter()
                .map(|part| Ok::<_, axum::Error>(Bytes::from(part))),
        ));
        let (is_hls, upstream) = sniff_hls(response(body)).await.unwrap();
        assert!(is_hls);
        assert_eq!(
            axum::body::to_bytes(upstream.body, MAX_MANIFEST_BYTES)
                .await
                .unwrap(),
            parts.concat()
        );
    }

    #[tokio::test]
    async fn non_manifest_larger_than_manifest_limit_is_not_buffered_or_truncated() {
        let mut data = vec![b'v'; MAX_MANIFEST_BYTES + 32];
        data[0..7].copy_from_slice(b"not hls");
        let (is_hls, upstream) = sniff_hls(response(Body::from(data.clone()))).await.unwrap();
        assert!(!is_hls);
        assert_eq!(
            axum::body::to_bytes(upstream.body, data.len())
                .await
                .unwrap()
                .as_ref(),
            data.as_slice()
        );
        let (is_hls, upstream) = sniff_hls(response(Body::from("small"))).await.unwrap();
        assert!(!is_hls);
        assert_eq!(
            axum::body::to_bytes(upstream.body, 100).await.unwrap(),
            "small"
        );
    }

    #[tokio::test]
    async fn non_success_status_is_never_treated_as_hls_and_is_untouched() {
        let mut upstream = response(Body::from("#EXTM3U\n#EXTINF:1,\nsegment.ts\n"));
        upstream.status = StatusCode::NOT_FOUND;
        let (is_hls, upstream) = sniff_hls(upstream).await.unwrap();
        assert!(!is_hls);
        assert_eq!(upstream.status, StatusCode::NOT_FOUND);
        assert_eq!(
            axum::body::to_bytes(upstream.body, 1000).await.unwrap(),
            "#EXTM3U\n#EXTINF:1,\nsegment.ts\n"
        );
    }

    #[tokio::test]
    async fn bom_prefixed_manifest_is_detected_and_url_lists_are_not() {
        let (is_hls, _) = sniff_hls(response(Body::from("\u{feff}\n#EXTM3U\n")))
            .await
            .unwrap();
        assert!(is_hls);
        let (is_hls, upstream) = sniff_hls(response(Body::from("http://a/x\nhttp://a/y\n")))
            .await
            .unwrap();
        assert!(!is_hls);
        assert_eq!(
            axum::body::to_bytes(upstream.body, 100).await.unwrap(),
            "http://a/x\nhttp://a/y\n"
        );
    }
}
