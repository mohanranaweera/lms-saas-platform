import { test, expect, type Page } from "@playwright/test";
import {
  COURSE_ID,
  getOpenedUrls,
  makeClassSession,
  mockClassSessionsApi,
  mockTenantSession,
  stubWindowOpen,
} from "./fixtures/class-session-mocks";
import {
  contentWith,
  launchAllowed,
  lessonNode,
  liveClassNode,
  mockKlassStudentCourse,
} from "./fixtures/klass/student-course-mocks";

/**
 * Wave 4 (PAR-19-04) Student live classes join flow, shown inside the course
 * page (`student/courses/[courseId]`).
 *
 * KLASS update (ADR-028, plan §4.3, D04/D14): the course page's "Live Classes"
 * tab now lists the course's live LESSONS from `GET /courses/{id}/live-classes`
 * (the same server evaluator as the content tree, only for this student).
 * Joining launches the lesson (`POST .../lessons/{id}/launch`, the server
 * re-checks access) and then calls the unchanged, server-guarded
 * `POST /class-sessions/{id}/join`. The old per-session list with
 * Upcoming/Past tabs was removed; its intents map to:
 * - joinable only when the server allows AND the session is live,
 * - a not-yet-started class offers no Join (never an error),
 * - nothing the student isn't entitled to is listed (empty state, no error),
 * - a completed class offers its recording through the lesson launch.
 */

async function openLiveTab(page: Page) {
  await page.goto(`/student/courses/${COURSE_ID}`);
  await page.getByTestId("course-tab-live").click();
  await expect(page.getByTestId("live-classes-panel")).toBeVisible();
}

