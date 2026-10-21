import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";
import { mockTenantSession } from "./fixtures/class-session-mocks";
import {
  bunnyLibrary,
  connection,
  mockBunnyLibraries,
  teacherLibraryRow,
  tenantIntegrations,
} from "./fixtures/integrations-mocks";

/**
 * MEDIA-INT-01 / MEDIA-INT-01A — Tenant Admin → Integrations (frontend, mock-only).
 *
 * Key verification, write-only storage, permissions and tenant isolation are
 * proven by the backend's `TenantAdminIntegrationsIntegrationTest` and
 * `BunnyTeacherLibraryIntegrationTest` (per-teacher libraries, including the
 * cross-tenant 404). These
 * tests prove the page reflects backend state, never shows keys, maps
 * provider errors to fields, and requires a reason before disconnecting a
 * teacher's account.
 */

const BUNNY = connection({
  id: "conn-bunny",
  provider: "BUNNY",
  accountLabel: "Bunny.net account",
  settings: { keyType: "ACCOUNT" },
});

/** Saved before MEDIA-INT-01A with a single library-only key. */
const LEGACY_BUNNY = connection({
  id: "conn-bunny-legacy",
  provider: "BUNNY",
  accountLabel: "Bunny library 12345",
  settings: { libraryId: "12345", cdnHostname: "vz-abc.b-cdn.net", tokenAuthConfigured: false },
});

const TEACHER_ZOOM_ROW = {
  teacherId: "teacher-1",
  teacherName: "Jane Perera",
  connection: connection({ id: "conn-zoom", provider: "ZOOM", accountLabel: "jane.zoom@example.test" }),
};

async function mockOverview(
  page: Page,
  overrides: Record<string, unknown> = {},
  libraries: ReturnType<typeof teacherLibraryRow>[] = []
): Promise<void> {
  await mockJson(page, "**/v1/tenant-admin/integrations", 200, apiSuccess(tenantIntegrations(overrides)));
  await mockBunnyLibraries(page, libraries);
}

