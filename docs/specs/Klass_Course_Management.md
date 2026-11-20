# Klass course management — Claude Code implementation instructions

Prepared 7 October 2026 from `Course - Front End.mp4` (71 seconds) and `Course - Backend Management.mp4` (73 seconds). These are visual workflow observations, not inspection of Klass source code, database, or network APIs. Timestamp ranges are approximate. No existing LMS repository was supplied for this analysis.

## Agentic execution contract — updated 12 October 2026

The parent Claude Code session is the implementation coordinator. Invoke the actual project subagents by their frontmatter `name`, not by filename or by merely saying you acted as that role. Read `.claude/agents/` definitions, CLAUDE.md and AGENTS.md before assigning tasks. Keep existing agent tools, models and permission modes; this specification does not grant tools absent from a definition. Review-only agents completing a plan is normal; the parent must then delegate implementation and continue until validation is complete.

### Authoritative enrollment correction

The owner explicitly authorizes free backend-confirmed enrollment in available courses. Paid LESSON ENTITLEMENT, not enrollment, activates only after verified backend payment or authorized manual approval. Frontend success pages authorize neither action. Course → Module → Lesson/Session remains the content hierarchy.

Before launching subagents, inspect local definitions and project guidance for older payment-before-enrollment rules. The supplied `backend-springboot-engineer` and `payment-ledger-specialist` definitions contain this conflict; frontend guidance also needs terminology clarification. The parent is authorized to make the narrowly scoped wording correction in `.claude/agents/` and corresponding CLAUDE.md/requirements, preserving other change controls. Record the approved business-rule change in an ADR using repository conventions. Replace contradictory rules with:

> Registered students may enroll freely through an authenticated, authorized backend enrollment operation. Enrollment never depends on payment. Paid lesson/session entitlements are granted only after verified backend payment confirmation or approved manual payment evidence, never from a frontend success page. Payment pending/failure/rejection does not cancel enrollment.

Do not weaken payment verification, financial-history preservation, tenant isolation, authentication or production-deployment controls. No new permission question is needed for this already instructed business change. If the repository reveals an unrelated protected architectural change, identify that specific blocker and continue independent work. Do not bypass denied tools or globally change agent permission modes.

### Agent responsibilities and ownership

| Actual subagent | Assignment | Ownership/boundary |
|---|---|---|
| product-requirements-analyst | Gap matrix, evidence/assumption distinctions, acceptance coverage, contradictions | Read-only; return analysis to parent |
| solution-architect | Module/API/access design, reusable hierarchy and tenant model review | Read-only; parent records decisions/ADRs |
| database-architect | Schema, constraints/indexes, new Flyway migrations and migration validation | Own new migration files; append-only history |
| backend-springboot-engineer | Java 21 Spring Boot/Maven services, DTOs, controllers, entities and authorization | Own assigned backend application files; do not edit frontend |
| frontend-nextjs-engineer | Next.js/TypeScript/Tailwind/shadcn UI and client API bindings | Own assigned frontend application files; do not edit backend |
| payment-ledger-specialist | Review quote math, account routing, verification, allocation, reversals and precise entitlement scope | Read-only; fixes assigned to backend/database owners |
| integration-engineer | Provider interface/adapters, storage/video/meeting/payment handoff integration and official-doc verification | Own explicitly assigned adapter files only; no credentials; no Bash in supplied definition |
| qa-test-engineer | JUnit/Testcontainers and Playwright acceptance/regression tests | Own assigned test files only; report application defects to owner |
| security-reviewer | Tenant/role boundaries, direct resource access, uploads, secrets, entitlement bypass review | Read-only; severity-ranked findings |
| ui-ux-reviewer | Role flows, form labels, states, responsiveness and reference fidelity | Read-only; supplied tools cannot visually open screenshots or run browser tests; parent/frontend/QA provide visual evidence |
| legacy-migration-engineer | Existing data mapping, ambiguity/integrity analysis and legacy preservation recommendations | Read-only; no production access or migration execution |
| devops-engineer | Local test dependencies, development infrastructure and CI issues | Infrastructure only; invoke when needed; no production database/deployment |

