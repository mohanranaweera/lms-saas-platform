import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * RPT-01 Teacher Income Summary (`docs/specs/teacher-income-summary-report.md`
 * §4.1). No real backend runs here; an in-memory fake answers
 * `/api/v1/reports/teacher-income/**` with the documented `ApiResponse<T>`
 * shapes. Authorization, tenant scoping, validation and every figure are
 * proven by the backend integration tests - these specs prove the UI: filter
 * validation, month selection, active-mode-only requests, result rendering,
 * stale-result export lock, authenticated PDF downloads and error states.
 */

async function mockTenantSession(page: Page, role = "FINANCE_STAFF"): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
}

const ADA = { id: "11111111-1111-1111-1111-111111111111", name: "Ada Lovelace" };
const ALAN = { id: "22222222-2222-2222-2222-222222222222", name: "Alan Turing" };
const RUN_ID = "33333333-3333-3333-3333-333333333333";

/**
 * Mirrors backend semantics: revenue = teacherIncome + commission + unratedRevenue,
 * totals = sums of the course rows, balance = teacherIncome - payouts. Defaults
 * match the LKR rows below (Algebra 100,000 = 50,000 + 50,000; Geometry 23,456 unrated).
 */
function totals(overrides: Record<string, unknown> = {}) {
  return {
    revenue: "123456.00",
    teacherIncome: "50000.00",
    commission: "50000.00",
    serviceFees: "500.00",
    payouts: "10000.00",
    payins: null,
    balance: "40000.00",
    paymentCount: 12,
    teacherCount: 1,
    unratedRevenue: "23456.00",
    ...overrides,
  };
}

function report(overrides: Record<string, unknown> = {}) {
  return {
    runId: RUN_ID,
    expiresAt: "2099-01-01T00:15:00Z",
    generatedAt: "2025-04-01T04:30:00Z",
    timezone: "Asia/Colombo",
    institute: { name: "Example Institute" },
    filters: {
      teachers: [ADA],
      paymentMethod: null,
      periodMode: "MONTHS",
      months: ["2024-12", "2025-01"],
      startDate: null,
      endDate: null,
      periodLabel: "Dec 2024, Jan 2025",
    },
    sections: [
      {
        currency: "LKR",
        totals: totals(),
        teachers: [
          {
            teacherId: ADA.id,
            teacherName: ADA.name,
            totals: totals(),
            courses: [
              { courseId: "c-1", courseName: "Algebra", paymentCount: 10, revenue: "100000.00", teacherIncome: "50000.00", commission: "50000.00", rateMissing: false },
              { courseId: "c-2", courseName: "Geometry", paymentCount: 2, revenue: "23456.00", teacherIncome: null, commission: null, rateMissing: true },
            ],
          },
        ],
      },
      {
        currency: "USD",
        totals: totals({ revenue: "40.00", teacherIncome: "20.00", commission: "20.00", serviceFees: "0.00", payouts: "0.00", balance: "20.00", paymentCount: 1, unratedRevenue: "0.00" }),
        teachers: [
          {
            teacherId: ADA.id,
            teacherName: ADA.name,
            totals: totals({ revenue: "40.00", teacherIncome: "20.00", commission: "20.00", serviceFees: "0.00", payouts: "0.00", balance: "20.00", paymentCount: 1, unratedRevenue: "0.00" }),
            courses: [
              { courseId: "c-3", courseName: "Physics", paymentCount: 1, revenue: "40.00", teacherIncome: "20.00", commission: "20.00", rateMissing: false },
            ],
          },
        ],
      },
    ],
    reconciliation: {
      byCurrency: [
        {
          currency: "LKR",
          grossRevenue: "130000.00",
          refunds: "6544.00",
          excludedNonCash: { count: 1, amount: "2000.00" },
          excludedNotMonthAttributable: { count: 0, amount: "0.00" },
          settledBasisDifference: "0.00",
          unassignedRevenue: "0.00",
        },
      ],
      notes: ["Service fees on teacher totals are not additive across teachers."],
    },
    ...overrides,
  };
}

interface FakeState {
  requests: { method: string; path: string; body: string | null; auth: string | null }[];
  forbidden?: boolean;
  runStatus?: number;
  runError?: ReturnType<typeof apiError>;
  runBody?: unknown;
  pdfStatus?: number;
  /** Delays the POST /runs response (to exercise cancel during an in-flight search). */
  runDelayMs?: number;
  teachers?: { id: string; name: string }[];
}

