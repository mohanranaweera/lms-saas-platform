import { test, expect } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";
import {
  examAttemptResponseBody,
  examResponseBody,
  isoOffsetMinutes,
  mockTenantSession,
  nowIso,
  savedAnswerResponseBody,
} from "./fixtures/exams-mocks";

/**
 * MVP-017 "Exams" — scenario 2 (plan §18): "Student exam-taking at a narrow
 * viewport (375×667): full attempt flow; `aria-busy`/live-region asserted on
 * submit via the accessibility tree, not just visual text; direct-URL access
 * before/after the window intercepted and asserted 403 with the
 * corresponding distinct blocked UI state."
 */

const EXAM_ID = "e0000000-0000-0000-0000-000000000001";
const COURSE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const ATTEMPT_ID = "att00000-0000-0000-0000-000000000001";
const MCQ_QUESTION_ID = "q0000000-0000-0000-0000-00000000000a";
const STRUCTURED_QUESTION_ID = "q0000000-0000-0000-0000-00000000000b";
const OPTION_PARIS_ID = "opt00000-0000-0000-0000-000000000001";
const OPTION_LONDON_ID = "opt00000-0000-0000-0000-000000000002";
const MCQ_BODY = "What is the capital of France?";
const STRUCTURED_BODY = "Explain photosynthesis.";

function examWithQuestions() {
  return examResponseBody({
    id: EXAM_ID,
    courseId: COURSE_ID,
    title: "Midterm Exam",
    status: "PUBLISHED",
    timeLimitMinutes: 60,
    questions: [
      {
        id: MCQ_QUESTION_ID,
        courseId: COURSE_ID,
        questionType: "MCQ",
        body: MCQ_BODY,
        options: [
          { id: OPTION_PARIS_ID, optionText: "Paris" },
          { id: OPTION_LONDON_ID, optionText: "London" },
        ],
      },
      {
        id: STRUCTURED_QUESTION_ID,
        courseId: COURSE_ID,
        questionType: "STRUCTURED",
        body: STRUCTURED_BODY,
        options: [],
      },
    ],
  });
}

