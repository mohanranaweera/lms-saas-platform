# Multi-Tenant Zoom Integration + Embedded AOC Live-Class Player

**Status:** Stage 1 plan approved for implementation planning  
**Date:** 2026-10-12  
**Approval:** Product Owner decision confirmed 2026-10-12; initial host model selected as **Pooled Host (`POOLED_HOST`)** — one connected Zoom account/integration may expose multiple eligible host users as a managed host pool. LMS teacher identity remains separate from Zoom host identity.  
**Branch:** `docs/multi-tenant-zoom-integration`  
**Scope:** Repository audit, gap analysis, current Zoom-platform research, architecture and implementation plan only.  
**Implementation guard:** No backend, frontend, native-player, Flyway migration, API implementation, or existing Zoom-code replacement is included in this stage.

> The architecture plan is approved for the next implementation-preparation stage. The `POOLED_HOST` model must still be validated against the connected Zoom account's account-level user-management scopes, per-host licensing/capability, concurrency rules, server-side ZAK retrieval, Meeting SDK start-on-behalf behavior, Marketplace review status and external-account OBF requirements before production host scheduling is enabled.

---

## 1. Executive summary

The repository already contains a functional first-generation Zoom live-class integration, but it implements a different product model from the required workflow:

- Current model: each LMS teacher connects their **own** Zoom OAuth account; that account hosts the teacher's sessions.
- Requested model: a tenant administrator creates one or more **named tenant-owned Zoom integrations**. Each integration represents one connected Zoom account and synchronizes multiple eligible Zoom host users from that account into a managed **host pool**. LMS teachers are allowed to use the integration, but they are **not permanently mapped to host users**. For each live-class session, the platform allocates an available host user from the selected integration's pool and reserves that host for the session.
- Current join model: the backend returns fresh Zoom `start_url` or personal registrant `join_url` links.
- Requested model: teachers and students use LMS authorization and an embedded Meeting SDK/AOC Player path, without raw Zoom URLs as the normal course UI path.

The existing implementation should therefore be **evolved, not deleted wholesale**. The following are valuable foundations:

- `class_session` as a tenant-isolated live-class aggregate;
- `integration-management` as the sole owner of provider credentials and webhooks;
- composite tenant FKs and tenant-aware repositories;
- two-phase persistence around outbound Zoom calls;
- Redis-backed single-use OAuth state and encrypted credential storage;
- Zoom webhook signature validation and an idempotency ledger;
- role/enrollment access guards;
- Tauri/WebView2 AOC Player security/session layers and capture-deterrence capabilities.

The required architecture is a modular-monolith extension with:

1. a tenant-owned `ZoomIntegration` aggregate in `integration-management`;
2. relational allowed-teacher rows that control which LMS teachers may use an integration;
3. tenant/integration-scoped external Zoom-user synchronization;
4. an explicit host-pool membership/configuration model for eligible Zoom users;
5. a race-safe `ZoomHostAllocator`/`ZoomHostResolver` that selects and reserves an available host based on capability, concurrency, health and schedule;
6. provider meeting creation under the **allocated Zoom host user**, not automatically `/users/me`;
7. a server-issued, short-lived Meeting SDK host/participant authorization contract;
8. a dedicated AOC live-class player route/engine rather than exposing raw meeting URLs;
9. integration-specific Media Library tabs for completed Zoom recordings;
10. participation-derived, occurrence-aware attendance with server-side reconciliation; and
11. verified, replay-resistant, idempotent webhook processing plus a staged migration from existing teacher-owned connections.

Recording and attendance remain first-class outcomes of the integration. A completed recording is indexed under the exact named Zoom integration and meeting/occurrence that produced it, and participation events are reconciled to LMS students and class-session occurrences before attendance is marked. Neither feature may depend solely on frontend SDK events or a stable provider URL.

The approved initial host model is **Pooled Host (`POOLED_HOST`)**. A tenant connects one Zoom account through a named integration and synchronizes the account's eligible host users. Tenant Admin explicitly enables selected Zoom users as host-pool members. When a live class is scheduled, the platform allocates one eligible host from that pool, reserves the host for the meeting window, creates the Zoom meeting for that host user, and persists the assigned host snapshot on the LMS session/meeting. The LMS teacher assigned to the class remains a separate LMS identity and does not permanently own or map to the Zoom host user.

For the teacher-start experience, the platform must validate Zoom's currently supported Meeting SDK **start-on-behalf** model. Current Zoom Meeting SDK documentation states that a non-logged-in SDK user can host a scheduled meeting on behalf of another Zoom user when authenticated with that host user's ZAK, and starting a meeting requires SDK role `1` plus the host's ZAK. Therefore the intended flow is: teacher clicks **Start Class** → LMS authorizes the teacher for the class → backend loads the already-reserved Zoom host → obtains that host user's short-lived ZAK server-side → issues the Meeting SDK host authorization → AOC Player starts the meeting under the allocated Zoom host identity. This must be proven in Wave 0 using a development Zoom account before schema implementation proceeds.

The architecture must not share Zoom passwords, dynamically transfer paid licenses to evade licensing rules, expose ZAK/refresh tokens to normal frontend storage, or misrepresent the LMS teacher as the provider host. Each pooled host user must independently satisfy Zoom's current host entitlement/license and concurrency requirements.

---

## 2. Existing implementation findings

### 2.1 Existing domain and persistence

| Area | Current finding | Reuse assessment |
|---|---|---|
| Live-class aggregate | `class_session` contains tenant, course, teacher, lesson, schedule, status, provider status/reference and optional `provider_connection_id` | Reuse aggregate; additive evolution only |
| Recordings | `class_session_recording` is tenant-scoped and linked by composite FK | Reuse; extend for provider recording metadata and access policy later |
| Webhook ledger | `class_session_provider_event` records provider/event IDs and has a unique idempotency constraint | Reuse concept; likely extend event identity/processing fields for real Zoom events |
| Provider connection | `media_provider_connection` stores encrypted OAuth/API credentials and currently supports teacher-scoped Zoom | Reuse credential/connection lifecycle primitives, but add a distinct tenant-owned Zoom-integration relationship rather than overloading the current teacher-owner constraint |
| Named integration card | `tenant_media_integration` and `tenant_media_integration_teacher` already model named tenant cards and allowed teachers for YouTube | Reuse pattern, not the YouTube table blindly; Zoom requires different connection/user/host entities |
| Tenant settings | `tenant_media_integration_settings.zoom_enabled` exists | Reuse as a coarse tenant switch, but do not treat it as the integration selector or source of host authorization |
| Tenant isolation | `tenant_id`, composite foreign keys and tenant-aware repository patterns are established | Mandatory for every new Zoom table and query |

### 2.2 Existing Zoom OAuth and provider code

The current Zoom path is implemented in `integrationmanagement`:

- `ZoomOAuthClient` builds a Zoom OAuth authorization URL with state and PKCE, exchanges authorization codes, refreshes rotating tokens, fetches `/users/me`, and revokes tokens.
- `OAuthConnectFlowService` binds Redis state to tenant, initiating user, provider, PKCE verifier and scope; consumes state once; parks encrypted credentials in a single-use pending record; requires the initiating teacher to confirm; and builds a tenant-derived return URL.
- `MediaCredentialsCodec`/`MediaTokenService` provide encrypted-at-rest credentials and token refresh locking.
- `ZoomMeetingClient` creates scheduled meetings for `/users/me`, fetches `start_url`, registers attendees, and deletes meetings.
- Under `POOLED_HOST`, this `/users/me` ownership assumption is a migration target: Wave 0 must verify the current Zoom API/scopes for creating and managing meetings for an allocated synchronized Zoom host user, and the provider client must use the reserved host identity rather than the authorizing admin by default.
- `ZoomLiveClassProviderAdapter` requires an active licensed teacher-owned connection, creates meetings, returns a host URL to the owner and a registrant URL to other participants, and marks a connection for reconnect after authentication failure.
- `LiveClassProviderRouter` selects the real Zoom adapter or the local/test fake fallback.

These components are reusable at the provider-adapter level, but the owner/scope assumptions are not reusable unchanged. In particular, current code assumes:

- Zoom is teacher-scoped (`scope=TEACHER`, `owner_user_id` required by the current schema check);
- one active Zoom connection per teacher/provider;
- scheduling uses the course teacher's own connection;
- `accountType=LICENSED` is sufficient host validation;
- a Zoom `start_url` or registrant `join_url` is the participant authorization product;
- no Zoom user catalog, host-pool membership model or race-safe host reservation model exists;
- no Meeting SDK signature/ZAK/OBF authorization service exists.

### 2.3 Existing live-class service behavior

`liveclassmanagement.service.ClassSessionService` and `ClassSessionSchedulingService` already demonstrate the correct transaction pattern:

1. validate local access and provider readiness;
2. persist a pending session;
3. commit before the external provider call;
4. call Zoom outside the database transaction;
5. persist provisioned/failed provider outcome in a separate transaction;
6. retry provisioning without deleting the LMS session;
7. delete a cancelled external meeting best-effort after the cancellation commits.

The access guard currently enforces:

- teacher ownership through a live course lookup rather than trusting only the stored display teacher ID;
- active enrollment for students;
- staff permissions for tenant-wide management;
- tenant-scoped lookup and anti-enumeration 404 behavior.

This boundary should remain. Zoom-specific authorization belongs behind `integrationmanagement.api` interfaces, while course/session ownership and enrollment remain in `liveclassmanagement` and `enrollmentmanagement` APIs.

### 2.4 Existing webhook behavior

There are two webhook paths:

1. `LiveClassWebhookController` for the generic/fake provider contract;
2. `ZoomWebhookController` for real Zoom.

`ZoomWebhookController` currently:

- verifies `x-zm-signature` over the raw request body and timestamp before parsing;
- rejects missing/invalid/stale signatures;
- answers `endpoint.url_validation` with HMAC-SHA256;
- handles `app_deauthorized` by disconnecting matching Zoom user connections;
- resolves meeting events through the platform's own `class_session.provider_reference`, not a payload tenant ID;
- records event idempotency using `class_session_provider_event`.

This is a strong foundation, but it currently records meeting events without applying authoritative meeting/attendance state, accepts a single configured webhook secret rather than selecting a secret per tenant integration, and derives an event key from event/timestamp/meeting ID rather than persisting the provider's strongest available event identity. Those gaps must be addressed before production attendance or recording processing.

### 2.5 Existing frontend

Existing frontend surfaces include:

- tenant-admin integration settings;
- teacher-owned integration settings;
- class-session create/edit forms;
- tenant-admin, teacher and student live-class pages;
- typed React Query hooks in `frontend/src/lib/api/integrations.ts` and `class-sessions.ts`;
- Playwright tests for integration and live-class flows.

The current integration UI is teacher-connection oriented. It does not yet provide:

- a tenant-admin list of multiple named Zoom integration cards;
- allowed-teacher create/edit selection for Zoom;
- synchronized Zoom-user catalog;
- Zoom host-pool membership/capability management;
- connection health per named integration;
- a Meeting SDK/AOC Player live session route;
- player-required versus web-allowed access policy.

The existing frontend must remain a presentation layer. Tenant, course, enrollment, host, meeting and player authorization must stay server authoritative.

### 2.6 Existing AOC Player

`native-player` is a Tauri 2 application using a TypeScript UI, Rust security/session commands, WebView2, strict navigation/CSP controls, deep-link launch, installation pairing, server exchange and heartbeat/revocation handling. Existing engines include file, HLS, YouTube and embed/DynTube paths.