Use every relevant agent, but do not spawn all twelve for each small task. Legacy and DevOps are conditional on actual data/infrastructure work; record why skipped. Preserve `model: inherit`. Missing agent definitions must be reported by name; continue independent work and do not pretend an unavailable agent was invoked.

### Coordination stages and dependency gates

1. **Discover and resolve requirements.** Parent reads both specifications and corrected agent definitions. Run product-requirements-analyst and solution-architect for independent read-only analysis; add payment-ledger-specialist for payment/access changes and legacy-migration-engineer if backfill/mapping is needed. Parent synthesizes findings into a gap matrix and decision log, not a plan-only final answer.
2. **Fix contracts and ownership.** Parent records DTOs/API operations, validation, error/access statuses, coverage rules and allowed files in the plan. Database architect reserves unique new Flyway versions based on repository history. No agent guesses migration versions independently. Backend and integration agents agree interface ownership before edits. Use mock fixtures only for development/tests; do not report them as completed integrations.
3. **Implement dependencies.** Database architect owns schema scripts; backend engineer owns matching entities/services. Serialise changes to overlapping files. Integration engineer writes only reserved adapter files; backend engineer or QA executes adapter checks because the provided integration agent lacks shell tools. Parent owns shared documentation.
4. **Implement independent UI/test work.** Once contracts are stable, backend and frontend may work concurrently on disjoint files. Assign QA exclusive test paths; frontend/backend owners must not concurrently modify those files. Agent definitions allowing engineers to write tests do not require simultaneous test writers. Schedule exploratory browser execution after the corresponding feature is integrated.
5. **Integrate and review.** Parent verifies actual changes, compiles/builds, checks API compatibility, and requests security, payment and UX review of the integrated implementation. Reviewers return findings without modifying application code. Parent routes fixes to responsible owners and requests focused re-review after changes.
6. **Verify and complete.** QA runs acceptance cases and relevant suites; backend runs Maven verify and frontend runs repository lint/typecheck/build checks. Parent inspects evidence and updates status for each acceptance ID. Resolve actionable findings, rerun affected checks and finish documentation. Do not call unexecuted checks passed or silently replace PostgreSQL integration tests with mocks when Docker is unavailable; DevOps diagnoses local dependencies and remaining checks are reported blocked honestly.

Reviewer plan modes apply to reviewer work, not the entire implementation. Return from those reviews to engineer implementation without another plan approval unless an actual project control requires it. Do not change reviewer roles to writing agents to avoid this distinction.

### Reference screenshots: exact repository locations

The owner placed screenshots in:
- `docs\references\Klass Course Backend Management UI`
- `docs\references\Klass Course Front End UI`

Equivalent repository-relative paths:
- `docs/references/Klass Course Backend Management UI/`
- `docs/references/Klass Course Front End UI/`

Spaces are intentional: quote paths in shell commands. Read available Screenshot_Index.txt files and list filenames before selecting screenshots. Do not assume image filenames. Parent or a visual-capable frontend agent opens only images relevant to the current feature; ensure the tool actually supports visual image reading rather than assuming text Read does. Provide reviewers with extracted labels/layout notes and captured test evidence when their tools are text-only. No need to load every image into every agent context. These two folders are course admin/student references; do not assume they contain the separate enrollment/payment or creation screenshots. If supplementary images are absent, proceed from documented workflow evidence and record the visual-verification limit.

### Task packet and handoff format

Every delegation includes: task ID; objective; requirement/acceptance IDs; relevant document sections; confirmed dependencies; allowed/forbidden paths; API/model contract; relevant reference images or notes; required validation; and return format. Give bounded context rather than the entire conversation.

Each agent returns: completed/partial/blocked status, files changed or inspected, decisions/assumptions, commands and actual results, remaining defects, and next-owner dependencies. Read-only agents explicitly return findings only. Parent verifies outputs rather than equating agent completion with feature completion.

Maintain a parent-owned task table in the existing plan: task, owner, dependencies, allowed files, requirement IDs, status, validation and blockers. Update after each handoff. Keep an acceptance matrix with evidence path for each completed item. Preserve uncommitted user work; no concurrent edits to shared files. No deployment, push, merge, destructive database action or production connection is authorized by this brief.

### Cross-specification scope

