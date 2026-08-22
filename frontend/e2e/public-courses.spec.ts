import { test, expect } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";

/**
 * Public course storefront (`/courses`, `/courses/[slug]`) — anonymous,
 * unauthenticated endpoints (`lib/api/public-courses.ts`). No login step is
 * needed; every test intercepts `/api/v1/public/courses/**` directly.
 */

const PUBLIC_COURSES = [
  {
    id: "11111111-1111-1111-1111-111111111111",
    name: "Intro to Biology",
    slug: "intro-to-biology",
    category: "Science",
    subject: "Biology",
    stream: null,
    grade: "Grade 9",
    academicYear: "2026",
    description: "A beginner-friendly introduction to biology.",
    price: 49.99,
    accessDurationDays: 180,
    enrollmentRule: "Open enrollment, no prerequisites.",
  },
  {
    id: "22222222-2222-2222-2222-222222222222",
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
  },
];

test.describe("public course list", () => {
  test("renders a card per course with name, category, and price", async ({ page }) => {
    await mockJson(page, "**/v1/public/courses*", 200, apiPageSuccess(PUBLIC_COURSES));
    await page.goto("/courses");

    await expect(page.getByRole("heading", { name: "Courses" })).toBeVisible();
    const list = page.getByRole("list");
    await expect(list.getByText("Intro to Biology")).toBeVisible();
    await expect(list.getByText("Science")).toBeVisible();
    await expect(list.getByText("49.99")).toBeVisible();
    await expect(list.getByText("Advanced Calculus")).toBeVisible();
    await expect(list.getByText("Mathematics")).toBeVisible();
  });

  test("each card links to the course's detail page by slug", async ({ page }) => {
    await mockJson(page, "**/v1/public/courses*", 200, apiPageSuccess(PUBLIC_COURSES));
    await mockJson(page, "**/v1/public/courses/intro-to-biology", 200, apiSuccess(PUBLIC_COURSES[0]));
    await page.goto("/courses");

    await page.getByRole("link", { name: /Intro to Biology/ }).click();
    await expect(page).toHaveURL(/\/courses\/intro-to-biology$/);
    await expect(page.getByRole("heading", { name: "Intro to Biology" })).toBeVisible();
  });

  test("a genuinely empty catalog shows the zero-courses empty state", async ({ page }) => {
    await mockJson(page, "**/v1/public/courses*", 200, apiPageSuccess([]));
    await page.goto("/courses");

    await expect(page.getByText("No courses are available yet")).toBeVisible();
  });

  test("a server error renders the shared error state with a retry action", async ({ page }) => {
    await mockJson(page, "**/v1/public/courses*", 500, apiError("INTERNAL_ERROR", "Something broke"));
    await page.goto("/courses");

    await expect(page.getByRole("alert")).toBeVisible();
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
  });

  test("a single-page result hides the Previous/Next pagination controls entirely", async ({
    page,
  }) => {
    await mockJson(
      page,
      "**/v1/public/courses*",
      200,
      apiPageSuccess(PUBLIC_COURSES, { page: 0, totalElements: PUBLIC_COURSES.length, totalPages: 1 })
    );
    await page.goto("/courses");

    await expect(page.getByRole("list").getByText("Intro to Biology")).toBeVisible();
    await expect(
      page.getByRole("navigation", { name: "Course catalog pagination" })
    ).toHaveCount(0);
  });

  test("Previous/Next paginate a two-page catalog, disabling controls at each boundary", async ({
    page,
  }) => {
    // Dispatch on the request's own `page` query param (per
    // `course-modules.spec.ts`'s stateful-mock pattern) so clicking Next
    // issues a real, distinguishable request rather than replaying one fixed
    // mocked response.
    await page.route("**/v1/public/courses*", async (route) => {
      const requestedPage = new URL(route.request().url()).searchParams.get("page");
      if (requestedPage === "1") {
        await fulfillJson(
          route,
          200,
          apiPageSuccess([PUBLIC_COURSES[1]], { page: 1, totalElements: 2, totalPages: 2 })
        );
      } else {
        await fulfillJson(
          route,
          200,
          apiPageSuccess([PUBLIC_COURSES[0]], { page: 0, totalElements: 2, totalPages: 2 })
        );
      }
    });

    await page.goto("/courses");

    const nav = page.getByRole("navigation", { name: "Course catalog pagination" });
    await expect(nav).toBeVisible();
    await expect(page.getByRole("list").getByText("Intro to Biology")).toBeVisible();
    await expect(page.getByText("Page 1 of 2")).toBeVisible();

    const previousButton = nav.getByRole("button", { name: "Previous" });
    const nextButton = nav.getByRole("button", { name: "Next" });
    await expect(previousButton).toBeDisabled();
    await expect(nextButton).toBeEnabled();

    await nextButton.click();

    await expect(page.getByRole("list").getByText("Advanced Calculus")).toBeVisible();
    await expect(page.getByRole("list").getByText("Intro to Biology")).toHaveCount(0);
    await expect(page.getByText("Page 2 of 2")).toBeVisible();
    await expect(nextButton).toBeDisabled();
    await expect(previousButton).toBeEnabled();
  });
});

