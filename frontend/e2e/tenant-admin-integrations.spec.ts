import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";
import { mockTenantSession } from "./fixtures/class-session-mocks";
import { connection, tenantIntegrations } from "./fixtures/integrations-mocks";

/**
 * MEDIA-INT-01 — Tenant Admin → Integrations (frontend, mock-only).
 *
 * Key verification, write-only storage, permissions and tenant isolation are
 * proven by the backend's `TenantAdminIntegrationsIntegrationTest`. These
 * tests prove the page reflects backend state, never shows keys, maps
 * provider errors to fields, and requires a reason before disconnecting a
 * teacher's account.
 */

const BUNNY = connection({
  id: "conn-bunny",
  provider: "BUNNY",
  accountLabel: "Bunny library 12345",
  settings: { libraryId: "12345", cdnHostname: "vz-abc.b-cdn.net", tokenAuthConfigured: false },
});

const TEACHER_ZOOM_ROW = {
  teacherId: "teacher-1",
  teacherName: "Jane Perera",
  connection: connection({ id: "conn-zoom", provider: "ZOOM", accountLabel: "jane.zoom@example.test" }),
};

async function mockOverview(page: Page, overrides: Record<string, unknown> = {}): Promise<void> {
  await mockJson(page, "**/v1/tenant-admin/integrations", 200, apiSuccess(tenantIntegrations(overrides)));
}

