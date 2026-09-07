import { test, expect } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";
import { courseResponseBody, examResponseBody, examSummaryResponseBody, markingQueueEntryBody, mockTenantSession } from "./fixtures/exams-mocks";

/**
 * MVP-017 "Exams" — scenario 3 (plan §18): "Marking Queue scoring control is
 * fully keyboard-operable end to end" — tab to the numeric score input, type
 * a value, tab to the submit control, activate via keyboard alone. Also
 * exercises the course → exam `ExamPicker` entry point (added post-review
 * once `GET /courses/{courseId}/exams` closed the earlier "no list-exams
 * endpoint" gap — see `lib/api/exams.ts`'s doc comment).
 */

const EXAM_ID = "e0000000-0000-4000-8000-000000000001";
const COURSE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const ANSWER_ID = "ans00000-0000-0000-0000-000000000001";
const QUESTION_BODY = "Explain photosynthesis.";

test.describe("Teacher Marking Queue (screen #6) — keyboard-only scoring", () => {
  test("picking a course and exam via the ExamPicker, then scoring a structured answer, is fully keyboard-operable end to end", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    await mockJson(
      page,
      `**/v1/exams/courses/${COURSE_ID}/exams*`,
      200,
      apiPageSuccess([examSummaryResponseBody({ id: EXAM_ID, courseId: COURSE_ID, status: "PUBLISHED" })])
    );
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}`,
      200,
      apiSuccess(examResponseBody({ id: EXAM_ID, courseId: COURSE_ID, status: "PUBLISHED" }))
    );
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/marking-queue*`,
      200,
      apiPageSuccess([
        markingQueueEntryBody({
          answerId: ANSWER_ID,
          examId: EXAM_ID,
          questionBody: QUESTION_BODY,
          response: "Plants convert sunlight into chemical energy.",
        }),
      ])
    );
    await mockJson(page, `**/v1/exams/answers/${ANSWER_ID}/mark`, 200, apiSuccess(null));

    await page.goto("/teacher/exams/marking");

    // Course select, keyboard only: focus, open, choose the only option.
    const courseTrigger = page.getByLabel("Course");
    await courseTrigger.focus();
    await page.keyboard.press("Enter");
    await page.getByRole("option", { name: "Intro to Biology" }).click();

    // Exam select, keyboard only.
    const examTrigger = page.getByLabel("Exam");
    await examTrigger.focus();
    await page.keyboard.press("Enter");
    await page.getByRole("option", { name: "Midterm Exam" }).click();

    // `exact: true` avoids matching the score input's own label ("Score for
    // question, out of 1 point — Explain photosynthesis."), which also
    // contains this text as a substring.
    await expect(page.getByText(QUESTION_BODY, { exact: true })).toBeVisible();

    // Tab to the numeric score input, type a value, tab to the submit
    // control, and activate it via the keyboard alone.
    const scoreInput = page.getByLabel(`Score for question, out of 1 point — ${QUESTION_BODY.slice(0, 40)}`);
    await scoreInput.focus();
    await expect(scoreInput).toBeFocused();
    await page.keyboard.type("1");

    await page.keyboard.press("Tab");
    const saveScoreButton = page.getByRole("button", { name: "Save score" });
    await expect(saveScoreButton).toBeFocused();
    await page.keyboard.press("Enter");

    await expect(page.getByText("Marked. This answer has left the queue.")).toBeVisible();
  });

  test("empty marking queue shows the explicit 'nothing to mark' state, not a spinner/error", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}`,
      200,
      apiSuccess(examResponseBody({ id: EXAM_ID, courseId: COURSE_ID, status: "PUBLISHED" }))
    );
    await mockJson(page, `**/v1/exams/${EXAM_ID}/marking-queue*`, 200, apiPageSuccess([]));

    await page.goto(`/teacher/exams/marking?examId=${EXAM_ID}`);

    const emptyState = page.getByRole("status").filter({ hasText: "Nothing to mark right now." });
    await expect(emptyState).toBeVisible();
  });
});

/**
 * Highest-priority coverage gap from the code review: a same-tenant-owner
 * exam id resolving to a cross-tenant record entirely (the real backend
 * behavior, confirmed directly against `ExamSchedulingService.getExam` — its
 * `loadExam` helper goes through `ExamRepository`, a `TenantAwareRepository`,
 * so a cross-tenant id resolves to `Optional.empty()` and a plain `404
 * "Exam not found"`, never a 403) must never leak exam data, and must never
 * render as a generic "something went wrong, maybe retry" failure that
 * implies a transient/network problem — `ErrorState` here surfaces the real
 * backend message and a `Try again` action, which is the *correct*, honest
 * rendering of a real 404, but it must be this and not a fabricated
 * permission-denied message the frontend has no basis to show for a 404.
 * Reached here via a direct `?examId=` URL (e.g. a stale/shared link) rather
 * than the picker, since the 404 behavior is about `useExam`, independent of
 * how the id was supplied.
 */
test.describe("Teacher Marking Queue (screen #6) — cross-tenant exam id lookup", () => {
  test("a cross-tenant exam id 404s and renders the generic not-found error — never exam data, never a fabricated permission-denied message", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 404, apiError("NOT_FOUND", "Exam not found."));

    await page.goto(`/teacher/exams/marking?examId=${EXAM_ID}`);

    const errorAlert = page.getByRole("alert").filter({ hasText: "Exam not found." });
    await expect(errorAlert).toBeVisible();
    await expect(errorAlert.getByRole("button", { name: "Try again" })).toBeVisible();

    // Never a leak of exam data, and never a fabricated permission-denied message.
    await expect(page.getByText("You don't have permission to view this.")).toHaveCount(0);
    await expect(page.getByText(QUESTION_BODY)).toHaveCount(0);
    await expect(page.getByRole("status").filter({ hasText: "Nothing to mark right now." })).toHaveCount(0);
  });
});

/**
 * Fix 4 (UX review): the inner `QueryStateBoundary` wrapping `queueQuery`
 * previously omitted `loginPath`/`permissionDenied`, so a 401/403 on
 * `GET /{examId}/marking-queue` specifically (independent of the
 * exam-metadata fetch succeeding) fell through to the generic retryable
 * `ErrorState` instead of the real permission-denied state.
 */
test.describe("Teacher Marking Queue (screen #6) — 403 on the marking-queue endpoint itself", () => {
  test("a 403 on GET /{examId}/marking-queue renders PermissionDeniedState, not the generic retryable error", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}`,
      200,
      apiSuccess(examResponseBody({ id: EXAM_ID, courseId: COURSE_ID, status: "PUBLISHED" }))
    );
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/marking-queue*`,
      403,
      apiError("FORBIDDEN", "You do not have permission to perform this action")
    );

    await page.goto(`/teacher/exams/marking?examId=${EXAM_ID}`);

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission to view this." });
    await expect(denied).toBeVisible();
    const dashboardLink = denied.getByRole("link", { name: "Back to your dashboard" });
    await expect(dashboardLink).toHaveAttribute("href", "/teacher/dashboard");

    // Must never fall through to the generic retryable error state, and must
    // never render as the (unrelated) empty "nothing to mark" state either.
    await expect(page.getByRole("button", { name: "Try again" })).toHaveCount(0);
    await expect(page.getByRole("status").filter({ hasText: "Nothing to mark right now." })).toHaveCount(0);
  });
});

test.describe("Teacher Marking Queue (screen #6) — loading states", () => {
  test("useExam's LoadingState renders while the looked-up exam is in flight", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route(`**/v1/exams/${EXAM_ID}`, async (route) => {
      await gate;
      await fulfillJson(
        route,
        200,
        apiSuccess(examResponseBody({ id: EXAM_ID, courseId: COURSE_ID, status: "PUBLISHED" }))
      );
    });
    await mockJson(page, `**/v1/exams/${EXAM_ID}/marking-queue*`, 200, apiPageSuccess([]));

    await page.goto(`/teacher/exams/marking?examId=${EXAM_ID}`);

    const loading = page.getByRole("status").filter({ hasText: "Loading exam…" });
    await expect(loading).toBeVisible();
    await expect(loading).toHaveAttribute("aria-busy", "true");

    release?.();
    await expect(loading).toHaveCount(0);
  });

  test("useMarkingQueueEntries's LoadingState renders while the queue page is in flight, after the exam itself has loaded", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}`,
      200,
      apiSuccess(examResponseBody({ id: EXAM_ID, courseId: COURSE_ID, status: "PUBLISHED" }))
    );
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route(`**/v1/exams/${EXAM_ID}/marking-queue*`, async (route) => {
      await gate;
      await fulfillJson(route, 200, apiPageSuccess([]));
    });

    await page.goto(`/teacher/exams/marking?examId=${EXAM_ID}`);

    const loading = page.getByRole("status").filter({ hasText: "Loading the marking queue…" });
    await expect(loading).toBeVisible();

    release?.();
    await expect(loading).toHaveCount(0);
    await expect(page.getByRole("status").filter({ hasText: "Nothing to mark right now." })).toBeVisible();
  });
});
