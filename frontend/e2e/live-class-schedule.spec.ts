import { test, expect, type Page } from "@playwright/test";
import { apiSuccess } from "./fixtures/auth-mocks";
import {
  COURSE_ID,
  makeClassSession,
  mockClassSessionsApi,
  mockTenantSession,
  TEACHER_ID,
} from "./fixtures/class-session-mocks";
import { mockJson } from "./fixtures/auth-mocks";

/**
 * Wave 4 (PAR-19-03) Teacher "Schedule Live Class" flow: create, edit (while
 * SCHEDULED), and retry a failed provisioning — plus the own-course-only
 * enforcement that the course picker only ever lists this Teacher's own
 * courses (server-scoped `GET /v1/courses`, never client-filtered).
 */

function futureDatetimeLocal(minutesFromNow: number): string {
  const date = new Date(Date.now() + minutesFromNow * 60_000);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

async function selectOption(page: Page, labelName: string, optionName: string): Promise<void> {
  await page.getByLabel(labelName, { exact: true }).click();
  await page.getByRole("option", { name: optionName }).click();
}

test.describe("Teacher Schedule Live Class", () => {
  test("schedules a class for one of this Teacher's own courses, redirects to the detail page", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    const { store } = await mockClassSessionsApi(page);
    await mockJson(page, `**/v1/courses/${COURSE_ID}/modules`, 200, apiSuccess([]));

    await page.goto("/teacher/live-classes/new");

    await selectOption(page, "Course", "Intro to Biology");
    await page.getByLabel("Title", { exact: true }).fill("Midterm Review");
    await page.getByLabel("Start", { exact: true }).fill(futureDatetimeLocal(60));
    await page.getByLabel("End", { exact: true }).fill(futureDatetimeLocal(120));

    await page.getByRole("button", { name: "Schedule class" }).click();

    await expect(page.getByRole("heading", { name: "Midterm Review" })).toBeVisible();
    await expect(page.getByText("Live class scheduled.")).toBeVisible();
    expect(store).toHaveLength(1);
    expect(store[0].courseId).toBe(COURSE_ID);
  });

  test("only this Teacher's own courses appear in the course picker", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    // `GET /v1/courses` is server-scoped to the caller's own courses — the
    // mock here returns only one course, proving the picker renders exactly
    // what the (already-scoped) backend response contains, not a wider set
    // filtered client-side.
    await mockClassSessionsApi(page);

    await page.goto("/teacher/live-classes/new");
    await page.getByLabel("Course", { exact: true }).click();
    await expect(page.getByRole("option")).toHaveCount(1);
    await expect(page.getByRole("option", { name: "Intro to Biology" })).toBeVisible();
  });

  test("edits a SCHEDULED session's title and schedule window", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    const session = makeClassSession({ id: "session-1", status: "SCHEDULED" });
    await mockClassSessionsApi(page, { initialSessions: [session] });

    await page.goto(`/teacher/live-classes/${session.id}/edit`);
    const titleInput = page.getByLabel("Title", { exact: true });
    await titleInput.fill("");
    await titleInput.fill("Rescheduled Review Session");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page).toHaveURL(new RegExp(`/teacher/live-classes/${session.id}\\?updated=1`));
    await expect(page.getByRole("heading", { name: "Rescheduled Review Session" })).toBeVisible();
  });

  test("edit is unreachable once the session has left SCHEDULED — fields render disabled with an explanatory note", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    const session = makeClassSession({ id: "session-1", status: "LIVE" });
    await mockClassSessionsApi(page, { initialSessions: [session] });

    await page.goto(`/teacher/live-classes/${session.id}/edit`);

    await expect(page.getByText(/no longer Scheduled/)).toBeVisible();
    await expect(page.getByLabel("Title", { exact: true })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Save changes" })).toHaveCount(0);
  });

  test("a FAILED provisioning is retryable from the detail page and clears once retried", async ({ page }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    const session = makeClassSession({
      id: "session-1",
      status: "SCHEDULED",
      providerStatus: "FAILED",
      providerFailureReason: "Meeting provider request timed out",
    });
    await mockClassSessionsApi(page, { initialSessions: [session] });

    await page.goto(`/teacher/live-classes/${session.id}`);

    // `exact: true` avoids an ambiguous match against the separate failure-
    // reason Alert text ("Meeting provisioning failed: ..."), which also
    // contains this substring.
    await expect(page.getByText("Provisioning failed", { exact: true })).toBeVisible();
    await expect(page.getByText(/Meeting provider request timed out/)).toBeVisible();

    await page.getByRole("button", { name: "Retry provisioning" }).click();

    await expect(page.getByText("Meeting ready")).toBeVisible();
    await expect(page.getByRole("button", { name: "Retry provisioning" })).toHaveCount(0);
  });
});
