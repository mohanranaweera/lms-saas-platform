# Klass LMS Product Parity Remediation Programme

## Mission

Transform the existing multi-tenant LMS SaaS platform into a functionally equivalent product to the approved Klass reference experience for Tenant Admin, Teacher and Student users, while preserving the stronger multi-tenant SaaS architecture already implemented.

This is NOT a greenfield rewrite.

This is NOT permission to replace existing architecture.

This is a controlled product-parity remediation of the existing system.

The existing repository and its 21 MVP modules are the implementation baseline.

---

# 1. Product hierarchy

The target hierarchy is:

Platform
→ Tenant / Institute
→ Tenant Admin / Staff
→ Teacher
→ Student

The existing Platform Admin portal is a SaaS operator portal and MUST remain separate.

The Klass Admin experience maps to Tenant Admin, NOT Platform Admin.

Do not redesign Platform Admin to mimic Klass Institute Admin.

---

# 2. Source-of-truth precedence

When requirements conflict, use this precedence:

1. Approved Klass reference recordings and parity specification
2. Product requirements under docs/requirements
3. Approved architecture and ADRs
4. Existing GitHub MVP issue requirements
5. Current implementation

However, security controls, tenant isolation, financial integrity and append-only audit/payment requirements MUST NOT be weakened merely to reproduce reference-product behavior.

If a Klass workflow appears to conflict with one of those controls, preserve the control and reproduce the user-visible behavior safely.

---

# 3. Mandatory discovery before coding

DO NOT immediately modify code.

First inspect:

