//! Session-bound media proxy for the native player.
//!
//! The WebView receives only an opaque `aoc-media://` URL. The upstream URL is
//! kept in Rust memory, checked against the compiled media origins, and fetched
//! only while its playback session is active. This is deliberately a custom
//! protocol handler rather than a localhost HTTP server: no other local
//! process gets a network endpoint it can probe.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use http::header::{ACCEPT, ACCEPT_RANGES, CONTENT_LENGTH, CONTENT_RANGE, CONTENT_TYPE, RANGE};
use http::{Request, Response, StatusCode};
use reqwest::header::HeaderMap;
use reqwest::redirect::Policy;
use tokio::net::lookup_host;
use url::Url;
use uuid::Uuid;

const SCHEME: &str = "aoc-media";
const MAX_URL_BYTES: usize = 4096;
const MAX_REDIRECTS: usize = 2;
const MAX_RESPONSE_BYTES: usize = 16 * 1024 * 1024;
/// Largest byte range fetched upstream per WebView request. The media element
/// asks for the next range as it plays, so any file size streams while each
/// response stays well under `MAX_RESPONSE_BYTES`.
const RANGE_CHUNK_BYTES: u64 = 4 * 1024 * 1024;
const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const REQUEST_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProxyError {
    InvalidRequest,
    SessionNotFound,
    SessionInactive,
    UpstreamUnavailable,
    InvalidUpstream,
    PrivateAddress,
    RedirectLimit,
    ResponseTooLarge,
    UnsupportedContentType,
}

#[derive(Clone)]
struct ProxySession {
    upstream: Url,
    active: bool,
}

#[derive(Clone)]
struct ResolvedDestination {
    host: String,
    addresses: Vec<SocketAddr>,
}

/// The Rust-owned state used by the `aoc-media` Tauri protocol.
pub struct NativeMediaProxy {
    sessions: Mutex<HashMap<Uuid, ProxySession>>,
    media_origins: Vec<Url>,
    allow_loopback_http: bool,
}

impl NativeMediaProxy {
    pub fn new(
        media_origins: Vec<Url>,
        allow_loopback_http: bool,
    ) -> Result<Arc<Self>, ProxyError> {
        reqwest::Client::builder()
            .redirect(Policy::none())
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(REQUEST_TIMEOUT)
            .build()
            .map_err(|_| ProxyError::UpstreamUnavailable)?;
        Ok(Arc::new(Self {
            sessions: Mutex::new(HashMap::new()),
            media_origins,
            allow_loopback_http,
        }))
    }

    /// Stores the upstream location and returns the only URL the WebView may see.
    pub fn bind(&self, session_id: Uuid, upstream: &str) -> Option<String> {
        let upstream = self.validate_url(upstream).ok()?;
        let mut sessions = self
            .sessions
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        sessions.insert(
            session_id,
            ProxySession {
                upstream,
                active: true,
            },
        );
        Some(self.public_url(session_id))
    }

    /// Replaces a signed URL after a heartbeat without changing the opaque URL.
    pub fn refresh(&self, session_id: Uuid, upstream: &str) -> bool {
        let Ok(upstream) = self.validate_url(upstream) else {
            return false;
        };
        let mut sessions = self
            .sessions
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let Some(session) = sessions.get_mut(&session_id) else {
            return false;
        };
        if !session.active {
            return false;
        }
        session.upstream = upstream;
        true
    }

    pub fn deactivate(&self, session_id: Uuid) {
        let mut sessions = self
            .sessions
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if let Some(session) = sessions.get_mut(&session_id) {
            session.active = false;
        }
        sessions.remove(&session_id);
    }

    pub fn public_url(&self, session_id: Uuid) -> String {
        // Wry rewrites this custom scheme to the Windows WebView2 origin
        // before dispatching the request, then restores it for our handler.
        #[cfg(windows)]
        {
            format!("http://{SCHEME}.localhost/session/{session_id}/media")
        }
        #[cfg(not(windows))]
        {
            format!("{SCHEME}://localhost/session/{session_id}/media")
        }
    }

