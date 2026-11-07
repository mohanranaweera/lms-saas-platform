import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Student Management — shared page states for the Tenant Admin students
 * directory and profile (originally MVP-006; rewritten for STU-MGMT-02 when
 * the flat client-filtered list became the server-paginated directory
 * `GET /v1/students/directory` and the detail page became the full profile
 * `GET /v1/students/{id}/detail`). Feature flows (search, create/edit,
 * drawer, QR, impersonation) live in `student-management-parity.spec.ts`;
 * this file keeps the baseline loading / error / permission-denied / empty /
 * role-visibility / responsive coverage.
 */

test.beforeEach(() => {
  test.slow();
});

const NAV_TIMEOUT = 20_000;

const ADA = {
  id: "11111111-1111-1111-1111-111111111111",
  indexNumber: "0001",
  firstName: "Ada",
  lastName: "Lovelace",
  email: "ada@example-institute.test",
  mobile: "0771234567",
  lastLoginAt: null,
  status: "ACTIVE",
  hasPhoto: false,
};
const GRACE = { ...ADA, id: "22222222-2222-2222-2222-222222222222", indexNumber: "0002", firstName: "Grace", lastName: "Hopper", email: "grace@example-institute.test" };

function page0(content: unknown[]) {
  return apiSuccess({ content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 });
}

async function fulfill(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

async function session(page: Page, role: string) {
  await page.route("**/api/v1/**", (route) => fulfill(route, 404, apiError("NOT_FOUND", "Not mocked")));
  await page.route("**/v1/auth/refresh", (route) => fulfill(route, 200, apiSuccess(refreshResponseBody(fakeJwt({ role })))));
}

async function directory(page: Page, handler: (route: Route) => Promise<void>) {
  await page.route((url) => url.pathname.endsWith("/v1/students/directory"), handler);
}

test.describe("students directory — states", () => {
  test("labelled search controls and an accessible loading indicator", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await directory(page, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 600));
      await fulfill(route, 200, page0([ADA, GRACE]));
    });
    await page.goto("/tenant-admin/students");
    await expect(page.getByRole("status").filter({ hasText: "Loading students…" })).toBeVisible({ timeout: NAV_TIMEOUT });
    await expect(page.getByRole("table").getByText("Lovelace")).toBeVisible();
    await expect(page.getByLabel("Search Field")).toBeVisible();
    await expect(page.getByLabel("Search", { exact: true })).toBeVisible();
  });

  test("a server error renders the shared error state; Try again recovers", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    let count = 0;
    await directory(page, async (route) => {
      count += 1;
      // Initial attempt + React Query's one automatic retry fail; the manual retry succeeds.
      if (count <= 2) return fulfill(route, 500, apiError("INTERNAL_ERROR", "Something went wrong."));
      return fulfill(route, 200, page0([ADA]));
    });
    await page.goto("/tenant-admin/students");
    await expect(page.getByRole("alert").filter({ hasText: "Something went wrong." })).toBeVisible({ timeout: NAV_TIMEOUT });
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByRole("table").getByText("Lovelace")).toBeVisible();
  });

  test("a 403 renders the permission-denied state, not a generic error", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await directory(page, (route) => fulfill(route, 403, apiError("FORBIDDEN", "You do not have permission.")));
    await page.goto("/tenant-admin/students");
    await expect(page.getByText(/permission/i).first()).toBeVisible({ timeout: NAV_TIMEOUT });
    await expect(page.getByRole("button", { name: "Try again" })).toHaveCount(0);
  });

  test("zero students shows 'No students yet' with Add Student and Bulk import", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await directory(page, (route) => fulfill(route, 200, page0([])));
    await page.goto("/tenant-admin/students");
    await expect(page.getByText("No students yet")).toBeVisible({ timeout: NAV_TIMEOUT });
    await expect(page.getByRole("button", { name: "Add Student" }).first()).toBeVisible();
    await expect(page.getByRole("link", { name: "Bulk import" })).toHaveAttribute("href", "/tenant-admin/students/bulk-import");
  });
});

