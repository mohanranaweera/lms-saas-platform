//! Player lifecycle against a mock backend. Every signed request is verified
//! with the installation's public key, the same way `PlayerRequestVerifier`
//! does on the server.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use p256::ecdsa::signature::Verifier as _;
use p256::ecdsa::{Signature, VerifyingKey};
use serde_json::{Value, json};
use time::OffsetDateTime;
use time::format_description::well_known::Rfc3339;
use url::Url;
use uuid::Uuid;
use wiremock::matchers::{header, method, path};
use wiremock::{Match, Mock, MockServer, Request, ResponseTemplate};

use super::*;
use crate::api::{
    INSTALLATION_HEADER, NONCE_HEADER, SIGNATURE_HEADER, TIMESTAMP_HEADER, TOKEN_HEADER,
};
use crate::installation::Installation;
use crate::keystore::software::{MemoryKeyStore, SoftwareKey};
use crate::signing;

const CODE: &str = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_AbCdE";
const EXCHANGE: &str = "/api/v1/player/exchange";
const MEDIA_ORIGIN: &str = "https://media.example.test";

#[derive(Default)]
struct Recorder(Mutex<Vec<View>>);

impl ViewSink for Recorder {
    fn show(&self, view: &View) {
        self.0.lock().unwrap().push(view.clone());
    }
}

impl Recorder {
    fn last(&self) -> Option<View> {
        self.0.lock().unwrap().last().cloned()
    }
}

struct CaptureRecorder {
    requests: Mutex<Vec<bool>>,
    clears: std::sync::atomic::AtomicUsize,
    applied_status: crate::capture_protection::CaptureStatus,
}

impl CaptureRecorder {
    fn new(applied_status: crate::capture_protection::CaptureStatus) -> Self {
        Self {
            requests: Mutex::new(Vec::new()),
            clears: std::sync::atomic::AtomicUsize::new(0),
            applied_status,
        }
    }

    fn clear_count(&self) -> usize {
        self.clears.load(std::sync::atomic::Ordering::Relaxed)
    }
}

impl crate::capture_protection::Controller for CaptureRecorder {
    fn apply(&self, enabled: bool) -> crate::capture_protection::CaptureStatus {
        self.requests.lock().unwrap().push(enabled);
        if enabled {
            self.applied_status
        } else {
            crate::capture_protection::CaptureStatus::NotRequested
        }
    }

    fn clear(&self) {
        self.clears
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }
}

