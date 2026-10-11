# ADR-026: Managed Streaming — DynTube First (Signed HLS, Own Player)

> **Amendment 1 (2026-10-11, owner-approved): DynTube plays in DynTube's own player through an
> LMS bridge page.** It supersedes Decision 4 (Playback) for DynTube only. See
> [Amendment 1](#amendment-1-dyntube-player-via-an-lms-bridge-page-2026-10-11) at the end. The
> provider-neutral signed-HLS core (hls.js players, `Media::Hls`) stays for Bunny Stream.

## Status

**Accepted (2026-10-10)** by the project owner, including the additive API-contract changes below.
Proposed 2026-10-09 for VIDEO Phase 13
(`docs/plans/multi-tenant video platform + aoc secure player.md`). Answers **Q-002**.

Owner decisions already given (2026-10-09):

- Managed host: **DynTube first, Bunny Stream later** (Bunny gets its own adapter in a follow-up phase
  on the same core).
- Self-hosted streaming: **interface only**. `SELF_HOSTED` stays reserved and its flag stays locked.
- Playback: **our own HLS player** (hls.js) in the web player and the AOC Player. DynTube's embed
  player is not used.
- AOC Player transport: **DIRECT now**. The NativeMediaProxy (VID-ADR-011) moves to Phase 14.
- The owner has a DynTube account and will verify the live contract. Keys are typed into the
  Tenant Admin UI, never into files.

Change-controlled areas (root `CLAUDE.md`) touched, each needing sign-off:

| Area | What changes |
|---|---|
| Approved API contracts | **Additive only.** `PUT /tenant-admin/integrations/dyntube` gains an optional write-only `tokenSecurityKey` and a `projectId`. The Media Library upload request gains a destination value `DYNTUBE`. The playback descriptor gains `hls` fields. No field is removed or renamed. |

Not touched: multi-tenancy strategy (no cross-tenant lookup, no webhook), authentication
architecture (no new caller type, no new unauthenticated endpoint), payment ledger and enrollment
activation rules (playback only *reads* `AccessPolicyApi`), production deployment strategy, and
migration history (one new migration).

## Context

- ADR-018 made DynTube and Bunny.net the tenant "video hosts" for protected paid content
  (`tenant_media_integration_settings.video_host = NONE | DYNTUBE | BUNNY`). Until now only the
  DynTube API-key check exists (`TenantVideoHostClient.verifyDynTube`), and that check was never
  confirmed against a real account.
- YouTube mode can't be truly protected. The video id stays playable on youtube.com, nothing may be
  drawn over the player, and we can't stop the bytes. Institutes selling paid video need a host
  where the LMS controls access to the bytes.
- DynTube's public material confirms the following:
  - Management API at `https://api.dyntube.com/v1` and binary upload at
    `https://upload.dyntube.com/v1/videos`, both with `Authorization: Bearer <apiKey>`.
  - Projects (`GET /projects`) and processing status (`GET /videos/{id}/status`).
  - **Token authentication.** A temporary HLS URL of the form `…/{videoKey}.m3u8?token=…&expires=…`
    is generated from a per-account *security key*, the video key and a Unix expiry (optional
    country limits).
- DynTube's developer reference renders client-side and couldn't be read in full. These items are
  **unverified** until checked with the owner's account (see Verification):
  - the exact token algorithm;
  - whether segments are protected or only the playlist;
  - the binary upload shape and whether it can resume;
  - the CDN host names;
  - the response field names.

## Decision

1. **Provider = concrete host.** Add `MediaAssetProvider.DYNTUBE` (and later `BUNNY`). It feeds
   `PlaybackMode.MANAGED_STREAM`. Application code asks `VideoProviderCatalog` for capabilities and
   never branches on the provider name. DynTube capabilities: upload, adaptive streaming,
   custom player, signed playback, overlay watermark, processing status, thumbnail, and
   server-side revocation (bounded by token TTL). No DRM and no provider player.
2. **Tenant connection.** The existing tenant DynTube connection
   (`media_provider_connection`, scope TENANT) is reused.
   - The token security key is sealed into the **same** AES-256-GCM credential blob as the API
     key (VID-ADR-005). It is write-only: never returned, logged, audited or sent to a browser or
     player.
   - The target `projectId` is non-secret configuration. It is checked against `GET /projects`
     when saved.
   - Saving or replacing keys is audit-logged without the values.
3. **Upload** reuses the Phase 4 pipeline (VID-ADR-004):
   - The browser uploads with presigned multipart to S3 staging. The background worker then
     **streams** the staged object to DynTube's binary upload in bounded memory, never on a
     request thread.
   - A DynTube adapter implements the provider upload port. Status sync polls
     `GET /videos/{id}/status` through the existing `MediaStatusSyncService`.
   - **No webhook endpoint**, so no new unauthenticated surface and no cross-tenant lookup.
   - Upload-by-URL (DynTube pulls a presigned staging URL) is a possible later optimisation. It
     isn't used now because local MinIO isn't reachable from DynTube.
4. **Playback.**
   - The backend builds the tokenized HLS URL itself, for the asset's own tenant connection only.
     Expiry is at most the session's playback-token lifetime (≤ 5 min).
   - The URL is renewed on each heartbeat while the session is ACTIVE and authorization
     (enrollment, device, watch time) still passes. A revoked, expired or exhausted session gets
     no new URL.
   - WEB: a new hls.js player in the lesson page, with the existing overlay watermark and Phase 12
     accounting.
   - NATIVE: a new `hls` engine in `native-player/`. DIRECT transport. The DynTube hosts are a
     **compiled-in allow-list** in the Rust media validator and the player CSP
     (`connect-src`/`media-src`, `blob:` for MSE). Capture protection and the moving overlay
     watermark apply as for INTERNAL media.
5. **Delete** is an LMS soft delete only; the video stays in DynTube (same as Q-017 for YouTube).
   Metadata edits sync to DynTube only if its API supports it (verification).
6. **Flags.**
   - `video.features.managed-stream-playback` is unlocked now that an implementation exists. It
     stays off by default.
   - A new `video.dyntube-upload.enabled` flag also defaults to off.
   - A platform flag OFF overrides any tenant choice. `self-hosted-playback` stays locked.
7. **Bunny later.** The core (provider port, descriptor, hls players, allow-list mechanism) is
   provider-neutral. Bunny needs only an adapter, a catalog entry, allow-list hosts and an
   ADR-018 amendment.

## Security consequences

- **Honest limit:** if DynTube's token protects only the playlist and not the segments, someone
  who captures the playlist inside its expiry window could fetch the segments. Mitigations:
  short expiry, per-session URLs, renewal tied to heartbeats, and an overlay watermark for
  attribution. This is deterrence, not DRM, and the UI and docs say so.
- No SSRF: only the fixed configured DynTube base URLs are called. Video keys returned by DynTube
  are validated (charset and length) before they're stored or put into a URL.
- The token security key never leaves the backend. Without it, a player or browser can't mint
  URLs.
- Tenant isolation: the asset row carries `tenant_id` and `provider_connection_id` (composite FK).
  URL signing loads the connection through the tenant filter. Cross-tenant negative tests are
  required on upload, status, playback and heartbeat renewal.

## Alternatives considered

- **Bunny Stream first.** Better documented (TUS, CDN token auth, webhooks) and partly integrated
  (per-teacher libraries). The owner chose DynTube first; Bunny follows on the same core.
- **DynTube embed iframe.** Less work, but no overlay watermark, weaker state reporting, and a
  third-party page inside the AOC Player. Rejected by the owner.
- **NativeMediaProxy now.** Stronger (no CDN URLs in the WebView, IP checks) but a large amount of
  Rust. Deferred to Phase 14.
- **Self-hosted HLS transcoding (ffmpeg + MinIO).** Out of scope; interface only.

## Confirmed from DynTube's developer reference (2026-10-10)

Read at `https://www.dyntube.com/dev/videos/*`:

- **Upload.** `POST https://upload.dyntube.com/v1/videos`, `multipart/form-data`.
  - Fields: `file` (required), `projectId`, `title`, `description`, `tags` (repeatable),
    `planType`, `uploadId`, and `videoId` (replaces an existing video).
  - Response: `{videoKey, uploadId, videoId, channelKey, privateLink, iframeLink}`.
  - It is one request, not resumable. The worker therefore streams the staged object in a single
    multipart request, retries the whole request, and replaces on retry via `videoId` when
    DynTube already created the video.
- **Status.** `GET /videos/{id}/status`. Values: `0` not available, `1` uploaded,
  `2` processing, `3` published (ready), `-1` failed, `50` deleted.
- **Video.** `GET /videos/{id}` returns `id`, `key` (the token `videoKey`), `duration`
  (`"HH:MM:SS.fffffff"`), `projectId`, `formats{hls,mp4}`, `hlsUrl`/`hlsUrlWeb` (only when direct
  HLS links are on the plan), `status`, `statusName`, `title`, `description`.
  - DynTube warns that this API is rate-limited and must not be called just before playback. We
    store `key` at sync time and mint URLs locally, so DynTube is never called on the play path.
- **Edit and delete.** `PUT /videos/{id}` and `DELETE /videos/{id}` exist. LMS delete stays soft
  (Decision 5). Metadata edits may sync.
- **Token.** `Base64url(SHA256(securityKey + expires + countries + ips + videoKey))`.
  - Base64 `+`→`-`, `/`→`_`, `=` and newlines removed. If `views > 0`, it is appended.
  - URL: `https://api.dyntube.com/v1/live/videos/tokens/{videoKey}.m3u8?token=…&expires=…`.
  - Optional `ips` can bind the URL to the viewer's IP. That is a possible later hardening; it
    isn't used now because a corporate/NAT or proxy IP mismatch would break playback.
- **Security key** is set in the DynTube dashboard at Projects → Global Settings → API keys.

## Live verification with the owner's account (2026-10-10)

Read-only probe. Keys were read from a local file and never printed.

| Check | Result |
|---|---|
| `GET /projects` | `{pager{page,totalPages,totalResults,sort}, projects[{id,name,…}]}`. The owner's account has one project per subject library. |
| Plan | `Pro`, `type 1`. Direct HLS links are on (`hlsUrl`, `hlsUrlWeb` populated, `formats.hls = true`). |
| `GET /videos/{id}` | Matches the docs, plus `image{xsUrl,smUrl,mdUrl,lgUrl}`, `sizeBytes` and `region`. `statusName` is absent from the single-video response. `key` is 22 chars `[A-Za-z0-9_-]`. |
| Token `expires` | **Milliseconds.** A seconds value is refused (403). |
| Bad or expired token | 403 `text/plain`. |
| Request without a browser `User-Agent` | **Empty 200**, not an error. So: (a) DynTube is never called from the server on the play path (already the design); (b) players must treat an empty playlist as an error. |
| CORS | The requesting `Origin` is reflected on the playlist, and segments send `*`. The web player can load directly. |
| Hosts | Master playlist: `api.dyntube.com`. Variant playlists, segments and keys: `cdn-s1.dyntube.net` (region-numbered `cdn-s*.dyntube.net` assumed). The allow-list is `api.dyntube.com` + `*.dyntube.net`, HTTPS only. |
| Segments | AES-encrypted (`#EXT-X-KEY`). The CDN URLs carry their own `md5` + `expires` signature: unsigned or tampered requests get 403. **That signature is valid for about 12 hours**, independent of our token. |
| **AES key (2026-10-11)** | The variant playlists' `#EXT-X-KEY` points at `https://api.dyntube.com/v1/live/videos/hls-key/{id}/{videoKey}`. On the owner's project this answers **400** to every origin tried (LMS host, `tauri.localhost`, DynTube's own domains, none), from curl and from Chrome with hls.js, so segments can't be decrypted and nothing plays. To resolve with the owner's DynTube settings / support (likely the project's Video Encryption / Extra Security / domain-whitelist setup). Both players now show a "video host refused to unlock this video" message instead of retrying. |
| **Untokenized playlist** | **`/live/videos/{key}.m3u8` and `hlsUrl` play without any token** (with a browser `User-Agent`). The `key` is visible in every tokenized URL and in the CDN path. |

### Consequences (accepted into this ADR)

1. **Token auth alone doesn't protect the account's videos today.** Any student who sees a
   tokenized URL can build the untokenized one. Before `managed-stream-playback` is enabled for
   any real tenant, the DynTube project must refuse untokenized playback. Options, in order:
   - DynTube support confirms a "token required" project setting, and the owner turns it on;
   - otherwise, Domain Whitelisting + Video Encryption limited to the LMS and AOC Player origins.
     That relies on Referer/Origin, so it is a weaker deterrent.

   The Tenant Admin DynTube card will state this requirement. The owner tracks it as an
   operational prerequisite (Phase 13 Before block). **"Extra Security" must stay off**, because
   it limits playback to DynTube's own player.
2. **Revocation granularity.** Our token bounds the *start* of playback (≤ 5 min). A student who
   already loaded the variant playlists keeps CDN access for up to about 12 hours. Server-side
   enforcement still holds for anything that goes through us (heartbeat STOP, watch time, new
   sessions), and the overlay watermark gives attribution. Documented as an honest limit.
3. **No IP binding for now** (the `ips` token parameter). NAT, proxies and mobile networks would
   break playback. It can be revisited per tenant later.

## Verification required before enabling in any real environment

To check with the owner's DynTube account. Live calls are made by the owner; responses are shared
with secrets removed.

Items 1, 3, 4, 5 and 6 were confirmed on 2026-10-10 (see the live verification section above).
Still open:

1. The upload size limit, checked at the first real upload in 13b's manual run.
2. **The DynTube project refuses untokenized playback** (consequence 1). Re-run the probe; the
   "untokenized playlist" row must show 403.
3. An end-to-end upload → publish → tokenized playback, in the web player and the AOC Player.

Until all six are confirmed, the adapter is built and tested against a fake DynTube server, and
the flags stay off.

## Amendment 1: DynTube player via an LMS bridge page (2026-10-11)

### Status

**Accepted (2026-10-11)** by the project owner, together with one new Flyway migration. It
supersedes Decision 4 for DynTube, consequence 1 and the "DynTube embed iframe" rejection in
§Alternatives. Decisions 1–3 and 5–7 stand.

### Why

The live run on 2026-10-11 showed the signed-HLS design can't work with DynTube's protections on:

- The variant playlists' AES key URL (`api.dyntube.com/v1/live/videos/hls-key/…`) answers **400**
  to our hls.js players from every origin.
- DynTube's docs confirm why. **Video Encryption** serves keys only to whitelisted domains, and
  **Extra Security** plays only in DynTube's own player ("third-party video players will not be
  able to play the content").

Turning those protections off to suit our player would also re-open consequence 1 (untokenized
playlists). The owner chose to keep every DynTube protection on and play in DynTube's player.

### Decision

1. **Bridge page on its own origin.**
   - A small static page, `dyntube-bridge.html`, is served from a dedicated **embed origin**
     (`video.embed-origin`; e.g. `https://embed.<platform-domain>`, and `http://embed.lms.test:3000`
     in dev). It is never served from a tenant's LMS origin.
   - The page loads DynTube's JS embed (`embed.dyntube.com`). That puts DynTube's player in a page
     we control, so it can read the player's `<video>` events.
   - DynTube's script runs only on the embed origin. That origin holds no LMS session, cookies or
     storage, and its page has no LMS API access.
   - Each tenant whitelists **only the embed origin** in its DynTube project.
2. **Who may frame the bridge.**
   - The bridge's CSP is `frame-ancestors` set to the platform's LMS origins and the AOC Player
     (`http://tauri.localhost`). It is `script-src 'self' https://embed.dyntube.com` plus DynTube's
     CDN hosts.
   - A copied embed key therefore doesn't play on another site: DynTube refuses non-whitelisted
     domains, and other sites can't frame the bridge.
3. **Protocol (postMessage, origins checked on both sides).**
   - Parent → bridge: `load {embedKey, watermark?, allowSeeking, startAt}` and `stop`.
   - Bridge → parent: `ready`, `state {positionSeconds, state: PLAYING|PAUSED, durationSeconds}`
     (on change and at most every 2 s), and `error {code}`.
   - The bridge accepts a key only from an allowed parent origin and only once per load.
4. **Playback descriptor.**
   - DynTube sessions return `mediaFormat: "EMBED"` with `embed: {provider: "DYNTUBE", key,
     bridgeUrl}` instead of a signed URL.
   - The key is issued only after the full playability check (enrollment, device, watch time,
     session ACTIVE), exactly as before.
   - A revoked, stopped or exhausted session tears down the iframe: the web player on its next
     heartbeat or progress answer, the AOC Player on its heartbeat STOP.
5. **Players.**
   - **Web:** `SecureVideoPlayer` frames the bridge and uses its `state` messages for Phase 12
     progress, resume and the seek deterrent, exactly as for `<video>`. The overlay watermark is
     drawn by the parent over the iframe.
   - **AOC Player:** a new `embed` engine. Rust accepts `Media::Embed` only for a key matching the
     DynTube charset, and only with the **compiled-in** bridge URL (no URL from the server is
     framed). The CSP's `frame-src` adds the embed origin only.
   - Capture protection and the moving overlay watermark apply as for other media.
6. **Embed key storage.**
   - DynTube's `channelKey` (the JS-embed key) is stored in a new nullable
     `video_asset.provider_embed_key`. A CHECK enforces the URL-safe charset and allows it only on
     DYNTUBE rows.
   - It is filled from the upload response and from status sync (`GET /videos/{id}`), so existing
     videos get it on their next sync.
   - It is not a secret: DynTube puts it in public embed code. Its protection is the domain
     whitelist plus the bridge's `frame-ancestors`.
7. **Token key.** The token security key is no longer needed to play DynTube video. It becomes
   optional, no longer gates uploads, and stays sealed and write-only if saved. Signed HLS remains
   the core for Bunny.

### Security consequences

- **Kept:** DynTube's encryption, Extra Security and domain whitelisting stay on. Video bytes and
  keys are served only to DynTube's player on the whitelisted embed origin.
- **Honest limit:** the embed key isn't a per-session secret. Anyone who sees it (devtools) could
  play the video only by framing our bridge, which `frame-ancestors` allows only for LMS origins
  and the AOC Player. A logged-in user with devtools on an LMS page could still frame the bridge
  outside our player, skipping watch-time accounting.
  - This is the same class of limit as the YouTube mode, and weaker than per-session signed URLs.
  - Mitigations: keys are handed out only after authorization, the overlay watermark gives
    attribution, and DynTube's own watermark parameter can carry the student id.
- **Third-party script isolation:** DynTube's script never runs on an LMS origin or in the AOC
  Player's main window (which holds the Tauri IPC). In the AOC Player it runs in a cross-origin
  iframe without IPC access.
- **Tenant isolation is unchanged:** the key is read from the asset row through the tenant filter.
  Cross-tenant negative tests cover the new descriptor.

### Owner verification before build

1. The `channelKey` field name in DynTube's upload response and in `GET /videos/{id}`. A
   redacted response is enough.
2. The test video plays through the JS embed on the whitelisted dev embed origin, with Encryption
   and Extra Security on.
3. DynTube's whitelist accepts the dev host (`embed.lms.test`).

**Results (2026-10-11; read-only probe, API key never printed):**

- **(1) Confirmed.**
  - `GET /videos/{id}` returns `channelKey` (the JS-embed key) and `iframeLink` (the iframe key),
    besides `key` (HLS) and `privateLink`/`hlsLink`.
  - Neither our stored `key` nor the `videoId` works as an embed key.
- **(2) Confirmed.** On `embed.lms.test`, DynTube's JS embed plays the encrypted video:
  - its player fetches keys from `player-hls-key/{playerSession}/…`;
  - the `<video>` element is in the bridge page's DOM, so state can be read.
- **(3) NOT enforced.** The same `channelKey` plays through the JS embed on `https://example.com`.
  The iframe link also loads there.
  - So, as configured, the whitelist protects neither embed path.
  - **Building waits until the owner's DynTube project refuses a non-whitelisted origin**, which
    is re-tested from `example.com`.
  - Without that, the embed key alone plays anywhere, and the bridge's `frame-ancestors` adds
    nothing.

**Owner decision (2026-10-11): build before (3) is fixed.** The owner chose to build the bridge
now and accepted the risk that, until the DynTube project refuses non-whitelisted origins, anyone
who sees an embed key (devtools) can play that video on any site without login, watermark or
watch-time limits. **Before any real tenant uses DynTube**, the whitelist must be enforced and the
`example.com` re-test above must fail. The migration number is V83 (V82 was taken by
`V82__material_soft_remove.sql`).

**As built (2026-10-11), differences from the decision above:**
- DynTube lessons launch only in the AOC Player (like YouTube, ADR-025 Amendment 3). The web
  player's embed branch is not built; it shows "This video plays in the AOC Player".
- The bridge is a Next route handler (`/embed/dyntube-bridge.html`) rather than a static file, so
  it can refuse any Host but the embed host and send a per-response nonce CSP.
- Playback controls are DynTube's own; the bridge applies the seek rule on its `<video>`.
- An ACTIVE DynTube connection is still required to play (a disconnected account stops playback).
- The bridge CSP allows `'unsafe-eval'`: DynTube's player evaluates strings as code and showed a
  black screen without it (live run 2026-10-11). It is confined to the embed origin, which holds
  no LMS session, cookies or storage.
- Production needs `EMBED_HOST`, `EMBED_PARENT_ORIGINS`, `PLAYER_DYNTUBE_BRIDGE_URL`, an AOC
  release built with `AOC_EMBED_BRIDGE_URL`, and the release CSP's `frame-src` set to that origin.
