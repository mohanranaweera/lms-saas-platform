import { test, expect, type Page, type Route } from "@playwright/test";
import { apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * R2 (G4, R2-D6): tag chips in the payment composer narrow the course
 * selector. Mock-only: the additive `tags` on `GET /api/v1/checkout/courses`
 * (R2-T3) may not be deployed on the local backend yet, so the list is mocked
 * here. The server remains the authority on which courses are listed (only the
 * student's enrolled courses with offers); the chips only narrow that list
 * client-side and never add a course.
 */

const BIOLOGY = { id: "tag-bio", name: "Biology" };
const CHEMISTRY = { id: "tag-chem", name: "Chemistry" };
const COURSES = [
  { courseId: "course-bio-1", title: "2028 Biology Theory", tags: [BIOLOGY] },
  { courseId: "course-bio-2", title: "2028 Biology Revision", tags: [BIOLOGY] },
  { courseId: "course-chem-1", title: "2027 Chemistry Theory", tags: [CHEMISTRY] },
  { courseId: "course-untagged", title: "General Skills", tags: [] },
];

async function openComposer(page: Page, checkoutCourses: unknown[]) {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "STUDENT" }))));
  await mockJson(page, "**/api/v1/course-tags", 200, apiSuccess([]));
  await mockJson(
    page,
    "**/api/v1/enrollments/my/catalog*",
    200,
    apiSuccess({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
  );
  await page.route("**/api/v1/checkout/courses*", async (route: Route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(checkoutCourses)) });
  });
  await page.route("**/api/v1/checkout/offers*", async (route: Route) => {
    const url = new URL(route.request().url());
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        apiSuccess({
          scope: url.searchParams.get("scope"),
          courseId: url.searchParams.get("courseId"),
          currency: "LKR",
          supported: true,
          years: [2026],
          items: [],
        })
      ),
    });
  });

  await page.goto("/student/courses");
  await page.getByTestId("global-pay-button").click();
  const composer = page.getByTestId("payment-composer");
  await expect(composer).toBeVisible();
  return composer;
}

async function courseOptions(composer: import("@playwright/test").Locator): Promise<string[]> {
  const texts = await composer.getByTestId("composer-course-select").locator("option").allTextContents();
  return texts.map((t) => t.trim()).filter((t) => COURSES.some((c) => c.title === t));
}

test("R2-G4: composer tag chips narrow the course select, report the match count, and toggle back to all courses", async ({
  page,
}) => {
  const composer = await openComposer(page, COURSES);

  const chips = composer.getByTestId("composer-tag-chip");
  // Distinct tags across the listed courses, sorted by name; untagged courses add no chip.
  await expect(chips).toHaveCount(2);
  await expect(chips.nth(0)).toHaveText("Biology");
  await expect(chips.nth(1)).toHaveText("Chemistry");
  await expect(chips.nth(0)).toHaveAttribute("aria-pressed", "false");
  expect(await courseOptions(composer)).toHaveLength(4);

  await composer.locator('[data-testid="composer-tag-chip"][data-tag-id="tag-bio"]').click();
  await expect(composer.locator('[data-testid="composer-tag-chip"][data-tag-id="tag-bio"]')).toHaveAttribute(
    "aria-pressed",
    "true"
  );
  await expect(composer.getByTestId("composer-tag-filter-status")).toHaveText(
    "2 courses match this tag. Select the tag again to show all courses."
  );
  await expect.poll(() => courseOptions(composer)).toEqual(["2028 Biology Theory", "2028 Biology Revision"]);

  // Switching chips: single active filter.
  await composer.locator('[data-testid="composer-tag-chip"][data-tag-id="tag-chem"]').click();
  await expect(composer.locator('[data-testid="composer-tag-chip"][data-tag-id="tag-bio"]')).toHaveAttribute(
    "aria-pressed",
    "false"
  );
  await expect(composer.getByTestId("composer-tag-filter-status")).toHaveText(
    "1 course matches this tag. Select the tag again to show all courses."
  );
  await expect.poll(() => courseOptions(composer)).toEqual(["2027 Chemistry Theory"]);

  // Pressing the active chip again clears the filter.
  await composer.locator('[data-testid="composer-tag-chip"][data-tag-id="tag-chem"]').click();
  await expect(composer.getByTestId("composer-tag-filter-status")).toHaveText("");
  await expect.poll(() => courseOptions(composer)).toHaveLength(4);
});

test("R2-G4: a selected course stays in the select even when the tag filter excludes it", async ({ page }) => {
  const composer = await openComposer(page, COURSES);
  await composer.getByTestId("composer-course-select").selectOption("course-chem-1");
  await composer.locator('[data-testid="composer-tag-chip"][data-tag-id="tag-bio"]').click();
  await expect
    .poll(() => courseOptions(composer))
    .toEqual(expect.arrayContaining(["2028 Biology Theory", "2028 Biology Revision", "2027 Chemistry Theory"]));
  expect(await courseOptions(composer)).not.toContain("General Skills");
  await expect(composer.getByTestId("composer-course-select")).toHaveValue("course-chem-1");
});

test("R2-G4: without tags on the server list (older backend), no chip row is rendered and every course is listed", async ({
  page,
}) => {
  const composer = await openComposer(
    page,
    COURSES.map(({ courseId, title }) => ({ courseId, title }))
  );
  await expect(composer.getByTestId("composer-course-select")).toBeVisible();
  await expect(composer.getByTestId("composer-tags")).toHaveCount(0);
  expect(await courseOptions(composer)).toHaveLength(4);
});
