# ADR-018: Media & Meeting Provider Connections — Per-Teacher Zoom/YouTube/Vimeo, Per-Tenant DynTube/Bunny.net

## Status

**Accepted (2026-09-28)** by the project owner ("Approved go ahead"), together with the plan
`docs/plans/MEDIA-INT-01 Media and Meeting Provider Connections.md`.

**Owner decisions recorded 2026-09-28:**
1. Zoom, YouTube and Vimeo accounts are **per teacher**. Each teacher connects their own
   account.
2. DynTube and Bunny.net are **one account each per tenant**, configured by the Tenant Admin.
   *(Bunny.net amended 2026-09-29: the account is connected with an account API key only, and
   each teacher gets their own library inside it - see Amendment 1.)*
3. Teachers connect via **OAuth "Connect"** buttons, not pasted API keys.
4. Students get a **personal Zoom join link** (meeting registrants). Teachers therefore need a
   **licensed (paid) Zoom account**. A Basic account may connect but cannot schedule classes.
5. Delivery is **phased**: MEDIA-INT-01 builds the connections and real Zoom live classes.
   Materials, secure hosting and live streaming follow in MEDIA-INT-02..04.

No root `CLAUDE.md` change-controlled area is changed. Tenancy, auth architecture, payment,
enrollment, deployment and migration history are untouched. This ADR records architecture
decisions that ADR-008, ADR-011 and the open-decisions register left open.

## Context

- `live-class-management` runs against `FakeZoomLiveClassProviderAdapter`, so no real meeting
  can be created. Webhooks use a generic HMAC format, not Zoom's.
- YouTube/Vimeo are attach-by-URL only (`MaterialType.LINK`).
- ADR-008 fixed the playback-token contract but left the video host provider unselected.
- `docs/requirements/open-decisions.md` lists open items: the Zoom link-sharing prevention
  mechanism, and whether YouTube/Vimeo content is exempt from secure-playback rules.
- PAY-GW-01 (ADR-017) established the pattern for tenant-owned credentials: AES-256-GCM
  ciphertext, write-only, disable-never-delete, and audit.

## Decisions

### 1. Provider selection

Zoom, YouTube (Google) and Vimeo are supported **per-teacher** providers. DynTube and Bunny
Stream are supported **per-tenant** video hosts, and both may be connected at once. The tenant
picks one as its active host (`integrations.video_host`), which is consumed from MEDIA-INT-03.
This resolves ADR-008 decision 2 for these two providers. Both support token-authenticated,
non-public delivery, which meets ADR-008's evaluation criteria.

### 2. Credential model

- One table, `media_provider_connection`, is owned by `integration-management`.
  - `scope` is TEACHER or TENANT.
  - `owner_user_id` is required exactly for TEACHER, via a composite FK to
    `tenant_user (tenant_id, id)`.
  - The provider/scope pairing is enforced by a CHECK constraint.
- Secrets (OAuth access/refresh tokens, API keys) are held in one AES-256-GCM encrypted blob.
  The key is `MEDIA_CREDENTIALS_ENCRYPTION_KEY`, **separate** from
  `PAYMENT_CREDENTIALS_ENCRYPTION_KEY` to limit blast radius. Secrets are write-only, never
  returned, logged or audited, and the feature fails closed (503) when the key is unset.
- Rows are never deleted. Replacing a connection marks the old row DISCONNECTED, because
  `class_session.provider_connection_id` references the account that hosted a meeting.
- The platform registers **one OAuth app per provider** (Zoom user-managed app, Google Cloud
  OAuth client, Vimeo app). Their client secrets are platform environment configuration.

### 3. Central OAuth callback

- Every tenant is redirected to one registered callback,
  `GET /api/v1/integrations/oauth/{provider}/callback`. Tenants live on different
  subdomains/custom domains, and providers require pre-registered redirect URIs.
- The endpoint is unauthenticated, like webhooks. It resolves tenant and teacher **only** from
  a server-issued, single-use `state` held in Redis (≥128-bit, TTL 10 min, consumed with
  `GETDEL`), with PKCE where the provider supports it.
- It redirects back only to a host that resolves to the same tenant, so there is no open
  redirect.
- The callback **does not save** the connection (amended 2026-09-29, security review S1). It
  parks the result for 10 minutes under a fresh single-use pending id that only the returning
  browser receives; the connection is saved only when the **same signed-in teacher** confirms it
  (`POST /teacher/integrations/pending/{id}/confirm`). This stops a consent link forwarded to
  another person from attaching that person's account to the teacher, and re-checks at save time
  that the caller is still an active teacher.