- CLAUDE.md
- backend/CLAUDE.md
- frontend/CLAUDE.md
- infrastructure/CLAUDE.md
- .claude/rules/**
- .claude/skills/**
- docs/requirements/**
- docs/architecture/**
- docs/adr/**
- docs/api/**
- docs/ui-ux/**
- all Flyway migrations
- backend package structure
- frontend route structure
- existing tests
- GitHub issues MVP-001 through MVP-021

Then inspect the actual implementation of every existing domain.

Do not assume an issue being closed means the functionality is correct.

Verify the code.

---

# 4. Create a parity inventory

Before implementing anything create:

docs/parity/klass-parity-matrix.md

For every target capability record:

- parity ID
- role
- target module
- reference behavior
- current implementation
- classification
- frontend impact
- backend impact
- database impact
- security impact
- tenant-isolation impact
- migration impact
- required tests
- status

Allowed classifications:

MATCHES
PARTIAL
WRONG_BEHAVIOR
WRONG_UX_IA
MISSING_SCREEN
MISSING_FIELD_ACTION
MISSING_CONFIGURATION
MISSING_WORKFLOW
ROLE_MISMATCH
ARCHITECTURAL_CONFLICT
OBSOLETE_CURRENT_FEATURE
PLATFORM_ONLY_FEATURE

Do not begin implementation until this inventory exists.

---

# 5. Preserve architectural controls

Do not replace or weaken:

- modular monolith architecture
- tenant resolution
- TenantAwareRepository strategy
- tenant_id requirements
- Spring Security
- server-side RBAC
- PostgreSQL
- Redis
- Flyway
- payment ledger
- webhook-confirmed payment rules
- payment-slip validation and duplicate detection
- enrollment activation integrity
- audit-log append-only rules
- Testcontainers
- Playwright
- Docker/Nginx deployment model

No frontend-provided tenant_id may become authoritative.

No client-side permission check may replace backend authorization.

---

# 6. Tenant Admin information architecture

Restructure Tenant Admin into an institute operations portal.

Required high-level areas:

Dashboard

Academic:
- Courses / Classes
- Teachers
- Students
- Learning Materials
- Attendance
- Exams

Finance:
- Student Payments
- Payment Slips
- Transactions
- Income
- Expenses
- Financial Reports

Communication:
- Notifications
- Email
- SMS / WhatsApp

Administration:
- Staff
- Roles & Permissions
- Audit Logs

Institute Configuration:
- General
- Branding
- Academic
- Student
- Teacher
- Course
- Payment
- Finance
- Attendance
- Exam
- Content / Video
- Notifications
- Integrations
- Security / Devices
- Domain

Navigation visibility must follow backend permissions.

---

# 7. Tenant configuration framework

Create a coherent tenant configuration subsystem.

Do NOT implement one unvalidated arbitrary JSON settings blob.

Support typed configuration domains for:

GENERAL
BRANDING
ACADEMIC
STUDENT
TEACHER
COURSE
PAYMENT
FINANCE
ATTENDANCE
EXAM
CONTENT
VIDEO
NOTIFICATION
SECURITY
DEVICE
DOMAIN
INTEGRATION

Every setting must have:

- tenant ownership
- validation
- default
- permission requirement
- API contract
- audit decision
- frontend control
- tests

Sensitive integration credentials must never be returned to the frontend after persistence.

---

# 8. Course/Class expansion

Extend the existing Course domain.

Do not create a duplicate Class domain unless architectural analysis proves that Course and Class are genuinely different aggregates.

Course operational detail must support:

- Overview
- Students
- Teachers
- Fees / Billing
- Schedule
- Live Sessions
- Recordings
- Materials
- Attendance
- Exams
- Access
- Settings
- Analytics

Support pricing models:

FREE
ONE_TIME
MONTHLY
SESSION
CUSTOM

Introduce billing periods where recurring/monthly charging requires them.

Do not reduce payment integrity to a mutable course.price field.

---

# 9. Student management expansion

Extend existing student functionality.

Student Detail must compose:

- Profile
- Enrollments
- Payments
- Attendance
- Exams/results
- Materials/access
- Devices
- Activity
- Notifications

Actions must include, subject to permission:

- edit
- activate/deactivate
- enroll
- revoke enrollment
- password reset
- device reset
- access extension

Cross-domain data must be obtained through owning module APIs, not repository joins across domain boundaries.

---

# 10. Registration configuration

Student registration must be tenant configurable.

Support policies for:

- public registration
- approval requirement
- OTP requirement
- required fields
- guardian information
- school
- grade
- stream
- mobile
- other approved profile fields

Do not hard-code tenant-specific registration forms.

---

# 11. Teacher management expansion

Teacher Detail must include:

- Profile
- Assigned Courses
- Students / Rosters
- Sessions
- Materials
- Attendance
- Exams
- Financial summary where permitted
- Activity

Lifecycle:

PENDING
APPROVED
SUSPENDED
REJECTED

Teacher assignment/reassignment remains server-authorized.

---

# 12. Class Session domain

Introduce a real class/session scheduling model.

Do not continue using Lesson as a substitute for a scheduled class.

A ClassSession belongs to:

tenant
course
teacher(s)

and supports:

- title
- schedule
- start/end
- session status
- meeting provider
- meeting reference
- recording relationship
- attendance relationship

Lifecycle:

SCHEDULED
LIVE
COMPLETED
CANCELLED

Attendance must ultimately reference the scheduled session.

---

# 13. Zoom and meeting integration

Implement meeting providers behind an integration interface.

Example:

MeetingProvider
ZoomMeetingProvider

Course/session domain code must not contain vendor-specific HTTP/API implementation.

Required workflow:

create/update class session
→ create/update provider meeting
→ persist provider reference
→ authorize student join
→ session
→ attach attendance/recording information

Tenant-specific integration credentials must be supported by the configuration architecture.

---

# 14. Learning materials

Extend existing materials safely.

Support approved material types such as:

PDF
IMAGE
DOCUMENT
LINK
NOTE
VIDEO
RECORDING
OTHER

Support:

- publish state
- visibility
- availability start
- expiry
- download permission
- course/module/lesson association

Continue using private object storage and server-authorized access.

Never expose predictable public object URLs for protected material.

---

# 15. Secure video subsystem

Introduce a dedicated video capability instead of treating video as an ordinary file.

Model concepts:

VideoAsset
VideoPlaybackPolicy
VideoWatchSession
VideoWatchProgress

Support configurable:

- access dates
- expiry
- maximum views
- maximum watch duration
- seeking rules where required
- download prohibition
- watermarking
- device restrictions

Playback authorization must be checked server-side.

Do not rely on frontend JavaScript alone to enforce entitlement.

---

# 16. Video watermark

Support optional user-specific dynamic watermark information.

Examples:

student name
student identifier
masked contact information

The overlay should periodically reposition.

Do not permanently modify the source video per student.

---

# 17. Enrollment and access

Preserve the current financial-integrity rule:

No confirmed/approved payment evidence
→ no paid enrollment activation.

Extend Enrollment with:

- source
- billing period
- access start
- access end
- suspension
- expiry
- reactivation
- manual authorized extension

Any privileged access override/extension must be explicitly permission-controlled and audited according to the security rules.

---

# 18. Payments

Preserve:

Order
Payment
LedgerEntry
Refund
PaymentSlip

and their integrity guarantees.

Expand the operational UI to include:

- all payments
- pending
- successful
- failed
- manual slips
- refunds
- student history
- course payment summary
- outstanding fees

Support filtering by:

date
student
course
teacher
status
payment method
reference

Paid status must continue to derive from authoritative payment/ledger state.

---

# 19. Manual payment slips

Preserve the existing secure slip architecture.

Do not remove:

- MIME/content validation
- duplicate reference detection
- duplicate image hash detection
- tenant-scoped duplicate checking
- manual review
- approve/reject state machine
- override reason
- audit trail
- atomic enrollment activation

Improve product workflow and UI without weakening these controls.

---

# 20. Finance and expenses

Add tenant finance management.

Introduce appropriate models such as:

ExpenseCategory
Expense

Expense should support:

date
category
description
amount
payment method
reference
attachment
created_by

Provide:

- income summary
- expenses
- course revenue
- teacher revenue where applicable
- finance reports

Do not modify immutable payment ledger entries to represent expenses.

---

# 21. Teacher settlement foundation

Design TeacherSettlement compatible with the existing payment roadmap.

Potential concepts:

teacher
period
course
gross revenue
commission/rate
deductions
net payable
status

Do not implement gateway split-payment behavior unless separately approved.

---

# 22. Attendance

Migrate attendance semantics to ClassSession.

Workflow:

Teacher
→ select course/session
→ load authorized roster
→ mark Present / Absent / Late
→ save

Reports:

Student:
own attendance only

Teacher:
assigned courses only

Tenant Admin:
tenant-wide subject to permission

Provide course/student/date filters and attendance percentages where required.

---

# 23. Exams

Preserve the existing exam architecture and expand configuration.

Support:

- question bank
- MCQ
- structured answers
- scheduling
- duration
- pass mark
- attempts
- question randomization
- option randomization
- result visibility
- review visibility
- auto marking
- manual marking
- result publication

Never trust client-supplied scoring.

Unpublished results remain unavailable from backend APIs.

---

# 24. Student payment UX

Student portal must clearly distinguish:

UNPAID
PENDING
UNDER_REVIEW
PAID
REJECTED
REFUNDED

Display:

course
billing period
amount
status
payment date
payment method
reference
receipt/slip where allowed

A submitted payment slip must never visually imply confirmed payment.

---

# 25. Device management

Implement tenant-configurable device security.

Concepts:

Device
DeviceSession
TenantDevicePolicy

Support:

- device limit
- device registration
- device list
- revoke/reset
- reset cooldown
- admin reset
- student-visible device management where enabled

Device-limit enforcement belongs in the authenticated session flow.

Do not identify a device only from User-Agent.

---

# 26. Communications

Expand notification-management to support:

- in-app notifications
- email
- SMS
- WhatsApp
- templates
- broadcasts
- audience selection

Potential audiences:

all students
course
teacher
selected users
payment status

Provider-specific integrations must remain behind adapters.

All async events must explicitly carry tenant_id.

---

# 27. Integrations

Create tenant configuration/management for approved integrations:

- payment gateways
- Zoom
- email
- SMS
- WhatsApp
- object storage
- video provider

Never expose persisted secrets back to normal API clients.

Use encrypted secret storage or secret references consistent with architecture rules.

---

# 28. Course reviews

Implement tenant-configurable CourseReview.

Policies may include:

- enabled
- verified enrolled students only
- moderation required
- anonymous display

Enforce review eligibility server-side.

---

# 29. Access policy / smart expiry

Do not duplicate expiry logic across controllers.

Create a centralized AccessPolicyService or equivalent domain API capable of evaluating:

- course access
- billing-period access
- material access
- video access
- manual extensions
- grace periods

Preserve immutable payment history.

---

# 30. Dashboards

Dashboards must be operational and backed by server-scoped data.

Tenant Admin should expose relevant KPIs/actions including:

students
teachers
courses
today's sessions
payments
pending slips
income
outstanding fees
recent registrations
upcoming classes

Teacher:

my courses
today's sessions
students
attendance work
exams
materials

Student:

my courses
upcoming classes
recordings
materials
payments
attendance
exams
notifications

Do not compute sensitive aggregates by downloading broad datasets and filtering them in the browser.

---

# 31. Public storefront

Expand the existing tenant storefront.

Support:

- tenant branding
- institute information
- course catalog
- course detail
- teacher display
- registration
- login

Tenant resolution must remain trusted and server-side.

Direct ID manipulation must never expose another tenant's courses.

---

# 32. Reports

Introduce reporting/read-model APIs where required.

Required report families should include:

- student registrations
- enrollments
- payments
- outstanding payments
- attendance
- exams/results
- course revenue
- expenses
- teacher settlements

Exports must apply exactly the same authorization and tenant filters as interactive results.

---

# 33. Platform Admin

Preserve Platform Admin as the SaaS operator portal.

It should manage:

- tenants
- approvals
- tenant status
- SaaS plans
- domains
- platform integrations
- platform financial oversight
- platform audit
- operational/system health where appropriate

Do not mix normal Tenant Admin operational screens into Platform Admin.

Any future impersonation capability must be explicit, backend-issued and audited.

---

# 34. UX parity requirements

Every major page must support:

- loading
- empty
- error
- populated
- validation
- permission denied
- success feedback
- responsive behavior

Every applicable list must support:

- search
- filtering
- pagination
- sorting where meaningful

Forms must support:

- proper labels
- required indicators
- validation
- save/cancel
- unsaved-change protection where appropriate

Do not produce placeholder-only pages.

Do not create navigation items whose underlying workflows are not implemented.

---

# 35. Database rules

Use additive Flyway migrations.

NEVER edit an already-applied migration.

For every tenant-owned table:

- tenant_id NOT NULL
- appropriate tenant-leading indexes
- same-tenant composite foreign keys where required

Before adding a table, inspect whether the concept already exists.

Prefer extending existing aggregates to creating parallel duplicate tables.

---

# 36. API rules

Maintain versioned APIs.

Every endpoint must specify:

- authentication requirement
- permission
- tenant behavior
- request DTO
- response DTO
- validation
- errors

Never expose JPA entities directly.

Never accept tenant_id from a normal client as authoritative.

---

# 37. Testing requirements

For every parity item implement:

Backend:
- unit tests where appropriate
- integration tests
- Testcontainers persistence tests
- authorization negative tests
- cross-tenant negative tests

Frontend:
- component behavior where applicable
- Playwright role workflow
- loading state
- empty state
- error state
- permission state

For financial/access workflows also test:

- idempotency
- transaction rollback
- duplicate submission
- direct-ID manipulation
- unauthorized state transitions

No parity item is DONE simply because the screen renders.

---

# 38. Performance

Do not create N+1 query patterns in dashboard/report/detail screens.

Use:

- pagination
- indexed tenant queries
- bounded result sets
- appropriate read models

Do not load all students/payments/attendance records into memory to compute a dashboard.

The architecture must remain suitable for high-concurrency LMS use.

---

# 39. Implementation waves

Execute in this order unless dependency analysis proves a small adjustment is necessary:

Wave 0:
Parity audit and documentation only.

Wave 1:
Tenant Admin navigation and tenant configuration framework.

Wave 2:
Course/Class expansion and billing model foundation.

Wave 3:
Student and Teacher operational profiles.

Wave 4:
ClassSession and Zoom/meeting integration.

Wave 5:
Materials, video and playback policies.

Wave 6:
Billing periods and Student Payment parity.

Wave 7:
Finance, expenses and settlement foundation.

Wave 8:
Attendance parity using ClassSession.

Wave 9:
Exam parity expansion.

Wave 10:
Device/access policy management.

Wave 11:
Notifications, communications and integrations.

Wave 12:
Dashboards and reporting.

Wave 13:
Public storefront, branding and domain parity.

Wave 14:
Cross-role product parity regression.

Wave 15:
Security, performance, staging and final readiness review.

DO NOT implement all waves in one Claude Code session.

---

# 40. Workflow for every wave

For each wave:

STEP 1 — Analyse

Inspect existing code and documentation.

STEP 2 — Plan

Create:

docs/parity/waves/wave-XX-plan.md

Document:

- current implementation
- target behavior
- gaps
- files likely affected
- database impact
- API impact
- frontend impact
- security impact
- tenant isolation impact
- test plan
- migration strategy
- risks

STOP after the plan if the wave contains architectural uncertainty or conflicts with an accepted ADR.

STEP 3 — Backend

Implement backend/database only.

Run:

backend\mvnw.cmd verify

Do not begin frontend work while backend verification is red.

STEP 4 — Review backend

Review:

tenant isolation
RBAC
transaction boundaries
API contracts
query performance
migration safety
financial integrity where applicable

STEP 5 — Frontend

Implement frontend against the real backend contract.

Do not mock away missing backend behavior.

STEP 6 — E2E

Implement Playwright coverage.

STEP 7 — Documentation

Update:

docs/api
docs/architecture
docs/ui-ux
docs/parity/klass-parity-matrix.md

STEP 8 — Verification

Run the complete relevant backend/frontend/E2E suite.

STEP 9 — Commit

One logical commit for the completed wave/slice.

---

# 41. Definition of parity

A feature is not considered MATCHED merely because a similarly named screen exists.

Parity requires all applicable dimensions:

1. role can reach it
2. navigation is correct
3. data shown is correct
4. fields exist
5. validation exists
6. actions work
7. state transitions work
8. permissions work
9. tenant isolation works
10. configuration affects behavior
11. responsive states work
12. loading/empty/error states work
13. persistence is correct
14. audit requirements are satisfied
15. regression tests exist

Only then mark the parity item MATCHES.

---

# 42. Prohibited shortcuts

Do not:

- rebuild the project from scratch
- replace Spring Boot
- replace Next.js
- replace PostgreSQL
- bypass TenantAwareRepository
- weaken server-side authorization
- trust tenant_id from the browser
- mark an order as paid from frontend callback state
- activate enrollment without authoritative evidence
- make protected storage objects public
- store secrets in frontend code
- edit historical Flyway migrations
- create duplicate domains because extending the existing domain is harder
- implement only visual mockups
- mark TODO/placeholder screens as complete
- silently decide unresolved business rules
- perform broad unrelated refactoring

---

# 43. First task

Do not implement a feature yet.

Perform Wave 0 only.

Inspect the entire repository and produce:

1. docs/parity/klass-parity-matrix.md
2. docs/parity/current-architecture-inventory.md
3. docs/parity/database-impact-analysis.md
4. docs/parity/api-impact-analysis.md
5. docs/parity/frontend-route-impact-analysis.md
6. docs/parity/rbac-impact-analysis.md
7. docs/parity/migration-strategy.md
8. docs/parity/implementation-roadmap.md

For every parity requirement, map it to:

existing module
existing backend implementation
existing frontend implementation
existing database objects
GitHub MVP issue
gap classification
proposed remediation
implementation wave

Do not modify production code during Wave 0.

At the end report:

- files created
- total parity requirements
- counts by classification
- architectural conflicts
- database migration risks
- security risks
- unresolved business decisions
- recommended Wave 1 scope

Then STOP and wait for human review before implementing Wave 1.