import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiSuccess,
  fakeJwt,
  loginResponseBody,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * Student Management (MVP-006) — My Profile (`/student/profile`), the
 * Student's self-service view backed by `GET`/`PATCH /v1/students/me`. No
 * backend runs in this environment — every `/v1/students/me` call is
 * intercepted via `page.route()`.
 *
 * `test.slow()` plus a generous `NAV_TIMEOUT`: `/student/profile` is a new
 * route, and the first Turbopack dev-server compile of a not-yet-visited
 * route needs real headroom over the default 5s assertion timeout.
 *
 * This file's one previously-reported "flaky under parallel workers" test
 * ("a server error renders the shared error state with a retry action")
 * traced to a fully deterministic cause, not a timing race — see the comment
 * on that test and `student-management.spec.ts`'s top-of-file note for the
 * full verification (reproduced with `--workers=1` on a warm server/cache).
 */

test.beforeEach(() => {
  test.slow();
});

const NAV_TIMEOUT = 20_000;

function envelope(data: unknown) {
  return {
    success: true,
    data,
    error: null,
    timestamp: new Date().toISOString(),
    traceId: "test-trace-id",
  };
}

const ME_ENDPOINT = "**/v1/students/me";

const PROFILE = {
  id: "44444444-4444-4444-4444-444444444444",
  name: "Jamie Rivera",
  email: "jamie.rivera@example-institute.test",
  roleCode: "STUDENT",
  status: "ACTIVE",
};

async function loginAsStudent(page: Page) {
  const token = fakeJwt({ role: "STUDENT" });
  await mockJson(page, "**/v1/auth/login", 200, apiSuccess(loginResponseBody(token)));
  await mockJson(
    page,
    "**/v1/auth/refresh",
    200,
    apiSuccess(refreshResponseBody(fakeJwt({ role: "STUDENT" })))
  );
  await page.goto("/login");
  await page.getByLabel("Email").fill("jamie.rivera@example-institute.test");
  await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/student\/dashboard$/, { timeout: NAV_TIMEOUT });
}

test.describe("student profile — no id anywhere in this page", () => {
  test("the profile route and page never reference a student id (self-service only)", async ({
    page,
  }) => {
    await mockJson(page, ME_ENDPOINT, 200, envelope(PROFILE));
    await loginAsStudent(page);

    await page.getByRole("link", { name: "Profile" }).click();
    await expect(page).toHaveURL(/\/student\/profile$/, { timeout: NAV_TIMEOUT });
    // No `[id]`-shaped segment anywhere in the URL.
    expect(new URL(page.url()).pathname).toBe("/student/profile");
  });
});

