# Zoom Meeting SDK Spike

**Status:** Spike plan and decision record template  
**Date:** 2026-10-12  
**Target:** AOC Tauri 2 / WebView2 player  
**Host strategy:** `POOLED_HOST`  
**Release 1 scope:** standard meetings; recurring meetings and webinars remain Release 3 validation.

> This document contains no SDK secret, signature, ZAK, OBF, meeting password or real participant data. The executable spike must use a dedicated sandbox account and synthetic identities.

## 1. Objective

Determine whether Zoom Meeting SDK Web Client View or Component View provides the required participant and pooled-host start-on-behalf experience inside the existing AOC Player architecture without exposing raw Zoom URLs or weakening server-side authorization.

The spike must produce a recommendation before any production Meeting SDK code is added to `frontend/`, `native-player/` or `backend/`.

## 2. Existing constraints

The existing AOC Player is Tauri 2 + Rust + TypeScript + WebView2. Relevant decisions:

- the player does not receive an LMS JWT;
- launch/exchange and installation/session security are server-controlled;
- the player uses strict CSP, navigation allow-lists and limited Tauri capabilities;
- production DevTools/navigation restrictions are part of the security boundary;
- capture protection and watermarking are deterrence controls, not perfect DRM;
- a live-class descriptor must remain separate from the video playback descriptor;
- `PLAYER_REQUIRED` must not silently fall back to a raw Zoom URL.

Relevant files/ADRs:

- `native-player/src/main.ts`
- `native-player/src/harden.ts`
- `native-player/src/engines/index.ts`
- `native-player/src/engines/types.ts`
- `native-player/src/styles.css`
- `docs/adr/ADR-025-native-secure-player.md`
- `docs/adr/ADR-026-managed-streaming-dyntube.md`

## 3. Test matrix

| Dimension | Client View | Component View | Evidence |
|---|---|---|---|
| Standard participant join, role 0 | `PENDING` | `PENDING` | Join success, reconnect and error behavior |
| Pooled host start-on-behalf, role 1 + allocated-host ZAK | `PENDING` | `PENDING` | LMS teacher starts the already-reserved pooled host; no password or authorizer fallback |
| External-account participant / OBF | `PENDING` | `PENDING` | Whether OBF is required and where it can be supplied |
| Webinar role/email behavior | `RELEASE_3` | `RELEASE_3` | `userEmail`, attendee/panelist/host roles |
| Host controls | `PENDING` | `PENDING` | Mute, participant, screen-share and end controls |
| UI customization | `PENDING` | `PENDING` | Ability to preserve AOC surface without hiding required provider controls |
| WebView2 microphone/camera | `PENDING` | `PENDING` | Permission prompts and device selection |
| CSP/WASM/assets | `PENDING` | `PENDING` | Required origins and unsafe capabilities |
| Fullscreen/window protection | `PENDING` | `PENDING` | Tauri window behavior and capture protection |
| Reconnect/network loss | `PENDING` | `PENDING` | State recovery and server heartbeat interaction |
| SDK event quality | `PENDING` | `PENDING` | Non-authoritative UI events only |
| Bundle/build impact | `PENDING` | `PENDING` | Build size, startup time and release packaging |

## 4. Authorization tests

The spike must obtain all sensitive values from a server-side test harness or environment variables and must keep them in memory only:

### Participant

- call the LMS-shaped grant using only a synthetic class-session ID;
- verify role `0` is generated server-side;
- verify meeting number/session identity is resolved server-side;
- verify enrollment/access-window checks occur before grant issuance;
- verify no raw `join_url` is required.

### Pooled host start-on-behalf

- synchronize synthetic Zoom Host A and Host B under one integration;
- explicitly enable both users as host-pool members;
- allocate Host A to an LMS Teacher A session;
- create the standard meeting for Host A through the user-specific API path;
- request a server-generated role `1` signature using the persisted reservation;
- retrieve/use Host A's ZAK through the server-only path where required;
- start in the SDK and verify the effective provider host is Host A while the LMS operator is Teacher A;
- allocate Host B to a concurrent LMS Teacher B session through the same integration;
- verify both sessions can run when provider capacity permits;
- verify a later pool configuration change does not alter historical host ownership;
- verify the client cannot replace the host ID, reservation, or role in its request;
- exhaust all pool capacity and verify allocation/start fails with an actionable no-capacity error.

### External-account join

- determine whether the target account/app relationship requires OBF;
- verify the associated authorized user and host-presence requirement from the current Zoom flow;
- do not treat OBF as a generic host token;
- keep OBF out of URLs, browser storage and logs.

## 5. AOC/WebView2 checks

Validate in the disposable spike harness:

- WebView2 runtime version and supported SDK browser requirements;
- camera/microphone permission prompts and persistence;
- CSP `script-src`, `connect-src`, `frame-src`, `worker-src`, WASM and asset origins;
- whether the SDK requires `unsafe-eval` or other policy exceptions and whether they can be isolated safely;
- navigation/window-open behavior; raw Zoom child windows must not become the supported path;
- fullscreen and resize behavior;
- Tauri content-protection API interaction;
- watermark overlay placement without obstructing required controls;
- reconnect after temporary network loss;
- server heartbeat/STOP behavior when the SDK remains visually active;
- teardown clearing all in-memory SDK authorization data.

Any CSP relaxation must be isolated to the smallest controlled surface and documented as a security consequence. Do not weaken the main player security boundary solely to make an unapproved SDK embedding work.

## 6. Decision criteria

Recommend the view that satisfies all Release 1 requirements with the smallest security and compatibility exception:

1. pooled-host role-1 start-on-behalf works using the allocated host's current ZAK/SDK rules;
2. role-0 participant join works for external tenant accounts;
3. standard meeting host controls are usable;
4. AOC/Tauri/WebView2 permissions and CSP are supportable;
5. raw Zoom URL/window fallback is not required;
6. reconnect, heartbeat and revocation can stop access;
7. SDK events are treated as UI signals, not attendance authority;
8. the build/package/runtime footprint is supportable;
9. provider Marketplace/review terms permit the selected distribution mode.

Possible outcomes:

- `PASS_CLIENT_VIEW`;
- `PASS_COMPONENT_VIEW`;
- `PASS_WITH_LIMITATIONS`;
- `BLOCKED_PROVIDER_REQUIREMENT`;
- `BLOCKED_WEBVIEW2_COMPATIBILITY`.

## 7. Release 3 follow-up

Recurring meetings and webinars require a separate validation pass after Release 1:

- recurring occurrence join/start and occurrence identity;
- webinar `userEmail` and registration requirements;
- webinar host/panelist/attendee role behavior;
- occurrence-level recording and attendance events;
- SDK parity between standard meetings and webinars;
- separate tenant capability and licensing checks.

## 8. Security evidence rules

- Do not commit SDK secrets, signatures, ZAK/OBF values, meeting passwords or raw participant data.
- Do not save authorization values in localStorage/sessionStorage.
- Do not put sensitive authorization values in deep links or query strings.
- Use synthetic users and masked provider IDs in screenshots/logs.
- Record only pass/fail, sanitized error code, SDK version, WebView2 version and capability outcome.
