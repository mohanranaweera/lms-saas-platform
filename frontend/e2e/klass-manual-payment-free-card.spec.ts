import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * ADR-030 Klass Manual Payment & Free Card - staff UI.
 *
 * No backend runs here: an in-memory fake answers `/api/v1/staff/students/**`,
 * `/api/v1/free-cards/**`, `/api/v1/students` and `/api/v1/courses` with the
 * documented shapes (plan §4). Every server rule (tenant resolution of the
 * student id, PAYMENTS_SLIPS / FREE_CARDS permissions, quote re-pricing,
 * amount equality, idempotency, overlap, ledger/entitlement effects) is proven
 * by the backend tests - these specs prove the UI states, role-based
 * visibility, confirmation steps, error mapping, single submission and the
 * idempotency-key handling of the client.
 */

const TZ = "Asia/Colombo";

function isoToday(): string {
  return new Intl.DateTimeFormat("en-CA", { timeZone: TZ, year: "numeric", month: "2-digit", day: "2-digit" }).format(
    new Date()
  );
}

function addMonthsEnd(iso: string, months: number): string {
  const [year, month] = iso.split("-").map(Number);
  const end = new Date(Date.UTC(year, month - 1 + months + 1, 0));
  return end.toISOString().slice(0, 10);
}

const PY = { courseId: "c-py", title: "Python", pricingModel: "MONTHLY", monthlyAmount: 3000, currency: "LKR", postageFee: 0 };
const CHEM = { courseId: "c-chem", title: "Chemistry", pricingModel: "MONTHLY", monthlyAmount: 2000, currency: "LKR", postageFee: 200 };
const BIO = { courseId: "c-bio", title: "Biology", pricingModel: "MONTHLY", monthlyAmount: 2500, currency: "LKR", postageFee: 0 };
const PRICE: Record<string, number> = { [PY.courseId]: 3000, [CHEM.courseId]: 2000, [BIO.courseId]: 2500 };
const TITLE: Record<string, string> = { [PY.courseId]: "Python", [CHEM.courseId]: "Chemistry", [BIO.courseId]: "Biology" };

const STUDENTS = [
  { id: "s-1", name: "John Doe", email: "john@example.test", roleCode: "STUDENT", status: "ACTIVE" },
  { id: "s-2", name: "Jane Roe", email: "jane@example.test", roleCode: "STUDENT", status: "ACTIVE" },
];

const CONTEXTS: Record<string, unknown> = {
  "s-1": {
    student: { studentId: "s-1", name: "John Doe", email: "john@example.test", mobile: "0713663831" },
    timezone: TZ,
    courses: [CHEM, PY],
  },
  "s-2": {
    student: { studentId: "s-2", name: "Jane Roe", email: "jane@example.test", mobile: null },
    timezone: TZ,
    courses: [BIO],
  },
};

function monthlyOffers(courseId: string) {
  const amount = PRICE[courseId];
  const items =
    courseId === PY.courseId
      ? [
          { year: 2026, month: 7, state: "PAID" },
          { year: 2026, month: 8, state: "AVAILABLE" },
          { year: 2026, month: 9, state: "PENDING" },
        ]
      : [{ year: 2026, month: 8, state: "AVAILABLE" }];
  return {
    scope: "MONTHLY",
    courseId,
    currency: "LKR",
    supported: true,
    years: [2026],
    items: items.map((item) => ({
      scope: "MONTHLY",
      year: item.year,
      month: item.month,
      moduleId: null,
      lessonId: null,
      label: `${item.month === 7 ? "July" : item.month === 8 ? "August" : "September"} 2026`,
      amount,
      state: item.state,
    })),
  };
}

function lessonOffers(courseId: string) {
  return {
    scope: "LESSON",
    courseId,
    currency: "LKR",
    supported: true,
    years: [],
    items:
      courseId === PY.courseId
        ? [
            { scope: "LESSON", year: null, month: null, moduleId: null, lessonId: "l-1", label: "Revision seminar", amount: 1000, state: "PAID" },
            { scope: "LESSON", year: null, month: null, moduleId: null, lessonId: "l-2", label: "Past paper class", amount: 800, state: "AVAILABLE" },
          ]
        : [],
  };
}

