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
    pricingModel: "ONE_TIME",
    resolvedAmount: 49.99,
    currency: "USD",
    requiresManualQuote: false,
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
    pricingModel: "ONE_TIME",
    resolvedAmount: 99.5,
    currency: "USD",
    requiresManualQuote: false,
  },
];

/** Base fields shared by every pricing-model-variant fixture below. */
const BASE_PUBLIC_COURSE = {
  id: "33333333-3333-3333-3333-333333333333",
  name: "Weekly Chess Club",
  slug: "weekly-chess-club",
  category: "Enrichment",
  subject: null,
  stream: null,
  grade: null,
  academicYear: null,
  description: null,
  price: 0,
  accessDurationDays: null,
  enrollmentRule: null,
};

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

test.describe("public course detail — pricing-model-aware rendering (course-management gap fix)", () => {
  test("a FREE course shows \"Free\", never a dollar amount", async ({ page }) => {
    const freeCourse = {
      ...BASE_PUBLIC_COURSE,
      slug: "free-course",
      pricingModel: "FREE",
      resolvedAmount: 0,
      currency: "USD",
      requiresManualQuote: false,
    };
    await mockJson(page, "**/v1/public/courses/free-course", 200, apiSuccess(freeCourse));
    await page.goto("/courses/free-course");

    await expect(page.getByRole("heading", { name: freeCourse.name })).toBeVisible();
    await expect(page.getByText("Free", { exact: true })).toBeVisible();
  });

  test("a MONTHLY course with no configured billing period shows a not-yet-available state instead of a broken price, and hides the enroll CTA", async ({
    page,
  }) => {
    const unconfigured = {
      ...BASE_PUBLIC_COURSE,
      slug: "monthly-unconfigured",
      pricingModel: "MONTHLY",
      resolvedAmount: null,
      currency: "USD",
      requiresManualQuote: false,
    };
    await mockJson(page, "**/v1/public/courses/monthly-unconfigured", 200, apiSuccess(unconfigured));
    await page.goto("/courses/monthly-unconfigured");

    await expect(page.getByText("Pricing not yet available")).toBeVisible();
    await expect(
      page.getByText("Pricing for this course hasn't been configured yet", { exact: false })
    ).toBeVisible();
    // No enroll/sign-in CTA is offered for a course that can't be checked out yet.
    await expect(page.getByRole("link", { name: "Sign in to enroll" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Enroll now" })).toHaveCount(0);
  });

  test("a MONTHLY course with a configured billing period shows the resolved amount with a /month suffix", async ({
    page,
  }) => {
    const configured = {
      ...BASE_PUBLIC_COURSE,
      slug: "monthly-configured",
      pricingModel: "MONTHLY",
      resolvedAmount: 15,
      currency: "USD",
      requiresManualQuote: false,
    };
    await mockJson(page, "**/v1/public/courses/monthly-configured", 200, apiSuccess(configured));
    await page.goto("/courses/monthly-configured");

    await expect(page.getByText("15.00 USD/month")).toBeVisible();
  });

  test("a CUSTOM-priced course shows contact-us messaging, never an amount, and never a checkout CTA", async ({
    page,
  }) => {
    const customCourse = {
      ...BASE_PUBLIC_COURSE,
      slug: "custom-priced",
      pricingModel: "CUSTOM",
      resolvedAmount: null,
      currency: "USD",
      requiresManualQuote: true,
    };
    await mockJson(page, "**/v1/public/courses/custom-priced", 200, apiSuccess(customCourse));
    await page.goto("/courses/custom-priced");

    await expect(page.getByText("Contact us for pricing")).toBeVisible();
    await expect(
      page.getByText("pricing and enrollment are arranged manually by our staff", { exact: false })
    ).toBeVisible();
    // No self-serve checkout CTA — this is the actual bug fix under test.
    await expect(page.getByRole("link", { name: "Sign in to enroll" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Enroll now" })).toHaveCount(0);
  });
});
