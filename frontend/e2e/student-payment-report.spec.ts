import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * RPT-02 Student Payment Report (`docs/specs/student-payment-report.md`).
 * No real backend runs here; an in-memory fake answers
 * `/api/v1/reports/student-payments/**` with the documented `ApiResponse<T>`
 * shapes. The fake pages and groups a stored row list the way the backend's
 * `StudentPaymentGrouping` does (group subtotals over the WHOLE run), so every
 * mock is internally consistent: Σ byMethod = coursePaymentTotal, and group
 * subtotals equal the sums of their rows. Authorization, tenant scoping,
 * validation and the figures themselves are proven by backend tests - these
 * specs prove the UI.
 */

async function mockTenantSession(page: Page, role = "FINANCE_STAFF"): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
}

const PHYSICS = { id: "c1111111-1111-1111-1111-111111111111", name: "Physics 2026 A/L" };
const CHEMISTRY = { id: "c2222222-2222-2222-2222-222222222222", name: "Chemistry 2026 A/L" };
const RUN_ID = "44444444-4444-4444-4444-444444444444";

interface Row {
  currency: string;
  entryId: string;
  receiptId: string;
  refund: boolean;
  studentId: string;
  studentName: string;
  indexNumber: string;
  courseId: string;
  courseName: string;
  reason: string;
  scope: string;
  method: string;
  manual: boolean;
  paymentDate: string;
  dateOnly: boolean;
  recordedAt: string;
  amount: string;
  serviceFee: string;
  postage: string;
}

const STUDENTS = {
  kamal: { studentId: "s0000000-0000-0000-0000-000000000084", studentName: "Kamal Perera", indexNumber: "0084" },
  nimali: { studentId: "s0000000-0000-0000-0000-000000000223", studentName: "Nimali Silva", indexNumber: "0223" },
  ashan: { studentId: "s0000000-0000-0000-0000-000000000105", studentName: "Ashan Fernando", indexNumber: "0105" },
  dilini: { studentId: "s0000000-0000-0000-0000-000000000042", studentName: "Dilini Jayasuriya", indexNumber: "0042" },
  ruwan: { studentId: "s0000000-0000-0000-0000-000000000310", studentName: "Ruwan Bandara", indexNumber: "0310" },
};

function row(
  student: (typeof STUDENTS)[keyof typeof STUDENTS],
  course: { id: string; name: string },
  entry: string,
  overrides: Partial<Row> = {}
): Row {
  return {
    currency: "LKR",
    entryId: `e-${entry}`,
    receiptId: `p-${entry}`,
    refund: false,
    ...student,
    courseId: course.id,
    courseName: course.name,
    reason: "2026-10",
    scope: "MONTHLY",
    method: "BANK_TRANSFER",
    manual: false,
    paymentDate: "2026-10-08T12:24:00Z",
    dateOnly: false,
    recordedAt: "2026-10-08T12:24:00Z",
    amount: "4000.00",
    serviceFee: "100.00",
    postage: "0.00",
    ...overrides,
  };
}

/**
 * The Klass reference: 5 students, 5 receipts, LKR 20,000.00 = bank 12,000.00
 * (R1 Kamal 2 courses, R3, R5) + online 8,000.00 (R2, R4); fees 500.00.
 */
const REFERENCE_ROWS: Row[] = [
  row(STUDENTS.kamal, PHYSICS, "1", { amount: "2000.00", serviceFee: "50.00" }),
  row(STUDENTS.kamal, CHEMISTRY, "1", { amount: "2000.00", serviceFee: "50.00" }),
  row(STUDENTS.nimali, PHYSICS, "2", { method: "ONLINE", paymentDate: "2026-10-08T12:30:00Z", recordedAt: "2026-10-08T12:30:00Z" }),
  row(STUDENTS.ashan, CHEMISTRY, "3", { paymentDate: "2026-10-08T13:00:00Z", recordedAt: "2026-10-08T13:00:00Z" }),
  row(STUDENTS.dilini, CHEMISTRY, "4", { method: "ONLINE", paymentDate: "2026-10-08T14:00:00Z", recordedAt: "2026-10-08T14:00:00Z" }),
  row(STUDENTS.ruwan, PHYSICS, "5", { paymentDate: "2026-10-08T15:00:00Z", recordedAt: "2026-10-08T15:00:00Z" }),
];

function summary(overrides: Record<string, unknown> = {}) {
  return {
    studentsPaid: 5,
    receiptCount: 5,
    refundCount: 0,
    rowCount: 6,
    coursePaymentTotal: "20000.00",
    byMethod: [
      { method: "BANK_TRANSFER", manual: false, total: "12000.00", receiptCount: 3 },
      { method: "ONLINE", manual: false, total: "8000.00", receiptCount: 2 },
    ],
    serviceFeeTotal: "500.00",
    postageTotal: "0.00",
    receivedTotal: "20500.00",
    ...overrides,
  };
}