### 4. Zoom join-link protection

- Students are added as meeting **registrants** after the existing enrollment/tenant/status
  checks. Each receives a personal join URL, minted at join time and never shown to anyone else.
- The teacher's host `start_url` is fetched fresh per request and never persisted.
- This closes the open decision "Concrete mechanism for preventing/detecting Zoom link
  sharing". It requires a licensed host account, which is checked at connect time
  (`external_account_type`) and enforced at scheduling time (409 `ZOOM_LICENSE_REQUIRED`).

### 5. Cross-tenant deauthorization lookup (ADR-011-style carve-out)

- Zoom's `app_deauthorized` webhook identifies only the Zoom user/account.
- Connections are found with the explicitly named repository method
  `findActiveAcrossTenantsByProviderAndExternalAccountId`, after Zoom signature and timestamp
  verification.
- The method may **only** transition matching rows to DISCONNECTED (`disconnected_by = PROVIDER`),
  with an audit entry per tenant. No data is read back to the caller.
- All other Zoom webhooks still resolve the tenant via
  `provider_reference → class_session` (ADR-011).

### 6. YouTube/Vimeo security posture

- Videos on a teacher's YouTube/Vimeo are **exempt** from ADR-008 single-use playback tokens.
  The platform cannot control a URL hosted by a third party.
- MEDIA-INT-02 must:
  - only list and attach **unlisted/private** videos;
  - use domain-restricted embeds where the provider supports them (Vimeo);
  - label the weaker protection in the teacher UI.
- Protected paid content should use the tenant video host (Decision 1). This closes the open
  decision in `27-youtube-vimeo-integrations.md`.

### 7. Token refresh

- Refresh happens server-side when a token is within 5 minutes of expiry, under a per-connection
  Redis lock (`SET NX EX 30`). Zoom rotates refresh tokens, so concurrent refreshes would
  invalidate each other.
- New tokens are persisted in a short transaction after the HTTP call. No DB transaction spans
  the call (`.claude/rules/backend.md`).
- `invalid_grant` → NEEDS_RECONNECT.

## Consequences

**Positive**
- Teachers keep ownership of their meetings, channels and licences. Institutes do not pay for
  a shared Zoom account.
- One credential model and UI pattern covers all five providers and future ones.
- Two long-standing open decisions (Zoom link sharing, YouTube/Vimeo posture) are resolved.

**Negative / accepted trade-offs**
- Teachers on free Zoom accounts cannot run live classes.
- Operational dependency on three OAuth app registrations. Google verification for
  `youtube.upload` (MEDIA-INT-02) can take weeks.
- A new unauthenticated endpoint class (OAuth callback). This is mitigated by single-use Redis
  state and host validation.

## Alternatives considered

