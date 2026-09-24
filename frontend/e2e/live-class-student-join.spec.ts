import { test, expect } from "@playwright/test";
import { apiSuccess } from "./fixtures/auth-mocks";
import { mockJson } from "./fixtures/auth-mocks";
import {
  getOpenedUrls,
  makeClassSession,
  mockClassSessionsApi,
  mockTenantSession,
  stubWindowOpen,
} from "./fixtures/class-session-mocks";

/**
 * Wave 4 (PAR-19-04) Student "Live Classes" join flow. `GET
 * /v1/class-sessions` is already entitlement-filtered server-side to only
 * this Student's own currently-ACTIVE enrollments — a not-enrolled/expired
 * student never receives a session it shouldn't in the first place (proven
 * here via the "no sessions returned" scenario, which must render the
 * ordinary empty state, never an error), and a Student never sees a Join
 * control it can actually use before `status === "LIVE"` AND
 * `providerStatus === "PROVISIONED"`.
 */

async function mockStudentCourses(page: import("@playwright/test").Page): Promise<void> {
  await mockJson(
    page,
    "**/v1/enrollments/my/courses",
    200,
    apiSuccess([{ id: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", name: "Intro to Biology", slug: "intro-to-biology", category: "Science" }])
  );
}

test.describe("Student Live Classes — join", () => {
  test("a LIVE, PROVISIONED session is joinable — clicking Join opens a fresh joinUrl in a new tab", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    await mockStudentCourses(page);
    const session = makeClassSession({ id: "session-1", status: "LIVE", providerStatus: "PROVISIONED" });
    await mockClassSessionsApi(page, { initialSessions: [session] });
    await stubWindowOpen(page);

    await page.goto("/student/live-classes");

    const joinButton = page.getByRole("button", { name: "Join" });
    await expect(joinButton).toBeEnabled();
    await joinButton.click();

    await expect.poll(() => getOpenedUrls(page)).toHaveLength(1);
    const opened = (await getOpenedUrls(page))[0] as [string, string, string];
    expect(opened[0]).toBe(`https://live-class-provider.test/join/${session.id}`);
    expect(opened[1]).toBe("_blank");
    expect(opened[2]).toContain("noopener");
  });

  test("a SCHEDULED (not yet live) session shows a disabled Join with 'Not started yet', not an error", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    await mockStudentCourses(page);
    const session = makeClassSession({ id: "session-1", status: "SCHEDULED" });
    await mockClassSessionsApi(page, { initialSessions: [session] });

    await page.goto("/student/live-classes");

    const joinButton = page.getByRole("button", { name: "Join" });
    await expect(joinButton).toBeDisabled();
    await expect(page.getByText("Not started yet")).toBeVisible();
    // Scoped to `main` — Next.js's own always-present route-announcer div
    // carries `role="alert"` outside the app's rendered content, so a bare
    // page-wide `getByRole("alert")` count is never a reliable "no error"
    // check (mirrors `attendance.spec.ts`'s identical `getByRole("main")`
    // scoping for this same assertion shape).
    await expect(page.getByRole("main").getByRole("alert")).toHaveCount(0);
  });

  test("a student with no ACTIVE-enrollment sessions sees the ordinary 'no upcoming' empty state, never an error or a hidden session", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    await mockStudentCourses(page);
    // Server-side entitlement filtering already excludes any session this
    // student isn't actively enrolled for (or whose access has expired) —
    // simulated here simply as an empty list, since that is exactly what the
    // backend would return for that student regardless of what sessions
    // exist for other students/tenants.
    await mockClassSessionsApi(page, { initialSessions: [] });

    await page.goto("/student/live-classes");

    await expect(page.getByText("No upcoming live classes")).toBeVisible();
    await expect(page.getByRole("main").getByRole("alert")).toHaveCount(0);

    await page.getByRole("tab", { name: "Past" }).click();
    await expect(page.getByText("No past live classes")).toBeVisible();
  });

  test("a COMPLETED session moves to the Past tab and a CANCELLED session shows no action", async ({ page }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    await mockStudentCourses(page);
    const completed = makeClassSession({ id: "session-done", title: "Past Class", status: "COMPLETED" });
    const cancelled = makeClassSession({ id: "session-cancelled", title: "Cancelled Class", status: "CANCELLED" });
    await mockClassSessionsApi(page, { initialSessions: [completed, cancelled] });

    await page.goto("/student/live-classes");
    await expect(page.getByText("No upcoming live classes")).toBeVisible();

    await page.getByRole("tab", { name: "Past" }).click();
    const completedRow = page.getByRole("listitem").filter({ hasText: "Past Class" });
    await expect(completedRow.getByRole("button", { name: "Watch recording" })).toBeVisible();

    const cancelledRow = page.getByRole("listitem").filter({ hasText: "Cancelled Class" });
    await expect(cancelledRow.getByRole("button")).toHaveCount(0);
  });
});
