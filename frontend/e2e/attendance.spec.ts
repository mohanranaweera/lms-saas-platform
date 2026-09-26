import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  fulfillJson,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * MVP-016 "Attendance" — the five frontend screens (plan §11):
 *   1. Teacher Mark Attendance (`/teacher/attendance/mark`)
 *   2. Teacher Attendance Reports (`/teacher/attendance/reports`)
 *   3. Student My Attendance (`/student/attendance`)
 *   4. Tenant Admin/Attendance Operator/Read-only Auditor Reports
 *      (`/tenant-admin/attendance/reports`)
 *   5. Staff Mark Attendance (`/tenant-admin/attendance/mark`)
 *
 * No real backend runs in this environment (see `fixtures/auth-mocks.ts`'s
 * module doc) — every scenario mocks `/v1/**` responses shaped like the
 * documented `ApiResponse<T>` envelope. Wave 8: attendance is taken per
 * `ClassSession` (Course -> Class session cascade via `/v1/class-sessions`,
 * roster/records via `/v1/attendance/class-sessions/{id}/...`); `SESSION_ID`
 * below is therefore a class-session id.
 */

const COURSE_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
const SESSION_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc";
const STUDENT_1 = "11111111-1111-1111-1111-111111111111";
const STUDENT_2 = "22222222-2222-2222-2222-222222222222";

/**
 * Wave 8 summary reads (`/v1/attendance/summary`, `/v1/attendance/my/summary`)
 * default to an empty list so every screen renders without an unmocked
 * request; tests that exercise the summaries register their own (later, so
 * higher-priority) route.
 */
test.beforeEach(async ({ page }) => {
  await mockJson(page, "**/v1/attendance/summary*", 200, apiSuccess([]));
  await mockJson(page, "**/v1/attendance/my/summary*", 200, apiSuccess([]));
});

function summaryRowBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    studentId: STUDENT_1,
    studentName: "Ada Lovelace",
    courseId: COURSE_ID,
    courseName: "Intro to Biology",
    present: 2,
    late: 1,
    absent: 1,
    total: 4,
    attendanceRate: 75.0,
    ...overrides,
  };
}

function nowIso(): string {
  return new Date().toISOString();
}

function courseResponseBody() {
  return {
    id: COURSE_ID,
    teacherId: "teacher-1",
    name: "Intro to Biology",
    slug: "intro-to-biology",
    category: "Science",
    subject: null,
    stream: null,
    grade: null,
    academicYear: null,
    description: null,
    price: 49.99,
    accessDurationDays: null,
    enrollmentRule: null,
    status: "PUBLIC",
    createdAt: nowIso(),
    updatedAt: nowIso(),
  };
}

const SESSION_TITLE = "Week 1: Cells";
/** `MarkAttendancePanel` labels options "<title> — <date> (<status>)"; the date is locale-formatted, so match on the title. */
const SESSION_OPTION = /^Week 1: Cells/;

function classSessionBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: SESSION_ID,
    courseId: COURSE_ID,
    teacherId: "teacher-1",
    lessonId: null,
    title: SESSION_TITLE,
    description: null,
    scheduledStart: "2026-09-20T09:00:00.000Z",
    scheduledEnd: "2026-09-20T10:00:00.000Z",
    status: "COMPLETED",
    meetingProvider: "ZOOM",
    providerStatus: "PROVISIONED",
    providerFailureReason: null,
    createdAt: nowIso(),
    updatedAt: nowIso(),
    ...overrides,
  };
}

/** Mirrors `ClassSessionRosterResponse`; entries default to named, currently-enrolled students. */
function rosterBody(
  entries: Array<{ studentId: string; status: string | null; studentName?: string | null; currentlyEnrolled?: boolean }>,
  overrides: Partial<Record<string, unknown>> = {}
) {
  return {
    sheetId: null,
    classSessionId: SESSION_ID,
    courseId: COURSE_ID,
    title: SESSION_TITLE,
    scheduledStart: "2026-09-20T09:00:00.000Z",
    scheduledEnd: "2026-09-20T10:00:00.000Z",
    sessionStatus: "COMPLETED",
    markingOpen: true,
    markingClosedReason: null,
    roster: entries.map((entry) => ({ studentName: null, currentlyEnrolled: true, ...entry })),
    ...overrides,
  };
}

function attendanceRecordBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: `record-${Math.random().toString(36).slice(2)}`,
    courseId: COURSE_ID,
    sessionId: null,
    sheetId: "sheet-1",
    source: "CLASS_SESSION",
    classSessionId: SESSION_ID,
    classSessionTitle: SESSION_TITLE,
    studentId: STUDENT_1,
    status: "PRESENT",
    markedBy: "teacher-1",
    markedAt: nowIso(),
    createdAt: nowIso(),
    updatedAt: nowIso(),
    ...overrides,
  };
}

/** Establishes a session for the given role by mocking `POST /v1/auth/refresh`, so `RouteGuard` resolves `ready` on direct navigation. */
async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

/** Mocks the Course -> Class session cascade `MarkAttendancePanel` (and the reports pages' course filter) reads. */
async function mockCourseCascade(page: Page, sessionOverrides: Partial<Record<string, unknown>> = {}): Promise<void> {
  await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
  await mockJson(page, "**/v1/class-sessions*", 200, apiSuccess([classSessionBody(sessionOverrides)]));
}

async function selectOption(page: Page, labelName: string, optionName: string | RegExp): Promise<void> {
  await page.getByLabel(labelName, { exact: true }).click();
  await page.getByRole("option", { name: optionName }).click();
}

/**
 * `.claude/rules/ui-ux.md` §5: "the page body must never scroll
 * horizontally". `+ 1` tolerates the 1px rounding some browsers introduce
 * between `scrollWidth`/`clientWidth` at fractional device-pixel-ratio
 * widths, not a meaningful overflow allowance.
 */
async function pageHasNoHorizontalOverflow(page: Page): Promise<boolean> {
  return page.evaluate(
    () => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1
  );
}

