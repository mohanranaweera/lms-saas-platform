import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Wave 1 (Tenant Admin IA + Configuration Framework) — Staff Management list
 * (`/tenant-admin/staff`, MVP-005 `STAFF-1`) and the read-only Roles &
 * Permissions catalog (`/tenant-admin/roles-permissions`, RBAC-1).
 *
 * No real backend runs in this environment (see
 * `fixtures/auth-mocks.ts`'s module doc) — every `/v1/staff*`/`/v1/roles*`
 * call is intercepted via `page.route()`, shaped like the documented
 * `ApiResponse<T>` envelope.
 */

/** Establishes a session for the given role by mocking `POST /v1/auth/refresh`, so `RouteGuard` resolves `ready` on direct navigation. */
async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

const ROLE_1 = {
  code: "FINANCE_STAFF",
  scope: "TENANT",
  displayName: "Finance Staff",
  description: "Manages payments, refunds, and manual payment slip review.",
  portalRouteGroup: "tenant-admin",
  selfRegisters: false,
  isProvisional: false,
};

const ROLE_2 = {
  code: "READ_ONLY_AUDITOR",
  scope: "TENANT",
  displayName: "Read-only Auditor",
  description: "View-only access across the tenant, for audit and compliance purposes.",
  portalRouteGroup: "tenant-admin",
  selfRegisters: false,
  isProvisional: false,
};

const STAFF_MEMBER = {
  id: "aaaaaaaa-1111-1111-1111-111111111111",
  name: "Priya Fernando",
  email: "priya@example-institute.test",
  roleCode: "FINANCE_STAFF",
  status: "ACTIVE",
};

test.describe("Staff list", () => {
  test("Tenant Admin sees the staff list and can create a new staff member", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/roles", 200, apiSuccess([ROLE_1, ROLE_2]));

    let listCallCount = 0;
    await page.route("**/v1/staff", async (route) => {
      if (route.request().method() === "POST") {
        const body = route.request().postDataJSON();
        expect(body).toEqual({
          name: "New Staffer",
          email: "new-staffer@example-institute.test",
          password: "correct-horse-battery-staple",
          roleCode: "FINANCE_STAFF",
        });
        await route.fulfill({
          status: 201,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess({ ...STAFF_MEMBER, id: "bbbb", name: "New Staffer" })),
        });
        return;
      }
      listCallCount += 1;
      const rows = listCallCount === 1 ? [STAFF_MEMBER] : [STAFF_MEMBER, { ...STAFF_MEMBER, id: "bbbb", name: "New Staffer" }];
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(rows)) });
    });

    await page.goto("/tenant-admin/staff");
    await expect(page.getByRole("table").getByText("Priya Fernando")).toBeVisible();

    const trigger = page.getByRole("button", { name: "Add staff" });
    await trigger.click();
    await expect(page.getByRole("dialog").getByRole("heading", { name: "Add staff" })).toBeVisible();

    await page.getByLabel("Name").fill("New Staffer");
    await page.getByLabel("Email").fill("new-staffer@example-institute.test");
    await page.getByLabel("Password").fill("correct-horse-battery-staple");
    await page.getByLabel("Role").selectOption("FINANCE_STAFF");
    await page.getByRole("button", { name: "Create staff account" }).click();

    await expect(page.getByRole("dialog")).toBeHidden();
    await expect(page.getByRole("table").getByText("New Staffer")).toBeVisible();
  });

  test("Read-only Auditor sees the staff list but no create action", async ({ page }) => {
    await mockJson(page, "**/v1/staff", 200, apiSuccess([STAFF_MEMBER]));
    await mockTenantSession(page, "READ_ONLY_AUDITOR");

    await page.goto("/tenant-admin/staff");

    await expect(page.getByRole("table").getByText("Priya Fernando")).toBeVisible();
    await expect(page.getByRole("button", { name: "Add staff" })).toHaveCount(0);
  });

  test("an empty tenant shows a distinct empty state with an Add staff CTA for a Tenant Admin", async ({
    page,
  }) => {
    await mockJson(page, "**/v1/staff", 200, apiSuccess([]));
    await mockTenantSession(page, "TENANT_ADMIN");

    await page.goto("/tenant-admin/staff");

    await expect(page.getByText("No staff accounts yet")).toBeVisible();
    await expect(page.getByRole("button", { name: "Add staff" })).toHaveCount(2);
  });

  test("a real 403 renders the permission-denied state for direct-URL access by an unrelated role", async ({
    page,
  }) => {
    await mockJson(
      page,
      "**/v1/staff",
      403,
      apiError("FORBIDDEN", "You do not have permission to view staff.")
    );
    await mockTenantSession(page, "COURSE_COORDINATOR");

    await page.goto("/tenant-admin/staff");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission to view this." });
    await expect(denied).toBeVisible();
    await expect(denied).toContainText("You do not have permission to view staff.");
  });
});

test.describe("Roles & Permissions", () => {
  test("lists assignable roles read-only, with no mutation control anywhere on the page", async ({
    page,
  }) => {
    await mockJson(page, "**/v1/roles", 200, apiSuccess([ROLE_1, ROLE_2]));
    await mockTenantSession(page, "TENANT_ADMIN");

    await page.goto("/tenant-admin/roles-permissions");

    const table = page.getByRole("table");
    await expect(table.getByText("Finance Staff")).toBeVisible();
    await expect(table.getByText("Read-only Auditor")).toBeVisible();
    await expect(table.getByText("Manages payments, refunds, and manual payment slip review.")).toBeVisible();

    await expect(page.getByRole("button", { name: /^Edit/i })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Delete/i })).toHaveCount(0);
    await expect(page.getByRole("checkbox")).toHaveCount(0);
  });

  test("Read-only Auditor can also view the roles catalog", async ({ page }) => {
    await mockJson(page, "**/v1/roles", 200, apiSuccess([ROLE_1]));
    await mockTenantSession(page, "READ_ONLY_AUDITOR");

    await page.goto("/tenant-admin/roles-permissions");

    await expect(page.getByRole("table").getByText("Finance Staff")).toBeVisible();
  });
});