Both specs describe one implementation. Course-management owns the admin Course → Module → Session and student browsing structure. Enrollment/payment owns free enrollment, Section 3A creation fields, offers/checkout, verification and lesson entitlements. Reconcile their overlapping code once. If executing both, complete the structural contracts first, then integrate payment/access, rather than independently creating duplicate course/forms/orders. Owner-specified free enrollment and leaf access rules take precedence over older payment-dependent enrollment text.

### Definition of done for the coordinator

All applicable acceptance IDs are implemented and evidenced, or specifically marked blocked with reason; database history and financial records preserved; meaningful tenant/payment/access tests executed; UI checked against available references; security/payment/UX findings resolved or transparently listed; plan/spec/QA docs match code. Finish with actual agent participation, changes/migrations, test results and unresolved limitations. Continue implementation, not merely task allocation.


## 1. Task and execution contract

Modify our EXISTING multi-tenant LMS course management to reproduce the workflows and screen structure specified below. Read this entire document before editing. Inspect the repository, AGENTS.md, architecture, migrations, course/lesson models, billing, enrollment, tenant resolution, roles and player integrations first. Reuse existing working features. Do not create a parallel course subsystem or replace the application with a generic LMS template.

This is an IMPLEMENTATION request. Produce the repository gap analysis and plan, then continue to implement, migrate, test and document in this same task. Do not stop after planning or ask whether to start implementation. Pause only for a concrete blocker that cannot be resolved from the repository or this specification. Report partial completion honestly if an external integration is unavailable.

Create/update these repository files throughout implementation:
- `docs/plans/klass-course-management-parity.md`: scope, evidence limits, repository mapping, migration plan, implementation stages and current status.
- `docs/specs/klass-course-management-workflows.md`: actual implemented fields, workflows, access rules and APIs.
- `docs/qa/klass-course-management-acceptance.md`: acceptance scenarios, commands, results and outstanding gaps.

Do not overwrite other feature plans. Use the existing stack and conventions. Validate backend behavior and student/admin flows, not just visual rendering. Do not claim exact Klass parity for screens or behaviors absent from these recordings.

Evidence labels in this document:
- **Observed**: visible in a recording.
- **Required design**: an implementation decision needed to make our LMS consistent, secure or usable; not a claim about Klass internals.
- **Unverified**: a control is visible but its action or rules were not demonstrated. Inspect existing implementation and preserve it where possible; document assumptions.

## 2. Core relationship and mapping

**Observed:** Admin navigates Courses → Modules of a selected course → Sessions of a selected module. Student navigates enrolled course cards → course detail → billing-month folders → module folders → individual sessions.

The month folder is a student presentation/billing grouping. The recording does not show admins creating month folders or prove that modules are duplicated monthly. Preserve a reusable course/module structure and explicitly associate sessions with a billing period using the existing model, or add the smallest compatible association. Do not infer billing periods by parsing names.

| Concept | Meaning in our implementation | Evidence |
|---|---|---|
| Course | Teacher, cover, title, payment model, price, registration state, archive state and categorization | Course list and student cards |
| Module | Reusable named group of sessions, with a persistent display order | Admin module table; student nested folders |
| Session | Dated content resource within a module, with resource type and payment type | Session table and form |
| Billing period | Explicit course/month or other existing billable period and associated entitlement | Student October unpaid and September paid folders |
| Viewed state | Student-specific session progress | `1 of 12 viewed`; empty circles and one green check |

**Required design:** Map existing lessons/content records to sessions wherever practical. Preserve IDs, existing enrollment references, orders and historical entitlements. Explain any conversion and provide a non-destructive migration/backfill. Keep billing period, resource availability, payment state, enrollment and viewed state as distinct concepts.

## 3. Admin course listing

**Observed, backend 00:00–00:07:**
- Left navigation highlights Courses; page heading Courses.
- Tabs ACTIVE and ARCHIVED.
- Search input on the left; `Today's courses first` toggle, QUICK CREATE and ADD COURSE on the right.
- Dense white table on a light background, blue primary controls.
- Columns: drag handle, `#`, Name, Teacher, Payment Type, Price (LKR), Enabled for Registration, Actions.
- Examples include Monthly Fee with 4000.00 and Free with N/A. Registration switches are shown enabled.
- Actions include a green list-like icon used to navigate into modules, people icon, pencil, red bin and vertical overflow menu.

