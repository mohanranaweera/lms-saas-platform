---
name: update-documentation
description: Use after any change to behavior, API contracts, architecture, or data model to update the relevant docs under docs/. Required as the last documentation step of every module before commit.
---

# Update Documentation

## When to use

Use whenever a change alters observable behavior: a new/changed API contract, a new/changed data model, a new user flow, or an architecture decision. This is required by `CLAUDE.md`'s development workflow step "Update documentation" before a module is committed.

## Scope boundary

Edits are confined to `docs/` (product, functional, architecture, API, UI/UX, requirements, ADRs). This skill does not change application code in `backend/` or `frontend/`.

## Checklist

- [ ] Identify every doc under `docs/` that describes the behavior being changed (requirements, architecture, API reference, UI/UX)
- [ ] Update each identified doc to match the new behavior — no doc is left describing the old behavior as current
- [ ] If the change required an approved exception to a change-controlled area (multi-tenancy strategy, authentication architecture, payment ledger rules, enrollment activation rules, production deployment strategy, database migration history, approved API contracts), record that decision under `docs/adr`
- [ ] API contract changes are reflected under `docs/api`, matching what `review-api-contract` recorded
- [ ] No documentation references production credentials, real tenant data, or real student/financial records
- [ ] If no doc actually needs updating for this change, that is stated explicitly with a one-line reason — not left unaddressed

## Detailed documentation rules

These rules extend the checklist above and govern what "update documentation" actually
requires. They apply whenever a change alters observable behavior — a new/changed API
contract, a new/changed data model, a new user flow, or an architecture decision.

### Where things go under `docs/`

- `docs/requirements` — source/product requirements as given; treat as historical
  input, not a living spec. Do not edit `source-requirements.md` to reflect
  implementation decisions — capture decisions in `docs/architecture` or `docs/adr`
  instead.
- `docs/architecture` — the current, living description of how the system is actually
  built: module boundaries, data flow, the confirmed backend domain list, and how it
  maps to what actually exists in `backend/` and `frontend/`. This is what a session
  should trust over the original requirements doc once implementation has begun.
- `docs/api` — the source of truth for API contracts, one file (or one section) per
  domain/endpoint group, kept in sync with what `review-api-contract` records for each
  contract. A contract change is not done until its `docs/api` entry matches.
- `docs/adr` — one file per architecture decision record, required whenever a change
  touches a change-controlled area (multi-tenancy strategy, authentication
  architecture, payment ledger rules, enrollment activation rules, production
  deployment strategy, database migration history, approved API contracts) or deviates
  from a rule in `.claude/rules/architecture.md`'s "When an ADR is required" section.
- `docs/ui-ux` — role-specific UX conventions and any approved deviations from the
  baseline state/accessibility requirements, kept in sync with `.claude/rules/ui-ux.md`.

### When documentation must change

- A new or changed REST endpoint, request/response shape, or status code: update
  `docs/api`.
- A new or changed table, column, or index that other modules or the frontend rely on:
  update `docs/architecture`'s data-model section.
- A new user-facing flow or a materially changed one: update the relevant
  `docs/architecture` or `docs/ui-ux` section so a fresh session can find it without
  reading the diff history.
- An approved exception to a change-controlled area: record it as a new file under
  `docs/adr`, referenced from the PR — do not fold the justification into a commit
  message only.
- If a change genuinely requires no documentation update, say so explicitly with a
  one-line reason (e.g. "internal refactor, no contract/behavior change") rather than
  skipping the step silently.

### Constraints

- No documentation file may reference production credentials, real tenant data, or
  real student/financial records — examples and samples must be synthetic, consistent
  with the project's testing conventions.
- Documentation updates are part of the same logical change as the code that motivated
  them — do not defer them to a separate, later commit.