test.describe("public course detail", () => {
  test("renders full course details when present", async ({ page }) => {
    await mockJson(
      page,
      "**/v1/public/courses/intro-to-biology",
      200,
      apiSuccess(PUBLIC_COURSES[0])
    );
    await page.goto("/courses/intro-to-biology");

    await expect(page.getByRole("heading", { name: "Intro to Biology" })).toBeVisible();
    await expect(page.getByText("Biology", { exact: true })).toBeVisible();
    await expect(page.getByText("Grade 9")).toBeVisible();
    await expect(page.getByText("A beginner-friendly introduction to biology.")).toBeVisible();
    await expect(page.getByText("49.99")).toBeVisible();
    await expect(page.getByText("180 days")).toBeVisible();
    await expect(page.getByText("Open enrollment, no prerequisites.")).toBeVisible();

    // Read-only inventory display only — no enrollment surface exists yet.
    await expect(page.getByRole("button", { name: /Enroll/ })).toHaveCount(0);
    await expect(page.getByRole("link", { name: /Enroll/ })).toHaveCount(0);
  });

  test("omits nullable fields instead of showing blanks, and shows lifetime access when unset", async ({
    page,
  }) => {
    await mockJson(
      page,
      "**/v1/public/courses/advanced-calculus",
      200,
      apiSuccess(PUBLIC_COURSES[1])
    );
    await page.goto("/courses/advanced-calculus");

    await expect(page.getByRole("heading", { name: "Advanced Calculus" })).toBeVisible();
    await expect(page.getByText("Lifetime access")).toBeVisible();
    await expect(page.getByText("Subject")).toHaveCount(0);
    await expect(page.getByText("Enrollment rule")).toHaveCount(0);
  });

  test("a 404 renders a neutral, generic not-found state — never a scary error style, never hinting at the reason", async ({
    page,
  }) => {
    await mockJson(
      page,
      "**/v1/public/courses/unpublished-course",
      404,
      apiError("NOT_FOUND", "Course not found")
    );
    await page.goto("/courses/unpublished-course");

    await expect(page.getByText("Course not found", { exact: true })).toBeVisible();
    await expect(
      page.getByText("it may be unpublished or the link may be incorrect", { exact: false })
    ).toBeVisible();
    // Must not use the scary/destructive-styled generic error state for this
    // expected, anti-enumeration outcome.
    await expect(page.getByRole("button", { name: "Try again" })).toHaveCount(0);
  });

  test("a non-404 error still falls back to the normal error state", async ({ page }) => {
    await mockJson(
      page,
      "**/v1/public/courses/intro-to-biology",
      500,
      apiError("INTERNAL_ERROR", "Something broke")
    );
    await page.goto("/courses/intro-to-biology");

    await expect(page.getByRole("alert").filter({ hasText: "Something broke" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
  });
});
