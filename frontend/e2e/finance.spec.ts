import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Wave 7 — Tenant Admin Finance (expenses, categories, reports, teacher
 * payouts). No real backend runs here (see `fixtures/auth-mocks.ts`); a small
 * in-memory fake answers every `/api/v1/finance/**` call with the documented
 * `ApiResponse<T>` shapes. Every server-side rule (permissions, validation,
 * ledger-derived income, stored settlement figures) is independently proven
 * by the backend integration tests — these specs prove the UI states, forms,
 * filters and role-based visibility.
 */

async function mockTenantSession(page: Page, role: string): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
}

const RENT = {
  id: "cat-rent",
  name: "Rent",
  description: null,
  archived: false,
  createdAt: "2025-03-01T00:00:00Z",
  updatedAt: "2025-03-01T00:00:00Z",
};
const OLD = { ...RENT, id: "cat-old", name: "Old stuff", archived: true };

function expense(overrides: Record<string, unknown> = {}) {
  return {
    id: "exp-1",
    expenseDate: "2025-03-15",
    categoryId: RENT.id,
    categoryName: "Rent",
    description: "March rent",
    amount: 500,
    currency: "USD",
    method: "CASH",
    reference: "INV-9",
    hasAttachment: true,
    attachmentFilename: "receipt.pdf",
    attachmentMimeType: "application/pdf",
    attachmentSizeBytes: 1200,
    createdBy: "u-fin",
    createdByEmail: "finance@example.test",
    createdAt: "2025-03-15T10:00:00Z",
    voided: false,
    voidedAt: null,
    voidedBy: null,
    voidedByEmail: null,
    voidReason: null as string | null,
    ...overrides,
  };
}

function page0<T>(content: T[]) {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 };
}

const TEACHER = { userId: "t-1", name: "Ada Lovelace", email: "ada@example.test" };

function settlement(overrides: Record<string, unknown> = {}) {
  return {
    id: "stl-1",
    kind: "REGULAR",
    teacherId: TEACHER.userId,
    teacherEmail: TEACHER.email,
    periodStart: "2025-03-01",
    periodEnd: "2025-03-31",
    grossAmount: 240,
    refundAmount: 0,
    netAmount: 240,
    sharePercent: 40,
    shareAmount: 96,
    effectiveShareAmount: 96,
    currency: "USD",
    adjustsSettlementId: null,
    reason: null,
    status: "CALCULATED",
    calculatedBy: "u-fin",
    calculatedByEmail: "finance@example.test",
    calculatedAt: "2025-04-02T10:00:00Z",
    paidBy: null,
    paidByEmail: null,
    paidAt: null,
    payoutReference: null,
    ...overrides,
  };
}

interface FakeState {
  expenses: ReturnType<typeof expense>[];
  categories: (typeof RENT)[];
  forbidden?: boolean;
  requests: { method: string; path: string; body: string | null }[];
  createExpenseStatus?: number;
}

function freshState(overrides: Partial<FakeState> = {}): FakeState {
  return { expenses: [expense()], categories: [RENT, OLD], requests: [], ...overrides };
}

const SUMMARY = {
  from: "2025-03-01",
  to: "2025-04-30",
  timezone: "UTC",
  currency: "USD",
  income: { gross: 490, refunds: 30, net: 460, paymentCount: 4, refundCount: 1 },
  expenses: {
    total: 560.25,
    count: 2,
    byCategory: [{ categoryId: RENT.id, categoryName: "Rent", total: 500, count: 1 }],
    byMethod: [],
  },
  netResult: -100.25,
};

const PERIODS = {
  from: "2025-03-01",
  to: "2025-04-30",
  timezone: "UTC",
  currency: "USD",
  rows: [
    { period: "2025-03", from: "2025-03-01", to: "2025-03-31", incomeGross: 240, refunds: 0, incomeNet: 240, expenses: 500, netResult: -260 },
    { period: "2025-04", from: "2025-04-01", to: "2025-04-30", incomeGross: 250, refunds: 30, incomeNet: 220, expenses: 60.25, netResult: 159.75 },
  ],
};

const REVENUE_TOTALS = { gross: 200, refunds: 30, net: 170, paymentCount: 2, refundCount: 1 };

