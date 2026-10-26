# Student Behavior Report

Status: implemented (RPT-08).

## Reference contract

Sources inspected once: `docs/references/klass-reporting-module/Student Behaviour Report.png` and `Student Behavior Report.pdf`.

Filter shows one required course and one selected month. Calendar has blue `SELECT MONTH` header with full month/year, independent displayed-year navigation, JAN–DEC in three columns/four rows, one wide blue selected pill, and explicit CANCEL / SEARCH actions. Browsing another year does not change selected or applied month.

Preview shows generated date, applied course/month badges, Export PDF, and separate New, Dropped and Continuing sections with complete counts. Screen columns are `#`, `Index no.`, `Student`, `Email`, `Mobile`, and authorized Student Details. PDF shows institute/course/month/generated metadata, three KPI cards, the three sections, and columns `#`, `INDEX`, `STUDENT NAME`, `MOBILE`. Empty categories have explicit text. The reference fixture counts 122 / 0 / 3 illustrate reconciliation only and are not application data.

## Classification contract

Population basis is confirmed cash coverage for the exact course and purchased billing month, not enrollment, attendance, receipt date, or current course state.

For selected month `M`:

- `C` = distinct student UUIDs with one or more exact `MONTHLY` order-line obligations for the selected course/month that are fully covered by signed ledger allocations.
- `P` = same set for `M.minusMonths(1)`; January compares previous December.
- `New = C - P`.
- `Dropped = P - C`.
- `Continuing = C intersect P`.

A student returning after a gap is New because only adjacent months are compared. Membership uses student UUIDs, so identical names remain distinct. Multiple orders, payment retries and duplicate fact reads do not inflate membership.

Gateway, approved bank slip and authorized manual receipt coverage count. Pending/failed/rejected attempts, FREE/STAFF_GRANTED, Free Cards, other course/month lines, facility/postage-only amounts and partial coverage do not count. Refund/reversal allocations reduce coverage; another qualifying fully-covered obligation may still keep the student in the set. Required amount uses immutable order-line snapshot course share, never current pricing. Payment receipt date does not move purchased-month coverage.

## History and as-of policy

Append-only order-line and ledger facts provide historical billing-month coverage. Report carries selected/comparison months, basis, historical-availability status and generation timestamp. Missing comparison history is not represented as zero and must not fabricate an all-New result. A selected current month is provisional through generation time; Dropped is not presented as final while that month remains incomplete.

No classification action enrolls/unenrolls, grants/revokes access, creates charges, changes payments, or sends notifications.

## API and security

Base: `/api/v1/reports/student-behavior`.

- `GET /options` — authorized tenant courses.
- `POST /runs` — `{ courseId, billingMonth: "YYYY-MM" }`; returns immutable report header/counts/metadata.
- `GET /runs/{runId}` — same stored run header.
- `GET /runs/{runId}/rows` — complete/paged category rows according to implemented contract.
- `GET /runs/{runId}/pdf` — all category rows from same stored run.

Every endpoint requires authenticated report-view permission. Tenant comes from trusted `TenantContext`; no request tenant ID is accepted. Teacher access, where enabled by existing report policy, is restricted to assigned courses. Runs are short-lived Redis snapshots bound to tenant and user. Responses and downloads use `Cache-Control: no-store`; exports have no permanent public URL. Workloads are bounded and batched. Report execution writes only ephemeral run/rate-limit state.

## Naming and limitations

UI uses project spelling `Student Behavior Report`; supplied screenshot filename and PDF title retain their original `Behaviour` spelling. Live Klass calendar interaction was not verified; explicit Search follows supplied evidence. No database migration is required.
