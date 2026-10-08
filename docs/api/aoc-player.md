# AOC Player API — Phase 8 (ADR-025 + Amendment 1)

The backend side of the native AOC Player: pairing an installation, launching NATIVE playback,
the exchange, signed heartbeats and revocation. The Windows player that calls these endpoints is
`native-player/` (Phase 9, see "The player (Phase 9)" below and `native-player/README.md`).
All responses use the standard `ApiResponse` envelope.

## Concepts

- **Installation** — a paired player: an ECDSA P-256 public key (X.509 SPKI, base64) bound to one
  Student's ADR-022 device. It shares that device's slot (Q-008). Re-pairing the same device
  replaces the previous installation. Rows are never deleted; revoking is a status change.
- **Pairing code / launch code** — 256-bit, base64url (43 chars), single use, stored only as
  `HMAC-SHA256(PLAYER_LAUNCH_CODE_PEPPER, code)`. Pairing codes live 90 s, launch codes the tenant's
  `launchCodeTtlSeconds` (15–60 s, default 45).
- **Player token** — a JWT with `aud=aoc-player`, bound to one session and installation, sent in
  `X-Player-Token`. It is refreshed by every heartbeat and is never accepted by any browser endpoint
  (and browser playback tokens are never accepted here).

## Signed requests (player → backend)

Exchange, heartbeat and end are signed by the installation key:

| Header | Value |
|---|---|
| `X-Player-Installation` | installation id (exchange only; heartbeat/end take it from the token) |
| `X-Player-Timestamp` | Unix epoch seconds; refused when more than 60 s from server time |
| `X-Player-Nonce` | 16–64 chars `[A-Za-z0-9_-]`, single use |
| `X-Player-Signature` | ECDSA P-256 / SHA-256, IEEE P1363 (`r‖s`, 64 bytes), base64url, over the bytes below |

```
METHOD \n PATH \n TIMESTAMP \n NONCE \n base64url(SHA-256(raw request body))
```

`PATH` is the request path without host or query, e.g. `/api/v1/player/exchange`.

## Student endpoints (Student JWT)

| Method & path | Result |
|---|---|
| `POST /api/v1/player/pairings` | `201 {pairingUrl: "aocplayer://pair?code=…", expiresAt}`. 409 `NATIVE_PLAYER_UNAVAILABLE` (platform flag off), 409 `PLAYER_DEVICE_NOT_REGISTERED` (this browser session has no active registered device), 403 for non-students. |
| `GET /api/v1/player/installations` | The caller's installations, newest first: `{id, deviceId, platform, playerVersion, status, pairedAt, lastSeenAt, revokedAt, revocationSource}`. |
| `POST /api/v1/player/installations/{id}/revoke` | Revokes the caller's own installation and ends its playback. 404 for anyone else's, 409 if already revoked. Audited `player.installation_revoked`. |
| `POST /api/v1/playback/sessions` `{lessonMediaId}` | `201 {sessionId, channel, expiresAt, descriptor, launch}` — see `playback-authorization.md`. NATIVE: `descriptor` is null and `launch = {url: "aocplayer://play?code=…", expiresAt}`. |
| `GET /api/v1/playback/sessions/{id}/status` | The caller's own NATIVE session: `{status: PENDING_LAUNCH\|ACTIVE\|ENDED\|REVOKED\|EXPIRED, exchanged}`. 404 for anyone else's. |

## Player endpoints (no JWT — ADR-025 Amendment 1)

These four are the only unauthenticated player paths (exact matchers). The tenant comes only from
a server-held row: the consumed pairing or launch code, or the verified player token.

### `POST /api/v1/player/pairings/complete`

Body `{pairingCode, publicKey, platform: "WINDOWS", playerVersion}` → `201 {installationId}`.
Failures: one generic `400 PLAYER_PAIRING_CODE_INVALID` (unknown/used/expired code, bad key, wrong
platform, revoked device); `426 PLAYER_VERSION_UNSUPPORTED`; `429 PLAYER_RATE_LIMITED` (20/min/IP).
A refused attempt does not consume the code. Audited `player.paired`.

### `POST /api/v1/player/exchange` (signed)

Body `{launchCode, playerVersion, platform}` →
`200 {sessionId, playbackToken, expiresAt, heartbeatIntervalSeconds, descriptor}`.
`descriptor.provider` is `YOUTUBE` (`providerVideoId`) or `INTERNAL` (`signedUrl`, ~3 min).

