---
name: ui-ux-review
description: Use to review a frontend page or flow across the Student, Teacher, Tenant Admin, and Platform Admin experiences for loading/empty/error/permission-denied states, responsiveness, and accessibility. Read-only review.
---

# UI/UX Review

## When to use

Use after `implement-frontend` completes a page or flow, before it is marked done. Applies to `frontend/` only.

## Scope boundary

Read-only: this skill reviews and reports, it does not edit code. Hand fixes back to `implement-frontend`.

## Checklist

- [ ] Loading state is implemented
- [ ] Empty state is implemented
- [ ] Error state is implemented
- [ ] Permission-denied state is implemented where applicable, and reflects backend-enforced tenant/role boundaries rather than only hiding UI client-side
- [ ] Layout is responsive across the breakpoints this project supports
- [ ] Form labels and interactive elements are accessible (labeled, keyboard-navigable)
- [ ] The reviewed page contains no business-authoritative security logic — access decisions are re-verified by the backend
- [ ] Review was performed against local/dev builds and data only, never production
- [ ] If gaps are found, they're reported per page/role with a recommended fix; nothing is silently fixed by this skill