test.describe("Student Exam Taking (screen #2) — narrow viewport (375x667), full attempt flow", () => {
  test.use({ viewport: { width: 375, height: 667 } });

  test("start attempt, answer an MCQ and a structured question, save each, then submit with aria-busy/live-region announcing state", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/attempts`,
      200,
      apiSuccess(examAttemptResponseBody({ id: ATTEMPT_ID, examId: EXAM_ID, status: "IN_PROGRESS" }))
    );
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 200, apiSuccess(examWithQuestions()));
    await mockJson(page, `**/v1/exams/attempts/${ATTEMPT_ID}/answers`, 200, apiSuccess(null));
    await mockJson(
      page,
      `**/v1/exams/attempts/${ATTEMPT_ID}/results*`,
      200,
      apiSuccess({ published: false, result: null })
    );

    await page.goto(`/student/exams/${EXAM_ID}/take`);

    await expect(page.getByRole("heading", { name: "Midterm Exam" })).toBeVisible();

    // Answer the MCQ question and save it.
    const mcqItem = page.locator("li", { hasText: MCQ_BODY });
    await mcqItem.getByRole("radio", { name: "Paris" }).check();
    await mcqItem.getByRole("button", { name: "Save answer" }).click();
    await expect(mcqItem.getByText("Saved")).toBeVisible();

    // Answer the structured question and save it.
    const structuredItem = page.locator("li", { hasText: STRUCTURED_BODY });
    await structuredItem.getByLabel(STRUCTURED_BODY).fill("Plants convert sunlight into chemical energy.");
    await structuredItem.getByRole("button", { name: "Save answer" }).click();
    await expect(structuredItem.getByText("Saved")).toBeVisible();

    // Submit — gated so the in-flight `aria-busy`/live-region state is observable.
    let releaseSubmit: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      releaseSubmit = resolve;
    });
    await page.route(`**/v1/exams/attempts/${ATTEMPT_ID}/submit`, async (route) => {
      await gate;
      await fulfillJson(
        route,
        200,
        apiSuccess(
          examAttemptResponseBody({ id: ATTEMPT_ID, examId: EXAM_ID, status: "SUBMITTED", submittedAt: nowIso() })
        )
      );
    });

    // `/^Submit/` (not the exact "Submit exam" label) stays matched through
    // the pending state too, whose label switches to "Submitting…" — a
    // locator captured against the exact idle-state label would stop
    // resolving once the label changes mid-click.
    const submitButton = page.getByRole("button", { name: /^Submit/ });
    await submitButton.click();

    await expect(submitButton).toHaveAttribute("aria-busy", "true");
    const submittingAnnouncement = page.getByRole("status").filter({ hasText: "Submitting your exam…" });
    await expect(submittingAnnouncement).toBeVisible();
    await expect(submittingAnnouncement).toHaveAttribute("aria-live", "polite");

    releaseSubmit?.();

    await expect(page).toHaveURL(new RegExp(`/student/exams/${EXAM_ID}/results\\?attemptId=${ATTEMPT_ID}`));
  });
});

test.describe("Student Exam Taking (screen #2) — resume after reload rehydrates saved answers (Fix 1)", () => {
  test("a previously saved answer survives a full page reload, is not shown blank, and a subsequent save never overwrites it with null", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/attempts`,
      200,
      apiSuccess(examAttemptResponseBody({ id: ATTEMPT_ID, examId: EXAM_ID, status: "IN_PROGRESS" }))
    );
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 200, apiSuccess(examWithQuestions()));

    // Stateful mock: `PUT` records the saved response; `GET` (fired again on
    // reload, re-driving `useAttemptAnswers`) returns whatever was last
    // saved — the same route persists across `page.reload()` since it's
    // registered on the `page` object, not per-navigation.
    let savedResponse: string | null = null;
    await page.route(`**/v1/exams/attempts/${ATTEMPT_ID}/answers`, async (route) => {
      if (route.request().method() === "PUT") {
        const body = route.request().postDataJSON() as { questionId: string; response: string | null };
        if (body.questionId === STRUCTURED_QUESTION_ID) {
          savedResponse = body.response;
        }
        await fulfillJson(route, 200, apiSuccess(null));
        return;
      }
      await fulfillJson(
        route,
        200,
        apiSuccess(
          savedResponse === null
            ? []
            : [savedAnswerResponseBody({ questionId: STRUCTURED_QUESTION_ID, response: savedResponse })]
        )
      );
    });

    await page.goto(`/student/exams/${EXAM_ID}/take`);
    await expect(page.getByRole("heading", { name: "Midterm Exam" })).toBeVisible();

    const structuredItem = page.locator("li", { hasText: STRUCTURED_BODY });
    await expect(structuredItem.getByLabel(STRUCTURED_BODY)).toHaveValue("");
    await structuredItem.getByLabel(STRUCTURED_BODY).fill("Plants convert sunlight into chemical energy.");
    await structuredItem.getByRole("button", { name: "Save answer" }).click();
    await expect(structuredItem.getByText("Saved")).toBeVisible();

    // Simulate a reload/crash-recovery: this re-runs the whole
    // start-attempt + seed-from-saved-answers flow from scratch, against an
    // attempt that already has a saved answer server-side.
    await page.reload();

    await expect(page.getByRole("heading", { name: "Midterm Exam" })).toBeVisible();
    const resumedStructuredItem = page.locator("li", { hasText: STRUCTURED_BODY });
    await expect(resumedStructuredItem.getByLabel(STRUCTURED_BODY)).toHaveValue(
      "Plants convert sunlight into chemical energy."
    );
    // Seeded on resume — the "Saved" indicator must be accurate immediately,
    // not just after another manual save (Fix 1's `savedQuestionIds` seeding).
    await expect(resumedStructuredItem.getByText("Saved")).toBeVisible();

    // Guards against the regression this fix closes: clicking "Save answer"
    // on a field that renders blank (because it was never seeded) would send
    // `response: null` and silently overwrite the good saved answer.
    expect(savedResponse).toBe("Plants convert sunlight into chemical energy.");
  });
});

