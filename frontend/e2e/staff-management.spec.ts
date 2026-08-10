import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiSuccess,
  fakeJwt,
  fulfillJson,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * Staff Management (MVP-005) — Tenant Admin portal, wired to the real backend
 * surface (`POST/GET /v1/staff`, `GET /v1/staff/{id}`,
 * `backend/.../staff/web/StaffController.java`).
 *
 * No backend runs in this environment (see `fixtures/auth-mocks.ts`), so
 * every scenario mocks `page.route()`. Rather than driving each test through
 * the full login form, most scenarios establish a session directly via a
 * mocked `POST /v1/auth/refresh` response carrying a role claim (the same
 * "no cached token" path `token-refresh.spec.ts` exercises) — this is
 * equivalent to a real session for `AuthProvider`'s purposes and keeps each
 * test focused on the staff screens rather than re-proving login mechanics.
 */

interface StaffAccountBody {
  id: string;
  name: string;
  email: string;
  roleCode: string;
  status: string;
}

function staffAccount(overrides: Partial<StaffAccountBody> = {}): StaffAccountBody {
  return {
    id: "staff-1",
    name: "Ada Lovelace",
    email: "ada@example.com",
    roleCode: "FINANCE_STAFF",
    status: "ACTIVE",
    ...overrides,
  };
}

/** Establishes an in-memory session with the given role, without visiting `/login`. */
async function mockRefresh(page: Page, role: string): Promise<void> {
  await mockJson(
    page,
    "**/v1/auth/refresh",
    200,
    apiSuccess(refreshResponseBody(fakeJwt({ role })))
  );
}

test.describe("staff list — unauthenticated", () => {
  test("redirects to login when no session can be established", async ({ page }) => {
    await mockJson(
      page,
      "**/v1/auth/refresh",
      401,
      apiError("INVALID_REFRESH_TOKEN", "Refresh token is invalid")
    );

    await page.goto("/tenant-admin/staff");

    await expect(page).toHaveURL(/\/login\?reason=session_expired$/);
  });
});

test.describe("staff list — permission denied", () => {
  test("renders the permission-denied state on a real backend 403", async ({ page }) => {
    await mockRefresh(page, "TEACHER");
    await mockJson(
      page,
      "**/v1/staff",
      403,
      apiError("FORBIDDEN", "You do not have permission to perform this action.")
    );

    await page.goto("/tenant-admin/staff");

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(
      page.getByText("You do not have permission to perform this action.")
    ).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to your dashboard" })).toBeVisible();
  });
});

test.describe("staff list — loading state", () => {
  test("shows an accessible loading indicator while staff are fetched", async ({ page }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await page.route("**/v1/staff", async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 400));
      await fulfillJson(route, 200, apiSuccess([staffAccount()]));
    });

    await page.goto("/tenant-admin/staff");

    await expect(page.getByRole("status").filter({ hasText: "Loading staff" })).toBeVisible();
    // Scoped to the table: at default desktop viewport both the table and the
    // (CSS-hidden) mobile card list are in the DOM, so an unscoped
    // `getByText("Ada Lovelace")` is a strict-mode violation (matches both).
    await expect(page.getByRole("table").getByText("Ada Lovelace")).toBeVisible();
  });
});

test.describe("staff list — empty state", () => {
  test("a Tenant Admin sees a contextual empty state with an Add staff action", async ({
    page,
  }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/staff", 200, apiSuccess([]));

    await page.goto("/tenant-admin/staff");

    await expect(page.getByText("No staff accounts yet")).toBeVisible();
    // Header CTA (always visible for a Tenant Admin) is a link to /new.
    await expect(page.getByRole("link", { name: "Add staff" })).toBeVisible();
    // Empty-state CTA is a distinct, real button that also routes to /new.
    await page.getByRole("button", { name: "Add staff" }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/staff\/new$/);
  });

  test("a non-Tenant-Admin role never sees the Add staff CTA (UX convenience only)", async ({
    page,
  }) => {
    await mockRefresh(page, "TEACHER");
    await mockJson(page, "**/v1/staff", 200, apiSuccess([]));

    await page.goto("/tenant-admin/staff");

    await expect(page.getByText("No staff accounts yet")).toBeVisible();
    await expect(page.getByRole("link", { name: "Add staff" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Add staff" })).toHaveCount(0);
  });
});

test.describe("staff list — populated", () => {
  test("renders every staff account with a role and status badge (icon + text)", async ({
    page,
  }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/staff",
      200,
      apiSuccess([
        staffAccount({
          id: "staff-1",
          name: "Ada Lovelace",
          email: "ada@example.com",
          roleCode: "FINANCE_STAFF",
          status: "ACTIVE",
        }),
        staffAccount({
          id: "staff-2",
          name: "Grace Hopper",
          email: "grace@example.com",
          roleCode: "READ_ONLY_AUDITOR",
          status: "SUSPENDED",
        }),
      ])
    );

    await page.goto("/tenant-admin/staff");

    const table = page.getByRole("table");
    await expect(table).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Name" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Email" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Role" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Status" })).toBeVisible();

    await expect(table.getByText("Ada Lovelace")).toBeVisible();
    await expect(table.getByText("ada@example.com")).toBeVisible();
    await expect(table.getByText("Finance Staff")).toBeVisible();

    await expect(table.getByText("Grace Hopper")).toBeVisible();
    await expect(table.getByText("Read-only Auditor")).toBeVisible();
    await expect(table.getByText("Suspended")).toBeVisible();
  });

  test("clicking a staff member's name navigates to their detail page", async ({ page }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/staff", 200, apiSuccess([staffAccount()]));
    await mockJson(page, "**/v1/staff/staff-1", 200, apiSuccess(staffAccount()));

    await page.goto("/tenant-admin/staff");
    await page.getByRole("link", { name: "Ada Lovelace" }).click();

    await expect(page).toHaveURL(/\/tenant-admin\/staff\/staff-1$/);
    await expect(page.getByRole("heading", { name: "Ada Lovelace" })).toBeVisible();
  });
});

