# Video Production Readiness Checklist

**Status:** In progress — Phase 15 integration and production-readiness review  
**Scope:** YouTube, DynTube managed streaming, internal media, AOC Secure Player, web playback, watch-time, device/session controls  
**Environment policy:** synthetic data only; no production deployment or production credentials

## Release decision

- [ ] Named human approver:
- [ ] Approval date (ISO-8601, `YYYY-MM-DD`):
- [ ] Decision: `APPROVED` / `BLOCKED` / `DEFERRED`
- [ ] Explicit blockers and follow-up owners recorded:

## Required gates

| Gate | Evidence | Status |
|---|---|---|
| Backend compile and full Maven verification | `backend/mvnw.cmd verify` output and test count | Pending |
| Focused video integration tests | Upload, playback authorization, player exchange, heartbeat, watch-time, DynTube suites | Pending |
| Tenant isolation | Tenant A cannot read, upload, exchange, heartbeat, or configure Tenant B media | Pending |
| Failure handling | Provider outage, 401/timeout, malformed descriptor, revoked/expired session, Redis outage, empty playlist | Pending |
| Idempotency/concurrency | One-time launch code, duplicate exchange, heartbeat/session stop, upload retry semantics | Pending |
| Frontend mocked regression | `frontend` lint, build, Playwright | Pending |
| Real-backend two-tenant E2E | Local Compose staging-equivalent stack with synthetic tenants and role fixtures | Pending |
| Native player quality | Typecheck, fmt, clippy with warnings denied, Rust tests, release-feature build | Pending |
| DynTube external-origin gate | Non-whitelisted origin is refused; untokenized playback is refused | **Blocking** |
| DynTube authorized flow | Upload → ready → playback in each supported player path | **Blocking** |
| Production bridge configuration | `EMBED_HOST`, exact `EMBED_PARENT_ORIGINS`, `PLAYER_DYNTUBE_BRIDGE_URL`, compiled AOC bridge URL | Pending |
| Storage lifecycle/CORS | Multipart CORS exposes `ETag`; staging objects expire/abort per policy | Pending |
| Nginx security | API/page routing, forwarded host, launch-code redaction from logs, health behavior | Pending |
| Release signing/custody | Authenticode/Trusted Signing and updater key custody reviewed | Pending |
| Dependency/license audit | Backend, frontend, native-player dependencies reviewed | Pending |
| Upgrade/rollback rehearsal | Fresh synthetic DB migration and previously migrated DB upgrade verified | Pending |
| Observability | Bounded video metrics, safe structured logs, scrape access controlled | In progress |

## Supported mode matrix

| Provider/mode | Web | AOC Player | Server authorization | Status |
|---|---:|---:|---:|---|
| Internal progressive media | Existing path | Required when tenant policy requires it | Required | Pending final regression |
| YouTube standard | No direct student video id | Yes | Required | Pending final regression |
| DynTube EMBED | Current web behavior must be confirmed | Yes | Required | Blocking external-origin gate |
| Self-hosted HLS | No | No | Locked/off | Deferred; no implementation |

## Observability contract

The backend Phase 15 metrics use bounded labels only:

- `video.playback.authorizations{provider}`
- `video.playback.denials{reason}`
- `video.player.exchanges{outcome,provider|reason}`
- `video.playback.heartbeats{outcome}`
- `video.provider.latency{provider,operation,outcome}`
- `video.media.uploads{provider,outcome}`
- `video.media.upload.failures{provider,reason}`

Tenant IDs, asset IDs, session IDs, user IDs, IPs, tokens, launch codes, provider keys, and arbitrary exception text must not become metric labels. Prometheus export is disabled by default and must be explicitly enabled per environment. `/actuator/health` and `/actuator/info` remain the unauthenticated operational endpoints; Prometheus access requires an environment-level exposure decision and network control before production use.

## Known blockers and limitations

- DynTube must reject non-whitelisted/untokenized playback before any real tenant is enabled.
- Production bridge origins and compiled AOC bridge URL must be set explicitly and reviewed.
- Production deployment topology, container images, Nginx configuration, storage lifecycle/CORS, and release-signing custody require their approved ADR/review and are not implied by passing backend tests.
- This checklist is not a production approval. Only the named human approver can approve go-live.
