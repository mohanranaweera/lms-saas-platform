import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * FIN-01 Klass-style Finance tabs (Student Income, Budget, Accounts,
 * Transactions, Scheduled, Categories). No real backend runs here: an
 * in-memory fake answers `/api/v1/finance/**` with the documented shapes
 * (`docs/api/finance-expense-management.md`, "Finance account book"). Every
 * server rule - capabilities, tenant scoping, immutable amounts, posting once,
 * stale balances - is proven by the backend integration suites; these specs
 * prove the tabs, states, forms, request shapes and role-based visibility.
 */

const FINANCE_VIEW_CAPS = [
  "FINANCE_VIEW_INCOME_SUMMARY",
  "FINANCE_DASHBOARD_ANNUAL_STATS",
  "FINANCE_DASHBOARD_TRANSACTIONS",
  "FINANCE_VIEW_SCHEDULES",
  "FINANCE_VIEW_CATEGORY",
  "FINANCE_VIEW_EXPENSES",
];
const FINANCE_STAFF_CAPS = [
  ...FINANCE_VIEW_CAPS,
  "FINANCE_ACCOUNTS_MANAGE",
  "FINANCE_RECORD_TRANSACTION",
  "FINANCE_ADJUST_ACCOUNT_BALANCE",
  "FINANCE_REVERSE_TRANSACTION",
  "FINANCE_MANAGE_SCHEDULES",
  "FINANCE_ADD_CATEGORY",
  "FINANCE_DELETE_CATEGORY",
];

