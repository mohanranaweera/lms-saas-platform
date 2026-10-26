# Undo Payments Report — Contract (RPT-09)

Status: implemented in working tree.

## Scope and source

Read-only tenant report of effective rows in append-only `payment_refund`, joined through the original payment/order and `payment_refund` audit event. Pending, failed, cancelled attempts and non-refund events are excluded. Chargebacks use `System` actor placeholder because gateway callbacks have no authenticated human actor.

Refund timestamp is `payment_refund.created_at`. Date filters use tenant timezone with inclusive selected start and exclusive next-day end. Original payment timestamp is shown independently as Payment Date.

## Row grain and amounts

One screen/PDF row represents one effective refund/course allocation. Legacy single-course orders produce one row. Line orders allocate refund magnitude across immutable order lines pro rata; amounts display positive magnitudes. `distinctUndoPayments` counts distinct refund IDs. `rowCount` equals table rows. Total amount sums displayed row amounts with `BigDecimal`.

Missing student, actor, reason, or course data renders `Unknown student`, `Unknown actor`/`System`, `—`, or `Unknown course`. Student indexes remain strings.

## API

`/api/v1/reports/undo-payments` requires authenticated tenant user with `FINANCE_EXPENSES/VIEW`.

- `GET /options`: tenant courses.
- `POST /runs`: `{ courseIds, startDate, endDate }`; empty `courseIds` means all courses.
- `GET /runs/{runId}` and `/rows`: owner-bound short-lived snapshot.
- `GET /runs/{runId}/pdf`: PDF generated from same snapshot.

Runs do not mutate payment, refund, ledger, enrollment, or entitlement records.

## Layout

Screen: `#`, Student, Course, Undo By, Undo Date, Amount (LKR), Payment Date.

PDF: `#`, Student, Index, Course, Reason, Date (Undo Date), Amount (LKR), with institute, date range, generated timestamp, totals and footer.