struct Fixture {
    server: MockServer,
    player: Arc<Player>,
    views: Arc<Recorder>,
    keys: MemoryKeyStore,
    dir: PathBuf,
    capture: Arc<CaptureRecorder>,
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

async fn fixture() -> Fixture {
    fixture_with_capture(crate::capture_protection::CaptureStatus::Unsupported).await
}

async fn fixture_with_capture(applied_status: crate::capture_protection::CaptureStatus) -> Fixture {
    let server = MockServer::start().await;
    let dir = std::env::temp_dir().join(format!("aoc-player-test-{}", Uuid::new_v4()));
    let keys = MemoryKeyStore::default();
    let views = Arc::new(Recorder::default());
    let capture = Arc::new(CaptureRecorder::new(applied_status));
    let api = ApiClient::new(Url::parse(&server.uri()).unwrap()).unwrap();
    let player = Player::with_capture_controller(
        api,
        Box::new(keys.clone()),
        InstallationStore::new(dir.clone()),
        views.clone(),
        crate::config::parse_origins(MEDIA_ORIGIN, false).unwrap(),
        capture.clone(),
    );
    Fixture {
        server,
        player,
        views,
        keys,
        dir,
        capture,
    }
}

/// Saves a paired installation and returns its id and public key.
fn paired(fixture: &Fixture) -> (Uuid, VerifyingKey) {
    let key = SoftwareKey::random();
    let name = "AOC-Player-existing".to_owned();
    fixture
        .keys
        .keys
        .lock()
        .unwrap()
        .insert(name.clone(), key.clone());
    let installation_id = Uuid::new_v4();
    InstallationStore::new(fixture.dir.clone())
        .save(&Installation {
            installation_id,
            key_name: name,
            key_provider: "memory".into(),
            paired_at: OffsetDateTime::now_utc(),
        })
        .unwrap();
    (installation_id, *key.0.verifying_key())
}

/// Matches a request whose signature headers verify with `key`, exactly as
/// the backend checks them.
struct SignedBy(VerifyingKey);

impl Match for SignedBy {
    fn matches(&self, request: &Request) -> bool {
        let header = |name: &str| {
            request
                .headers
                .get(name)
                .and_then(|v| v.to_str().ok())
                .map(str::to_owned)
        };
        let (Some(timestamp), Some(nonce), Some(signature)) = (
            header(TIMESTAMP_HEADER),
            header(NONCE_HEADER),
            header(SIGNATURE_HEADER),
        ) else {
            return false;
        };
        let Ok(timestamp) = timestamp.parse::<i64>() else {
            return false;
        };
        let now = OffsetDateTime::now_utc().unix_timestamp();
        use base64::Engine as _;
        let Ok(bytes) = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(signature) else {
            return false;
        };
        let Ok(signature) = Signature::from_slice(&bytes) else {
            return false;
        };
        let message = signing::canonical(
            request.method.as_str(),
            request.url.path(),
            timestamp,
            &nonce,
            &request.body,
        );
        (timestamp - now).abs() <= 60
            && bytes.len() == 64
            && self.0.verify(&message, &signature).is_ok()
    }
}

fn envelope(data: Value) -> Value {
    json!({ "success": true, "data": data, "error": null, "timestamp": "2026-10-08T00:00:00Z", "traceId": "t" })
}

fn failure(code: &str) -> Value {
    json!({ "success": false, "data": null, "error": { "code": code, "message": "x", "fieldErrors": [] },
            "timestamp": "2026-10-08T00:00:00Z", "traceId": "t" })
}

fn in_seconds(seconds: i64) -> String {
    (OffsetDateTime::now_utc() + time::Duration::seconds(seconds))
        .format(&Rfc3339)
        .unwrap()
}

fn exchange_body(session_id: Uuid, token: &str, deadline_seconds: i64) -> Value {
    exchange_body_with_capture(session_id, token, deadline_seconds, false, false)
}

fn exchange_body_with_capture(
    session_id: Uuid,
    token: &str,
    deadline_seconds: i64,
    capture_enabled: bool,
    capture_required: bool,
) -> Value {
    envelope(json!({
        "sessionId": session_id,
        "playbackToken": token,
        "expiresAt": in_seconds(deadline_seconds),
        "heartbeatIntervalSeconds": 1,
        "descriptor": {
            "sessionId": session_id, "provider": "YOUTUBE", "providerVideoId": "dQw4w9WgXcQ",
            "signedUrl": null, "expiresAt": null, "title": "Lesson 1", "durationSeconds": 600,
            "watermarkText": "student@example.test", "allowSeeking": true, "allowDownload": false,
            "watermark": { "enabled": false, "text": null, "opacity": null, "movementIntervalSeconds": 0, "placement": "OUTSIDE_PLAYER" },
            "captureProtection": { "enabled": capture_enabled, "required": capture_required }
        }
    }))
}

fn beat(action: &str, reason: Option<&str>, token: &str) -> Value {
    envelope(json!({
        "action": action, "reason": reason, "playbackToken": token,
        "expiresAt": in_seconds(60), "heartbeatIntervalSeconds": 1, "mediaUrl": null
    }))
}

async fn wait_for(views: &Recorder, wanted: impl Fn(&View) -> bool) -> View {
    for _ in 0..200 {
        if let Some(view) = views.last().filter(|v| wanted(v)) {
            return view;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    panic!("view never appeared; last = {:?}", views.last());
}

fn play_link() -> String {
    format!("aocplayer://play?code={CODE}")
}

#[tokio::test]
async fn invalid_links_make_no_request() {
    let f = fixture().await;
    paired(&f);
    for link in [
        "aocplayer://play?code=short",
        "aocplayer://evil.example.com/play?code=x",
        "https://x",
    ] {
        f.player.handle_link(link).await;
        assert_eq!(
            f.views.last(),
            Some(View::Error {
                code: "INVALID_LINK".into()
            })
        );
    }
    assert!(f.server.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn pairing_creates_a_key_and_replaces_the_previous_installation() {
    let f = fixture().await;
    paired(&f);
    let installation_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path("/api/v1/player/pairings/complete"))
        .respond_with(
            ResponseTemplate::new(201)
                .set_body_json(envelope(json!({ "installationId": installation_id }))),
        )
        .expect(1)
        .mount(&f.server)
        .await;

    f.player
        .handle_link(&format!("aocplayer://pair?code={CODE}"))
        .await;

    assert_eq!(f.views.last(), Some(View::Ready));
    let saved = InstallationStore::new(f.dir.clone()).load().unwrap();
    assert_eq!(saved.installation_id, installation_id);
    let new_key = {
        let keys = f.keys.keys.lock().unwrap();
        assert_eq!(keys.len(), 1, "the previous key is removed");
        keys.get(&saved.key_name)
            .expect("the new key is kept")
            .clone()
    };

    let requests = f.server.received_requests().await.unwrap();
    let body: Value = serde_json::from_slice(&requests[0].body).unwrap();
    assert_eq!(body["pairingCode"], CODE);
    assert_eq!(body["platform"], "WINDOWS");
    assert_eq!(body["playerVersion"], env!("CARGO_PKG_VERSION"));
    assert_eq!(body["publicKey"], new_key.public_key_spki().unwrap());
    assert!(requests[0].headers.get("authorization").is_none());
}

#[tokio::test]
async fn failed_pairing_keeps_the_previous_installation() {
    let f = fixture().await;
    let (installation_id, _) = paired(&f);
    Mock::given(method("POST"))
        .and(path("/api/v1/player/pairings/complete"))
        .respond_with(
            ResponseTemplate::new(400).set_body_json(failure("PLAYER_PAIRING_CODE_INVALID")),
        )
        .mount(&f.server)
        .await;

    f.player
        .handle_link(&format!("aocplayer://pair?code={CODE}"))
        .await;

    assert_eq!(
        f.views.last(),
        Some(View::Error {
            code: "PLAYER_PAIRING_CODE_INVALID".into()
        })
    );
    let keys = f.keys.keys.lock().unwrap();
    assert_eq!(keys.keys().collect::<Vec<_>>(), vec!["AOC-Player-existing"]);
    let saved = InstallationStore::new(f.dir.clone()).load().unwrap();
    assert_eq!(saved.installation_id, installation_id);
}

#[tokio::test]
async fn playing_without_pairing_asks_to_pair_first() {
    let f = fixture().await;
    f.player.handle_link(&play_link()).await;
    assert_eq!(
        f.views.last(),
        Some(View::Error {
            code: "NOT_PAIRED".into()
        })
    );
    assert!(f.server.received_requests().await.unwrap().is_empty());
}

#[tokio::test]
async fn signed_exchange_then_heartbeats_until_the_server_says_stop() {
    let f = fixture().await;
    let (installation_id, public_key) = paired(&f);
    let session_id = Uuid::new_v4();
    let heartbeat_path = format!("/api/v1/player/sessions/{session_id}/heartbeat");
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .and(header(
            INSTALLATION_HEADER,
            installation_id.to_string().as_str(),
        ))
        .and(SignedBy(public_key))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body(session_id, "token-1", 60)),
        )
        .expect(1)
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(heartbeat_path.as_str()))
        .and(header(TOKEN_HEADER, "token-1"))
        .and(SignedBy(public_key))
        .respond_with(ResponseTemplate::new(200).set_body_json(beat("CONTINUE", None, "token-2")))
        .expect(1)
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(heartbeat_path.as_str()))
        .and(header(TOKEN_HEADER, "token-2"))
        .and(SignedBy(public_key))
        .respond_with(ResponseTemplate::new(200).set_body_json(beat("STOP", Some("ADMIN"), "")))
        .expect(1)
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    assert_eq!(
        f.views.last(),
        Some(View::Playing {
            session_id,
            lesson_title: Some("Lesson 1".into()),
            watermark_text: Some("student@example.test".into()),
            watermark: crate::api::WatermarkPolicy::default(),
            capture_status: crate::capture_protection::CaptureStatus::NotRequested,
            media: Media::Youtube {
                video_id: "dQw4w9WgXcQ".into()
            },
        })
    );
    assert!(f.player.has_session());