const HISTORY = [
  {
    orderId: "o-1",
    createdAt: "2026-08-02T04:00:00Z",
    status: "RECEIVED",
    source: "MANUAL",
    currency: "LKR",
    orderTotal: 3000,
    receivedAmount: 3000,
    staffAdjustmentAmount: 0,
    refundedAmount: 0,
    paymentId: "p-1",
    slipId: null,
    receipt: {
      receiptId: "r-1",
      method: "CASH",
      referenceNumber: "RCPT-77",
      receivedOn: "2026-08-02",
      notes: "Paid at the front desk",
      adjustmentReason: null,
      recordedBy: "u-9",
      recordedByName: "finance@inst.test",
      recordedAt: "2026-08-02T04:00:00Z",
    },
    lines: [{ lineNo: 1, courseId: "c-py", courseTitle: "Python", scope: "MONTHLY", label: "July 2026", year: 2026, month: 7, lineTotal: 3000 }],
  },
  {
    orderId: "o-2",
    createdAt: "2026-09-01T04:00:00Z",
    status: "PENDING_SLIP",
    source: "SLIP",
    currency: "LKR",
    orderTotal: 3000,
    receivedAmount: null,
    staffAdjustmentAmount: null,
    refundedAmount: null,
    paymentId: null,
    slipId: "sl-1",
    receipt: null,
    lines: [{ lineNo: 1, courseId: "c-py", courseTitle: "Python", scope: "MONTHLY", label: "September 2026", year: 2026, month: 9, lineTotal: 3000 }],
  },
];

function freeCard(overrides: Record<string, unknown> = {}) {
  return {
    id: "fc-1",
    studentId: "s-1",
    studentName: "John Doe",
    courseId: "c-py",
    courseTitle: "Python",
    cardType: "FULL",
    validFrom: "2026-08-01",
    validTo: "2026-08-31",
    timezone: TZ,
    effectiveFrom: "2026-07-31T18:30:00Z",
    effectiveUntil: "2026-08-31T18:30:00Z",
    coveredMonths: ["2026-08"],
    status: "ACTIVE",
    reason: "Scholarship",
    currency: "LKR",
    nominalMonthlyAmount: 3000,
    nominalValue: 3000,
    issuedBy: "u-1",
    issuedByName: "admin@inst.test",
    issuedAt: "2026-07-20T04:00:00Z",
    revokedAt: null as string | null,
    revokedBy: null as string | null,
    revokedByName: null as string | null,
    revokedReason: null as string | null,
    enrollmentCreated: false,
    replay: false,
    ...overrides,
  };
}

type Card = ReturnType<typeof freeCard>;

interface ErrorReply {
  status: number;
  code: string;
  message: string;
  details?: Record<string, unknown>;
  fieldErrors?: { field: string; message: string }[];
}

interface FakeState {
  role: string;
  cards: Card[];
  forbidden: boolean;
  /** Replies for successive record POSTs (consumed in order); afterwards: 201. */
  recordReplies: ErrorReply[];
  recordDelayMs: number;
  issueReplies: ErrorReply[];
  records: Record<string, unknown>[];
  issues: Record<string, unknown>[];
  revokes: { id: string; body: Record<string, unknown> }[];
  /** Override the next quote total (e.g. after QUOTE_CHANGED). */
  quoteTotalOverride: number | null;
}

function buildQuote(body: { items: { courseId: string; year: number; month: number }[]; postageCourseIds: string[] }, totalOverride: number | null) {
  const lines = body.items.map((item, index) => ({
    lineNo: index + 1,
    scope: "MONTHLY",
    label: `${item.month === 8 ? "August" : "Month " + item.month} ${item.year}`,
    year: item.year,
    month: item.month,
    moduleId: null,
    lessonId: null,
    unitAmount: PRICE[item.courseId],
    facilityFee: 0,
    lineTotal: PRICE[item.courseId],
    courseId: item.courseId,
    courseTitle: TITLE[item.courseId],
    discountAmount: 0,
    reward: false,
    discountName: null,
  }));
  const subtotal = lines.reduce((sum, line) => sum + line.unitAmount, 0);
  const postage = body.postageCourseIds.includes(CHEM.courseId) ? 200 : 0;
  const total = totalOverride ?? subtotal + postage;
  return {
    courseId: null,
    currency: "LKR",
    lines,
    courses: [],
    subtotal,
    facilityFeeTotal: 0,
    postageTotal: postage,
    discountTotal: 0,
    total,
    feeBasis: "NONE",
    feeNote: null,
    deliveryRequired: postage > 0,
    delivery: null,
    quoteToken: `qt-${total}-${lines.length}`,
    methods: { online: false, slip: true },
    bankDetails: null,
    discounts: [],
    codeResult: null,
    rewardOffers: [],
    notices: [],
    blockingIssue: null,
    blockingMessage: null,
  };
}