const CASH = {
  id: "acc-cash",
  name: "Cash in hand",
  description: "Front desk",
  currency: "LKR",
  balance: 1500,
  archived: false,
  mappedMethods: ["CASH"],
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};
const BANK = { ...CASH, id: "acc-bank", name: "Main bank", description: null, balance: 14800, mappedMethods: ["BANK_TRANSFER", "GATEWAY", "MANUAL_SLIP"] };
const RENT = { id: "cat-rent", name: "Rent", description: null, archived: false, createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" };
const FEES = { ...RENT, id: "cat-fees", name: "Class fees" };

function tx(overrides: Record<string, unknown> = {}) {
  return {
    id: "tx-manual",
    type: "INCOME",
    status: "POSTED",
    amount: 2500,
    currency: "LKR",
    fromAccountId: null,
    fromAccountName: null,
    toAccountId: CASH.id,
    toAccountName: CASH.name,
    categoryId: FEES.id,
    categoryName: FEES.name,
    transactionDate: "2026-11-10",
    postedAt: "2026-11-10T08:00:00Z",
    note: "Hall hire",
    sourceType: "MANUAL",
    sourceId: null,
    reversesTransactionId: null,
    reversedByTransactionId: null,
    balanceBefore: null,
    balanceAfter: null,
    reason: null,
    createdBy: "u-fin",
    createdByEmail: "finance@example.test",
    createdAt: "2026-11-10T08:00:00Z",
    reversedAt: null,
    reversibleHere: true,
    correctionOwner: null,
    ...overrides,
  };
}

const PAYMENT_TX = tx({
  id: "tx-payment",
  amount: 3000,
  toAccountId: BANK.id,
  toAccountName: BANK.name,
  categoryId: null,
  categoryName: null,
  note: "Student payment",
  sourceType: "LEDGER_ENTRY",
  sourceId: "le-1",
  reversibleHere: false,
  correctionOwner: "PAYMENTS",
});
const UNPOSTED_TX = tx({ id: "tx-unposted", status: "UNPOSTED", amount: 800, toAccountId: null, toAccountName: null, categoryId: null, categoryName: null, note: "Card payment", sourceType: "LEDGER_ENTRY", reversibleHere: false, correctionOwner: "PAYMENTS" });

const SCHEDULE = {
  id: "sch-rent",
  name: "Office rent",
  type: "EXPENSE",
  amount: 40000,
  currency: "LKR",
  fromAccountId: BANK.id,
  fromAccountName: BANK.name,
  toAccountId: null,
  toAccountName: null,
  categoryId: RENT.id,
  categoryName: RENT.name,
  note: null,
  scheduleType: "MONTHLY",
  scheduledDate: null,
  dayOfMonth: 31,
  startsOn: "2026-01-01",
  status: "ACTIVE",
  nextDueDate: "2026-12-31",
  openOccurrences: 1,
  postedOccurrences: 8,
  failedOccurrences: 1,
  skippedOccurrences: 0,
  lastExecution: null,
  skippedMonths: ["2026-02", "2026-04"],
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
  cancelledAt: null,
};
const FAILED_OCCURRENCE = {
  id: "occ-1",
  scheduleId: SCHEDULE.id,
  dueDate: "2026-10-31",
  status: "FAILED",
  transactionId: null,
  attempts: 1,
  lastError: "Account Main bank is archived",
  executedAt: null,
  executedBy: null,
};

const MONTH_LABELS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
function income(year: number, withWallet: boolean) {
  return {
    year,
    currency: "LKR",
    timezone: "Asia/Colombo",
    generatedAt: "2026-11-16T08:00:00Z",
    totals: {
      grossIncome: 22900,
      refunds: 900,
      netIncome: 22000,
      teacherShare: 19910,
      instituteCommission: 2090,
      serviceFees: 0,
      unratedRevenue: 0,
      expenses: 101920,
      netAfterExpenses: -79920,
      paymentCount: 8,
      averagePayment: 2862.5,
    },
    months: MONTH_LABELS.map((label, i) => ({
      month: `${year}-${String(i + 1).padStart(2, "0")}`,
      label,
      income: i === 10 ? 3000 : 1000,
      refunds: 0,
      netIncome: i === 10 ? 3000 : 1000,
      expenses: 500,
      commission: 100,
      teacherShare: 900,
      paymentCount: 1,
    })),
    methods: {
      onlineCount: 2,
      onlineTotal: 3100,
      manualCount: 6,
      manualTotal: 19800,
      onlinePercent: 13.5,
      byCode: [{ code: "CASH", label: "Cash", online: false, count: 6, total: 19800 }],
    },
    teacherRows: [
      { teacherId: "t-1", teacherName: "Demo Teacher", month: `${year}-01`, monthLabel: "January", income: 2000, forInstitute: 200, forTeacher: 1800, rateMissing: false },
      { teacherId: "t-2", teacherName: "New Teacher", month: `${year}-02`, monthLabel: "February", income: 100, forInstitute: null, forTeacher: null, rateMissing: true },
    ],
    wallet: withWallet
      ? {
          teacherId: "t-1",
          walletId: "w-1",
          currency: "LKR",
          balance: 1470,
          unpaidSettlements: 1,
          walletEntries: 2,
          payIns: [],
          payouts: [{ entryId: "we-1", kind: "PAYOUT", amount: 500, description: "October payout", recordedAt: "2026-11-01T00:00:00Z" }],
        }
      : null,
    accountBook: { postedStudentIncome: 21200, unpostedCount: 1 },
    notes: ["Student income comes from the payment ledger."],
  };
}

function budget(empty: boolean) {
  return {
    from: "2026-10-31",
    to: "2026-11-29",
    timezone: "Asia/Colombo",
    currency: "LKR",
    totalBalance: 16300,
    incomeForPeriod: empty ? 0 : 5500,
    grossIncomeForPeriod: empty ? 0 : 5500,
    refundsForPeriod: 0,
    expensesForPeriod: empty ? 0 : 7000,
    netActivity: empty ? 0 : -1500,
    adjustmentsForPeriod: 0,
    transfersForPeriod: empty ? 0 : 1000,
    incomeCount: empty ? 0 : 2,
    expenseCount: empty ? 0 : 1,
    incomeByCategory: empty ? [] : [{ categoryId: FEES.id, label: "Class fees", total: 5500, count: 2 }],
    expensesByCategory: empty ? [] : [{ categoryId: RENT.id, label: "Rent", total: 7000, count: 1 }],
    accountBalances: [
      { accountId: CASH.id, name: CASH.name, description: null, balance: 1500 },
      { accountId: BANK.id, name: BANK.name, description: null, balance: 14800 },
    ],
    trendGranularity: "DAY",
    cashFlowTrend: empty ? [] : [
      { period: "2026-11-01", income: 2500, expenses: 0, net: 2500 },
      { period: "2026-11-02", income: 3000, expenses: 7000, net: -4000 },
    ],
    notes: ["Transfers and adjustments are not income or expenses."],
  };
}

interface FakeState {
  accounts: (typeof CASH)[];
  transactions: ReturnType<typeof tx>[];
  unposted: ReturnType<typeof tx>[];
  schedules: (typeof SCHEDULE)[];
  due: (typeof FAILED_OCCURRENCE)[];
  wallet: boolean;
  emptyBudget: boolean;
  requests: { method: string; path: string; body: string | null; idempotencyKey: string | null }[];
}

function freshState(overrides: Partial<FakeState> = {}): FakeState {
  return {
    accounts: [CASH, BANK],
    transactions: [tx(), PAYMENT_TX],
    unposted: [UNPOSTED_TX],
    schedules: [SCHEDULE],
    due: [FAILED_OCCURRENCE],
    wallet: false,
    emptyBudget: false,
    requests: [],
    ...overrides,
  };
}

function page0<T>(content: T[], size = 10) {
  return { content, page: 0, size, totalElements: content.length, totalPages: content.length ? 1 : 0 };
}

async function setUp(page: Page, role: string, capabilities: string[], state: FakeState): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  await mockJson(page, "**/v1/me/permissions", 200, apiSuccess({ role, capabilities, permissionVersion: 1 }));
  await page.route("**/api/v1/finance/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^.*\/api\/v1\/finance/, "");
    const method = request.method();
    const body = request.postData();
    state.requests.push({ method, path, body, idempotencyKey: (await request.allHeaders())["idempotency-key"] ?? null });

    if (path === "/expense-categories" && method === "GET") return fulfillJson(route, 200, apiSuccess([RENT, FEES]));
    if (path === "/accounts" && method === "GET") {
      const include = url.searchParams.get("includeArchived") === "true";
      return fulfillJson(route, 200, apiSuccess(state.accounts.filter((a) => include || !a.archived)));
    }
    if (path === "/accounts" && method === "POST") {
      const input = JSON.parse(body ?? "{}");
      if (state.accounts.some((a) => a.name.toLowerCase() === String(input.name).toLowerCase())) {
        return fulfillJson(route, 409, apiError("CONFLICT", "An account with this name already exists"));
      }
      const created = { ...CASH, id: `acc-${state.accounts.length + 1}`, name: input.name, description: input.description, balance: input.openingBalance, mappedMethods: [] };
      state.accounts.push(created);
      return fulfillJson(route, 201, apiSuccess(created));
    }
    if (path === "/accounts/routing" && method === "GET") {
      return fulfillJson(route, 200, apiSuccess({ cashAccountId: CASH.id, cardAccountId: null, bankOnlineAccountId: BANK.id, otherAccountId: null, methods: {}, updatedAt: null }));
    }
    if (path === "/accounts/routing" && method === "PUT") return fulfillJson(route, 200, apiSuccess({ ...JSON.parse(body ?? "{}"), methods: {}, updatedAt: null }));
    const adjust = path.match(/^\/accounts\/([^/]+)\/adjustments$/);
    if (adjust && method === "POST") {
      const input = JSON.parse(body ?? "{}");
      const account = state.accounts.find((a) => a.id === adjust[1]);
      if (!account) return fulfillJson(route, 404, apiError("NOT_FOUND", "Account not found"));
      if (input.expectedBalance !== account.balance) return fulfillJson(route, 409, apiError("CONFLICT", "The balance changed. Reload and try again."));
      account.balance = input.targetBalance;
      return fulfillJson(route, 201, apiSuccess(tx({ id: "tx-adj", type: "ADJUSTMENT", reason: input.reason })));
    }
    if (path === "/transactions" && method === "GET") {
      const type = url.searchParams.get("type");
      const rows = state.transactions.filter((t) => !type || t.type === type);
      return fulfillJson(route, 200, apiSuccess(page0(rows)));
    }
    if (path === "/transactions" && method === "POST") {
      const input = JSON.parse(body ?? "{}");
      const created = tx({ id: `tx-${state.transactions.length + 1}`, type: input.type, amount: input.amount, note: input.note });
      state.transactions.unshift(created);
      return fulfillJson(route, 201, apiSuccess(created));
    }
    if (path === "/transactions/unposted") return fulfillJson(route, 200, apiSuccess(state.unposted));
    const reverse = path.match(/^\/transactions\/([^/]+)\/reverse$/);
    if (reverse && method === "POST") {
      const target = state.transactions.find((t) => t.id === reverse[1]);
      if (!target) return fulfillJson(route, 404, apiError("NOT_FOUND", "Transaction not found"));
      target.status = "REVERSED";
      return fulfillJson(route, 201, apiSuccess(tx({ id: "tx-rev", type: "REVERSAL", reversesTransactionId: target.id })));
    }
    const post = path.match(/^\/transactions\/([^/]+)\/post$/);
    if (post && method === "POST") {
      state.unposted = state.unposted.filter((t) => t.id !== post[1]);
      return fulfillJson(route, 201, apiSuccess(tx({ id: post[1], status: "POSTED" })));
    }
    if (path === "/schedules" && method === "GET") {
      const status = url.searchParams.get("status");
      return fulfillJson(route, 200, apiSuccess(page0(state.schedules.filter((s) => !status || s.status === status), 20)));
    }
    if (path === "/schedules" && method === "POST") {
      const input = JSON.parse(body ?? "{}");
      const created = { ...SCHEDULE, id: "sch-new", name: input.name, dayOfMonth: input.dayOfMonth, skippedMonths: [] };
      state.schedules.push(created);
      return fulfillJson(route, 201, apiSuccess(created));
    }
    if (path === "/schedules/due") return fulfillJson(route, 200, apiSuccess(state.due));
    const occurrencePost = path.match(/^\/schedules\/occurrences\/([^/]+)\/post$/);
    if (occurrencePost && method === "POST") {
      state.due = state.due.filter((o) => o.id !== occurrencePost[1]);
      return fulfillJson(route, 201, apiSuccess({ ...FAILED_OCCURRENCE, status: "POSTED", lastError: null }));
    }
    if (path === "/budget") return fulfillJson(route, 200, apiSuccess(budget(state.emptyBudget)));
    if (path === "/student-income") return fulfillJson(route, 200, apiSuccess(income(Number(url.searchParams.get("year")), state.wallet)));
    const month = path.match(/^\/student-income\/months\/(\d{4}-\d{2})$/);
    if (month) {
      const d = income(Number(month[1].slice(0, 4)), false);
      const payments =
        month[1].endsWith("-11")
          ? [{ ledgerEntryId: "le-1", orderId: "o-1", paymentId: "p-1", refund: false, amount: 3000, currency: "LKR", method: "Cash", methodCode: "CASH", date: `${month[1]}-05`, recordedAt: `${month[1]}-05T10:00:00Z`, courseCount: 2 }]
          : [];
      return fulfillJson(route, 200, apiSuccess({ month: month[1], currency: "LKR", timezone: "Asia/Colombo", totals: d.totals, methods: d.methods, teacherRows: [], payments }));
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", `Unmocked ${method} ${path}`));
  });
}

