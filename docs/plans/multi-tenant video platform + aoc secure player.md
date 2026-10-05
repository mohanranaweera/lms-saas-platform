# Multi-Tenant Video Platform + AOC Secure Player — Living Plan

```text
Project:          Multi-Tenant Video Platform + AOC Secure Player
Overall Status:   IN PROGRESS
Current Phase:    5b — Institute channel: allowed teachers + teacher YouTube removal (TESTED — awaiting commit)
Last Updated:     2026-10-05
Current Branch:   feat/video-channel-teachers
Latest Migration: V73__institute_video_channel_teachers.sql
Backend Build:    PASS — mvnw verify, 2026-10-05 (BUILD SUCCESS, 13:20 min)
Frontend Build:   not affected (no frontend change in Phases 1–2)
Player Build:     n/a (player project does not exist yet)
Tests:            backend 2357 run / 0 failures / 0 errors / 0 skipped (full suite, 2026-10-05)
Open Blockers:    B1–B4, B6 (§0.3) — owner approvals for change-controlled areas (B5 approved 2026-10-04)
```

| Phase | Description | Status | Started | Completed |
|---|---|---|---|---|
| 1 | Architecture & Repository Analysis | COMPLETE | 2026-10-04 | 2026-10-04 |
| 2 | Media Domain Foundation | COMPLETE | 2026-10-04 | 2026-10-04 |
| 3 | Tenant Video Provider Accounts | TESTED (full backend suite re-run + commit pending) | 2026-10-04 | |
| 4 | YouTube Integration | NOT STARTED | | |
| 5 | Media Library | NOT STARTED | | |
| 6 | Lesson Integration | NOT STARTED | | |
| 7 | Playback Authorization | NOT STARTED | | |
| 8 | Device & Session Security | NOT STARTED | | |
| 9 | AOC Secure Player Foundation | NOT STARTED | | |
| 10 | Playback Engines | NOT STARTED | | |
| 11 | Watermark & Capture Protection | NOT STARTED | | |
| 12 | Watch-Time & Heartbeat | NOT STARTED | | |
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
| B3 | **ADR-008 amendment**: YouTube is gated but unprotected; protected playback = managed HLS; launch codes, exchange and heartbeat added to the token model | Phase 7 |
| B4 | **ADR-025 Native Secure Player**: Rust/Tauri 2 stack, `aocplayer://`, installation keys, the exchange endpoint as an ADR-011-style unauthenticated carve-out, signed updates | Phase 8–11 |
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
| 6 | LessonMedia endpoints + lesson editor picker | ✔ | ✔ | — | Q-001 |
| 7 | Playback sessions, authorization, launch codes, exchange, descriptors, WEB YouTube engine | ✔ | ✔ | — | B3, B4 (exchange carve-out) |
| 8 | Player installations, pairing, device limits/revocation, concurrency, admin revoke | ✔ | ✔ | — | B4 |
| 9 | `native-player/` Tauri shell, deep link, single instance, exchange client, session lifecycle, updater foundation, CI | — | download page | ✔ | B4 |
| 10 | Engines: YouTubeStandard (prod), Hls/Dash (behind flag), FutureProvider stub | — | web engine split | ✔ | — |
| 11 | Watermark policy + layer, Windows capture affinity, capability reporting | ✔ | ✔ | ✔ | Q-006, Q-012 |
| 12 | Heartbeat, server watch-time, resume, stale-session cleanup, periodic re-authorization | ✔ | ✔ | ✔ | — |
| 13 | Managed provider (candidate Bunny Stream) + signed HLS/DASH + NativeMediaProxy | ✔ | ✔ | ✔ | Q-002 + ADR |
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
`ADR-025-native-secure-player.md`, `ADR-026-video-provider-abstraction.md` (VID-ADR-001/002/006);
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
| VID-ADR-003 | 2026-10-04 | LessonMedia = existing `Material(VIDEO)` row | One ordering/visibility/entitlement path | New `lesson_media` table | No migration in Phase 6 | PROPOSED (Q-001) |
| VID-ADR-004 | 2026-10-04 | Upload via presigned S3 staging + background worker → YouTube resumable | No binary on request threads; server-side validation; bounded memory | Multipart through backend (brief §21); browser direct to YouTube session URI | Scoped architecture.md exception (worker only) | ACCEPTED (owner, B2, 2026-10-04) |
| VID-ADR-005 | 2026-10-04 | Provider credentials stay in `media_provider_connection` ciphertext (AES-256-GCM+AAD) | Existing reviewed mechanism (ADR-018/019) | External vault/KMS | No new secret store; key rotation = TD-003 | PROPOSED |
| VID-ADR-006 | 2026-10-04 | Feature flags = typed config properties + tenant config switches until Module D exists | No flag engine; repo pattern | Introduce Unleash/FF4J | No per-plan gating yet | PROPOSED |
| VID-ADR-007 | 2026-10-04 | YouTube mode: no overlay on the player; watermark band outside it | YouTube RMF | Overlay anyway | Weaker attribution in YouTube mode | PROPOSED (Q-006 legal) |
| VID-ADR-008 | 2026-10-04 | One generic external launch-code error; detailed reason only in security logs | Avoid oracle for code probing | Brief's three distinct codes | Player shows one message | PROPOSED |
| VID-ADR-009 | 2026-10-04 | Player authenticates with an installation keypair (proof-of-possession), never an LMS JWT | Deep-link hijack resistance; no long-lived bearer on disk | Bearer installation token | Pairing step required | PROPOSED (B4) |
| VID-ADR-010 | 2026-10-04 | `YOUTUBE_CUSTOM` = interfaces + locked-off flag; no resolver code | No authorized arrangement; YouTube terms | Implement extraction | Future work needs ADR + provider authorization | ACCEPTED (brief mandate) |
| VID-ADR-011 | 2026-10-04 | NativeMediaProxy via custom protocol scheme with descriptor-bound allow-list, not a localhost port | Reduces local attack surface and SSRF | localhost HTTP proxy | Phase 13 design | PROPOSED |

