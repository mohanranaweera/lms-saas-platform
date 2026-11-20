# Klass Manual Payment & Free Card — acceptance evidence

Plan / decisions: `docs/plans/klass-manual-payment-free-card.md`. Spec: `docs/specs/klass-manual-payment-free-card.md`.
Backend integration tests (PostgreSQL via Testcontainers, real security/JWT, fresh tenants per test):
`backend/src/test/java/com/lms/klass/manualpayment/ManualPaymentIntegrationTest.java` (11),
`FreeCardIntegrationTest.java` (9), shared fixture `KlassManualPaymentTestSupport.java`.
Frontend: `frontend/e2e/klass-manual-payment-free-card.spec.ts` (mocked API, UI states and request discipline).

## Results by acceptance ID

| ID | Requirement | Evidence | Result |
|---|---|---|---|
| A | Preselected student/course loads scoped page | IT: payment-context, offers, history scoped to the student; second student sees none. E2E: preselected query params load the scoped page; unknown student id reported, never trusted; changing student resets course and form | Pass |
| B | Cross-tenant / unauthorized denied server-side | IT: tenant-B staff → 404 for tenant-A student id on all 5 staff endpoints, course id (offers, quote, record item, free-card issue), card id (revoke), list filter; lists never contain foreign rows; nothing written | Pass |
| C | Confirmed receipt persists exact allocation | IT: one CONFIRMED `MANUAL-` payment, one PAYMENT_CONFIRMED ledger row, receipt, order lines, entitlements with `source_payment_id`; lesson ALLOWED; SQL UPDATE/DELETE on receipt rejected | Pass |
| D | Double submit / retry no duplicates | IT: identical replay → 200 same receipt, nothing new; changed details or other student → 409 `IDEMPOTENCY_KEY_REUSED`; two-thread race → one 201, one `ITEM_ALREADY_PAID`, one payment | Pass |
| E | Unverified slip stays pending | IT: pending slip → staff quote/record 422 `ITEM_PENDING`, no writes, slip unchanged | Pass |
| F | Under/overpayment explicit policy | IT: `AMOUNT_MISMATCH` 409 persists nothing (order included); adjustment reason required; adjustment ≥ total refused; future / > 365-day-old date refused; valid adjustment → net on payment+ledger, full on order+receipt | Pass |
| G | Free Card = scoped access, no cash | IT: no order/payment/ledger rows; covered month ALLOWED; PAID lesson and other months still require payment | Pass |
| H | Future/active/expired/revoked | IT: future = SCHEDULED, no access; revoked = no access; expired (SQL-inserted) = EXPIRED, no access; invalid windows incl. start before current month → 422 | Pass |
| I | Overlap does not multiply access | IT: overlap → 409 `FREE_CARD_OVERLAP`; replay → same card; reissue after revoke allowed | Pass |
| J | Revocation keeps paid access | IT: paid + carded month, revoke card → still ALLOWED, entitlement row untouched | Pass |
| K | Student cannot issue/record | IT: student/teacher 403 on all staff endpoints | Pass |
| L | Record ≠ issue permission | IT: Finance Staff records (201) but 403 on issue/revoke; unit: permission matrix + MockMvc | Pass |
| M | Direct resource requests agree | IT: lesson launch endpoint agrees with content tree before/after revoke (shared evaluator for materials, playback, player, live join — security review) | Pass |
| N | Linked content not expanded | IT: card on parent course does not cover source-course lessons | Pass |
| O | Fees/discounts/postage separate | IT: PER_LINE fee + postage → expected 6400 = quote total; adjustment + postage → 422 `ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED`; free card writes no fee/postage rows | Pass |
| P | Reversal/revocation keep history | IT: refund adds REFUND ledger row, payment/receipt/original ledger unchanged; revoked card keeps row, SQL DELETE rejected; audit rows `free_card.issued`, `free_card.revoked`, `manual_payment.recorded` (payment + student profile) | Pass |
| Q | Frontend failure/reload no false success / duplicates | E2E: double-click sends one POST; unchanged retry reuses the idempotency key, edited form sends a new key; QUOTE_CHANGED forces re-confirmation; AMOUNT_MISMATCH / ITEM_PENDING / overlap show errors and no saved state; success only rendered from a 200/201 body. Backend replay/race tests (D) cover reload/retry | Pass |
| R | Free enrollment + multi-course checkout unaffected | IT: self-enroll + student quote/order; existing `MultiCourseCheckoutIntegrationTest`, `CheckoutComposerEntitlementIntegrationTest` green | Pass |

