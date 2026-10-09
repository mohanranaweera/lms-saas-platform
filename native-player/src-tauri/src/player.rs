//! The player core (ADR-025 §3, §5): pairing, launching and the session
//! lifecycle - heartbeat, `STOP`, end, and teardown when a heartbeat deadline
//! is missed. It has no Tauri dependency so it can be tested against a mock
//! server.
//!
//! The player never decides entitlement: it plays only while the server keeps
//! answering `CONTINUE`. The playback token and descriptor live only inside
//! the session task and are dropped when it ends; nothing is written to disk
//! except the installation record (no secret).

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant};

use serde::Serialize;
use time::OffsetDateTime;
use tokio::sync::{Mutex as AsyncMutex, watch};
use tokio::task::JoinHandle;
use url::Url;
use uuid::Uuid;

use crate::api::{ApiClient, ApiError, Exchange};
use crate::capture_protection::{CaptureStatus, Controller, UnsupportedController};
use crate::deep_link::{self, DeepLink};
use crate::installation::{Installation, InstallationStore};
use crate::keystore::{self, InstallationKey, KeyError, KeyStore};
use crate::media::Media;

/// Bounds for the server's heartbeat interval.
const MIN_INTERVAL: Duration = Duration::from_secs(1);
const MAX_INTERVAL: Duration = Duration::from_secs(300);
/// After a failed heartbeat, retry at most this often until the deadline.
const RETRY_DELAY: Duration = Duration::from_secs(5);
/// A deadline further away than this is not believed.
const MAX_DEADLINE: Duration = Duration::from_secs(900);
/// How long closing the player waits for `/end`.
const END_TIMEOUT: Duration = Duration::from_secs(5);
/// A clock correction larger than this retries a refused exchange once.
const CLOCK_RETRY_THRESHOLD_SECONDS: i64 = 30;
/// Largest position the UI may report (the server refuses anything larger).
const MAX_POSITION_SECONDS: f64 = 86_400_000.0;

/// What the UI reports about playback (VIDEO Phase 12). Telemetry only: the
/// server decides how much of it counts as watched.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum PlaybackState {
    Playing,
    Paused,
    Idle,
}

impl PlaybackState {
    fn as_str(self) -> &'static str {
        match self {
            PlaybackState::Playing => "PLAYING",
            PlaybackState::Paused => "PAUSED",
            PlaybackState::Idle => "IDLE",
        }
    }
}

/// The latest report for the live session.
struct Progress {
    session_id: Uuid,
    position_seconds: u32,
    state: PlaybackState,
    /// Playback ran at some point since the last heartbeat, so a pause just
    /// before the beat does not hide the time watched (the server still caps
    /// it by wall-clock time and position progress).
    played_since_beat: bool,
}

/// What the UI shows. Codes are mapped to text by the UI.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(
    tag = "screen",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum View {
    NotPaired,
    Pairing,
    Ready,
    Opening,
    Playing {
        session_id: Uuid,
        lesson_title: Option<String>,
        watermark_text: Option<String>,
        watermark: crate::api::WatermarkPolicy,
        capture_status: CaptureStatus,
        media: Media,
    },
    Stopped {
        reason: String,
    },
    Error {
        code: String,
    },
}

pub trait ViewSink: Send + Sync + 'static {
    fn show(&self, view: &View);
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Cancel {
    None,
    /// The student pressed "End" or closed the window.
    User,
    /// A new link replaces the current session.
    Replaced,
}

struct Active {
    generation: u64,
    cancel: watch::Sender<Cancel>,
    task: JoinHandle<()>,
}

struct State {
    view: View,
    active: Option<Active>,
    generation: u64,
    last_link: Option<String>,
}

/// Everything one live session needs; owned by the session task only.
struct Session {
    id: Uuid,
    token: String,
    deadline: Instant,
    interval: Duration,
    lesson_title: Option<String>,
    watermark_text: Option<String>,
    media: Media,
    capture_status: CaptureStatus,
    watermark: crate::api::WatermarkPolicy,
}

pub struct Player {
    api: ApiClient,
    keys: Box<dyn KeyStore>,
    store: InstallationStore,
    sink: Arc<dyn ViewSink>,
    /// Origins a platform-hosted video file may come from (compiled in).
    media_origins: Vec<Url>,
    /// Links are handled one at a time.
    operations: AsyncMutex<()>,
    state: Mutex<State>,
    closing: AtomicBool,
    capture: Arc<dyn Controller>,
    /// Reported by the UI; read by the session task at each heartbeat.
    progress: Mutex<Option<Progress>>,
}

