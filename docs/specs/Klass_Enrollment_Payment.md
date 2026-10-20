# Klass-style free enrollment and lesson-level payment access
## Separate Claude Code implementation instructions — 7 October 2026

Sources: `Course - Enrollment And Payment.mp4`, approximately 2 minutes 15 seconds, and `Course - Creation.mp4`, approximately 49 seconds. Updated to include admin course creation. This document is a separate implementation brief. It supplements course-management instructions without replacing them. No repository, Klass source, API traffic or database was inspected.

Evidence categories: **Observed** means visible in this recording; **User requirement** means the business rule explicitly supplied by the owner; **Required design** means our implementation decision rather than a claim about Klass internals; **Unverified** means not established by the video.

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
- `docs\references\Klass Course_Front End UI`

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


## 1. Read this first: implement the correct business model

**User requirement:** Any registered student can enroll freely in an available course. Enrollment does not require course payment, a bank slip, an order, manual approval or a membership purchase. Payment restriction applies when accessing an individual lesson/session.

Canonical content hierarchy: **Course → Module → Lesson**. Klass calls the leaf a Session in its administrative/payment UI. Treat Lesson and Session as the same leaf-level concept for this feature, unless repository inspection reveals an established distinction. Reuse the existing entity and provide a documented terminology mapping. Do not introduce two competing content trees.

Enrollment grants course participation and navigation. A successful payment grants specifically scoped content entitlement. These are separate records and separate decisions.

A billing month may group modules/lessons in the student interface, but is not another mandatory content entity between Course and Module. Use an explicit billing association/projection; do not duplicate the entire course or parse titles to determine payment scope.

This brief is authoritative for free enrollment and lesson-level payment enforcement if earlier instructions or current code tie enrollment to payment. Preserve valid historical access and document the migration. Do not add an enrollment approval workflow.

## 2. Execution instructions

Read AGENTS.md and inspect existing authentication, tenant resolution, roles, course catalog, enrollment, modules/lessons, payment methods, orders, ledger, slip approval, discounts and resource launch code. Produce a requirement-to-repository gap matrix and then continue to IMPLEMENT, migrate, test and document. Do not stop after a plan or ask whether to start implementation.

Use the current stack and naming conventions. Reuse the existing central payment orchestration and tenant-aware collection-account architecture. Modify current flows rather than build a parallel enrollment or checkout subsystem. Do not implement every unseen workflow as if its Klass behavior were known.

The parent coordinator must create and maintain:
- `docs/plans/klass-enrollment-payment-parity.md`
- `docs/specs/klass-enrollment-payment-workflows.md`
- `docs/qa/klass-enrollment-payment-acceptance.md`

Update them after each stage with actual changes, decisions, checks and remaining gaps. Preserve other plans. Only pause for a concrete blocker that cannot be resolved from the repository or the documented requirements.

## 3. Recording walkthrough and evidence

| Approximate time | Visible workflow | Meaning |
|---|---|---|
| 00:00–00:12 | Enrolled Courses cards and course view pills | Existing enrollments visible before payment |
| 00:12–00:18 | Available Courses selected; ICT filter selected | Available catalog can be filtered |
| 00:18–00:24 | Enroll on ICT Theory; confirmation; button spinner | Enrollment is a discrete action |
| 00:24–00:32 | Course disappears from filtered available list and appears under Enrolled Courses | Immediate enrollment/list refresh without checkout |
| 00:32–00:51 | ICT course opened; October/module expanded; lessons show Pay now | Enrollment allows browsing but does not grant protected content |
| 00:51–01:07 | Global PAY opens Make Payment; Monthly course dropdown used | Payment is independently accessible |
| 01:07–01:25 | 2026 month grid; September and October selected | Multiple billing periods can be paid together |
| 01:25–01:35 | Summary, discount accordion, payment-method switch | Shared payment cart/summary and method selection |
| 01:35–01:55 | Proceed to payment clicked; prolonged spinner; bank-slip option shown again | A payment attempt is visible, but no successful result |
| 01:55–02:13 | Daily, Module, Session, Installment tabs examined | Several payment scopes exist, only partial selectors demonstrated |
| Final seconds | Dialog closed; course lessons still show Pay now | No verified entitlement transition shown |