test.describe("Teacher Mark Attendance (screen #1)", () => {
  test("cascade select course -> class session, mark roster, submit with a partial batch failure surfaced per row, reload and verify the persisted status", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockCourseCascade(page);
    // Stateful roster mock: tracks each student's last-marked status in a
    // closure so a post-submit refetch (whether via `useMarkAttendance`'s own
    // cache invalidation, or a genuine full-page reload below) actually
    // proves persistence, rather than a single static response that could
    // pass even if nothing was saved server-side.
    const rosterStatuses: Record<string, string | null> = {
      [STUDENT_1]: null,
      [STUDENT_2]: null,
    };
    await page.route(`**/v1/attendance/class-sessions/${SESSION_ID}/roster`, async (route) => {
      await fulfillJson(
        route,
        200,
        apiSuccess(rosterBody([
            { studentId: STUDENT_1, status: rosterStatuses[STUDENT_1] },
            { studentId: STUDENT_2, status: rosterStatuses[STUDENT_2] },
          ]))
      );
    });
    await page.route(`**/v1/attendance/class-sessions/${SESSION_ID}/records`, async (route) => {
      const body = route.request().postDataJSON() as {
        marks: Array<{ studentId: string; status: string }>;
      };
      const results = body.marks.map((mark) => {
        if (mark.studentId === STUDENT_1) {
          rosterStatuses[STUDENT_1] = mark.status;
          return {
            studentId: mark.studentId,
            success: true,
            record: attendanceRecordBody({ studentId: mark.studentId, status: mark.status }),
            reason: null,
          };
        }
        return { studentId: mark.studentId, success: false, record: null, reason: "Session is locked." };
      });
      await fulfillJson(route, 200, apiSuccess(results));
    });

    await page.goto("/teacher/attendance/mark");

    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);

    await expect(page.getByRole("heading", { name: SESSION_TITLE })).toBeVisible();

    const student1Label = `Student #${STUDENT_1.slice(0, 8)}`;
    const student2Label = `Student #${STUDENT_2.slice(0, 8)}`;
    await page.getByRole("radio", { name: `Present — ${student1Label}` }).click();
    await page.getByRole("radio", { name: `Absent — ${student2Label}` }).click();

    await page.getByRole("button", { name: /^Save attendance/ }).click();

    // Partial-failure outcomes render as one consolidated assertive
    // announcement (`failureAnnouncement`, role="alert") rather than a
    // separate polite success summary plus per-row alerts — see
    // `mark-attendance-panel.tsx`'s own comment on why N simultaneous
    // per-row alerts is an announcement-storm risk for screen readers.
    await expect(
      page.getByText(`1 of 2 saved. Failed: ${student2Label} (Session is locked.).`)
    ).toBeVisible();
    // The roster row's own inline failure text stays visible (visual-only,
    // no separate role="alert") alongside the consolidated announcement.
    await expect(
      page.getByText(`Could not save ${student2Label}: Session is locked.`)
    ).toBeVisible();

    // Reload the whole page (all client state, including React Query's
    // cache, is gone) and redrive the cascade — the roster mock above is
    // registered on `page` and survives navigation, so if student 1's mark
    // actually persisted "server-side" the reselected roster must come back
    // already showing Present, not "Not marked".
    await page.reload();
    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);

    await expect(page.getByRole("heading", { name: SESSION_TITLE })).toBeVisible();
    await expect(page.getByRole("radio", { name: `Present — ${student1Label}` })).toHaveAttribute(
      "aria-checked",
      "true"
    );
    await expect(page.getByRole("radio", { name: `Absent — ${student2Label}` })).toHaveAttribute(
      "aria-checked",
      "false"
    );
  });

  test("empty state when the roster has no enrolled students", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockCourseCascade(page);
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/roster`,
      200,
      apiSuccess(rosterBody([]))
    );

    await page.goto("/teacher/attendance/mark");
    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);

    await expect(page.getByText("No students enrolled")).toBeVisible();
  });

  test("empty state when the selected course has no class sessions yet", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
    await mockJson(page, "**/v1/class-sessions*", 200, apiSuccess([]));

    await page.goto("/teacher/attendance/mark");
    await selectOption(page, "Course", "Intro to Biology");

    await expect(page.getByText("No class sessions scheduled for this course")).toBeVisible();
  });

  test("a cancelled session shows why marking is closed and offers no editable controls or save button", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockCourseCascade(page, { status: "CANCELLED" });
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/roster`,
      200,
      apiSuccess(
        rosterBody([{ studentId: STUDENT_1, status: "PRESENT", studentName: "Ada Lovelace" }], {
          sessionStatus: "CANCELLED",
          markingOpen: false,
          markingClosedReason: "Attendance cannot be recorded for a cancelled class session.",
        })
      )
    );

    await page.goto("/teacher/attendance/mark");
    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);

    await expect(page.getByText("Attendance cannot be recorded for a cancelled class session.")).toBeVisible();
    await expect(page.getByText("Ada Lovelace")).toBeVisible();
    await expect(page.getByRole("radio", { name: /Ada Lovelace/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Save attendance/ })).toHaveCount(0);
  });

  test("a previously marked student who is no longer enrolled stays visible as a read-only row", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockCourseCascade(page);
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/roster`,
      200,
      apiSuccess(
        rosterBody([
          { studentId: STUDENT_1, status: null, studentName: "Ada Lovelace" },
          { studentId: STUDENT_2, status: "LATE", studentName: "Alan Turing", currentlyEnrolled: false },
        ])
      )
    );

    await page.goto("/teacher/attendance/mark");
    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);

    await expect(page.getByRole("radio", { name: "Present — Ada Lovelace" })).toBeVisible();
    await expect(page.getByText("No longer enrolled")).toBeVisible();
    await expect(page.getByRole("radio", { name: /Alan Turing/ })).toHaveCount(0);
  });

  test("a 409 from the records mutation (session not started) is surfaced as an error, nothing marked as saved", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockCourseCascade(page);
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/roster`,
      200,
      apiSuccess(rosterBody([{ studentId: STUDENT_1, status: null, studentName: "Ada Lovelace" }]))
    );
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/records`,
      409,
      apiError("CONFLICT", "Attendance can be recorded once the class session has started.")
    );

    await page.goto("/teacher/attendance/mark");
    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);
    await page.getByRole("radio", { name: "Present — Ada Lovelace" }).click();
    await page.getByRole("button", { name: /^Save attendance/ }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "Attendance can be recorded once the class session has started." })
    ).toBeVisible();
    await expect(page.getByText(/attendance record(s)? saved/)).toHaveCount(0);
  });

  test("permission-denied state on a real 403 from the courses read", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(
      page,
      "**/v1/courses*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view these courses.")
    );

    await page.goto("/teacher/attendance/mark");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(denied.getByRole("link", { name: "Back to your dashboard" })).toHaveAttribute(
      "href",
      "/teacher/dashboard"
    );
  });

  test("permission-denied state on a real 403 from the roster read itself, not just an upstream courses read", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockCourseCascade(page);
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/roster`,
      403,
      apiError("FORBIDDEN", "You do not have permission to view this roster.")
    );

    await page.goto("/teacher/attendance/mark");
    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(denied.getByRole("link", { name: "Back to your dashboard" })).toHaveAttribute(
      "href",
      "/teacher/dashboard"
    );
  });

  test("a cross-tenant sessionId resolves to a 404 from the roster read, rendering the generic error state (not permission-denied)", async ({
    page,
  }) => {
    // Mirrors "Staff Mark Attendance (screen #5)"'s equivalent test exactly
    // (post-ship review Finding 4, MVP-016 plan §22) - both code paths share
    // the same `MarkAttendancePanel` component and the same
    // `classifyQueryError` classification, but each screen's own describe
    // block previously proved only one of the two error paths (this one had
    // only the same-tenant/not-owner 403 case, screen #5 only the
    // cross-tenant 404 case) - closes the 1:1 plan-checklist mapping gap.
    await mockTenantSession(page, "TEACHER");
    await mockCourseCascade(page);
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/roster`,
      404,
      apiError("NOT_FOUND", "Session not found.")
    );

    await page.goto("/teacher/attendance/mark");
    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);

    // `classifyQueryError`'s documented invariant: only a real 403 renders
    // `PermissionDeniedState`. A 404 (the anti-enumeration response a
    // cross-tenant sessionId actually gets, per plan §13/§15) falls through
    // to the generic `ErrorState` instead — asserting the wrong classification
    // here would make this test pass for the wrong reason.
    const errorAlert = page.getByRole("alert").filter({ hasText: "Session not found." });
    await expect(errorAlert).toBeVisible();
    await expect(errorAlert.getByRole("button", { name: "Try again" })).toBeVisible();
    await expect(page.getByText("You don't have permission")).toHaveCount(0);
  });
});

test.describe("Staff Mark Attendance (screen #5)", () => {
  test("Attendance Operator can mark attendance via the same panel at the staff route", async ({
    page,
  }) => {
    await mockTenantSession(page, "ATTENDANCE_OPERATOR");
    await mockCourseCascade(page);
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/roster`,
      200,
      apiSuccess(rosterBody([{ studentId: STUDENT_1, status: null }]))
    );

    await page.goto("/tenant-admin/attendance/mark");
    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);

    await expect(page.getByRole("heading", { name: SESSION_TITLE })).toBeVisible();
  });

  test("a Read-only Auditor hitting this route directly gets a real backend 403, not a hidden UI", async ({
    page,
  }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockJson(
      page,
      "**/v1/courses*",
      403,
      apiError("FORBIDDEN", "You do not have permission to mark attendance.")
    );

    await page.goto("/tenant-admin/attendance/mark");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
  });

  test("a Read-only Auditor hitting the roster endpoint itself (past a successful cascade) gets a real backend 403", async ({
    page,
  }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockCourseCascade(page);
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/roster`,
      403,
      apiError("FORBIDDEN", "You do not have permission to view this roster.")
    );

    await page.goto("/tenant-admin/attendance/mark");
    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
  });

  test("a cross-tenant sessionId resolves to a 404 from the roster read, rendering the generic error state (not permission-denied)", async ({
    page,
  }) => {
    await mockTenantSession(page, "ATTENDANCE_OPERATOR");
    await mockCourseCascade(page);
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/roster`,
      404,
      apiError("NOT_FOUND", "Session not found.")
    );

    await page.goto("/tenant-admin/attendance/mark");
    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);

    // `classifyQueryError`'s documented invariant: only a real 403 renders
    // `PermissionDeniedState`. A 404 (the anti-enumeration response a
    // cross-tenant sessionId actually gets, per plan §13/§15) falls through
    // to the generic `ErrorState` instead — asserting the wrong classification
    // here would make this test pass for the wrong reason.
    const errorAlert = page.getByRole("alert").filter({ hasText: "Session not found." });
    await expect(errorAlert).toBeVisible();
    await expect(errorAlert.getByRole("button", { name: "Try again" })).toBeVisible();
    await expect(page.getByText("You don't have permission")).toHaveCount(0);
  });

  test("a Read-only Auditor's replayed submit is rejected by a real backend 403 on the records mutation itself", async ({
    page,
  }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockCourseCascade(page);
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/roster`,
      200,
      apiSuccess(rosterBody([{ studentId: STUDENT_1, status: null }]))
    );
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/records`,
      403,
      apiError("FORBIDDEN", "You do not have permission to mark attendance.")
    );

    await page.goto("/tenant-admin/attendance/mark");
    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);

    const student1Label = `Student #${STUDENT_1.slice(0, 8)}`;
    await page.getByRole("radio", { name: `Present — ${student1Label}` }).click();
    await page.getByRole("button", { name: /^Save attendance/ }).click();

    const submitAlert = page
      .getByRole("alert")
      .filter({ hasText: "You do not have permission to mark attendance." });
    await expect(submitAlert).toBeVisible();
  });
});