impl Player {
    pub fn new(
        api: ApiClient,
        keys: Box<dyn KeyStore>,
        store: InstallationStore,
        sink: Arc<dyn ViewSink>,
        media_origins: Vec<Url>,
    ) -> Arc<Self> {
        Self::with_capture_controller(
            api,
            keys,
            store,
            sink,
            media_origins,
            Arc::new(UnsupportedController),
        )
    }

    pub fn with_capture_controller(
        api: ApiClient,
        keys: Box<dyn KeyStore>,
        store: InstallationStore,
        sink: Arc<dyn ViewSink>,
        media_origins: Vec<Url>,
        capture: Arc<dyn Controller>,
    ) -> Arc<Self> {
        let view = if store.load().is_some() {
            View::Ready
        } else {
            View::NotPaired
        };
        Arc::new(Self {
            api,
            keys,
            store,
            sink,
            media_origins,
            operations: AsyncMutex::new(()),
            state: Mutex::new(State {
                view,
                active: None,
                generation: 0,
                last_link: None,
            }),
            closing: AtomicBool::new(false),
            capture,
            progress: Mutex::new(None),
        })
    }

    /// Records what the UI is doing. Reports for anything but the live
    /// session, or with a position that isn't a sane number, are ignored.
    pub fn report_playback(&self, session_id: Uuid, position_seconds: f64, state: PlaybackState) {
        if !position_seconds.is_finite() || position_seconds < 0.0 {
            return;
        }
        let mut progress = self.progress();
        if let Some(progress) = progress.as_mut().filter(|p| p.session_id == session_id) {
            progress.position_seconds = position_seconds.min(MAX_POSITION_SECONDS).floor() as u32;
            progress.state = state;
            progress.played_since_beat |= state == PlaybackState::Playing;
        }
    }

    /// What the next heartbeat reports; starts a new reporting window.
    fn progress_for_beat(&self, session_id: Uuid) -> (u32, PlaybackState) {
        let mut progress = self.progress();
        match progress.as_mut().filter(|p| p.session_id == session_id) {
            Some(progress) => {
                let state = if progress.played_since_beat {
                    PlaybackState::Playing
                } else {
                    progress.state
                };
                progress.played_since_beat = progress.state == PlaybackState::Playing;
                (progress.position_seconds, state)
            }
            None => (0, PlaybackState::Idle),
        }
    }

    fn progress(&self) -> MutexGuard<'_, Option<Progress>> {
        self.progress
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    pub fn view(&self) -> View {
        self.state().view.clone()
    }

    pub fn has_session(&self) -> bool {
        self.state().active.is_some()
    }

    /// Returns the server-supplied capture capability currently shown to the UI.
    /// No native handle or policy secret crosses this boundary.
    pub fn capture_status(&self) -> Option<CaptureStatus> {
        match self.view() {
            View::Playing { capture_status, .. } => Some(capture_status),
            _ => None,
        }
    }

    /// Handles one `aocplayer://` link. A link that was just handled (the OS
    /// can deliver the same one twice) is ignored.
    pub async fn handle_link(self: &Arc<Self>, raw: &str) {
        let _one_at_a_time = self.operations.lock().await;
        if self.closing.load(Ordering::SeqCst) {
            return;
        }
        {
            let mut state = self.state();
            if state.last_link.as_deref() == Some(raw) {
                return;
            }
            state.last_link = Some(raw.to_owned());
        }
        match deep_link::parse(raw) {
            Err(_) => self.show(error("INVALID_LINK")),
            Ok(DeepLink::Pair(code)) => self.pair(&code).await,
            Ok(DeepLink::Play { code, pair }) => self.play(&code, pair.as_deref()).await,
        }
    }

    /// "End" in the UI: tells the server, then shows the stopped screen.
    pub async fn end_by_student(&self) {
        let _one_at_a_time = self.operations.lock().await;
        self.stop_active(Cancel::User).await;
    }

    /// Window closing: ends any session (bounded by `END_TIMEOUT`) and refuses
    /// new links.
    pub async fn shutdown(&self) {
        self.closing.store(true, Ordering::SeqCst);
        self.stop_active(Cancel::User).await;
    }