Reusable capabilities:

- no LMS JWT in the native player;
- launch/exchange authorization controlled by Rust/server;
- stop/revoke handling;
- watermark overlay/band support;
- capture-protection status and refusal policy;
- protected navigation and production DevTools restrictions.

A live Zoom Meeting SDK should be a separate engine or a dedicated live-session player surface. The existing video playback descriptor must not be overloaded with meeting credentials or meeting IDs without an ADR and a distinct contract.

---

## 3. Reference-video workflow findings

The requested functional workflow remains compatible with the existing named-card UX used for video integrations, but the Zoom host model is now account-centric and pool-based:

```text
Tenant Admin
  -> Integrations
  -> Add Integration
  -> name, description, Zoom type, allowed teachers
  -> create card (NOT CONNECTED)
  -> connect
  -> Zoom OAuth authorization
  -> callback + confirmation
  -> CONNECTED / health state
  -> synchronize Zoom account users
  -> enable eligible users as Host Pool members
  -> configure pool priority/capacity metadata where supported

Teacher/Admin
  -> Course -> Module -> Session
  -> session type LIVE_ZOOM
  -> choose LMS teacher + Zoom integration
  -> platform allocates/reserves an eligible pooled host
  -> provision Zoom meeting for allocated host user

Teacher
  -> assigned LMS live session
  -> Start Class
  -> backend verifies teacher/session + reserved host
  -> backend obtains allocated host's current start authorization/ZAK
  -> AOC Player / Meeting SDK starts meeting under pooled host identity

Student
  -> enrolled course -> live session
  -> backend enrollment/window/tenant check
  -> short-lived participant grant
  -> AOC Player / Meeting SDK

Zoom
  -> verified webhook
  -> idempotent meeting/participant/recording events
  -> LMS attendance/recording metadata
```

The workflow must not copy Klass branding, proprietary assets, CSS, source code or visual identity. It should use the existing LMS design system and role-group routes.

The core separation is:

```text
LMS Teacher != Zoom Host Pool User != Zoom Integration
```

An LMS teacher is authorized to operate the class. The allocated Zoom host user is the actual provider host identity and licensing resource. The Zoom integration is the tenant-owned OAuth/account connection.

---

## 4. Requirements

### 4.1 Tenant-admin integration management

A tenant admin must be able to:

- create multiple Zoom integration cards in one tenant when the product allows multiple connected accounts;
- treat each integration as one connected Zoom account with its own independent host pool;
- edit name and description;
- choose `ALL_TEACHERS` or a selected relational teacher set;
- connect/reconnect each card independently through Zoom OAuth;
- see connection status, connected Zoom identity/account, last health check and last error;
- synchronize eligible Zoom users for that integration;
- see each synchronized user's host capability/license snapshot and last verification time where available;
- enable/disable eligible Zoom users as host-pool members;
- optionally configure pool priority/order and approved concurrency override only where supported by provider evidence;
- inspect current/future host reservations and scheduling conflicts;
- disconnect/delete a card without deleting financial/academic history or provider audit history;
- see actionable connection, licensing, host-capacity and allocation errors.


### 4.2 Domain separation

The design must keep these concepts separate:

- LMS teacher identity;
- tenant-owned Zoom integration/card;
- external Zoom account identity;
- synchronized Zoom external user identity;
- enabled Zoom host-pool member/resource;
- Zoom host license/meeting capability;
- host reservation/allocation;
- LMS course live session;
- Zoom meeting/occurrence;
- AOC player session/grant.

No implementation may equate `LMS teacher == Zoom host user == Zoom integration`.

An LMS teacher may operate a live class without permanently owning the Zoom host identity. The provider host is selected from the integration's eligible host pool and remains the actual Zoom meeting host for that session.


### 4.3 Allowed teachers

Allowed-teacher rows mean only that an LMS teacher may use the integration. They do not prove ownership of a Zoom account or license. An empty mapping must have one explicit, documented meaning. Recommended meaning: `ALL_TEACHERS` represented by a mode column, not ambiguous absence; if the existing card convention uses empty rows, the API and database invariant must still distinguish “all” from “not configured”.

### 4.4 Live sessions

A live session must persist an explicit integration reference and the **allocated pooled host identity/reservation snapshot**. It must not infer the integration from tenant alone or use a client-supplied Zoom meeting/host ID. It must support create, update/reschedule, cancel, retry, start and join flows with safe external consistency handling.

For Release 1:

- the normal scheduling form selects the LMS teacher and Zoom integration;
- the backend, not the browser, allocates the Zoom host;
- the allocation is based on enabled host-pool membership, current capability/health, schedule overlap and verified concurrency capacity;
- the allocated host is reserved race-safely before the provider meeting is provisioned;
- the same LMS teacher may use different Zoom host users for different sessions;
- the same host user may serve different LMS teachers at non-conflicting times;
- the teacher does not supply or choose an arbitrary Zoom host user in the standard flow;
- a reschedule must revalidate the reservation and may reallocate only through an explicit audited operation if the original host can no longer satisfy the new schedule.


### 4.5 Recording Media Library

Every named Zoom integration must have its own Media Library tab. The tab is created from the tenant-owned integration identity, not from a user-controlled provider ID. It must display recordings imported from meetings created or synchronized through that integration, including:

- recording thumbnail/provider icon and recording type;
- title/topic and associated course, module, lesson and class session;
- meeting type and occurrence label where applicable;
- recording date/time, duration and processing/access status;
- search, pagination and empty/loading/error/permission-denied states;
- attach-to-session or lesson actions only where the caller has the required tenant/course permission;
- secure playback through an LMS authorization grant, never a raw long-lived Zoom recording URL.

The overall Media Library may also retain existing Uploads, YouTube, DynTube and other provider tabs. Zoom integration tabs are tenant-scoped and must remain stable when a connection is disconnected so historical recordings do not disappear. A deleted/disconnected integration should be rendered as archived/read-only unless an explicit approved migration moves future ownership; historical recording provenance must remain unchanged.

### 4.6 Participation-derived attendance

The system must support marking attendance from Zoom participation. For each class-session occurrence, it must reconcile verified Zoom participant join/leave/report data to enrolled LMS students and produce an auditable attendance result containing at minimum:

- tenant, course, class session and occurrence;
- LMS student identity;
- provider participant identifiers and normalized identity evidence;
- first joined time, last left time, total participation duration and reconnect count;
- attendance decision (`PRESENT`, `PARTIAL`, `ABSENT`, `UNRESOLVED`, or the approved equivalent);
- policy version/threshold used and source event/reconciliation timestamps;
- manual override actor, reason and before/after values when staff changes the result.

Participation must not be marked solely from a browser callback or unverified display name. Webhooks, provider participant/report APIs and server-side player-session evidence must be reconciled idempotently. Attendance policy must support configurable minimum duration/percentage, grace period, late join and early leave behavior, with occurrence-level results for recurring meetings and webinar sessions.

### 4.7 Supported Zoom session types

The provider abstraction must support all required Zoom session types without collapsing their different capabilities:

- **Standard scheduled meeting**;
- **Recurring meeting series**, including fixed-time and no-fixed-time series where the provider permits them, with occurrence-level identity, schedule, cancellation and attendance/recording association;
- **Zoom Webinar**, including webinar-specific host/panelist/attendee roles, registration/access policy and participant-report limitations.

The selected session type must be stored in the LMS/provider contract and validated against the connected integration's granted scopes, account entitlement, Meeting SDK support and tenant policy. A recurring series must not be treated as one attendance or recording row: each occurrence is independently linked to the LMS class session/occurrence. Webinar-specific restrictions must produce actionable validation errors rather than being silently treated as meetings.

### 4.8 AOC Player

For the expected Advance Online Class policy, default `zoomMeetingAccessMode` is `PLAYER_REQUIRED`. The normal student course UI must not expose a raw Zoom join URL. A browser/web fallback may be separately configured only after security and compatibility review.

---

## 5. Constraints and non-goals

- Keep the modular monolith; no Zoom microservice.
- Do not create a separate deployment per tenant.
- Do not trust tenant IDs, user IDs, meeting IDs or host IDs from ordinary frontend input.
- Do not store refresh tokens, access tokens, ZAK, OBF, SDK secret or webhook secret in plaintext.
- Do not log or audit secret values.
- Do not hold a database transaction open over Zoom HTTP calls.
- Do not use a raw `start_url` as the student authorization mechanism.
- Do not promise perfect screenshot prevention in browsers or WebViews.
- Do not implement dynamic license reassignment, credential sharing or host-user impersonation as a way to bypass Zoom licensing terms.
- Do not treat an LMS teacher as the Zoom host identity; the allocated host-pool member remains the provider host.
- Do not expose host-pool ZAK tokens to query strings, persistent browser storage or ordinary frontend APIs.
- Do not activate attendance solely from frontend Meeting SDK events; webhooks/REST reconciliation remain authoritative.
- Do not edit V1–V83 migrations; all schema changes are additive migrations after approval.
- Do not remove teacher-owned Zoom implementation until a replacement path, migration and rollback plan exist.
- Do not implement backend and frontend in the same wave unless a later request explicitly approves full-stack implementation.

---

## 6. Zoom platform assumptions and current research

The following findings come from current official Zoom Developer documentation reviewed on 2026-10-12. Exact scope names and Marketplace configuration must be revalidated immediately before implementation because Zoom may change granular scope labels.

### 6.1 OAuth app type

Use a Zoom **General OAuth app** for tenant administrators to authorize external Zoom accounts. Do not use deprecated JWT-app guidance and do not default to Server-to-Server OAuth for tenant-owned external accounts.

Zoom's current end-user-auth guidance distinguishes:

- user-level scopes, which authorize an individual user's resources;
- account-level/admin scopes, which require the authorizing user to hold the corresponding Zoom role permissions.

The planned app should request the minimum current scopes needed for the approved product path. The final scope matrix must be recorded in the setup documentation and verified against the live Marketplace scope catalog.

Likely capability groups to validate:

- read current authorized Zoom user/account identity;
- create/update/delete meetings for the selected host user;
- read/list account users for the synchronized catalog, if the tenant's authorization level permits it;
- retrieve a host user's ZAK where the approved host start flow requires it;
- read meeting/participant/reporting data required for attendance and recording reconciliation;
- receive meeting and deauthorization webhook events.

If account-level user synchronization cannot be granted under the authorizing tenant admin's Zoom role/scopes, `POOLED_HOST` scheduling must remain disabled for that integration until the tenant reauthorizes with the required Zoom account/admin permissions. Release 1 must not fall back to unverified manually typed host IDs.

### 6.2 OAuth security and lifecycle

Current Zoom OAuth documentation supports:

- authorization-code flow;
- `state`;
- PKCE with S256;
- refresh-token grant returning a new access token and a new refresh token.

The existing Redis state flow is suitable as a base. The new state must additionally bind:

- tenant ID;
- integration ID;
- initiating admin ID;
- provider/app configuration version where needed;
- nonce/PKCE verifier;
- expiration and one-time consumption.

The callback must never derive tenant or integration from a user-controlled redirect parameter. After code exchange, the result should remain pending until the initiating tenant admin confirms it, preserving the existing forwarded-consent protection.

Token rotation must replace the stored refresh token atomically under the existing per-connection lock. Authorization revocation/deauthorization must transition only the affected integration(s), not all tenants.

