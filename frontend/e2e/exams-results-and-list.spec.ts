import { test, expect } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";
import { mockTenantSession } from "./fixtures/exams-mocks";

/**
 * MVP-017 "Exams" — scenarios 4, 5, 6 (plan §18):
 *  4. "Both Results & Review empty-state variants (submitted-unpublished vs.
 *     published) render distinctly."
 *  5. "Both Exam List empty-state variants... render distinctly" (adapted:
 *     `GET /my/upcoming` structurally can only ever return the
 *     zero-data case — see `student/exams/page.tsx`'s own doc comment — so
 *     this covers the one real empty case the shipped endpoint supports).
 *  6. "A student never sees another student's data via id substitution" —
 *     the backend's real behavior for another student's attempt id is a
 *     404, asserted here to render a not-found/error state, never partial
 *     score data.
 */

const EXAM_ID = "e0000000-0000-0000-0000-000000000001";
const ATTEMPT_ID = "att00000-0000-0000-0000-000000000001";
const OTHER_STUDENT_ATTEMPT_ID = "att00000-0000-0000-0000-000000000099";
const MCQ_QUESTION_ID = "q0000000-0000-0000-0000-00000000000a";
const STRUCTURED_QUESTION_ID = "q0000000-0000-0000-0000-00000000000b";

function resultsUrl(attemptId: string): string {
  return `/student/exams/${EXAM_ID}/results?attemptId=${attemptId}`;
}

test.describe("Student Results & Review (screen #3) — two distinct states", () => {
  test("a submitted, unpublished attempt shows the distinct 'awaiting publish' copy — not the score", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      `**/v1/exams/attempts/${ATTEMPT_ID}/results`,
      200,
      apiSuccess({ published: false, result: null })
    );

    await page.goto(resultsUrl(ATTEMPT_ID));

    await expect(
      page.getByText("Your exam has been submitted — results will be published soon")
    ).toBeVisible();
    await expect(page.getByText(/^Score:/)).toHaveCount(0);
  });

  test("a published attempt shows the score and per-question review — distinct copy from the unpublished state", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      `**/v1/exams/attempts/${ATTEMPT_ID}/results`,
      200,
      apiSuccess({
        published: true,
        result: {
          attemptId: ATTEMPT_ID,
          examId: EXAM_ID,
          status: "SUBMITTED",
          score: 5,
          maxScore: 10,
          answers: [
            { questionId: MCQ_QUESTION_ID, response: "opt-1", autoScore: 5, manualScore: null },
            { questionId: STRUCTURED_QUESTION_ID, response: "My answer.", autoScore: null, manualScore: null },
          ],
        },
      })
    );

    await page.goto(resultsUrl(ATTEMPT_ID));

    await expect(page.getByText("Score: 5 / 10")).toBeVisible();
    await expect(page.getByText("Correct (5 pts)")).toBeVisible();
    await expect(page.getByText("Pending review")).toBeVisible();
    await expect(
      page.getByText("Your exam has been submitted — results will be published soon")
    ).toHaveCount(0);
  });
});

test.describe("Student Results & Review (screen #3) — cross-student id substitution", () => {
  test("another student's attempt id resolves to a real backend 404, rendering a dedicated not-found state (no retry), never partial score data", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      `**/v1/exams/attempts/${OTHER_STUDENT_ATTEMPT_ID}/results`,
      404,
      apiError("NOT_FOUND", "Attempt not found.")
    );

    await page.goto(resultsUrl(OTHER_STUDENT_ATTEMPT_ID));

    // Post-review fix: a 404 (the anti-enumeration response another
    // student's attempt id actually gets, per plan §13/§15) now renders a
    // dedicated "Attempt not found" state with no retry affordance — not the
    // generic retryable `ErrorState`, which would misleadingly offer "Try
    // again" for a permanently nonexistent/not-yours resource. Never a
    // permission-denied message, and never any score/answer content.
    await expect(page.getByText("Attempt not found")).toBeVisible();
    await expect(page.getByRole("button", { name: "Try again" })).toHaveCount(0);
    const backLink = page.getByRole("button", { name: "Back to Exams" });
    await expect(backLink).toBeVisible();
    await expect(page.getByText("You don't have permission")).toHaveCount(0);
    await expect(page.getByText(/^Score:/)).toHaveCount(0);
    await expect(
      page.getByText("Your exam has been submitted — results will be published soon")
    ).toHaveCount(0);
  });
});

test.describe("Student Exam List (screen #1) — empty state", () => {
  test("zero upcoming exams shows the distinct 'No exams scheduled yet' copy", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/v1/exams/my/upcoming*", 200, apiPageSuccess([]));

    await page.goto("/student/exams");

    const emptyState = page.getByRole("status").filter({ hasText: "No exams scheduled yet" });
    await expect(emptyState).toBeVisible();
    await expect(
      emptyState.getByText("When a teacher schedules an exam for one of your courses, it will appear here.")
    ).toBeVisible();
  });
});

test.describe("Student Exam List (screen #1) — loading state", () => {
  test("useMyUpcomingExams's LoadingState renders while the list is in flight", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/enrollments/my/courses", 200, apiSuccess([]));
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route("**/v1/exams/my/upcoming*", async (route) => {
      await gate;
      await fulfillJson(route, 200, apiPageSuccess([]));
    });

    await page.goto("/student/exams");

    const loading = page.getByRole("status").filter({ hasText: "Loading your exams…" });
    await expect(loading).toBeVisible();
    await expect(loading).toHaveAttribute("aria-busy", "true");

    release?.();
    await expect(loading).toHaveCount(0);
    await expect(page.getByRole("status").filter({ hasText: "No exams scheduled yet" })).toBeVisible();
  });
});
