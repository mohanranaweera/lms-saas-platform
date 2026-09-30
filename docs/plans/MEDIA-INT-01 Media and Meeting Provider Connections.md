# MEDIA-INT-01 — Media & Meeting Provider Connections (Zoom, YouTube, Vimeo, DynTube, Bunny.net)

Status: **Implemented and reviewed** (backend + frontend, 2026-09-29; ADR-018 Accepted
2026-09-28). See §13 for what changed during implementation and review. Backend first, then frontend (no full-stack approval given); committed as one
unit once the frontend is done.

## 0. Programme overview (owner decisions, 2026-09-28)

| Provider | Account scope | Connected via | Used for |
|---|---|---|---|
| Zoom | **Per teacher** (each teacher's own account) | OAuth "Connect" | Live classes hosted on the teacher's account; Zoom recording export (later) |
| YouTube | **Per teacher** | OAuth (Google) | Pick own private/unlisted videos, upload from LMS, YouTube Live (later) |
| Vimeo | **Per teacher** | OAuth | Pick own private videos, upload from LMS, Vimeo Live (later) |
| DynTube | **One per tenant** | API key (Tenant Admin) | Secure hosting of uploaded VIDEO/RECORDING materials (later) |
| Bunny.net (Stream) | **One per tenant** | API key + library (Tenant Admin) | Secure hosting of uploaded VIDEO/RECORDING materials (later) |

Delivered in phases, each with its own plan:

- **MEDIA-INT-01 (this plan):** connection framework for all five providers: settings pages,
  OAuth connect/callback, encrypted credential storage, test connection, token refresh,
  disconnect, audit. Plus **real Zoom live classes on the teacher's own account**, which replaces
  the fake Zoom adapter for tenants whose teacher is connected.
- **MEDIA-INT-02:** YouTube/Vimeo in Materials. Teachers pick their own private/unlisted videos
  and upload from the LMS to their own channel/account.
- **MEDIA-INT-03:** DynTube/Bunny secure hosting for VIDEO/RECORDING materials, behind the
  existing ADR-008 playback token.
- **MEDIA-INT-04:** YouTube Live/Vimeo Live as live-class providers, and automatic export of Zoom
  recordings to the teacher's YouTube/Vimeo.

## 1. Business goal

Institutes want their teachers to run classes and publish videos using **the teachers' own
Zoom, YouTube and Vimeo accounts**. Each teacher's meetings, recordings and channels stay under
that teacher's control and licence. The institute also wants **one tenant-wide secure video
host** (DynTube or Bunny.net) for the paid video content it owns.

Today:

- Live classes run against a fake Zoom adapter, so no real meeting can be created.
- YouTube/Vimeo can only be attached by pasting a URL.
- There is nowhere to configure any of these accounts.

This module gives every teacher a **Settings → Integrations** page and every Tenant Admin an
**Institute configuration → Integrations** page to connect these accounts safely. It then makes
live classes actually create Zoom meetings on the scheduling teacher's own Zoom account.

## 2. User roles

| Role | Interaction |
|---|---|
| Teacher | **Settings → Integrations** (new teacher settings area): Connect/Reconnect/Disconnect own Zoom, YouTube, Vimeo via OAuth; sees account label (e.g. Zoom email, YouTube channel name), status, last verified, "Test connection". Can only ever see/act on **their own** connections. Scheduling a live class uses their own Zoom connection. |
| Tenant Admin | **Institute configuration → Integrations**: configure/replace/test/disable the tenant's DynTube and Bunny.net accounts (API keys, write-only); choose which teacher-level providers teachers may connect (Zoom/YouTube/Vimeo on/off); read-only table of teachers' connection status with a **Disconnect** action (e.g. a teacher left). Cannot connect on a teacher's behalf and never sees any token. |
| Staff with `INTEGRATIONS` VIEW | Read-only view of the same Integrations page (status only). |
| Staff with `LIVE_CLASSES` grant | Scheduling a session for a course creates the meeting on the **session's assigned teacher's** Zoom account; blocked with a clear message when that teacher is not connected. |
| Student | No settings. Joins live classes through the existing join endpoint, which now returns a real Zoom join link (see §8 link-sharing). |
| Platform Admin | Out of scope for this phase: no per-tenant integration UI. Operates the platform-level OAuth apps (Zoom/Google/Vimeo client id/secret) as environment configuration only. |

## 3. Acceptance criteria

### Connection framework (all providers)

1. Teacher-level providers (ZOOM, YOUTUBE, VIMEO) can only exist with `scope = TEACHER` and an
   owning teacher. Tenant-level providers (DYNTUBE, BUNNY) can only exist with `scope = TENANT`
   and no owner. Both rules are enforced by a DB CHECK and a service check.
2. At most **one** non-disconnected connection exists per (tenant, teacher, provider) for
   teacher providers, and per (tenant, provider) for tenant providers. This is enforced by
   partial unique indexes. Replacing or reconnecting marks the old row `DISCONNECTED` and inserts
   a new row. Rows are never deleted, because `class_session` references the connection that
   hosted it.
3. Every secret is AES-256-GCM encrypted with a **separate** key (`MEDIA_CREDENTIALS_ENCRYPTION_KEY`)
   before persistence. Secrets include OAuth access/refresh tokens and DynTube/Bunny API keys.
   Secrets are never returned by any API, never logged, and never written to audit/metrics.
   With the key unset, connect/save fails closed (503) and nothing is stored.
4. Responses only ever expose: provider, scope, status, account label, masked external account
   id, granted scopes, connectedAt, lastVerifiedAt, lastError (sanitised), and
   disconnectedBy/At/reason.
5. **Test connection** calls a cheap read endpoint on the provider using the stored
   credentials. On success it updates `last_verified_at`. On an auth failure it moves the
   connection to `NEEDS_RECONNECT` with a sanitised `last_error`. It returns the result and never
   echoes provider response bodies containing tokens.
6. A teacher can Connect/Reconnect/Disconnect/Test only their **own** connections. A request
   addressing another teacher's connection id → 404.
7. A Tenant Admin (`INTEGRATIONS` CREATE_EDIT) can Disconnect any teacher connection in their
   own tenant, with a required reason. They cannot create, reconnect or read tokens for it.
8. Disconnect is best-effort revocation at the provider (Zoom/Google/Vimeo token revoke) **after**
   the local commit. A revoke failure is logged but never blocks the local disconnect.
9. Every connect/replace/disconnect/status change writes an audit log entry. Each entry records
   actor, tenant, provider, scope, owner teacher, masked account id, and reason. Provider-driven
   changes use a system actor.
10. A Tenant Admin can switch each teacher provider (Zoom/YouTube/Vimeo) on/off for the tenant.
    When off, teachers see the provider as "Not enabled by your institute", and connect → 409.
    Existing connections stay but are not used; live-class scheduling reports "Zoom is not
    enabled for your institute".
11. A provider whose platform OAuth app is not configured (env vars unset) is shown as
    "Unavailable", and connect → 503. This is never a silent failure.

### OAuth connect flow (Zoom, YouTube, Vimeo)

12. `POST .../connect` returns a provider authorisation URL. It includes a random single-use
    `state` (≥128-bit) and a PKCE challenge where the provider supports it. The state is bound in
    Redis (TTL 10 min) to tenant, teacher user id, provider, PKCE verifier and the tenant's
    return host.
13. The provider redirects to **one platform-wide callback URL** (the only one registered with
    each provider). The callback resolves tenant and teacher **only from the Redis state**, which
    it consumes atomically (`GETDEL`). It never trusts query parameters other than `code` and
    `state`. Unknown, expired or replayed state → no connection, and the user is redirected to a
    generic failure page. A security log entry is written.
14. After exchanging the code, the backend fetches the account identity (Zoom `users/me`,
    YouTube `channels?mine=true`, Vimeo `/me`), stores it as label and external id, and redirects
    to the **tenant's own host** `/teacher/settings/integrations?pending=<id>&provider=` (superseded: the teacher then confirms - see §13). The
    return host comes from the state and must be a host that resolves to that same tenant, so
    there is no open redirect.
15. The user denying consent (`error=access_denied`) → redirect with `?error=denied`. Nothing is
    stored, and the state is consumed.
16. Scopes requested this phase are the minimum needed:
    - Zoom: `meeting:write`, `meeting:read`, `user:read`, `recording:read`.
    - YouTube: `youtube.readonly`.
    - Vimeo: `public private`.

    Later phases add scopes through incremental reconnect. Granted scopes are stored, and a
    feature needing a missing scope reports "Reconnect to grant access".
17. Access-token refresh happens server-side when a token is within 5 minutes of expiry.
    - A per-connection Redis lock ensures only one refresh runs at a time, which matters because
      Zoom rotates refresh tokens.
    - The new tokens are persisted in a short transaction **after** the HTTP call; no DB
      transaction is held open across it.
    - A refresh that fails with `invalid_grant` → `NEEDS_RECONNECT`, audit entry, and the teacher
      sees a "Reconnect" banner.

### Tenant-level providers (DynTube, Bunny.net)

18. Tenant Admin saves:
    - DynTube: API key.
    - Bunny: Stream API key, video library id, CDN hostname, token-authentication key.

    Keys are write-only. A blank key on update keeps the stored key, but a key is required when
    none is stored.
19. Save runs Test connection first:
    - DynTube: list projects.
    - Bunny: get library.

    Invalid credentials → 400 with a field error, and nothing is stored.
20. Both DynTube **and** Bunny may be connected at once, but only one is the tenant's
    **active video host**. The active host is a tenant config setting, and choosing one requires
    an ACTIVE connection. Nothing consumes it until MEDIA-INT-03, and the UI labels it "Used for
    uploaded videos (coming soon)" until then.

### Zoom live classes on the teacher's account

21. Scheduling a class session (existing flow) resolves the **session's teacher's** ACTIVE Zoom
    connection. It creates the meeting on that account via the Zoom API (outside any DB
    transaction, per the existing PENDING → PROVISIONED pattern) and stores
    `provider_connection_id` and `provider_reference` (the Zoom meeting id).
22. No ACTIVE Zoom connection for the teacher → the session is **not** created. The response is
    409 "<Teacher> has not connected a Zoom account" (teacher view: "Connect your Zoom account in
    Settings → Integrations"). If the platform OAuth app is not configured and the environment is
    local/test with `live-class.provider.fake-fallback-enabled=true`, the fake adapter is used.
    That flag is never set in real environments.
23. **Teacher start:** `POST /class-sessions/{id}/join`, called by the course-owning teacher,
    fetches a fresh Zoom `start_url` at request time. It is never stored, and it is only returned
    once the existing ownership and status checks pass.
24. **Student join:** Students are added as Zoom meeting **registrants** at join time, and the
    per-student registrant join URL is returned only after the existing
    enrollment/tenant/status checks pass. This makes each join link personal, which answers the
    open link-sharing decision (ADR-018 §4).
    - Registrants require a licensed Zoom account.
    - Connecting a Basic (free) Zoom account is allowed but flagged "Licensed Zoom account
      required for live classes", and scheduling → 409 with that message.
25. Cancelling a session deletes the Zoom meeting best-effort (existing `cancelMeeting`), using
    the connection that created it.
26. Teacher disconnects Zoom while having upcoming sessions:
    - Disconnect is allowed after a confirmation dialog listing the count of affected upcoming
      sessions.
    - Those sessions show "Zoom account disconnected". Join/start → 409 with a clear message.
    - Reconnecting the **same** Zoom account (same external account id) restores them
      automatically.
    - A different account requires the teacher to use **Recreate meeting**
      (`retry-provisioning`), which provisions a new meeting and new `provider_reference` on the
      current connection.
27. Zoom webhooks move from the generic HMAC format to **Zoom's real format**, verified with the
    Zoom app's secret token.
    - Signature: `x-zm-signature` = `v0=` + HMAC-SHA256 of `v0:{x-zm-request-timestamp}:{body}`.
    - Timestamps older than 5 minutes are rejected.
    - The `endpoint.url_validation` challenge is supported.
    - Tenant is still resolved only via `provider_reference` → `class_session` (ADR-011), never
      from the payload. Existing idempotency is unchanged.
28. **Zoom `app_deauthorized` webhook:** every connection with that Zoom user id →
    `DISCONNECTED` (disconnectedBy `PROVIDER`), with an audit entry. This is the cross-tenant
    lookup by external account id justified in ADR-018 §5.

## 4. Database impact

New migration **`V60__create_media_provider_connection.sql`**. It is additive only, and no
existing migration is edited.

- **`media_provider_connection`** (owner `com.lms.integrationmanagement.domain`)
  - `id UUID PK`, `tenant_id UUID NOT NULL REFERENCES tenant(id)`
  - `provider VARCHAR(20) NOT NULL CHECK (provider IN ('ZOOM','YOUTUBE','VIMEO','DYNTUBE','BUNNY'))`
  - `scope VARCHAR(10) NOT NULL CHECK (scope IN ('TEACHER','TENANT'))`
  - `owner_user_id UUID NULL` — composite FK `(tenant_id, owner_user_id) → tenant_user (tenant_id, id)`
  - `auth_type VARCHAR(10) NOT NULL CHECK (auth_type IN ('OAUTH','API_KEY'))`
  - `external_account_id VARCHAR(255)`, `external_account_label VARCHAR(255)`
  - `external_account_type VARCHAR(30)` (e.g. Zoom `BASIC`/`LICENSED`)
  - `credentials_ciphertext TEXT NOT NULL`: an encrypted JSON blob holding the access/refresh
    token or API key, plus provider-specific non-secret config (e.g. Bunny library id) stored in
    `settings_json JSONB`
  - `access_token_expires_at TIMESTAMPTZ`, `granted_scopes VARCHAR(1000)`
  - `status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','NEEDS_RECONNECT','DISCONNECTED'))`
  - `disconnected_by VARCHAR(12) CHECK (IN ('OWNER','TENANT_ADMIN','PROVIDER','SYSTEM'))`,
    `disconnected_reason TEXT`, `disconnected_at TIMESTAMPTZ`
  - `last_verified_at TIMESTAMPTZ`, `last_error VARCHAR(500)`
  - `created_at/updated_at/created_by/updated_by`
  - `UNIQUE (tenant_id, id)`
  - CHECK scope/provider pairing: `(provider IN ('ZOOM','YOUTUBE','VIMEO') AND scope='TEACHER'
    AND owner_user_id IS NOT NULL) OR (provider IN ('DYNTUBE','BUNNY') AND scope='TENANT' AND
    owner_user_id IS NULL)`
  - CHECK disconnected consistency (same shape as V57)
  - Partial unique indexes:
    - `(tenant_id, provider, owner_user_id) WHERE scope='TEACHER' AND status <> 'DISCONNECTED'`
    - `(tenant_id, provider) WHERE scope='TENANT' AND status <> 'DISCONNECTED'`
  - Indexes:
    - `(tenant_id, owner_user_id, provider)`: teacher's own page and scheduling lookup
    - `(tenant_id, provider, status)`: admin table
    - `(provider, external_account_id) WHERE status <> 'DISCONNECTED'`: Zoom deauthorization
      lookup (cross-tenant, ADR-018 §5)
- **`class_session`** (live-class-management):
  - add `provider_connection_id UUID NULL` + composite FK
    `(tenant_id, provider_connection_id) → media_provider_connection (tenant_id, id)`
  - add index `(tenant_id, provider_connection_id)`
  - Existing rows stay NULL (fake-provider sessions).
  - `ck_class_session_meeting_provider` stays `('ZOOM')` this phase. MEDIA-INT-04 widens it with
    a new migration.
- **Tenant config** - *superseded: implemented as the `tenant_media_integration_settings` table (§13).* Originally planned as `tenant_config_entry` registry keys
  - `integrations.teacher_providers.zoom_enabled`, `.youtube_enabled`, `.vimeo_enabled`
    (boolean, default true)
  - `integrations.video_host` (`NONE|DYNTUBE|BUNNY`, default `NONE`)

  No credentials ever go in `tenant_config_entry`.
- **Redis (ephemeral):**
  - `oauth:state:{state}` → JSON {tenantId, userId, provider, pkceVerifier, returnHost}, TTL 600s,
    consumed with GETDEL
  - `media:refresh-lock:{connectionId}` SET NX EX 30

No change to payment, ledger, enrollment or audit tables.

## 5. Backend impact

All provider code lives in **`integration-management`**, per the architecture rule that it owns
all third-party credentials. Other domains use its `api` only.

- **Crypto:** extract the AES-GCM logic of `CredentialCipher` into a package-private
  `AesGcmCipher(Supplier<key>)`, then:
  - `CredentialCipher` (payments) keeps `PAYMENT_CREDENTIALS_ENCRYPTION_KEY` with unchanged
    behaviour, and its existing tests must still pass.
  - New `MediaCredentialCipher` uses `MEDIA_CREDENTIALS_ENCRYPTION_KEY`.
- **Config:** `MediaOAuthProperties` (`integrations.oauth.{zoom,google,vimeo}.client-id/client-secret`,
  `integrations.oauth.callback-base-url`), `ZoomWebhookProperties` (secret token),
  `MediaProviderProperties` (per-provider API base URLs so tests can point at WireMock).
- **Domain:** `MediaProviderConnection` entity (TenantOwned, no plaintext accessor),
  `MediaProvider`, `ConnectionScope`, `ConnectionStatus`, `ConnectionDisconnectedBy`.
- **Repository:** `MediaProviderConnectionRepository extends TenantAwareRepository`, plus one
  explicitly named bypass method `findActiveAcrossTenantsByProviderAndExternalAccountId`
  (deauthorization webhook only).
- **Provider clients (adapters, `gateway` package):**
  - `ZoomOAuthClient`/`ZoomApiClient`
  - `GoogleOAuthClient`/`YouTubeApiClient`
  - `VimeoOAuthClient`/`VimeoApiClient`
  - `DynTubeApiClient`, `BunnyStreamApiClient`

  They share an `OAuthProviderClient` interface (authorizeUrl, exchangeCode, refresh, revoke,
  fetchIdentity). They use Spring `RestClient` with timeouts, never log bodies, and map provider
  errors to sanitised messages.
- **Services:**
  - `MediaConnectionService`: teacher self-service list/connect/test/disconnect, admin list/
    disconnect, and tenant-level save/test/disable. All permission and ownership checks live
    here. Audit writes happen in the same transaction.
  - `OAuthConnectFlowService`: state issue/consume (Redis), code exchange, identity fetch,
    persistence, and return-host validation via `TenantLookupApi`.
  - `MediaTokenService`: returns a valid access token for a connection, refreshing under the
    Redis lock. It is package-internal to integration-management.
  - `ZoomLiveClassProviderAdapter`: real implementation of `LiveClassProviderApi` covering
    create/cancel/host-start-url/registrant-join. `FakeZoomLiveClassProviderAdapter` remains, chosen
    only when the fallback flag is set.
- **`api` changes** (internal Java contract, not REST):
  - `LiveClassProviderApi.createMeeting(...)` gains a `hostTeacherUserId` and returns
    `connectionId`.
  - Join and cancel take the `connectionId`.
  - New `getHostStartUrl(connectionId, providerReference)`.
  - New `MediaConnectionApi.findActiveTeacherConnection(teacherUserId, provider)` returning
    status/type only, for live-class-management messaging.
- **live-class-management:**
  - `ClassSessionWriteService` passes the teacher id and persists `provider_connection_id`.
  - `retry-provisioning` widened to recreate the meeting on the current connection.
  - `join` returns the host start URL for the owning teacher and a registrant URL for
    everyone else, and makes the Zoom call outside the read transaction.
  - Mapping to 409 when not connected, not licensed, or the provider is disabled.
- **Webhooks:**
  - New `ZoomWebhookController` at `/api/v1/integrations/webhooks/zoom`: Zoom signature +
    timestamp verification, URL validation challenge, meeting/recording events delegated to the
    existing `LiveClassWebhookProcessingService`, and `app_deauthorized` handling.
  - The existing generic `LiveClassWebhookController` stays for the fake provider in local/test
    only.
- **OAuth callback:** `OAuthCallbackController` at `GET /api/v1/integrations/oauth/{provider}/callback`,
  `permitAll` in `SecurityFilterChainConfig`, protected by the single-use state.
- **Permissions:** new `DomainArea.INTEGRATIONS`, granted in `PermissionCheckServiceImpl`:
  - Tenant Admin: VIEW + CREATE_EDIT.
  - Institute Owner: same as Tenant Admin.
  - Read-only Auditor: VIEW.

  Teacher self-service is ownership-gated (role TEACHER + `owner_user_id == principal`) and not
  a DomainArea grant.
- **Config registry:** add the tenant config keys from §4 to `ConfigPropertyRegistry` with
  validation.

## 6. API contract

**Source of truth: [`docs/api/integrations.md`](../api/integrations.md)**. The contract was reviewed
2026-09-28 via `review-api-contract`. The review changed the draft in four ways:

- The separate `start-link` endpoint is dropped. The existing `POST /class-sessions/{id}/join`
  now returns the Zoom **host start URL** for the course-owning teacher, and a personal
  **registrant URL** for students and staff. The response shape is unchanged.
- The separate `recreate-meeting` endpoint is dropped. The existing
  `POST /class-sessions/{id}/retry-provisioning` is widened to re-provision a PROVISIONED session
  whose host connection is no longer ACTIVE.
- Zoom cloud recordings are **not** served in this phase: `GET /recording` → 404 for real Zoom
  sessions. Zoom's recording links are not short-lived per-viewer URLs, so they violate
  `.claude/rules/security.md`. Recordings are delivered in MEDIA-INT-04 instead.
- The generic `CONFLICT` code is replaced by specific error codes (`PROVIDER_NOT_CONNECTED`,
  `PROVIDER_LICENSE_REQUIRED`, `PROVIDER_DISABLED_BY_TENANT`, `PROVIDER_RECONNECT_REQUIRED`,
  `PROVIDER_CREDENTIALS_INVALID`), so the frontend can show the right call to action.
  `ClassSessionResponse.hostConnectionStatus` is added.

## 7. Frontend impact

- **Teacher (new):** `app/(teacher)/teacher/settings/page.tsx` (settings index) and
  `settings/integrations/page.tsx`. Add a "Settings" entry to `teacher-nav.tsx`.
  - One card per provider (Zoom, YouTube, Vimeo), each showing:
    - Status chip with text and icon: Connected / Needs reconnect / Not connected / Not enabled
      by institute / Unavailable.
    - Account label and last verified time.
    - Connect/Reconnect/Test/Disconnect buttons.
  - Connect calls the API, then `window.location.assign(authorizationUrl)`.
  - Handle `?pending=` (confirm once, see §13) / `?error=` query params with an accessible message, then clean the URL.
  - Disconnect Zoom opens a confirm dialog showing `upcomingSessionCount`.
  - Global "Reconnect needed" banner in the teacher layout when any connection is
    NEEDS_RECONNECT.
- **Teacher live classes:**
  - Schedule form shows an inline notice and a link to Settings → Integrations when Zoom is not
    connected, and handles the new 409 codes.
  - Session detail gets a "Start class" button (calls `join`, opens in a new tab) and a
    "Recreate meeting" action when the session's connection is disconnected.
- **Tenant Admin (new):** `app/(tenant-admin)/tenant-admin/settings/integrations/page.tsx`, plus
  a card on the settings index. Sections:
  1. Teacher providers: toggles for Zoom/YouTube/Vimeo.
  2. Video hosting: DynTube and Bunny forms. Secret fields are write-only ("Key configured" +
     replace), with Test connection and an active-host radio labelled "coming soon".
  3. Teacher connections: a responsive table that becomes cards below `md`, with filter by
     provider/status, and Disconnect with a required reason.
- **Student:** no new pages. The existing join button keeps working with a clear message when
  the teacher's Zoom is disconnected.
- **Public:** `app/(public)/integration-error/page.tsx`, a neutral page for an unresolvable
  OAuth state.
- **Shared:** `lib/api/integrations.ts` (typed client) and Zod schemas for the DynTube/Bunny
  forms. Use the existing QueryStateBoundary and empty/error/permission-denied components.
  Empty-state copy is per section.

## 8. Security impact

- **Secrets:** new env vars:
  - `MEDIA_CREDENTIALS_ENCRYPTION_KEY`
  - `ZOOM_OAUTH_CLIENT_ID/SECRET`, `ZOOM_WEBHOOK_SECRET_TOKEN`
  - `GOOGLE_OAUTH_CLIENT_ID/SECRET`
  - `VIMEO_OAUTH_CLIENT_ID/SECRET`

  All have empty defaults, fail closed, and only placeholders go in `.env.example`. Tokens and
  keys are encrypted at rest, write-only, never logged (add them to log-masking), and never sent
  to the frontend.
- **OAuth:**
  - State is single-use, bound to a user, and stored only in Redis. PKCE is used where
    supported.
  - The callback redirect target is validated against the tenant's own hosts, so there is no
    open redirect.
  - The code exchange and identity fetch are server-side only.
  - Minimum scopes are requested.
- **Authorization:**
  - Teacher self-service is enforced by ownership, and another teacher's connection id → 404.
  - Admin actions need `INTEGRATIONS` CREATE_EDIT.
  - Start-link is only for the session's teacher. Join still requires the existing
    enrollment/tenant/status checks.
- **Link sharing:** per-student Zoom registrant links replace a shared meeting link. The host
  start URL is fetched fresh and never persisted.
- **Webhooks:**
  - Zoom's real signature is verified with a 5-minute timestamp window.
  - Tenant is resolved only from our own data.
  - Deauthorization uses a narrowly named cross-tenant lookup.
- **SSRF:** provider base URLs are fixed configuration. The Bunny `cdnHostname` is validated as a
  hostname pattern (`*.b-cdn.net` or a verified custom hostname) and is **never** used as an
  outbound request target by the backend. Only fixed `api.bunny.net`/`video.bunny.net` are
  called.
- **Audit:** connect/replace/disconnect/deauthorize/provider enable-toggle/video-host change.
  These are not in the mandatory list, but they are security-relevant credential changes, so
  they are logged anyway, consistent with PAY-GW-01.
- **YouTube/Vimeo content** has no access control once public. This phase only connects
  accounts, so MEDIA-INT-02 must use unlisted/private plus domain-restricted embeds (Vimeo) and
  label the weaker guarantee in the UI (ADR-018 §6).

## 9. Tenant impact

- `media_provider_connection` has a NOT NULL `tenant_id`, is filtered by `TenantAwareRepository`,
  and every index leads with `tenant_id` (except the one named deauthorization lookup).
- `owner_user_id` has a composite FK into `tenant_user (tenant_id, id)`, so a connection can
  never belong to another tenant's teacher.
- `class_session.provider_connection_id` has a composite FK, so a session can never use another
  tenant's connection.
- The OAuth callback derives tenant and teacher only from server-issued Redis state, never from
  the request.
- Zoom webhooks resolve the tenant via `provider_reference` → `class_session` (ADR-011).
  Deauthorization is the one cross-tenant lookup, which only ever sets status to DISCONNECTED,
  and it is justified in ADR-018 §5.
- The same real person teaching at two institutes gets two independent connections, one per
  `tenant_user`. Each is authorised separately, and nothing is shared between tenants.

## 10. Tests

**Backend: unit (JUnit)**
- `MediaCredentialCipher`: round trip; fails closed with no key or a wrong key length; the
  payment `CredentialCipher` is unchanged.
- Zoom webhook signature: valid, tampered body, stale timestamp, url_validation response.
- OAuth state: issue/consume, replay rejected, expiry, and return-host validation rejects a
  foreign host.
- Token refresh: refreshes near expiry, lock prevents a double refresh, `invalid_grant` →
  NEEDS_RECONNECT.
- Scope/provider pairing validation; masking; sanitised errors never contain tokens.

**Backend: integration (Spring Boot + Testcontainers Postgres/Redis, WireMock for providers)**
- Teacher connect → callback → ACTIVE connection with encrypted blob. The DB column does not
  contain the plaintext token.
- Callback with a replayed/unknown state → no row, and a redirect to the error page.
- Denied consent → no row.
- Reconnect replaces: the old row is DISCONNECTED and the new row ACTIVE, and the partial unique
  index holds under a concurrent double connect.
- Tenant Admin DynTube/Bunny save: test fails → 400 with nothing stored; blank key keeps the
  stored key; the response never contains the key.
- Schedule a live class with a connected teacher → Zoom create called on that teacher's token,
  and `provider_connection_id` is stored.
- Schedule when not connected / Basic account / provider disabled → 409 with the right code, and
  no session row.
- Start-link: session teacher 200; other teacher 404; student 403/404.
- Join → registrant added and a per-student URL returned; not enrolled → 403/404 as today.
- Disconnect with upcoming sessions → join 409; reconnect the same account → join works;
  Recreate meeting with a different account → new provider_reference.
- `app_deauthorized` webhook → all matching connections DISCONNECTED and audited.
- Audit entries are written for each mutation and contain no secret.
- Migration test: CHECK and unique constraints reject a teacher provider with no owner, a tenant
  provider with an owner, and two active connections.

**Cross-tenant (mandatory)**
- A teacher in tenant A addressing tenant B's connection id (test/disconnect/impact) → 404.
- A Tenant Admin in A listing integrations never sees B's connections; disconnecting B's
  connection id → 404.
- An OAuth state issued for tenant A with a return host of tenant B → rejected.
- A class session in A cannot reference B's connection, rejected by the FK at DB level and by
  the service.
- A Zoom webhook with a meeting id from A cannot mutate B.

**Frontend: Playwright**
- Teacher integrations page: not connected, connected, needs reconnect, not enabled by
  institute, and unavailable states. Connect redirects (mocked), the `?pending=` confirm-once flow, the
  disconnect confirm with impact count, and a keyboard-only flow.
- Tenant Admin integrations: toggles; DynTube/Bunny save success and validation error;
  write-only secret display; teacher-connections table desktop + mobile card view; disconnect
  with a required reason; 403 permission-denied state for a staff user.
- Live classes: schedule blocked with a "Connect Zoom" link; Start class button; Recreate
  meeting.
- Accessibility: status chips include text, icon-only buttons have `aria-label`, toasts use
  `aria-live`.

## 11. Documentation updates

- `docs/adr/ADR-018-media-provider-connections.md` (new; see §12)
- `docs/api/integrations.md` (new: teacher/admin integration endpoints, OAuth callback, Zoom
  webhook)
- `docs/api/live-class-management.md` (join/retry-provisioning deltas, new 409 codes,
  `hostConnectionStatus`, Zoom recordings deferred)
- `docs/architecture/integration-architecture.md` (provider matrix, OAuth flow, credential
  encryption, token refresh)
- `docs/architecture/system-context.md` (Zoom/YouTube/Vimeo per teacher, DynTube/Bunny per
  tenant)
- `docs/requirements/open-decisions.md` (close Zoom link-sharing mechanism and Zoom scheduling
  permission; note the YouTube/Vimeo security posture decision)
- `docs/requirements/specifications/19-zoom-live-classes.md`,
  `27-youtube-vimeo-integrations.md` (per-teacher accounts)
- `docs/ui-ux/screen-map.md`, `docs/ui-ux/information-architecture.md` (Teacher Settings →
  Integrations, Tenant Admin → Integrations)
- `docs/operations` / `.env.example`: new env vars and how to register the Zoom/Google/Vimeo
  OAuth apps (redirect URI, scopes, Google app verification for YouTube scopes)
- `.claude/rules/security.md`: nothing changes, unless ADR-018 decides otherwise

## 12. Change-control items requiring explicit approval (ADR-018)

None of the CLAUDE.md change-controlled areas (tenancy strategy, auth architecture, payment
ledger, enrollment activation, deployment, migration history, approved API contracts) is
changed. However, these architecture decisions need an ADR **accepted before implementation**:

1. **Provider selection:** ADR-008 left the video host unselected. This picks DynTube and Bunny
   Stream as supported tenant video hosts (used in MEDIA-INT-03), and Zoom/YouTube/Vimeo as
   per-teacher providers.
2. **Credential model:** per-teacher OAuth tokens and per-tenant API keys are stored encrypted
   in `media_provider_connection` under a new, separate encryption key. Platform-level OAuth apps
   are registered for Zoom/Google/Vimeo.
3. **Central OAuth callback:** one platform callback host with Redis single-use state, and a
   redirect back to the tenant's host. This is a new unauthenticated endpoint class, similar to
   webhooks.
4. **Zoom link-sharing mechanism:** per-student registrant join links, which means teachers need
   licensed Zoom accounts. This closes the open decision in 19-zoom-live-classes.md.
5. **Cross-tenant deauthorization lookup:** by (provider, external_account_id), which can only
   disconnect. This is an ADR-011-style carve-out.
6. **YouTube/Vimeo security posture** (the open decision in 27-youtube-vimeo-integrations.md):
   these videos are exempt from ADR-008 single-use playback tokens. They must be
   unlisted/private, with domain-restricted embeds where the provider supports it, and the UI
   must state the weaker protection. Paid, protected content should use the tenant video host.
   This must be decided before MEDIA-INT-02.

**Operational dependencies (not code):**
- A Zoom Marketplace user-managed OAuth app, with the deauthorization endpoint required by Zoom.
- A Google Cloud OAuth client. `youtube.upload` in MEDIA-INT-02 needs Google OAuth app
  verification, which can take weeks and should be started early.
- A Vimeo developer app.

## 13. Implementation notes & deviations (2026-09-29)

- **OAuth confirm step (supersedes AC 14):** the callback no longer saves anything. It redirects to
  `/teacher/settings/integrations?pending={id}&provider=`; the page confirms once with
  `POST /teacher/integrations/pending/{id}/confirm`, which saves only for the teacher who started
  it (security review S1/S3). Contract: `docs/api/integrations.md`.
- **Tenant switches (supersedes §4 "Tenant config")** live in `tenant_media_integration_settings`
  (V60), not `tenant_config_entry`, so they are gated by `INTEGRATIONS`.
- **`start-link` and `recreate-meeting` endpoints were not added** (contract review): the
  teacher's `join` returns the host start URL, and `retry-provisioning` recreates the meeting.
- **Zoom recordings** return 404 for real Zoom sessions (deferred to MEDIA-INT-04).
- **Audit vs security log (AC 9):** provider-driven changes (Zoom deauthorization, rejected
  refresh) are security-logged, because the audit log requires a known user actor.
- **Credentials** are wiped on disconnect/replace (S2) and bound to tenant/provider/owner (S4).
- **Not done:** rescheduling a session does not update the Zoom meeting time (join links keep
  working); DynTube's API is unverified (ADR-018 implementation notes).
- **Tests:** backend - `TeacherMediaConnectionIntegrationTest`, `ZoomLiveClassIntegrationTest`,
  `TenantAdminIntegrationsIntegrationTest`, `MediaProviderConnectionSchemaIntegrationTest`, plus unit
  tests (cipher, Zoom signatures, token lock, log masking); frontend - `teacher-integrations`,
  `tenant-admin-integrations`, `live-class-zoom-connection` specs.

- **Bunny.net superseded by MEDIA-INT-01A (2026-09-29).** The Bunny connection now takes only an
  account API key, and each teacher gets their own library - see
  `docs/plans/MEDIA-INT-01A Bunny Account Key and Per-Teacher Libraries.md` and ADR-018
  Amendment 1. The Bunny parts of acceptance criteria 18-19 and the `PUT /bunny` contract above
  describe the original MEDIA-INT-01 design.