    async fn pair(self: &Arc<Self>, code: &str) {
        self.stop_active(Cancel::Replaced).await;
        self.show(View::Pairing);
        match self.pair_installation(code).await {
            Ok(()) => self.show(View::Ready),
            Err(code) => self.show(View::Error { code }),
        }
    }

    /// Creates a key and pairs it with the student's device; on success the
    /// new installation replaces any previous one (local record and key). The
    /// error is the code the UI shows.
    async fn pair_installation(self: &Arc<Self>, code: &str) -> Result<(), String> {
        let previous = self.store.load();
        let name = keystore::new_key_name();
        let created = {
            let me = Arc::clone(self);
            let name = name.clone();
            tokio::task::spawn_blocking(move || me.keys.create(&name)).await
        };
        let Ok(Ok((key, provider))) = created else {
            return Err("KEY_UNAVAILABLE".to_owned());
        };
        let result = match key.public_key_spki() {
            Ok(public_key) => self.api.complete_pairing(code, &public_key).await,
            Err(failure) => Err(failure.into()),
        };
        match result {
            Ok(installation_id) => {
                let installation = Installation {
                    installation_id,
                    key_name: name,
                    key_provider: provider,
                    paired_at: OffsetDateTime::now_utc(),
                };
                if self.store.save(&installation).is_err() {
                    let _ = self
                        .keys
                        .delete(&installation.key_name, &installation.key_provider);
                    return Err("STORAGE_UNAVAILABLE".to_owned());
                }
                // The server revoked the previous installation of this device.
                if let Some(old) = previous {
                    let _ = self.keys.delete(&old.key_name, &old.key_provider);
                }
                Ok(())
            }
            Err(failure) => {
                let _ = self.keys.delete(&name, &provider);
                Err(error_code(&failure))
            }
        }
    }

    async fn play(self: &Arc<Self>, code: &str, pair: Option<&str>) {
        self.stop_active(Cancel::Replaced).await;
        // The LMS found no active player for this device: pair first, then play.
        if let Some(pair) = pair {
            self.show(View::Pairing);
            if let Err(code) = self.pair_installation(pair).await {
                return self.show(View::Error { code });
            }
        }
        let Some(installation) = self.store.load() else {
            return self.show(error("NOT_PAIRED"));
        };
        let key: Arc<dyn InstallationKey> = match self
            .keys
            .open(&installation.key_name, &installation.key_provider)
        {
            Ok(key) => Arc::from(key),
            Err(KeyError::NotFound) => {
                let _ = self.store.clear();
                return self.show(error("NOT_PAIRED"));
            }
            Err(_) => return self.show(error("KEY_UNAVAILABLE")),
        };
        self.show(View::Opening);
        let offset_before = self.api.clock_offset();
        let mut result = self
            .api
            .exchange(&*key, installation.installation_id, code)
            .await;
        // A refused exchange never consumes the code, so after learning that
        // our clock is off we may try once more with corrected timestamps.
        if matches!(result, Err(ApiError::Rejected { status: 400, .. }))
            && (self.api.clock_offset() - offset_before).abs() > CLOCK_RETRY_THRESHOLD_SECONDS
        {
            result = self
                .api
                .exchange(&*key, installation.installation_id, code)
                .await;
        }
        match result {
            Ok(mut exchange) => {
                let Some(watermark) = sanitize_watermark(&exchange.descriptor.watermark) else {
                    let _ = tokio::time::timeout(
                        END_TIMEOUT,
                        self.api
                            .end(&*key, exchange.session_id, &exchange.playback_token),
                    )
                    .await;
                    return self.show(error("MEDIA_UNAVAILABLE"));
                };
                exchange.descriptor.watermark = watermark;
                let media = Media::from_descriptor(&exchange.descriptor, &self.media_origins);
                if media == Media::Unsupported {
                    // Nothing this player may play: give the session back at once.
                    let _ = tokio::time::timeout(
                        END_TIMEOUT,
                        self.api
                            .end(&*key, exchange.session_id, &exchange.playback_token),
                    )
                    .await;
                    return self.show(error("MEDIA_UNAVAILABLE"));
                }
                let capture_status = self
                    .capture
                    .apply(exchange.descriptor.capture_protection.enabled);
                if exchange.descriptor.capture_protection.required && !capture_status.is_applied() {
                    self.capture.clear();
                    let _ = tokio::time::timeout(
                        END_TIMEOUT,
                        self.api
                            .end(&*key, exchange.session_id, &exchange.playback_token),
                    )
                    .await;
                    return self.show(error("CAPTURE_PROTECTION_UNAVAILABLE"));
                }
                self.start(key, exchange, media, capture_status)
            }
            Err(failure) => self.show(error_view(&failure)),
        }
    }