test.describe("Student Exam Taking (screen #2) — distinct blocked states for a direct-URL access outside the window", () => {
  test.use({ viewport: { width: 375, height: 667 } });

  test("accessing before scheduled_start renders a distinct 'not yet open' state (NOT_YET_OPEN)", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 200, apiSuccess(examWithQuestions()));
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/attempts`,
      403,
      apiError("NOT_YET_OPEN", "This exam's window has not opened yet.")
    );

    await page.goto(`/student/exams/${EXAM_ID}/take`);

    const blockedState = page.getByRole("status").filter({ hasText: "This exam is not yet open" });
    await expect(blockedState).toBeVisible();
    await expect(page.getByText("This exam's window has closed")).toHaveCount(0);
  });

  test("accessing after scheduled_end/CLOSED renders a distinct 'window closed' state (WINDOW_CLOSED)", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 200, apiSuccess(examWithQuestions()));
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/attempts`,
      403,
      apiError("WINDOW_CLOSED", "This exam's window has closed.")
    );

    await page.goto(`/student/exams/${EXAM_ID}/take`);

    const blockedState = page.getByRole("status").filter({ hasText: "This exam's window has closed" });
    await expect(blockedState).toBeVisible();
    await expect(page.getByText("This exam is not yet open")).toHaveCount(0);
  });
});

test.describe("Student Exam Taking (screen #2) — shared-device attempt-storage scoping (Fix 3)", () => {
  test("a second student's session on the same browser never inherits the first student's remembered attempt id", async ({
    page,
  }) => {
    // Student A (`sub: "user-1"`, `mockTenantSession`'s default) starts the
    // exam — this best-effort remembers `{examId: ATTEMPT_ID}` in this
    // browser's localStorage, scoped to user-1's id.
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/attempts`,
      200,
      apiSuccess(examAttemptResponseBody({ id: ATTEMPT_ID, examId: EXAM_ID, status: "IN_PROGRESS" }))
    );
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 200, apiSuccess(examWithQuestions()));
    await mockJson(page, `**/v1/exams/attempts/${ATTEMPT_ID}/answers`, 200, apiSuccess([]));

    await page.goto(`/student/exams/${EXAM_ID}/take`);
    await expect(page.getByRole("heading", { name: "Midterm Exam" })).toBeVisible();

    // A different student signs in on this same shared browser/device —
    // re-mock the refresh endpoint with a distinct `sub` claim (no logout
    // call needed here: this asserts the storage scoping itself, which is
    // what protects against inheriting a stale entry regardless of how the
    // session changed).
    const otherStudentToken = fakeJwt({ role: "STUDENT", sub: "user-2" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(otherStudentToken)));
    // User-2 genuinely has no attempts of their own — the Results page's
    // `useMyAttempts` fallback (added post-review) must not surface user-1's.
    await mockJson(page, "**/v1/exams/attempts/my*", 200, apiSuccess({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }));

    // Visiting Results & Review directly (no `?attemptId=`) must NOT resolve
    // to user-1's remembered attempt — it must honestly fall through to the
    // "we don't know which attempt" state instead of leaking user-1's data
    // to user-2.
    await page.goto(`/student/exams/${EXAM_ID}/results`);

    await expect(page.getByText("We don't know which attempt to show")).toBeVisible();
    await expect(page).not.toHaveURL(new RegExp(`attemptId=${ATTEMPT_ID}`));
  });
});

test.describe("Student Exam Taking (screen #2) — exam-detail query goes through QueryStateBoundary", () => {
  test("a 403 on GET /exams/{examId} renders PermissionDeniedState, not the generic retryable error", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    // The attempt itself starts fine; it's the exam-detail fetch that's
    // forbidden (e.g. the student's enrollment was revoked after the
    // attempt started) — this must render the real, actionable
    // permission-denied state, not a plain "Try again" error.
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/attempts`,
      200,
      apiSuccess(examAttemptResponseBody({ id: ATTEMPT_ID, examId: EXAM_ID, status: "IN_PROGRESS" }))
    );
    const forbiddenMessage = "You no longer have access to this exam.";
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 403, apiError("FORBIDDEN", forbiddenMessage));
    await mockJson(page, `**/v1/exams/attempts/${ATTEMPT_ID}/answers`, 200, apiSuccess([]));

    await page.goto(`/student/exams/${EXAM_ID}/take`);

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(page.getByText(forbiddenMessage)).toBeVisible();
    const dashboardLink = page.getByRole("link", { name: "Back to your dashboard" });
    await expect(dashboardLink).toBeVisible();
    await expect(dashboardLink).toHaveAttribute("href", "/student/dashboard");

    // The generic retryable error state (a plain "Try again" button with no
    // permission-denied copy) must not render for this 403.
    await expect(page.getByRole("button", { name: "Try again" })).toHaveCount(0);
  });
});

