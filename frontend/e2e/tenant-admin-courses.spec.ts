import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  loginResponseBody,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";
import { openCourseActions } from "./fixtures/klass/course-row-menu";

/**
 * Tenant Admin course list (`/tenant-admin/courses`) and course workspace
 * (`/tenant-admin/courses/[courseId]/**`, Wave 2's tabbed restructuring —
 * Overview/Fees & Billing/Settings/Access/placeholders, see
 * `components/courses/course-workspace-shell.tsx`) coverage. No real backend
 * runs in this environment (see `fixtures/auth-mocks.ts`) — every test logs
 * in through a mocked `POST /v1/auth/login` and intercepts
 * `/api/v1/courses/**` directly.
 */

const BASE_COURSES = [
  {
    id: "11111111-1111-1111-1111-111111111111",
    teacherId: "aaaaaaaa-1111-2222-3333-444444444444",
    name: "Intro to Biology",
    slug: "intro-to-biology",
    category: "Science",
    subject: "Biology",
    stream: null,
    grade: "Grade 9",
    academicYear: "2026",
    description: "A beginner biology course.",
    price: 49.99,
    accessDurationDays: 180,
    enrollmentRule: "Open enrollment",
    status: "PUBLIC",
    pricingModel: "ONE_TIME",
    archivedAt: null,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-02T00:00:00Z",
  },
  {
    id: "22222222-2222-2222-2222-222222222222",
    teacherId: "bbbbbbbb-1111-2222-3333-444444444444",
    name: "Advanced Calculus",
    slug: "advanced-calculus",
    category: "Mathematics",
    subject: null,
    stream: null,
    grade: null,
    academicYear: null,
    description: null,
    price: 99.5,
    accessDurationDays: null,
    enrollmentRule: null,
    status: "DRAFT",
    pricingModel: "ONE_TIME",
    archivedAt: null,
    createdAt: "2026-01-03T00:00:00Z",
    updatedAt: "2026-01-03T00:00:00Z",
  },
];

const SECOND_TEACHER_USER_ID = "cccccccc-1111-2222-3333-444444444444";

/** Teacher picker options (`GET /v1/teachers`): the two course owners plus a second active teacher. */
const TEACHERS = [
  { id: "teacher-row-a", userId: "aaaaaaaa-1111-2222-3333-444444444444", name: "Teacher Alpha", approvalStatus: "APPROVED", accountStatus: "ACTIVE" },
  { id: "teacher-row-b", userId: "bbbbbbbb-1111-2222-3333-444444444444", name: "Teacher Bravo", approvalStatus: "APPROVED", accountStatus: "ACTIVE" },
  { id: "teacher-row-c", userId: SECOND_TEACHER_USER_ID, name: "Teacher Charlie", approvalStatus: "APPROVED", accountStatus: "ACTIVE" },
];

/** R2: the Klass course edit form reads these `CourseResponse` fields (plan §4.1). */
function withKlassFields<T extends { teacherId: string; price: number; pricingModel: string; accessDurationDays: number | null }>(
  course: T
) {
  const owner = TEACHERS.find((teacher) => teacher.userId === course.teacherId);
  return {
    ...course,
    teachers: [{ id: course.teacherId, name: owner?.name ?? "Teacher", primary: true }],
    tags: [] as { id: string; name: string }[],
    linkedCourses: [] as { id: string; title: string }[],
    priceDisplay: { amount: course.price, currency: "USD", period: null },
    enrollmentFee: "0.00",
    paymentValidDays: course.accessDurationDays,
    commissionRate: null,
    whatsappInviteUrl: null,
    telegramInviteUrl: null,
    coverImageUrl: null,
    registrationEnabled: true,
    displayOrder: null,
    timezone: "Asia/Colombo",
  };
}

const COURSES = BASE_COURSES.map(withKlassFields);

