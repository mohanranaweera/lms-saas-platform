# MVP-009 — Lessons and Learning Materials — Module Plan

**GitHub issue:** #9 — https://github.com/mohanranaweera/lms-saas-platform/issues/9 (fetched successfully
this session via `gh issue view`)
**Branch:** `feature/lessons-and-learning-materials` (current branch, based directly on `main` @ `c707e1e`)
**Spec source:** `docs/requirements/specifications/06-lessons-and-materials.md`
**Backend domain:** `content-management` (new top-level domain — `com.lms.contentmanagement` — per the
confirmed domain list in `docs/requirements/module-catalog.md`).

This plan was produced by delegating to six specialist agents in parallel (product-requirements-analyst,
solution-architect, database-architect, security-reviewer, qa-test-engineer, ui-ux-reviewer), each grounded in
the existing requirements/architecture/ADR corpus and the actual current repository state (including reading
the unmerged `feature/course-management` sibling branch directly via `git show`), then reconciled into this
one document — the same process used for MVP-005 and MVP-008. **payment-ledger-specialist was deliberately not
invoked**: this module owns no `Order`/`Payment`/ledger entity and performs no payment/ledger mutation (see
§17). This is a **plan only** — no application files were created or edited. Several genuine gaps and
cross-document contradictions are flagged explicitly below as **open decisions/risks**, not resolved. Per root
`CLAUDE.md`, this plan does not invent unresolved business decisions.

## Grounding note on current repository state (verified directly, not assumed)

`git branch -a` and `git log` show this branch is based directly on `main` (merge-base = `main` HEAD,
`c707e1e`). **Four sibling feature branches exist, none merged into `main`:** `feature/course-management`,
`feature/student-management`, `feature/teacher-management`, and this branch's own predecessor state. Only
`com.lms.common`, `com.lms.identityaccessservice`, `com.lms.tenantmanagement`, and `com.lms.usermanagement`
(Staff Management only) exist in this branch's actual `backend/src/main/java/com/lms` tree today; Flyway
migration history here stops at `V10__create_staff_profile.sql`.

