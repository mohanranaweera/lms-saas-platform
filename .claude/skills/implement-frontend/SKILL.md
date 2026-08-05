---
name: implement-frontend
description: Use to implement Next.js/React frontend UI, pages, and components in this LMS SaaS platform after a module plan and API contract exist. Must not edit anything under backend/ unless an API mismatch has been reported first.
---

# Implement Frontend

## When to use

Use after `plan-module` (and ideally `review-api-contract`) has produced a plan and API contract for work under `frontend/`: pages, components, forms, and Playwright tests. Use `implement-backend` for anything under `backend/`.

## Scope boundary

- Edit only files under `frontend/`.
- Do not modify anything under `backend/`. Exception: if implementation surfaces an API mismatch (missing field, wrong shape, wrong status code), first report the mismatch explicitly — via `review-api-contract` or directly to the user — before making any change under `backend/`, and only if the task then explicitly authorizes it.
- The frontend must never contain business-authoritative security logic — backend authorization remains mandatory regardless of what the UI hides or shows.

## Checklist

- [ ] TypeScript, React, Tailwind CSS, shadcn/ui, npm only
- [ ] Every page implements: loading state, empty state, error state, permission-denied state where applicable, responsive behavior, accessible form labels
- [ ] No client-supplied `tenant_id` or role claim is trusted for access control — all authorization decisions are re-verified server-side; the frontend only reflects backend-confirmed state
- [ ] Enrollment is never activated from a frontend success page — success pages only reflect a backend-confirmed state, never cause it
- [ ] Playwright tests are added or updated for the flows implemented, including a permission-denied / cross-tenant-blocked case where applicable
- [ ] No production API or production data is used during development or testing
- [ ] If this change alters observable behavior (new page, changed flow, changed contract usage), documentation updates are flagged for `update-documentation`
- [ ] `backend/` was not touched, unless a reported API mismatch was explicitly authorized for a fix