    let stopped = wait_for(&f.views, |v| matches!(v, View::Stopped { .. })).await;
    assert_eq!(
        stopped,
        View::Stopped {
            reason: "ADMIN".into()
        }
    );
    assert!(!f.player.has_session());

    let exchange = &f.server.received_requests().await.unwrap()[0];
    let body: Value = serde_json::from_slice(&exchange.body).unwrap();
    assert_eq!(
        body,
        json!({ "launchCode": CODE, "playerVersion": env!("CARGO_PKG_VERSION"), "platform": "WINDOWS" })
    );
}

#[tokio::test]
async fn required_capture_protection_fails_closed_before_playing() {
    let f = fixture().await;
    paired(&f);
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body_with_capture(
                session_id, "token-1", 60, true, true,
            )),
        )
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{session_id}/end").as_str(),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(envelope(Value::Null)))
        .expect(1)
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;

    assert_eq!(
        f.views.last(),
        Some(View::Error {
            code: "CAPTURE_PROTECTION_UNAVAILABLE".into()
        })
    );
    assert!(!f.player.has_session());
    assert_eq!(f.capture.requests.lock().unwrap().as_slice(), &[true]);
    assert_eq!(f.capture.clear_count(), 1);
    assert!(
        !f.views
            .0
            .lock()
            .unwrap()
            .iter()
            .any(|view| matches!(view, View::Playing { .. }))
    );
}

