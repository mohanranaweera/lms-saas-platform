import { expect, test, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

const T1 = { id: "11111111-1111-1111-1111-111111111111", name: "Sampath Lankadheera" };
const T2 = { id: "22222222-2222-2222-2222-222222222222", name: "Indunil Gunawardana" };
const C1 = { id: "33333333-3333-3333-3333-333333333333", name: "2027 A/L Biology Theory" };
const C2 = { id: "44444444-4444-4444-4444-444444444444", name: "Chemistry Theory" };
const RUN = "55555555-5555-5555-5555-555555555555";

function result() { return { runId: RUN, expiresAt: "2099-01-01T00:15:00Z", generatedAt: "2026-10-24T06:00:00Z", timezone: "Asia/Colombo", institute: { name: "Institute", currency: "LKR" }, teacher: T1, course: C1, title: "Teacher Report", filters: { teacherId: T1.id, courseId: C1.id, startDate: "2026-10-01", endDate: "2026-10-31", month: "2026-10", reportName: "Teacher Report" }, kpis: { totalStudents: 45, newRegistrations: 11, payments: 56, commissionPercent: "25.00%", classes: 6, attendance: 135, revenue: "224000.00", commission: "56000.00", teacherShare: "168000.00" }, weekly: [], cumulative: [], notes: [] }; }

async function install(page: Page, forbidden = false) {
  const requests: Array<Record<string, unknown>> = [];
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TENANT_ADMIN" }))));
  await page.route("**/api/v1/reports/course-summary/**", async (route: Route) => {
    const req = route.request(); const path = new URL(req.url()).pathname.replace(/^.*\/api\/v1\/reports\/course-summary/, "");
    requests.push({ path, method: req.method(), body: req.postData() ? JSON.parse(req.postData()!) : null });
    if (forbidden) return fulfillJson(route, 403, apiError("FORBIDDEN", "Access denied"));
    if (path === "/options") return fulfillJson(route, 200, apiSuccess({ teachers: [T1, T2] }));
    if (path === `/options/teachers/${T1.id}/courses`) return fulfillJson(route, 200, apiSuccess({ courses: [C1] }));
    if (path === `/options/teachers/${T2.id}/courses`) return fulfillJson(route, 200, apiSuccess({ courses: [C2] }));
    if (path === "/runs" && req.method() === "POST") return fulfillJson(route, 201, apiSuccess(result()));
    if (path === `/runs/${RUN}/pdf`) return route.fulfill({ status: 200, contentType: "application/pdf", body: Buffer.from("%PDF fake") });
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Unknown"));
  });
  return requests;
}

test("three-step wizard validates, preserves state, and downloads exact run", async ({ page }) => {
  const requests = await install(page); await page.goto("/tenant-admin/reports/course-summary");
  await expect(page.getByRole("list", { name: "Course Summary progress" })).toContainText("1. Select teacher");
  await page.getByRole("button", { name: "Next", exact: true }).click(); await expect(page.getByText("Select a teacher.")).toBeVisible();
  await page.getByRole("radio", { name: T1.name }).check(); await expect(page.getByRole("region", { name: "2. SELECT COURSE" })).toBeVisible();
  await page.getByRole("radio", { name: C1.name }).check(); await page.getByLabel("Start date").fill("2026-10-01"); await page.getByLabel("End date").fill("2026-10-31"); await page.getByLabel("Month").fill("2026-10");
  await page.getByRole("button", { name: "Back" }).click(); await expect(page.getByRole("radio", { name: C1.name })).toBeChecked(); await page.getByRole("button", { name: "Next", exact: true }).click();
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Generate report" }).click()]);
  expect(download.suggestedFilename()).toBe("course-summary-report.pdf");
  const run = requests.find((item) => item.path === "/runs"); expect(run?.body).toEqual({ teacherId: T1.id, courseId: C1.id, startDate: "2026-10-01", endDate: "2026-10-31", month: "2026-10", reportName: "Teacher Report" });
});

test("changing teacher invalidates selected course", async ({ page }) => {
  await install(page); await page.goto("/tenant-admin/reports/course-summary"); await page.getByRole("radio", { name: T1.name }).check(); await page.getByRole("radio", { name: C1.name }).check(); await page.getByRole("button", { name: "Back" }).click(); await page.getByRole("button", { name: "Back" }).click(); await page.getByRole("radio", { name: T2.name }).check(); await expect(page.getByRole("radio", { name: C2.name })).not.toBeChecked();
});

test("permission denied state comes from backend", async ({ page }) => { await install(page, true); await page.goto("/tenant-admin/reports/course-summary"); await expect(page.getByText("You don't have permission to view this.")).toBeVisible(); });