**Required design:** Implement active/archive listing and persistent registration state. Registration disabled prevents new registrations but must not silently revoke an existing student's access. Use server-side authorization for mutations. Persist ordering if drag handles are implemented. Search must return matching tenant courses. Keep price numeric and format by tenant currency.

**Unverified:** Search matching rules, sorting choices, drag behavior, archive/restore interaction, people/overflow actions, deletion semantics, QUICK CREATE and ADD COURSE form fields. Keep existing functioning course creation/edit fields and workflows. Do not invent a supposedly complete Klass course form. Make icon actions accessible with names/tooltips; infer only the pencil/edit and bin/delete affordances, and preserve existing semantics. Use archive or guarded deletion for referenced courses.

## 4. Admin modules

**Observed, backend 00:07–00:18:**
- Heading `Modules of 2027 A/L Biology Theory - Live Online Class` with course breadcrumb.
- Search; IMPORT MODULES and ADD MODULE.
- Table: drag handle, number, Name, Price (LKR), Actions; sample prices N/A.
- Five groups: extra-class recordings, live Zoom meetings, extra-class live Zoom meetings, recordings, class materials.
- ADD MODULE opens `Add New Module` modal with `Name *`, close X, CANCEL and CREATE MODULE.
- Closing returns to the unchanged table. No successful creation is recorded.
- A module action opens its Sessions page.
- Pagination shows Items per page 10, row range and first/previous/next/last controls.

**Required design:** Create named modules in the selected course; validate trimmed non-empty names on client and server. Provide edit, guarded delete and persistent ordering through existing conventions. Navigate into sessions and retain course/module breadcrumbs. Never allow a module from another tenant or course to be selected by changing an ID.

**Unverified:** IMPORT MODULES format and copy/reference behavior; module pricing configuration; meanings of other module action icons. Retain existing import capability. If missing, record an explicit gap rather than fabricate Klass's import dialog or data contract. A new import workflow requires an explicitly documented design with preview, validation and duplicate handling.

## 5. Admin session list and form

**Observed, backend 00:18–01:04:**
- Heading Sessions and breadcrumbs Courses → course → module → Sessions.
- Search, IMPORT SESSIONS and ADD SESSION.
- Table: drag handle, `#`, Name, Type, From, To, Payment Type, Price (LKR), Expired?, Actions.
- Sample: `2027 Biology Extra Class - October 7`, Video Link, From 2026-10-07, To 2026-10-31, Covered by class fees. Price and Expired? are blank in the shown row.
- Link, QR-like, pencil and bin action icons are visible. Their resulting dialogs are not demonstrated.

ADD SESSION opens `Add New Session`:

| Field | Required marker in recording | Presentation |
|---|---|---|
| Name | Yes | Full-width text input |
| From Date | Yes | Left half of date/time row; populated 2026-10-07 |
| From Time | Yes | Right half; populated 09:50 PM |
| To Date | No | Left half of second row; initially blank |
| To Time | No | Right half; initially blank |
| Resource Type | Yes | Full-width dropdown |
| Payment Type | Yes | Full-width dropdown |
| Lesson | No | Shown after expanding additional settings; input-like control |
| Description | No | Multiline field under additional settings |

Footer: CANCEL and CREATE SESSION; close X in header. SHOW ADDITIONAL SETTINGS expands Lesson and Description and becomes HIDE ADDITIONAL SETTINGS. Empty Resource Type and Payment Type visibly become red with `This field is required.` after interaction. No resource is selected, no conditional provider configuration is shown, and no session is successfully submitted.

### Resource types visible in the scrolled dropdown

1. Vimeo Video
2. Vidrora Video
3. Document
4. Zoom Link
5. Zoom Meeting
6. Zoom Webinar
7. Zoom Recording
8. Video Link
9. Google Drive Link
10. MS Teams Link
11. Google Meet

The recording shows help/question icons alongside options; their help text is not opened. Vimeo Video appears visually muted; the reason is unknown. Do not treat it as definitively disabled.

### Payment types visible

- Covered by class fees
- Paid
- Free

**Required design:** Store resource type and payment type independently. Use an extensible resource adapter/configuration model. A visible dropdown alone does not count as an implemented provider. Reuse real integrations, document unsupported providers and avoid misleading success states. Keep the competitor label Vidrora in the evidence mapping; use our own secure player name in the product unless actual Vidrora integration already exists.