const TABS = ["Student Income", "Budget Dashboard", "Accounts", "Transactions", "Scheduled", "Categories"];

test.describe("Finance hub tabs", () => {
  test("six deep-linkable tabs, Student Income first and marked current", async ({ page }) => {
    await setUp(page, "TENANT_ADMIN", [], freshState());
    await page.goto("/tenant-admin/finance");
    const tabs = page.getByRole("navigation", { name: "Finance sections" });
    for (const name of TABS) await expect(tabs.getByRole("link", { name })).toBeVisible();
    await expect(tabs.getByRole("link", { name: "Student Income" })).toHaveAttribute("aria-current", "page");
    await tabs.getByRole("link", { name: "Accounts" }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/finance\/accounts$/);
    await expect(tabs.getByRole("link", { name: "Accounts" })).toHaveAttribute("aria-current", "page");
  });

  test("Student Income shows ledger figures, month drilldown and the book comparison; no wallet panel for an admin", async ({ page }) => {
    await setUp(page, "TENANT_ADMIN", [], freshState());
    await page.goto("/tenant-admin/finance");
    await expect(page.getByTestId("income-net")).toHaveText("22000.00 LKR");
    await expect(page.getByText("-79920.00 LKR after expenses")).toBeVisible();
    await expect(page.getByTestId("wallet-panel")).toHaveCount(0);
    await expect(page.getByRole("table", { name: "Income by teacher and month" }).getByText("No rate").first()).toBeVisible();
    await expect(page.getByTestId("income-book-comparison")).toContainText("1 payment is waiting");

    await page.getByLabel("Month", { exact: true }).selectOption({ label: "November" });
    await expect(page.getByRole("table", { name: /Payments in November/ }).getByText("3000.00 LKR")).toBeVisible();
    await page.getByLabel("Month", { exact: true }).selectOption({ label: "March" });
    await expect(page.getByText(/No payments recorded in March/)).toBeVisible();
  });

  test("the Wallet panel appears only when the server returns the viewer's own wallet", async ({ page }) => {
    await setUp(page, "TENANT_ADMIN", [], freshState({ wallet: true }));
    await page.goto("/tenant-admin/finance");
    const wallet = page.getByTestId("wallet-panel");
    await expect(wallet).toContainText("Balance: 1470.00 LKR");
    await expect(wallet).toContainText("No pay-ins to display.");
    await wallet.getByRole("button", { name: "Payouts" }).click();
    await expect(wallet).toContainText("October payout");
  });

  test("Budget dashboard: populated and empty periods", async ({ page }) => {
    const state = freshState();
    await setUp(page, "TENANT_ADMIN", [], state);
    await page.goto("/tenant-admin/finance/budget");
    await expect(page.getByTestId("stat-total-balance")).toContainText("16300.00 LKR");
    await expect(page.getByTestId("stat-net")).toContainText("-1500.00 LKR");
    await expect(page.getByRole("list", { name: "Expenses by category" }).getByText("Rent")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Cash flow trend" })).toBeVisible();

    state.emptyBudget = true;
    await page.getByLabel("Start date").fill("2026-01-01");
    await page.getByRole("button", { name: "Apply" }).click();
    await expect(page.getByText("Not enough data for this period.").first()).toBeVisible();
    expect(state.requests.some((r) => r.path === "/budget" && r.method === "GET")).toBe(true);
  });
});

test.describe("Accounts", () => {
  test("cards show balances; Configure saves routing without any tenant id", async ({ page }) => {
    const state = freshState();
    await setUp(page, "TENANT_ADMIN", [], state);
    await page.goto("/tenant-admin/finance/accounts");
    await expect(page.getByTestId("account-card-acc-bank").getByTestId("account-balance")).toHaveText("14800.00 LKR");
    await expect(page.getByTestId("account-card-acc-bank")).toContainText("Bank slip");

    await page.getByRole("button", { name: "Configure" }).click();
    const dialog = page.getByTestId("routing-dialog");
    await dialog.getByLabel("Card account").selectOption({ label: "Main bank" });
    await dialog.getByRole("button", { name: "Save" }).click();
    await expect(dialog).toHaveCount(0);
    const put = state.requests.find((r) => r.method === "PUT" && r.path === "/accounts/routing");
    expect(JSON.parse(put?.body ?? "{}")).toEqual({ cashAccountId: CASH.id, cardAccountId: BANK.id, bankOnlineAccountId: BANK.id, otherAccountId: null });
    expect(put?.body).not.toContain("tenant");
  });

  test("new account validates, surfaces a duplicate-name 409, then creates", async ({ page }) => {
    const state = freshState();
    await setUp(page, "TENANT_ADMIN", [], state);
    await page.goto("/tenant-admin/finance/accounts");
    await page.getByRole("button", { name: "New account" }).click();
    const dialog = page.getByTestId("account-dialog");
    await dialog.getByRole("button", { name: "Create account" }).click();
    await expect(dialog.getByText("Enter a name.")).toBeVisible();
    await dialog.getByLabel("Name").fill("cash in hand");
    await dialog.getByRole("button", { name: "Create account" }).click();
    await expect(dialog.getByText("An account with this name already exists")).toBeVisible();
    await dialog.getByLabel("Name").fill("Card terminal");
    await dialog.getByLabel("Opening balance").fill("250.50");
    await dialog.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByRole("heading", { name: "Card terminal" })).toBeVisible();
    const post = state.requests.filter((r) => r.method === "POST" && r.path === "/accounts").at(-1);
    expect(JSON.parse(post?.body ?? "{}")).toMatchObject({ name: "Card terminal", openingBalance: 250.5 });
  });

  test("balance adjustment sends the seen balance, a reason and an idempotency key", async ({ page }) => {
    const state = freshState();
    await setUp(page, "TENANT_ADMIN", [], state);
    await page.goto("/tenant-admin/finance/accounts");
    await page.getByRole("button", { name: "Adjust balance of Cash in hand" }).click();
    const dialog = page.getByTestId("adjust-dialog");
    await dialog.getByLabel("New balance").fill("1400");
    await dialog.getByRole("button", { name: "Record adjustment" }).click();
    await expect(dialog.getByText("Explain why the balance is being adjusted.")).toBeVisible();
    await dialog.getByLabel("Reason").fill("Cash count short");
    await dialog.getByRole("button", { name: "Record adjustment" }).click();
    await expect(dialog).toHaveCount(0);
    const call = state.requests.find((r) => r.path === "/accounts/acc-cash/adjustments");
    expect(JSON.parse(call?.body ?? "{}")).toEqual({ targetBalance: 1400, expectedBalance: 1500, reason: "Cash count short" });
    expect(call?.idempotencyKey).toBeTruthy();
  });

  test("empty state offers New account", async ({ page }) => {
    await setUp(page, "TENANT_ADMIN", [], freshState({ accounts: [] }));
    await page.goto("/tenant-admin/finance/accounts");
    await expect(page.getByText("No accounts yet")).toBeVisible();
    await expect(page.getByRole("button", { name: "New account" }).first()).toBeVisible();
  });
});