test.describe("students directory — role visibility (UX only)", () => {
  for (const role of ["TENANT_ADMIN", "STUDENT_SUPPORT"]) {
    test(`${role} sees Add Student`, async ({ page }) => {
      await session(page, role);
      await directory(page, (route) => fulfill(route, 200, page0([ADA])));
      await page.goto("/tenant-admin/students");
      await expect(page.getByRole("button", { name: "Add Student" })).toBeVisible({ timeout: NAV_TIMEOUT });
    });
  }

  test("a view-only role (Read-only Auditor) sees no mutating actions", async ({ page }) => {
    await session(page, "READ_ONLY_AUDITOR");
    await directory(page, (route) => fulfill(route, 200, page0([ADA])));
    await page.goto("/tenant-admin/students");
    await expect(page.getByRole("table").getByText("Lovelace")).toBeVisible({ timeout: NAV_TIMEOUT });
    await expect(page.getByRole("button", { name: "Add Student" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Bulk import" })).toHaveCount(0);
    await expect(page.getByRole("table").getByRole("button", { name: /Edit Student/ })).toHaveCount(0);
    await expect(page.getByRole("table").getByRole("button", { name: /Login As Student/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Export as CSV" })).toBeVisible();
  });
});

test.describe("students directory — responsive behavior", () => {
  test("below md the table becomes a card list with the row actions", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await session(page, "TENANT_ADMIN");
    await directory(page, (route) => fulfill(route, 200, page0([ADA, GRACE])));
    await page.goto("/tenant-admin/students");
    await expect(page.getByRole("list").getByText("Ada Lovelace")).toBeVisible({ timeout: NAV_TIMEOUT });
    await expect(page.getByRole("table")).toBeHidden();
    await expect(page.getByRole("button", { name: "View Student Details: Ada Lovelace" })).toBeVisible();
  });

  test("at md and above the table is shown", async ({ page }) => {
    await page.setViewportSize({ width: 1024, height: 800 });
    await session(page, "TENANT_ADMIN");
    await directory(page, (route) => fulfill(route, 200, page0([ADA, GRACE])));
    await page.goto("/tenant-admin/students");
    await expect(page.getByRole("table")).toBeVisible({ timeout: NAV_TIMEOUT });
  });
});

test.describe("student profile — states", () => {
  test("Comprehensive Report opens the student's profile", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await directory(page, (route) => fulfill(route, 200, page0([ADA])));
    await page.goto("/tenant-admin/students");
    await page.getByRole("table").getByRole("button", { name: "More actions: Ada Lovelace" }).click({ timeout: NAV_TIMEOUT });
    await page.getByRole("menuitem", { name: "Comprehensive Report" }).click();
    await expect(page).toHaveURL(new RegExp(`/tenant-admin/students/${ADA.id}$`), { timeout: NAV_TIMEOUT });
  });

  test("a uniform 404 (nonexistent or cross-tenant id) renders the error state with the backend's message", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await page.route(`**/v1/students/${ADA.id}/detail`, (route) => fulfill(route, 404, apiError("NOT_FOUND", "Student not found")));
    await page.goto(`/tenant-admin/students/${ADA.id}`);
    await expect(page.getByRole("alert").filter({ hasText: "Student not found" })).toBeVisible({ timeout: NAV_TIMEOUT });
    await expect(page.getByTestId("student-identity")).toHaveCount(0);
  });

  test("a 403 on the profile renders the permission-denied state", async ({ page }) => {
    await session(page, "TEACHER");
    await page.route(`**/v1/students/${ADA.id}/detail`, (route) => fulfill(route, 403, apiError("FORBIDDEN", "You do not have permission.")));
    await page.goto(`/tenant-admin/students/${ADA.id}`);
    await expect(page.getByText(/permission/i).first()).toBeVisible({ timeout: NAV_TIMEOUT });
    await expect(page.getByTestId("student-identity")).toHaveCount(0);
  });
});
