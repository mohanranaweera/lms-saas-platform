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
 * Tenant Admin course list (`/tenant-admin/courses`) and detail
 * (`/tenant-admin/courses/[courseId]`) coverage. No real backend runs in
 * this environment (see `fixtures/auth-mocks.ts`) — every test logs in
 * through a mocked `POST /v1/auth/login` and intercepts
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
    createdAt: "2026-01-03T00:00:00Z",
    updatedAt: "2026-01-03T00:00:00Z",
  },
];

async function loginAsTenantAdmin(page: Page) {
  const token = fakeJwt({ role: "TENANT_ADMIN" });
  await mockJson(page, "**/v1/auth/login", 200, apiSuccess(loginResponseBody(token)));
  await page.goto("/login");
  await page.getByLabel("Email").fill("admin@example.com");
  await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/tenant-admin\/dashboard$/);
}

/**
 * Navigates from the tenant admin course list to a given course's detail
 * page via the app's own client-side links (never `page.goto`, which would
 * force a full browser navigation and drop the in-memory access token — see
 * `loginAsTenantAdmin`'s sibling comment above and `lib/auth/auth-context.tsx`).
 * Deliberately makes no assertion about the detail page's rendered content —
 * callers assert whatever their scenario (success, 403, etc.) actually needs.
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

test.describe("tenant admin course list — empty state", () => {
  test("shows tenant-admin-specific empty copy, distinct from the teacher empty state", async ({
    page,
  }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));

    await page.getByRole("link", { name: "Courses" }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/courses$/);

    await expect(page.getByText("No courses in this tenant yet")).toBeVisible();
    await expect(
      page.getByText("Courses are created by teachers, not tenant admins", { exact: false })
    ).toBeVisible();
    // Must not reuse the Teacher "My Courses" empty-state copy.
    await expect(page.getByText("No assigned courses yet")).toHaveCount(0);
    // No dead creation CTA — Tenant Admin has no course-creation screen at MVP.
    await expect(page.getByRole("button", { name: "New course" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "New course" })).toHaveCount(0);
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
    await expect(page.getByText("No courses in this tenant yet")).toHaveCount(0);

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

  test("each row's View action links to the tenant admin course detail page", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${COURSES[0].id}`, 200, apiSuccess(COURSES[0]));

    await page.getByRole("link", { name: "Courses" }).click();
    await expect(page.getByRole("table")).toBeVisible();

    const row = page.getByRole("row").filter({ hasText: "Intro to Biology" });
    await row.getByRole("link", { name: "View" }).click();

    await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${COURSES[0].id}$`));
    await expect(page.getByRole("heading", { name: /Course details/ })).toBeVisible();
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

test.describe("tenant admin course detail — composition", () => {
  test("renders the edit form, visibility/price actions, teacher reassignment, and delete action", async ({
    page,
  }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));

    await gotoCourseDetail(page, course);

    await expect(page.getByRole("heading", { name: `Course details: ${course.name}` })).toBeVisible();
    await expect(page.getByLabel("Course name")).toHaveValue(course.name);
    await expect(page.getByRole("heading", { name: "Visibility" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Change price" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Reassign teacher" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Delete course" })).toBeVisible();
  });
});

test.describe("tenant admin course detail — visibility (publish/unpublish)", () => {
  test("publishing a draft course flips its status to Public and swaps Publish for Unpublish", async ({
    page,
  }) => {
    const course = COURSES[1]; // Advanced Calculus, starts DRAFT
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);

    await expect(page.getByText("Draft", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Publish", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Unpublish (revert to Draft)" })).toHaveCount(0);

    await mockJson(
      page,
      `**/v1/courses/${course.id}/publish`,
      200,
      apiSuccess({ ...course, status: "PUBLIC" })
    );
    await page.getByRole("button", { name: "Publish", exact: true }).click();

    await expect(page.getByText("Public", { exact: true })).toBeVisible();
    await expect(page.getByText("Draft", { exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Unpublish (revert to Draft)" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Publish", exact: true })).toHaveCount(0);
  });

  test("unpublishing a public course reverts its status to Draft and swaps Unpublish for Publish", async ({
    page,
  }) => {
    const course = COURSES[0]; // Intro to Biology, starts PUBLIC
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);

    await expect(page.getByText("Public", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Unpublish (revert to Draft)" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Publish", exact: true })).toHaveCount(0);

    await mockJson(
      page,
      `**/v1/courses/${course.id}/unpublish`,
      200,
      apiSuccess({ ...course, status: "DRAFT" })
    );
    await page.getByRole("button", { name: "Unpublish (revert to Draft)" }).click();

    await expect(page.getByText("Draft", { exact: true })).toBeVisible();
    await expect(page.getByText("Public", { exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Publish", exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Unpublish (revert to Draft)" })).toHaveCount(0);
  });

  test("a failed publish call surfaces via the control's own error state and leaves status unchanged", async ({
    page,
  }) => {
    const course = COURSES[1]; // starts DRAFT
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);

    await mockJson(
      page,
      `**/v1/courses/${course.id}/publish`,
      500,
      apiError("INTERNAL_ERROR", "Could not publish this course right now.")
    );
    await page.getByRole("button", { name: "Publish", exact: true }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "Could not publish this course right now." })
    ).toBeVisible();
    // Status is unchanged and the action is retryable, not stuck disabled.
    await expect(page.getByText("Draft", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: "Publish", exact: true })).toBeEnabled();
  });
});

test.describe("tenant admin course detail — price change", () => {
  test("submitting a new price sends the exact request body and shows the confirmation", async ({
    page,
  }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);

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
    await expect(page.getByText("Current price: 129.99.", { exact: false })).toBeVisible();
  });

  test("a price-change failure surfaces via the form's own error state, not a page-level crash", async ({
    page,
  }) => {
    const course = COURSES[0];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);

    await mockJson(
      page,
      `**/v1/courses/${course.id}/price`,
      500,
      apiError("INTERNAL_ERROR", "Something went wrong updating the price.")
    );

    await page.getByLabel("New price").fill("129.99");
    await page.getByRole("button", { name: "Save price" }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "Something went wrong updating the price." })
    ).toBeVisible();
    await expect(page.getByText("Price updated.")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Save price" })).toBeEnabled();
  });
});

test.describe("tenant admin course detail — teacher reassignment", () => {
  test("submitting a new teacher ID sends the exact request body and shows the confirmation", async ({
    page,
  }) => {
    const course = COURSES[0];
    const newTeacherId = "99999999-9999-9999-9999-999999999999";
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));
    await gotoCourseDetail(page, course);

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
});

test.describe("tenant admin course detail — delete", () => {
  test("deleting the course redirects to the tenant admin course list", async ({ page }) => {
    const course = COURSES[1];
    await loginAsTenantAdmin(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await mockJson(page, `**/v1/courses/${course.id}`, 200, apiSuccess(course));

    await gotoCourseDetail(page, course);
    await expect(page.getByRole("heading", { name: `Course details: ${course.name}` })).toBeVisible();

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

    await page.getByRole("button", { name: "Delete course" }).click();
    await page
      .getByRole("alertdialog")
      .getByRole("button", { name: "Delete course" })
      .click();

    await expect(page).toHaveURL(/\/tenant-admin\/courses$/);
    expect(deleteCalled).toBe(true);
  });
});

test.describe("tenant admin course detail — permission denied", () => {
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

test.describe("tenant admin course detail — role gating (Teacher viewing their own course)", () => {
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
    await expect(page.getByRole("heading", { name: `Course details: ${course.name}` })).toBeVisible();
    await expect(page.getByLabel("Course name")).toHaveValue(course.name);
    await expect(page.getByRole("heading", { name: "Visibility" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Change price" })).toBeVisible();

    // Tenant-Admin-only controls must be entirely absent, not just disabled.
    await expect(page.getByRole("heading", { name: "Reassign teacher" })).toHaveCount(0);
    await expect(page.getByLabel("New teacher ID (UUID)")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reassign" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Delete course" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Delete course" })).toHaveCount(0);
  });
});
