# PAY-HUB-01 — Payments Hub (Klass parity)

Status: **Draft — awaiting review**. Date: 2026-10-29.
Reference screenshots: `docs/references/klass-payment-module/`.

Decisions taken with the product owner (2026-10-29):

- "All Orders" shows **one row per order** (new tenant-scoped order list), not one row per ledger entry.
- "Free Cards" tab = the Student Payments workspace **plus** the existing all-free-cards list as a sub-view.
- Klass "Add Note" is in scope as **append-only slip notes** (new table).
- Work stays on branch `feat/tenant-appearance-public-home` (no new branch).

## 1. Business goal

Payment work is currently spread over five finance-nav pages (Payments, Refunds, Payment Slips,
Student Payments, Free Cards). Staff have to jump between them to answer "has this order been paid?"
and to clear the slip queue. PAY-HUB-01 puts everything into one **Payments** page with four tabs,
modelled on Klass:

| Tab | Source today | Change |
|---|---|---|
| **All Orders** (default) | `payments/dashboard` "Dashboard" tab (ledger rows) | One row per order; search by order number + order date; status/method filters kept; "View order" drawer with products, fees, postage, delivery. "Outstanding" and "Course summary" tabs removed. |
| **Payment Slips** | `payments/slip-review` + `slip-review/[slipId]` | Klass "Pending" grid: pending slips by default, student name + contact, price/discount/to-pay, slip viewer modal, inline approve / decline / note, row expander, search. |
| **Free Cards** | `payments/students` ("Student Payments") + `payments/free-cards` | Renamed to Free Cards; the workspace and the all-cards list are both in this tab. |
| **Refunds** | `payments/refunds` | Moved into the tab; its order drawer becomes the shared one, so it shows postage too. |

Approval, rejection, refund, manual payment and free-card **rules are unchanged**. This module
regroups the UI, adds read/search endpoints and adds append-only notes.

## 2. User roles

| Role | Access (enforced by backend; nav/tab visibility is UX only) |
|---|---|
| Tenant Admin | All four tabs; approve/reject slips; add notes; refunds; record payments; issue/revoke free cards. |
| Finance Staff | Same as today: All Orders, Payment Slips (review), Refunds, Free Cards per their `FREE_CARDS` grants. |
| Student Support / Read-only Auditor | All Orders + Payment Slips read-only (`PAYMENTS_SLIPS/VIEW`); no approve/reject/note buttons; Free Cards read-only where `FREE_CARDS/VIEW`. |
| Teacher, Student | No access (403). Students never see staff slip notes. |
| Platform Admin | Unchanged (`platform-admin/payments` is out of scope). |

## 3. Acceptance criteria

