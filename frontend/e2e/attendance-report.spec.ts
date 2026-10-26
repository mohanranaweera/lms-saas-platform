import { expect, test, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

const COURSE = { id: "11111111-1111-1111-1111-111111111111", name: "2027 A/L Biology Theory" };
const SECOND = { id: "22222222-2222-2222-2222-222222222222", name: "Chemistry Advanced" };
const RUN_ID = "33333333-3333-3333-3333-333333333333";

interface State {
  requests: Array<{ method: string; path: string; body: Record<string, unknown> | null; auth: string | null }>;
  forbidden?: boolean;
  optionsError?: boolean;
  runError?: { status: number; body: unknown };
  pdfStatus?: number;
}

function report() {
  return {
    runId: RUN_ID,
    expiresAt: "2099-01-01T00:15:00Z",
    generatedAt: "2026-10-23T06:21:51Z",
    timezone: "Asia/Colombo",
    institute: { name: "Example Institute" },
    course: COURSE,
    title: "Attendance Report",
    filters: { courseId: COURSE.id, startDate: "2026-10-01", endDate: "2026-10-24", reportName: "Attendance Report" },
    dates: ["2026-10-03"],
    rows: [{ studentId: "s1", indexNumber: "0048", studentName: "Asha", cells: ["P"] }],
    totals: { totalRecords: 45, present: 135, absent: 0 },
    legend: { present: "P = Present", absent: "A = Explicitly absent", unknown: "— = No recorded status" },
  };
}

async function install(page: Page, state: State) {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TENANT_ADMIN" }))));
  await page.route("**/api/v1/reports/attendance/**", async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^.*\/api\/v1\/reports\/attendance/, "");
    state.requests.push({ method: request.method(), path, body: request.postData() ? JSON.parse(request.postData()!) : null, auth: request.headers()["authorization"] ?? null });
    if (state.forbidden) return fulfillJson(route, 403, apiError("FORBIDDEN", "Access denied"));
    if (path === "/options") {
      if (state.optionsError) return fulfillJson(route, 503, apiError("UNAVAILABLE", "Reports unavailable"));
      return fulfillJson(route, 200, apiSuccess({ courses: [COURSE, SECOND] }));
    }
    if (path === "/runs" && request.method() === "POST") {
      if (state.runError) return fulfillJson(route, state.runError.status, state.runError.body);
      return fulfillJson(route, 201, apiSuccess(report()));
    }
    if (path === `/runs/${RUN_ID}/pdf`) {
      if (state.pdfStatus) return fulfillJson(route, state.pdfStatus, apiError("NOT_FOUND", "Report run not found"));
      return route.fulfill({ status: 200, contentType: "application/pdf", body: Buffer.from("%PDF-1.4 fake") });
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Unknown route"));
  });
}

async function open(page: Page, state: State) {
  await install(page, state);
  await page.goto("/tenant-admin/reports/attendance");
  await expect(page.getByRole("heading", { name: "Attendance Report", level: 1 })).toBeVisible();
}

test("search preserves selected course and valid request downloads authenticated PDF", async ({ page }) => {
  const state: State = { requests: [] };
  await open(page, state);
  await page.getByRole("radio", { name: COURSE.name }).check();
  await page.getByLabel("Search courses").fill("Chemistry");
  await expect(page.getByText("Selected course").locator("..")).toContainText(COURSE.name);
  await page.getByLabel("Start date").fill("2026-10-01");
  await page.getByLabel("End date").fill("2026-10-24");
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("button", { name: "Generate report" }).click()]);
  expect(download.suggestedFilename()).toBe("attendance-report.pdf");
  const run = state.requests.find((request) => request.path === "/runs");
  expect(run?.body).toEqual({ courseId: COURSE.id, startDate: "2026-10-01", endDate: "2026-10-24", reportName: "Attendance Report" });
  expect(run?.auth).toMatch(/^Bearer /);
  await expect(page.getByTestId("attendance-run-result")).toContainText("45 students · 135 present · 0 absent");
});

test("client validation blocks missing course and invalid dates", async ({ page }) => {
  const state: State = { requests: [] };
  await open(page, state);
  await page.getByRole("button", { name: "Generate report" }).click();
  await expect(page.getByText("Select one course.")).toBeVisible();
  await expect(page.getByText("Choose a start date.")).toBeVisible();
  await expect(page.getByText("Choose an end date.")).toBeVisible();
  await page.getByLabel("Start date").fill("2026-10-24");
  await page.getByLabel("End date").fill("2026-10-01");
  await page.getByRole("radio", { name: COURSE.name }).check();
  await page.getByRole("button", { name: "Generate report" }).click();
  await expect(page.getByText("The end date must be on or after the start date.")).toBeVisible();
  expect(state.requests.filter((request) => request.path === "/runs")).toHaveLength(0);
});

test("blank report title normalizes and duplicate generation is disabled while pending", async ({ page }) => {
  const state: State = { requests: [] };
  await open(page, state);
  await page.getByRole("radio", { name: COURSE.name }).check();
  await page.getByLabel("Start date").fill("2026-10-01");
  await page.getByLabel("End date").fill("2026-10-24");
  await page.getByLabel("Report name").fill("   ");
  await page.route("**/api/v1/reports/attendance/runs", async (route) => { await new Promise((resolve) => setTimeout(resolve, 150)); await fulfillJson(route, 201, apiSuccess(report())); });
  await page.getByRole("button", { name: "Generate report" }).click();
  await expect(page.getByRole("button", { name: "Generating…" })).toBeDisabled();
});

test("options 403 renders permission denied", async ({ page }) => {
  await open(page, { requests: [], forbidden: true });
  await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
});

test("server validation error maps to course field summary", async ({ page }) => {
  const state: State = { requests: [], runError: { status: 422, body: apiError("REPORT_VALIDATION", "Invalid", [{ field: "courseId", message: "course not found" }]) } };
  await open(page, state);
  await page.getByRole("radio", { name: COURSE.name }).check();
  await page.getByLabel("Start date").fill("2026-10-01");
  await page.getByLabel("End date").fill("2026-10-24");
  await page.getByRole("button", { name: "Generate report" }).click();
  await expect(page.getByText("course not found", { exact: true })).toBeVisible();
  await expect(page.getByTestId("attendance-filter-errors")).toBeFocused();
});
