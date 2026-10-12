# Zoom Wave 0 Validation

**Status:** Validation record in progress — **PARKED 2026-10-12** pending purchase of a Zoom Pro account with ≥2 licensed users. Wave 0a (free-account checks) and Wave 0b (pooled-host checks needing licensed users) will resume together; Wave 1 must not start before Wave 0b passes. The V84 foundation migration on this branch is pre-amendment and must not merge to `main` until it is reconciled with the pooled-host model.  
**Date:** 2026-10-12  
**Plan:** `docs/plans/multi-tenant-zoom-integration.md`  
**Approved initial host strategy:** `POOLED_HOST`  
**Release scope:** Release 1 standard meetings/AOC Player; Release 2 attendance and recordings; Release 3 recurring meetings and webinars.

> This document contains no credentials, tokens, meeting passwords, ZAK/OBF values, SDK secrets, webhook secrets, student records or production data. Any future sandbox evidence must use masked/synthetic identifiers.

## 1. Validation outcome

| Gate | Result | Evidence/status |
|---|---|---|
| General OAuth app and external tenant authorization | `PENDING_SANDBOX` | Official docs support General OAuth/end-user authorization; dedicated app/account validation still required. |
| Zoom account user synchronization | `PENDING_SANDBOX` | Required account/user-management scopes and tenant-admin role still require confirmation in the target Marketplace app. |
| Host-pool enablement and eligibility | `PENDING_SANDBOX` | Explicit Tenant Admin enablement, per-user capability and freshness behavior require validation. |
| Create/update/delete meeting for allocated user | `PENDING_SANDBOX` | Current docs show a user-specific meetings endpoint; existing code currently uses `/users/me`. |
| Pooled-host capability/license/concurrency validation | `PENDING_SANDBOX` | User type/status fields are documented; target account entitlement and capacity behavior remain unverified. |
| Allocated host ZAK / SDK role-1 start-on-behalf | `PENDING_SANDBOX` | Current docs require host ZAK plus role 1; start-on-behalf with an LMS teacher operator remains unverified. |
| External-account participant OBF flow | `PENDING_MARKETPLACE` | Current docs require OBF for applicable external-account joins and associate it with an authorized user; target app review/distribution status is unknown. |
| Standard scheduled meeting | `DOCUMENTED` | Supported by the current provider API shape; sandbox create/start/join test remains required. |
| Recurring meeting series | `RELEASE_3_VALIDATION` | Architecturally supported, intentionally not a Release 1 blocker. Occurrence behavior requires separate validation. |
| Zoom Webinar | `RELEASE_3_VALIDATION` | Architecturally supported, intentionally not a Release 1 blocker. Webinar entitlement/roles/events require separate validation. |
| Recording ingestion | `RELEASE_2_VALIDATION` | Required product capability; payload, retention and secure playback behavior require sandbox evidence. |
| Participation attendance | `RELEASE_2_VALIDATION` | Required product capability; participant identity/report fields and policy reconciliation require sandbox evidence. |
| Meeting SDK Client vs Component View | `PENDING_SPIKE` | Dedicated AOC/Tauri/WebView2 spike required before SDK implementation. |
| Production host scheduling | `BLOCKED` | Must remain disabled until pooled-host sandbox, Marketplace, scope, licensing, capacity, ZAK/OBF and SDK checks pass. |

## 2. Current official documentation findings

Official Zoom sources were reviewed through Context7 on 2026-10-11:

- OAuth: <https://developers.zoom.us/docs/integrations/oauth.md>
- End-user authorization: <https://developers.zoom.us/docs/integrations/end-user-auth.md>
- Meeting SDK auth: <https://developers.zoom.us/docs/meeting-sdk/auth.md>
- Meeting SDK OBF FAQ: <https://developers.zoom.us/docs/meeting-sdk/obf-faq.md>
- Web Meeting SDK Component View meetings/webinars: <https://developers.zoom.us/docs/meeting-sdk/web/component-view/meetings-webinars.md>
- Users API: <https://developers.zoom.us/docs/api/users/ma.md>
- Meetings API: <https://developers.zoom.us/docs/api/meetings/ma.md>
- Webhooks: <https://developers.zoom.us/docs/api/webhooks.md>