### 6.3 Meeting SDK authorization

Zoom Meeting SDK authorization uses a server-generated JWT signature. Current web documentation shows a signature payload containing the SDK app key, meeting number, role, `iat`, `exp` and `tokenExp`.

The signature service must be server-side and accept only an LMS live-session identity. It must resolve the meeting, tenant, caller role, enrollment/teacher authorization and integration/host state. It must never accept arbitrary meeting number, role, passcode, ZAK or OBF from the browser.

Current Zoom guidance states:

- joining/starting as a Zoom user uses JWT plus ZAK;
- joining a meeting inside the app owner's account as a participant can use JWT only;
- joining a meeting outside the app owner's account requires JWT plus OBF, and an associated user with a ZAK must already be in the meeting;
- the OBF external-account path requires Zoom review and an approved/unlisted/otherwise-authorized distribution route from 2026-03-02 for external meetings.

Therefore the selected Meeting SDK path is a hard architecture gate, not a late implementation detail. The final player contract must be based on Zoom's current approved flow for the exact tenant/account/host relationship.

### 6.4 Webhooks

Current Zoom webhook guidance requires:

- `x-zm-request-timestamp`;
- `x-zm-signature = v0=HMAC-SHA256(secretToken, "v0:" + timestamp + ":" + rawBody)`;
- timestamp freshness validation;
- constant-time comparison;
- endpoint URL validation using HMAC-SHA256 over `plainToken`.

The existing verifier follows this pattern. The planned implementation must add robust event identity/replay handling and integration-specific secret resolution where the Marketplace configuration permits it.

Candidate events to validate against the current Zoom catalog:

- `meeting.started` / `meeting.ended`;
- `meeting.participant_joined` / `meeting.participant_left`;
- recurring-meeting occurrence start/end/cancellation events where available;
- webinar started/ended and webinar participant/panelist events where available;
- `recording.completed` and recording-processing updates where available;
- app deauthorization/revocation.

The implementation must confirm the exact event names and payload differences for meetings versus webinars. Attendance and recording handlers must branch on the normalized `session_type`/occurrence model rather than assuming every payload is a standard meeting payload.

Webhook requests must be acknowledged quickly, then process durable event state asynchronously where provider timing requires it. Duplicate and stale events must be safe no-ops.

### 6.5 External-account and Marketplace gate

Before implementation begins, the team must confirm with Zoom documentation/Marketplace or Zoom ISV support:

1. whether the General OAuth app can be used for all intended external tenant accounts;
2. whether account-level scopes require each tenant admin to be a Zoom account admin;
3. whether Meeting SDK embed must be enabled on the app;
4. whether the app must be submitted for review or may be unlisted;
5. the exact OBF issuance path and scope;
6. how OBF/ZAK authorization works for the chosen Web/Component View and AOC WebView2 environment;
7. whether the selected host/user model is allowed for meetings created through the tenant authorization;
8. whether webhook subscriptions are account-level, user-level, or app-level for this app type.

No production claim should be made until these questions have written answers in `docs/integrations/zoom/`.

---

## 7. Licensing and compliance analysis

The business requirement is to reuse a **single connected Zoom account/integration with multiple eligible host users** across many LMS teachers. This is a resource-pooling model, not teacher-to-host identity mapping.

### 7.1 Prohibited implementation

Do not implement any of the following as a workaround:

- dynamically moving one paid Zoom license between distinct Zoom users in order to evade licensing requirements;
- sharing Zoom usernames/passwords with LMS teachers;
- persisting a reusable host ZAK as if it were a password or long-lived credential;
- presenting the LMS teacher as the provider host when another Zoom user actually owns the meeting;
- exceeding a host user's verified concurrency limit;
- using alternative-host, co-host or OBF functionality as a hidden license-sharing mechanism;
- silently allocating an inactive, unlicensed or unhealthy Zoom user;
- silently changing an already-provisioned meeting's host without an explicit audited reallocation/reprovisioning flow.

### 7.2 Approved model — Pooled Host

The selected initial implementation model is **Pooled Host (`POOLED_HOST`)**.

Each tenant-owned Zoom integration represents one authorized Zoom account. The platform synchronizes Zoom users from that account, and Tenant Admin explicitly enables eligible users as host-pool members. LMS teachers are permitted to use the integration through the existing allowed-teacher policy, but there is no required permanent teacher-to-Zoom-user mapping.

For each live-class session:

1. the LMS teacher is resolved from trusted course/session context;
2. the selected Zoom integration is validated;
3. the host allocator loads enabled pool members for that integration;
4. inactive, stale, incapable or unavailable hosts are excluded;
5. verified provider concurrency and overlapping reservations are evaluated;
6. one host is selected deterministically;
7. the host is race-safely reserved for the class window;
8. the Zoom meeting is created for that exact provider host user;
9. the LMS meeting/session stores the allocated host identity and capability snapshot;
10. at class start, the backend revalidates the host and obtains the host's current Meeting SDK start authorization/ZAK;
11. the authorized LMS teacher operates the class through AOC Player while Zoom continues to treat the allocated pool user as the actual provider host.

A recommended deterministic selection order is:

```text
eligible + enabled
-> current capability valid
-> capacity available
-> no forbidden overlap
-> lowest current utilization
-> configured priority
-> least recently assigned
-> stable ID tie-break
```

The exact final ordering may be adjusted during implementation, but it must be deterministic, testable and safe under concurrent scheduling requests.

### 7.3 Start-on-behalf validation

Current Zoom Meeting SDK documentation states that:

- starting a meeting requires SDK role `1` and the actual host user's ZAK;
- a non-logged-in SDK user can host a scheduled meeting on behalf of another Zoom user when authenticated with that host user's ZAK;
- scheduling/managing meetings is performed through the Meeting API rather than directly in the Meeting SDK.

This is the technical basis for the desired teacher experience:

```text
LMS Teacher
  -> Start Class
  -> LMS authorization
  -> load reserved Zoom host
  -> server retrieves host ZAK
  -> role-1 Meeting SDK authorization
  -> AOC Player starts meeting
  -> Zoom host identity remains the reserved pool user
```

Wave 0 must prove this flow end-to-end using a dedicated development Zoom account with at least two eligible host users before database implementation proceeds.

### 7.4 Licensing and concurrency

Each host-pool member is an actual Zoom user and must independently satisfy the provider requirements for hosting the meeting type requested. The application must not assume all synchronized users are host-capable.

The platform must maintain a provider-verified or conservatively configured concurrency capacity per pool member. If capacity cannot be determined reliably, default to the lowest safe supported capacity and fail closed.

The pool improves utilization by allocating different classes across different eligible host users. It does not multiply one user's concurrency or transfer one user's paid entitlement to another user.

### 7.5 Future strategies

Keep the host-strategy abstraction extensible, but only `POOLED_HOST` is enabled for Release 1.

Reserved strategies may include:

- `MAPPED_TEACHER_HOST` (future optional strategy only; not Release 1);
- `FIXED_APPROVED_HOST`;
- `ALTERNATIVE_HOST`;
- `CO_HOST`.

No strategy may silently fall back to another without an explicit approved policy and audit trail.

The `POOLED_HOST` model must be verified against current Zoom General OAuth/admin scopes, account-level user visibility, meeting creation for arbitrary authorized account users, Get User Token/ZAK access, Meeting SDK start-on-behalf behavior, provider concurrency rules, Marketplace review status and external-account OBF requirements before production host scheduling is enabled.

---

## 8. Proposed domain model

The following names are conceptual and must be aligned to existing package conventions during the approved backend wave.

### 8.1 `zoom_integration`

Tenant-owned named integration card:

- `id` UUIDv7;
- `tenant_id` not null;
- `name`, `description`;
- `provider = ZOOM`;
- `status` (`CREATED`, `CONNECTING`, `CONNECTED`, `TOKEN_EXPIRED`, `AUTHORIZATION_REVOKED`, `ERROR`, `DISCONNECTED`);
- `zoom_account_id` and connected Zoom user identity (masked in API views);
- `connected_at`, `last_verified_at`, `last_sync_at`, `last_error`;
- `created_by`, `updated_by`, `deleted_at`, `deleted_by` or a forward-only lifecycle equivalent;
- optimistic version.

A disconnected/deleted integration remains as history because class sessions and audit records refer to it.

### 8.2 `zoom_integration_allowed_teacher`

Relational allowed-teacher mapping:

- `tenant_id`;
- `integration_id`;
- `teacher_user_id`;
- unique `(tenant_id, integration_id, teacher_user_id)`;
- composite FKs to tenant-owned integration and tenant user;
- tenant-leading index.

The selected “all teachers” mode should be explicit in the integration row or a policy table. The API must not make an empty set ambiguous.

### 8.3 `zoom_external_user`

Minimal synchronized external user representation:

- `id` UUIDv7 or project-standard identifier;
- `tenant_id`;
- `integration_id`;
- `zoom_user_id` opaque provider ID;
- email and display name;
- status (`ACTIVE`, `INACTIVE`, `DELETED`, `UNKNOWN`);
- user type/license/capability snapshot required for host eligibility;
- account relationship/role metadata only where required;
- `last_synced_at`;
- `capability_verified_at`;
- provider metadata required for capability validation only;
- unique `(tenant_id, integration_id, zoom_user_id)` and tenant-leading indexes.

Do not store Zoom passwords or unnecessary profile data.

A synchronized external user is not automatically a host-pool member. Tenant Admin must explicitly enable it or an approved migration must do so.


### 8.4 `zoom_host_pool_member`

Explicit host-pool membership/configuration for synchronized Zoom users:

- `id`;
- `tenant_id`;
- `integration_id`;
- `zoom_external_user_id`;
- `enabled`;
- optional `priority`;
- provider-derived host-capability/license snapshot reference or validation version;
- configured concurrency override only when explicitly approved and never above a known provider limit;
- `last_eligible_at`, `last_ineligible_reason`;
- `created_by`, `updated_by`, timestamps;
- unique `(tenant_id, integration_id, zoom_external_user_id)`;
- composite FKs ensuring same tenant and integration.

A pool member is a provider hosting resource. It is not owned by one LMS teacher.

Disabling a pool member prevents new allocations but must not rewrite historical meeting ownership. Existing future reservations require an explicit reallocation/cancellation workflow.

### 8.5 `zoom_host_reservation`

Race-safe reservation of a pooled host for an LMS live session:

- `id`;
- `tenant_id`;
- `integration_id`;
- `host_pool_member_id`;
- `zoom_external_user_id`;
- `class_session_id`;
- nullable provider meeting row reference until provisioning completes;
- `reserved_from`;
- `reserved_until`;
- `status` (`PENDING`, `RESERVED`, `PROVISIONED`, `RELEASED`, `CANCELLED`, `FAILED`, or project-approved equivalent);
- `capacity_slot` or equivalent when verified host concurrency is greater than one;
- allocation strategy/version;
- allocation correlation/idempotency key;
- `created_at`, `released_at`, failure reason;
- tenant-leading indexes and uniqueness/locking rules needed to prevent over-allocation.

The implementation must make overlapping allocation race-safe. A plain "find available then insert" read without locking/constraint protection is insufficient.

### 8.6 `zoom_meeting` and `zoom_meeting_occurrence`

Provider meeting projection owned by integration-management or a carefully bounded live-class provider submodel:

