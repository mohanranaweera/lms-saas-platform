# Unpaid Students Report

Status: implemented (RPT-06).

## Definition

Report starts with current `course_participation` rows for one authorized course as of generation. It does not infer eligibility from registered users, attendance, payment access, enrollment history, or Free Cards. Participation is free and remains independent from payment.

Selected month means purchased billing month (`YYYY-MM`), not payment received date. Student is paid only when one or more immutable monthly order-line snapshots for this exact student/course/month have full signed cash coverage from confirmed ledger allocations. Required amount is stored line total minus stored facility fee; discounts remain in snapshot. Gateway, approved bank slip, and authorized manual receipt count. Pending/rejected/failed attempts, Free Card, FREE/STAFF_GRANTED, other course/month, facility/postage-only amounts, partial coverage, and reversed coverage do not satisfy the requirement. A reversal restores unpaid status when no other qualifying coverage remains. Current course pricing is never used.

One monthly order is considered covered when its selected monthly line/course amount is fully covered. This avoids double-counting duplicate/retried orders while preserving separate monthly obligations if a student has more than one order.

## Screen contract

Filter panel: required single Course, `At least one attended` switch default off, single purchased billing month with year navigation, Search and Cancel. Result panel: selected month heading, course badge, complete unpaid count, Export PDF, table columns `#`, `Index no.`, `Student`, `Email`, `Mobile`, and authorized Student Details action. Missing contact values display `—`; index numbers remain strings.

Attendance switch applies after unpaid qualification. On means at least one authoritative `PRESENT` or `LATE` fact in selected course/month, tenant-local date. Facts deduplicate by record id and then count distinct attendance sheet/session; records without sheet id use distinct local date. Absence, duplicate joins, enrollment, page views, and video views do not count.

## PDF contract

Jasper PDF uses same immutable snapshot as preview. Header shows title, institute, course, purchased month, explicit `At Least One Attended: Yes/No`, timezone timestamp; KPI cards show unpaid count and course. Table shows `#`, `INDEX`, `STUDENT NAME`, `MOBILE`, `ATTENDANCE`, with repeated headers, page numbers, Unicode-safe font, long-name scaling, empty-state copy, and footer. PDF exports complete filtered result, not current page.

## Security and limits

All options/run/rows/export endpoints require server-side `FINANCE_EXPENSES/VIEW` authorization, trusted tenant context, owner-bound short-lived Redis snapshots, parameterized tenant-scoped cross-module reads, bounded participant/ledger/attendance/rows/PDF workloads, and `Cache-Control: no-store`. Teachers have no flat finance grant and are denied; future teacher-facing access requires a separately approved contract. Report execution writes only ephemeral snapshot/rate-limit state and does not mutate payment, ledger, order, enrollment, attendance, entitlement, participation, or Free Card records.

Known limitation: historical participation reconstruction is unavailable; report explicitly uses current participation as of generation. Legacy line-less course orders, module offers, and undated/session offers have no explicit selected billing month and cannot qualify this monthly report.
