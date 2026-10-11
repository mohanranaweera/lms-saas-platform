# Multi-Tenant Video Platform + AOC Secure Player — Living Plan

```text
Project:          Multi-Tenant Video Platform + AOC Secure Player
Overall Status:   IN PROGRESS
Current Phase:    12 — Watch-Time & Heartbeat
Last Updated:     2026-10-09
Current Branch:   feat/video-watch-time
Latest Migration: V79__watch_time_accounting.sql
Backend Build:    mvnw verify 2026-10-07: 2419 tests, 0 failures (Phase 8 backend)
Frontend Build:   Phase 7 WEB player done 2026-10-07 (tsc clean, new Playwright spec 8/8)
Player Build:     2026-10-08 (Phase 10) cargo test 45/45 incl. TPM, clippy -D warnings clean (dev + release feature)
Tests:            Phase 6 tests added; Maven verification pending environment access
Open Blockers:    none for Phase 9 dev builds; code-signing route + updater-key custody needed before the first player release
```

| Phase | Description | Status | Started | Completed |
|---|---|---|---|---|
| 1 | Architecture & Repository Analysis | COMPLETE | 2026-10-04 | 2026-10-04 |
| 2 | Media Domain Foundation | COMPLETE | 2026-10-04 | 2026-10-04 |
| 3 | Tenant Video Provider Accounts | COMPLETE | 2026-10-04 | |
| 4 | YouTube Integration | COMPLETE | | |
| 5 | Media Library | COMPLETE | | |
| 6 | Lesson Integration | COMPLETE | 2026-10-06 | |
| 7 | Playback Authorization | COMPLETE | 2026-10-06 | 2026-10-07 |
| 8 | Device & Session Security | COMPLETE | 2026-10-07 | 2026-10-07 |
| 9 | AOC Secure Player Foundation | COMPLETE | 2026-10-08 | 2026-10-08 |
| 10 | Playback Engines | TESTED (commit pending) | 2026-10-08 | 2026-10-08 |
| 11 | Watermark & Capture Protection | COMPLETE | 2026-10-08 | 2026-10-09 |
| 12 | Watch-Time & Heartbeat | IN PROGRESS | 2026-10-09 | |
| 13 | Managed/Self-Hosted Streaming | NOT STARTED | | |
| 14 | Security Hardening | NOT STARTED | | |
| 15 | Integration & Production Readiness | NOT STARTED | | |

Valid statuses: NOT STARTED · PLANNING · APPROVED · IN PROGRESS · BLOCKED · IMPLEMENTED · TESTED · COMPLETE.

> **Provenance:** this plan consolidates an earlier draft, `VIDEO-01 Tenant YouTube Media and
> Secure Player.md` (same day, same `HEAD 3dcd6bf`). That draft held the read-only reviews by the
> solution-architect, database-architect, security-reviewer, backend, frontend, QA and DevOps agents
> plus current Google/Tauri documentation (Context7). All of its content was carried into this
> document. At the owner's instruction the draft was deleted on 2026-10-04 (Q-018 resolved). It was
> never committed.

---

## 0. Phase 1 summary for review

### 0.1 What Phase 1 did
- Re-checked the repository directly (migrations, module list, `videoaccessmanagement` contents,
  `common/api` error codes, `.claude/rules/architecture.md`, ADR-008/018/022, feature-flag
  mechanism, rate limiter, metrics dependencies) and consolidated the agent reviews held in VIDEO-01.
- **No production code, migrations, or config were changed.** The only change is this document,
  on branch `docs/video-platform-architecture` (not committed).

### 0.2 Headline recommendations
1. **Evolve the existing video module, don't build a parallel one.** The brief's `MediaAsset`,
   `TenantVideoProviderAccount`, `PlaybackSession`, `UserDevice` already have live counterparts
   (`video_asset`, `media_provider_connection`, `video_watch_session`, `device`) with FKs, audit
   history and tests. We keep the brief's *domain names* in the Java API but map them to evolved
   tables (VID-ADR-002, **approved 2026-10-04**).
2. **YouTube mode is free hosting with LMS-gated access — not content protection.** An unlisted
   YouTube video plays on youtube.com for anyone holding its ID. Real protection (watermark over
   video, signed URLs, revocation that actually stops bytes) needs `MANAGED_STREAM`.
3. **No overlays on the YouTube player.** YouTube's Required Minimum Functionality forbids them, so
   in YouTube mode the watermark goes in a band *outside* the embedded player.
4. **Uploads go browser → object storage → background worker → YouTube**, not through a request
   thread. Even so, the worker moves bytes through the JVM, so it needs a scoped exception to the
   "no binary media through the app" rule.
5. **`YOUTUBE_CUSTOM` is interfaces + a flag (default OFF) only.** No googlevideo extraction.
6. **The native player gets its own ADR (ADR-025).** Rust/Tauri is outside the CLAUDE.md stack, and
   it touches authentication and deployment, which are both change-controlled.

### 0.3 Blocking approvals needed before Phase 2+
| ID | Approval | Blocks |
|---|---|---|
| ~~B1~~ | **ADR-018 Amendment 2**: allow a *tenant-scoped* YouTube connection. This reverses the per-teacher owner decision and the V60 CHECK. **APPROVED by owner 2026-10-04** | — |
| ~~B2~~ | **Scoped exception to architecture.md:94–97** for the upload transfer worker only (S3 staging → YouTube). **APPROVED by owner 2026-10-04** | — |
| ~~B3~~ | **ADR-008 amendment**: YouTube WEB playback is LMS-gated but unprotected; protected playback requires managed streaming. **Approved by owner for Phase 7 WEB scope 2026-10-06.** Native exchange/player work remains deferred. | Phase 7 |
| ~~B4~~ | **ADR-025 Native Secure Player**: Rust/Tauri 2 stack, `aocplayer://`, installation keys, the exchange endpoint as an ADR-011-style unauthenticated carve-out, signed updates. **ACCEPTED by owner 2026-10-07** (`docs/adr/ADR-025-native-secure-player.md`; Q-008 share slot, Q-009 per-tenant default off, Windows only). Code signing route still open (Phase 9) | Phase 8–11 |
| ~~B5~~ | **VID-ADR-002**: evolve existing tables instead of creating the brief's separately named tables. **APPROVED by owner 2026-10-04** | — |
| ~~B6~~ | **Additive API contracts**: new `/api/v1/media`, `/api/v1/playback/*`; existing `/api/v1/videos/*` unchanged. **APPROVED by owner 2026-10-04** | — |

---

## 1. Executive summary

The LMS already has a tenant-isolated video module (`videoaccessmanagement`, ADR-008): MinIO-backed
assets, per-asset playback policy, single-active watch sessions with JWT playback tokens,
heartbeat/progress, and an overlay watermark. There is also an encrypted per-teacher provider
connection framework (ADR-018: YouTube read-only, Bunny, DynTube) and device limits with a
centralized `AccessPolicyApi` (ADR-022).

This feature turns that into a **provider-neutral media platform**:
- each tenant connects its own YouTube channel (and later a managed streaming provider);
- teachers upload into a tenant Media Library;
- lessons reference media assets through a provider-neutral link;
- the backend authorizes every playback, then hands either the web player or the new native
  **AOC Secure Player** a short-lived, provider-specific `PlaybackDescriptor`;
- the player enforces the session, heartbeat, watch time, watermark and capture deterrence.

Course, Lesson, Enrollment, Payment, Tenant, Auth and RBAC do not change shape. The provider is
always reached through capabilities and strategies.

## 2. Existing repository findings

| Area | What exists | Where |
|---|---|---|
| Modules | 17 modules under `com.lms.*`, including `videoaccessmanagement`, `integrationmanagement`, `contentmanagement`, `coursemanagement`, `enrollmentmanagement`, `auditlogmanagement` | `backend/src/main/java/com/lms/` |
| Migrations | V1–V69, latest `V69__add_textlk_and_meta_whatsapp_providers.sql` | `backend/src/main/resources/db/migration` |
| Video module | `VideoAsset` (status PENDING/READY/FAILED, MinIO key), `VideoPlaybackPolicy`, `VideoWatchSession` (+ revoked reasons), `VideoWatchProgress`, `PlaybackTokenService`, `PlaybackSessionCacheService`, `VideoContentSniffer`, `VideoAccessGuard`, `VideoPlaybackSessionRevokedEvent` | `videoaccessmanagement/**` |
| Video API | `POST /api/v1/videos` (multipart), `PUT /{id}/policy`, `POST /{id}/playback-sessions`, `POST /playback-sessions/{id}/progress`, `POST /playback-sessions/{id}/end` | `VideoController.java:48-100` |
| Upload memory | `VideoAssetService.upload` buffers the whole file; global multipart cap 25 MB | `VideoAssetService`, `application.yml` |
| Provider credentials | `media_provider_connection`: AES-256-GCM with AAD, never deleted, NEEDS_RECONNECT; central OAuth callback, Redis single-use state, pending-confirm step, refresh under a Redis lock | V60, ADR-018 |
| YouTube today | Per-teacher, scope `youtube.readonly`, attach-by-URL, **exempt** from ADR-008 tokens; V60 CHECK forces `YOUTUBE ⇒ scope=TEACHER`; lessons store YouTube as `MaterialType.LINK` | ADR-018 §1/§6, `YouTubeOAuthClient.java` |
| Tenant video host | `tenant_media_integration_settings.video_host ∈ {NONE, DYNTUBE, BUNNY}`; Bunny per-teacher libraries | V60/V61, MEDIA-INT-01A |
| Feature flags | **No flag engine.** Module D "Feature Flag & Plan Limit Engine" is explicitly unratified (V2 comment, module catalog). Flags today are `@ConfigurationProperties` booleans in `application.yml` (e.g. `payment.gateway.fake-fallback-enabled`, `MESSAGING_*_ENABLED`) | `application.yml`, V2 |
| Errors | `ApiResponse<T>` / `ApiError{code}`; codes in `ApiErrorCodes`; typed exceptions in `common/error` (`PlaybackTokenInvalidException`, `VideoPlaybackPolicyViolationException`, `DeviceLimitReachedException`, `TooManyRequestsException`, …) | `common/api`, `common/error` |
| Logging | `CorrelationIdFilter`, `SecretMaskingJsonMembersCustomizer`, `ContactMasking` | `common/config`, `common/logging` |
| Rate limiting | Redis-backed `AuthRateLimiter` (identity module only) | `identityaccessservice/service` |
| Metrics | No Micrometer/Prometheus registry dependency in `backend/pom.xml` | — |
| Jobs | Plain `@Scheduled` + `FOR UPDATE SKIP LOCKED` claim; outbox retry pattern (V68); no ShedLock | `notificationmanagement` |
| Secrets | ADR-019: env vars validated at startup by `ProductionSecretsStartupValidator`, declared in four places | ADR-019 |
| Infra | Docker Compose; no Nginx config yet; CI = `mvnw verify` + frontend lint/build/Playwright | `infrastructure/`, `.github/workflows/ci.yml` |
| Frontend | Route groups `(tenant-admin)`, `(teacher)`, `(student)`, `(platform-admin)`, `(public)`, `(auth)`; `settings/integrations`; `secure-video-player.tsx` (overlay watermark, heartbeat); TanStack Query; `authorizedFetch`; Playwright with `page.route` mocks | `frontend/src` |

### 2.1 Conflicts between the brief and approved rules
| # | Brief asks | Conflicts with | Proposed resolution |
|---|---|---|---|
| C1 | YouTube channel per tenant | ADR-018 Decision 1 (per teacher) + V60 CHECK | B1: ADR-018 Am. 2 + new migration |
| C2 | Playback sessions/"secure" for YouTube | ADR-008 §2, security.md ("never a stable/predictable URL") | B3: YouTube labelled gated-but-unprotected |
| C3 | `POST /api/v1/media` multipart streamed to YouTube | architecture.md:94–97 (no binary media through the app) | Presigned upload to S3 staging + worker; B2 scoped exception |
| C4 | Moving watermark over the YouTube player | YouTube RMF: no overlays on the embedded player | Watermark band outside the player in YouTube mode |
| C5 | Rust/Tauri native player | CLAUDE.md required stack; auth + deployment change control | B4: ADR-025 |
| C6 | `SELF_HOSTED` | architecture.md:94 "never self-hosted on the application VPS" | Interpreted as *tenant/platform-managed external* object storage + CDN (R2/S3), never the app VPS; interface only until its own ADR |
| C7 | Separate `media_asset`, `playback_session`, `user_device`… tables | Existing tables with live FKs/history | B5: VID-ADR-002 |
| C8 | Branch names `feature/video-*` | `.claude/rules/git-workflow.md` (`<type>/<desc>`) | `feat/video-*`, `docs/video-*` |
| C9 | Separate backend and frontend commits per phase | Owner memory: one commit per module (backend + frontend together) | One commit per phase once its frontend is done; backend-only phases commit alone |

## 3. Existing Tenant architecture

- Shared database, shared schema, `tenant_id NOT NULL` + FK on every tenant-owned table (ADR-002).
- `TenantContext` is resolved once per request from the validated JWT in `identityaccessservice`.
  `TenantAwareRepository` / `TenantAwareRepositoryFactoryBean` filter by it and throw
  `CrossTenantPersistenceException` on mismatched saves (ADR-006). `AppendOnlyTenantAwareRepository`
  is used for append-only tables.
- Named cross-tenant carve-outs exist only for webhooks and similar paths (ADR-011, e.g. PayHere
  notify, OAuth callback). The tenant then comes from a server-held record, never from the payload.
- Async work carries `tenant_id` in the job row or event payload.

**Video impact:** every new table is tenant-owned with a composite FK where it references another
tenant-owned row. The OAuth callback and the player `exchange` endpoint are the only new
unauthenticated paths. Both resolve the tenant from a server-held record (OAuth state / launch-code
row).

## 4. Existing authentication / RBAC architecture

- JWT access + rotating refresh tokens. A server-issued `lms_device_id` cookie
  (`Path=/api/v1/auth`) identifies the browser device. Device limits, reset cooldown and
  `AccessPolicyApi` come from ADR-007/ADR-022 (V64/V65).
- RBAC: `DomainArea` × action through `PermissionCheckService` (ADR-009). Relevant areas:
  `INTEGRATIONS` (provider connections), `MATERIALS` (lesson content). Per payments.md §8, a
  domain grant is a category grant, not authorization for a specific mutation.

**Video impact:** new permission checks reuse `INTEGRATIONS` (provider accounts, video
configuration) and `MATERIALS` (media library, lesson media). Whether to add a dedicated
`DomainArea.MEDIA_LIBRARY` is open (Q-011). The native player never receives an LMS JWT (§18).

## 5. Existing Course / Lesson architecture

`Course → CourseModule → CourseLesson` (coursemanagement, V14). `CourseLookupApi.resolveLessonOwnership`
gives cross-module ownership checks. Lesson content is `Material` (contentmanagement, V16/V50/V51)
with types including `VIDEO`/`RECORDING` (→ `video_asset_id`) and `LINK` (current YouTube),
plus sequence, visibility and availability window.

**Video impact:** no YouTube field is ever added to `course_lesson`. The provider-neutral link is
LessonMedia (§15).

## 6. Existing Enrollment / Payment relationship

Enrollment activation follows only verified payment / approved slip / zero-amount / staff grant
(ADR-010/012/013/015/016/017). `AccessPolicyApi.requireStudentCourseAccess` centralizes entitlement:
active enrollment, payment-derived access, expiry, grace, extensions, course device limit and
session-device authorization (ADR-022).

**Video impact:** playback **only** calls `AccessPolicyApi`, at session creation **and again** at
player exchange and periodically during heartbeat. The video module never reads orders, payments or
ledger. Refunds, chargebacks and revocations (`payments.md` §9) end active playback through the
existing revocation event path. No change-controlled payment/enrollment rule is touched.

## 7. Proposed video architecture

```
                    ┌──────────────── Spring Boot modular monolith ─────────────────┐
Tenant Admin─OAuth─▶│ integrationmanagement  (provider HTTP + credentials ONLY)      │─▶ Google OAuth
                    │   media_provider_connection (scope TENANT)  = ProviderAccount  │─▶ YouTube Data API
                    │   VideoProviderGateway impls: YouTube, (Bunny), (SelfHosted)   │─▶ Managed provider
                    │   ObjectStorageApi (presigned multipart, ranged GET)           │─▶ S3/MinIO staging
                    │ videoaccessmanagement  (media domain + playback)               │
Teacher ─meta─────▶ │   MediaAsset(=video_asset)  MediaUploadJob  StatusSync         │
Browser ─PUT parts──┼──────────────────────────────────────────────────────────────▶ │ S3 staging
                    │   ProviderCapabilities  PlaybackStrategy registry              │
Student ─Play─────▶ │   PlaybackAuthorizationService → AccessPolicyApi (enrollment)  │
                    │   PlaybackSession(=video_watch_session)  LaunchCode            │
AOC Player─exchange▶│   PlayerInstallation  Heartbeat  WatchTime                     │
                    │ contentmanagement: Material(VIDEO) = LessonMedia               │
                    │ auditlogmanagement: AuditLogApi                                │
                    └────────────────────────────────────────────────────────────────┘
AOC Player (Tauri 2 / WebView2)
   ├─ YouTubeStandardEngine ──IFrame API──▶ youtube.com
   ├─ HlsEngine / DashEngine ──(NativeMediaProxy)──▶ managed CDN (signed URLs)
   └─ FutureProviderEngine (YOUTUBE_CUSTOM, flag OFF)
```

The ten concerns stay separate:

| Concern | Owner |
|---|---|
| Ownership | `tenant_id` on `video_asset` |
| Storage / Transcoding / CDN | Provider (via `VideoProviderGateway`) |
| Metadata | `video_asset` |
| Lesson association | `Material(VIDEO)` |
| Playback authorization | `PlaybackAuthorizationService` |
| Transport | `PlaybackDescriptor` + `MediaTransport` |
| Presentation | Player engine |
| Player security | Player security/session layers |

## 8. Multi-tenant provider model

### 8.1 Provider modes (playback/hosting capability, not course types)
`YOUTUBE_STANDARD` (Phase 4/10), `YOUTUBE_CUSTOM` (reserved, flag OFF), `MANAGED_STREAM`
(Phase 13; Bunny Stream is the candidate because ADR-018 already integrates it), `SELF_HOSTED`
(interface only).

### 8.2 Capability model (no `if provider == YOUTUBE` in application logic)
```java
public record VideoProviderCapabilities(
    boolean supportsUpload, boolean supportsResumableUpload, boolean supportsExternalStorage,
    boolean supportsAdaptiveStreaming, boolean supportsCustomPlayer, boolean supportsSignedPlayback,
    boolean supportsDrm, boolean supportsNativeProxy, boolean supportsProviderPlayer,
    boolean supportsDelete, boolean supportsThumbnail, boolean supportsProcessingStatus,
    boolean supportsOverlayWatermark, boolean supportsServerSideRevocation) {}
```
| Mode | upload | adaptive | customPlayer | signedPlayback | providerPlayer | overlayWatermark | serverRevocation |
|---|---|---|---|---|---|---|---|
| YOUTUBE_STANDARD | ✔ | ✔ | ✘ | ✘ | ✔ | ✘ (RMF) | ✘ (ID stays playable on youtube.com) |
| YOUTUBE_CUSTOM | ✔ | ✔ | ✔ | ? | ✘ | ✔ | ? — needs authorized arrangement |
| MANAGED_STREAM | ✔ | ✔ | ✔ | ✔ | optional | ✔ | ✔ (token TTL) |
| SELF_HOSTED | ✔ | ✔ | ✔ | ✔ | ✘ | ✔ | ✔ |

Interfaces (in `videoaccessmanagement`, implemented by adapters in `integrationmanagement`):
- `VideoProviderGateway`: `capabilities()`, `startUpload`, `uploadChunk`, `fetchStatus`,
  `updateMetadata`, `delete`.
- `PlaybackStrategy`: `mode()`, `capabilities()`, `buildDescriptor(session, asset, policy)`.
- `PlaybackResolver`: provider asset → transport URLs. Used only by `MANAGED_STREAM`,
  `SELF_HOSTED` and the future `YOUTUBE_CUSTOM`.

A `PlaybackStrategyRegistry` picks the strategy from `(asset.provider, tenant configuration, flags)`.
UI and authorization code ask about capabilities, for example "can this asset carry an overlay
watermark?".

### 8.3 Isolation and failure isolation
- An asset row carries `tenant_id` + `provider_connection_id` with a composite FK. It can only be
  uploaded, synced and played through its own tenant's connection.
- Connection status, quota budget, job retries and errors are per connection. When tenant A gets
  `invalid_grant`, only A's connection becomes NEEDS_RECONNECT and only A's jobs park.
- Shared caveat: all tenants share one Google Cloud project quota (Q-007).

## 9. MediaAsset model (`video_asset`, evolved — VID-ADR-002)

| Brief field | Column | Notes |
|---|---|---|
| id | `id` UUIDv7 | LMS PK; the YouTube ID is never the PK |
| tenant_id | `tenant_id` | existing |
| provider_account_id | `provider_connection_id` | nullable for `INTERNAL`; composite FK `(tenant_id, provider_connection_id)` |
| provider | `provider` | `INTERNAL` (existing MinIO rows), `YOUTUBE`, `MANAGED_STREAM`, `SELF_HOSTED` |
| provider_asset_id | `provider_asset_id` | unique `(tenant_id, provider, provider_asset_id)` |
| title, description, thumbnail_url, duration_seconds, privacy_status | same | `privacy_status ∈ {PRIVATE, UNLISTED, PUBLIC}`; default `UNLISTED` (private cannot be embedded) |
| processing_status | `status` | `CREATED, UPLOADING, UPLOADED, PROCESSING, READY, FAILED, DELETED` (+ legacy `PENDING` mapped) |
| created_by, created_at, updated_at, deleted_at | same + `deleted_by`, `version` | soft delete |
| — | `provider_failure_reason`, `last_synced_at`, `next_sync_at`, `sync_failure_count` | status sync |
| — | `storage_object_key` | becomes nullable; per-provider shape CHECK |

Status transitions are recorded append-only in `video_asset_status_event`. The status machine
rejects illegal transitions (e.g. `READY → UPLOADING`). The UI never assumes "uploaded" means
"playable".

## 10. TenantVideoProviderAccount model (`media_provider_connection`, `scope=TENANT`)

| Brief field | Existing / new column |
|---|---|
| id, tenant_id, provider | existing |
| external_account_id / external_channel_id / external_channel_name | existing `external_account_id` / `external_account_label` + new `external_channel_id` if the account and channel differ |
| credential_reference | existing `*_ciphertext` (AES-256-GCM + AAD = tenant+provider+scope); no separate secret store (VID-ADR-005) |
| connection_status | existing `ACTIVE / NEEDS_RECONNECT / DISCONNECTED` (UI: Connected / Expired / Error / Not connected) |
| connected_by / connected_at / last_verified_at | existing `connected_by`, `created_at`, `last_verified_at` (+ `last_error`) |
| — | `disabled_by ∈ {TENANT, PLATFORM}`, mirroring PAY-GW-01 |

One current tenant YouTube connection per tenant (partial unique index already exists). One channel
cannot be connected to two tenants (unique on `(provider, external_account_id)` for live tenant
rows, Q-003). Rows are never deleted. Assets reference them.

## 11. TenantVideoConfiguration model (new `tenant_video_configuration`, one row per tenant)

```
tenant_id PK/FK, version
default_provider            (NONE|YOUTUBE|MANAGED_STREAM|SELF_HOSTED)
default_playback_mode       (YOUTUBE_STANDARD|MANAGED_STREAM|...)  CHECK: YOUTUBE_CUSTOM rejected unless flag on
default_privacy_status      (UNLISTED|PRIVATE)
uploads_enabled
native_player_required      boolean
watermark_enabled, watermark_opacity (0.05–0.6), watermark_movement_interval_seconds (5–120),
watermark_fields            JSONB array of enum {STUDENT_DISPLAY_ID, MASKED_EMAIL, MASKED_PHONE, SESSION_ID, TIMESTAMP}
capture_protection_enabled, capture_protection_required (refuse vs warn, Q-012)
max_concurrent_sessions     (1–5)
watch_time_policy           (NONE|VIDEO_DURATION_MULTIPLIER|FIXED_MINUTES)
watch_time_multiplier       numeric(4,2)   watch_time_fixed_minutes int
launch_code_ttl_seconds     (15–60, default 45)
heartbeat_interval_seconds  (10–60, default 20)
updated_by, updated_at
```
`max_registered_devices` is **not** duplicated. It stays in the ADR-022 device policy (plan →
tenant → course → student precedence), and this screen links to it. A missing row means the
platform defaults from `app.video.defaults.*`. Every change is audited with before/after values.

## 12. YouTube OAuth architecture (reuses `OAuthConnectFlowService`)

1. `POST /api/v1/tenant-admin/video-providers/youtube/connect` (`INTEGRATIONS CREATE_EDIT`). The
   server creates state: ≥256-bit, stored in Redis, TTL 10 min, consumed with `GETDEL`. It holds
   `{tenantId, initiatingUserId, provider, scope=TENANT, nonce, pkceVerifier, expiresAt}`. The
   endpoint returns the consent URL (`access_type=offline`, `prompt=consent`, PKCE, scopes exactly
   `youtube.upload` + `youtube.readonly`).
2. Google redirects to the central `GET /api/v1/integrations/oauth/youtube/callback`
   (unauthenticated). Tenant and user come **only** from the state. The server exchanges the code,
   checks the granted scopes against an allow-list, and resolves the channel with
   `channels.list?mine=true`.
3. The result is parked under a single-use pending id (10 min). The callback redirects to the
   **same tenant's** settings page (fixed path, no open redirect).
4. The admin sees the channel title and ID, then calls
   `POST …/youtube/pending/{id}/confirm`. The server re-checks same tenant, same initiating user,
   and that the permission is still held. It then encrypts and saves. A forwarded consent link
   fails at this step.
5. Lifecycle:
   - **Reconnect** creates a new row and marks the old one DISCONNECTED. A different channel ID
     needs an explicit "replace channel" confirmation and is audited.
   - **Disconnect** revokes at Google, wipes the ciphertext and cancels in-flight jobs. Assets
     stay (Q-004).
   - **Refresh** uses the existing `MediaTokenService` (Redis lock; `invalid_grant` →
     NEEDS_RECONNECT + security log).
   - A Platform Admin may disable any tenant's connection.