No payment is completed. No slip is selected or approved. No lesson is successfully opened. Do not infer successful checkout or approval from the spinner.

## 3A. Admin course creation — added from Course - Creation.mp4

Additional source: `Course - Creation.mp4`, approximately 49 seconds. This resolves the previously missing base course creation form and additional settings. It does not resolve payment-type-specific price forms: the operator opens the dropdown but selects no payment type, enters no complete course and does not successfully save.

### Recorded steps

| Time | Action and visible result |
|---|---|
| 00:00–00:04 | Admin Courses active table; click ADD COURSE; QUICK CREATE is a separate visible control |
| 00:04–00:08 | Add New Course modal with Name, Teacher, Payment Type, Enrollment Fee, additional-settings toggle and Cover Image |
| 00:08–00:12 | Teacher dropdown opened; checkbox options for tenant teachers/admin |
| 00:12–00:20 | Payment Type dropdown opened; six options with question/help icons |
| 00:20–00:25 | Enrollment Fee focused; Rs. prefix, .00 suffix and helper appear; required-field errors visible |
| 00:25–00:34 | Additional settings expanded; payment-valid days, commission, invitation links and description revealed |
| 00:34–00:38 | Tags selector opened; existing tags and guidance for creating new tags |
| 00:38–00:43 | Link another course selector opened with checkbox options and explanatory helper |
| 00:43–00:47 | Cover Image CHANGE clicked; native file picker appears; no completed image choice shown |
| 00:47–end | Return to unchanged course table; no successful creation demonstrated |

### Base modal layout and fields

Modal heading `Add New Course`; close X; dimmed course list behind. Fields appear in this order. Footer has CANCEL and CREATE COURSE. Expanded content scrolls inside the modal.

| Field | Observed control/requirement | Implementation instruction |
|---|---|---|
| Name * | Text input; `Name is required` error | Trim and validate required name on client/server; use existing length limits |
| Teacher * | Dropdown with checkbox options; `Teacher is required` | Model selection as a teacher-assignment collection if compatible with repository; require at least one valid tenant teacher |
| Payment Type * | Six-option dropdown; `Payment Type is required` | Store course billing model separately from enrollment and leaf payment policy |
| Enrollment Fee | Currency amount; Rs. prefix/.00 suffix when focused; helper `Enrollment fee for the course`; no required star | Preserve observed metadata field with free-enrollment behavior explained below |
| SHOW ADDITIONAL SETTINGS | Collapsible section, changes to HIDE ADDITIONAL SETTINGS | Hide advanced fields initially; retain entered values on collapse |
| Cover Image | Existing generic/default thumbnail preview; blue CHANGE button opens file picker | Allow optional validated upload and preview; preserve tenant default cover when absent |

Teacher dropdown visibly contains AOC Admin, Sampath Lankadheera, Indunil Gunawardana, Ananda Illangakoon, Vajira Seneviratne and Nuwan Balasooriya. These are tenant data, not hard-coded options. Checkboxes suggest multi-selection; no combination is submitted, so persistence/card display semantics for multiple teachers are unverified. Preserve existing primary-teacher semantics while retaining any additional assignment list.

### Course payment models: exact recorded labels

| Course dropdown label | Existing payment-composer scope to connect | Caveat |
|---|---|---|
| Monthly Fee | MONTHLY | Per-period offer pricing; price fields after selection unseen |
| Full Course | COURSE | Coverage duration/exclusions not demonstrated |
| Day payments | DAILY | Daily offer/date rules not demonstrated |
| Pay For Module | MODULE | Module offer pricing UI not demonstrated |
| Installments | INSTALLMENT | Plan definition and access-release rules not demonstrated |
| Free | No course fee required | Explicit leaf Paid content, if supported, still follows its documented policy |

SESSION is a leaf purchase scope, not a seventh course payment type in this recording. Do not automatically add it to this course dropdown or confuse these six course billing models with lesson payment types Covered by class fees / Paid / Free.

Question/help icons are visible beside payment choices; their help text is unopened. Provide accurate help derived from our implemented rules, not invented Klass descriptions.

### Enrollment Fee and the owner's free-enrollment rule