test.describe("Tenant Admin integrations", () => {
  test("shows the institute's accounts and teachers' connections without any key", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockOverview(page, { bunny: BUNNY, videoHost: "BUNNY", teacherConnections: [TEACHER_ZOOM_ROW] });

    await page.goto("/tenant-admin/settings/integrations");

    const bunny = page.getByRole("region", { name: "Bunny.net Stream" });
    await expect(bunny.getByText("Saved (hidden)")).toBeVisible();
    await expect(bunny.getByText("vz-abc.b-cdn.net").first()).toBeVisible();
    await expect(bunny.getByLabel("Stream API key")).toHaveValue("");
    await expect(page.getByRole("region", { name: "DynTube" }).getByText("Not set up yet.")).toBeVisible();
    await expect(page.getByRole("radio", { name: "Bunny.net" })).toBeChecked();
    await expect(page.getByRole("radio", { name: /DynTube/ })).toBeDisabled();
    await expect(page.getByRole("cell", { name: "Jane Perera", exact: true })).toBeVisible();
    await expect(page.getByRole("cell", { name: "jane.zoom@example.test" })).toBeVisible();
  });

  test("a DynTube key rejected by DynTube is shown on the key field", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockOverview(page);
    await mockJson(
      page,
      "**/v1/tenant-admin/integrations/dyntube",
      400,
      apiError("PROVIDER_CREDENTIALS_INVALID", "DynTube rejected the credentials", [
        { field: "apiKey", message: "was rejected by DynTube" },
      ])
    );

    await page.goto("/tenant-admin/settings/integrations");
    await page.getByLabel("DynTube API key").fill("wrong-key");
    await page.getByRole("button", { name: "Connect DynTube" }).click();

    await expect(page.getByText("was rejected by DynTube")).toBeVisible();
  });

  test("a DynTube key is required the first time and sent only once", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockOverview(page);
    let sentBody: unknown = null;
    await page.route("**/v1/tenant-admin/integrations/dyntube", async (route) => {
      sentBody = route.request().postDataJSON();
      await fulfillJson(route, 200, apiSuccess(connection({ id: "conn-dyn", provider: "DYNTUBE", accountLabel: "DynTube" })));
    });

    await page.goto("/tenant-admin/settings/integrations");
    await page.getByRole("button", { name: "Connect DynTube" }).click();
    await expect(page.getByText("API key is required.")).toBeVisible();
    expect(sentBody).toBeNull();

    await page.getByLabel("DynTube API key").fill("test-dyntube-key");
    await page.getByRole("button", { name: "Connect DynTube" }).click();
    await expect(page.getByText("DynTube account saved.")).toBeVisible();
    expect(sentBody).toEqual({ apiKey: "test-dyntube-key" });
    await expect(page.getByLabel("DynTube API key")).toHaveValue("");
  });

  test("a Bunny CDN hostname must be a bare hostname", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockOverview(page);
    let called = false;
    await page.route("**/v1/tenant-admin/integrations/bunny", async (route) => {
      called = true;
      await route.abort();
    });

    await page.goto("/tenant-admin/settings/integrations");
    await page.getByLabel("Stream API key").fill("test-bunny-key");
    await page.getByLabel("Video library ID").fill("12345");
    await page.getByLabel("CDN hostname").fill("https://vz-abc.b-cdn.net/videos");
    await page.getByRole("button", { name: "Connect Bunny.net" }).click();

    await expect(page.getByText("Enter just the hostname", { exact: false })).toBeVisible();
    expect(called).toBe(false);
  });

  test("disconnecting a teacher's account requires a reason", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockOverview(page, { teacherConnections: [TEACHER_ZOOM_ROW] });
    let sentBody: unknown = null;
    await page.route(`**/v1/tenant-admin/integrations/connections/${TEACHER_ZOOM_ROW.connection.id}/disconnect`, async (route) => {
      sentBody = route.request().postDataJSON();
      await fulfillJson(route, 200, apiSuccess({ ...TEACHER_ZOOM_ROW.connection, status: "DISCONNECTED" }));
    });

    await page.goto("/tenant-admin/settings/integrations");
    await page.getByRole("button", { name: "Disconnect Jane Perera's Zoom" }).click();
    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Disconnect" }).click();
    await expect(dialog.getByText("A reason is required.")).toBeVisible();
    expect(sentBody).toBeNull();

    await dialog.getByLabel("Reason").fill("Teacher left the institute");
    await dialog.getByRole("button", { name: "Disconnect" }).click();
    await expect(dialog).toHaveCount(0);
    expect(sentBody).toEqual({ reason: "Teacher left the institute" });
  });

  test("distinguishes no connections from no filter matches", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockOverview(page, { teacherConnections: [TEACHER_ZOOM_ROW] });

    await page.goto("/tenant-admin/settings/integrations");
    await page.getByLabel("Service").selectOption("VIMEO");

    await expect(page.getByText("No connections match your filters")).toBeVisible();
    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect(page.getByRole("cell", { name: "Jane Perera", exact: true })).toBeVisible();
  });

  test("shows an actionable empty state when no teacher has connected", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockOverview(page);

    await page.goto("/tenant-admin/settings/integrations");

    await expect(page.getByText("No teacher has connected an account yet")).toBeVisible();
  });

  test("a read-only auditor sees status but no controls", async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockOverview(page, { bunny: BUNNY, teacherConnections: [TEACHER_ZOOM_ROW] });

    await page.goto("/tenant-admin/settings/integrations");

    await expect(page.getByText("Only a Tenant Admin can change these settings.")).toBeVisible();
    await expect(page.getByRole("button", { name: /Save|Connect|Disconnect/ })).toHaveCount(0);
    await expect(page.getByRole("cell", { name: "Jane Perera", exact: true })).toBeVisible();
  });

  test("renders the permission-denied state on a backend 403", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await mockJson(page, "**/v1/tenant-admin/integrations", 403, apiError("FORBIDDEN", "You do not have permission"));

    await page.goto("/tenant-admin/settings/integrations");

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
  });

  test("shows teachers' connections as cards on a phone", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockOverview(page, { teacherConnections: [TEACHER_ZOOM_ROW] });

    await page.goto("/tenant-admin/settings/integrations");

    await expect(page.getByText("Jane Perera", { exact: true }).filter({ visible: true })).toBeVisible();
    await expect(page.getByRole("table")).toBeHidden();
  });
});