async function loginAsTenantAdmin(page: Page) {
  const token = fakeJwt({ role: "TENANT_ADMIN" });
  await mockJson(page, "**/v1/auth/login", 200, apiSuccess(loginResponseBody(token)));
  // This flow always lands on `/tenant-admin/dashboard` first (per
  // `DASHBOARD_PATH_BY_ROLE`) before any test navigates on to Courses. As of
  // MVP-015 TADASH-1, that dashboard fires its own `GET /v1/students`, `GET
  // /api/v1/courses*` (always), and `GET /api/v1/ledger/dashboard*` (Tenant
  // Admin holds `PAYMENTS_SLIPS`/`VIEW`) reads — mock all three here (zero/
  // empty responses) so this helper's transient landing on the dashboard
  // never fires a live, unmocked request. Registered before any test's own
  // `**/v1/courses*` mock, so a test-specific mock registered afterward
  // (Playwright routes take precedence in most-recently-registered order)
  // still wins for that test's own Courses-page navigation — mirrors
  // `student-management.spec.ts`'s already-fixed `loginAs` helper.
  await mockJson(page, "**/v1/students", 200, apiSuccess([]));
  await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));
  await mockJson(page, "**/api/v1/ledger/dashboard*", 200, apiPageSuccess([]));
  // R2: the course edit form reads the teacher picker options and tenant tags.
  await mockJson(page, "**/v1/teachers", 200, apiSuccess(TEACHERS));
  await mockJson(page, "**/v1/course-tags", 200, apiSuccess([]));
  await page.goto("/login");
  await page.getByLabel("Email").fill("admin@example.com");
  await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/tenant-admin\/dashboard$/);
}

/**
 * Navigates from the tenant admin course list to a given course's workspace
 * (Overview tab) via the app's own client-side links (never `page.goto`,
 * which would force a full browser navigation and drop the in-memory access
 * token — see `loginAsTenantAdmin`'s sibling comment above and
 * `lib/auth/auth-context.tsx`). Deliberately makes no assertion about the
 * detail page's rendered content — callers assert whatever their scenario
 * (success, 403, etc.) actually needs.
 */
