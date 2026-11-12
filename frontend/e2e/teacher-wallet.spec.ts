import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * TEACHER-WALLET-01 — Teachers → Show Wallet. No real backend runs here: a
 * small in-memory fake answers `/v1/finance/teachers/{id}/wallet/**` with the
 * documented shapes. Server rules (overdraft, idempotency, stale balance,
 * permissions, tenant isolation) are proven by `TeacherWalletIntegrationTest`;
 * these specs prove the UI flow, effect preview, states and role visibility.
 */

const ADA = { id: "tp-ada", userId: "tu-ada", name: "Ada Perera" };
const BEN = { id: "tp-ben", userId: "tu-ben", name: "Ben Silva" };

function teacher(t: { id: string; userId: string; name: string }) {
  return {
    ...t,
    firstName: null,
    lastName: null,
    email: `${t.id}@example.test`,
    contactNumber: null,
    instituteCommissionPercent: 0,
    qualifications: null,
    description: null,
    profileMediaFileId: null,
    profileImageUrl: null,
    publicProfileDisabled: false,
    studentCount: 0,
    approvalStatus: "APPROVED",
    accountStatus: "ACTIVE",
    approvedBy: null,
    approvedAt: null,
  };
}

interface Entry {
  id: string;
  kind: string;
  amount: number;
  currency: string;
  description: string | null;
  balanceBefore: number;
  balanceAfter: number;
  createdAt: string;
  createdBy: string;
  createdByEmail: string | null;
  accountName: null;
  categoryName: null;
}

class FakeWallets {
  readonly wallets = new Map<string, { entries: Entry[] }>();
  readonly keys: string[] = [];

  balance(teacherId: string): number {
    const wallet = this.wallets.get(teacherId);
    return wallet ? Math.round(wallet.entries.reduce((sum, e) => sum + e.amount * 100, 0)) / 100 : 0;
  }

  view(teacherId: string) {
    const wallet = this.wallets.get(teacherId);
    if (!wallet) {
      return { teacherId, exists: false, walletId: null, currency: "LKR", balance: null, unpaidSettlements: null, walletEntries: null, createdAt: null };
    }
    const balance = this.balance(teacherId);
    return { teacherId, exists: true, walletId: `w-${teacherId}`, currency: "LKR", balance, unpaidSettlements: 0, walletEntries: balance, createdAt: "2026-11-01T00:00:00Z" };
  }

  post(teacherId: string, kind: string, amount: number, description: string | null): Entry {
    const wallet = this.wallets.get(teacherId)!;
    const before = this.balance(teacherId);
    const entry: Entry = {
      id: `e-${wallet.entries.length + 1}`,
      kind,
      amount,
      currency: "LKR",
      description,
      balanceBefore: before,
      balanceAfter: Math.round((before + amount) * 100) / 100,
      createdAt: new Date().toISOString(),
      createdBy: "u-admin",
      createdByEmail: "admin@example.test",
      accountName: null,
      categoryName: null,
    };
    wallet.entries.unshift(entry);
    return entry;
  }

  async install(page: Page): Promise<void> {
    await page.route(/\/v1\/finance\/teachers\/([^/]+)\/wallet(\/[^?]*)?(\?.*)?$/, async (route: Route) => {
      const url = new URL(route.request().url());
      const match = url.pathname.match(/\/teachers\/([^/]+)\/wallet\/?(.*)$/)!;
      const teacherId = match[1];
      const sub = match[2];
      const method = route.request().method();
      const body = route.request().postDataJSON?.() ?? null;

      if (method === "GET" && sub === "") return fulfillJson(route, 200, apiSuccess(this.view(teacherId)));
      if (method === "GET" && sub === "entries") {
        const kind = url.searchParams.get("kind");
        const entries = this.wallets.get(teacherId)?.entries.filter((e) => e.kind === kind) ?? [];
        return fulfillJson(route, 200, apiSuccess(entries));
      }
      if (method === "POST" && sub === "") {
        this.wallets.set(teacherId, { entries: [] });
        this.post(teacherId, "OPENING", Number(body.openingBalance), "Opening balance");
        return fulfillJson(route, 201, apiSuccess(this.view(teacherId)));
      }
      const key = route.request().headers()["idempotency-key"];
      if (!key) return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Idempotency-Key header required"));
      this.keys.push(key);
      if (sub === "pay-outs" || sub === "pay-ins") {
        if (body.amount === 13) {
          return fulfillJson(route, 400, apiError("VALIDATION_ERROR", "Invalid amount", [{ field: "amount", message: "server says no" }]));
        }
        const signed = sub === "pay-outs" ? -body.amount : body.amount;
        const entry = this.post(teacherId, sub === "pay-outs" ? "PAY_OUT" : "PAY_IN", signed, body.description);
        return fulfillJson(route, 201, apiSuccess({ wallet: this.view(teacherId), entry, created: true }));
      }
      if (sub === "adjustments") {
        const diff = Math.round((body.targetBalance - this.balance(teacherId)) * 100) / 100;
        const entry = this.post(teacherId, "BALANCE_ADJ", diff, body.reason);
        return fulfillJson(route, 201, apiSuccess({ wallet: this.view(teacherId), entry, created: true }));
      }
      return fulfillJson(route, 404, apiError("NOT_FOUND", "Not found"));
    });
  }
}