test.describe("Teacher Attendance Reports (screen #2)", () => {
  test("applying a course filter shows the per-student attendance summary with the server-computed rate", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
    await mockJson(page, "**/v1/attendance/reports*", 200, apiPageSuccess([attendanceRecordBody()]));
    const summaryQueries: string[] = [];
    await page.route("**/v1/attendance/summary*", async (route) => {
      summaryQueries.push(new URL(route.request().url()).search);
      await fulfillJson(route, 200, apiSuccess([summaryRowBody()]));
    });

    await page.goto("/teacher/attendance/reports");
    await expect(page.getByText(SESSION_TITLE).first()).toBeVisible();
    await expect(page.getByText("Select one of your courses in the filter")).toBeVisible();

    await selectOption(page, "Course", "Intro to Biology");
    await page.getByRole("button", { name: "Apply filters" }).click();

    const summary = page.getByRole("region", { name: "Attendance summary" });
    await expect(summary.getByText("Ada Lovelace").first()).toBeVisible();
    await expect(summary.getByText("75.0%").first()).toBeVisible();
    expect(summaryQueries.some((q) => q.includes(`courseId=${COURSE_ID}`))).toBe(true);
  });

  test("loading, populated rows, then applying a filter narrows the request", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));

    let releaseReports: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      releaseReports = resolve;
    });
    const seenQueries: string[] = [];
    await page.route("**/v1/attendance/reports*", async (route) => {
      seenQueries.push(new URL(route.request().url()).search);
      await gate;
      await fulfillJson(
        route,
        200,
        apiPageSuccess([attendanceRecordBody({ status: "PRESENT" })])
      );
    });

    await page.goto("/teacher/attendance/reports");

    const status = page.getByRole("status").filter({ hasText: "Loading attendance records…" });
    await expect(status).toBeVisible();
    await expect(status).toHaveAttribute("aria-busy", "true");

    releaseReports?.();
    await expect(page.getByText("Present")).toBeVisible();

    await page.route("**/v1/attendance/reports*", async (route) => {
      seenQueries.push(new URL(route.request().url()).search);
      await fulfillJson(route, 200, apiPageSuccess([]));
    });
    await selectOption(page, "Course", "Intro to Biology");
    await page.getByRole("button", { name: "Apply filters" }).click();

    const emptyState = page.getByRole("status").filter({ hasText: "No sessions match" });
    await expect(emptyState).toBeVisible();
    await expect(emptyState.getByRole("button", { name: "Clear filters" })).toBeVisible();
    expect(seenQueries.some((q) => q.includes(`courseId=${COURSE_ID}`))).toBe(true);
  });

  test("true empty state (no filters applied) uses distinct 'no records yet' copy", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
    await mockJson(page, "**/v1/attendance/reports*", 200, apiPageSuccess([]));

    await page.goto("/teacher/attendance/reports");

    const emptyState = page.getByRole("status").filter({ hasText: "No attendance records yet" });
    await expect(emptyState).toBeVisible();
    await expect(emptyState.getByRole("button", { name: "Clear filters" })).toHaveCount(0);
  });

  test("a failed read shows a retryable error, and Retry recovers", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
    await mockJson(
      page,
      "**/v1/attendance/reports*",
      500,
      apiError("INTERNAL_ERROR", "Could not load attendance records.")
    );

    await page.goto("/teacher/attendance/reports");

    const errorAlert = page
      .getByRole("alert")
      .filter({ hasText: "Could not load attendance records." });
    await expect(errorAlert).toBeVisible();

    await mockJson(page, "**/v1/attendance/reports*", 200, apiPageSuccess([]));
    await errorAlert.getByRole("button", { name: "Try again" }).click();

    await expect(page.getByText("No attendance records yet")).toBeVisible();
  });

  test("permission-denied state on a real 403", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
    await mockJson(
      page,
      "**/v1/attendance/reports*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view attendance reports.")
    );

    await page.goto("/teacher/attendance/reports");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
  });
});