Checks, in order: code usable → installation ACTIVE, same student, same device the Student pressed
Play on → signature, clock, nonce → player version → session still pending → the same playability
rule as the browser (enrollment, course device limit on that device, visibility, window, READY,
flags) → other ACTIVE sessions of that video for the student are superseded. Audited
`video.playback_authorized` (channel NATIVE).

Failures: one generic `400 PLAYBACK_LAUNCH_CODE_INVALID` (the reason is only in the security log);
`426 PLAYER_VERSION_UNSUPPORTED`; `429 PLAYER_RATE_LIMITED` (30/min per IP, 20/min per installation + IP). A
request without the right key never consumes the code. Losing access revokes the pending session.
Concurrent exchanges of one code: exactly one succeeds.

### `POST /api/v1/player/sessions/{id}/heartbeat` (signed + `X-Player-Token`)

Body `{positionSeconds, state, playerVersion}` →
`200 {action: CONTINUE|STOP, reason, playbackToken, expiresAt, heartbeatIntervalSeconds, mediaUrl}`.

- `CONTINUE`: a fresh token and deadline (`max(60 s, 3 × heartbeatIntervalSeconds)`); `mediaUrl`
  is a fresh signed URL for INTERNAL media.
- `STOP` (`reason`: `ENDED`, `EXPIRED`, `DEVICE_REVOKED`, `INSTALLATION_REVOKED`, `ACCESS_REVOKED`,
  `ADMIN`, `SUPERSEDED_BY_NEW_SESSION`, `PLAYER_VERSION_UNSUPPORTED`): the player must tear down
  playback and clear media state. Missing a deadline is `EXPIRED`.
- `401 PLAYBACK_SESSION_EXPIRED`: missing/invalid/expired token, signature, clock or nonce.

Every heartbeat re-checks access on the installation's device. Watch-time accounting is Phase 12.

### `POST /api/v1/player/sessions/{id}/end` (signed + `X-Player-Token`)

Ends the session (idempotent). `401 PLAYBACK_SESSION_EXPIRED` as above.

## Staff endpoints (`DomainArea.DEVICES`)

`{studentId}` is the student **profile** id, as in `/api/v1/students/{studentId}/devices`.

| Method & path | Permission | Result |
|---|---|---|
| `GET /api/v1/students/{studentId}/player-installations` | VIEW | The student's installations. |
| `POST /api/v1/students/{studentId}/player-installations/{installationId}/revoke` `{reason}` | CREATE_EDIT | Revokes it and ends its playback. Reason required (≤ 500). Audited. |
| `POST /api/v1/playback/sessions/{id}/revoke` `{reason}` | CREATE_EDIT | Stops a live session (`STOP ADMIN` at the next heartbeat). 409 if already ended. Audited `video.playback_session_revoked`. |

Ids from another tenant are 404.

## Revocation cascade

- Resetting or removing an ADR-022 device (admin or student) publishes `DeviceRevokedEvent`; its
  installation is revoked (`DEVICE_REVOKED`) and its pending/active NATIVE sessions end — in the
  same transaction as the device revoke.
- Revoking an installation ends the sessions it claimed (`INSTALLATION_REVOKED`).

## Settings and kill switches

- `video.features.native-player` (platform) — off: pairing and exchange are refused; tenants that
  require the player get `409 NATIVE_PLAYER_UNAVAILABLE` on Play (never a silent WEB fallback).
- `tenant_video_configuration.native_player_required` (tenant, default false) — on: Play returns a
  NATIVE launch for YouTube and INTERNAL media, and the legacy `POST /api/v1/videos/{id}/playback-sessions`
  refuses Students with `409 NATIVE_PLAYER_REQUIRED`.
- `app.player.min-version` (`PLAYER_MIN_VERSION`, default `0.1.0`) — older players get 426 at pairing
  and exchange, and `STOP` at the next heartbeat.
- `PLAYER_LAUNCH_CODE_PEPPER` — required; blank fails closed (503).

## Frontend (Phase 8)

- **Student → AOC Player** (`(student)/student/player`, nav "AOC Player"): "Pair the AOC Player" asks
  for a pairing link and hands it to the OS from the click; the page re-reads the player list every
  3 s for up to 2 min until the new player appears. Lists the Student's players (active / removed and
  why) with a confirmed "Remove".