    fn start(
        self: &Arc<Self>,
        key: Arc<dyn InstallationKey>,
        exchange: Exchange,
        media: Media,
        capture_status: CaptureStatus,
    ) {
        let session = Session {
            id: exchange.session_id,
            token: exchange.playback_token,
            deadline: self.local_deadline(exchange.expires_at),
            interval: clamp_interval(exchange.heartbeat_interval_seconds),
            lesson_title: exchange.descriptor.title,
            watermark_text: exchange.descriptor.watermark_text,
            media,
            capture_status,
            watermark: exchange.descriptor.watermark,
        };
        // Ready before the UI sees the session, so its first report counts.
        *self.progress() = Some(Progress {
            session_id: session.id,
            position_seconds: 0,
            state: PlaybackState::Idle,
            played_since_beat: false,
        });
        self.show(playing_view(&session));
        let (cancel, cancelled) = watch::channel(Cancel::None);
        let mut state = self.state();
        state.generation += 1;
        let generation = state.generation;
        let me = Arc::clone(self);
        let task = tokio::spawn(async move { me.run(generation, key, session, cancelled).await });
        state.active = Some(Active {
            generation,
            cancel,
            task,
        });
    }

    async fn run(
        self: Arc<Self>,
        generation: u64,
        key: Arc<dyn InstallationKey>,
        mut session: Session,
        mut cancelled: watch::Receiver<Cancel>,
    ) {
        let mut wait = session.interval;
        let outcome = loop {
            tokio::select! {
                _ = tokio::time::sleep(wait) => {}
                changed = cancelled.changed() => {
                    let cause = if changed.is_ok() { *cancelled.borrow() } else { Cancel::User };
                    break Err(cause);
                }
            }
            if Instant::now() >= session.deadline {
                break Ok("CONNECTION_LOST".to_owned());
            }
            let (position_seconds, state) = self.progress_for_beat(session.id);
            // "End" or closing the window must not wait for a slow heartbeat.
            let beat = tokio::select! {
                beat = self.api.heartbeat(&*key, session.id, &session.token, position_seconds, state.as_str()) => beat,
                changed = cancelled.changed() => {
                    let cause = if changed.is_ok() { *cancelled.borrow() } else { Cancel::User };
                    break Err(cause);
                }
            };
            match beat {
                Ok(beat) if beat.action == "CONTINUE" => {
                    match (beat.playback_token, beat.expires_at) {
                        (Some(token), Some(expires_at)) => {
                            session.token = token;
                            session.deadline = self.local_deadline(expires_at);
                            if let Some(seconds) = beat.heartbeat_interval_seconds {
                                session.interval = clamp_interval(seconds);
                            }
                            // A platform-hosted file gets a fresh signed URL each beat.
                            let fresh = beat.media_url.as_deref().and_then(|url| {
                                session.media.with_fresh_url(url, &self.media_origins)
                            });
                            if let Some(fresh) = fresh.filter(|fresh| *fresh != session.media) {
                                session.media = fresh;
                                self.show(playing_view(&session));
                            }
                            wait = session.interval;
                        }
                        _ => wait = retry_wait(&session),
                    }
                }
                // STOP, or anything this player does not understand.
                Ok(beat) => break Ok(sanitize(beat.reason.as_deref(), "STOPPED")),
                Err(ApiError::Rejected {
                    status: 401 | 403 | 404,
                    ..
                }) => break Ok("EXPIRED".to_owned()),
                Err(ApiError::Rejected { status: 426, .. }) => {
                    break Ok("PLAYER_VERSION_UNSUPPORTED".to_owned());
                }
                // Network trouble or a server error: keep trying until the deadline.
                Err(_) => wait = retry_wait(&session),
            }
        };
        let reason = match outcome {
            Ok(reason) => {
                // This pairing is dead on the server: forget it, so the next
                // launch asks to pair again instead of failing generically.
                if matches!(reason.as_str(), "INSTALLATION_REVOKED" | "DEVICE_REVOKED") {
                    self.forget_installation();
                }
                Some(reason)
            }
            Err(cause) => {
                let _ = tokio::time::timeout(
                    END_TIMEOUT,
                    self.api.end(&*key, session.id, &session.token),
                )
                .await;
                (cause == Cancel::User).then(|| "ENDED_BY_STUDENT".to_owned())
            }
        };
        // Tear down: the token and descriptor die with this task, and the
        // native window is no longer excluded from capture.
        self.capture.clear();
        {
            let mut progress = self.progress();
            if progress
                .as_ref()
                .is_some_and(|p| p.session_id == session.id)
            {
                *progress = None;
            }
        }
        drop(session);
        {
            let mut state = self.state();
            if state
                .active
                .as_ref()
                .is_some_and(|active| active.generation == generation)
            {
                state.active = None;
            }
        }
        if let Some(reason) = reason {
            self.show(View::Stopped { reason });
        }
    }