- `tenant_id`;
- `class_session_id` composite FK;
- `integration_id` composite FK;
- `host_pool_member_id` and synchronized external-user reference;
- actual provider host Zoom user ID;
- host strategy = `POOLED_HOST`;
- reservation ID;
- host capability/concurrency snapshot and allocation timestamp;
- external meeting ID and UUID (opaque, never used as the student authorization boundary);
- `session_type` (`MEETING` or `WEBINAR`);
- `meeting_structure` (`SINGLE` or `RECURRING_SERIES`);
- topic, start/end/duration/timezone snapshot;
- safe meeting/webinar configuration snapshot;
- provider-created/updated timestamps;
- cancellation/deletion/state synchronization fields;
- correlation/idempotency metadata.

For recurring series in a later release, `zoom_meeting_occurrence` must store the provider occurrence ID/UUID, scheduled window, actual start/end, occurrence status, cancellation status and the exact LMS class-session occurrence association. Each occurrence must either retain its assigned pooled host or be explicitly reallocated/reprovisioned under an approved policy.

The existing `class_session.provider_reference` can remain the lookup key during migration, but a dedicated row is preferred for host/integration/meeting lifecycle data. Provider meeting IDs, UUIDs, occurrence IDs and webinar IDs remain server-side opaque identifiers.

### 8.7 `zoom_oauth_state` / pending authorization

Use the established Redis single-use state for short-lived CSRF state and a durable or Redis pending record for the exchanged encrypted token set. If a durable record is needed for operational recovery, retain only encrypted credentials and non-sensitive metadata.

### 8.8 `zoom_webhook_event`

Durable idempotency ledger should contain:

- tenant/integration if resolvable;
- provider event identity and event type;
- raw-body hash, not necessarily raw body;
- received timestamp and provider timestamp;
- signature validation result;
- processing status and retry count;
- resolved class session/meeting ID;
- unique provider/event identity constraint;
- tenant-leading index for authorized admin inspection.

Malformed/unresolvable events require a clearly documented platform-scope exception, matching the existing webhook ledger rationale.

### 8.9 `zoom_recording`

Recording metadata must be tenant-owned and linked to the exact Zoom integration, meeting, occurrence and LMS class session:

- `tenant_id`;
- `integration_id`;
- `zoom_meeting_id` and nullable `occurrence_id`;
- `class_session_id` and nullable lesson/material association;
- provider recording ID/file ID/type/format/status;
- topic/title, recording start time, duration, size and processing timestamps;
- thumbnail/metadata references where safe;
- import/synchronization state and failure reason;
- access policy and soft-archive fields.

The Media Library query contract must require the trusted tenant context and an integration ID resolved through that tenant. A recording may appear in exactly one integration tab based on provider provenance; it must not be duplicated into another tab merely because an admin changes a label. If a recording is manually attached to another lesson/session, its provider integration provenance remains unchanged.

The backend must issue a short-lived recording playback grant after checking tenant, role, course/session access and recording policy. It must not expose a raw long-lived Zoom recording URL or provider download token to the normal frontend.

### 8.10 `zoom_meeting_participant` and attendance projection

Participation data must be tenant-owned and linked to the LMS class-session occurrence, not only to a meeting ID. Use provider IDs as opaque lookup fields with tenant/integration constraints. Recommended separation:

- `zoom_meeting_participant`: immutable or append-only normalized join/leave observations, provider event identity, provider participant/SDK identifiers, display/email evidence, timestamps and source;
- `zoom_attendance_result`: the current occurrence-level derived decision for an LMS student, policy version, duration/percentage, status, reconciliation timestamp and manual-override audit reference.

Raw observations must not be overwritten when a later webhook/report reconciliation changes the derived result. Duplicate webhook delivery must not create duplicate observations. A participant whose identity cannot be safely matched to an LMS student remains `UNRESOLVED` for review and must never be assigned to a student solely from an untrusted display name.

---

## 9. Database migrations

No migration is created in Stage 1. The approved implementation will require additive migrations, likely in this sequence:

1. add tenant-owned Zoom integration card table and lifecycle/status constraints;
2. add allowed-teacher policy table and explicit all/selected mode;
3. add integration-aware credential linkage and a migration bridge from teacher-owned Zoom connections;
4. add synchronized external Zoom user table;
5. add `zoom_host_pool_member`;
6. add `zoom_host_reservation` with race-safe allocation constraints/indexes;
7. add meeting projection/allocated-host snapshot fields and composite tenant FKs;
8. add OAuth state/pending/audit persistence only if Redis-only state is insufficient;
9. add durable webhook event identity/replay fields or a new Zoom-specific ledger;
10. add `zoom_meeting_occurrence` later when recurring meeting support is approved;
11. add `zoom_recording` metadata, integration-tab provenance, import state and access-policy fields in Release 2;
12. add participant observation and derived attendance-result tables or extend attendance-management through an approved API projection in Release 2;
13. add tenant Zoom live-class configuration (`PLAYER_REQUIRED`, `WEB_ALLOWED`, `EXTERNAL_ZOOM_ALLOWED`, access windows, watermark policy, attendance thresholds and grace rules) using existing tenant configuration conventions or a dedicated table after review.

Every tenant-owned table must include:

- `tenant_id UUID NOT NULL REFERENCES tenant(id)`;
- composite FKs for tenant-owned references;
- tenant-leading indexes;
- tenant-scoped unique constraints;
- no nullable tenant ID unless a separately approved platform-level webhook ledger exception is documented;
- no migration edits or renumbering of V1–V83.

Host-pool-specific schema requirements:

- `(tenant_id, integration_id, zoom_external_user_id)` uniquely identifies membership;
- disabling a pool member does not delete historical meeting/reservation references;
- reservations are linked to both class session and pool member;
- overlapping reservations must be protected by a concurrency-safe design;
- if host concurrency capacity is greater than one, the model must represent capacity slots or another provably race-safe capacity counter;
- provider/user capability snapshots are auditable and time-stamped;
- meeting rows persist the exact allocated host identity independent of later pool changes.

Recording-specific schema requirements:

- `(tenant_id, integration_id, ...)` must lead Media Library recording queries;
- recording provider IDs/file IDs must be unique within the integration/tenant scope, not globally assumed unique;
- recordings remain queryable under disconnected/deleted integration tabs as archived history;
- recording-to-course/session links use composite tenant FKs;
- multiple recording files for one meeting occurrence are supported;
- no recording row may authorize playback by itself without the LMS access-policy check.

Attendance-specific schema requirements:

- participation observations are deduplicated by provider event identity and participant/occurrence/source keys;
- derived attendance results are unique per `(tenant_id, occurrence_id, student_id)`;
- policy/version and manual-override metadata are retained;
- raw observations are not mutated or deleted as a shortcut to correcting attendance.

Migration/backfill must not guess a host pool. Existing teacher-owned connections can be represented as legacy connection rows and surfaced for an explicit Tenant Admin reconnect/migration workflow. The new tenant-owned integration must synchronize its account users and the admin must explicitly enable pool members before new pooled scheduling becomes available.

---

## 10. Backend architecture

### 10.1 Module ownership

- `integrationmanagement`: Zoom OAuth, encrypted credentials, provider HTTP client, integration aggregate, user sync, host-pool management/allocation, meeting/occurrence API adapter, recording ingestion/provenance, webhook verification and provider event ingestion.
- `liveclassmanagement`: class-session ownership, schedule and lifecycle, course/teacher authorization, host-resolution request, session-type/occurrence orchestration and start/join flows through APIs.
- `enrollmentmanagement`: student entitlement/access-window API.
- `attendance-management`: owns the attendance decision contract and consumes normalized participation/attendance events or API projections; never reads integration repositories directly.
- `audit-log-management`: append-only audit events via API/events, including attendance overrides and recording attachment changes.
- `content-management`/`videoaccessmanagement`: owns the Media Library/lesson attachment contract and secure recording playback access through APIs; it does not own Zoom credentials.
- `native-player`: reuses player security primitives but does not own Zoom credentials.

No domain may import another domain's repository/entity. Cross-domain communication uses `api` interfaces or events.

### 10.2 Provider abstraction

Replace the current teacher-only assumptions behind `LiveClassProviderApi` with explicit request/response contracts such as:

```text
synchronizeUsers(tenantId, integrationId)

listHostPool(tenantId, integrationId)

allocateHost(
    tenantId,
    integrationId,
    classSessionId,
    scheduledStart,
    scheduledEnd
)

releaseHostReservation(
    tenantId,
    reservationId,
    reason
)

createMeeting(
    tenantId,
    classSessionId,
    integrationId,
    reservationId,
    allocatedZoomHostUserId,
    sessionType,
    meetingStructure,
    schedule,
    policy
)

updateMeeting(tenantId, meetingId, schedule, policy)

cancelMeeting(tenantId, meetingId)

issueParticipantAuthorization(
    tenantId,
    classSessionId,
    occurrenceId,
    principal
)

issueTeacherHostAuthorization(
    tenantId,
    classSessionId,
    occurrenceId,
    principal
)

listRecordings(tenantId, integrationId, cursor, filters)

reconcileParticipation(tenantId, classSessionId, occurrenceId)

checkConnectionHealth(integrationId)
```

The exact Java API should use project-style request/response DTOs, never provider entities or secret-bearing objects.

The provider adapter must receive an integration ID and allocated host reference, then resolve/decrypt credentials internally. The ordinary frontend must never submit an arbitrary provider host user ID as the authorization source.

`allocateHost` must be a domain operation with transactional/race-safe reservation semantics rather than a convenience query.


### 10.3 External-call consistency

Continue the existing two-phase pattern:

- local transaction creates/updates a pending operation;
- commit;
- call Zoom;
- persist success/failure in a second transaction;
- use an outbox/retry record for non-idempotent or delayed reconciliation operations;
- use provider idempotency keys where supported;
- never pretend that an external call and local commit are one atomic transaction.

---

## 11. OAuth flow

1. Tenant Admin opens a specific `zoom_integration` card and presses Connect.
2. Backend checks tenant context and `INTEGRATIONS/ZOOM_INTEGRATION_CONNECT` permission.
3. Backend issues cryptographically random, one-time state with short TTL and PKCE verifier.
4. State binds tenant, integration, initiating admin, redirect target identifier and OAuth app configuration version.
5. Browser redirects to Zoom General OAuth authorization URL.
6. Callback is public but does not resolve tenant from host/query input; it consumes state first.
7. Backend validates provider, code/error, state freshness and one-time use.
8. Backend exchanges code using the server-held verifier and client secret.
9. Backend fetches authorized Zoom identity/account and validates required granted scopes.
10. Backend stores encrypted pending credentials and redirects to the same tenant's fixed integration page.
11. Initiating admin confirms the pending connection; backend rechecks tenant, integration status, actor permission and pending ownership.
12. Backend atomically marks the old connection superseded/disconnected, stores the new encrypted token set, records connected identity and emits an audit event.
13. Backend performs an initial health check/user sync asynchronously or in a bounded post-confirmation operation.
14. Reconnect replaces credentials without losing historical meeting references.
15. Deauthorization/revocation marks the integration unavailable and prevents new meetings; existing LMS history remains. LMS-owned academic/audit history may remain where required, but Zoom-derived credentials and provider user/profile data must be deleted or anonymized according to the current Zoom deauthorization/data-retention requirements; this policy must be documented before production.

Never expose refresh/access tokens to the frontend. Never place authorization codes, tokens, ZAK or OBF in a URL beyond the provider callback's short-lived code exchange.

