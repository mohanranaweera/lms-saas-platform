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