test.describe("Student My Attendance (screen #3)", () => {
  test("shows own per-course attendance rate cards and labels records by class session", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/enrollments/my/courses", 200, apiSuccess([courseResponseBody()]));
    await mockJson(page, "**/v1/attendance/my/summary*", 200, apiSuccess([summaryRowBody()]));
    await mockJson(
      page,
      "**/v1/attendance/my*",
      200,
      apiPageSuccess([
        attendanceRecordBody({ status: "PRESENT" }),
        attendanceRecordBody({
          status: "ABSENT",
          source: "LEGACY_LESSON",
          sessionId: "dddddddd-dddd-dddd-dddd-dddddddddddd",
          classSessionId: null,
          classSessionTitle: null,
        }),
      ])
    );

    await page.goto("/student/attendance");

    const summary = page.getByRole("region", { name: "Attendance by course" });
    await expect(summary.getByText("75.0%")).toBeVisible();
    await expect(summary.getByText("2 present · 1 late · 1 absent (4 marked)")).toBeVisible();
    await expect(page.getByText(new RegExp(`^${SESSION_TITLE} · Marked`))).toBeVisible();
    await expect(page.getByText(/Lesson #dddddddd \(legacy\) · Marked/)).toBeVisible();
  });

  test("renders own attendance history and degrades gracefully when the course-summary lookup fails", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      "**/v1/enrollments/my/courses",
      500,
      apiError("INTERNAL_ERROR", "Could not load course summaries.")
    );
    await mockJson(
      page,
      "**/v1/attendance/my*",
      200,
      apiPageSuccess([attendanceRecordBody({ studentId: STUDENT_1, status: "LATE" })])
    );

    await page.goto("/student/attendance");

    await expect(page.getByText("Late")).toBeVisible();
    // Course filter degrades to "All courses" only, never a blocking error.
    await expect(page.getByLabel("Course", { exact: true })).toBeVisible();
    await expect(page.getByRole("main").getByRole("alert")).toHaveCount(0);
  });

  test("true empty state (no records yet) uses distinct 'no records yet' copy", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/v1/attendance/my*", 200, apiPageSuccess([]));

    await page.goto("/student/attendance");

    const emptyState = page.getByRole("status").filter({ hasText: "No attendance records yet" });
    await expect(emptyState).toBeVisible();
    await expect(emptyState.getByRole("button", { name: "Clear filters" })).toHaveCount(0);
  });

  test("filtered-zero-results empty state shows 'No sessions match the selected date filter' with a working Clear filters action", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/enrollments/my/courses", 200, apiSuccess([courseResponseBody()]));
    await mockJson(
      page,
      "**/v1/attendance/my*",
      200,
      apiPageSuccess([attendanceRecordBody({ studentId: STUDENT_1, status: "PRESENT" })])
    );

    await page.goto("/student/attendance");
    await expect(page.getByText("Present")).toBeVisible();

    await mockJson(page, "**/v1/attendance/my*", 200, apiPageSuccess([]));
    await selectOption(page, "Course", "Intro to Biology");
    await page.getByRole("button", { name: "Apply filters" }).click();

    const emptyState = page.getByRole("status").filter({ hasText: "No sessions match" });
    await expect(emptyState).toBeVisible();
    await expect(emptyState.getByRole("button", { name: "Clear filters" })).toBeVisible();
  });

  test("permission-denied state on a real 403", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(
      page,
      "**/v1/attendance/my*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view this attendance history.")
    );

    await page.goto("/student/attendance");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(denied.getByRole("link", { name: "Back to your dashboard" })).toHaveAttribute(
      "href",
      "/student/dashboard"
    );
  });
});

