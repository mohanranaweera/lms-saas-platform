import { test, expect, type Page, type Route } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  mockJson,
  mockStudentRegistrationPolicy,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * LOGIN-01 Phase B — institute policies. Mock-only: the backend's validation,
 * BRANDING_SETTINGS enforcement and host-tenant scoping are proven by
 * `TenantAppearanceIntegrationTest` / `ConfigPropertyRegistryInstituteTest`.
 * These tests prove what the UI sends and that the text is rendered as text.
 * All institute data is invented.
 */

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
  institute: null,
  policies: { termsOfService: true, refundPolicy: false, privacyPolicy: true },
};

const TERMS = "1. Be kind.\n2. <b>Never</b> share your account.\n\n<script>alert(1)</script>";

async function mockAnonymous(page: Page, policies = HOME.policies, policyTexts: Record<string, string | null> = {}) {
  await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  await mockJson(page, "**/v1/platform-admin/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  await mockJson(page, "**/v1/public/tenant-config/home", 200, apiSuccess({ ...HOME, policies }));
  await mockStudentRegistrationPolicy(page);
  await mockJson(page, "**/v1/public/teachers*", 200, apiPageSuccess([]));
  await mockJson(page, "**/v1/public/courses*", 200, apiPageSuccess([]));
  await page.route("**/v1/public/tenant-config/policies/*", async (route: Route) => {
    const kind = route.request().url().split("/").pop() ?? "";
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(apiSuccess({ kind, text: policyTexts[kind] ?? null })),
    });
  });
}

test.describe("public policy pages", () => {
  test("terms are shown as plain text with line breaks, never as HTML", async ({ page }) => {
    let dialogs = 0;
    page.on("dialog", (dialog) => {
      dialogs += 1;
      void dialog.dismiss();
    });
    await mockAnonymous(page, HOME.policies, { terms: TERMS });
    await page.goto("/terms");

    await expect(page.getByRole("heading", { name: "Terms of Service", level: 1 })).toBeVisible();
    const text = page.getByTestId("policy-text");
    await expect(text).toHaveText(TERMS);
    await expect(text.locator("b, script")).toHaveCount(0);
    expect(dialogs).toBe(0);
  });

  test("an unpublished policy says so", async ({ page }) => {
    await mockAnonymous(page);
    await page.goto("/refund-policy");

    await expect(page.getByRole("heading", { name: "Refund Policy", level: 1 })).toBeVisible();
    await expect(page.getByTestId("policy-empty")).toContainText("Northwind Academy hasn't published its refund policy yet.");
  });

  test("the footer links only the published policies", async ({ page }) => {
    await mockAnonymous(page);
    await page.goto("/");

    const footer = page.getByTestId("footer-policies");
    await expect(footer.getByRole("link", { name: "Terms of Service" })).toHaveAttribute("href", "/terms");
    await expect(footer.getByRole("link", { name: "Privacy Policy" })).toHaveAttribute("href", "/privacy");
    await expect(footer.getByRole("link", { name: "Refund Policy" })).toHaveCount(0);
  });

  test("no policy links when nothing is published", async ({ page }) => {
    await mockAnonymous(page, { termsOfService: false, refundPolicy: false, privacyPolicy: false });
    await page.goto("/");

    await expect(page.getByTestId("public-site-footer")).toBeVisible();
    await expect(page.getByTestId("footer-policies")).toHaveCount(0);
  });
});