function reconciliation(overrides: Record<string, unknown> = {}) {
  return {
    currency: "LKR",
    grossCoursePayments: "20000.00",
    refunds: "0.00",
    staffAdjustments: { count: 0, amount: "0.00" },
    excludedNonCash: { count: 0, amount: "0.00" },
    excludedNotMonthAttributable: { count: 0, amount: "0.00" },
    ...overrides,
  };
}

function report(overrides: Record<string, unknown> = {}, filterOverrides: Record<string, unknown> = {}) {
  return {
    runId: RUN_ID,
    expiresAt: "2099-01-01T00:15:00Z",
    generatedAt: "2026-10-22T04:30:00Z",
    timezone: "Asia/Colombo",
    institute: { name: "Example Institute" },
    filters: {
      courses: [],
      paymentMethod: null,
      periodMode: "MONTHS",
      months: ["2026-10"],
      startDate: null,
      endDate: null,
      periodLabel: "Oct 2026",
      ...filterOverrides,
    },
    sections: [{ currency: "LKR", summary: summary() }],
    reconciliation: { byCurrency: [reconciliation()], notes: ["Totals are net of refunds."] },
    rowCount: REFERENCE_ROWS.length,
    ...overrides,
  };
}

/** 60 single-course online receipts of 100.00 each (pagination fixture). */
const MANY_ROWS: Row[] = Array.from({ length: 60 }, (_, i) => {
  const n = String(i + 1).padStart(4, "0");
  return row(
    { studentId: `s-many-${n}`, studentName: `Student ${n}`, indexNumber: n },
    PHYSICS,
    `m${n}`,
    { method: "ONLINE", amount: "100.00", serviceFee: "0.00" }
  );
});

function manyReport() {
  return report({
    sections: [
      {
        currency: "LKR",
        summary: summary({
          studentsPaid: 60,
          receiptCount: 60,
          rowCount: 60,
          coursePaymentTotal: "6000.00",
          byMethod: [{ method: "ONLINE", manual: false, total: "6000.00", receiptCount: 60 }],
          serviceFeeTotal: "0.00",
          receivedTotal: "6000.00",
        }),
      },
    ],
    reconciliation: { byCurrency: [reconciliation({ grossCoursePayments: "6000.00" })], notes: [] },
    rowCount: 60,
  });
}

/** A manual cash receipt (date-only) + a refund of Nimali's online payment. Net 4,000 - 4,000 + 3,000 = 3,000. */
const REFUND_ROWS: Row[] = [
  row(STUDENTS.nimali, PHYSICS, "2", { method: "ONLINE", paymentDate: "2026-10-08T12:30:00Z", recordedAt: "2026-10-08T12:30:00Z" }),
  row(STUDENTS.ashan, CHEMISTRY, "7", {
    method: "CASH",
    manual: true,
    paymentDate: "2026-10-07",
    dateOnly: true,
    recordedAt: "2026-10-09T04:00:00Z",
    amount: "3000.00",
    serviceFee: "0.00",
    postage: "250.00",
  }),
  row(STUDENTS.nimali, PHYSICS, "8", {
    refund: true,
    receiptId: "p-2",
    method: "ONLINE",
    paymentDate: "2026-10-10T06:00:00Z",
    recordedAt: "2026-10-10T06:00:00Z",
    amount: "-4000.00",
    serviceFee: "-100.00",
  }),
];

function refundReport() {
  return report({
    sections: [
      {
        currency: "LKR",
        summary: summary({
          studentsPaid: 2,
          receiptCount: 2,
          refundCount: 1,
          rowCount: 3,
          coursePaymentTotal: "3000.00",
          byMethod: [
            { method: "ONLINE", manual: false, total: "0.00", receiptCount: 1 },
            { method: "CASH", manual: true, total: "3000.00", receiptCount: 1 },
          ],
          serviceFeeTotal: "0.00",
          postageTotal: "250.00",
          receivedTotal: "3250.00",
        }),
      },
    ],
    reconciliation: {
      byCurrency: [
        reconciliation({
          grossCoursePayments: "7000.00",
          refunds: "4000.00",
          staffAdjustments: { count: 1, amount: "500.00" },
        }),
      ],
      notes: [],
    },
    rowCount: 3,
  });
}

// ---------------------------------------------------------------------------
// Fake backend: pages + groups exactly like StudentPaymentGrouping.