---

## 12. Host allocation and reservation model

Implement a deterministic, race-safe `ZoomHostAllocator` (or `ZoomHostResolver` if that name better matches the existing codebase):

```text
allocateHost(
    tenant,
    integration,
    classSession,
    scheduledStart,
    scheduledEnd
)
```

The LMS teacher is **not** the host-selection key. Teacher authorization and host allocation are separate concerns.

Required checks:

1. integration belongs to the authenticated tenant and is connected/healthy;
2. LMS teacher assigned to the class is valid for the tenant/course/session;
3. teacher is allowed by the integration's `ALL_TEACHERS`/selected-teachers policy;
4. pool member belongs to the same tenant/integration;
5. pool member is enabled;
6. synchronized Zoom user exists, belongs to the connected account and is active;
7. current license/host capability snapshot is sufficient for the requested meeting;
8. provider capability data is fresh enough for the configured health window;
9. schedule is normalized and valid;
10. current reservations/meetings do not consume the member's verified capacity;
11. provider/account concurrency rules are satisfied;
12. granted OAuth scopes can create/update/delete meetings for the selected host user;
13. current Get User Token/ZAK permission path is available for that host;
14. allocation is committed with locking/constraints so two concurrent requests cannot over-allocate the same capacity;
15. return `NO_AVAILABLE_ZOOM_HOST` or another structured error when no eligible host exists.

The approved initial/default strategy is:

- `POOLED_HOST`.

Reserved future strategies may include:

- `MAPPED_TEACHER_HOST` (future optional strategy only; not Release 1);
- `FIXED_APPROVED_HOST`;
- `ALTERNATIVE_HOST`;
- `CO_HOST`.

No strategy may silently fall back to another. Only provider-supported capabilities confirmed by current Zoom documentation and the connected account's entitlements may be enabled.

### 12.1 Allocation algorithm

Recommended logical ordering:

```text
enabled pool members
    -> active provider users
    -> valid/fresh host capability
    -> remaining verified concurrency capacity
    -> no conflicting reservation beyond capacity
    -> lowest current utilization
    -> configured priority
    -> least recently assigned
    -> stable identifier tie-break
```

The selection criteria must be deterministic and testable.

### 12.2 Reservation lifecycle

Use explicit reservation states so external Zoom calls do not occur while a database allocation lock is held longer than necessary:

```text
PENDING/RESERVED
    -> provider create
        -> PROVISIONED
        -> FAILED / RELEASED according to retry policy
```

A typical safe flow:

1. begin local transaction;
2. lock/select eligible capacity;
3. create reservation;
4. commit;
5. call Zoom to create the meeting for the reserved host;
6. persist provider meeting and mark reservation `PROVISIONED`;
7. if provider creation fails, retain a retryable failure state or release capacity according to the approved retry/idempotency policy.

Do not hold an ordinary database transaction open across the Zoom HTTP call.

### 12.3 Reschedule and reallocation

When a session is rescheduled:

- first check whether the existing host reservation remains valid for the new window;
- if valid, keep the same host;
- if invalid, perform an explicit audited reallocation operation;
- never silently change a host on an already provisioned provider meeting without correctly updating/recreating the Zoom meeting;
- preserve historical allocation/meeting audit details.

### 12.4 Teacher start behavior

The class teacher does not become a different Zoom account user. Instead:

```text
Teacher clicks Start Class
  -> LMS verifies teacher/course/session
  -> load provisioned meeting + reserved host
  -> revalidate host/integration/start window
  -> server retrieves reserved host's short-lived ZAK
  -> generate Meeting SDK role-1 signature
  -> issue narrow in-memory AOC Player descriptor
  -> player starts meeting as reserved Zoom host
```

This is a start-on-behalf flow and must be proven in Wave 0. The teacher must never be given the Zoom host password, OAuth refresh token or a reusable host credential.

---

## 13. Course/live-session integration

The existing `ClassSession` remains the LMS scheduling aggregate. Proposed changes:

- add explicit `zoomIntegrationId` through a tenant-safe reference;
- persist the LMS teacher ID separately from the Zoom host identity;
- persist host reservation ID, host-pool member ID, synchronized Zoom external-user reference, actual Zoom host user ID, `POOLED_HOST` strategy, capability snapshot/version and allocation timestamp;
- persist approved `sessionType` and `meetingStructure`, with Release 1 limited to standard scheduled meetings;
- persist or reference the normalized LMS/provider occurrence for later attendance and recording association;
- keep provider meeting IDs internal;
- add meeting configuration fields only for approved LMS policy options;
- preserve `SCHEDULED`, `LIVE`, `COMPLETED`, `CANCELLED` state transitions separately from provider provisioning status;
- expose connection/host-allocation health status without secrets or raw provider URLs.

For Release 1, the normal course/session UI selects:

- LMS teacher;
- date/time/duration/timezone;
- connected Zoom integration;
- approved meeting policy/options.

The frontend does **not** choose an arbitrary provider host. The backend allocates the host pool resource and can display the resulting host resource/status to authorized Tenant Admin/Teacher users as read-only operational information.

### 13.1 Release 1 standard-meeting behavior

One LMS live session maps to one provider scheduled meeting and one normalized occurrence.

Create flow:

1. resolve course/lesson/teacher from trusted context and course API;
2. validate integration belongs to tenant and selected teacher is allowed to use it;
3. validate schedule and policy;
4. allocate/reserve pooled host capacity;
5. persist pending class session/meeting operation;
6. commit;
7. call Zoom outside the database transaction using the allocated provider host user;
8. persist provider identifiers and provisioned state;
9. bind meeting to reservation;
10. publish audit/event after commit.

If provider creation fails, the LMS must keep a visible retryable synchronization state and apply the approved reservation-release/retry policy.

### 13.2 Update/reschedule/cancel behavior

For reschedule:

1. determine whether current host capacity remains valid at the new time;
2. keep the host if valid;
3. otherwise explicitly allocate replacement capacity;
4. update/recreate the provider meeting correctly;
5. only then finalize the local provider-sync state;
6. release obsolete reservation capacity after the new state is safely established.

For cancellation:

- commit LMS cancellation;
- cancel/delete provider meeting best-effort using existing two-phase patterns;
- release future host reservation capacity;
- preserve audit/history.

### 13.3 Later session types

Recurring meetings and Zoom Webinars are Release 3 capabilities.

The architecture must remain extensible, but they are not blockers for Release 1.

For recurring series, each occurrence must retain or explicitly allocate a host resource; series-level host assumptions must not corrupt occurrence-level capacity.

For Webinars, webinar host/panelist/attendee entitlements are separate provider capabilities and must be validated before enablement.

### 13.4 Recording-to-Media-Library behavior

Recording ingestion remains a Release 2 capability.

When a verified recording event or reconciliation job discovers recording files:

1. resolve integration, provider host, meeting occurrence and LMS session from server-held mappings;
2. persist recording metadata and processing state;
3. associate the recording with the exact integration tab;
4. publish a media-library projection/event;
5. make it visible only after provider processing and LMS access checks;
6. issue secure playback grants per viewer.

The Media Library must not expose raw long-lived Zoom recording URLs or change provider provenance merely because a session was operated by a particular LMS teacher.

---

## 14. Meeting SDK authorization

### 14.1 Backend contract

Conceptual endpoint:

```text
POST /api/v1/class-sessions/{sessionId}/zoom/sdk/join-authorization
```

The request identifies only the LMS class session. It accepts no meeting number, role, host ID, passcode, ZAK or OBF from the client.

The response contains only the minimum short-lived, audience-specific data required by the selected SDK view and approved Zoom flow, for example:

- short-lived SDK signature;
- meeting number only if the SDK requires it and the player has already passed LMS authorization;
- short-lived passcode or opaque SDK join data only if required;
- participant display name derived from LMS identity;
- role determined server-side;
- short expiry and correlation/session ID;
- OBF/ZAK only if the approved flow requires it and only as an in-memory response to the trusted player channel.

Do not put secrets in query strings, localStorage, sessionStorage, audit records or logs. If OBF/ZAK must cross a WebView boundary, use the narrowest in-memory bridge and document the residual exposure.

### 14.2 Student authorization

Require:

- authenticated student;
- tenant resolved from auth context;
- class session belongs to tenant;
- active enrollment/course access;
- session is live or within configured student access window;
- provider meeting provisioned and not cancelled/ended;
- player/access policy satisfied;
- device/session/concurrency policy where configured.

Return 404 for unauthorized/nonexistent student-session access where anti-enumeration is required.

### 14.3 Teacher authorization

Require:

- authenticated LMS teacher or explicitly authorized staff;
- current course assignment/ownership, not only stored display teacher ID;
- teacher is allowed to use the selected Zoom integration;
- session is inside the configured teacher start window and is in a startable state;
- host reservation exists and is bound to the session;
- persisted allocated Zoom host remains active, enabled and capable;
- host reservation/capacity remains valid;
- integration is connected and current host authorization is available;
- server-generated Meeting SDK role is derived from the LMS session, never from client input;
- backend can retrieve the allocated host user's current ZAK using the approved Zoom scope/path;
- selected Meeting SDK view supports the required role-1 start-on-behalf behavior.

Teacher start flow:

```text
LMS Teacher
  -> Start Class
  -> authorize teacher/session
  -> load allocated pooled host
  -> retrieve host ZAK server-side
  -> generate role-1 SDK signature
  -> issue short-lived protected player descriptor
  -> AOC Player starts meeting under pooled host identity
```

The teacher must never enter or receive the Zoom host's password. ZAK, OAuth tokens and SDK secrets must never be placed in URLs, logs, audit payloads, localStorage or sessionStorage.

OBF is not a generic host token. External-account participant authorization must remain separate from the host's role-1/ZAK start path.

---

## 15. Meeting SDK technical spike and AOC Player architecture

Before production implementation, create a technical spike comparing Zoom Meeting SDK Web and proving the pooled-host start-on-behalf flow with at least two eligible host users in one development Zoom account:

- Client View versus Component View;
- Next.js App Router/client boundary;
- WebView2/Tauri CSP and WebAssembly requirements;
- camera/microphone permissions;
- host controls and participant controls;
- reconnect behavior;
- fullscreen and window protection;
- OBF/ZAK support in the selected view;
- desktop and mobile compatibility;
- cloud recording/attendance event behavior;
- Zoom review/distribution restrictions.
- role-1 start using a reserved pool member's ZAK when the LMS teacher is not that Zoom user;
- repeated classes where different LMS teachers operate the same host resource at different times;
- concurrent classes allocated to different host users;
- rejection when all host capacity is reserved.

**Provisional recommendation:** use a dedicated live-class player route with the SDK in the AOC Player, favoring the view that provides complete meeting functionality and the most reliable host/participant behavior. Do not choose Component View solely because it is easier to style. The final choice requires the spike and ADR.

Proposed flow:

```text
LMS live-session page
  -> backend player/session authorization
  -> aocplayer://live?launchCode=<single-use opaque code>
  -> Tauri Rust exchange
  -> live-session descriptor
  -> Meeting SDK engine
  -> heartbeat/stop/revocation through existing player session layer
```

The launch code must not contain a tenant ID, session ID, meeting ID or JWT. The Rust layer must exchange it server-side, validate installation/device binding and receive a short-lived live-class descriptor.

The browser-only `/player/live/{sessionId}` route may exist for `WEB_ALLOWED`, but it must still request a server-side grant and must not expose raw Zoom join links.