async function installReportsFake(page: Page, state: FakeState): Promise<void> {
  await page.route("**/api/v1/reports/teacher-income/**", async (route: Route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace(/^.*\/api\/v1\/reports\/teacher-income/, "");
    const method = request.method();
    state.requests.push({ method, path, body: request.postData(), auth: request.headers()["authorization"] ?? null });
    if (state.forbidden) {
      return fulfillJson(route, 403, apiError("FORBIDDEN", "Access denied"));
    }
    if (path === "/options" && method === "GET") {
      return fulfillJson(route, 200, apiSuccess({ teachers: state.teachers ?? [ADA, ALAN], paymentMethods: ["GATEWAY", "MANUAL_SLIP", "MANUAL_RECEIPT"] }));
    }
    if (path === "/runs" && method === "POST") {
      if (state.runDelayMs) {
        await new Promise((resolve) => setTimeout(resolve, state.runDelayMs));
        // The page may have aborted the request meanwhile; fulfilling then throws.
        return fulfillJson(route, 201, apiSuccess(report())).catch(() => undefined);
      }
      if (state.runStatus && state.runError) return fulfillJson(route, state.runStatus, state.runError);
      return fulfillJson(route, 201, apiSuccess(state.runBody ?? report()));
    }
    if (path === `/runs/${RUN_ID}/pdf` || path === `/runs/${RUN_ID}/teachers/${ADA.id}/payslip.pdf`) {
      if (state.pdfStatus === 404) return fulfillJson(route, 404, apiError("NOT_FOUND", "Report run not found"));
      return route.fulfill({
        status: 200,
        contentType: "application/pdf",
        headers: { "Content-Disposition": 'attachment; filename="report.pdf"', "Cache-Control": "no-store" },
        body: Buffer.from("%PDF-1.4 fake"),
      });
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", `Unmocked ${method} ${path}`));
  });
}

async function openReport(page: Page, state: FakeState, role?: string) {
  await mockTenantSession(page, role);
  await installReportsFake(page, state);
  await page.goto("/tenant-admin/reports/teacher-income");
  await expect(page.getByRole("heading", { name: "Teacher Income Summary Report" })).toBeVisible();
}

async function selectTeacher(page: Page, name: string) {
  await page.getByTestId("report-teachers").click();
  const popup = page.getByTestId("report-teachers-popup");
  await popup.getByTestId("report-teachers-search").fill(name.split(" ")[0]);
  await popup.getByRole("checkbox", { name }).check();
  await page.keyboard.press("Escape");
}

const currentYear = new Date().getFullYear();

async function searchMonths(page: Page) {
  await selectTeacher(page, ADA.name);
  await page.getByRole("button", { name: `March ${currentYear}` }).click();
  await page.getByRole("button", { name: "Search" }).click();
}

async function runMonthsReport(page: Page) {
  await searchMonths(page);
  await expect(page.getByTestId("teacher-income-results")).toBeVisible();
}

function runBodies(state: FakeState) {
  return state.requests
    .filter((r) => r.method === "POST" && r.path === "/runs")
    .map((r) => JSON.parse(r.body ?? "{}") as Record<string, unknown>);
}

test.describe("Reports navigation and catalog", () => {
  test("Finance Staff sees Reports in Finance and the catalog lists the Teacher Income report", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await page.goto("/tenant-admin/reports");
    await expect(page.getByRole("link", { name: "Reports", exact: true })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Teacher Income Summary Report" })).toBeVisible();
    await expect(page.getByText(/coming soon/i)).toHaveCount(0);
    await page.getByRole("link", { name: /Open report/ }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/reports\/teacher-income$/);
  });

  test("Content Manager sees no Reports entry", async ({ page }) => {
    await mockTenantSession(page, "CONTENT_MANAGER");
    await page.goto("/tenant-admin/reports");
    await expect(page.getByRole("link", { name: "Reports", exact: true })).toHaveCount(0);
  });
});

test.describe("Teacher income report filters", () => {
  test("shows the initial prompt and client validation for teacher and months", async ({ page }) => {
    const state: FakeState = { requests: [] };
    await openReport(page, state);
    await expect(page.getByText("Choose filters to run the report")).toBeVisible();
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByText("Select at least one teacher.")).toBeVisible();
    await expect(page.getByText("Select at least one month.")).toBeVisible();
    expect(runBodies(state)).toHaveLength(0);
  });

  test("teacher chips are removable", async ({ page }) => {
    await openReport(page, { requests: [] });
    await selectTeacher(page, ADA.name);
    await selectTeacher(page, ALAN.name);
    const chips = page.getByRole("list", { name: "Selected teachers" });
    await expect(chips.getByRole("listitem")).toHaveCount(2);
    await page.getByRole("button", { name: `Remove ${ADA.name}` }).click();
    await expect(chips.getByRole("listitem")).toHaveCount(1);
    await expect(chips).toContainText(ALAN.name);
  });

  test("month selection persists across years and the count reflects every year", async ({ page }) => {
    const state: FakeState = { requests: [] };
    await openReport(page, state);
    await expect(page.getByTestId("month-picker-year")).toHaveText(String(currentYear));
    await expect(page.getByTestId("months-selected-count")).toHaveText("0 months selected");

    const jan = page.getByRole("button", { name: `January ${currentYear}` });
    await jan.click();
    await expect(jan).toHaveAttribute("aria-pressed", "true");
    await page.getByRole("button", { name: `Previous year, ${currentYear - 1}` }).click();
    await expect(page.getByTestId("month-picker-year")).toHaveText(String(currentYear - 1));
    await page.getByRole("button", { name: `December ${currentYear - 1}` }).click();
    await expect(page.getByTestId("months-selected-count")).toHaveText("2 months selected");
    await expect(page.getByTestId("months-other-years")).toContainText(`Jan ${currentYear}`);

    await page.getByRole("button", { name: `Next year, ${currentYear}` }).click();
    await expect(page.getByRole("button", { name: `January ${currentYear}` })).toHaveAttribute("aria-pressed", "true");

    await selectTeacher(page, ADA.name);
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByTestId("teacher-income-results")).toBeVisible();
    const [body] = runBodies(state);
    expect(body.months).toEqual([`${currentYear - 1}-12`, `${currentYear}-01`]);
    expect(body.periodMode).toBe("MONTHS");
    expect(body).not.toHaveProperty("startDate");
    expect(body).not.toHaveProperty("endDate");
    expect(body).not.toHaveProperty("paymentMethod");
    expect(JSON.stringify(body)).not.toContain("tenant");
  });

  test("DATES mode validates the range and sends only the active mode's values", async ({ page }) => {
    const state: FakeState = { requests: [] };
    await openReport(page, state);
    await selectTeacher(page, ADA.name);
    // A month picked first must not leak into a DATES request.
    await page.getByRole("button", { name: `March ${currentYear}` }).click();
    await page.getByText("DATES", { exact: true }).click();
    await expect(page.getByRole("group", { name: "Payment received (confirmed) date" })).toBeVisible();

    await page.getByLabel("Start date").fill("2025-03-31");
    await page.getByLabel("End date").fill("2025-03-01");
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByText("Start date must be on or before the end date.")).toBeVisible();

    await page.getByLabel("Start date").fill("2024-01-01");
    await page.getByLabel("End date").fill("2025-03-01");
    await expect(page.getByText("The period must not exceed 366 days.")).toBeVisible();

    await page.getByLabel("Start date").fill("2025-03-01");
    await page.getByLabel("End date").fill("2025-03-31");
    await page.getByLabel("Payment method").click();
    await page.getByRole("option", { name: "Bank transfer (slip)" }).click();
    await page.getByRole("button", { name: "Search" }).click();
    await expect(page.getByTestId("teacher-income-results")).toBeVisible();

    const [body] = runBodies(state);
    expect(body).toEqual({
      teacherIds: [ADA.id],
      periodMode: "DATES",
      paymentMethod: "MANUAL_SLIP",
      startDate: "2025-03-01",
      endDate: "2025-03-31",
    });
  });

  test("server 422 field errors are rendered on the matching fields", async ({ page }) => {
    const state: FakeState = {
      requests: [],
      runStatus: 422,
      runError: apiError("REPORT_VALIDATION", "The report filter has invalid fields", [
        { field: "teacherIds[0]", message: "teacher not found" },
      ]),
    };
    await openReport(page, state);
    await searchMonths(page);
    await expect(page.getByText("teacher not found")).toBeVisible();
    await expect(page.getByTestId("filter-error-summary")).toBeVisible();
  });
});