- **Tenant-wide Zoom account** (the current spec's "tenant Zoom account"): rejected by the owner.
  Teachers must have independent accounts.
- **Pasted API credentials per teacher:** rejected because it is hard for teachers, and Google
  effectively requires OAuth for YouTube.
- **One shared meeting join URL behind our authorization gate:** rejected because the URL is
  freely shareable once seen.
- **Storing credentials in `tenant_config_entry`:** rejected because those values are plaintext.

## Related

- ADR-008 (playback token), ADR-011 (webhook tenant resolution), ADR-017 (credential pattern)
- `docs/plans/MEDIA-INT-01 Media and Meeting Provider Connections.md`
- `docs/requirements/specifications/19-zoom-live-classes.md`, `27-youtube-vimeo-integrations.md`

## Implementation notes (2026-09-29)

Recorded after MEDIA-INT-01 was built and reviewed. None changes a decision above; they record
how each was realised.

- **Confirm step** - see Decision 3 (security review S1/S3).
- **Credentials wiped on disconnect** - a DISCONNECTED row keeps its history but its
  `credentials_ciphertext` is replaced with a non-decryptable marker, so no possibly-valid token
  or key outlives the connection (S2).
- **Ciphertext bound to its row's context** - AES-GCM additional authenticated data =
  tenant + provider + owner, so a value copied into another row cannot be decrypted (S4).
- **Refresh lock never blocks** - a request that finds another request refreshing uses the
  still-valid current token, or fails fast if it has expired (S5).
- **Provider-driven changes are security-logged, not audited** - Zoom `app_deauthorized` and a
  refresh rejected with `invalid_grant` have no platform user to name as the actor, so they go to
  the `SECURITY` log (same approach as PAY-GW-01 chargebacks). Every user-driven change is in the
  audit log.
- **Tenant switches have their own table** - `tenant_media_integration_settings` (not
  `tenant_config_entry`), so they sit behind the new `INTEGRATIONS` permission.
- **Zoom cloud recordings are not served** for real Zoom sessions until MEDIA-INT-04.
- **Log masking** - `logging.structured.json.customizer` masks tokens/keys/Bearer credentials in
  every JSON log line (S7).
- **Privacy** - joining a class shares the student's name and email with the teacher's Zoom
  account (registrant). Students see a note under the Join button; the platform privacy policy
  must say so before go-live.
- **DynTube** - its API could not be verified against published docs; the credential check
  (`GET /projects`, bearer key) and base URL are configurable and must be confirmed with a real
  DynTube account before go-live.

## Amendment 1 (2026-09-29): Bunny.net account key and one library per teacher

**Status:** Accepted 2026-09-29 by the project owner ("approved"), with the plan
`docs/plans/MEDIA-INT-01A Bunny Account Key and Per-Teacher Libraries.md`.
It changes the Bunny.net part of Decision 2 and the approved `PUT /tenant-admin/integrations/bunny`
contract, both change-controlled.

**Context.** MEDIA-INT-01 asked the Tenant Admin for a Stream API key plus a video library id,
CDN hostname and token-auth key. Those describe a single library, so a tenant was limited to
one Bunny library for every teacher, and the ids had to be copied from the Bunny dashboard.

**Decision.**
1. The tenant's Bunny.net account is connected with **one account-level API key** (a
   sub-user key limited to Stream is recommended in the UI; the backend cannot enforce it).
   It is verified against the account API (`GET https://api.bunny.net/videolibrary`), so a
   library-only key is rejected with a message asking for an account key. The account stays
   **one per tenant** - Decision 2 still holds.
2. Each teacher gets **their own Bunny Stream library** inside that account, recorded in a new
   tenant-owned table `bunny_teacher_library` (V61). Libraries are created automatically on a
   teacher's first need (MEDIA-INT-03 upload, via `BunnyLibraryApi.ensureTeacherLibrary`), or
   by a Tenant Admin ("Create library now"). Only approved, active teachers qualify.
3. **No per-library secret is stored.** A library's own API key and token-auth key are read
   from Bunny with the account key when MEDIA-INT-03 needs them. `cdnHostname` input is
   removed entirely.
4. The backend calls a **fixed allow-list** on the account API: list and create video
   libraries. No DNS, billing, storage-zone or delete call exists.
5. Libraries are **never deleted** (in Bunny or in our table). A teacher leaving, Bunny being
   disconnected, or the key being replaced leaves them in place; a library the current key
   cannot see is shown as `UNAVAILABLE`, never recreated automatically.

**How creation stays safe.**
- Idempotent: an ACTIVE library is returned unchanged.
- No DB transaction across the Bunny call: a `PROVISIONING` row is committed first, then
  Bunny is called, then the row is activated or failed in a new transaction.
- Duplicates are prevented by a partial unique index (one live library per teacher), not
  just by code. A setup stuck in `PROVISIONING` for more than 5 minutes may be taken over.
- Each library name carries a stable 12-hex tag from the teacher's id; before creating, the
  account is searched for it, so a retry after an interrupted attempt **adopts** the library
  instead of creating a second one.
- The availability check makes at most one Bunny listing call per page load (security review
  M-1), and a Bunny outage leaves statuses as stored.
- Creation, adoption and failure are audit-logged (`media_integration.bunny_library_created`
  / `_failed`).

**Existing data.** Connections saved under MEDIA-INT-01 hold a library key. They are not
rewritten: their next test fails against the account API and they move to
`NEEDS_RECONNECT`; the UI asks for an account key. V60 is untouched - its comment that
`settings_json` holds the library id / CDN hostname now describes legacy rows only; new rows
store `{"keyType":"ACCOUNT"}`.

**Consequences.**
- *Positive:* one paste to connect; each teacher's videos, storage and usage are separate;
  fewer secrets at rest.
- *Negative:* the platform holds a more powerful credential than before (account key vs
  library key). Mitigated by the Stream sub-user recommendation, the endpoint allow-list,
  encryption as in Decision 2, and never logging or returning the key.
- *Still to confirm with a real Bunny account:* that a Stream sub-user key may create
  libraries, and that Bunny's library search matches the tag. MEDIA-INT-03 must also confirm
  how to read a library's CDN hostname and token-auth key (pull zone read).
