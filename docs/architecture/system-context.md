# System Context

Status: Living document

## 1. Purpose

This document describes the platform's boundary: who and what interacts with it from
the outside. It is the "level 1" view in a C4-style sense — actors and external
systems only, no internal module detail (see `modular-monolith.md` for that).

## 2. External actors

| Actor | Description | Access surface |
|---|---|---|
| **Student** | Enrolled learner within a tenant. Views courses, materials, exams, attendance, payment history; makes payments/uploads payment slips. | Tenant-branded web app (Next.js), future mobile app. |
| **Teacher** | Instructor assigned to one or more courses within a tenant. Manages course content, live classes, attendance, exam marking. | Tenant-branded web app. |
| **Teacher Assistant** | Support role under a Teacher with a subset of Teacher permissions. | Tenant-branded web app. |
| **Tenant Admin (Institute Owner)** | Owns/administers a single tenant: staff, courses, students, payments, branding, settlement view. | Tenant admin web app. |
| **Tenant Staff sub-roles** | Finance Staff, Course Coordinator, Student Support, Content Manager, Exam Manager, Attendance Operator, Read-only Auditor — scoped subsets of Tenant Admin capability. | Tenant admin web app, permission-scoped. |
| **Platform Admin** | Operates the SaaS platform itself: tenant approval, plan/feature limits, cross-tenant reporting, platform-level support. | Platform admin web app. |
| **Guardian/Parent** *(data subject, not necessarily a distinct login in MVP)* | Referenced via student profile fields; not assumed to have a distinct authenticated portal unless/until a module defines one. | N/A until specified. |

All actors reach the system as browser clients today; a mobile client is a stated
future phase (per `docs/requirements/source-requirements.md`, Phase 3) and should be
treated as an additional client of the same backend API, not a reason to change the
backend's module/tenancy architecture.

## 3. External systems

| System | Owned/called by | Nature of interaction |
|---|---|---|
| **Zoom (or equivalent live-class provider)** | `live-class-management`, via `integration-management` | Create/manage meetings, generate join URLs, sync attendance, manage cloud recordings. Named explicitly in source requirements — kept as-is. |
| **SMS provider** (generic — no vendor selected) | `notification-management`, via `integration-management` | Outbound transactional SMS (payment reminders, class reminders, device-limit alerts). |
| **WhatsApp Business API** | `notification-management`, via `integration-management` | Outbound templated WhatsApp notifications; template approval workflow is provider-governed. |
| **Email / SMTP provider** (generic — no vendor selected) | `notification-management`, via `integration-management` | Transactional and templated email (receipts, reminders, credential resets). |
| **Payment gateway** (generic — no vendor selected) | `payment-management`, via `integration-management` | Payment initiation, confirmation callbacks/webhooks. Selection is an open question — see below. |
| **Object storage** (generic — no vendor selected) | `content-management`, via `integration-management` | Storage of non-video materials (PDFs, images, notes) as signed-URL-accessed objects, not served through the app server. |
| **Secure video hosting / video storage provider** (generic — no vendor selected) | `video-access-management`, `content-management`, via `integration-management` | Storage and secure signed playback of course video content; source-of-truth for video binaries lives outside the application VPS. |
| **YouTube / Vimeo** | `content-management` / `live-class-management` (attach-only) | Attaching externally hosted videos as course material; platform does not host this content. |

Per `.claude/rules/architecture.md`, all of the above are integrated through
`integration-management`'s `api` interfaces — no other domain embeds a provider SDK or
holds that provider's credentials directly. See `integration-architecture.md`.

## 4. System context diagram (text description)

```
                         +-----------------------------------------+
                         |                Actors                    |
                         |  Student, Teacher, Teacher Assistant      |
                         |  Tenant Admin, Tenant Staff roles          |
                         |  Platform Admin                            |
                         +-------------------+-----------------------+
                                             | HTTPS (browser today,
                                             | mobile app future)
                         +-------------------v-----------------------+
                         |                                            |
                         |     Multi-Tenant SaaS LMS Platform          |
                         |   (Next.js frontend + Spring Boot            |
                         |    modular monolith + PostgreSQL/Redis)      |
                         |                                            |
                         +---+-------+-------+-------+-------+-------+
                             |       |       |       |       |
                 +-----------v-+ +---v---+ +-v------+ +v------+ +v-----------+
                 | Zoom (live  | | SMS    | |WhatsApp | | Email  | | Payment    |
                 | classes)    | |provider| |Business | |/SMTP   | | gateway    |
                 |             | |        | |  API    | |provider| | (TBD)      |
                 +-------------+ +--------+ +---------+ +--------+ +------------+
                             |                                        |
                 +-----------v--------------+             +-----------v----------+
                 | Object storage (TBD)       |             | Secure video hosting  |
                 | (materials: PDF/images)    |             | provider (TBD)        |
                 +----------------------------+             +-----------------------+

                 (YouTube / Vimeo attached as external links, not shown as a
                  managed integration boundary -- no credentials/hosting owned
                  by the platform for these.)
```

## 5. Trust boundary notes

- The only trusted source of tenant identity for any request is the authenticated
  session/token resolved by `identity-access-service`, at the edge of the request
  lifecycle. No external system, and no actor-supplied field, is a trusted source of
  `tenant_id` (see `.claude/rules/tenancy.md`).
- All outbound calls to external systems and all inbound webhooks from external
  systems are mediated by `integration-management` (see `integration-architecture.md`)
  — this is the single seam where third-party credentials exist and where
  provider-specific payload shapes are translated into internal domain events/DTOs.

## 6. Open questions

- **Payment gateway**: no specific provider is selected in source material or
  CLAUDE.md; this is intentionally left generic. Selecting a provider, and any
  provider-specific settlement/split-payment capability (source requirements, module
  12, phases 3-4), needs an ADR before implementation, since it interacts with the
  change-controlled payment ledger rules.
- **Object storage / secure video hosting provider**: no vendor named; left generic
  pending an ADR-backed selection.
- **Mobile app client**: named as a future phase; no architecture decision yet on
  native vs. cross-platform, or whether it introduces new auth/session considerations
  (e.g., refresh-token/device-binding differences from the browser client). Flag for a
  dedicated ADR when that phase starts.