The presence of the Enrollment Fee field does not demonstrate that Klass charges it before enrollment. The user's explicit instruction remains authoritative: registered students enroll freely and payment gating occurs at the lesson/session level.

**Required design:** Default Enrollment Fee to zero for the current free-enrollment mode, retain the field for parity/configuration, and label any nonzero legacy value as informational/non-blocking in this mode. Do not create or charge an enrollment order. Reject or ignore attempts to turn a fee into a prerequisite through the API. If the repository has a pre-existing fee-based enrollment mode, preserve its configuration/history without enabling it for this requested workflow; document the mode boundary. Do not quietly reinterpret this field as a facility fee or a lesson fee.

### Additional settings: exact observed order

| Field | Observed form/help | Required implementation and evidence limits |
|---|---|---|
| Payment Valid Days | Numeric input with stepper affordance; no required star | Optional configurable duration; nonnegative integer validation. Start event, scope and meaning of zero/blank are not demonstrated; reuse explicit existing rules and document them |
| Institute Commission | Percentage input; % suffix; helper `Commission percentage for the institute` | Validate 0–100 when set; integrate existing tenant settlement rules, not student fee calculation |
| Whatsapp Invitation Link | Text input with WhatsApp icon | Optional validated invitation URL; student display/access location unseen |
| Telegram Invitation Link | Text input with Telegram icon | Optional validated invitation URL; student display/access location unseen |
| Description | Multiline input | Persist course description; render safely in existing About this course flow |
| Tags | Selector accepting existing/new tags | Support tenant tag selection and creation via commas or Enter, as helper explicitly states |
| Link another course | Checkbox dropdown listing tenant courses | Persist one or more links; student content projection required by helper; permission and billing behavior must remain explicit |

Tags helper: select existing tags or create new ones, separated by commas or Enter. Visible examples include Physics, Combined Maths, bioeve, Biology, 2028 A/L and Chemeve. Tags feed the student catalog chips. Trim labels, deduplicate consistently and scope tags to tenant. Do not hard-code the examples.

Linked-course helper explicitly says that content from linked courses appears in the student portal under this course name. **Implement a content projection/reference, not a copied course/content database tree.** No course is selected in this recording, so inherited billing, transitive linking and source membership rules remain unverified.

**Required design:** Source lesson payment/visibility rules remain authoritative. Displaying a linked lesson does not make it free or auto-enroll the student into the source course. If the displayed parent enrollment is allowed to satisfy navigation enrollment for linked content, use an explicit validated link context; still enforce the source lesson's paid entitlement. Apply the same decision to tree metadata and launch APIs. Reject cross-tenant links, self-links and cycles; deduplicate repeated content by source identity while preserving meaningful grouping. Keep the source Course → Module → Lesson identity and entitlement provenance. Document the policy and include tests so linking cannot become a payment bypass.

Commission must not be added to student checkout as a facility fee. Payment Valid Days must not override resource expiry or change course enrollment lifetime silently. Changing these values must not rewrite settled order snapshots or historical entitlements without an explicit supported migration.

### Implementation steps for Claude Code

1. Extend repository discovery/gap matrix to every field in this section and map existing create/edit models, DTOs, validators and persistence.
2. Implement Add New Course in the existing admin course screen with the observed base and additional fields. Keep QUICK CREATE separate; its form remains unverified.
3. Wire real tenant teacher/tag/course-option loading, cover upload, validation and create mutation. No mock success or local-only record.
4. Validate all references server-side; only authorized tenant course managers can create. Tenant ID must come from authenticated context. Use existing money/commission precision and upload policies.
5. Create transactionally and return the persisted record. Prevent duplicate submit; show bounded loading, success and recoverable errors. Successful creation must refresh Courses and remain after reload. Cancel/X produces no course; clean up uncommitted uploaded cover artifacts according to existing storage policy.
6. Map course billing model to configured offers for the composer while keeping enrollment free. Add necessary conditional price/plan configuration using existing repository rules; mark its fields as our design because this recording does not reveal them. Do not claim hidden forms have exact Klass parity.
7. Make advanced values available to actual tag filtering, safe descriptions, commission configuration and linked-content projection. Do not treat fields that only persist unused strings as fully completed behavior.
8. Preserve existing registration/archive policies; form contains no shown registration toggle, so do not invent a recorded default. Apply and document our existing default and allow normal admin registration management.
9. Add compatible non-destructive migrations for missing fields/associations. Preserve IDs, teacher assignments, covers, prices and historical orders/access; backfill only reliable values.
10. Update all three plan/spec/QA documents with creation status and model/coverage mapping. Continue implementation and validation rather than stop after this plan.