test.describe("Tenant Admin Attendance Reports (screen #4)", () => {
  test("shows skeleton rows while loading, then a DataTable with no mutating controls", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));

    let releaseReports: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      releaseReports = resolve;
    });
    await page.route("**/v1/attendance/reports*", async (route) => {
      await gate;
      await fulfillJson(
        route,
        200,
        apiPageSuccess([attendanceRecordBody({ studentId: STUDENT_1, status: "ABSENT" })])
      );
    });

    await page.goto("/tenant-admin/attendance/reports");

    const loadingAnnouncement = page.getByText("Loading attendance reports…");
    await expect(loadingAnnouncement).toBeAttached();
    await expect(page.getByRole("table")).toHaveCount(0);

    releaseReports?.();
    const table = page.getByRole("table");
    await expect(table).toBeVisible();
    await expect(table.getByText(`Student #${STUDENT_1.slice(0, 8)}`)).toBeVisible();
    await expect(table.getByText("Absent")).toBeVisible();

    // Report-only screen: no mark/edit affordance anywhere.
    await expect(page.getByRole("button", { name: /^Save attendance/ })).toHaveCount(0);
    await expect(page.getByRole("radiogroup")).toHaveCount(0);
  });

  test("Read-only Auditor sees the identical read-only report (no mutating controls) as Tenant Admin", async ({
    page,
  }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
    await mockJson(
      page,
      "**/v1/attendance/reports*",
      200,
      apiPageSuccess([attendanceRecordBody({ studentId: STUDENT_1, status: "PRESENT" })])
    );

    await page.goto("/tenant-admin/attendance/reports");

    await expect(page.getByRole("table")).toBeVisible();
    await expect(page.getByRole("button", { name: /^Save attendance/ })).toHaveCount(0);
    await expect(page.getByRole("radiogroup")).toHaveCount(0);
  });

  test("a 403 renders PermissionDeniedState for a role without ATTENDANCE/VIEW", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
    await mockJson(
      page,
      "**/v1/attendance/reports*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view attendance reports.")
    );

    await page.goto("/tenant-admin/attendance/reports");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
  });

  test("true empty state (no records yet) uses distinct 'no records yet' copy", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
    await mockJson(page, "**/v1/attendance/reports*", 200, apiPageSuccess([]));

    await page.goto("/tenant-admin/attendance/reports");

    const emptyState = page.getByRole("status").filter({ hasText: "No attendance records yet" });
    await expect(emptyState).toBeVisible();
    await expect(emptyState.getByRole("button", { name: "Clear filters" })).toHaveCount(0);
    await expect(page.getByRole("table")).toHaveCount(0);
  });

  test("filtered-zero-results empty state shows 'No sessions match the selected date filter' with a working Clear filters action", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
    await mockJson(
      page,
      "**/v1/attendance/reports*",
      200,
      apiPageSuccess([attendanceRecordBody({ studentId: STUDENT_1, status: "ABSENT" })])
    );

    await page.goto("/tenant-admin/attendance/reports");
    await expect(page.getByRole("table")).toBeVisible();

    await mockJson(page, "**/v1/attendance/reports*", 200, apiPageSuccess([]));
    await selectOption(page, "Course", "Intro to Biology");
    await page.getByRole("button", { name: "Apply filters" }).click();

    const emptyState = page.getByRole("status").filter({ hasText: "No sessions match" });
    await expect(emptyState).toBeVisible();
    await expect(emptyState.getByRole("button", { name: "Clear filters" })).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
  });
});