test.describe("student profile — read-only fields and editable name", () => {
  test("renders read-only email, status, and role, and an editable name field", async ({ page }) => {
    await mockJson(page, ME_ENDPOINT, 200, envelope(PROFILE));
    await loginAsStudent(page);
    await page.getByRole("link", { name: "Profile" }).click();

    await expect(page.getByText(PROFILE.email)).toBeVisible({ timeout: NAV_TIMEOUT });
    await expect(page.getByText("Active", { exact: true })).toBeVisible();
    await expect(page.getByText("STUDENT", { exact: true })).toBeVisible();
    await expect(page.getByLabel("Name")).toHaveValue("Jamie Rivera");
  });

  test("saving a name change calls PATCH /v1/students/me and announces success", async ({ page }) => {
    let patchBody: unknown = null;
    await page.route(ME_ENDPOINT, async (route) => {
      if (route.request().method() === "PATCH") {
        patchBody = route.request().postDataJSON();
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(envelope({ ...PROFILE, name: "Jamie R. Rivera" })),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope(PROFILE)),
      });
    });
    await loginAsStudent(page);
    await page.getByRole("link", { name: "Profile" }).click();

    await page.getByLabel("Name").fill("Jamie R. Rivera");
    await page.getByRole("button", { name: "Save changes" }).click();

    // Two "Saved." nodes exist by design: an `aria-live` sr-only announcement
    // for assistive tech, and a visible confirmation for sighted users —
    // assert the visible one specifically (last in DOM order).
    await expect(page.getByText("Saved.").last()).toBeVisible();
    expect(patchBody).toEqual({ name: "Jamie R. Rivera" });
  });

  test("submitting an empty name is rejected client-side, no request made", async ({ page }) => {
    let patchRequestMade = false;
    await page.route(ME_ENDPOINT, async (route) => {
      if (route.request().method() === "PATCH") {
        patchRequestMade = true;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope(PROFILE)),
      });
    });
    await loginAsStudent(page);
    await page.getByRole("link", { name: "Profile" }).click();

    await page.getByLabel("Name").fill("");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.getByText("Name is required.")).toBeVisible();
    // Not just visible text — the input must be programmatically linked to
    // its error via aria-describedby, or a screen-reader user gets no
    // association between the two.
    await expect(page.getByLabel("Name")).toHaveAttribute("aria-describedby", "profile-name-error");
    expect(patchRequestMade).toBe(false);
  });

  test("saving announces the busy state while the request is in flight", async ({ page }) => {
    await page.route(ME_ENDPOINT, async (route) => {
      if (route.request().method() === "PATCH") {
        await new Promise((resolve) => setTimeout(resolve, 500));
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(envelope({ ...PROFILE, name: "Jamie R. Rivera" })),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope(PROFILE)),
      });
    });
    await loginAsStudent(page);
    await page.getByRole("link", { name: "Profile" }).click();

    await page.getByLabel("Name").fill("Jamie R. Rivera");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.getByRole("status").filter({ hasText: "Saving…" })).toBeVisible();
    await expect(page.locator("form[aria-busy='true']")).toBeVisible();
    await expect(page.getByText("Saved.").last()).toBeVisible();
  });

  test("a 403 on save surfaces an accessible alert instead of crashing", async ({ page }) => {
    await page.route(ME_ENDPOINT, async (route) => {
      if (route.request().method() === "PATCH") {
        await route.fulfill({
          status: 403,
          contentType: "application/json",
          body: JSON.stringify({
            success: false,
            data: null,
            error: {
              code: "FORBIDDEN",
              message: "You do not have permission to edit this profile.",
              fieldErrors: [],
            },
            timestamp: new Date().toISOString(),
            traceId: "test-trace-id",
          }),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope(PROFILE)),
      });
    });
    await loginAsStudent(page);
    await page.getByRole("link", { name: "Profile" }).click();

    await expect(page.getByLabel("Name")).toBeVisible({ timeout: NAV_TIMEOUT });
    await page.getByLabel("Name").fill("Someone Else");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "You do not have permission to edit this profile." })
    ).toBeVisible();
  });
});

test.describe("student profile — loading, error, and permission-denied states", () => {
  test("shows an accessible loading indicator while the profile is in flight", async ({ page }) => {
    await page.route(ME_ENDPOINT, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 500));
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope(PROFILE)),
      });
    });
    await loginAsStudent(page);
    await page.getByRole("link", { name: "Profile" }).click();

    await expect(page.getByRole("status").filter({ hasText: "Loading your profile…" })).toBeVisible();
    await expect(page.getByText(PROFILE.email)).toBeVisible();
  });

  test("a server error renders the shared error state with a retry action", async ({ page }) => {
    let requestCount = 0;
    await page.route(ME_ENDPOINT, async (route) => {
      requestCount += 1;
      // `QueryProvider`'s default `retry: 1` means a query that fails once
      // and then succeeds resolves via React Query's own silent automatic
      // retry, without the UI ever exposing a persistent "Try again" — the
      // mock must fail the first two requests (initial attempt + the one
      // automatic retry) to actually exhaust retries and reach the rendered
      // error state; the 3rd request is the explicit "Try again" click
      // below. See `student-management.spec.ts`'s equivalent test/comment.
      if (requestCount <= 2) {
        await route.fulfill({
          status: 500,
          contentType: "application/json",
          body: JSON.stringify({
            success: false,
            data: null,
            error: { code: "INTERNAL_ERROR", message: "Something went wrong.", fieldErrors: [] },
            timestamp: new Date().toISOString(),
            traceId: "test-trace-id",
          }),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope(PROFILE)),
      });
    });
    await loginAsStudent(page);
    await page.getByRole("link", { name: "Profile" }).click();

    await expect(page.getByRole("alert")).toBeVisible({ timeout: NAV_TIMEOUT });
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText(PROFILE.email)).toBeVisible();
  });

  test("a 403 renders the permission-denied state, not a generic error", async ({ page }) => {
    await mockJson(
      page,
      ME_ENDPOINT,
      403,
      apiError("FORBIDDEN", "You do not have permission to view this profile.")
    );
    await loginAsStudent(page);
    await page.getByRole("link", { name: "Profile" }).click();

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(page.getByText("You do not have permission to view this profile.")).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to your dashboard" })).toBeVisible();
  });
});