### Additional acceptance criteria

| ID | Scenario | Pass condition |
|---|---|---|
| C01 | ADD COURSE | Correct modal, required fields, advanced toggle, default cover and action buttons |
| C02 | Missing Name/Teacher/Payment Type | Inline errors and server validation; no invalid course persisted |
| C03 | Teacher assignment | Tenant-scoped options; required assignment persists; invalid/cross-tenant IDs denied |
| C04 | Course billing dropdown | Exact six recorded labels mapped to real model; session scope remains leaf-specific |
| C05 | Enrollment Fee zero/nonzero metadata | Registered student still enrolls freely; no enrollment checkout/order |
| C06 | Advanced settings collapse/reopen | Entered values retained and saved accurately |
| C07 | Tags select/create | Comma/Enter creation, deduplication and tenant catalog chips work |
| C08 | Cover change/cancel | Valid upload preview/save; invalid files rejected; cancel creates no course |
| C09 | Commission/payment-valid days | Invalid values rejected; documented semantics; no silent historical repricing |
| C10 | Linked course | Referenced content appears under parent; source identity/payment policy retained |
| C11 | Link bypass attempts | Cross-tenant/self/cyclic links denied; unpaid source lessons cannot launch |
| C12 | Valid save/reload | Persisted course, assignments, fields/cover visible; one create for repeated submit |
| C13 | Create/course visibility → enrollment → unpaid lesson | End-to-end flow consistent with free enrollment and leaf payment gating |

Course-creation evidence confidence: 0.97 for field labels/options, 0.85 for selectors' implied multi-selection, 0.60 for unseen conditional pricing/validity/link access semantics. The instructions above deliberately identify required design choices separately from recorded behavior.

## 4. Available Courses and free enrollment

### Observed UI

- Heading changes between Enrolled Courses and Available Courses.
- Two pills: Enrolled Courses and Available Courses.
- Search courses input; Sort by with Default selected; category/tag chips.
- ICT chip receives blue selected styling and a check; ICT cards are shown.
- Desktop cards show cover, price badge, title, teacher, View course and blue Enroll.
- Selected course: `2027 A/L ICT Theory - Live Online Class`, teacher Indunil Gunawardana, LKR 3500 / month.
- Enroll opens `Are you sure?`, text `Enroll in 2027 A/L ICT Theory - Live Online Class?`, NO and YES buttons, close X.
- Confirmation is followed by a spinner in that card's Enroll button. No payment or approval step appears.
- The course is removed from the available list and later shown among enrolled cards with Open course.

### Implement

1. Require authenticated, registered student context and a tenant-visible, available course. Any such student can enroll; no paid entitlement check is permitted here.
2. Keep View course separate from Enroll. Its pre-enrollment detail behavior is not demonstrated; preserve safe existing preview behavior without exposing protected assets.
3. NO or close X leaves enrollment unchanged. YES calls the enrollment mutation once; disable repeated clicks while pending.
4. Persist enrollment immediately and return the authoritative enrolled state. Refresh available/enrolled queries without a full reload. Preserve sensible search/filter context.
5. Make the operation idempotent and race-safe with a uniqueness rule equivalent to tenant + student + course. Repeated requests must not create duplicates, orders or charges.
6. Use the authenticated student ID, not an arbitrary client-submitted owner. Server checks tenant and course availability.
7. Course registration-disabled/archive rules remain availability checks, not payment checks. Existing enrolled students must not lose paid access merely because registration is closed.
8. Show recoverable enrollment failure and allow retry. Never show a successful enrolled state before the server confirms it.

**Do not:** Generate a zero-value payment order merely to enroll; require a card/slip; mark all lessons paid; require staff approval; unenroll when a month is unpaid; remove the course when checkout fails.