For Paid sessions, use existing independent pricing and session entitlement infrastructure; if absent, add an explicit price configuration and document that its form placement is our design, since it was not shown. For Covered by class fees, resolve the relevant course/billing-period entitlement. Free bypasses payment, while retaining tenant visibility, applicable enrollment policy and availability checks.

**Required design:** Use tenant timezone for default/displayed times and canonical instants for checks. Validate end after start; document handling of partial end date/time fields. Do not hard-code October 2026 or 09:50 PM. Treat Lesson as optional metadata until the existing model clarifies whether it is a label or association. Do not invent a full lesson-management screen from that single field.

## 6. Student course discovery

**Observed, front 00:00–00:18:**
- Heading Enrolled Courses, pills Enrolled Courses and Available Courses.
- Search courses, Sort by with Default selected, filter chips below.
- Visible chips include 2027 A/L, Chemeve, Chemistry, Biology, 2028 A/L, ICT, Postal Fee, Combined Maths, bioeve and Physics. These are tenant data, not fixed global options.
- Four cards in a desktop row. Each has cover image, price badge, title, teacher and Open course link.
- Examples: Biology live class LKR 4000 / month; Chemistry live class LKR 4000 / month; Chemistry paper revision LKR 2000 / month; Biology catch-up Charged per module.
- Typing `biology` reduces the list to two Biology cards; clicking Open course opens Biology live-class detail.

**Required design:** Search case-insensitively at least by course title; preserve existing search scope. Render category/tag filters from tenant data. Expose Default sort; preserve existing options because the menu is not opened. Keep enrolled/available views distinct and use actual enrollment data. Available Courses interaction and chip matching semantics are unverified. Use responsive cards, loading, empty and error states; these states are our design, not recorded behavior.

## 7. Student course detail

**Observed, front 00:18–00:28:** Header card contains cover on left and price badge, course title and teacher on right. Tabs are Course content, Exams, Live Classes, Tute Delivery, About this course. Active tab has blue text and underline; shared header stays across tab navigation.

- Exams: In progress, Upcoming and Previous selectors, all counts zero; empty text `No exams running right now` and `When your teacher starts an exam it will appear here.`
- Live Classes: Upcoming live classes, badge `2 classes`, rows grouped by day/date. Rows display start/end time, session title, Zoom label, module name and Pay now. The two shown rows are October 10 and October 14. The reason later October sessions are absent here is unverified; do not infer a particular date horizon.
- Tute Delivery: `No tutes for this course yet` and `When your institute sends printed tutes, you can follow them here.`
- About this course: tab visible; its content is not demonstrated.

**Required design:** Bind these tabs to existing exam/live-class/delivery/course-description modules. Live Classes must use the same sessions and access decisions as Course content, avoiding duplicate schedule records. Preserve existing non-empty workflows; this recording does not specify exam-taking or shipment tracking forms.

## 8. Student content tree and payment state

**Observed, front 00:28–00:38 and 00:58–01:06:**
- White Course content panel; right-side `1 of 12 viewed` with green progress bar.
- Top-level folders `2026 October` then `2026 September`.
- October shows a red Pay now action; September shows a green Paid badge.
- Expanding October reveals module folders for live Zoom, recordings, extra-class live Zoom, materials and extra-class recordings.
- Expanding live Zoom shows October 10, 17, 24 and 31 sessions. Each shows an empty progress circle, resource icon, title, green Starts timestamp, red Expires timestamp and Pay now.
- Expanding paid September → recordings shows `2027 Biology - September 12 Recording`, green viewed check and expiry text. The expiry appears December 31, so paid month and content expiry are not necessarily identical.
- Nested sections can remain open together; paying is not required merely to expand the visible content tree.

**Required design:** Render course → billing period → module → session as a projection of actual course/module/session data. A reusable module can appear in several periods. Keep fee coverage period distinct from resource expiry. Preserve stable ordering; newest billing months first matches the observed sample. Do not reveal protected resource URLs in unpaid metadata responses. Clicking a session covered by class fees must resolve that month's course fee, not charge separately for each session. Paid sessions use their own entitlement. Free sessions must not display Pay now due solely to an unpaid month.

