import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Wave 1 (Tenant Admin IA + Configuration Framework) — General
 * (`/tenant-admin/settings/general`) and Branding
 * (`/tenant-admin/settings/branding`) settings screens, backed by
 * `GET/PUT /api/v1/tenant-config/{GENERAL|BRANDING}`.
 *
 * No real backend runs in this environment — every `/v1/tenant-config/**`
 * call is intercepted via `page.route()`, shaped like the documented
 * `ApiResponse<T>` envelope.
 */

async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

function property(key: string, value: unknown, defaultValue: unknown = null) {
  return { key, value, type: "STRING", defaultValue, sensitive: false };
}

const GENERAL_PROPERTIES = [
  property("institute_name", "Example Institute"),
  property("support_email", "support@example-institute.test"),
  property("support_phone", "+94 11 234 5678"),
  property("default_timezone", "UTC", "UTC"),
  property("default_currency", "USD", "USD"),
];

test.describe("General settings", () => {
  test("direct-URL access by an unauthorized role renders PermissionDeniedState from a real 403", async ({
    page,
  }) => {
    await mockJson(
      page,
      "**/v1/tenant-config/GENERAL",
      403,
      apiError("FORBIDDEN", "You do not have permission to view institute configuration.")
    );
    await mockTenantSession(page, "FINANCE_STAFF");

    await page.goto("/tenant-admin/settings/general");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission to view this." });
    await expect(denied).toBeVisible();
    await expect(denied).toContainText("You do not have permission to view institute configuration.");
    await expect(page.getByLabel("Institute name")).toHaveCount(0);
  });

  test("Tenant Admin can edit and save General settings; an invalid email shows an inline error and does not save", async ({
    page,
  }) => {
    await mockJson(page, "**/v1/tenant-config/GENERAL", 200, apiSuccess(GENERAL_PROPERTIES));
    await mockTenantSession(page, "TENANT_ADMIN");

    await page.goto("/tenant-admin/settings/general");
    await expect(page.getByLabel("Institute name")).toHaveValue("Example Institute");

    // Invalid value first: blocked client-side, no request fires.
    let putCalled = false;
    await page.route("**/v1/tenant-config/GENERAL", async (route) => {
      if (route.request().method() === "PUT") {
        putCalled = true;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(GENERAL_PROPERTIES)) });
    });
    await page.getByLabel("Contact email").fill("not-an-email");
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.getByText("Enter a valid email address.")).toBeVisible();
    expect(putCalled).toBe(false);

    // Valid edit: saves and sends only the changed key.
    let putBody: unknown = null;
    await page.route("**/v1/tenant-config/GENERAL", async (route) => {
      if (route.request().method() === "PUT") {
        putBody = route.request().postDataJSON();
        const updated = GENERAL_PROPERTIES.map((prop) =>
          prop.key === "support_email" ? { ...prop, value: "new-support@example-institute.test" } : prop
        );
        await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(updated)) });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(GENERAL_PROPERTIES)) });
    });
    await page.getByLabel("Contact email").fill("new-support@example-institute.test");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.getByText("Saved.").last()).toBeVisible();
    expect(putBody).toEqual({ support_email: "new-support@example-institute.test" });
  });

  test("Read-only Auditor sees the form read-only, with current values but no save action", async ({
    page,
  }) => {
    await mockJson(page, "**/v1/tenant-config/GENERAL", 200, apiSuccess(GENERAL_PROPERTIES));
    await mockTenantSession(page, "READ_ONLY_AUDITOR");

    await page.goto("/tenant-admin/settings/general");

    await expect(page.getByLabel("Institute name")).toHaveValue("Example Institute");
    await expect(page.getByLabel("Institute name")).toBeDisabled();
    await expect(page.getByRole("button", { name: "Save changes" })).toHaveCount(0);
    await expect(page.getByText("You have view-only access to institute configuration.")).toBeVisible();
  });
});

// Branding moved into Settings > Appearance (/settings/branding redirects);
// tenant-appearance-settings.spec.ts covers saving, contrast errors, the
// read-only auditor and a 403.