Unenrollment and re-enrollment are not demonstrated. Preserve existing safe behavior and avoid introducing new flows in this task.

## 5. Enrolled course and lesson tree

### Observed UI

Opening ICT Theory shows cover/title/teacher and LKR 3500 / month; tabs Course content, Exams, Live Classes, Tute Delivery, About this course. Course content displays `0 of 7 viewed`, progress bar, `2026 October` folder and Pay now. Expanding it reveals `2027 A/L ICT Theory - Live Zoom Meetings` and lesson rows.

Rows include progress circle, resource icon, title, Starts in green, Expires in red and Pay now. Examples are October 10, 17 and 24, each 12:30 PM–3:00 PM. The student can expand unpaid folders and see safe lesson metadata.

### Implement

- Enrolled students can open the course, read safe metadata, navigate modules, inspect lesson names/schedules and see payment prompts without payment.
- Enforce payment on the leaf lesson resource operation: video/stream/manifest/token issuance, document download, Zoom/meeting join link, external-player handoff and other protected resources.
- A month/module Pay now control is a payment shortcut; it does not replace lesson-level backend authorization.
- Use a single server access evaluator for content rows, live schedule and resource launch. Protected URLs must not be delivered in catalog/tree responses to unauthorized students.
- Keep progress separate from enrollment/payment. Browsing, paying or expanding a folder does not mark a lesson viewed.
- Allow lessons explicitly configured Free without payment. Covered by class fees requires the associated course-period entitlement; Paid requires the configured independent entitlement. These leaf payment types were seen in the earlier course-management recording and remain compatible with this brief.

## 6. Access contract and scope resolution

**Required design:** For each lesson request, resolve tenant, authenticated student, parent course/module, enrollment, publication/visibility, availability window and applicable payment requirement. Return a structured access status without the protected resource when denied. Suitable statuses include ALLOWED, NOT_ENROLLED, PAYMENT_REQUIRED, PAYMENT_PENDING, NOT_STARTED, EXPIRED and UNAVAILABLE; labels and precedence are our design.

Suggested evaluation:
1. Verify tenant, course/module/lesson relationship and student identity.
2. Verify visibility and enrollment for student learning access.
3. Verify resource availability start/end according to existing rules.
4. If Free, allow without a payment entitlement.
5. Otherwise resolve the lesson's explicit billing requirement and check a valid entitlement that actually covers it.
6. Only then resolve/issue the resource response or short-lived handoff token.

Admin/teacher previews may use existing explicit permissions; they must not bypass tenant checks. Revoked/refunded entitlements follow the current reversal policy.

| Payment scope | Scope purchased | Lesson-level result | Evidence limit |
|---|---|---|---|
| Course | Configured course-wide offer | Covers only lessons included by that offer | Tab visible; offer duration/exclusions unseen |
| Monthly | Course + year/month | Covers associated lessons whose policy accepts that monthly entitlement | Two months selected in video |
| Daily | Configured course/date offer | Covers explicitly associated daily lessons | Dropdown shows No data available |
| Module | Selected module offer | Covers module lessons included by the offer | Course dropdown shown; modules not selected |
| Session | Specific lesson/session offer | Covers that leaf only | Course dropdown shown; session list unseen |
| Installment | Configured plan installment | Coverage follows explicit plan milestones/rules | Initial course selector only |

Do not automatically let broad payments override every independent Paid lesson. Store explicit offer coverage/inheritance rules and preserve repository policy; flag undefined coverage instead of silently granting too much. Payment tab names alone do not prove precedence, duration or installment release rules.

**Critical examples:** Paying ICT October must not pay Chemistry October or ICT September. Enrollment alone pays none. A session purchase does not pay sibling sessions. Monthly coverage must not depend solely on `startsAt` month: use an explicit association because content availability can outlast its billed period. Course enrollment may remain active after a paid period ends.

## 7. Global Make Payment dialog

### Observed

Global blue PAY in header opens a large centered `Make Payment` modal, subtitle `Select what you want to pay for and choose how to pay`, close X, dimmed background.

Desktop layout: left selection area about two thirds; right summary/method area about one third. Tabs in order COURSE, MONTHLY, DAILY, MODULE, SESSION, INSTALLMENT. MONTHLY initially selected. Initial right panel: Payment summary, No items selected, Subtotal LKR 0.00, Total LKR 0.00, CANCEL. No enabled payment action is shown for the empty selection.

