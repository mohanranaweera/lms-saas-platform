# ADR-008: Playback Token Mechanism, Single-Use Enforcement, and Deterrence Scope

## Status

Accepted (2026-08-02). Drafted during a pre-implementation readiness review to resolve
the open items listed in `docs/architecture/video-content-security.md` §12. Not yet
implemented — no video/content-management code exists yet — but
`video-access-management`/`content-management` playback-token and upload-validation
code may now be written against the decisions below.

This ADR deliberately does **not** select a named storage/video vendor — provider
selection remains open per `docs/architecture/integration-architecture.md`
("Payment gateway provider — not selected... provider TBD" applies analogously to
video/object storage) and is a procurement/business decision, not an architecture one.
What this ADR fixes is the *contract* every provider adapter must satisfy, so
`video-access-management`/`content-management` can be built against a stable
`VideoStorageApi`/`ObjectStorageApi` interface regardless of which concrete provider
`integration-management` later wires in.

## Context

`docs/architecture/video-content-security.md` fully specifies required *behavior*
(short-lived, signed, single-use, non-replayable tokens; server-side re-verification at
every playback request; concurrent-session blocking; upload validation) but explicitly
defers six technology/policy decisions. Five of those six need resolving before code can
be written against them; the sixth (anomaly-detection thresholds) is explicitly a
config/policy tuning decision, not an architectural one, and stays out of this ADR.

## Decisions

### 1. Signed-URL/token format and signing mechanism

**Decision:** Backend-issued, backend-signed **short-lived JWT playback token** (reusing
the JWT infrastructure from ADR-007: same signing key management, same "app is
stateless" rationale), containing `sub` (student id), `tenant_id`, `video_id` (or
`resource_id` for non-video protected content), a unique `jti`, and an expiry — not a
provider-native signed URL as the primary mechanism.

- The backend validates this token on every playback/content request (per §5 of the
  security doc) *before* asking the storage/video provider for the actual bytes/stream
  location. Whether the provider's own native signed-URL feature is used as a second,
  provider-side layer underneath the backend's token is an adapter-level implementation
  detail decided when a provider is chosen — not a platform-wide requirement.
- Rationale: keeps single-use and re-verification-at-use-time enforcement (§4-5 of the
  security doc) entirely under this platform's control, rather than depending on
  whichever revocation/short-TTL primitives a specific provider happens to expose. This
  also keeps the mechanism provider-swappable, consistent with `integration-architecture.md`'s
  provider-agnostic `VideoStorageApi`.
- Expiry: recommend a short window (2-5 minutes) sufficient to start playback, distinct
  from and much shorter than the ADR-007 access-token expiry — a playback token is
  narrowly scoped to one resource, so a short lifetime has low UX cost.

### 2. Specific external video/object storage provider

**Decision:** Not resolved by this ADR — remains an open procurement/business decision,
consistent with `integration-architecture.md`. Evaluation criteria for when that
decision is made: the provider must support (a) origin/referrer or IP-scoped delivery
restriction as defense-in-depth, and (b) either native signed URLs or an access-controlled
fetch API the backend can gate behind the JWT playback token above — a provider requiring
public/predictable URLs would violate §10 of the security doc and should be disqualified.

### 3. DRM vs. watermark-only deterrence for MVP

**Decision:** **Watermark + session/device/token controls are sufficient for MVP; formal
DRM (e.g. Widevine/FairPlay/PlayReady) is explicitly deferred**, not built now.

- This matches the security doc's own framing (§8): the platform deters and detects,
  it does not claim to prevent screen recording. Formal DRM adds real licensing cost,
  vendor lock-in to DRM-capable players/providers, and material playback-compatibility
  complexity (browser/device DRM support varies) — none of which is justified without a
  demonstrated piracy problem at this stage.
- Revisit as a new ADR if/when a specific tenant or content-licensing agreement requires
  DRM as a contractual condition (e.g. a licensed third-party course catalog), or if
  measured unauthorized-redistribution rates justify the cost — not speculatively now.

### 4. Watermark implementation

**Decision:** **Client-side dynamic overlay** (student name/identifier, moving position,
rendered by the frontend video player), not provider-side burned-in watermarking.

- Rationale: does not constrain video-provider choice to only those offering
  server-side/burned-in watermarking, keeping decision #2 genuinely open. Client-side
  overlay is also faster to iterate on (copy/position/opacity changes ship as a frontend
  change, not a re-encode).
