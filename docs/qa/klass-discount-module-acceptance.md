# Klass Discount Module — Acceptance (DISC-01)

Status legend: PASS (executed, green) · FAIL · NOT RUN. Only executed results are marked PASS.

| # | Acceptance case | Test | Status |
|---|---|---|---|
| A1 | Admin rules persist and survive reload (all four types) | backend IT + Playwright | NOT RUN |
| A2 | Selection modes All/Any/Min count + conditional fields | IT + unit + Playwright | NOT RUN |
| A3 | Cross-tenant rule ids (404) and course ids (422) rejected | IT | NOT RUN |
| A4 | Advance boundary: current month not advance, next month advance, paid/pending excluded | unit + IT | NOT RUN |
| A5 | Bundle uses distinct courses (2 months of one course ≠ 2 courses) | unit | NOT RUN |
| A6 | Removing a prerequisite removes discount/reward with a notice | unit + IT | NOT RUN |
| A7 | Free reward covers only configured months; no lifetime/global free | unit + IT | NOT RUN |
| A8 | No recursive/cyclic rewards, no duplicate reward lines | unit | NOT RUN |
| A9 | Code valid/invalid (incl. expired/disabled/not-yet-valid, shown as INVALID)/ineligible/usage-limit/better-discount | IT | NOT RUN |
| A10 | Concurrent orders never exceed Maximum Uses; retries/replays consume once | IT | NOT RUN |
| A11 | Deterministic combination and rounding; allocations sum exactly | unit | NOT RUN |
| A12 | No negative totals / over-discount; zero total blocked | unit + IT | NOT RUN |
| A13 | Facility-fee inheritance, zero override and postage unchanged | IT | NOT RUN |
| A14 | Multi-course selection survives code/course changes | Playwright | NOT RUN |
| A15 | Online/manual completion uses the snapshotted discounted amount | IT | NOT RUN |
| A16 | Pending/rejected payments grant no paid access | IT | NOT RUN |
| A17 | Historical orders unchanged after rule edit/disable/archive; refund uses original | IT | NOT RUN |
| A18 | Student vs admin permission boundaries | IT | NOT RUN |

## Results log

_Filled in with actual commands and outcomes as checks run._

### 2026-10-21 — backend regression (targeted)

`backend/`: `./mvnw -B -q test -Dtest='*Discount*,*Checkout*,PermissionCheckServiceImplTest,PaymentConfirmationServiceTest,SlipReviewServiceTest'`
→ exit 0; 19 test classes, 362 tests, 0 failures, 0 errors, 0 skipped (Testcontainers PostgreSQL).
Covers the backend side of A1-A13 and A15-A18 as implemented in those classes; the full backend
suite was not run in this pass, so the table above keeps NOT RUN until each case is mapped to a test.

### 2026-10-21 — frontend (mocked API, no backend)

`frontend/`: `npx playwright test e2e/discounts-admin.spec.ts e2e/klass-discount-checkout.spec.ts e2e/unit/composer-model.spec.ts e2e/klass-multi-course-checkout.spec.ts`
→ 30 passed (plus 2 added composer-model unit tests: 9/9 passed). `npx tsc --noEmit` clean;
`npm run lint` 0 errors (3 pre-existing warnings in unrelated files); `npm run build` succeeds.

UI evidence (mocked server responses - UI contract only, rules proven by the backend):
- A1/A2 (UI): create per type with conditional fields (code, free courses + months, value kind),
  client validation, server 422 field errors and 409 `DISCOUNT_CODE_TAKEN` mapped onto fields, edit
  locks the type and PUTs a new version, toggle sends only `{active}`, delete handles the 204.
- A9 (UI): APPLIED / INVALID / USAGE_LIMIT_REACHED statuses shown from `codeResult`; 429 rate limit
  explained without a retry loop.
- A12 (UI): `ORDER_TOTAL_ZERO` hides the payment methods and shows the server message.
- A14 (partial): the code and accepted offers stay in the cart when items change (unit test); the
  existing multi-course checkout spec still passes with the new cart fields.
- A18 (UI): Read-only Auditor sees masked codes and no mutating controls; a 403 renders the
  permission-denied state; roles without `DISCOUNTS` get no nav entry; an unknown/foreign id renders
  the error state, not a form.