Content and right panel scroll in the recording. Replicate a usable responsive equivalent with stable header, readable summary and reachable controls; do not deliberately duplicate awkward scroll behavior or a stuck spinner.

### Implement

One payment composer backed by the existing order foundation, not six unrelated checkouts. It should load tenant-configured eligible offers by scope. Course lists are server-provided and tenant-scoped. The video does not prove how non-enrolled courses are handled in PAY; for this implementation restrict student purchase selectors to eligible enrolled courses unless the repository has an explicit, documented alternative. Do not add pay-to-enroll behavior.

Global PAY opens without assuming a lesson. A lesson/month Pay now shortcut should open the same engine with appropriate course/scope/period context, a required usability design not demonstrated in this recording. It must never default silently to today's month when a historical lesson is selected.

## 8. Monthly selector and multiple-item summary

### Observed

Monthly payments → Select a course dropdown includes enrolled Biology, Chemistry, newly enrolled ICT, 2028 Chemistry and Chemistry revision courses. Selecting ICT loads year 2026 with previous/next arrows and a 12-month grid, three columns on desktop. Month cards display abbreviated month and LKR 3,500.00. Selecting September makes it blue; October is also added.

Summary has separate entries:
- ICT Theory — 2026 Sep — Monthly payment — 3,500.00 — remove X.
- ICT Theory — 2026 Oct — Monthly payment — 3,500.00 — remove X.

Subtotal LKR 7,000.00; Facility Fee LKR 200.00; Total LKR 7,200.00. Text: `Includes a Facility Fee set by the institute.` Discount code accordion is visible but not expanded.

### Implement

- Support multiple selected months, persisted in dialog state and represented as distinct order line items.
- Identify a monthly item by course ID + year + month + offer/version, never display title alone.
- Toggle selection without duplicate lines. Remove X deselects matching month and recomputes server quote.
- Support year navigation from configured eligible periods. Do not invent unrestricted future/past purchasing because all month cards were displayed; eligibility comes from server policy.
- Keep a consistent cart when switching selection views; selected items remain explicit in the summary. Cross-course and mixed-scope checkout were not demonstrated; retain only combinations supported by existing payment routing and document any restrictions.
- Already-paid/pending periods need clear indicators and protection against accidental duplicate charges. Their exact styling is not shown, so document our design.
- Monthly prices may vary by period: load configured price per offer, do not use a single course badge as the authoritative checkout amount.
- Quote and create orders server-side using fixed-precision currency amounts. Client submits item identifiers, not trusted totals.
- Persist price, fee, discount, currency, collection-account identity and coverage snapshots for auditability.

### Facility fee

The two-month example is consistent with LKR 100 per selected item, but one example does not establish the universal fee formula. Use our tenant-configured fee policy and reproduce 7,200 in a fixture configured with 100 per month line. Support the existing fee modes; do not hard-code 200 per order or global 100. Calculate discounts and rounding according to a documented rule; the video does not show discount behavior.

The discount accordion must use real existing validation if enabled. Do not accept arbitrary client discount values or show an apply control that silently has no effect. If unsupported, record the gap rather than inventing Klass coupon rules.

## 9. Payment methods and completion

### Observed

Once items are selected, Payment method offers radio choices Pay online and Upload a payment slip. Slip choice displays Deposit to bank/branch, account holder, account number/copy icon, Payment slip input and SUBMIT FOR APPROVAL. With no attachment the approval button appears disabled before the online attempt.

Selecting Pay online hides bank/slip fields and shows PROCEED TO PAYMENT and CANCEL. Proceed is clicked, the action turns into a loading spinner and remains pending during the recording. No gateway success/error/redirect is shown. Switching back to slip and other tabs leaves a spinner visible. This is observed incomplete behavior, not a success path to reproduce.

### Online implementation

