# ADR-027: Multi-Tenant Zoom Integrations with Pooled Hosts and AOC Live-Class Player

## Status

**Architecture amendment proposed (2026-10-12).**

The Product Owner superseded the prior `MAPPED_TEACHER_HOST` Release 1 model with **`POOLED_HOST`**. The prior Wave 1 teacher-mapping foundation remains legacy implementation state and does not define pooled allocation. Production pooled-host scheduling remains blocked until the validation gates in `docs/integrations/zoom/wave-0-validation.md` and `docs/integrations/zoom/meeting-sdk-spike.md` pass for the target Zoom application/account.

This ADR is additive and supersedes the prior host-selection assumptions in ADR-018/ADR-027 planning only after Wave 0 validation and a subsequent implementation approval. It does not authorize application-code, frontend, player or Flyway changes by itself.

## Context

The existing implementation (`ADR-018`, MEDIA-INT-01) treats Zoom as a teacher-owned OAuth provider. A teacher connects their own Zoom account, sessions are created through that connection, and participants receive freshly fetched provider join links.

The approved product workflow requires:

- multiple named Zoom integrations per tenant;
- Tenant Admin-managed allowed-teacher policies;
- synchronized Zoom users belonging to the connected account;
- explicitly enabled host-pool members and race-safe host reservations;
- the platform to allocate an available Zoom host independently from LMS teacher identity;
- standard meetings in Release 1, attendance and recordings in Release 2, recurring meetings and webinars in Release 3;
- one Media Library tab per named Zoom integration for completed recordings;
- participation-derived attendance;
- Meeting SDK access through the AOC Player rather than raw Zoom URLs;
- strict tenant isolation and no license-sharing workaround.

Existing implementation seams are reusable but not correct unchanged:

- `ZoomOAuthClient`/`OAuthConnectFlowService` provide OAuth, PKCE, pending confirmation and token rotation patterns;
- `MediaCredentialsCodec`/`MediaTokenService` provide encrypted credentials and refresh locking;
- `ZoomMeetingClient` currently uses `/users/me`, which is insufficient for pooled-host ownership until user-specific meeting management is validated;
- `ZoomLiveClassProviderAdapter` assumes teacher-scoped connections and raw start/registrant URLs;
- `ClassSession` scheduling already separates local transactions from outbound provider calls;
- `ZoomWebhookController` already verifies signatures and records idempotency;
- ADR-025 provides AOC Player launch, installation, exchange, heartbeat and capture-deterrence principles.

## Decision

### 1. Tenant-owned named integrations

A tenant owns zero or more named Zoom integration cards. Each integration represents one authorized Zoom account and has its own OAuth authorization, encrypted credentials, connection status, account identity, allowed-teacher policy, synchronized user catalog, enabled host-pool members, reservations, meetings, recordings and audit trail.

A provider connection must be associated with the tenant integration that authorized it. A disconnected/deleted integration remains as historical provenance for sessions, meetings, recordings and audit records.

The tenant identity always comes from trusted authenticated context or a server-held OAuth/webhook mapping. A normal request must never choose a tenant through body, query or hidden form input.

### 2. OAuth and credentials

Use a Zoom General OAuth application for external tenant account authorization. The flow uses authorization code, CSPRNG state, PKCE S256, server-held tenant/integration/admin binding, one-time consumption and pending confirmation by the initiating Tenant Admin.

The final scope matrix must be validated against the current Zoom Marketplace app. It must cover only the approved release capabilities, including as applicable:

- current authorized account identity;
- account user listing/synchronization and user capability fields;
- create/update/delete meetings for a selected pool host user;
- selected pool-host ZAK retrieval;
- participant/report and recording data for Release 2;
- webinar capabilities for Release 3;
- webhook subscriptions and deauthorization.

OAuth access/refresh tokens are encrypted at rest, never returned to clients, never logged/audited, and refreshed server-side with atomic rotating-refresh-token persistence. Encryption context binds ciphertext to tenant/integration/provider. Deauthorization marks the integration unavailable and applies a documented separation:

- LMS-owned academic/session/audit history remains where required for integrity;
- Zoom-derived credentials, external-user/profile data and provider data are deleted or anonymized according to current Zoom deauthorization/data-retention requirements;
- the exact retention/deletion policy must be confirmed before production.

### 3. Allowed teachers and pooled host strategy

The initial Release 1 strategy is **`POOLED_HOST`**:

1. Tenant Admin creates and connects a named integration representing one Zoom account.
2. Backend synchronizes the minimum required Zoom-user data for that integration.
3. Tenant Admin explicitly enables eligible synchronized users as host-pool members.
4. Allowed-teacher policy determines which LMS teachers may use the integration; it does not select a host.
5. Scheduling allocates one eligible host-pool member for the requested class window.
6. The platform creates the provider meeting for that allocated Zoom user and persists a reservation/host snapshot.
7. The assigned LMS teacher operates the class through the server-authorized Meeting SDK/AOC Player flow.

