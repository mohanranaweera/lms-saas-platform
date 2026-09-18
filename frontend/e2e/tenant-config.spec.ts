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

const BRANDING_PROPERTIES = [
  property("primary_color", "#0F172A"),
  property("secondary_color", "#F59E0B"),
  property("logo_url", "https://cdn.example.test/logo.png"),
  property("favicon_url", "https://cdn.example.test/favicon.ico"),
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
    await page.getByLabel("Support email").fill("not-an-email");
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
    await page.getByLabel("Support email").fill("new-support@example-institute.test");
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

test.describe("Branding settings", () => {
  test("Tenant Admin can edit and save Branding settings; a WCAG-failing color pair shows the server's field error", async ({
    page,
  }) => {
    await mockJson(page, "**/v1/tenant-config/GENERAL", 200, apiSuccess(GENERAL_PROPERTIES));
    await mockJson(page, "**/v1/tenant-config/BRANDING", 200, apiSuccess(BRANDING_PROPERTIES));
    await mockTenantSession(page, "TENANT_ADMIN");

    await page.goto("/tenant-admin/settings/branding");
    await expect(page.getByLabel("Primary color")).toHaveValue("#0F172A");
    // Live preview renders the institute name from the GENERAL domain.
    await expect(page.getByRole("region", { name: "Branding preview" }).getByText("Example Institute")).toBeVisible();

    await page.route("**/v1/tenant-config/BRANDING", async (route) => {
      if (route.request().method() === "PUT") {
        await route.fulfill({
          status: 400,
          contentType: "application/json",
          body: JSON.stringify(
            apiError("VALIDATION_ERROR", "Validation failed", [
              {
                field: "secondary_color",
                message: "secondary_color does not meet the required 4.5:1 contrast ratio against primary_color.",
              },
            ])
          ),
        });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(BRANDING_PROPERTIES)) });
    });

    // A hex-well-formed but (per the mocked backend) contrast-failing pair —
    // this has no client-side equivalent check, so the client schema lets it
    // through and only the real PUT response rejects it.
    await page.getByLabel("Secondary color").fill("#FFFFFF");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(
      page.getByText("secondary_color does not meet the required 4.5:1 contrast ratio against primary_color.")
    ).toBeVisible();
  });

  test("Read-only Auditor sees the branding form read-only with the live preview still rendering current values", async ({
    page,
  }) => {
    await mockJson(page, "**/v1/tenant-config/GENERAL", 200, apiSuccess(GENERAL_PROPERTIES));
    await mockJson(page, "**/v1/tenant-config/BRANDING", 200, apiSuccess(BRANDING_PROPERTIES));
    await mockTenantSession(page, "READ_ONLY_AUDITOR");

    await page.goto("/tenant-admin/settings/branding");

    await expect(page.getByLabel("Primary color")).toHaveValue("#0F172A");
    await expect(page.getByLabel("Primary color")).toBeDisabled();
    await expect(page.getByRole("button", { name: "Save changes" })).toHaveCount(0);
    await expect(page.getByRole("region", { name: "Branding preview" }).getByText("Example Institute")).toBeVisible();
  });
});