test.describe("Tenant Admin integrations", () => {
  test("shows the institute's accounts and teachers' connections without any key", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockOverview(page, { bunny: BUNNY, videoHost: "BUNNY", teacherConnections: [TEACHER_ZOOM_ROW] });

    await page.goto("/tenant-admin/settings/integrations");

    const bunny = page.getByRole("region", { name: "Bunny.net Stream" });
    await expect(bunny.getByText("Saved (hidden)")).toBeVisible();
    await expect(bunny.getByLabel("Account API key")).toHaveValue("");
    await expect(bunny.getByLabel("Video library ID")).toHaveCount(0);
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
    // VIDEO Phase 13: blank token key / project are sent as null ("keep what is saved").
    expect(sentBody).toEqual({ apiKey: "test-dyntube-key", tokenSecurityKey: null, projectId: null });
    await expect(page.getByLabel("DynTube API key")).toHaveValue("");
  });

  test("the DynTube token key is write-only and a project the account doesn't own is shown on its field", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const dyntube = connection({
      id: "conn-dyn",
      provider: "DYNTUBE",
      accountLabel: "DynTube",
      settings: { projectId: "RyfsGebl7kKs6jyZeNmcQ", tokenSecurityKeyConfigured: true },
    });
    await mockOverview(page, { dyntube, videoHost: "DYNTUBE" });
    const sent: unknown[] = [];
    await page.route("**/v1/tenant-admin/integrations/dyntube", async (route) => {
      const body = route.request().postDataJSON() as { projectId: string | null };
      sent.push(body);
      if (body.projectId === "projNotMine000001") {
        await fulfillJson(
          route,
          400,
          apiError("VALIDATION_ERROR", "Invalid", [
            { field: "projectId", message: "is not a project of this DynTube account" },
          ])
        );
        return;
      }
      await fulfillJson(route, 200, apiSuccess(dyntube));
    });

    await page.goto("/tenant-admin/settings/integrations");
    const region = page.getByRole("region", { name: "DynTube" });
    await expect(region.getByText("Saved (hidden)")).toHaveCount(2);
    await expect(region.getByLabel("Token security key")).toHaveValue("");
    await expect(region.getByLabel("Upload project ID")).toHaveValue("RyfsGebl7kKs6jyZeNmcQ");

    await region.getByLabel("Upload project ID").fill("projNotMine000001");
    await region.getByRole("button", { name: "Save DynTube" }).click();
    await expect(region.getByText("is not a project of this DynTube account")).toBeVisible();

    await region.getByLabel("Token security key").fill("new-token-key");
    await region.getByLabel("Upload project ID").fill("RyfsGebl7kKs6jyZeNmcQ");
    await region.getByRole("button", { name: "Save DynTube" }).click();
    await expect(region.getByText("DynTube account saved.")).toBeVisible();
    expect(sent.at(-1)).toEqual({ apiKey: null, tokenSecurityKey: "new-token-key", projectId: "RyfsGebl7kKs6jyZeNmcQ" });
    await expect(region.getByLabel("Token security key")).toHaveValue("");
    await expect(page.getByText("new-token-key")).toHaveCount(0);
  });

  test("Bunny.net needs only the account API key", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockOverview(page);
    let sentBody: unknown = null;
    await page.route("**/v1/tenant-admin/integrations/bunny", async (route) => {
      sentBody = route.request().postDataJSON();
      await fulfillJson(route, 200, apiSuccess(BUNNY));
    });

    await page.goto("/tenant-admin/settings/integrations");
    const bunny = page.getByRole("region", { name: "Bunny.net Stream" });
    await expect(bunny.getByLabel("Account API key")).toHaveAccessibleDescription(/not a video library key/);
    await bunny.getByRole("button", { name: "Connect Bunny.net" }).click();
    await expect(bunny.getByText("Account API key is required.")).toBeVisible();
    expect(sentBody).toBeNull();

    await bunny.getByLabel("Account API key").fill("test-bunny-account-key");
    await bunny.getByRole("button", { name: "Connect Bunny.net" }).click();
    await expect(bunny.getByText("Bunny.net account saved.")).toBeVisible();
    expect(sentBody).toEqual({ apiKey: "test-bunny-account-key" });
    await expect(bunny.getByLabel("Account API key")).toHaveValue("");
  });

  test("a video library key rejected by Bunny.net is shown on the key field", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockOverview(page);
    await mockJson(
      page,
      "**/v1/tenant-admin/integrations/bunny",
      400,
      apiError("PROVIDER_CREDENTIALS_INVALID", "Bunny.net rejected the API key", [
        { field: "apiKey", message: "was rejected by Bunny.net - use an account API key, not a video library key" },
      ])
    );

    await page.goto("/tenant-admin/settings/integrations");
    await page.getByLabel("Account API key").fill("library-only-key");
    await page.getByRole("button", { name: "Connect Bunny.net" }).click();

    await expect(page.getByText("use an account API key", { exact: false })).toBeVisible();
  });

  test("a connection saved with a library-only key asks to reconnect", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockOverview(page, { bunny: LEGACY_BUNNY });

    await page.goto("/tenant-admin/settings/integrations");

    const bunny = page.getByRole("region", { name: "Bunny.net Stream" });
    await expect(bunny.getByText("must be replaced with a bunny.net account API key", { exact: false })).toBeVisible();
    await expect(bunny.getByText("Enter an account API key below to reconnect.", { exact: false })).toBeVisible();
    // The summary agrees with the banner: the old library key can't be kept (UX-5).
    await expect(bunny.getByText("Library key (must be replaced)")).toBeVisible();
    await expect(bunny.getByRole("button", { name: "Connect Bunny.net", exact: true })).toBeVisible();
    await expect(bunny.getByText("vz-abc.b-cdn.net")).toHaveCount(0);
  });

  test("a read-only role is told to ask a Tenant Admin to reconnect", async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockOverview(page, { bunny: LEGACY_BUNNY });

    await page.goto("/tenant-admin/settings/integrations");

    const bunny = page.getByRole("region", { name: "Bunny.net Stream" });
    await expect(bunny.getByText("Ask a Tenant Admin to reconnect Bunny.net.", { exact: false })).toBeVisible();
    await expect(bunny.getByText("Enter an account API key below", { exact: false })).toHaveCount(0);
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

  test.describe("Teacher video libraries", () => {
    const JANE = "teacher-1";

    test("lists every teacher with their library status and no ids or keys", async ({ page }) => {
      await mockTenantSession(page, "TENANT_ADMIN");
      await mockOverview(page, { bunny: BUNNY }, [
        teacherLibraryRow(JANE, "Jane Perera", bunnyLibrary()),
        teacherLibraryRow(
          "teacher-2",
          "Kamal Silva",
          bunnyLibrary({
            id: "library-2",
            status: "UNAVAILABLE",
            libraryName: "Old [aaaaaaaaaaaa]",
            lastError: "Not visible to the current Bunny.net account.",
          })
        ),
        teacherLibraryRow("teacher-3", "Nimal Fernando"),
      ]);

      await page.goto("/tenant-admin/settings/integrations");

      const section = page.getByRole("region", { name: "Teacher video libraries" });
      const table = section.getByRole("table", { name: "Teacher video libraries" });
      await expect(table.getByRole("row", { name: /Jane Perera/ }).getByText("Ready")).toBeVisible();
      await expect(table.getByRole("row", { name: /Kamal Silva/ }).getByText("Unavailable")).toBeVisible();
      await expect(table.getByText("Not visible to the current Bunny.net account.")).toBeVisible();
      await expect(table.getByRole("row", { name: /Nimal Fernando/ }).getByText("Not set up")).toBeVisible();
      // Only a teacher without a library gets a create button.
      await expect(table.getByRole("button", { name: /library now|Retry/ })).toHaveCount(1);
      await expect(table.getByRole("button", { name: "Create Nimal Fernando's library now" })).toBeVisible();
    });

    test("Create library now asks the backend and shows the ready library", async ({ page }) => {
      await mockTenantSession(page, "TENANT_ADMIN");
      await mockOverview(page, { bunny: BUNNY });
      let created = false;
      await page.route("**/v1/tenant-admin/integrations/bunny/libraries", async (route) => {
        await fulfillJson(route, 200, apiSuccess([teacherLibraryRow(JANE, "Jane Perera", created ? bunnyLibrary() : null)]));
      });
      let requestUrl: string | null = null;
      await page.route(`**/v1/tenant-admin/integrations/bunny/libraries/${JANE}`, async (route) => {
        requestUrl = route.request().url();
        created = true;
        await fulfillJson(route, 201, apiSuccess(bunnyLibrary()));
      });

      await page.goto("/tenant-admin/settings/integrations");
      const table = page.getByRole("table", { name: "Teacher video libraries" });
      await table.getByRole("button", { name: "Create Jane Perera's library now" }).click();

      const announced = page.getByRole("status").filter({ hasText: "Jane Perera's video library is ready." });
      await expect(announced).toBeVisible();
      // The clicked button is gone, so focus moves to the announcement instead of the page body (UX-2).
      await expect(announced).toBeFocused();
      await expect(table.getByRole("row", { name: /Jane Perera/ }).getByText("Ready")).toBeVisible();
      await expect(table.getByRole("button", { name: "Create Jane Perera's library now" })).toHaveCount(0);
      expect(requestUrl).toContain(`/bunny/libraries/${JANE}`);
    });

    test("a failed setup can be retried and a backend error is announced", async ({ page }) => {
      await mockTenantSession(page, "TENANT_ADMIN");
      await mockOverview(page, { bunny: BUNNY }, [
        teacherLibraryRow(JANE, "Jane Perera", bunnyLibrary({ status: "FAILED", lastError: "The provider could not be reached." })),
      ]);
      await mockJson(
        page,
        `**/v1/tenant-admin/integrations/bunny/libraries/${JANE}`,
        503,
        apiError("SERVICE_UNAVAILABLE", "Bunny.net could not create the library. Try again later.")
      );

      await page.goto("/tenant-admin/settings/integrations");
      const row = page.getByRole("table", { name: "Teacher video libraries" }).getByRole("row", { name: /Jane Perera/ });
      await expect(row.getByText("Setup failed")).toBeVisible();
      await row.getByRole("button", { name: "Retry creating Jane Perera's library" }).click();

      await expect(page.getByRole("alert").filter({ hasText: "Bunny.net could not create the library" })).toBeVisible();
    });

    test("an interrupted setup is shown as such and can be retried", async ({ page }) => {
      await mockTenantSession(page, "TENANT_ADMIN");
      await mockOverview(page, { bunny: BUNNY }, [
        // The backend offers a create once a PROVISIONING row is past the takeover window (UX-1).
        teacherLibraryRow(JANE, "Jane Perera", bunnyLibrary({ status: "PROVISIONING" }), true),
        teacherLibraryRow("teacher-2", "Kamal Silva", bunnyLibrary({ id: "library-2", status: "PROVISIONING" }), false),
      ]);

      await page.goto("/tenant-admin/settings/integrations");

      const table = page.getByRole("table", { name: "Teacher video libraries" });
      const jane = table.getByRole("row", { name: /Jane Perera/ });
      await expect(jane.getByText("Setup interrupted")).toBeVisible();
      await expect(jane.getByText("The setup was interrupted. Retry to finish it.")).toBeVisible();
      await expect(jane.getByRole("button", { name: "Retry creating Jane Perera's library" })).toBeVisible();
      // A setup still in progress keeps its status and offers no action.
      const kamal = table.getByRole("row", { name: /Kamal Silva/ });
      await expect(kamal.getByText("Being set up")).toBeVisible();
      await expect(kamal.getByRole("button")).toHaveCount(0);
    });

    test("without a connected Bunny.net account there is nothing to create", async ({ page }) => {
      await mockTenantSession(page, "TENANT_ADMIN");
      await mockOverview(page, {}, [teacherLibraryRow(JANE, "Jane Perera", null, false)]);

      await page.goto("/tenant-admin/settings/integrations");

      const section = page.getByRole("region", { name: "Teacher video libraries" });
      await expect(section.getByText("Connect a Bunny.net account above to create teacher libraries.")).toBeVisible();
      await expect(section.getByRole("button", { name: /library now/ })).toHaveCount(0);
    });

    test("a read-only auditor sees libraries but cannot create one", async ({ page }) => {
      await mockTenantSession(page, "READ_ONLY_AUDITOR");
      await mockOverview(page, { bunny: BUNNY }, [teacherLibraryRow(JANE, "Jane Perera")]);

      await page.goto("/tenant-admin/settings/integrations");

      const table = page.getByRole("table", { name: "Teacher video libraries" });
      await expect(table.getByRole("row", { name: /Jane Perera/ }).getByText("Not set up")).toBeVisible();
      await expect(page.getByRole("button", { name: /library now|Retry/ })).toHaveCount(0);
    });

    test("shows empty and error states", async ({ page }) => {
      await mockTenantSession(page, "TENANT_ADMIN");
      await mockOverview(page, { bunny: BUNNY });

      await page.goto("/tenant-admin/settings/integrations");
      await expect(page.getByText("No approved teachers yet")).toBeVisible();
      // The empty state leads somewhere (UX-6).
      await expect(page.getByRole("button", { name: "Manage teachers" })).toBeVisible();

      await mockJson(page, "**/v1/tenant-admin/integrations/bunny/libraries", 500, apiError("INTERNAL_ERROR", "Something went wrong"));
      await page.reload();
      await expect(
        page.getByRole("region", { name: "Teacher video libraries" }).getByText("Something went wrong")
      ).toBeVisible();
    });

    test("a 403 on the libraries list shows the permission-denied state", async ({ page }) => {
      await mockTenantSession(page, "TENANT_ADMIN");
      await mockOverview(page, { bunny: BUNNY });
      await mockJson(page, "**/v1/tenant-admin/integrations/bunny/libraries", 403, apiError("FORBIDDEN", "You do not have permission"));

      await page.goto("/tenant-admin/settings/integrations");

      await expect(
        page.getByRole("region", { name: "Teacher video libraries" }).getByText("You don't have permission to view this.")
      ).toBeVisible();
    });

    test("shows libraries as cards on a phone", async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 800 });
      await mockTenantSession(page, "TENANT_ADMIN");
      await mockOverview(page, { bunny: BUNNY }, [teacherLibraryRow(JANE, "Jane Perera")]);

      await page.goto("/tenant-admin/settings/integrations");

      const section = page.getByRole("region", { name: "Teacher video libraries" });
      await expect(section.getByText("Jane Perera", { exact: true }).filter({ visible: true })).toBeVisible();
      await expect(section.getByRole("table")).toBeHidden();
      await expect(
        section.getByRole("button", { name: "Create Jane Perera's library now" }).filter({ visible: true })
      ).toBeVisible();
    });
  });
});