**Critical finding that reshapes this module's real scope**: GitHub issue #9's own "Database requirements"
text ("New tables `course_module(...)`, `lesson(...)`") is **stale**. Those tables already exist — built and
shipped, in full, by the *sibling* `course-management` domain (MVP-008, branch `feature/course-management`,
commit `960bdb1`, migration `V11__create_course_management_schema.sql`), not by `content-management`. That
migration's own header comment states the tables are "Owned by `course-management`... per `.claude/rules/
architecture.md`'s 'one table, one owning domain' rule," and `CourseLesson.java`'s javadoc states explicitly:
*"No material content lives here — that is `content-management`'s table, once it exists, referencing this
entity's id by FK from its own side."* MAT-1 (module & lesson structure, keyboard-reorderable) is therefore
**already built**, just under a different domain's ownership than the spec doc originally assumed. This
module's real, net-new scope is **MAT-2 (material upload) and MAT-3 (material organization + fetch-time
visibility)** only — consuming `course-management`'s `course_lesson` by reference, never rebuilding it.

No `integration-management` package (object storage) and no `audit-log-management` package exist anywhere in
git history — checked `main` and every sibling feature branch via `git ls-tree`/`Glob`. No `enrollment-management`
package exists either. These are genuine, currently-unbuilt cross-module dependencies this module leans on;
they are named as explicit blocking risks throughout, not designed around by inventing local substitutes.

---

## 1. Business goal

Give teachers (and permitted staff) a validated upload path for protected learning materials (PDF/image/notes)
that never streams binaries through the Spring Boot app tier, organized against the already-existing
module/lesson scaffold, with **fetch-time** (not just navigation-time) visibility enforcement so students only
ever see materials they are authorized to access. This is the narrower, MAT-2/MAT-3-only goal issue #9 actually
scopes — module/lesson structure itself (MAT-1) is inherited from `course-management`, not rebuilt here.

Without this module, `course-management`'s module/lesson scaffold has no actual content attached to it —
Teachers can structure a course but cannot put anything learnable inside it. This blocks the eventual
`enrollment-management`/Student experience even though this module itself does not activate enrollment or
process payment.

Two genuine, named forward-dependencies constrain this module and are **not resolved by this plan**:
- **`integration-management`** (external object-storage `api`) does not exist anywhere in the codebase. MAT-2's
  upload path cannot be completed end-to-end without it landing first (§21 item 1).
- **`enrollment-management`/ENR-1** (Module 12) does not exist anywhere in the codebase. MAT-3's fetch-time
  visibility enforcement ships with an **interim access check** now, per the issue's own stated plan ("fetch-time
  visibility enforcement will likely ship with an interim access check and be revisited once Module 12's ENR-1
  lands") — not a workaround invented in this plan (§16, §21 item 2).

## 2. Roles and permissions

Staff sub-role authorization is **already implemented and merged on this branch** — `DomainArea.MATERIALS`
exists in `backend/src/main/java/com/lms/identityaccessservice/api/DomainArea.java`, and the full grant matrix
is already transcribed in `PermissionCheckServiceImpl` (verified directly, lines ~140, ~174, ~195-196, ~215),
matching `docs/requirements/user-roles-and-permissions.md` §2's "Materials" row exactly:

| Role | Materials grant | Notes |
|---|---|---|
| Tenant Admin / Institute Owner | `V/C/E/D` | Full oversight |
| Content Manager | `V/C/E/D` | Primary staff uploader/organizer |
| Course Coordinator | `V` only | Must be server-side blocked from `CREATE_EDIT`/`DELETE` — same pattern MAT-1's own course-management plan already called out for this exact role |
| Read-only Auditor | `V` only | `PermissionCheckServiceImpl`'s static initializer already structurally forbids any write grant for this role — inherited safety net, still needs a materials-specific test |
| Finance Staff, Student Support, Exam Manager, Attendance Operator | none | Any materials endpoint call must 403 |
| Teacher | Not in the flat matrix by design (ownership-scoped, not domain-flat, same as `course-management`'s `CourseAccessGuard` pattern) | Allowed only for lessons belonging to courses they are assigned to teach — resolved via a cross-module lookup into `course-management`'s `api` (§9) |
| Teacher Assistant | Per `user-roles-and-permissions.md` §3: "Create/edit modules, lessons, materials: Yes" — **PROVISIONAL, explicitly unratified** | Must not be hard-built as a confirmed decision; implement behind a clearly-labeled default, flagged in code/tests as pending sign-off (§21 item 3) |
| Student | Not in the matrix — interim access-scoped consumer | Read-only; gated by the interim check in §16, not a domain grant |
| Platform Admin | No implicit tenant-admin-equivalent access | Out of scope absent an audited impersonation flow |
| Anonymous / Public | No access | Materials are never part of any public storefront rendering |

Reuse `PermissionCheckService.hasPermission(DomainArea.MATERIALS, action)` exactly as `StaffService` and
`course-management`'s controllers already do — no new domain-area/matrix code is needed for the 8 staff roles.
Teacher/Teacher Assistant/Student authorization logic is **net-new** work for this module (§9).

## 3. Preconditions

- The target course, module, and lesson already exist, owned entirely by `course-management`
  (`feature/course-management`, unmerged). **This is a hard sequencing blocker**: this module's backend cannot
  be meaningfully implemented or tested until that branch (or at minimum its schema and `CourseLookupApi`) is
  available to build against (§20, §21 item 1).
- Tenant identity and actor identity are already resolved once, upstream, via `TenantContextHolder`/
  `AuthenticatedPrincipalHolder` before any `content-management` code runs; this module is a consumer of that
  context, never a re-resolver (`tenancy.md`).
- `DomainArea.MATERIALS` and its staff grant matrix already exist and are directly reusable (§2).
- `integration-management`'s object-storage `api` does not exist. MAT-2 cannot go to production without it
  (§9, §21 item 1) — this module defines the interface shape it expects, not a working implementation.
- `enrollment-management`/ENR-1 does not exist. MAT-3 must be built against an explicitly named, isolated
  interim-check abstraction (§16), swappable later without a rewrite.
- Tenant must be active; no document anywhere defines what "inactive tenant" does to in-flight material
  access — flagged, not resolved (§21 item 7).

## 4. User flows

### Inherited from `course-management` — not new work here
Module & lesson CRUD, tenant+course scoping, and the keyboard-operable reorder ("move up/down") equivalent to
drag-and-drop — all already delivered under MAT-1/MVP-008. `content-management` consumes this only by
reference (`lessonId`) through a narrow, read-only cross-module lookup (§9); it must never re-implement or
duplicate that CRUD surface.

### Normal flow — material upload (MAT-2, new)
1. An authorized actor (Tenant Admin/Content Manager via `DomainArea.MATERIALS`; Teacher/Teacher Assistant via
   course-assignment ownership) selects an existing lesson and submits a PDF/image/notes upload.
2. Backend resolves tenant + actor from context; runs `MaterialAccessGuard` (§9) to confirm the target lesson
   exists, belongs to the resolved tenant, and the caller is authorized for its parent course — reject 403/404
   *before* any storage interaction if this fails.
3. Server-side validation runs in order: size limit, then MIME/content-sniffing on the actual byte stream (not
   file extension or declared `Content-Type`) against an allow-list (PDF/image/notes only at MVP) — all before
   acceptance.
4. Only after every check passes does the module call `integration-management`'s (not-yet-built) object-storage
   `api`. File bytes never persist through Postgres and are never buffered long-term by the Spring Boot process
   itself.
5. On success, exactly one `material` row is created (`tenant_id`, `lesson_id`, `uploaded_by`,
   `storage_object_key`, `mime_type`, `size_bytes`, `sequence`, `visibility`, `created_at`).
6. On any failure at steps 2-4, zero DB rows and zero storage objects exist — this ordering itself is the "no
   partial write" guarantee, not a separate cleanup step.

### Normal flow — organization and fetch-time visibility (MAT-3, new)
1. Authorized staff/Teacher sets a material's `visibility` (`VISIBLE`/`HIDDEN`, §21 item 4) and its `sequence`
   within a lesson, via explicit keyboard-operable "move up"/"move down" controls (not drag-and-drop-only).
2. A Student opens the Lesson/Material View; the list endpoint re-derives tenant from context, applies the
   interim access check (§16) in place of real enrollment state, and returns only materials that are both
   explicitly attached to that lesson and currently `VISIBLE`.
3. When a Student (or any caller) requests a specific material by id — including to obtain a signed
   download/view URL — the backend independently re-runs the tenant + role + interim-access + visibility check
   at that exact request. Passing the earlier list-view filter is never treated as sufficient for the
   single-item fetch.

### Alternative / edge-case flows
- Teacher of tenant A (or a Teacher of the correct tenant but not assigned to the target course) attempts to
  create/list/delete materials against a `lesson_id` they don't own → 403/404, uniform response shape
  regardless of "wrong tenant" vs. "doesn't exist" vs. "not my course" (no existence leakage).
- Upload fails MIME/content-sniffing (e.g., a renamed executable) or exceeds the size limit → rejected, no
  partial DB or storage write.
- Unauthorized uploader (any role without a `MATERIALS` grant and without course-teacher ownership) → 403.
- **Bulk-upload partial-failure behavior is explicitly unspecified** (`docs/requirements/open-decisions.md`) —
  not invented here; MVP-009 supports **single-file-per-request upload only** (§6).
- Student from tenant A, or from a different course in the same tenant, guesses/increments a material id →
  403/404, never a silently-empty 200.
- Student requests a material for a course they have not (verifiably) enrolled in — MVP-009's interim check
  cannot fully enforce this (§16); tracked as an accepted, named gap, not silently closed.
- Empty states: a lesson with zero materials (200 + `[]`) must be visibly distinct from a 403 access-denied
  response, on both the Teacher's Materials Manager and the Student's Lesson/Material View.
- Material deletion → exactly one audit-shaped domain event published in the same transaction (§16); creation/
  edit are explicitly **not** audit-mandatory per `.claude/rules/security.md`'s canonical list and spec §9.
- Course Coordinator / Read-only Auditor attempts a mutating call → 403 server-side.

## 5. Acceptance criteria

1. Given an authorized actor (Tenant Admin, Content Manager, or an assigned Teacher/Teacher Assistant) uploads
   a valid PDF/image/notes file to an existing lesson in their own tenant, it succeeds only after server-side
   size, MIME/content-sniffing, and ownership validation, producing exactly one `material` row and one storage
   object.
2. Given an unauthorized uploader, a failed validation (oversized, MIME-mismatched, or a non-existent/
   cross-tenant/not-owned `lesson_id`), the request is rejected with **no** partial write to Postgres or object
   storage.
3. Given a Teacher of tenant A (or an unassigned Teacher of the correct tenant) attempts to create/list/delete
   materials against a lesson they don't own, rejected 403/404 (cross-tenant/cross-ownership negative test
   mandatory, per `docs/requirements/module-catalog.md`'s content-management test row).
4. Given a Student's material list for a lesson, it reflects only materials explicitly attached **and**
   currently `VISIBLE` — enforced at fetch time, not just absent from navigation.
5. Given a Student fetches a specific material by id (including a download-URL request), the same tenant +
   role + interim-access + visibility checks are independently re-evaluated at that request, never inherited
   from a prior list call.
6. Given a Student from tenant A, or a different course in the same tenant, guesses/increments a material id,
   rejected 403/404 — uniform response shape regardless of whether the id belongs to another tenant, another
   course, or doesn't exist at all (no existence leak).
7. Given a lesson/module reorder or a material reorder action, a keyboard-operable "move up"/"move down"
   control exists and is functionally equivalent to any drag-and-drop affordance — verified by an accessibility
   test (Playwright), not asserted only visually.
8. Given `enrollment-management`/ENR-1 does not exist yet, MAT-3's access check is an explicitly named,
   isolated interim implementation (§16) with a tracked follow-up to fully re-enable real enrollment-state
   checking once ENR-1 ships — never presented as permanently done.
9. Given a material is deleted, exactly one `MaterialDeletedEvent` is published in the same transaction as the
   deletion, carrying actor, tenant, target material id, lesson/course id, timestamp, and a before-state
   snapshot; creation/edit require no such event.
10. **Contradiction resolved (Phase-table authoritative, same precedent MVP-008 used for its own Zoom-recording
    contradiction)**: the spec doc's acceptance-criteria bullet "a material with an expiry date in the past
    returns a distinct 'access expired' state" is **excluded from MVP-009**. `docs/requirements/specifications/
    06-lessons-and-materials.md` §10 and `functional-requirements.md`'s FR-CNT-3 both place expiry/view-download
    limits/watermarking in Phase 2. The `expiry_at` column exists now (cheap, forward-compatible) but stays
    **unenforced** — no denial logic reads it in MVP-009.
11. **Scope flag, not silently resolved**: FR-CNT-5 places general "drag-and-drop ordering"/versioning/bulk
    upload/folder structure in Phase 2, while the issue's own DB-requirements text implies a `sequence` column
    exists at MVP. Resolution adopted here: a persisted `sequence` value and its keyboard-operable reorder
    control are in scope (mirrors the already-shipped lesson/module pattern exactly); a richer drag-and-drop
    *experience* beyond that keyboard-equivalent control is not required for MVP.
12. Given a staff role with no `MATERIALS` grant calls any materials endpoint, rejected 403 server-side.
13. Given Course Coordinator or Read-only Auditor (both `V`-only) attempts `CREATE_EDIT`/`DELETE`, rejected 403
    server-side, with a materials-specific test (not just inherited from the matrix's general test suite).
14. Given the visibility taxonomy is undefined anywhere in the requirements corpus, MVP-009 ships the issue's
    own accepted default — a DB `CHECK`-constrained `VISIBLE`/`HIDDEN` enum — not a richer taxonomy (§21 item 4).
15. Cross-tenant and cross-role negative tests are mandatory for material create, list, fetch-by-id,
    download-url, reorder, and delete (§18).

## 6. Out-of-scope items

- **Bulk upload** (multi-file transactional upload) — Phase 2 per FR-CNT-5; partial-failure behavior is an
  explicit, unresolved open decision. MVP-009 supports single-file-per-request uploads only.
- **Expiry enforcement** (denied-state on expired material) — Phase 2 per FR-CNT-3/spec §10; see AC #10.
  `expiry_at` column exists now, unenforced.
- **View/download limits** — Phase 2 per FR-CNT-3.
- **Static and dynamic watermarking** — static is Phase 2 (FR-CNT-4), dynamic is Phase 3 (FR-CNT-6).
- **Material versioning and folder structure** — Phase 2 per FR-CNT-5.
- **Full drag-and-drop material-reordering experience beyond the keyboard-equivalent control** — Phase 2 per
  FR-CNT-5 (see AC #11).
- **YouTube/Vimeo link attachment** — Phase 3 per FR-CNT-1 and `module-catalog.md`'s content-management phase
  entry.
- **Zoom recording attachment** — owned entirely by `live-class-management`, Phase 2 in full; not deliverable
  at MVP regardless of `source-requirements.md` Module 7's unphased wording (spec §10's own pre-existing,
  already-documented contradiction — same resolution precedent MVP-008 used).
- **Document analytics** — Phase 3 per FR-CNT-6.
- **Real enrollment-state-based visibility enforcement** — blocked on `enrollment-management`/ENR-1; ships as
  an interim check only (§16), tracked for follow-up.
- **Object-storage integration build-out** — owned by `integration-management`, which does not exist; MVP-009
  depends on, but does not implement, that domain's `api` surface.
- **Module/lesson CRUD and its own reorder control** — already delivered by `course-management`; consumed by
  reference only, never duplicated.
- **Video-specific protected playback** (secure streaming, watch-time tracking, concurrent-session blocking) —
  owned by `video-access-management` per spec §7's explicit boundary note.
- **Tenant Admin "Materials Oversight" screen** (folder structure/bulk-upload management) — named in
  `screen-map.md`/spec §UI-notes but not in issue #9's stated frontend scope (`app/(teacher)/`,
  `app/(student)/` only). Deferred as an explicit decision, not an oversight.
- **Platform Admin cross-tenant materials oversight / impersonation-based access** — undefined anywhere for
  this module; out of scope pending the impersonation flow in `user-roles-and-permissions.md` §5.
- **Inline-authored text notes (a rich-text editor, no file upload)** — MVP-009 treats "notes" as an uploaded
  file (txt/markdown/doc), validated identically to PDF/image, consistent with the spec's literal wording
  ("uploads PDFs/images/notes"). If the intended UX is instead an inline note editor with no object-storage
  interaction, that is a different schema/feature and needs explicit confirmation before implementation
  (§21 item 8).

## 7. Domain model

`content-management` owns one aggregate, `Material`, referencing `course-management`'s `course_lesson` by an
**opaque UUID only** — no JPA `@ManyToOne` association, no import of `course-management`'s entity/repository
classes, per `.claude/rules/architecture.md`'s cross-module boundary rule. This mirrors the exact pattern
`CourseLesson` itself already uses for `moduleId` (a same-domain, still-opaque-reference precedent) and the
pattern `Course.teacherId` uses for a cross-domain (`user-management`) reference.

- **`Material`** — belongs to exactly one `course_lesson` (by id), ordered by `sequence` within that lesson.
  Carries `title`, `originalFilename`, `storageObjectKey`, `mimeType`, `sizeBytes`, `visibility`, `expiryAt`
  (unenforced at MVP), `uploadedBy`. Extends `Auditable`, implements `TenantOwned`, matching `CourseModule`/
  `CourseLesson`'s exact shape convention.

**Reconciled design decision — schema-level FK vs. pure opaque reference (the two specialist agents disagreed;
this plan resolves it):** the solution-architect review recommended *no* database foreign key at all from
`material` to `course_lesson`, reasoning that any physical FK crossing a module boundary couples two
independently-developed migrations and works against `modular-monolith.md`'s future per-domain-schema
extraction path. The database-architect review recommended a **composite FK** `(tenant_id, lesson_id)
REFERENCES course_lesson (tenant_id, id)`, matching the exact precedent already used by every other
parent/child pair in this codebase (`course_module → course`, `course_lesson → course_module`,
`course_price_history → course`). **This plan adopts the composite-FK design.** `.claude/rules/tenancy.md`'s
data-model section is explicit and non-optional: *"Foreign keys from one tenant-owned table to another must
reference rows within the same tenant; where the referenced table doesn't enforce this by its own PK shape, the
migration should add a composite FK or a check/trigger... not just an FK on the child ID alone."* That rule
draws no exception for "a different owning domain," and today's architecture (ADR-002, shared-database tenancy)
is a single physical Postgres schema, so the FK is achievable without importing any Java code across the
boundary — a SQL `FOREIGN KEY` constraint referencing a table by name requires no JPA entity import. The
solution-architect's underlying concern (no cross-domain **object-graph** coupling) is preserved exactly: at
the **Java/JPA level**, `Material.lessonId` stays a bare `UUID` field with no `@ManyToOne`/no import of
`CourseLesson` — identical to how `CourseLesson.moduleId` itself already works. If/when this codebase actually
splits into per-domain physical schemas (a documented but not-yet-triggered future path), this FK is one of the
migrations that would need to be revisited at that time — that is an accepted, explicit tradeoff, not an
oversight.

No `enrollment-management` or `payment-management` entity/table is referenced anywhere in this domain.

## 8. Database design

**Blocking prerequisite, found during this review, not something this module can fix itself**: `course_lesson`
(as currently drafted on the unmerged `feature/course-management` branch, migration `V11`) has **no**
`UNIQUE (tenant_id, id)` constraint — every sibling parent table in that same migration (`course`,
`course_module`) has one, but `course_lesson` only has `uq_course_lesson_sequence UNIQUE (tenant_id, module_id,
sequence)`. PostgreSQL requires a composite FK's referenced column set to exactly match an existing unique/PK
constraint on the parent table. `material`'s mandated `(tenant_id, lesson_id) REFERENCES course_lesson
(tenant_id, id)` FK **cannot be created** until this is fixed. This must land as a `course-management`-owned
fix — either editing `V11` directly (safe pre-merge, since that migration is not yet applied anywhere shared)
or a follow-on migration before `course-management` merges — coordinated with whoever owns that branch, not
something `content-management` can resolve unilaterally (§20, §21 item 1).

**Migration sequencing**: this branch's migration history stops at `V10__create_staff_profile.sql`.
`course-management` (V11-V14), `student-management` (V11), and `teacher-management` (V11) are three
independent, unmerged branches that each separately claim `V11` on top of `V10` — a three-way numbering
collision that isn't this module's decision to resolve, but must be resolved (by whoever merges them, in
whatever order is chosen) before `content-management`'s own migration can be assigned a real number. This
migration is therefore proposed as a **placeholder-numbered** file, `V15__create_content_management_schema.sql`
(next-free assuming `course-management`'s V11-V14 lands first, which this module hard-depends on regardless of
numbering) — to be renumbered at actual merge time.

### `material`

```sql
CREATE TABLE material (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenant (id),
    lesson_id            UUID NOT NULL,
    title                VARCHAR(255) NOT NULL,
    original_filename    VARCHAR(255) NOT NULL,
    storage_object_key   VARCHAR(1024) NOT NULL,
    mime_type            VARCHAR(255) NOT NULL,
    size_bytes           BIGINT NOT NULL,
    sequence             INTEGER NOT NULL,
    visibility           VARCHAR(10) NOT NULL DEFAULT 'VISIBLE'
                         CHECK (visibility IN ('VISIBLE', 'HIDDEN')),
    expiry_at            TIMESTAMPTZ,
    uploaded_by          UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,
    created_by           UUID,
    updated_by           UUID,

    CONSTRAINT uq_material_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_material_sequence UNIQUE (tenant_id, lesson_id, sequence),

    CONSTRAINT fk_material_lesson FOREIGN KEY (tenant_id, lesson_id)
        REFERENCES course_lesson (tenant_id, id),
    CONSTRAINT fk_material_uploaded_by FOREIGN KEY (tenant_id, uploaded_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_material_sequence CHECK (sequence > 0),
    CONSTRAINT ck_material_size_bytes CHECK (size_bytes > 0)
);

CREATE INDEX idx_material_tenant_lesson_sequence
    ON material (tenant_id, lesson_id, sequence);
```

Conventions matched exactly: `id` UUIDv7, app-generated, no DB default (V1 baseline); `Auditable` columns; every
cross-table reference is a **composite** `(tenant_id, ...)` FK, never a bare id FK (`tenancy.md`); `sequence`
naming and its unique/index pair mirror `course_module`/`course_lesson` verbatim, for the same keyboard-reorder
read/write pattern. `visibility` is the issue's own accepted MVP default (`VISIBLE`/`HIDDEN` only,
CHECK-constrained) — widening it later is a new migration, not an edit to this one. `mime_type` is deliberately
**unconstrained** at the DB level (no CHECK allow-list) — the MIME/content-sniffing allow-list is a
service-layer, evolving concern; the database only records what was already validated at write time.
`expiry_at` exists now (cheap, non-breaking) but is unenforced (AC #10). No `material_type`/derived-classification
column is added — the File Preview UI can classify PDF/image/notes from `mime_type` directly, avoiding an extra
stored, potentially-drifting column for a purely-cosmetic need.

**Reorder concurrency**: the `uq_material_sequence` unique constraint means a "move up/down" swap between two
rows needs the same service-layer strategy `CourseModuleService`/`CourseLessonService` already had to solve for
an identical constraint (e.g., a temporary out-of-range sequence value mid-swap, or a single
`UPDATE ... FROM`) — confirm and reuse their approach rather than re-deriving one (§9).

No hard-delete restriction: `material` is not one of `.claude/rules/backend.md`'s named append-only domains
(ledger/audit/payment); deletion is a real row delete, paired with an in-transaction domain event for audit
(§16) — not a soft-delete/`deleted_at` design, which would be a materially different, undiscussed decision.

## 9. Backend design

Package: `com.lms.contentmanagement`, following `modular-monolith.md`'s convention:

```
com.lms.contentmanagement
├── api
│   └── MaterialDeletedEvent.java       // domain event, published in-tx; future audit-log-management consumer
├── material
│   ├── domain
│   │   ├── Material.java               // extends Auditable, implements TenantOwned
│   │   └── MaterialVisibility.java     // VISIBLE, HIDDEN (+ JPA AttributeConverter, mirrors CourseStatus)
│   ├── repository
│   │   └── MaterialRepository.java     // extends TenantAwareRepository<Material, UUID>
│   ├── service
│   │   ├── MaterialService.java        // owns @Transactional boundaries: create/reorder/delete/list
│   │   ├── MaterialAccessGuard.java    // tenant + ownership + permission resolution (below)
│   │   └── UploadValidationService.java // size + content-sniffing, runs before any storage/DB write
│   └── web
│       ├── MaterialController.java     // @PreAuthorize("isAuthenticated()") coarse gate; thin
│       └── dto/ (MaterialUploadRequest, MaterialResponse, MaterialReorderRequest, MaterialDownloadUrlResponse)
```

### Cross-module ownership resolution (the real design problem this module has to solve)

A naive design that only calls `course-management`'s existing `CourseLookupApi.getTeacherId(courseId)` against
the **path's** `courseId` is unsafe: a Teacher who legitimately owns `courseA` could submit a request whose path
says `courseA` but whose body/another path segment names a `lessonId` that actually belongs to `courseB` (which
they don't own) — passing the ownership check on `courseA` while persisting a `Material` row against a lesson
they don't control. Since materials are fetched by `lessonId` directly (not re-validated against
`course-management` on every read), this is a real cross-course content-injection risk, not a hypothetical.

**Adopted design**: extend `course-management`'s existing `api` package with one narrow, additive, read-only
method (a coordinated cross-branch addition, §21 item 1):

```java
// com.lms.coursemanagement.api.CourseLookupApi — new method, additive to the existing interface
Optional<LessonOwnership> resolveLessonOwnership(UUID lessonId);

public record LessonOwnership(UUID lessonId, UUID moduleId, UUID courseId, UUID teacherId, boolean coursePublished) {}
```

Implemented inside `course-management` (which already legitimately owns the `course_lesson → course_module →
course` join), scoped internally to the caller's already-resolved tenant context — never a parameter — so a
cross-tenant `lessonId` returns `Optional.empty()`, structurally a 404, mirroring `CourseAccessGuard`'s
"tenant-scoped lookup first" pattern.

`MaterialAccessGuard` flow, run on every mutating request (create, reorder, delete) and re-run independently on
every read (list, single fetch, download-url — a single in-process call, not a network hop, in a modular
monolith):
1. `resolveLessonOwnership(lessonId)` → empty → 404.
2. Any path-segment (`moduleId`/`courseId`) inconsistency with the resolved result → 404, never a distinguishing
   400 that would leak which segment was wrong.
3. `permissionCheckService.hasPermission(DomainArea.MATERIALS, action)` → allow (staff).
4. Else `resolved.teacherId().equals(principal.userId())` → allow (owning Teacher/Teacher Assistant, §21 item 3).
5. Else, for a `STUDENT`-role caller on a **read** action only: apply the interim check (§16).
6. Else 403.

### Storage abstraction (blocked on `integration-management`, §21 item 1)

`integration-management` does not exist anywhere in the codebase. This module defines, but does not implement,
the interface shape it depends on:

```java
// com.lms.integrationmanagement.api.ObjectStorageApi — does not exist yet; hard blocking dependency
public interface ObjectStorageApi {
    StoredObject store(StoreObjectCommand command);
    void delete(String objectKey);
    SignedDownloadUrl generateSignedDownloadUrl(String objectKey, Duration ttl);
}
public record StoreObjectCommand(UUID tenantId, InputStream content, String detectedMimeType, long sizeBytes, String fileName) {}
public record StoredObject(String objectKey, long sizeBytes) {}
public record SignedDownloadUrl(String url, Instant expiresAt) {}
```

`MaterialService` depends on this interface via constructor injection so it is unit-testable with a test double
today, and swaps to the real bean once `integration-management` ships. **The upload endpoint and the
download-url endpoint cannot go to production without that domain actually existing** — do not stand up a
silent local-filesystem/fake implementation to route around this; `architecture.md` explicitly forbids
self-hosted binary media storage through the app tier.

**Transaction boundaries** (per `.claude/rules/backend.md` — never span a DB transaction across an outbound
call): **upload** — validate, then call `ObjectStorageApi.store(...)` *outside* any DB transaction; only on a
successful return does a separate `@Transactional` method persist the `Material` row referencing the returned
`objectKey`. **delete** — one `@Transactional` method deletes the row and publishes `MaterialDeletedEvent`
synchronously in the same transaction (satisfies `security.md`'s "audit write in the same transaction boundary
as the privileged action"); a `@TransactionalEventListener(phase = AFTER_COMMIT)` then calls
`ObjectStorageApi.delete(objectKey)` after commit, keeping the external call out of the DB transaction.

### Open scope question this design does not silently resolve

"Notes" (PDF/image/**notes**) is read here as an uploaded text/markdown/doc **file**, validated identically to
PDF/image (§6) — not an inline rich-text editor with no storage interaction. If the latter is actually intended,
the `Material` shape (`storageObjectKey`/`mimeType`/`sizeBytes` would need to become nullable, plus a `body`
text column) and its upload validation path both change materially — confirm before implementation (§21 item 8).

## 10. API contract

Draft only — must be finalized via the `review-api-contract` skill into `docs/api/content-management.md`
before implementation starts on either side, mirroring `docs/api/course-management.md`'s existing shape
exactly (same `ApiResponse<T>` envelope, same pagination envelope where relevant). All responses use
`com.lms.common.api.ApiResponse<T>`. No client-supplied `tenantId` is ever accepted.

| Method + path | Auth | Notes |
|---|---|---|
| `POST /api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials` | `MATERIALS` `CREATE_EDIT` (staff) or owning Teacher/TA | Multipart: `file`, `title`, `description?`. `201` on success. Server-side size + content-sniffing validation before any storage call; `413`/`415`/`422` on rejection, no partial write. **Blocked on `ObjectStorageApi` existing.** |
| `GET /api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials` | `MATERIALS` `VIEW` (staff), owning Teacher/TA, or Student (interim check, §16) | `200`, ordered by `sequence`. |
| `GET /.../materials/{materialId}` | Same as list, independently re-checked | `200` metadata only — never a raw storage URL or byte stream in this response. |
| `GET /.../materials/{materialId}/download-url` | Same as list, **re-verified on this exact request** | `200`, short-lived signed URL via `ObjectStorageApi.generateSignedDownloadUrl`. **Blocked on `ObjectStorageApi`.** |
| `PATCH /.../materials/reorder` | `MATERIALS` `CREATE_EDIT` (staff) or owning Teacher/TA | Body: ordered list of `materialId`s for one lesson. Validate the id set matches the existing set for that `(tenant, lesson)` before renumbering `sequence` transactionally; `400` on mismatch. This — plus explicit "move up"/"move down" affordances in the frontend calling single-step reorders through the same endpoint shape — is the required keyboard-operable alternative to drag-and-drop. |
| `DELETE /.../materials/{materialId}` | `MATERIALS` `DELETE` (staff) or owning Teacher/TA | Deletes row + publishes `MaterialDeletedEvent` in-tx; `ObjectStorageApi.delete` after commit. **Blocked on `ObjectStorageApi`.** |

Common error codes: `401` unauthenticated; `403` permission/ownership denied; `404` tenant-scoped/ownership
not-found (including path-segment/lesson-ownership mismatch — never a distinguishable `400` that would leak
existence); `400` validation; `413`/`415` upload-specific; `502`/`503` storage-provider unavailable (once wired).

## 11. Frontend screens

**Teacher** (`app/(teacher)/teacher/courses/[courseId]/...`) — note: neither `(teacher)` nor `(student)` route
group currently has any *course* routes on this branch, since `course-management`'s frontend isn't merged
either; paths below are provisional and must be re-anchored once that lands.

- **Materials Manager**, nested per-lesson inside the (inherited) Module & Lesson Editor: expandable Materials
  list per lesson with File Preview rows. States: `Loading` (row-shaped skeleton, not full-page spinner),
  `Empty` ("No materials added to this lesson yet" + Upload CTA — call-site copy, not shared-component
  default), `Error` (Retry, `role="alert"`), `Permission-denied` (controls hidden for `V`-only roles as UX
  convenience; a stale-permission 403 on any action still routes through the shared `PermissionDeniedState`,
  server-verified only). Keyboard reorder: explicit "Move up"/"Move down" `IconButton`s per row with
  row-specific `aria-label`s (e.g. `aria-label="Move Lecture-1-slides.pdf up"`) — required by
  `docs/ui-ux/accessibility.md` §1 for this exact surface. Mobile-first responsive: single-column stacking,
  Move up/down remains the only reorder affordance at narrow widths (there is no drag-only fallback to design
  around, since drag was never the sole mechanism).
- **Upload Control** (shared component, reusable if Tenant Admin's Materials Oversight is ever built): `Default`
  (dashed drop zone + Browse button + **visible**, not hint-only, accepted-format/size-limit text — the exact
  allow-list and byte limit are undefined anywhere in the spec, §21 item 5), `Drag-over`, `Uploading`
  (per-file progress, input disabled mid-flight), `Error` (`Validation Message` inside a `role="alert"` region
  — the issue's own explicit test requirement). Accessible file-input label must state accepted formats + size
  limit per `accessibility.md` §6.
- **File Preview**: thumbnail/type icon + filename + size + remove (pre-submit) or "View" (post-submit). The
  "View" action must never render a raw/predictable storage URL — always calls the signed download-url endpoint
  on click/render, per `.claude/rules/security.md`.

**Student** (`app/(student)/student/courses/[courseId]/lessons/[lessonId]/`) — this route group already exists
on this branch (`student/dashboard/...`), but has no course routes yet either.

- **Lesson/Material View**: `Loading` (skeleton for list; a full-viewer spinner is acceptable only for the
  active document/video transition itself). `Empty` (lesson has no materials — distinct copy from the denied
  case below). `Error` (generic fetch failure, Retry). **Denied/not-found** (cross-tenant/cross-course id
  guessing per AC #6): must render the **same generic UI regardless of 403 vs. 404**, with hardcoded
  endpoint-specific copy ("This material isn't available") — flagged because the existing
  `permission-denied-state.tsx` currently renders `error.message` verbatim, which is safe for "you lack
  permission" contexts but not for this specific anti-enumeration case; this screen must not pipe the raw
  backend message through unless the backend guarantees identical `code`+`message` for both 403 and 404 on this
  endpoint (§21 item 6). **Explicit scope note**: the spec's "distinct denied state" language sits in the same
  sentence as expiry/limit enforcement (Phase 2, out of scope here per AC #10) — the only denied case that's
  real at MVP is the tenant/role/visibility mismatch above; do not build "expired"/"limit reached" copy yet.
- **Course Card `Locked` state** (My Courses, Catalog/Browse): dims thumbnail, overlays a lock icon + a
  server-returned reason (never a client-inferred guess from local enrollment cache, per `.claude/rules/
  ui-ux.md` §1's Student-scope rule). Stays keyboard-focusable so the reason is announced to assistive tech,
  even though the card itself isn't otherwise actionable.

**Tenant Admin Materials Oversight** is out of scope for this pass (§6) — flagged as a documented deferral, not
an oversight, since it's named in two source docs (`06-lessons-and-materials.md`, `screen-map.md`).

## 12. Validation rules

- React Hook Form + Zod for the upload form, per `.claude/rules/frontend.md`. `lessonId` sourced from the
  already-loaded lesson tree, never free text. `file`: `type` must be in the accepted-MIME allow-list, `size`
  must be ≤ a max-byte constant — **both values are undefined anywhere in the spec** (§21 item 5); recommend
  sourcing them from one shared constant (or a tiny backend-served "upload policy" value) so the client's Zod
  check, the accessible label's stated formats/size, and the server's real allow-list never drift independently.
- `visibility` **cannot be schema'd with a placeholder value** — ship the resolved `VISIBLE`/`HIDDEN` enum once
  §21 item 4 is confirmed at implementation time; it is treated as settled for MVP per the issue's own text, not
  a genuinely open UI question.
- Explicitly excluded from the MVP form schema: expiry date, view/download limit, watermark toggle (Phase 2,
  §6) — even though `screen-map.md`'s one-line "Materials Manager" description bundles them in; that's a
  documentation discrepancy, not a signal to build them now.
- Client-side validation is **UX-only**, never authoritative (`.claude/rules/security.md`): a file that passes
  the Zod MIME/size check must still be handled as capable of failing the backend's real content-sniffing (a
  renamed executable with a spoofed extension/MIME passes a naive client `type` check) — the Upload Control's
  `Error` state must be reachable identically from both the pre-submit Zod failure and a post-submit
  `422 VALIDATION_ERROR`, using one rendering path, not two different error UIs for the same conceptual failure.
- Every field uses the existing Form Field Wrapper pattern (`htmlFor`/`id`, `aria-describedby`,
  `aria-required`) so a 422 response's `fieldErrors` array maps onto the same per-field slots the client-side
  Zod errors already use.

## 13. Error cases

1. **Oversized upload** — client Zod blocks pre-submission; if bypassed, backend 413/422 renders through the
   same Upload Control `Error` state.
2. **MIME mismatch (renamed executable)** — client check is advisory only; backend content-sniffing is
   authoritative. Frontend treats a backend rejection here as a normal, expected error path, server message
   surfaced verbatim (not leak-sensitive).
3. **Unauthorized uploader** — 403 renders `PermissionDeniedState` scoped inline to the Materials section (the
   rest of the lesson editor stays usable for a `V`-only role), driven by the actual `ApiClientError`, never a
   locally cached permission flag.
4. **Cross-tenant/cross-role id-guessing on fetch** — 403/404 must render the **same** generic denied/not-found
   UI (§11's flagged gap against the current `PermissionDeniedState` default behavior) — this is exactly the
   scenario spec §8's acceptance criteria and `.claude/rules/security.md`'s enumeration-test requirement both
   target; must not be left undecided at implementation time.
5. **Failed upload announces via `role="alert"`** — explicit issue requirement; satisfied by following the
   existing `error-state.tsx`/`permission-denied-state.tsx` convention already in this codebase, not a
   hand-rolled `aria-live` region.
6. **Course Card Locked reason integrity** — a missing backend reason field degrades to a generic "Locked"
   label; a frontend that infers a specific reason ("Payment required" vs. "Not enrolled") from local state
   instead would violate the Student-scope no-business-logic-in-frontend rule.
7. **Global fallbacks already in place** (verified, no gap): `frontend/src/app/not-found.tsx` and `error.tsx`
   already exist and are accessible — new material routes reuse them for truly-unexpected errors; only the
   material-specific denied/not-found case (item 4) needs bespoke handling.

## 14. Tenant-isolation rules

- Every tenant-owned entity this module adds (`material`) is accessed exclusively through
  `TenantAwareRepository<T, ID>` — no hand-rolled `WHERE tenant_id = ?` in a custom `@Query`. If a custom
  `@Query` is unavoidable, it still routes through the tenant-aware base so the filter is structural.
- `tenant_id` is resolved exactly once, from `TenantContextHolder`, at both write time (service layer) and
  read time (repository layer) — never accepted as a field on any request/response DTO, never taken from a
  path/query param even for a staff-facing endpoint.
- `material.lesson_id`'s composite FK `(tenant_id, lesson_id) REFERENCES course_lesson (tenant_id, id)` (§7,
  §8) makes a cross-tenant reference a schema-level constraint violation, not a service-layer-only check —
  contingent on the `course_lesson` unique-constraint fix in §8/§21 item 1.
- `fk_material_uploaded_by` composite FK to `tenant_user(tenant_id, id)` confirms an uploader can never belong
  to a different tenant than the material row, structurally.
- Index `(tenant_id, lesson_id, sequence)` leads with `tenant_id`, matching this module's actual query shape —
  a bare `tenant_id` index alone is not sufficient per `backend.md`'s indexing rule.
- Cross-tenant negative tests are mandatory for every new endpoint/query (create, list, fetch-by-id,
  download-url, reorder, delete) — see §18; these satisfy both the security and tenancy review gates, not two
  separate suites.
- Async/background concerns: if upload/storage confirmation ever moves off the request thread (e.g., once
  `integration-management`'s real implementation lands), `tenant_id` must be carried explicitly in the
  job/event payload and set via `TenantContextHolder` — it is not inherited automatically across a thread
  boundary. Flagged now as a requirement for whatever async storage-confirmation mechanism eventually ships.

## 15. Security rules

Upload validation pipeline (`UploadValidationService`), server-side, strictly in this order, all before any
storage call:
1. Authenticate + resolve tenant (never a request field).
2. `MaterialAccessGuard` ownership/permission check (§9) — tenant-scoped lesson lookup first, so a cross-tenant
   lesson id is a 404 before any ownership logic runs.
3. Size check against a server-side configured max, enforced with a streaming cap that aborts before full
   buffering — never trusting a client-declared `Content-Length` alone.
4. MIME/content-sniffing on the actual byte stream (magic-number/library-based detection, allow-listed to
   PDF/image/notes) — never the client `Content-Type` header or file extension alone.
5. Only after 1-4 all pass does the module call `integration-management`'s object-storage `api`. If any check
   fails, **no storage call is made at all** — this ordering is the "no partial write" guarantee itself.
6. The `material` row is persisted only after storage confirms the object was written, in its own transaction
   step (§9).

Fetch-time authorization (list AND single-item AND download-url) is independently re-checked per request, never
cached client-side or trusted from a prior list response; a cross-tenant id is structurally invisible (404),
not filtered client-side. No fetch ever returns a raw/stable storage URL in a JSON body — every material view
mints a short-lived signed URL through the storage `api` (mirrors `security.md`'s video-playback-URL rule,
which the spec doc's own UI notes extend explicitly to non-video content-management materials too).

**Mandatory negative tests** (§18 has the full list): cross-tenant fetch by id; cross-role/cross-course fetch;
cross-tenant upload attempt (zero storage calls, asserted via mock/spy); oversized file; MIME-mismatched/
renamed-executable upload; unauthorized uploader (zero storage calls).

## 16. Audit requirements

Per `.claude/rules/security.md`'s Audit Logging section and spec §9 (both independently confirmed), **only
material deletion** is on the mandatory audit list for this module — creation/edit are explicitly not required.
Do not over-build a full CRUD audit trail beyond this; that is scope invention, not a safety improvement.

No `audit-log-management` domain/table exists anywhere yet. Following the exact precedent `course-management`
established for its own analogous requirement (`CoursePriceChangedEvent`): `MaterialService#delete` performs
the actual row delete and publishes a `MaterialDeletedEvent` — in the `content-management.api` package, per
`architecture.md`'s rule that other domains (including a future `audit-log-management`) only depend on a
module's `api` package — in the **same** `@Transactional` boundary as the delete itself, not a separate/
skippable call. The event payload carries actor id, tenant id, target material id, the lesson/course id it was
attached to, timestamp, and a before-state snapshot (title, mime type, uploaded-by, storage key) — satisfying
`security.md`'s "before/after where applicable" for a delete, which has no "after" state. No consumer exists
yet to persist this event; that is an accepted, named gap pending `audit-log-management`'s own build-out, not
something this module works around by writing to a bespoke audit table.

### Fetch-time visibility — the interim access check (MAT-3's central open item)

No `enrollment-management`/ENR-1 table exists, and no student-course roster/linkage substitute exists anywhere
in the current schema (`usermanagement`, `identityaccessservice`, `tenantmanagement` only have `tenant_user`,
`role_catalog`, `device_session`, `staff_profile` — none link a student to a specific course). Given that, the
interim check for a `STUDENT`-role caller can verify only: **tenant match + role == Student + the material's
parent course is `PUBLIC` (via `CourseLookupApi.isPublished(courseId)`, which already exists) + the material's
own `visibility == VISIBLE`.** It cannot verify actual enrollment/payment status, because nothing in the schema
records that yet.

This is adopted as the MVP behavior **because the issue itself names this exact tradeoff as the intended path**
("fetch-time visibility enforcement will likely ship with an interim access check and be revisited once Module
12's ENR-1 lands") — it is not an interpretation invented by this plan. The accepted consequence, stated
plainly: **a Student in the correct tenant can view a published course's materials without having a paid/
active enrollment for that specific course.** This is not a violation of `.claude/rules/payments.md` (this
module never activates enrollment or touches payment/ledger data — see §17), but it is a real content-exposure
gap adjacent to that boundary, and must be tracked with priority once `enrollment-management`/ENR-1 exists
(§21 item 2) — not quietly treated as "done."

## 17. Payment impact

**None directly.** `content-management` creates, reads, or mutates no `Order`, `Payment`, or ledger entity, and
does not activate, extend, or revoke enrollment. No change-controlled payment-ledger rule (`.claude/rules/
payments.md` §1-§7) is touched by this module, which is why `payment-ledger-specialist` was not invoked for
this plan.

**Adjacent risk, flagged prominently rather than buried**: the interim visibility check in §16 means a student
without a paid/active enrollment for a specific course can still view that course's materials, as long as the
course is published and they belong to the correct tenant. This is a content-exposure gap next to the
payment/enrollment boundary, not a payment-ledger rule violation — but Finance/Product should be aware of it
before this module is considered fully "done," and it should be prioritized for closure once
`enrollment-management`/ENR-1 exists (§21 item 2).

## 18. Tests

### Backend — unit (Mockito only, no Spring context)
- `MaterialUploadServiceTest`: valid PDF/image/notes upload by an owning actor succeeds and calls
  `ObjectStorageApi` exactly once; a `.exe` renamed to `.pdf` is rejected by sniffed-byte content regardless of
  declared `Content-Type`/extension; a file type outside the PDF/image/notes allow-list is rejected even with a
  matching extension; oversized file rejected before any storage call; upload by a non-owning/unauthorized actor
  rejected with zero storage calls; every validation-failure branch (MIME, size, ownership) asserts
  `verifyNoInteractions(objectStorageApi)`; a storage-call failure after validation passes does not persist a
  `material` row (no orphaned metadata pointing at a non-existent object).
- `MaterialAccessGuardTest` (mirrors `CourseAccessGuardTest`): owning Teacher allowed view/create/delete on own
  course's lessons; non-owning Teacher denied on another Teacher's lessons; staff without the `MATERIALS` grant
  denied; staff with the grant allowed via `PermissionCheckService` delegation; Student never allowed
  create/edit/delete regardless of any grant.
- `MaterialOrderingServiceTest`: move-up/move-down swap only the two affected rows' `sequence`, leave all
  others unchanged; boundary no-ops (first item move-up, last item move-down) don't throw; reorder is scoped to
  a single lesson and cannot swap sequence across two different lessons.
- `MaterialDeletedEventTest`: deletion by an authorized actor publishes exactly one `MaterialDeletedEvent` with
  correct tenant/actor/target/before-state; a rejected/unauthorized deletion attempt publishes no event; a
  nonexistent/already-deleted id is rejected and publishes no event.

### Backend — Testcontainers integration (real Postgres, MockMvc + tenant resolution)
**Sequencing note**: these tests need a real `course`/`course_module`/`course_lesson` schema, which today only
exists on the unmerged `feature/course-management` branch — fixtures seed lesson rows via raw `JdbcTemplate`
inserts (mirroring `CourseStructureCompositeFkIntegrationTest`'s own pattern for `course`), never through
`course-management`'s HTTP API or test-support classes, per the cross-module boundary rule. These tests cannot
run on this branch until that schema is present (§20).

- `MaterialUploadIntegrationTest`: owning Teacher uploads PDF/image/notes successfully, sequence appended
  correctly; renamed-executable upload returns `422` and the material is absent from the list afterward
  (proves no partial write end-to-end, not just via mock verification); oversized upload returns `413`, no row
  persisted; non-owning-Teacher and Student uploads return `403`, no row persisted; cross-tenant `lesson_id`
  returns `404` (not `403`).
- `MaterialFetchVisibilityIntegrationTest`: student list for a lesson returns only materials attached to that
  lesson, not the whole course; single-material GET independently re-runs the authorization check (not just
  filtered out of the list endpoint); cross-tenant material-id guess → `404`, no partial data leak; staff from
  tenant A guessing a material id in tenant B → `404` even with a valid `MATERIALS` grant in their own tenant;
  a deleted material id is no longer fetchable.
- `MaterialCrossTenantAndCrossRoleEnumerationIntegrationTest`: sequential id-guessing across 2+ tenants never
  returns any field of another tenant's material, even partially; one student's material list never includes
  another student's/course's private materials in the same tenant.
- `MaterialDeletionIntegrationTest`: owning Teacher delete removes the row and returns `200`; deletion calls
  `ObjectStorageApi.delete` exactly once with the correct stored ref (stub bean in test context); cross-tenant
  delete → `404`, no storage call; non-owning-Teacher delete → `403`, row unchanged.
- `MaterialReorderIntegrationTest`: move-up/move-down endpoints persist the correct swapped `sequence` values;
  boundary no-ops return `200` without error (matters for the keyboard control never appearing broken/disabled);
  cross-tenant/cross-lesson material ids in a reorder request → `404`; Student/non-owning-Teacher reorder
  attempts → `403`.
- `MaterialCompositeFkIntegrationTest` (mirrors `CourseStructureCompositeFkIntegrationTest` exactly, both a JPA
  save attempt and a raw-SQL insert): a `material` row whose `tenant_id` doesn't match its parent lesson's
  `tenant_id` violates the composite FK, at the database level, not just in service-layer logic.

### Frontend — Playwright (`frontend/e2e/`, mocked backend via this repo's `page.route()` convention)
- `material-upload-states.spec.ts`: drag-over highlights the dropzone with a visible + announced state change;
  uploading state disables the input/shows progress until the mocked response resolves; upload error surfaces
  inside a `role="alert"` element with the server's message and moves focus to it; a second failed attempt
  replaces rather than stacks the alert; the file input's accessible label states accepted formats and size
  limit; successful upload appends the new material to the list without a full reload.
- `material-reorder-keyboard.spec.ts` (mirrors `course-builder-keyboard.spec.ts`'s pure-keyboard-driven
  pattern): each row exposes Tab-reachable "Move up"/"Move down" buttons, not only a drag handle; Enter on
  "Move down" for the first item swaps it with the second and the PATCH request body reflects the swap;
  boundary controls (first item's "Move up", last item's "Move down") remain present, focusable, and inert
  rather than removed or disabled-without-explanation; reorder state persists after reload.
- `material-student-visibility.spec.ts`: student sees only the mocked-API-returned material list for the
  current lesson; navigating directly to a material URL that the mocked API 403s or 404s on both render the
  same accessible Locked/access-denied state, with no download/preview control rendered underneath and no
  leaked title/filename from the denied material.

### Explicitly flagged test gaps (not silently closed)
- **Same-tenant, wrong-course student denial** cannot be authored as a real test yet — no `enrollment-management`
  exists to seed real (non-)enrollment state. The interim-check tests above only prove tenant+role+published+
  visibility; a same-tenant student requesting a *different* course's material will currently pass the interim
  check even though the broader product intent implies it shouldn't (§16). This gap must be re-flagged, not
  silently closed by a misleading "passing" test, once `enrollment-management` exists.
- **Real object-storage integration correctness** (presigned URL expiry, actual byte-for-byte storage) is out
  of scope for this module's tests — `ObjectStorageApi` is stubbed/mocked throughout.
- **Material versioning/folder structure** — no tests proposed; out of MAT-1..3 scope (§6).

Both suites (`backend\mvnw.cmd verify` with Docker running for Testcontainers; `npx playwright test` from
`frontend/`) must be run and their results reported as part of closing this module, per root `CLAUDE.md`'s
development-workflow steps 3/6 — not part of this plan-only section itself.

## 19. Documentation changes

- `docs/api/content-management.md` (new) — the finalized API contract (§10), produced via the
  `review-api-contract` skill before implementation starts on either side, mirroring
  `docs/api/course-management.md`'s structure (response envelope, auth requirements, authorization model,
  per-endpoint request/response shapes).
- `docs/architecture/database-architecture.md` — add the `material` table to the shared-schema table inventory
  (mirroring how `course-management`'s tables were added), documenting the composite-FK contingency on
  `course_lesson`'s missing unique constraint (§8) until that's resolved.
- `docs/architecture/modular-monolith.md` or a new short architecture note — document the `content-management`
  domain's existence, its `api` package surface (`MaterialDeletedEvent`), and its dependency on
  `course-management.api.CourseLookupApi.resolveLessonOwnership(...)` and the not-yet-built
  `integration-management.api.ObjectStorageApi`.
- `docs/requirements/module-catalog.md` — no content change expected (the `content-management` row already
  correctly describes this scope), but confirm after implementation that "Owns: Learning Materials Management
  (Module 7) in full" is amended to reflect that module/lesson *structure* itself is actually owned by
  `course-management`, not `content-management` — this plan's central scope-reconciliation finding should be
  reflected back into that catalog once accepted.
- `docs/requirements/open-decisions.md` — update the "visibility taxonomy" and "bulk-upload partial-failure"
  entries once/if they're formally resolved beyond this plan's working defaults (§21 items 4-5).

## 20. Implementation order

1. **Prerequisite, not this module's own step**: `course-management` (`feature/course-management`) merges (or
   is otherwise made available to build against), with the `course_lesson` `UNIQUE (tenant_id, id)` fix from
   §8 included. `content-management` cannot be meaningfully implemented before this.
2. **Prerequisite, coordinated addition**: `course-management.api.CourseLookupApi` gains
   `resolveLessonOwnership(UUID)` (§9) — needed by `content-management`'s authorization guard from day one.
3. Backend: `com.lms.contentmanagement` package scaffold, `Material` entity + repository + migration (§7-§8),
   `MaterialAccessGuard` + `UploadValidationService` (unit-testable against a mocked `ObjectStorageApi` even
   before `integration-management` exists), `MaterialService` (create/list/fetch/reorder/delete), controller +
   DTOs (§9-§10).
4. Backend tests (§18's unit + Testcontainers suites) — run and green before frontend work starts, per root
   `CLAUDE.md`'s workflow step 3.
5. `docs/api/content-management.md` finalized via `review-api-contract` (§19), before frontend implementation
   begins.
6. Frontend: Upload Control, File Preview, Materials Manager (nested in the inherited Module & Lesson Editor),
   Student Lesson/Material View, Course Card Locked state (§11).
7. Frontend/E2E tests (§18's Playwright suite) — run and green.
8. Security, tenant-isolation, and integration reviews (§14-§16), per root `CLAUDE.md`'s workflow step 6.
9. Documentation updates (§19).
10. **Explicitly deferred, not part of this module's delivery**: wiring a real `integration-management`
    implementation of `ObjectStorageApi` (§9, §21 item 1), and re-enabling full enrollment-based visibility once
    `enrollment-management`/ENR-1 exists (§16, §21 item 2) — both tracked as follow-up work, not blockers to
    calling MAT-2/MAT-3's *interim* scope done.

## 21. Risks and unresolved decisions

1. **Blocking — `integration-management` does not exist anywhere in the codebase.** MAT-2's upload path and the
   download-url endpoint cannot go to production without it. This module can define and unit-test against the
   `ObjectStorageApi` interface shape (§9) but cannot ship a working upload/download feature until that domain
   is built. Needs explicit sequencing/ownership decision from whoever plans cross-module delivery order.
2. **Named, accepted gap — `enrollment-management`/ENR-1 does not exist.** MAT-3's fetch-time visibility
   enforcement ships with the interim check in §16 (tenant + role + course-published + material-visible),
   which cannot verify real enrollment/payment status. A student in the correct tenant can view a published
   course's materials without a paid/active enrollment for that specific course. This is the issue's own
   stated intended tradeoff, not an invented workaround — but it must be tracked with priority for closure once
   ENR-1 lands, and Finance/Product should be explicitly aware of it (§17).
3. **Teacher Assistant's permission boundary is PROVISIONAL/unratified** (`user-roles-and-permissions.md` §3).
   Any implementation of TA create/edit rights on materials must be clearly labeled in code/tests as
   implementing a provisional default pending sign-off, not presented as a settled decision.
4. **Visibility taxonomy** — `docs/requirements/open-decisions.md` confirms no richer taxonomy is defined
   anywhere. This plan treats the issue's own proposed `VISIBLE`/`HIDDEN` CHECK-constrained default as accepted
   for MVP (matching how MVP-008 treated similar issue-embedded defaults) — flagged here as a working default,
   not an independently-ratified business decision, in case that reading is wrong.
5. **Accepted file types and size limit are undefined anywhere in the spec.** `06-lessons-and-materials.md`
   says only "PDFs/images/notes" with no concrete MIME/extension list or byte limit. This blocks finalizing the
   Zod schema, the accessible upload-label copy, and the server-side allow-list/size constant — needs a
   decision before implementation, not a guessed default.
6. **`PermissionDeniedState`'s current default behavior (render `error.message` verbatim) is unsafe for the
   material-fetch anti-enumeration case** (§11, §13 item 4) — either this one screen must use fixed local copy
   ignoring both `code` and `message`, or the backend contract must guarantee identical `code`+`message` for
   both 403 and 404 on this endpoint. Needs an explicit decision at API-contract-finalization time (§19 step 5),
   not left ambiguous.
7. **No document anywhere defines what "inactive tenant" does to existing/in-flight material access.** Flagged,
   not resolved.
8. **"Notes" is read as an uploaded file, not an inline-authored rich-text note** (§9, §6) — a reasonable
   reading of the spec's literal wording, but not independently confirmed. If wrong, the `Material` entity
   shape and its upload validation path both need to change before implementation.
9. **Migration-numbering three-way collision**: `course-management` (V11-V14), `student-management` (V11), and
   `teacher-management` (V11) are three independent, unmerged branches all claiming `V11` on top of this
   branch's `V10`. `content-management`'s own migration (proposed as placeholder `V15`) depends on that
   collision being resolved first, in whatever merge order is chosen — not something this module can decide.
10. **Database-defect finding on the unmerged `course-management` branch**: `course_lesson`'s `V11` migration
    is missing `UNIQUE (tenant_id, id)`, which every sibling parent table in that same migration has. This
    blocks `material`'s composite FK (§8) and must be fixed on that branch (directly editing `V11`, since it is
    not yet applied anywhere shared, or via a follow-on migration before merge) — coordinated with whoever owns
    that branch, not resolved unilaterally from this plan.