6. Production prerequisites: Google OAuth verification for the sensitive `youtube.upload` scope,
   and a YouTube API compliance audit to extend the quota.

## 13. YouTube upload architecture

```
Browser ─POST /api/v1/media {title, description, privacyStatus, fileName, mimeType, sizeBytes}─▶ backend
  backend: tenant from context · permission · flag YOUTUBE_UPLOAD · quota · ACTIVE connection
           → asset CREATED · S3 multipart initiated (tenant-prefixed key) · presigned part URLs (short TTL)
Browser ─PUT parts (XHR progress, per-part retry, resume)─▶ S3 staging
Browser ─POST /api/v1/media/{id}/upload/complete {parts}─▶ backend
  backend: complete multipart · HEAD size check · ranged GET first 64 KB → VideoContentSniffer
           reject ⇒ delete staging object, FAILED · accept ⇒ UPLOADED, MediaUploadJob QUEUED
UploadTransferWorker (@Scheduled, SKIP LOCKED, never on a request thread):
  init YouTube resumable session (snippet+status) with the tenant's token
  stream S3 ranged GET → PUT 8 MiB chunks (multiple of 256 KiB) with Content-Range
  308+Range ⇒ continue · 5xx/IO ⇒ query "bytes */N", exponential backoff + jitter
  401 ⇒ refresh once · quotaExceeded/uploadLimitExceeded ⇒ QUOTA_PAUSED until Pacific midnight
  invalid_grant ⇒ connection NEEDS_RECONNECT, job parked · cancel ⇒ DELETE session
  success ⇒ provider_asset_id stored, PROCESSING, staging object deleted
```
- The JVM holds at most one chunk per active transfer. Transfers are capped per tenant and globally.
- The resumable `session_uri` is a bearer capability. It is stored encrypted and never returned.
- The staging copy is temporary: lifecycle rule (abort incomplete multipart uploads, expire after N
  days) plus an orphan sweeper. This satisfies brief §24, "no permanent duplicate".
- Rejected alternative: handing the browser the YouTube session URI. That loses server-side
  MIME/size validation (security.md) and leaks a bearer capability.

## 14. Media Library architecture

- Backend: `MediaAssetService` (CRUD, soft delete, 409 while attached), `MediaUploadService`,
  `MediaStatusSyncJob`. YouTube has no processing webhook, so the job polls due assets grouped per
  connection with `videos.list?id=<≤50>&part=status,processingDetails,contentDetails,snippet`
  (1 unit/call) and backs off through `next_sync_at`. `MediaAssetReadyEvent` /
  `MediaAssetFailedEvent` (with `tenant_id`) drive notifications.
- Visibility: Tenant Admin sees the whole tenant. A teacher sees their own uploads plus assets
  attached to courses they teach (Q-010). Students never list media.
- Frontend: card/table views (thumbnail, title, provider, duration, status badge, privacy,
  created). Also upload panel, detail page, edit metadata, delete, attach-to-lesson picker.
  Every view has loading, empty, error, permission-denied and reconnect-required states.

## 15. Lesson integration

`Lesson → LessonMedia → MediaAsset` is realized by the **existing `Material` row of type VIDEO**
(`lesson_id`, `video_asset_id`, sequence, visibility, availability window), exposed through
LessonMedia-shaped endpoints (VID-ADR-003, Q-001). This keeps one entitlement path
(`VideoAccessGuard → MaterialLookupApi → CourseLookupApi → AccessPolicyApi`) and no duplicate
ordering/visibility logic.

- `POST /api/v1/courses/{courseId}/lessons/{lessonId}/media {mediaAssetId, title?, sequence?}`
- `GET  /api/v1/courses/{courseId}/lessons/{lessonId}/media`
- `DELETE /api/v1/courses/{courseId}/lessons/{lessonId}/media/{lessonMediaId}`: soft delete, audited.

Attaching requires: the lesson is in the caller's tenant; the caller owns the course or has
`MATERIALS CREATE_EDIT`; the asset is in the same tenant (composite FK backstop); the asset is not
DELETED. Existing `MaterialType.LINK` YouTube URLs remain as legacy and can be migrated later
(TD-004).

## 16. Playback authorization architecture

`POST /api/v1/playback/sessions {lessonMediaId}` (student JWT):
1. Resolve the tenant from context. Resolve lessonMedia → lesson → course with tenant filters.
   Foreign or unknown ids return 404.
2. `AccessPolicyApi.requireStudentCourseAccess`: enrollment, payment-derived entitlement, expiry,
   grace, device limits, session-device authorization.
3. The asset is READY, the provider connection is not platform-disabled, the material is visible
   and inside its window, and the mode's flag is on.
4. Policy: concurrent sessions (counted under a row lock; the single-active partial unique index
   is the backstop), remaining watch time, `max_views_per_student`.
5. Pick the channel. `native_player_required` gives `launch_channel=NATIVE` plus a launch code.
   Otherwise `WEB` gets a descriptor directly. Modes without a web-safe engine force NATIVE.
6. Create the session bound to the principal's session and device. Audit `PLAYBACK_AUTHORIZED`.
   Denials produce `PLAYBACK_DENIED{reason}` in the security log (audit only for user-actor cases).

The same checks run again at exchange (§18), and access is re-checked every N heartbeats (§20).

## 17. PlaybackSession model (`video_watch_session`, evolved)

Existing: `id, tenant_id, student_id, video_asset_id, token jti, status, revoked_reason,
created_at, expires_at, last_position`. Added: `material_id` (lesson link), `launch_channel
(WEB|NATIVE)`, `player_installation_id`, `playback_mode`, `started_at`, `ended_at`,
`last_heartbeat_at`, `ip_hash`. Added revoked reasons: `ADMIN, DEVICE_REVOKED, ACCESS_REVOKED,
HEARTBEAT_TIMEOUT, CONCURRENCY, WATCH_TIME_EXHAUSTED, ANOMALY`.
Status: `PENDING_LAUNCH → ACTIVE → ENDED | EXPIRED | REVOKED`. Live heartbeat state lives in
Redis; Postgres is written at most once every 60 s and on every terminal transition.

## 18. LaunchCode model (new `playback_launch_code`)

```
id, tenant_id, playback_session_id (composite FK), student_id, issued_for_device_id,
code_hash (HMAC-SHA256(PLAYER_LAUNCH_CODE_PEPPER, code)) UNIQUE,
status ISSUED|CONSUMED|EXPIRED|REVOKED, created_at, expires_at, consumed_at,
consumed_by_installation_id
CHECK (expires_at <= created_at + interval '60 seconds')
unique partial: one ISSUED code per session
```
- Code: 256-bit CSPRNG, base64url (43 chars). TTL from configuration, default 45 s.
- Single use: atomic `UPDATE … SET status='CONSUMED' WHERE code_hash=? AND status='ISSUED' AND
  expires_at>now() RETURNING …`.
- Errors are generic externally. Internally they are distinguished as
  invalid / expired / consumed for the security log.
- The plaintext code is never stored, logged or audited. Nginx access logs strip the `code` param.
- `aocplayer://play?code=…` carries nothing else: no tenant, JWT, video id or host.

## 19. Device model (brief `UserDevice` → ADR-022 `device` + new `player_installation`)

ADR-022 `device` stays the device-limit authority (server-issued id, revoke, reset cooldown).
The native player adds `player_installation`:
```
id, tenant_id, user_id, device_id (composite FK → device), installation_id_hash,
public_key (Ed25519/P-256, from a non-exportable key on the machine), platform, player_version,
status ACTIVE|REVOKED, first_seen_at, last_seen_at, revoked_at, revoked_by, revoked_reason
```
- **Pairing (once):** a signed-in, device-bound browser session clicks "Pair player". The browser
  opens `aocplayer://pair?code=`. The player generates a keypair and posts the public key with the
  pairing code. The installation is then bound to the same device slot, not a new one (Q-008).
- No hardware fingerprinting. The installation id is random, stored hashed server-side, and the key
  is held in Windows CNG (TPM-backed when available).
- Revoking a device or installation revokes its active sessions (`VideoPlaybackSessionRevokedEvent`)
  and is audited (`DEVICE_REVOKED`).

## 20. Watch-time model

- Server-authoritative elapsed time = `min(client-reported delta, server wall-clock since last
  heartbeat)`, counted only while `state=PLAYING`. Seeking adds nothing. This fixes the existing
  `watchedDeltaSeconds=0` bypass in `PlaybackProgressRequest`.
- Policies:
  - `NONE`.
  - `VIDEO_DURATION_MULTIPLIER`: `round(duration × multiplier)` per student per asset.
  - `FIXED_MINUTES`.
  - The existing `max_views_per_student` still applies.
- Running totals live in Redis and are flushed to `video_watch_progress` (+ `authorized_watch_seconds`,
  `last_position_seconds`) at most every 60 s. Exhaustion returns `STOP`, ends the session with
  `WATCH_TIME_EXHAUSTED` and is audited.
- YouTube caveat: watch time can only be enforced inside LMS players.

## 21. AOC Secure Player architecture (`native-player/`, ADR-025)

- Stack: Tauri 2 (Rust core) + WebView2. The UI is a bundled TypeScript/npm app. Plugins:
  `single-instance` (registered first, `deep-link` feature), `deep-link` (static scheme
  `aocplayer`), `updater`.
- Layers (strictly separated):
  - **Security layer** (Rust): deep-link validation, installation key, request signing, API origin
    pinned in the signed binary, navigation allow-list, devtools off, strict CSP.
  - **Session layer** (Rust): exchange, heartbeat scheduler, revocation, `STOP` handling, network
    loss.
  - **Watermark layer** (WebView overlay/band, text from the descriptor).
  - **Playback engine** (WebView): the engine is chosen from `descriptor.mode`.
- The player never decides entitlement. A `STOP`, 401/403, or a missed-heartbeat threshold tears
  down playback and clears media state.
- Updates: Tauri updater with Ed25519 signatures (offline/HSM key) plus Authenticode. HTTPS only,
  no downgrade. The server-side `minPlayerVersion` acts as a kill switch
  (`PLAYER_VERSION_UNSUPPORTED`). Update metadata = `{version, platform, url, signature, notes}`.
- Windows first. macOS and mobile are not designed out: the descriptor and API are platform-neutral
  (Q-015).

## 22. Playback-engine architecture

`PlaybackDescriptor` (sealed, discriminated by `mode`):
```
common: sessionId, playbackToken (ADR-008 JWT, aud=player, session-bound, ≤5 min, refreshed by heartbeat),
        expiresAt, mode, tenant{name, logoUrl}, lesson{title},
        security{watermark{enabled, text, opacity, moveIntervalSeconds, placement OUTSIDE_PLAYER|OVERLAY},
                 captureProtection{enabled, required}},
        heartbeatIntervalSeconds, minPlayerVersion, resumePositionSeconds
YouTubeStandardPlaybackDescriptor : { videoId }                  placement forced OUTSIDE_PLAYER
ManagedHlsPlaybackDescriptor      : { manifestUrl, expiresAt, transport: DIRECT|NATIVE_PROXY }
ManagedDashPlaybackDescriptor     : { manifestUrl, expiresAt, transport }
SelfHostedPlaybackDescriptor      : { manifestUrl, expiresAt, transport }
FutureCustomProviderPlaybackDescriptor : reserved (YOUTUBE_CUSTOM), never emitted while flag OFF
```
Player engines: `YouTubeStandardEngine` (IFrame Player API, documented parameters only, sends an
explicit Referer/origin identifying the AOC client, minimum 200×200, no overlay), `HlsEngine`
(hls.js), `DashEngine` (dash.js), `FutureProviderEngine` (stub that refuses). Engines implement
`load/play/pause/seek/position/state/onError/destroy` and contain no auth or session logic. The web
player (`secure-video-player.tsx`) gets the same engine split for the WEB channel.

## 23. NativeMediaProxy architecture (Phase 13, `MediaTransport` abstraction)

`MediaTransport = DIRECT | NATIVE_PROXY`. With `NATIVE_PROXY`, the WebView loads
`aoc-media://session/<opaque-id>/…` (custom protocol handler, not an open TCP port). The Rust proxy
maps that to upstream URLs **already present in the current descriptor**. Controls:
- per-session upstream host allow-list from the descriptor ∩ a compiled-in provider allow-list;
- HTTPS only;
- strict URL parsing;
- resolved-IP checks that block private, loopback, link-local, CGNAT and cloud metadata
  (169.254.169.254, fd00:ec2::254) addresses;
- re-validation on every redirect (max 2, same allow-list);
- connect/read timeouts and response-size caps per segment/manifest;
- MIME allow-list (`application/vnd.apple.mpegurl`, `application/dash+xml`, `video/mp2t`,
  `video/mp4`, `audio/mp4`, `video/iso.segment`);
- `Range` pass-through;
- forwarded headers limited to `Range`/`Accept`; no cookies, no Authorization from the WebView;
- the session must be ACTIVE (a heartbeat `STOP` drops it immediately).

There is **no** `/fetch?url=` form. The proxy is used only where provider terms allow it.

## 24. YOUTUBE_CUSTOM extension point (reserved; flag `video.youtube-custom-playback.enabled=false`)

| Element | Definition |
|---|---|
| Interfaces | `PlaybackResolver` (provider asset → manifest/streams), `PlaybackStrategy` with `mode=YOUTUBE_CUSTOM`, `FutureCustomProviderPlaybackDescriptor` |
| Player engine | `FutureProviderEngine` → would delegate to `HlsEngine`/`DashEngine`/a custom renderer |
| Transport | `NATIVE_PROXY` with its own host allow-list entry, added only under authorization |
| Configuration | flag (env, default false, startup fails if true outside approved profiles until an ADR exists); `tenant_video_configuration.default_playback_mode` CHECK rejects it while disabled; registry refuses to register a resolver bean without the flag |
| Security controls | all §23 controls; per-tenant opt-in; audit of every resolution; kill switch |
| Unresolved | **No authorized arrangement exists.** Extracting googlevideo/manifest URLs is outside YouTube's API terms. Activation needs a written provider authorization + new ADR (Q-005). No resolver code is written in any phase of this plan. |

Course, Lesson and DB need no redesign: `video_asset.provider=YOUTUBE` stays, and only the strategy
chosen by the registry changes.

## 25. Feature flags

There is no flag engine (Module D unratified), so flags follow the repo pattern: typed
`@ConfigurationProperties` booleans from env vars, with tenant-level switches in
`tenant_video_configuration` (VID-ADR-006). A platform flag OFF overrides tenant ON.

| Flag (property) | Default (prod) | Scope |
|---|---|---|
| `video.youtube-upload.enabled` | false until OAuth verification | platform |
| `video.youtube-standard-playback.enabled` | true once Phase 7 ships | platform |
| `video.youtube-custom-playback.enabled` | **false, locked** | platform |
| `video.managed-stream-playback.enabled` | false | platform |
| `video.self-hosted-playback.enabled` | false (no impl) | platform |
| `video.native-player.enabled` | false until Phase 9 | platform + tenant `native_player_required` |
| `video.dynamic-watermark.enabled` | true | platform + tenant `watermark_enabled` |
| `video.capture-protection.enabled` | true | platform + tenant `capture_protection_enabled` |

Plan-tier gating (commercial plans) waits for Module D (TD-002).

## 26. Database impact

All new and evolved tables are tenant-owned: `tenant_id NOT NULL` + FK, leading-`tenant_id`
indexes, composite FKs for same-tenant references, per-tenant uniques, and no edits to V1–V69.
Audit and status-event tables are append-only. Large CHECK changes on `video_asset` are added
`NOT VALID` and then validated. Redis holds only ephemeral heartbeat, launch-rate and OAuth state.

## 27. Proposed Flyway migrations

Numbers are **proposed**. Each phase re-checks the latest migration before creating its own.

| Phase | Migration | Change |
|---|---|---|
| 2 | `V70__evolve_video_asset_provider_neutral.sql` | §9 columns, status CHECK, unique `(tenant_id, provider, provider_asset_id)`, `(tenant_id, status, created_at) WHERE deleted_at IS NULL`, sync index `(next_sync_at) WHERE status IN ('UPLOADED','PROCESSING')`; `video_asset_status_event` |
| 3 | `V71__tenant_video_provider_accounts.sql` | relax `ck_media_provider_connection_scope` to allow `(YOUTUBE, TENANT)`; channel uniqueness; `disabled_by`; `video_host` CHECK += `YOUTUBE`; `tenant_video_configuration` |
| 4 | `V72__create_media_upload_job.sql` | staging key, encrypted `session_uri`, bytes total/sent, attempts, `next_attempt_at`, status `QUEUED/TRANSFERRING/RETRY_WAIT/QUOTA_PAUSED/COMPLETED/FAILED/CANCELLED`, one live job per asset, claim index; `tenant_video_usage_daily` (uploads, bytes, API units) |
| 6 | none if VID-ADR-003 is approved (else `V7x__create_lesson_media.sql`) | |
| 7 | `V73__playback_sessions_and_launch_codes.sql` | §17 session columns + reasons; `playback_launch_code` |
| 8 | `V74__create_player_installation.sql` | §19 |
| 12 | `V75__watch_time_accounting.sql` | `video_watch_progress` += `authorized_watch_seconds`, `last_position_seconds`, `watch_budget_seconds_snapshot` |
| 13 | `V76__managed_stream_provider.sql` | provider/`video_host` CHECK += managed provider; managed asset fields |

## 28. REST API contracts (additive; `ApiResponse<T>` envelope; `/api/v1/videos/*` unchanged)

```
# Provider accounts (Tenant Admin, INTEGRATIONS)
GET    /api/v1/tenant-admin/video-providers                         -> [ProviderAccountView]
POST   /api/v1/tenant-admin/video-providers/youtube/connect         -> {authorizationUrl}
GET    /api/v1/integrations/oauth/youtube/callback                  (existing central callback)
GET    /api/v1/tenant-admin/video-providers/youtube/pending/{id}    -> {channelId, channelTitle}
POST   /api/v1/tenant-admin/video-providers/youtube/pending/{id}/confirm
POST   /api/v1/tenant-admin/video-providers/{accountId}/disconnect  {reason}
ProviderAccountView = {id, provider, status: NOT_CONNECTED|CONNECTED|EXPIRED|ERROR, channelId, channelTitle,
                       connectedBy{id,name}, connectedAt, lastVerifiedAt, lastError, capabilities}
# Platform Admin
POST   /api/v1/platform-admin/tenants/{tenantId}/video-providers/{accountId}/disable|enable

# Video configuration (Tenant Admin)
GET/PUT /api/v1/tenant-admin/video-configuration

# Media (Teacher / Tenant Admin, MATERIALS)
POST   /api/v1/media {title, description, privacyStatus, provider, fileName, mimeType, sizeBytes}
       -> 201 {mediaAsset, upload{uploadId, partSizeBytes, parts[{partNumber,url,expiresAt}]}}
POST   /api/v1/media/{id}/upload/parts {partNumbers[]} | /upload/complete {parts[{partNumber,etag}]} -> 202
POST   /api/v1/media/{id}/upload/cancel | /upload/retry
GET    /api/v1/media?status=&provider=&q=&page=&size= ; GET/PATCH/DELETE /api/v1/media/{id}
MediaAssetView = {id, provider, title, description, thumbnailUrl, durationSeconds, privacyStatus,
                  processingStatus, uploadProgress{bytesSent,totalBytes}, failureReason, createdBy,
                  createdAt, attachedLessonCount, capabilities}

# Lesson media
POST/GET /api/v1/courses/{courseId}/lessons/{lessonId}/media ; DELETE …/media/{lessonMediaId}

# Playback
POST   /api/v1/playback/sessions {lessonMediaId}
       -> {sessionId, channel: NATIVE|WEB, launch{url, expiresAt}?, descriptor?}
GET    /api/v1/playback/sessions/{id}/status          -> {status, exchanged}
POST   /api/v1/playback/exchange   (no JWT; carve-out) {launchCode, installationId, playerVersion,
                                    platform, nonce, timestamp, signature} -> PlaybackDescriptor
POST   /api/v1/playback/sessions/{id}/heartbeat {positionSeconds, state, playerVersion, nonce, signature}
       -> {action: CONTINUE|STOP, reason?, playbackToken?}
POST   /api/v1/playback/sessions/{id}/end
DELETE /api/v1/tenant-admin/playback/sessions/{id}    (admin revoke)

# Player installations
POST   /api/v1/player/pairings -> {pairingUrl, expiresAt} ; POST /api/v1/player/installations (pair)
GET    /api/v1/player/installations (own) ; DELETE /api/v1/player/installations/{id}
GET    /api/v1/tenant-admin/students/{id}/player-installations ; DELETE … (revoke)
GET    /api/v1/player/releases/latest?platform=        (updater metadata, public)
```

**Error codes** (added to `ApiErrorCodes`; exceptions in `common/error`):

| Code | HTTP | Player/UI message |
|---|---|---|
| `VIDEO_PROVIDER_NOT_CONNECTED` | 409 | "Video uploads aren't set up for your institute yet." |
| `VIDEO_PROVIDER_AUTH_EXPIRED` | 409 | "Reconnect YouTube in Settings → Video Providers." |
| `MEDIA_UPLOAD_FAILED` | 422 | "Upload failed — try again." |
| `MEDIA_PROCESSING` / `MEDIA_NOT_READY` | 409 | "This video is still processing." |
| `MEDIA_NOT_FOUND` | 404 | generic not found (also for cross-tenant ids) |
| `MEDIA_ATTACHED` | 409 | "Remove it from lessons first." |
| `UPLOAD_QUOTA_EXCEEDED` | 429 | "Daily upload limit reached." |
| `PLAYBACK_NOT_AUTHORIZED` | 403 | "You don't have access to this lesson." |
| `PLAYBACK_SESSION_EXPIRED` | 401 | "Session ended — press Play again." |
| `PLAYBACK_DEVICE_LIMIT` | 403 | reuses the ADR-022 device-limit copy |
| `PLAYBACK_CONCURRENT_LIMIT` | 409 | "Video playing elsewhere — stop it there first." |
| `PLAYBACK_WATCH_LIMIT` | 403 | "Watch-time limit for this video reached." |
| `PLAYBACK_LAUNCH_CODE_INVALID` | 400 | single generic external code; invalid/expired/consumed are distinguished only in security logs (anti-oracle — deviation from the brief, §0/VID-ADR-008) |
| `NATIVE_PLAYER_REQUIRED` | 409 | "Open this lesson in the AOC Player." |
| `PLAYER_VERSION_UNSUPPORTED` | 426 | "Update the AOC Player." |

## 29. Frontend impact

- **Settings → Video Providers:** `(tenant-admin)/tenant-admin/settings/video-providers/page.tsx`,
  `components/integrations/youtube-provider-card.tsx` (status, Connect / Reconnect / Disconnect
  with alert-dialog, pending-confirm channel view). Never shows tokens.
- **Video Configuration:** `settings/video-configuration/page.tsx`,
  `components/settings/video-configuration-form.tsx`, `lib/validation/video-configuration.ts`.
  Needs shadcn `switch`, `slider`, `checkbox`, `progress` added through the shadcn CLI.
- **Media Library:** `(tenant-admin)/tenant-admin/media`, `(teacher)/teacher/media` (+ `[mediaId]`);
  `components/media/{media-library-grid,media-card,media-status-badge,media-upload-panel,media-detail,
  media-picker-sheet}.tsx`; `lib/api/media.ts`, `lib/api/upload-multipart.ts` (XHR to presigned URLs,
  progress, per-part retry/resume/cancel, `beforeunload` guard).
- **Lesson editor:** `components/media/lesson-media-attachments.tsx` inside the lesson item.
- **Student playback:** `components/player/{lesson-play-button,native-player-launcher,
  youtube-standard-engine,hls-engine,watermark-layer,playback-error}.tsx`, `lib/api/playback.ts`,
  `lib/player/native-launch.ts` (launch from the click handler, blur heuristic + server-confirmed
  exchange polling, "Didn't open? Download the AOC Player" fallback).
- **Devices:** student `(student)/student/devices` (browser devices + player installations); admin
  device management extends the existing ADR-022 screens.
- **Player download / pairing:** `(public)/player/download`, `(student)/student/player`.
- `lib/auth/permissions.ts`: `canViewMedia`, `canManageMedia`, `canManageVideoConfiguration`,
  `canManageVideoProviders`.

## 30. Security threat model

| Threat | Mitigation |
|---|---|
| OAuth state rebound to another tenant / replay / forwarded consent | server-held state (tenant+user+nonce+PKCE), `GETDEL`, pending-confirm by the same admin with permission re-check |
| Scope creep / wrong channel | granted-scope allow-list; channel shown before confirm; replace-channel audited; one channel ↔ one tenant |
| Token leakage | AES-GCM+AAD, write-only, `SecretMaskingJsonMembersCustomizer`, never in audit/metrics/responses |
| Upload abuse / malware / oversized | presigned URLs scoped to tenant prefix + `Content-Length`; size + magic-byte sniff on the staged object; per-tenant quotas; YouTube processing as the final check |
| Media IDOR | tenant-filtered repositories, 404 on foreign ids, composite FKs |
| Launch-code interception / `aocplayer://` hijack | 45 s, single use, hashed, bound to session+student+device; useless without the installation private key |
| Exchange brute force | 256-bit codes; per-IP and per-installation Redis rate limits (generalize `AuthRateLimiter`); generic errors; security log |
| Heartbeat spoofing / watch-time bypass | signed heartbeats with nonce+timestamp; server-clock elapsed time |
| Descriptor leakage (YouTube) | video ID is effectively public (unlisted) — accepted, labelled; protected content → managed HLS |
| Signed manifest sharing (managed) | short TTL, session-bound token, IP/session claims where the provider supports them, revocation |
| NativeMediaProxy SSRF | §23 controls; no open-URL endpoint |
| Malicious player update | Ed25519 updater signature, Authenticode, HTTPS, no downgrade, `minPlayerVersion` kill switch |
| Tampered player binary | server remains authoritative; tampering only weakens deterrence (watermark/capture), never entitlement |
| Quota exhaustion by one tenant | per-tenant daily unit budget + upload caps, QUOTA_PAUSED queueing |
| Screen capture | `WDA_EXCLUDEFROMCAPTURE` deterrence only; watermark for attribution; honest copy |