test.describe("staff list — generic server error", () => {
  test("a 500 renders the generic error state with a working retry, not permission-denied", async ({
    page,
  }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    let requestCount = 0;
    await page.route("**/v1/staff", async (route) => {
      requestCount += 1;
      // React Query's shared client config (`components/providers/query-provider.tsx`)
      // sets `retry: 1`, so the first render already costs 2 requests (the
      // initial attempt plus one automatic retry) before `ErrorState` renders
      // and a manual "Try again" click becomes possible — only the 3rd
      // request (the manual retry) should succeed.
      if (requestCount <= 2) {
        await fulfillJson(route, 500, apiError("INTERNAL_ERROR", "Something went wrong on our end."));
        return;
      }
      await fulfillJson(route, 200, apiSuccess([staffAccount()]));
    });

    await page.goto("/tenant-admin/staff");

    const errorState = page.getByRole("alert").filter({ hasText: "Something went wrong on our end." });
    await expect(errorState).toBeVisible();
    // A 500 must never be misreported as a permission problem.
    await expect(page.getByText("You don't have permission to view this.")).toHaveCount(0);

    await page.getByRole("button", { name: "Try again" }).click();

    await expect(page.getByRole("table").getByText("Ada Lovelace")).toBeVisible();
    expect(requestCount).toBe(3);
  });

  test("a network failure (no response) also renders the generic error state", async ({ page }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await page.route("**/v1/staff", async (route) => {
      await route.abort("failed");
    });

    await page.goto("/tenant-admin/staff");

    await expect(page.getByRole("alert")).toBeVisible();
    await expect(page.getByText("You don't have permission to view this.")).toHaveCount(0);
  });
});

test.describe("staff list — unrecognized role/status values", () => {
  test("a roleCode or status outside the known set still renders (raw value, no crash)", async ({
    page,
  }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/staff",
      200,
      apiSuccess([
        staffAccount({
          id: "staff-3",
          name: "Future Role",
          roleCode: "SOME_FUTURE_ROLE",
          status: "PENDING_ACTIVATION",
        }),
      ])
    );

    await page.goto("/tenant-admin/staff");

    const table = page.getByRole("table");
    await expect(table).toBeVisible();
    await expect(table.getByText("Future Role")).toBeVisible();
    await expect(table.getByText("SOME_FUTURE_ROLE")).toBeVisible();
    await expect(table.getByText("PENDING_ACTIVATION")).toBeVisible();
  });
});

test.describe("staff list — responsive behavior", () => {
  test("below md, the table converts to a stacked card list", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/staff", 200, apiSuccess([staffAccount()]));

    await page.goto("/tenant-admin/staff");

    await expect(page.getByRole("table")).toBeHidden();
    await expect(page.getByRole("list").getByText("Ada Lovelace")).toBeVisible();
  });

  test("at md and above, the table is shown instead of the card list", async ({ page }) => {
    await page.setViewportSize({ width: 1024, height: 800 });
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/staff", 200, apiSuccess([staffAccount()]));

    await page.goto("/tenant-admin/staff");

    await expect(page.getByRole("table")).toBeVisible();
  });
});

test.describe("tenant admin nav", () => {
  test("the Staff nav link navigates to the staff list", async ({ page }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/staff", 200, apiSuccess([]));

    await page.goto("/tenant-admin/dashboard");
    await page.getByRole("link", { name: "Staff" }).click();

    await expect(page).toHaveURL(/\/tenant-admin\/staff$/);
    await expect(page.getByRole("heading", { name: "Staff" })).toBeVisible();
  });
});