test.describe("Transactions", () => {
  test("student payments are corrected in Payments; a manual row reverses with a reason", async ({ page }) => {
    const state = freshState();
    await setUp(page, "TENANT_ADMIN", [], state);
    await page.goto("/tenant-admin/finance/transactions");
    const table = page.getByRole("table", { name: "Finance transactions" });
    await expect(table.getByRole("link", { name: "Correct in Payments" })).toHaveAttribute("href", "/tenant-admin/payments");
    await expect(table.getByRole("button", { name: /^Reverse/ })).toHaveCount(1);

    await table.getByRole("button", { name: /^Reverse/ }).click();
    const dialog = page.getByTestId("reverse-transaction-dialog");
    await dialog.getByRole("button", { name: "Reverse" }).click();
    await expect(dialog.getByText("Explain why this is being reversed.")).toBeVisible();
    await dialog.getByLabel("Reason").fill("Recorded twice");
    await dialog.getByRole("button", { name: "Reverse" }).click();
    await expect(dialog).toHaveCount(0);
    expect(state.requests.find((r) => r.path === "/transactions/tx-manual/reverse")?.body).toBe(JSON.stringify({ reason: "Recorded twice" }));
    await expect(table.getByText("Reversed")).toBeVisible();
  });

  test("record a transfer: validation, then one POST with an idempotency key and no tenant id", async ({ page }) => {
    const state = freshState();
    await setUp(page, "FINANCE_STAFF", FINANCE_STAFF_CAPS, state);
    await page.goto("/tenant-admin/finance/transactions");
    await page.getByRole("button", { name: "Record transaction" }).first().click();
    const dialog = page.getByTestId("record-transaction-dialog");
    await dialog.getByLabel("Transfer").check();
    await dialog.getByLabel("Amount").fill("1000");
    await dialog.getByLabel("From account").selectOption({ label: "Main bank" });
    await dialog.getByLabel("To account").selectOption({ label: "Main bank" });
    await dialog.getByRole("button", { name: "Record" }).click();
    await expect(dialog.getByText("Choose two different accounts.")).toBeVisible();
    await dialog.getByLabel("To account").selectOption({ label: "Cash in hand" });
    await dialog.getByRole("button", { name: "Record" }).click();
    await expect(dialog).toHaveCount(0);
    const posts = state.requests.filter((r) => r.method === "POST" && r.path === "/transactions");
    expect(posts).toHaveLength(1);
    expect(posts[0].idempotencyKey).toBeTruthy();
    const sent = JSON.parse(posts[0].body ?? "{}");
    expect(sent).toMatchObject({ type: "TRANSFER", amount: 1000, fromAccountId: BANK.id, toAccountId: CASH.id, accountId: null, categoryId: null });
    expect(Object.keys(sent)).not.toContain("tenantId");
  });

  test("an unposted payment is posted to a chosen account; Reconcile is Tenant Admin only", async ({ page }) => {
    const state = freshState();
    await setUp(page, "FINANCE_STAFF", FINANCE_STAFF_CAPS, state);
    await page.goto("/tenant-admin/finance/transactions");
    const panel = page.getByTestId("unposted-panel");
    await expect(panel).toContainText("Unposted (1)");
    await expect(panel.getByRole("button", { name: "Reconcile past payments" })).toHaveCount(0);
    await panel.getByLabel(/Account for income/).selectOption({ label: "Cash in hand" });
    await panel.getByRole("button", { name: "Post" }).click();
    await expect(panel).toHaveCount(0);
    expect(state.requests.find((r) => r.path === "/transactions/tx-unposted/post")?.body).toBe(JSON.stringify({ accountId: CASH.id }));
  });

  test("empty list explains how postings arrive", async ({ page }) => {
    await setUp(page, "TENANT_ADMIN", [], freshState({ transactions: [], unposted: [] }));
    await page.goto("/tenant-admin/finance/transactions");
    await expect(page.getByText("No transactions")).toBeVisible();
  });
});

