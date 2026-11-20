import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";
import { mockTenantSession } from "./fixtures/class-session-mocks";

/**
 * AUTH-PWD-01 / ADR-020 (frontend, mock-only). Token validity, single use,
 * tenant isolation, no enumeration and the PASSWORD_CHANGE_REQUIRED gate are
 * proven by the backend's AccountSetupAndPasswordIntegrationTest; these tests
 * prove the pages reflect the backend, keep the token out of the address bar,
 * and send the right requests.
 */

const TOKEN = "Synthetic_Token_abc123";
const NEW_PASSWORD = "Brand-New-Password-42!";

async function mockVerify(page: Page, purpose = "ACCOUNT_SETUP") {
  await mockJson(
    page,
    "**/v1/auth/password-tokens/verify",
    200,
    apiSuccess({ purpose, emailMasked: "o***@academy.test", expiresAt: "2026-10-03T08:00:00Z" })
  );
}

test.describe("Account setup and reset links", () => {
  test("removes the token from the address bar and sets the password", async ({ page }) => {
    await mockVerify(page);
    let sent: unknown = null;
    await page.route("**/v1/auth/password-tokens/complete", async (route) => {
      sent = route.request().postDataJSON();
      await route.fulfill({ status: 204 });
    });

    await page.goto(`/account-setup#token=${TOKEN}`);

    await expect(page.getByText("o***@academy.test")).toBeVisible();
    await expect(page).toHaveURL(/\/account-setup$/);
    await page.getByLabel("New password", { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel("Confirm new password").fill(NEW_PASSWORD);
    await page.getByRole("button", { name: "Set password" }).click();

    await expect(page.getByRole("status").filter({ hasText: "Your account is ready" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Go to sign in" })).toHaveAttribute("href", "/login");
    expect(sent).toEqual({ token: TOKEN, newPassword: NEW_PASSWORD });
  });

  test("an invalid, expired or used link shows one generic message", async ({ page }) => {
    await mockJson(
      page,
      "**/v1/auth/password-tokens/verify",
      400,
      apiError("TOKEN_INVALID", "This link is invalid or has expired.")
    );

    await page.goto(`/reset-password#token=${TOKEN}`);

    await expect(page.getByText("This link is invalid or has expired.", { exact: false })).toBeVisible();
    await expect(page.getByRole("link", { name: "request a new link" })).toHaveAttribute("href", "/forgot-password");
    await expect(page).toHaveURL(/\/reset-password$/);
  });

  test("a link without a token is invalid without calling the backend", async ({ page }) => {
    let called = false;
    await page.route("**/v1/auth/password-tokens/verify", async (route) => {
      called = true;
      await route.abort();
    });

    await page.goto("/account-setup");

    await expect(page.getByText("This link is invalid or has expired.", { exact: false })).toBeVisible();
    expect(called).toBe(false);
  });

  test("mismatched passwords are caught before anything is sent", async ({ page }) => {
    await mockVerify(page, "PASSWORD_RESET");
    let called = false;
    await page.route("**/v1/auth/password-tokens/complete", async (route) => {
      called = true;
      await route.fulfill({ status: 204 });
    });

    await page.goto(`/reset-password#token=${TOKEN}`);
    await page.getByLabel("New password", { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel("Confirm new password").fill("Something-Else-1!");
    await page.getByRole("button", { name: "Reset password" }).click();

    await expect(page.getByText("The passwords don't match.")).toBeVisible();
    expect(called).toBe(false);
  });
});

test.describe("Forgot password", () => {
  test("always shows the same confirmation and sends only the email", async ({ page }) => {
    let sent: unknown = null;
    await page.route("**/v1/auth/password-reset/request", async (route) => {
      sent = route.request().postDataJSON();
      await fulfillJson(
        route,
        202,
        apiSuccess({ message: "If an account exists for this email, we've sent a link to reset the password." })
      );
    });

    await page.goto("/forgot-password");
    await page.getByLabel("Email").fill("someone@example.test");
    await page.getByRole("button", { name: "Send reset link" }).click();

    await expect(page.getByRole("status").filter({ hasText: "If an account exists for this email" })).toBeVisible();
    expect(sent).toEqual({ email: "someone@example.test" });
  });

  test("the tenant sign-in page links to it", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("link", { name: "Forgot password?" })).toHaveAttribute("href", "/forgot-password");
  });
});

test.describe("Change password", () => {
  test("a wrong current password shows on its field, then a change succeeds", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    let attempt = 0;
    await page.route("**/v1/auth/password", async (route) => {
      attempt += 1;
      if (attempt === 1) {
        await fulfillJson(
          route,
          400,
          apiError("VALIDATION_ERROR", "Invalid password", [{ field: "currentPassword", message: "is incorrect" }])
        );
        return;
      }
      await fulfillJson(route, 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "STUDENT" }))));
    });

    await page.goto("/change-password");
    await page.getByLabel("Current password").fill("Wrong-Password-1!");
    await page.getByLabel("New password", { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel("Confirm new password").fill(NEW_PASSWORD);
    await page.getByRole("button", { name: "Change password" }).click();
    await expect(page.getByText("Current password is incorrect.")).toBeVisible();

    await page.getByLabel("Current password").fill("Correct-Password-1!");
    await page.getByLabel("New password", { exact: true }).fill(NEW_PASSWORD);
    await page.getByLabel("Confirm new password").fill(NEW_PASSWORD);
    await page.getByRole("button", { name: "Change password" }).click();
    await expect(page.getByRole("status").filter({ hasText: "Your password has been changed" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Continue" })).toHaveAttribute("href", "/student/dashboard");
  });

  test("signing in with a temporary password goes straight to change password", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      "**/v1/auth/login",
      200,
      apiSuccess({
        accessToken: fakeJwt({ role: "STUDENT" }),
        expiresInSeconds: 900,
        sessionId: "s-1",
        mustChangePassword: true,
      })
    );

    await page.goto("/login");
    await page.getByLabel("Email").fill("student@example.test");
    await page.getByLabel("Password", { exact: true }).fill("Temporary-1!");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page).toHaveURL(/\/change-password$/);
  });

  test("a PASSWORD_CHANGE_REQUIRED answer anywhere redirects to change password", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(
      page,
      "**/v1/teacher/integrations",
      403,
      apiError("PASSWORD_CHANGE_REQUIRED", "You must change your password before continuing.")
    );

    await page.goto("/teacher/settings/integrations");

    await expect(page).toHaveURL(/\/change-password$/);
  });
});

test.describe("Platform Admin - Tenant Admin panel", () => {
  const TENANT_ID = "tenant-1";

  const INVITED = {
    status: "INVITED",
    emailMasked: "o***@academy.test",
    invitedAt: "2026-09-30T06:31:00Z",
    inviteExpiresAt: "2099-01-01T00:00:00Z",
  };

  async function mockDetail(page: Page, firstAdmin: Record<string, unknown>, status = "TRIAL") {
    await mockJson(
      page,
      "**/v1/platform-admin/auth/refresh",
      200,
      apiSuccess(refreshResponseBody(fakeJwt({ role: "PLATFORM_ADMIN" })))
    );
    await mockJson(
      page,
      `**/v1/platform-admin/tenants/${TENANT_ID}`,
      200,
      apiSuccess({
        id: TENANT_ID,
        name: "Test Academy",
        subdomain: "academy",
        status,
        requestedPlan: "standard",
        contactName: "Test Owner",
        contactEmail: "owner@academy.test",
        contactPhone: null,
        createdAt: "2026-09-30T06:30:00Z",
        firstAdmin,
      })
    );
  }

  test("a pending invitation can be resent", async ({ page }) => {
    await mockDetail(page, INVITED);
    let sent: unknown = "none";
    await page.route(`**/v1/platform-admin/tenants/${TENANT_ID}/admin-invitation`, async (route) => {
      sent = route.request().postDataJSON();
      await fulfillJson(route, 202, apiSuccess(INVITED));
    });

    await page.goto(`/platform-admin/tenants/${TENANT_ID}`);
    const panel = page.getByRole("region", { name: "Tenant Admin" });
    await expect(panel.getByText("Invitation pending")).toBeVisible();
    await panel.getByRole("button", { name: "Resend invitation" }).click();

    await expect(panel.getByRole("status").filter({ hasText: "Invitation sent to o***@academy.test." })).toBeVisible();
    expect(sent).toEqual({});
  });

  test("the email can be corrected while pending, and a throttle is announced", async ({ page }) => {
    await mockDetail(page, INVITED);
    let attempt = 0;
    let sent: unknown = null;
    await page.route(`**/v1/platform-admin/tenants/${TENANT_ID}/admin-invitation`, async (route) => {
      attempt += 1;
      sent = route.request().postDataJSON();
      if (attempt === 1) {
        await fulfillJson(route, 202, apiSuccess({ ...INVITED, emailMasked: "f***@academy.test" }));
        return;
      }
      await fulfillJson(
        route,
        429,
        apiError("TOO_MANY_REQUESTS", "An invitation was just sent. Try again in a minute.")
      );
    });

    await page.goto(`/platform-admin/tenants/${TENANT_ID}`);
    const panel = page.getByRole("region", { name: "Tenant Admin" });
    await panel.getByRole("button", { name: "Change email" }).click();
    await panel.getByLabel("Admin email").fill("fixed@academy.test");
    await panel.getByRole("button", { name: "Save and resend" }).click();
    await expect(panel.getByRole("status").filter({ hasText: "f***@academy.test" })).toBeVisible();
    expect(sent).toEqual({ email: "fixed@academy.test" });

    await panel.getByRole("button", { name: "Resend invitation" }).click();
    await expect(panel.getByRole("alert").filter({ hasText: "Try again in a minute" })).toBeVisible();
  });

  test("an active admin has no invitation actions", async ({ page }) => {
    await mockDetail(page, { status: "ACTIVE", emailMasked: "o***@academy.test", invitedAt: null, inviteExpiresAt: null });

    await page.goto(`/platform-admin/tenants/${TENANT_ID}`);
    const panel = page.getByRole("region", { name: "Tenant Admin" });
    await expect(panel.getByText("Active", { exact: true })).toBeVisible();
    await expect(panel.getByRole("button")).toHaveCount(0);
  });

  test("an approved tenant without an admin can invite one", async ({ page }) => {
    await mockDetail(page, { status: "NONE", emailMasked: null, invitedAt: null, inviteExpiresAt: null }, "ACTIVE");

    await page.goto(`/platform-admin/tenants/${TENANT_ID}`);
    const panel = page.getByRole("region", { name: "Tenant Admin" });
    await expect(panel.getByText("No admin yet")).toBeVisible();
    await expect(panel.getByRole("button", { name: "Invite with the registration contact email" })).toBeVisible();
  });
});
