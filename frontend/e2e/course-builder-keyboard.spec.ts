import { test, expect, type Page } from "@playwright/test";
import {
  apiSuccess,
  fakeJwt,
  loginResponseBody,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * Full keyboard Tab-order traversal of the Teacher Course Builder create flow
 * (`/teacher/courses/new`, `components/courses/course-create-form.tsx`).
 *
 * This is a real keyboard-driven test, not a visual/screenshot check
 * (`docs/plans/MVP-008 Course Management.md` §18): every step transition,
 * field, and submission below is driven with `page.keyboard.press`/`type`
 * and asserted with `toBeFocused()`/`document.activeElement` — never a mouse
 * click to advance a step, select an option, or submit.
 *
 * No real backend runs in this environment (see `fixtures/auth-mocks.ts`).
 * `**\/v1/auth/refresh` is mocked alongside login (mirroring
 * `course-modules.spec.ts`'s pattern) because `TeacherNav` has no in-app link
 * to `/teacher/courses/new`, so reaching it requires a full `page.goto`
 * navigation after the UI login step, which drops the in-memory access token
 * unless the refresh-cookie-backed silent refresh is also mocked.
 */

async function loginAsTeacher(page: Page) {
  const token = fakeJwt({ role: "TEACHER" });
  await mockJson(page, "**/v1/auth/login", 200, apiSuccess(loginResponseBody(token)));
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
  await page.goto("/login");
  await page.getByLabel("Email").fill("teacher@example.com");
  await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/teacher\/dashboard$/);
}

function currentStepIndicator(page: Page) {
  return page.locator('[aria-current="step"]');
}

test.describe("course builder — keyboard-only traversal", () => {
  test("every step's fields, Back, Next, the Select, and final submission are reachable and operable via keyboard alone", async ({
    page,
  }) => {
    let createRequestBody: unknown = null;
    await loginAsTeacher(page);
    await page.route("**/api/v1/courses", async (route) => {
      if (route.request().method() === "POST") {
        createRequestBody = route.request().postDataJSON();
        await route.fulfill({
          status: 201,
          contentType: "application/json",
          body: JSON.stringify(
            apiSuccess({
              id: "c0000000-0000-0000-0000-000000000099",
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
              createdAt: new Date().toISOString(),
              updatedAt: new Date().toISOString(),
            })
          ),
        });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
    });

    await page.goto("/teacher/courses/new");
    await expect(page.getByRole("heading", { name: "Create a course" })).toBeVisible();

    // --- Step 1: Basics ------------------------------------------------
    // The Course Builder moves focus to the current step's heading on every
    // transition, including the initial mount (see course-create-form.tsx's
    // `useEffect` keyed on `stepIndex`) — this is the real, non-mouse
    // starting point for the Tab-order assertions below, not a test-seeded
    // one.
    const basicsHeading = page.getByRole("heading", { name: "Basics", exact: true });
    await expect(basicsHeading).toBeFocused();

    await page.keyboard.press("Tab");
    await expect(page.locator("#course-create-name")).toBeFocused();
    await page.keyboard.type("Intro to Biology");

    await page.keyboard.press("Tab");
    await expect(page.locator("#course-create-slug")).toBeFocused();
    await page.keyboard.type("intro-to-biology");

    await page.keyboard.press("Tab");
    await expect(page.locator("#course-create-category")).toBeFocused();
    await page.keyboard.type("Science");

    await page.keyboard.press("Tab");
    await expect(page.locator("#course-create-description")).toBeFocused();
    await page.keyboard.type("A beginner-friendly introduction to biology.");

    // Back is disabled on step 1, so it must not receive focus — Tab from
    // the last field lands directly on Next.
    await page.keyboard.press("Tab");
    const nextButtonStep1 = page.getByRole("button", { name: "Next", exact: true });
    await expect(nextButtonStep1).toBeFocused();
    await expect(page.getByRole("button", { name: "Back" })).toBeDisabled();

    await page.keyboard.press("Enter");

    // --- Step 2: Classification -----------------------------------------
    const classificationHeading = page.getByRole("heading", { name: "Classification", exact: true });
    await expect(classificationHeading).toBeFocused();
    await expect(currentStepIndicator(page)).toContainText("Classification");

    await page.keyboard.press("Tab");
    await expect(page.locator("#course-create-subject")).toBeFocused();

    await page.keyboard.press("Tab");
    await expect(page.locator("#course-create-stream")).toBeFocused();

    await page.keyboard.press("Tab");
    await expect(page.locator("#course-create-grade")).toBeFocused();

    await page.keyboard.press("Tab");
    await expect(page.locator("#course-create-academicYear")).toBeFocused();

    // Back is enabled from step 2 onward and precedes Next in visual/tab
    // order — confirm it is keyboard-reachable here (point 5 of the spec).
    await page.keyboard.press("Tab");
    const backButtonStep2 = page.getByRole("button", { name: "Back" });
    await expect(backButtonStep2).toBeFocused();
    await expect(backButtonStep2).toBeEnabled();

    await page.keyboard.press("Tab");
    const nextButtonStep2 = page.getByRole("button", { name: "Next", exact: true });
    await expect(nextButtonStep2).toBeFocused();

    // Activating Back (via Shift+Tab back to it, then Enter) must return
    // focus/state to Step 1 without touching the mouse.
    await page.keyboard.press("Shift+Tab");
    await expect(backButtonStep2).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(basicsHeading).toBeFocused();
    await expect(currentStepIndicator(page)).toContainText("Basics");
    // Values entered earlier on this step are preserved.
    await expect(page.locator("#course-create-name")).toHaveValue("Intro to Biology");

    // Re-advance back to Step 2, then all the way past it to Step 3, purely
    // via keyboard again.
    await page.keyboard.press("Tab"); // -> name
    await page.keyboard.press("Tab"); // -> slug
    await page.keyboard.press("Tab"); // -> category
    await page.keyboard.press("Tab"); // -> description
    await page.keyboard.press("Tab"); // -> Next (Back still disabled on step 1)
    await expect(page.getByRole("button", { name: "Next", exact: true })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(classificationHeading).toBeFocused();

    await page.keyboard.press("Tab"); // -> subject
    await page.keyboard.press("Tab"); // -> stream
    await page.keyboard.press("Tab"); // -> grade
    await page.keyboard.press("Tab"); // -> academicYear
    await page.keyboard.press("Tab"); // -> Back
    await page.keyboard.press("Tab"); // -> Next
    await expect(page.getByRole("button", { name: "Next", exact: true })).toBeFocused();
    await page.keyboard.press("Enter");

    // --- Step 3: Pricing --------------------------------------------------
    const pricingHeading = page.getByRole("heading", { name: "Pricing", exact: true });
    await expect(pricingHeading).toBeFocused();
    await expect(currentStepIndicator(page)).toContainText("Pricing");

    await page.keyboard.press("Tab");
    await expect(page.locator("#course-create-price")).toBeFocused();
    await page.keyboard.type("49.99");

    await page.keyboard.press("Tab"); // -> Back
    await expect(page.getByRole("button", { name: "Back" })).toBeFocused();
    await page.keyboard.press("Tab"); // -> Next
    await expect(page.getByRole("button", { name: "Next", exact: true })).toBeFocused();
    await page.keyboard.press("Enter");

    // --- Step 4: Enrollment & access ---------------------------------------
    const enrollmentHeading = page.getByRole("heading", { name: "Enrollment & access", exact: true });
    await expect(enrollmentHeading).toBeFocused();
    await expect(currentStepIndicator(page)).toContainText("Enrollment & access");

    await page.keyboard.press("Tab");
    await expect(page.locator("#course-create-enrollmentRule")).toBeFocused();

    await page.keyboard.press("Tab");
    await expect(page.locator("#course-create-accessDurationDays")).toBeFocused();

    await page.keyboard.press("Tab"); // -> Back
    await expect(page.getByRole("button", { name: "Back" })).toBeFocused();
    await page.keyboard.press("Tab"); // -> Next
    await expect(page.getByRole("button", { name: "Next", exact: true })).toBeFocused();
    await page.keyboard.press("Enter");

    // --- Step 5: Visibility -------------------------------------------------
    const visibilityHeading = page.getByRole("heading", { name: "Visibility", exact: true });
    await expect(visibilityHeading).toBeFocused();
    await expect(currentStepIndicator(page)).toContainText("Visibility");

    await page.keyboard.press("Tab");
    const statusTrigger = page.locator("#course-create-status");
    await expect(statusTrigger).toBeFocused();

    // Open the Select with the keyboard and choose "Public" — Base UI's
    // Select trigger opens on Enter (verified empirically: Space/ArrowDown
    // also open it) with a roving-tabindex listbox where the first option
    // ("Draft") is focused/highlighted immediately on open; ArrowDown moves
    // real DOM focus item-by-item, and Enter commits the highlighted option
    // and returns focus to the trigger — the standard listbox keyboard
    // contract, with no custom key handling layered on top (select.tsx is a
    // plain Base UI trigger/popup pair).
    await page.keyboard.press("Enter");
    const publicOption = page.getByRole("option", { name: "Public" });
    await expect(publicOption).toBeVisible();
    await expect(page.getByRole("option", { name: "Draft" })).toBeFocused();
    await page.keyboard.press("ArrowDown"); // -> Private
    await expect(page.getByRole("option", { name: "Private" })).toBeFocused();
    await page.keyboard.press("ArrowDown"); // -> Public
    await expect(publicOption).toBeFocused();
    await page.keyboard.press("Enter");
    // The trigger renders the friendly label ("Public"), matching the label
    // shown inside the option list — not the raw `CourseStatus` enum value.
    await expect(statusTrigger).toContainText("Public");
    await expect(statusTrigger).toBeFocused();

    await page.keyboard.press("Tab"); // -> Back
    await expect(page.getByRole("button", { name: "Back" })).toBeFocused();
    await page.keyboard.press("Tab"); // -> Create course
    const createButton = page.getByRole("button", { name: "Create course" });
    await expect(createButton).toBeFocused();

    await page.keyboard.press("Enter");

    await expect(page).toHaveURL(
      /\/teacher\/courses\/c0000000-0000-0000-0000-000000000099\/modules\?created=1$/
    );
    expect(createRequestBody).toMatchObject({
      name: "Intro to Biology",
      slug: "intro-to-biology",
      category: "Science",
      price: 49.99,
      status: "PUBLIC",
    });
  });
});