/** One route handler for the whole finance API surface. */
async function installFinanceFake(page: Page, state: FakeState): Promise<void> {
  await page.route("**/api/v1/finance/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^.*\/api\/v1\/finance/, "");
    const method = request.method();
    state.requests.push({ method, path, body: request.postData() });
    if (state.forbidden) {
      return fulfillJson(route, 403, apiError("FORBIDDEN", "Access denied"));
    }

    if (path === "/expense-categories" && method === "GET") {
      const include = url.searchParams.get("includeArchived") === "true";
      return fulfillJson(route, 200, apiSuccess(state.categories.filter((c) => include || !c.archived)));
    }
    if (path === "/expense-categories" && method === "POST") {
      const body = JSON.parse(request.postData() ?? "{}");
      if (state.categories.some((c) => c.name.toLowerCase() === String(body.name).toLowerCase())) {
        return fulfillJson(route, 409, apiError("CONFLICT", "An expense category with this name already exists"));
      }
      const created = { ...RENT, id: `cat-${state.categories.length + 1}`, name: body.name };
      state.categories.push(created);
      return fulfillJson(route, 201, apiSuccess(created));
    }
    if (path === "/expenses" && method === "GET") {
      const includeVoided = url.searchParams.get("includeVoided") === "true";
      const from = url.searchParams.get("from");
      let rows = state.expenses.filter((e) => includeVoided || !e.voided);
      if (from) rows = rows.filter((e) => e.expenseDate >= from);
      return fulfillJson(route, 200, apiSuccess(page0(rows)));
    }
    if (path === "/expenses" && method === "POST") {
      if (state.createExpenseStatus === 415) {
        return fulfillJson(route, 415, apiError("UNSUPPORTED_MEDIA_TYPE", "Bad receipt"));
      }
      const created = expense({ id: `exp-${state.expenses.length + 1}`, description: "New chairs", hasAttachment: false });
      state.expenses.push(created);
      return fulfillJson(route, 201, apiSuccess(created));
    }
    const voidMatch = path.match(/^\/expenses\/([^/]+)\/void$/);
    if (voidMatch && method === "POST") {
      const target = state.expenses.find((e) => e.id === voidMatch[1]);
      if (!target) return fulfillJson(route, 404, apiError("NOT_FOUND", "Expense not found"));
      Object.assign(target, { voided: true, voidReason: JSON.parse(request.postData() ?? "{}").reason });
      return fulfillJson(route, 200, apiSuccess(target));
    }
    if (/^\/expenses\/[^/]+\/attachment-url$/.test(path)) {
      return fulfillJson(route, 200, apiSuccess({ url: "https://storage.invalid/signed", expiresAt: "2099-01-01T00:00:00Z" }));
    }
    if (path === "/reports/summary") return fulfillJson(route, 200, apiSuccess(SUMMARY));
    if (path === "/reports/periods") return fulfillJson(route, 200, apiSuccess(PERIODS));
    if (path === "/reports/course-revenue") {
      return fulfillJson(route, 200, apiSuccess({
        from: "2025-03-01",
        to: "2025-04-30",
        currency: "USD",
        rows: [{ courseId: "course-x", courseTitle: "Algebra", teacherId: TEACHER.userId, ...REVENUE_TOTALS }],
        totals: REVENUE_TOTALS,
      }));
    }
    if (path === "/reports/teacher-revenue") {
      return fulfillJson(route, 200, apiSuccess({
        from: "2025-03-01",
        to: "2025-04-30",
        currency: "USD",
        rows: [{ teacherId: TEACHER.userId, teacherEmail: TEACHER.email, courseCount: 1, ...REVENUE_TOTALS }],
        totals: REVENUE_TOTALS,
      }));
    }
    if (path === "/teachers") return fulfillJson(route, 200, apiSuccess([TEACHER]));
    if (path === "/teacher-share-rates" && method === "GET") {
      return fulfillJson(route, 200, apiSuccess([
        { id: "r-1", teacherId: TEACHER.userId, teacherEmail: TEACHER.email, sharePercent: 40, effectiveFrom: "2025-01-01", createdBy: "u-fin", createdAt: "2025-01-01T00:00:00Z" },
      ]));
    }
    if (path === "/teacher-settlements" && method === "GET") {
      return fulfillJson(route, 200, apiSuccess(page0([settlement()])));
    }
    if (path === "/teacher-settlements" && method === "POST") {
      return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Period is not closed", [
        { field: "periodEnd", message: "must be before today - only closed periods can be settled" },
      ]));
    }
    if (path === "/teacher-settlements/stl-1" && method === "GET") {
      return fulfillJson(route, 200, apiSuccess({
        settlement: settlement({ effectiveShareAmount: 106.5 }),
        courses: [{ courseId: "course-x", courseTitle: "Algebra", gross: 240, refunds: 0, net: 240, entryCount: 3 }],
        adjustments: [
          settlement({ id: "adj-1", kind: "ADJUSTMENT", periodStart: null, periodEnd: null, shareAmount: 10.5, effectiveShareAmount: 10.5, adjustsSettlementId: "stl-1", reason: "Bonus session" }),
        ],
      }));
    }
    if (path === "/teacher-settlements/stl-1/mark-paid") {
      return fulfillJson(route, 200, apiSuccess({ settlement: settlement({ status: "PAID" }), courses: [], adjustments: [] }));
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", `Unmocked ${method} ${path}`));
  });
}