async function fillStaffForm(
  page: Page,
  values: { name: string; email: string; password: string; role: string }
) {
  await page.getByLabel("Name").fill(values.name);
  await page.getByLabel("Email").fill(values.email);
  await page.getByLabel("Temporary password").fill(values.password);
  await page.getByLabel(values.role).check();
}

test.describe("add staff — accessible form", () => {
  test("every field has a visible, associated label, and the role picker is a fieldset", async ({
    page,
  }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/staff/new");

    await expect(page.getByLabel("Name")).toBeVisible();
    await expect(page.getByLabel("Email")).toBeVisible();
    await expect(page.getByLabel("Temporary password")).toBeVisible();
    await expect(page.getByRole("group", { name: "Role" })).toBeVisible();
    await expect(page.getByLabel("Finance Staff")).toBeVisible();
    await expect(page.getByLabel("Read-only Auditor")).toBeVisible();
  });
});

test.describe("add staff — client-side validation", () => {
  test("a too-short password is rejected before any request is sent", async ({ page }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    let postCalled = false;
    await page.route("**/v1/staff", async (route) => {
      if (route.request().method() === "POST") postCalled = true;
      await route.continue();
    });

    await page.goto("/tenant-admin/staff/new");
    await fillStaffForm(page, {
      name: "New Person",
      email: "new@example.com",
      password: "short",
      role: "Finance Staff",
    });
    await page.getByRole("button", { name: "Create staff account" }).click();

    await expect(page.getByText("Password must be at least 8 characters.")).toBeVisible();
    expect(postCalled).toBe(false);
  });

  test("submitting with no role selected is rejected client-side", async ({ page }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    let postCalled = false;
    await page.route("**/v1/staff", async (route) => {
      if (route.request().method() === "POST") postCalled = true;
      await route.continue();
    });

    await page.goto("/tenant-admin/staff/new");
    await page.getByLabel("Name").fill("New Person");
    await page.getByLabel("Email").fill("new@example.com");
    await page.getByLabel("Temporary password").fill("correct-horse-battery-staple");
    await page.getByRole("button", { name: "Create staff account" }).click();

    await expect(page.getByText("Select a role.")).toBeVisible();
    expect(postCalled).toBe(false);
  });
});

test.describe("add staff — success", () => {
  test("creating a staff account redirects to their own detail page", async ({ page }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    const created = staffAccount({
      id: "staff-9",
      name: "New Person",
      email: "new@example.com",
      roleCode: "COURSE_COORDINATOR",
      status: "ACTIVE",
    });

    let capturedBody: unknown;
    await page.route("**/v1/staff", async (route) => {
      if (route.request().method() === "POST") {
        capturedBody = route.request().postDataJSON();
        await fulfillJson(route, 201, apiSuccess(created));
        return;
      }
      await route.continue();
    });
    await mockJson(page, "**/v1/staff/staff-9", 200, apiSuccess(created));

    await page.goto("/tenant-admin/staff/new");
    await fillStaffForm(page, {
      name: "New Person",
      email: "new@example.com",
      password: "correct-horse-battery-staple",
      role: "Course Coordinator",
    });
    await page.getByRole("button", { name: "Create staff account" }).click();

    await expect(page).toHaveURL(/\/tenant-admin\/staff\/staff-9$/);
    await expect(page.getByRole("heading", { name: "New Person" })).toBeVisible();
    expect(capturedBody).toMatchObject({
      name: "New Person",
      email: "new@example.com",
      password: "correct-horse-battery-staple",
      roleCode: "COURSE_COORDINATOR",
    });
  });
});

test.describe("add staff — backend validation errors", () => {
  test("maps VALIDATION_ERROR field errors onto the matching fields", async ({ page }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/staff",
      400,
      apiError("VALIDATION_ERROR", "Validation failed", [
        { field: "name", message: "Name must be 255 characters or fewer." },
      ])
    );

    await page.goto("/tenant-admin/staff/new");
    await fillStaffForm(page, {
      name: "New Person",
      email: "new@example.com",
      password: "correct-horse-battery-staple",
      role: "Finance Staff",
    });
    await page.getByRole("button", { name: "Create staff account" }).click();

    await expect(page.getByText("Name must be 255 characters or fewer.")).toBeVisible();
  });

  test("a field error for a field this form doesn't render falls back to the page-level error", async ({
    page,
  }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/staff",
      400,
      apiError("VALIDATION_ERROR", "Validation failed", [
        { field: "tenantId", message: "Tenant could not be resolved." },
      ])
    );

    await page.goto("/tenant-admin/staff/new");
    await fillStaffForm(page, {
      name: "New Person",
      email: "new@example.com",
      password: "correct-horse-battery-staple",
      role: "Finance Staff",
    });
    await page.getByRole("button", { name: "Create staff account" }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "Some information couldn't be validated." })
    ).toBeVisible();
    await expect(page.getByText("Tenant could not be resolved.")).toBeVisible();
  });
});