---

## 16. Screen-capture protection and watermark

### 16.1 What can be provided

For Tauri/Windows:

- use existing native window content-protection APIs where supported;
- keep protected content in the primary controlled window;
- disable unnecessary production DevTools/navigation;
- block raw Zoom child-window fallback when `PLAYER_REQUIRED`;
- surface `APPLIED`, `UNSUPPORTED` and `FAILED` states;
- refuse playback only when tenant policy explicitly requires protection and the capability is unavailable.

For Android/iOS clients, use native secure-window/capture-detection mechanisms only if those clients exist and are approved; document platform-specific limitations.

For normal browsers/WebViews, state clearly that OS-level screenshots and recordings cannot be completely prevented.

### 16.2 Dynamic watermark

Add an optional LMS overlay outside or above the Meeting SDK surface where technically compatible:

- student display name;
- masked student identifier or student number;
- current date/time;
- moving position;
- low opacity;
- tenant-configurable enablement and fields.

The watermark must not obstruct Zoom controls or claim DRM-level protection. It must be derived from the backend-issued descriptor, not arbitrary query parameters.

---

## 17. Attendance and webhooks

### 17.1 Authority model

Frontend Meeting SDK events are useful for immediate UI but not authoritative. The backend should combine:

- verified Zoom webhook events;
- Zoom participant/report APIs where permitted;
- server-side player-session events;
- reconciliation jobs for missed webhook deliveries;
- occurrence-level provider status and schedule data.

The authoritative attendance result is a derived, auditable decision. Raw participation observations must remain available for reconciliation and review. A configurable tenant attendance policy should define, at minimum:

- minimum attended minutes and/or percentage of scheduled/actual occurrence duration;
- grace period before a late join is considered partial/absent;
- early-leave treatment;
- handling of reconnects and overlapping participant intervals;
- treatment of webinar attendee/panelist/host roles;
- whether unresolved identities are excluded or queued for manual review;
- whether a teacher/admin may manually override an automatic result.

Recommended default: merge overlapping intervals, calculate total unique participation duration, compare it against the policy threshold, and produce `PRESENT`, `PARTIAL`, `ABSENT` or `UNRESOLVED`. Store the policy version used so later policy changes do not rewrite historical decisions automatically.

Attendance records must link to:

- tenant;
- LMS student;
- course and class session;
- Zoom meeting and exact occurrence;
- integration provenance;
- joined/left timestamps;
- total unique participation duration and percentage;
- reconnect count/source;
- final attendance status;
- provider event/reconciliation correlation;
- manual override actor/reason and before/after values, when applicable.

Participant identity resolution must prefer a server-issued LMS join/session identity or verified provider user/SDK identifier. Do not trust a browser-supplied name. Email matching must be treated as evidence subject to tenant policy, not as an unrestricted cross-tenant identity lookup.

### 17.2 Recording ingestion and Media Library tabs

Zoom recording files can arrive after a meeting ends and may arrive as multiple files or in multiple webhook deliveries. The ingestion pipeline must be idempotent and asynchronous:

1. accept and persist the verified event;
2. resolve the integration and occurrence from platform-held mappings;
3. fetch or reconcile recording metadata using the integration's server-side token;
4. persist each file/recording item with processing status;
5. link it to the integration-specific Media Library tab and LMS session;
6. publish readiness to the Media Library;
7. apply viewer access policy and issue a short-lived playback grant.

A recording must not appear as ready before provider processing is complete. If a provider recording file is unavailable or access expires, the tab displays an actionable unavailable state without exposing the raw provider URL.

### 17.3 Webhook processing

1. receive raw body;
2. verify timestamp/signature before parsing;
3. handle URL validation;
4. derive integration/tenant only from a server-held mapping or verified integration context;
5. persist an idempotency row using provider event identity/body hash;
6. acknowledge quickly;
7. process meeting/participant/recording effect once;
8. record failure/retry status without losing the original event;
9. audit security failures and deauthorization.

Events from unknown meetings must be safe no-ops and must not create a tenant association from payload data.

---

## 18. Recording integration and Media Library tabs

Recording support is a required product capability, not a later optional enhancement:

```text
Zoom recording.completed / processing webhook
  -> verify + dedupe
  -> resolve integration + meeting occurrence + LMS session
  -> fetch/reconcile recording file metadata
  -> persist recording item(s) and processing state
  -> publish integration-specific Media Library projection
  -> apply tenant/course/enrollment access policy
  -> issue short-lived secure playback grant
```

The Media Library should use a tabbed provider/source layout similar to the supplied reference image. Existing sources such as Uploads, YouTube and DynTube remain separate. Each tenant-owned Zoom integration produces a separate tab labelled with its configured integration name, for example `AOC ZOOM 0`, `AOC ZOOM 1` and `AOC ZOOM 2`. The exact visual design must use the LMS design system and must not copy third-party branding or proprietary CSS.

Each Zoom tab must support:

- recording count and processing status;
- search/filter by title, course, session, date, meeting type and occurrence;
- responsive card/list presentation with provider icon, title/topic, recording date, duration and status;
- standard meeting, recurring occurrence and webinar recording metadata;
- loading, empty, error and permission-denied states;
- secure preview/playback through the existing authorization boundary;
- attach/link to a course lesson or class session where authorized;
- archived state when its integration is disconnected/deleted;
- pagination/cursor handling rather than an unbounded tenant-wide response.

Recommended conceptual API contract:

```text
GET /api/v1/media-library/sources
GET /api/v1/media-library/zoom-integrations/{integrationId}/recordings
GET /api/v1/media-library/recordings/{recordingId}
POST /api/v1/media-library/recordings/{recordingId}/playback-grant
POST /api/v1/media-library/recordings/{recordingId}/attach
```

The client may request an integration ID for navigation, but the backend must resolve that ID through the authenticated tenant context and enforce the caller's role/course permissions. The response must never contain a raw long-lived Zoom recording URL, provider download token, OAuth token or secret. A recording may be listed under only the Zoom integration that produced it; lesson attachment does not change provider provenance.

Zoom recording files can arrive after a meeting ends and may arrive as multiple files or in multiple webhook deliveries. Store each file/item separately when required by the provider, deduplicate it, and expose a parent recording/occurrence grouping in the Media Library. Do not preserve the current one-recording-per-session assumption if Zoom supplies multiple recording files.

---

## 19. Security architecture

Required controls:

- OAuth state is CSPRNG, short-lived, one-time and bound to tenant/integration/admin/PKCE;
- callback uses state for tenant/integration resolution, never request-supplied tenant data;
- client ID/secret and tokens are encrypted at rest; encryption AAD binds ciphertext to tenant/integration/provider;
- refresh-token rotation is atomic and serialized;
- deauthorization marks only affected integration(s) unavailable;
- no credentials in DTOs, logs, audit metadata, browser storage or URLs;
- Meeting SDK signature generated server-side with short TTL and server-derived role;
- host allocation/reservation is server-side and race-safe; ordinary clients cannot select arbitrary host IDs;
- ZAK is retrieved only for the already-allocated host and only after LMS teacher/session authorization;
- ZAK/OBF retrieved on demand and not persisted longer than the approved flow requires;
- session join checks enrollment, tenant, course, session window, provider state, device/session policy and revocation;
- opaque meeting IDs never become a direct authorization mechanism;
- all protected content/player routes verify tenant and role server-side;
- webhook signature verification happens before parsing/state changes;
- webhook timestamp freshness, event idempotency and replay resistance are enforced;
- rate limit OAuth initiation, callback abuse, SDK authorization and webhook failures;
- audit integration lifecycle, mappings, meeting lifecycle, host conflicts, player grants, deauthorization and security failures;
- provider/API error messages are sanitized and bounded;
- metrics/log fields include tenant/integration/session/operation/correlation ID but never secrets;
- failure is closed when encryption keys, SDK secrets, webhook secrets or required Zoom app configuration are missing in real environments.

---

## 20. Tenant isolation

Every tenant-owned Zoom row/query must use the trusted tenant context. Required negative tests include:

- Tenant B cannot list, inspect, edit, connect, reconnect, delete or disconnect Tenant A's integration;
- Tenant B cannot see Tenant A's external Zoom users, host-pool members or reservations;
- Tenant B cannot enable/disable Tenant A host-pool members or reserve Tenant A host capacity;
- Tenant B cannot create/update/cancel a meeting on Tenant A's integration;
- Tenant B cannot resolve or start Tenant A's host;
- Tenant B student cannot receive an SDK grant for Tenant A's session;
- Tenant B cannot join Tenant A's session through guessed session/meeting IDs;
- Tenant B cannot access Tenant A participant, attendance or recording metadata;
- a body/path/query `tenantId` cannot override authenticated tenant context;
- webhook payload tenant/account identifiers cannot override the platform's own integration/meeting lookup;
- platform-admin cross-tenant reports, if later needed, use explicitly named and reviewed bypass APIs rather than weakening tenant-aware repositories.

A query containing a tenant filter is insufficient evidence by itself; tests must prove the filter derives from authenticated context.

---

## 21. Failure handling

Map failures to actionable states:

| Failure | Expected behavior |
|---|---|
| OAuth state invalid/expired/replayed | neutral error redirect, security log, no write |
| Admin cancels consent | integration remains unchanged |
| Missing required scope | pending grant revoked/discarded, reconnect message |
| Refresh token invalid | integration `AUTHORIZATION_REVOKED`/reconnect-required; no new meeting |
| Zoom API unavailable | pending/retryable state, no false provisioned success |
| No enabled host-pool members | scheduling rejected with pool-configuration error |
| All hosts at capacity | `NO_AVAILABLE_ZOOM_HOST`; show safe conflicting-window/capacity guidance to authorized staff |
| Host user inactive/unlicensed/incapable | remove from eligibility; existing affected future sessions flagged for reallocation |
| Host capability data stale | refresh/revalidate or fail closed according to health policy |
| Reservation race | one request succeeds; competing request retries/reallocates without overbooking |
| Provider meeting creation fails after reservation | retain retryable operation or release reservation according to idempotency policy |
| Reserved host becomes unavailable before class | start blocked; explicit admin reallocation/reprovisioning workflow required |
| Host ZAK retrieval fails | no start_url/raw credential fallback under protected policy; reconnect/revalidate integration |
| Meeting deleted externally | mark provider state stale; require recreate/resync |
| SDK signature/grant failure | no raw URL fallback under `PLAYER_REQUIRED`; structured user error |
| Camera/microphone denied | meaningful player permission state; meeting may remain limited if policy permits |
| Connection loss | reconnect/heartbeat policy; stop/revoke when authorization expires |
| Webhook invalid/replayed | 401/no state change or idempotent no-op, security log as appropriate |
| Recording unavailable | metadata state remains pending; no raw provider URL exposed |

---

## 22. Observability

Structured events/log fields:

- `tenantId`;
- `integrationId`;
- `classSessionId`;
- internal `zoomMeetingId` only where safe for server logs;
- operation (`oauth_start`, `oauth_callback`, `sync_users`, `resolve_host`, `create_meeting`, `sdk_grant`, `webhook`);
- external HTTP status and sanitized Zoom error code;
- duration;
- correlation ID;
- retry number/state.

Never log:

- client secret;
- access/refresh token;
- authorization code;
- ZAK/OBF;
- SDK secret/signature payload containing sensitive data;
- meeting passcode unless a separately approved masked diagnostic is required;
- raw webhook bodies if they contain unnecessary personal data.