#[tokio::test]
async fn applied_capture_protection_is_reported_before_playing() {
    let f = fixture_with_capture(crate::capture_protection::CaptureStatus::Applied).await;
    paired(&f);
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body_with_capture(
                session_id, "token-1", 60, true, true,
            )),
        )
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{session_id}/heartbeat").as_str(),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(beat("STOP", Some("ADMIN"), "")))
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;

    assert!(matches!(
        f.views.last(),
        Some(View::Playing {
            capture_status: crate::capture_protection::CaptureStatus::Applied,
            ..
        })
    ));
    assert_eq!(f.capture.requests.lock().unwrap().as_slice(), &[true]);
    let _ = wait_for(&f.views, |view| matches!(view, View::Stopped { .. })).await;
    assert_eq!(f.capture.clear_count(), 1);
}

#[tokio::test]
async fn disabled_capture_protection_is_not_requested() {
    let f = fixture().await;
    paired(&f);
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body(session_id, "token-1", 60)),
        )
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{session_id}/heartbeat").as_str(),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(beat("STOP", Some("ADMIN"), "")))
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    assert!(matches!(
        f.views.last(),
        Some(View::Playing {
            capture_status: crate::capture_protection::CaptureStatus::NotRequested,
            ..
        })
    ));
    assert_eq!(f.capture.requests.lock().unwrap().as_slice(), &[false]);
    let _ = wait_for(&f.views, |view| matches!(view, View::Stopped { .. })).await;
    assert_eq!(f.capture.clear_count(), 1);
}

#[tokio::test]
async fn refused_exchange_shows_the_generic_error() {
    let f = fixture().await;
    paired(&f);
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(400).set_body_json(failure("PLAYBACK_LAUNCH_CODE_INVALID")),
        )
        .expect(1)
        .mount(&f.server)
        .await;
    f.player.handle_link(&play_link()).await;
    assert_eq!(
        f.views.last(),
        Some(View::Error {
            code: "PLAYBACK_LAUNCH_CODE_INVALID".into()
        })
    );
    assert!(!f.player.has_session());
}

#[tokio::test]
async fn old_player_is_told_to_update() {
    let f = fixture().await;
    paired(&f);
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(426).set_body_json(failure("PLAYER_VERSION_UNSUPPORTED")),
        )
        .mount(&f.server)
        .await;
    f.player.handle_link(&play_link()).await;
    assert_eq!(
        f.views.last(),
        Some(View::Error {
            code: "PLAYER_VERSION_UNSUPPORTED".into()
        })
    );
}

#[tokio::test]
async fn rejected_heartbeat_tears_down_as_expired() {
    let f = fixture().await;
    paired(&f);
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body(session_id, "token-1", 60)),
        )
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{session_id}/heartbeat").as_str(),
        ))
        .respond_with(ResponseTemplate::new(401).set_body_json(failure("PLAYBACK_SESSION_EXPIRED")))
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    let stopped = wait_for(&f.views, |v| matches!(v, View::Stopped { .. })).await;
    assert_eq!(
        stopped,
        View::Stopped {
            reason: "EXPIRED".into()
        }
    );
    assert!(!f.player.has_session());
}

#[tokio::test]
async fn unknown_heartbeat_action_stops_playback() {
    let f = fixture().await;
    paired(&f);
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body(session_id, "token-1", 60)),
        )
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{session_id}/heartbeat").as_str(),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(beat(
            "PAUSE",
            Some("<script>"),
            "t",
        )))
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    let stopped = wait_for(&f.views, |v| matches!(v, View::Stopped { .. })).await;
    assert_eq!(
        stopped,
        View::Stopped {
            reason: "STOPPED".into()
        }
    );
}

#[tokio::test]
async fn missed_deadline_tears_down_when_the_server_is_unreachable() {
    let f = fixture().await;
    paired(&f);
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body(session_id, "token-1", 3)),
        )
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{session_id}/heartbeat").as_str(),
        ))
        .respond_with(ResponseTemplate::new(503))
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    let stopped = wait_for(&f.views, |v| matches!(v, View::Stopped { .. })).await;
    assert_eq!(
        stopped,
        View::Stopped {
            reason: "CONNECTION_LOST".into()
        }
    );
    let beats = f
        .server
        .received_requests()
        .await
        .unwrap()
        .iter()
        .filter(|r| r.url.path().ends_with("/heartbeat"))
        .count();
    assert!(beats >= 2, "kept retrying until the deadline ({beats})");
}

