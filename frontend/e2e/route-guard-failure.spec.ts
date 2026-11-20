import { test, expect } from "@playwright/test";
import { apiError } from "./fixtures/auth-mocks";

test.describe("route guard session bootstrap failures", () => {
  test("does not redirect on a non-authentication refresh failure", async ({ page }) => {
    await page.route("**/v1/auth/refresh", async (route) => {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify(apiError("SERVICE_UNAVAILABLE", "The session service is unavailable.")),
      });
    });

    await page.goto("/student/dashboard");

    await expect(page).toHaveURL(/\/student\/dashboard$/);
    await expect(page.getByRole("alert").filter({ hasText: "The session service is unavailable." })).toBeVisible();
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
  });
});