Confirmed/documented points:

1. General OAuth supports an authorization-code flow with `state`, PKCE S256 and refresh-token rotation. Refresh responses include a new refresh token, so storage must replace the prior token atomically.
2. User-level and account-level/admin scopes have different role requirements. Account-level scopes require validation of the authorizing Zoom user's account permissions.
3. User/account management documentation identifies `user:master` and granular user-management scopes for account user listing and user token operations. The exact production scope matrix must be confirmed against the target Marketplace app.
4. The Meetings API supports creating a meeting for a specified user path, not only the current user. Wave 0 must prove the granted OAuth app can call that path for a selected pool host in the connected external account.
5. The user token/ZAK documentation identifies user-specific token retrieval and requires the relevant user-management scope/granular scope for account/user paths. The exact path and authorization behavior for an allocated pool host must be tested in the target account.
6. Meeting SDK role `1` is used to start as host and role `0` to join. Current Web Component View documentation says host start uses the host's ZAK; webinars require `userEmail`; registration may require additional participant/registrant data.
7. Current Meeting SDK guidance says external-account joining may require OBF and that the associated authorized user must already be in the meeting where applicable. External meeting use is subject to current Zoom review/distribution requirements.
8. Zoom webhook validation uses the raw body, request timestamp, `x-zm-signature`, timestamp freshness and HMAC-SHA256. URL validation uses the `plainToken` HMAC response.

These findings are platform documentation findings, not proof that the current project app/account is approved or capable of all requested operations.

## 3. Required pooled-host sandbox validation

Use a dedicated non-production Zoom account with synthetic LMS identities such as `teacher-a@example.test` and `teacher-b@example.test`, plus at least Host A and Host B as eligible Zoom users. Do not store or report raw email/token values in this repository.

### 3.1 Account and pool setup

- Authorize one named tenant integration through the General OAuth flow.
- Resolve and mask the connected Zoom account ID.
- List or otherwise validate at least two active Zoom users in that account.
- Capture only masked external IDs, status and capability/license type.
- Explicitly enable Host A and Host B as host-pool members.
- Verify synchronized users are not automatically enabled as pool members.
- Verify a user from another Zoom account cannot be enabled.
- Verify inactive/deleted/unlicensed users are excluded.

### 3.2 Pooled allocation and provider meeting ownership

For two synthetic LMS teachers using the same integration, verify:

- each teacher is allowed to use the integration;
- allocation selects an eligible pool member, not a permanent teacher mapping;
- meeting creation succeeds through the user-specific endpoint for the allocated host;
- returned provider host ID equals the allocated pool user;
- update/reschedule/cancellation target the correct meeting and reservation;
- the implementation does not rely on `/users/me` as an implicit host;
- a later session may allocate a different host to the same LMS teacher;
- an LMS teacher cannot submit an arbitrary host ID.

### 3.3 Capability, capacity and concurrency

- Verify pool members' license/status/capability data is sufficient for standard meetings.
- Determine whether the provider exposes reliable per-user/account concurrent-meeting limits.
- If limits are not available, document the conservative platform capacity and rejection behavior.
- Run two simultaneous sessions allocated to Host A and Host B where permitted.
- Exhaust all verified pool capacity and confirm `NO_AVAILABLE_ZOOM_HOST` or equivalent.
- Run concurrent allocation requests and prove no host capacity is double-booked.
- Verify disabling a pool member prevents new allocations but does not rewrite historical ownership.
- Do not test or implement license reassignment between users.

### 3.4 Start-on-behalf and participant join

