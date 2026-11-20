import { test, expect } from "@playwright/test";
import { apiError, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";
import { mockTenantSession, TEACHER_ID } from "./fixtures/class-session-mocks";
import { connection, mockTeacherIntegrations, teacherIntegrations } from "./fixtures/integrations-mocks";

/**
 * MEDIA-INT-01 — Teacher → Settings → Integrations (frontend, mock-only).
 *
 * The OAuth state, token storage, ownership (another teacher's / tenant's
 * connection is 404) and refresh are proven by the backend's
 * `TeacherMediaConnectionIntegrationTest`. These tests prove the UI reflects
 * backend state, sends the browser only to an https consent page, never
 * shows credentials, and warns before disconnecting Zoom.
 */

const ZOOM = connection({ id: "conn-zoom", provider: "ZOOM", accountLabel: "jane.zoom@example.test" });

test.describe("Teacher integrations", () => {
  test("shows each provider's own state from the backend", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    await mockTeacherIntegrations(
      page,
      teacherIntegrations({ ZOOM: { connection: ZOOM }, VIMEO: { enabledByTenant: false } })
    );

    await page.goto("/teacher/settings/integrations");

    const zoom = page.getByRole("region", { name: "Zoom" });
    await expect(zoom.getByText("Connected", { exact: true })).toBeVisible();
    await expect(zoom.getByText("jane.zoom@example.test")).toBeVisible();
    await expect(zoom.getByRole("button", { name: "Reconnect Zoom" })).toBeVisible();

    // YouTube is the institute's channel now (ADR-018 Amendment 4) - not a teacher connection.
    await expect(page.getByRole("region", { name: "YouTube" })).toHaveCount(0);

    const vimeo = page.getByRole("region", { name: "Vimeo" });
    await expect(vimeo.getByText("Not enabled by your institute")).toBeVisible();
    await expect(vimeo.getByRole("button", { name: "Connect Vimeo" })).toHaveCount(0);
  });

  test("Connect sends the browser to the provider's https consent page", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    await mockTeacherIntegrations(page, teacherIntegrations());
    await mockJson(
      page,
      "**/v1/teacher/integrations/zoom/connect",
      200,
      apiSuccess({ authorizationUrl: "https://zoom.us/oauth/authorize?response_type=code&state=abc" })
    );
    await page.route("https://zoom.us/**", (route) =>
      route.fulfill({ status: 200, contentType: "text/html", body: "<h1>Zoom consent</h1>" })
    );

    await page.goto("/teacher/settings/integrations");
    await page.getByRole("button", { name: "Connect Zoom" }).click();

    await expect(page).toHaveURL(/^https:\/\/zoom\.us\/oauth\/authorize/);
  });

  test("never navigates to a non-https consent URL", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    await mockTeacherIntegrations(page, teacherIntegrations());
    await mockJson(
      page,
      "**/v1/teacher/integrations/zoom/connect",
      200,
      apiSuccess({ authorizationUrl: "javascript:alert(1)" })
    );

    await page.goto("/teacher/settings/integrations");
    await page.getByRole("button", { name: "Connect Zoom" }).click();

    await expect(page.getByText("We couldn't open Zoom's sign-in page.", { exact: false })).toBeVisible();
    await expect(page).toHaveURL(/\/teacher\/settings\/integrations$/);
  });

  test("confirms a finished connection exactly once with the teacher's own session, then cleans the URL", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    await mockTeacherIntegrations(page, teacherIntegrations({ ZOOM: { connection: ZOOM } }));
    const confirmCalls: string[] = [];
    await page.route("**/v1/teacher/integrations/pending/*/confirm", async (route) => {
      confirmCalls.push(route.request().url());
      await fulfillJson(route, 200, apiSuccess(ZOOM));
    });

    await page.goto("/teacher/settings/integrations?pending=pending-abc&provider=zoom");

    await expect(page.getByText("Zoom is now connected.")).toBeVisible();
    await expect(page).toHaveURL(/\/teacher\/settings\/integrations$/);
    expect(confirmCalls).toHaveLength(1);
    expect(confirmCalls[0]).toContain("/pending/pending-abc/confirm");
  });

  test("an expired or someone else's connection request is explained, not saved", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    await mockTeacherIntegrations(page, teacherIntegrations());
    await mockJson(
      page,
      "**/v1/teacher/integrations/pending/*/confirm",
      404,
      apiError("NOT_FOUND", "This connection request has expired or was already used. Connect again.")
    );

    await page.goto("/teacher/settings/integrations?pending=stale-id&provider=zoom");

    await expect(page.getByText("This connection request has expired or was already used.", { exact: false })).toBeVisible();
    await expect(page.getByRole("region", { name: "Zoom" }).getByText("Not connected")).toBeVisible();
  });

  test("a denied consent is explained", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    await mockTeacherIntegrations(page, teacherIntegrations());

    await page.goto("/teacher/settings/integrations?error=denied&provider=vimeo");

    await expect(page.getByText("Vimeo was not connected because access wasn't allowed.")).toBeVisible();
  });

  test("disconnecting Zoom warns about upcoming classes first (keyboard only)", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    await mockTeacherIntegrations(page, teacherIntegrations({ ZOOM: { connection: ZOOM } }));
    await mockJson(
      page,
      `**/v1/teacher/integrations/connections/${ZOOM.id}/impact`,
      200,
      apiSuccess({ upcomingSessionCount: 2 })
    );
    let disconnected = false;
    await page.route(`**/v1/teacher/integrations/connections/${ZOOM.id}/disconnect`, async (route) => {
      disconnected = true;
      await fulfillJson(
        route,
        200,
        apiSuccess({ ...ZOOM, status: "DISCONNECTED", disconnectedBy: "OWNER", disconnectedAt: "2026-09-28T09:00:00Z" })
      );
    });

    await page.goto("/teacher/settings/integrations");
    await page.getByRole("region", { name: "Zoom" }).getByRole("button", { name: "Disconnect" }).focus();
    await page.keyboard.press("Enter");

    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByText("2 upcoming live classes use", { exact: false })).toBeVisible();
    await dialog.getByRole("button", { name: "Disconnect Zoom" }).click();
    await expect(dialog).toHaveCount(0);
    expect(disconnected).toBe(true);
  });

  test("a connection that needs reconnecting is flagged on every teacher page", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    await mockTeacherIntegrations(
      page,
      teacherIntegrations({
        ZOOM: { connection: { ...ZOOM, status: "NEEDS_RECONNECT", lastError: "Access was revoked" } },
      })
    );

    await page.goto("/teacher/settings/integrations");

    await expect(page.getByText("Your Zoom account needs to be reconnected.", { exact: false })).toBeVisible();
    await expect(page.getByRole("region", { name: "Zoom" }).getByText("Needs reconnect")).toBeVisible();
    await expect(page.getByText("Access was revoked", { exact: false })).toBeVisible();
  });

  test("a free Zoom account is flagged as unable to host", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    await mockTeacherIntegrations(
      page,
      teacherIntegrations({ ZOOM: { connection: { ...ZOOM, accountType: "BASIC" } } })
    );

    await page.goto("/teacher/settings/integrations");

    await expect(page.getByText("This is a free (Basic) Zoom account.", { exact: false })).toBeVisible();
  });

  test("renders the permission-denied state on a backend 403", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/teacher/integrations", 403, apiError("FORBIDDEN", "Only teachers can manage their own integrations"));

    await page.goto("/teacher/settings/integrations");

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
  });

  test.describe("Your video library (MEDIA-INT-01A)", () => {
    const LIBRARY_NAME = "Demo Institute - Jane Perera [a1b2c3d4e5f6]";

    test("says when the institute hasn't connected Bunny.net", async ({ page }) => {
      await mockTenantSession(page, "TEACHER", TEACHER_ID);
      await mockTeacherIntegrations(page, teacherIntegrations());

      await page.goto("/teacher/settings/integrations");

      const card = page.getByRole("region", { name: "Your video library" });
      await expect(card.getByText("Not set up", { exact: true })).toBeVisible();
      await expect(card.getByText("Your institute hasn't connected Bunny.net yet.")).toBeVisible();
    });

    test("explains the library is created on the first upload", async ({ page }) => {
      await mockTenantSession(page, "TEACHER", TEACHER_ID);
      await mockTeacherIntegrations(page, teacherIntegrations({}, { bunnyConnected: true }));

      await page.goto("/teacher/settings/integrations");

      await expect(
        page.getByRole("region", { name: "Your video library" }).getByText("created automatically when you first upload")
      ).toBeVisible();
    });

    test("shows the teacher's own ready library, read-only", async ({ page }) => {
      await mockTenantSession(page, "TEACHER", TEACHER_ID);
      await mockTeacherIntegrations(
        page,
        teacherIntegrations({}, { bunnyConnected: true, bunnyLibrary: { status: "ACTIVE", libraryName: LIBRARY_NAME } })
      );

      await page.goto("/teacher/settings/integrations");

      const card = page.getByRole("region", { name: "Your video library" });
      await expect(card.getByText("Ready", { exact: true })).toBeVisible();
      await expect(card.getByText(LIBRARY_NAME)).toBeVisible();
      await expect(card.getByRole("button")).toHaveCount(0);
    });

    test("an unavailable library tells the teacher to contact the institute", async ({ page }) => {
      await mockTenantSession(page, "TEACHER", TEACHER_ID);
      await mockTeacherIntegrations(
        page,
        teacherIntegrations({}, { bunnyConnected: true, bunnyLibrary: { status: "UNAVAILABLE", libraryName: LIBRARY_NAME } })
      );

      await page.goto("/teacher/settings/integrations");

      const card = page.getByRole("region", { name: "Your video library" });
      await expect(card.getByText("Unavailable", { exact: true })).toBeVisible();
      await expect(card.getByText("Contact your institute.", { exact: false })).toBeVisible();
    });
  });
});
