import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * SET-PAY-01 — Settings → Payments tab and institute public information
 * (Klass parity). Mock-only: the backend's validation, `BRANDING_SETTINGS`/
 * `PAYMENT_GATEWAY` enforcement, audit logging and bank-transfer enforcement
 * are proven by `ConfigPropertyRegistryInstituteTest`,
 * `BankTransferSwitchIntegrationTest` and `TenantAppearanceIntegrationTest`.
 * These tests prove what the UI shows each role and exactly what it sends.
 * All institute data below is invented.
 */

type Property = { key: string; value: unknown; type: string; defaultValue: unknown; sensitive: boolean };

function prop(key: string, value: unknown, defaultValue: unknown = null, type = "STRING"): Property {
  return { key, value, type, defaultValue, sensitive: false };
}

const GENERAL: Property[] = [
  prop("institute_name", "Northwind Academy"),
  prop("support_email", "help@northwind.example"),
  prop("support_phone", "+94 11 000 0000"),
  prop("default_timezone", "Asia/Colombo", "UTC"),
  prop("default_currency", null, "USD"),
  prop("address_line1", "12 Lake Road"),
  prop("address_line2", null),
  prop("city", null),
  prop("postal_code", null),
  prop("institute_introduction", null),
  prop("facebook_url", null),
  prop("youtube_url", null),
];

const PAYMENT: Property[] = [
  prop("facility_fee_mode", null, "NONE"),
  prop("facility_fee_amount", null, 0, "NUMBER"),
  prop("bank_name", null),
  prop("bank_branch", null),
  prop("bank_account_holder", null),
  prop("bank_account_number", null),
  prop("bank_transfer_enabled", null, true, "BOOLEAN"),
];

const ACTIVE_ACCOUNT = {
  id: "acct-1",
  provider: "PAYHERE",
  mode: "LIVE",
  merchantIdMasked: "***1149",
  status: "ACTIVE",
  secretConfigured: true,
  disabledBy: null,
  disabledReason: null,
  disabledAt: null,
  updatedAt: new Date().toISOString(),
  checkoutReady: true,
};

interface ConfigMock {
  puts: Record<string, Record<string, unknown>[]>;
}

/** Stateful `GET/PUT /v1/tenant-config/{GENERAL|PAYMENT}`; every PUT body is recorded per domain. */
async function mockConfig(
  page: Page,
  options: { role: string; gatewayAccounts?: unknown[]; general?: Property[]; payment?: Property[] }
): Promise<ConfigMock> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: options.role }))));
  const domains: Record<string, Property[]> = {
    GENERAL: (options.general ?? GENERAL).map((entry) => ({ ...entry })),
    PAYMENT: (options.payment ?? PAYMENT).map((entry) => ({ ...entry })),
  };
  const puts: ConfigMock["puts"] = { GENERAL: [], PAYMENT: [] };
  await page.route("**/api/v1/tenant-config/*", async (route: Route) => {
    const domain = route.request().url().split("/").pop() ?? "";
    if (!(domain in domains)) {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
      return;
    }
    if (route.request().method() === "PUT") {
      const body = route.request().postDataJSON() as Record<string, unknown>;
      puts[domain].push(body);
      domains[domain] = domains[domain].map((entry) => (entry.key in body ? { ...entry, value: body[entry.key] } : entry));
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(domains[domain])) });
  });
  await mockJson(page, "**/v1/payment-gateway-accounts", 200, apiSuccess(options.gatewayAccounts ?? [ACTIVE_ACCOUNT]));
  return { puts };
}