test.describe("Teacher income report results", () => {
  test("renders currency sections, KPIs, teacher cards, Not tracked payins and notes", async ({ page }) => {
    await openReport(page, { requests: [] });
    await runMonthsReport(page);

    await expect(page.getByTestId("applied-period")).toHaveText("Dec 2024, Jan 2025");
    await expect(page.getByText("Asia/Colombo", { exact: false })).toBeVisible();

    const lkr = page.getByTestId("currency-section-LKR");
    const usd = page.getByTestId("currency-section-USD");
    await expect(lkr.getByRole("heading", { name: "Amounts in LKR" })).toBeVisible();
    await expect(usd.getByRole("heading", { name: "Amounts in USD" })).toBeVisible();
    await expect(lkr.getByText("Rs. 123,456.00").first()).toBeVisible();
    await expect(usd.getByText("USD 40.00").first()).toBeVisible();
    await expect(lkr.getByText("Teacher share (earned)")).toBeVisible();
    // KPIs show exactly the server's figures (the UI never computes them).
    const kpi = (label: string) => lkr.getByTestId("kpi").filter({ hasText: label });
    await expect(kpi("Total Revenue")).toContainText("Rs. 123,456.00");
    await expect(kpi("Teacher share (earned)")).toContainText("Rs. 50,000.00");
    await expect(kpi("Institute Commission")).toContainText("Rs. 50,000.00");
    await expect(kpi("Total Teachers")).toContainText("1");
    await expect(lkr.getByText(/Rs\. 23,456\.00 of revenue has no teacher share rate/)).toBeVisible();
    const usdKpi = (label: string) => usd.getByTestId("kpi").filter({ hasText: label });
    await expect(usdKpi("Institute Commission")).toContainText("USD 20.00");
    await expect(lkr.getByTestId("service-fees-LKR")).toContainText("institute only, not in the figures above");
    await expect(lkr.getByTestId("service-fees-LKR")).toContainText("Rs. 500.00");

    const card = lkr.getByTestId("teacher-card");
    await expect(card.getByText("Not tracked")).toBeVisible();
    await expect(card.getByText("Total income (earned share)")).toBeVisible();
    await expect(page.getByTestId("multi-currency-note")).toHaveText("Amounts in different currencies are never combined.");
    const table = card.getByRole("table", { name: /Course income for Ada Lovelace \(LKR\)/ });
    await expect(table.getByRole("columnheader")).toHaveText(["Course", "No. Payments", "Total", "Teacher share", "Commission"]);
    const geometry = table.getByRole("row", { name: /Geometry/ });
    await expect(geometry).toContainText("*");
    await expect(geometry).toContainText("—");

    await expect(page.getByTestId("reconciliation-notes")).toContainText("not additive");
    await lkr.getByRole("button", { name: "About Teacher share (earned)" }).first().click();
    await expect(page.getByText(/not the cash paid out/)).toBeVisible();
  });

  test("changing a filter after a run marks the result stale and disables export", async ({ page }) => {
    await openReport(page, { requests: [] });
    await runMonthsReport(page);
    const exportButton = page.getByRole("button", { name: "Export PDF" });
    await expect(exportButton).toBeEnabled();

    await page.getByRole("button", { name: `April ${currentYear}` }).click();
    await expect(page.getByTestId("stale-notice")).toBeVisible();
    await expect(exportButton).toBeDisabled();
    await expect(page.getByRole("button", { name: /Download payslip/ }).first()).toBeDisabled();

    // Reverting to the exact filters of the run re-enables export.
    await page.getByRole("button", { name: `April ${currentYear}` }).click();
    await expect(exportButton).toBeEnabled();
  });

  test("Export PDF and Payslip download authenticated PDFs", async ({ page }) => {
    const state: FakeState = { requests: [] };
    await openReport(page, state);
    await runMonthsReport(page);

    const [summary] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("button", { name: "Export PDF" }).click(),
    ]);
    expect(summary.suggestedFilename()).toBe("teacher-income-summary.pdf");

    const [payslip] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("button", { name: "Download payslip for Ada Lovelace (LKR)" }).click(),
    ]);
    expect(payslip.suggestedFilename()).toBe("payslip-ada-lovelace.pdf");

    const pdfRequests = state.requests.filter((r) => r.path.endsWith("pdf"));
    expect(pdfRequests.map((r) => r.path)).toEqual([`/runs/${RUN_ID}/pdf`, `/runs/${RUN_ID}/teachers/${ADA.id}/payslip.pdf`]);
    for (const r of pdfRequests) expect(r.auth).toMatch(/^Bearer /);
  });

  test("an expired run (404) on export shows a notice with Run again", async ({ page }) => {
    const state: FakeState = { requests: [], pdfStatus: 404 };
    await openReport(page, state);
    await runMonthsReport(page);
    await page.getByRole("button", { name: "Export PDF" }).click();
    const notice = page.getByTestId("expired-notice");
    await expect(notice).toContainText("This report has expired — run it again");
    await expect(page.getByRole("button", { name: "Export PDF" })).toBeDisabled();
    const payslip = page.getByRole("button", { name: "Download payslip for Ada Lovelace (LKR)" });
    await expect(payslip).toBeDisabled();
    await expect(payslip).toHaveAttribute("aria-describedby", "export-disabled-reason");

    await notice.getByRole("button", { name: "Run again" }).click();
    await expect(page.getByTestId("expired-notice")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Export PDF" })).toBeEnabled();
    expect(runBodies(state)).toHaveLength(2);
    expect(runBodies(state)[1]).toEqual(runBodies(state)[0]);
  });

  test("an empty run is announced, focused, and offers Change filters", async ({ page }) => {
    await openReport(page, { requests: [], runBody: report({ sections: [], reconciliation: { byCurrency: [], notes: [] } }) });
    await searchMonths(page);
    await expect(page.getByText("No payments to show", { exact: true })).toBeVisible();
    await expect(page.getByTestId("result-pane")).toBeFocused();
    await expect(page.locator(".sr-only[role=status]").filter({ hasText: "No payments to show." })).toHaveCount(1);
    await page.getByRole("button", { name: "Change filters" }).click();
    await expect(page.locator("#report-teachers")).toBeFocused();
  });

  test("Cancel during an in-flight search ignores the late result and keeps the filters", async ({ page }) => {
    const state: FakeState = { requests: [], runDelayMs: 1500 };
    await openReport(page, state);
    await searchMonths(page);
    await expect(page.getByText("Generating report…").first()).toBeVisible();
    await page.getByRole("button", { name: "Cancel search" }).click();
    await expect(page.getByText("Choose filters to run the report")).toBeVisible();
    // Filters are kept (Cancel only stopped the search).
    await expect(page.getByRole("list", { name: "Selected teachers" })).toContainText(ADA.name);
    await expect(page.getByTestId("months-selected-count")).toHaveText("1 month selected");
    // Wait past the delayed response: it must never apply.
    await page.waitForTimeout(2000);
    await expect(page.getByTestId("teacher-income-results")).toHaveCount(0);
    await expect(page.getByText("Choose filters to run the report")).toBeVisible();
    await expect(page.getByRole("button", { name: "Cancel", exact: true })).toBeVisible();
  });
});