test.describe("Attendance nav visibility", () => {
  test("Teacher nav shows Mark Attendance and Attendance Reports", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));

    await page.goto("/teacher/dashboard");

    await expect(page.getByRole("link", { name: "Mark Attendance" })).toHaveAttribute(
      "href",
      "/teacher/attendance/mark"
    );
    await expect(page.getByRole("link", { name: "Attendance Reports" })).toHaveAttribute(
      "href",
      "/teacher/attendance/reports"
    );
  });

  test("Student nav shows My Attendance", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/enrollments/my", 200, apiSuccess([]));

    await page.goto("/student/dashboard");

    await expect(page.getByRole("link", { name: "My Attendance" })).toHaveAttribute(
      "href",
      "/student/attendance"
    );
  });

  test("Tenant Admin nav shows both attendance entries; Attendance Operator shows both; Read-only Auditor shows reports only; unrelated staff role shows neither", async ({
    page,
  }) => {
    async function mockDashboardReads() {
      await mockJson(page, "**/v1/students", 200, apiSuccess([]));
      await mockJson(page, "**/api/v1/ledger/dashboard*", 200, apiPageSuccess([]));
      await page.route("**/api/v1/courses*", async (route) => {
        await fulfillJson(route, 200, apiPageSuccess([]));
      });
    }
    await mockDashboardReads();

    await mockTenantSession(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/dashboard");
    await expect(page.getByRole("link", { name: "Attendance Reports" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Mark Attendance" })).toBeVisible();

    await mockTenantSession(page, "ATTENDANCE_OPERATOR");
    await page.goto("/tenant-admin/dashboard");
    await expect(page.getByRole("link", { name: "Attendance Reports" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Mark Attendance" })).toBeVisible();

    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await page.goto("/tenant-admin/dashboard");
    await expect(page.getByRole("link", { name: "Attendance Reports" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Mark Attendance" })).toHaveCount(0);

    await mockTenantSession(page, "FINANCE_STAFF");
    await page.goto("/tenant-admin/dashboard");
    await expect(page.getByRole("link", { name: "Attendance Reports" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Mark Attendance" })).toHaveCount(0);
  });
});

/**
 * Narrow-viewport coverage (plan §18: "Mobile-first Student/Teacher views and
 * the responsive admin data-table view are each tested at a narrow viewport
 * (375×667 or equivalent)"). Additive to the scenarios above — these don't
 * re-cover loading/empty/error/permission-denied, they assert the actual
 * layout consequence of the two responsive strategies plan §11 assigns to
 * this module's five screens:
 *   - Teacher Mark/Reports and Student My Attendance are "consumer-style,
 *     mobile-first ... no `DataTable`" — at 375px this means the page's
 *     core content and controls stay visible/reachable and the page body
 *     never scrolls horizontally (`.claude/rules/ui-ux.md` §5).
 *   - Tenant Admin Reports uses the shared `DataTable`, which renders both a
 *     `<table>` (`.hidden md:block`) and a `<ul aria-label=...>` card list in
 *     the DOM simultaneously, swapped by CSS per breakpoint — at 375px the
 *     card list must be the one actually visible, not the table.
 * `page.setViewportSize` before `page.goto`, matching the established
 * pattern in `platform-admin-tenants.spec.ts` / `student-management.spec.ts`.
 */
test.describe("Teacher Mark Attendance — narrow viewport (375x667)", () => {
  test("cascade selects and the roster's segmented controls stay visible and clickable, with no horizontal page overflow", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await mockTenantSession(page, "TEACHER");
    await mockCourseCascade(page);
    await mockJson(
      page,
      `**/v1/attendance/class-sessions/${SESSION_ID}/roster`,
      200,
      apiSuccess(rosterBody([{ studentId: STUDENT_1, status: null }]))
    );

    await page.goto("/teacher/attendance/mark");

    await selectOption(page, "Course", "Intro to Biology");
    await selectOption(page, "Class session", SESSION_OPTION);

    await expect(page.getByRole("heading", { name: SESSION_TITLE })).toBeVisible();

    const student1Label = `Student #${STUDENT_1.slice(0, 8)}`;
    const presentRadio = page.getByRole("radio", { name: `Present — ${student1Label}` });
    await expect(presentRadio).toBeVisible();
    await presentRadio.click();
    await expect(presentRadio).toHaveAttribute("aria-checked", "true");

    expect(await pageHasNoHorizontalOverflow(page)).toBe(true);
  });
});

test.describe("Teacher Attendance Reports — narrow viewport (375x667)", () => {
  test("report rows and filter controls stay visible and clickable, with no horizontal page overflow", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
    await mockJson(
      page,
      "**/v1/attendance/reports*",
      200,
      apiPageSuccess([attendanceRecordBody({ status: "PRESENT" })])
    );

    await page.goto("/teacher/attendance/reports");

    await expect(page.getByText("Present")).toBeVisible();

    await expect(page.getByLabel("Course", { exact: true })).toBeVisible();
    await expect(page.getByLabel("From", { exact: true })).toBeVisible();
    await expect(page.getByLabel("To", { exact: true })).toBeVisible();
    const applyButton = page.getByRole("button", { name: "Apply filters" });
    await expect(applyButton).toBeVisible();
    await applyButton.click();

    expect(await pageHasNoHorizontalOverflow(page)).toBe(true);
  });
});

test.describe("Student My Attendance — narrow viewport (375x667)", () => {
  test("attendance history and filter controls stay visible and clickable, with no horizontal page overflow", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(
      page,
      "**/v1/attendance/my*",
      200,
      apiPageSuccess([attendanceRecordBody({ studentId: STUDENT_1, status: "LATE" })])
    );

    await page.goto("/student/attendance");

    await expect(page.getByText("Late")).toBeVisible();
    await expect(page.getByLabel("Course", { exact: true })).toBeVisible();
    const clearButton = page.getByRole("button", { name: "Clear filters" });
    await expect(clearButton).toBeVisible();
    await clearButton.click();

    expect(await pageHasNoHorizontalOverflow(page)).toBe(true);
  });
});

test.describe("Tenant Admin Attendance Reports — narrow viewport (375x667)", () => {
  test("below md, the report renders as the DataTable's card list, not the desktop table, with no horizontal page overflow", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
    await mockJson(
      page,
      "**/v1/attendance/reports*",
      200,
      apiPageSuccess([attendanceRecordBody({ studentId: STUDENT_1, status: "ABSENT" })])
    );

    await page.goto("/tenant-admin/attendance/reports");

    // The desktop table wrapper (`.hidden.md:block` in data-table.tsx) exists
    // in the DOM but must not be the visible rendering at this width.
    await expect(page.getByRole("table")).toBeHidden();

    const cardList = page.getByRole("list", { name: "Attendance reports" });
    await expect(cardList).toBeVisible();
    await expect(cardList.getByText(`Student #${STUDENT_1.slice(0, 8)}`)).toBeVisible();
    await expect(cardList.getByText("Absent")).toBeVisible();

    expect(await pageHasNoHorizontalOverflow(page)).toBe(true);
  });
});
