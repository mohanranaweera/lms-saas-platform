import { test, expect } from "@playwright/test";
import { api, apiLogin, EVIDENCE_DIR, loginUi, STUDENT_EMAIL, TENANT_BASE } from "./fixtures/klass/klass-api";

/**
 * KLASS live E2E — external-player handoff (CM-R03) on the REAL local backend.
 *
 * A handoff needs a lesson with a READY video the student is entitled to, in a
 * tenant whose video settings require the native player. Such a video cannot
 * be produced in a test run (it needs a provider upload), so this spec uses an
 * EXISTING demo-tenant lesson, located by name (override with env vars), and
 * skips with a visible reason when it isn't there. It changes no settings; the
 * only side effect is the lesson view the launch records for the demo student.
 */

const COURSE_NAME = process.env.KLASS_E2E_VIDEO_COURSE ?? "2028 Biology Theory Class";
const LESSON_TITLE = process.env.KLASS_E2E_VIDEO_LESSON ?? "Lesson 01";

interface LessonNode {
  lessonId: string;
  title: string;
  access: { status: string };
}

test("CM-R03: a video lesson in a native-player tenant opens the 'web playback is turned off' handoff, never a browser player", async ({
  page,
  request,
}) => {
  const token = await apiLogin(request, STUDENT_EMAIL);
  const catalog = await api<{ content: { courseId: string; title: string }[] }>(
    request,
    token,
    "GET",
    `/enrollments/my/catalog?view=ENROLLED&search=${encodeURIComponent(COURSE_NAME)}`
  );
  const course = (catalog.body.data?.content ?? []).find((c) => c.title === COURSE_NAME);
  test.skip(!course, `Demo data missing: the student is not enrolled in "${COURSE_NAME}"`);

  const content = await api<{ periods: { modules: { lessons: LessonNode[] }[] }[] }>(
    request,
    token,
    "GET",
    `/courses/${course!.courseId}/content`
  );
  const lesson = (content.body.data?.periods ?? [])
    .flatMap((p) => p.modules)
    .flatMap((m) => m.lessons)
    .find((l) => l.title === LESSON_TITLE && l.access.status === "ALLOWED");
  test.skip(!lesson, `Demo data missing: no ALLOWED "${LESSON_TITLE}" in "${COURSE_NAME}"`);

  const launch: { body: { data?: { playerHandoffRequired?: boolean } } | null } = { body: null };
  page.on("response", async (response) => {
    if (response.url().includes(`/lessons/${lesson!.lessonId}/launch`) && response.request().method() === "POST") {
      launch.body = await response.json().catch(() => null);
    }
  });

  await loginUi(page, STUDENT_EMAIL);
  await page.goto(`${TENANT_BASE}/student/courses/${course!.courseId}`);
  const row = page.locator(`[data-testid="content-lesson"][data-lesson-id="${lesson!.lessonId}"]`);
  // Expand every folder until the lesson row is visible.
  await expect(page.getByTestId("content-period-toggle").first()).toBeVisible();
  for (const toggle of await page.getByTestId("content-period-toggle").all()) {
    if ((await toggle.getAttribute("aria-expanded")) !== "true") await toggle.click();
  }
  await expect(page.getByTestId("content-module-toggle").first()).toBeVisible();
  for (const toggle of await page.getByTestId("content-module-toggle").all()) {
    if ((await toggle.getAttribute("aria-expanded")) !== "true") await toggle.click();
  }
  await row.getByTestId("content-lesson-open").click();

  const dialog = page.getByTestId("player-handoff-dialog");
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText("Web playback is turned off");
  expect(launch.body?.data?.playerHandoffRequired).toBe(true);
  // No in-browser player or media URL is rendered.
  await expect(page.locator("video, iframe[src*='youtube'], iframe[src*='dyntube']")).toHaveCount(0);
  await page.screenshot({ path: `${EVIDENCE_DIR}/14-player-handoff-modal.png` });
});
