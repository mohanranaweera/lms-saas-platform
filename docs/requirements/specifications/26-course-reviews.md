# Course Reviews

**Domain:** `course-management` (moderation-workflow ownership unratified, see below) · **Portal(s):** Student, Teacher, Tenant Admin, Public

## 1. Business purpose

Student-submitted, moderated course reviews/ratings for social proof on the public storefront.

Source: `docs/requirements/source-requirements.md` Module 19.

## 2. Actors

- **Student** — submits, verified-enrollment only
- **Course Coordinator / Tenant Admin** — moderates (`V/A` for both)
- **Teacher** — may respond to a review (recommended addition)
- **Public visitors** — view approved reviews

## 3. Preconditions

`course-management` course/enrollment data must exist to verify "verified-enrollment-only"; a
course-level review toggle (owned by `course-management`) must exist.

## 4. Normal flow

1. Enrolled, verified student opens `Reviews > My Reviews`, submits star rating + written feedback for an enrolled course.
2. Review enters a moderation queue.
3. Moderator approves/rejects.
4. Only approved reviews are publicly visible on the storefront, subject to the course-level toggle.

## 5. Alternative flows

- Non-enrolled (or unverified) student attempts to submit: rejected server-side, not merely hidden in UI.
- Review flagged for abuse (recommended addition): routed to moderation, not auto-hidden without review.
- Course has reviews toggled off: no submission entry point, no public display even if reviews exist.
- Course cloning explicitly never carries over review history.

## 6. Authorization rules

Per `docs/requirements/user-roles-and-permissions.md` §2, "Reviews moderation" row: Tenant Admin
`V/A`; Course Coordinator `V/A`; all other staff sub-roles `—`. Per §3, Teacher may respond to
reviews; Teacher Assistant explicitly may **not** (PROVISIONAL, not confirmed).

## 7. Tenant rules

Reviews are tenant-owned (via the course they belong to); only verified-enrollment students may
submit. The public Reviews page must only ever render tenant-scoped, approved, and
toggle-enabled reviews — never an unfiltered/unmoderated fetch.

## 8. Acceptance criteria

- [ ] Non-enrolled student's review-submission attempt is rejected server-side (401/403), independent of UI state.
- [ ] Only `APPROVED`-status reviews are publicly visible; `SUBMITTED`/pending reviews never leak to the public storefront.
- [ ] Course-level review toggle, when off, hides both submission and display, tenant-verified.
- [ ] Cross-tenant test: a review authored under Tenant A's course is never visible on a superficially similar Tenant B course.
- [ ] Cloning a course does not carry over prior reviews.
- [ ] Moderation Queue distinguishes "no pending reviews" vs. "no reviews match filter."

## 9. Audit requirements

**Open Decision** — not on `.claude/rules/security.md`'s mandatory list. Whether
approve/reject-review moderation actions require an audit entry is unresolved (review moderation
is a lower-blast-radius action than the listed items, but not explicitly excluded or included).

## 10. MVP or later-phase classification

**Phase 2.** FR-CM-6; `module-catalog.md` line 60; `source-requirements.md` line 653.

## Open question flagged in source docs

`module-catalog.md` §Open Questions #1: whether `course-management` or a not-yet-named domain
(possibly `support-management` for moderation queues) owns the review submission/moderation
*workflow*, versus just the course-level toggle — unresolved, flagged for architecture-owner
confirmation before backend scaffolding.

## UI-state and portal notes

- **Portal placement**: Public `Storefront > Reviews`; Student `Reviews > My Reviews`; Teacher `Courses > Course Reviews`; Tenant Admin `Reviews > Moderation Queue`.
- Star-rating input needs an accessible equivalent to a purely visual star-click widget (e.g. radio-group semantics with `fieldset`/`legend`) — no accessibility spec currently exists for this control.

## Open decisions

- Ownership of the review submission/moderation workflow (`course-management` vs. `support-management`).
- Whether review moderation actions require an audit entry.
- No documented UI flow for review abuse reporting or featured testimonials (recommended additions).
