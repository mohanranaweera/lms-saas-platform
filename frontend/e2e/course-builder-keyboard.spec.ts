import { test, expect, type Page } from "@playwright/test";
import {
  apiSuccess,
  fakeJwt,
  loginResponseBody,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * Keyboard-only course creation for a Teacher (`/teacher/courses/new`, which
 * opens `components/courses/admin/course-create-dialog.tsx`; the old
 * multi-step Course Builder is gone).
 *
 * A real keyboard-driven test, not a visual check (`docs/plans/MVP-008 Course
 * Management.md` §18, ui-ux.md §4): fields are reached with Tab, the payment
 * type is chosen by typing, the form is submitted with Enter, and focus stays
 * trapped inside the dialog — never a mouse click.
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

const CREATED_ID = "c0000000-0000-0000-0000-000000000099";

test.describe("course create dialog — keyboard-only (Teacher)", () => {
  test("every field is reachable with Tab, focus stays in the dialog, and Enter submits", async ({ page }) => {
    let createRequestBody: unknown = null;
    await loginAsTeacher(page);
    await page.route("**/api/v1/courses", async (route) => {
      if (route.request().method() !== "POST") {
        await route.fallback();
        return;
      }
      createRequestBody = route.request().postDataJSON();
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ id: CREATED_ID, name: "Intro to Biology", status: "DRAFT" })),
      });
    });

    await page.goto("/teacher/courses/new");
    const dialog = page.getByTestId("add-course-dialog");
    await expect(dialog.getByRole("heading", { name: "Create New Course" })).toBeVisible();

    // Opens on the dialog's Close button; the first Tab lands on Course name.
    await page.keyboard.press("Tab");
    await expect(dialog.getByTestId("add-course-name")).toBeFocused();
    await page.keyboard.type("Intro to Biology");

    // Teacher (read-only for a Teacher) -> payment-type help -> Payment Type.
    await page.keyboard.press("Tab");
    await expect(dialog.getByTestId("add-course-teachers-readonly")).toBeFocused();
    await page.keyboard.press("Tab");
    await page.keyboard.press("Tab");
    const paymentType = dialog.getByTestId("add-course-payment-type");
    await expect(paymentType).toBeFocused();
    await page.keyboard.type("Monthly");
    await expect(paymentType).toHaveValue("MONTHLY");

    await page.keyboard.press("Tab");
    await expect(dialog.getByTestId("add-course-price")).toBeFocused();
    await page.keyboard.type("3500");

    // Focus is trapped: tabbing past Create course wraps back into the dialog.
    for (let i = 0; i < 12; i++) {
      await page.keyboard.press("Tab");
      expect(await dialog.evaluate((el) => el.contains(document.activeElement))).toBe(true);
    }

    await dialog.getByTestId("add-course-price").focus();
    await page.keyboard.press("Enter");

    await expect(page).toHaveURL(new RegExp(`/teacher/courses/${CREATED_ID}/edit\\?created=1$`));
    expect(createRequestBody).toMatchObject({ name: "Intro to Biology", pricingModel: "MONTHLY", price: "3500.00" });
  });
});