async function errorReply(route: Route, reply: ErrorReply) {
  const body = apiError(reply.code, reply.message, reply.fieldErrors ?? []) as { error: Record<string, unknown> };
  if (reply.details) body.error.details = reply.details;
  await fulfillJson(route, reply.status, body);
}

async function setup(page: Page, role: string, overrides: Partial<FakeState> = {}): Promise<FakeState> {
  const state: FakeState = {
    role,
    cards: [freeCard()],
    forbidden: false,
    recordReplies: [],
    recordDelayMs: 0,
    issueReplies: [],
    records: [],
    issues: [],
    revokes: [],
    quoteTotalOverride: null,
    ...overrides,
  };
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  await mockJson(page, "**/api/v1/students", 200, apiSuccess(STUDENTS));
  await page.route("**/api/v1/courses?**", (route) =>
    fulfillJson(route, 200, apiSuccess({
      content: [PY, CHEM, BIO].map((course) => ({ id: course.courseId, name: course.title })),
      page: 0,
      size: 100,
      totalElements: 3,
      totalPages: 1,
    }))
  );

  await page.route("**/api/v1/staff/students/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const match = url.pathname.match(/\/api\/v1\/staff\/students\/([^/]+)\/(.+)$/);
    if (!match) return fulfillJson(route, 404, apiError("NOT_FOUND", "Unhandled"));
    if (state.forbidden) return fulfillJson(route, 403, apiError("FORBIDDEN", "Access denied"));
    const [, studentId, rest] = match;
    const context = CONTEXTS[studentId];
    if (!context) return fulfillJson(route, 404, apiError("NOT_FOUND", "Student not found"));

    if (rest === "payment-context") return fulfillJson(route, 200, apiSuccess(context));
    if (rest === "payment-offers") {
      const courseId = url.searchParams.get("courseId") ?? "";
      const scope = url.searchParams.get("scope");
      return fulfillJson(route, 200, apiSuccess(scope === "LESSON" ? lessonOffers(courseId) : monthlyOffers(courseId)));
    }
    if (rest === "payment-history") {
      const courseId = url.searchParams.get("courseId");
      return fulfillJson(route, 200, apiSuccess(courseId === PY.courseId ? HISTORY : []));
    }
    if (rest === "manual-payments/quote") {
      const body = JSON.parse(request.postData() ?? "{}");
      return fulfillJson(route, 200, apiSuccess(buildQuote(body, state.quoteTotalOverride)));
    }
    if (rest === "manual-payments") {
      const body = JSON.parse(request.postData() ?? "{}");
      state.records.push(body);
      if (state.recordDelayMs) await new Promise((resolve) => setTimeout(resolve, state.recordDelayMs));
      const reply = state.recordReplies.shift();
      if (reply) return errorReply(route, reply);
      return fulfillJson(route, 201, apiSuccess({
        receiptId: "r-new",
        orderId: "o-new",
        paymentId: "p-new",
        currency: "LKR",
        expectedAmount: Number(body.expectedReceivedAmount) + Number(body.staffAdjustmentAmount),
        staffAdjustmentAmount: Number(body.staffAdjustmentAmount),
        receivedAmount: Number(body.expectedReceivedAmount),
        paymentMethod: body.paymentMethod,
        referenceNumber: body.referenceNumber,
        receivedOn: body.receivedOn,
        notes: body.notes,
        adjustmentReason: body.adjustmentReason,
        recordedBy: "u-1",
        recordedAt: "2026-10-21T04:00:00Z",
        lines: [{ lineNo: 1, courseId: "c-py", courseTitle: "Python", scope: "MONTHLY", label: "August 2026", year: 2026, month: 8, lineTotal: 3000 }],
        replay: false,
      }));
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Unhandled"));
  });

  await page.route("**/api/v1/free-cards**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^.*\/api\/v1\/free-cards/, "");
    const method = request.method();
    if (state.forbidden) return fulfillJson(route, 403, apiError("FORBIDDEN", "Access denied"));
    if (path === "" && method === "GET") {
      const studentId = url.searchParams.get("studentId");
      const courseId = url.searchParams.get("courseId");
      const status = url.searchParams.get("status");
      const content = state.cards.filter(
        (card) =>
          (!studentId || card.studentId === studentId) &&
          (!courseId || card.courseId === courseId) &&
          (!status || card.status === status)
      );
      return fulfillJson(route, 200, apiSuccess({ content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 }));
    }
    if (path === "" && method === "POST") {
      const body = JSON.parse(request.postData() ?? "{}");
      state.issues.push(body);
      const reply = state.issueReplies.shift();
      if (reply) return errorReply(route, reply);
      const created = freeCard({
        id: `fc-${state.cards.length + 1}`,
        validFrom: body.validFrom,
        validTo: body.validTo,
        reason: body.reason,
        coveredMonths: [body.validFrom.slice(0, 7)],
        status: "ACTIVE",
        enrollmentCreated: true,
      });
      state.cards.push(created);
      return fulfillJson(route, 201, apiSuccess(created));
    }
    const revoke = path.match(/^\/([^/]+)\/revoke$/);
    if (revoke && method === "POST") {
      const body = JSON.parse(request.postData() ?? "{}");
      state.revokes.push({ id: revoke[1], body });
      const card = state.cards.find((entry) => entry.id === revoke[1]);
      if (!card) return fulfillJson(route, 404, apiError("NOT_FOUND", "Free card not found"));
      Object.assign(card, { status: "REVOKED", revokedAt: "2026-10-21T04:00:00Z", revokedReason: body.reason, revokedByName: "admin@inst.test" });
      return fulfillJson(route, 200, apiSuccess(card));
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Unhandled"));
  });
  return state;
}