The LMS teacher, Zoom host user and Zoom integration are distinct identities. A synchronized Zoom user is not automatically a pool member, and a pool member is not permanently owned by an LMS teacher. Each pooled host must independently satisfy current Zoom account, license, entitlement, status, API scope, concurrency and Meeting SDK requirements.

The system must:

- allocate deterministically using capability, health, reservations, utilization, priority and stable tie-break rules;
- create/update/delete meetings for the allocated Zoom user, not implicitly `/users/me`;
- reserve capacity with race-safe database locking/constraints before calling Zoom;
- return `NO_AVAILABLE_ZOOM_HOST` or equivalent when no eligible capacity exists;
- preserve allocated host/reservation snapshots historically;
- revalidate the allocated host at teacher start;
- never silently choose a different strategy, share credentials, dynamically transfer a paid license or present the LMS teacher as the Zoom host.

`MAPPED_TEACHER_HOST`, `FIXED_APPROVED_HOST`, `ALTERNATIVE_HOST` and `CO_HOST` remain disabled future strategies. Existing teacher mappings may remain as legacy/future-strategy data but must not control `POOLED_HOST`.

### 4. Meeting types and release scope

#### Release 1

Support standard scheduled Zoom meetings with one normalized occurrence per LMS session. The pooled host is allocated from the selected integration's enabled host pool and retained in the session/meeting snapshot.

#### Release 2

Add participation-derived attendance, recording ingestion, occurrence-level projections, integration-specific Media Library tabs and secure recording playback grants.

#### Release 3

Add recurring meeting series/occurrences and Zoom Webinars after separate capability validation. Recurring series are never flattened into one attendance or recording row. Webinars preserve distinct host, panelist and attendee roles, registration rules and participant-report limitations.

Independent meetings remain the safe fallback when an LMS recurrence contains per-occurrence teacher/host/policy changes.

### 5. Meeting lifecycle and transactions

`liveclassmanagement` remains the owner of LMS session ownership, course/teacher authorization, enrollment checks and LMS state transitions. `integrationmanagement` owns Zoom credentials, provider HTTP calls, user synchronization, host-pool membership/allocation, reservations, meeting/occurrence projections, recording ingestion and webhooks.

Cross-module communication uses `api` DTOs/interfaces or events. No domain imports another domain's repositories/entities.

External calls follow the existing two-phase pattern:

1. validate and persist a pending local operation;
2. commit;
3. call Zoom outside the transaction;
4. persist provisioned/failed state in a new transaction;
5. retry/reconcile through durable operations where required.

Provider meeting identifiers remain opaque server-side values. Client requests identify LMS sessions/occurrences, never arbitrary Zoom meeting numbers or host IDs.

### 6. Meeting SDK and AOC Player

A dedicated technical spike must select Client View or Component View based on complete standard-meeting host/participant functionality, pooled-host role-1/ZAK start-on-behalf behavior, external-account OBF behavior, WebView2/Tauri CSP and permission compatibility, reconnect, fullscreen, capture protection and Marketplace distribution rules.

The server-side grant endpoint identifies only an LMS class session/occurrence. It derives:

- tenant;
- caller role;
- course/session access;
- allocated host reservation and pool member;
- provider meeting/occurrence;
- SDK role;
- short expiry;
- ZAK/OBF requirement.

The client cannot submit a meeting number, role, host ID, passcode, ZAK or OBF as authoritative input. SDK signatures are server-generated. Role `1` is used only for an authorized start against the session's allocated pool host; role `0` is used for participant join. Webinars additionally require the provider-supported user email/registration fields.

The AOC Player receives a single-use opaque launch code and obtains a short-lived live-class descriptor through the existing secure exchange pattern. It never receives an LMS JWT or durable provider credential. `PLAYER_REQUIRED` never falls back to a raw Zoom URL/window.

### 7. Recordings and Media Library

Each named Zoom integration produces one tenant-scoped Media Library tab. The tab remains visible as archived history after disconnect/delete and is sourced from the integration row, not a raw provider account ID.

Recording ingestion is verified, asynchronous and idempotent:

1. verify and persist the provider event;
2. resolve integration, meeting, occurrence and LMS session through server-held mappings;
3. reconcile recording metadata/files;
4. persist each file/item and processing state;
5. publish an integration-specific Media Library projection;
6. issue secure, short-lived playback grants after tenant/course/enrollment checks.

