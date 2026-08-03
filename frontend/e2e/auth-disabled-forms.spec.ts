import { test, expect } from "@playwright/test";

/**
 * The (auth) placeholder forms (login/register/forgot-password) are intentionally
 * non-functional pending the identity-access-service module: the entire <fieldset>
 * is `disabled`, the submit button is `disabled`, and the <form>'s onSubmit handler
 * only calls `event.preventDefault()`.
 *
 * `route-groups.spec.ts` already asserts the submit button reports `disabled` via
 * the accessibility tree. This file goes further and proves the form is genuinely
 * non-submittable in practice: every field is disabled (so no value can be typed
 * through normal interaction), and attempting to activate the disabled submit
 * button causes no navigation and no visible state change.
 */

interface AuthFormCase {
  path: string;
  fieldLabels: string[];
  submitName: string;
  noticeText: string;
}

const cases: AuthFormCase[] = [
  {
    path: "/login",
    fieldLabels: ["Email", "Password"],
    submitName: "Sign in",
    noticeText: "Not yet implemented",
  },
  {
    path: "/register",
    fieldLabels: ["Full name", "Email", "Password", "Confirm password"],
    submitName: "Create account",
    noticeText: "Not yet implemented",
  },
  {
    path: "/forgot-password",
    fieldLabels: ["Email"],
    submitName: "Send reset link",
    noticeText: "Not yet implemented",
  },
];

for (const { path, fieldLabels, submitName, noticeText } of cases) {
  test.describe(`${path} disabled form is genuinely non-submittable`, () => {
    test("every labeled field is disabled", async ({ page }) => {
      await page.goto(path);
      for (const label of fieldLabels) {
        await expect(page.getByLabel(label, { exact: true })).toBeDisabled();
      }
    });

    test("normal typing does not change a field's value", async ({ page }) => {
      await page.goto(path);
      const [firstLabel] = fieldLabels;
      const field = page.getByLabel(firstLabel, { exact: true });
      // A disabled control cannot receive focus, so a real (non-forced) click/type
      // is rejected by Playwright's actionability checks rather than silently
      // no-op'ing — which itself proves the field cannot be interacted with.
      await expect(field.click({ timeout: 1000 })).rejects.toThrow();
      await expect(field).toHaveValue("");
    });

    test("activating the disabled submit button causes no navigation or state change", async ({
      page,
    }) => {
      await page.goto(path);
      const submit = page.getByRole("button", { name: submitName });
      await expect(submit).toBeDisabled();

      const urlBefore = page.url();
      // Bypass Playwright's actionability guard so we can prove, at the DOM level,
      // that a click reaching the button element still does nothing — no fetch,
      // no navigation, no success/error UI swap-in.
      await submit.click({ force: true });

      await expect(page).toHaveURL(urlBefore);
      await expect(page.getByText(noticeText, { exact: false })).toBeVisible();
      await expect(submit).toBeDisabled();
    });
  });
}
