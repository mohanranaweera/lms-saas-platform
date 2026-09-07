import { test, expect } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";
import {
  courseResponseBody,
  examQuestionResponseBody,
  examResponseBody,
  examSummaryResponseBody,
  mockTenantSession,
} from "./fixtures/exams-mocks";

/**
 * MVP-017 "Exams" — scenario 7 (plan §18): the Teacher Results Publishing
 * screen's three distinct next-action states (not-closed / closed-and-ready /
 * already-published) plus its `ExamPicker` "switch exam" affordance, and the
 * Tenant Admin Exam Oversight page's real tenant-wide list (rebuilt
 * post-review onto `GET /api/v1/exams` — see `lib/api/exams.ts`'s doc
 * comment for the closed-gap record; previously an ID-paste-only lookup
 * tool). Also covers the Publish/Schedule confirmation-dialog fixes from the
 * post-review UX pass (Fix 2/Fix 3): both irreversible actions require an
 * `AlertDialog` confirmation before the underlying mutation fires.
 */

const EXAM_ID = "e0000000-0000-0000-0000-000000000001";
const COURSE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const QUESTION_ID = "q0000000-0000-0000-0000-00000000000a";

test.describe("Teacher Results Publishing (screen #7) — three distinct next-action states", () => {
  test("an exam that has not yet closed shows the 'not yet closed' next-action state, with no Publish control", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}`,
      200,
      apiSuccess(examResponseBody({ id: EXAM_ID, courseId: COURSE_ID, status: "SCHEDULED", resultsPublishedAt: null }))
    );

    await page.goto(`/teacher/exams/${EXAM_ID}/publish`);

    await expect(
      page.getByText(
        "Publish becomes available once the exam window closes and its status advances to Closed. This exam is currently SCHEDULED."
      )
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Publish results" })).toHaveCount(0);
  });

  test("a closed, unpublished exam shows the 'ready to publish' state, and publishing transitions to the 'already published' state", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));

    let publishedAt: string | null = null;
    await page.route(`**/v1/exams/${EXAM_ID}`, async (route) => {
      await fulfillJson(
        route,
        200,
        apiSuccess(
          examResponseBody({ id: EXAM_ID, courseId: COURSE_ID, status: "CLOSED", resultsPublishedAt: publishedAt })
        )
      );
    });
    let releasePublish: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      releasePublish = resolve;
    });
    await page.route(`**/v1/exams/${EXAM_ID}/publish-results`, async (route) => {
      await gate;
      publishedAt = new Date().toISOString();
      await fulfillJson(route, 200, apiSuccess({ examId: EXAM_ID, resultsPublishedAt: publishedAt }));
    });

    await page.goto(`/teacher/exams/${EXAM_ID}/publish`);

    await expect(
      page.getByText(
        "This exam is Closed and ready to publish. Every enrolled student will immediately be able to see their score and per-question review."
      )
    ).toBeVisible();

    // Publishing is irreversible (Fix 2, UX review) — the "Publish results"
    // control only opens a confirmation `AlertDialog`; the actual publish
    // call fires from the dialog's own confirm button.
    const publishTrigger = page.getByRole("button", { name: "Publish results" });
    await publishTrigger.click();

    const dialog = page.getByRole("alertdialog", { name: "Publish results for this exam?" });
    await expect(dialog).toBeVisible();

    // The confirm button's idle label matches the trigger's ("Publish
    // results") so a `/^Publish/` regex (not the exact idle-state label)
    // stays matched through the pending state too, whose label switches to
    // "Publishing…" — a locator captured against the exact idle-state label
    // would stop resolving once the label changes mid-click.
    const confirmButton = dialog.getByRole("button", { name: /^Publish/ });
    await confirmButton.click();

    await expect(confirmButton).toHaveAttribute("aria-busy", "true");
    const publishingAnnouncement = page.getByRole("status").filter({ hasText: "Publishing results…" });
    await expect(publishingAnnouncement).toBeVisible();

    releasePublish?.();

    await expect(dialog).not.toBeVisible();
    await expect(page.getByText(/^Results were already published on /)).toBeVisible();
    await expect(page.getByText("Publishing is a one-way action — there is no unpublish path.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Publish results" })).toHaveCount(0);
  });

  test("cancelling the publish confirmation dialog fires no publish request", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}`,
      200,
      apiSuccess(examResponseBody({ id: EXAM_ID, courseId: COURSE_ID, status: "CLOSED", resultsPublishedAt: null }))
    );
    let publishCalled = false;
    await page.route(`**/v1/exams/${EXAM_ID}/publish-results`, async (route) => {
      publishCalled = true;
      await fulfillJson(route, 200, apiSuccess({ examId: EXAM_ID, resultsPublishedAt: new Date().toISOString() }));
    });

    await page.goto(`/teacher/exams/${EXAM_ID}/publish`);

    await page.getByRole("button", { name: "Publish results" }).click();
    const dialog = page.getByRole("alertdialog", { name: "Publish results for this exam?" });
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: "Cancel" }).click();

    await expect(dialog).not.toBeVisible();
    expect(publishCalled).toBe(false);
    await expect(
      page.getByText(
        "This exam is Closed and ready to publish. Every enrolled student will immediately be able to see their score and per-question review."
      )
    ).toBeVisible();
  });

  test("an already-published exam shows the 'already published' state on first load, distinct from the other two", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody({ id: COURSE_ID })]));
    await mockJson(
      page,
      `**/v1/exams/${EXAM_ID}`,
      200,
      apiSuccess(
        examResponseBody({
          id: EXAM_ID,
          courseId: COURSE_ID,
          status: "CLOSED",
          resultsPublishedAt: "2026-01-01T00:00:00.000Z",
        })
      )
    );

    await page.goto(`/teacher/exams/${EXAM_ID}/publish`);

    await expect(page.getByText(/^Results were already published on /)).toBeVisible();
    await expect(
      page.getByText(
        "This exam is Closed and ready to publish. Every enrolled student will immediately be able to see their score and per-question review."
      )
    ).toHaveCount(0);
    await expect(
      page.getByText("Publish becomes available once the exam window closes")
    ).toHaveCount(0);
  });
});