Raw long-lived Zoom recording URLs/download tokens are not returned to the normal frontend. Recording provenance remains with the originating integration even when a recording is attached to another lesson/session. Multiple files per occurrence are supported.

### 8. Attendance

Frontend SDK events are UI signals only. Attendance is derived server-side from verified Zoom participant events, provider reports/reconciliation and server-side player-session evidence.

Raw participation observations remain available and are deduplicated by provider event/participant/occurrence/source identity. Derived results are unique per tenant/occurrence/student and include:

- first join, last leave, total unique duration, percentage and reconnect count;
- `PRESENT`, `PARTIAL`, `ABSENT` or `UNRESOLVED` status;
- policy version and threshold/grace rules;
- source/reconciliation timestamps;
- manual override actor, reason and before/after values.

Unresolved identity is not automatically assigned from an untrusted display name. Attendance policy, manual-override permission and audit behavior are finalized in Release 2.

### 9. Webhooks and tenant resolution

Webhook verification happens before parsing or state changes. The implementation uses the raw body, request timestamp, constant-time HMAC comparison, URL validation and stale-timestamp rejection.

Meeting, occurrence, participant, webinar, recording and deauthorization events are deduplicated and resolved through platform-held integration/meeting mappings. Payload tenant/account IDs never override trusted platform resolution. Unknown meetings are safe no-ops.

The exact current event names and payload differences for standard meetings, recurring occurrences and webinars must be validated before Release 2/3 enablement.

## Security and tenancy consequences

Positive consequences:

- one tenant integration can serve multiple LMS teachers while deterministically utilizing multiple eligible Zoom users;
- actual provider host identity and reservation are explicit and auditable;
- missing pool capacity/capability fails closed;
- raw Zoom URLs are removed from the protected student path;
- recordings and attendance retain integration/occurrence provenance;
- existing tenant-aware repository and AOC Player security patterns are reused.

Accepted constraints:

- every enabled pool host must independently satisfy Zoom licensing and entitlement rules;
- user-management scopes and account-admin permissions may be required;
- Zoom Marketplace review/OBF/ZAK behavior may block production scheduling;
- browser/WebView2 capture prevention is not perfect;
- provider reports/webhooks may not expose enough identity to avoid `UNRESOLVED` attendance cases;
- Zoom deauthorization may require deletion/anonymization of provider-derived data while LMS history remains.

Mandatory tests include cross-tenant integration/user/pool/reservation/session/grant/recording/attendance denial, pool-member mismatch, inactive/unlicensed host, stale capability, no-capacity allocation, reservation race, no fallback, webhook replay, raw URL absence and secure recording playback reauthorization.

## Alternatives considered

### Continue teacher-owned OAuth connections

Rejected for the approved product workflow. It does not support a tenant admin's named integrations, shared integration permissions or managed host capacity. Existing rows remain as migration history and transition input.

### One global tenant Zoom token/user

Rejected because it collapses tenants and host identities and creates an unacceptable license/concurrency boundary.

### Fixed approved host

Not selected for Release 1. It remains a future strategy; it cannot be automatic fallback when the pool is unavailable.

### Raw Zoom start/join URLs

Rejected as the normal protected workflow because links are bearer capabilities and do not provide the required AOC Player/session policy boundary.

### License reassignment/shared credentials

Rejected as non-compliant and forbidden by project security/licensing requirements.

## Validation evidence and blockers

See:

- `docs/integrations/zoom/wave-0-validation.md`
- `docs/integrations/zoom/setup-guide.md`
- `docs/integrations/zoom/meeting-sdk-spike.md`

At ADR preparation time, the following are intentionally blocked/pending:

- target Marketplace app review/distribution status;
- exact account-level user-management and selected-user meeting-management scopes;
- account-level user synchronization;
- pool-member eligibility/capability and capacity proof;
- selected-user meeting ownership proof;
- pooled-host ZAK role-1 start-on-behalf;
- external-account OBF participant flow;
- AOC Client/Component View spike;
- provider-derived data retention/deauthorization policy.

Production host scheduling must remain disabled until these blockers are evidenced and reviewed.

## Consequences and follow-up

After Wave 0 acceptance, Release 1 backend-only work may begin with:

1. tenant integration/card schema and lifecycle;
2. external Zoom-user synchronization;
3. explicit host-pool membership and eligibility;
4. host reservation/allocation with race-safe capacity checks;
5. standard meeting lifecycle for the allocated host;
6. Meeting SDK grant contract and AOC exchange design.

Existing `zoom_teacher_user_mapping` rows are retained only as legacy/future-strategy data and do not control pooled allocation.

Release 2 adds attendance/recordings and Media Library tabs. Release 3 adds recurring meetings and webinars. Each release requires its own backend tests, security review, tenant-isolation review and documentation update.
