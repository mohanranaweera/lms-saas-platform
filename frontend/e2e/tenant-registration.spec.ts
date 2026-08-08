import { test, expect, type Page } from "@playwright/test";

/**
 * Public tenant self-registration flow (`/register-institute`, TEN-1's frontend
 * slice). Deliberately not at `/register` — see that route's own disabled
 * placeholder, covered separately in `route-groups.spec.ts` /
 * `auth-disabled-forms.spec.ts`.
 *
 * The backend isn't running for this test suite, so every `POST
 * .../api/v1/tenant-registrations` call is intercepted via `page.route` and
 * fulfilled with a hand-built response matching the real `ApiResponse<T>`
 * envelope (`frontend/src/lib/api/types.ts`) — this proves the frontend's own
 * request/response handling and error-mapping logic, not the backend's behavior.
 */

const REGISTRATION_ENDPOINT = "**/api/v1/tenant-registrations";

function envelope(data: unknown) {
  return {
    success: true,
    data,
    error: null,
    timestamp: new Date().toISOString(),
    traceId: "test-trace-id",
  };
}

function errorEnvelope(code: string, message: string, fieldErrors: Array<{ field: string; message: string }> = []) {
  return {
    success: false,
    data: null,
    error: { code, message, fieldErrors },
    timestamp: new Date().toISOString(),
    traceId: "test-trace-id",
  };
}

async function fillValidForm(page: Page) {
  await page.getByLabel("Institute name").fill("Example Institute A");
  await page.getByLabel("Subdomain").fill("example-institute-a");
  await page.getByLabel("Requested plan").fill("Growth (up to 500 students)");
  await page.getByLabel("Contact name").fill("Jordan Example");
  await page.getByLabel("Contact email").fill("jordan@example-institute-a.test");
}

test.describe("tenant self-registration — accessible form", () => {
  test("every field has an associated visible label", async ({ page }) => {
    await page.goto("/register-institute");
    await expect(page.getByLabel("Institute name")).toBeVisible();
    await expect(page.getByLabel("Subdomain")).toBeVisible();
    await expect(page.getByLabel("Requested plan")).toBeVisible();
    await expect(page.getByLabel("Contact name")).toBeVisible();
    await expect(page.getByLabel("Contact email")).toBeVisible();
    await expect(page.getByLabel("Contact phone (optional)")).toBeVisible();
  });

  test("requested plan renders as a free-text input, not a dropdown", async ({ page }) => {
    await page.goto("/register-institute");
    const planField = page.getByLabel("Requested plan");
    const tagName = await planField.evaluate((element) => element.tagName);
    expect(tagName).toBe("INPUT");
  });
});

test.describe("tenant self-registration — client-side validation", () => {
  test("submitting an empty form shows inline required-field errors and makes no request", async ({
    page,
  }) => {
    let requestMade = false;
    await page.route(REGISTRATION_ENDPOINT, async (route) => {
      requestMade = true;
      await route.continue();
    });

    await page.goto("/register-institute");
    await page.getByRole("button", { name: "Submit application" }).click();

    await expect(page.getByText("Institute name is required.")).toBeVisible();
    await expect(page.getByText("Subdomain is required.")).toBeVisible();
    await expect(page.getByText("Requested plan is required.")).toBeVisible();
    expect(requestMade).toBe(false);
  });

  test("a reserved subdomain is rejected client-side before submission", async ({ page }) => {
    let requestMade = false;
    await page.route(REGISTRATION_ENDPOINT, async (route) => {
      requestMade = true;
      await route.continue();
    });

    await page.goto("/register-institute");
    await fillValidForm(page);
    await page.getByLabel("Subdomain").fill("admin");
    await page.getByRole("button", { name: "Submit application" }).click();

    await expect(page.getByText("This subdomain is reserved", { exact: false })).toBeVisible();
    expect(requestMade).toBe(false);
  });

  test("a malformed subdomain (uppercase letters) is rejected client-side with a format error, before submission", async ({
    page,
  }) => {
    let requestMade = false;
    await page.route(REGISTRATION_ENDPOINT, async (route) => {
      requestMade = true;
      await route.continue();
    });

    await page.goto("/register-institute");
    await fillValidForm(page);
    await page.getByLabel("Subdomain").fill("UPPER-Case");
    await page.getByRole("button", { name: "Submit application" }).click();

    await expect(
      page.getByText("Use lowercase letters, numbers, and hyphens only", { exact: false })
    ).toBeVisible();
    expect(requestMade).toBe(false);
  });

  test("a subdomain starting with a hyphen is rejected client-side with a format error, before submission", async ({
    page,
  }) => {
    let requestMade = false;
    await page.route(REGISTRATION_ENDPOINT, async (route) => {
      requestMade = true;
      await route.continue();
    });

    await page.goto("/register-institute");
    await fillValidForm(page);
    await page.getByLabel("Subdomain").fill("-leading-hyphen");
    await page.getByRole("button", { name: "Submit application" }).click();

    await expect(
      page.getByText("Use lowercase letters, numbers, and hyphens only", { exact: false })
    ).toBeVisible();
    expect(requestMade).toBe(false);
  });
});