- Accepted trade-off: a client-side overlay is technically strippable by a sufficiently
  motivated bad actor (e.g. via browser dev tools or a modified client) in a way a
  burned-in watermark is not. This is consistent with the explicit non-claim in §8 —
  deterrence, not prevention — and is not being weighed as a security control on its
  own; it works in combination with token/session/device controls, which do not depend
  on the watermark holding.

### 5. Single-use token enforcement mechanism

**Decision:** **Redis-backed consumed-token record**, keyed by the token's `jti`, written
atomically (`SET jti consumed NX EX <remaining-ttl>`) on first successful validation; a
second validation attempt for the same `jti` finding the key already present is rejected
as a replay.

- Consistent with ADR-007's "PostgreSQL authoritative, Redis for ephemeral/hot-path
  state" split and `.claude/rules/architecture.md`'s "Redis is cache/ephemeral-state
  only" rule: a playback token's single-use state is *inherently* ephemeral — it only
  matters within the token's own short (2-5 minute) lifetime, so a Redis flush at worst
  narrows a replay-prevention window that already expires on its own within minutes, and
  never affects durable enrollment/financial/audit state (which stays in PostgreSQL per
  the existing rule).
- The atomic `SET ... NX` (not a separate read-then-write) is required specifically to
  close the concurrent-request race the security doc flags in §5 ("consistency
  guarantees under concurrent requests") — two simultaneous validation attempts for the
  same token must not both succeed.

### 6. Anomaly-detection thresholds

**Decision:** Deliberately **not resolved by this ADR** — `docs/architecture/video-content-
security.md` §12 already correctly classifies this as a policy/config decision, not an
architectural one. The mechanism it plugs into (§7's server-side anomaly detection,
audit-logged per `.claude/rules/security.md`) is unaffected by this ADR; specific
thresholds (e.g. "reject after N distinct IPs in M minutes") are tunable configuration
set when `video-access-management` implements Section 7, not a standing architectural
commitment.

## Consequences

**Positive**

- `video-access-management`/`content-management` can be implemented against a concrete,
  stable token contract today without waiting on provider procurement.
- Single-use enforcement reuses the same Redis-as-cache / PostgreSQL-as-source-of-truth
  split already established for sessions (ADR-007), rather than introducing a third
  pattern for the platform to reason about.
- Deferring DRM and provider selection avoids over-building/over-spending ahead of a
  demonstrated need, while the watermark decision keeps provider choice unconstrained
  when that selection does happen.

**Negative / trade-offs accepted**

- A backend-issued JWT playback token adds one more token type to reason about
  alongside the ADR-007 access/refresh tokens (distinct signing scope, much shorter
  TTL) — acceptable complexity given it keeps single-use/revocation logic
  provider-independent.
- Client-side watermarking is the weaker deterrence option compared to burned-in
  watermarking; accepted per §8's explicit non-claim and to preserve provider
  flexibility, not because it is the strongest possible control.

## Alternatives considered

- **Relying solely on the video provider's native signed URLs, with no backend-issued
  token layer** — rejected: makes single-use/re-verification-at-use-time enforcement
  entirely dependent on provider-specific capabilities, undermining the
  provider-agnostic `VideoStorageApi` interface goal and complicating a future provider
  swap.
- **Selecting a DRM-capable provider now to keep the option open** — rejected as
  premature: would narrow provider choice and add cost before any decision requires it;
  a provider swap remains possible later regardless, per the adapter pattern in
  `integration-architecture.md`.
- **PostgreSQL-backed single-use token tracking** — rejected in favor of Redis: the
  state is genuinely ephemeral (matches the token's own short lifetime), and routing a
  check-on-every-playback-request lookup through PostgreSQL would add unnecessary
  durable-storage load for data with no long-term value, contrary to the
  reporting-analytics/database scalability guidance in `.claude/rules/architecture.md`.

## Related

- `docs/architecture/video-content-security.md` (behavioral baseline this ADR supplies
  the missing mechanism decisions for)
- `docs/architecture/integration-architecture.md` (`VideoStorageApi`/`ObjectStorageApi`
  provider-agnostic interface pattern)
- ADR-007-authentication-token-and-device-mechanism.md (JWT signing infrastructure and
  Redis-cache/PostgreSQL-authoritative pattern this ADR reuses)
- `.claude/rules/security.md`, `.claude/rules/architecture.md`
