# AOC Secure Player (Windows)

The desktop player for lesson videos (ADR-025, `docs/adr/ADR-025-native-secure-player.md`).
Tauri 2 + WebView2. The Rust core holds the security and session layers; the UI is a small
Vite + TypeScript app bundled into the binary. It talks only to the four player endpoints in
`docs/api/aoc-player.md` and never holds an LMS login.

It pairs, launches, keeps the session alive, plays the lesson video (Phase 10) and stops it.
Phase 11 adds Windows capture-affinity deterrence and a moving watermark for platform-hosted media;
adaptive streaming (HLS) remains Phase 13.

## How it works

| Step | What happens |
|---|---|
| Pair | The Student's browser opens `aocplayer://pair?code=…`. The player creates a non-exportable ECDSA P-256 key in Windows CNG (in the TPM when available, otherwise the Microsoft Software Key Storage Provider) and sends the public key with the code to `POST /api/v1/player/pairings/complete`. |
| Play | The browser opens `aocplayer://play?code=…`. The player signs `POST /api/v1/player/exchange` with its key and gets a session, a short-lived player token and the descriptor. |
| Keep alive | A signed heartbeat every `heartbeatIntervalSeconds`. `CONTINUE` refreshes the token and the deadline. |
| Play video | Rust picks and validates the engine (`src-tauri/src/media.rs`) and the UI plays it (`src/engines/`). Each heartbeat brings a fresh signed URL for platform-hosted files. |
| Stop | `STOP`, a 401/403/404, a 426, or a missed deadline destroys the engine and drops the token and descriptor. Closing the window (✕) calls `/end` first. A `STOP` for `INSTALLATION_REVOKED`/`DEVICE_REVOKED` also forgets the local pairing, so the next launch asks to pair again. |

Links are parsed strictly (`src-tauri/src/deep_link.rs`): only `pair`/`play` with one 43-character
base64url `code`; anything else is refused before any network call. A link handed over twice
(the OS sometimes does) is handled once.

Signed requests carry `X-Player-Timestamp`, `X-Player-Nonce`, `X-Player-Signature` (ECDSA P-256 /
SHA-256, IEEE P1363 `r‖s`, base64url) over
`METHOD\nPATH\nTIMESTAMP\nNONCE\nbase64url(SHA-256(body))` - byte-for-byte the backend's
`PlayerRequestVerifier.canonical`. The player learns the server's clock from `Date` headers; if a
refused exchange reveals a clock more than 30 s off, it retries once with corrected time (a refused
exchange never consumes the code).

## Playback (Phase 10)

While a video plays the window shows only the video, its control bar and the watermark line;
the whole frame is shown letterboxed (never cropped). The window opens centred.

| Descriptor `provider` | Engine | Notes |
|---|---|---|
| `DYNTUBE` (`EMBED`) | `embed.ts`: DynTube's own player inside the bridge page compiled in as `AOC_EMBED_BRIDGE_URL` (debug default `http://embed.lms.test:3000/embed/dyntube-bridge.html`); only the embed key comes from the server. Cross-origin frame without pop-ups; state comes back over origin-checked postMessage; overlay watermark on top (ADR-026 Amendment 1). |
| `YOUTUBE` | `youtube.ts`: the official embed (`youtube-nocookie.com/embed/<id>`, documented parameters only) | Id must be `^[A-Za-z0-9_-]{11}$`. Sandboxed cross-origin frame (no top navigation, no pop-ups, no picture-in-picture), not clickable; our control bar drives it. YouTube's title bar and "Watch on YouTube"/share buttons are clipped out of view, start/end screens are covered, and the moving watermark is drawn over the video (ADR-025 Amendment 4, owner-accepted YouTube terms risk). LMS-gated, not protected (B3). |
| `INTERNAL` | `file.ts` + `controls.ts`: `<video>` on the signed MP4 URL | URL must be on an origin in `AOC_MEDIA_ORIGINS`. Own control bar: play/pause, −10 s, seek bar (only when `allowSeeking`), time, mute + volume, speed 0.75–2×, full screen; keys Space/K, ←/J, →/L (seeking only), ↑/↓, M, F. No download, PiP or casting. A fresh URL is swapped in only when the current one fails (expired), resuming at the same point. Single progressive file: no adaptive bitrate until Phase 13. |
| anything else | refusal (FutureProviderEngine) | The session is ended at once and the player says the video can't be played here. |

## Capture deterrence and watermark (Phase 11)

- On Windows 10 version 2004 and later, the Rust shell applies
  `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)` to the AOC Player window before required
  playback is shown, and clears it when the session ends or the window closes.
- This is a capture deterrent, not DRM: external cameras, HDMI/hardware capture, some GPU
  drivers, virtual machines, remote desktop paths and modified binaries may still record the
  display. The player and tenant settings use that wording deliberately.
- If a tenant requires the control and Windows cannot apply it, the exchange is ended and the
  player shows `CAPTURE_PROTECTION_UNAVAILABLE`; it never silently starts unprotected playback.
- INTERNAL/platform-hosted media may show a bounded, moving watermark inside the video stage.
  YouTube always shows the watermark in the band outside the cross-origin iframe; no overlay is
  drawn over the YouTube player.
