use crate::config::Config;
use crate::crypto::{EncryptionHandler, ProxyData};
use crate::dash::DashManifestRewriter;
use crate::epg::EpgCache;
use crate::hls::HlsPlaylistRewriter;
use crate::session::SessionManager;
use crate::upstream::{filter_upstream_headers, ConnectionEngine};
use axum::{
    body::Body,
    extract::{Path as AxumPath, Query, State},
    http::{header, HeaderMap, HeaderValue, Request, StatusCode},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use std::time::Duration;
use tower_http::cors::{Any, CorsLayer};
use tracing::info;
use url::Url;

#[derive(Clone)]
pub struct AppState {
    pub password: String,
    pub session_manager: SessionManager,
    pub epg_cache: EpgCache,
    pub engine: Arc<ConnectionEngine>,
    pub encryption_handler: EncryptionHandler,
}

#[derive(Deserialize)]
pub struct RegisterRequest {
    pub url: String,
    #[serde(default)]
    pub headers: HashMap<String, String>,
}

#[derive(Serialize)]
pub struct RegisterResponse {
    pub proxy_url: String,
    pub encrypted_url: String,
}

#[derive(Deserialize)]
pub struct EpgQuery {
    pub uri: Option<String>,
}

pub fn create_router(config: Config) -> Result<(Router, String, u16), String> {
    let password = config.get_validated_password()?;
    let encryption_handler = EncryptionHandler::new(password.as_bytes());

    let state = AppState {
        password: password.clone(),
        session_manager: SessionManager::new(),
        epg_cache: EpgCache::new(Duration::from_secs(14400)), // 4 hours
        engine: Arc::new(ConnectionEngine::new(config.ffmpeg_path.clone())),
        encryption_handler,
    };

    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any)
        .expose_headers([
            header::CONTENT_LENGTH,
            header::CONTENT_RANGE,
            header::ACCEPT_RANGES,
        ]);

    let app = Router::new()
        .route("/", get(demo_html_handler))
        .route("/demo.html", get(demo_html_handler))
        .route("/health", get(health_handler))
        .route("/ping", get(health_handler))
        .route("/register", post(register_handler))
        .route("/epg", get(epg_handler))
        .route("/s/*path", get(stateful_proxy_handler))
        .route("/proxy/*path", get(encrypted_proxy_handler))
        .layer(cors)
        .layer(middleware::from_fn_with_state(
            state.clone(),
            auth_middleware,
        ))
        .with_state(state);

    Ok((app, config.address.clone(), config.port))
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
        Some(t) if t == state.password => Ok(next.run(req).await),
        _ => Err(StatusCode::FORBIDDEN),
    }
}

