import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";
import {
  COURSE_ID,
  makeClassSession,
  mockClassSessionsApi,
  mockTenantSession,
  TEACHER_ID,
} from "./fixtures/class-session-mocks";
import { connection, mockTeacherIntegrations, teacherIntegrations } from "./fixtures/integrations-mocks";

/**
 * MEDIA-INT-01 — live classes hosted on the teacher's own Zoom account
 * (frontend, mock-only). The real host checks, registrant links and
 * "Recreate meeting" are proven by the backend's
 * `ZoomLiveClassIntegrationTest`; these prove the teacher is told what to fix
 * and how.
 */

function futureDatetimeLocal(minutesFromNow: number): string {
  const date = new Date(Date.now() + minutesFromNow * 60_000);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

async function fillScheduleForm(page: Page): Promise<void> {
  await page.getByLabel("Course", { exact: true }).click();
  await page.getByRole("option", { name: "Intro to Biology" }).click();
  await page.getByLabel("Title", { exact: true }).fill("Zoom class");
  await page.getByLabel("Start", { exact: true }).fill(futureDatetimeLocal(60));
  await page.getByLabel("End", { exact: true }).fill(futureDatetimeLocal(120));
}

test.describe("Live classes on the teacher's Zoom account", () => {
  test("the schedule form asks the teacher to connect Zoom first", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    await mockClassSessionsApi(page);
    await mockTeacherIntegrations(page, teacherIntegrations());

    await page.goto("/teacher/live-classes/new");

    await expect(page.getByText("Connect your Zoom account before scheduling.", { exact: false })).toBeVisible();
    await expect(page.getByRole("link", { name: "Open Settings → Integrations" })).toHaveAttribute(
      "href",
      "/teacher/settings/integrations"
    );
  });

  test("a refused schedule explains the Zoom problem and links to the fix", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    await mockClassSessionsApi(page);
    await mockJson(page, `**/v1/courses/${COURSE_ID}/modules`, 200, apiSuccess([]));
    await mockTeacherIntegrations(
      page,
      teacherIntegrations({ ZOOM: { connection: connection({ id: "z", provider: "ZOOM" }) } })
    );
    await page.route("**/v1/class-sessions", async (route) => {
      if (route.request().method() !== "POST") return route.fallback();
      await fulfillJson(
        route,
        409,
        apiError("PROVIDER_LICENSE_REQUIRED", "A licensed (paid) Zoom account is required to host live classes.")
      );
    });

    await page.goto("/teacher/live-classes/new");
    await fillScheduleForm(page);
    await page.getByRole("button", { name: "Schedule class" }).click();

    await expect(page.getByText("A licensed (paid) Zoom account is required to host live classes.")).toBeVisible();
    await expect(page.getByRole("link", { name: "Open Settings → Integrations" })).toBeVisible();
    await expect(page).toHaveURL(/\/teacher\/live-classes\/new$/);
  });

  test("a class whose Zoom host was disconnected can be recreated", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    const session = makeClassSession({ id: "session-zoom", hostConnectionStatus: "DISCONNECTED" });
    await mockClassSessionsApi(page, { initialSessions: [session] });
    let recreated = false;
    await page.route(`**/v1/class-sessions/${session.id}/retry-provisioning`, async (route) => {
      recreated = true;
      session.hostConnectionStatus = "ACTIVE";
      await fulfillJson(route, 200, apiSuccess(session));
    });

    await page.goto(`/teacher/live-classes/${session.id}`);

    await expect(page.getByText("The Zoom account hosting this class is", { exact: false })).toBeVisible();
    await page.getByRole("button", { name: "Recreate meeting" }).click();

    await expect(page.getByText("The Zoom account hosting this class is", { exact: false })).toHaveCount(0);
    expect(recreated).toBe(true);
  });

  test("recreating without a connected Zoom account links to the fix", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    const session = makeClassSession({ id: "session-zoom-2", hostConnectionStatus: "DISCONNECTED" });
    await mockClassSessionsApi(page, { initialSessions: [session] });
    await mockJson(
      page,
      `**/v1/class-sessions/${session.id}/retry-provisioning`,
      409,
      apiError("PROVIDER_NOT_CONNECTED", "The teacher has not connected a Zoom account. Connect it in Settings > Integrations.")
    );

    await page.goto(`/teacher/live-classes/${session.id}`);
    await page.getByRole("button", { name: "Recreate meeting" }).click();

    await expect(page.getByText("The teacher has not connected a Zoom account.", { exact: false })).toBeVisible();
    await expect(page.getByRole("link", { name: "Open Settings → Integrations" })).toBeVisible();
  });
});