#[tokio::test]
async fn student_end_tells_the_server() {
    let f = fixture().await;
    let (_, public_key) = paired(&f);
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body(session_id, "token-1", 60)),
        )
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{session_id}/end").as_str(),
        ))
        .and(header(TOKEN_HEADER, "token-1"))
        .and(SignedBy(public_key))
        .respond_with(ResponseTemplate::new(200).set_body_json(envelope(Value::Null)))
        .expect(1)
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    f.player.end_by_student().await;

    assert_eq!(
        f.views.last(),
        Some(View::Stopped {
            reason: "ENDED_BY_STUDENT".into()
        })
    );
    assert!(!f.player.has_session());
}

#[tokio::test]
async fn a_new_launch_ends_the_current_session_first() {
    let f = fixture().await;
    paired(&f);
    let first = Uuid::new_v4();
    let second = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(ResponseTemplate::new(200).set_body_json(exchange_body(first, "token-1", 60)))
        .up_to_n_times(1)
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body(second, "token-2", 60)),
        )
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{first}/end").as_str(),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(envelope(Value::Null)))
        .expect(1)
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    f.player
        .handle_link(&format!("aocplayer://play?code={}", CODE.replace('A', "B")))
        .await;

    assert!(matches!(f.views.last(), Some(View::Playing { .. })));
    assert!(f.player.has_session());
    f.player.shutdown().await;
}

#[tokio::test]
async fn the_same_link_twice_is_handled_once() {
    let f = fixture().await;
    paired(&f);
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(400).set_body_json(failure("PLAYBACK_LAUNCH_CODE_INVALID")),
        )
        .expect(1)
        .mount(&f.server)
        .await;
    f.player.handle_link(&play_link()).await;
    f.player.handle_link(&play_link()).await;
}

/// The server's `Date` header says our clock is 10 minutes slow.
struct TimestampNear(i64);

impl Match for TimestampNear {
    fn matches(&self, request: &Request) -> bool {
        request
            .headers
            .get(TIMESTAMP_HEADER)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.parse::<i64>().ok())
            .is_some_and(|t| (t - self.0).abs() <= 5)
    }
}

#[tokio::test]
async fn a_wrong_clock_is_corrected_and_the_exchange_retried_once() {
    let f = fixture().await;
    paired(&f);
    let skewed = OffsetDateTime::now_utc().unix_timestamp() + 600;
    let date = httpdate::fmt_http_date(std::time::UNIX_EPOCH + Duration::from_secs(skewed as u64));
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .and(TimestampNear(skewed))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body(session_id, "token-1", 660)),
        )
        .expect(1)
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(400)
                .insert_header("Date", date.as_str())
                .set_body_json(failure("PLAYBACK_LAUNCH_CODE_INVALID")),
        )
        .expect(1)
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    assert!(matches!(f.views.last(), Some(View::Playing { .. })));
    f.player.shutdown().await;
}

#[test]
fn views_serialize_for_the_ui() {
    let session_id = Uuid::nil();
    let playing = View::Playing {
        session_id,
        lesson_title: Some("L".into()),
        watermark_text: None,
        watermark: crate::api::WatermarkPolicy::default(),
        capture_status: crate::capture_protection::CaptureStatus::NotRequested,
        media: Media::File {
            url: "https://media.example.test/a.mp4".into(),
            allow_seeking: false,
        },
    };
    assert_eq!(
        serde_json::to_value(&playing).unwrap(),
        json!({
            "screen": "playing",
            "sessionId": "00000000-0000-0000-0000-000000000000",
            "lessonTitle": "L",
            "watermarkText": null,
            "watermark": { "enabled": false, "text": null, "opacity": null, "movementIntervalSeconds": 0, "placement": "OUTSIDE_PLAYER" },
            "captureStatus": "NOT_REQUESTED",
            "media": { "engine": "file", "url": "https://media.example.test/a.mp4", "allowSeeking": false }
        })
    );
    assert_eq!(
        serde_json::to_value(View::NotPaired).unwrap(),
        json!({ "screen": "notPaired" })
    );
    assert_eq!(
        serde_json::to_value(View::Error { code: "X".into() }).unwrap(),
        json!({ "screen": "error", "code": "X" })
    );
}