test.describe("Finance navigation", () => {
  test("Finance Staff sees every Finance entry", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await installFinanceFake(page, freshState());
    await page.goto("/tenant-admin/finance");
    for (const name of ["Finance Summary", "Expenses", "Expense Categories", "Finance Reports", "Teacher Payouts"]) {
      await expect(page.getByRole("link", { name, exact: true }).first()).toBeVisible();
    }
  });

  test("Content Manager sees no Finance entries", async ({ page }) => {
    await mockTenantSession(page, "CONTENT_MANAGER");
    await installFinanceFake(page, freshState({ forbidden: true }));
    await page.goto("/tenant-admin/finance");
    await expect(page.getByRole("link", { name: "Expenses", exact: true })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Teacher Payouts" })).toHaveCount(0);
  });
});

test.describe("Finance overview", () => {
  test("shows ledger-derived income, expenses and a clearly-marked negative net result", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await installFinanceFake(page, freshState());
    await page.goto("/tenant-admin/finance");
    await expect(page.getByRole("heading", { name: "Finance overview" })).toBeVisible();
    await expect(page.getByText("490.00")).toBeVisible();
    await expect(page.getByText("560.25").first()).toBeVisible();
    await expect(page.getByText("−100.25")).toBeVisible();
    const table = page.getByRole("table", { name: /Monthly income and expense summary/ });
    await expect(table.locator("tbody tr")).toHaveCount(2);
    await expect(table.getByText("159.75")).toBeVisible();
  });

  test("renders the permission-denied state on a 403", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await installFinanceFake(page, freshState({ forbidden: true }));
    await page.goto("/tenant-admin/finance");
    await expect(page.getByText(/permission|access/i).first()).toBeVisible();
    await expect(page.getByText("490.00")).toHaveCount(0);
  });
});

