# Portals Overview

This document maps the platform's four portals to the features they surface. It is a navigation
aid over the per-feature specifications in `docs/requirements/specifications/` — for full
business purpose, flows, authorization, tenant rules, acceptance criteria, and audit
requirements, follow the links below. Screen-level detail (route names, component composition)
lives in `docs/ui-ux/screen-map.md`; role-scope and visual-unambiguity rules live in
`.claude/rules/ui-ux.md` §1.

Every page rendering tenant- or role-scoped data must show that scope in a persistent UI element
(header, breadcrumb, or banner) — this applies across all four portals below, not just where
called out per feature.

## Student Portal

Scope: the student's own tenant-scoped records only. No student-selector or ID-based navigation
to another student's data exists anywhere in this portal.

| Feature | Spec |
|---|---|
| Student self-registration & profile | [03-student-management.md](specifications/03-student-management.md) |
| Course browsing, enrollment | [05-course-management.md](specifications/05-course-management.md), [09-enrollments.md](specifications/09-enrollments.md) |
| Lesson/material viewing (PDF/notes/video) | [06-lessons-and-materials.md](specifications/06-lessons-and-materials.md), [20-secure-video.md](specifications/20-secure-video.md), [17-session-view-limits.md](specifications/17-session-view-limits.md) |
| Payments, slip upload, payment history | [07-orders-and-payments.md](specifications/07-orders-and-payments.md), [08-manual-payment-slips.md](specifications/08-manual-payment-slips.md) |
| Access expiry & reactivation | [18-smart-expiry.md](specifications/18-smart-expiry.md) |
| Attendance history | [10-attendance.md](specifications/10-attendance.md) |
| Exam taking, results | [11-exams.md](specifications/11-exams.md) |
| Notifications | [12-notifications.md](specifications/12-notifications.md) |
| Device management | [16-device-authentication.md](specifications/16-device-authentication.md) |
| Live classes (join) | [19-zoom-live-classes.md](specifications/19-zoom-live-classes.md) |
| Course reviews (submit) | [26-course-reviews.md](specifications/26-course-reviews.md) |

## Teacher Portal

Scope: assigned courses only, backend-filtered — never a full dataset filtered client-side.
Teacher Assistant's permission boundary throughout this portal is **PROVISIONAL, unratified**
(see `docs/requirements/open-decisions.md`).

| Feature | Spec |
|---|---|
| Course authoring (Course Builder) | [05-course-management.md](specifications/05-course-management.md) |
| Lessons & materials management | [06-lessons-and-materials.md](specifications/06-lessons-and-materials.md), [27-youtube-vimeo-integrations.md](specifications/27-youtube-vimeo-integrations.md) |
| Roster (read-only, course-scoped) | [03-student-management.md](specifications/03-student-management.md) |
| Attendance marking | [10-attendance.md](specifications/10-attendance.md) |
| Question bank, exam scheduling, marking, results | [11-exams.md](specifications/11-exams.md) |
| Live class scheduling, recordings | [19-zoom-live-classes.md](specifications/19-zoom-live-classes.md) |
| Course reviews (respond) | [26-course-reviews.md](specifications/26-course-reviews.md) |
| Commission/payout profile (Phase 2) | [04-teacher-management.md](specifications/04-teacher-management.md) |

## Tenant Admin Portal