#[test]
fn server_codes_are_sanitized() {
    assert_eq!(sanitize(Some("ADMIN"), "F"), "ADMIN");
    assert_eq!(sanitize(Some("<img onerror=x>"), "F"), "F");
    assert_eq!(sanitize(Some(""), "F"), "F");
    assert_eq!(sanitize(Some(&"A".repeat(65)), "F"), "F");
    assert_eq!(sanitize(None, "F"), "F");
}

#[test]
fn watermark_policy_defaults_and_bounds_are_enforced() {
    assert_eq!(
        serde_json::from_value::<crate::api::Descriptor>(json!({ "provider": "YOUTUBE" }))
            .unwrap()
            .watermark,
        crate::api::WatermarkPolicy::default()
    );
    assert!(
        sanitize_watermark(&crate::api::WatermarkPolicy {
            enabled: true,
            text: Some("  student@example.test  ".into()),
            opacity: Some(0.25),
            movement_interval_seconds: 30,
            placement: crate::api::WatermarkPlacement::Overlay,
        })
        .is_some_and(|policy| policy.text.as_deref() == Some("student@example.test"))
    );
    assert!(
        sanitize_watermark(&crate::api::WatermarkPolicy {
            enabled: true,
            text: Some("x".into()),
            opacity: Some(0.01),
            movement_interval_seconds: 30,
            placement: crate::api::WatermarkPlacement::Overlay,
        })
        .is_none()
    );
}

#[tokio::test]
async fn ending_does_not_wait_for_a_slow_heartbeat() {
    let f = fixture().await;
    paired(&f);
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body(session_id, "token-1", 60)),
        )
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{session_id}/heartbeat").as_str(),
        ))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(beat("CONTINUE", None, "token-2"))
                .set_delay(Duration::from_secs(15)),
        )
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{session_id}/end").as_str(),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(envelope(Value::Null)))
        .expect(1)
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    // Let the first heartbeat start and hang.
    tokio::time::sleep(Duration::from_millis(1_500)).await;
    let started = std::time::Instant::now();
    f.player.end_by_student().await;
    assert!(
        started.elapsed() < Duration::from_secs(3),
        "took {:?}",
        started.elapsed()
    );
    assert_eq!(
        f.views.last(),
        Some(View::Stopped {
            reason: "ENDED_BY_STUDENT".into()
        })
    );
}

#[tokio::test]
async fn oversized_responses_are_refused() {
    let f = fixture().await;
    paired(&f);
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(ResponseTemplate::new(200).set_body_string("x".repeat(70 * 1024)))
        .mount(&f.server)
        .await;
    f.player.handle_link(&play_link()).await;
    assert_eq!(
        f.views.last(),
        Some(View::Error {
            code: "PROTOCOL".into()
        })
    );
    assert!(!f.player.has_session());
}

fn internal_exchange(session_id: Uuid, signed_url: &str) -> Value {
    envelope(json!({
        "sessionId": session_id,
        "playbackToken": "token-1",
        "expiresAt": in_seconds(60),
        "heartbeatIntervalSeconds": 1,
        "descriptor": {
            "sessionId": session_id, "provider": "INTERNAL", "providerVideoId": null,
            "signedUrl": signed_url, "expiresAt": in_seconds(180), "title": "Lesson 2",
            "durationSeconds": 600, "watermarkText": null, "allowSeeking": false, "allowDownload": false,
            "watermark": { "enabled": false, "text": null, "opacity": null, "movementIntervalSeconds": 0, "placement": "OUTSIDE_PLAYER" },
            "captureProtection": { "enabled": false, "required": false }
        }
    }))
}

fn beat_with_media(token: &str, media_url: &str) -> Value {
    envelope(json!({
        "action": "CONTINUE", "reason": null, "playbackToken": token,
        "expiresAt": in_seconds(60), "heartbeatIntervalSeconds": 1, "mediaUrl": media_url
    }))
}

fn file_url(view: &View) -> Option<String> {
    match view {
        View::Playing {
            media: Media::File { url, .. },
            ..
        } => Some(url.clone()),
        _ => None,
    }
}

