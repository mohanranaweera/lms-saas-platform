//! Client for the four player endpoints (`docs/api/aoc-player.md`). Every
//! response uses the backend's `ApiResponse` envelope.
//!
//! The client never follows redirects, only talks to the compiled-in origin,
//! and keeps the player token out of `Authorization` (it travels in
//! `X-Player-Token`, ADR-025 Amendment 1).

use std::sync::atomic::{AtomicI64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use reqwest::header::{ACCEPT, CONTENT_TYPE, DATE, HeaderMap};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use time::OffsetDateTime;
use url::Url;
use uuid::Uuid;

use crate::config::{self, PLATFORM, PLAYER_VERSION};
use crate::keystore::{InstallationKey, KeyError};
use crate::signing;

pub const INSTALLATION_HEADER: &str = "X-Player-Installation";
pub const TIMESTAMP_HEADER: &str = "X-Player-Timestamp";
pub const NONCE_HEADER: &str = "X-Player-Nonce";
pub const SIGNATURE_HEADER: &str = "X-Player-Signature";
pub const TOKEN_HEADER: &str = "X-Player-Token";

/// Responses larger than this are refused (they are a few hundred bytes).
const MAX_RESPONSE_BYTES: usize = 64 * 1024;

#[derive(Debug, thiserror::Error)]
pub enum ApiError {
    #[error("the server refused the request (HTTP {status})")]
    Rejected { status: u16, code: Option<String> },
    #[error("the server could not be reached")]
    Network,
    #[error("the server sent an unexpected response")]
    Protocol,
    #[error(transparent)]
    Key(#[from] KeyError),
}

impl ApiError {
    /// The backend's error code, e.g. `PLAYBACK_LAUNCH_CODE_INVALID`.
    pub fn code(&self) -> Option<&str> {
        match self {
            ApiError::Rejected { code, .. } => code.as_deref(),
            _ => None,
        }
    }

    pub fn status(&self) -> Option<u16> {
        match self {
            ApiError::Rejected { status, .. } => Some(*status),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Descriptor {
    pub provider: String,
    #[serde(default)]
    pub provider_video_id: Option<String>,
    #[serde(default)]
    pub signed_url: Option<String>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub duration_seconds: Option<i64>,
    #[serde(default)]
    pub watermark_text: Option<String>,
    #[serde(default)]
    pub allow_seeking: bool,
    #[serde(default)]
    pub watermark: WatermarkPolicy,
    #[serde(default)]
    pub capture_protection: CaptureProtectionPolicy,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WatermarkPolicy {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub text: Option<String>,
    #[serde(default)]
    pub opacity: Option<f32>,
    #[serde(default)]
    pub movement_interval_seconds: u32,
    #[serde(default)]
    pub placement: WatermarkPlacement,
}

#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum WatermarkPlacement {
    Overlay,
    #[default]
    OutsidePlayer,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CaptureProtectionPolicy {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub required: bool,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Exchange {
    pub session_id: Uuid,
    pub playback_token: String,
    #[serde(with = "time::serde::rfc3339")]
    pub expires_at: OffsetDateTime,
    pub heartbeat_interval_seconds: u32,
    pub descriptor: Descriptor,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Heartbeat {
    pub action: String,
    #[serde(default)]
    pub reason: Option<String>,
    #[serde(default)]
    pub playback_token: Option<String>,
    #[serde(default, with = "time::serde::rfc3339::option")]
    pub expires_at: Option<OffsetDateTime>,
    #[serde(default)]
    pub heartbeat_interval_seconds: Option<u32>,
    #[serde(default)]
    pub media_url: Option<String>,
}

#[derive(Deserialize)]
struct Envelope<T> {
    success: bool,
    data: Option<T>,
    error: Option<ErrorBody>,
}

#[derive(Deserialize)]
struct ErrorBody {
    code: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PairingCompleted {
    installation_id: Uuid,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PairingRequest<'a> {
    pairing_code: &'a str,
    public_key: &'a str,
    platform: &'a str,
    player_version: &'a str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ExchangeRequest<'a> {
    launch_code: &'a str,
    player_version: &'a str,
    platform: &'a str,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct HeartbeatRequest<'a> {
    position_seconds: u32,
    state: &'a str,
    player_version: &'a str,
}

pub struct ApiClient {
    http: reqwest::Client,
    origin: Url,
    /// Server time minus local time, in seconds, learnt from `Date` headers.
    /// Signed timestamps must be within ±60 s of the server's clock.
    clock_offset: AtomicI64,
}

impl ApiClient {
    pub fn new(origin: Url) -> Result<Self, ApiError> {
        let http = reqwest::Client::builder()
            .https_only(!config::allows_http(&origin))
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(20))
            .user_agent(format!("AOC-Player/{PLAYER_VERSION} ({PLATFORM})"))
            .build()
            .map_err(|_| ApiError::Network)?;
        Ok(Self {
            http,
            origin,
            clock_offset: AtomicI64::new(0),
        })
    }

    /// Server time in Unix seconds, as best known.
    pub fn server_now(&self) -> i64 {
        local_now() + self.clock_offset()
    }

    pub fn clock_offset(&self) -> i64 {
        self.clock_offset.load(Ordering::Relaxed)
    }

    pub async fn complete_pairing(
        &self,
        pairing_code: &str,
        public_key: &str,
    ) -> Result<Uuid, ApiError> {
        let body = to_json(&PairingRequest {
            pairing_code,
            public_key,
            platform: PLATFORM,
            player_version: PLAYER_VERSION,
        });
        let done: PairingCompleted = self
            .post("/api/v1/player/pairings/complete", body, HeaderMap::new())
            .await?
            .ok_or(ApiError::Protocol)?;
        Ok(done.installation_id)
    }

    pub async fn exchange(
        &self,
        key: &dyn InstallationKey,
        installation_id: Uuid,
        launch_code: &str,
    ) -> Result<Exchange, ApiError> {
        let path = "/api/v1/player/exchange";
        let body = to_json(&ExchangeRequest {
            launch_code,
            player_version: PLAYER_VERSION,
            platform: PLATFORM,
        });
        let mut headers = self.signed_headers(key, path, &body)?;
        headers.insert(
            INSTALLATION_HEADER,
            header_value(&installation_id.to_string())?,
        );
        self.post(path, body, headers)
            .await?
            .ok_or(ApiError::Protocol)
    }

    pub async fn heartbeat(
        &self,
        key: &dyn InstallationKey,
        session_id: Uuid,
        token: &str,
        position_seconds: u32,
        state: &str,
    ) -> Result<Heartbeat, ApiError> {
        let path = format!("/api/v1/player/sessions/{session_id}/heartbeat");
        let body = to_json(&HeartbeatRequest {
            position_seconds,
            state,
            player_version: PLAYER_VERSION,
        });
        let mut headers = self.signed_headers(key, &path, &body)?;
        headers.insert(TOKEN_HEADER, header_value(token)?);
        self.post(&path, body, headers)
            .await?
            .ok_or(ApiError::Protocol)
    }

    pub async fn end(
        &self,
        key: &dyn InstallationKey,
        session_id: Uuid,
        token: &str,
    ) -> Result<(), ApiError> {
        let path = format!("/api/v1/player/sessions/{session_id}/end");
        let body = b"{}".to_vec();
        let mut headers = self.signed_headers(key, &path, &body)?;
        headers.insert(TOKEN_HEADER, header_value(token)?);
        self.post::<serde::de::IgnoredAny>(&path, body, headers)
            .await
            .map(|_| ())
    }

    fn signed_headers(
        &self,
        key: &dyn InstallationKey,
        path: &str,
        body: &[u8],
    ) -> Result<HeaderMap, ApiError> {
        let timestamp = self.server_now();
        let nonce = signing::new_nonce();
        let signature = key.sign(&signing::canonical("POST", path, timestamp, &nonce, body))?;
        let mut headers = HeaderMap::new();
        headers.insert(TIMESTAMP_HEADER, header_value(&timestamp.to_string())?);
        headers.insert(NONCE_HEADER, header_value(&nonce)?);
        headers.insert(
            SIGNATURE_HEADER,
            header_value(&signing::base64url(&signature))?,
        );
        Ok(headers)
    }

    /// Sends one request; `Ok(None)` is a success envelope without data.
    async fn post<T: DeserializeOwned>(
        &self,
        path: &str,
        body: Vec<u8>,
        headers: HeaderMap,
    ) -> Result<Option<T>, ApiError> {
        let url = self.origin.join(path).map_err(|_| ApiError::Protocol)?;
        let response = self
            .http
            .post(url)
            .headers(headers)
            .header(CONTENT_TYPE, "application/json")
            .header(ACCEPT, "application/json")
            .body(body)
            .send()
            .await
            .map_err(|_| ApiError::Network)?;
        self.learn_clock(response.headers());
        let status = response.status();
        let mut response = response;
        if response
            .content_length()
            .is_some_and(|length| length > MAX_RESPONSE_BYTES as u64)
        {
            return Err(ApiError::Protocol);
        }
        // Read at most MAX_RESPONSE_BYTES even when no Content-Length was sent.
        let mut bytes = Vec::new();
        while let Some(chunk) = response.chunk().await.map_err(|_| ApiError::Network)? {
            if bytes.len() + chunk.len() > MAX_RESPONSE_BYTES {
                return Err(ApiError::Protocol);
            }
            bytes.extend_from_slice(&chunk);
        }
        let envelope = serde_json::from_slice::<Envelope<T>>(&bytes).ok();
        if status.is_success() {
            match envelope {
                Some(Envelope {
                    success: true,
                    data,
                    ..
                }) => Ok(data),
                _ => Err(ApiError::Protocol),
            }
        } else {
            Err(ApiError::Rejected {
                status: status.as_u16(),
                code: envelope.and_then(|e| e.error).and_then(|e| e.code),
            })
        }
    }

    fn learn_clock(&self, headers: &HeaderMap) {
        let server = headers
            .get(DATE)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| httpdate::parse_http_date(value).ok())
            .and_then(|time| time.duration_since(UNIX_EPOCH).ok());
        if let Some(server) = server {
            self.clock_offset
                .store(server.as_secs() as i64 - local_now(), Ordering::Relaxed);
        }
    }
}

fn local_now() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

fn to_json<T: Serialize>(value: &T) -> Vec<u8> {
    serde_json::to_vec(value).expect("request bodies always serialize")
}

fn header_value(value: &str) -> Result<reqwest::header::HeaderValue, ApiError> {
    reqwest::header::HeaderValue::from_str(value).map_err(|_| ApiError::Protocol)
}