test.describe("Student Live Classes — join", () => {
  test("a LIVE, PROVISIONED session is joinable — clicking Join opens a fresh joinUrl in a new tab", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    const session = makeClassSession({ id: "session-1", status: "LIVE", providerStatus: "PROVISIONED" });
    await mockClassSessionsApi(page, { initialSessions: [session] });
    const live = liveClassNode({ lessonId: "lesson-live", title: "Live Q&A Session" });
    const mocks = await mockKlassStudentCourse(page, {
      liveClasses: [live],
      launches: {
        [live.lessonId]: {
          status: 200,
          body: { success: true, data: launchAllowed([{ kind: "CLASS_SESSION", id: session.id, title: session.title, type: "LIVE" }]) },
        },
      },
    });
    await stubWindowOpen(page);

    await openLiveTab(page);
    await page.getByRole("button", { name: "Join Live Q&A Session" }).click();
    // The server launch is always consulted first.
    expect(mocks.launchedLessonIds).toEqual([live.lessonId]);

    const dialog = page.getByTestId("lesson-resources-dialog");
    const joinButton = dialog.getByRole("button", { name: "Join" });
    await expect(joinButton).toBeEnabled();
    await joinButton.click();

    await expect.poll(() => getOpenedUrls(page)).toHaveLength(1);
    const opened = (await getOpenedUrls(page))[0] as [string, string, string];
    expect(opened[0]).toBe(`https://live-class-provider.test/join/${session.id}`);
    expect(opened[1]).toBe("_blank");
    expect(opened[2]).toContain("noopener");
  });

  test("a not-yet-started live class shows 'Starts soon' with no Join, not an error", async ({ page }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    await mockClassSessionsApi(page, { initialSessions: [] });
    const upcoming = liveClassNode({
      lessonId: "lesson-upcoming",
      title: "Tomorrow's class",
      startsAt: new Date(Date.now() + 24 * 3_600_000).toISOString(),
      endsAt: new Date(Date.now() + 26 * 3_600_000).toISOString(),
      access: { status: "NOT_STARTED", availability: "NOT_STARTED", payment: "COVERED", requiredPurchase: null },
    });
    await mockKlassStudentCourse(page, { liveClasses: [upcoming] });

    await openLiveTab(page);

    const row = page.getByTestId("live-class-row").filter({ hasText: "Tomorrow's class" });
    await expect(row.getByTestId("lesson-status")).toHaveText("Starts soon");
    await expect(row.getByRole("button", { name: /Join/ })).toHaveCount(0);
    // Scoped to `main` — Next.js's own always-present route-announcer div
    // carries `role="alert"` outside the app's rendered content; the page
    // also keeps an always-present EMPTY alert live region (toasts), so only
    // an alert with content counts as an error.
    await expect(page.getByRole("main").getByRole("alert").filter({ hasText: /\S/ })).toHaveCount(0);
  });

  test("the server, not the UI, decides joinability: a session that isn't live yet answers 409 and nothing opens", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    const session = makeClassSession({ id: "session-1", status: "SCHEDULED" });
    await mockClassSessionsApi(page, { initialSessions: [session] });
    const live = liveClassNode({ lessonId: "lesson-live", title: "Live Q&A Session" });
    await mockKlassStudentCourse(page, {
      liveClasses: [live],
      launches: {
        [live.lessonId]: {
          status: 200,
          body: { success: true, data: launchAllowed([{ kind: "CLASS_SESSION", id: session.id, title: session.title, type: "SCHEDULED" }]) },
        },
      },
    });
    await stubWindowOpen(page);

    await openLiveTab(page);
    await page.getByRole("button", { name: "Join Live Q&A Session" }).click();
    await page.getByTestId("lesson-resources-dialog").getByRole("button", { name: "Join" }).click();

    await expect(page.getByTestId("lesson-resources-dialog").getByRole("alert")).toContainText(
      "This class session is not currently live"
    );
    expect(await getOpenedUrls(page)).toHaveLength(0);
  });

  test("a student with no entitled live classes sees the ordinary 'no upcoming' empty state, never an error or a hidden class", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    // Server-side evaluation already scopes the list to this student/tenant -
    // simulated as an empty list, exactly what the backend returns.
    await mockKlassStudentCourse(page, { liveClasses: [] });

    await openLiveTab(page);

    await expect(page.getByTestId("live-classes-empty")).toContainText("No upcoming live classes");
    await expect(page.getByTestId("live-class-row")).toHaveCount(0);
    await expect(page.getByRole("main").getByRole("alert").filter({ hasText: /\S/ })).toHaveCount(0);
  });

  test("a COMPLETED session offers its recording from the lesson launch, and a CANCELLED one offers no action", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    const completed = makeClassSession({ id: "session-done", title: "Past Class", status: "COMPLETED" });
    const cancelled = makeClassSession({ id: "session-cancelled", title: "Cancelled Class", status: "CANCELLED" });
    await mockClassSessionsApi(page, { initialSessions: [completed, cancelled] });
    const pastLesson = lessonNode({ lessonId: "lesson-past", title: "Past Class", resourceType: "ZOOM_MEETING" });
    const cancelledLesson = lessonNode({ lessonId: "lesson-cancelled", title: "Cancelled Class", resourceType: "ZOOM_MEETING" });
    await mockKlassStudentCourse(page, {
      content: contentWith([pastLesson, cancelledLesson]),
      launches: {
        [pastLesson.lessonId]: {
          status: 200,
          body: { success: true, data: launchAllowed([{ kind: "CLASS_SESSION", id: completed.id, title: completed.title, type: "COMPLETED" }]) },
        },
        // The server's launch never lists CANCELLED sessions.
        [cancelledLesson.lessonId]: { status: 200, body: { success: true, data: launchAllowed([]) } },
      },
    });

    await page.goto(`/student/courses/${COURSE_ID}`);
    // Finished classes are not "upcoming".
    await page.getByTestId("course-tab-live").click();
    await expect(page.getByTestId("live-classes-empty")).toBeVisible();
    await page.getByTestId("course-tab-content").click();

    await page.getByTestId("content-period-toggle").click();
    await page.getByTestId("content-module-toggle").click();
    await page.getByTestId("content-lesson").filter({ hasText: "Past Class" }).getByTestId("content-lesson-open").click();
    const dialog = page.getByTestId("lesson-resources-dialog");
    await expect(dialog.getByRole("button", { name: "Watch recording" })).toBeVisible();
    await expect(dialog.getByRole("button", { name: "Join" })).toHaveCount(0);
    await dialog.getByRole("button", { name: "Close" }).first().click();

    await page
      .getByTestId("content-lesson")
      .filter({ hasText: "Cancelled Class" })
      .getByTestId("content-lesson-open")
      .click();
    await expect(page.getByTestId("lesson-resources-dialog")).toHaveCount(0);
    await expect(page.getByRole("button", { name: /Join|Watch recording/ })).toHaveCount(0);
  });
});
