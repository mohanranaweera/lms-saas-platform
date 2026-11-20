# MEDIA-INT-01A — Bunny.net: Account API Key Only, One Library per Teacher

**Status:** Approved and implemented (2026-09-29). This plan **amends ADR-018** (recorded as
ADR-018 Amendment 1) and changes the approved `PUT /api/v1/tenant-admin/integrations/bunny`
contract - both change-controlled items (see §12), approved by the project owner. See §14 for
how the implementation differs from this plan.

**Owner decisions (2026-09-29):**
1. The Bunny account belongs to the **tenant**. The Tenant Admin pastes one Bunny API key, and
   ADR-018's "one Bunny account per tenant" still holds.
2. Each teacher gets **their own video library** inside that account. The backend **creates it
   automatically**, and nobody types a library ID or CDN hostname.

Builds on `docs/plans/MEDIA-INT-01 Media and Meeting Provider Connections.md` and
`docs/adr/ADR-018-media-provider-connections.md`.

---

## 1. Business goal

MEDIA-INT-01 asks the Tenant Admin for four Bunny values: the Stream API key, the video library
ID, the CDN hostname and a token-auth key. The last three describe **one** library, so the whole
tenant is limited to one Bunny library and its videos can't be separated by teacher. Reading the
IDs out of the Bunny dashboard is also error-prone.

After this change:
- The Tenant Admin pastes **one** key, a bunny.net *account-level* API key. We recommend a
  sub-user key limited to Stream.
- The platform uses that key to **create and manage a separate Bunny Stream library for each
  teacher**.
- The library ID, CDN hostname and per-library keys are discovered from Bunny's API when they
  are needed. They are never typed in.

This gives each teacher their own storage, usage figures and later their own playback settings,
while the tenant keeps one bill and one account.

## 2. User roles

| Role | Interaction |
|---|---|
| Tenant Admin | Connects, replaces, tests and disconnects the tenant's Bunny account with **API key only**. Sees a **Teacher video libraries** table: each teacher, library name, status and created date. Can **Create library now** for a teacher, and **Retry** for a failed one. Never sees any key. |
| Teacher | Sees a read-only **Your video library** card on *Settings → Integrations*: none yet / being set up / ready / unavailable. Cannot create, rename or see IDs or keys. In MEDIA-INT-03 their uploads go to their own library. |
| Student | No change. Playback comes in MEDIA-INT-03. |
| Platform Admin | No change. No cross-tenant library view in this phase. |

## 3. Acceptance criteria

### Connecting the account
1. `PUT …/integrations/bunny` accepts `{ "apiKey": "…" }` only. The request is rejected with
   `400 VALIDATION_ERROR` (field named) if it includes a non-null `libraryId`, `cdnHostname` or
   `tokenAuthKey`, so an outdated client can't silently save a wrong setup.
2. The key is verified against Bunny's **account API** (`GET https://api.bunny.net/videolibrary`,
   `AccessKey` header). A 401/403 from Bunny → `400 PROVIDER_CREDENTIALS_INVALID` on `apiKey`.
   Network failure → `503`. Nothing is stored on failure.
3. On success the connection is saved as today (encrypted, previous row DISCONNECTED, audit
   entry). The account label is `"Bunny.net account"`. `settings` becomes
   `{ "keyType": "ACCOUNT" }`, with no library ID or hostname.
4. A blank `apiKey` with an ACTIVE connection returns the current row unchanged (same as DynTube).
5. A key that only works for one library (the old kind) fails step 2 with an error message that
   says an **account** API key is needed.
6. **Existing Bunny connections** made under MEDIA-INT-01 hold a library key. They are not
   rewritten. Their next test fails against the account API, so they move to `NEEDS_RECONNECT`
   and the admin sees "Reconnect with an account API key." No data migration is needed.

### Per-teacher libraries
7. A teacher has **at most one live library** (`PROVISIONING` or `ACTIVE`) per tenant. This is
   enforced by a partial unique index, not only by code.