**Required design:** Return a server-computed access result with reasons such as payment required, pending approval, not started, expired, allowed and external-player required. The precedence and exact labels beyond observed Paid/Pay now are our design and must be documented. Make viewed progress user-specific and idempotent; merely expanding folders must not mark sessions viewed. The recording does not establish whether viewed means opened or watched to completion. Reuse our established progress definition and state it in the QA document.

## 9. Payment workflows

**Observed, front 00:38–00:57:** Clicking October Pay now opens Make a payment modal over the content tree. Summary identifies October 2026 and the Biology live class; base LKR 4,000.00. Options:
- Pay online — Card or online banking — + LKR 100.00 Facility Fee.
- Upload a payment slip — Paid at a bank? Upload the deposit slip. — + LKR 100.00 Facility Fee.
- Close X and Cancel.

Pay online opens a provider checkout showing Rs. 4,100.00 and card/other payment options. It is closed without payment. Bank-slip option opens a second Make a payment screen containing:
- Course title, Month 2026 October, LKR 4,100.00, Includes Facility Fee: LKR 100.00.
- Bank and branch, account holder, account number and copy icon.
- Required Payment slip attachment; helper `A photo or PDF of your bank deposit slip.`
- Back, Cancel, Submit and close X.
No slip is uploaded, no successful payment or approval is shown.

**Required design:** Reuse central orders/payment orchestration and tenant payment accounts. Prices, facility fees, methods and bank details must be tenant-configured; the example values are fixtures only. Calculate totals server-side and store a price breakdown with the order. Enforce idempotent checkout/webhook processing and prevent duplicate entitlements. Grant access only following verified payment or authorized manual approval, not checkout launch, return URL or slip upload. Show pending/rejected states using the existing payment workflow; these states are not observed in the video.

Scope all slip storage/downloads to authorized users and reviewers. Validate image/PDF MIME, size and content using existing upload policy. Never place real sample account numbers in seed production configuration. Keep payment modal context consistent when opened from month, covered session or Live Classes. Closing/back navigation must preserve expanded folders and selected course. Integration credentials must remain server-side.

## 10. External-player handoff

**Observed, front 01:06–end:** Clicking a paid September recording opens `Web playback is turned off`. Text says the institute disabled browser playback and suggests Vidrora Player or mobile app. Buttons: Open in Vidrora Player, Android, iOS; close X. The recording does not show an app launching, a deep-link format, playback, token exchange or the hosting architecture.

**Required design:** Use our existing tenant-level browser-playback setting and secure-player integration. If browser playback is disabled, show equivalent messaging with our player branding and configured platform links. Recheck entitlement server-side before issuing a short-lived handoff token. Do not expose permanent protected media URLs. If our player is not implemented, clearly show unavailable configuration and document the blocker. This course-management task does not authorize claiming an implemented desktop player, DRM, YouTube proxy or capture prevention from this modal alone.

## 11. Backend, tenant and migration requirements

1. Inspect current API/entity names and publish an exact repository mapping before choosing endpoint paths. Illustrative operations needed: course lists/update registration/archive; module list/create/update/order; session list/create/update/order; student content projection; payment quote/order/slip submission; authorized resource launch; viewed progress. These are requirements, not discovered Klass endpoints.
2. Scope every read and mutation to authenticated tenant and role. Validate parent-child relationships on the server. Teacher authority follows our existing assigned-course permissions; a separate teacher workflow was not recorded.
3. Use one entitlement evaluation service across content rows, live schedule, documents, video launch and player handoff. Prevent ID manipulation and cross-tenant references.
4. Reuse the central ledger and payment foundation. Do not grant access to all historical months when only one period is paid. Do not confuse a scheduled end time with payment period end.
5. Preserve original billing history and provider data in migrations. Backfill explicit billing-period associations with a report of ambiguous records; do not infer them silently from title strings.
6. Use pagination and efficient joins/batched access evaluation, avoid a query per session, and add indexes justified by existing queries.
7. Audit course/module/session mutations, registration/archive changes, manual payment decisions and resource launches according to current logging policy. Do not log tokens, credentials or payment-slip contents.

## 12. Execution stages

