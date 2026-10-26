import { expect, test, type Page, type Route } from "@playwright/test";
import {
  apiError,
  apiSuccess,
  fakeJwt,
  fulfillJson,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

const COURSE = {
  id: "c1111111-1111-1111-1111-111111111111",
  name: "Physics 2026 A/L",
};
const OPERATOR = {
  id: "a1111111-1111-1111-1111-111111111111",
  name: "finance@example.test",
};
const RUN_ID = "44444444-4444-4444-4444-444444444444";

async function installFake(page: Page) {
  await mockJson(
    page,
    "**/v1/auth/refresh",
    200,
    apiSuccess(refreshResponseBody(fakeJwt({ role: "FINANCE_STAFF" }))),
  );
  await page.route(
    "**/api/v1/reports/registrations/**",
    async (route: Route) => {
      const request = route.request();
      const url = new URL(request.url());
      const path = url.pathname.replace(
        /^.*\/api\/v1\/reports\/registrations/,
        "",
      );
      if (path === "/options") {
        return fulfillJson(
          route,
          200,
          apiSuccess({
            courses: [COURSE],
            operators: [OPERATOR],
            registrationMethods: ["SELF_REGISTERED", "ADMIN_CREATED"],
          }),
        );
      }
      if (path === "/runs" && request.method() === "POST") {
        return fulfillJson(
          route,
          201,
          apiSuccess({
            runId: RUN_ID,
            expiresAt: "2099-01-01T00:15:00Z",
            generatedAt: "2026-10-25T04:30:00Z",
            timezone: "Asia/Colombo",
            institute: { name: "Example Institute" },
            filters: {
              courses: [COURSE],
              operator: OPERATOR,
              registrationMethod: "SELF_REGISTERED",
              startDate: "2026-10-01",
              endDate: "2026-10-31",
              selectedDayCount: 31,
            },
            currentFacts: {
              courseAssociationBasis: "current course_participation",
              lastPaymentDefinition: "not available",
              asOf: "2026-10-25T04:30:00Z",
            },
            totalRegistrations: 1,
          }),
        );
      }
      if (path === `/runs/${RUN_ID}/rows`) {
        return fulfillJson(
          route,
          200,
          apiSuccess({
            page: 0,
            size: 50,
            totalRows: 1,
            totalPages: 1,
            rows: [
              {
                studentProfileId: "s1111111-1111-1111-1111-111111111111",
                studentId: "u1111111-1111-1111-1111-111111111111",
                indexNumber: "0013",
                studentName: "Diyana Jayaweera",
                email: "diyana@example.test",
                mobile: "0771234567",
                registeredById: OPERATOR.id,
                registeredBy: OPERATOR.name,
                registrationMethod: "SELF_REGISTERED",
                courses: [COURSE],
                lastPayment: null,
                registrationDate: "2026-10-01T04:30:00Z",
              },
            ],
          }),
        );
      }
      return fulfillJson(route, 404, apiError("NOT_FOUND", `Unmocked ${path}`));
    },
  );
}

test("labels options, selects a range, and renders returned registrations", async ({
  page,
}) => {
  await installFake(page);
  await page.goto("/tenant-admin/reports/registrations");

  await expect(page.getByLabel("Calendar month")).toBeVisible();
  await expect(page.getByLabel("Calendar year")).toBeVisible();
  const initialYear = new Date().getFullYear().toString();
  await page.getByLabel("Calendar month").click();
  await page.getByRole("option", { name: "OCT" }).click();
  await expect(page.getByLabel("Calendar month")).toContainText("OCT");
  await page.getByLabel("Calendar year").click();
  await page.getByRole("option", { name: initialYear, exact: true }).click();
  await expect(page.getByLabel("Calendar year")).toContainText(initialYear);
  await page.getByRole("button", { name: "Previous year" }).click();
  await expect(page.getByLabel("Calendar year")).not.toHaveText(initialYear);
  await page.getByRole("button", { name: "Next year" }).click();
  await expect(page.getByLabel("Calendar year")).toContainText(initialYear);

  await expect(page.getByLabel("Course")).toContainText("All Courses");
  await page.getByLabel("Course").click();
  await page.getByRole("option", { name: COURSE.name }).click();
  await expect(page.getByLabel("Course")).toContainText(COURSE.name);
  await expect(page.getByLabel("Course")).not.toContainText(COURSE.id);

  await page.getByLabel("Operator").click();
  await page.getByRole("option", { name: OPERATOR.name }).click();
  await expect(page.getByLabel("Operator")).toContainText(OPERATOR.name);
  await expect(page.getByLabel("Operator")).not.toContainText(OPERATOR.id);

  await page.getByLabel("Registration Method").click();
  await page.getByRole("option", { name: "Self registered" }).click();
  await expect(page.getByLabel("Registration Method")).toContainText(
    "Self registered",
  );
  await expect(page.getByLabel("Registration Method")).not.toContainText(
    "SELF_REGISTERED",
  );

  const dates = page.locator('[role="group"][aria-label$="dates"] button');
  await dates.nth(0).click();
  await dates.nth(1).click();
  await expect(page.getByText("2 selected", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "Search" }).click();
  await expect(page.getByTestId("registration-results")).toBeVisible();
  await expect(page.getByRole("table")).toContainText("Diyana Jayaweera");
});