1. Require non-empty valid selections and tenant-enabled method; create/reuse an idempotent order/payment attempt with server quote.
2. Route to the collection account defined by existing tenant payment mode. Keep credentials server-side.
3. Launch the configured gateway using existing integration. Show bounded loading and actionable failure/retry; do not leave unrelated controls stuck indefinitely.
4. Treat the browser return as a status-refresh trigger. Grant access only after authoritative verified provider success via existing callback/webhook/verification rules.
5. Check amount, currency, merchant/account, order and authenticity. Reject spoofed, mismatched and unauthorized updates.
6. Process callbacks transactionally and idempotently. A duplicate callback cannot duplicate ledger entries or entitlement grants.
7. Verified paid lines generate the exact covered entitlements. Update affected lesson access and payment summaries without creating a second enrollment.
8. Failure/cancellation leaves free enrollment intact and protected lessons unpaid.

### Manual slip implementation

1. Display configured collection-bank details; never seed production with the recording's bank account number.
2. Require valid attachment using existing allowed formats, MIME/content checks, size policy and private storage. Previous recording indicated image or PDF; reuse that established policy.
3. Submit an order/slip for approval and show a clear pending state. Slip upload is not proof of payment and grants no paid entitlement.
4. Authorized reviewer approves/rejects via existing workflow. Verify covered lines/amount according to current policy; retain reviewer/time/reason audit records.
5. Approval posts ledger/payment allocation and grants exact line coverage transactionally and once. Rejection leaves lesson access unpaid and enrollment unchanged.
6. Repeated submit, network retry or duplicate approval must not duplicate charges, slips or grants. Permit resubmission according to existing workflow with history preserved.
7. Restrict attachment download to owner and authorized reviewers. Do not expose slip URLs in public catalog responses.

Switching method must preserve selected items and quote. Cancelling/closing preserves underlying course navigation. After submitting an order, status is persistent on the server even if the dialog closes. Do not silently cancel a real payment transaction when a modal is dismissed.

## 10. Other payment tabs: implement honestly

- COURSE: tab present; not opened in this recording. Reuse existing course offers; do not claim its selector or access rules were inspected.
- DAILY: Daily payments heading; Select a course opens No data available. Implement a real empty state for no eligible daily offers; no date grid was demonstrated.
- MODULE: Module payments heading; course dropdown contains Biology Theory Catch-up Classes. The module selection screen, prices and purchase are unseen.
- SESSION: Session payments heading; course dropdown lists the five monthly courses plus Biology catch-up. No leaf is selected. The fact a course appears here does not mean every lesson has an independent price.
- INSTALLMENT: Installment payments heading and Select a course. Plans, amounts, due dates and coverage are unseen.

Preserve working existing scope flows. Add adapters for missing scopes only with explicit repository-derived rules and document our design. A visible tab without functional eligible-offer loading, validation and real payment integration is not complete implementation. Do not fabricate installment release rules, module price fields or daily selection screens as observed Klass parity.

## 11. Data, API and migration guidance

Required logical concepts, mapped to existing tables/entities rather than blindly created anew:
- Enrollment: tenant, student, course, enrollment status/timestamps; independent of payment state.
- Billable offer: scope and explicit target/period, configured price/currency, eligibility and coverage policy.
- Order and immutable order lines: selected offers, price/fee/discount snapshots and payment-account routing.
- Payment attempt/manual submission: lifecycle, provider/reference, verified state and audit trail.
- Entitlement/payment allocation: source paid line, student/tenant, exact coverage and validity/reversal state.
- Lesson access policy: Free, Covered by class fees, Paid and explicit coverage association.

Do not store a single course-wide `isPaid` flag as the source of lesson access. Do not duplicate grants for every lesson if the repository already supports scoped entitlement evaluation efficiently. Index and batch checks to avoid one database request per lesson.

Operations needed: available/enrolled lists, idempotent enrollment, scoped content metadata, lesson authorization/launch, eligible offers by tab/course, quote, order/payment initiation, slip submission/review, order status and payment completion verification. Use current API conventions; these are operation requirements, not discovered Klass endpoint paths.

Migration must preserve enrollments, history and access already purchased. Remove only the inappropriate payment prerequisite from enrollment. Backfill coverage from reliable existing payment references; report ambiguous records for review instead of opening all content or dropping paid access. Run migration on a representative copy and document before/after counts. Existing unpublished/registration-disabled course policies remain intact.