**What YouTube mode cannot protect:** playback on youtube.com by anyone holding the ID, outside
every LMS control (tokens, devices, watch time, revocation, watermark). Private videos cannot be
embedded for students, and YouTube has no domain-restricted embed.

**Capture-deterrence limits:** external cameras, HDMI/hardware capture, some GPU drivers, VMs,
remote desktop environments, patched binaries.

## 31. Tenant-isolation analysis

Trusted tenant sources:

| Path | Tenant comes from |
|---|---|
| Request | `TenantContext` |
| OAuth callback | Server state → pending row (same tenant + user confirm) |
| Exchange | Consumed launch-code row |
| Heartbeat | Session row matched to the playback token + installation |
| Jobs | The claimed job / asset row |

No repository method takes a caller-supplied `tenant_id`. The only cross-tenant lookups are named
methods for (a) the OAuth pending id and (b) the launch-code hash. Both are documented in ADR-011
style and covered by tests. Bulk endpoints (media list, usage stats, audit) are tenant-scoped.
Platform-admin views of provider status use an explicit platform-scoped service and never the
tenant repositories.

## 32. Testing strategy

Testcontainers Postgres + Redis. `FakeYouTubeServer` (JDK `HttpServer`, scripted responses, records
`Authorization`), modelled on `FakeMediaProviderServer`. No real Google credentials in CI.

**Mandatory cross-tenant tests (brief §51):**

| # | Test |
|---|---|
| T1 | A cannot list B's media |
| T2 | A cannot retrieve B's media (404) |
| T3 | A cannot update B's media |
| T4 | A cannot delete B's media |
| T5 | A cannot attach B's media to A's lesson, nor A's media to B's lesson |
| T6 | A cannot view or disconnect B's provider account |
| T7 | A's upload uses only A's token (the fake server asserts the bearer) |
| T8 | A's student cannot create playback for B's lesson media |
| T9 | A cannot read, heartbeat or revoke B's session |
| T10 | A cannot revoke B's device or installation |
| T11 | A's OAuth state / pending id cannot be confirmed by B; tampered/replayed state stores nothing |
| T12 | Injected `tenantId` in body/header/query is ignored |
| T13 | A's `invalid_grant` / quota error leaves B working |

**Unit:** capability/strategy selection, status-machine transitions, launch-code generation /
expiry / single use, watch-time math (seek, pause, clock skew), configuration validation, flag
gating (`YOUTUBE_CUSTOM` cannot be selected).

**Integration:**
- upload success and failure (308/Range, 5xx retry, 404 session re-init, quota pause,
  `invalid_grant`);
- processing lifecycle;
- lesson attach;
- authorized and unauthorized playback;
- expired and replayed launch codes;
- 10-way concurrent exchange (exactly one succeeds);
- concurrency cap;
- device, enrollment or chargeback revoked mid-session;
- heartbeat timeout;
- cross-student access;
- schema tests (tenant FKs and indexes).

**Frontend:** Playwright with `page.route` mocks for every screen state, plus real-backend
cross-tenant specs.

**Player (cargo test + WebDriver E2E):**
- deep-link parsing (reject extra params, other hosts, overlong codes);
- invalid or expired code;
- heartbeat failure;
- `STOP`/revocation teardown;
- watermark movement;
- capture-affinity capability detection;
- updater signature rejection;
- NativeMediaProxy SSRF table tests (private IPs, redirects, DNS rebinding, schemes, sizes).

## 33. Deployment architecture

- Backend: no new service. Configuration goes under `app.media.youtube.*` (chunk size, max size,
  transfer concurrency, poll cron, per-tenant unit budget), `app.video.defaults.*`, `app.player.*`
  (min version) and `video.*` flags. New secret `PLAYER_LAUNCH_CODE_PEPPER` is declared in all four
  ADR-019 places. The existing `GOOGLE_OAUTH_CLIENT_ID/SECRET` only gets a scope change, unless a
  separate upload project is chosen (Q-007).
- Object storage: staging prefix with lifecycle rules. CORS allows presigned PUT from tenant
  origins, including custom domains (TEN-DOM-01).
- Nginx (not yet created): no large bodies are needed; strip `code` from access logs.
- Native player: `native-player/` in the monorepo; `.github/workflows/native-player.yml`
  (`windows-latest`). PRs run fmt/clippy/test/build. Release runs only by manual dispatch in a
  protected, approval-gated environment via `tauri-action`. Signing: Authenticode (cloud HSM /
  Trusted Signing); the updater Ed25519 key is a CI secret with an offline backup. `latest.json`
  and installers go on object storage/CDN. **Publishing an update is a production release and needs
  human approval.** The NSIS/MSI installer registers `aocplayer`; WebView2 uses `embedBootstrapper`.
- Google Cloud: one project per environment, and file the quota extension early. An upload costs
  ≈100 units (Dec 2025 revision). The default 10,000 units/day/project ≈ 100 uploads/day **shared
  by all tenants**, minus polling.

## 34. Observability

There is no metrics registry today. Phase 15 proposes adding Micrometer + Prometheus through
`spring-boot-starter-actuator`. It needs devops review, and tenant tags must stay bounded (TD-005).

| Metric | Labels |
|---|---|
| `media_uploads_total` | outcome |
| `media_upload_failures_total` | reason |
| `media_transfer_bytes` / `media_transfer_duration` | — |
| `video_provider_latency` / `video_provider_errors_total` | provider, op |
| `youtube_quota_units_used` | self-counted |
| `media_processing_duration` | — |
| `playback_authorizations_total` / `playback_denials_total` | reason |
| `playback_sessions_active` | — |
| `playback_heartbeat_failures_total` | — |
| `playback_device_limit_failures_total` | — |
| `playback_concurrency_failures_total` | — |
| `player_version_info` | — |

Structured logs carry `tenant_id`, `asset_id`, `job_id`, `session_id` and the correlation id
(`CorrelationIdFilter`, propagated into job rows). They never carry tokens, session URIs or launch
codes.

Alerts: project quota at 70/90 %, tenant over budget, refresh-failure spikes, stuck jobs, staging
growth, exchange-failure spikes.

Audit actions use the existing `<module>.<event>` naming, e.g. `video.provider_connected`
(`VIDEO_PROVIDER_CONNECTED`), `video.media_upload_started`, `video.playback_authorized`,
`video.device_revoked`. System-driven events (`MEDIA_UPLOAD_COMPLETED/FAILED`, `PLAYBACK_EXPIRED`)
have no user actor. The audit DB trigger requires one, so these go to `video_asset_status_event` /
the security log (Q-013).

## 35. Implementation phases

Each phase follows CLAUDE.md: update this plan → backend → backend tests → frontend (if any) → E2E →
security / tenant / integration review → docs → one commit (§2.1 C9). Before starting a phase, set
its status to PLANNING and fill its §P-n "before" block. After finishing, fill the "after" block,
test evidence and files changed.

| Phase | Scope | Backend | Frontend | Player | Needs |
|---|---|---|---|---|---|
| 2 | `video_asset` evolution, status machine, capabilities, `VideoProviderGateway`/`PlaybackStrategy` interfaces, `MediaAssetService` (tenant-scoped), tests | ✔ | — | — | B5 ✔ approved |
| 3 | Tenant YouTube connection (connect/confirm/disconnect/platform disable), `tenant_video_configuration` API, isolation tests | ✔ | provider card, config form | — | B1 |
| 4 | YouTube OAuth scopes, upload pipeline (presign → worker → resumable), status sync, quota budget, `FakeYouTubeServer` | ✔ | — | — | B1, B2 |
| 5 | Media Library API completion + UI (upload, cards, status, edit, delete) | ✔ | ✔ | — | — |
| 6 | LessonMedia endpoints + lesson editor picker | ✔ | IN PROGRESS | — | Q-001 resolved (reuse Material(VIDEO)); verification pending |
| 7 | Authenticated WEB playback authorization, descriptors, WEB YouTube engine | IN PROGRESS | PLANNED | — | B3 approved; B4/native exchange deferred |
| 8 | Player installations, pairing, device limits/revocation, concurrency, admin revoke | ✔ | ✔ | — | B4 |
| 9 | `native-player/` Tauri shell, deep link, single instance, exchange client, session lifecycle, updater foundation, CI | — | download page | ✔ | B4 |
| 10 | Engines: YouTubeStandard (prod), Hls/Dash (behind flag), FutureProvider stub | — | web engine split | ✔ | — |
| 11 | Watermark policy + layer, Windows capture affinity, capability reporting | ✔ | ✔ | ✔ | Q-006, Q-012 |
| 12 | Heartbeat, server watch-time, resume, stale-session cleanup, periodic re-authorization | ✔ | ✔ | ✔ | — |
| 13 | Managed provider: **DynTube** (Bunny later) + signed HLS, own hls.js player; NativeMediaProxy → 14 | ✔ | ✔ | ✔ | Q-002 ✔ + ADR-026 |
| 14 | Security hardening reviews (all §30 areas), dependency review | review | review | review | — |
| 15 | E2E, multi-tenant, failure, load, upgrade, deployment & monitoring review, metrics registry | ✔ | ✔ | ✔ | devops |

## 36. Proposed branch strategy

Following `.claude/rules/git-workflow.md`: `docs/video-platform-architecture` (Phase 1, current),
then `feat/video-media-foundation`, `feat/video-provider-accounts`, `feat/video-youtube-integration`,
`feat/video-media-library`, `feat/video-lesson-integration`, `feat/video-playback-authorization`,
`feat/video-device-security`, `feat/aoc-secure-player`, `feat/video-playback-engines`,
`feat/video-content-protection`, `feat/video-watch-time`, `feat/video-managed-streaming`,
`fix/video-security-hardening`, `test/video-production-readiness`. Each branch gets one PR into
`main` with human approval and never auto-merges. Phase PRs name the change-controlled areas they
touch and link the approving ADR.

## 37. Files / modules expected to change

**Docs:** this plan; `docs/adr/ADR-018-…` (Am. 2); `docs/adr/ADR-008-…` (amendment); new
`ADR-025-native-secure-player.md`, a video provider abstraction ADR (VID-ADR-001/002/006; not yet written - next free number, since ADR-026 is managed streaming);
`.claude/rules/architecture.md` (scoped worker exception); `docs/architecture/*` (video security,
integrations, deployment, observability); `docs/api/{video-access-management,integrations}.md`.

**Backend — modify:**
- `integrationmanagement/{api/MediaProvider,api/MediaConnectionApi,api/ObjectStorageApi,gateway/media/YouTubeOAuthClient,service/OAuthConnectFlowService,service/OAuthStateStore,service/MediaConnectionStore,service/MediaConnectionService,service/TenantMediaSettingsService,domain/MediaProviderConnection,domain/VideoHost,storage/S3ObjectStorageApi,web/TenantAdminIntegrationController,web/OAuthCallbackController}`
- `videoaccessmanagement/{domain/VideoAsset,VideoAssetStatus,VideoWatchSession,VideoWatchSessionRevokedReason,VideoWatchProgress; service/VideoPlaybackSessionService,PlaybackTokenService,VideoContentSniffer; support/VideoAccessGuard; api/VideoAccessApi,VideoAssetSummary; web/VideoController}`
- `contentmanagement/{api/MaterialLookupApi, material/service/MaterialService}`
- `identityaccessservice/{config/SecurityFilterChainConfig, api/DeviceAccessApi}` + tenant-resolution exclusions
- `common/api/ApiErrorCodes`, `common/error/*`
- `application*.yml`, `backend/.env.example`, `infrastructure/{docker-compose.prod.yml,prod-secrets.reference}`

**Backend — new:**
- `videoaccessmanagement/provider/{VideoProviderCapabilities,VideoProviderGateway,PlaybackStrategy,PlaybackStrategyRegistry,PlaybackResolver,PlaybackMode}`
- `…/playback/descriptor/*`
- `…/domain/{VideoAssetStatusEvent,MediaUploadJob,PlaybackLaunchCode,PlayerInstallation,TenantVideoConfiguration}` + repositories
- services `{MediaAssetService,MediaUploadService,UploadTransferWorker,MediaStatusSyncJob,LessonMediaService,PlaybackAuthorizationService,LaunchCodeService,PlaybackExchangeService,PlayerInstallationService,HeartbeatService,WatchTimeService,TenantVideoConfigurationService}`
- controllers `{MediaController,LessonMediaController,PlaybackController,PlayerInstallationController,TenantVideoConfigurationController,TenantVideoProviderController}`
- `integrationmanagement/gateway/media/{YouTubeDataApiClient,YouTubeVideoProviderGateway,YouTubeQuotaException}`
- `config/{VideoFeatureFlags,YouTubeUploadProperties}`
- migrations V70–V76
- tests including `FakeYouTubeServer`

**Frontend:** the files in §29, `e2e/{tenant-admin-video-providers,video-configuration,media-library,
lesson-media,student-playback,player-download}.spec.ts`, `e2e/fixtures/*-mocks.ts`,
`e2e/cross-tenant/video-media.spec.ts`, nav files.

**Player (new):** `native-player/{package.json,src/**,src-tauri/{Cargo.toml,tauri.conf.json,capabilities/*.json,src/{main,lib,deeplink,api_client,installation_key,session,heartbeat,capture_protection,media_proxy,updater}.rs,tests/*.rs}}`,
`.github/workflows/native-player.yml`, `.gitignore`.

---

## 38. Architecture Decision Log

| ID | Date | Decision | Reason | Alternatives | Impact | Status |
|---|---|---|---|---|---|---|
| VID-ADR-001 | 2026-10-04 | Lessons reference media via LessonMedia → MediaAsset; never a YouTube ID/URL on lessons | Provider neutrality | `Lesson.youtubeVideoId` | No course/lesson schema change | ACCEPTED (brief mandate) |
| VID-ADR-002 | 2026-10-04 | Evolve `video_asset`, `media_provider_connection`, `video_watch_session`, ADR-022 `device` instead of new parallel tables; Java API uses brief names | Keep existing FKs, history, tests, one entitlement path | New `media_asset`/`playback_session`/`user_device` tables + migration of old data | Brief table names differ from DB names | ACCEPTED (owner, 2026-10-04) |
| VID-ADR-003 | 2026-10-06 | LessonMedia = existing `Material(VIDEO)` row | One ordering/visibility/entitlement path | New `lesson_media` table | No migration in Phase 6 | ACCEPTED (owner Q-001) |
| VID-ADR-004 | 2026-10-04 | Upload via presigned S3 staging + background worker → YouTube resumable | No binary on request threads; server-side validation; bounded memory | Multipart through backend (brief §21); browser direct to YouTube session URI | Scoped architecture.md exception (worker only) | ACCEPTED (owner, B2, 2026-10-04) |
| VID-ADR-005 | 2026-10-04 | Provider credentials stay in `media_provider_connection` ciphertext (AES-256-GCM+AAD) | Existing reviewed mechanism (ADR-018/019) | External vault/KMS | No new secret store; key rotation = TD-003 | PROPOSED |
| VID-ADR-006 | 2026-10-04 | Feature flags = typed config properties + tenant config switches until Module D exists | No flag engine; repo pattern | Introduce Unleash/FF4J | No per-plan gating yet | PROPOSED |
| VID-ADR-007 | 2026-10-04 | YouTube mode: no overlay on the player; watermark band outside it | YouTube RMF | Overlay anyway | Weaker attribution in YouTube mode | SUPERSEDED 2026-10-11 by ADR-025 Amendment 4 (owner chose overlay + hidden YouTube chrome, terms risk accepted) |
| VID-ADR-008 | 2026-10-04 | One generic external launch-code error; detailed reason only in security logs | Avoid oracle for code probing | Brief's three distinct codes | Player shows one message | ACCEPTED (owner, B4, 2026-10-07) |
| VID-ADR-009 | 2026-10-04 | Player authenticates with an installation keypair (proof-of-possession), never an LMS JWT | Deep-link hijack resistance; no long-lived bearer on disk | Bearer installation token | Pairing step required | ACCEPTED (owner, B4, 2026-10-07) |
| VID-ADR-010 | 2026-10-04 | `YOUTUBE_CUSTOM` = interfaces + locked-off flag; no resolver code | No authorized arrangement; YouTube terms | Implement extraction | Future work needs ADR + provider authorization | ACCEPTED (brief mandate) |
| VID-ADR-011 | 2026-10-04 | NativeMediaProxy via custom protocol scheme with descriptor-bound allow-list, not a localhost port | Reduces local attack surface and SSRF | localhost HTTP proxy | Phase 13 design | PROPOSED |

## 39. Open Questions

| ID | Question | Recommendation | Blocks |
|---|---|---|---|
| ~~Q-001~~ | LessonMedia: reuse `Material(VIDEO)` or new `lesson_media` table? | **Resolved 2026-10-06: reuse existing tenant-scoped `Material(VIDEO)` rows** | — |
| ~~Q-002~~ | Which managed streaming provider? | **Answered 2026-10-09 (owner): DynTube first, Bunny Stream later on the same core - ADR-026 (Accepted 2026-10-10)** | — |
| Q-003 | May one YouTube channel be connected to two tenants? | No | Phase 3 |
| Q-004 | Keep playing already-uploaded videos after disconnect? | Yes, LMS-gated | Phase 3 |
| Q-005 | Under what authorized provider arrangement could `YOUTUBE_CUSTOM` be activated? | None today; owner/legal | — |
| Q-006 | Legal review of RMF for the outside-player watermark band and window capture affinity in YouTube mode | Obtain before Phase 11 ships to prod | Phase 11 |
| ~~Q-007~~ | Google Cloud: one platform project (shared quota), separate upload project, or tenant-supplied projects? | **Resolved 2026-10-04: one platform project per environment + per-tenant budget** | — |
| ~~Q-008~~ | Does a paired player installation share the browser's device slot or count as an extra device? | **Resolved 2026-10-07: share the browser's device slot** | — |
| ~~Q-009~~ | Is the native player mandatory for all tenants, or per-tenant opt-in? | **Resolved 2026-10-07: per-tenant `native_player_required`, default false** | — |
| Q-010 | Teacher visibility: own uploads only, or all tenant media? | **2026-10-04: own uploads in Phase 5; attached-course media added in Phase 6** | Phase 6 |
| ~~Q-011~~ | New `DomainArea.MEDIA_LIBRARY` or reuse `MATERIALS`/`INTEGRATIONS`? | **Resolved 2026-10-04: reuse `MATERIALS`** | — |
| Q-012 | Capture protection required but OS unsupported → refuse or warn? | Refuse when tenant sets `required` | Phase 11 |
| ~~Q-013~~ | Audit `actor_id` for system jobs (DB trigger requires a real user) | **Resolved 2026-10-04: `video_asset_status_event` (actor NULL) + security log; `audit_log` stays for human actions** | — |
| ~~Q-014~~ | Default per-tenant YouTube upload budget | **Resolved 2026-10-04: 20/day/tenant, configurable per tenant** | — |
| ~~Q-015~~ | macOS / mobile players timeline | **Resolved 2026-10-07: Windows only for now; a mobile app (Android/iOS) WILL be built later (owner, 2026-10-07) - see the Phase 8 "Mobile devices" note** | — |
| Q-016 | Which commercial plans get device restrictions / native player? | Wait for Module D | — |
| ~~Q-017~~ | Deleting in the LMS also deletes on YouTube? | **Resolved 2026-10-04: LMS soft delete only; the video stays on YouTube.** Edits (title/description/privacy) DO sync to YouTube (owner), which needs `youtube.force-ssl` - ADR-018 Amendment 3 | — |
| ~~Q-018~~ | Delete the superseded `VIDEO-01` draft? | **Resolved 2026-10-04: deleted at owner's instruction** | — |

## 40. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| YouTube quota (≈100 uploads/day shared) blocks tenants | High | High | Per-tenant budget, QUOTA_PAUSED queue, early quota extension, Q-007 |
| Google OAuth verification for `youtube.upload` delays launch | Med | High | Start in Phase 3; flag `youtube-upload` off until verified |
| Owners believe YouTube mode is "secure" | High | High | Honest UI copy, capability badges, docs |
| RMF violation (watermark/capture) → API access revoked | Med | High | Q-006 legal review; no overlays |
| Native player distribution/signing cost and complexity | Med | Med | ADR-025, Trusted Signing, gated release |
| Deep-link hijack / spoofed player | Med | Med | Installation keypair, pairing, short codes |
| Worker transfers saturate the JVM / network | Med | Med | Chunk-bounded memory, concurrency caps, separate pool |
| Refactoring `video_asset` breaks existing MinIO playback | Med | High | `INTERNAL` provider default, regression tests, NOT VALID checks |
| Scope (15 phases) drifts from plan | Med | Med | This living plan + phase gates |

## 41. Dependencies

Google Cloud project + OAuth consent verification + YouTube quota extension; S3-compatible staging
bucket (MinIO locally) with lifecycle + CORS; Redis; Windows code-signing certificate (Authenticode /
Trusted Signing); updater signing key custody; managed streaming provider account (Phase 13);
Rust toolchain + `windows-latest` CI runners; owner approvals B1–B6; legal review (Q-006).

## 42. Technical Debt / Deferred Work

| ID | Description | Reason Deferred | Risk | Target Phase |
|---|---|---|---|---|
| TD-001 | `VideoAssetService.upload` buffers whole files in heap (existing MinIO path) | Existing behaviour, outside YouTube path | OOM on large uploads | 5 (switch INTERNAL to presigned too) |
| TD-002 | No plan-tier gating of video features | Module D unratified | Can't sell tiers | post-15 |
| TD-003 | No credential encryption-key rotation | Inherited from ADR-018/019 | Long-lived key | 14 |
| TD-004 | Legacy `MaterialType.LINK` YouTube URLs not migrated to MediaAsset | Needs owner data decision | Two YouTube paths coexist | 6 (optional) |
| TD-005 | No metrics registry | Not in repo | No dashboards | 15 |
| TD-006 | macOS/mobile players, DRM, offline playback | Out of scope | — | future ADRs |
| TD-007 | Existing `watchedDeltaSeconds=0` bypass in `PlaybackProgressRequest` | Fixed by server-clock accounting | Watch limits bypassable today | 12 |
| ~~TD-008~~ | Platform Admin cannot disable a tenant's YouTube channel | **Resolved in Phase 4** (V72 `platform_disabled_*`, `/api/v1/platform-admin/tenants/{tenantId}/video-providers`) | — | 4 ✔ |
| TD-009 | A pending tenant authorization that is never confirmed keeps its Google grant until the user revokes it at Google (the Redis entry expires after 10 min with no revoke) | Same behaviour as the existing teacher flow | An orphaned grant on the admin's own Google account | 14 |

---

## Test Evidence

| Phase | Backend | Frontend | Integration | Player | Manual | Failures | Resolution |
|---|---|---|---|---|---|---|---|
| 1 | not run (no code) | not run | not run | n/a | Repository inspection only | — | — |
| 4 | Targeted 2026-10-04: 12 suites / 99 tests / 0 failures — new `MediaUploadIntegrationTest` 19/19, `MediaUploadRulesTest` 6/6, `S3MediaStagingApiTest` 7/7, `YouTubeUploadUnitTest` 1/1, `YouTubeUploadServiceStatusTest` 3/3; unchanged `TenantVideoProviderIntegrationTest` 13/13, `TeacherMediaConnectionIntegrationTest` 16/16, `TenantAdminIntegrationsIntegrationTest` 7/7, `MediaProviderConnectionSchemaIntegrationTest` 5/5, `MediaLibraryIntegrationTest` 10/10, `TenantVideoConfigurationIntegrationTest` 6/6, `S3ObjectStorageApiTest` 6/6. Full `mvnw verify` 2026-10-04: **2338 tests, 0 failures/errors/skips, BUILD SUCCESS** (the shell wrapper was reaped for low memory, Maven itself finished). The retry same-channel guard added after it was verified by re-running `MediaUploadIntegrationTest`: 20/20 (incl. `aFailedUploadCannotBeRetriedIntoADifferentChannel`) | n/a (backend only) | see Backend | n/a | none | none | — |
| 3 (frontend) | — | `tsc --noEmit` clean. `npm run lint` 0 errors (2 pre-existing warnings in untouched files). Playwright `tenant-admin-video-settings.spec.ts` 11/11. Regression `route-groups` + `tenant-admin-integrations` + `accessibility`: 43/44 in a parallel run; the one failure (`Teacher video libraries › a failed setup can be retried…`, untouched code) passed 3/3 when re-run alone, so it is timing-flaky, not a regression | — | n/a | none | 1 flaky pre-existing test | Recorded; not changed |
| 3 | Targeted: integrationmanagement + videoaccessmanagement packages, 27 suites / 207 tests / 0 failures (incl. new `TenantVideoProviderIntegrationTest` 13/13, `TenantVideoConfigurationIntegrationTest` 6/6). Full `mvnw verify` **incomplete**: stopped by Claude Code for low system memory after 257 suites / 1947 tests, 0 failures so far | n/a (frontend not started) | see Backend | n/a | none | none | Full suite to be re-run when the machine has memory |
| 2 | Unit: `VideoAssetLifecycleRulesTest` 8/8, `VideoProviderCatalogTest` 4/4. Full `mvnw verify` 2026-10-04: 2283 tests, 0 failures/errors/skips | n/a (no frontend change) | `MediaLibraryIntegrationTest` 10/10 (3 tagged `cross-tenant`); existing `VideoPlaybackSessionIntegrationTest` 18/18 unchanged | n/a | none | none | — (`application.yml` flag block was added after the full run started; the video suite was re-run afterwards: 53/53 pass) |

## Files Changed