    /// Handles one custom-protocol request. The caller should run this off the
    /// Tauri UI thread; the method performs DNS and network I/O.
    pub async fn handle(&self, request: Request<Vec<u8>>) -> Result<Response<Vec<u8>>, ProxyError> {
        let (session_id, path) = parse_request_uri(request.uri())?;
        if path != "/media" || !matches!(*request.method(), http::Method::GET | http::Method::HEAD)
        {
            return Err(ProxyError::InvalidRequest);
        }

        let session = {
            let sessions = self
                .sessions
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            sessions
                .get(&session_id)
                .cloned()
                .ok_or(ProxyError::SessionNotFound)?
        };
        if !session.active {
            return Err(ProxyError::SessionInactive);
        }

        let mut upstream = session.upstream;
        let mut response = None;
        for redirects in 0..=MAX_REDIRECTS {
            let resolved = self.validate_resolved_destination(&upstream).await?;
            let mut headers = HeaderMap::new();
            let requested = request
                .headers()
                .get(RANGE)
                .and_then(|value| value.to_str().ok());
            let range = bounded_range(requested);
            headers.insert(
                RANGE,
                range.parse().map_err(|_| ProxyError::InvalidRequest)?,
            );
            if let Some(value) = request.headers().get(ACCEPT) {
                headers.insert(ACCEPT, value.clone());
            }
            let client = self.client_for(&upstream, &resolved)?;
            let result = client
                .request(request.method().clone(), upstream.clone())
                .headers(headers)
                .send()
                .await
                .map_err(|_| ProxyError::UpstreamUnavailable)?;
            if result.status().is_redirection() {
                if redirects == MAX_REDIRECTS {
                    return Err(ProxyError::RedirectLimit);
                }
                let location = result
                    .headers()
                    .get(reqwest::header::LOCATION)
                    .and_then(|value| value.to_str().ok())
                    .ok_or(ProxyError::InvalidUpstream)?;
                upstream = upstream
                    .join(location)
                    .map_err(|_| ProxyError::InvalidUpstream)?;
                self.validate_url(upstream.as_ref())?;
                continue;
            }
            response = Some(result);
            break;
        }
        let result = response.ok_or(ProxyError::UpstreamUnavailable)?;
        let status = result.status();
        if !matches!(
            status,
            StatusCode::OK
                | StatusCode::PARTIAL_CONTENT
                | StatusCode::RANGE_NOT_SATISFIABLE
                | StatusCode::NOT_FOUND
        ) {
            return Err(ProxyError::UpstreamUnavailable);
        }
        let content_type = result
            .headers()
            .get(CONTENT_TYPE)
            .and_then(|value| value.to_str().ok())
            .ok_or(ProxyError::UnsupportedContentType)?;
        if !allowed_content_type(content_type) {
            return Err(ProxyError::UnsupportedContentType);
        }

        let mut builder = Response::builder().status(status);
        builder = builder.header(CONTENT_TYPE, content_type);
        if let Some(value) = result.headers().get(CONTENT_RANGE) {
            builder = builder.header(CONTENT_RANGE, value);
        }
        builder = builder.header(ACCEPT_RANGES, "bytes");
        if request.method() == http::Method::HEAD {
            return builder
                .body(Vec::new())
                .map_err(|_| ProxyError::UpstreamUnavailable);
        }
        let body = read_bounded(result).await?;
        builder
            .header(CONTENT_LENGTH, body.len())
            .body(body)
            .map_err(|_| ProxyError::UpstreamUnavailable)
    }

    fn validate_url(&self, raw: &str) -> Result<Url, ProxyError> {
        if raw.len() > MAX_URL_BYTES {
            return Err(ProxyError::InvalidUpstream);
        }
        let url = Url::parse(raw).map_err(|_| ProxyError::InvalidUpstream)?;
        let host = url.host_str().ok_or(ProxyError::InvalidUpstream)?;
        let allowed_origin = self
            .media_origins
            .iter()
            .any(|origin| origin.origin() == url.origin());
        let http_allowed = self.allow_loopback_http
            && url.scheme() == "http"
            && matches!(host, "localhost" | "127.0.0.1" | "[::1]");
        if !(url.scheme() == "https" || http_allowed) || !allowed_origin {
            return Err(ProxyError::InvalidUpstream);
        }
        if !url.username().is_empty() || url.password().is_some() || url.fragment().is_some() {
            return Err(ProxyError::InvalidUpstream);
        }
        Ok(url)
    }