test.describe("Teacher income report: no teachers", () => {
  test("Tenant Admin gets a link to the Teachers page", async ({ page }) => {
    await openNoTeachers(page, "TENANT_ADMIN");
    await expect(page.getByText("No teachers yet")).toBeVisible();
    await page.getByRole("button", { name: "Go to Teachers" }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/teachers$/);
  });

  test("Finance Staff gets guidance instead of a link", async ({ page }) => {
    await openNoTeachers(page, "FINANCE_STAFF");
    await expect(page.getByText("No teachers yet")).toBeVisible();
    await expect(page.getByText(/Ask a Tenant Admin to add teachers/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Go to Teachers" })).toHaveCount(0);
  });
});

async function openNoTeachers(page: Page, role: string) {
  await mockTenantSession(page, role);
  await installReportsFake(page, { requests: [], teachers: [] });
  await page.goto("/tenant-admin/reports/teacher-income");
}

test.describe("Teacher income report error states", () => {
  test("a 403 on options renders the permission-denied state", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await installReportsFake(page, { requests: [], forbidden: true });
    await page.goto("/tenant-admin/reports/teacher-income");
    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Search" })).toHaveCount(0);
  });

  test("a 403 on run renders the permission-denied state in the result pane", async ({ page }) => {
    await openReport(page, { requests: [], runStatus: 403, runError: apiError("FORBIDDEN", "Access denied") });
    await searchMonths(page);
    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
  });

  test("REPORT_TOO_LARGE at run creation shows the server message with a narrowing hint", async ({ page }) => {
    await openReport(page, {
      requests: [],
      runStatus: 422,
      runError: apiError("REPORT_TOO_LARGE", "The report is too large", [
        { field: "teacherIds", message: "too many ledger entries for this period" },
      ]),
    });
    await searchMonths(page);
    await expect(page.getByText(/^The report is too large\. Narrow the period or select fewer teachers/)).toBeVisible();
    await expect(page.getByText("too many ledger entries for this period")).toBeVisible();
  });

  test("429 rate limit shows a wait message", async ({ page }) => {
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
});