test.describe("Teacher Exam Scheduler (screen #5) — schedule confirmation (Fix 3)", () => {
  function draftExamWithOneQuestion() {
    return examResponseBody({
      id: EXAM_ID,
      courseId: COURSE_ID,
      status: "DRAFT",
      questions: [examQuestionResponseBody({ id: QUESTION_ID, courseId: COURSE_ID })],
    });
  }

  test("Schedule exam requires confirmation via an AlertDialog before the schedule request fires", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 200, apiSuccess(draftExamWithOneQuestion()));
    // `ExamDraftForm` (rendered above the Schedule control) independently
    // fetches the course's question bank for its picker.
    await mockJson(page, `**/v1/exams/courses/${COURSE_ID}/questions*`, 200, apiPageSuccess([]));
    let scheduled = false;
    await page.route(`**/v1/exams/${EXAM_ID}/schedule`, async (route) => {
      scheduled = true;
      await fulfillJson(
        route,
        200,
        apiSuccess({ ...draftExamWithOneQuestion(), status: "SCHEDULED" })
      );
    });

    await page.goto(`/teacher/exams/${EXAM_ID}/schedule`);

    const scheduleTrigger = page.getByRole("button", { name: "Schedule exam" });
    await expect(scheduleTrigger).toBeVisible();
    await expect(page.getByRole("alertdialog")).toHaveCount(0);
    expect(scheduled).toBe(false);

    await scheduleTrigger.click();
    const dialog = page.getByRole("alertdialog", { name: "Schedule this exam?" });
    await expect(dialog).toBeVisible();
    // Opening the dialog alone must not have fired the request yet.
    expect(scheduled).toBe(false);

    // The confirm button's idle label matches the trigger's ("Schedule
    // exam"); scoping to `dialog` disambiguates it from the (still-visible,
    // outside-the-dialog) trigger of the same name.
    await dialog.getByRole("button", { name: "Schedule exam" }).click();

    await expect(dialog).not.toBeVisible();
    expect(scheduled).toBe(true);
    // A non-DRAFT exam no longer renders the Schedule control.
    await expect(page.getByRole("button", { name: "Schedule exam" })).toHaveCount(0);
  });

  test("cancelling the schedule confirmation dialog fires no schedule request", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, `**/v1/exams/${EXAM_ID}`, 200, apiSuccess(draftExamWithOneQuestion()));
    // `ExamDraftForm` (rendered above the Schedule control) independently
    // fetches the course's question bank for its picker.
    await mockJson(page, `**/v1/exams/courses/${COURSE_ID}/questions*`, 200, apiPageSuccess([]));
    let scheduleCalled = false;
    await page.route(`**/v1/exams/${EXAM_ID}/schedule`, async (route) => {
      scheduleCalled = true;
      await fulfillJson(route, 200, apiSuccess({ ...draftExamWithOneQuestion(), status: "SCHEDULED" }));
    });

    await page.goto(`/teacher/exams/${EXAM_ID}/schedule`);

    await page.getByRole("button", { name: "Schedule exam" }).click();
    const dialog = page.getByRole("alertdialog", { name: "Schedule this exam?" });
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: "Cancel" }).click();

    await expect(dialog).not.toBeVisible();
    expect(scheduleCalled).toBe(false);
    await expect(page.getByRole("button", { name: "Schedule exam" })).toBeVisible();
  });
});