test.describe("Settings → Payments tab", () => {
  test("tenant admin sees every section under the Payments tab, and the old sidebar link is gone", async ({ page }) => {
    await mockConfig(page, { role: "TENANT_ADMIN" });

    await page.goto("/tenant-admin/settings/payments");

    const tabs = page.getByRole("navigation", { name: "Settings sections" });
    await expect(tabs.getByRole("link", { name: "Payments" })).toHaveAttribute("aria-current", "page");
    await expect(tabs.getByRole("link", { name: "General" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Currency settings" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Service fee (facility fee)" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Payment gateway — PayHere" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Bank details" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Online Payments", exact: true })).toHaveCount(0);
  });

  test("currency is a select that saves only default_currency to GENERAL", async ({ page }) => {
    const mock = await mockConfig(page, { role: "TENANT_ADMIN" });
    await page.goto("/tenant-admin/settings/payments");
    const section = page.getByTestId("currency-settings");
    await expect(section.getByText("USD", { exact: true })).toBeVisible();

    await section.getByLabel("Currency students are charged in").selectOption("LKR");
    await section.getByTestId("currency-save").click();

    await expect(section.getByText("Currency saved.")).toBeVisible();
    expect(mock.puts.GENERAL).toEqual([{ default_currency: "LKR" }]);
    await expect(section.getByText("LKR", { exact: true })).toBeVisible();
  });

  test("bank details are edited behind Edit, saved to PAYMENT, and a cleared field is sent as null", async ({ page }) => {
    const mock = await mockConfig(page, { role: "TENANT_ADMIN" });
    await page.goto("/tenant-admin/settings/payments");
    const section = page.getByTestId("bank-details-settings");
    await expect(section.getByTestId("bank-details-empty")).toBeVisible();
    await expect(section.getByLabel("Account holder")).toHaveAttribute("readonly", "");

    await section.getByTestId("bank-details-edit").click();
    await section.getByLabel("Account holder").fill("Northwind Academy (Pvt) Ltd");
    await section.getByLabel("Account number").fill("0012 3456 7890");
    await section.getByLabel("Bank name").fill("Example Bank");
    await section.getByLabel("Branch").fill("  Kandy  ");
    await section.getByTestId("bank-details-save").click();

    await expect(section.getByText("Bank details saved.", { exact: false })).toBeVisible();
    expect(mock.puts.PAYMENT[0]).toEqual({
      bank_account_holder: "Northwind Academy (Pvt) Ltd",
      bank_account_number: "0012 3456 7890",
      bank_name: "Example Bank",
      bank_branch: "Kandy",
    });

    await section.getByTestId("bank-details-edit").click();
    await section.getByLabel("Branch").fill("");
    await section.getByTestId("bank-details-save").click();
    await expect.poll(() => mock.puts.PAYMENT.length).toBe(2);
    expect(mock.puts.PAYMENT[1]).toEqual({ bank_branch: null });
  });

  test("Cancel discards unsaved bank detail edits without a request", async ({ page }) => {
    const mock = await mockConfig(page, { role: "TENANT_ADMIN" });
    await page.goto("/tenant-admin/settings/payments");
    const section = page.getByTestId("bank-details-settings");

    await section.getByTestId("bank-details-edit").click();
    await section.getByLabel("Bank name").fill("Typo Bank");
    await section.getByRole("button", { name: "Cancel" }).click();

    await expect(section.getByLabel("Bank name")).toHaveValue("");
    expect(mock.puts.PAYMENT).toHaveLength(0);
  });

  test("the bank transfer switch saves bank_transfer_enabled", async ({ page }) => {
    const mock = await mockConfig(page, { role: "TENANT_ADMIN" });
    await page.goto("/tenant-admin/settings/payments");
    const toggle = page.getByRole("switch", { name: "Accept bank transfer" });
    await expect(toggle).toHaveAttribute("aria-checked", "true");

    await toggle.click();

    await expect(toggle).toHaveAttribute("aria-checked", "false");
    expect(mock.puts.PAYMENT).toEqual([{ bank_transfer_enabled: false }]);
    // PayHere is active and checkout-ready here, so no fallback warning.
    await expect(page.getByTestId("bank-transfer-fallback-warning")).toHaveCount(0);
  });

  test("switching bank transfer off without a ready PayHere account explains it stays available", async ({ page }) => {
    await mockConfig(page, { role: "TENANT_ADMIN", gatewayAccounts: [] });
    await page.goto("/tenant-admin/settings/payments");

    await page.getByRole("switch", { name: "Accept bank transfer" }).click();

    await expect(page.getByTestId("bank-transfer-fallback-warning")).toContainText(
      "students can still pay by bank transfer"
    );
  });

  test("a failed switch save is announced and the switch stays on", async ({ page }) => {
    await mockConfig(page, { role: "TENANT_ADMIN" });
    await page.route("**/api/v1/tenant-config/PAYMENT", async (route) => {
      if (route.request().method() === "PUT") {
        await route.fulfill({ status: 403, contentType: "application/json", body: JSON.stringify(apiError("FORBIDDEN", "Not allowed")) });
        return;
      }
      await route.fallback();
    });
    await page.goto("/tenant-admin/settings/payments");
    const toggle = page.getByRole("switch", { name: "Accept bank transfer" });

    await toggle.click();

    await expect(page.getByRole("alert").filter({ hasText: "You don't have permission to change this setting." })).toBeVisible();
    await expect(toggle).toHaveAttribute("aria-checked", "true");
  });

  test("read-only auditor sees every section with no way to change anything", async ({ page }) => {
    await mockConfig(page, { role: "READ_ONLY_AUDITOR" });
    await page.goto("/tenant-admin/settings/payments");

    await expect(page.getByLabel("Currency students are charged in")).toBeDisabled();
    await expect(page.getByTestId("currency-save")).toHaveCount(0);
    await expect(page.getByTestId("bank-details-edit")).toHaveCount(0);
    await expect(page.getByRole("switch", { name: "Accept bank transfer" })).toBeDisabled();
    await expect(page.getByLabel("Merchant ID")).toHaveCount(0);
  });

  test("finance staff see only the PayHere status, and only the Payments tab", async ({ page }) => {
    await mockConfig(page, { role: "FINANCE_STAFF" });
    await page.goto("/tenant-admin/settings/payments");

    await expect(page.getByLabel("PayHere account status")).toBeVisible();
    await expect(page.getByTestId("currency-settings")).toHaveCount(0);
    await expect(page.getByTestId("bank-details-settings")).toHaveCount(0);
    await expect(page.getByTestId("facility-fee-settings")).toHaveCount(0);
    const tabs = page.getByRole("navigation", { name: "Settings sections" });
    await expect(tabs.getByRole("link", { name: "Payments" })).toBeVisible();
    await expect(tabs.getByRole("link", { name: "General" })).toHaveCount(0);
  });

  test("a role with neither grant gets the server's permission-denied state, not an empty page", async ({ page }) => {
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "STUDENT_SUPPORT" }))));
    await mockJson(page, "**/v1/payment-gateway-accounts", 403, apiError("FORBIDDEN", "Not allowed"));

    await page.goto("/tenant-admin/settings/payments");

    await expect(page.getByTestId("payment-gateway-settings").getByRole("alert")).toBeVisible();
    await expect(page.getByLabel("Merchant ID")).toHaveCount(0);
  });
});