### Page shell
- AC-1 `/tenant-admin/payments` renders "Payments" with tabs **All Orders · Payment Slips · Free Cards · Refunds**. The tab is in `?tab=` (`orders` default, `slips`, `free-cards`, `refunds`). An unknown value falls back to `orders`.
- AC-2 A tab is shown only when the role helper allows it (All Orders and Payment Slips: `canViewPaymentDashboard`, same as today's nav; Free Cards: `canViewStudentPayments || canViewFreeCards`; Refunds: `canProcessRefunds`). If no tab is allowed, the page shows the permission-denied state.
- AC-3 Header card in Klass style: "N pending — Payment slips awaiting review", using the pending slip count (`SUBMITTED` + `UNDER_REVIEW`). The Payment Slips tab label shows the same count as a badge.
- AC-4 Old routes `payments/dashboard`, `payments/slip-review`, `payments/students`, `payments/free-cards` and `payments/refunds` redirect to the matching tab and keep their query params (e.g. `?studentId=&courseId=`). `payments/slip-review/[slipId]` still works as a deep link, and its back link points to `?tab=slips`.
- AC-5 The finance nav shows a single "Payments" entry in place of Payments / Refunds / Payment Slips / Student Payments / Free Cards. "Discounts" and "Reactivation Approvals" stay where they are.

### All Orders
- AC-6 The grid shows one row per order in the caller's tenant, newest first: order number (`ORD-…` display), order date, student name (link to student), items summary (first course + "+N more"), total, payment status badge (`PaymentOperationalState`), method, and a **View order** action.
- AC-7 The order-number search accepts `ORD-000123`, `000123` or `123` and finds an exact match. Partial matches are not required.
- AC-8 The order-date filter takes From/To dates (inclusive), interpreted in the tenant's `GENERAL.default_timezone` config (default UTC). From > To gives an inline validation error and no request.
- AC-9 Status and Method filters keep today's options and combine with search using AND. "Clear" resets all four.
- AC-10 **View order** opens a right-side drawer: order number, date, student, status, method, payment/reference, a product line list (course, label, scope, unit, facility fee, discount, line total), subtotal, facility fee, **postage**, discount, total, refunded and remaining, plus the delivery address when postage was selected. A legacy order with no lines shows the "Legacy single-product order" note and still shows its totals.
- AC-11 Empty states: no orders ever → "No orders yet". A filtered or later page with no results → inline empty state with "Reset filters"; the filters stay visible.

### Payment Slips
- AC-12 The tab loads **pending** slips by default (`SUBMITTED` + `UNDER_REVIEW`). A status selector (Pending / Approved / Rejected) gives Klass's Completed/Rejected views without extra top-level tabs.
- AC-13 Grid columns: **Student name** (link to the student page), **Contact** (mobile, else email), Order no., Price, Discount, To pay (bold), Payment Slip (info icon, opens viewer), Flags, Actions (Approve ✓, Decline 🗑, Add note ✎), and an expander (▾).
- AC-14 One search box matches student name, email, mobile, slip reference and order number (case-insensitive contains; exact match for the order number). Search is debounced (300 ms), runs on the server, and resets to page 1.
- AC-15 The slip viewer modal shows the slip **inline**: an image renders in the modal; a PDF renders in an embedded viewer with a fallback link. It also shows the read-only reference number and method "Bank transfer", the flag badges, and the buttons **Approve · Reject · Close · Download**. The image comes from a fresh short-lived signed URL, fetched each time the modal opens and never stored.
- AC-16 Approve (from the grid or the viewer): if the slip has no flags, a confirm and done. If it has flags, a mandatory override-reason field (existing rule; the backend rejects a missing reason). On success the row leaves the pending list, the count goes down and a toast is announced.
- AC-17 Decline requires a reason (existing reject endpoint). It never auto-rejects. The row leaves the pending list.
- AC-18 Approve/Decline are shown only when `canReviewSlips(role)` and status is `UNDER_REVIEW`. A `SUBMITTED` row (duplicate check still running) shows the actions disabled with the tooltip "Duplicate check in progress". A server 403/409 is shown inline, never swallowed.
- AC-19 Add note (Tenant Admin and Finance Staff only, `PAYMENTS_SLIPS/CREATE_EDIT`): a dialog with a textarea of up to 1000 chars (required, trimmed). After saving, the note shows in the expander and in the viewer with author and time. Notes cannot be edited or deleted, and students never see them.
- AC-20 The expander shows order lines, the fee/postage breakdown, delivery address, flag history and notes (reusing `SlipOrderBreakdown`).
- AC-21 Pagination with 10 / 20 / 50 items per page and "x–y of N", Klass-style.

### Free Cards
- AC-22 The tab is headed "Free Cards". It has the Student Payments workspace (student + course picker, payment history, record payment, issue/revoke card) and an "All free cards" sub-view (segmented control) with the existing course/status filters. Links between them stay within the tab (`?tab=free-cards&view=all|student&studentId=…`).

### Refunds
- AC-23 The Refunds tab behaves exactly as the current page does. "View order details" opens the **shared order drawer** (AC-10), so postage and delivery now appear there too.

## 4. Database impact

One new Flyway migration, **`V106__create_payment_slip_note.sql`** (check the highest version on the branch right before creating it; V105 is the current latest). Per the dev-DB memory note, the file is written once in its final form, because the dev backend applies it on save.

```sql
CREATE TABLE payment_slip_note (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant(id),
    slip_id         UUID NOT NULL,
    author_user_id  UUID NOT NULL,
    body            VARCHAR(1000) NOT NULL CHECK (length(btrim(body)) > 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_slip_note_slip_same_tenant
        FOREIGN KEY (tenant_id, slip_id) REFERENCES payment_slip (tenant_id, id)
);
CREATE INDEX idx_payment_slip_note_tenant_slip ON payment_slip_note (tenant_id, slip_id, created_at);
```

- If `payment_slip` has no `UNIQUE (tenant_id, id)` yet, the same migration adds `uq_payment_slip_tenant_id UNIQUE (tenant_id, id)` so the composite FK can work.
- Append-only: no `updated_at`; the repository has no update/delete methods. A DB trigger that rejects UPDATE/DELETE should match whatever the audit-log table uses.
- Search indexes: these may be needed for the order list (`student_order (tenant_id, created_at DESC)` and `(tenant_id, order_number)`). Check existing indexes first and add them in the same migration only if they are missing (`uq_student_order_tenant_order_number` probably already covers the order number).
- No change to order, payment, slip, ledger, refund or free-card tables. No existing migration is edited.

## 5. Backend impact

**Ledger & settlement module (`ledgersettlementmanagement`)**
- `LedgerQueryService.getOrders(OrderListFilter, Pageable)`: generalises `getOutstanding()` to all orders. It reuses `PaymentOperationalStateResolver`, filters `orderNumber` / `from` / `to` in the order query (pushed down to SQL through an `OrderLookupApi` method in paymentmanagement), and filters status/method in application code (same constraint already documented for `getDashboard`). Requires `PAYMENTS_SLIPS/VIEW`. Student names come in one batch call per page through `StudentLookupApi.findContacts`.
- `LedgerController`: `GET /api/v1/ledger/orders`. The `/outstanding` and `/courses/{id}/summary` endpoints **stay**, because other screens and tests may use them. Only their UI tabs go away.

**Payment module (`paymentmanagement`)**
- `OrderAdminQueryService` (new): `getOrderDetail(orderId)` returns order + lines + subtotal / facility fee / discount / **postage** / delivery + payments (id, method, status, reference) + refunded / remaining. Requires `PAYMENTS_SLIPS/VIEW`. Uses a tenant-scoped `findById`, so another tenant's id gives 404.
- An `OrderLookupApi` method (or extension of an existing one) for the paged, tenant-scoped order search used by the ledger module.
- `RefundVerificationView` gains `postageAmount` and a `delivery` value (additive fields).
- Slips:
  - `SlipReviewService.getReviewQueue(status, search, pageable)`: optional `search`. Student matches come from a new tenant-scoped `StudentLookupApi.searchStudentUserIds(term, limit)` (name/email/mobile); reference and order number match in the slip/order query. Combined with OR, then AND with status.
  - `PaymentSlipView` / `PaymentSlipResponse` gain `studentName`, `studentMobile`, `orderNumber` and `displayOrderNumber` (additive), batch-loaded per page.
  - `GET /api/v1/payment-slips/review-queue/count`: returns the pending count for the header and badge.
  - `SlipDownloadUrlResponse` gains `contentType` (from storage object metadata, or else the object-key extension recorded at the sniffed upload) so the viewer can choose img vs PDF.
  - `SlipNoteService` + `PaymentSlipNoteRepository` (tenant-aware, append-only) + `SlipNoteController`.

**User module (`usermanagement`)**
- `StudentLookupApi.searchStudentUserIds(String term, int limit)`: tenant-scoped, case-insensitive, capped (e.g. 500).

Unchanged: `SlipReviewService.approve/reject`, duplicate checks, ledger writes, entitlement grants, refunds, manual payments, free cards.

## 6. API contract

**Status: reviewed with `review-api-contract` on 2026-10-29.** Both `implement-backend` and
`implement-frontend` work from this section. Once the module ships it is copied into
`docs/api/ledger-settlement-management.md` and `docs/api/payment-management.md`.

### Common conventions (all endpoints below)

- **Envelope.** `ApiResponse<T>` = `{success, data, error, timestamp, traceId}`.
- **Error object.** `ApiError` = `{code, message, fieldErrors[]}`, where each field error is `{field, message}`.
- **Status codes:**

  | Code | When | `error.code` |
  |---|---|---|
  | `400` | Bad param/body | `VALIDATION_ERROR`, with `fieldErrors` naming the param |
  | `401` | No or expired token | `UNAUTHENTICATED` |
  | `403` | Authenticated, but the role lacks the grant | `FORBIDDEN` |
  | `404` | Unknown id or another tenant's id | `NOT_FOUND`. The existence of another tenant's resource is never revealed. |

- **Collections.** `PageResponse<T>` = `{content, page, size, totalElements, totalPages}`. `page` is 0-based. `size` defaults to 20 and the server caps it at 100 (a larger value is clamped, not rejected). Sort order is fixed per endpoint; a client `sort` param is ignored unless the endpoint lists it.
- **Tenant.** Taken from the authenticated context only. No endpoint accepts `tenantId`, `role` or `studentId` as a filter.
- **Money.** Money fields are JSON numbers with 2 decimal places (`BigDecimal`). `currency` is an ISO-4217 code.
- **Instants.** Instants are ISO-8601 UTC.
- **Staff-only endpoints.** These are the endpoints that require only `PAYMENTS_SLIPS` grants. A Student or Teacher caller gets `403`. This is not an anti-enumeration risk, because the endpoint rejects every id the same way.

### 6.1 `GET /api/v1/ledger/orders` — All Orders list (new)

| Param | Type | Rule |
|---|---|---|
| `orderNumber` | string, optional | Trimmed, case-insensitive. Accepts `ORD-000123`, `000123` or `123`. Exact match on `order_number`. Anything that isn't `(ORD-)?\d{1,12}` → `400`. |
| `from`, `to` | `yyyy-MM-dd`, optional | Inclusive calendar days in the tenant's `GENERAL.default_timezone` config (default **UTC**, the same resolution `FreeCardService` uses). `from > to` → `400` (`fieldErrors[0].field = "from"`). Either bound may be given alone. |
| `status` | `PaymentOperationalState`, optional | `UNPAID \| PENDING \| UNDER_REVIEW \| PAID \| REJECTED \| REFUNDED`. Any other value → `400`. |
| `method` | `PaymentMethod`, optional | `GATEWAY \| MANUAL_SLIP \| FREE \| STAFF_GRANTED \| MANUAL_RECEIPT`. |
| `page`, `size` | int | See the common conventions. Sort order is fixed: `createdAt DESC, orderNumber DESC`. |

All filters combine with AND.

**Response — `200`, `PageResponse<AdminOrderRowResponse>`:**

```jsonc
{
  "orderId": "uuid",
  "orderNumber": 123,
  "displayOrderNumber": "ORD-000123",
  "createdAt": "2026-10-29T04:12:00Z",
  "studentId": "uuid",               // student_profile id, for linking to the student page
  "studentName": "Nimal Perera",     // null if the profile can't be resolved
  "itemCount": 2,                    // number of order lines; 1 for a legacy line-less order
  "firstItemTitle": "Grade 11 Maths",// first line's course title, or the legacy order's course; may be null
  "amount": 3500.00,                 // order total incl. fees/postage, minus discount
  "currency": "LKR",
  "operationalState": "PAID",        // same resolver as /ledger/dashboard and /ledger/outstanding
  "method": "MANUAL_SLIP",           // see the rule below
  "reference": "BOC-77812"           // see the rule below
}
```

- **`method` and `reference`** come from the payment that determines `operationalState` (the same payment the resolver picks). Both are `null` when the order has no payment attempt (`UNPAID`).
- **`reference`** is the slip reference or the gateway reference, whichever applies.

**Errors and access:**
- `400` for a bad param.
- `403` without `PAYMENTS_SLIPS/VIEW`. Tenant Admin, Finance Staff, Student Support and Read-only Auditor are allowed.

`/ledger/outstanding` and `/ledger/courses/{id}/summary` stay **unchanged**.

### 6.2 `GET /api/v1/orders/{orderId}/admin-detail` — order drawer (new)

Staff-only, `PAYMENTS_SLIPS/VIEW`.

**Errors:**

| Code | When |
|---|---|
| `404` | Unknown id or another tenant's id |
| `403` | Student, Teacher, or staff without VIEW |

**Response — `200`, `AdminOrderDetailResponse`:**

```jsonc
{
  "orderId": "uuid", "orderNumber": 123, "displayOrderNumber": "ORD-000123",
  "createdAt": "…", "orderStatus": "PLACED",          // OrderStatus enum, as stored
  "operationalState": "PAID",
  "student": { "studentId": "uuid", "name": "…", "email": "…", "mobile": "…" },   // fields nullable
  "currency": "LKR",
  "lines": [ { "lineNo": 1, "courseId": "uuid", "courseTitle": "…", "label": "…", "scope": "…",
               "unitAmount": 3000.00, "facilityFee": 100.00, "discountAmount": 0.00, "lineTotal": 3100.00 } ],
  "subtotal": 3000.00,      // nullable (legacy)
  "facilityFee": 100.00,    // nullable (legacy)
  "postage": 400.00,        // nullable (legacy / pre-V90); 0.00 when postage wasn't chosen on a V90+ order
  "discount": 0.00,         // nullable (legacy)
  "total": 3500.00,         // = order.amount, always present
  "refunded": 0.00,         // sum of payment_refund rows for this order's payments
  "remaining": 3500.00,     // confirmed amount − refunded; 0.00 if nothing is confirmed
  "delivery": { "recipientName": "…", "addressLine": "…", "city": "…", "phone": "…" },  // null when no postage
  "payments": [ { "paymentId": "uuid", "method": "MANUAL_SLIP", "status": "CONFIRMED",
                  "reference": "…", "createdAt": "…", "slipId": "uuid|null" } ]   // oldest first
}
```

- `lines` is `[]` for a legacy line-less order.
- This endpoint is read-only. It never changes order, payment or ledger state.

### 6.3 `GET /api/v1/payments/{id}/refund-verification` (existing — additive)

Two fields are added:
- `postageAmount: number | null`
- `delivery: {recipientName, addressLine, city, phone} | null`

Auth, status codes and every existing field are unchanged.

### 6.4 `GET /api/v1/payment-slips/review-queue` (existing — additive)

**New optional param `search`:**
- Trimmed; 1–100 chars after trimming (longer → `400`). Blank is treated as absent.
- Case-insensitive *contains* match against student name, email and mobile, and against the slip `referenceNumber`.
- *Exact* match against the order number (same parsing as 6.1, but a non-numeric term simply doesn't match an order number, so no `400` for that).
- The match groups are combined with OR. The result is then ANDed with `status`; omitting `status` still means `SUBMITTED` + `UNDER_REVIEW`.
- Student matching goes through a capped (500) tenant-scoped lookup. A term that matches more than 500 students only returns slips for the first 500 by name. This is documented behaviour, not an error.

**Fields added to each item:**
- `studentName: string | null`
- `studentMobile: string | null`
- `orderNumber: number`
- `displayOrderNumber: string`

The existing fields, including `orderLines`, `orderPostage`, `orderDelivery` and `flags`, are unchanged. Sort stays `submittedAt ASC` (oldest first, so the queue clears in arrival order) (confirmed: this is the existing `@PageableDefault`).

### 6.5 `GET /api/v1/payment-slips/review-queue/count` (new)

- **Response — `200`:** `{"pending": 3}`, the count of `SUBMITTED` + `UNDER_REVIEW` slips in the caller's tenant.
- **Access:** staff-only, `PAYMENTS_SLIPS/VIEW`; `403` otherwise.
- **Routing:** this is a literal path segment, so it does not clash with `/{slipId}`.

### 6.6 `GET /api/v1/payment-slips/{slipId}/download-url` (existing — additive)

- **Field added:** `contentType: "image/png" | "image/jpeg" | "image/gif" | "application/pdf" | null`.
  - It is read from the stored object's metadata (the type was sniffed at upload).
  - `null` if the type can't be determined; the frontend then offers Download only.
- **Unchanged:** auth (owner student or staff VIEW), the TTL, and `url` / `expiresAt`.

### 6.7 `GET /api/v1/payment-slips/{slipId}/notes` (new)

- **Access:** staff-only, `PAYMENTS_SLIPS/VIEW`. A Student caller, **including the slip's owner**, gets `403`.
- **Not found:** `404` for an unknown id or another tenant's slip.
- **Response — `200`:** `SlipNoteResponse[]`, oldest first, not paginated (a slip carries few notes; the server caps the list at 200).

```jsonc
{ "id": "uuid", "slipId": "uuid", "body": "Called student, amount confirmed",
  "authorUserId": "uuid", "authorName": "Kamal (Finance)", "createdAt": "…" }   // authorName nullable
```

### 6.8 `POST /api/v1/payment-slips/{slipId}/notes` (new)

- **Body:** `{"body": "…"}`. It is trimmed and must be 1–1000 chars, otherwise `400`. Unknown fields are ignored; there is no `authorUserId` or `tenantId` input.
- **Response — `201`:** `SlipNoteResponse`.
- **Access:** requires `PAYMENTS_SLIPS/CREATE_EDIT`, which only Tenant Admin and Finance Staff hold. Student Support, Read-only Auditor, Student and Teacher get `403`.
- **Not found:** `404` for an unknown id or another tenant's slip.
- **Any slip status:** a note may be added in every status, including `APPROVED` and `REJECTED`. A note never changes the slip's state.
- There is **no** PUT, PATCH or DELETE.

### Contract review result

| Check | Result |
|---|---|
| Request/response shapes and null handling | Specified above |
| Status codes per failure mode | Specified (`400`/`401`/`403`/`404`; no `409`, because nothing here changes state except creating a note) |
| Pagination/sort/filter | 6.1 and 6.4 specified; 6.7 has a capped list |
| Auth per endpoint | Specified, and matches the `PermissionCheckServiceImpl` grant matrix |
| No client-supplied tenant/role/actor | Confirmed; the note author comes from the session |
| Breaking changes to shipped contracts | **None.** 6.3, 6.4 and 6.6 only add fields or an optional param, and existing clients ignore unknown fields. |
| Production data used as reference | None |

**Corrections made during the review:**

- **Timezone.** It is the tenant's `default_timezone` config (default UTC), not Asia/Colombo.
- **Note permission.** It is `CREATE_EDIT`, which only Tenant Admin and Finance Staff hold. Approval itself uses `APPROVE`, which is unchanged.
- **Viewer file type.** `contentType` is nullable, because the slip row does not store the file type.
- **`method` / `reference` on an order row.** These come from the state-deciding payment, because one order can have several payment attempts.
- **Student access to notes.** Notes return `403` to students, including the slip's owner.

**Amendments made during backend implementation (2026-10-29).** All of these are on endpoints or fields that have not shipped yet:

- **6.2 order detail.**
  - `payments[].slipId` is dropped. A slip payment's row carries `"SLIP-" + paymentId`, not the slip id, so there is no reliable link to report.
  - Instead the response has a separate `slips: [{slipId, referenceNumber, status, submittedAt}]` list, oldest first.
  - Each payment also carries `amount`.
  - For a slip payment, `payments[].reference` is the approved slip's reference.
- **6.4 review queue.** Items also carry `studentProfileId` (nullable), so the grid can link to the student page; `studentId` stays the student's user id.
- **6.7 / 6.8 notes.** `authorName` becomes **`authorEmail`** (nullable). Staff accounts have no display name reachable from payment-management, so the email is the honest label.
- **6.1 method for an unconfirmed order.**
  - `MANUAL_SLIP` while a slip is open or was rejected.
  - `GATEWAY` while a gateway attempt is pending or was rejected.
  - Otherwise `null`.
  - `reference` is set only once a payment is confirmed.
- **Student search.** It matches profile name and mobile, plus the email of any STUDENT-role user in the tenant. The email match is by role, not by profile, so a student without a profile row is still found.

## 7. Frontend impact

- New `app/(tenant-admin)/tenant-admin/payments/page.tsx`: the hub shell (header card + `Tabs`, `?tab=` as the source of truth, lazy tab mounting like the current dashboard).
- New `components/payments/hub/`:
  - `all-orders-tab.tsx`, `order-search-controls.tsx` (order no., from/to, plus the existing `PaymentFilterControls`)
  - `order-details-drawer.tsx` (shared; replaces `refunds/order-details-panel.tsx`)
  - `payment-slips-tab.tsx`, `slip-viewer-dialog.tsx`, `slip-row-actions.tsx`, `slip-note-dialog.tsx`, `slip-row-expander.tsx`
  - `free-cards-tab.tsx` (wraps the extracted `StudentPaymentsWorkspace` + `FreeCardsList`)
  - `refunds-tab.tsx`
- Extract page bodies into components so the old routes and the hub share code: `slip-review/[slipId]/approve-slip-dialog.tsx` and `reject-slip-dialog.tsx` move to `components/payments/slips/` and gain an `onDone` callback; `SlipOrderBreakdown` moves too.
- Old route pages become `redirect()`s to the hub tab (AC-4); the slip detail page stays.
- `lib/api/`: `admin-orders.ts` (list + detail hooks); `payment-slips.ts` (search param, count hook, notes hooks, new fields); `refund-verification.ts` (postage/delivery types).
- `components/layout/nav/tenant-admin-nav.tsx`: collapse to one "Payments" entry pointing to `/tenant-admin/payments`.
- Delete `payments/dashboard/outstanding-tab.tsx` and `course-summary-tab.tsx` (UI only).
- Uses the existing shadcn `Dialog` / `Sheet`, `DataTable` and `LiveRegion`. Icons from lucide (`CheckCircle2`, `Trash2`, `NotebookPen`, `Info`, `ChevronDown`).

## 8. Security impact

- No change to authentication, approval rules or entitlement activation. Approve/reject call the existing endpoints, so the duplicate-check gates, the mandatory override reason and the audit log are untouched (`.claude/rules/payments.md` §2–3).
- The slip viewer renders a short-lived signed URL. It is never put in the DOM beyond the `src` of the open modal and never cached in the query cache (it is a mutation, as today). Download uses the same URL. `<img>`/`<iframe>` sources must allow the storage origin; check `next.config.ts` CSP `img-src`/`frame-src` and widen it only to the configured storage origin.
- Notes are internal staff data: staff-only on both endpoints (a student owner gets 403, even on their own slip). The body is rendered as text (no HTML). Notes are append-only, with no update/delete endpoint or repository method.
- Search input is bound as a parameter (no string-built SQL). Length is capped; the number of `StudentLookupApi` search results is capped.
- Nav and tab visibility is UX only. Every endpoint re-checks permissions server-side.

## 9. Tenant impact

- Every new query runs through `TenantAwareRepository` / the tenant from the request context. No endpoint accepts `tenant_id`.
- `payment_slip_note` has `NOT NULL tenant_id`, an FK to `tenant`, a composite same-tenant FK to `payment_slip`, and an index led by `tenant_id`.
- The order list, order detail, slip search, the count and notes all resolve the tenant once from context. Cross-module lookups (`StudentLookupApi`, `OrderLookupApi`) are tenant-scoped inside their own modules.
- A slip reference or order number shared by two tenants never mixes results.
- Bulk/listing endpoints (order list, slip count, slip search) get explicit cross-tenant tests (see §10), not only single-id tests.

## 10. Tests

**Backend (JUnit + Spring Boot integration + Testcontainers)**
- `AdminOrderListIntegrationTest`: lists only the caller's orders; order-number exact match (with/without `ORD-`); date range is inclusive and uses the tenant timezone; status/method AND search; `from>to` → 400; pagination totals. **Cross-tenant:** a tenant-B order never appears; tenant-A search for B's order number → empty.
- `AdminOrderDetailIntegrationTest`: lines + postage + delivery + refunded/remaining are correct; legacy line-less order; **tenant A → B's orderId = 404**; student → 403; teacher → 403.
- `RefundVerification…Test`: postage/delivery are present.
- `SlipReviewQueueSearchIntegrationTest`: search by name, email, mobile, reference, order number; default pending only; new fields filled; batch loading (no per-row calls). **Cross-tenant:** the same reference in tenant B is not returned.
- `SlipPendingCountIntegrationTest`: count is tenant-scoped.
- `SlipNoteIntegrationTest`: create/list; blank/1001-char body → 400; Read-only Auditor POST → 403; student owner GET → 403; **tenant A note on B's slip → 404**; no update/delete routes; DB rejects UPDATE/DELETE (if a trigger is added).
- `SlipDownloadUrl…Test`: `contentType` is present.
- `StudentLookupApi.searchStudentUserIds` unit/integration test: tenant-scoped and capped.
- Regression: existing slip approve/reject, refund, ledger dashboard and free-card suites stay green.

**Frontend (Playwright)**
- `payments-hub.spec.ts`: tabs show per role; `?tab=` deep links; old routes redirect with params; the nav has one Payments entry.
- All Orders: order-number search, date range + validation, status/method filters, the drawer shows products, fees, **postage** and delivery.
- Payment Slips: pending loads by default; the grid shows student name + contact; search; viewer modal renders the image; approve with no flag, approve with flag requires a reason, decline requires a reason; the row disappears and the count goes down; add note shows in the expander; actions hidden for Read-only Auditor; disabled for a `SUBMITTED` row.
- Free Cards: workspace + all-cards sub-view; links stay in the tab.
- Refunds: refund flow unchanged; the drawer shows postage.
- Update the affected existing specs (`manual-payment-slips`, `klass-manual-payment-free-card`, `order-and-payment`, `tenant-admin-nav`, `route-groups`) for the new routes/nav.
- Accessibility: tab keyboard navigation, modal focus trap, `aria-label` on icon-only buttons.

## 11. Documentation updates

- This plan → status updated when it ships, with QA evidence.
- `docs/specs/payments-hub.md` (new): screen spec + the API contract from §6.
- `docs/plans/MVP-011 Manual Payment Slips.md`, `klass-manual-payment-free-card.md`, `MVP-010 Order and Payment Foundation.md`: add a "superseded UI location → Payments hub" note.
- API reference docs for the new/extended endpoints (wherever the existing ledger/slip endpoints are documented under `docs/`).
- Data-model doc: `payment_slip_note`.

## Delivery order (per CLAUDE.md workflow, separate commits)

1. `review-api-contract` on §6.
2. `database-migration`: V106.
3. `implement-backend` + backend tests → commit "backend: add payments hub order list, slip search and notes (PAY-HUB-01)".
4. `implement-frontend` + Playwright → commit "frontend: consolidate payment pages into Payments hub (PAY-HUB-01)".
5. Tenant-isolation + security + payment-ledger reviews, docs → commit.

## Change-control check

- Payment ledger rules, enrollment/entitlement activation (ADR-028), slip approval gates: **not changed**.
- Approved API contracts: **additive only**. New endpoints, new optional params and new response fields; nothing removed or renamed.
- Database migration history: **new migration only**.
- Multi-tenancy / auth architecture: **not changed**.
