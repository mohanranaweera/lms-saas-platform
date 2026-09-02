import { test, expect } from "@playwright/test";
import {
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * Structural/manual accessibility smoke checks. No new npm dependency (e.g.
 * @axe-core/playwright) is introduced for this pass — these assertions cover form
 * label association, landmark labeling, and keyboard-reachable mobile navigation
 * using Playwright's built-in role/label queries only.
 */

test.describe("accessible form labels", () => {
  test("login form fields are associated with visible labels", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByLabel("Email")).toBeVisible();
    // `exact: true` avoids matching the password show/hide toggle, whose
    // accessible name ("Show password"/"Hide password") otherwise substring-
    // matches a loose "Password" query — same pattern already used below for
    // the register form's Password field.
    await expect(page.getByLabel("Password", { exact: true })).toBeVisible();
  });

  test("register form fields are associated with visible labels", async ({ page }) => {
    await page.goto("/register");
    await expect(page.getByLabel("Full name")).toBeVisible();
    await expect(page.getByLabel("Email")).toBeVisible();
    await expect(page.getByLabel("Password", { exact: true })).toBeVisible();
    await expect(page.getByLabel("Confirm password")).toBeVisible();
  });

  test("forgot-password form field is associated with a visible label", async ({
    page,
  }) => {
    await page.goto("/forgot-password");
    await expect(page.getByLabel("Email")).toBeVisible();
  });
});

test.describe("landmarks", () => {
  test("dashboard shell exposes a labeled primary navigation landmark", async ({
    page,
  }) => {
    // `/teacher/dashboard` is guarded by `RouteGuard` (MVP-014 Teacher
    // Dashboard) — mock a successful silent refresh so the guard resolves
    // without a real backend, matching this file's "no npm dependency, real
    // request interception only" posture.
    const token = fakeJwt({ role: "TEACHER" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
    // The nav landmark renders independent of the courses fetch, but every
    // request this environment makes must be mocked (see teacher-dashboard.spec.ts's
    // own module doc) rather than left to hit a real, unavailable backend.
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));
    await page.goto("/teacher/dashboard");
    await expect(page.getByRole("navigation", { name: "Primary" })).toBeVisible();
  });
});

test.describe("mobile navigation", () => {
  test.use({ viewport: { width: 375, height: 812 } });

  test.beforeEach(async ({ page }) => {
    // `/student/dashboard` is guarded by `RouteGuard` (MVP-006 Student
    // Management) — mock a successful silent refresh so the guard resolves
    // without a real backend, matching this file's "no npm dependency, real
    // request interception only" posture.
    const token = fakeJwt({ role: "STUDENT" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
  });

  test("hamburger trigger is labeled and opens a focus-trapped drawer", async ({
    page,
  }) => {
    await page.goto("/student/dashboard");

    const trigger = page.getByRole("button", { name: "Open navigation menu" });
    await expect(trigger).toBeVisible();

    await trigger.click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("link", { name: "Dashboard" })).toBeVisible();

    // Base UI's Dialog traps focus and exposes an accessible close control.
    await expect(dialog.getByRole("button", { name: "Close" })).toBeVisible();
  });

  test("focus is trapped inside the open drawer and returns to the trigger on close", async ({
    page,
  }) => {
    await page.goto("/student/dashboard");

    const trigger = page.getByRole("button", { name: "Open navigation menu" });
    await trigger.click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();

    // Tab well past the number of focusable elements inside the drawer (the
    // "Dashboard" link and the "Close" button) and confirm focus never escapes
    // to the underlying page (e.g. back to the hamburger trigger, or to links in
    // the hidden desktop sidebar) while the dialog is open.
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press("Tab");
      await expect(dialog.locator(":focus")).toHaveCount(1);
    }

    await page.keyboard.press("Escape");
    await expect(dialog).not.toBeVisible();
    await expect(trigger).toBeFocused();
  });

  test("drawer closes when a nav link inside it is activated", async ({ page }) => {
    await page.goto("/student/dashboard");

    const trigger = page.getByRole("button", { name: "Open navigation menu" });
    await trigger.click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();

    await dialog.getByRole("link", { name: "Dashboard" }).click();

    await expect(dialog).not.toBeVisible();
  });
});