test.describe("tenant self-registration — submission states", () => {
  test("loading state disables the form and announces busy status", async ({ page }) => {
    await page.route(REGISTRATION_ENDPOINT, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 500));
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(
          envelope({ id: "11111111-1111-1111-1111-111111111111", subdomain: "example-institute-a", status: "pending_approval" })
        ),
      });
    });

    await page.goto("/register-institute");
    await fillValidForm(page);

    const form = page.locator("form");
    await page.getByRole("button", { name: "Submit application" }).click();

    await expect(form).toHaveAttribute("aria-busy", "true");
    await expect(page.getByRole("button", { name: "Submitting…" })).toBeDisabled();
    await expect(page.getByLabel("Institute name")).toBeDisabled();
  });

  test("successful submission shows a pending-review confirmation, never an active/approved claim", async ({
    page,
  }) => {
    await page.route(REGISTRATION_ENDPOINT, async (route) => {
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(
          envelope({ id: "11111111-1111-1111-1111-111111111111", subdomain: "example-institute-a", status: "pending_approval" })
        ),
      });
    });

    await page.goto("/register-institute");
    await fillValidForm(page);
    await page.getByRole("button", { name: "Submit application" }).click();

    await expect(page.getByRole("heading", { name: "Application submitted" })).toBeVisible();
    await expect(page.getByText("pending review", { exact: false })).toBeVisible();
    await expect(page.getByText("example-institute-a")).toBeVisible();
    await expect(page.getByText("not yet active", { exact: false })).toBeVisible();

    // Must never claim/imply activation — enrollment/tenant activation is a
    // backend-confirmed event only, never asserted from a frontend success page.
    await expect(page.getByText(/\bis now active\b/i)).toHaveCount(0);
    await expect(page.getByText(/\bapproved\b/i)).toHaveCount(0);
  });

  test("server-side field validation errors render inline under the matching fields", async ({
    page,
  }) => {
    await page.route(REGISTRATION_ENDPOINT, async (route) => {
      await route.fulfill({
        status: 400,
        contentType: "application/json",
        body: JSON.stringify(
          errorEnvelope("VALIDATION_ERROR", "Validation failed", [
            { field: "contactEmail", message: "Contact email is already associated with another application." },
          ])
        ),
      });
    });

    await page.goto("/register-institute");
    await fillValidForm(page);
    await page.getByRole("button", { name: "Submit application" }).click();

    await expect(
      page.getByText("Contact email is already associated with another application.")
    ).toBeVisible();
    // A field-shaped error must not also render the page-level ErrorState banner.
    await expect(page.getByRole("alert").filter({ hasText: "Error code" })).toHaveCount(0);
  });

  test("duplicate-subdomain conflict (409) surfaces as a field-level error on subdomain, not a page banner", async ({
    page,
  }) => {
    await page.route(REGISTRATION_ENDPOINT, async (route) => {
      await route.fulfill({
        status: 409,
        contentType: "application/json",
        body: JSON.stringify(
          errorEnvelope("CONFLICT", "A tenant with this subdomain already exists")
        ),
      });
    });

    await page.goto("/register-institute");
    await fillValidForm(page);
    await page.getByRole("button", { name: "Submit application" }).click();

    const subdomainField = page.getByLabel("Subdomain");
    await expect(page.getByText("A tenant with this subdomain already exists")).toBeVisible();
    await expect(subdomainField).toHaveAttribute("aria-invalid", "true");
    await expect(page.getByText("Error code", { exact: false })).toHaveCount(0);
  });

  test("a network failure renders the shared page-level error state with a retry action", async ({
    page,
  }) => {
    await page.route(REGISTRATION_ENDPOINT, async (route) => {
      await route.abort("failed");
    });

    await page.goto("/register-institute");
    await fillValidForm(page);
    await page.getByRole("button", { name: "Submit application" }).click();

    await expect(page.getByRole("alert").filter({ hasText: "Unable to reach the server" })).toBeVisible();
    const retry = page.getByRole("button", { name: "Try again" });
    await expect(retry).toBeVisible();

    await retry.click();
    await expect(page.getByRole("button", { name: "Submit application" })).toBeVisible();
  });
});