- The server remains authoritative for tenant policy and entitlement. The UI receives only the
  sanitized capability status (`NOT_REQUESTED`, `APPLIED`, `UNSUPPORTED` or `FAILED`) and never
  receives a window handle, player token or tenant credential.

## Security properties

- **API origin is compiled in** (`AOC_API_ORIGIN`). `build.rs` refuses a release build without an
  `https://` origin; plain `http://` is accepted only for loopback in debug builds.
- **Media origins are compiled in** (`AOC_MEDIA_ORIGINS`, comma-separated bare origins). Release
  builds require https origins; debug builds default to the local MinIO (`http://localhost:9000`).
  The CSP's `media-src` matches (the release workflow writes it from the same list) and `frame-src`
  is only `https://www.youtube-nocookie.com`.
- **No secret on disk.** `%LOCALAPPDATA%\com.aoc.secureplayer\installation.json` holds only the
  installation id, the key's name and provider, and the pairing time. The private key stays in CNG.
  The player token lives in memory inside the session task only and is never given to the WebView.
- **WebView lock-down.** Strict CSP (`connect-src` is IPC only - the UI cannot call the network),
  navigation limited to the bundled UI, devtools off in release builds, and a capability file that
  grants the UI exactly `get_state` and event listening (the UI cannot end, start or change
  anything; closing the window ends the session in Rust). The YouTube frame is a remote origin and
  gets no IPC access.
- **No redirects followed**, responses capped at 64 KiB, server codes shown only if they look like
  codes (`[A-Z0-9_]`).
- **The player decides nothing.** It plays only while the server answers `CONTINUE`.

## Develop

Prerequisites (Windows): Node 24, Rust stable (MSVC toolchain, `rustup`), Visual Studio 2022 with
**MSVC v143 C++ x64/x86 build tools** and a Windows SDK, and WebView2 (built into Windows 11).

```powershell
cd native-player
npm install
npm run tauri dev          # debug build against http://localhost:8080
```

For the LMS "Pair" and "Open in AOC Player" buttons, build the local player once (and again after
changing the player):

```powershell
npm run build:local        # tauri build --debug --no-bundle, UI compiled in
```

It copies the exe to `src-tauri/target/local-player/` and registers the `aocplayer` scheme for the
current user to that copy, so links work whether or not Vite / `tauri dev` is running (still a debug
build against `localhost`). Close the player before rebuilding: Windows cannot replace a running
exe. `tauri dev` builds load the UI from Vite and therefore do not register the scheme; a link still
reaches a running `tauri dev` instance through the single-instance plugin. Keep `npm run dev` (or
`npm run tauri dev`) running while testing UI changes. Point a dev build at another backend with
`$env:AOC_API_ORIGIN = "https://…"` (and `$env:AOC_MEDIA_ORIGINS`) before building. Dev builds keep
debug info only for this crate (`[profile.dev]`), so `src-tauri/target` stays around 3–4 GB.

> Run cargo from PowerShell or cmd. In Git Bash, `/usr/bin/link` shadows the MSVC linker and builds
> fail with `link: extra operand`.

Checks (the same as CI, `.github/workflows/native-player.yml`):

```powershell
npm run build                                   # tsc + vite
cd src-tauri
cargo fmt --check
cargo clippy --all-targets -- -D warnings
cargo clippy --all-targets --features release -- -D warnings
cargo test                                      # add `-- --include-ignored` to also test a TPM key
```

The player tests run the full pair → exchange → heartbeat → stop lifecycle against a mock server
and verify every signature with the installation's public key, as the backend does.

## Release (not yet enabled)

Publishing a player release is a production release and always needs human approval (ADR-025 §7).
The `release` job in `native-player.yml` runs only by manual dispatch in the protected
`player-release` environment, and it refuses to run until these are decided and configured:

- the **code-signing route** (Azure Trusted Signing or a purchased certificate) - `vars.AOC_SIGNING_ROUTE`
  plus the matching signing step, which still has to be added;
- the **updater key**: generate with `npx tauri signer generate`, store the private key as
  `secrets.TAURI_SIGNING_PRIVATE_KEY` (+ password) with an offline backup, and the public key as
  `vars.AOC_UPDATER_PUBKEY` - losing the private key means installed players can never be updated;
- `vars.AOC_API_ORIGIN` (https), `vars.AOC_MEDIA_ORIGINS` (bare https storage/CDN origins,
  comma-separated; also written into the release CSP) and `vars.AOC_UPDATER_ENDPOINT` (https
  `latest.json`).

Release builds use the `release` cargo feature, which adds the Tauri updater: it installs a signed,
newer release when nothing is playing. The server-side kill switch is `app.player.min-version`.
After a release is published, set the frontend's `NEXT_PUBLIC_AOC_PLAYER_DOWNLOAD_URL` so the
Student "AOC Player" page offers the download.

The icon in `app-icon.png` (and `src-tauri/icons/`, generated with `npx tauri icon app-icon.png`)
is a placeholder; the bundle identifier `com.aoc.secureplayer` must not change after the first
release (it names the app-data folder and the updater's install).