test.describe("Expenses", () => {
  test("lists expenses with a receipt link and no edit or delete control", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await installFinanceFake(page, freshState());
    await page.goto("/tenant-admin/finance/expenses");
    const table = page.getByRole("table", { name: "Expenses" });
    await expect(table.getByText("March rent")).toBeVisible();
    await expect(table.getByRole("button", { name: /View receipt for March rent/ })).toBeVisible();
    await expect(table.getByRole("button", { name: /Void expense March rent/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /^Edit/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Delete/ })).toHaveCount(0);
  });

  test("record form validates amounts client-side, then posts multipart without tenant or currency", async ({ page }) => {
    const state = freshState();
    await mockTenantSession(page, "FINANCE_STAFF");
    await installFinanceFake(page, state);
    await page.goto("/tenant-admin/finance/expenses");
    await page.getByRole("button", { name: "Record expense" }).click();

    await page.getByRole("button", { name: "Save expense" }).click();
    await expect(page.getByText("Choose a category.")).toBeVisible();
    await expect(page.getByText("Amount is required.")).toBeVisible();

    await page.locator("#expense-category").click();
    await expect(page.getByRole("option", { name: "Old stuff" })).toHaveCount(0);
    await page.getByRole("option", { name: "Rent" }).click();
    await page.getByLabel("Date", { exact: true }).fill("2025-03-20");
    await page.getByLabel("Payment method").click();
    await page.getByRole("option", { name: "Card" }).click();
    await page.getByLabel("Description").fill("New chairs");
    await page.getByLabel("Amount").fill("1.234");
    await page.getByRole("button", { name: "Save expense" }).click();
    await expect(page.locator("p[role=alert]", { hasText: /Up to 10 digits/ })).toBeVisible();
    await page.getByLabel("Amount").fill("0");
    await page.getByRole("button", { name: "Save expense" }).click();
    await expect(page.getByText("Amount must be greater than zero.")).toBeVisible();

    await page.getByLabel("Amount").fill("120.50");
    await page.getByRole("button", { name: "Save expense" }).click();
    await expect(page.getByRole("heading", { name: "Record an expense" })).toHaveCount(0);

    const post = state.requests.find((r) => r.method === "POST" && r.path === "/expenses");
    expect(post?.body).toContain("120.50");
    expect(post?.body).toContain("CARD");
    expect(post?.body).not.toContain("tenantId");
    expect(post?.body).not.toContain("currency");
  });

  test("a server-rejected receipt (415) is shown on the receipt field", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await installFinanceFake(page, freshState({ createExpenseStatus: 415 }));
    await page.goto("/tenant-admin/finance/expenses");
    await page.getByRole("button", { name: "Record expense" }).click();
    await page.locator("#expense-category").click();
    await page.getByRole("option", { name: "Rent" }).click();
    await page.getByLabel("Date", { exact: true }).fill("2025-03-20");
    await page.getByLabel("Payment method").click();
    await page.getByRole("option", { name: "Cash" }).click();
    await page.getByLabel("Description").fill("Spoofed");
    await page.getByLabel("Amount").fill("10");
    await page.getByLabel("Receipt (optional)").setInputFiles({
      name: "receipt.pdf",
      mimeType: "application/pdf",
      buffer: Buffer.from("MZ-not-really-a-pdf"),
    });
    await page.getByRole("button", { name: "Save expense" }).click();
    await expect(page.getByText("The receipt's content is not a valid PDF, PNG or JPEG.")).toBeVisible();
  });

  test("voiding requires a reason and keeps the record visible under Show voided", async ({ page }) => {
    const state = freshState();
    await mockTenantSession(page, "TENANT_ADMIN");
    await installFinanceFake(page, state);
    await page.goto("/tenant-admin/finance/expenses");

    await page.getByRole("button", { name: /Void expense March rent/ }).first().click();
    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Void expense" }).click();
    await expect(dialog.getByText("A reason is required.")).toBeVisible();
    await dialog.getByLabel("Reason").fill("Entered twice");
    await dialog.getByRole("button", { name: "Void expense" }).click();
    await expect(dialog).toHaveCount(0);

    await expect(page.getByText("No expenses recorded yet")).toBeVisible();
    await page.getByLabel("Show voided").check();
    await expect(page.getByRole("table", { name: "Expenses" }).getByText("Voided")).toBeVisible();
    await expect(page.getByRole("table", { name: "Expenses" }).getByText("Reason: Entered twice")).toBeVisible();
  });

  test("a filtered empty result offers Clear filters, distinct from the zero-data state", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await installFinanceFake(page, freshState());
    await page.goto("/tenant-admin/finance/expenses");
    await page.getByLabel("From").fill("2026-01-01");
    await page.getByRole("button", { name: "Apply" }).click();
    await expect(page.getByText("No expenses match your filters")).toBeVisible();
    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect(page.getByRole("table", { name: "Expenses" }).getByText("March rent")).toBeVisible();
  });

  test("Read-only Auditor can view receipts but sees no record or void actions", async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await installFinanceFake(page, freshState());
    await page.goto("/tenant-admin/finance/expenses");
    await expect(page.getByRole("table", { name: "Expenses" }).getByText("March rent")).toBeVisible();
    await expect(page.getByRole("button", { name: "Record expense" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /Void expense/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /View receipt/ }).first()).toBeVisible();
  });
});