test.describe("Scheduled", () => {
  test("policy note, skipped months, and a failed occurrence retried by a user", async ({ page }) => {
    const state = freshState();
    await setUp(page, "TENANT_ADMIN", [], state);
    await page.goto("/tenant-admin/finance/scheduled");
    await expect(page.getByTestId("schedule-policy-note")).toContainText("not posted automatically");
    await expect(page.getByTestId("schedule-card-sch-rent")).toContainText("Skipped (no day 31): February 2026, April 2026");
    const due = page.getByTestId("due-panel");
    await expect(due).toContainText("Last attempt failed: Account Main bank is archived");
    await due.getByRole("button", { name: /^Retry Office rent/ }).click();
    await expect(due).toHaveCount(0);
    expect(state.requests.some((r) => r.path === "/schedules/occurrences/occ-1/post")).toBe(true);
  });

  test("a monthly schedule on day 31 warns that short months are skipped", async ({ page }) => {
    const state = freshState();
    await setUp(page, "TENANT_ADMIN", [], state);
    await page.goto("/tenant-admin/finance/scheduled");
    await page.getByRole("button", { name: "Schedule transaction" }).first().click();
    const dialog = page.getByTestId("schedule-dialog");
    await dialog.getByLabel("Name").fill("Salaries");
    await dialog.getByLabel("Amount").fill("90000");
    await dialog.getByLabel("Account").selectOption({ label: "Main bank" });
    await dialog.getByLabel("Category").selectOption({ label: "Rent" });
    await dialog.getByLabel("Schedule type").selectOption("MONTHLY");
    await dialog.getByLabel("Day of month").fill("31");
    await expect(dialog.getByText("Months without a day 31 are skipped, not moved.")).toBeVisible();
    await dialog.getByRole("button", { name: "Schedule" }).click();
    await expect(dialog).toHaveCount(0);
    const sent = JSON.parse(state.requests.find((r) => r.method === "POST" && r.path === "/schedules")?.body ?? "{}");
    expect(sent).toMatchObject({ name: "Salaries", type: "EXPENSE", scheduleType: "MONTHLY", dayOfMonth: 31, scheduledDate: null, accountId: BANK.id });
  });
});