test.describe("Settings → General: institute public information", () => {
  test("default currency is no longer on General", async ({ page }) => {
    await mockConfig(page, { role: "TENANT_ADMIN" });
    await page.goto("/tenant-admin/settings/general");

    await expect(page.getByRole("heading", { name: "Institute public information" })).toBeVisible();
    await expect(page.getByLabel("Default currency")).toHaveCount(0);
  });

  test("saves the new public fields; an emptied field is cleared with null", async ({ page }) => {
    const mock = await mockConfig(page, { role: "TENANT_ADMIN" });
    await page.goto("/tenant-admin/settings/general");
    const section = page.getByTestId("institute-public-information");

    await section.getByLabel("Address line 1").fill("");
    await section.getByLabel("Address line 2").fill("Kodapaluwa");
    await section.getByLabel("City").fill("Alawwa");
    await section.getByLabel("Postal code").fill("60280");
    await section.getByLabel("Institute introduction").fill("Physics and chemistry classes.\nSince 2001.");
    await section.getByLabel("Facebook").fill("https://www.facebook.com/northwind");
    await section.getByLabel("YouTube").fill(" https://www.youtube.com/@northwind ");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.getByText("Saved.", { exact: true })).toBeVisible();
    expect(mock.puts.GENERAL).toEqual([
      {
        address_line1: null,
        address_line2: "Kodapaluwa",
        city: "Alawwa",
        postal_code: "60280",
        institute_introduction: "Physics and chemistry classes.\nSince 2001.",
        facebook_url: "https://www.facebook.com/northwind",
        youtube_url: "https://www.youtube.com/@northwind",
      },
    ]);
  });

  test("a non-Facebook link is rejected before any request", async ({ page }) => {
    const mock = await mockConfig(page, { role: "TENANT_ADMIN" });
    await page.goto("/tenant-admin/settings/general");

    await page.getByLabel("Facebook").fill("javascript:alert(1)");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.getByText("Enter your Facebook page link", { exact: false })).toBeVisible();
    expect(mock.puts.GENERAL).toHaveLength(0);
  });

  test("a backend field error is shown on that field", async ({ page }) => {
    await mockConfig(page, { role: "TENANT_ADMIN" });
    await page.route("**/api/v1/tenant-config/GENERAL", async (route) => {
      if (route.request().method() === "PUT") {
        await route.fulfill({
          status: 400,
          contentType: "application/json",
          body: JSON.stringify(
            apiError("VALIDATION_ERROR", "Invalid", [{ field: "youtube_url", message: "Invalid value for youtube_url" }])
          ),
        });
        return;
      }
      await route.fallback();
    });
    await page.goto("/tenant-admin/settings/general");

    await page.getByLabel("YouTube").fill("https://youtu.be/abc");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.getByText("Invalid value for youtube_url")).toBeVisible();
  });
});

