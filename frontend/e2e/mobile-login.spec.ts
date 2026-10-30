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
 * ADR-031 / LOGIN-01 Phase C — mobile-number sign-in (frontend). Mock-only:
 * normalisation, per-institute uniqueness, the switch, throttling and
 * anti-enumeration are proven by `MobileLoginIntegrationTest`. These tests
 * prove what the UI offers and sends, and how it shows each server answer.
 * All people and numbers are invented.
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
  policies: null,
  loginOptions: { mobileEnabled: true },
};

async function mockAnonymous(page: Page, mobileEnabled = true) {
  await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  await mockJson(page, "**/v1/platform-admin/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  await mockJson(page, "**/v1/public/tenant-config/home", 200, apiSuccess({ ...HOME, loginOptions: { mobileEnabled } }));
  await mockJson(page, "**/v1/public/teachers*", 200, apiPageSuccess([]));
  await mockJson(page, "**/v1/public/courses*", 200, apiPageSuccess([]));
}

test.describe("sign-in page", () => {
  test("offers Email and Mobile tabs and sends only the mobile identifier", async ({ page }) => {
    await mockAnonymous(page);
    let body: Record<string, unknown> | null = null;
    await page.route("**/v1/auth/login", async (route: Route) => {
      body = route.request().postDataJSON();
      await route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify(apiError("INVALID_CREDENTIALS", "Invalid mobile number or password")),
      });
    });
    await page.goto("/login");

    const tabs = page.getByRole("tablist", { name: "Sign in with" });
    await expect(tabs.getByRole("tab", { name: "Email" })).toHaveAttribute("aria-selected", "true");
    await tabs.getByRole("tab", { name: "Mobile" }).click();
    // The Email panel stays in the DOM (hidden, labelled by its tab); its input is gone.
    await expect(page.getByRole("textbox", { name: "Email" })).toHaveCount(0);
    await page.getByLabel("Mobile number").fill(" 077 123 4567 ");
    await page.getByLabel("Password", { exact: true }).fill("wrong-password");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page.getByRole("alert").filter({ hasText: "Invalid mobile number or password" })).toBeVisible();
    expect(body).toEqual({ mobile: "077 123 4567", password: "wrong-password" });
  });

  test("the tabs are keyboard operable", async ({ page }) => {
    await mockAnonymous(page);
    await page.goto("/login");

    await page.getByRole("tab", { name: "Email" }).focus();
    await page.keyboard.press("ArrowRight");
    await expect(page.getByRole("tab", { name: "Mobile" })).toBeFocused();
    await expect(page.getByRole("tab", { name: "Mobile" })).toHaveAttribute("aria-selected", "true");
    await expect(page.getByLabel("Mobile number")).toBeVisible();
  });

  test("an obviously invalid number is caught before any request", async ({ page }) => {
    await mockAnonymous(page);
    let called = false;
    await page.route("**/v1/auth/login", async (route) => {
      called = true;
      await route.abort();
    });
    await page.goto("/login");

    await page.getByRole("tab", { name: "Mobile" }).click();
    await page.getByLabel("Mobile number").fill("abc");
    await page.getByLabel("Password", { exact: true }).fill("password123");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page.getByText("Enter a valid mobile number.")).toBeVisible();
    expect(called).toBe(false);
  });

  test("too many attempts is shown with its own title", async ({ page }) => {
    await mockAnonymous(page);
    await mockJson(
      page,
      "**/v1/auth/login",
      429,
      apiError("TOO_MANY_REQUESTS", "Too many sign-in attempts. Please wait a few minutes and try again.")
    );
    await page.goto("/login");

    await page.getByLabel("Email").fill("someone@example.test");
    await page.getByLabel("Password", { exact: true }).fill("password123");
    await page.getByRole("button", { name: "Sign in" }).click();

    const alert = page.getByRole("alert").filter({ hasText: "Too many attempts" });
    await expect(alert).toContainText("Please wait a few minutes");
  });

  test("no Mobile tab when the institute switched mobile sign-in off", async ({ page }) => {
    await mockAnonymous(page, false);
    await page.goto("/login");

    await expect(page.getByLabel("Email")).toBeVisible();
    await expect(page.getByRole("tablist", { name: "Sign in with" })).toHaveCount(0);
  });

  test("Platform Admin sign-in stays email-only", async ({ page }) => {
    await mockAnonymous(page);
    await page.goto("/platform-admin/login");

    await expect(page.getByLabel("Email")).toBeVisible();
    await expect(page.getByRole("tab", { name: "Mobile" })).toHaveCount(0);
  });
});