async function gotoCourseDetail(page: Page, course: { id: string; name: string }) {
  await page.getByRole("link", { name: "Courses" }).click();
  await expect(page).toHaveURL(/\/tenant-admin\/courses$/);
  // R2 (G5): row actions live in the "Actions for <name>" overflow menu.
  const menu = await openCourseActions(page, page.getByTestId("course-row").filter({ hasText: course.name }));
  await menu.getByTestId("course-action-edit").click();
  await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${course.id}$`));
}

/** Navigates from the (already-open) course workspace to a given tab. */
async function gotoTab(page: Page, tabLabel: string) {
  await page.getByRole("navigation", { name: "Course sections" }).getByRole("link", { name: tabLabel, exact: true }).click();
}

test.describe("tenant admin course list — empty state", () => {
  test("shows a tenant-admin-specific empty state with an Add course CTA", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));

    await page.getByRole("link", { name: "Courses" }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/courses$/);

    // KLASS (CM-A01): new contextual copy for the Active tab's zero-data state.
    const empty = page.getByTestId("courses-empty");
    await expect(empty.getByText("No active courses yet")).toBeVisible();
    // Must not reuse the Teacher "My Courses" empty-state copy.
    await expect(page.getByText("No assigned courses yet")).toHaveCount(0);
    // The create CTA is now the in-page "Add course" modal trigger (EP-C01),
    // not a link to a separate create screen.
    await expect(empty.getByRole("button", { name: "Add course" })).toBeVisible();
  });
});

test.describe("tenant admin course list — content and filters", () => {
  test("lists every course in the tenant with a teacher column", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.getByRole("link", { name: "Courses" }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/courses$/);

    const table = page.getByRole("table");
    await expect(table).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Teacher" })).toBeVisible();
    await expect(table.getByText("Intro to Biology")).toBeVisible();
    await expect(table.getByText("Advanced Calculus")).toBeVisible();
  });

  // KLASS (CM-A01 / plan §4.1): the client-side "Teacher ID" filter was
  // replaced by server-side search (title or teacher name). The intent kept:
  // the narrowing is real (it is sent to the server), and a no-match search
  // shows a distinct filtered-empty state with a clear action.
  test("search narrows the list server-side, and a distinct filtered-empty state appears for no matches", async ({
    page,
  }) => {
    await loginAsTenantAdmin(page);
    const searches: string[] = [];
    await page.route("**/v1/courses*", async (route) => {
      const url = new URL(route.request().url());
      const search = (url.searchParams.get("search") ?? "").toLowerCase();
      if (search) searches.push(search);
      const rows = search ? COURSES.filter((c) => c.name.toLowerCase().includes(search)) : COURSES;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiPageSuccess(rows)) });
    });

    await page.getByRole("link", { name: "Courses" }).click();
    const table = page.getByRole("table");
    await expect(table).toBeVisible();

    await page.getByLabel("Search courses").fill("biology");
    await expect(table.getByText("Intro to Biology")).toBeVisible();
    await expect(table.getByText("Advanced Calculus")).toHaveCount(0);
    expect(searches).toContain("biology");

    await page.getByLabel("Search courses").fill("no-such-course");
    await expect(page.getByText("No courses match “no-such-course”")).toBeVisible();
    // Distinct from the true zero-data empty state.
    await expect(page.getByText("No active courses yet")).toHaveCount(0);

    await page.getByRole("button", { name: "Clear search" }).click();
    await expect(table.getByText("Intro to Biology")).toBeVisible();
    await expect(table.getByText("Advanced Calculus")).toBeVisible();
  });

  // KLASS (CM-A01): the status dropdown was replaced by Active / Archived tabs.
  test("the Active and Archived tabs request archived=false / archived=true", async ({ page }) => {
    await loginAsTenantAdmin(page);
    const archivedParams: string[] = [];
    const archivedCourse = { ...COURSES[1], archivedAt: "2026-02-01T00:00:00Z" };
    await page.route("**/v1/courses*", async (route) => {
      const url = new URL(route.request().url());
      if (!url.pathname.endsWith("/v1/courses")) return route.fallback();
      const archived = url.searchParams.get("archived") ?? "";
      archivedParams.push(archived);
      const rows: unknown[] = archived === "true" ? [archivedCourse] : [COURSES[0]];
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiPageSuccess(rows)) });
    });

    await page.getByRole("link", { name: "Courses" }).click();
    await expect(page.getByRole("table").getByText("Intro to Biology")).toBeVisible();
    expect(archivedParams).toContain("false");

    await page.getByRole("tab", { name: "Archived" }).click();
    await expect(page.getByRole("table").getByText("Advanced Calculus")).toBeVisible();
    await expect(page.getByRole("table").getByText("Intro to Biology")).toHaveCount(0);
    await expect(page.getByTestId("courses-registration-archived-note")).toBeVisible();
    expect(archivedParams).toContain("true");
  });

  test("each row's Edit action links to the tenant admin course workspace's Overview tab", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${COURSES[0].id}`, 200, apiSuccess(COURSES[0]));

    await page.getByRole("link", { name: "Courses" }).click();
    await expect(page.getByRole("table")).toBeVisible();

    const row = page.getByTestId("course-row").filter({ hasText: "Intro to Biology" });
    const menu = await openCourseActions(page, row);
    await expect(menu.getByTestId("course-action-schedule")).toHaveAttribute(
      "href",
      `/tenant-admin/courses/${COURSES[0].id}/sessions`
    );
    await expect(menu.getByTestId("course-action-roster")).toHaveAttribute(
      "href",
      `/tenant-admin/courses/${COURSES[0].id}/roster`
    );
    await menu.getByTestId("course-action-edit").click();

    await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${COURSES[0].id}$`));
    await expect(page.getByRole("heading", { name: "Intro to Biology" })).toBeVisible();
  });
});

test.describe("tenant admin course list — responsive behavior", () => {
  test("below md, the table converts to a stacked card list", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await page.setViewportSize({ width: 375, height: 812 });

    // The sidebar's "Courses" link is present but hidden below `md`; open the
    // hamburger-triggered drawer and navigate from inside it instead (mirrors
    // accessibility.spec.ts's mobile-nav pattern).
    await page.getByRole("button", { name: "Open navigation menu" }).click();
    const dialog = page.getByRole("dialog");
    await dialog.getByRole("link", { name: "Courses" }).click();

    await expect(page).toHaveURL(/\/tenant-admin\/courses$/);
    await expect(page.getByRole("table")).toBeHidden();
    await expect(page.getByRole("list").getByText("Intro to Biology")).toBeVisible();
  });
});

test.describe("tenant admin course workspace — Overview tab", () => {
  test("renders the edit form and the workspace tab nav", async ({ page }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));

    await gotoCourseDetail(page, course);

    await expect(page.getByRole("heading", { name: course.name })).toBeVisible();
    // R2 (G3): Overview hosts the Klass-style edit form; no slug anywhere (server-generated).
    await expect(page.getByTestId("edit-course-name")).toHaveValue(course.name);
    await expect(page.getByTestId("edit-course-enrollment-fee")).toBeVisible();
    await expect(page.getByText(course.slug, { exact: true })).toHaveCount(0);
    await expect(page.locator('[id*="slug"], [data-testid*="slug"]')).toHaveCount(0);
    const nav = page.getByRole("navigation", { name: "Course sections" });
    await expect(nav.getByRole("link", { name: "Overview" })).toBeVisible();
    // R2-D5: the Fees & Billing tab was removed.
    await expect(nav.getByRole("link", { name: "Fees & Billing" })).toHaveCount(0);
    await expect(nav.getByRole("link", { name: "Settings" })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Access" })).toBeVisible();

    // Actions that moved to other tabs are no longer on Overview.
    await expect(page.getByRole("heading", { name: "Visibility" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Change price" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Reassign teacher" })).toHaveCount(0);
  });
});

test.describe("tenant admin course workspace — placeholder tabs", () => {
  test("Schedule/Recordings/Analytics are visible, clickable, and show an honest not-yet-available state", async ({
    page,
  }) => {
    // Live classes is no longer a placeholder as of Wave 4 (PAR-19-03/live-class-
    // management) — see the dedicated "Live classes tab (Wave 4)" describe block
    // below for its real-content coverage.
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);

    for (const [label, expectedTitle] of [
      ["Schedule", "Schedule isn't available yet"],
      ["Recordings", "Recordings isn't available yet"],
      ["Analytics", "Analytics isn't available yet"],
    ] as const) {
      await gotoTab(page, label);
      await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${course.id}/${label.toLowerCase()}$`));
      await expect(page.getByText(expectedTitle)).toBeVisible();
      await expect(page.getByText("Coming in a later release.")).toBeVisible();
    }
  });
});

