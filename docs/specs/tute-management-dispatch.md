# Tute Management & Tute Dispatch (TUTE-01)

Status: implemented, live-accepted locally (2026-11-09, `docs/qa/tute-management-acceptance.md`) · Branch `feat/tute-management-dispatch` · Closes the R2-D4 omission
(`docs/plans/klass-gaps-round2.md`) and the 8 UNAVAILABLE Tute rows of the Staff matrix
(`docs/specs/staff-management-permissions-parity.md`).

Rule tags: **[K]** verified Klass (references in `docs/references/klass-tute-management/`),
**[L]** existing LMS rule, **[P]** proposed LMS behaviour (this module's decision, reviewable).

Change control: no change to ledger entry types, payment/order/checkout contracts, enrollment or
paid-entitlement activation (ADR-028), tenancy or auth architecture. Tutes never create orders,
payments or ledger rows. Paid-tute **checkout** (a tute as its own priced cart item) is
**out of scope and needs approval** (it would add an order-line scope = approved checkout contract).

## 1. Business goal

Staff create printed tutes (handouts) per course, decide which students receive them (manually or
from confirmed payments), and dispatch postal copies from one queue with printable A4 labels,
while stock, history and permissions stay auditable and tenant-isolated.

## 2. Roles

| Role | Access |
|---|---|
| Tenant Admin | everything (static `TUTES` V/C-E/D/A) [P] |
| Operator / Manager / 7 seeded sub-roles | nothing by default; each capability grantable in Staff → Permissions [L] |
| Teacher | nothing by default (new feature, no silent broadening) [P]; when granted, only courses where `course.teacher_id` = self [L] |
| Read-only Auditor | configurable for view-only capabilities only [L] |
| Student | own copies only, read-only: course page "Tute Delivery" tab (TUTE-02, owner-approved 2026-11-09) [P] |

## 3. Course → Manage Tutes [K]

- Course row More menu: **Manage Tutes** (between Edit Class Schedule and Archive) → page
  `/tenant-admin/courses/{courseId}/tutes`, title "Tutes of {Course}", breadcrumbs Courses › {Course}.
- Toolbar: Search (name), **Add Tute**. Table: Name | Quantity | Payment Type | Sold Through | Price |
  Actions; server pagination (10/25/50), "No data available" empty state [K]. Quantity column shows
  `quantity` plus "n available" [P].
- One dialog for create/edit, field order [K]: Tute Name* · Quantity* · Sell through a paid session
  (optional, searchable, clearable) · Payment Type* (Covered by class fees / Paid / Free) · Price
  (shown + required only when Paid [K]) · Description · Cancel / **Create Tute** (edit: **Save Changes**).
- Actions per row: View (assigned students), Edit, Delete; plus Sync payments in the view.

### Field rules
| Field | Rule |
|---|---|
| name | required, trimmed, 1..200 [P] |
| quantity | required whole number 0..1,000,000 = **total copies the institute holds for this tute** (lifetime stock). `available = quantity − reserved(PENDING) − dispatched(DISPATCHED)`; cancelled rows release. Edit may not set quantity below `reserved + dispatched` (409 `TUTE_QUANTITY_BELOW_ALLOCATED`) [P] |
| paidLessonId | optional; must be a `course_lesson` of **this** course in the caller's tenant with `payment_type = PAID`; else 400 `TUTE_SESSION_NOT_ELIGIBLE` (cross-course/cross-tenant/unknown look identical). Allowed **only when paymentType = PAID** (400 otherwise) [P] |
| paymentType | `COVERED_BY_CLASS_FEES` / `PAID` / `FREE` [K] |
| price | PAID: required, > 0, ≤ 999,999.99, scale ≤ 2 (common money rules) [K]+[L]; COVERED/FREE: must be null/absent — a non-null price is **rejected** with 400 `TUTE_PRICE_NOT_ALLOWED` (UI clears the field when leaving Paid) [P]. Currency = course currency [L] |
| description | optional, ≤ 2000 [P] |

Edits never rewrite history: each fulfillment snapshots tute name, payment type, price and
currency at creation [P]. Delete: no fulfillment rows ever → hard delete; otherwise **archive**
(`status = ARCHIVED`, hidden by default, no new assignments, history kept) [L convention: course archive].

## 4. Fulfillment model [P]

`tute_fulfillment` = one student's copy of one tute.

```
PENDING ──(confirm dispatch / mark handed over)──▶ DISPATCHED   (terminal, never undone)
   └────(unroll by staff, reason required)───────▶ CANCELLED    (terminal, releases stock)
```
- **Method**: `POSTAL` (enters To Send) or `COLLECTION` (handed over at class). Changeable only while
  PENDING (Update Tute Status).
- **Uniqueness**: at most one non-cancelled row per (tenant, tute, student) — partial unique index;
  retries/concurrent requests are idempotent (existing row returned / counted as "already assigned").
- **Stock**: reserved at row creation inside a transaction holding the tute row lock
  (`SELECT … FOR UPDATE`); manual assignment beyond `available` → 409 `TUTE_OUT_OF_STOCK`, nothing
  written. Dispatch does not change `available` (already reserved); cancel releases.
- **Hold**: `payment_reversed_at` set when the source payment is refunded/charged back. A held PENDING
  item is excluded from To Send until staff **Release hold** or **Unroll**; a DISPATCHED item is
  only flagged ("Payment reversed after dispatch") — never undone [P per payments rules].
- Actor + timestamp recorded for create, cancel, dispatch, hold release; audit entries via
  `AuditLogApi` (Staff Activities) without addresses/phones.

## 5. Triggers (automatic assignment) [P]

Automatic assignment reads **confirmed** payment facts only (pending/failed/rejected never count) [L],
creates `source = PAYMENT` rows with `source_payment_id`/`source_order_id`, never charges or unlocks
anything. Two entry points, same matcher:
1. `PaymentConfirmedEvent` (gateway + free checkout), AFTER_COMMIT, tenant from the event
   (ADR-032 exam precedent) [L].
2. Staff **Sync payments** per tute (covers slip approvals and manual receipts, which publish no event) [L precedent].

Only payments confirmed **at/after the tute's `created_at`** qualify — no historical backfill.

| Payment type | Qualifying confirmed line (same course) |
|---|---|
| Covered by class fees | a `MONTHLY` line whose period (year, month) ≥ the tute's creation month (Asia/Colombo), a `MODULE` line, or a legacy (line-less) course order. `LESSON` lines never qualify. Free Cards never qualify (not cash) [L ADR-030]; staff may assign card holders manually. |
| Paid | only when linked to a paid session: the `LESSON` line for `paid_lesson_id`. The session price is the only charge — the tute is included, never charged twice. Unlinked Paid tutes: manual assignment only (no checkout item — needs approval). |
| Free | manual assignment only (eligible = course participants). |

Out of stock on an automatic match: no row, structured warning, counted as `skippedOutOfStock`
in the Sync result; staff raise quantity and re-sync (idempotent).

Method on automatic rows: `POSTAL` when the order's V90 course charge has `postage_selected = true`
for that course, else `COLLECTION`. Zero postage fee or free price alone never decides it [P].
Manual assignment: staff choose method (default POSTAL).

## 6. Delivery address [P]

Resolution per item, at read time until dispatch:
1. the order's delivery snapshot (`student_order.delivery_*`) when the item came from an order with
   postage selected for that course;
2. else the student's profile address (`address_line`, `address_line_2`, `city`, `postal_code`) and
   phone `tute_delivery_phone_1` → `mobile`.
Missing = blank address line or city → row shows "No address provided" warning; printing warns with
the count first; **confirm dispatch rejects** missing-address POSTAL items (422
`TUTE_ADDRESS_MISSING`). On dispatch the resolved address is snapshotted onto the row (later profile
edits never rewrite it). No address is ever invented.

## 7. Tute Dispatch queue

- Nav item **Tute Dispatch** (`/tenant-admin/tute-dispatch`), capability-gated [K].
- Rows = **recipient groups**: (student, normalized address) over PENDING, POSTAL, not-held items.
  Same student at two addresses → two rows/labels; never merged [P].
- Toolbar [K]: Search (name / index / city), Course filter, Tute filter (narrowed by course),
  "2+ tutes only", **Print N labels (PDF)**. Columns [K]: checkbox, Index Number (sortable, link to
  student profile, leading zeros kept), Student Name, Tutes (count chip), To Send (chips
  "COURSE – Tute"), City. Name/Tutes/City sortable too [P].
- Filters narrow both rows **and label contents** (labels = the chips shown) [P]. "2+" applies to
  the filtered pending count per group, before pagination [P].
- Teacher: only items of own courses.
- Selection [P]: header checkbox = select page; banner offers "Select all N matching" (all-filtered
  mode). Selection clears on any filter/search change. Button reads "Print {n} selected labels" or
  "Print all {N} labels".
- One server projection serves rows, counts and PDF.

### Labels PDF [K layout, P improvements]
Portrait A4, 2 × 3 labels per page with dashed cut guides; per label: "TO", recipient name (bold),
address lines or "No address provided" (italic), "Tel: …" when known, course – tute list,
footer "From: {institute}" and "{n} tute(s)". Larger type than Klass, wrapping long names /
addresses, labels never split across pages, Noto Sans + Sinhala + Tamil fonts (existing report
fonts), selectable text. Response `Cache-Control: no-store`, `X-Label-Count`.

Printing/reprinting never changes status, stock, payments or notifications [P].
**Confirm dispatch** (separate action, Update Tute Status) revalidates each selected item
(tenant, scope, PENDING, POSTAL, not held, address present) and transitions it exactly once;
already-dispatched items are reported, not re-applied.

## 8. Permissions (Staff → Permissions, Tute group)

New `DomainArea.TUTES`; static grants: Tenant Admin V/C-E/D/A only. Every capability:
configurable for Operator/Manager/seeded roles (default = static matrix) and Teacher
(**default not granted**, ownership-scoped) [P].

| Capability (key) | Label | Pair | Operations |
|---|---|---|---|
| TUTE_VIEW_TUTES | View Tutes | TUTES/VIEW | list course tutes, paid-session options |
| TUTE_VIEW_TUTE | View Tute | TUTES/VIEW | tute detail + assigned students |
| TUTE_CREATE | Create Tute | TUTES/CREATE_EDIT | create |
| TUTE_UPDATE | Update Tute | TUTES/CREATE_EDIT | edit, sync payments |
| TUTE_ENROLL | Enroll Tute | TUTES/CREATE_EDIT | manual assignment, eligible-student search |
| TUTE_UNROLL | Unroll Tute | TUTES/CREATE_EDIT | cancel a PENDING item |
| TUTE_UPDATE_STATUS | Update Tute Status | TUTES/APPROVE | confirm dispatch, mark handed over, change method, release hold |
| TUTE_DELETE | Delete Tute | TUTES/DELETE | delete / archive |
| TUTE_DISPATCH_VIEW (new) | View Tute Dispatch | TUTES/VIEW | queue rows (name, index, city) |
| TUTE_DISPATCH_PRINT_LABELS (new) | Print Dispatch Labels | TUTES/VIEW | label PDF (full addresses/phones) |

Viewing a list never implies printing or confirming. Every endpoint carries `@RequiresCapability`
and the service re-checks the exact capability + course scope; grants are read per request, so a
revoke applies on the next request [L].

## 9. API (`/api/v1`, envelope `ApiResponse`, errors `ApiErrorCodes`)

| Method | Path | Capability |
|---|---|---|
| GET | `/courses/{courseId}/tutes?search&includeArchived&page&size` | VIEW_TUTES |
| GET | `/courses/{courseId}/tutes/paid-sessions?search` | VIEW_TUTES |
| POST | `/courses/{courseId}/tutes` | CREATE |
| GET | `/courses/{courseId}/tutes/{tuteId}` | VIEW_TUTE |
| PUT | `/courses/{courseId}/tutes/{tuteId}` (body includes `version`) | UPDATE |
| DELETE | `/courses/{courseId}/tutes/{tuteId}` → `{result: DELETED|ARCHIVED}` | DELETE |
| POST | `/courses/{courseId}/tutes/{tuteId}/sync-payments` → counts | UPDATE |
| GET | `/courses/{courseId}/tutes/{tuteId}/fulfillments?status&search&page&size` | VIEW_TUTE |
| GET | `/courses/{courseId}/tutes/{tuteId}/eligible-students?search` | ENROLL |
| POST | `/courses/{courseId}/tutes/{tuteId}/fulfillments` `{studentIds[≤200], method}` | ENROLL |
| POST | `/courses/{courseId}/tutes/{tuteId}/fulfillments/{id}/cancel` `{reason}` | UNROLL |
| POST | `/courses/{courseId}/tutes/{tuteId}/fulfillments/{id}/status` `{action: MARK_HANDED_OVER|CHANGE_METHOD|RELEASE_HOLD, method?}` | UPDATE_STATUS |
| GET | `/tute-dispatch/queue?search&courseId&tuteId&twoPlus&sort&direction&page&size` | DISPATCH_VIEW |
| GET | `/tute-dispatch/filter-options` | DISPATCH_VIEW |
| POST | `/tute-dispatch/labels` `{fulfillmentIds[≤3000]}` or `{allFiltered:true, filters, expectedLabelCount}` → PDF | DISPATCH_PRINT_LABELS |
| POST | `/tute-dispatch/confirm` `{fulfillmentIds[≤3000]}` | UPDATE_STATUS |
| GET | `/courses/{courseId}/my-tutes` → `[{id, tuteName, status: PENDING\|ON_HOLD\|DISPATCHED, method, assignedAt, dispatchedAt}]` | Student role (TUTE-02) |

Stale selection (item no longer eligible / count mismatch) → 409 `TUTE_SELECTION_STALE`; nothing
printed or dispatched. Cross-tenant ids → 404. Response shapes: see DTOs in
`backend/src/main/java/com/lms/tutemanagement/web/dto`.

## 10. Data (V121)

`tute` and `tute_fulfillment`, both `tenant_id NOT NULL` FK tenant, composite FKs to
`course`/`course_lesson`/`tute` on `(tenant_id, id)`, tenant-leading indexes, partial unique
`(tenant_id, tute_id, student_id) WHERE status <> 'CANCELLED'`, check constraints for price/type,
link/type, status/timestamps. Additive only.

## 11. Tests & docs

Backend: unit (validation, matcher, grouping, address resolution), Testcontainers integration
(CRUD, stock/concurrency, triggers incl. pending/rejected/refund/mixed-course/zero postage,
queue/labels/confirm, permissions grant/revoke, two-tenant + Teacher scope). Frontend: Playwright
for course menu, dialog, queue selection/labels, Staff group. Docs: this spec, staff parity spec,
`klass-gaps-round2.md` R2-D4, QA evidence `docs/qa/tute-management-evidence/`.

## 12. As implemented (deviations from the sections above)

- Response DTOs live in `tutemanagement/service/TuteViews.java`; request DTOs in `web/dto`.
- Linking a session to a non-Paid tute returns 400 `TUTE_SESSION_NOT_ELIGIBLE`.
- Reversals: chargebacks publish no event, so refunds/chargebacks are also detected at label,
  confirm and Sync time (`TuteReversalGuard`, own transaction so the hold persists while the
  request fails with 409 `TUTE_SELECTION_STALE`). Any refund amount, partial or full, holds the
  copy; a payment that already has a refund never assigns. Assign vs refund race is closed with a
  per-payment advisory lock.
- Release hold requires a reason (1..500), recorded in the audit entry.
- Read-only Auditor is **not** configurable for `TUTE_DISPATCH_PRINT_LABELS` (bulk address export).
- Label-export audit records the exported student ids (ids only, capped at 1000 + total).
- Callers without the capability get 403 before the course is resolved (even for unknown ids);
  holders still get 404 for unknown/cross-tenant ids.
- Sync and the payment trigger never re-assign a copy staff unrolled for the same payment.
- Order delivery snapshots hold only name, address line, city, phone; missing name/phone fall back
  to the profile.
- `tute`/`tute_fulfillment` use restrict FKs to `course`, so a course with tutes cannot be
  hard-deleted (409), as with exams; the paid-session link is cleared if its lesson is deleted.
- Queue working set capped at 10,000 pending postal copies (422 `TUTE_WORKING_SET_TOO_LARGE`).
- "No address provided" prints red, upright (no italic face in the font set).
- Teachers get the same pages under `/teacher/...`; index numbers are not links there (no teacher
  student-profile page).

## 13. Open / needs approval

- Paid-tute checkout item (separate price in cart) — change-controlled, not built.
- ~~Student-facing Tute Delivery tab content~~ — built as TUTE-02 (owner-approved 2026-11-09), see §14.

## 14. Student Tute Delivery tab (TUTE-02)

Owner-approved 2026-11-09 (full-stack). The student course page "Tute Delivery" tab lists the
caller's own **non-cancelled** copies of that course, created-order:

- `GET /api/v1/courses/{courseId}/my-tutes`, `hasRole('STUDENT')`, `Cache-Control: no-store`.
  Student = authenticated user, tenant = resolved context (tenant-aware repository); unknown /
  cross-tenant course → 404; staff → 403. No participation check: a student keeps seeing copies
  already assigned to them.
- Fields: tute name, status, method, assigned / dispatched timestamps. `ON_HOLD` = PENDING with a
  reversed source payment not yet released. Never addresses, phones, payment, price or staff ids.
- UI labels: Pending (by post / collect at class), On hold, Dispatched ("Posted on …"), Handed
  over (DISPATCHED + COLLECTION). The query is never served from cache (`staleTime: 0`), so a staff
  dispatch shows on the next tab open or window focus.
- Tests: `StudentTuteIntegrationTest` (own rows, live status, hold, cross-course / cross-tenant /
  staff denial, no delivery fields), `frontend/e2e/student-course-tutes.spec.ts`.