test.describe("Tenant Admin Exam Oversight (screen #8) — real tenant-wide list", () => {
  test("exams across the tenant render in the DataTable, and the status filter re-queries with the selected status", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    let lastUrl = "";
    await page.route("**/v1/exams?*", async (route) => {
      lastUrl = route.request().url();
      const status = new URL(lastUrl).searchParams.get("status");
      const rows =
        status === "CLOSED"
          ? []
          : [examSummaryResponseBody({ id: EXAM_ID, courseId: COURSE_ID, title: "Final Exam", status: "PUBLISHED" })];
      await fulfillJson(route, 200, apiPageSuccess(rows));
    });

    await page.goto("/tenant-admin/exams");

    await expect(page.getByRole("table")).toBeVisible();
    await expect(page.getByRole("table").getByText("Final Exam")).toBeVisible();

    await page.getByRole("button", { name: "Closed", exact: true }).click();

    await expect(page.getByText("No closed exams")).toBeVisible();
    expect(lastUrl).toContain("status=CLOSED");
  });

  test("zero exams in the tenant shows the explicit 'no exams exist yet' empty state", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/exams?*", 200, apiPageSuccess([]));

    await page.goto("/tenant-admin/exams");

    await expect(page.getByText("No exams exist yet")).toBeVisible();
  });
});

test.describe("Tenant Admin Exam Oversight (screen #8) — 403 on the tenant-wide list", () => {
  test("a 403 from the list endpoint renders PermissionDeniedState, never the DataTable", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/exams?*",
      403,
      apiError("FORBIDDEN", "You do not have permission to perform this action")
    );

    await page.goto("/tenant-admin/exams");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission to view this." });
    await expect(denied).toBeVisible();
    const dashboardLink = denied.getByRole("link", { name: "Back to your dashboard" });
    await expect(dashboardLink).toHaveAttribute("href", "/tenant-admin/dashboard");

    await expect(page.getByRole("table")).toHaveCount(0);
  });
});

test.describe("Teacher Results Publishing (screen #7) — useExam loading state", () => {
  test("LoadingState renders while the exam detail fetch is in flight", async ({ page }) => {
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
        apiSuccess(examResponseBody({ id: EXAM_ID, courseId: COURSE_ID, status: "SCHEDULED" }))
      );
    });

    await page.goto(`/teacher/exams/${EXAM_ID}/publish`);

    const loading = page.getByRole("status").filter({ hasText: "Loading exam…" });
    await expect(loading).toBeVisible();
    await expect(loading).toHaveAttribute("aria-busy", "true");

    release?.();
    await expect(loading).toHaveCount(0);
  });
});