// PAY-HUB-01: Student Payments is now the Payments hub "Free Cards" tab (student view).
const SCOPED = "/tenant-admin/payments?tab=free-cards&view=student&studentId=s-1&courseId=c-py";

/** Opens Add Payment for John Doe / Python, ticks August 2026, Cash, and waits for the quote. */
async function fillAddPayment(page: Page) {
  await page.getByTestId("add-payment-open").click();
  const dialog = page.getByTestId("add-payment-dialog");
  await dialog.getByLabel("2026 August").check();
  await dialog.getByLabel("Payment Method *").selectOption("CASH");
  await expect(dialog.getByTestId("add-payment-net")).toHaveText("Net Amount: LKR 3,000.00");
  return dialog;
}

test.describe("Student Payments - manual payment & free card (ADR-030)", () => {
  test("a preselected student and course load the scoped page", async ({ page }) => {
    await setup(page, "TENANT_ADMIN");
    await page.goto(SCOPED);

    await expect(page.getByRole("tab", { name: "Free Cards", selected: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Student payments", pressed: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "Payments", exact: true })).toBeVisible();
    // Free Cards is a tab of the Payments hub now, not a separate nav link.
    await expect(page.getByRole("link", { name: "Free Cards", exact: true })).toHaveCount(0);
    await expect(page.getByTestId("student-name")).toContainText("John Doe");
    await expect(page.getByTestId("student-card")).toContainText("0713663831");
    await expect(page.getByTestId("course-select")).toHaveValue("c-py");
    await expect(page.getByRole("heading", { name: "John's Payments" })).toBeVisible();
    await expect(page.getByTestId("history-course-chip")).toHaveText("Python");

    const rows = page.getByTestId("history-row");
    await expect(rows).toHaveCount(2);
    await expect(rows.first().getByTestId("history-status")).toHaveText("Received");
    await expect(rows.first().getByTestId("history-months")).toHaveText("2026 July");
    await expect(rows.first().getByTestId("history-amount")).toHaveText("LKR 3,000.00");
    await expect(rows.nth(1).getByTestId("history-status")).toHaveText("Pending slip");
    await expect(rows.nth(1).getByRole("link", { name: "review in Payment Slips" })).toHaveAttribute(
      "href",
      "/tenant-admin/payments?tab=slips"
    );
    await expect(page.getByText("Paid", { exact: true }).first()).toBeVisible();

    await rows.first().getByTestId("history-expand").click();
    const details = rows.first().getByTestId("history-details");
    await expect(details).toContainText("Cash");
    await expect(details).toContainText("RCPT-77");
    await expect(details).toContainText("finance@inst.test");
    await expect(details.getByTestId("history-reverse")).toHaveAttribute("href", "/tenant-admin/payments?tab=refunds");

    await expect(page.getByTestId("free-card-row")).toHaveCount(1);
    await expect(page.getByTestId("free-card-row").getByTestId("free-card-status")).toHaveText("Active");
    await expect(page.getByTestId("paid-resources")).toContainText("Revision seminar");
    await expect(page.getByTestId("paid-resources")).toContainText("Not paid");
    await expect(page.getByTestId("add-payment-open")).toBeVisible();
    await expect(page.getByTestId("issue-free-card-open")).toBeVisible();
  });

  test("Finance Staff can add payments but not issue or revoke free cards", async ({ page }) => {
    await setup(page, "FINANCE_STAFF");
    await page.goto(SCOPED);
    await expect(page.getByTestId("add-payment-open")).toBeVisible();
    await expect(page.getByTestId("free-card-row")).toHaveCount(1);
    await expect(page.getByTestId("issue-free-card-open")).toHaveCount(0);
    await expect(page.getByTestId("free-card-revoke")).toHaveCount(0);
  });

  test("Student Support is read-only", async ({ page }) => {
    await setup(page, "STUDENT_SUPPORT");
    await page.goto(SCOPED);
    await expect(page.getByTestId("history-row")).toHaveCount(2);
    await expect(page.getByTestId("add-payment-open")).toHaveCount(0);
    await expect(page.getByTestId("issue-free-card-open")).toHaveCount(0);
    await expect(page.getByTestId("free-card-revoke")).toHaveCount(0);
    await page.getByTestId("history-row").first().getByTestId("history-expand").click();
    await expect(page.getByTestId("history-reverse")).toHaveCount(0);
  });

  test("add payment: discount chip, reason, confirmation and the saved receipt", async ({ page }) => {
    const state = await setup(page, "TENANT_ADMIN");
    await page.goto(SCOPED);
    await page.getByTestId("add-payment-open").click();
    const dialog = page.getByTestId("add-payment-dialog");

    await expect(dialog.getByLabel("2026 July – Paid")).toBeDisabled();
    await expect(dialog.getByLabel("2026 September – Pending payment")).toBeDisabled();
    await dialog.getByLabel("2026 August").check();
    // The ACTIVE free card covers August: warn, never block.
    await expect(dialog.getByTestId("free-card-warning")).toContainText(
      "Covered by a free card — recording a payment is still allowed"
    );
    await expect(dialog.getByTestId("add-payment-received-on")).toHaveValue(isoToday());

    // Required fields before the confirmation step.
    await expect(dialog.getByTestId("add-payment-net")).toHaveText("Net Amount: LKR 3,000.00");
    await dialog.getByTestId("add-payment-review").click();
    await expect(dialog.getByText("Select a payment method.")).toBeVisible();
    await dialog.getByLabel("Payment Method *").selectOption("BANK_TRANSFER");

    await dialog.getByTestId("add-payment-chip-50").click();
    await expect(dialog.getByTestId("add-payment-discount")).toHaveValue("1500.00");
    await expect(dialog.getByTestId("add-payment-net")).toHaveText("Net Amount: LKR 1,500.00");
    await dialog.getByTestId("add-payment-review").click();
    await expect(dialog.getByText("Enter a reason for the discount.")).toBeVisible();
    await dialog.getByLabel("Discount reason *").fill("Sibling discount");
    await dialog.getByLabel("Reference Number").fill("BT-1001");
    await dialog.getByTestId("add-payment-review").click();

    const confirm = dialog.getByTestId("add-payment-confirm");
    await expect(confirm).toContainText("John Doe");
    await expect(confirm).toContainText("2026 August");
    await expect(confirm).toContainText("Bank Transfer");
    await expect(confirm.getByTestId("confirm-net")).toHaveText("LKR 1,500.00");
    expect(state.records).toHaveLength(0);

    await confirm.getByTestId("add-payment-confirm-submit").click();
    const success = dialog.getByTestId("add-payment-success");
    await expect(success).toContainText("Payment recorded.");
    await expect(success.getByTestId("receipt-id")).toHaveText("r-new");
    await expect(success.getByTestId("receipt-payment-id")).toHaveText("p-new");
    await expect(success.getByTestId("receipt-amount")).toHaveText("LKR 1,500.00");

    expect(state.records).toHaveLength(1);
    const body = state.records[0];
    expect(body.items).toEqual([{ courseId: "c-py", scope: "MONTHLY", year: 2026, month: 8 }]);
    expect(body.postageCourseIds).toEqual([]);
    expect(body.quoteToken).toBe("qt-3000-1");
    expect(body.staffAdjustmentAmount).toBe("1500.00");
    expect(body.expectedReceivedAmount).toBe("1500.00");
    expect(body.adjustmentReason).toBe("Sibling discount");
    expect(body.paymentMethod).toBe("BANK_TRANSFER");
    expect(body.referenceNumber).toBe("BT-1001");
    expect(body.receivedOn).toBe(isoToday());
    expect(body.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(body).not.toHaveProperty("studentId");
    expect(body).not.toHaveProperty("tenantId");
  });

  test("double-click submits once; an unchanged retry reuses the idempotency key", async ({ page }) => {
    const state = await setup(page, "FINANCE_STAFF", {
      recordReplies: [{ status: 500, code: "INTERNAL_ERROR", message: "Temporary failure" }],
      recordDelayMs: 400,
    });
    await page.goto(SCOPED);
    const dialog = await fillAddPayment(page);
    await dialog.getByTestId("add-payment-review").click();
    await dialog.getByTestId("add-payment-confirm-submit").dblclick();
    await expect(dialog.getByTestId("add-payment-error")).toContainText("Temporary failure");
    await expect(dialog.getByTestId("add-payment-success")).toHaveCount(0);
    expect(state.records).toHaveLength(1);

    await dialog.getByTestId("add-payment-confirm-submit").click();
    await expect(dialog.getByTestId("add-payment-success")).toBeVisible();
    expect(state.records).toHaveLength(2);
    expect(state.records[1].idempotencyKey).toBe(state.records[0].idempotencyKey);
  });

  test("editing the form after a failed attempt sends a new idempotency key", async ({ page }) => {
    const state = await setup(page, "TENANT_ADMIN", {
      recordReplies: [{ status: 500, code: "INTERNAL_ERROR", message: "Temporary failure" }],
    });
    await page.goto(SCOPED);
    const dialog = await fillAddPayment(page);
    await dialog.getByTestId("add-payment-review").click();
    await dialog.getByTestId("add-payment-confirm-submit").click();
    await expect(dialog.getByTestId("add-payment-error")).toContainText("Temporary failure");

    await dialog.getByRole("button", { name: "Back" }).click();
    await dialog.getByLabel("Reference Number").fill("CASH-42");
    await dialog.getByTestId("add-payment-review").click();
    await dialog.getByTestId("add-payment-confirm-submit").click();
    await expect(dialog.getByTestId("add-payment-success")).toBeVisible();

    expect(state.records).toHaveLength(2);
    expect(state.records[1].referenceNumber).toBe("CASH-42");
    expect(state.records[1].idempotencyKey).not.toBe(state.records[0].idempotencyKey);
  });

  test("QUOTE_CHANGED shows the new quote and requires a fresh confirmation", async ({ page }) => {
    const state = await setup(page, "TENANT_ADMIN");
    state.recordReplies.push({
      status: 409,
      code: "QUOTE_CHANGED",
      message: "Quote changed",
      details: { quote: buildQuote({ items: [{ courseId: "c-py", year: 2026, month: 8 }], postageCourseIds: [] }, 3500) },
    });
    await page.goto(SCOPED);
    const dialog = await fillAddPayment(page);
    await dialog.getByTestId("add-payment-review").click();
    await dialog.getByTestId("add-payment-confirm-submit").click();

    await expect(dialog.getByTestId("add-payment-error")).toContainText("The amount changed since you reviewed it");
    await expect(dialog.getByTestId("add-payment-success")).toHaveCount(0);
    await expect(dialog.getByTestId("confirm-net")).toHaveText("LKR 3,500.00");
    expect(state.records).toHaveLength(1);

    await dialog.getByTestId("add-payment-confirm-submit").click();
    await expect(dialog.getByTestId("add-payment-success")).toBeVisible();
    expect(state.records[1].expectedReceivedAmount).toBe("3500.00");
    expect(state.records[1].quoteToken).toBe("qt-3500-1");
  });

  test("AMOUNT_MISMATCH and ITEM_PENDING are explained and nothing is shown as saved", async ({ page }) => {
    await setup(page, "TENANT_ADMIN", {
      recordReplies: [
        { status: 409, code: "AMOUNT_MISMATCH", message: "Amount mismatch" },
        { status: 422, code: "ITEM_PENDING", message: "Pending" },
      ],
    });
    await page.goto(SCOPED);
    const dialog = await fillAddPayment(page);
    await dialog.getByTestId("add-payment-review").click();
    await dialog.getByTestId("add-payment-confirm-submit").click();
    await expect(dialog.getByTestId("add-payment-error")).toContainText("doesn't match the server's amount");
    await expect(dialog.getByTestId("add-payment-success")).toHaveCount(0);

    await dialog.getByTestId("add-payment-confirm-submit").click();
    const error = dialog.getByTestId("add-payment-error");
    await expect(error).toContainText("already has a student payment waiting");
    await expect(error.getByRole("link", { name: "Open Payment Slips" })).toHaveAttribute(
      "href",
      "/tenant-admin/payments?tab=slips"
    );
    await expect(dialog.getByTestId("add-payment-success")).toHaveCount(0);
  });

  test("postage disables the discount; the server code maps to the same message", async ({ page }) => {
    await setup(page, "TENANT_ADMIN", {
      recordReplies: [{ status: 422, code: "ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED", message: "No" }],
    });
    await page.goto("/tenant-admin/payments?tab=free-cards&view=student&studentId=s-1&courseId=c-chem");
    await page.getByTestId("add-payment-open").click();
    const dialog = page.getByTestId("add-payment-dialog");
    await dialog.getByLabel("2026 August").check();
    await dialog.getByTestId("add-payment-chip-25").click();
    await expect(dialog.getByTestId("add-payment-discount")).toHaveValue("500.00");
    await dialog.getByLabel(/Postage for Chemistry/).check();
    await expect(dialog.getByTestId("add-payment-discount")).toBeDisabled();
    await expect(dialog.getByTestId("add-payment-discount")).toHaveValue("");
    await expect(dialog.getByTestId("add-payment-chip-25")).toBeDisabled();
    await expect(dialog.getByText("A discount can't be combined with postage").first()).toBeVisible();
    await expect(dialog.getByTestId("add-payment-net")).toHaveText("Net Amount: LKR 2,200.00");

    await dialog.getByLabel("Payment Method *").selectOption("CARD");
    await dialog.getByTestId("add-payment-review").click();
    await dialog.getByTestId("add-payment-confirm-submit").click();
    await expect(dialog.getByTestId("add-payment-error")).toContainText("A discount can't be combined with postage");
  });

  test("issue a free card: required dates and reason, Half disabled, confirmation", async ({ page }) => {
    const state = await setup(page, "TENANT_ADMIN");
    await page.goto(SCOPED);
    await page.getByTestId("issue-free-card-open").click();
    const dialog = page.getByTestId("issue-free-card-dialog");
    await expect(dialog).toContainText("Student: John Doe");
    await expect(dialog.getByLabel("Course *")).toHaveValue("c-py");
    await expect(dialog.getByLabel("Course *").locator("option", { hasText: "Python - LKR 3,000.00" })).toHaveCount(1);
    await expect(dialog.getByRole("option", { name: "Half (not supported yet)" })).toBeDisabled();
    await expect(dialog.getByText("Temporary Credit")).toHaveCount(0);
    const firstOfMonth = `${isoToday().slice(0, 7)}-01`;
    await expect(dialog.getByLabel("From *")).toHaveAttribute("min", firstOfMonth);

    await dialog.getByTestId("issue-free-card-review").click();
    await expect(dialog.getByText("Required. Free cards always have an end date.", { exact: true }).first()).toBeVisible();
    await expect(dialog.getByText("Enter a reason.")).toBeVisible();
    expect(state.issues).toHaveLength(0);

    const to = addMonthsEnd(isoToday(), 1);
    await dialog.getByLabel("From *").fill(firstOfMonth);
    await dialog.getByLabel("To *").fill(to);
    await dialog.getByLabel("Reason *").fill("Scholarship student");
    await dialog.getByTestId("issue-free-card-review").click();
    const confirm = dialog.getByTestId("issue-free-card-confirm");
    await expect(confirm).toContainText("John Doe");
    await expect(confirm.getByTestId("free-card-confirm-months")).toContainText(",");
    await confirm.getByTestId("issue-free-card-confirm-submit").click();

    await expect(dialog.getByTestId("issue-free-card-success")).toContainText("Free card issued.");
    await expect(dialog.getByTestId("free-card-enrolled")).toHaveText("Student was enrolled in this course.");
    expect(state.issues).toHaveLength(1);
    expect(state.issues[0]).toMatchObject({
      studentId: "s-1",
      courseId: "c-py",
      cardType: "FULL",
      validFrom: firstOfMonth,
      validTo: to,
      reason: "Scholarship student",
    });
    expect(state.issues[0].idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  test("an overlapping free card is refused with an explanation", async ({ page }) => {
    await setup(page, "TENANT_ADMIN", {
      issueReplies: [{ status: 409, code: "FREE_CARD_OVERLAP", message: "Overlap" }],
    });
    await page.goto(SCOPED);
    await page.getByTestId("issue-free-card-open").click();
    const dialog = page.getByTestId("issue-free-card-dialog");
    await dialog.getByLabel("From *").fill(`${isoToday().slice(0, 7)}-01`);
    await dialog.getByLabel("To *").fill(addMonthsEnd(isoToday(), 0));
    await dialog.getByLabel("Reason *").fill("Hardship");
    await dialog.getByTestId("issue-free-card-review").click();
    await dialog.getByTestId("issue-free-card-confirm-submit").click();
    await expect(dialog.getByTestId("issue-free-card-error")).toContainText("overlaps these dates");
    await expect(dialog.getByTestId("issue-free-card-success")).toHaveCount(0);
  });

  test("revoke a free card with a required reason", async ({ page }) => {
    const state = await setup(page, "TENANT_ADMIN");
    await page.goto(SCOPED);
    await page.getByTestId("free-card-revoke").click();
    await page.getByTestId("free-card-revoke-confirm").click();
    await expect(page.getByText("Enter a reason for revoking this free card.")).toBeVisible();
    expect(state.revokes).toHaveLength(0);
    await page.getByTestId("free-card-revoke-reason").fill("Issued by mistake");
    await page.getByTestId("free-card-revoke-confirm").click();
    await expect(page.getByTestId("free-card-row").getByTestId("free-card-status")).toHaveText("Revoked");
    expect(state.revokes).toEqual([{ id: "fc-1", body: { reason: "Issued by mistake" } }]);
  });

  test("a 403 renders the permission-denied state", async ({ page }) => {
    await setup(page, "TENANT_ADMIN", { forbidden: true });
    await page.goto(SCOPED);
    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(page.getByTestId("add-payment-open")).toHaveCount(0);
  });

  test("an unknown student id is reported, never trusted", async ({ page }) => {
    await setup(page, "TENANT_ADMIN");
    // The old Student Payments URL still works: it redirects into the hub, keeping the selection.
    await page.goto("/tenant-admin/payments/students?studentId=s-unknown&courseId=c-py");
    await expect(page).toHaveURL(/tab=free-cards&view=student&studentId=s-unknown&courseId=c-py$/);
    await expect(page.getByTestId("student-not-found")).toContainText("Student not found / not in your institute");
    await expect(page.getByTestId("history-row")).toHaveCount(0);
  });

  test("changing the student resets the course and the open form", async ({ page }) => {
    await setup(page, "TENANT_ADMIN");
    await page.goto(SCOPED);
    await expect(page.getByTestId("history-row")).toHaveCount(2);

    await page.getByTestId("student-select").selectOption("s-2");
    await expect(page).toHaveURL(/studentId=s-2$/);
    await expect(page.getByTestId("student-name")).toContainText("Jane Roe");
    await expect(page.getByTestId("course-select")).toHaveValue("");
    await expect(page.getByText("Choose one of Jane Roe's courses above to see its payments.")).toBeVisible();
    await expect(page.getByTestId("history-row")).toHaveCount(0);

    await page.getByTestId("course-select").selectOption("c-bio");
    await expect(page).toHaveURL(/studentId=s-2&courseId=c-bio/);
    await page.getByTestId("add-payment-open").click();
    const dialog = page.getByTestId("add-payment-dialog");
    await expect(dialog.getByLabel("2026 August")).not.toBeChecked();
    await expect(dialog.getByTestId("add-payment-reference")).toHaveValue("");
  });

  test("Free Cards list filters by status and revokes", async ({ page }) => {
    const state = await setup(page, "TENANT_ADMIN", {
      cards: [freeCard(), freeCard({ id: "fc-2", status: "EXPIRED", coveredMonths: ["2026-05"], validFrom: "2026-05-01", validTo: "2026-05-31" })],
    });
    // The old Free Cards URL redirects to the hub tab's "All free cards" view.
    await page.goto("/tenant-admin/payments/free-cards");
    await expect(page.getByRole("tab", { name: "Free Cards", selected: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "All free cards", pressed: true })).toBeVisible();
    const table = page.getByRole("table");
    await expect(table.getByRole("row")).toHaveCount(3);
    await page.getByTestId("free-cards-status-filter").selectOption("EXPIRED");
    await expect(table.getByRole("row")).toHaveCount(2);
    await expect(table).toContainText("2026 May");
    await page.getByTestId("free-cards-status-filter").selectOption("");
    await table.getByTestId("free-card-revoke").first().click();
    await page.getByTestId("free-card-revoke-reason").fill("Left the class");
    await page.getByTestId("free-card-revoke-confirm").click();
    await expect.poll(() => state.revokes.length).toBe(1);
  });
});
