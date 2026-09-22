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

/**
 * Tenant Admin course list (`/tenant-admin/courses`) and course workspace
 * (`/tenant-admin/courses/[courseId]/**`, Wave 2's tabbed restructuring —
 * Overview/Fees & Billing/Settings/Access/placeholders, see
 * `components/courses/course-workspace-shell.tsx`) coverage. No real backend
 * runs in this environment (see `fixtures/auth-mocks.ts`) — every test logs
 * in through a mocked `POST /v1/auth/login` and intercepts
 * `/api/v1/courses/**` directly.
 */

const COURSES = [
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
  await page
    .getByRole("row")
    .filter({ hasText: course.name })
    .getByRole("link", { name: "View" })
    .click();
  await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${course.id}$`));
}

/** Navigates from the (already-open) course workspace to a given tab. */
async function gotoTab(page: Page, tabLabel: string) {
  await page.getByRole("navigation", { name: "Course sections" }).getByRole("link", { name: tabLabel }).click();
}

test.describe("tenant admin course list — empty state", () => {
  test("shows a tenant-admin-specific empty state with a New course CTA", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));

    await page.getByRole("link", { name: "Courses" }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/courses$/);

    await expect(page.getByText("No active courses in this tenant yet")).toBeVisible();
    // Must not reuse the Teacher "My Courses" empty-state copy.
    await expect(page.getByText("No assigned courses yet")).toHaveCount(0);
    // Wave 2 (PAR-05-02): Tenant Admin now has a real create-course screen.
    await expect(page.getByRole("link", { name: "New course" }).first()).toBeVisible();
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

  test("filtering by teacher ID narrows the list, and a distinct filtered-empty state appears for no matches", async ({
    page,
  }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.getByRole("link", { name: "Courses" }).click();
    const table = page.getByRole("table");
    await expect(table).toBeVisible();

    await page.getByLabel("Teacher ID").fill("aaaaaaaa");
    await expect(table.getByText("Intro to Biology")).toBeVisible();
    await expect(table.getByText("Advanced Calculus")).toHaveCount(0);

    await page.getByLabel("Teacher ID").fill("no-such-teacher");
    await expect(page.getByText("No courses match your filters")).toBeVisible();
    // Distinct from the true zero-data empty state.
    await expect(page.getByText("No active courses in this tenant yet")).toHaveCount(0);

    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect(table.getByText("Intro to Biology")).toBeVisible();
    await expect(table.getByText("Advanced Calculus")).toBeVisible();
  });

  test("filtering by status narrows the list", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.getByRole("link", { name: "Courses" }).click();
    const table = page.getByRole("table");
    await expect(table).toBeVisible();

    await page.getByLabel("Status").click();
    await page.getByRole("option", { name: "Draft" }).click();

    await expect(table.getByText("Advanced Calculus")).toBeVisible();
    await expect(table.getByText("Intro to Biology")).toHaveCount(0);
  });

  test("each row's View action links to the tenant admin course workspace's Overview tab", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${COURSES[0].id}`, 200, apiSuccess(COURSES[0]));

    await page.getByRole("link", { name: "Courses" }).click();
    await expect(page.getByRole("table")).toBeVisible();

    const row = page.getByRole("row").filter({ hasText: "Intro to Biology" });
    await row.getByRole("link", { name: "View" }).click();

    await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${COURSES[0].id}$`));
    await expect(page.getByRole("heading", { name: "Intro to Biology" })).toBeVisible();
  });

  test("Show archived courses toggles includeArchived on the list request", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.getByRole("link", { name: "Courses" }).click();
    await expect(page.getByRole("table")).toBeVisible();

    const archivedCourse = { ...COURSES[1], archivedAt: "2026-02-01T00:00:00Z" };
    let sawIncludeArchivedTrue = false;
    await page.route("**/v1/courses*", async (route) => {
      const url = new URL(route.request().url());
      if (url.searchParams.get("includeArchived") === "true") {
        sawIncludeArchivedTrue = true;
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiPageSuccess([COURSES[0], archivedCourse])),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiPageSuccess([COURSES[0]])),
      });
    });

    await page.getByLabel("Show archived courses").check();
    expect(sawIncludeArchivedTrue).toBe(true);
    await expect(page.getByRole("table").getByText("Advanced Calculus")).toBeVisible();
    await expect(page.getByRole("table").getByText("Archived")).toBeVisible();
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
    await expect(page.getByLabel("Course name")).toHaveValue(course.name);
    const nav = page.getByRole("navigation", { name: "Course sections" });
    await expect(nav.getByRole("link", { name: "Overview" })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Fees & Billing" })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Settings" })).toBeVisible();
    await expect(nav.getByRole("link", { name: "Access" })).toBeVisible();

    // Actions that moved to other tabs are no longer on Overview.
    await expect(page.getByRole("heading", { name: "Visibility" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Change price" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Reassign teacher" })).toHaveCount(0);
  });
});

test.describe("tenant admin course workspace — placeholder tabs", () => {
  test("Schedule/Sessions/Recordings/Analytics are visible, clickable, and show an honest not-yet-available state", async ({
    page,
  }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);

    for (const [label, expectedTitle] of [
      ["Schedule", "Schedule isn't available yet"],
      ["Sessions", "Sessions isn't available yet"],
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
  test("renders visibility/archive/clone/reassign/delete and publishing flips status", async ({ page }) => {
    const course = COURSES[1]; // Advanced Calculus, starts DRAFT
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Settings");

    await expect(page.getByRole("heading", { name: "Visibility" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Archive course" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Reassign teacher" })).toBeVisible();
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

  test("teacher reassignment sends the exact request body and shows the confirmation", async ({ page }) => {
    const course = COURSES[0];
    const newTeacherId = "99999999-9999-9999-9999-999999999999";
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Settings");

    let capturedBody: unknown = null;
    await page.route(`**/v1/courses/${course.id}/teacher`, async (route) => {
      capturedBody = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ ...course, teacherId: newTeacherId })),
      });
    });

    await page.getByLabel("New teacher ID (UUID)").fill(newTeacherId);
    await page.getByRole("button", { name: "Reassign" }).click();

    await expect(page.getByText("Teacher reassigned.")).toBeVisible();
    expect(capturedBody).toEqual({ teacherId: newTeacherId });
    await expect(page.getByText(newTeacherId, { exact: false })).toBeVisible();
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

test.describe("tenant admin course workspace — Fees & Billing tab", () => {
  test("ONE_TIME: submitting a new price sends the exact request body and shows the confirmation", async ({
    page,
  }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Fees & Billing");

    await expect(page.getByRole("heading", { name: "Change price" })).toBeVisible();

    let capturedBody: unknown = null;
    await page.route(`**/v1/courses/${course.id}/price`, async (route) => {
      capturedBody = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ ...course, price: 129.99 })),
      });
    });

    await page.getByLabel("New price").fill("129.99");
    await page.getByRole("button", { name: "Save price" }).click();

    await expect(page.getByText("Price updated.")).toBeVisible();
    expect(capturedBody).toEqual({ price: 129.99 });
  });

  test("changing the pricing model calls PATCH .../pricing-model with the selected value", async ({ page }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Fees & Billing");

    let capturedBody: unknown = null;
    await page.route(`**/v1/courses/${course.id}/pricing-model`, async (route) => {
      capturedBody = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ ...course, pricingModel: "FREE" })),
      });
    });

    await page.locator(`#pricing-model-${course.id}-pricingModel`).click();
    await page.getByRole("option", { name: "Free" }).click();
    await page.getByRole("button", { name: "Save pricing model" }).click();

    await expect(page.getByText("Pricing model updated.")).toBeVisible();
    expect(capturedBody).toEqual({ pricingModel: "FREE" });
  });

  test("FREE course shows a no-fee-configuration notice, never a form", async ({ page }) => {
    const course = { ...COURSES[0], pricingModel: "FREE" as const };
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Fees & Billing");

    await expect(page.getByText("This course is free — no fee configuration needed.")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Change price" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Billing configuration" })).toHaveCount(0);
  });

  test("MONTHLY course with no billing configuration yet shows the create form, then the period history after creating one", async ({
    page,
  }) => {
    const course = { ...COURSES[0], pricingModel: "MONTHLY" as const };
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await mockJson(
      page,
      `**/v1/courses/${course.id}/billing-configuration`,
      404,
      apiError("NOT_FOUND", "Course billing configuration not found")
    );
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Fees & Billing");

    await expect(
      page.getByText("This course has no billing configuration yet", { exact: false })
    ).toBeVisible();
    // SESSION-only field must not render for MONTHLY.
    await expect(page.getByLabel("Session rate")).toHaveCount(0);

    let capturedBody: unknown = null;
    await page.route(`**/api/v1/courses/${course.id}/billing-configuration`, async (route) => {
      if (route.request().method() === "POST") {
        capturedBody = route.request().postDataJSON();
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(
            apiSuccess({
              id: "cfg-1",
              courseId: course.id,
              sessionRate: null,
              currency: "USD",
              requiresManualQuote: false,
              createdAt: "2026-03-01T00:00:00Z",
              updatedAt: "2026-03-01T00:00:00Z",
            })
          ),
        });
        return;
      }
      await route.fallback();
    });

    await page.getByLabel("Currency").fill("USD");
    await page.getByRole("button", { name: "Create billing configuration" }).click();

    await expect(page.getByText("Billing configuration saved.")).toBeVisible();
    expect(capturedBody).toEqual({ sessionRate: undefined, currency: "USD", requiresManualQuote: false });
  });

  test("SESSION course's billing configuration form includes a Session rate field", async ({ page }) => {
    const course = { ...COURSES[0], pricingModel: "SESSION" as const };
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await mockJson(
      page,
      `**/v1/courses/${course.id}/billing-configuration`,
      200,
      apiSuccess({
        id: "cfg-2",
        courseId: course.id,
        sessionRate: 15,
        currency: "USD",
        requiresManualQuote: false,
        createdAt: "2026-03-01T00:00:00Z",
        updatedAt: "2026-03-01T00:00:00Z",
      })
    );
    await mockJson(page, `**/v1/courses/${course.id}/billing-periods*`, 200, apiPageSuccess([]));
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Fees & Billing");

    await expect(page.getByLabel("Session rate")).toHaveValue("15");
  });

  test("CUSTOM course shows the manual/staff-checkout notice and the requires-manual-quote checkbox", async ({
    page,
  }) => {
    const course = { ...COURSES[0], pricingModel: "CUSTOM" as const };
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await mockJson(
      page,
      `**/v1/courses/${course.id}/billing-configuration`,
      404,
      apiError("NOT_FOUND", "Course billing configuration not found")
    );
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Fees & Billing");

    await expect(
      page.getByText("Checkout is completed through a manual/staff process instead.", { exact: false })
    ).toBeVisible();
    await expect(page.getByLabel(/Requires a manual quote/)).toBeVisible();
  });

  test("adding a billing period shows the non-retroactive note and posts the exact request body", async ({
    page,
  }) => {
    const course = { ...COURSES[0], pricingModel: "MONTHLY" as const };
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await mockJson(
      page,
      `**/v1/courses/${course.id}/billing-configuration`,
      200,
      apiSuccess({
        id: "cfg-3",
        courseId: course.id,
        sessionRate: null,
        currency: "USD",
        requiresManualQuote: false,
        createdAt: "2026-03-01T00:00:00Z",
        updatedAt: "2026-03-01T00:00:00Z",
      })
    );
    const existingPeriod = {
      id: "period-1",
      billingConfigurationId: "cfg-3",
      amount: 20,
      currency: "USD",
      effectiveFrom: "2026-02-01T00:00:00Z",
      effectiveTo: null,
      createdAt: "2026-02-01T00:00:00Z",
    };
    await mockJson(page, `**/v1/courses/${course.id}/billing-periods*`, 200, apiPageSuccess([existingPeriod]));
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Fees & Billing");

    await expect(
      page.getByText("does not retroactively change any past period or any payment already made", {
        exact: false,
      })
    ).toBeVisible();

    let capturedBody: unknown = null;
    await page.route(`**/api/v1/courses/${course.id}/billing-periods`, async (route) => {
      if (route.request().method() === "POST") {
        capturedBody = route.request().postDataJSON();
        await route.fulfill({
          status: 201,
          contentType: "application/json",
          body: JSON.stringify(
            apiSuccess({
              id: "period-2",
              billingConfigurationId: "cfg-3",
              amount: 25,
              currency: "USD",
              effectiveFrom: "2026-03-01T00:00:00Z",
              effectiveTo: null,
              createdAt: "2026-03-01T00:00:00Z",
            })
          ),
        });
        return;
      }
      await route.fallback();
    });

    await page.locator(`#billing-period-${course.id}-amount`).fill("25");
    await page.locator(`#billing-period-${course.id}-currency`).fill("USD");
    await page.getByRole("button", { name: "Add period", exact: true }).click();

    await expect(page.getByText("Billing period added.")).toBeVisible();
    expect(capturedBody).toMatchObject({ amount: 25, currency: "USD" });
  });

  test("a 409 when adding an out-of-order billing period surfaces the backend's error message", async ({
    page,
  }) => {
    const course = { ...COURSES[0], pricingModel: "MONTHLY" as const };
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await mockJson(
      page,
      `**/v1/courses/${course.id}/billing-configuration`,
      200,
      apiSuccess({
        id: "cfg-4",
        courseId: course.id,
        sessionRate: null,
        currency: "USD",
        requiresManualQuote: false,
        createdAt: "2026-03-01T00:00:00Z",
        updatedAt: "2026-03-01T00:00:00Z",
      })
    );
    await mockJson(page, `**/v1/courses/${course.id}/billing-periods*`, 200, apiPageSuccess([]));
    await gotoCourseDetail(page, course);
    await gotoTab(page, "Fees & Billing");

    await mockJson(
      page,
      `**/v1/courses/${course.id}/billing-periods`,
      409,
      apiError("CONFLICT", "A current billing period already exists for this course")
    );

    await page.locator(`#billing-period-${course.id}-amount`).fill("25");
    await page.locator(`#billing-period-${course.id}-currency`).fill("USD");
    await page.getByRole("button", { name: "Add period", exact: true }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "A current billing period already exists for this course" })
    ).toBeVisible();
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
  test("a Teacher who directly navigates to a course they own sees edit/visibility/price controls but not the admin-only reassign/delete controls", async ({
    page,
  }) => {
    const course = { ...COURSES[0], teacherId: "user-1" }; // fakeJwt's default `sub`

    const teacherToken = fakeJwt({ role: "TEACHER" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(teacherToken)));
    // `GET /api/v1/courses/{id}` succeeds — ownership-scoped access, not
    // Tenant-Admin-scoped — simulating the real backend behavior this page's
    // `isTenantAdmin` gate exists to compensate for in the UI.
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));

    // No client-side link from Teacher UI reaches this Tenant Admin route —
    // direct navigation is the realistic way a Teacher would end up here
    // (e.g. a saved/shared URL), and the in-memory-token/refresh-cookie model
    // (see `lib/auth/auth-context.tsx`) makes a fresh `page.goto` + mocked
    // `/v1/auth/refresh` the correct way to simulate that, mirroring
    // `course-modules.spec.ts`'s direct-navigation pattern for Teacher routes.
    await page.goto(`/tenant-admin/courses/${course.id}`);

    // Legitimately usable by a Teacher on their own course.
    await expect(page.getByRole("heading", { name: course.name })).toBeVisible();
    await expect(page.getByLabel("Course name")).toHaveValue(course.name);

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

    await gotoTab(page, "Fees & Billing");
    await expect(page.getByRole("heading", { name: "Change price" })).toBeVisible();
  });
});