| Phase | Created | Modified | Deleted | Migrations |
|---|---|---|---|---|
| 1 | `docs/plans/multi-tenant video platform + aoc secure player.md` | — | `docs/plans/VIDEO-01 Tenant YouTube Media and Secure Player.md` (untracked draft, never committed) | — |
| 4 | Backend: `V72__create_media_upload_job.sql`; `integrationmanagement/api/{MediaStagingApi,VideoProviderUploadApi}.java`, `storage/{S3Clients,S3MediaStagingApi,UnavailableMediaStagingApi}.java`, `gateway/media/YouTubeUploadHttp.java`, `service/{YouTubeUploadService,PlatformVideoProviderService}.java`, `web/PlatformAdminVideoProviderController.java`; `common/error/MediaUploadException.java`; `videoaccessmanagement/config/VideoUploadProperties.java`, `domain/{MediaUploadJob,MediaUploadJobStatus,TenantVideoUsageDaily}.java`, `repository/{MediaUploadJobRepository,TenantVideoUsageDailyRepository}.java`, `service/{MediaUploadService,MediaUploadStore,UploadTransferWorker,MediaUploadMaintenance,MediaStatusSyncService,MediaUploadScheduler,UploadSchedule}.java`, `web/dto/MediaUploadDtos.java`; tests `videoaccessmanagement/web/MediaUploadIntegrationTest`, `videoaccessmanagement/service/MediaUploadRulesTest`, `integrationmanagement/storage/S3MediaStagingApiTest`, `integrationmanagement/gateway/media/YouTubeUploadUnitTest`, `integrationmanagement/service/YouTubeUploadServiceStatusTest`, test doubles `integrationmanagement/media/{InMemoryMediaStagingApi,FakeYouTubeUploads}` | Backend: `MediaProviderConnection`, `MediaConnectionApi`, `MediaConnectionLookupService`, `MediaTokenService`, `TenantVideoProviderService`, `S3ObjectStorageApi` (shared client factory), `ApiErrorCodes`, `TenantVideoConfiguration`, `TenantVideoConfigurationService`, `VideoAsset`, `VideoAssetRepository`, `MediaController`, `application.yml`; tests `FakeMediaProviderServer`, `MediaIntegrationTestSupport`, test `application.yml`. Docs: `.claude/rules/architecture.md` (B2 scoped exception), `ADR-018` (Phase 4 note), `docs/api/{integrations,video-access-management}.md`, this plan | — | `V72__create_media_upload_job.sql` |
| 3 | Backend: `V71__tenant_video_provider_accounts.sql`; `integrationmanagement/{service/TenantVideoProviderService,web/TenantVideoProviderController}.java`; `videoaccessmanagement/domain/{TenantVideoConfiguration,TenantVideoSettings,WatchTimePolicy,WatermarkField,WatermarkFieldsConverter}.java`, `repository/TenantVideoConfigurationRepository.java`, `service/TenantVideoConfigurationService.java`, `web/TenantVideoConfigurationController.java`, `web/dto/TenantVideoConfigurationRequest.java`; tests `integrationmanagement/media/TenantVideoProviderIntegrationTest`, `videoaccessmanagement/web/TenantVideoConfigurationIntegrationTest`. Frontend: `lib/api/video-providers.ts`, `lib/validation/video-configuration.ts`, `components/integrations/tenant-youtube-channel-card.tsx`, `app/(tenant-admin)/tenant-admin/settings/{video-providers,video-configuration}/page.tsx`, `e2e/tenant-admin-video-settings.spec.ts` | Backend: `OAuthStateStore`, `OAuthConnectFlowService`, `MediaConnectionStore`, `MediaProviderConnection`, `YouTubeOAuthClient`, `ApiErrorCodes`; test `FakeMediaProviderServer`. Frontend: `settings/page.tsx`, `components/layout/nav/tenant-admin-nav.tsx`. Docs: `ADR-018` (Amendment 2), `docs/api/{integrations,video-access-management}.md`, this plan | — | `V71__tenant_video_provider_accounts.sql` |
| 2 | `videoaccessmanagement/domain/{MediaAssetProvider,MediaPrivacyStatus,PlaybackMode,VideoProviderCapabilities,VideoAssetStatusEvent}.java`; `repository/VideoAssetStatusEventRepository.java`; `config/VideoFeatureFlags.java`; `service/{VideoProviderCatalog,MediaAssetService,MediaAssetLifecycleService,MediaAssetView,MediaAssetViews}.java`; `web/MediaController.java`; `web/dto/MediaAssetResponse.java`; tests `domain/VideoAssetLifecycleRulesTest`, `service/VideoProviderCatalogTest`, `web/MediaLibraryIntegrationTest` | `domain/{VideoAsset,VideoAssetStatus}.java`; `service/VideoAssetService.java`; `application.yml`; `docs/api/video-access-management.md`; this plan | — | `V70__evolve_video_asset_provider_neutral.sql` |

## Plan vs Implementation Deviations

Deviations from the **master instruction** proposed in Phase 1 (each needs approval):

| Original (brief) | Proposed | Reason | Security impact | Tenant impact | Follow-up |
|---|---|---|---|---|---|
| New `media_asset`, `tenant_video_provider_account`, `playback_session`, `user_device` tables | Evolve existing tables (VID-ADR-002) | Reuse, no parallel subsystem (brief §2) | Neutral | Neutral (existing filters) | Approved 2026-10-04 |
| `POST /api/v1/media` multipart file | Metadata POST + presigned S3 parts + worker | architecture.md:94–97, memory bounds | Positive (server-side sniff kept) | Tenant-prefixed staging keys | B2 |
| Watermark over YouTube player | Band outside the player | YouTube RMF | Weaker attribution in YT mode | — | Q-006 |
| Distinct launch-code expired/consumed errors | One generic external error | Anti-oracle | Positive | — | VID-ADR-008 |
| `TenantVideoConfiguration.max_registered_devices` | Stays in ADR-022 device policy | Avoid two sources of truth | Positive | — | — |
| `feature/*` branches, split commits | `feat/*`, one commit per phase | git-workflow.md, owner memory | — | — | — |
| **Phase 2:** Media Library API in Phase 5 | Read-only `GET /api/v1/media[/{id}]` shipped in Phase 2 | Tenancy rules require endpoint-level cross-tenant tests for new tenant-owned reads; gives Phase 5 a stable contract | Positive (isolation proven over HTTP) | Proven by T1/T2/T12 tests | Phase 5 adds upload/PATCH/DELETE |
| **Phase 2:** provider abstractions incl. `VideoProviderGateway`, `PlaybackStrategy`, `PlaybackResolver` | Only capabilities, `PlaybackMode`, `VideoProviderCatalog`, `VideoFeatureFlags` | Avoid speculative signatures with no implementation; each interface ships with its first adapter | None | None | Gateway → Phase 4, Strategy → Phase 7, Resolver → Phase 13 |
| **Phase 2:** separate `provider/` package (§37) | Types placed in existing `domain/`, `service/`, `config/` packages | `.claude/rules/architecture.md` package layout | None | None | §37 file list superseded by Files Changed |
| `MEDIA_NOT_FOUND` error code (§28) | Generic `NOT_FOUND` | Matches the rest of the codebase; does not distinguish missing from foreign | Positive (no enumeration signal) | — | Revisit in Phase 5 if the UI needs it |
| Asset provider values `MANAGED_STREAM`/`SELF_HOSTED` (§9) | `ck_video_asset_provider` = `INTERNAL`, `YOUTUBE` only | Provider = concrete host; values added with the migration that ships each provider | None | None | Phase 13 migration |
| Sync scheduling index in V70 (§27) | Deferred | Ships with the job that reads it | None | None | Phase 4 ✔ (V72 `idx_video_asset_sync_due`) |
| **Phase 4:** `disabled_by (TENANT\|PLATFORM)` on the connection (Before block) | `platform_disabled_at` + `platform_disabled_reason`, PLATFORM only | The tenant already disables by disconnecting; only the platform switch-off needs a new state, and it must not touch status/credentials | Positive (narrow CHECK: tenant YouTube rows only) | None | — |
| **Phase 4:** `tenant_video_configuration.daily_upload_limit` editable per tenant (Q-014) | Column + entity field, not in the Phase 3 settings API | Keeps the approved Phase 3 API contract unchanged; changed by platform operations for now | None | None | Phase 5 may expose it |
| **Phase 4:** S3 staging tests | In-memory `MediaStagingApi` test double + mocked-SDK unit test, no MinIO container | No S3 Testcontainers module in the backend (owner-accepted in the Before block) | Real S3 presign/multipart covered only by unit tests | None | Optional MinIO container later |
| **Phase 4:** upload progress in `MediaAssetResponse` (§28 `uploadProgress`) | Not returned yet | Phase 5 (Media Library completion) owns the response changes | None | None | Phase 5 |
| **Phase 4:** `uploadId` in the create response (§28) | Not returned | The server keeps the S3 upload id; the browser only needs part URLs | Positive | None | — |

---

## Phase log

### Phase 1 — Architecture & Repository Analysis
- **Before:** objective = analyse repo, produce this plan, change no code. Affected = docs only.
  DB/API/frontend/player/tenant/security impact: none.
- **After:** plan created. No code, migrations or tests.
- **2026-10-04 owner decisions:** B5 / VID-ADR-002 approved (evolve existing tables). The VIDEO-01
  draft was deleted (Q-018).
- **Historical Phase 1 note:** B1–B4 and B6 were open at the time of the original architecture review; later phase blocks record the approvals and implementation decisions.
- **Phase 2 prerequisites:** B5 was met. Q-001 / Q-003 / Q-004 affected later phases; Q-001 is now resolved by VID-ADR-003.

### Phase 2 — Media Domain Foundation

**Before (2026-10-04, status → IN PROGRESS; owner approved B5 + B6 and said "go ahead with Phase 2")**

- **Objective:** make `video_asset` provider-neutral (the `MediaAsset` model). Add the asset status
  machine with append-only history, the provider capability catalog, playback modes and feature
  flags (with locked modes that fail fast), and a tenant-scoped read API that later phases build on.
- **Repository state:** `HEAD 3dcd6bf`, branch `feat/video-media-foundation`, latest migration V69.
  `VideoAsset` maps V51 (`status ∈ PENDING/READY/FAILED`, only `READY` is ever persisted).
  `VideoAssetRepository` is tenant-aware: overridden CRUD and `Specification` reads are filtered;
  derived finders are not, so Specifications are used.
- **Affected module:** `videoaccessmanagement` only (plus `common/api/ApiErrorCodes` if a code is
  needed). No change to `integrationmanagement`, content, course, enrollment or payment modules.
- **Proposed changes:**
  - `V70__evolve_video_asset_provider_neutral.sql`:
    - add §9 columns minus the sync scheduling index (deferred to Phase 4 with its job);
    - provider `INTERNAL|YOUTUBE`, widened later per phase;
    - status set `CREATED…DELETED` (legacy `PENDING` → `CREATED`; none exist);
    - provider-shape / deleted-consistency / processed-has-provider-id CHECKs;
    - composite FKs to `media_provider_connection` and `tenant_user`;
    - per-tenant unique provider asset id;
    - library indexes;
    - append-only `video_asset_status_event` with a forbid-mutation trigger.
  - Domain:
    - `VideoAsset` new fields, `createForProvider` factory, `transitionTo`;
    - `VideoAssetStatus` transition table;
    - `MediaAssetProvider`, `MediaPrivacyStatus` (+ converters);
    - `VideoAssetStatusEvent` + `AppendOnlyTenantAwareRepository`.
  - `videoaccessmanagement/provider/`: `VideoProviderCapabilities`, `PlaybackMode`,
    `VideoProviderCatalog` (capabilities + candidate modes per provider), `VideoFeatureFlags`
    (`video.features.*`; `youtube-custom-playback`, `managed-stream-playback`,
    `self-hosted-playback` fail startup if enabled — no implementation/ADR yet).
  - Services:
    - `MediaAssetService` (tenant-scoped list/get with role visibility);
    - `MediaAssetLifecycleService` (validated transition + status event, for Phases 4/5);
    - the legacy `VideoAssetService.upload` now records its initial `→ READY` event.
  - `MediaController`: `GET /api/v1/media`, `GET /api/v1/media/{id}`.
- **DB impact:** V70 only. Additive columns. `storage_object_key` becomes nullable, guarded by a
  CHECK. Existing rows become `provider=INTERNAL`. Status column widened to 20.
- **API impact:** new read endpoints (additive, B6).
  - Visibility: staff with `MATERIALS VIEW` see the whole tenant library; Teacher/TA see their own
    uploads; Student gets 403.
  - Not-visible or foreign ids return 404.
  - `/api/v1/videos` is unchanged: INTERNAL assets stay `READY`, and the response enum only gains
    values that INTERNAL rows never take.
- **Frontend / player impact:** none (Media Library UI is Phase 5).
- **Tenant impact:** every read goes through tenant-filtered Specifications. Composite FKs keep the
  asset, connection, uploader and status event in the same tenant. Status events are tenant-owned
  and append-only.
- **Security impact:**
  - No credentials, tokens or storage keys in responses (`storage_object_key` is never exposed).
  - Locked flags cannot be switched on by configuration.
  - Illegal transitions → 409.
- **Tests:**
  - Unit: status transitions, provider catalog, feature-flag locks.
  - Integration (Testcontainers):
    - media list/get visibility by role;
    - cross-tenant list/get (A cannot list or retrieve B's media → T1/T2);
    - injected `tenantId` ignored (T12);
    - lifecycle transitions persist events in the right tenant;
    - illegal transition rejected;
    - status-event table refuses UPDATE/DELETE;
    - legacy upload records its event;
    - existing video suite still green.
- **Dependencies / assumptions:** none external. `VideoProviderGateway` / `PlaybackStrategy` /
  `PlaybackResolver` interfaces are deferred to the phases that implement them (4 / 7 / 13) so no
  speculative signatures ship.
- **Risks:** V70 breaking existing MinIO playback. Mitigated: INTERNAL is the default and the full
  existing video suite is re-run.
- **Unresolved:** Q-010 (teacher sees attached-course media too) is deferred to Phase 5/6;
  `attachedLessonCount` lands in Phase 6.

**After (2026-10-04, status → TESTED; commit pending owner approval)**

- **Implemented as planned:** V70, status machine + append-only history (entity guard + DB
  trigger), capability catalog, playback modes, locked feature flags, tenant-scoped Media
  Library reads, and the lifecycle service. The legacy upload now writes its `→ READY` event in
  the same transaction as the asset (`MediaAssetLifecycleService#create`).
- **Files / migrations:** see Files Changed (Phase 2).
- **APIs:** `GET /api/v1/media`, `GET /api/v1/media/{id}` (documented in
  `docs/api/video-access-management.md`). `/api/v1/videos/*` unchanged; its response `status`
  enum only gained values INTERNAL rows never take.
- **Tests:** see Test Evidence (Phase 2). Full suite green.
- **Technical decisions:**
  - Types live in the existing `domain`/`service`/`config` packages.
  - Enum columns use `@Enumerated(STRING)` (the majority repo pattern).
  - Event actor = the authenticated principal only if it belongs to the asset's tenant;
    otherwise `NULL` (system). This is needed because the actor FK only accepts `tenant_user`.
- **Security review (inline):**
  - Responses carry no storage key, connection id or credential (asserted in a test).
  - LIKE wildcards are escaped.
  - Page size is capped at 100.
  - Locked flags fail startup.
  - Illegal transitions → 409.
  - Students → 403.
- **Tenant-isolation review (inline, `.claude/rules/tenancy.md` checklist):**
  1. Cross-tenant tests are in the same change.
  2. No repository method accepts a caller-supplied `tenant_id` (reads are Specifications).
  3. Endpoints use the shared JWT tenant context, and a client-supplied `tenantId` is proven
     ignored.
  4. Composite FKs: asset↔connection, asset↔uploader/deleter, event↔asset/actor.
- **Deviations:** six, recorded in Plan vs Implementation Deviations.
- **Known issues:** none. TD-001 (heap-buffered INTERNAL upload) is unchanged.
- **Next-phase prerequisites (Phase 3):**
  - **B1** — the ADR-018 Amendment 2 approval for a tenant-scoped YouTube connection;
  - Q-003 (one channel ↔ one tenant);
  - Q-004 (playback after disconnect);
  - the owner's commit approval for Phase 2.
- **Committed by owner:** `0c8779d feat(video): add provider-neutral media asset foundation`
  (on local `main`).

### Phase 3 — Tenant Video Provider Accounts

**Before (2026-10-04, status → IN PROGRESS; owner approved B1 = ADR-018 Amendment 2)**

- **Objective:**
  - a Tenant Admin connects the tenant's **own** YouTube channel through OAuth (upload +
    readonly scopes), then reviews and confirms it, and can verify or disconnect it;
  - tenant-wide video configuration (`TenantVideoConfiguration`).
- **Repository state:** branch `feat/video-provider-accounts` from `0c8779d`. V60's
  `ck_media_provider_connection_scope` forces `YOUTUBE ⇒ TEACHER`. The ADR-018 OAuth flow
  (Redis state + PKCE, pending-confirm, AES-GCM with AAD scoped to tenant or owner) is teacher-only.
  The tenant API-key flow (Bunny/DynTube), `adminTest` and `adminDisconnect` already exist and
  work by connection id inside the tenant.
- **Affected modules:**
  - `integrationmanagement`: provider account, OAuth, controller;
  - `videoaccessmanagement`: configuration;
  - docs: ADR-018 Amendment 2.
- **Proposed changes:**
  - `V71__tenant_video_provider_accounts.sql`:
    - re-create `ck_media_provider_connection_scope` to also allow
      `(YOUTUBE, TENANT, owner NULL, OAUTH)`;
    - `uq_media_provider_connection_youtube_channel` on `(provider, external_account_id)`
      for live tenant-scoped YouTube rows (Q-003: one channel ↔ one tenant);
    - `tenant_video_configuration` (one row per tenant).
  - `OAuthStateStore` records carry `scope`; a missing value (older Redis entries) means
    TEACHER. The teacher confirm refuses a TENANT pending and vice versa.
  - `OAuthConnectFlowService.completeCallback`, for scope TENANT:
    - credentials are sealed with the tenant AAD (owner `null`);
    - granted scopes must include `youtube.upload` + `youtube.readonly`, otherwise the
      tokens are revoked and the user is redirected with `error=missing_scopes`;
    - the user is redirected to `/tenant-admin/settings/video-providers`.
  - `YouTubeOAuthClient#tenantChannelAuthorizationUrl` requests exactly those two scopes
    without `include_granted_scopes`.
  - New `TenantVideoProviderService` + `TenantVideoProviderController`
    (`/api/v1/tenant-admin/video-providers`):
    - list;
    - `youtube/connect`;
    - `youtube/pending/{id}` (non-consuming preview);
    - `youtube/pending/{id}/confirm {replaceChannel}`;
    - `{id}/test`;
    - `{id}/disconnect`.
    - Requires `INTEGRATIONS` VIEW / CREATE_EDIT.
    - The pending entry may only be used by the same tenant + the same initiating admin.
    - Replacing a *different* current channel needs `replaceChannel=true`; without it the
      response is 409 and the pending entry is kept.
  - `MediaConnectionStore#saveTenantOAuthConnection`: replaces the current row (old row →
    DISCONNECTED/SYSTEM, never deleted). Audited as `video_provider.connected`,
    `video_provider.reauthorized` (same channel) or `video_provider.channel_replaced`.
    Disconnect keeps the existing `media_integration.disconnected` audit + Google revoke.
  - `TenantVideoConfiguration` (videoaccessmanagement):
    - entity, repo, service, `GET/PUT /api/v1/tenant-admin/video-configuration`;
    - platform defaults when no row exists;
    - every change audited with before/after (`video_configuration.updated`).
- **DB impact:** V71 only. Additive CHECK relaxation + partial unique index + one new
  tenant-owned table (`tenant_id` unique FK, CHECK ranges). No data rewrite.
- **API impact:** new additive endpoints listed above (B6). Existing teacher/tenant-admin
  integration endpoints are unchanged.
- **Frontend impact:** none in this step. The provider card + configuration form follow as the
  Phase 3 frontend step after backend review.
- **Tenant impact:**
  - OAuth tenant/user come only from server state.
  - Credentials AAD is bound to the tenant.
  - Composite tenant FKs throughout.
  - All lookups go through tenant-aware Specifications.
  - The global channel-uniqueness index is the one deliberate cross-tenant constraint: it can
    only reject, and the error is generic.
- **Security impact:**
  - Tokens are never returned or logged.
  - Pending confirm is bound to the initiating admin with a permission re-check.
  - Forwarded or mismatched pendings are revoked at Google.
  - The scope allow-list is enforced.
  - Replace-channel needs explicit confirmation and is audited.
- **Tests:** `FakeMediaProviderServer` gains a configurable Google scope + channel.
  - Integration:
    - connect → preview → confirm;
    - missing scope rejected;
    - teacher cannot confirm a tenant pending, and the reverse;
    - another admin in the same tenant cannot confirm;
    - tenant B cannot preview/confirm A's pending (T11);
    - B cannot list/test/disconnect A's account (T6);
    - the ciphertext does not open under another tenant's AAD;
    - one channel cannot be confirmed by two tenants (Q-003);
    - replace-channel guard;
    - reconnect same channel → old row DISCONNECTED;
    - disconnect revokes at Google;
    - configuration defaults/update/validation/audit and cross-tenant isolation;
    - Student/Teacher → 403.
  - Existing teacher YouTube tests must stay green.
- **Assumptions (provisional, owner may override):** Q-003 = one channel ↔ one tenant. Q-004 =
  already-uploaded videos keep playing after disconnect (affects Phase 7, not this phase).
- **Frontend step (owner: "go ahead with the Phase 3 frontend", 2026-10-04):**
  - `lib/api/video-providers.ts` (hooks for both endpoint groups);
  - `lib/validation/video-configuration.ts`;
  - `settings/video-providers/page.tsx`: YouTube channel card (Connect/Reconnect/Test/Disconnect)
    plus OAuth return handling. `?pending=` → preview → explicit Confirm, with replace-channel
    wording when the channel differs; `?error=denied|failed|missing_scopes`;
  - `settings/video-configuration/page.tsx`: full settings form; Read-only Auditor sees it
    disabled;
  - settings index + nav links (`INTEGRATIONS` view);
  - Playwright `e2e/tenant-admin-video-settings.spec.ts` (mock-only; isolation is proven by the
    backend tests). Native inputs/selects as in `devices-access`; no new shadcn components.
- **Deferred:**
  - Platform Admin disable of a tenant account → TD-008;
  - `video_host` CHECK unchanged (the default provider lives in the configuration);
  - YOUTUBE_CUSTOM mode preference is not stored until that mode exists.

**After (2026-10-04, status → TESTED; full backend suite green, commit pending)**

- **Implemented as planned.** Backend: V71, tenant-scoped YouTube OAuth (scope-tagged state,
  preview + confirm by the initiating admin, replace guard, channel-in-use guard, revoke on every
  rejected path), video provider endpoints, tenant video configuration with audit. Frontend:
  Video providers page (channel card, Google return handling with explicit confirm and
  replacement wording, expired / missing-scope messages, disconnect dialog), Video settings form,
  index + nav links.
- **Technical decisions:**
  - `watermark_fields` is a CHECK-regex'd comma list with a JPA converter, not JSONB (avoids
    the JSON FormatMapper on Jackson 3);
  - TENANT credentials are sealed with the existing tenant AAD form;
  - disconnect reuses `MediaConnectionService.adminDisconnect`, guarded to the tenant YouTube
    row;
  - "Connected by" shows "You" or "An administrator" (the API returns only a user id).
- **Security review (inline):**
  - no token in any response (asserted backend + E2E);
  - consent URL must be `https:` before navigating;
  - the `?pending`/`?error` query is stripped from the address bar on load;
  - the confirm button is never auto-fired (unlike the teacher flow), so a forwarded link cannot
    silently connect a channel;
  - server-side permission checks on every endpoint; Read-only Auditor UI is read-only.
- **Tenant-isolation review:**
  - T6 / T11 / Q-003 / AAD-binding tests added;
  - no repository method takes a caller `tenant_id`;
  - the one cross-tenant artifact is the V71 channel unique index (reject-only).
- **Deviations:** `video_host` CHECK unchanged and no YOUTUBE_CUSTOM preference column (recorded
  above). Platform-admin disable deferred → TD-008. Orphaned Google grant on abandoned pending
  → TD-009.
- **Known issues:**
  - the first full `mvnw verify` was killed for low memory after 1947/≈2300 tests (0 failures);
    the re-run on 2026-10-04 (`MAVEN_OPTS=-Xmx512m`, `-DargLine="-Xmx1536m -XX:+UseSerialGC"`)
    passed: 2302 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS;
  - one pre-existing timing-flaky E2E test.
- **Next-phase prerequisites (Phase 4, YouTube upload pipeline):**
  - **B2** (scoped exception to architecture.md for the transfer worker);
  - Q-007 (Google Cloud project/quota model);
  - Q-013 (system-actor audit, current answer: status-event table);
  - Q-014 (per-tenant upload budget);
  - TD-008 before uploads go live;
  - S3 staging bucket lifecycle/CORS (devops).

### Phase 4 — YouTube Upload Pipeline

**Before (2026-10-04, status → PLANNING; owner approved B2, Q-007, Q-013, Q-014 as recommended)**

- **Objective:** a Teacher or permitted staff member uploads a video to the tenant's connected
  YouTube channel. Bytes go browser → S3 staging (presigned multipart). A background worker
  streams them staging → YouTube resumable upload. A status-sync job then moves the asset to
  READY or FAILED. All of this is backend only; the upload UI is Phase 5.
- **Repository state:** branch `feat/video-youtube-upload` from `8244cfa`. Latest migration is V71.
  - `ObjectStorageApi` has store / delete / signed GET only. There is no multipart or ranged read.
  - `MediaTokenService.validAccessToken` already refreshes under a Redis lock.
  - `MediaAssetLifecycleService` already records system transitions with a `null` actor.
  - `NotificationOutboxRepository` is the established `FOR UPDATE SKIP LOCKED` claim pattern.
  - There is no S3 Testcontainers module (only Postgres + Redis).
- **Approvals recorded:**
  - **B2 / VID-ADR-004 ACCEPTED:** only `UploadTransferWorker` may move video bytes through the
    JVM. It runs off request threads, holds one chunk per transfer, and is capped per tenant and
    globally. A scoped note is added to `.claude/rules/architecture.md`.
  - **Q-007:** one Google Cloud project per environment.
  - **Q-013:** status events + security log.
  - **Q-014:** 20 uploads per day per tenant, configurable.
- **Affected modules:**
  - `integrationmanagement`: staging-storage port + S3 adapter, YouTube upload/status client,
    platform-admin disable (TD-008);
  - `videoaccessmanagement`: upload service, job entity/repo, worker, status sync, quota budget,
    endpoints;
  - `common/api/ApiErrorCodes`;
  - docs: `docs/api/video-access-management.md`, `docs/api/integrations.md`, ADR-018 note, this plan.