test.describe("Expense categories", () => {
  test("a duplicate name surfaces the server 409 inline and no delete control exists", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await installFinanceFake(page, freshState());
    await page.goto("/tenant-admin/finance/categories");
    await expect(page.getByRole("table", { name: "Expense categories" }).getByText("Old stuff")).toBeVisible();
    await page.getByRole("button", { name: "New category" }).first().click();
    await page.locator("#category-name").fill("rent");
    await page.getByRole("button", { name: "Save category" }).click();
    await expect(page.getByText("A category with this name already exists.")).toBeVisible();
    await expect(page.getByRole("button", { name: /^Delete/ })).toHaveCount(0);
  });
});

test.describe("Finance reports", () => {
  test("course and teacher revenue render with teacher names", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await installFinanceFake(page, freshState());
    await page.goto("/tenant-admin/finance/reports");
    const courses = page.getByRole("table", { name: /Course revenue/ });
    await expect(courses.getByText("Algebra")).toBeVisible();
    await expect(courses.getByText("Ada Lovelace")).toBeVisible();
    await expect(courses.getByText("170.00")).toBeVisible();
    await expect(page.getByRole("table", { name: /Teacher revenue/ }).getByText("Ada Lovelace")).toBeVisible();
  });
});

test.describe("Teacher payouts", () => {
  test("lists statements with an accessible status chip and maps server period errors onto the form", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await installFinanceFake(page, freshState());
    await page.goto("/tenant-admin/finance/teacher-payouts");
    const table = page.getByRole("table", { name: "Teacher payout statements" });
    await expect(table.getByText("Ada Lovelace")).toBeVisible();
    await expect(table.getByText("Calculated — unpaid")).toBeVisible();
    await expect(page.getByRole("table", { name: "Revenue share rate history" }).getByText("40%")).toBeVisible();

    await page.getByRole("button", { name: "Calculate statement" }).click();
    await page.locator("#calc-teacher").click();
    await page.getByRole("option", { name: /Ada Lovelace/ }).click();
    await page.getByLabel("Period start").fill("2025-09-01");
    await page.getByLabel("Period end").fill("2025-08-01");
    await page.getByRole("button", { name: "Calculate", exact: true }).click();
    await expect(page.getByText("The end date must be on or after the start date.")).toBeVisible();
    await page.getByLabel("Period end").fill("2099-09-30");
    await page.getByRole("button", { name: "Calculate", exact: true }).click();
    await expect(page.getByText(/only closed periods can be settled/)).toBeVisible();
  });

  test("detail shows stored figures and adjustments; mark paid is available to finance roles", async ({ page }) => {
    const state = freshState();
    await mockTenantSession(page, "FINANCE_STAFF");
    await installFinanceFake(page, state);
    await page.goto("/tenant-admin/finance/teacher-payouts/stl-1");
    await expect(page.getByRole("heading", { name: /Ada Lovelace — 2025-03-01 to 2025-03-31/ })).toBeVisible();
    await expect(page.getByText("96.00 USD").first()).toBeVisible();
    await expect(page.getByText("106.50 USD")).toBeVisible();
    await expect(page.getByRole("table", { name: "Statement adjustments" }).getByText("Bonus session")).toBeVisible();

    await page.getByLabel("Payout reference (optional)").fill("BANK-42");
    await page.getByRole("button", { name: "Mark as paid" }).click();
    await expect
      .poll(() => state.requests.find((r) => r.path.endsWith("/mark-paid"))?.body ?? "")
      .toContain("BANK-42");
  });

  test("Read-only Auditor sees statements but no calculate, adjust or mark-paid controls", async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await installFinanceFake(page, freshState());
    await page.goto("/tenant-admin/finance/teacher-payouts");
    await expect(page.getByRole("table", { name: "Teacher payout statements" }).getByText("Ada Lovelace")).toBeVisible();
    await expect(page.getByRole("button", { name: "Calculate statement" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Set share rate" })).toHaveCount(0);

    await page.goto("/tenant-admin/finance/teacher-payouts/stl-1");
    await expect(page.getByText("106.50 USD")).toBeVisible();
    await expect(page.getByRole("button", { name: "Mark as paid" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Add adjustment" })).toHaveCount(0);
  });
});
