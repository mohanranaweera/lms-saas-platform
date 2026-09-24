import { test, expect } from "@playwright/test";

/**
 * `/forgot-password` remains an intentionally non-functional placeholder form
 * pending its own backend story (no AUTH-1/2/3 acceptance criterion covers it
 * — see docs/plans/MVP-002 Authentication Foundation.md §4/§6): the entire
 * <fieldset> is `disabled`, the submit button is `disabled`, and the
 * <form>'s onSubmit handler only calls `event.preventDefault()`.
 *
 * `/login` is a real, wired-up form (identity-access-service's
 * `POST /v1/auth/login`) and is intentionally excluded from this file — see
 * `route-groups.spec.ts`'s "auth route group" describe block for its
 * (now-enabled) coverage instead.
 *
 * `/register` was the third member of this file's case table until Wave 3
 * (PAR-03-01) replaced its disabled placeholder with a real, submitting
 * student self-registration form — its coverage now lives in
 * `student-registration.spec.ts` instead of here.
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
