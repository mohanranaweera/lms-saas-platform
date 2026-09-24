import { test, expect } from "@playwright/test";
import { makeClassSession, mockClassSessionsApi, mockTenantSession, TEACHER_ID } from "./fixtures/class-session-mocks";

/**
 * Wave 4 (PAR-19-02) status-transition lifecycle
 * (`SCHEDULED -> LIVE -> COMPLETED` / `SCHEDULED|LIVE -> CANCELLED`) from the
 * Teacher detail page, and that the visible status/provider-status badges
 * update in place after each transition (no full reload needed — proves the
 * mutation's cache invalidation actually refreshes the detail read).
 */

test.describe("Live class status transitions", () => {
  test("SCHEDULED -> start -> LIVE -> complete -> COMPLETED, with the recording affordance appearing only once completed", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    const session = makeClassSession({ id: "session-1", status: "SCHEDULED" });
    await mockClassSessionsApi(page, { initialSessions: [session] });

    await page.goto(`/teacher/live-classes/${session.id}`);

    await expect(page.getByText("Scheduled", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Watch recording" })).toHaveCount(0);

    await page.getByRole("button", { name: "Start class" }).click();
    await expect(page.getByText("Live", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Start class" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Join as host" })).toBeVisible();

    await page.getByRole("button", { name: "Mark completed" }).click();
    await expect(page.getByText("Completed", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Mark completed" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Cancel class" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Watch recording" })).toBeVisible();
  });

  test("cancelling a SCHEDULED session removes every status-transition action", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    const session = makeClassSession({ id: "session-1", status: "SCHEDULED" });
    await mockClassSessionsApi(page, { initialSessions: [session] });

    await page.goto(`/teacher/live-classes/${session.id}`);
    await page.getByRole("button", { name: "Cancel class" }).click();

    await expect(page.getByText("Cancelled", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Start class" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Cancel class" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Edit" })).toHaveCount(0);
  });

  test("cancelling a LIVE session is legal; cancelling an already-COMPLETED session is not offered", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    const liveSession = makeClassSession({ id: "session-live", status: "LIVE" });
    const completedSession = makeClassSession({ id: "session-completed", status: "COMPLETED" });
    await mockClassSessionsApi(page, { initialSessions: [liveSession, completedSession] });

    await page.goto(`/teacher/live-classes/${liveSession.id}`);
    await expect(page.getByRole("button", { name: "Cancel class" })).toBeVisible();
    await page.getByRole("button", { name: "Cancel class" }).click();
    await expect(page.getByText("Cancelled", { exact: true })).toBeVisible();

    await page.goto(`/teacher/live-classes/${completedSession.id}`);
    await expect(page.getByRole("button", { name: "Cancel class" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Start class" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Mark completed" })).toHaveCount(0);
  });

  test("the list page's status badges reflect each session's current state, and a FAILED provider status is visually distinct", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    const scheduled = makeClassSession({ id: "s1", title: "Scheduled Session", status: "SCHEDULED" });
    const live = makeClassSession({ id: "s2", title: "Live Session", status: "LIVE" });
    const stuck = makeClassSession({
      id: "s3",
      title: "Stuck Session",
      status: "SCHEDULED",
      providerStatus: "FAILED",
      providerFailureReason: "Timed out",
    });
    await mockClassSessionsApi(page, { initialSessions: [scheduled, live, stuck] });

    await page.goto("/teacher/live-classes");

    const scheduledRow = page.getByRole("listitem").filter({ hasText: "Scheduled Session" });
    await expect(scheduledRow.getByText("Scheduled", { exact: true })).toBeVisible();

    const liveRow = page.getByRole("listitem").filter({ hasText: "Live Session" });
    await expect(liveRow.getByText("Live", { exact: true })).toBeVisible();

    const stuckRow = page.getByRole("listitem").filter({ hasText: "Stuck Session" });
    await expect(stuckRow.getByText("Provisioning failed")).toBeVisible();
  });
});