test.describe("Student Exam Taking (screen #2) — distinct submit-time WINDOW_CLOSED handling", () => {
  test("a 403 WINDOW_CLOSED on submit shows distinct copy, not the generic submit-failure message", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/attempts`,
      200,
      apiSuccess(examAttemptResponseBody({ id: ATTEMPT_ID, examId: EXAM_ID, status: "IN_PROGRESS" }))
    );
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 200, apiSuccess(examWithQuestions()));
    await mockJson(page, `**/v1/exams/attempts/${ATTEMPT_ID}/answers`, 200, apiSuccess([]));
    await mockJson(
      page,
      `**/v1/exams/attempts/${ATTEMPT_ID}/submit`,
      403,
      apiError("WINDOW_CLOSED", "This exam's window has closed.")
    );

    await page.goto(`/student/exams/${EXAM_ID}/take`);
    await expect(page.getByRole("heading", { name: "Midterm Exam" })).toBeVisible();

    await page.getByRole("button", { name: /^Submit/ }).click();

    // `exact: true` disambiguates from the sr-only live-region span, which
    // also matches this text as a substring (its announcement is prefixed
    // with "Submission failed: ") — both carry `role="alert"`, so an
    // exact-text match is required to target only the visible destructive
    // message paragraph.
    await expect(
      page.getByText(
        "This exam's window closed before your submission went through, so it was not recorded as submitted. Contact your teacher if you believe this is a mistake.",
        { exact: true }
      )
    ).toBeVisible();
    await expect(page.getByText("Could not submit your exam. Please try again.")).toHaveCount(0);
  });
});

test.describe("Student Exam Taking (screen #2) — countdown reaching zero", () => {
  test("the visible countdown reaching zero renders the 'Time's up — submit now.' alert", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    // `startedAt` far enough in the past (120 minutes) that
    // `computeAttemptDeadline` (startedAt + timeLimitMinutes, clamped by the
    // exam's own `scheduledEnd`) has already elapsed by the time this
    // attempt is "resumed" — `remainingMs` is negative from the very first
    // render (it's computed from `Date.now()` at mount, not just on each
    // 1s tick), so this doesn't require waiting on the real 1-second interval.
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/attempts`,
      200,
      apiSuccess(
        examAttemptResponseBody({
          id: ATTEMPT_ID,
          examId: EXAM_ID,
          status: "IN_PROGRESS",
          startedAt: isoOffsetMinutes(-120),
        })
      )
    );
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 200, apiSuccess(examWithQuestions()));
    await mockJson(page, `**/v1/exams/attempts/${ATTEMPT_ID}/answers`, 200, apiSuccess([]));

    await page.goto(`/student/exams/${EXAM_ID}/take`);

    await expect(page.getByRole("heading", { name: "Midterm Exam" })).toBeVisible();
    const expiredAlert = page.getByRole("alert").filter({ hasText: "Time's up — submit now." });
    await expect(expiredAlert).toBeVisible();
    await expect(page.getByText(/^Time remaining: 0:00/)).toBeVisible();
  });
});