## 39. Open Questions

| ID | Question | Recommendation | Blocks |
|---|---|---|---|
| Q-001 | LessonMedia: reuse `Material(VIDEO)` or new `lesson_media` table? | Reuse | Phase 6 |
| Q-002 | Which managed streaming provider? | Bunny Stream (already integrated), confirm after review | Phase 13 |
| Q-003 | May one YouTube channel be connected to two tenants? | No | Phase 3 |
| Q-004 | Keep playing already-uploaded videos after disconnect? | Yes, LMS-gated | Phase 3 |
| Q-005 | Under what authorized provider arrangement could `YOUTUBE_CUSTOM` be activated? | None today; owner/legal | — |
| Q-006 | Legal review of RMF for the outside-player watermark band and window capture affinity in YouTube mode | Obtain before Phase 11 ships to prod | Phase 11 |
| ~~Q-007~~ | Google Cloud: one platform project (shared quota), separate upload project, or tenant-supplied projects? | **Resolved 2026-10-04: one platform project per environment + per-tenant budget** | — |
| Q-008 | Does a paired player installation share the browser's device slot or count as an extra device? | Share | Phase 8 |
| Q-009 | Is the native player mandatory for all tenants, or per-tenant opt-in? | Per-tenant `native_player_required` | Phase 7 |
| Q-010 | Teacher visibility: own uploads only, or all tenant media? | **2026-10-04: own uploads in Phase 5; attached-course media added in Phase 6** | Phase 6 |
| ~~Q-011~~ | New `DomainArea.MEDIA_LIBRARY` or reuse `MATERIALS`/`INTEGRATIONS`? | **Resolved 2026-10-04: reuse `MATERIALS`** | — |
| Q-012 | Capture protection required but OS unsupported → refuse or warn? | Refuse when tenant sets `required` | Phase 11 |
| ~~Q-013~~ | Audit `actor_id` for system jobs (DB trigger requires a real user) | **Resolved 2026-10-04: `video_asset_status_event` (actor NULL) + security log; `audit_log` stays for human actions** | — |
| ~~Q-014~~ | Default per-tenant YouTube upload budget | **Resolved 2026-10-04: 20/day/tenant, configurable per tenant** | — |
| Q-015 | macOS / mobile players timeline | Windows first; out of scope now | — |
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
- **Still open:** B1–B4, B6 for later phases; Q-001…Q-017.
- **Phase 2 prerequisites:** B5 is met. Q-001 / Q-003 / Q-004 affect later phases only. Phase 2
  still needs an explicit instruction to start.

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
