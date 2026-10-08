# AOC Secure Player (Windows)

The desktop player for lesson videos (ADR-025, `docs/adr/ADR-025-native-secure-player.md`).
Tauri 2 + WebView2. The Rust core holds the security and session layers; the UI is a small
Vite + TypeScript app bundled into the binary. It talks only to the four player endpoints in
`docs/api/aoc-player.md` and never holds an LMS login.

Phase 9 (this version) pairs, launches, keeps the session alive and stops it. Video engines
arrive in Phase 10; until then a claimed session shows the lesson title and watermark text.

## How it works

| Step | What happens |
|---|---|
| Pair | The Student's browser opens `aocplayer://pair?code=…`. The player creates a non-exportable ECDSA P-256 key in Windows CNG (in the TPM when available, otherwise the Microsoft Software Key Storage Provider) and sends the public key with the code to `POST /api/v1/player/pairings/complete`. |
| Play | The browser opens `aocplayer://play?code=…`. The player signs `POST /api/v1/player/exchange` with its key and gets a session, a short-lived player token and the descriptor. |
| Keep alive | A signed heartbeat every `heartbeatIntervalSeconds`. `CONTINUE` refreshes the token and the deadline. |
| Stop | `STOP`, a 401/403/404, a 426, or a missed deadline ends playback and drops the token and descriptor. "End session" or closing the window calls `/end` first. |

Links are parsed strictly (`src-tauri/src/deep_link.rs`): only `pair`/`play` with one 43-character
base64url `code`; anything else is refused before any network call. A link handed over twice
(the OS sometimes does) is handled once.

Signed requests carry `X-Player-Timestamp`, `X-Player-Nonce`, `X-Player-Signature` (ECDSA P-256 /
SHA-256, IEEE P1363 `r‖s`, base64url) over
`METHOD\nPATH\nTIMESTAMP\nNONCE\nbase64url(SHA-256(body))` - byte-for-byte the backend's
`PlayerRequestVerifier.canonical`. The player learns the server's clock from `Date` headers; if a
refused exchange reveals a clock more than 30 s off, it retries once with corrected time (a refused
exchange never consumes the code).

## Security properties

- **API origin is compiled in** (`AOC_API_ORIGIN`). `build.rs` refuses a release build without an
  `https://` origin; plain `http://` is accepted only for loopback in debug builds.
- **No secret on disk.** `%LOCALAPPDATA%\com.aoc.secureplayer\installation.json` holds only the
  installation id, the key's name and provider, and the pairing time. The private key stays in CNG.
  The player token lives in memory inside the session task only and is never given to the WebView.
- **WebView lock-down.** Strict CSP (`connect-src` is IPC only - the UI cannot call the network),
  navigation limited to the bundled UI, devtools off in release builds, and a capability file that
  grants the UI exactly `get_state`, `end_session` and event listening.
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

`tauri dev` registers the `aocplayer` scheme for the current user, so the LMS "Pair" and
"Open in AOC Player" buttons open this dev build. Point a dev build at another backend with
`$env:AOC_API_ORIGIN = "https://…"` before building.

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
- `vars.AOC_API_ORIGIN` (https) and `vars.AOC_UPDATER_ENDPOINT` (https `latest.json`).

Release builds use the `release` cargo feature, which adds the Tauri updater: it installs a signed,
newer release when nothing is playing. The server-side kill switch is `app.player.min-version`.
After a release is published, set the frontend's `NEXT_PUBLIC_AOC_PLAYER_DOWNLOAD_URL` so the
Student "AOC Player" page offers the download.

The icon in `app-icon.png` (and `src-tauri/icons/`, generated with `npx tauri icon app-icon.png`)
is a placeholder; the bundle identifier `com.aoc.secureplayer` must not change after the first
release (it names the app-data folder and the updater's install).