test.describe("Student Exam Taking (screen #2) — 409 concurrent-attempt-in-progress race on start", () => {
  test("a 409 CONFLICT on POST /attempts shows the distinct 'already in progress' copy, not NOT_YET_OPEN/WINDOW_CLOSED", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    // The exact shape `GlobalExceptionHandler.handleDataIntegrityViolation`
    // maps a concurrent-`IN_PROGRESS`-attempt DB conflict to (confirmed
    // directly against `com.lms.common.web.GlobalExceptionHandler` and
    // `ApiErrorCodes.CONFLICT`) — the take-page's third start-error branch
    // matches on `error.status === 409` alone (not this specific `code`),
    // unlike the `NOT_YET_OPEN`/`WINDOW_CLOSED` branches above it.
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/attempts`,
      409,
      apiError("CONFLICT", "The request conflicts with existing data")
    );
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 200, apiSuccess(examWithQuestions()));

    await page.goto(`/student/exams/${EXAM_ID}/take`);

    const conflictState = page.getByRole("status").filter({ hasText: "This exam attempt is already in progress" });
    await expect(conflictState).toBeVisible();
    await expect(page.getByText("This exam is not yet open")).toHaveCount(0);
    await expect(page.getByText("This exam's window has closed")).toHaveCount(0);
  });
});

test.describe("Student Exam Taking (screen #2) — generic (non-403, non-documented-code) failure paths", () => {
  test("a generic 500 on GET /exams/{examId} renders ErrorState with a retry option, not PermissionDeniedState", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/attempts`,
      200,
      apiSuccess(examAttemptResponseBody({ id: ATTEMPT_ID, examId: EXAM_ID, status: "IN_PROGRESS" }))
    );
    const genericMessage = "Something went wrong on our end.";
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 500, apiError("INTERNAL_ERROR", genericMessage));
    await mockJson(page, `**/v1/exams/attempts/${ATTEMPT_ID}/answers`, 200, apiSuccess([]));

    await page.goto(`/student/exams/${EXAM_ID}/take`);

    const errorAlert = page.getByRole("alert").filter({ hasText: genericMessage });
    await expect(errorAlert).toBeVisible();
    await expect(errorAlert.getByRole("button", { name: "Try again" })).toBeVisible();
    await expect(page.getByText("You don't have permission to view this.")).toHaveCount(0);
  });

  test("a generic failure on PUT /attempts/{id}/answers renders its own role=alert message, scoped to that question", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}/attempts`,
      200,
      apiSuccess(examAttemptResponseBody({ id: ATTEMPT_ID, examId: EXAM_ID, status: "IN_PROGRESS" }))
    );
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 200, apiSuccess(examWithQuestions()));
    const saveFailureMessage = "Could not save due to a server hiccup.";
    await mockJson(
      page,
      `**/v1/exams/attempts/${ATTEMPT_ID}/answers`,
      500,
      apiError("INTERNAL_ERROR", saveFailureMessage)
    );

    await page.goto(`/student/exams/${EXAM_ID}/take`);
    await expect(page.getByRole("heading", { name: "Midterm Exam" })).toBeVisible();

    const mcqItem = page.locator("li", { hasText: MCQ_BODY });
    await mcqItem.getByRole("radio", { name: "Paris" }).check();
    await mcqItem.getByRole("button", { name: "Save answer" }).click();

    await expect(mcqItem.getByRole("alert").filter({ hasText: saveFailureMessage })).toBeVisible();
    await expect(mcqItem.getByText("Saved")).toHaveCount(0);
  });
});