- **Proposed changes:**
  - `V72__create_media_upload_job.sql`:
    - `media_upload_job`:
      - `tenant_id`, `video_asset_id`, `provider_connection_id` (composite tenant FKs);
      - `staging_object_key`, `staging_upload_id`;
      - `session_uri_ciphertext` (AES-GCM, tenant AAD, never returned);
      - `bytes_total`, `bytes_sent`, `attempt_count`, `next_attempt_at`, `claimed_at`, `last_error`;
      - status `AWAITING_PARTS / QUEUED / TRANSFERRING / RETRY_WAIT / QUOTA_PAUSED /
        PARKED_RECONNECT / COMPLETED / FAILED / CANCELLED`;
      - partial unique index: one live job per asset;
      - claim index `(status, next_attempt_at)`;
    - `tenant_video_usage_daily (tenant_id, usage_date, uploads, bytes, api_units)`: unique per
      tenant/day, with the day in `America/Los_Angeles` to match the YouTube quota reset;
    - `tenant_video_configuration.daily_upload_limit INT NOT NULL DEFAULT 20` (CHECK 0–1000);
    - the deferred V70 sync index `video_asset (next_sync_at) WHERE status IN ('UPLOADED','PROCESSING')`;
    - TD-008: `media_provider_connection.disabled_by` (`TENANT|PLATFORM`, nullable) + a CHECK.
  - Staging port (`integrationmanagement.api.MediaStagingApi`):
    - `initiate(tenantId, assetId, mime)` → key `video-staging/{tenantId}/{assetId}`;
    - `presignParts(key, uploadId, partNumbers, ttl)`; `complete(parts)`; `abort`;
    - `head` (size); `readRange(key, from, to)` → bounded stream.
    - Two adapters, chosen by the same conditional-bean rule as `ObjectStorageApi`:
      `S3MediaStagingApi` and a fail-closed `Unavailable…`.
    - Tests use an in-memory fake bean, because there is no S3 container.
  - YouTube client (`integrationmanagement.api.VideoProviderUploadApi`, implemented by
    `YouTubeUploadClient`):
    - `startResumableSession(connectionId, metadata, size, mime)`;
    - `uploadChunk(sessionUri, offset, bytes, total)` → `Progress | Complete(videoId)`;
    - `queryProgress(sessionUri, total)`; `cancel(sessionUri)`; `fetchStatus(connectionId, videoIds)`.
    - Tokens come from `MediaTokenService` (one refresh on 401). `invalid_grant` marks the
      connection NEEDS_RECONNECT.
    - Errors map to a typed `UploadFailure` (`RETRYABLE / QUOTA / AUTH / SESSION_GONE / FATAL`).
      The host allow-list is unchanged (`www.googleapis.com`).
  - `MediaUploadService` + `MediaController` additions (§28):
    - `POST /api/v1/media`, `POST /{id}/upload/parts`, `/upload/complete`, `/upload/cancel`,
      `/upload/retry`.
    - Gates, in order:
      1. authenticated (Teacher/TA for their own asset, otherwise `MATERIALS` CREATE_EDIT);
      2. platform flag `video.features.youtube-upload`;
      3. tenant `uploads_enabled`;
      4. ACTIVE tenant YouTube connection that is not platform-disabled;
      5. `mime` in the allow-list, `sizeBytes` ≤ configured max (default 10 GiB; YouTube's cap is
         256 GB);
      6. daily budget: reserved at create, released on cancel or reject.
    - `complete`: S3 complete → HEAD size equals declared → ranged GET of the first 64 KiB →
      `VideoContentSniffer`. Reject ⇒ delete the staging object, asset FAILED. Accept ⇒ asset
      CREATED → UPLOADING and job QUEUED.
  - `UploadTransferWorker` (`@Scheduled`, `SKIP LOCKED` claim in a short TX, transfer with no TX
    open, short TX per progress write):
    - sets `TenantContext` from the claimed row;
    - 8 MiB chunks; resumes from `bytes_sent` or a `bytes */N` query;
    - 5xx/IO ⇒ RETRY_WAIT with exponential backoff + jitter (max 8 attempts, then FAILED);
    - quota ⇒ QUOTA_PAUSED until the next Pacific midnight;
    - auth ⇒ PARKED_RECONNECT, which resumes after the tenant reconnects the same channel;
    - session 404/410 ⇒ re-init the session (bytes_sent = 0);
    - success ⇒ store `provider_asset_id`, asset UPLOADED → PROCESSING, delete the staging
      object, schedule the first sync.
    - Concurrency caps: `video.upload.max-concurrent-transfers` (default 2) and 1 per tenant.
    - A stale-claim sweeper releases `claimed_at` older than 15 min.
  - `MediaStatusSyncJob`: batches of up to 50 `videos.list` ids per connection (`status` +
    `processingDetails`); processed ⇒ READY (+ duration and thumbnail), rejected/failed ⇒ FAILED
    with the provider reason, otherwise backoff on `next_sync_at` (1m → 30m, give up after
    24 h ⇒ FAILED).
  - `MediaStagingSweeper`: aborts or deletes staging older than 48 h for CANCELLED/FAILED/abandoned
    jobs. A devops lifecycle rule is the backstop (documented, not deployed).
  - TD-008: `POST /api/v1/platform-admin/tenants/{tenantId}/video-providers/{accountId}/disable|enable`.
    PLATFORM-disabled can only be re-enabled by a Platform Admin. Audited. Uploads are refused and
    live jobs parked while disabled.
  - Error codes: `VIDEO_PROVIDER_NOT_CONNECTED`, `VIDEO_PROVIDER_AUTH_EXPIRED`, `MEDIA_UPLOAD_FAILED`,
    `MEDIA_NOT_FOUND`, `UPLOAD_QUOTA_EXCEEDED` (429), `VIDEO_UPLOADS_DISABLED`.
- **DB impact:** V72 only.
  - Two new tenant-owned tables with `NOT NULL tenant_id`, leading-`tenant_id` indexes and
    composite FKs.
  - One config column with a default.
  - One partial index.
  - One nullable column + CHECK.
  - No data rewrite.
- **API impact:** additive only (B6): the media upload endpoints and the platform-admin
  disable/enable. `/api/v1/videos/*` and the Phase 3 endpoints are unchanged.
- **Frontend impact:** none (Phase 5).
- **Tenant impact:**
  - Tenant comes from auth context on requests, and from the claimed row in jobs (never a payload).
  - Staging keys are tenant-prefixed and never accepted from the client; part URLs are generated
    server-side for the server-held key only.
  - Each upload uses only its own tenant's connection token (T7).
  - One tenant's quota or `invalid_grant` never pauses another tenant's jobs (T13).
  - The budget is per tenant.
- **Security impact:**
  - Server-side MIME sniff + size check before any byte leaves for YouTube.
  - Presigned part URLs: short TTL (15 min), PUT only, one key.
  - The resumable `session_uri` is encrypted and never returned or logged.
  - Video bytes never touch request threads.
  - Bounded memory (one chunk per transfer).
  - Platform kill switch (TD-008).
  - Security log for every rejection or park.
  - Cancel deletes both the YouTube session and the staging object.
- **Tests:**
  - `FakeYouTubeServer` (resumable init / 308 Range / chunk PUT / status query / videos.list,
    scripted 5xx / quota / 401 / invalid_grant / 404 session, records the bearer).
  - In-memory `MediaStagingApi` fake.
  - Unit: chunk/range math, backoff, Pacific-midnight calculation, job state machine.
  - Integration:
    - happy path through to READY;
    - 308 resume after IO failure;
    - 5xx retries then FAILED;
    - quota ⇒ QUOTA_PAUSED and other tenant unaffected (T13);
    - 401 refresh once;
    - invalid_grant ⇒ parked + connection NEEDS_RECONNECT;
    - session 404 re-init;
    - sniff reject;
    - size mismatch;
    - cancel / retry;
    - budget 429 + release on cancel;
    - flag/tenant switch off ⇒ refused;
    - no connection ⇒ 409;
    - platform-disable blocks + parks, tenant cannot re-enable;
    - Student ⇒ 403;
    - Teacher cannot complete another teacher's upload;
    - T1–T4 on upload endpoints, T7 (fake asserts tenant A's bearer), T12 (injected `tenantId`
      ignored);
    - status sync READY / FAILED / backoff;
    - schema test for V72 tenant FKs and indexes.
  - Existing suites stay green.
- **Deferred:**
  - upload UI, edit/delete and the Media Library screens → Phase 5;
  - deleting on YouTube (Q-017) → Phase 5;
  - per-tenant API-unit accounting beyond counting uploads is recorded but not enforced;
  - Google quota extension request and OAuth app verification → owner/devops;
  - S3 staging bucket lifecycle/CORS rules → devops (documented in `docs/api` + deployment notes).

**After (2026-10-04, status → TESTED; full backend suite green, commit pending)**

- **Implemented as planned**:
  - V72;
  - staging port + S3 adapter;
  - YouTube resumable client (sealed session handle, origin-checked session URI, one refresh
    on 401);
  - upload endpoints;
  - transfer worker on its own executor (2 per instance, 1 per tenant, one 8 MiB buffer);
  - status sync;
  - maintenance (resume parked, expire, abandon, staging sweep);
  - per-tenant daily budget;
  - TD-008 Platform Admin switch-off.
- **Technical decisions:**
  - every job state change takes a `FOR UPDATE` row lock, so cancel and worker never
    overwrite each other; the worker stops at its next progress write after a cancel;
  - a retry is a new job row that takes over the staged file and holds no budget slot;
  - the budget slot is returned only if no YouTube session was ever opened;
  - status sync postpones (never fails) when the channel can't be asked; ~26 h of
    "processing" → FAILED;
  - a retry or a resumed parked job continues only on the **same** channel (a replaced
    channel → 409 / stays parked, then fails after 7 days);
  - timers off in tests (`video.upload.scheduler-enabled=false`); tests drive
    `UploadTransferWorker.drain`.
- **Security review (inline):**
  - server-side size + magic-byte check before any byte goes to YouTube; reject deletes the
    staged file;
  - session URI sealed (tenant AAD), never returned or logged, must match the Google API
    origin (SSRF test);
  - presigned URLs 15 min, server-generated tenant-prefixed keys, also enforced by a V72
    CHECK;
  - no video bytes on request threads;
  - security log for rejections, parks, platform switch-off;
  - audit for platform disable/enable.
