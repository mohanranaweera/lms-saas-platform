# ADR-014: Audit Log Viewer Access Scope (Interim Allowlist)

## Status

**Accepted (2026-09-12).**

An earlier version of this section recorded acceptance "via a structured decision
request presented during a security-reviewer pass" — i.e., the decision was resolved
inside an automated agent workflow, with the ADR itself asserting product-owner
approval without any independently verifiable human sign-off attached. A subsequent
MVP-019 completion review (five parallel specialist passes — `solution-architect`,
`security-reviewer`, `database-architect`, `ui-ux-reviewer`, `qa-test-engineer`) had the
`solution-architect` and `security-reviewer` passes independently flag this as a
change-control provenance gap per root `CLAUDE.md` and `.claude/rules/git-workflow.md`
("a PR touching a change-controlled area with no linked approval should not be
merged") — an agent's own prior output does not constitute the product owner's
consent.

That gap is now closed: the product owner (Mohan Ranaweera, repository owner) reviewed
this decision directly in an interactive session on 2026-09-12 and explicitly approved
**Option (B)** below as the real decision, in direct response to the provenance concern
the completion review surfaced. This is the first point at which this ADR's decision
carries a verifiable human approval, superseding the earlier self-certified framing.

The implementation (`AuditLogQueryService.VIEWER_ALLOWED_ROLES`,
`frontend/src/lib/auth/permissions.ts`'s `canViewAuditLog`) already matches option (B)
as shipped — this status update changes only the recorded provenance of the decision,
not the decision's substance or any code.

## Context

MVP-019's plan (`docs/plans/MVP-019 Audit Logs.md` §21 decision 1) identified that no
enforcement mechanism exists anywhere in the codebase for "own-area" scoping of staff
sub-roles, and that the generic `DomainArea.AUDIT_LOG`/`VIEW` permission grant is
currently held by every staff sub-role. Two options were named and neither was
selected by the plan itself:

- **(A)** Ship the viewer using the existing coarse `AUDIT_LOG`/`VIEW` grant as-is —
  every staff sub-role holding that grant sees the full tenant-scoped log, with no area
  restriction. Matches the literal current permission-matrix state; carries a
  least-privilege over-exposure risk (e.g. an Attendance Operator could read
  refund/material-deletion history unrelated to their area).
- **(B)** Restrict the viewer, for this MVP, to Institute Owner + Read-only Auditor
  only, via an explicit allowlist check independent of the generic `hasPermission`
  grant — deferring all other staff sub-role access until "own area" is actually
  defined. Narrower than the literal spec's role table, but avoids the over-exposure
  risk.

This falls under the class of decisions root `CLAUDE.md` and `.claude/rules/payments.md`
§8 treat as requiring explicit sign-off rather than an implicit code-level choice: a
coarse domain-area permission grant is not, by itself, sufficient authorization for a
restricted-access resource, and who can see which tenant's privileged-action history is
authorization-policy territory adjacent to this project's change-controlled areas.

## Decision

The product owner selected **Option (B)**: restrict the Audit Log Viewer, for this MVP,
to **Institute Owner (`TENANT_ADMIN`) and Read-only Auditor (`READ_ONLY_AUDITOR`)
only**, independent of the generic `AUDIT_LOG`/`VIEW` grant.

- **Backend**: `AuditLogQueryService.VIEWER_ALLOWED_ROLES` (`Set.of("TENANT_ADMIN",
  "READ_ONLY_AUDITOR")`) is checked independently of, and in addition to, the coarse
  `PermissionCheckService.hasPermission(DomainArea.AUDIT_LOG, VIEW)` grant. A caller
  holding the coarse grant but not on this allowlist (e.g. Finance Staff, Content
  Manager) still receives a `403`, proven by
  `AuditLogViewerIntegrationTest#financeStaffHoldsTheCoarseGrantButIsDeniedByTheNarrowerAllowlist`
  and the equivalent Content Manager case.
- **Frontend**: `frontend/src/lib/auth/permissions.ts`'s `canViewAuditLog` mirrors the
  identical two-role allowlist, used only to gate the nav-link's visibility (UX
  convenience) — the actual authorization boundary remains the backend's `403`, per
  `.claude/rules/frontend.md`'s "permission-denied state must be driven only by a
  server-verified signal" rule. The page issues its data request unconditionally and
  renders `PermissionDeniedState` only from a real backend `403`.
- Broader staff sub-role access (the "own area" question) remains deferred, not decided
  here — this ADR authorizes only the narrower interim allowlist, not a resolution of
  what "own area" means for other sub-roles.

## Consequences

**Positive**

- The over-exposure risk security review originally flagged (any staff sub-role reading
  the full tenant-scoped privileged-action history, including refund and
  material-deletion events unrelated to their area) is closed for this MVP without
  waiting on the unrelated, larger "own area" design question.
- Authorization is enforced at two independent layers server-side (coarse grant +
  explicit allowlist), consistent with `.claude/rules/payments.md` §8's generalized
  principle that a coarse domain-area grant is not sufficient authorization on its own
  for a sensitive mutation or, by the same logic applied here, a sensitive read.
- The frontend's role gate and the backend's allowlist are independently verified to
  match (`ui-ux-reviewer` and `security-reviewer` passes on the completed
  implementation), so there is no drift between the UX-only gate and the actual
  enforcement point.

**Negative / trade-offs accepted**

- Narrower than the literal spec's staff-sub-role viewer-access table — any staff
  sub-role other than Institute Owner/Read-only Auditor cannot use this viewer at all
  until "own area" is defined and a follow-up decision expands access.
- This is an interim allowlist, not a permanent authorization model — a future change
  to broaden or redefine viewer access is new scope requiring its own review, not an
  assumed extension of this ADR.

## Alternatives considered

- **Option (A)** — ship with the existing coarse grant as-is. Rejected due to the
  least-privilege over-exposure risk identified by security review (every staff
  sub-role currently holds the coarse grant, with no area restriction available to
  narrow it).

## Required follow-up if accepted

- If "own area" scoping for staff sub-roles is later defined, that is new scope
  requiring its own review against this ADR's interim allowlist, not an assumed
  extension of it.
- If a future module needs to broaden `VIEWER_ALLOWED_ROLES` beyond Institute
  Owner/Read-only Auditor, update this ADR or supersede it with a new one, rather than
  changing the allowlist as an incidental code change.

## Related

- `docs/plans/MVP-019 Audit Logs.md` §21 decision 1
- `docs/requirements/open-decisions.md` (audit-log-management central scoping entry)
- `.claude/rules/payments.md` §8
- `.claude/rules/frontend.md`
- `AuditLogQueryService.java`, `AuditLogViewerIntegrationTest.java`
- `frontend/src/lib/auth/permissions.ts`, `frontend/src/components/layout/nav/tenant-admin-nav.tsx`
