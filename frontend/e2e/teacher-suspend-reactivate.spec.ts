import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Teacher Suspend/Reactivate (Wave 3, PAR-04-04) —
 * `(tenant-admin)/tenant-admin/teachers/**`. No real backend runs in this
 * environment.
 */

interface MockTeacher {
  id: string;
  name: string;
  email: string;
  approvalStatus: "PENDING" | "APPROVED" | "REJECTED" | "SUSPENDED";
  accountStatus: "ACTIVE" | "SUSPENDED";
  approvedBy: string | null;
  approvedAt: string | null;
}

function makeTeacher(overrides: Partial<MockTeacher> & { id: string; name: string }): MockTeacher {
  return {
    email: `${overrides.id}@example.test`,
    approvalStatus: "APPROVED",
    accountStatus: "ACTIVE",
    approvedBy: "admin-1",
    approvedAt: new Date().toISOString(),
    ...overrides,
  };
}

async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

test.describe("teacher list — status filter includes Suspended", () => {
  test("the status filter dropdown offers a Suspended option and filters to it", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const approved = makeTeacher({ id: "teacher-1", name: "Kavindi Fernando", approvalStatus: "APPROVED" });
    const suspended = makeTeacher({ id: "teacher-2", name: "Ruwan Jayasuriya", approvalStatus: "SUSPENDED" });
    await mockJson(page, "**/v1/teachers", 200, apiSuccess([approved, suspended]));

    await page.goto("/tenant-admin/teachers");
    await page.getByLabel("Status").selectOption("SUSPENDED");

    await expect(page.getByRole("table").getByText("Ruwan Jayasuriya")).toBeVisible();
    await expect(page.getByRole("table").getByText("Kavindi Fernando")).toHaveCount(0);
    await expect(page.getByRole("table").getByText("Suspended", { exact: true })).toBeVisible();
  });
});

test.describe("teacher list — row-level Suspend/Reactivate actions", () => {
  test("an APPROVED row offers Suspend; a SUSPENDED row offers Reactivate", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const approved = makeTeacher({ id: "teacher-approved", name: "Asha Perera", approvalStatus: "APPROVED" });
    const suspended = makeTeacher({ id: "teacher-suspended", name: "Nadun Silva", approvalStatus: "SUSPENDED" });
    await mockJson(page, "**/v1/teachers", 200, apiSuccess([approved, suspended]));

    await page.goto("/tenant-admin/teachers");

    await expect(page.getByRole("button", { name: `Suspend teacher ${approved.name}` })).toBeVisible();
    await expect(page.getByRole("button", { name: `Reactivate teacher ${suspended.name}` })).toBeVisible();
  });

  test("confirming Suspend updates the row's badge without a page reload", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const approved = makeTeacher({ id: "teacher-suspend-ok", name: "Sanduni Ekanayake", approvalStatus: "APPROVED" });

    let suspended = false;
    await page.route("**/v1/teachers", async (route) => {
      const current = suspended ? { ...approved, approvalStatus: "SUSPENDED" as const } : approved;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([current])) });
    });
    await page.route(`**/v1/teachers/${approved.id}/suspend`, async (route) => {
      suspended = true;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ ...approved, approvalStatus: "SUSPENDED" })),
      });
    });

    await page.goto("/tenant-admin/teachers");
    const table = page.getByRole("table");
    await expect(table.getByText("Approved", { exact: true })).toBeVisible();

    await page.getByRole("button", { name: `Suspend teacher ${approved.name}` }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: "Suspend", exact: true }).click();

    await expect(dialog).not.toBeVisible();
    await expect(table.getByText("Suspended", { exact: true })).toBeVisible();
    await expect(page.getByRole("button", { name: `Suspend teacher ${approved.name}` })).toHaveCount(0);
    await expect(page.getByRole("button", { name: `Reactivate teacher ${approved.name}` })).toBeVisible();
  });

  test("a 403 on suspend is surfaced inline, dialog stays open", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const approved = makeTeacher({ id: "teacher-403", name: "Chamod Gunasekara", approvalStatus: "APPROVED" });
    await mockJson(page, "**/v1/teachers", 200, apiSuccess([approved]));
    await mockJson(
      page,
      `**/v1/teachers/${approved.id}/suspend`,
      403,
      apiError("FORBIDDEN", "You do not have permission to suspend teachers.")
    );

    await page.goto("/tenant-admin/teachers");
    await page.getByRole("button", { name: `Suspend teacher ${approved.name}` }).click();
    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Suspend", exact: true }).click();

    await expect(dialog.getByText("You do not have permission to suspend teachers.")).toBeVisible();
    await expect(dialog).toBeVisible();
  });
});

test.describe("teacher detail — Suspend/Reactivate from the Profile tab", () => {
  test("an APPROVED teacher's Profile tab offers Suspend; confirming reactivates the badge state", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const approved = makeTeacher({ id: "teacher-detail-1", name: "Imesha Rathnayake", approvalStatus: "APPROVED" });
    let suspended = false;
    await page.route(`**/v1/teachers/${approved.id}`, async (route) => {
      const current = suspended ? { ...approved, approvalStatus: "SUSPENDED" as const } : approved;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(current)) });
    });
    await page.route(`**/v1/teachers/${approved.id}/suspend`, async (route) => {
      suspended = true;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ ...approved, approvalStatus: "SUSPENDED" })),
      });
    });

    await page.goto(`/tenant-admin/teachers/${approved.id}`);
    await page.getByRole("button", { name: "Suspend", exact: true }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Suspend", exact: true }).click();

    await expect(page.getByText("Suspended", { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Reactivate", exact: true })).toBeVisible();
  });

  test("a PENDING teacher's Profile tab offers neither Suspend nor Reactivate", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const pending = makeTeacher({ id: "teacher-pending-1", name: "Tharindu Bandara", approvalStatus: "PENDING" });
    await mockJson(page, `**/v1/teachers/${pending.id}`, 200, apiSuccess(pending));

    await page.goto(`/tenant-admin/teachers/${pending.id}`);
    await expect(page.getByRole("button", { name: "Suspend", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reactivate", exact: true })).toHaveCount(0);
  });
});

test.describe("Course Coordinator session — Suspend/Reactivate controls are hidden", () => {
  test("no Suspend/Reactivate control renders anywhere on the list or detail page", async ({ page }) => {
    await mockTenantSession(page, "COURSE_COORDINATOR");
    const approved = makeTeacher({ id: "teacher-cc-2", name: "Dinithi Wickramasinghe", approvalStatus: "APPROVED" });
    await mockJson(page, "**/v1/teachers", 200, apiSuccess([approved]));
    await mockJson(page, `**/v1/teachers/${approved.id}`, 200, apiSuccess(approved));

    await page.goto("/tenant-admin/teachers");
    await expect(page.getByRole("button", { name: /^Suspend teacher/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Reactivate teacher/ })).toHaveCount(0);

    await page.goto(`/tenant-admin/teachers/${approved.id}`);
    await expect(page.getByRole("button", { name: "Suspend", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reactivate", exact: true })).toHaveCount(0);
  });
});