test.describe("consent line on sign-in pages", () => {
  test("login and register link the published terms and privacy policy", async ({ page }) => {
    await mockAnonymous(page);
    for (const path of ["/login", "/register"]) {
      await page.goto(path);
      const consent = page.getByTestId("policy-consent");
      await expect(consent).toContainText("By using this service you agree to the Terms of Service and Privacy Policy.");
      await expect(consent.getByRole("link", { name: "Terms of Service" })).toHaveAttribute("href", "/terms");
      await expect(consent.getByRole("link", { name: "Privacy Policy" })).toHaveAttribute("href", "/privacy");
    }
  });

  test("only the terms are linked when only they are published", async ({ page }) => {
    await mockAnonymous(page, { termsOfService: true, refundPolicy: true, privacyPolicy: false });
    await page.goto("/login");

    await expect(page.getByTestId("policy-consent")).toHaveText("By using this service you agree to the Terms of Service.");
  });

  test("no consent line when neither terms nor privacy policy is published", async ({ page }) => {
    await mockAnonymous(page, { termsOfService: false, refundPolicy: true, privacyPolicy: false });
    await page.goto("/login");

    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
    await expect(page.getByTestId("policy-consent")).toHaveCount(0);
  });
});

test.describe("Settings → Policies", () => {
  type Property = { key: string; value: unknown; type: string; defaultValue: unknown; sensitive: boolean };

  async function mockAdmin(page: Page, role = "TENANT_ADMIN") {
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
    let properties: Property[] = [
      { key: "institute_name", value: "Northwind Academy", type: "STRING", defaultValue: null, sensitive: false },
      { key: "terms_of_service", value: "Old terms.", type: "STRING", defaultValue: null, sensitive: false },
      { key: "refund_policy", value: null, type: "STRING", defaultValue: null, sensitive: false },
      { key: "privacy_policy", value: null, type: "STRING", defaultValue: null, sensitive: false },
    ];
    const puts: Record<string, unknown>[] = [];
    await page.route("**/api/v1/tenant-config/GENERAL", async (route) => {
      if (route.request().method() === "PUT") {
        const body = route.request().postDataJSON() as Record<string, unknown>;
        puts.push(body);
        properties = properties.map((entry) => (entry.key in body ? { ...entry, value: body[entry.key] } : entry));
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(properties)) });
    });
    return puts;
  }

  test("tenant admin publishes, edits and unpublishes policies; only changed keys are sent", async ({ page }) => {
    const puts = await mockAdmin(page);
    await page.goto("/tenant-admin/settings/policies");

    await expect(page.getByRole("navigation", { name: "Settings sections" }).getByRole("link", { name: "Policies" })).toHaveAttribute(
      "aria-current",
      "page"
    );
    await expect(page.getByTestId("policy-field-terms").getByRole("link", { name: /View public page/ })).toHaveAttribute("href", "/terms");
    await expect(page.getByTestId("policy-field-refund")).toContainText("Not published");

    await page.getByLabel("Refund Policy").fill("  Refunds within 7 days.\nNo refunds after the first class.  ");
    await page.getByLabel("Terms of Service").fill("");
    await page.getByTestId("policies-save").click();

    await expect(page.getByText("Saved.", { exact: true })).toBeVisible();
    expect(puts).toEqual([{ terms_of_service: null, refund_policy: "Refunds within 7 days.\nNo refunds after the first class." }]);
    await expect(page.getByTestId("policy-field-terms")).toContainText("Not published");
  });

  test("read-only auditor sees the text but cannot save", async ({ page }) => {
    await mockAdmin(page, "READ_ONLY_AUDITOR");
    await page.goto("/tenant-admin/settings/policies");

    await expect(page.getByLabel("Terms of Service")).toHaveValue("Old terms.");
    await expect(page.getByLabel("Terms of Service")).toBeDisabled();
    await expect(page.getByTestId("policies-save")).toHaveCount(0);
  });

  test("a 403 on the read renders the permission-denied state", async ({ page }) => {
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TENANT_ADMIN" }))));
    await mockJson(page, "**/api/v1/tenant-config/GENERAL", 403, apiError("FORBIDDEN", "Not allowed"));
    await page.goto("/tenant-admin/settings/policies");

    await expect(page.getByRole("alert").filter({ hasText: "You don't have permission to view this." })).toBeVisible();
    await expect(page.getByLabel("Terms of Service")).toHaveCount(0);
  });
});