#[tokio::test]
async fn a_platform_file_plays_and_gets_fresh_signed_urls_from_heartbeats() {
    let f = fixture().await;
    paired(&f);
    let session_id = Uuid::new_v4();
    let heartbeat = format!("/api/v1/player/sessions/{session_id}/heartbeat");
    let first = format!("{MEDIA_ORIGIN}/videos/a.mp4?X-Amz-Signature=1");
    let second = format!("{MEDIA_ORIGIN}/videos/a.mp4?X-Amz-Signature=2");
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(internal_exchange(session_id, &first)),
        )
        .mount(&f.server)
        .await;
    // A fresh URL on a foreign host is ignored; the next good one is used.
    Mock::given(method("POST"))
        .and(path(heartbeat.as_str()))
        .and(header(TOKEN_HEADER, "token-1"))
        .respond_with(ResponseTemplate::new(200).set_body_json(beat_with_media(
            "token-2",
            "https://evil.example.test/a.mp4",
        )))
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(heartbeat.as_str()))
        .and(header(TOKEN_HEADER, "token-2"))
        .respond_with(ResponseTemplate::new(200).set_body_json(beat_with_media("token-3", &second)))
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(heartbeat.as_str()))
        .and(header(TOKEN_HEADER, "token-3"))
        .respond_with(ResponseTemplate::new(200).set_body_json(beat(
            "STOP",
            Some("ACCESS_REVOKED"),
            "",
        )))
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    let playing = f.views.last().unwrap();
    assert_eq!(file_url(&playing).as_deref(), Some(first.as_str()));
    assert!(matches!(
        playing,
        View::Playing {
            media: Media::File {
                allow_seeking: false,
                ..
            },
            ..
        }
    ));

    let stopped = wait_for(&f.views, |v| matches!(v, View::Stopped { .. })).await;
    assert_eq!(
        stopped,
        View::Stopped {
            reason: "ACCESS_REVOKED".into()
        }
    );
    let urls: Vec<String> = f
        .views
        .0
        .lock()
        .unwrap()
        .iter()
        .filter_map(file_url)
        .collect();
    assert_eq!(
        urls,
        vec![first.clone(), second.clone()],
        "only allowed URLs reach the UI"
    );
}

#[tokio::test]
async fn unplayable_media_gives_the_session_back() {
    let f = fixture().await;
    paired(&f);
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(ResponseTemplate::new(200).set_body_json(internal_exchange(
            session_id,
            "https://evil.example.test/a.mp4",
        )))
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{session_id}/end").as_str(),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(envelope(Value::Null)))
        .expect(1)
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    assert_eq!(
        f.views.last(),
        Some(View::Error {
            code: "MEDIA_UNAVAILABLE".into()
        })
    );
    assert!(!f.player.has_session());
    let shown = f.views.0.lock().unwrap().clone();
    assert!(
        !shown.iter().any(|v| matches!(v, View::Playing { .. })),
        "never shown as playing"
    );
}

#[tokio::test]
async fn a_revoked_installation_is_forgotten_so_the_next_launch_asks_to_pair() {
    let f = fixture().await;
    paired(&f);
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body(session_id, "token-1", 60)),
        )
        .expect(1)
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{session_id}/heartbeat").as_str(),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(beat(
            "STOP",
            Some("INSTALLATION_REVOKED"),
            "",
        )))
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    let stopped = wait_for(&f.views, |v| matches!(v, View::Stopped { .. })).await;
    assert_eq!(
        stopped,
        View::Stopped {
            reason: "INSTALLATION_REVOKED".into()
        }
    );
    assert_eq!(InstallationStore::new(f.dir.clone()).load(), None);
    assert!(
        f.keys.keys.lock().unwrap().is_empty(),
        "the dead key is deleted"
    );

    f.player
        .handle_link(&format!("aocplayer://play?code={}", CODE.replace('A', "C")))
        .await;
    assert_eq!(
        f.views.last(),
        Some(View::Error {
            code: "NOT_PAIRED".into()
        })
    );
}