Proceed through these stages without a plan-only stop:
1. Repository discovery and gap matrix: observed requirement → existing files/features → change needed → evidence limit.
2. Compatible model/migration and shared access/payment logic.
3. Admin courses/modules/sessions UI and real CRUD operations.
4. Student cards/detail tabs/content projection/progress.
5. Payment modal and real integration with existing online/manual workflows.
6. Resource launch and configured external-player handoff.
7. Focused tests, browser walkthrough, screenshots and documentation update.

Update the plan after each stage with actual status. Keep unrelated changes out of the implementation. Follow repository branch and commit conventions; do not commit credentials or production payment details. Do not publish or deploy unless separately authorized.

## 13. Acceptance criteria

| ID | Scenario | Pass condition |
|---|---|---|
| A01 | Admin course list | Required visible columns, active/archive tabs and persistent registration state |
| A02 | Course → modules → sessions | Correct scoped hierarchy and working breadcrumbs |
| A03 | Add module | Name required; successful save survives reload; cancel creates nothing |
| A04 | Add session | Recorded fields and payment/resource options; backend validation; additional settings toggle |
| A05 | Session schedule | Invalid end/start rejected; times round-trip correctly in tenant timezone |
| A06 | Existing data | Course/content/enrollments/payment history preserved after migration |
| S01 | Course search | `biology` filters appropriate cards; title/teacher/cover/price/open link visible |
| S02 | Detail navigation | Five tabs retain course header; existing tab data remains functional |
| S03 | Content hierarchy | October/September fixture groups with nested reusable modules and sessions |
| S04 | Paid/unpaid periods | October payment required, September paid; paying October does not grant unrelated periods |
| S05 | Live Classes | Schedule uses same session data and access policy as content tree |
| S06 | Viewed state | Per-user persisted progress; expansion alone does not increase count |
| P01 | Fee quote | Fixture 4000 + 100 = 4100; real totals use tenant configuration and server calculation |
| P02 | Online payment | Checkout launch does not grant access; verified success does; replay does not duplicate |
| P03 | Manual slip | Valid file produces pending state; authorized approval grants correct entitlement; rejection does not |
| P04 | Payment context | Month and covered-session entry points target the same billable course period |
| R01 | Three payment types | Covered, independent paid and free follow distinct access rules |
| R02 | Expiry | Expired resource denied even when appropriate payment is recorded |
| R03 | External player | Browser disabled modal uses configured links; launch requires authorized short-lived token |
| T01 | Isolation | Tenant A cannot inspect/edit/pay/launch tenant B records using modified IDs |
| T02 | Role checks | Unauthorized student/teacher cannot perform admin mutations or approve slips |
| U01 | Honest controls | No fake imports, provider success, placeholder actions represented as completed features |

Use existing test frameworks. Add meaningful regression tests for entitlement scope, tenant boundaries, migration compatibility and payment idempotency, plus focused UI coverage for the demonstrated navigation. Report commands actually run and any environmental limitations. Capture admin course/modules/session form and student cards/tree/payment/player modal screenshots for review.

## 14. Open evidence gaps and confidence

Observed screen labels/hierarchy/options: confidence 0.96. Student/admin relationship and month projection: confidence 0.90; physical database schema is unknown. Complete hidden business rules: confidence 0.60; the following remain unverified:
- Full course creation/edit and Quick Create forms; all payment models and sort options.
- Import schemas, duplicate behavior, icon tooltips/actions, QR/link behavior and reorder persistence.
- Resource-specific fields after selecting a type; provider integration and help text.
- Paid-session price form and module-price configuration.
- Monthly entitlement assignment rules, progress calculation and live schedule date horizon.
- Successful online checkout, slip approval/rejection/refund workflows.
- Available-course registration, populated exams/delivery/About content and teacher screens.
- Desktop/mobile launch protocol and actual playback architecture.

Implement the observed surfaces plus the explicitly identified required designs. Preserve existing behavior for unseen areas and list any proposed deviations. Do not state that unseen Klass features have been reproduced exactly.

## 15. Final response required from Claude Code

Explain what changed, exact files/migrations, how existing data was preserved, checks run, screenshots captured and remaining gaps. Include a completed/partial/blocked matrix against section 13. Distinguish implemented provider behavior from configuration-only support. Update all three documentation files to match the final code.