async fn register_handler(
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(payload): Json<RegisterRequest>,
) -> Result<Json<RegisterResponse>, (StatusCode, String)> {
    let session = state
        .session_manager
        .register(payload.url.clone(), payload.headers.clone());
    let host_str = headers
        .get(header::HOST)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("127.0.0.1:8888");

    let proxy_url = format!(
        "http://{}/s/{}/manifest.m3u8?token={}",
        host_str, session.id, state.password
    );

    // Generate MediaFlow-compatible AES-256 encrypted token
    let proxy_data = ProxyData {
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

    let encrypted_url = format!(
        "http://{}/proxy/hls/manifest.m3u8?token={}",
        host_str, encrypted_token
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
        .fetch_url_bytes(&uri_str, &HashMap::new())
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

    let mut target_url = String::new();
    if let Some((_, query_uri)) = query_params.iter().find(|(k, _)| k == "uri") {
        if !query_uri.is_empty() {
            target_url = query_uri.clone();
        }
    }

    if target_url.is_empty() {
        if path_segments.len() == 2 && path_segments[1] == "manifest.m3u8" {
            target_url = session.original_url.clone();
        } else {
            let rel_segments = &path_segments[1..];
            target_url = resolve_target_url(&session.original_url, rel_segments, &query_params);
        }
    }

    let forward_headers =
        filter_upstream_headers(&session.headers, &incoming_headers, &target_url, session_id);
    let is_hls = target_url.to_lowercase().contains(".m3u8")
        || req.uri().path().to_lowercase().contains(".m3u8");

    if is_hls {
        handle_stateful_hls_playlist(&state, session_id, &target_url, &forward_headers).await
    } else {
        handle_segment(&state, &target_url, &forward_headers).await
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
    let token = match query_params.iter().find(|(k, _)| k == "token") {
        Some((_, val)) if !val.is_empty() => val,
        _ => {
            return Err((
                StatusCode::FORBIDDEN,
                "Missing encrypted token parameter".to_string(),
            ))
        }
    };

    let proxy_data = match state.encryption_handler.decrypt(token, None) {
        Ok(pd) => pd,
        Err(e) => {
            return Err((
                StatusCode::FORBIDDEN,
                format!("Invalid encrypted token: {}", e),
            ))
        }
    };

    let mut target_url = proxy_data.destination.clone();
    if let Some((_, query_uri)) = query_params.iter().find(|(k, _)| k == "uri") {
        if !query_uri.is_empty() {
            target_url = query_uri.clone();
        }
    }

    let session_headers = proxy_data.request_headers.clone().unwrap_or_default();
    let forward_headers = filter_upstream_headers(
        &session_headers,
        &incoming_headers,
        &target_url,
        "encrypted",
    );

    let is_hls = target_url.to_lowercase().contains(".m3u8")
        || req.uri().path().to_lowercase().contains(".m3u8");

    if is_hls {
        handle_encrypted_hls_playlist(&state, &target_url, &forward_headers, &proxy_data).await
    } else {
        handle_segment(&state, &target_url, &forward_headers).await
    }
}

async fn handle_stateful_hls_playlist(
    state: &AppState,
    session_id: &str,
    target_url: &str,
    headers: &HashMap<String, String>,
) -> Result<Response, (StatusCode, String)> {
    match state.engine.fetch_url_bytes(target_url, headers).await {
        Ok(bytes) => {
            let content = String::from_utf8_lossy(&bytes);
            let base_uri = match Url::parse(target_url) {
                Ok(u) => u,
                Err(e) => {
                    return Err((
                        StatusCode::INTERNAL_SERVER_ERROR,
                        format!("Parse URL error: {}", e),
                    ))
                }
            };

            let password = state.password.clone();
            let rewritten = HlsPlaylistRewriter::rewrite(&content, &base_uri, |resolved_target| {
                let resolved_uri = match Url::parse(resolved_target) {
                    Ok(u) => u,
                    Err(_) => return resolved_target.to_string(),
                };

                let filename = resolved_uri
                    .path_segments()
                    .and_then(|mut s| s.next_back())
                    .unwrap_or("item");

                format!(
                    "/s/{}/{}?uri={}&token={}",
                    session_id,
                    filename,
                    urlencoding::encode(resolved_target),
                    password
                )
            });

            Ok((
                [
                    (header::CONTENT_TYPE, "application/vnd.apple.mpegurl"),
                    (header::CACHE_CONTROL, "no-cache, no-store, must-revalidate"),
                ],
                rewritten,
            )
                .into_response())
        }
        Err(e) => Err((
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Failed to fetch/rewrite HLS playlist: {}", e),
        )),
    }
}

async fn handle_encrypted_hls_playlist(
    state: &AppState,
    target_url: &str,
    headers: &HashMap<String, String>,
    proxy_data: &ProxyData,
) -> Result<Response, (StatusCode, String)> {
    match state.engine.fetch_url_bytes(target_url, headers).await {
        Ok(bytes) => {
            let content = String::from_utf8_lossy(&bytes);
            let base_uri = match Url::parse(target_url) {
                Ok(u) => u,
                Err(e) => {
                    return Err((
                        StatusCode::INTERNAL_SERVER_ERROR,
                        format!("Parse URL error: {}", e),
                    ))
                }
            };

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

                let item_token = state
                    .encryption_handler
                    .encrypt(&item_data)
                    .unwrap_or_default();

                format!("/proxy/hls/{}?token={}", filename, item_token)
            });

            Ok((
                [
                    (header::CONTENT_TYPE, "application/vnd.apple.mpegurl"),
                    (header::CACHE_CONTROL, "no-cache, no-store, must-revalidate"),
                ],
                rewritten,
            )
                .into_response())
        }
        Err(e) => Err((
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Failed to fetch/rewrite encrypted HLS playlist: {}", e),
        )),
    }
}

async fn handle_segment(
    state: &AppState,
    target_url: &str,
    headers: &HashMap<String, String>,
) -> Result<Response, (StatusCode, String)> {
    match state.engine.connect_upstream(target_url, headers).await {
        Ok(upstream) => {
            let content_type = upstream
                .headers
                .get(header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .unwrap_or("")
                .to_lowercase();

            let is_dash = content_type.contains("dash+xml")
                || target_url.to_lowercase().contains(".mpd")
                || target_url.to_lowercase().contains("manifest/dash");

            if is_dash {
                let bytes = match axum::body::to_bytes(upstream.body, usize::MAX).await {
                    Ok(b) => b,
                    Err(e) => {
                        return Err((
                            StatusCode::INTERNAL_SERVER_ERROR,
                            format!("Failed reading DASH bytes: {}", e),
                        ))
                    }
                };
                let raw_content = String::from_utf8_lossy(&bytes);
                let rewritten = DashManifestRewriter::rewrite(&raw_content, Some(&state.password));
                return Ok((
                    [
                        (header::CONTENT_TYPE, "application/dash+xml"),
                        (header::CACHE_CONTROL, "no-cache, no-store, must-revalidate"),
                    ],
                    rewritten,
                )
                    .into_response());
            }

            let mime = mime_for(target_url);
            let mut response_builder = Response::builder().status(upstream.status);

            if let Some(headers_map) = response_builder.headers_mut() {
                headers_map.insert(header::CONTENT_TYPE, HeaderValue::from_static(mime));
                headers_map.insert(
                    header::CACHE_CONTROL,
                    HeaderValue::from_static("public, max-age=3600"),
                );
                for (k, v) in &upstream.headers {
                    headers_map.insert(k.clone(), v.clone());
                }
            }

            Ok(response_builder.body(upstream.body).unwrap_or_else(|_| {
                (StatusCode::INTERNAL_SERVER_ERROR, "Build response error").into_response()
            }))
        }
        Err(e) => Err((
            StatusCode::INTERNAL_SERVER_ERROR,
            format!("Failed to fetch segment: {}", e),
        )),
    }
}

fn resolve_target_url(
    base_spec: &str,
    relative_segments: &[&str],
    query_params: &[(String, String)],
) -> String {
    let base_uri = match Url::parse(base_spec) {
        Ok(u) => u,
        Err(_) => return base_spec.to_string(),
    };

    if relative_segments.is_empty() {
        return base_spec.to_string();
    }

    let mut resolved = if relative_segments[0] == "_root_" {
        let mut origin = format!(
            "{}://{}",
            base_uri.scheme(),
            base_uri.host_str().unwrap_or("")
        );
        if let Some(port) = base_uri.port() {
            origin.push_str(&format!(":{}", port));
        }
        let rel_path = relative_segments[1..].join("/");
        Url::parse(&origin)
            .and_then(|u| u.join(&rel_path))
            .unwrap_or_else(|_| base_uri.clone())
    } else {
        let rel_path = relative_segments.join("/");
        base_uri
            .join(&rel_path)
            .unwrap_or_else(|_| base_uri.clone())
    };

    let mut merged_query: Vec<(String, String)> = Vec::new();

    for (k, v) in base_uri.query_pairs() {
        merged_query.push((k.into_owned(), v.into_owned()));
    }
    for (k, v) in resolved.query_pairs() {
        if !merged_query.iter().any(|(existing_k, _)| existing_k == &*k) {
            merged_query.push((k.into_owned(), v.into_owned()));
        }
    }
    for (k, v) in query_params {
        if k != "token" && !merged_query.iter().any(|(existing_k, _)| existing_k == k) {
            merged_query.push((k.clone(), v.clone()));
        }
    }

    if !merged_query.is_empty() {
        let query_str = serde_urlencoded::to_string(&merged_query).unwrap_or_default();
        resolved.set_query(Some(&query_str));
    }

    resolved.to_string()
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