- **Lesson video** (Student lesson materials): when `POST /playback/sessions` answers `NATIVE` (YouTube
  path) or the legacy player gets `NATIVE_PLAYER_REQUIRED` (platform-storage path),
  `components/player/native-player-launcher.tsx` shows "Open in AOC Player". The click opens the
  one-time link (a fresh one when the current link was already used or is about to expire), then
  polls `GET /playback/sessions/{id}/status` every 2 s: "Playing in the AOC Player." once claimed;
  "The AOC Player didn't open." with a link to the setup page once the link expires unclaimed.
  `NATIVE_PLAYER_UNAVAILABLE` and `PLAYER_DEVICE_NOT_REGISTERED` have their own messages; nothing
  falls back to browser playback.
- `lib/player/native-launch.ts` only opens `aocplayer://(play|pair)?code=<43 chars>` links.
- On a non-Windows browser (phone, tablet, Mac) the launcher and the pairing card show "This video can
  only be watched on a Windows computer with the AOC Player" instead (user-agent check, UX only). A
  mobile app is planned (plan Phase 8 "Mobile devices").
- **Tenant Admin → Student → Devices**: an "AOC Player" section lists the student's players; Tenant
  Admin can remove one with a required reason. Device resets refresh it. The "Require the AOC Secure
  Player app" switch in Settings → Video configuration (Phase 3) is the tenant setting.
- Playwright: `e2e/aoc-player.spec.ts` (15 tests incl. phone and Mac, mocked backend; `window.__aocLaunch` captures links).

## The player (Phase 9)

`native-player/` is the client of the four player endpoints above; no endpoint changed.

- **Keys:** non-exportable ECDSA P-256 in Windows CNG - the TPM (Microsoft Platform Crypto
  Provider) when available, otherwise the Microsoft Software Key Storage Provider. The public key
  is sent as base64 X.509 SPKI; `NCryptSignHash` signatures are already IEEE P1363 `r‖s`. A TPM-made
  signature was verified with Java's `SHA256withECDSAinP1363Format` over the canonical bytes
  (2026-10-08).
- **Requests:** `platform: "WINDOWS"`, `playerVersion` = the binary's version. Timestamps use the
  server clock learnt from `Date` headers; a refused exchange that reveals a clock more than 30 s
  off is retried once. Heartbeats send `positionSeconds: 0`, `state: "IDLE"` until Phase 12
  (watch-time).
- **Teardown:** `STOP` (any `reason`), 401/403/404 (`EXPIRED`), 426, a non-`CONTINUE` action, or a
  missed `expiresAt` deadline (network trouble or 5xx are retried until then) end the session.
  "End session" and closing the window call `/end` (5 s cap). A new launch link ends the current
  session first.
- **Download:** the Student AOC Player page shows an "Install the AOC Player" card linking to
  `NEXT_PUBLIC_AOC_PLAYER_DOWNLOAD_URL` (https or a site path, build-time); unset, it says the
  download isn't available yet. Hidden on phones, tablets and Macs. Playwright covers both states.

## Playback in the player (Phase 10)

No endpoint or field changed; the player now uses the exchange `descriptor` and the heartbeat
`mediaUrl`:

- `provider: YOUTUBE` → `providerVideoId` (must match `^[A-Za-z0-9_-]{11}$`) is played with the
  official YouTube embed. Watermark band below the player, never over it (YouTube terms).
- `provider: INTERNAL` → `signedUrl` is played only if its origin is one of the player build's
  `AOC_MEDIA_ORIGINS` (the object-storage/CDN origin, i.e. `OBJECT_STORAGE_ENDPOINT`'s host in that
  environment). `allowSeeking: false` hides the seek bar and undoes forward jumps (deterrent; the
  server stays authoritative). Each `CONTINUE` heartbeat's `mediaUrl` is validated the same way and
  used when the current URL expires; a URL on any other origin is ignored.
- Any other provider, or a reference that fails validation: the player calls `/end` at once and
  shows "this video can't be played here" (`MEDIA_UNAVAILABLE`, player-side only).
- Deploy note: the player build for an environment must list that environment's storage/CDN
  origin in `AOC_MEDIA_ORIGINS`, or INTERNAL videos are refused by the player.