Add operational alerts for repeated refresh failures, integration revocations, webhook signature failures, event backlog, host-conflict spikes, SDK grant failures and Zoom API rate limiting.

---

## 23. Test strategy

### 23.1 Backend unit/integration tests

Use JUnit, Spring Boot integration tests and Testcontainers/PostgreSQL/Redis.

OAuth/integration:

- create multiple integrations in one tenant;
- selected versus all-teacher policy;
- OAuth state binds integration/tenant/admin;
- invalid, expired and replayed state;
- callback cannot change tenant/integration;
- pending confirmation only by initiating admin;
- token refresh rotates and persists the new refresh token;
- refresh failure transitions only the affected integration;
- reconnect/disconnect lifecycle and credential wiping;
- deauthorization isolation.

Zoom user synchronization/host pool:

- sync users with pagination;
- synchronized user is not automatically a pool member;
- enable valid host user as pool member;
- reject wrong-tenant/wrong-integration pool membership;
- inactive/unlicensed/incapable users excluded from allocation;
- disabling pool member prevents new allocation;
- historical meeting references survive member disablement;
- stale capability snapshot triggers refresh/fail-closed behavior;
- priority/order metadata is tenant-scoped and audited.

Host allocation/reservation:

- one available host is allocated;
- least-utilized/priority ordering is deterministic;
- same LMS teacher can receive different hosts for different sessions;
- different LMS teachers can use same host at non-conflicting times;
- overlapping request consumes capacity correctly;
- different host users can serve simultaneous sessions;
- capacity greater than one is honored only when explicitly verified;
- all-hosts-busy returns `NO_AVAILABLE_ZOOM_HOST`;
- concurrent allocation race cannot overbook;
- failed provisioning follows reservation release/retry policy;
- reschedule keeps existing host when capacity remains valid;
- reschedule reallocates only through explicit audited path;
- no client-supplied host ID can override allocation.

Meeting lifecycle:

- meeting created for allocated provider host user rather than always `/users/me`;
- create, retry, update, reschedule and cancel;
- external API failure leaves retryable local state;
- no transaction spans outbound Zoom call;
- meeting ID never appears as student authorization;
- allocated host snapshot remains historical even after pool configuration changes;
- externally deleted meeting is reconciled.

Teacher Meeting SDK authorization:

- assigned LMS teacher can start the session;
- wrong teacher is blocked;
- reserved host is resolved from server state, not client input;
- backend retrieves allocated host ZAK through approved scope/path;
- role is server-derived as host role;
- short-lived descriptor contains no long-lived provider credential;
- host disabled/inactive before start is rejected;
- ZAK retrieval failure fails closed;
- start-on-behalf integration spike succeeds against development Zoom account.

Student SDK authorization:

- valid enrolled student inside window;
- student too early/late;
- unenrolled and expired student blocked;
- wrong tenant/guessed session blocked;
- participant role is server-derived;
- external-account OBF path used where current Zoom rules require it;
- no secret in response/log/audit/browser-storage contract.

Webhooks/attendance/recordings:

- valid signature;
- invalid signature, stale timestamp, malformed body;
- URL validation;
- duplicate event is no-op;
- replay/body hash mismatch is rejected or safely quarantined;
- unknown meeting/occurrence does not create tenant association;
- meeting events resolve through stored meeting/integration/host mappings;
- Release 2 participant/attendance tests remain occurrence-aware;
- Release 2 recording completion links to correct integration/session;
- deauthorization affects only matching integration.

Tenant isolation:

- explicit cross-tenant negative tests for every new endpoint/repository/use case listed in §20.

### 23.2 Frontend tests

Tenant-admin integration/host pool:

- loading/empty/error/permission-denied states;
- create/edit/delete integration card;
- all teachers versus selected teachers;
- connect/reconnect/callback/pending confirmation;
- status/health display;
- user synchronization;
- enable/disable host-pool members;
- show capability/eligibility state;
- show safe host-capacity/reservation information;
- responsive and accessible forms.

Course/live-session:

- teacher/integration selection;
- automatic host allocation;
- no arbitrary provider-host selector in ordinary flow;
- allocation pending/error/success state;
- no-capacity error;
- standard meeting provisioning pending/failed/retry;
- reschedule revalidation/reallocation state;
- no raw join URL rendered as the primary student path.

Teacher/student:

- assigned teacher start button and denied wrong-teacher case;
- teacher sees operational class status without host secrets;
- student access window and enrollment denial;
- player-required launch;
- reconnect, permission and SDK failure states;
- dynamic watermark and connection status.

### 23.3 Playwright E2E

Use mocked/stubbed Zoom provider flows for normal CI:

1. Tenant Admin creates a named Zoom integration.
2. Admin connects a mocked Zoom account.
3. Admin synchronizes multiple Zoom users.
4. Admin enables Host A, Host B and Host C as host-pool members.
5. Admin permits Teachers A/B/C to use the integration.
6. Teacher A schedules a standard meeting; backend allocates Host A.
7. Teacher B schedules an overlapping class; backend allocates Host B.
8. Teacher C schedules another overlapping class; backend allocates Host C where capacity permits.
9. Another overlapping class is rejected when all verified capacity is exhausted.
10. Teacher A starts through AOC Player mock/SDK harness using the stored host reservation.
11. Wrong teacher cannot start Teacher A's class.
12. Enrolled student joins through protected player.
13. Unauthorized student is denied.
14. Cancellation releases host capacity.
15. Reschedule keeps or explicitly reallocates host according to capacity.
16. Tenant B cannot access Tenant A integration/pool/reservation/session/grant data.
17. Release 2 recording/attendance flows are tested separately when enabled.

Real Zoom tests are separate from normal CI and use dedicated non-production accounts. Wave 0 must include an actual Zoom development-account test with at least two host-capable users to prove meeting creation by allocated user, ZAK retrieval, Meeting SDK role-1 start-on-behalf and concurrent allocation behavior.

---

## 24. Migration from existing implementation

Migration must be additive and reversible:

1. inventory all current teacher-owned Zoom connections and existing/future `class_session` references;
2. do not automatically claim a teacher's personal Zoom account as a tenant-owned pooled integration without explicit Tenant Admin consent;
3. create a transition view showing teacher-owned connections and affected upcoming sessions;
4. allow a Tenant Admin to create a named tenant integration and authorize the institution's Zoom account;
5. synchronize account users;
6. explicitly enable eligible users as host-pool members;
7. verify host capability and current provider concurrency behavior;
8. migrate/recreate only future sessions after pool availability and integration validation;
9. preserve historical `class_session` provider references, connection IDs, audit records, attendance provenance and recording provenance;
10. keep old teacher-owned connection rows read-only/disconnected after replacement, with credentials wiped according to existing rules;
11. provide a rollback path that leaves old history intact and can temporarily continue the old join path only if security policy permits;
12. remove old teacher-owned scheduling code only after all call sites, tests, API docs and operational dashboards use the new pool model.

The migration must not:

- infer a permanent teacher-to-host mapping;
- silently reassign active meetings to a new host;
- rewrite historical meeting ownership;
- enable synchronized Zoom users as hosts without explicit eligibility/configuration;
- transfer licenses between Zoom users as part of migration.

---

## 25. Implementation waves

The following waves honor the repository's backend-first workflow and the Product Owner's approval of `POOLED_HOST`. Each wave requires review before the next.

### Release 1 — core pooled-host Zoom live classes

#### Wave 0 — Zoom technical validation and ADR

**No production schema/application implementation in this wave.**

- approve this amended plan and `POOLED_HOST` ADR direction;
- configure a dedicated Zoom development/test account with at least two host-capable users;
- verify General OAuth app type and account-level/admin scopes;
- verify current API/scopes for listing account users;
- verify current API/scopes for creating/updating/deleting a meeting for a selected account user (`/users/{userId}/meetings` or current equivalent);
- verify Get User Token/ZAK scope for selected host users;
- prove Meeting SDK role-1 start using allocated Host A's ZAK when LMS Teacher A is not that Zoom user;
- repeat with LMS Teacher B and Host B;
- prove one host cannot be over-allocated beyond verified capacity;
- prove two different eligible host users can support simultaneous sessions where the account permits it;
- verify current external-account participant OBF requirements;
- compare Meeting SDK Client View versus Component View in AOC Tauri/WebView2;
- confirm Marketplace/Beta/review implications;
- create/update ADR covering OAuth + pooled host + Meeting SDK + OBF architecture.

**Gate:** do not begin Wave 1 until the start-on-behalf flow and required scopes are proven or an explicit Product Owner-approved alternative is selected.

#### Wave 1 — backend tenant Zoom integration/schema foundation

- add tenant Zoom integration/card model;
- add allowed-teacher policy model;
- add synchronized external Zoom user model;
- add `zoom_host_pool_member`;
- add `zoom_host_reservation`;
- add tenant-safe meeting host snapshot fields/model;
- add tenant-safe repositories/entities/DTOs;
- add database constraints/indexes for tenant isolation and reservation safety;
- backend tests only;
- no frontend changes.

#### Wave 2 — backend OAuth and integration lifecycle

- extend OAuth state/pending flow for integration ID and Tenant Admin;
- General OAuth connect/reconnect/disconnect;
- encrypted token rotation and health state;
- allowed-teacher policy API;
- account-user synchronization API;
- audits and negative tests;
- backend/security review.

#### Wave 3 — host pool management and allocator

- host-pool enable/disable API;
- host eligibility/capability refresh;
- implement `ZoomHostStrategy` with `POOLED_HOST` as the only enabled Release 1 strategy;
- deterministic host allocator;
- race-safe reservation/capacity model;
- no-capacity errors;
- reservation release/retry/reallocation services;
- concurrency and locking tests;
- tenant-isolation review.

#### Wave 4 — standard Zoom meeting lifecycle using allocated host

- create standard scheduled meetings for the reserved Zoom host user using current supported API/scopes;
- update/reschedule/cancel/retry;
- meeting/reservation binding;
- provider failure synchronization;
- preserve allocated-host historical snapshot;
- backend review/security review.

#### Wave 5 — Meeting SDK authorization backend

- teacher start authorization using persisted reservation/host;
- server-side Get User Token/ZAK retrieval;
- role-1 Meeting SDK signature generation;
- short-lived AOC Player live-class descriptor;
- student participant authorization;
- external-account OBF handling where required;
- no raw Zoom URL fallback under `PLAYER_REQUIRED`;
- security tests and secret-leak review.

#### Wave 6 — Tenant Admin Zoom integration/host-pool frontend

- integrations list/create/edit/detail;
- allowed teachers;
- connection health/reconnect/disconnect;
- synchronized Zoom users;
- enable/disable host-pool members;
- host eligibility/capability state;
- reservation/capacity operational view where useful;
- loading/empty/error/permission/accessibility/responsive states;
- frontend review.

#### Wave 7 — course/teacher/student Zoom frontend

- live-session form with teacher + integration selection;
- automatic allocation/provisioning state;
- no-capacity and provider-sync error UX;
- teacher start state;
- student access-window state;
- player launch/error/reconnect states;
- no arbitrary provider-host selection in normal flow;
- Playwright tests;
- frontend review.

#### Wave 8 — AOC Player Meeting SDK integration

