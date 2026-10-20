import { test, expect } from "@playwright/test";
import {
  COURSE_ID,
  getOpenedUrls,
  makeClassSession,
  mockClassSessionsApi,
  mockTenantSession,
  stubWindowOpen,
  TEACHER_ID,
} from "./fixtures/class-session-mocks";
import {
  contentWith,
  launchAllowed,
  lessonNode,
  mockKlassStudentCourse,
} from "./fixtures/klass/student-course-mocks";

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

  // KLASS (ADR-028, plan §4.3): the student course page has no Past tab any
  // more; a finished class's recording is reached by launching its lesson
  // (server-checked) and then "Watch recording", which still mints a fresh
  // playbackUrl from `GET /class-sessions/{id}/recording` on every click.
  test("Student course page: Watch recording (via the lesson launch) opens a fresh playbackUrl for a COMPLETED session", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    const session = makeClassSession({ id: "session-1", status: "COMPLETED" });
    await mockClassSessionsApi(page, { initialSessions: [session] });
    const lesson = lessonNode({ lessonId: "lesson-recorded", title: "Recorded class", resourceType: "ZOOM_MEETING" });
    const mocks = await mockKlassStudentCourse(page, {
      content: contentWith([lesson]),
      launches: {
        [lesson.lessonId]: {
          status: 200,
          body: { success: true, data: launchAllowed([{ kind: "CLASS_SESSION", id: session.id, title: session.title, type: "COMPLETED" }]) },
        },
      },
    });
    let recordingReads = 0;
    page.on("request", (request) => {
      if (request.url().includes(`/class-sessions/${session.id}/recording`)) recordingReads += 1;
    });
    await stubWindowOpen(page);

    await page.goto(`/student/courses/${COURSE_ID}`);
    await page.getByTestId("content-period-toggle").click();
    await page.getByTestId("content-module-toggle").click();
    await page.getByTestId("content-lesson-open").click();
    expect(mocks.launchedLessonIds).toEqual([lesson.lessonId]);
    await page.getByTestId("lesson-resources-dialog").getByRole("button", { name: "Watch recording" }).click();

    await expect.poll(() => getOpenedUrls(page)).toHaveLength(1);
    const opened = (await getOpenedUrls(page))[0] as [string, string];
    expect(opened[0]).toBe(`https://live-class-provider.test/playback/${session.id}`);
    expect(opened[1]).toBe("_blank");

    // Never cached/reused: a second click asks the server again.
    await page.getByTestId("lesson-resources-dialog").getByRole("button", { name: "Watch recording" }).click();
    await expect.poll(() => getOpenedUrls(page)).toHaveLength(2);
    expect(recordingReads).toBe(2);
  });
});