    async fn validate_resolved_destination(
        &self,
        url: &Url,
    ) -> Result<ResolvedDestination, ProxyError> {
        let host = url
            .host_str()
            .ok_or(ProxyError::InvalidUpstream)?
            .to_owned();
        let port = url
            .port_or_known_default()
            .ok_or(ProxyError::InvalidUpstream)?;
        let addresses = if let Ok(ip) = host.parse::<IpAddr>() {
            vec![SocketAddr::new(ip, port)]
        } else {
            lookup_host((host.as_str(), port))
                .await
                .map_err(|_| ProxyError::UpstreamUnavailable)?
                .collect::<Vec<_>>()
        };
        let loopback_debug_exception = self.allow_loopback_http
            && url.scheme() == "http"
            && addresses.iter().all(|address| address.ip().is_loopback());
        if addresses.is_empty()
            || (!loopback_debug_exception
                && addresses.iter().any(|address| !is_public_ip(address.ip())))
        {
            return Err(ProxyError::PrivateAddress);
        }
        Ok(ResolvedDestination { host, addresses })
    }

    fn client_for(
        &self,
        url: &Url,
        resolved: &ResolvedDestination,
    ) -> Result<reqwest::Client, ProxyError> {
        let port = url
            .port_or_known_default()
            .ok_or(ProxyError::InvalidUpstream)?;
        let addresses = resolved
            .addresses
            .iter()
            .map(|address| SocketAddr::new(address.ip(), port))
            .collect::<Vec<_>>();
        reqwest::Client::builder()
            .redirect(Policy::none())
            .connect_timeout(CONNECT_TIMEOUT)
            .timeout(REQUEST_TIMEOUT)
            .resolve_to_addrs(&resolved.host, addresses.as_slice())
            .build()
            .map_err(|_| ProxyError::UpstreamUnavailable)
    }
}

fn parse_request_uri(uri: &http::Uri) -> Result<(Uuid, &'static str), ProxyError> {
    // On Windows WebView2 receives the HTTP workaround URL, but Wry reverts
    // it to the original custom URI before invoking this handler.
    let valid_origin = uri.scheme_str() == Some(SCHEME) && uri.host() == Some("localhost");
    if !valid_origin || uri.query().is_some() {
        return Err(ProxyError::InvalidRequest);
    }
    let mut segments = uri.path().split('/').filter(|segment| !segment.is_empty());
    if segments.next() != Some("session") {
        return Err(ProxyError::InvalidRequest);
    }
    let session_id = segments
        .next()
        .and_then(|value| Uuid::parse_str(value).ok())
        .ok_or(ProxyError::InvalidRequest)?;
    if segments.next() != Some("media") || segments.next().is_some() {
        return Err(ProxyError::InvalidRequest);
    }
    Ok((session_id, "/media"))
}

/// The upstream `Range` for one WebView request: always a closed range of at
/// most `RANGE_CHUNK_BYTES`. No range, an open range (`bytes=N-`, what a media
/// element sends first) or an oversized one becomes `bytes=N-(N+chunk-1)`; the
/// upstream answers 206 with the real total in `Content-Range`. Anything this
/// doesn't understand (suffix or multi-range) is sent as the first chunk.
fn bounded_range(requested: Option<&str>) -> String {
    let parsed = requested
        .map(str::trim)
        .and_then(|value| value.strip_prefix("bytes="))
        .filter(|spec| !spec.contains(','))
        .and_then(|spec| spec.split_once('-'))
        .and_then(|(start, end)| {
            let start = start.trim().parse::<u64>().ok()?;
            let end = match end.trim() {
                "" => None,
                value => Some(value.parse::<u64>().ok()?),
            };
            Some((start, end))
        })
        .filter(|(start, end)| end.is_none_or(|end| end >= *start));
    let (start, end) = parsed.unwrap_or((0, None));
    let last = start + RANGE_CHUNK_BYTES - 1;
    let end = end.map_or(last, |end| end.min(last));
    format!("bytes={start}-{end}")
}