Scope: exactly one tenant (the admin's own). No tenant selector/switcher exists anywhere in this
portal — its absence is itself part of how scope is communicated.

| Feature | Spec |
|---|---|
| Staff management, roles | [02-staff-management.md](specifications/02-staff-management.md) |
| Student management, bulk import | [03-student-management.md](specifications/03-student-management.md) |
| Teacher management, approval | [04-teacher-management.md](specifications/04-teacher-management.md) |
| Course oversight/approval | [05-course-management.md](specifications/05-course-management.md) |
| Materials oversight | [06-lessons-and-materials.md](specifications/06-lessons-and-materials.md) |
| Payment dashboard, refunds, reports | [07-orders-and-payments.md](specifications/07-orders-and-payments.md) |
| Manual slip review queue | [08-manual-payment-slips.md](specifications/08-manual-payment-slips.md), [25-duplicate-payment-slip-detection.md](specifications/25-duplicate-payment-slip-detection.md) |
| Access & expiry / reactivation approvals | [18-smart-expiry.md](specifications/18-smart-expiry.md) |
| Attendance reports | [10-attendance.md](specifications/10-attendance.md) |
| Exam oversight, model paper library | [11-exams.md](specifications/11-exams.md) |
| Communications (templates, bulk messaging, delivery logs) | [12-notifications.md](specifications/12-notifications.md), [21-sms.md](specifications/21-sms.md), [22-whatsapp.md](specifications/22-whatsapp.md) |
| Audit log viewer | [13-audit-logs.md](specifications/13-audit-logs.md) |
| Branding settings | [14-white-labelling.md](specifications/14-white-labelling.md), [15-custom-domains.md](specifications/15-custom-domains.md) |
| Device policy, reset & history | [16-device-authentication.md](specifications/16-device-authentication.md) |
| Zoom accounts, live class oversight | [19-zoom-live-classes.md](specifications/19-zoom-live-classes.md) |
| Finance & expenses, tutor payouts | [23-finance-and-expenses.md](specifications/23-finance-and-expenses.md) |
| Reviews moderation queue | [26-course-reviews.md](specifications/26-course-reviews.md) |
| Integration settings | [19-zoom-live-classes.md](specifications/19-zoom-live-classes.md), [21-sms.md](specifications/21-sms.md), [22-whatsapp.md](specifications/22-whatsapp.md), [27-youtube-vimeo-integrations.md](specifications/27-youtube-vimeo-integrations.md) |

## Platform Admin Portal

Scope: cross-tenant, platform-level operations only — never a shortcut for tenant-scoped access.
Any cross-tenant list/table must show the tenant name/identifier on every row; drilling into a
single tenant's data requires a persistent, non-dismissible tenant-context banner for the
duration.

| Feature | Spec |
|---|---|
| Tenant list, approval queue | [01-tenant-onboarding.md](specifications/01-tenant-onboarding.md) |
| Cross-tenant payment dashboard | [07-orders-and-payments.md](specifications/07-orders-and-payments.md) |
| Settlement runs | [24-settlements.md](specifications/24-settlements.md) |
| Platform finance reports | [23-finance-and-expenses.md](specifications/23-finance-and-expenses.md) |
| Platform audit log, tenant drill-down | [13-audit-logs.md](specifications/13-audit-logs.md) |
| Platform default integrations | [19-zoom-live-classes.md](specifications/19-zoom-live-classes.md), [21-sms.md](specifications/21-sms.md), [22-whatsapp.md](specifications/22-whatsapp.md) |

## Cross-cutting / not yet owned by a portal

The following are referenced in requirements but their owning domain is explicitly unratified
per `docs/requirements/module-catalog.md`'s "Cross-Cutting / Unowned Items" — they do not yet
have a confirmed portal home:

- **Public Storefront** (Module C) — implied Public-portal surface for course listing/detail, teacher public profiles, reviews; no confirmed owning backend domain.
- **Feature Flag & Plan Limit Engine** (Module D) — gates plan-based entitlements referenced throughout this catalog (e.g. [14-white-labelling.md](specifications/14-white-labelling.md), [15-custom-domains.md](specifications/15-custom-domains.md), [02-staff-management.md](specifications/02-staff-management.md)'s staff-count limit); no confirmed owning domain.
- **AI Assistant** (Module F) — not scoped in any reviewed document beyond a name.

See `docs/requirements/open-decisions.md` for the full tracking of this and every other
unresolved item surfaced while building this catalog.