    fn forget_installation(&self) {
        if let Some(installation) = self.store.load() {
            let _ = self
                .keys
                .delete(&installation.key_name, &installation.key_provider);
            let _ = self.store.clear();
        }
    }

    async fn stop_active(&self, cause: Cancel) {
        let active = self.state().active.take();
        if let Some(active) = active {
            let _ = active.cancel.send(cause);
            let _ = active.task.await;
        }
    }

    /// Converts the server's deadline to a local instant, using the clock
    /// offset learnt from the server.
    fn local_deadline(&self, expires_at: OffsetDateTime) -> Instant {
        let remaining = (expires_at.unix_timestamp() - self.api.server_now()).max(0) as u64;
        Instant::now() + Duration::from_secs(remaining).min(MAX_DEADLINE)
    }

    fn show(&self, view: View) {
        self.state().view = view.clone();
        self.sink.show(&view);
    }

    fn state(&self) -> MutexGuard<'_, State> {
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

fn sanitize_watermark(policy: &crate::api::WatermarkPolicy) -> Option<crate::api::WatermarkPolicy> {
    if !policy.enabled {
        return Some(crate::api::WatermarkPolicy::default());
    }
    let text = policy.text.as_deref()?.trim();
    if text.is_empty()
        || text.len() > 256
        || !policy
            .opacity
            .is_some_and(|opacity| (0.05..=0.60).contains(&opacity))
        || !(5..=120).contains(&policy.movement_interval_seconds)
    {
        return None;
    }
    Some(crate::api::WatermarkPolicy {
        enabled: true,
        text: Some(text.to_owned()),
        opacity: policy.opacity,
        movement_interval_seconds: policy.movement_interval_seconds,
        placement: policy.placement,
    })
}

fn playing_view(session: &Session) -> View {
    View::Playing {
        session_id: session.id,
        lesson_title: session.lesson_title.clone(),
        watermark_text: session.watermark_text.clone(),
        watermark: session.watermark.clone(),
        capture_status: session.capture_status,
        media: session.media.clone(),
    }
}

fn clamp_interval(seconds: u32) -> Duration {
    Duration::from_secs(seconds.into()).clamp(MIN_INTERVAL, MAX_INTERVAL)
}

fn retry_wait(session: &Session) -> Duration {
    RETRY_DELAY.min(session.interval)
}

fn error(code: &str) -> View {
    View::Error {
        code: code.to_owned(),
    }
}

fn error_view(failure: &ApiError) -> View {
    View::Error {
        code: error_code(failure),
    }
}

fn error_code(failure: &ApiError) -> String {
    match failure {
        ApiError::Rejected { status: 426, .. } => "PLAYER_VERSION_UNSUPPORTED".to_owned(),
        ApiError::Rejected { status: 429, .. } => "PLAYER_RATE_LIMITED".to_owned(),
        ApiError::Rejected { code, .. } => sanitize(code.as_deref(), "REJECTED"),
        ApiError::Network => "NETWORK".to_owned(),
        ApiError::Protocol => "PROTOCOL".to_owned(),
        ApiError::Key(_) => "KEY_UNAVAILABLE".to_owned(),
    }
}

/// Server-provided codes are shown only if they look like codes.
fn sanitize(code: Option<&str>, fallback: &str) -> String {
    match code {
        Some(code)
            if !code.is_empty()
                && code.len() <= 64
                && code
                    .bytes()
                    .all(|b| b.is_ascii_uppercase() || b.is_ascii_digit() || b == b'_') =>
        {
            code.to_owned()
        }
        _ => fallback.to_owned(),
    }
}

#[cfg(test)]
mod tests;