test.describe("tenant admin course workspace — Live classes tab (Wave 4)", () => {
  test("lists this course's live class sessions, scoped via courseId, with an empty state when none exist", async ({
    page,
  }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await mockJson(page, "**/v1/class-sessions*", 200, apiSuccess([]));
    await gotoCourseDetail(page, course);

    await gotoTab(page, "Live classes");
    await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${course.id}/sessions$`));
    await expect(page.getByText("No live class sessions for this course yet")).toBeVisible();
  });
});

test.describe("tenant admin course workspace — Access tab", () => {
  test("shows and updates accessDurationDays/enrollmentRule via a full CourseUpdateRequest", async ({ page }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Access");

    await expect(page.locator("#course-access-accessDurationDays")).toHaveValue("180");
    await expect(page.locator("#course-access-enrollmentRule")).toHaveValue("Open enrollment");

    let patchBody: unknown = null;
    await page.route(`**/api/v1/courses/${course.id}`, async (route) => {
      if (route.request().method() === "PATCH") {
        patchBody = route.request().postDataJSON();
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess({ ...course, accessDurationDays: 365 })),
        });
        return;
      }
      await route.fallback();
    });

    await page.locator("#course-access-accessDurationDays").fill("365");
    await page.getByRole("button", { name: "Save access settings" }).click();

    await expect(page.getByText("Access settings saved.")).toBeVisible();
    expect(patchBody).toMatchObject({
      name: course.name,
      slug: course.slug,
      category: course.category,
      accessDurationDays: 365,
      enrollmentRule: "Open enrollment",
    });
  });
});

test.describe("tenant admin course workspace — Settings tab", () => {
  test("renders visibility/archive/clone/delete (no UUID reassign form) and publishing flips status", async ({ page }) => {
    const course = COURSES[1]; // Advanced Calculus, starts DRAFT
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Settings");

    await expect(page.getByRole("heading", { name: "Visibility" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Archive course" })).toBeVisible();
    // R2 (G2): teachers are assigned with the multi-select on Overview; the UUID form is gone.
    await expect(page.getByRole("heading", { name: "Reassign teacher" })).toHaveCount(0);
    await expect(page.getByLabel("New teacher ID (UUID)")).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Clone course" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Delete course" })).toBeVisible();

    await expect(page.getByText("Draft", { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Publish", exact: true })).toBeVisible();

    await mockJson(
      page,
      `**/v1/courses/${course.id}/publish`,
      200,
      apiSuccess({ ...course, status: "PUBLIC" })
    );
    await page.getByRole("button", { name: "Publish", exact: true }).click();

    await expect(page.getByRole("button", { name: "Unpublish (revert to Draft)" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Publish", exact: true })).toHaveCount(0);
  });

  test("archiving a course shows the archived banner and swaps Archive for Unarchive", async ({ page }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Settings");

    await mockJson(
      page,
      `**/v1/courses/${course.id}/archive`,
      200,
      apiSuccess({ ...course, archivedAt: "2026-03-01T00:00:00Z" })
    );
    await page.getByRole("button", { name: "Archive course", exact: true }).click();
    await page
      .getByRole("alertdialog")
      .getByRole("button", { name: "Archive course" })
      .click();

    await expect(page.getByRole("heading", { name: "Unarchive course" })).toBeVisible();
    await expect(page.getByText("This course is archived", { exact: false })).toBeVisible();
  });

  // R2 (G2): replaces the UUID "Reassign teacher" form. Teachers are picked
  // with the multi-select on Overview and the primary chosen with a radio; the
  // PATCH carries `teacherIds` (primary first) and preserves the hidden legacy
  // fields (a PATCH with `name` replaces them).
  test("teacher multi-select: add a second teacher and make them primary — exact PATCH body, legacy fields preserved", async ({
    page,
  }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    let patchBody: Record<string, unknown> | null = null;
    await page.route(`**/api/v1/courses/${course.id}`, async (route) => {
      if (route.request().method() === "PATCH") {
        patchBody = route.request().postDataJSON();
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(
            apiSuccess({
              ...course,
              teacherId: SECOND_TEACHER_USER_ID,
              teachers: [
                { id: SECOND_TEACHER_USER_ID, name: "Teacher Charlie", primary: true },
                { id: course.teacherId, name: "Teacher Alpha", primary: false },
              ],
            })
          ),
        });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(course)) });
    });
    await gotoCourseDetail(page, course);

    const teachers = page.getByTestId("edit-course-teachers");
    await teachers.getByRole("button").first().click();
    await teachers.getByRole("checkbox", { name: "Teacher Charlie" }).check();
    const primary = page.getByTestId("edit-course-primary-teacher");
    await expect(primary.getByTestId("edit-course-primary-teacher-option")).toHaveCount(2);
    await primary.getByRole("radio", { name: "Teacher Charlie" }).check();
    await page.getByTestId("edit-course-submit").click();

    await expect(page.getByText("Course details saved.")).toBeVisible();
    expect(patchBody).toMatchObject({
      name: course.name,
      teacherIds: [SECOND_TEACHER_USER_ID, course.teacherId],
      subject: course.subject,
      stream: course.stream,
      grade: course.grade,
      academicYear: course.academicYear,
      enrollmentRule: course.enrollmentRule,
    });
    expect(patchBody).not.toHaveProperty("slug");
    expect(patchBody).not.toHaveProperty("teacherId");
  });

  test("cloning a course navigates to the new course's workspace", async ({ page }) => {
    const course = COURSES[0];
    const clonedId = "33333333-3333-3333-3333-333333333333";
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await mockJson(
      page,
      `**/v1/courses/${clonedId}`,
      200,
      apiSuccess({ ...course, id: clonedId, name: `${course.name} (Copy)`, status: "DRAFT" })
    );
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Settings");

    await mockJson(
      page,
      `**/v1/courses/${course.id}/clone`,
      201,
      apiSuccess({ ...course, id: clonedId, name: `${course.name} (Copy)`, status: "DRAFT" })
    );
    await page.getByRole("button", { name: "Clone course", exact: true }).click();
    await page
      .getByRole("alertdialog")
      .getByRole("button", { name: "Clone course" })
      .click();

    await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${clonedId}\\?cloned=1$`));
    await expect(page.getByText("Course cloned.", { exact: false })).toBeVisible();
  });

  test("deleting the course redirects to the tenant admin course list", async ({ page }) => {
    const course = COURSES[1];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));

    await gotoCourseDetail(page, course);
    await gotoTab(page, "Settings");
    await expect(page.getByRole("heading", { name: "Delete course" })).toBeVisible();

    let deleteCalled = false;
    await page.route(`**/v1/courses/${course.id}`, async (route) => {
      if (route.request().method() === "DELETE") {
        deleteCalled = true;
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(null)),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(course)),
      });
    });

    await page.getByRole("button", { name: "Delete course", exact: true }).click();
    await page
      .getByRole("alertdialog")
      .getByRole("button", { name: "Delete course" })
      .click();

    await expect(page).toHaveURL(/\/tenant-admin\/courses$/);
    expect(deleteCalled).toBe(true);
  });
});

