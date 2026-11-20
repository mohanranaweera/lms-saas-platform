# TUTE-01 Tute Management & Dispatch — acceptance

Branch `feat/tute-management-dispatch` · 2026-11-09 · local stack only (backend `local` profile on
`lms_dev`, frontend :3000). Synthetic data only; no Klass demo records were read or changed.
Spec: `docs/specs/tute-management-dispatch.md`. Evidence: `docs/qa/tute-management-evidence/`
(phone numbers masked in text evidence; label PNGs show synthetic numbers).

## Automated checks

| Suite | Result |
|---|---|
| Backend tute unit + integration (`Tute*Test`, `DispatchProjectionTest`) incl. `TuteSecurityIntegrationTest` | pass (unit 13, integration 10) |
| Permission/catalog/matrix (`PermissionCheckServiceImplTest`, `CapabilitySemanticsTest`, `TuteCapabilityCatalogTest`, `RolePermissionMatrixIntegrationTest`) | pass |
| Full `mvnw verify` | 3402 run, 4 failures — all in `TeacherManagementIntegrationTest`, **pre-existing** (same 4 fail on `main`) |
| Frontend Playwright mocked (`tute-management`, `tute-dispatch`, `unit/tute-dispatch-selection`) | 36/36 |
| Live API harness `e2e/tute-live-api.spec.ts` (run `mw9106ui`) | 131 PASS, 0 FAIL, 2 BLOCKED |
| Live UI `e2e/tute-live.spec.ts` | 4/4 |
| `tsc --noEmit` / `npm run lint` | only the pre-existing errors in untouched files |

Rerun live (local stack only): from `frontend/`,
`TUTE_LIVE=1 npx playwright test e2e/tute-live-api.spec.ts --workers=1` then
`TUTE_LIVE=1 npx playwright test e2e/tute-live.spec.ts --workers=1`.

## Requirements

| # | Area | Result | Evidence |
|---|---|---|---|
| A1 | Create/reopen/edit each payment mode; validation | PASS | Paid without price → 400; price on Free/Covered → 400 `TUTE_PRICE_NOT_ALLOWED`; bad quantities → 400; cross-course / tenant-B / unknown session → 400 `TUTE_SESSION_NOT_ELIGIBLE`; stale version and quantity below allocated → 409 |
| A2 | Manual assign/unroll, stock, concurrency | PASS | 8 parallel assigns at stock 3 → 3×200, 5×409, 3 rows; same student ×6 → 1 row; unroll needs reason and releases stock |
| A3 | Automatic triggers | PASS, 2 BLOCKED | Free checkout event, approved slip + Sync, paid-session LESSON purchase, manual receipt with postage, mixed C1+C2 order, Free Card (no assign), refund hold + reasoned release. **BLOCKED:** online gateway (no PayHere account / webhook secret locally) and PayHere chargeback `-3` notify — covered by backend integration tests instead |
| A4 | Dispatch queue | PASS | Two addresses → two rows; missing address flagged; search name/index/city/Sinhala; filters narrow rows and chips; 2+ counted before paging; 4 sort keys both directions; teacher scope |
| A5 | Labels | PASS | Selected 4 → `X-Label-Count` 4, 1 page; all-filtered 11 → 2 pages; text has names, "No address provided", Tel, course – tute, From, counts, Sinhala + Tamil; stale → 409; printing twice changes nothing |
| A6 | Confirm dispatch | PASS | Missing address → 422 and nothing dispatched; valid → DISPATCHED once; address snapshot survives a later profile edit; repeat → `alreadyDispatched` |
| A7 | Staff grant/revoke, scope, tenants | PASS | Operator via UI: view-only hides Print/Confirm and API → 403; grant print → 200; revoke → next request 403 on the same session; Auditor print grant → 400; Teacher default 403, own course only after grant, other teacher 403, tenant B 404; tenant B admin on A ids → 404 ×8. Matrix restored to baseline |
| A8 | Staff Activities | PASS | All tute actions present; no address/city/phone in details |
| A9 | Visual (1440×900, 390×844) + PDFs | PASS | No page-level horizontal overflow; A4 portrait 2×3 labels, none clipped or split; long text wraps/shrinks; Sinhala/Tamil render |

## Accepted deviations / observations

- "No address provided" prints red and upright (the bundled Noto font set has no italic face).
- Zero course postage with postage selected at checkout yields POSTAL (spec §5: the fee never decides the method).
- A teacher filtering the queue by another teacher's course id gets an empty page (filter, not a fetch by id).
- Tables scroll horizontally inside their card on phones; the page itself does not.
- Paid-tute checkout item (tute as its own priced cart line) is not built — needs approval (change-controlled checkout contract).

## Synthetic data left in `lms_dev`

`TQ *` / `Tute QA probe*` courses (25, 3 in tenant B), 26 `TQ *` tutes, 50 fulfillments, 34
`tq-*@example.test` users, related synthetic orders/slips/receipts/refunds/free cards, and extra
device registrations for the demo tenant admin. Nothing was deleted.