test.describe("add staff — duplicate email", () => {
  test("a 409 CONFLICT is attributed to the email field, not a page banner", async ({ page }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/staff",
      409,
      apiError("CONFLICT", "A staff account with this email already exists")
    );

    await page.goto("/tenant-admin/staff/new");
    await fillStaffForm(page, {
      name: "New Person",
      email: "dup@example.com",
      password: "correct-horse-battery-staple",
      role: "Finance Staff",
    });
    await page.getByRole("button", { name: "Create staff account" }).click();

    const inlineError = page
      .getByRole("alert")
      .filter({ hasText: "A staff account with this email already exists" });
    await expect(inlineError).toBeVisible();
  });
});

test.describe("add staff — permission denied on submit", () => {
  test("a 403 on submit renders permission-denied copy, not a retry-oriented error box", async ({
    page,
  }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/staff",
      403,
      apiError("FORBIDDEN", "You do not have permission to perform this action.")
    );

    await page.goto("/tenant-admin/staff/new");
    await fillStaffForm(page, {
      name: "New Person",
      email: "new@example.com",
      password: "correct-horse-battery-staple",
      role: "Finance Staff",
    });
    await page.getByRole("button", { name: "Create staff account" }).click();

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(
      page.getByText("You do not have permission to perform this action.")
    ).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to your dashboard" })).toBeVisible();
    // The form itself is gone — retrying would just fail identically.
    await expect(page.getByRole("button", { name: "Create staff account" })).toHaveCount(0);
  });
});

test.describe("add staff — busy state", () => {
  test("disables the form and announces progress while the mutation is pending", async ({
    page,
  }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await page.route("**/v1/staff", async (route) => {
      if (route.request().method() !== "POST") {
        await route.continue();
        return;
      }
      await new Promise((resolve) => setTimeout(resolve, 400));
      await fulfillJson(route, 201, apiSuccess(staffAccount({ id: "staff-9" })));
    });
    await mockJson(page, "**/v1/staff/staff-9", 200, apiSuccess(staffAccount({ id: "staff-9" })));

    await page.goto("/tenant-admin/staff/new");
    await fillStaffForm(page, {
      name: "New Person",
      email: "new@example.com",
      password: "correct-horse-battery-staple",
      role: "Finance Staff",
    });
    await page.getByRole("button", { name: "Create staff account" }).click();

    await expect(page.getByRole("button", { name: "Creating…" })).toBeDisabled();
    await expect(page.getByLabel("Name")).toBeDisabled();
  });
});

test.describe("staff detail", () => {
  test("renders name, email, role, and status", async ({ page }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/staff/staff-1", 200, apiSuccess(staffAccount()));

    await page.goto("/tenant-admin/staff/staff-1");

    await expect(page.getByRole("heading", { name: "Ada Lovelace" })).toBeVisible();
    await expect(page.getByText("ada@example.com")).toBeVisible();
    await expect(page.getByText("Finance Staff")).toBeVisible();
    await expect(page.getByText("Active", { exact: true })).toBeVisible();
  });

  test("renders a generic error state (not permission-denied) for a 404", async ({ page }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/staff/missing-id",
      404,
      apiError("NOT_FOUND", "Staff account not found")
    );

    await page.goto("/tenant-admin/staff/missing-id");

    await expect(page.getByText("Staff account not found")).toBeVisible();
    await expect(page.getByText("You don't have permission")).toHaveCount(0);
  });

  test("renders the permission-denied state for a 403", async ({ page }) => {
    await mockRefresh(page, "TEACHER");
    await mockJson(
      page,
      "**/v1/staff/staff-1",
      403,
      apiError("FORBIDDEN", "You do not have permission to perform this action.")
    );

    await page.goto("/tenant-admin/staff/staff-1");

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
  });

  test("has no edit, delete, deactivate, or password-reset controls", async ({ page }) => {
    await mockRefresh(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/staff/staff-1", 200, apiSuccess(staffAccount()));

    await page.goto("/tenant-admin/staff/staff-1");
    await expect(page.getByRole("heading", { name: "Ada Lovelace" })).toBeVisible();

    for (const name of [/^edit$/i, /^delete$/i, /^deactivate$/i, /reset password/i]) {
      await expect(page.getByRole("button", { name })).toHaveCount(0);
    }
  });
});