Additional: non-participant card → STAFF_GRANT participation + audit; registration closed → 409; non-MONTHLY course → 409; HALF → 422.

## Commands

| Command (from `backend/`) | Result |
|---|---|
| `./mvnw.cmd verify -Dtest='ManualPaymentIntegrationTest,FreeCardIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` | 20 tests, 0 failures |
| `./mvnw.cmd test -Dtest='FreeCardTest,FreeCardServiceTest,LessonAccessServiceTest,ManualPaymentServiceTest,ManualPaymentAndFreeCardPermissionWebTest,CheckoutComposerStaffPermissionTest,PermissionCheckServiceImplTest,EntitlementGrantServiceTest,CheckoutDiscountInvariantTest,SlipUploadServiceTest'` | 309 tests, 0 failures |
| `./mvnw.cmd verify -Dtest='FlywayMigrationIntegrationTest,KlassRolesAndPaymentOwnershipAcceptanceIntegrationTest,KlassLessonAccessAcceptanceIntegrationTest,CheckoutComposerEntitlementIntegrationTest,MultiCourseCheckoutIntegrationTest'` | 39 tests, 0 failures |
| `./mvnw.cmd verify` (full, final, after review fixes) | 2862 tests, 0 failures, BUILD SUCCESS (Surefire printed a fork-JVM shutdown notice after exit; no effect on results) |

| Command (from `frontend/`) | Result |
|---|---|
| `npm run lint` | 0 errors (3 pre-existing warnings in untouched files) |
| `npx tsc --noEmit` | exit 0 |
| `npx playwright test e2e/klass-manual-payment-free-card.spec.ts` | 16 passed (also 32/32 with `--repeat-each=2`) |
| `npx playwright test e2e/discounts-admin.spec.ts e2e/klass-discount-checkout.spec.ts e2e/tenant-admin-nav.spec.ts e2e/student-detail-tabs.spec.ts` | 46 passed |

## Reviews

- payment-ledger-specialist: no Critical/High. Medium: adjustment vs postage pro-rata (resolved by refusing the combination; owner decision O-MP1 open); skipped-grant rollback (fixed). Low: grant restricted to `MANUAL-` payments (fixed), replay hardening (fixed), ledger course-summary shows order value for adjusted orders (documented), `collected_by` NULL (note O-MP2), staff warning for card-covered months (UI).
- security-reviewer: no Critical. High: missing cross-tenant tests (added, B). Medium: staff composer/grant methods self-check permission (fixed). Low: receivedOn floor, validFrom floor, replay strictness (fixed); suspended students not refused (decision).

## Known limitations

- Not built: HALF cards, Temporary Credit, Registration/Enrollment fee "Mark as paid", receipt printing.
- Staff refunds do not revoke access (O-2 unchanged).
- Some cross-tenant assertions accept 404 or 422 (foreign `postageCourseIds`) / 200-empty or 404 (foreign course filter on lists); no data leaks in either case.
- Staff names shown as email (no staff display-name API).
- "Reverse (refund)" links to the refunds page with the payment id shown (the page cannot preselect a payment).
- Free Cards list has no Mobile/Email columns (not in the API response).
- E2E runs against a mocked API; server rules are proven by the backend integration tests.
