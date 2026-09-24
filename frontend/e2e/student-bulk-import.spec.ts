import { test, expect, type Page } from "@playwright/test";
import path from "node:path";
import os from "node:os";
import fs from "node:fs";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Student Bulk Import (Wave 3, PAR-03-03) —
 * `/tenant-admin/students/bulk-import`. No real backend runs in this
 * environment — every `/v1/students/**` call is intercepted via
 * `page.route()`.
 */

async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

function writeTempCsv(contents: string): string {
  const filePath = path.join(os.tmpdir(), `bulk-import-${Date.now()}-${Math.random().toString(36).slice(2)}.csv`);
  fs.writeFileSync(filePath, contents, "utf-8");
  return filePath;
}

test.describe("student bulk import — entry point", () => {
  test("the students list has a Bulk import entry point for a role that can manage students", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/students", 200, apiSuccess([]));
    await page.goto("/tenant-admin/students");

    const link = page.getByRole("link", { name: "Bulk import" });
    await expect(link).toBeVisible();
    await link.click();
    await expect(page).toHaveURL(/\/tenant-admin\/students\/bulk-import$/);
  });
});

test.describe("student bulk import — form", () => {
  test("submitting without choosing a file shows an inline error and makes no request", async ({
    page,
  }) => {
    let requestMade = false;
    await page.route("**/v1/students/bulk-import", async (route) => {
      requestMade = true;
      await route.fulfill({ status: 500, body: "should not be called" });
    });
    await mockTenantSession(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/students/bulk-import");

    await page.getByRole("button", { name: "Import" }).click();
    await expect(page.getByText("Choose a CSV file to import.")).toBeVisible();
    expect(requestMade).toBe(false);
  });

  test("a fully-successful import renders every row as Created", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/students/bulk-import",
      200,
      apiSuccess([
        { row: 1, status: "CREATED", reason: null, studentId: "11111111-1111-1111-1111-111111111111" },
        { row: 2, status: "CREATED", reason: null, studentId: "22222222-2222-2222-2222-222222222222" },
      ])
    );
    await page.goto("/tenant-admin/students/bulk-import");

    const filePath = writeTempCsv("name,email,password\nAda,ada@example.test,correct-horse-battery\n");
    await page.locator("#bulk-import-file").setInputFiles(filePath);
    await page.getByRole("button", { name: "Import" }).click();

    await expect(page.getByText("2 of 2 rows created successfully.")).toBeVisible();
    await expect(page.getByRole("table").getByText("Created").first()).toBeVisible();
  });

  test("a partial-failure import shows per-row success and failure with reasons", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/students/bulk-import",
      200,
      apiSuccess([
        { row: 1, status: "CREATED", reason: null, studentId: "11111111-1111-1111-1111-111111111111" },
        { row: 2, status: "FAILED", reason: "Email already exists", studentId: null },
        { row: 3, status: "FAILED", reason: "Missing required column: name", studentId: null },
      ])
    );
    await page.goto("/tenant-admin/students/bulk-import");

    const filePath = writeTempCsv("name,email,password\nAda,ada@example.test,correct-horse-battery\n");
    await page.locator("#bulk-import-file").setInputFiles(filePath);
    await page.getByRole("button", { name: "Import" }).click();

    await expect(page.getByText("1 of 3 rows created successfully.")).toBeVisible();
    // Scoped to the table: `DataTable` also renders a (CSS-hidden at this
    // desktop viewport) mobile card list with the same text, so an unscoped
    // `getByText` resolves to two elements.
    const table = page.getByRole("table");
    await expect(table.getByText("Email already exists")).toBeVisible();
    await expect(table.getByText("Missing required column: name")).toBeVisible();
    await expect(table.getByText("Created")).toHaveCount(1);
    await expect(table.getByText("Failed")).toHaveCount(2);
  });

  test("a downloadable CSV template with the correct header row is offered", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/students/bulk-import");

    // Prose alone previously described the required column names — this
    // affordance gives staff a ready-to-fill starting point instead of
    // having to guess column names/order.
    await expect(page.getByText("name,email,password").first()).toBeVisible();

    const [download] = await Promise.all([
      page.waitForEvent("download"),
      page.getByRole("button", { name: "Download CSV template" }).click(),
    ]);
    expect(download.suggestedFilename()).toBe("student-bulk-import-template.csv");
    const downloadPath = await download.path();
    const contents = fs.readFileSync(downloadPath!, "utf-8");
    expect(contents.split("\n")[0].trim()).toBe("name,email,password");
  });

  test("a 403 from the upload renders the permission-denied state", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/students/bulk-import",
      403,
      apiError("FORBIDDEN", "You do not have permission to import students.")
    );
    await page.goto("/tenant-admin/students/bulk-import");

    const filePath = writeTempCsv("name,email,password\nAda,ada@example.test,correct-horse-battery\n");
    await page.locator("#bulk-import-file").setInputFiles(filePath);
    await page.getByRole("button", { name: "Import" }).click();

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(page.getByText("You do not have permission to import students.")).toBeVisible();
  });
});