async function setup(page: Page, role: string, capabilities?: string[]): Promise<FakeWallets> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  if (capabilities) {
    await mockJson(page, "**/v1/me/permissions", 200, apiSuccess({ role, capabilities, permissionVersion: 1 }));
  }
  await page.route(/\/v1\/teachers(\?.*)?$/, (route) => fulfillJson(route, 200, apiSuccess([teacher(ADA), teacher(BEN)])));
  const fake = new FakeWallets();
  await fake.install(page);
  return fake;
}

function sheet(page: Page) {
  return page.getByTestId("teacher-wallet-sheet");
}

test.describe("Teachers → Show Wallet", () => {
  test("desktop: create with a zero opening balance, preview and record pay in / pay out, adjust the balance", async ({ page }) => {
    const fake = await setup(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/teachers");

    await page.getByRole("button", { name: `Show wallet for ${ADA.name}` }).click();
    const wallet = sheet(page);
    await expect(wallet.getByText(`${ADA.name} — Wallet`)).toBeVisible();
    await expect(wallet.getByText("Amounts in LKR.", { exact: false })).toBeVisible();

    // No wallet yet: explicit zero opening balance.
    await expect(wallet.getByLabel("Initial balance (LKR)")).toHaveValue("0");
    await wallet.getByRole("button", { name: "Create Wallet" }).click();
    await expect(wallet.getByTestId("wallet-balance")).toHaveText("0.00 LKR");
    expect(fake.wallets.has(ADA.userId)).toBe(true);

    // Distinct empty states per tab.
    await expect(wallet.getByText("No payouts have been recorded for this teacher.")).toBeVisible();
    await wallet.getByRole("tab", { name: "Pay In" }).click();
    await expect(wallet.getByText("No pay-ins have been recorded for this teacher.")).toBeVisible();

    // Pay In with before → after preview; wallet only.
    await wallet.getByLabel("Amount (LKR)").fill("100");
    await expect(wallet.getByTestId("payin-effect")).toContainText("Balance 0.00 LKR → 100.00 LKR");
    await expect(wallet.getByTestId("payin-effect")).toContainText("no Finance entry will be recorded");
    await wallet.getByLabel("Description").fill("Seminar fee share");
    await wallet.getByRole("button", { name: "Record Pay In" }).click();
    await expect(wallet.getByTestId("wallet-balance")).toHaveText("100.00 LKR");
    await expect(wallet.getByText("Seminar fee share")).toBeVisible();
    await expect(wallet.getByLabel("Amount (LKR)")).toHaveValue("");

    // Pay Out: overdraw is blocked in the preview, a valid payout reduces the balance.
    await wallet.getByRole("tab", { name: "Pay Out" }).click();
    await expect(wallet.getByLabel("Expense Account (optional)")).toBeDisabled();
    await expect(wallet.getByLabel("Category (optional)")).toHaveCount(0);
    await wallet.getByLabel("Amount (LKR)").fill("150");
    await expect(wallet.getByTestId("payout-effect")).toContainText("This exceeds the amount payable (100.00 LKR)");
    await expect(wallet.getByRole("button", { name: "Record Pay Out" })).toBeDisabled();
    await wallet.getByLabel("Amount (LKR)").fill("40.25");
    await expect(wallet.getByTestId("payout-effect")).toContainText("Balance 100.00 LKR → 59.75 LKR");
    await wallet.getByLabel("Description").fill("November payout");
    await wallet.getByRole("button", { name: "Record Pay Out" }).click();
    await expect(wallet.getByTestId("wallet-balance")).toHaveText("59.75 LKR");
    await expect(wallet.getByText("November payout")).toBeVisible();
    expect(new Set(fake.keys).size).toBe(fake.keys.length);

    // Balance pencil = adjustment with a computed difference and a required reason.
    await wallet.getByRole("button", { name: `Adjust ${ADA.name}'s wallet balance` }).click();
    await wallet.getByLabel("New balance (LKR)").fill("50");
    await expect(wallet.getByTestId("adjust-effect")).toContainText("59.75 LKR → 50.00 LKR (adjustment −9.75 LKR)");
    await wallet.getByRole("button", { name: "Save adjustment" }).click();
    await expect(wallet.getByText("A reason is required.")).toBeVisible();
    await wallet.getByLabel("Reason").fill("Recount");
    await wallet.getByRole("button", { name: "Save adjustment" }).click();
    await expect(wallet.getByTestId("wallet-balance")).toHaveText("50.00 LKR");
    expect(fake.wallets.get(ADA.userId)!.entries.map((e) => e.kind)).toEqual(["BALANCE_ADJ", "PAY_OUT", "PAY_IN", "OPENING"]);
  });

  test("narrow screen: the sheet is usable, a server error keeps inputs, and a teacher change resets them", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    const fake = await setup(page, "TENANT_ADMIN");
    fake.wallets.set(ADA.userId, { entries: [] });
    fake.post(ADA.userId, "OPENING", 20, "Opening balance");
    await page.goto("/tenant-admin/teachers");

    await page.getByRole("button", { name: `Show wallet for ${ADA.name}` }).click();
    const wallet = sheet(page);
    await expect(wallet.getByTestId("wallet-balance")).toHaveText("20.00 LKR");
    const box = await wallet.boundingBox();
    expect(box!.width).toBeLessThanOrEqual(375);

    await wallet.getByLabel("Amount (LKR)").fill("13");
    await wallet.getByLabel("Description").fill("Will fail");
    await wallet.getByRole("button", { name: "Record Pay Out" }).click();
    await expect(wallet.getByText("server says no")).toBeVisible();
    await expect(wallet.getByLabel("Amount (LKR)")).toHaveValue("13");
    await expect(wallet.getByLabel("Description")).toHaveValue("Will fail");

    await page.keyboard.press("Escape");
    await expect(wallet).toHaveCount(0);
    await page.getByRole("button", { name: `Show wallet for ${BEN.name}` }).click();
    await expect(sheet(page).getByText(`${BEN.name} — Wallet`)).toBeVisible();
    await expect(sheet(page).getByLabel("Initial balance (LKR)")).toHaveValue("0");
  });

  test("view-only staff see the wallet but no recording controls; staff without the grant see no Show Wallet", async ({ page }) => {
    const fake = await setup(page, "READ_ONLY_AUDITOR", ["TEACHERS_VIEW", "TEACHERS_VIEW_WALLET"]);
    fake.wallets.set(ADA.userId, { entries: [] });
    fake.post(ADA.userId, "OPENING", 10, "Opening balance");
    await page.goto("/tenant-admin/teachers");

    await page.getByRole("button", { name: `Show wallet for ${ADA.name}` }).click();
    const wallet = sheet(page);
    await expect(wallet.getByTestId("wallet-balance")).toHaveText("10.00 LKR");
    await expect(wallet.getByText("You can view payouts but not record them.")).toBeVisible();
    await expect(wallet.getByRole("button", { name: "Record Pay Out" })).toHaveCount(0);
    await expect(wallet.getByRole("button", { name: /Adjust .* wallet balance/ })).toHaveCount(0);
  });

  test("a role without View Wallet gets no Show Wallet action", async ({ page }) => {
    await setup(page, "OPERATOR", ["TEACHERS_VIEW"]);
    await page.goto("/tenant-admin/teachers");
    await expect(page.getByText(ADA.name).first()).toBeVisible();
    await expect(page.getByRole("button", { name: /Show wallet for/ })).toHaveCount(0);
  });
});