- dedicated live engine/route;
- Rust launch/exchange and short-lived descriptor;
- role-1 host start using server-issued reserved-host authorization;
- student participant join;
- capture protection and watermark;
- WebView permissions/CSP/fullscreen/reconnect;
- native/browser compatibility tests;
- player security review.

#### Wave 9 — webhooks, migration and Release 1 verification

- durable Zoom event identity and replay handling;
- standard meeting state processing;
- provider reconciliation jobs;
- migrate/retire teacher-owned Zoom scheduling path;
- full integration, security and tenant-isolation tests;
- real Zoom test-account verification;
- documentation/runbook/demo;
- definition-of-done and PR readiness review.

### Release 2 — attendance and recordings

#### Wave 10 — participation attendance

- participant observations/webhook/report ingestion;
- occurrence-level reconciliation;
- configurable attendance thresholds/grace;
- manual override/audit;
- attendance-management API/event integration;
- cross-tenant and idempotency tests.

#### Wave 11 — recordings and Zoom Media Library tabs

- recording ingestion/reconciliation and multiple-file support;
- integration-specific Media Library source/tab;
- secure playback grants;
- attach/link flow;
- archived integration history;
- access-control and tenant-isolation tests.

### Release 3 — advanced Zoom session types

#### Wave 12 — recurring meetings and Zoom Webinars

- recurring series/occurrence model;
- per-occurrence host-capacity handling;
- webinar capability/roles/registration/provider adapter;
- attendance/recording support for advanced session types;
- additional Marketplace/entitlement validation;
- full regression and security review.

---

## 26. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Zoom changes ZAK/OBF/Meeting SDK policy | Host/student join breaks | isolate adapter; revalidate official docs and sandbox before release |
| Start-on-behalf using pooled host ZAK is restricted for target app/account | Core teacher-start UX blocked | Wave 0 proof before schema implementation; ADR gate |
| Marketplace/Beta/review delay | External-account rollout blocked | submit early; separate internal development test from external-tenant validation |
| OAuth lacks account-level user/admin scopes | Host pool cannot sync/manage users | require suitable Tenant Admin authorization and actionable reconnect state |
| API cannot create meetings for selected account users under granted scopes | Pool allocation cannot provision host meeting | Wave 0 test `/users/{userId}/meetings` path/scopes before implementation |
| No eligible host-pool members | Scheduling unavailable | admin eligibility UI, health checks, clear configuration error |
| All host capacity consumed | Class cannot be scheduled at requested time | deterministic allocation, conflict/capacity view, actionable reschedule response |
| Reservation race | Same host capacity overbooked | DB locking/constraints/advisory lock or equivalent proven concurrency control |
| Host loses license/becomes inactive | Existing future meetings may fail | periodic sync, preflight/start-time revalidation, explicit reallocation workflow |
| Host ZAK retrieval fails | Teacher cannot start protected meeting | narrow retry/reconnect flow; no credential-sharing fallback |
| One host's verified capacity misunderstood | Licensing/concurrency violation | conservative capacity default and provider/account validation |
| WebView2 Meeting SDK incompatibility | AOC Player cannot host meetings | Wave 0 technical spike before player implementation |
| Raw Zoom URLs or ZAK leak | Unauthorized access / credential exposure | protected grants, memory-only descriptor, no normal raw URL path |
| Webhook delivery/replay | Incorrect state/attendance/recordings | signature validation, durable idempotency, reconciliation jobs |
| Existing teacher-owned connections | Migration disruption | additive transition; preserve history; explicit tenant reconnect |
| Tenant query omission | Cross-tenant breach | structural repository enforcement and endpoint-specific negative tests |
| Provider/API outage | Session provisioning failure | pending/retry states, reservation lifecycle, alerts and meaningful UI |

---

## 27. Open decisions requiring human approval

1. **Resolved:** the initial/default Zoom host strategy is `POOLED_HOST`.
2. **Resolved:** LMS teachers are not permanently mapped to Zoom host users in Release 1.
3. **Resolved:** each named Zoom integration represents one authorized Zoom account and maintains its own enabled host pool; a tenant may have more than one named integration/account if product policy permits.
4. **Resolved:** normal course/session scheduling selects teacher + integration; the backend allocates and reserves the provider host.
5. **Resolved:** synchronized Zoom users are not automatically pool members; Tenant Admin explicitly enables eligible pool resources.
6. **Resolved:** `MAPPED_TEACHER_HOST` and `FIXED_APPROVED_HOST` are not automatic fallbacks and remain future strategies only.
7. Must every tenant authorize a Zoom account admin with account-level/admin scopes? This should become a Wave 0 technical answer.
7. Is the Zoom General OAuth/Meeting SDK app Development, Beta, Published Unlisted or Marketplace-listed for each rollout stage?
8. Which exact current Zoom scopes are approved for production, including account-user listing, meeting management for selected users and Get User Token/ZAK?
9. What provider field(s) can reliably determine whether a synchronized Zoom user is host-capable/licensed?
10. What safe default concurrency capacity should be used when the provider/account capability cannot be established automatically?
11. Is Meeting SDK Client View or Component View selected after the Wave 0 player spike?
12. Is the AOC Player mandatory for teachers and students, or students only?
13. What is the tenant policy for `PLAYER_REQUIRED`, `WEB_ALLOWED` and `EXTERNAL_ZOOM_ALLOWED`?
14. What access-window defaults apply to teachers and students?
15. What is the tenant watermark identity format and capture-protection refusal policy?
16. What is the exact reservation hold/retry policy when Zoom meeting creation fails after a host has been reserved?
17. May Tenant Admin manually pin a specific host for an exceptional session, or is automatic allocation the only Release 1 behavior? Recommendation: automatic allocation only initially.
18. What is the approved data-retention/deletion policy for Zoom external-user metadata and participant identity evidence after deauthorization?
19. Should attendance be linked to the existing attendance-management session model through events or a new API projection?
20. What attendance threshold/grace/late-join/early-leave policy becomes the Release 2 default?
21. Which recording types/files are visible to students, teachers and Tenant Admins, and what is the Release 2 retention policy?
22. Are recurring meetings and Webinars both required in Release 3, or should they be independently enabled?
23. What is the migration policy for already provisioned teacher-owned Zoom class sessions that occur after the new pooled-host feature is released?

---

## 28. Licensing/compliance gate

**The Product Owner has approved the `POOLED_HOST` model for implementation planning, but production host scheduling remains gated on Wave 0 technical validation.**

The approved business architecture is:

```text
Tenant Zoom Integration
    -> one authorized Zoom account
    -> synchronized Zoom users
    -> explicitly enabled eligible Host Pool
    -> race-safe host allocation per LMS class session
    -> provider meeting created for allocated host
    -> assigned LMS teacher starts/operates class through AOC Player using short-lived host authorization
```

This approval does not waive Zoom licensing, Marketplace, OAuth scope, concurrency, ZAK, OBF or Meeting SDK requirements.

Required compliance rules:

- every enabled pooled host must be a real Zoom user with the provider capability/entitlement required to host the meeting;
- the platform must not dynamically transfer a paid license between users to evade Zoom licensing;
- LMS teachers must not receive shared Zoom passwords;
- the allocated Zoom pool user remains the actual provider host identity;
- a teacher may operate the meeting only through an approved Meeting SDK start-on-behalf flow;
- host ZAK must be obtained server-side through the approved token API/scope and treated as short-lived sensitive authorization;
- capacity/concurrency limits must be enforced conservatively and race-safely;
- external-account student joins must follow current OBF/review rules where required;
- no production enablement occurs until Wave 0 proves the required account-user, meeting-management, ZAK and Meeting SDK flows.

If Wave 0 shows that the desired start-on-behalf flow cannot be used under the target Zoom app/account model, implementation must stop and return to architecture review rather than silently switching to credential sharing, co-host tricks or another host strategy.

---

## 29. Acceptance criteria

This Stage 1 plan is complete when:

- existing Zoom/live-class/AOC implementation is documented accurately;
- reusable and non-reusable pieces are identified;
- `POOLED_HOST` is consistently represented as the approved Release 1 host model;
- LMS teacher identity and provider host identity remain separate;
- one named integration maps to one authorized Zoom account and an explicitly enabled host pool;
- host-pool membership and reservation domain models are defined;
- race-safe host allocation and capacity behavior are defined;
- provider meeting creation is bound to the allocated host user rather than `/users/me` by default;
- teacher start-on-behalf uses server-authorized allocated-host ZAK/role-1 Meeting SDK flow, subject to Wave 0 proof;
- student participant authorization remains server-authoritative and OBF-aware where required;
- tenant-isolated domain/database/API architecture is defined;
- OAuth, host allocation, standard meeting lifecycle, SDK grant, player and webhook flows are defined for Release 1;
- attendance and recording architecture remains defined but staged to Release 2;
- recurring meeting/Webinar architecture remains extensible but staged to Release 3;
- migration from teacher-owned Zoom connections is additive and history-preserving;
- backend/frontend/player tests include host-pool allocation, concurrency race, no-capacity, role-1 start-on-behalf and cross-tenant negative cases;
- implementation waves and review gates are explicit;
- open decisions and the licensing gate are presented for human approval;
- no application code, frontend code, player code or Flyway migration is changed in Stage 1.

### Official references reviewed

- Zoom OAuth: <https://developers.zoom.us/docs/integrations/oauth.md>
- Zoom end-user authorization: <https://developers.zoom.us/docs/integrations/end-user-auth.md>
- Zoom Meeting SDK authorization: <https://developers.zoom.us/docs/meeting-sdk/auth/>
- Zoom Meeting SDK Web — Client View: <https://developers.zoom.us/docs/meeting-sdk/web/client-view/meetings-webinars/>
- Zoom Meeting SDK user login/start-on-behalf model: <https://developers.zoom.us/docs/meeting-sdk/user-login/>
- Zoom external-account/OBF FAQ: <https://developers.zoom.us/docs/meeting-sdk/obf-faq/>
- Zoom Meetings API: <https://developers.zoom.us/docs/api/meetings/>
- Zoom ISV create-meeting workflow: <https://developers.zoom.us/docs/isv/workflows/create-meeting/>
- Zoom ISV start-meeting workflow: <https://developers.zoom.us/docs/isv/workflows/start-meeting/>
- Zoom Users API: <https://developers.zoom.us/docs/api/users/>
- Zoom webhooks: <https://developers.zoom.us/docs/api/webhooks/>
- Zoom deauthorization guidance: <https://developers.zoom.us/docs/integrations/oauth.md>

---

## Stage 1 stop condition

This document is approved for the next implementation-preparation stage with `POOLED_HOST` as the selected Release 1 architecture.

Before any production host scheduling is enabled, the team must complete Wave 0 technical validation covering:

- Zoom General OAuth app/account model;
- account-level/admin user-listing scopes;
- meeting create/update/delete for a selected host user;
- reliable host eligibility/license/capability determination;
- provider/account concurrency behavior;
- server-side Get User Token/ZAK access for the allocated host;
- Meeting SDK role-1 start-on-behalf behavior when the LMS teacher is not the Zoom host account user;
- external-account participant OBF requirements;
- Meeting SDK Client/Component View compatibility in the AOC Tauri/WebView2 player;
- Marketplace/Beta/review/distribution requirements;
- deauthorization/data-retention obligations;
- at least one real development-account test with two host-capable Zoom users and multiple LMS teachers.

No application code, migration or production-host scheduling implementation is approved until Wave 0 passes or the Product Owner explicitly approves an amended architecture.