async fn read_bounded(mut response: reqwest::Response) -> Result<Vec<u8>, ProxyError> {
    if response
        .content_length()
        .is_some_and(|length| length > MAX_RESPONSE_BYTES as u64)
    {
        return Err(ProxyError::ResponseTooLarge);
    }
    let mut body = Vec::new();
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| ProxyError::UpstreamUnavailable)?
    {
        if body.len() + chunk.len() > MAX_RESPONSE_BYTES {
            return Err(ProxyError::ResponseTooLarge);
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

fn allowed_content_type(value: &str) -> bool {
    let media_type = value
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .to_ascii_lowercase();
    matches!(
        media_type.as_str(),
        "video/mp4"
            | "application/vnd.apple.mpegurl"
            | "application/x-mpegurl"
            | "video/mp2t"
            | "video/iso.segment"
            | "audio/mp4"
            | "application/dash+xml"
    )
}

fn is_public_ip(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(ip) => {
            let octets = ip.octets();
            !ip.is_private()
                && !ip.is_loopback()
                && !ip.is_link_local()
                && !ip.is_unspecified()
                && !(octets[0] == 100 && (64..=127).contains(&octets[1]))
                && !(octets == [169, 254, 169, 254])
        }
        IpAddr::V6(ip) => {
            !ip.is_loopback()
                && !ip.is_unspecified()
                && !ip.is_unique_local()
                && !ip.is_unicast_link_local()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn proxy() -> NativeMediaProxy {
        NativeMediaProxy::new(
            vec![Url::parse("https://media.example.test").unwrap()],
            false,
        )
        .unwrap()
        .as_ref()
        .clone_for_test()
    }

    impl NativeMediaProxy {
        fn clone_for_test(&self) -> NativeMediaProxy {
            NativeMediaProxy {
                sessions: Mutex::new(HashMap::new()),
                media_origins: self.media_origins.clone(),
                allow_loopback_http: self.allow_loopback_http,
            }
        }
    }

    #[tokio::test]
    async fn forwards_only_safe_headers_and_media_body() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/video.mp4"))
            .and(header("range", "bytes=0-3"))
            .respond_with(
                ResponseTemplate::new(206)
                    .insert_header("Content-Type", "video/mp4")
                    .insert_header("Content-Range", "bytes 0-3/4")
                    .set_body_bytes(vec![1, 2, 3, 4]),
            )
            .expect(1)
            .mount(&server)
            .await;
        let origin = Url::parse(&server.uri()).unwrap();
        let proxy = NativeMediaProxy::new(vec![origin.clone()], true).unwrap();
        let session = Uuid::new_v4();
        let upstream = format!("{}/video.mp4?signature=secret", server.uri());
        assert!(proxy.bind(session, &upstream).is_some());
        let request = Request::builder()
            .uri(format!("{SCHEME}://localhost/session/{session}/media"))
            .header(RANGE, "bytes=0-3")
            .header("Cookie", "session=must-not-forward")
            .header("Authorization", "Bearer must-not-forward")
            .body(Vec::new())
            .unwrap();
        let response = proxy.handle(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
        assert_eq!(response.body(), &vec![1, 2, 3, 4]);
        server.verify().await;
    }

    #[test]
    fn upstream_ranges_are_always_closed_and_bounded() {
        let chunk = RANGE_CHUNK_BYTES;
        assert_eq!(bounded_range(None), format!("bytes=0-{}", chunk - 1));
        assert_eq!(bounded_range(Some("bytes=0-")), format!("bytes=0-{}", chunk - 1));
        assert_eq!(
            bounded_range(Some("bytes=10000000-")),
            format!("bytes=10000000-{}", 10_000_000 + chunk - 1)
        );
        assert_eq!(bounded_range(Some("bytes=0-3")), "bytes=0-3");
        assert_eq!(
            bounded_range(Some("bytes=5-99999999")),
            format!("bytes=5-{}", 5 + chunk - 1)
        );
        for odd in ["bytes=-500", "bytes=0-1,5-9", "items=0-1", "bytes=9-3", "bytes=x-"] {
            assert_eq!(bounded_range(Some(odd)), format!("bytes=0-{}", chunk - 1), "{odd}");
        }
    }

    /// A media element's first request (`bytes=0-`) on a file larger than
    /// `MAX_RESPONSE_BYTES` used to fail and leave a black screen.
    #[tokio::test]
    async fn an_open_range_on_a_large_file_streams_one_bounded_chunk() {
        let server = MockServer::start().await;
        let chunk = RANGE_CHUNK_BYTES as usize;
        let total = 20 * 1024 * 1024;
        Mock::given(method("GET"))
            .and(path("/big.mp4"))
            .and(header("range", format!("bytes=0-{}", chunk - 1).as_str()))
            .respond_with(
                ResponseTemplate::new(206)
                    .insert_header("Content-Type", "video/mp4")
                    .insert_header("Content-Range", format!("bytes 0-{}/{total}", chunk - 1).as_str())
                    .set_body_bytes(vec![7u8; chunk]),
            )
            .expect(1)
            .mount(&server)
            .await;
        let origin = Url::parse(&server.uri()).unwrap();
        let proxy = NativeMediaProxy::new(vec![origin], true).unwrap();
        let session = Uuid::new_v4();
        assert!(proxy.bind(session, &format!("{}/big.mp4", server.uri())).is_some());
        let request = Request::builder()
            .uri(format!("{SCHEME}://localhost/session/{session}/media"))
            .header(RANGE, "bytes=0-")
            .body(Vec::new())
            .unwrap();
        let response = proxy.handle(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::PARTIAL_CONTENT);
        assert_eq!(response.body().len(), chunk);
        assert_eq!(
            response.headers().get(CONTENT_RANGE).unwrap(),
            format!("bytes 0-{}/{total}", chunk - 1).as_str()
        );
        server.verify().await;
    }

    #[tokio::test]
    async fn deactivated_sessions_cannot_fetch_media() {
        let server = MockServer::start().await;
        let origin = Url::parse(&server.uri()).unwrap();
        let proxy = NativeMediaProxy::new(vec![origin], true).unwrap();
        let session = Uuid::new_v4();
        assert!(
            proxy
                .bind(session, &format!("{}/video.mp4", server.uri()))
                .is_some()
        );
        proxy.deactivate(session);
        let request: Request<Vec<u8>> = Request::builder()
            .uri(format!("{SCHEME}://localhost/session/{session}/media"))
            .body(Vec::new())
            .unwrap();
        assert!(matches!(
            proxy.handle(request).await,
            Err(ProxyError::SessionNotFound)
        ));
    }

    #[test]
    fn debug_loopback_http_is_explicitly_allowed_but_release_rejects_it() {
        let debug = NativeMediaProxy::new(vec![Url::parse("http://127.0.0.1:9000").unwrap()], true)
            .unwrap();
        let session = Uuid::new_v4();
        assert!(
            debug
                .bind(session, "http://127.0.0.1:9000/video.mp4")
                .is_some()
        );

        let release =
            NativeMediaProxy::new(vec![Url::parse("http://127.0.0.1:9000").unwrap()], false)
                .unwrap();
        assert!(
            release
                .bind(Uuid::new_v4(), "http://127.0.0.1:9000/video.mp4")
                .is_none()
        );
    }

    #[test]
    fn binds_only_allowed_https_urls_and_returns_opaque_reference() {
        let proxy = proxy();
        let session = Uuid::new_v4();
        assert_eq!(
            proxy.bind(session, "https://media.example.test/video.mp4?sig=x"),
            Some(proxy.public_url(session))
        );
        assert!(
            proxy
                .bind(Uuid::new_v4(), "https://media.example.test.evil/video.mp4")
                .is_none()
        );
        assert!(
            proxy
                .bind(Uuid::new_v4(), "http://media.example.test/video.mp4")
                .is_none()
        );
        assert!(
            proxy
                .bind(
                    Uuid::new_v4(),
                    "https://user:pass@media.example.test/video.mp4"
                )
                .is_none()
        );
    }

    #[test]
    fn opaque_uri_parser_rejects_path_query_and_host_confusion() {
        let session = Uuid::new_v4();
        // Wry presents the original custom URI to the handler on Windows too.
        let valid = format!("{SCHEME}://localhost/session/{session}/media");
        let uri: http::Uri = valid.parse().unwrap();
        assert_eq!(parse_request_uri(&uri), Ok((session, "/media")));
        for raw in [
            format!("{SCHEME}://evil/session/{session}/media"),
            format!("{SCHEME}://localhost/session/{session}/media?url=https://evil.test"),
            format!("{SCHEME}://localhost/session/{session}/other"),
            format!("http://evil.localhost/session/{session}/media"),
        ] {
            let uri: http::Uri = raw.parse().unwrap();
            assert_eq!(
                parse_request_uri(&uri),
                Err(ProxyError::InvalidRequest),
                "{raw}"
            );
        }
    }

    #[test]
    fn private_and_metadata_ranges_are_rejected() {
        for raw in [
            "127.0.0.1",
            "10.0.0.1",
            "172.16.0.1",
            "192.168.1.1",
            "100.64.0.1",
            "169.254.169.254",
            "::1",
            "fd00::1",
        ] {
            assert!(!is_public_ip(raw.parse().unwrap()), "{raw}");
        }
        assert!(is_public_ip("8.8.8.8".parse().unwrap()));
    }

    #[test]
    fn only_media_content_types_are_allowed() {
        assert!(allowed_content_type("video/mp4"));
        assert!(allowed_content_type(
            "application/vnd.apple.mpegurl; charset=utf-8"
        ));
        assert!(!allowed_content_type("text/html"));
        assert!(!allowed_content_type("application/javascript"));
    }
}
