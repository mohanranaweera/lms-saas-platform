# ADR-025: AOC Secure Player — Native Desktop Player, Launch-Code Exchange and Installation Keys

## Status

**Proposed (2026-10-07)**, blocker **B4** in
`docs/plans/multi-tenant video platform + aoc secure player.md` §0.3. Not accepted. No
player, launch-code, exchange or installation code may be written until the project owner
accepts this ADR.

This ADR touches four root `CLAUDE.md` change-controlled areas and needs the owner's explicit
sign-off on each one:

| Area | What changes |
|---|---|
| Required stack | Adds Rust + Tauri 2 for one new deliverable, `native-player/`. The backend and web frontend stacks are unchanged. |
| Authentication architecture | Adds a second kind of caller that is not an LMS user session: a paired player installation. It proves itself with a key pair, and the player gets a session-bound playback token. It never gets an LMS JWT. Adds one unauthenticated endpoint, `POST /api/v1/playback/exchange`, as an ADR-011-style carve-out. |
| Multi-tenancy strategy | Adds one named cross-tenant lookup, by launch-code hash (same pattern as ADR-011's `findByGatewayReferenceAcrossTenants`). |
| Production deployment strategy | Adds a signed Windows desktop binary with an auto-updater. Publishing a player release is a production release and always needs human approval. |

Not touched: payment ledger rules, enrollment activation rules (playback only *reads*
`AccessPolicyApi`), approved API contracts (all additions are new endpoints, B6), and
migration history (new tables are new migrations only).

## Context

- The video brief asks for an "AOC Secure Player": a desktop app that plays lesson video with
  screen-capture deterrence, a moving watermark, heartbeats and server-enforced watch time.
- A browser cannot stop screen capture. On Windows, `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)`
  hides a window from most capture APIs. Only native code can call it. That is the main reason
  for a native player.
- Phase 7 (B3, accepted 2026-10-06) built **WEB** playback: an authenticated Student calls
  `POST /api/v1/playback/sessions` and gets a descriptor. The native path was deferred to this ADR.
- The player is launched from a browser click through a custom URL scheme. Any local app can
  register a scheme or read the command line, and the Tauri docs warn that a user can pass a fake
  deep link as a command-line argument. So the deep link must carry nothing that is useful on its
  own, and the player must prove which installation it is.
- ADR-007 tokens are HS256 JWTs for browser sessions with refresh rotation. Putting a long-lived
  LMS credential on disk in a desktop app would let anyone who copies it act as the student
  across the whole LMS, not just video.

## Decisions

### 1. Stack: Tauri 2 (Rust core) + WebView2, Windows first

- New top-level `native-player/`: Tauri 2. The Rust core holds the security and session layers.
  The UI is a bundled TypeScript app built with npm (same language and tooling as `frontend/`;
  no Next.js inside the player).
- Plugins: `tauri-plugin-single-instance` with the `deep-link` feature, **registered first**;
  `tauri-plugin-deep-link` with the static desktop scheme `aocplayer`; `tauri-plugin-updater`.
- The API origin is compiled into the signed binary per environment. The player never takes an
  API host from a deep link, file or user input.
- WebView hardening: devtools off in release builds, strict CSP, a navigation allow-list (the
  bundled UI plus the descriptor's playback origins only), and a Tauri capability file that
  grants the UI only the commands it needs.
- Windows first. macOS and mobile are not designed out: the API and descriptor are platform-neutral
  (Q-015), but each new platform needs its own ADR amendment.
- The stack exception covers `native-player/` only. Nothing in `backend/` or `frontend/` may adopt
  Rust or Tauri because of this ADR.

### 2. Launch codes (`aocplayer://play?code=…`)

- When native playback is chosen, `POST /api/v1/playback/sessions` (Student JWT, unchanged checks)
  creates the session as `PENDING_LAUNCH` and returns `launch{url, expiresAt}`.
- New tenant-owned table `playback_launch_code`, as in plan §18: composite FK to the session,
  `code_hash = HMAC-SHA256(PLAYER_LAUNCH_CODE_PEPPER, code)` unique, status
  `ISSUED | CONSUMED | EXPIRED | REVOKED`, `CHECK (expires_at <= created_at + 60 s)`, and at most
  one `ISSUED` code per session (partial unique index).
- The code is 256 bits from a CSPRNG, base64url (43 characters), with a default TTL of 45 s. The
  plaintext is never stored, logged or audited. The Nginx access log strips the `code` parameter.
- The deep link carries the code only: no tenant, user, video, host or token. The player rejects
  any other host, path or extra parameter, and any overlong code, before making a network call.
- Single use is one atomic statement:
  `UPDATE … SET status='CONSUMED' … WHERE code_hash=? AND status='ISSUED' AND expires_at>now() RETURNING …`.
- **VID-ADR-008:** externally there is one error, `PLAYBACK_LAUNCH_CODE_INVALID` (400), for
  invalid, expired and already-used codes. The distinction goes only to the security log.

### 3. Installation identity (VID-ADR-009): key pair, not a bearer token

- New tenant-owned table `player_installation`, as in plan §19: `user_id`, `device_id` (composite FK
  to the ADR-022 `device`), `installation_id_hash`, `public_key`, platform, player version, status
  `ACTIVE | REVOKED`, revoke fields. Rows are never deleted.
- **Pairing (once per installation):** a signed-in Student in a device-bound browser session asks
  for a pairing code (`POST /api/v1/player/pairings`, Student JWT). The browser opens
  `aocplayer://pair?code=…`. The player creates a non-exportable key pair in Windows CNG
  (TPM-backed when available) and posts the public key with the pairing code. Pairing codes follow
  the launch-code rules in §2: hashed, short TTL, single use.
- The installation is bound to **the same device slot** as the browser that paired it (Q-008,
  recommended). It does not take an extra ADR-022 slot. A slot in cooldown cannot pair.
- No hardware fingerprinting. The installation id is random and stored hashed on the server.
- Every player request after pairing is signed with the installation key over
  `method, path, body hash, nonce, timestamp`. Nonces are kept in Redis for the timestamp window
  (`SET NX`) to block replay; a timestamp outside ±60 s is rejected.
- Revoking the device, the installation, or the student's access revokes the installation's
  active playback sessions through the existing `VideoPlaybackSessionRevokedEvent` path, and is
  audited (`DEVICE_REVOKED`).

### 4. The exchange endpoint carve-out (ADR-011 style)

`POST /api/v1/playback/exchange` `{launchCode, installationId, playerVersion, platform, nonce,
timestamp, signature}` → `PlaybackDescriptor`.

- `SecurityFilterChainConfig`: `permitAll()` for this **exact** path and method only, never a
  wildcard. `TenantResolutionFilter`: the same exact path goes on its exclusion list.
- Tenant identity comes **only** from the consumed launch-code row, through one named,
  reviewable method `PlaybackLaunchCodeRepository.consumeByCodeHashAcrossTenants`. The service sets
  `TenantContextHolder` to that row's `tenant_id` inside `try/finally` for that one transaction.
  The request body has no tenant field.
- Order of checks (any failure → generic error, no state change except the consumed code, and a
  security-log entry):
  1. Per-IP and per-installation Redis rate limits (generalize `AuthRateLimiter`).
  2. Atomically consume the code (§2).
  3. Find the installation in **that row's tenant**. It must be `ACTIVE`, belong to the same
     student, and sit on the device the code was issued for. Verify the signature with its stored
     public key, and check the nonce and timestamp.
  4. Re-run `AccessPolicyApi.requireStudentCourseAccess` and the Phase 7 playback checks (asset
     READY, provider not disabled, material visible and in window, flags on). Entitlement can
     change in the 45 s since the code was issued.
  5. `playerVersion >= minPlayerVersion`, otherwise `PLAYER_VERSION_UNSUPPORTED` (426).
  6. Mark the session `ACTIVE`, bind it to the installation, audit `PLAYBACK_AUTHORIZED`
     (channel NATIVE), and return the descriptor.
- The browser learns the launch succeeded by polling `GET /api/v1/playback/sessions/{id}/status`
  (Student JWT), never from anything the player sends it.

### 5. What the player holds after exchange

- A **playback token**: the existing ADR-008 JWT shape with `aud=player`, bound to the session id
  and installation, valid ≤5 minutes, refreshed only through a signed heartbeat. It authorizes
  that one session's heartbeat, end and media calls, and nothing else in the LMS.
- Heartbeat and end (`/api/v1/playback/sessions/{id}/heartbeat`, `/end`) accept this token
  **plus** the installation signature, not a Student JWT. A `STOP`, a 401/403, or missed
  heartbeats past the threshold make the player tear down playback and clear media state.
- The player never decides entitlement. A tampered player can only weaken deterrence
  (watermark, capture affinity), never access: every access decision stays on the server.

### 6. Who uses the native player

- Per-tenant `native_player_required` in `tenant_video_configuration`, default **false** (Q-009).
  When false, Phase 7 WEB playback is unchanged. When true, `POST /playback/sessions` returns
  `channel=NATIVE` with a launch link and never a WEB descriptor, and any modes without a web-safe
  engine also force NATIVE.
- YouTube mode stays "LMS-gated, not protected" (B3) even in the native player: the video ID is
  effectively public. Capture affinity and the outside-player watermark band are deterrence only.

### 7. Signed releases and updates

- Two signatures on every release: **Authenticode** on the installer and binary (cloud HSM /
  Trusted Signing), and the **Tauri updater signature** (Ed25519/minisign). The updater public key
  is compiled into the binary; Tauri refuses unsigned updates and this cannot be disabled.
- The private updater key is a CI secret in a protected environment with an offline backup.
  Losing it means installed players can no longer be updated, so its custody is part of
  acceptance.
- Updates are HTTPS only, with no downgrades. `latest.json` and the installers are served from
  object storage/CDN; `GET /api/v1/player/releases/latest?platform=` is public metadata only.
- The server-side `app.player.min-version` is the kill switch: an older player gets
  `PLAYER_VERSION_UNSUPPORTED` at exchange and heartbeat.
- CI: `.github/workflows/native-player.yml` on `windows-latest`. PRs run fmt, clippy, tests and
  build. A release runs only by manual dispatch in a protected, approval-gated environment.
  **Publishing a player release is a production release and is never automatic.**

### 8. New secret

`PLAYER_LAUNCH_CODE_PEPPER` is declared in all four ADR-019 places and validated at startup by
`ProductionSecretsStartupValidator`. The updater and Authenticode keys are CI-only and never
reach the backend.

## Consequences

**Positive**

- A stolen deep link is useless: it expires in 45 s, works once, and needs the paired
  installation's private key.
- No LMS credential is ever on disk in the player. A compromised installation exposes at most one
  playback session for ≤5 minutes, and it can be revoked.
- One entitlement path: the exchange and heartbeats call the same `AccessPolicyApi` as the web, so
  refunds, chargebacks, expiry and device revocation stop native playback too.
- Windows capture affinity becomes possible, which the web cannot offer.

**Negative / trade-offs accepted**

- A new language and toolchain (Rust) to maintain, review and patch, plus Windows code-signing
  cost and signing-key custody.
- `/api/v1/playback/exchange` is unauthenticated at the Spring Security layer. Its safety rests on
  the code, the installation signature and the access re-check inside the service. A broad
  refactor of `SecurityFilterChainConfig` must keep the matcher exact.
- This is a second path, after ADR-011, where `TenantContextHolder` is set in the service layer
  instead of the edge filter. Reviewers auditing tenant resolution must know both.
- Students must install the player and pair it once. Tenants that require it add support load.
- Capture deterrence does not stop cameras, HDMI capture, some GPU drivers, VMs, remote desktop or
  patched binaries. Product copy must say "deterrence", never "prevents recording".

## Alternatives considered

- **Electron.** Rejected: ships Node.js and Chromium in the binary, a larger attack surface and
  install size, and more work to lock down than Tauri's capability model.
- **Native .NET (WPF/WinUI).** Rejected: a second UI stack with no code shared with `frontend/`,
  and Windows-only by construction.
- **Browser-only playback (no native player).** Already built for WEB (Phase 7). Kept as the
  default; it cannot offer capture affinity.
- **Pass the LMS JWT or a refresh token in the deep link.** Rejected: any app that can read the
  command line or register the scheme gets the student's whole LMS session.
- **Bearer installation token instead of a key pair.** Rejected (VID-ADR-009): a copied token
  works from any machine; a non-exportable key does not.
- **Localhost callback server in the player.** Rejected: opens a local port other software can
  reach.
- **Separate error codes for invalid / expired / used launch codes** (as in the brief). Rejected
  (VID-ADR-008): it gives an attacker an oracle for probing codes.

## Decisions the owner must confirm with acceptance

1. Stack exception: Rust + Tauri 2 for `native-player/` only.
2. The exchange carve-out (§4) and the player auth model (§3, §5).
3. **Q-008:** a paired installation shares the browser's device slot (recommended), or counts as an
   extra device.
4. **Q-009:** per-tenant `native_player_required`, default false (recommended), or mandatory for
   everyone.
5. Windows only for now (Q-015).
6. Code-signing route (Azure Trusted Signing or a purchased OV/EV certificate) and who holds the
   updater key's offline backup.

## Implementation sequencing if accepted

Launch codes and the exchange need a paired installation to verify a signature against, so they
cannot be finished inside Phase 7 alone. Recommended order:

- **Phase 7 (current):** finish and commit the WEB scope unchanged.
- **Phase 8:** `player_installation`, pairing, `playback_launch_code`, exchange, signed
  heartbeat/end, `native_player_required`, device and installation revocation, admin revoke.
  Backend tests include: 10 concurrent exchanges (exactly one succeeds), expired and replayed
  codes, a wrong installation or key, revoked mid-session, and cross-tenant exchange/heartbeat
  (T8–T10).
- **Phase 9:** `native-player/` shell, deep link + single instance, exchange client, session
  lifecycle, updater foundation, CI; the frontend download and pairing pages.
- Phases 10–11: engines, watermark and capture affinity (needs Q-006 and Q-012).

## Related

- `docs/plans/multi-tenant video platform + aoc secure player.md` §16–§22, §28, §30–§33, §38
  (VID-ADR-008, VID-ADR-009)
- ADR-007 (tokens and devices), ADR-008 (playback token, single use), ADR-011 (carve-out
  pattern), ADR-019 (secrets), ADR-022 (device limits, `AccessPolicyApi`)
- `.claude/rules/security.md` (video and session protection), `.claude/rules/tenancy.md`
- `SecurityFilterChainConfig.java`, `TenantResolutionFilter.java`, `AuthRateLimiter.java`