// R2-D5: the "Fees & Billing" tab (pricing-model / billing-configuration /
// billing-period UI) was removed. The fee is edited in the Overview edit form
// (a monthly fee change still records billing-period history server-side —
// covered by backend tests), and the old /billing route redirects to Overview.
test.describe("tenant admin course workspace — fee editing (former Fees & Billing tab)", () => {
  test("ONE_TIME: changing the course fee in the edit form sends only the new price (plus preserved legacy fields)", async ({
    page,
  }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    let patchBody: Record<string, unknown> | null = null;
    await page.route(`**/api/v1/courses/${course.id}`, async (route) => {
      if (route.request().method() === "PATCH") {
        patchBody = route.request().postDataJSON();
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess({ ...course, price: 59.99, priceDisplay: { amount: 59.99, currency: "USD", period: null } })),
        });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(course)) });
    });
    await gotoCourseDetail(page, course);

    const price = page.getByTestId("edit-course-price");
    await expect(price).toHaveValue(/49\.99/);
    await price.fill("59.99");
    await page.getByTestId("edit-course-submit").click();

    await expect(page.getByText("Course details saved.")).toBeVisible();
    expect(patchBody).toMatchObject({ name: course.name, price: "59.99", subject: course.subject, grade: course.grade });
    // Unchanged payment type is not re-sent; teachers untouched are not re-sent.
    expect(patchBody).not.toHaveProperty("pricingModel");
    expect(patchBody).not.toHaveProperty("teacherIds");
    expect(patchBody).not.toHaveProperty("slug");
  });

  test("the old /billing route redirects to the course Overview and no Fees & Billing tab is offered", async ({ page }) => {
    const course = COURSES[0];
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TENANT_ADMIN" }))));
    await mockJson(page, "**/v1/teachers", 200, apiSuccess(TEACHERS));
    await mockJson(page, "**/v1/course-tags", 200, apiSuccess([]));
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));

    await page.goto(`/tenant-admin/courses/${course.id}/billing`);

    await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${course.id}$`));
    await expect(page.getByTestId("edit-course-name")).toHaveValue(course.name);
    const nav = page.getByRole("navigation", { name: "Course sections" });
    await expect(nav.getByRole("link", { name: "Fees & Billing" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Change price" })).toHaveCount(0);
    await expect(page.getByLabel("Pricing model")).toHaveCount(0);
  });
});

test.describe("tenant admin course workspace — permission denied", () => {
  test("a 403 on the course fetch renders the shared permission-denied state with a link back to the dashboard", async ({
    page,
  }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(
      page,
      `**/v1/courses/${course.id}`,
      403,
      apiError("FORBIDDEN", "You do not have access to this course.")
    );

    await gotoCourseDetail(page, course);

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(page.getByText("You do not have access to this course.")).toBeVisible();
    const backLink = page.getByRole("link", { name: "Back to your dashboard" });
    await expect(backLink).toBeVisible();
    await expect(backLink).toHaveAttribute("href", "/tenant-admin/dashboard");

    // Not a blank page and not the generic ErrorState (which would render a
    // "Try again" retry button instead of a permission-scoped message + link).
    await expect(page.getByRole("button", { name: "Try again" })).toHaveCount(0);
  });
});

test.describe("tenant admin course workspace — role gating (Teacher viewing their own course)", () => {
  test("a Teacher who directly navigates to a course they own sees edit/visibility/price controls but not the admin-only teacher-assignment/delete controls", async ({
    page,
  }) => {
    const course = { ...COURSES[0], teacherId: "user-1" }; // fakeJwt's default `sub`

    const teacherToken = fakeJwt({ role: "TEACHER" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(teacherToken)));
    // `GET /api/v1/courses/{id}` succeeds — ownership-scoped access, not
    // Tenant-Admin-scoped — simulating the real backend behavior this page's
    // `isTenantAdmin` gate exists to compensate for in the UI.
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([course]));
    await mockJson(page, "**/v1/course-tags", 200, apiSuccess([]));
    // A teacher may not list the institute's teachers (server 403).
    await mockJson(page, "**/v1/teachers", 403, apiError("FORBIDDEN", "You do not have permission."));

    // No client-side link from Teacher UI reaches this Tenant Admin route —
    // direct navigation is the realistic way a Teacher would end up here
    // (e.g. a saved/shared URL), and the in-memory-token/refresh-cookie model
    // (see `lib/auth/auth-context.tsx`) makes a fresh `page.goto` + mocked
    // `/v1/auth/refresh` the correct way to simulate that, mirroring
    // `course-modules.spec.ts`'s direct-navigation pattern for Teacher routes.
    await page.goto(`/tenant-admin/courses/${course.id}`);

    // Legitimately usable by a Teacher on their own course.
    await expect(page.getByRole("heading", { name: course.name })).toBeVisible();
    await expect(page.getByTestId("edit-course-name")).toHaveValue(course.name);
    // Teachers are read-only for a teacher: no picker and no primary radios.
    await expect(page.getByTestId("edit-course-teachers-readonly")).toBeVisible();
    await expect(page.getByTestId("edit-course-teachers")).toHaveCount(0);
    await expect(page.getByTestId("edit-course-primary-teacher")).toHaveCount(0);
    // The fee stays editable for the owning teacher (in the edit form now).
    await expect(page.getByTestId("edit-course-price")).toBeVisible();

    await gotoTab(page, "Settings");
    await expect(page.getByRole("heading", { name: "Visibility" })).toBeVisible();
    // Tenant-Admin-only controls must be entirely absent, not just disabled.
    await expect(page.getByRole("heading", { name: "Reassign teacher" })).toHaveCount(0);
    await expect(page.getByLabel("New teacher ID (UUID)")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reassign" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Delete course" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Delete course" })).toHaveCount(0);
    // Archive/Clone remain available to the owning Teacher (Wave 2 additions,
    // gated the same as every other `CREATE_EDIT`-guarded course mutation).
    await expect(page.getByRole("heading", { name: "Archive course" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Clone course" })).toBeVisible();

    await expect(
      page.getByRole("navigation", { name: "Course sections" }).getByRole("link", { name: "Fees & Billing" })
    ).toHaveCount(0);
  });
});