8. `ensureTeacherLibrary(teacherUserId)` is idempotent. If the teacher already has an ACTIVE
   library it returns that one. Otherwise it creates one:
   1. Insert a `PROVISIONING` row and **commit**.
   2. Call Bunny `POST /videolibrary` with the name `"<Tenant name> - <Teacher name> [<8-char teacher id>]"`.
      Before creating, it first searches `GET /videolibrary?search=<8-char id>` so a retry after
      a half-finished attempt adopts the existing library instead of making a duplicate.
   3. In a new transaction, mark the row `ACTIVE` with the Bunny library ID, name and pull zone ID.
   The database transaction is never held open across the Bunny call (`.claude/rules/backend.md`).
9. If step 8.2 fails, the row becomes `FAILED` with a short sanitized `last_error`, and **Retry**
   is allowed. A concurrent second call for the same teacher hits the unique index and gets
   `409 CONFLICT` ("library is being set up"). A second library is never created.
10. **When libraries are created:**
    - (a) **Automatically**, the first time a teacher needs one. In MEDIA-INT-03 that is their
      first video upload. `BunnyLibraryApi.ensureTeacherLibrary` is the hook.
    - (b) **Manually**, when the Tenant Admin clicks **Create library now**. This lets it be
      tested before MEDIA-INT-03 exists.
    - Libraries are never created for every teacher up front, which would create empty Bunny
      libraries.
11. You can only create a library for a user who is a **TEACHER in the same tenant**. Another
    tenant's user or a non-teacher → `404`.
12. **No Bunny connection is ACTIVE** → `409 CONFLICT` "Connect a Bunny.net account first". No
    row is written.
13. Libraries are **never deleted**, in Bunny or in our table. A teacher leaving, the Bunny
    account being disconnected, or the key being replaced leaves the rows as they are, because
    their videos stay the tenant's property.
14. **After the key is replaced**, a library is used only if the new key can still see it
    (`GET /videolibrary/{id}` succeeds). If not, that library's status shows `UNAVAILABLE`
    ("not visible to the current Bunny account"). It is flagged, not recreated automatically.
15. Per-library secrets (the library `ApiKey`, the token-auth key) are **not stored**. They are
    fetched from Bunny with the account key when MEDIA-INT-03 needs them, never returned by any
    API, and never logged.
16. Every library creation, adoption or failure writes an audit entry
    `media_integration.bunny_library_created` or `…_failed`. It records the actor (the admin,
    or the teacher who triggered it), the tenant, the teacher and the Bunny library ID. No keys.

