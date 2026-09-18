# RBAC Impact Analysis (Wave 0)

Status: analysis only. No role, permission, or authorization code was modified to produce this
document.

## 1. Role model: target vs. current — MATCHES

Target role list (`docs/requirements/user-roles-and-permissions.md` §1): Platform Admin, Tenant
Admin/Institute Owner, Finance Staff, Course Coordinator, Student Support, Content Manager, Exam
Manager, Attendance Operator, Read-only Auditor, Teacher, Teacher Assistant, Student,
Anonymous/Public.

Current `Role` enum (`identityaccessservice/domain/Role.java`): `TENANT_ADMIN, FINANCE_STAFF,
COURSE_COORDINATOR, STUDENT_SUPPORT, CONTENT_MANAGER, EXAM_MANAGER, ATTENDANCE_OPERATOR,
READ_ONLY_AUDITOR, TEACHER, TEACHER_ASSISTANT, STUDENT` (11 values) plus `PlatformAdminUser` as a
structurally separate table/implicit role, deliberately excluded from the enum so it can never
land in `tenant_user.role` via the same FK path.

**This is an exact match**, including the deliberate platform-admin exclusion mechanism, which
is itself the correct implementation of "Platform Admin is not represented as a tenant-scoped
row."

## 2. Domain-area / action model: target vs. current — MATCHES

Target permission matrix rows (`user-roles-and-permissions.md` §2): Students, Teachers, Staff &
roles, Courses, Materials, Payments/slips, Finance & expenses, Attendance, Exams, Devices,
Access & expiry/reactivation, Reviews moderation, Audit log, Branding & settings, Support
tickets — 15 rows.

Current `DomainArea` enum: `STUDENTS, TEACHERS, STAFF_AND_ROLES, COURSES, MATERIALS,
PAYMENTS_SLIPS, FINANCE_EXPENSES, ATTENDANCE, EXAMS, DEVICES, ACCESS_EXPIRY,
REVIEWS_MODERATION, AUDIT_LOG, BRANDING_SETTINGS, SUPPORT_TICKETS` — 15 values, **exact
1:1 match** including domain areas whose actual feature does not exist yet.

Current `PermissionAction` enum: `VIEW, CREATE_EDIT, DELETE, APPROVE` — exact match to the
matrix's V / C-E / D / A columns.

**Implication**: the RBAC skeleton was deliberately built ahead of the features it will
eventually gate. This is a genuine strength (no schema/enum churn needed when Waves 1, 7, 10,
11, 16, 19, 26 land their features) but it also means **`hasPermission` returning a grant today
for `DEVICES`, `BRANDING_SETTINGS`, `SUPPORT_TICKETS`, `FINANCE_EXPENSES`, or
`REVIEWS_MODERATION` is currently meaningless in practice** — no controller checks those areas
yet because no controller exists for those domains. This is not a bug; it is simply unused
capacity, and should not be mistaken for those features being "half-built."

## 3. Enforcement pattern — MATCHES, with two named category-grant caveats

Every inspected controller gates on `PermissionCheckService.hasPermission(DomainArea,
PermissionAction)` rather than ad hoc role-string checks. `DomainArea.java`'s own Javadoc
explicitly documents two non-bypassable caveats that any Wave 7/23/24 (Finance & Expenses,
Settlements) or Wave 6/18 (Access & Expiry) work must preserve:

- A `PAYMENTS_SLIPS`/`FINANCE_EXPENSES` grant is a **category grant only** — it never by itself
  authorizes mutating a terminal-state (`CONFIRMED`/`REJECTED`/`REFUNDED`) payment, an
  `APPROVED` manual slip, or a ledger-row delete. `.claude/rules/payments.md` §1–§4 governs those
  mutations independently regardless of what the permission check returns.
- An `ACCESS_EXPIRY` grant authorizes exactly the `reactivation_request` status transition — it
  never itself authorizes a direct `enrollment` write, which always requires independent
  `PaymentStatusApi`/`SlipStatusApi` re-verification inside `EnrollmentActivationApi`.