test.describe("public home: institute information", () => {
  const HOME = {
    publicPageEnabled: true,
    instituteName: "Northwind Academy",
    logoUrl: null,
    faviconUrl: null,
    homepageUrl: null,
    subHeroEnabled: false,
    ctaEnabled: false,
    supportVideoUrl: null,
    desktopBannerUrls: [],
    mobileBannerUrls: [],
    featuredCourses: [],
    theme: null,
    logoDisplaySize: "STANDARD",
    teacherAutoRotate: false,
    supportEmail: null,
    supportPhone: null,
  };

  async function mockPublic(page: Page, institute: Record<string, unknown> | null) {
    await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
    await mockJson(page, "**/v1/platform-admin/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
    await mockJson(page, "**/v1/public/tenant-config/home", 200, apiSuccess({ ...HOME, institute }));
    await mockJson(page, "**/v1/public/teachers*", 200, apiPageSuccess([]));
    await mockJson(page, "**/v1/public/courses*", 200, apiPageSuccess([]));
  }

  test("shows the introduction, address and social links when set", async ({ page }) => {
    await mockPublic(page, {
      introduction: "Physics and chemistry classes.\nSince 2001.",
      addressLine1: "12 Lake Road",
      addressLine2: "Kodapaluwa",
      city: "Alawwa",
      postalCode: "60280",
      facebookUrl: "https://www.facebook.com/northwind",
      youtubeUrl: "https://www.youtube.com/@northwind",
    });
    await page.goto("/");

    const about = page.getByTestId("about-section");
    await expect(about.getByRole("heading", { name: "Northwind Academy" })).toBeVisible();
    await expect(about.getByTestId("about-introduction")).toHaveText("Physics and chemistry classes.\nSince 2001.");
    await expect(about.getByText("12 Lake Road, Kodapaluwa, Alawwa 60280")).toBeVisible();

    const footer = page.getByTestId("public-site-footer");
    await expect(footer.getByTestId("footer-address")).toContainText("Alawwa 60280");
    const facebook = footer.getByRole("link", { name: "Northwind Academy on Facebook (opens in a new tab)" });
    await expect(facebook).toHaveAttribute("href", "https://www.facebook.com/northwind");
    await expect(facebook).toHaveAttribute("rel", "noopener noreferrer");
    await expect(footer.getByRole("link", { name: "Northwind Academy on YouTube (opens in a new tab)" })).toBeVisible();
  });

  test("renders nothing extra when no institute information is set", async ({ page }) => {
    await mockPublic(page, null);
    await page.goto("/");

    await expect(page.getByTestId("public-site-footer")).toBeVisible();
    await expect(page.getByTestId("about-section")).toHaveCount(0);
    await expect(page.getByTestId("institute-social-links")).toHaveCount(0);
    await expect(page.getByTestId("footer-address")).toHaveCount(0);
  });

  test("never renders a social link that is not https on the expected host", async ({ page }) => {
    await mockPublic(page, {
      introduction: "Hello",
      addressLine1: null,
      addressLine2: null,
      city: null,
      postalCode: null,
      facebookUrl: "javascript:alert(1)",
      youtubeUrl: "https://evil.example/watch",
    });
    await page.goto("/");

    await expect(page.getByTestId("about-introduction")).toHaveText("Hello");
    await expect(page.getByTestId("institute-social-links")).toHaveCount(0);
  });
});