test.describe("Read-only Auditor", () => {
  test("sees every tab but no mutating control", async ({ page }) => {
    await setUp(page, "READ_ONLY_AUDITOR", FINANCE_VIEW_CAPS, freshState());
    await page.goto("/tenant-admin/finance/accounts");
    await expect(page.getByTestId("account-card-acc-cash")).toBeVisible();
    await expect(page.getByRole("button", { name: /New account|Configure|Adjust balance|^Edit|^Archive/ })).toHaveCount(0);

    await page.goto("/tenant-admin/finance/transactions");
    await expect(page.getByRole("table", { name: "Finance transactions" })).toBeVisible();
    await expect(page.getByRole("button", { name: /Record transaction|^Reverse|^Edit note|^Post$|Reconcile/ })).toHaveCount(0);

    await page.goto("/tenant-admin/finance/scheduled");
    await expect(page.getByTestId("schedule-card-sch-rent")).toBeVisible();
    await expect(page.getByRole("button", { name: /Schedule transaction|^Retry|^Record|^Skip|^Cancel/ })).toHaveCount(0);

    await page.goto("/tenant-admin/finance/categories");
    await expect(page.getByRole("list", { name: "Categories" }).getByText("Rent")).toBeVisible();
    await expect(page.getByRole("button", { name: /New category|^Rename|^Archive/ })).toHaveCount(0);
  });
});

test.describe("Mobile (375px)", () => {
  test.use({ viewport: { width: 375, height: 800 } });

  for (const [tab, path] of [
    ["Student Income", ""],
    ["Budget", "/budget"],
    ["Accounts", "/accounts"],
    ["Transactions", "/transactions"],
    ["Scheduled", "/scheduled"],
    ["Categories", "/categories"],
  ] as const) {
    test(`${tab} renders without horizontal page scroll`, async ({ page }) => {
      await setUp(page, "TENANT_ADMIN", [], freshState());
      await page.goto(`/tenant-admin/finance${path}`);
      await expect(page.getByRole("navigation", { name: "Finance sections" })).toBeVisible();
      await expect(page.getByRole("heading", { level: 2 }).first()).toBeVisible();
      await page.waitForLoadState("networkidle");
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
      expect(overflow).toBeLessThanOrEqual(1);
    });
  }
});