## 12. Implementation sequence

1. Inspect repository and produce evidence/gap/model mapping; update plan.
2. Implement course creation and settings from section 3A, with compatible migrations and real persistence.
3. Separate enrollment from payment in backend and migration, preserving history.
4. Implement Available → confirmation → immediate enrollment → Enrolled flow.
5. Centralize lesson access and protect every resource launch/download path.
6. Implement global composer and demonstrated monthly multi-selection/quote/summary.
7. Wire online and manual approval paths to existing central payments/ledger.
8. Integrate supported other scopes; document unverified/blocked capabilities.
9. Run focused tests and student/admin review walkthrough; update all three documents.

Continue through implementation. Do not deploy or publish unless separately authorized. Do not replace unrelated course-management screens or media-provider architecture.

## 13. Acceptance checks

| ID | Test | Expected result |
|---|---|---|
| E01 | Registered student confirms enrollment without payment | Enrollment immediate; zero payment orders/charges; course under Enrolled |
| E02 | NO/X in confirmation | No enrollment mutation |
| E03 | Double click/concurrent enrollment | One enrollment record; consistent response |
| E04 | Registration closed/unavailable course | New enrollment denied by availability rule, not fee rule |
| E05 | Failed/expired/rejected payment | Enrollment remains; protected unpaid lessons remain locked |
| L01 | Enrolled unpaid student browses modules | Metadata visible; protected assets absent |
| L02 | Direct URL/API manipulation for unpaid lesson | Backend denies download/join/token/stream access |
| L03 | Free lesson in otherwise unpaid course | Accessible when visible/available and enrolled |
| L04 | Paid ICT October | Only explicitly covered ICT October lessons unlock |
| L05 | Paid September + availability through later month | Coverage and expiry remain separate |
| L06 | Individual session purchase | Does not unlock sibling lessons unless offer explicitly covers them |
| M01 | Empty payment composer | Zero totals; payment action unavailable |
| M02 | Select ICT September and October | Two distinct lines; fixture subtotal 7000, fees 200, total 7200 |
| M03 | Remove/toggle item | Correct deselection, quote and fee recalculation; no duplicate lines |
| M04 | Client alters price or target IDs | Server rejects/recomputes; no unauthorized offer purchase |
| M05 | Change year/course/tab/method | Selection/summary consistent; incompatible combinations clearly prevented |
| P01 | Start online checkout only | No entitlement until verified success |
| P02 | Verified success and duplicate callbacks | Exact grants/ledger allocations once; no duplicate enrollment |
| P03 | Provider timeout/cancel/failure | Recoverable UI; no indefinite stuck submission state |
| P04 | Slip uploaded | Pending approval; no paid access |
| P05 | Authorized approval/rejection | Approval grants exact coverage once; rejection grants none |
| P06 | Wrong tenant/user/role or slip ownership | Request denied server-side |
| P07 | Already-paid/pending item selected | Prevent duplicate charging according to documented policy |
| S01 | No daily offers | Honest No data available state |
| S02 | Unsupported scope/provider | Explicit gap; no fake successful payment |
| D01 | Migration with existing history | Enrollment/order/ledger/access preserved; ambiguous coverage reported |

Use existing test frameworks and meaningful integration tests for enrollment idempotency, lesson enforcement, tenant isolation, quote math, webhook replay and manual approval. Verify UI navigation with the app's available browser test tooling. Capture screenshots of available confirmation, newly enrolled unpaid course, monthly two-line summary, slip pending and approved-access states. Report actual commands/results and environment limitations.

## 14. Completion report and caveats

Claude Code must report changed files/migrations, completed/partial/blocked acceptance checks, tests actually executed, screenshots and outstanding scope rules. Distinguish production integration from stub/demo support. Do not claim exact hidden Klass behavior.

Confidence: 0.97 for observed enrollment and monthly-selector labels; 0.95 for the owner-specified free-enrollment/lesson-access model; 0.65 for unseen coverage semantics. Successful online settlement, slip approval, refunds, discounts, cross-course mixed-scope checkout and detailed Course/Daily/Module/Session/Installment offers are not demonstrated. Required security/payment completion behavior in this document is our implementation specification, not reverse-engineered Klass internals.