Any new endpoint built in later waves that touches these same tables must replicate this
"permission grant is necessary but not sufficient" pattern rather than treating a `true` result
from `hasPermission` as authorization enough on its own — this is a documented rule
(`.claude/rules/payments.md` §8), not a Wave 0 discovery, but it is restated here because it
directly governs how new RBAC-gated endpoints for Finance & Expenses/Settlements/Access-Expiry
expansion must be written in Waves 6/7/24.

## 4. Permission-matrix gaps: Klass domains with no defined role authority at all

These are genuine authorization gaps, independent of whether the feature is built, because they
mean nobody can currently answer "who is allowed to do this":

| Capability | Matrix status | Parity ID |
|---|---|---|
| Notification templates, bulk/segment messaging, delivery logs, preference-center config | **No row exists** in the permission matrix at all | PAR-12-04 |
| SMS/WhatsApp campaign triggering / template approval | **No row exists** | PAR-21-02, PAR-22-02 |
| Zoom/live-class scheduling and recording management authority | **No row exists** (closest analogs — Course Coordinator on Courses, Teacher on assigned courses — don't explicitly cover it) | PAR-19-03 |
| Settlement-run trigger authority (Platform Admin only, vs. tenant-level Finance Staff/Institute Owner also) | **Explicitly unresolved**, not just undocumented | PAR-24-02 |
| Course-approval second-approver requirement for high-value/published courses | **Explicitly unresolved** | (cross-ref PAR-05-03) |
| Teacher approval as a distinct `A`-level permission vs. folded into Course Coordinator's `C/E` | **Explicitly unresolved** — no `A` column exists for Teachers unlike Courses/Payments/Exams | PAR-04-04 |

**Recommendation**: resolve these as explicit product decisions (not engineering defaults)
before scaffolding the corresponding `DomainArea` values and controllers in Waves 11, 19, and
24 — inventing a permission row silently would violate root `CLAUDE.md`'s instruction not to
silently decide unresolved business rules.

## 5. Teacher vs. Teacher Assistant — PROVISIONAL, not yet a hard gate

`user-roles-and-permissions.md` §3 explicitly marks the Teacher Assistant capability split as
"a reasonable default, not a confirmed decision — do not build a hard permission gate against
it without sign-off." This Wave 0 pass did not find evidence either way of whether
`TEACHER_ASSISTANT` currently has any narrower enforcement than `TEACHER` in code (this would
require reading every course/material/exam service's authorization branch, which was judged
out of scope for Wave 0's breadth-first pass). **Flagged as `NEEDS_VERIFICATION`**: if a hard
gate already exists against the PROVISIONAL matrix, that itself needs product sign-off before
Wave 1 proceeds to build more Teacher Assistant-gated screens on top of it (e.g. Live Classes
scheduling, PAR-19-03); if no gate exists yet, none should be added without that sign-off
either.

## 6. Read-only Auditor server-side enforcement — spot-check

The spec's own acceptance criteria repeatedly assert "no mutating endpoint may succeed for
Read-only Auditor regardless of stale client UI state" as an already-shipped guarantee for every
MVP-scope domain (students, courses, payments, slips, attendance, exams, audit log). This Wave 0
pass relied on that per-domain assertion rather than independently re-running a mutation attempt
against every mutating endpoint as Read-only Auditor — that level of verification belongs to
`security-reviewer`/`qa-regression` as a standing regression check, not a one-time Wave 0
narrative claim. No evidence contradicting the assertion was found during this pass.

## 7. Impersonation — not yet built, correctly not built

`user-roles-and-permissions.md` §5 describes impersonation as an "if/when" future capability
with specific requirements (distinct backend-issued session, dual-identity audit trail on every
action, non-dismissible UI indicator). No impersonation code was found anywhere in the backend
or frontend inventories, and none should exist yet — this is a **MATCHES** finding in the sense
that the absence itself is correct (master instruction §33 explicitly says "any future
impersonation capability must be explicit, backend-issued and audited," implying it is not
expected at MVP). Recorded here so a future wave doesn't have to re-derive this from scratch.