### Empty and error states
17. Admin libraries table: an empty state before any library exists ("Libraries are created
    automatically when a teacher first uploads a video"), a loading state, an error state, and
    per-row status badges.
18. Teacher card: "Not set up yet", "Being set up", "Ready", "Unavailable - contact your
    institute", and "Your institute hasn't connected Bunny.net" when there is no connection.

## 4. Database impact

**New migration `V61__create_bunny_teacher_library.sql`.** V60 is already committed and is
**not edited**. Its comment ("settings_json holds Bunny library id / CDN hostname") becomes
out of date, and the ADR amendment records that.

```sql
CREATE TABLE bunny_teacher_library (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenant (id),
    teacher_user_id       UUID NOT NULL,
    connection_id         UUID NOT NULL,          -- the Bunny connection that created it
    bunny_library_id      BIGINT,                 -- NULL while PROVISIONING / FAILED
    library_name          VARCHAR(255) NOT NULL,
    pull_zone_id          BIGINT,
    status                VARCHAR(15) NOT NULL,   -- PROVISIONING | ACTIVE | FAILED
    last_error            VARCHAR(500),
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    created_by            UUID,
    updated_by            UUID,
    CONSTRAINT uq_bunny_teacher_library_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_bunny_teacher_library_teacher
        FOREIGN KEY (tenant_id, teacher_user_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_bunny_teacher_library_connection
        FOREIGN KEY (tenant_id, connection_id) REFERENCES media_provider_connection (tenant_id, id),
    CONSTRAINT ck_bunny_teacher_library_status CHECK (status IN ('PROVISIONING','ACTIVE','FAILED')),
    CONSTRAINT ck_bunny_teacher_library_active_has_id
        CHECK (status <> 'ACTIVE' OR bunny_library_id IS NOT NULL)
);
-- One live library per teacher per tenant (AC 7/9)
CREATE UNIQUE INDEX uq_bunny_teacher_library_live
    ON bunny_teacher_library (tenant_id, teacher_user_id) WHERE status IN ('PROVISIONING','ACTIVE');
-- A Bunny library maps to one teacher within a tenant
CREATE UNIQUE INDEX uq_bunny_teacher_library_bunny_id
    ON bunny_teacher_library (tenant_id, bunny_library_id) WHERE bunny_library_id IS NOT NULL;
-- Admin table / teacher card lookups
CREATE INDEX idx_bunny_teacher_library_tenant_status
    ON bunny_teacher_library (tenant_id, status, created_at);
```

- The table is tenant-owned: `tenant_id NOT NULL` + FK, composite FKs keep the teacher and the
  connection inside the same tenant, and every index leads with `tenant_id`.
- `UNAVAILABLE` (AC 14) is a **computed** status from a live check and is not stored, so a
  temporary Bunny outage never rewrites rows.
- A `FAILED` row stays as history. A retry inserts a new `PROVISIONING` row.
- `media_provider_connection`: no schema change. New BUNNY rows store
  `settings_json = {"keyType":"ACCOUNT"}`, and old rows keep their legacy settings untouched.

## 5. Backend impact

`integration-management` only.
- **Config:** `MediaIntegrationProperties.bunny` gets `accountApiBaseUrl`
  (default `https://api.bunny.net`) next to the existing Stream base URL
  `https://video.bunnycdn.com`. Both are fixed values, never taken from a tenant.
- **Gateway:** `TenantVideoHostClient` changes as follows.
  - Replace `verifyBunny(apiKey, libraryId)` with `verifyBunnyAccount(apiKey)` (list libraries,
    page 1 of 5).
  - Add a new `BunnyAccountClient` with `findLibraryByTag(key, tag)`, `createLibrary(key, name)`
    and `getLibrary(key, id)`. It returns only the ID, name and pull zone ID. The library
    `ApiKey` is kept in memory and used only inside the gateway, for MEDIA-INT-03.
  - Every call goes through `ProviderHttpClient`, which has fixed hosts, redacted logging and
    timeouts.
- **Domain / repository:** a `BunnyTeacherLibrary` entity and a `BunnyTeacherLibraryRepository`
  that extends `TenantAwareRepository`, the same pattern as `MediaProviderConnectionRepository`.
  There is no delete method.
- **Service:**
  - `MediaConnectionService.saveBunny(apiKey)` becomes a single-argument method with the checks
    in AC 1–5. `verifyApiKeyConnection` uses the account check for BUNNY.
  - New `BunnyLibraryService`:
    - `ensureTeacherLibrary(teacherUserId, actor)`, following AC 8–12 with separate short
      transactions through a `BunnyLibraryStore`, the same split used by `MediaConnectionStore`.
    - `listForAdmin()`, which merges in teacher names from `TeacherLookupApi`.
    - `ownLibrary()`, for the teacher card.
- **Public API (`api` package):** `BunnyLibraryApi` with `ensureTeacherLibrary(UUID
  teacherUserId)` → `TeacherLibrarySummary(id, status, libraryName)`. No keys and no tenant
  parameter. MEDIA-INT-03 will call this.
- **Web:**
  - `TenantAdminIntegrationController` changes `PUT /bunny` to use `BunnyAccountRequest(apiKey)`
    and gains two library endpoints.
  - `TeacherIntegrationController`: the overview includes `bunnyLibrary`.
- **Permissions:**
  - Admin endpoints use `DomainArea.INTEGRATIONS` (VIEW / CREATE_EDIT), as today.
  - The teacher card uses the existing teacher-integrations access.
  - Library creation called through `BunnyLibraryApi` requires the caller to be that teacher,
    or an admin with CREATE_EDIT.
- **Error mapping:** reuse `PROVIDER_CREDENTIALS_INVALID`, `CONFLICT`, `SERVICE_UNAVAILABLE` and
  `NOT_FOUND`. No new error codes.

## 6. API contract (changes to `docs/api/integrations.md`)

**Changed: `PUT /api/v1/tenant-admin/integrations/bunny`** *(approved contract, needs approval)*
```jsonc
// request
{ "apiKey": "…" }          // account API key; blank keeps the stored key
// 200 → ConnectionView; settings = { "keyType": "ACCOUNT" }
// 400 VALIDATION_ERROR (libraryId/cdnHostname/tokenAuthKey sent, key too long)
// 400 PROVIDER_CREDENTIALS_INVALID (fieldErrors: apiKey)   503 SERVICE_UNAVAILABLE
```

**New: `GET /api/v1/tenant-admin/integrations/bunny/libraries`** → `200`
```jsonc
[{ "teacherUserId": "uuid", "teacherName": "Jane Perera",
   "library": { "id": "uuid", "status": "ACTIVE", "libraryName": "Demo Institute - Jane Perera [1a2b3c4d]",
                "createdAt": "2026-09-29T08:00:00Z", "lastError": null } | null }]
```
This returns every teacher in the tenant, with `library: null` if the teacher has none.
`status` is one of `PROVISIONING | ACTIVE | FAILED | UNAVAILABLE`. The Bunny numeric library ID
is **not** exposed. The admin doesn't need it, and leaving it out keeps the contract free of
provider details.

**New: `POST /api/v1/tenant-admin/integrations/bunny/libraries/{teacherUserId}`** (no body)
- `200` → the existing ACTIVE library (idempotent). `201` → created or adopted.
- `404`: the user is not a teacher in this tenant, including a user from another tenant.
- `409`: no ACTIVE Bunny connection, or a library is already `PROVISIONING`.
- `503`: Bunny could not be reached. The row is left `FAILED`.

**Changed: `GET /api/v1/teacher/integrations`**: adds
`"bunnyLibrary": { "status": "...", "libraryName": "..." } | null` and
`"bunnyConnected": true|false`. This is additive.

All responses use the standard `ApiResponse` envelope and error format.

## 7. Frontend impact

- `components/integrations/admin-integration-sections.tsx` → `BunnySection`:
  - Remove the library ID, CDN hostname and token-auth fields.
  - Keep a single write-only "Account API key" input, with help text: "Use a bunny.net API key
    for your account. We recommend a sub-user key limited to Stream."
  - Show a `NEEDS_RECONNECT` banner for legacy library-key connections.
- New `BunnyLibrariesSection` on the tenant-admin integrations page:
  - A table of teachers with a status badge, created date and error.
  - A **Create library now** or **Retry** button, visible only with CREATE_EDIT permission.
  - Loading, empty and error states. Buttons are disabled while a request is running, and a
    toast shows on success or failure.
- Teacher `settings/integrations/page.tsx`: a read-only **Your video library** card (AC 18).
- `lib/api/integrations.ts`:
  - The `useSaveBunny` payload becomes `{apiKey}`.
  - New hooks `useBunnyLibraries` and `useCreateBunnyLibrary`, plus the updated teacher overview
    type.
- `lib/validation/integrations.ts`: `bunnySchema` keeps only `apiKey`.

## 8. Security impact

- **More powerful credential.** An account key can do more than a library key. Mitigations:
  - UI guidance to use a Stream-only sub-user key.
  - The backend calls a **fixed allow-list** of endpoints only: `GET/POST /videolibrary`,
    `GET /videolibrary/{id}`, and (MEDIA-INT-03) pull zone reads.
  - There is no code path to DNS, billing, storage zones or deletes.
  - The key is stored as it is today: AES-256-GCM with `MEDIA_CREDENTIALS_ENCRYPTION_KEY`,
    AAD-bound, write-only.
- **Fewer secrets stored.** Per-library keys are not stored at all (AC 15).
- **SSRF.** Only the two configured base URLs are called. Library IDs are numeric (`long`) and
  path-encoded. Search terms are our own 8-hex-character teacher tag, never user input.
  `cdnHostname` input is removed completely.
- **Authorization.**
  - An admin creates libraries only for same-tenant teachers.
  - A teacher sees only their own library.
  - Library IDs and keys are never exposed.
- **Audit.** Connect, replace and disconnect entries exist already; library created and failed
  entries are added (AC 16).
- **Logging.** `ProviderHttpClient` already redacts headers. `last_error` stores sanitized text
  only, never Bunny response bodies.
- **Change controls.** Authentication architecture, multi-tenancy strategy and payment rules
  are untouched.

## 9. Tenant impact

- `bunny_teacher_library` is tenant-owned. Tenant isolation is structural through
  `TenantAwareRepository`, the composite FKs keep the teacher and the connection in the same
  tenant, and every index leads with `tenant_id`.
- The tenant comes only from `TenantContext`. No endpoint or `api` method takes a tenant ID.
  `teacherUserId` in the path is checked with `TeacherLookupApi` in the current tenant, and an
  unknown ID returns `404`.
- Bunny libraries sit in the tenant's own Bunny account. One tenant's key can't see another
  tenant's libraries, and our `bunny_library_id` unique index is per tenant.
- For MEDIA-INT-03 async work (upload processing), the tenant ID and teacher ID must be carried
  in the job payload. This is noted for that plan.

## 10. Tests

**Backend (JUnit + Spring Boot integration + Testcontainers Postgres, with Bunny stubbed by a
mock HTTP server):**
- Save Bunny:
  - Only `apiKey` → ACTIVE, with `settings.keyType=ACCOUNT`.
  - Legacy fields sent → 400 naming the field.
  - Bunny 401 → `PROVIDER_CREDENTIALS_INVALID`, nothing stored.
  - Network error → 503.
  - Blank key with an ACTIVE connection → unchanged.
- A legacy library-key connection's test → `NEEDS_RECONNECT`.
- `ensureTeacherLibrary`:
  - Creates → ACTIVE with the ID and pull zone saved.
  - A second call → the same library (200). Bunny's create is called once.
  - An existing Bunny library with the tag → adopted, no create call.
  - Bunny fails → `FAILED` + audit. Retry → a new row ACTIVE.
  - Two concurrent calls → one Bunny create, the other gets 409, backed by the DB unique index.
  - No ACTIVE connection → 409, no row.
  - A non-teacher user → 404.
- The key is replaced and the new key can't see the library → `UNAVAILABLE` in the list, and
  the row is not changed.
- Migration test: V61 applies on top of V60 and the constraints hold. That covers the partial
  unique index, `ACTIVE` without a library ID being rejected, and a cross-tenant composite FK
  being rejected.
- **Cross-tenant tests (mandatory):**
  - Tenant A's admin, `POST …/libraries/{tenant-B teacher id}` → 404, no Bunny call.
  - Tenant A's admin, `GET …/libraries` → only tenant A's teachers.
  - Tenant A's teacher overview never shows tenant B's library.
  - A repository query under tenant A's context never returns tenant B's rows.
- Security: no response or log line contains the account key or any library key. The
  controller DTO `toString` is redacted.

**Frontend (Playwright + component tests):**
- The Bunny form shows only the API key field. Save succeeds. The invalid-key error shows under
  the field. The legacy `NEEDS_RECONNECT` banner shows.
- Libraries table:
  - Empty, loading and error states.
  - **Create library now** → the row turns ACTIVE.
  - A failed row shows **Retry**.
  - Buttons are hidden for read-only admins.
- Teacher card: all AC 18 states.
- The E2E flow runs against the backend with Bunny stubbed. No real Bunny account in CI.

## 11. Documentation updates

- `docs/adr/ADR-018-media-provider-connections.md`: add **Amendment 1 (2026-09-29)**. It covers
  the account key, per-teacher libraries, the stale V60 comment and the reasoning.
- `docs/api/integrations.md`: the changed `PUT /bunny`, the two new library endpoints, and the
  teacher overview fields.
- `docs/architecture/database-architecture.md`: the `bunny_teacher_library` table.
- `docs/architecture/integration-architecture.md`: the Bunny account API vs Stream API flow.
- `docs/plans/MEDIA-INT-01 …`: a pointer to this plan in §13.
- `docs/ui-ux/screen-map.md`: the admin libraries table and the teacher library card.
- `README` / env docs: none. `MEDIA_CREDENTIALS_ENCRYPTION_KEY` is already documented.
  `integrations.media.bunny.account-api-base-url` is optional (it has a default).

## 12. Change-control items requiring explicit approval

1. **ADR-018 amendment.** The Bunny credential model changes from a library key plus library ID
   and hostname to an account key with auto-created per-teacher libraries.
2. **Approved API contract change.** `PUT /api/v1/tenant-admin/integrations/bunny` drops
   `libraryId`, `cdnHostname` and `tokenAuthKey`. This is a breaking change, and the frontend
   is updated in the same module.
3. **Credential scope.** The platform will hold a tenant's account-level Bunny key (Stream
   sub-user recommended, but the backend can't enforce this).

Nothing else under root `CLAUDE.md` change controls is touched. V60 is not edited, and the new
schema goes in V61.

## 13. Open items to confirm during implementation (real Bunny account needed)

- Confirm that a **Stream sub-user API key** can call `POST /videolibrary`. The docs list
  sub-user Stream roles for `GET /videolibrary`, but create is not yet confirmed. If it can't,
  the UI help text says a full account key is required.
- Confirm how to read a library's **CDN hostname and token-auth key**, through
  `GET /pullzone/{PullZoneId}` or a library field, and whether a sub-user key may read it. This
  is needed only in MEDIA-INT-03, not in this change.
- Confirm Bunny's name length limit for the `<Tenant> - <Teacher> [tag]` library name. We'll
  truncate the display part and always keep the tag.

## 14. Implementation notes & review fixes (2026-09-29)

Built as planned, with these differences (all reflected in `docs/api/integrations.md`):

- **Eligible teachers (security review L-3).** Only teachers with an `APPROVED` profile and an
  `active` account can get a library (`TeacherLookupApi.listApprovedActiveTeachers()`, a new
  additive method; teacher settlements keep `listTeachers()`). The admin list shows those
  teachers plus anyone who already has a library, and each row carries a backend-decided
  `canCreateLibrary` flag that drives the button (additive contract field).
- **One Bunny call for availability (M-1).** `UNAVAILABLE` is worked out with one account
  listing (`GET /videolibrary?perPage=1000`, paged, capped) instead of one call per library;
  the per-library read was removed, so the account-API allow-list is just list + create.
  MEDIA-INT-03 adds a library-details read when it needs the library key.
- **Concurrent takeover (L-1).** Taking over a stale `PROVISIONING` row skips a row another
  request has already taken over instead of failing with a 500; the live-row unique index then
  decides which request continues (the other gets 409).
- **`BunnyLibraryApi` tested (L-2)** - self (teacher) allowed, another teacher 403, admin
  allowed and idempotent.
- **Teacher overview is not transactional** - working out `UNAVAILABLE` may call Bunny, and no
  transaction may span a provider call.
- **Frontend.** The libraries section uses the shared `QueryStateBoundary` (a 403 shows the
  permission-denied state); the empty state reads "No approved teachers yet".
- **Tests.** Backend: `BunnyTeacherLibraryIntegrationTest` (17 tests), updated
  `TenantAdminIntegrationsIntegrationTest`; full suite 2,117 green. Playwright:
  `tenant-admin-integrations.spec.ts` and `teacher-integrations.spec.ts` extended. Two
  pre-existing failures in `teacher-integrations.spec.ts` (Zoom OAuth-callback confirm tests)
  also fail without this change and are tracked separately.
- **UI/UX review fixes.** UX-1: a `PROVISIONING` row older than 5 minutes gets
  `canCreateLibrary = true` and is shown as "Setup interrupted" with Retry (previously it
  stayed "Being set up" with no way out). UX-2: focus moves to the success message after a
  create. UX-3: the success/error live regions are always mounted. UX-4: read-only roles are
  told to ask a Tenant Admin to reconnect. UX-5: a legacy connection's summary reads "Library
  key (must be replaced)". UX-6: the empty libraries state links to teacher management.
- **Still open** (§13): Stream sub-user key rights to create libraries, and Bunny search
  matching the tag, need a real Bunny account.