#[tokio::test]
async fn heartbeats_carry_the_reported_position_and_state() {
    let f = fixture().await;
    paired(&f);
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body(session_id, "token-1", 60)),
        )
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(
            format!("/api/v1/player/sessions/{session_id}/heartbeat").as_str(),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(beat("CONTINUE", None, "token-1")))
        .mount(&f.server)
        .await;

    f.player.handle_link(&play_link()).await;
    // Played, then paused just before the beat: the beat still says PLAYING.
    f.player
        .report_playback(session_id, 41.2, PlaybackState::Playing);
    f.player
        .report_playback(session_id, 43.9, PlaybackState::Paused);
    // Ignored: another session, or a position that isn't a sane number.
    f.player
        .report_playback(Uuid::new_v4(), 900.0, PlaybackState::Playing);
    f.player
        .report_playback(session_id, f64::NAN, PlaybackState::Playing);
    f.player
        .report_playback(session_id, -5.0, PlaybackState::Playing);

    let mut bodies: Vec<Value> = Vec::new();
    for _ in 0..100 {
        bodies = f
            .server
            .received_requests()
            .await
            .unwrap()
            .iter()
            .filter(|r| r.url.path().ends_with("/heartbeat"))
            .map(|r| serde_json::from_slice(&r.body).unwrap())
            .collect();
        if bodies.len() >= 2 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    assert!(
        bodies.len() >= 2,
        "expected two heartbeats, got {}",
        bodies.len()
    );
    let version = env!("CARGO_PKG_VERSION");
    assert_eq!(
        bodies[0],
        json!({ "positionSeconds": 43, "state": "PLAYING", "playerVersion": version })
    );
    // Nothing played since: the paused state is reported as it is.
    assert_eq!(
        bodies[1],
        json!({ "positionSeconds": 43, "state": "PAUSED", "playerVersion": version })
    );
    f.player.end_by_student().await;
}

#[tokio::test]
async fn reports_without_a_live_session_are_ignored() {
    let f = fixture().await;
    let stray = Uuid::new_v4();
    f.player
        .report_playback(stray, 10.0, PlaybackState::Playing);
    assert_eq!(f.player.progress_for_beat(stray), (0, PlaybackState::Idle));
}

const PAIR_CODE: &str = "ZyXwVuTsRqPoNmLkJiHgFeDcBa9876543210_-ZyXwV";

#[tokio::test]
async fn a_play_link_with_a_pairing_code_pairs_then_plays_in_one_step() {
    let f = fixture().await;
    // Never paired on this computer.
    let installation_id = Uuid::new_v4();
    let session_id = Uuid::new_v4();
    Mock::given(method("POST"))
        .and(path("/api/v1/player/pairings/complete"))
        .respond_with(
            ResponseTemplate::new(201)
                .set_body_json(envelope(json!({ "installationId": installation_id }))),
        )
        .expect(1)
        .mount(&f.server)
        .await;
    Mock::given(method("POST"))
        .and(path(EXCHANGE))
        .and(header(
            INSTALLATION_HEADER,
            installation_id.to_string().as_str(),
        ))
        .respond_with(
            ResponseTemplate::new(200).set_body_json(exchange_body(session_id, "token-1", 60)),
        )
        .expect(1)
        .mount(&f.server)
        .await;

    f.player
        .handle_link(&format!("aocplayer://play?code={CODE}&pair={PAIR_CODE}"))
        .await;

    assert!(matches!(f.views.last(), Some(View::Playing { .. })));
    let requests = f.server.received_requests().await.unwrap();
    // Pairing first, with the pairing code; then the exchange, with the launch code.
    assert!(requests[0].url.path().ends_with("/pairings/complete"));
    let pairing: Value = serde_json::from_slice(&requests[0].body).unwrap();
    assert_eq!(pairing["pairingCode"], PAIR_CODE);
    assert_eq!(requests[1].url.path(), EXCHANGE);
    let exchange: Value = serde_json::from_slice(&requests[1].body).unwrap();
    assert_eq!(exchange["launchCode"], CODE);
    // The exchange is signed by the key created for this pairing.
    let saved = InstallationStore::new(f.dir.clone()).load().unwrap();
    assert_eq!(saved.installation_id, installation_id);
    let verifying_key = *f
        .keys
        .keys
        .lock()
        .unwrap()
        .get(&saved.key_name)
        .unwrap()
        .0
        .verifying_key();
    assert!(SignedBy(verifying_key).matches(&requests[1]));
    f.player.end_by_student().await;
}

#[tokio::test]
async fn a_refused_pairing_code_shows_the_error_and_claims_nothing() {
    let f = fixture().await;
    Mock::given(method("POST"))
        .and(path("/api/v1/player/pairings/complete"))
        .respond_with(
            ResponseTemplate::new(400).set_body_json(failure("PLAYER_PAIRING_CODE_INVALID")),
        )
        .mount(&f.server)
        .await;

    f.player
        .handle_link(&format!("aocplayer://play?code={CODE}&pair={PAIR_CODE}"))
        .await;

    assert_eq!(
        f.views.last(),
        Some(View::Error {
            code: "PLAYER_PAIRING_CODE_INVALID".into()
        })
    );
    assert!(
        f.server
            .received_requests()
            .await
            .unwrap()
            .iter()
            .all(|r| r.url.path() != EXCHANGE)
    );
    assert!(InstallationStore::new(f.dir.clone()).load().is_none());
}
