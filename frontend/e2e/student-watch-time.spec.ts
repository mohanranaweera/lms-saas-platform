import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * VIDEO Phase 12 - Tenant Admin student "Watch time" tab: used/allowed time
 * per video and "Add time" (an audited, append-only grant with a reason).
 * Backend mocked; the backend enforces every rule independently.
 */

const STUDENT_PROFILE_ID = "11111111-1111-1111-1111-111111111111";
const VIDEO_ID = "22222222-2222-2222-2222-222222222222";

function exhaustedVideo() {
  return {
    videoAssetId: VIDEO_ID,
    title: "Week 1 lecture",
    limit: "LIMITED",
    usedSeconds: 600,
    baseSeconds: 600,
    grantedSeconds: 0,
    allowedSeconds: 600,
    remainingSeconds: 0,
    exhausted: true,
    lastWatchedAt: "2026-10-09T08:00:00Z",
    grants: [],
  };
}

function extendedVideo() {
  return {
    ...exhaustedVideo(),
    grantedSeconds: 1800,
    allowedSeconds: 2400,
    remainingSeconds: 1800,
    exhausted: false,
    grants: [
      {
        id: "33333333-3333-3333-3333-333333333333",
        extraSeconds: 1800,
        reason: "Revision before the final exam",
        grantedBy: "44444444-4444-4444-4444-444444444444",
        grantedAt: "2026-10-09T09:00:00Z",
      },
    ],
  };
}

async function gotoWatchTimeTab(page: Page, role: string, videos: () => unknown[]) {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  await mockJson(page, "**/v1/notifications/unread-count", 200, apiSuccess({ unreadCount: 0 }));
  await mockJson(
    page,
    `**/v1/students/${STUDENT_PROFILE_ID}`,
    200,
    apiSuccess({
      id: STUDENT_PROFILE_ID,
      name: "Ada Lovelace",
      email: "ada@example-institute.test",
      roleCode: "STUDENT",
      status: "ACTIVE",
    })
  );
  await page.route(`**/v1/students/${STUDENT_PROFILE_ID}/watch-time`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(videos())) })
  );
  await page.goto(`/tenant-admin/students/${STUDENT_PROFILE_ID}?tab=watch-time`);
  await expect(page.getByRole("heading", { name: "Ada Lovelace" })).toBeVisible();
}

test.describe("student watch time (Tenant Admin)", () => {
  test("a used-up video shows it's blocked, and adding time sends minutes and a reason", async ({ page }) => {
    let granted = false;
    const bodies: unknown[] = [];
    await page.route(`**/v1/students/${STUDENT_PROFILE_ID}/watch-time/${VIDEO_ID}/grants`, (route) => {
      bodies.push(route.request().postDataJSON());
      granted = true;
      return route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(apiSuccess(extendedVideo())) });
    });
    await gotoWatchTimeTab(page, "TENANT_ADMIN", () => [granted ? extendedVideo() : exhaustedVideo()]);

    const row = page.getByRole("list", { name: "Watch time by video" }).getByRole("listitem").first();
    await expect(row).toContainText("Week 1 lecture");
    await expect(row).toContainText("10 min");
    await expect(row).toContainText("Used up - playback blocked");

    await row.getByRole("button", { name: "Add watch time to Week 1 lecture" }).click();
    const dialog = page.getByRole("alertdialog");
    // Both fields are required.
    await dialog.getByRole("button", { name: "Add time" }).click();
    await expect(dialog.getByText("Enter the extra time in minutes.")).toBeVisible();
    await expect(dialog.getByText("A reason is required.")).toBeVisible();
    expect(bodies).toEqual([]);

    await dialog.getByLabel("Extra time (minutes)").fill("30");
    await dialog.getByLabel(/Reason/).fill("Revision before the final exam");
    await dialog.getByRole("button", { name: "Add time" }).click();

    await expect.poll(() => bodies).toEqual([{ extraMinutes: 30, reason: "Revision before the final exam" }]);
    await expect(page.getByRole("status").filter({ hasText: "Added 30 min" })).toBeVisible();
    await expect(row).not.toContainText("Used up - playback blocked");
    await expect(row).toContainText("40 min (limit 10 min + 30 min added)");
    await row.getByText("Extra time added (1)").click();
    await expect(row).toContainText("Revision before the final exam");
  });

  test("a refused grant shows the backend's message and keeps the dialog open", async ({ page }) => {
    await page.route(`**/v1/students/${STUDENT_PROFILE_ID}/watch-time/${VIDEO_ID}/grants`, (route) =>
      route.fulfill({
        status: 409,
        contentType: "application/json",
        body: JSON.stringify(apiError("CONFLICT", "No watch-time limit applies to this video, so there is no time to add.")),
      })
    );
    await gotoWatchTimeTab(page, "TENANT_ADMIN", () => [exhaustedVideo()]);

    await page.getByRole("button", { name: "Add watch time to Week 1 lecture" }).click();
    const dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Extra time (minutes)").fill("5");
    await dialog.getByLabel(/Reason/).fill("Test");
    await dialog.getByRole("button", { name: "Add time" }).click();
    await expect(dialog.getByRole("alert").first()).toContainText("No watch-time limit applies");
  });

  test("read-only roles see watch time but cannot add time", async ({ page }) => {
    await gotoWatchTimeTab(page, "STUDENT_SUPPORT", () => [exhaustedVideo()]);
    await expect(page.getByText("Week 1 lecture")).toBeVisible();
    await expect(page.getByRole("button", { name: /Add watch time/ })).toHaveCount(0);
  });

  test("a student who hasn't watched anything gets a clear empty state", async ({ page }) => {
    await gotoWatchTimeTab(page, "TENANT_ADMIN", () => []);
    await expect(page.getByText("This student hasn't watched any videos yet.")).toBeVisible();
  });

  test("staff can find a video by name or show only used-up videos", async ({ page }) => {
    const other = { ...exhaustedVideo(), videoAssetId: "55555555-5555-5555-5555-555555555555", title: "Week 2 lab", exhausted: false, remainingSeconds: 300 };
    await gotoWatchTimeTab(page, "TENANT_ADMIN", () => [exhaustedVideo(), other]);
    const list = page.getByRole("list", { name: "Watch time by video" });
    await expect(list.getByRole("listitem")).toHaveCount(2);

    await page.getByLabel("Search videos").fill("lab");
    await expect(list.getByRole("listitem")).toHaveCount(1);
    await expect(list).toContainText("Week 2 lab");
    await expect(page.getByText("Showing 1 of 2 videos")).toBeVisible();

    await page.getByLabel("Search videos").fill("");
    await page.getByLabel("Only used up (1)").check();
    await expect(list.getByRole("listitem")).toHaveCount(1);
    await expect(list).toContainText("Week 1 lecture");

    await page.getByLabel("Search videos").fill("lab");
    await expect(page.getByText("No videos match your search or filter.")).toBeVisible();
    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect(list.getByRole("listitem")).toHaveCount(2);
  });

  test("videos without a limit say so and offer no extra time", async ({ page }) => {
    await gotoWatchTimeTab(page, "TENANT_ADMIN", () => [
      { ...exhaustedVideo(), limit: "NONE", baseSeconds: null, allowedSeconds: null, remainingSeconds: null, exhausted: false },
    ]);
    await expect(page.getByText("No limit")).toBeVisible();
    await expect(page.getByRole("button", { name: /Add watch time/ })).toHaveCount(0);
  });
});