test.describe("registration", () => {
  test("a number already registered shows on the mobile field", async ({ page }) => {
    await mockAnonymous(page);
    await mockStudentRegistrationPolicy(page);
    await mockJson(
      page,
      "**/v1/students/register",
      409,
      apiError("MOBILE_ALREADY_REGISTERED", "This mobile number is already registered", [
        { field: "profileFields.MOBILE", message: "This mobile number is already registered" },
      ])
    );
    await page.goto("/register");

    await page.getByLabel("Full name").fill("New Student");
    await page.getByLabel("Email").fill("new-student@example.test");
    await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
    await page.getByLabel("Confirm password").fill("correct-horse-battery-staple");
    await page.locator("#register-MOBILE").fill("0771234567");
    await page.getByRole("button", { name: "Create account" }).click();

    await expect(page.getByText("This mobile number is already registered")).toBeVisible();
  });
});

test.describe("tenant admin", () => {
  type Property = { key: string; value: unknown; type: string; defaultValue: unknown; sensitive: boolean };

  async function mockAdmin(page: Page, role = "TENANT_ADMIN") {
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
    let properties: Property[] = [
      { key: "institute_name", value: "Northwind Academy", type: "STRING", defaultValue: null, sensitive: false },
      { key: "mobile_login_enabled", value: null, type: "BOOLEAN", defaultValue: true, sensitive: false },
      { key: "phone_region", value: null, type: "STRING", defaultValue: "LK", sensitive: false },
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

  async function mockLoginMobile(page: Page, glob: string, initial: string | null, conflictFor?: string) {
    let current = initial;
    const puts: unknown[] = [];
    await page.route(glob, async (route) => {
      if (route.request().method() === "PUT") {
        const body = route.request().postDataJSON() as { loginMobile: string | null };
        puts.push(body);
        if (conflictFor && body.loginMobile === conflictFor) {
          await route.fulfill({
            status: 409,
            contentType: "application/json",
            body: JSON.stringify(
              apiError("MOBILE_ALREADY_REGISTERED", "This mobile number is already registered", [
                { field: "loginMobile", message: "This mobile number is already registered" },
              ])
            ),
          });
          return;
        }
        current = body.loginMobile ? "+94705551234" : null;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess({ loginMobile: current })) });
    });
    return puts;
  }

  test("Login options: switch and country save on change", async ({ page }) => {
    const puts = await mockAdmin(page);
    await mockLoginMobile(page, "**/v1/auth/me/login-mobile", null);
    await page.goto("/tenant-admin/settings/general");

    const section = page.getByTestId("login-options");
    const toggle = section.getByRole("switch", { name: "Allow sign-in with mobile number" });
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await section.getByLabel("Phone number country").selectOption("IN");

    await expect.poll(() => puts.length).toBe(2);
    expect(puts).toEqual([{ mobile_login_enabled: false }, { phone_region: "IN" }]);
  });

  test("the admin sets their own sign-in mobile; a taken number is shown on the field", async ({ page }) => {
    await mockAdmin(page);
    const puts = await mockLoginMobile(page, "**/v1/auth/me/login-mobile", null, "0771234567");
    await page.goto("/tenant-admin/settings/general");
    const card = page.getByTestId("my-login-mobile-card");
    await expect(card).toContainText("Not set — signs in with email only.");

    await card.getByLabel("Mobile number").fill("0771234567");
    await card.getByTestId("my-login-mobile-save").click();
    await expect(card.getByRole("alert")).toHaveText("This mobile number is already registered");

    await card.getByLabel("Mobile number").fill("070 555 1234");
    await card.getByTestId("my-login-mobile-save").click();
    await expect(card.getByText("Sign-in mobile saved.")).toBeVisible();
    await expect(card.getByLabel("Mobile number")).toHaveValue("+94705551234");
    expect(puts).toEqual([{ loginMobile: "0771234567" }, { loginMobile: "070 555 1234" }]);
  });

  test("a staff member's sign-in mobile is set from the staff list", async ({ page }) => {
    await mockAdmin(page);
    await mockJson(
      page,
      "**/v1/staff",
      200,
      apiSuccess([{ id: "staff-1", name: "Finance Person", email: "finance@example.test", roleCode: "FINANCE_STAFF", status: "ACTIVE" }])
    );
    await mockJson(page, "**/v1/roles*", 200, apiSuccess([]));
    const puts = await mockLoginMobile(page, "**/v1/staff/staff-1/login-mobile", null);
    await page.goto("/tenant-admin/staff");

    await page.getByRole("button", { name: "Sign-in mobile for Finance Person" }).first().click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByText("Sign-in mobile — Finance Person")).toBeVisible();
    await dialog.getByLabel("Mobile number").fill("0705551234");
    await dialog.getByTestId("staff-login-mobile-save").click();

    await expect(dialog.getByText("Sign-in mobile saved.")).toBeVisible();
    expect(puts).toEqual([{ loginMobile: "0705551234" }]);
  });

  test("read-only auditor sees the switch disabled", async ({ page }) => {
    await mockAdmin(page, "READ_ONLY_AUDITOR");
    await mockLoginMobile(page, "**/v1/auth/me/login-mobile", null);
    await page.goto("/tenant-admin/settings/general");

    await expect(page.getByRole("switch", { name: "Allow sign-in with mobile number" })).toBeDisabled();
    await expect(page.getByLabel("Phone number country")).toBeDisabled();
  });
});
