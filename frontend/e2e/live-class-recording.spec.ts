import { test, expect } from "@playwright/test";
import { apiSuccess, mockJson } from "./fixtures/auth-mocks";
import {
  getOpenedUrls,
  makeClassSession,
  mockClassSessionsApi,
  mockTenantSession,
  stubWindowOpen,
  TEACHER_ID,
} from "./fixtures/class-session-mocks";

/**
 * Wave 4 (PAR-19-05, publishing side) recording affordance: a `COMPLETED`
 * session offers "Watch recording", which mints a fresh short-lived playback
 * link on click (never cached/reused) and opens it in a new tab — same
 * short-lived-URL handling as join.
 */

test.describe("Live class recording affordance", () => {
  test("Teacher detail page: a COMPLETED session's Watch recording opens a fresh playbackUrl", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    const session = makeClassSession({ id: "session-1", status: "COMPLETED" });
    await mockClassSessionsApi(page, { initialSessions: [session] });
    await stubWindowOpen(page);

    await page.goto(`/teacher/live-classes/${session.id}`);

    await expect(page.getByRole("heading", { name: "Recording" })).toBeVisible();
    await page.getByRole("button", { name: "Watch recording" }).click();

    await expect.poll(() => getOpenedUrls(page)).toHaveLength(1);
    const opened = (await getOpenedUrls(page))[0] as [string, string];
    expect(opened[0]).toBe(`https://live-class-provider.test/playback/${session.id}`);
    expect(opened[1]).toBe("_blank");
  });

  test("no recording affordance for a session that has not completed yet", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    const session = makeClassSession({ id: "session-1", status: "LIVE" });
    await mockClassSessionsApi(page, { initialSessions: [session] });

    await page.goto(`/teacher/live-classes/${session.id}`);

    await expect(page.getByRole("heading", { name: "Recording" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Watch recording" })).toHaveCount(0);
  });

  test("Student Past tab: Watch recording opens a fresh playbackUrl for a COMPLETED session", async ({ page }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    await mockJson(
      page,
      "**/v1/enrollments/my/courses",
      200,
      apiSuccess([{ id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", name: "Intro to Biology", slug: "intro-to-biology", category: "Science" }])
    );
    const session = makeClassSession({ id: "session-1", status: "COMPLETED" });
    await mockClassSessionsApi(page, { initialSessions: [session] });
    await stubWindowOpen(page);

    await page.goto("/student/live-classes");
    await page.getByRole("tab", { name: "Past" }).click();
    await page.getByRole("button", { name: "Watch recording" }).click();

    await expect.poll(() => getOpenedUrls(page)).toHaveLength(1);
    const opened = (await getOpenedUrls(page))[0] as [string, string];
    expect(opened[0]).toBe(`https://live-class-provider.test/playback/${session.id}`);
  });
});