- Retrieve/use the allocated host's ZAK only through a server-side sandbox harness.
- Prove an LMS Teacher A can start a meeting reserved for Host A without Host A's Zoom password.
- Repeat with LMS Teacher B and Host B.
- Prove role-1 SDK host start for the allocated host.
- Prove role-0 participant join through the selected external-account authorization path.
- Determine whether OBF is required for the target account/app relationship.
- Confirm no token/ZAK/OBF is placed in URLs, logs, browser storage or screenshots.

## 4. Release-gated session-type validation

### Release 1: standard meetings

Required before production Release 1:

- allocated-user meeting create/update/delete;
- host-pool capability/license/capacity validation;
- pooled-host role-1 start-on-behalf and role-0 participant join;
- meeting started/ended webhook resolution and idempotency;
- AOC Player SDK spike result;
- no raw Zoom join/start URL as the supported student path.

### Release 2: attendance and recordings

Required before Release 2:

- participant join/leave/report payload mapping;
- identity matching rules and unresolved-state behavior;
- recording completion/processing payloads and multiple-file handling;
- occurrence/session linkage;
- secure recording playback-grant path;
- configurable attendance policy and manual override audit.

### Release 3: recurring meetings and webinars

Required before Release 3:

- recurring series and occurrence IDs/UUIDs;
- occurrence cancellation/rescheduling;
- webinar entitlement and webinar-specific roles;
- webinar participant/report/registration behavior;
- separate recording and attendance event handling;
- Meeting SDK parity or documented unsupported features.

## 5. Current code reconciliation

| Existing seam | Wave 0 conclusion |
|---|---|
| `ZoomOAuthClient` | Reuse OAuth/PKCE/token lifecycle concepts; extend state/connection scope to tenant integration. |
| `OAuthConnectFlowService` | Reuse single-use state, pending confirmation and tenant-derived redirect; bind state to integration/admin. |
| `ZoomMeetingClient` | Reuse HTTP/error patterns, but `/users/me` ownership is not sufficient for `POOLED_HOST`; validate a user-specific endpoint for the allocated host before implementation. |
| `ZoomLiveClassProviderAdapter` | Reuse provider adapter boundary and fail-closed errors; replace teacher-owned connection assumptions with pool allocation/reservation and raw URL join contract. |
| `MediaProviderConnection` | Reuse encrypted credential/token lifecycle; add tenant integration ownership rather than reusing teacher scope unchanged. |
| `ClassSession` and scheduling service | Reuse two-phase persistence around external calls and separate LMS/provider state machines. |
| `ZoomWebhookController` | Reuse signature/url-validation foundation; extend event identity, occurrence resolution, processing and deauthorization/data-retention behavior. |
| AOC Player / ADR-025 | Reuse paired-installation, launch-code, exchange, heartbeat and capture-deterrence principles; create a distinct live-class descriptor. |
| Existing Zoom tests | Preserve as regression coverage for legacy transition; add new pooled-host allocation/start-on-behalf contract tests only after Wave 0 validation passes. |

## 6. Blockers and decisions

Production scheduling is blocked until the following are evidenced for the target app/account:

- exact General OAuth user/account/admin scopes;
- account-level user synchronization or an approved constrained pool-validation alternative;
- create/update/delete meeting for allocated users;
- per-user license/capability and pooled capacity/concurrency behavior;
- allocated-host ZAK retrieval/use for SDK role 1 start-on-behalf;
- external-account participant OBF requirements and Marketplace/distribution approval;
- chosen Meeting SDK view working in the AOC Tauri/WebView2 environment;
- deauthorization/data-retention behavior for provider-derived users, recordings and participant data.

## 7. Evidence rules

- Use only dedicated sandbox/test accounts.
- Use masked/synthetic identifiers in committed documentation.
- Keep credentials in environment variables or an approved secret store.
- Never commit `.env` files, tokens, meeting passwords, ZAK/OBF values, SDK secrets or raw webhook payloads containing personal data.
- Record pass/fail/blocked status and provider error codes without sensitive payloads.
- Keep real Zoom tests separate from normal CI.