- **Tenant-isolation review:**
  - T1–T4, T7 (fake asserts the tenant's own bearer), T12, T13 (quota pause isolated) tested;
  - cross-tenant poller queries return only ids + the row's own tenant, which is then set as
    the tenant context;
  - repository methods taking a tenant id are called only by default methods that read it
    from the context;
  - composite tenant FKs on the new tables (schema test).
- **Deviations:** see the Plan vs Implementation table (Phase 4 rows).
- **Known issues:**
  - the retry same-channel guard was added after the full run; `MediaUploadIntegrationTest` re-run 20/20;
  - pre-existing: `MediaConnectionStore.markNeedsReconnect` and other request-thread logs
    add `tenantId` that the MDC already holds, so logback drops those lines ("The name
    'tenantId' has already been written") — not introduced here; the new Phase 4
    request-thread logs avoid it.
- **Production prerequisites (not code):**
  - S3 staging lifecycle rule + CORS (`docs/api/video-access-management.md`);
  - Google OAuth verification of `youtube.upload` and the quota extension;
  - `VIDEO_YOUTUBE_UPLOAD_ENABLED` stays `false` until then.
- **Next:** Phase 5 (Media Library API completion + upload UI).

### Phase 5 — Media Library

**Before (2026-10-04, status → PLANNING; owner decisions Q-010, Q-011, Q-017, edit sync)**

- **Objective:**
  - complete the Media Library API (edit, delete, upload progress);
  - build the Tenant Admin and Teacher Media Library UI: list/grid, upload panel (browser →
    S3 multipart), detail, edit, delete, status, every loading/empty/error/denied/reconnect
    state.
- **Owner decisions:**
  - Q-010: teachers see their own uploads (Phase 6 adds media attached to their courses);
  - Q-011: `MATERIALS` VIEW / CREATE_EDIT / DELETE;
  - Q-017: delete = LMS soft delete only, never on YouTube;
  - edits to title / description / privacy **sync to YouTube**.
- **Needs approval - ADR-018 Amendment 3 (B7):** YouTube has no edit-only scope. Editing
  needs `youtube.force-ssl`, which also permits delete (we never call it).
  - Tenant channel scopes become `youtube.upload` + `youtube.readonly` + `youtube.force-ssl`.
  - Existing tenant channels keep uploading. Editing a YouTube-hosted asset on a channel
    without the scope → `409 VIDEO_PROVIDER_SCOPE_MISSING` ("Reconnect YouTube to edit videos").
  - The provider card shows "Reconnect to enable editing".
- **Backend:**
  - `PATCH /api/v1/media/{id}` `{title?, description?, privacyStatus?}`:
    - allowed in CREATED (LMS only - the worker sends it at session start) and in
      PROCESSING / READY / FAILED-with-provider-id (YouTube `videos.update`
      snippet+status, then the LMS row);
    - UPLOADING / UPLOADED → `409` (the session is already open);
    - PUBLIC still refused;
    - provider call with no transaction open;
    - audited `media_asset.updated` (before/after).
  - `DELETE /api/v1/media/{id}`:
    - soft delete;
    - `409 MEDIA_ATTACHED` while a material references it (`MaterialLookupApi`);
    - `409` while an upload is live (cancel first);
    - security.md requires deletions to be audited: `media_asset.deleted`;
    - status event → DELETED;
    - the video stays on YouTube.
  - `MediaAssetResponse` gains `uploadProgress {state, bytesSent, totalBytes}` (latest job,
    batch-loaded for lists) and `attached` (boolean).
  - `VideoProviderUploadApi.updateMetadata` (scope-checked, AUTH/RETRYABLE mapping).
  - `GET /api/v1/media/upload-limits` → `{dailyLimit, usedToday, maxFileSizeBytes,
    partSizeBytes, uploadsEnabled, channel: NOT_CONNECTED|CONNECTED|EXPIRED|DISABLED}` for
    the upload panel.
  - Daily limit stays non-editable (platform operations).
- **DB impact:** none (V72 has everything).
- **Frontend:**
  - `lib/api/media.ts`;
  - `lib/api/upload-multipart.ts` (XHR PUT per part, progress, 3 retries per part, re-sign
    expired URLs, cancel, `beforeunload` guard);
  - `components/media/{media-library,media-card,media-status-badge,media-upload-panel,media-detail,media-edit-form}.tsx`;
  - pages `(tenant-admin)/tenant-admin/media[/[mediaId]]` and `(teacher)/teacher/media[/[mediaId]]`;
  - nav links;
  - permission helpers;
  - Playwright `e2e/media-library.spec.ts` (mocked API + presigned PUTs).
- **Tenant impact:** unchanged model. New endpoints are tenant-filtered; cross-tenant
  PATCH/DELETE/upload-limits → `404` / own data (T3, T4 tests).
- **Security:**
  - the broader Google scope is documented as a residual risk (we never delete);
  - PATCH and DELETE are re-authorized server-side;
  - audit on delete/edit;
  - no staging key, session or connection id in any response.
- **Tests:**
  - backend integration: edit LMS-only / synced / scope-missing / wrong-state, delete
    (attached, live upload, soft-deleted hidden, audit), progress fields, upload-limits,
    teacher-vs-teacher and cross-tenant 404s, Student 403, scope check on the callback;
  - Playwright for each screen state.
- **Deferred:**
  - lesson attach (Phase 6);
  - media from teachers' courses (Phase 6);
  - YouTube delete (declined);
  - editable daily limit.

**Backend step (2026-10-04, owner approved ADR-018 Amendment 3 / B7)**

- **Implemented as planned:**
  - tenant channel scopes + `force-ssl`, `canEditVideos`, `editScopeGranted`;
  - `VideoProviderUploadApi.updateMetadata` (reads snippet+status, changes only title /
    description / privacy, keeps other YouTube settings);
  - `PATCH` / `DELETE /api/v1/media/{id}`;
  - `GET /api/v1/media/upload-limits`;
  - `uploadProgress` + `attached` on reads (batch-loaded for lists);
  - `MaterialLookupApi.findVideoAssetIdsInUse`;
  - error codes `VIDEO_PROVIDER_SCOPE_MISSING`, `MEDIA_ATTACHED`;
  - audit `media_asset.updated` / `media_asset.deleted`.
- **Tests:**
  - new `MediaLibraryManagementIntegrationTest` 12/12;
  - upload tests refactored onto the shared `MediaUploadTestSupport` (`MediaUploadIntegrationTest` 20/20);
  - unchanged neighbours green: `TenantVideoProviderIntegrationTest` 13, `MediaLibraryIntegrationTest` 10,
    `VideoPlaybackSessionIntegrationTest` 18, `TeacherMediaConnectionIntegrationTest` 16, unit suites;
  - full `mvnw verify` to run after the frontend step.
- **Deviation:** delete of media whose upload waits for its parts is refused (cancel first) rather than
  cancelling implicitly - one explicit action per outcome.

**Frontend step (2026-10-04, owner: "Yes go ahead with frontend development.")**

- **Implemented:**
  - `lib/api/media.ts` (React Query hooks, polling while uploads / processing are in flight);
  - `lib/api/upload-multipart.ts` (XHR PUT per part straight to the presigned URL - no
    credentials - 3 parts at a time, 3 retries with backoff, re-signs expiring / 403 URLs,
    abort);
  - `lib/validation/media.ts` (Zod; React Hook Form in both forms);
  - `lib/media-format.ts`;
  - `components/media/{media-library,media-upload-panel,media-detail,media-status-badge}.tsx`;
  - pages `(tenant-admin)/tenant-admin/media[/[mediaId]]`, `(teacher)/teacher/media[/[mediaId]]`;
  - nav links (Tenant Admin: `MATERIALS` VIEW roles; Teacher: always);
  - permission helpers `canViewMedia` / `canManageMedia` (UX only);
  - the YouTube card shows the platform switch-off and "reconnect to enable editing".
- **States:**
  - every page has loading, empty (true-empty vs filtered, with an action), error and
    permission-denied (server 403);
  - the upload panel adds a blocked reason from the server limits (channel not connected /
    expired / switched off / daily limit / uploads off);
  - the detail page explains queued / sending / retrying / paused-for-quota /
    waiting-for-reconnect / processing / failed;
  - delete is hidden while a lesson uses the media or an upload is live; the delete dialog
    says the YouTube copy stays.
- **API mismatch found and fixed (reported):** `DELETE /api/v1/media/{id}` returned a bare
  `204`, but the codebase convention (and `apiFetch`) is `200` with `ApiResponse.success(null)`.
  The endpoint is new in this uncommitted phase, so the backend was aligned. Doc and test updated.
- **Tests:**
  - Playwright `e2e/media-library.spec.ts` 15/15 (mock-only, incl. presigned PUTs with CORS
    preflight, no Authorization header on part uploads, exact start/complete/PATCH bodies);
  - Phase 3 `tenant-admin-video-settings.spec.ts` 11/11 (fixture updated with the new
    account fields);
  - `tsc --noEmit` clean;
  - `eslint src` 0 errors (2 existing warnings in untouched files).
- **Production prerequisite (unchanged):** the staging bucket's CORS must allow `PUT` from
  tenant origins and expose `ETag`, or part uploads fail with a clear message.

- **Full backend `mvnw verify` (2026-10-04, after the DELETE alignment): 2351 tests, 0 failures/errors/skips, BUILD SUCCESS.** Phase 5 status → TESTED, commit pending.

### Phase 5b — Institute channel: allowed teachers, teacher YouTube removal

**Before (2026-10-05, status → PLANNING; owner decisions after reviewing Klass's Integrations UI)**

- **Owner decisions:**
  - keep ONE institute YouTube channel;
  - add an optional name / description and **allowed teachers** to its card ("Klass-style",
    connect still goes straight to Google);
  - no teachers selected = **all teachers** may upload;
  - **remove the teacher-level YouTube connection** (needs ADR-018 Amendment 4 = B8).
- **Clarified:** Google OAuth is set up once per platform environment by the operator, not per
  tenant (one OAuth client, app verification for the sensitive scopes, quota extension).
  Klass does the same.
- **Needs approval - ADR-018 Amendment 4 (B8):**
  - YouTube stops being a teacher-level provider. Teachers no longer connect a personal
    YouTube account; the institute channel is the only YouTube connection.
  - Live teacher YouTube connections are disconnected by migration (status DISCONNECTED /
    SYSTEM, credentials wiped, rows kept as history). They can't be revoked at Google from a
    migration, so the read-only grant stays on the teacher's own Google account until they
    remove it. Nothing used these grants (no consumer exists).
  - Tenant "teacher providers" toggles lose `youtubeEnabled`: still accepted in `PUT` and
    ignored (no breaking change); dropped from the view.
- **Backend:**
  - `V73__institute_video_channel_teachers.sql`:
    - `tenant_video_configuration` += `channel_display_name VARCHAR(100)`,
      `channel_description VARCHAR(500)`;
    - new `tenant_video_upload_teacher (tenant_id, teacher_user_id)` (composite tenant FKs to
      `tenant_user`, PK on both);
    - disconnect live TEACHER-scope YouTube connections.
  - `GET` / `PUT /api/v1/tenant-admin/video-providers/youtube/settings`
    `{displayName, description, allowedTeacherIds[]}` (empty = all teachers): `INTEGRATIONS`
    VIEW / CREATE_EDIT; teacher ids must be TEACHER / TEACHER_ASSISTANT users of the same
    tenant (else 400); audited `video_provider.settings_updated` with before/after.
  - The provider account view gains `displayName`, `description`, `allowedTeacherIds`,
    `allTeachersAllowed`.
  - Enforcement: `MediaUploadService.create` / `retry` - a Teacher / TA not on a non-empty
    list → `403 VIDEO_UPLOAD_NOT_ALLOWED`. Editing / deleting / cancelling their own existing
    media stays allowed. Staff with `MATERIALS` are unaffected.
  - `upload-limits` += `canUpload` + `notAllowedReason` for the caller.
  - Teacher YouTube: OAuth begin / confirm for YOUTUBE + TEACHER refused (404, same as an
    unknown provider); removed from the teacher overview and the teacher-provider toggles.
- **Frontend:**
  - Video providers card: "Name" / "Description" and "Who can upload: All teachers / Selected
    teachers" (checkbox list of the institute's teachers), saved separately from Connect;
  - card subtitle "YouTube · All teachers / N teachers" like Klass;
  - the teacher Media Library shows "You haven't been given access to upload…" from the
    server;
  - the teacher Settings → Integrations page drops YouTube;
  - the admin teacher-provider toggles drop YouTube;
  - RHF + Zod; Playwright.
- **Tenant / security:**
  - teacher ids are validated against the caller's tenant (composite FK backstop);
  - the allow-list is checked server-side on every create / retry;
  - cross-tenant tests: B can't read or set A's list; a teacher id from another tenant → 400.
- **Tests:** settings CRUD + validation + audit; enforcement (listed / unlisted / all);
  existing-media edit by a de-listed teacher still works; teacher YouTube connect refused;
  migration disconnects teacher YouTube rows; cross-tenant; Playwright for the card, the
  teacher-denied state and the teacher integrations page.

**After — backend (2026-10-05, ADR-018 Amendment 4 approved "Yes Approved")**

- Built as planned, with these differences:
  - the settings live on their own endpoint/view (`ChannelSettingsView`), not on the provider
    account view;
  - the view also returns `availableTeachers` (every teacher of the institute by **user id**):
    `/api/v1/teachers` returns teacher-*profile* ids, which the allow-list does not accept, so
    the form takes its choices from here (found while building the frontend);
  - allowed ids are validated against `TeacherLookupApi.listTeachers()` (the tenant's teacher
    profiles); a student / another tenant's teacher / unknown id → 400.
- Files: `V73__institute_video_channel_teachers.sql`; `TenantVideoUploadTeacher` +
  repository; `TenantVideoChannelService`; `TenantVideoChannelController`;
  `TenantVideoConfiguration` (+ name / description); `MediaUploadService` (allow-list on
  create / retry, `canUpload` / `notAllowedReason`); `MediaUploadException.notAllowed()`;
  `ApiErrorCodes.VIDEO_UPLOAD_NOT_ALLOWED`; `MediaProvider.YOUTUBE` no longer teacher-scoped;
  `MediaConnectionService` / `MediaConnectionViews` (teacher providers = Zoom, Vimeo).
- Tests: new `InstituteChannelTeachersIntegrationTest` (6, incl. cross-tenant and V73 checks);
  `TeacherMediaConnectionIntegrationTest` and `TenantVideoProviderIntegrationTest` reworked
  to Vimeo + "YouTube begin → 404". Video/integration suites: 80 tests across 7 classes, green.

**After — frontend (2026-10-05)**

- `video-providers.ts`: `ChannelSettingsView`, `useVideoChannelSettings`,
  `useUpdateVideoChannelSettings`.
- New `tenant-youtube-channel-settings.tsx` (RHF + Zod `validation/video-channel.ts`): Name,
  Description, "Who can upload: All teachers / Selected teachers only" with a checkbox list;
  server field errors mapped onto the form; disabled with no Save button for read-only roles.
- `tenant-youtube-channel-card.tsx`: subtitle "<name> — YouTube · All teachers / N teachers".
- `media.ts` / `media-upload-panel.tsx`: `canUpload` / `notAllowedReason` block the upload form
  with the server's reason.
- Teacher YouTube removed from `integrations.ts`, the teacher provider card, the admin
  teacher-provider toggles and the e2e fixtures.
- Playwright: 4 new channel-settings tests (exact PUT body with user ids, validation + back to
  all teachers, server field error, read-only auditor), 1 new teacher-not-allowed upload test,
  teacher integrations page asserts no YouTube card and uses Vimeo for the denied case.
  `tenant-admin-video-settings`, `media-library`, `teacher-integrations`,
  `tenant-admin-integrations`: 65 passed, 2 failed — the 2 are **pre-existing** (they fail
  identically on `HEAD` with Phase 5b stashed): `teacher-integrations` "confirms a finished
  connection exactly once…" and "an expired or someone else's connection request…" stay on
  "Finishing connecting…" (the confirm mutation's result is never shown). Logged as TD, not
  part of this phase.
- `tsc --noEmit` and `eslint` clean.
- Full backend `mvnw verify` (2026-10-05): 2357 tests, 0 failures / errors / skipped, BUILD SUCCESS.

### Phase 5c — Platform Admin: Google OAuth app screen

**Before (2026-10-05, status → IN PROGRESS; Phase 5b committed as `c66684f`)**

- **Owner decisions (ADR-018 Amendment 5):** a Platform Admin screen for the platform's single
  Google OAuth client instead of env vars only (env vars stay the fallback); several channels
  per institute follow in Phase 5d.
- **Goal:** the Platform Admin enters the Google Client ID / Secret, sees the redirect URI to
  register in Google Cloud, and can Verify the credentials with Google; tenants keep just
  clicking Connect.
- **Backend:**
  - `V74__platform_oauth_app.sql`: `platform_oauth_app` (provider unique, client_id,
    client_secret_ciphertext, updated_at / updated_by → `platform_admin_user`);
    `platform_oauth_app_change` (append-only history: action SAVED / REMOVED, masked client id
    before / after, secret_changed, actor, occurred_at) with a trigger rejecting UPDATE / DELETE.
  - `GoogleOAuthAppService` (integrationmanagement): `current()` resolves saved row → env vars;
    `view()`, `save(clientId, clientSecret)` (both always required - the secret is never read
    back, so it is re-entered on every save), `remove()`, `verify()`.
  - `YouTubeOAuthClient` reads credentials per call through the resolver (`isConfigured()`
    too, so "YouTube unavailable" flips without a restart).
  - Verify: a token request with a dummy authorization code - `invalid_grant` = the client is
    valid, `invalid_client` / 401 = wrong id or secret. New `ProviderHttpClient.probeForm`
    returning only the HTTP status and the OAuth `error` code.
  - `GET` / `PUT` / `DELETE /api/v1/platform-admin/oauth-apps/google`, `POST …/verify`;
    `hasRole('PLATFORM_ADMIN')`.
- **Frontend:** Platform Admin → "Google (YouTube) app" page: source (saved / environment /
  not configured), masked client id, redirect URI with copy, form (RHF + Zod; secret field
  write-only), warning that changing the client id forces every institute to reconnect,
  Verify, Remove saved credentials.
- **Security / tenant:** platform-only endpoints (tenant tokens → 403); secret never in any
  response, log, history row or metric; ciphertext bound to the provider (AAD); the
  encryption key missing → 503, nothing stored. No tenant data involved.
- **Tests:** role checks (tenant admin / teacher / anonymous refused); save → view masked, no
  secret anywhere; history rows + trigger; saved row overrides env, removal falls back to env;
  tenant YouTube connect uses the saved client id; Verify valid / invalid / unreachable
  (fake Google); Playwright for the page.

**After — backend (2026-10-05)**

- Built as planned. Files: `V74__platform_oauth_app.sql`; `PlatformOAuthApp`,
  `PlatformOAuthAppChange`, `PlatformOAuthProvider`; `PlatformOAuthAppRepository` (plain
  `JpaRepository`, platform-level), `PlatformOAuthAppChangeRepository` (save + read only);
  `GoogleClientCredentials` (saved row → env vars, read per call); `GoogleOAuthAppService`;
  `PlatformAdminOAuthAppController`; `YouTubeOAuthClient` (credentials via the resolver +
  `verifyClient`); `ProviderHttpClient.probeForm`.
- Deviation: client ids are shown in full on the screen (Google puts them in every consent URL,
  they are not secret); only the history stores them masked.
- Tests: new `PlatformGoogleOAuthAppIntegrationTest` (7: roles, env view + redirect URI, saved
  app encrypted / never returned / used by an institute's Connect and token exchange, validation,
  removal → env fallback, Verify valid / rejected, history append-only). No secret in any log
  line of the run. Media / YouTube suites re-run green (Teacher 16, TenantAdminIntegrations 7,
  TenantVideoProvider 13, InstituteChannelTeachers 6, MediaUpload 20, MediaLibraryManagement 12).

**After — frontend (2026-10-05)**

- Platform Admin → **Integrations** (new nav entry) → "Google (YouTube) app" card:
  source badge, client id, hidden-secret state, redirect URI with Copy, RHF + Zod form (secret
  is a write-only password field, cleared after save), an explicit "I understand every institute
  must reconnect" acknowledgement before replacing an existing client, Verify with Google,
  Remove saved app (confirm dialog), recent changes; disabled with an explanation when the
  encryption key is missing; shared loading / error / permission-denied states.
- Files: `lib/api/platform-oauth-apps.ts`, `lib/validation/google-oauth-app.ts`,
  `app/(platform-admin)/platform-admin/(dashboard)/integrations/{page,google-oauth-app-card}.tsx`,
  `components/layout/nav/platform-admin-nav.tsx`.
- Playwright `platform-admin-google-oauth-app.spec.ts`: 6 passed. `tsc` and `eslint` clean.
- Docs: `docs/api/integrations.md` (Platform Admin: Google OAuth app),
  `docs/operations/production-secrets-runbook.md`, ADR-018 Amendment 5.
- Full backend `mvnw verify` (2026-10-05): 2364 tests, 0 failures / errors / skipped, BUILD SUCCESS.

**Revised (2026-10-05) — owner: "Even youtube credentials are configured in platform level it should be
against tenant level"** (decisions: Tenant Admin enters it in Video providers; the platform app
stays the fallback). ADR-018 Amendment 5 item 1a.

- Backend: `V75__tenant_oauth_app.sql` (tenant-owned, `NOT NULL tenant_id` FK, unique
  `(tenant_id, provider)`); `TenantOAuthApp` + `TenantOAuthAppRepository` (tenant-aware);
  `GoogleClientCredentials.current(tenantId)` (own → platform → env; reads through the
  tenant-aware repository, setting the context only where none exists - the public callback -
  and refusing a mismatched one); `YouTubeOAuthClient.forTenant(tenantId)` used by
  `OAuthConnectFlowService` (begin + callback exchange, tenant from the server-side state) and
  `MediaTokenService` (refresh, tenant from the connection row); provider view availability per
  institute; `TenantGoogleOAuthAppService` + `TenantGoogleOAuthAppController`
  (`/api/v1/tenant-admin/video-providers/youtube/oauth-app`, audited, marks the live channel
  NEEDS_RECONNECT when the app in use changes). Validation shared with the platform app.
- Fix found by the tests: `AuditLogEntry` rejects null metadata values (`Map.copyOf`) - nulls are
  dropped before recording.
- Tests: new `TenantGoogleOAuthAppIntegrationTest` (6: platform fallback; own app encrypted,
  tenant-bound, audited and used for Connect, the callback exchange and refresh; cross-tenant
  B never sees or uses A's app; changing the app marks the channel for reconnect and removal
  falls back; roles + validation; Verify). No secret in any log line.
- Frontend: "Google app" section on Settings → Video providers (`tenant-google-app-section.tsx`,
  hooks in `video-providers.ts`, shared Zod schema): which app is used, redirect URI, form with
  a reconnect acknowledgement when a channel is connected, Verify, "Remove my Google app";
  read-only for other roles. Playwright: 4 new tests; `tenant-admin-video-settings` +
  `platform-admin-google-oauth-app` 25 passed. `tsc` / `eslint` clean.
- Full backend `mvnw verify` after the revision (2026-10-05): 2370 tests, 0 failures / errors / skipped, BUILD SUCCESS.

**Owner follow-ups (2026-10-05): clearer "uploads off" message; open YouTube videos in a new tab**

- Cause found: locally `VIDEO_YOUTUBE_UPLOAD_ENABLED` was unset (default `false`), but the screen
  said "switched off for your institute". `upload-limits` now returns `uploadsDisabledBy`
  (`PLATFORM` / `INSTITUTE`) + `uploadsDisabledReason`; create returns the matching message.
- Media Library views carry `youtubeUrl` (Media Library endpoints only - see the API doc's
  warning). A card for a video YouTube has opens it on YouTube in a new tab
  (`target=_blank`, `rel=noopener noreferrer`), with a separate "Details" link; other cards open
  the details page as before. The details page has "Open on YouTube".
- The click error reported was the long-running dev server's crashed worker ("Jest worker
  encountered 2 child process exceptions"), which breaks `/media/[id]`; restarting the dev
  server fixes it (same as earlier in Phase 5b).
- Tests: `MediaUploadIntegrationTest` (platform vs institute message + `uploadsDisabledBy`),
  `MediaLibraryManagementIntegrationTest` (`youtubeUrl` absent while uploading, the watch URL
  after) - 20 + 12 green. Playwright `media-library.spec.ts` 19 passed (3 new). `tsc` / `eslint`
  clean.

### Phase 6 — Lesson Integration

**Before (2026-10-06, status → PLANNING; owner selected Q-001 = reuse `Material(VIDEO)`)**

- **Objective:** expose additive LessonMedia-shaped attach/list/update/detach endpoints over the
  existing tenant-scoped `material` association, and extend Teacher Media Library VIEW visibility
  to videos attached to courses they teach.
- **Decision:** VID-ADR-003 accepted; no `lesson_media` table or Flyway migration. Existing
  composite tenant FKs, material ordering/visibility, and enrollment guard remain authoritative.
- **Security/tenant requirements:** lesson ownership comes from `CourseLookupApi`; media details
  come through `VideoAccessApi`; foreign ids return 404; students use the existing entitlement
  path and see visible rows only; attach/detach are audited; deleted/non-READY assets cannot attach.
- **Frontend:** deferred until backend implementation and tests complete, per backend-first workflow.

**Backend implementation (2026-10-06)**

- Added `LessonMediaController` and `LessonMediaService` under content-management with
  `GET/POST/PATCH/DELETE /api/v1/courses/{courseId}/lessons/{lessonId}/media`.
- Added request/response DTOs and `MEDIA_NOT_READY`; attach uses READY-only assets, duplicate and
  sequence checks, and records `lesson_media.attached`; detach removes only the Material row and
  records `lesson_media.detached`.
- Added tenant-scoped `VideoAssetDetails` projections, distinct `attachedLessonCount`, teacher
  attached-course VIEW visibility, and batch course/module/lesson lookup helpers. Deleted assets
  are excluded from both legacy and LessonMedia attachment paths.
- Added `LessonMediaServiceTest`, `LessonMediaIntegrationTest`, and a cross-tenant
  `CourseLookupApiIntegrationTest` case. Maven compile/verify could not be executed in this session:
  shell calls were intercepted by the environment's temporary model-availability gate before Maven
  output was produced; this remains a verification blocker.
- API documentation updated in `docs/api/content-management.md` and
  `docs/api/video-access-management.md`. No frontend files changed.

**Frontend implementation (2026-10-06)**

- Added typed LessonMedia query/mutation hooks to `frontend/src/lib/api/media.ts`.
- Added responsive accessible `MediaPickerSheet` with READY filtering, search, attached-state,
  loading/empty/error/403 states, and server-authoritative attach handling.
- Added `LessonMediaAttachments` for server-backed title/visibility updates and detach confirmation;
  integrated both into the existing course lesson Materials section.
- Added `frontend/e2e/lesson-media.spec.ts` covering attach, READY filtering, empty, permission-denied,
  and detach flows with mocked API responses.
- Frontend typecheck/lint/Playwright could not be executed in this session because shell commands were
  intercepted by the environment's temporary model-availability gate. Existing known failures remain the
  two pre-existing teacher integration confirmation tests documented under Phase 5b.

**Phase 6 status:** implementation complete pending environment verification; no Flyway migration,
payment/enrollment rule, authentication architecture, or player code changed. Next: run backend Maven
verification, frontend typecheck/lint, and the new Playwright spec when shell access is available.

### Phase 7 — Playback Authorization (B3-only WEB scope)

**Before (2026-10-06, status → PLANNING; owner selected B3-only / defer B4)**

- **Objective:** authorize authenticated Student WEB playback from LessonMedia and support WEB
  YouTube Standard descriptors without YouTube stream extraction.
- **Approval:** B3 approved for this WEB scope; B4/ADR-025 native exchange/player work deferred.
- **Constraints:** legacy `/api/v1/videos/*` remains INTERNAL playback; no native launch codes,
  unauthenticated exchange, installation keys, or new native schema.

**Backend implementation (2026-10-06)**

- Added safe `LessonMediaPlaybackContext` and `VideoAssetDetails.providerAssetId/integrationId`
  projections across the existing module API boundaries.
- Added `PlaybackAuthorizationService` and authenticated
  `POST /api/v1/playback/sessions` for Student LessonMedia authorization.
- Added provider-aware WEB descriptor types. YouTube returns only the provider asset ID required
  by the documented WEB player; INTERNAL remains on the legacy signed-object-storage endpoint.
- Added `PLAYBACK_NOT_AUTHORIZED` and `VIDEO_PLAYBACK_DISABLED` error codes; deleted, hidden,
  foreign, not-ready, disabled, and missing-provider-id cases are rejected.
- Added unit coverage in `PlaybackAuthorizationServiceTest` for Student-only access, YouTube
  descriptor safety, disabled playback, hidden materials, and non-READY assets.
- Backend `mvnw.cmd -DskipTests compile` and `test-compile` pass. Focused
  `PlaybackAuthorizationServiceTest`: **5 tests, 0 failures/errors**.
- **Verification (2026-10-07):** new `PlaybackAuthorizationIntegrationTest` 11/11 (real fake-YouTube
  upload -> READY -> LessonMedia; enrolled student descriptor, flag off, not enrolled 404, staff 403,
  anonymous 401, hidden/unknown 404, platform-disabled channel 409, INTERNAL 409; cross-tenant T8 and
  T12). Fixed to match the contract: non-READY -> `409 MEDIA_NOT_READY` (was generic CONFLICT),
  DELETED asset -> 404. Full `mvnw verify`: **2397 tests, 0 failures/errors/skips, BUILD SUCCESS**
  (13:12). The service fix landed after verify compiled, so the playback suites were re-run on the
  final code: 40/40 (`PlaybackAuthorization*`, `VideoPlaybackSessionIntegrationTest`,
  `LessonMediaIntegrationTest`).
- API contract: `docs/api/playback-authorization.md`. Native/B4 work remains deferred.

**Frontend (2026-10-07)**

- `lib/api/playback.ts` (`useCreateWebPlayback`), `components/player/youtube-lesson-player.tsx`
  (authorize on open, `youtube-nocookie.com` embed of a validated 11-char id, no overlay, watermark
  band below, honest "plays through YouTube" copy, fixed anti-enumeration copy for 403/404), and the
  Student lesson materials page picks the player from the LessonMedia `provider` (YouTube → new
  player; INTERNAL / RECORDING → legacy `SecureVideoPlayer`).
- Tests: new `e2e/student-youtube-playback.spec.ts` 8/8 (stable 3x with `secure-video-player`:
  36/36); regression `secure-video-player`, `material-student-*`, `student-course-content` pass.
  `tsc --noEmit` clean; eslint 0 errors (2 pre-existing warnings in untouched files).
- Pre-existing, not caused by this phase (also fail with these changes stashed): `lesson-media.spec.ts`
  "loads ready media, attaches it…" (Phase 6), and the popup tests `material-student-visibility:93` /
  `material-student-types:37` (intermittent).
- Full Playwright suite 2026-10-07: **847 passed, 14 failed, 1 skipped**. None of the failing
  specs use a file changed in Phase 7. Known: `lesson-media:137`, the two `teacher-integrations`
  Zoom confirm tests, the intermittent `material-student-types:37` popup. The other 10 fail the same
  way on a serial re-run (forgot-password disabled form x4, platform-admin audit-log drill-down x2,
  tenant-admin course-edit parity x2, student-profile error state, bank-transfer in-flight) - pre-existing,
  to triage separately.
- Gap fixed (owner approved 2026-10-07): the legacy `POST /api/v1/videos/{id}/playback-sessions`
  now refuses non-INTERNAL (YouTube) assets with `409 CONFLICT` before any session, view count or
  signing (`VideoPlaybackSessionService`). Test
  `PlaybackAuthorizationIntegrationTest#theLegacyVideoEndpointRefusesYouTubeAssetsWithoutCreatingASession`;
  re-run 51/51 (`PlaybackAuthorization*`, `VideoPlaybackSessionIntegrationTest`,
  `MediaLibraryIntegrationTest`, `LessonMediaIntegrationTest`).

**B4 draft (2026-10-07)**

- Owner chose "draft ADR-025 first". `docs/adr/ADR-025-native-secure-player.md` written as
  **PROPOSED** (docs only, no code): Tauri 2 stack exception for `native-player/` only; launch
  codes; installation key pair + pairing (VID-ADR-009); exact-path exchange carve-out with tenant
  from the consumed code row; `aud=player` session-bound playback token; per-tenant
  `native_player_required`; dual-signed, approval-gated releases.
- Recommended sequencing in the ADR: finish Phase 7 WEB unchanged; launch codes + exchange move to
  Phase 8 with pairing (the exchange needs a paired installation key to verify).
- Owner must confirm before acceptance: stack exception, carve-out + player auth model, Q-008,
  Q-009, Windows-only (Q-015), code-signing route and updater-key custody.

### Phase 8 — Player installations, launch codes and exchange (ADR-025)

**Before (2026-10-07, status → PLANNING; branch `feat/video-device-security`; ADR-025 accepted)**

- **Objective:** everything the backend and web app need before the Tauri player exists (Phase 9):
  pairing a player installation to a student's device, one-time launch codes, the exchange, signed
  heartbeat/end with a player-only playback token, per-tenant `native_player_required`, and
  revocation (student, admin, device revoke). Watch-time accounting stays in Phase 12.
- **Findings that shape the design:**
  1. `AccessPolicyService.isCurrentDeviceAuthorized` returns true when no principal is set, so the
     exchange must not call `requireStudentCourseAccess`. New
     `AccessPolicyApi.requireStudentCourseAccessOnDevice(studentId, courseId, deviceId, notFound)` +
     `DeviceAccessApi.isDeviceAuthorized(userId, deviceId, courseMaxDevices)` (device must be ACTIVE).
  2. Device revoke publishes no event. Identity publishes `DeviceRevokedEvent(tenantId, userId,
     deviceId)` inside the revoke transaction; video-access-management listens synchronously and
     revokes that device's installations and their PENDING_LAUNCH / ACTIVE native sessions.
  3. `tenant_video_configuration` already has `native_player_required`, `launch_code_ttl_seconds`,
     `heartbeat_interval_seconds`; `video.features.native-player` exists. No config migration.
  4. Playback JWTs have no audience. Player tokens get `aud=player` + `installation_id`; the legacy
     `/videos/playback-sessions/*` parser rejects any token with an audience.
  5. Installation keys are **ECDSA P-256 (SHA256withECDSA)**, not Ed25519: Windows CNG/TPM
     non-exportable keys support P-256, not Ed25519. JDK-native, no new dependency.
- **DB `V77__player_installations_and_launch_codes.sql`** (new tables tenant-owned, composite FKs,
  `tenant_id`-leading indexes):
  - `player_pairing_code` (user, device, `code_hash` unique, ISSUED/CONSUMED/EXPIRED, TTL ≤ 120 s
    CHECK, consumed installation);
  - `player_installation` (user, device FK, `public_key` SPKI, `key_algorithm` = ES256, platform,
    player version, ACTIVE/REVOKED + revoke fields; one ACTIVE per device - re-pairing revokes the
    previous one; rows never deleted);
  - `playback_launch_code` (session FK, student, `issued_for_device_id`, `code_hash` unique,
    ISSUED/CONSUMED/EXPIRED/REVOKED, TTL ≤ 60 s CHECK, one ISSUED per session);
  - `video_watch_session` + `material_id`, `launch_channel` (WEB|NATIVE, default WEB),
    `player_installation_id`; status + `PENDING_LAUNCH`; revoked reasons + `DEVICE_REVOKED`,
    `INSTALLATION_REVOKED`, `ACCESS_REVOKED`, `ADMIN`, `LAUNCH_EXPIRED` (CHECKs replaced in V77).
- **API (additive, B6):**
  - Student JWT: `POST /api/v1/player/pairings` → `{pairingUrl, expiresAt}`;
    `GET /api/v1/player/installations`; `POST /api/v1/player/installations/{id}/revoke`;
    `POST /api/v1/playback/sessions` adds `channel` (WEB|NATIVE) and `launch{url, expiresAt}`;
    `GET /api/v1/playback/sessions/{id}/status` → `{status, exchanged}` (own session only).
  - Player, **no JWT** (all under `/api/v1/player/`, exact matchers, excluded from
    `TenantResolutionFilter`): `POST /player/pairings/complete` (pairing code + public key; tenant
    from the pairing row), `POST /player/exchange` (tenant from the consumed launch-code row),
    `POST /player/sessions/{id}/heartbeat` and `/end` (player token + signature). Signed requests
    carry `X-Player-Installation`, `X-Player-Timestamp` (±60 s), `X-Player-Nonce` (Redis `SET NX`),
    `X-Player-Signature` over `METHOD\nPATH\nTIMESTAMP\nNONCE\nbase64url(SHA-256(body))`.
  - Tenant Admin (`DomainArea.DEVICES`): `GET /api/v1/tenant-admin/students/{id}/player-installations`,
    `POST .../{installationId}/revoke` (reason), `POST /api/v1/tenant-admin/playback/sessions/{id}/revoke`.
- **Rules:** exchange failures → one generic `400 PLAYBACK_LAUNCH_CODE_INVALID` (detail only in the
  security log); `426 PLAYER_VERSION_UNSUPPORTED` below `app.player.min-version`; heartbeat failures →
  `401 PLAYBACK_SESSION_EXPIRED`; heartbeat re-runs the access check on the installation's device and
  answers `STOP` when access, device, installation or session is gone. If a tenant requires the native
  player but the platform `native-player` flag is off, playback is refused (`409 NATIVE_PLAYER_UNAVAILABLE`),
  never silently downgraded to WEB. Pending launches expire with their code.
- **New secret:** `PLAYER_LAUNCH_CODE_PEPPER` (pairing + launch code HMAC) in the four ADR-019 places.
- **Audit:** `player.paired`, `player.installation_revoked` (student/admin), `video.playback_authorized`
  (channel), `video.playback_session_revoked` (admin). Device revoke keeps its own audit entry.
- **Frontend:** Student "AOC Player" page (pair + installations + revoke); lesson player handles
  `channel=NATIVE` ("Open in AOC Player" from the click, status polling, fallback copy); Tenant Admin
  video configuration toggle for `native_player_required`; student detail lists installations with
  revoke.
- **Tests:** pairing (single use, expiry, cooldown/revoked device refused, re-pair revokes previous),
  exchange (happy, expired, replayed, 10 concurrent → exactly one, wrong installation, other student's
  installation, bad signature, nonce replay, clock skew, revoked installation, lost access, course
  device limit), heartbeat (refresh, STOP on revoke/access lost, token audience separation both ways),
  device revoke cascade, admin revoke, native required + flag off, cross-tenant (code, installation,
  session, admin endpoints), schema (composite FKs, CHECKs). Playwright for every new screen state.

**After — backend (2026-10-07)**

- Built as planned, with these changes found while building (all in ADR-025 Amendment 1 / `docs/api/aoc-player.md`):
  - `V77`: also widens `video_watch_session.status` to VARCHAR(20) (`PENDING_LAUNCH` did not fit V51's 10).
  - The player token travels in `X-Player-Token` (not `Authorization`, so the login filter never parses it).
  - Staff installation endpoints use the student **profile** id like the ADR-022 device endpoints:
    `/api/v1/students/{studentProfileId}/player-installations[/{id}/revoke]`; admin session stop is
    `POST /api/v1/playback/sessions/{id}/revoke`.
  - When `native_player_required`, the NATIVE channel serves INTERNAL media too (signed URL in the
    exchange descriptor, refreshed in each heartbeat) and the legacy `/videos/{id}/playback-sessions`
    refuses Students (`409 NATIVE_PLAYER_REQUIRED`) - otherwise the requirement could be bypassed.
  - `AccessPolicyApi.requireStudentCourseAccessOnDevice` is deliberately not `@Transactional`: the exchange
    and heartbeat catch its denial to revoke the session, and a joined throwing transaction would have been
    marked rollback-only.
  - Exchange per-installation rate limit is keyed by installation + IP (it runs before the signature check).
- New: `LessonMediaPlayability` (one playability rule for WEB authorize, exchange and heartbeat);
  `service/player/{PlayerCodes, PlayerRequestVerifier, PlayerVersion, PlayerRateLimiter, PlayerSessionRevoker,
  NativeLaunchService, PlayerPairingService, PlayerExchangeTransactions, PlayerExchangeService,
  PlayerSessionService}`; domain `PlayerInstallation`, `PlayerPairingCode`, `PlaybackLaunchCode` + enums;
  repositories with the two named `findByCodeHashAcrossTenantsForUpdate` lookups; `PlayerController`,
  `StudentPlayerInstallationController`, `PlayerDtos`; `PlayerProperties` (`app.player.*`);
  identity `DeviceRevokedEvent` + `DeviceAccessApi.findActiveSessionDeviceId / isDeviceAuthorized`;
  exact carve-outs in `SecurityFilterChainConfig` / `TenantResolutionFilter`; `PLAYER_LAUNCH_CODE_PEPPER`
  in application.yml, compose (both lists) and `prod-secrets.reference`. Removed `WebPlaybackSessionResponse`.
- Tests: `PlayerIntegrationTest` 14/14 (happy path; single use + expiry; 10 concurrent exchanges -> exactly
  one; wrong key / other student's player cannot use or burn a code; nonce replay, stale clock, bad token;
  audience separation; device reset cascade; student revoke + re-pair; lost access -> STOP; admin revoke +
  audit + permissions; native required cannot be bypassed or downgraded; old player 426; cross-tenant;
  schema), `PlayerCryptoTest` 4/4, `PlaybackAuthorizationServiceTest` 8/8. Full `mvnw verify`:
  **2419 tests, 0 failures/errors/skips, BUILD SUCCESS** (18:26). The rate-limit key change landed during
  the run; the player suites were re-run after it: 26/26.

**After — frontend (2026-10-07)**

- `lib/api/player.ts` (installations, pairing, launch status with 2 s polling, staff list/revoke);
  `lib/api/playback.ts` now types `PlaybackStart {sessionId, channel, expiresAt, descriptor, launch}`
  (`useStartPlayback`); `lib/player/native-launch.ts` (only `aocplayer://(play|pair)?code=` links; test
  hook `window.__aocLaunch`); `components/player/{native-player-launcher.tsx, playback-errors.ts}`;
  YouTube player hands NATIVE answers to the launcher; `SecureVideoPlayer` gained
  `onNativePlayerRequired` (legacy `409 NATIVE_PLAYER_REQUIRED` -> launcher); new Student page
  `(student)/student/player` + nav link; Tenant Admin Devices tab `player-installations-section.tsx`
  (list + remove with reason); device mutations also refresh the player list. The tenant switch already
  existed (Phase 3 Video configuration).
- Tests: new `e2e/aoc-player.spec.ts` 12/12 (36/36 over 3 repeats); regression `devices-and-access`,
  `student-youtube-playback`, `secure-video-player` pass. `tsc --noEmit` clean, eslint 0 errors.

**Mobile devices (owner decision 2026-10-07, while testing Phase 8)**

- A tenant that requires the AOC Player gets NO browser playback on any device, so its students cannot
  watch lesson videos on phones, tablets or Macs until a player exists there. Option 1 was built: on a
  non-Windows browser the lesson page and the AOC Player page say "This video can only be watched on a
  Windows computer with the AOC Player" (no launch, no pairing), and the Tenant Admin "Require the AOC
  Secure Player app" switch warns about phones/tablets/Macs. The device check is UX only
  (`isAocPlayerSupportedPlatform`, user-agent based); the backend never trusts it. Playwright: phone and
  Mac cases in `e2e/aoc-player.spec.ts` (15 tests, 60/60 over 4 repeats).
- Rejected for now: a per-tenant "allow phones in the browser" switch - the device type is client-claimed,
  so anyone could fake a phone and get the unprotected player.
- **Backlog - mobile AOC Player (owner: "we have to develop a mobile application later"):** Android and
  iOS apps (Tauri 2 mobile or native), after Phase 9 Windows. Needs an ADR-025 amendment (platforms,
  store distribution and signing, `player_installation.platform` CHECK, mobile key storage - Android
  Keystore / iOS Secure Enclave, both P-256), and honest capture copy: Android `FLAG_SECURE` blocks
  capture; iOS can only detect recording. The backend API is already platform-neutral.

### Phase 5d — Named YouTube integrations + Media Library tabs

**Scope decided by the owner on 2026-10-05** (after reviewing Klass's Media Library, where every
named integration is a tab - "Uploads", "AOC YT", "AOC ZOOM 0-3", "AOC Cloud Recording"):

- Several named YouTube integrations per institute (ADR-018 Amendment 5 item 2): a card exists
  before Connect (name, description, allowed teachers), one live connection per card.
- **Media Library tabs:** "Uploads" (institute storage, `provider = INTERNAL`) + one tab per YouTube
  integration, named after its card; a tab lists only the videos stored there; "New media" uploads
  to the selected tab's channel (server checks the channel belongs to the institute and the
  teacher is allowed on it). Teachers see only the tabs they may use.
- Not in 5d: tabs for other providers (e.g. Vidrora - not supported).

**Backlog - Zoom cloud-recording tabs (own phase, after 5d; owner 2026-10-05):** institute-level
Zoom integrations whose cloud recordings appear as Media Library tabs (Klass "AOC ZOOM n").
Needs: a decision on which Zoom accounts appear (institute-level vs teachers' own - today Zoom is
teacher-level and only creates meetings), Zoom's recording-read scope, an ADR-018 amendment, and
the recording-access rules (recordings are protected content).

**Before (2026-10-05, status → IN PROGRESS; Phase 5c committed as `5d90402`)**

- **Owner decisions:** deleting an integration keeps its videos in a read-only **Archived** tab;
  the **Uploads** tab lists every video file stored in the platform's own storage
  (`provider = INTERNAL` video assets - course VIDEO / RECORDING materials point to these);
  Platform Admin switch-off stays **per channel** (the existing per-connection endpoint);
  at most **10** YouTube integrations per institute.
- **DB `V76__tenant_media_integrations.sql`:** `tenant_media_integration` (tenant-owned; name
  unique per tenant among ACTIVE, case-insensitive; ACTIVE / DELETED); its teacher list
  `tenant_media_integration_teacher` (V73's `tenant_video_upload_teacher` copied in, then dropped);
  `media_provider_connection.integration_id` (composite FK, required for YouTube TENANT rows);
  one live connection per integration replaces one per (tenant, provider) for YouTube;
  `video_asset.integration_id` (composite FK) backfilled from the connection; one integration per
  existing channel / 5b settings (name = 5b display name → channel title → "YouTube");
  `tenant_video_configuration.channel_display_name / channel_description` dropped.
- **API:** Tenant Admin list = cards; `POST/PUT/DELETE .../youtube/integrations[/{id}]`;
  `POST .../integrations/{id}/connect`; pending preview / confirm per card; `POST /youtube/connect`
  and 5b `/youtube/settings` removed (never released). Media: `GET /media/channels` (tabs for the
  caller), `GET /media?integrationId=`, `upload-limits?integrationId=`, `POST /media` requires
  `integrationId`. Integration-management owns names / teacher lists; video-access-management
  reads them through `MediaConnectionApi`.
- **Security / tenant:** every card / tab id resolved through the tenant filter (foreign → 404);
  uploads re-check integration + teacher list on create / retry; resume stays on the same
  integration; deleting a card revokes at Google and keeps all rows.
- **Tests:** rework single-channel tests; new: several cards, two channels in one institute,
  per-card teachers, upload to the chosen card, tabs per role, Archived, backfill, limit 10,
  cross-tenant; Playwright for cards, dialogs, tabs and upload target.

**After — backend (2026-10-05)**

- Built as planned: `V76__tenant_media_integrations.sql`; `TenantMediaIntegration`,
  `TenantMediaIntegrationTeacher` + tenant-aware repositories; `TenantMediaIntegrationService`
  (create / update / delete, limit 10, unique names, audit; delete disconnects outside the DB
  transaction, then marks DELETED); `TenantVideoProviderService` / controller per card;
  `MediaProviderConnection.integrationId` + per-integration finders; OAuth state carries the card;
  `MediaConnectionApi` → `listVideoIntegrations` / `findVideoIntegration` /
  `isTeacherAllowedOnIntegration` / `VideoIntegrationSummary`; `MediaUploadService` (create needs
  `integrationId`, retry stays on the asset's card), `MediaUploadMaintenance` (resume only on a
  usable own card), `MediaAssetService` (tabs, `integrationId` / `archived` filters),
  `VideoAsset.integrationId`; `TenantGoogleOAuthAppService` checks / resets every live channel;
  Platform Admin list adds `integrationName`.
- Removed: 5b `TenantVideoChannelService`, `TenantVideoChannelController`,
  `TenantVideoUploadTeacher` (+ repository, table dropped by V76), `POST /youtube/connect`,
  `/youtube/settings`, `InstituteChannelTeachersIntegrationTest`.
- Tests: new `VideoIntegrationsIntegrationTest` (7: card CRUD + validation + name rule + limit 10 +
  audit; two channels and upload to the chosen card; per-card teacher lists and teacher tabs;
  delete → disconnect + revoke + Archived; per-channel platform switch-off; cross-tenant;
  schema constraints). Reworked: `TenantVideoProviderIntegrationTest`,
  `MediaUploadIntegrationTest`, `MediaLibraryManagementIntegrationTest` (per-card helpers).

**After — frontend (2026-10-05)**

- Video providers: "N of M connected", **Add integration** (disabled at 10), Klass-style cards
  ("YouTube · All teachers / N teachers", description, Connect / Reconnect / Test / Disconnect,
  **Edit**, **Delete** with the Archived explanation), `VideoIntegrationSheet` (RHF + Zod, full-screen
  on phones), confirm step names the card; the 5b channel-settings section is gone; the Google app
  section stays.
- Media Library: tabs per YouTube integration ("Not connected" badge), **Uploads** (platform
  storage) and **Archived**; each tab queries only its own videos; "Upload video" uploads to the
  selected tab ("To YouTube · <name>"); the details page reads limits for the video's own card.
- Playwright: `tenant-admin-video-settings` 20 passed (5 new integration tests),
  `media-library` 21 passed (2 new tab tests); `teacher-integrations` / `tenant-admin-integrations` /
  `platform-admin-google-oauth-app` green except the 2 pre-existing teacher confirm failures.
  `tsc` / `eslint` clean.
- Full backend `mvnw verify` (2026-10-05): 2371 tests, 0 failures / errors / skipped, BUILD SUCCESS; media / integration suites re-run green after adding `integrationId` to the media view.

### Phase 9 — AOC Secure Player foundation (`native-player/`, ADR-025)

**Before (2026-10-08, status → IN PROGRESS; branch `feat/aoc-secure-player`; Rust 1.99 installed)**

- **Objective:** the Windows player shell that uses the Phase 8 player API unchanged: pairing, the
  `aocplayer://` launch link, the signed exchange, the session lifecycle (heartbeat, `STOP`, end,
  missed-deadline teardown), the updater foundation and CI; plus a download section on the Student
  "AOC Player" page. Video engines are Phase 10: a claimed session shows a "session active" screen
  (lesson title, watermark text) with no video yet.
- **Owner decisions (2026-10-08):**
  1. Installation key in **Windows CNG**: non-exportable ECDSA P-256, created in the Microsoft
     Platform Crypto Provider (TPM) when available, otherwise the Microsoft Software Key Storage
     Provider. `NCryptSignHash` already returns IEEE P1363 `r‖s`, the contract's format. DPAPI was
     rejected (a software key can be copied by malware running as the user).
  2. Download section reads `NEXT_PUBLIC_AOC_PLAYER_DOWNLOAD_URL`; unset → "not available yet". The
     public `GET /api/v1/player/releases/latest` (ADR-025 §7) waits for signed releases. No backend
     change this phase.
  3. Code signing deferred: dev builds only. The release workflow is wired (manual dispatch,
     protected environment) but cannot publish until the signing route and updater-key custody are
     decided.
- **Design:**
  - Tauri 2 + WebView2; UI is Vite + plain TypeScript (npm), no framework (few screens, small attack
    surface). Plugins: `single-instance` (`deep-link` feature) registered first, `deep-link` (static
    scheme `aocplayer`), `updater` behind the `release` cargo feature (dev builds carry no updater key).
  - Deep links: only `aocplayer://pair?code=<43 base64url>` and `aocplayer://play?code=<43>`; any other
    host, path, parameter or length is rejected before a network call (covers fake command-line links).
  - API origin compiled in per build (`AOC_API_ORIGIN`); `http://` only for loopback in debug builds.
  - State: `%LOCALAPPDATA%\com.aoc.secureplayer\installation.json` = installation id + CNG key name (no secret).
    Re-pairing creates a new key and replaces both.
  - Signing: `METHOD\nPATH\nTIMESTAMP\nNONCE\nbase64url(SHA-256(body))`, 32-byte CSPRNG nonce.
  - Session: exchange → heartbeat every `heartbeatIntervalSeconds`; `STOP`, 401/403 or a missed deadline
    tears down and clears session state; closing the window sends `/end`. The player token stays in
    Rust memory only, never in the WebView or on disk.
  - WebView: devtools off in release, strict CSP, navigation limited to the bundled UI, capability file
    exposing only the player's own commands.
- **Impact:** DB none; API none (Phase 8 contract `docs/api/aoc-player.md`); tenant none on the client
  (tenant comes from server rows); frontend: download section on `(student)/student/player`.
- **Tests:** Rust unit (deep-link parser incl. hostile input, canonical string, signature encoding,
  version, session state machine), Rust integration against a mock HTTP server, a cross-check that
  a player signature verifies with Java `SHA256withECDSA`, a manual run against the local backend
  (pair → Play → exchange → heartbeats → admin revoke → `STOP`), Playwright for the download
  section, `cargo clippy -D warnings`, `cargo fmt --check`.

**After (2026-10-08)**

- Built as planned. Changes found while building:
  - Tauri resolved to 2.11 because edition-2024's resolver honours `rust-version`; raised to
    `rust-version = "1.90"` so the crate (2.12.1) matches `@tauri-apps/api` (the CLI refuses a
    mismatch). `windows` 0.62 (same copy Tauri uses).
  - `reqwest` uses `native-tls` (SChannel, the OS trust store): the default rustls/aws-lc-rs needs
    CMake + NASM on Windows.
  - The TPM provider rejects `NCRYPT_SILENT_FLAG` on `NCryptDeleteKey`; deletes pass 0.
  - App data lives under the bundle identifier: `%LOCALAPPDATA%\com.aoc.secureplayer\` (the
    identifier must not change after the first release).
  - Clock: timestamps use the server time learnt from `Date` headers; a refused exchange that
    reveals > 30 s of skew is retried once (a refused exchange never consumes the code).
  - Machine setup: the VS 2022 install lacked the "MSVC v143 C++ x64/x86 build tools" component
    (only OneCore libs, `LNK1104 msvcrt.lib`); owner installed it. In Git Bash `/usr/bin/link`
    shadows the MSVC linker - build from PowerShell (README).
- `native-player/`: `src-tauri/src/{config, deep_link, signing, keystore/{mod, cng, software(test)},
  api, installation, player (+ tests), lib, main}.rs`, `build.rs` (release builds need an https
  `AOC_API_ORIGIN`; app-manifest limits commands to `get_state`/`end_session`), `tauri.conf.json`
  (strict CSP, `aocplayer` scheme, NSIS per-user), `capabilities/default.json`, UI
  `index.html` + `src/main.ts` + `src/styles.css`, placeholder icon, `README.md`.
  CI `.github/workflows/native-player.yml` (PR checks; release = manual dispatch in the
  `player-release` environment, refuses until signing is configured, uploads an artifact only).
- Frontend: Student "AOC Player" page gains an "Install the AOC Player" card
  (`NEXT_PUBLIC_AOC_PLAYER_DOWNLOAD_URL`, validated https/path in `lib/env.ts`; unset → "isn't
  available yet"; hidden on phones/tablets/Macs). `.env.example`, deployment doc updated.
- Security review (2026-10-08): fixed - updater signing secret scoped to the build step only (was
  job-wide, readable by `npm ci` install scripts); "End"/close no longer waits for an in-flight
  heartbeat; response bodies capped while streaming. Accepted: a same-user process can ask CNG to
  sign with the key but cannot export it (ADR-025 deterrence model). Recommended for the signing
  change: pin release-job actions to commit SHAs.
- Tests: `cargo test` **35/35** incl. the TPM test (deep links incl. hostile input, canonical
  bytes, SPKI, CNG software + TPM keys sign/verify/non-exportable/delete, origin rules, navigation
  allow-list, installation file, and the player lifecycle against a mock server with every
  signature verified: pair/re-pair/failed pair, not paired, exchange → CONTINUE → STOP, 401 →
  EXPIRED, unknown action, missed deadline, student end, replaced session, same link twice, clock
  correction, slow heartbeat, oversized response). Cross-language: a TPM-made signature verified
  with Java `SHA256withECDSAinP1363Format` (tampered message rejected). `cargo clippy -D warnings`
  (dev + `release`), `cargo fmt --check`, `tauri build --debug --no-bundle`, release build refused
  without/with http origin and accepted with https. Frontend: `tsc` clean, eslint clean,
  `e2e/aoc-player.spec.ts` 16 passed (48/48 over 3 repeats) + the configured-installer test passed
  with the variable set.
- Pending: manual run against the local backend (pair → Play → exchange → heartbeats → admin
  revoke → STOP) and the commit.

### Phase 10 — Playback engines (native player)

**Before (2026-10-08, status → IN PROGRESS; branch `feat/video-playback-engines`)**

- **Objective:** the AOC Player plays the lesson video. Phase 9 manual run (2026-10-08, local
  backend): pairing, signed exchange and ~4 min of TPM-signed heartbeats verified; Phase 9 committed
  as `996489c`.
- **Owner decisions (2026-10-08):**
  1. Engines now: **YouTube** (official embed) and **File** (INTERNAL MP4 via the signed URL), plus
     the refusing `FutureProviderEngine` stub. **HLS/DASH engines move to Phase 13**, with the first
     real stream source (nothing emits manifests yet).
  2. YouTube: **plain embed iframe**, documented player parameters only; no `iframe_api` script in
     the privileged player window. Position/state for watch-time is decided in Phase 12.
  3. **Web engine split deferred** (to Phase 13): browser players unchanged; Phase 10 is
     `native-player/` only.
- **Design:** no backend/API change (exchange descriptor: `provider`, `providerVideoId`,
  `signedUrl`, `allowSeeking`, `watermarkText`; heartbeat `mediaUrl` refreshes INTERNAL links).
  Rust validates every media reference before the UI sees it (YouTube id `^[A-Za-z0-9_-]{11}$`;
  file URL https - http only for loopback in debug - on a host in the compiled
  `AOC_MEDIA_ORIGINS` allow-list) and pushes a fresh `mediaUrl` to the File engine, which swaps the
  source keeping position. `allowSeeking=false` → no scrubber + snap-back. Teardown destroys the
  engine and clears `src`. CSP opens `frame-src` to the YouTube embed host and `media-src` to the
  build's media origins only. Watermark stays outside the YouTube player (band below), as on the
  web. Risk checked first: YouTube embed "Error 153" (missing/odd referrer) from
  `http://tauri.localhost`.
- **Owner decision (2026-10-08, during Phase 10): player controls now, adaptive streaming in Phase 13.**
  The File engine gets its own control bar (play/pause, back 10 s, scrubber only when seeking is
  allowed, time, mute + volume, speed 0.75-2x, fullscreen, keyboard shortcuts). INTERNAL video is a
  single progressive MP4, so there is no adaptive bitrate or quality menu until Phase 13 transcodes
  to HLS (hls.js levels: Auto + per-rendition). YouTube keeps its own quality/speed menu (its API no
  longer sets quality). Consequence for **Phase 12**: with speed allowed, watch-time counts video
  time watched (position progress), not wall-clock time.
- Found during the manual run: a STOP with `INSTALLATION_REVOKED`/`DEVICE_REVOKED` now makes the
  player forget its pairing (local record + key), so the next launch says "pair again" instead of the
  generic, deliberately uninformative `PLAYBACK_LAUNCH_CODE_INVALID`.
- **Owner decisions for Phase 11 (2026-10-08, asked during the Phase 10 manual run):**
  - YouTube watermark stays a **band below the YouTube player** (YouTube API terms forbid drawing
    over the player; a breach risks the platform's YouTube API access for every tenant). The moving
    on-video watermark is for platform-hosted video only; content that needs protection should be
    hosted on the platform, not YouTube.
  - Screen-capture blocking (`SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)`, Windows 10 2004+)
    is the **next phase (11)**, started right after Phase 10 is tested and committed, together with
    the moving watermark, the tenant "capture protection required" setting, capability reporting
    and Q-012 (refuse when required and unsupported). Capture blocking in YouTube mode remains part
    of the Q-006 legal review.
- Owner UX changes (2026-10-08): while playing, the window shows only the video, control bar and
  watermark line (no header, title, description or End button); closing the window (✕) ends the
  session (the `end_session` command was removed); the whole frame is shown letterboxed
  (`object-fit: contain`); the window opens centred at 1280×760.
- **Impact:** DB none; API none; tenant none; security: third-party frame + media origins,
  constrained by CSP and Rust-side validation.
- **Tests:** Rust unit (media validation, mediaUrl refresh, teardown view), UI typecheck/build,
  manual run with one YouTube and one INTERNAL lesson.

**After (2026-10-08)**

- Built as planned (YouTube + File engines, refusal stub; HLS/DASH and the web split deferred to
  Phase 13), plus the owner's in-phase changes (controls, video-only window, close = end, centred
  window). No backend, API, DB or frontend change.
- **Rust:** `media.rs` (engine choice + validation: YouTube id regex; file URL origin must equal one
  of `AOC_MEDIA_ORIGINS`, no credentials/fragment, ≤ 4096 chars; YouTube-page check for links
  opened outside); `config::media_origins` (compiled list; debug default local MinIO);
  `player.rs` (`View::Playing` carries `sessionId` + `media`; unplayable media → `/end` +
  `MEDIA_UNAVAILABLE`; heartbeat `mediaUrl` validated and pushed; revoked STOP forgets the pairing);
  `lib.rs` (`on_new_window` → Deny, https YouTube pages to the system browser via `ShellExecuteW`;
  window centred 1280×760; `end_session` command removed - closing the window ends the session);
  `build.rs` (release needs https `AOC_MEDIA_ORIGINS`); `[profile.dev]` keeps debug info for this
  crate only (target/ had reached 12 GB on a nearly full disk).
- **UI:** `src/engines/{types, index, youtube, file, controls}.ts`; `main.ts` keeps one engine per
  session + media type, destroys it on any non-playing view; while playing only the video, control
  bar and watermark line are shown (letterboxed, `object-fit: contain`).
- **CSP:** `frame-src https://www.youtube-nocookie.com`; `media-src 'self'` + local MinIO in the
  base config, replaced in releases by the workflow from `vars.AOC_MEDIA_ORIGINS` (validated as bare
  https origins).
- **Checks done first:** YouTube embed from a `*.localhost` origin in Edge (WebView2's engine) - no
  "Error 153"; Tauri 2.12's `on_navigation` is top-level only (NavigationStarting), so the YouTube
  frame is unaffected, and `on_new_window` exists to refuse pop-ups.
- **Manual run (local backend, owner, 2026-10-08):** INTERNAL lesson plays in the player with the
  control bar; layout fixes found and verified in Edge at the window size (video element covered the
  bar; then video-only full-window layout). A launch refused with `installation_mismatch` turned out
  to be the Phase 9 admin-revoke test's installation - led to the "forget revoked pairing" change.
- **Security review (2026-10-08):** fixed - release `AOC_MEDIA_ORIGINS` could inject CSP directives
  (now strict origin regex); YouTube frame no longer allows picture-in-picture (a PiP window would
  escape Phase 11 capture blocking). Verified: Rust-side validation of every media reference, no
  IPC for the remote YouTube frame (no `remote` capability), sandboxed frame without top
  navigation, pop-ups refused, UI can only read state.
- **Tests:** `cargo test` **45/45** incl. TPM (new: 7 media-validation tests; platform file plays and
  takes fresh URLs while foreign ones never reach the UI; unplayable media ends the session and is
  never shown playing; revoked installation is forgotten and the next launch says NOT_PAIRED).
  `cargo clippy -D warnings` (dev + `release`), `cargo fmt --check`, `npm run build` (tsc + vite),
  `tauri build --debug --no-bundle`.
- **Not verified here:** a YouTube lesson end-to-end in the player (no YouTube video was attached to
  a lesson in the demo data; the embed itself was checked in Edge), and the > 3 min signed-URL
  swap (owner reported "looks right" without that detail) - re-check during Phase 11 testing.

### Phase 11 — Watermark & Capture Protection

**Before (2026-10-08, status → IN PROGRESS; branch `feat/video-content-protection`; branch creation pending environment shell classifier recovery)**

- **Objective:** make the existing tenant watermark/capture settings effective in the native player:
  Windows `WDA_EXCLUDEFROMCAPTURE` capture-affinity deterrence, capability reporting and fail-closed
  behavior for `captureProtectionRequired`, plus a moving watermark for INTERNAL media. YouTube stays
  outside-player only because its watermark cannot be drawn over the cross-origin iframe.
- **Owner decisions carried forward:** Q-012 refuses playback when required protection is unavailable;
  Q-006 legal review remains required before production shipping of capture affinity in YouTube mode.
- **Impact:** no migration is expected; V71 already stores tenant watermark/capture settings. The
  exchange descriptor gains additive watermark/capture policy objects. Tenant identity remains derived
  from the consumed launch-code row and trusted effective settings; the player request cannot supply
  tenant or policy values.
- **Security/test goals:** apply affinity before required media is shown, clear it on every teardown,
  never expose handles/tokens to the WebView, render moving overlays only for INTERNAL media, and add
  native/backend contract tests plus honest deterrence documentation.

**Implementation update (2026-10-08; automated verification complete, acceptance gates pending)**

- **Backend:** `PlaybackDescriptor` now carries additive `watermark` and `captureProtection` policy
  objects. `PlayerExchangeService` resolves trusted tenant settings under the consumed launch-code
  tenant, applies platform gates, forces YouTube placement to `OUTSIDE_PLAYER`, and builds bounded
  attribution from the tenant-scoped identity API (`maskedEmail`, session id and configured timestamp
  fields). No request-controlled tenant/policy values are accepted. V71 remains sufficient; no DB
  migration was added.
- **Native player:** added `capture_protection.rs` with Windows `SetWindowDisplayAffinity`/
  `WDA_EXCLUDEFROMCAPTURE`, a testable controller abstraction, explicit unsupported/failed statuses,
  and `WDA_NONE` teardown. Required protection ends the exchanged session before `View::Playing` if
  affinity is unavailable. `View::Playing` reports sanitized watermark policy and capture status.
  INTERNAL media gets a bounded moving stage watermark; YouTube retains only the outside-player band.
  The remote iframe receives no IPC or overlay access.
- **Frontend/UX:** capture-required copy remains honest about deterrence and external capture methods;
  the native UI shows a generic refusal message without exposing OS handles or policy secrets.
- **Tests added/updated:** native lifecycle tests cover required-capture fail-closed behavior,
  successful applied-capture reporting, teardown clearing, policy/view serialization, watermark bounds
  and old descriptor defaults; backend player integration assertions cover trusted attribution,
  YouTube placement, masked-phone attribution, effective capture policy and cross-tenant behavior.
  `native-player` TypeScript/Vite build, Rust formatting, normal/release clippy and Rust tests pass;
  final focused backend player verification passes (15 tests), and definitive full backend verification
  passes 2,420 tests with 0 failures/errors/skips (`BUILD SUCCESS`). Manual Windows affinity verification
  and Q-006 legal review remain pending.
- **Security review:** no new migration, credential, endpoint, or WebView command. Capture is explicitly
  deterrence, not DRM; server entitlement and tenant context remain authoritative. Work is on
  `feat/video-content-protection`; no commit, push, production enablement or release has been performed.

### Phase 12 — Watch-Time & Heartbeat

**Before (2026-10-09, status → IN PROGRESS; branch `feat/video-watch-time`)**

- **Objective:** replace client-authoritative heartbeat deltas with one server-authoritative accounting
  rule shared by WEB and NATIVE: charge only bounded server elapsed time while `PLAYING`, ignore pauses
  and rewinds for authorization, snapshot the tenant/student/video budget, return resume/remaining data,
  and terminate exhausted or stale sessions without deleting history.
- **Impact:** additive migration `V79__watch_time_accounting.sql` extends `video_watch_progress` with
  authorized counters, resume position and budget snapshot, and extends `video_watch_session` with
  heartbeat state/position. Existing endpoint paths remain stable; WEB progress and AOC heartbeat bodies
  gain bounded state and responses gain server-derived resume/remaining fields. Tenant identity remains
  trusted-context/session-derived; Redis remains non-authoritative.
- **Backend implementation:** `WatchTimeService` centralizes budget calculation and wall-clock accounting;
  row locking and tenant guards protect progress updates. `VideoWatchSessionCleanupService` expires stale
  sessions and publishes the existing audit event path. Exhaustion uses `PLAYBACK_WATCH_LIMIT` for WEB and
  `WATCH_TIME_EXHAUSTED` for the native `STOP` response. Existing token, device, enrollment/playability,
  and anti-enumeration checks remain in front of accounting.
- **Tests:** `WatchTimeServiceTest` covers pause, server elapsed accounting, position-ahead capping and
  budget exhaustion. Backend compile/test-compile pass; targeted unit tests pass (4/4). Testcontainers
  integration suites were attempted but are blocked in this environment because Docker is unavailable;
  no integration assertions ran. Frontend/native-player contract consumers and E2E remain the next phase
  step; no commit, push, deployment or production enablement has been performed.
- **Native player + "pair again" (2026-10-09, during the Phase 12 manual run):**
  - Found: the player still sent `positionSeconds: 0, state: IDLE`, so NATIVE playback was never
    charged. Now the File and YouTube engines report position/state to Rust (`report_playback`,
    live session only) and each heartbeat carries the latest report (`PLAYING` if playback ran since
    the previous beat). YouTube state comes from the embed's `enablejsapi` messages (origin + source
    checked); no YouTube script runs in the player window.
  - Found: a device removed while the player was closed revoked its pairing, and the next launch
    failed with the generic `PLAYBACK_LAUNCH_CODE_INVALID` ("Couldn't continue"). `POST
    /playback/sessions` (NATIVE) now answers `409 PLAYER_NOT_PAIRED` when the Student has no ACTIVE
    installation on the browser's device; the LMS shows "Pair the AOC Player" + "Try again".
  - Found: `aocplayer://` was registered to the `tauri dev` exe, whose UI lives on Vite, so links
    showed "127.0.0.1 refused to connect" whenever Vite was down. `npm run build:local` builds a
    debug exe with the UI compiled in, copies it to `target/local-player/` and registers the scheme
    there; dev-server builds (`tauri::is_dev()`) no longer register it.
  - Tests: `PlayerIntegrationTest` 18/18 (new `aLaunchNeedsTheStudentsOwnActivePlayerOnThisDevice`),
    `PlaybackAuthorizationServiceTest` 8/8, native `cargo test` 55 passed (2 new), fmt + clippy (incl.
    `--features release`) clean, Playwright `aoc-player` + `student-youtube-playback` 26 passed.
- **Review fixes (2026-10-09):**
  - Rewatch was free: deltas were measured against the all-time furthest position. Now measured from
    the session's previous heartbeat; `last_position_seconds` on progress is the latest (resume) point.
  - Double counting: accounting committed in its own `REQUIRES_NEW` transaction. It now joins the
    heartbeat transaction; the WEB refusal commits (`noRollbackFor`) with a same-transaction revoke.
  - Speed: forward progress is capped at 2 x server elapsed (was 1 x), per the 2026-10-08 decision.
  - First native interval was never charged: the exchange now sets `last_heartbeat_at`.
  - Blocking: a used-up budget is refused at start (`requireRemaining` in `POST /playback/sessions`
    and the legacy issue endpoint) with `409 PLAYBACK_WATCH_LIMIT` (was 403, which the UI showed as
    "not available"). WEB player and lesson page show a terminal watch-time message; the AOC Player
    explains `WATCH_TIME_EXHAUSTED`.
  - Tests: `WatchTimeServiceTest` 8/8, `PlaybackAuthorizationServiceTest` 9/9,
    `PlayerIntegrationTest` 19/19, `VideoPlaybackSessionIntegrationTest` 19/19; Playwright
    `secure-video-player` + `aoc-player` + `student-youtube-playback` 31 passed.
- **No pairing step + install fallback + mobile preparation (owner, 2026-10-09; ADR-025 Amendment 2):**
  - Supersedes `PLAYER_NOT_PAIRED` above: a NATIVE launch for a device without an ACTIVE player
    carries a pairing code (`aocplayer://play?code=…&pair=…`); the player pairs, then claims the
    launch. Pairing-code issuing moved into `NativeLaunchService.issuePairingCode` (the pairing
    page uses it too). The "Pair this computer" card is optional.
  - Launcher: no claim after 12 s → "Install the AOC Player to watch this video" + "I've installed
    it - play now"; a browser that saw a claim before says "didn't open" instead.
  - Mobile: "Watch this video in the AOC app" + store link on Android/iOS once
    `NEXT_PUBLIC_AOC_MOBILE_APP_ANDROID_URL` / `_IOS_URL` are set; otherwise the Windows message.
    The app is still backlog (own amendment).
  - Tests: `PlayerIntegrationTest` 19/19 (`aLaunchWithoutAPlayerOnThisDevicePairsAndPlaysInOneStep`),
    `PlaybackAuthorizationServiceTest` 9/9; native `cargo test` 56 passed (parser + pair-then-play
    + refused pairing), fmt + clippy (incl. release) clean; Playwright player specs 31 passed.
- **Grant extra watch time (owner, 2026-10-09):** Tenant Admin → Students → Watch time tab lists used /
  allowed time per video and "Add time" (minutes + required reason). `V80__video_watch_time_grant.sql`
  (append-only, trigger, composite tenant FKs); allowed = budget snapshot + grants, time watched never
  reduced; `ACCESS_EXPIRY` V / C/E; audit `video.watch_time_granted` with before/after. Chosen over a
  "reset" so the record of what was watched is kept. API: `docs/api/video-access-management.md`
  "Watch-time grants". Tests: `StudentWatchTimeIntegrationTest` 5/5, `WatchTimeServiceTest` 9/9,
  regression `VideoPlaybackSessionIntegrationTest` 19/19 + `PlayerIntegrationTest` 19/19, Playwright
  `student-watch-time.spec.ts` 5/5.
- **Per-video limits (owner, 2026-10-09):** "instead of a global rule, add restrictions when adding a
  video to a course". Decided: the limit is per video and **the video's own limit wins** over the
  institute rule (higher or lower); the institute rule is the default. `GET/PUT
  /videos/{id}/watch-time-limit` (same ownership gate as playback rules, audited, clears budget
  snapshots so a change applies to everyone). UI: optional limit in the Media Library picker, "Edit
  limit" on each attached lesson video, upload form in minutes. Also: Watch time tab shows file names
  for untitled platform uploads and gets search + "Only used up". Tests:
  `VideoWatchTimeLimitIntegrationTest` 3/3, `StudentWatchTimeIntegrationTest` 5/5,
  `WatchTimeServiceTest` 11/11, `VideoPlaybackSessionIntegrationTest` 19/19, `PlayerIntegrationTest`
  19/19; Playwright `lesson-media` 6/6, `student-watch-time` 6/6, material/player specs 105 passed
  (2 failures in `route-groups.spec.ts` expect "Not yet implemented" placeholders on pages built since -
  stale, unrelated).

### Phase 13 — Managed Streaming (DynTube first) + Self-Hosted interface

**Before (2026-10-09, status → PLANNING; branch `feat/video-managed-streaming`; ADR-026 Accepted 2026-10-10)**

- **Owner decisions (2026-10-09):**
  - Q-002 → **DynTube first, Bunny later**.
  - Self-hosted = **interface only**.
  - **Own HLS player** (hls.js), not DynTube's embed.
  - AOC Player transport **DIRECT**; NativeMediaProxy moves to Phase 14.
  - The owner has a DynTube account for live verification.
- **Objective:** an institute whose video host is DynTube can upload lesson video from the Media
  Library. The video is processed by DynTube and plays only through short-lived, server-minted,
  tokenized HLS URLs, in both the web player and the AOC Player. Overlay watermark, capture
  protection and Phase 12 watch time apply exactly as for platform video.
- **Phase 13 is split into two steps, each with its own gate:**
  - **13a — Backend.** Provider, connection keys, upload adapter, status sync, signed-URL
    playback, heartbeat renewal, flags, migration and tests. Built against a fake DynTube server.
  - **13b — Frontend + AOC Player.** DynTube key/project form, upload destination, web hls.js
    player, native `hls` engine with a CSP/allow-list, and E2E tests.
  - Live DynTube verification (ADR-026 §Verification 1-6) happens between 13a and 13b. Contract
    corrections land before 13b.
- **Database impact:** `V81__managed_stream_dyntube.sql`.
  - Widens `ck_video_asset_provider` to add `DYNTUBE` and the provider-shape CHECK (DynTube rows
    need `provider_connection_id` and `provider_asset_id` once uploaded, and no
    `storage_object_key`). Added `NOT VALID`, then validated.
  - Widens any CHECK on `media_upload_job` that pins the provider.
  - No new tables. No edits to V1–V80.
  - The project id lives on the connection as non-secret configuration. The column vs the
    existing integration settings is decided during implementation; neither holds a secret.
- **API impact (additive):**
  - `PUT /api/v1/tenant-admin/integrations/dyntube` gains `tokenSecurityKey` (write-only) and
    `projectId`. The view gains `hasTokenSecurityKey` and `projectId`.
  - Media upload create accepts destination `DYNTUBE`.
  - `POST /api/v1/playback/sessions` (WEB) answers DynTube assets with a descriptor that carries
    `hls: {manifestUrl, expiresAt}`. WEB heartbeat/progress responses return a renewed
    `manifestUrl` before expiry.
  - The native exchange and heartbeat carry the same object.
  - `docs/api/video-access-management.md`, `docs/api/integrations.md` and
    `docs/api/aoc-player.md` are updated.
- **Security impact:**
  - The token security key is sealed with the API key (AES-256-GCM + AAD) and never returned,
    logged or audited.
  - URLs are minted only after the full playability check (enrollment, device, watch time,
    session ACTIVE), expire within 5 minutes, and stop being renewed on revoke, stop or
    exhaustion.
  - Only the fixed DynTube base URLs are called (no SSRF). Provider video keys are validated
    before use.
  - The AOC Player accepts `hls` media only for compiled-in DynTube hosts over HTTPS.
  - Honest limit: if segments aren't token-protected, this is deterrence, not DRM.
- **Tenant impact:**
  - Every upload, sync, mint and renew goes through the asset's own tenant connection, loaded by
    the tenant filter, with the composite `(tenant_id, provider_connection_id)` FK.
  - No cross-tenant lookup and no webhook.
  - Cross-tenant negative tests are added on media read, upload create, playback start and
    heartbeat renew.
- **Tests required:**
  - `DynTubeUploadAdapterTest` and `DynTubeTokenSignerTest` against `FakeDynTubeServer`.
  - Extended `MediaUploadIntegrationTest` (DynTube destination, retry, fatal, auth park).
  - `MediaStatusSyncService` DynTube cases.
  - `ManagedStreamPlaybackIntegrationTest`: WEB start, renew, refuse after revoke or
    exhaustion, cross-tenant 404, flag off.
  - `PlayerIntegrationTest` DynTube exchange.
  - `TenantAdminIntegrationsIntegrationTest`: key write-only, project check, audit without
    values.
  - Migration check for the widened CHECKs.
  - 13b: Vitest/Playwright for the key form, upload destination and the hls.js player (mocked
    manifest); native `cargo test` for the media validator allow-list.
- **Self-hosted:** stays reserved (`PlaybackMode.SELF_HOSTED`, locked flag); no code. Recorded as
  ADR-026 §Alternatives.
- **Out of scope:** Bunny adapter (next phase, ADR-018 amendment), NativeMediaProxy (Phase 14),
  DRM, webhooks, upload-by-URL, deleting videos at DynTube.


**13a — Backend (2026-10-10, status → BUILT; ADR-026 Accepted 2026-10-10)**

- **Live verification (owner's DynTube account, read-only probe, keys never printed):**
  - Token algorithm confirmed: `expires` is in **milliseconds**.
  - Bad and expired tokens get 403. A request without a browser `User-Agent` gets an **empty 200**.
  - Segments are AES-128 with their own CDN `md5`/`expires` signatures, valid for about 12 h.
  - **The untokenized `/live/videos/{key}.m3u8` still plays.** This is now ADR-026 consequence 1:
    both DynTube flags stay off in real environments until the project refuses it.
  - Hosts: `api.dyntube.com` serves the master playlist; `cdn-s*.dyntube.net` serves variants,
    segments and keys.
  - Full results are in ADR-026 §Live verification.
- **Integration module:**
  - `DynTubeClient`: `GET /projects`, streamed multipart `POST {upload}/videos`, and
    `GET /videos/{id}`. Fixed base URLs; every id and key is checked against the URL-safe pattern.
  - `DynTubeTokenSigner`.
  - `DynTubeStreamService` implements the whole-file upload, status sync and
    `ManagedStreamPlaybackApi.signHlsUrl`. It always uses the tenant's current ACTIVE DynTube
    row; a 401 marks that row NEEDS_RECONNECT.
  - `VideoProviderUploadRouter` (`@Primary`) sends DynTube connections to the new service and
    YouTube to the unchanged service.
  - `PUT /integrations/dyntube` adds the write-only `tokenSecurityKey` and `projectId`. The project
    must be one of the account's own. The project and a "token key configured" flag live in
    `settings_json`; the key stays in the sealed credentials. The audit entry has no values.
  - `MediaConnectionApi.findManagedHost`; the same-channel lookup now follows DynTube's current row.
- **Video module:**
  - `MediaAssetProvider.DYNTUBE` and its catalog capabilities (own player, signed, adaptive,
    overlay; no DRM, no resumable upload).
  - `managed-stream-playback` is unlocked and a new `dyntube-upload` flag is added (both default
    off; env `VIDEO_MANAGED_STREAM_PLAYBACK_ENABLED` / `VIDEO_DYNTUBE_UPLOAD_ENABLED`).
  - `POST /media` accepts `provider: DYNTUBE`, gated on: flag, institute switch, DynTube as video
    host, ACTIVE account, project and token key.
  - `UploadTransferWorker.transferWhole` streams the staged object in one ranged S3 GET with no
    buffer and no disk. `architecture.md`'s scoped exception now names this path.
  - Sync stores the playback key **before** READY.
  - `PlaybackMediaSigner` (`FILE` | `HLS`) is used by web sessions, the native exchange and
    heartbeats. Web sessions now sign **before** creating the session, so a refusal leaves no
    orphan holding the concurrency slot.
  - Lesson playability refuses DynTube while managed streaming is off. `mediaFormat` is added to
    the web session response and the player descriptor.
- **Migration:** `V81__managed_stream_dyntube.sql`.
  - `ck_video_asset_provider` gains `DYNTUBE` (added NOT VALID, then validated).
  - New `video_asset.provider_playback_key`, with a CHECK for the URL-safe charset; READY DynTube
    rows require it and other providers must leave it null.
- **Tests:**
  - New: `DynTubeTokenSignerTest` 5/5 (vector computed independently with Node), and
    `DynTubeUploadIntegrationTest` 11/11 covering keys (write-only, project check, audit), the
    upload → READY flow with the key, processing failure, refusal gates, 415/503/401, cross-tenant
    upload and playback, signed HLS (token recomputed in the test), flag-off and needing-reconnect
    with no session created, and the teacher preview.
  - `PlayerIntegrationTest` 20/20, including the new DynTube exchange + heartbeat renewal +
    kill-switch test. `VideoProviderCatalogTest` 6/6 (managed streaming unlocked).
  - The fake DynTube server reproduces the live-verified shapes.
  - Regression: `MediaUploadIntegrationTest` 20/20, `VideoPlaybackSessionIntegrationTest` 19/19,
    `PlaybackAuthorizationIntegrationTest` 12/12, `MediaLibrary*` 22/22,
    `TenantAdminIntegrationsIntegrationTest` 7/7.
  - Full `mvnw verify` (2026-10-10): **2464 tests, 0 failures/errors/skips, BUILD SUCCESS**.
  - After the full run, two review fixes landed. A failed DynTube upload can now be retried on
    the institute's DynTube account (it used to look up a YouTube integration and answer 404).
    Renaming a DynTube video stays in the LMS, so a DynTube account problem never blocks it.
    Re-run: `DynTubeUploadIntegrationTest` 12/12 (new edit test), `MediaUploadIntegrationTest`
    20/20, `MediaLibraryManagementIntegrationTest` 12/12.
- **Not yet:** 13b covers the Tenant Admin DynTube form (token key + project picker), the upload
  destination in the Media Library, the web hls.js player, and the native `hls` engine with the
  CSP and allow-list for `api.dyntube.com` + `*.dyntube.net`.

**13b — Frontend + AOC Player (2026-10-10, status → BUILT)**

- **API mismatch found and fixed additively (backend, reported here):** the Media Library had no
  way to show or upload to DynTube.
  - `GET /media/channels` gains `managedHost`.
  - `GET /media/upload-limits` accepts `provider=DYNTUBE`.
  - Contract in `docs/api/video-access-management.md`. Covered by `DynTubeUploadIntegrationTest`
    (now 13/13, including the tab/limits test).
- **Web (`frontend/`):**
  - **Tenant Admin → Integrations → DynTube:** the write-only "Token security key" (shown as
    "Saved (hidden)"), the "Upload project ID" (server field errors land on it), a note to make the
    DynTube project refuse unsigned playback and keep "Extra Security" off, and a summary of the
    key and project.
  - **Media Library:** a DynTube tab (default when present) listing `provider=DYNTUBE`.
    `MediaUploadPanel` takes an `UploadDestination` (YouTube integration | DynTube). DynTube
    uploads send `provider: DYNTUBE` with no channel or privacy, and the blocked reasons and links
    point to Integrations.
  - **Lesson player:** `SecureVideoPlayer` plays `mediaFormat: HLS` sessions with hls.js (loaded
    on demand, `components/courses/hls-source.ts`), with Safari native HLS as the fallback. The
    3-minute session refresh renews the token but keeps a running stream. A fatal stream error
    goes through the existing bounded re-issue. A generation counter drops superseded attaches,
    and teardown always destroys hls.js.
- **AOC Player (`native-player/`):**
  - `Media::Hls` with `checked_hls_url` (HTTPS `api.dyntube.com`, the tokens path, the key
    charset, no credentials, port or fragment). A `DYNTUBE` descriptor without `mediaFormat: HLS`
    is refused.
  - `engines/hls.ts` (hls.js without a worker) has the same controls, seek deterrent, overlay
    watermark and playback reports as the file engine. It reloads from a fresher heartbeat URL
    only after a fatal error.
  - CSP: `connect-src` adds `https://api.dyntube.com` and `https://*.dyntube.net`; `media-src`
    adds `blob:`.
- **Tests:**
  - Playwright: `media-library` + `tenant-admin-integrations` + `secure-video-player` 52/52
    (5 new: the DynTube tab and upload body, the not-set-up reason, the write-only token key with
    the project field error, and HLS loaded via hls.js as a `blob:` source). Regression of 8 video
    and material specs: 79/79.
  - `tsc` clean; ESLint 0 errors (2 pre-existing warnings).
  - Native: `cargo test` 59/59 (3 new HLS tests), `cargo fmt`, and clippy `-D warnings` with and
    without `--features release` all clean; `npm run build` passes (bundle 606 kB with hls.js).
  - Backend regression: 114/114 across the DynTube, player, upload, library, integrations and
    playback suites.
- **Still to do (owner):**
  1. Make the DynTube project refuse unsigned playback, then re-run the probe.
  2. Do a live run: Integrations (keys + project) → video host DynTube → upload → wait for READY →
     attach to a lesson → play in the browser and in the AOC Player (`npm run build:local` in
     `native-player/`). Both flags must be on locally: `VIDEO_DYNTUBE_UPLOAD_ENABLED=true` and
     `VIDEO_MANAGED_STREAM_PLAYBACK_ENABLED=true`.
  3. Check the upload size limit at that first real upload.

**13c — DynTube player via an LMS bridge page (2026-10-11, status → BUILT for the AOC Player; ADR-026 Amendment 1, owner-approved)**

- **Built (2026-10-11), owner chose to build before the whitelist gate (see ADR-026):**
  - Backend: `V83__video_asset_provider_embed_key.sql` (not V82, which was taken), `channelKey`
    parsed at status sync, a one-time backfill of READY DynTube rows, `PlaybackMediaSigner`
    DYNTUBE → `EMBED` (needs the flag, an ACTIVE account, the embed key and
    `app.player.dyntube-bridge-url`), `embed {provider, key, bridgeUrl}` on the web session and
    the native descriptor, and DynTube lessons launch only in the AOC Player
    (`Playable.playsOnlyInAocPlayer`).
  - Bridge: a Next route handler, `src/app/embed/dyntube-bridge.html/route.ts` (not a static
    `public/` file), served only when Host = `EMBED_HOST` (dev `embed.lms.test:3000`), with a
    nonce CSP and `frame-ancestors` from `EMBED_PARENT_ORIGINS`.
  - AOC Player: `Media::Embed` (compiled-in `AOC_EMBED_BRIDGE_URL`, the server's `bridgeUrl` is
    ignored), `engines/embed.ts`, `frame-src` adds the dev embed origin, and the overlay
    watermark over the frame.
  - Not built: the web `SecureVideoPlayer` embed (it shows "plays in the AOC Player"), and the
    "token key no longer required for uploads" relaxation.

- **Why:** the live run showed DynTube serves AES keys only to its own player on whitelisted
  domains (Video Encryption / Extra Security), so our hls.js players get 400 on the key. The owner
  chose to keep DynTube's protections on and embed DynTube's player instead.
- **Goal:** DynTube lesson video plays in DynTube's own player inside our web lesson player and the
  AOC Player. Phase 12 watch time, resume, seek deterrent, overlay watermark, capture protection
  and server-side stop keep working.
- **Design:**
  - A static bridge page on a dedicated embed origin runs DynTube's JS embed and relays the
    player's state to its parent over origin-checked postMessage.
  - Each tenant whitelists only the embed origin at DynTube.
  - Full design in ADR-026 Amendment 1.
- **Order (gated):**
  1. Owner verification (Amendment 1 §Owner verification 1-3).
  2. Backend.
  3. Bridge page + web player.
  4. AOC Player.
  5. Reviews + docs.
- **Files affected:**
  - Backend:
    - `V82__video_asset_provider_embed_key.sql`
    - `VideoAsset` (`providerEmbedKey`)
    - `DynTubeClient` (parse `channelKey` from upload and `GET /videos/{id}`)
    - `DynTubeStreamService` and `VideoProviderUploadApi` (`Complete` / `ProviderVideoStatus` carry
      the embed key)
    - `MediaUploadStore` / status sync (store the key, backfill existing rows)
    - `PlaybackMediaSigner` (DYNTUBE → `EMBED`)
    - `PlaybackDescriptor`, `PlaybackSessionView` and the web DTOs (`embed`)
    - `VideoProviderCatalog` (DynTube: provider player, no custom player)
    - `MediaAssetService.uploadBlockedReason` and `MediaUploadService.requireUsableManagedHost` (the
      token key no longer required)
    - video config properties (`video.embed-origin`, `video.embed-bridge-path`)
  - Frontend:
    - `public/embed/dyntube-bridge.html` + `dyntube-bridge.js`, served on the embed host only
      (a `next.config` header with the bridge CSP, and `frame-ancestors`)
    - `components/courses/embed-source.ts` (the parent side of the protocol)
    - `SecureVideoPlayer` (EMBED branch)
    - `lib/api/videos.ts` types
  - Native:
    - `media.rs` (`Media::Embed` + the compiled-in bridge URL)
    - `engines/embed.ts`, `engines/index.ts`, `types.ts`
    - `tauri.conf.json` (`frame-src` adds the embed origin)
  - Docs:
    - `docs/api/video-access-management.md` and `docs/api/aoc-player.md` (`mediaFormat: EMBED`)
    - The integrations note: whitelist the embed origin; Extra Security may stay on
    - This plan and ADR-026.
- **Database impact:**
  - One new migration, V82, adding nullable `video_asset.provider_embed_key VARCHAR(64)`.
  - A CHECK enforces the URL-safe charset, and `provider = 'DYNTUBE' OR provider_embed_key IS NULL`.
  - No backfill SQL: status sync fills existing DynTube rows.
  - No edits to V1–V81.
- **API impact (additive):**
  - Web session and native descriptor: `mediaFormat` gains `EMBED`, with
    `embed: {provider, key, bridgeUrl}`. For EMBED, `signedUrl` is null.
  - `managedHost.uploadBlockedReason` no longer names the token key.
  - `PUT /integrations/dyntube`: `tokenSecurityKey` stays optional and accepted (unchanged shape).
- **Security impact:**
  - DynTube protections stay on. Its third-party script is confined to the embed origin: no LMS
    session, and no Tauri IPC in the AOC Player.
  - The bridge accepts commands only from allowed parent origins. `frame-ancestors` limits who can
    frame it.
  - The AOC Player frames only the compiled-in bridge URL.
  - The key is issued only after the full playability check.
  - Honest limit: the embed key isn't per-session (ADR-026 Amendment 1).
- **Tenant impact:**
  - The key is read from the tenant-filtered asset row; no cross-tenant path.
  - Cross-tenant 404 tests for the EMBED descriptor (web session, native exchange, heartbeat).
- **Tests required:**
  - Backend: `DynTubeUploadIntegrationTest` (embed key stored at upload and at sync; EMBED
    descriptor; no key → not playable; cross-tenant 404; uploads without a token key);
    `PlayerIntegrationTest` (DynTube exchange/heartbeat EMBED); `VideoProviderCatalogTest`; and
    the V82 migration CHECKs.
  - Frontend Playwright: a mocked bridge iframe drives `state` messages, then progress is posted;
    messages from a wrong origin are ignored; STOP tears down the iframe; the watermark is drawn
    over the iframe; the bridge refuses a non-allowed parent.
  - Native: `cargo test` for `Media::Embed` (key charset, bridge URL compiled in, no server URL
    framed); `tsc`; `npm run build:local`; a manual AOC run.
- **Owner prerequisites:**
  - Add `127.0.0.1 embed.lms.test` to the hosts file.
  - Whitelist `embed.lms.test` (dev) in the DynTube project.
  - Share a redacted `GET /videos/{id}` response showing `channelKey`.