function cmp(a: string, b: string): number {
  const ci = a.toLowerCase().localeCompare(b.toLowerCase());
  return ci !== 0 ? ci : a < b ? -1 : a > b ? 1 : 0;
}

function money(cents: number): string {
  const sign = cents < 0 ? "-" : "";
  const abs = Math.abs(cents);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, "0")}`;
}

function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

function groupIds(groupBy: string, r: Row): { id: string; level: string; key: string; parentKey: string | null }[] {
  const s = { id: `${r.currency}|STUDENT||${r.studentId}`, level: "STUDENT", key: r.studentId, parentKey: null };
  const c = { id: `${r.currency}|COURSE||${r.courseId}`, level: "COURSE", key: r.courseId, parentKey: null };
  const sc = { id: `${r.currency}|COURSE|${r.studentId}|${r.courseId}`, level: "COURSE", key: r.courseId, parentKey: r.studentId };
  if (groupBy === "STUDENT") return [s];
  if (groupBy === "COURSE") return [c];
  if (groupBy === "STUDENT_COURSE") return [s, sc];
  return [];
}

function rowsPage(all: Row[], groupBy: string, page: number, size: number) {
  const indexed = all.map((r, index) => ({ r, index }));
  const byStudent = (a: Row, b: Row) => cmp(a.studentName, b.studentName) || cmp(a.studentId, b.studentId);
  const byCourse = (a: Row, b: Row) => cmp(a.courseName, b.courseName) || cmp(a.courseId, b.courseId);
  indexed.sort((x, y) => {
    const a = x.r;
    const b = y.r;
    let result = 0;
    if (groupBy !== "NONE") result = cmp(a.currency, b.currency);
    if (!result && (groupBy === "STUDENT" || groupBy === "STUDENT_COURSE")) result = byStudent(a, b);
    if (!result && (groupBy === "COURSE" || groupBy === "STUDENT_COURSE")) result = byCourse(a, b);
    return result || x.index - y.index;
  });
  const sorted = indexed.map((x) => x.r);
  const groups = new Map<string, Record<string, unknown> & { _c: number; _f: number; _p: number; _receipts: Set<string>; rowCount: number }>();
  for (const r of sorted) {
    for (const g of groupIds(groupBy, r)) {
      let acc = groups.get(g.id);
      if (!acc) {
        acc = {
          level: g.level,
          key: g.key,
          parentKey: g.parentKey,
          label: g.level === "STUDENT" ? r.studentName : r.courseName,
          indexNumber: g.level === "STUDENT" ? r.indexNumber : null,
          currency: r.currency,
          _c: 0,
          _f: 0,
          _p: 0,
          _receipts: new Set<string>(),
          rowCount: 0,
        };
        groups.set(g.id, acc);
      }
      acc._c += cents(r.amount);
      acc._f += cents(r.serviceFee);
      acc._p += cents(r.postage);
      acc.rowCount += 1;
      if (!r.refund) acc._receipts.add(r.entryId);
    }
  }
  const from = Math.min(page * size, sorted.length);
  const to = Math.min(from + size, sorted.length);
  const onPage = new Map<string, unknown>();
  const rows = sorted.slice(from, to).map((r, i) => {
    for (const g of groupIds(groupBy, r)) {
      if (!onPage.has(g.id)) {
        const { _c, _f, _p, _receipts, ...rest } = groups.get(g.id)!;
        onPage.set(g.id, { ...rest, coursePayment: money(_c), serviceFee: money(_f), postage: money(_p), receiptCount: _receipts.size });
      }
    }
    return { rowNo: from + i + 1, ...r };
  });
  return {
    groupBy,
    page,
    size,
    totalRows: sorted.length,
    totalPages: sorted.length === 0 ? 0 : Math.ceil(sorted.length / size),
    rows,
    groups: [...onPage.values()],
  };
}

interface FakeState {
  requests: { method: string; path: string; query: URLSearchParams; body: string | null; auth: string | null }[];
  forbidden?: boolean;
  runStatus?: number;
  runError?: ReturnType<typeof apiError>;
  runBody?: unknown;
  rows?: Row[];
  /** Answer the next rows request for this page with 429 (then clear). */
  rowsRateLimitedPage?: number;
  rowsStatus?: number;
  pdfStatus?: number;
  courses?: { id: string; name: string }[];
}

async function installFake(page: Page, state: FakeState): Promise<void> {
  await page.route("**/api/v1/reports/student-payments/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^.*\/api\/v1\/reports\/student-payments/, "");
    const method = request.method();
    state.requests.push({ method, path, query: url.searchParams, body: request.postData(), auth: request.headers()["authorization"] ?? null });
    if (state.forbidden) return fulfillJson(route, 403, apiError("FORBIDDEN", "Access denied"));
    if (path === "/options" && method === "GET") {
      return fulfillJson(
        route,
        200,
        apiSuccess({
          courses: state.courses ?? [PHYSICS, CHEMISTRY],
          paymentMethods: ["GATEWAY", "MANUAL_SLIP", "MANUAL_RECEIPT"],
          groupByModes: ["NONE", "STUDENT", "COURSE", "STUDENT_COURSE"],
        })
      );
    }
    if (path === "/runs" && method === "POST") {
      if (state.runStatus && state.runError) return fulfillJson(route, state.runStatus, state.runError);
      return fulfillJson(route, 201, apiSuccess(state.runBody ?? report()));
    }
    if (path === `/runs/${RUN_ID}/rows`) {
      if (state.rowsStatus === 404) return fulfillJson(route, 404, apiError("NOT_FOUND", "Report run not found"));
      const pageNo = Number(url.searchParams.get("page") ?? "0");
      if (state.rowsRateLimitedPage === pageNo) {
        state.rowsRateLimitedPage = undefined;
        return fulfillJson(route, 429, apiError("RATE_LIMITED", "Too many requests"));
      }
      return fulfillJson(
        route,
        200,
        apiSuccess(rowsPage(state.rows ?? REFERENCE_ROWS, url.searchParams.get("groupBy") ?? "NONE", pageNo, Number(url.searchParams.get("size") ?? "50")))
      );
    }
    if (path === `/runs/${RUN_ID}/pdf`) {
      if (state.pdfStatus === 404) return fulfillJson(route, 404, apiError("NOT_FOUND", "Report run not found"));
      return route.fulfill({
        status: 200,
        contentType: "application/pdf",
        headers: { "Content-Disposition": 'attachment; filename="student-payment-report.pdf"', "Cache-Control": "no-store" },
        body: Buffer.from("%PDF-1.4 fake"),
      });
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", `Unmocked ${method} ${path}`));
  });
}

async function openReport(page: Page, state: FakeState, role?: string) {
  await mockTenantSession(page, role);
  await installFake(page, state);
  await page.goto("/tenant-admin/reports/student-payments");
  await expect(page.getByRole("heading", { name: "Student Payment Report" })).toBeVisible();
}

const currentYear = new Date().getFullYear();

async function searchMonths(page: Page) {
  await page.getByRole("button", { name: `October ${currentYear}` }).click();
  await page.getByRole("button", { name: "Search" }).click();
}

async function runReport(page: Page) {
  await searchMonths(page);
  await expect(page.getByTestId("student-payment-results")).toBeVisible();
  await expect(page.getByRole("table").getByTestId("payment-row").first()).toBeVisible();
}

function runBodies(state: FakeState) {
  return state.requests
    .filter((r) => r.method === "POST" && r.path === "/runs")
    .map((r) => JSON.parse(r.body ?? "{}") as Record<string, unknown>);
}

function rowsRequests(state: FakeState) {
  return state.requests.filter((r) => r.path.endsWith("/rows"));
}

async function selectCourse(page: Page, name: string) {
  await page.getByTestId("report-courses").click();
  const popup = page.getByTestId("report-courses-popup");
  await popup.getByTestId("report-courses-search").fill(name.split(" ")[0]);
  await popup.getByRole("checkbox", { name }).check();
  await page.keyboard.press("Escape");
}

test.describe("Student payment report: catalog", () => {
  test("the catalog lists the Student Payment report and opens it", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await installFake(page, { requests: [] });
    await page.goto("/tenant-admin/reports");
    await expect(page.getByRole("heading", { name: "Student Payment Report" })).toBeVisible();
    await page.getByRole("link", { name: /Open report.*Student Payment Report/ }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/reports\/student-payments$/);
    await expect(page.getByRole("heading", { name: "Student Payment Report", level: 1 })).toBeVisible();
  });
});

test.describe("Student payment report: filters", () => {
  test("courses are optional; selected courses become removable chips and are sent", async ({ page }) => {
    const state: FakeState = { requests: [] };
    await openReport(page, state);
    await expect(page.getByText("Choose filters to run the report")).toBeVisible();
    await expect(page.getByTestId("report-courses")).toContainText("All courses");

    await runReport(page);
    expect(runBodies(state)[0]).toEqual({ periodMode: "MONTHS", months: [`${currentYear}-10`] });
    await expect(page.getByTestId("applied-filters")).toContainText("Course: All courses");

    await selectCourse(page, PHYSICS.name);
    await selectCourse(page, CHEMISTRY.name);
    const chips = page.getByRole("list", { name: "Selected courses" });
    await expect(chips.getByRole("listitem")).toHaveCount(2);
    await page.getByRole("button", { name: `Remove ${CHEMISTRY.name}` }).click();
    await expect(chips.getByRole("listitem")).toHaveCount(1);
    await page.getByRole("button", { name: "Search" }).click();
    await expect.poll(() => runBodies(state).length).toBe(2);
    expect(runBodies(state)[1]).toEqual({ courseIds: [PHYSICS.id], periodMode: "MONTHS", months: [`${currentYear}-10`] });
  });

  test("DATES: a single date sends start = end, an optional to-date sends a range, and months never leak", async ({ page }) => {
    const state: FakeState = { requests: [] };
    await openReport(page, state);
    await page.getByRole("button", { name: `March ${currentYear}` }).click();
    await page.getByText("DATES", { exact: true }).click();
    await expect(page.getByTestId("period-hint")).toHaveText(
      "Payment received date (manual receipts use the date the money was received)."
    );

    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByText("Choose a date.")).toBeVisible();
    expect(runBodies(state)).toHaveLength(0);

    await page.locator("#report-start-date").fill("2026-10-08");
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByTestId("student-payment-results")).toBeVisible();
    expect(runBodies(state)[0]).toEqual({ periodMode: "DATES", startDate: "2026-10-08", endDate: "2026-10-08" });

    await page.getByLabel("To date (optional)").fill("2026-10-01");
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByText("The end date must be on or after the date.")).toBeVisible();

    await page.getByLabel("To date (optional)").fill("2026-10-31");
    await page.getByRole("combobox", { name: "Payment method" }).click();
    await page.getByRole("option", { name: "Online (gateway)" }).click();
    await page.getByRole("button", { name: "Search" }).click();
    await expect.poll(() => runBodies(state).length).toBe(2);
    expect(runBodies(state)[1]).toEqual({
      periodMode: "DATES",
      paymentMethod: "GATEWAY",
      startDate: "2026-10-08",
      endDate: "2026-10-31",
    });
    expect(JSON.stringify(runBodies(state))).not.toContain("tenant");
  });

  test("MONTHS across years are sent sorted, with no date fields", async ({ page }) => {
    const state: FakeState = { requests: [] };
    await openReport(page, state);
    await page.getByRole("button", { name: `January ${currentYear}` }).click();
    await page.getByRole("button", { name: `Previous year, ${currentYear - 1}` }).click();
    await page.getByRole("button", { name: `December ${currentYear - 1}` }).click();
    await expect(page.getByTestId("months-selected-count")).toHaveText("2 months selected");
    await expect(page.getByTestId("months-other-years")).toContainText(`Jan ${currentYear}`);
    // A date typed in DATES mode must not leak into a MONTHS request.
    await page.getByText("DATES", { exact: true }).click();
    await page.locator("#report-start-date").fill("2026-10-08");
    await page.getByText("MONTHS", { exact: true }).click();
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByTestId("student-payment-results")).toBeVisible();
    expect(runBodies(state)[0]).toEqual({ periodMode: "MONTHS", months: [`${currentYear - 1}-12`, `${currentYear}-01`] });
  });

  test("server 422 field errors are rendered on the matching fields", async ({ page }) => {
    const state: FakeState = {
      requests: [],
      runStatus: 422,
      runError: apiError("REPORT_VALIDATION", "The report filter has invalid fields", [
        { field: "courseIds[0]", message: "course not found" },
        { field: "months", message: "at most 36 months can be selected" },
      ]),
    };
    await openReport(page, state);
    await selectCourse(page, PHYSICS.name);
    await searchMonths(page);
    await expect(page.getByText("course not found")).toBeVisible();
    await expect(page.getByText("at most 36 months can be selected")).toBeVisible();
    await expect(page.getByTestId("filter-error-summary")).toBeFocused();
    await expect(page.getByText("Check the filters")).toBeVisible();
  });

  test("server 422 on endDate is shown under the to-date field", async ({ page }) => {
    const state: FakeState = {
      requests: [],
      runStatus: 422,
      runError: apiError("REPORT_VALIDATION", "invalid", [{ field: "endDate", message: "the period must not exceed 366 days" }]),
    };
    await openReport(page, state);
    await page.getByText("DATES", { exact: true }).click();
    await page.locator("#report-start-date").fill("2026-10-08");
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.locator("#report-end-date-error")).toHaveText("the period must not exceed 366 days");
  });
});

test.describe("Student payment report: results", () => {
  test("renders the reference fixture: summary, method totals, fees, leading-zero index and rows", async ({ page }) => {
    const state: FakeState = { requests: [] };
    await openReport(page, state);
    await runReport(page);

    await expect(page.getByRole("heading", { name: "Payment history report — Oct 2026" })).toBeFocused();
    await expect(page.getByTestId("generated-at")).toContainText("(Asia/Colombo)");
    await expect(page.getByTestId("applied-filters")).toContainText("Payment method: All methods");

    const lkr = page.getByTestId("summary-LKR");
    await expect(lkr.getByTestId("summary-headline-LKR")).toHaveText("Filtered results show 5 students paid, totaling LKR 20,000.00");
    const methods = lkr.getByTestId("method-total");
    await expect(methods.filter({ hasText: "Bank transfer (slip)" })).toContainText("LKR 12,000.00");
    await expect(methods.filter({ hasText: "Online (gateway)" })).toContainText("LKR 8,000.00");
    await expect(lkr.getByTestId("service-fees-LKR")).toHaveText(/Service fees collected on top of these amounts\s*LKR 500\.00/);
    await expect(lkr.getByTestId("received-total-LKR")).toContainText("LKR 20,500.00");
    await expect(lkr.getByTestId("postage-LKR")).toHaveCount(0);
    await expect(lkr.getByTestId("refunds-LKR")).toHaveCount(0);
    const kpi = (label: string) => lkr.getByTestId("kpi").filter({ hasText: label });
    await expect(kpi("Total amount")).toContainText("LKR 20,000.00");
    await expect(kpi("Total students")).toContainText("5");
    await expect(kpi("Total payments")).toContainText("5");

    const table = page.getByRole("table");
    await expect(table.getByRole("columnheader")).toHaveText([
      "#", "Index no.", "Student", "Course", "Reason", "Payment method", "Date", "Amount (LKR)",
    ]);
    await expect(table.getByTestId("payment-row")).toHaveCount(6);
    const first = table.getByTestId("payment-row").first();
    await expect(first.getByTestId("index-number")).toHaveText("0084");
    await expect(first).toContainText("Kamal Perera");
    await expect(first).toContainText("Bank transfer (slip)");
    await expect(first.getByTestId("payment-date")).toHaveText("08 Oct 2026");
    await expect(first.getByTestId("payment-time")).toHaveText("05:54 pm");
    await expect(first.getByTestId("amount-cell")).toContainText("2,000.00");
    await expect(first.getByTestId("amount-cell")).toContainText("+ 50.00 fee");
    await expect(table.getByTestId("group-row")).toHaveCount(0);
    await expect(page.getByTestId("reconciliation-notes")).toContainText("net of refunds");
    expect(rowsRequests(state)[0].query.get("groupBy")).toBe("NONE");
    expect(rowsRequests(state)[0].query.get("page")).toBe("0");
  });

  test("grouping switches re-page the same run (no new run), keep totals, and drive the PDF groupBy", async ({ page }) => {
    const state: FakeState = { requests: [] };
    await openReport(page, state);
    await runReport(page);
    const headline = page.getByTestId("summary-headline-LKR");
    const table = page.getByRole("table");

    await page.getByRole("switch", { name: "Group by student" }).click();
    await expect(page.getByRole("switch", { name: "Group by student" })).toHaveAttribute("aria-checked", "true");
    const kamal = table.getByTestId("group-row").filter({ hasText: "Student: Kamal Perera" });
    await expect(kamal).toContainText("Index no. 0084");
    await expect(kamal.getByTestId("group-subtotal")).toContainText("1 payment · Subtotal LKR 4,000.00 + 100.00 fee");
    await expect(table.getByTestId("group-row")).toHaveCount(5);
    expect(rowsRequests(state).at(-1)!.query.get("groupBy")).toBe("STUDENT");

    await page.getByRole("switch", { name: "Group by course" }).click();
    await expect.poll(() => rowsRequests(state).at(-1)!.query.get("groupBy")).toBe("STUDENT_COURSE");
    // Kamal's student header, then his two nested course groups.
    await expect(table.getByTestId("course-group")).toHaveCount(6);
    // Students sort by name (Ashan, Dilini, Kamal, ...); Kamal's 2-course receipt splits into two nested groups.
    const groupTexts = await table.getByTestId("group-row").allInnerTexts();
    const kamalAt = groupTexts.findIndex((text) => text.includes("Student: Kamal Perera"));
    expect(kamalAt).toBe(4);
    expect(groupTexts[5]).toContain("Course: Chemistry 2026 A/L");
    expect(groupTexts[5]).toContain("Subtotal LKR 2,000.00 + 50.00 fee");
    expect(groupTexts[6]).toContain("Course: Physics 2026 A/L");
    expect(groupTexts[6]).toContain("Subtotal LKR 2,000.00 + 50.00 fee");
    await expect(table.getByTestId("payment-row").first()).toContainText("Ashan Fernando");

    await page.getByRole("switch", { name: "Group by student" }).click();
    await expect.poll(() => rowsRequests(state).at(-1)!.query.get("groupBy")).toBe("COURSE");
    await expect(table.getByTestId("group-row")).toHaveCount(2);
    await expect(table.getByTestId("group-row").filter({ hasText: "Physics" })).toContainText("3 payments · Subtotal LKR 10,000.00 + 250.00 fee");

    // Totals unchanged, no new run, not stale, export still enabled.
    await expect(headline).toHaveText("Filtered results show 5 students paid, totaling LKR 20,000.00");
    expect(runBodies(state)).toHaveLength(1);
    await expect(page.getByTestId("stale-notice")).toHaveCount(0);

    await page.getByRole("switch", { name: "Group by student" }).click();
    const [download] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("button", { name: "Export PDF" }).click(),
    ]);
    expect(download.suggestedFilename()).toBe("student-payment-report.pdf");
    const pdf = state.requests.filter((r) => r.path.endsWith("/pdf"));
    expect(pdf).toHaveLength(1);
    expect(pdf[0].path).toBe(`/runs/${RUN_ID}/pdf`);
    expect(pdf[0].query.get("groupBy")).toBe("STUDENT_COURSE");
    expect(pdf[0].auth).toMatch(/^Bearer /);
  });

  test("pagination requests the next page of the same run", async ({ page }) => {
    const state: FakeState = { requests: [], rows: MANY_ROWS, runBody: manyReport() };
    await openReport(page, state);
    await runReport(page);
    await expect(page.getByTestId("page-indicator")).toHaveText("Page 1 of 2");
    await expect(page.getByTestId("rows-range")).toHaveText("Showing 1–50 of 60");
    await expect(page.getByRole("button", { name: "Previous", exact: true })).toBeDisabled();
    await page.getByRole("button", { name: "Next", exact: true }).click();
    await expect(page.getByTestId("page-indicator")).toHaveText("Page 2 of 2");
    await expect(page.getByTestId("rows-range")).toHaveText("Showing 51–60 of 60");
    await expect(page.getByRole("table").getByTestId("payment-row")).toHaveCount(10);
    await expect(page.getByRole("button", { name: "Next", exact: true })).toBeDisabled();
    expect(rowsRequests(state).at(-1)!.query.get("page")).toBe("1");
    expect(rowsRequests(state).at(-1)!.query.get("size")).toBe("50");
    expect(runBodies(state)).toHaveLength(1);
  });

  test("a 429 on a rows page keeps the current page visible with a retry", async ({ page }) => {
    const state: FakeState = { requests: [], rows: MANY_ROWS, runBody: manyReport(), rowsRateLimitedPage: 1 };
    await openReport(page, state);
    await runReport(page);
    await page.getByRole("button", { name: "Next", exact: true }).click();
    await expect(page.getByTestId("rows-error")).toContainText("Too many requests — wait a moment");
    await expect(page.getByTestId("page-indicator")).toHaveText("Page 1 of 2");
    await expect(page.getByRole("table").getByTestId("payment-row")).toHaveCount(50);
    await page.getByTestId("rows-error").getByRole("button", { name: "Try again" }).click();
    await expect(page.getByTestId("page-indicator")).toHaveText("Page 2 of 2");
    await expect(page.getByTestId("rows-error")).toHaveCount(0);
  });

  test("refund rows are marked with text and negative amounts; manual receipts show the recorded time", async ({ page }) => {
    await openReport(page, { requests: [], rows: REFUND_ROWS, runBody: refundReport() });
    await runReport(page);
    const table = page.getByRole("table");
    const refund = table.getByTestId("payment-row").filter({ hasText: "Refund" });
    await expect(refund).toHaveCount(1);
    await expect(refund.getByTestId("amount-cell")).toContainText("-4,000.00");
    await expect(refund.getByTestId("amount-cell")).toContainText("− 100.00 fee");
    const manual = table.getByTestId("payment-row").filter({ hasText: "Manual – cash" });
    await expect(manual).toContainText("07 Oct 2026");
    await expect(manual).toContainText("recorded 09 Oct 2026, 09:30 am");
    await expect(manual.getByTestId("amount-cell")).toContainText("+ 250.00 postage");

    const lkr = page.getByTestId("summary-LKR");
    await expect(lkr.getByTestId("refunds-LKR")).toContainText("1 refund");
    await expect(lkr.getByTestId("refunds-LKR")).toContainText("LKR 4,000.00");
    await expect(lkr.getByTestId("postage-LKR")).toContainText("LKR 250.00");
    await expect(lkr.getByTestId("staff-adjustments-LKR")).toContainText("LKR 500.00");
    await expect(lkr.getByTestId("method-total").filter({ hasText: "Manual – cash" })).toContainText("LKR 3,000.00");
  });

  test("changing a filter marks the result stale and disables export", async ({ page }) => {
    await openReport(page, { requests: [] });
    await runReport(page);
    const exportButton = page.getByRole("button", { name: "Export PDF" });
    await expect(exportButton).toBeEnabled();
    await page.getByRole("button", { name: `November ${currentYear}` }).click();
    await expect(page.getByTestId("stale-notice")).toBeVisible();
    await expect(exportButton).toBeDisabled();
    await expect(exportButton).toHaveAttribute("aria-describedby", "export-disabled-reason");
    await page.getByRole("button", { name: `November ${currentYear}` }).click();
    await expect(exportButton).toBeEnabled();
  });

  test("an expired run (404) on rows shows a notice without a stuck loading table", async ({ page }) => {
    const state: FakeState = { requests: [], rowsStatus: 404 };
    await openReport(page, state);
    await searchMonths(page);
    await expect(page.getByTestId("expired-notice")).toContainText("This report has expired — run it again");
    await expect(page.getByText("Loading payments…")).toHaveCount(0);
    await expect(page.getByRole("table")).toHaveCount(0);
  });

  test("an expired run (404) on export shows a notice with Run again", async ({ page }) => {
    const state: FakeState = { requests: [], pdfStatus: 404 };
    await openReport(page, state);
    await runReport(page);
    await page.getByRole("button", { name: "Export PDF" }).click();
    const notice = page.getByTestId("expired-notice");
    await expect(notice).toContainText("This report has expired — run it again");
    await expect(page.getByRole("button", { name: "Export PDF" })).toBeDisabled();
    await notice.getByRole("button", { name: "Run again" }).click();
    await expect(page.getByTestId("expired-notice")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Export PDF" })).toBeEnabled();
    expect(runBodies(state)).toHaveLength(2);
    expect(runBodies(state)[1]).toEqual(runBodies(state)[0]);
  });

  test("an empty run is announced, focused, and offers Change filters", async ({ page }) => {
    await openReport(page, {
      requests: [],
      runBody: report({ sections: [], reconciliation: { byCurrency: [], notes: [] }, rowCount: 0 }),
    });
    await searchMonths(page);
    await expect(page.getByText("No payments to show", { exact: true })).toBeVisible();
    await expect(page.getByTestId("result-pane")).toBeFocused();
    await expect(page.locator(".sr-only[role=status]").filter({ hasText: "No payments to show." })).toHaveCount(1);
    await page.getByRole("button", { name: "Change filters" }).click();
    await expect(page.locator("#report-courses")).toBeFocused();
  });
});

test.describe("Student payment report: error states", () => {
  test("a 403 on options renders the permission-denied state", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await installFake(page, { requests: [], forbidden: true });
    await page.goto("/tenant-admin/reports/student-payments");
    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Search" })).toHaveCount(0);
  });

  test("a 403 on run renders the permission-denied state in the result pane", async ({ page }) => {
    await openReport(page, { requests: [], runStatus: 403, runError: apiError("FORBIDDEN", "Access denied") });
    await searchMonths(page);
    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
  });

  test("429 on run shows a wait message", async ({ page }) => {
    await openReport(page, { requests: [], runStatus: 429, runError: apiError("RATE_LIMITED", "Too many requests") });
    await searchMonths(page);
    await expect(page.getByTestId("result-pane").getByText("Too many report requests, please wait a moment and try again.")).toBeVisible();
  });

  test("503 shows a temporarily unavailable message with retry", async ({ page }) => {
    await openReport(page, { requests: [], runStatus: 503, runError: apiError("SERVICE_UNAVAILABLE", "Unavailable") });
    await searchMonths(page);
    await expect(page.getByRole("alert").filter({ hasText: "Reports are temporarily unavailable. Please try again shortly." })).toBeVisible();
    await expect(page.getByTestId("result-pane")).toBeFocused();
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
  });

  test("REPORT_TOO_LARGE at run creation suggests narrowing", async ({ page }) => {
    await openReport(page, {
      requests: [],
      runStatus: 422,
      runError: apiError("REPORT_TOO_LARGE", "The report is too large", [{ field: "period", message: "has 25000 rows; at most 20000" }]),
    });
    await searchMonths(page);
    await expect(page.getByText(/^The report is too large\. Narrow the period or select fewer courses/)).toBeVisible();
  });
});
