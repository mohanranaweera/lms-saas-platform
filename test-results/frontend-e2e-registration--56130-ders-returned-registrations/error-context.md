# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: frontend\e2e\registration-report.spec.ts >> labels options, selects a range, and renders returned registrations
- Location: frontend\e2e\registration-report.spec.ts:108:5

# Error details

```
Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
Call log:
  - navigating to "/tenant-admin/reports/registrations", waiting until "load"

```

# Test source

```ts
  12  |   id: "c1111111-1111-1111-1111-111111111111",
  13  |   name: "Physics 2026 A/L",
  14  | };
  15  | const OPERATOR = {
  16  |   id: "a1111111-1111-1111-1111-111111111111",
  17  |   name: "finance@example.test",
  18  | };
  19  | const RUN_ID = "44444444-4444-4444-4444-444444444444";
  20  | 
  21  | async function installFake(page: Page) {
  22  |   await mockJson(
  23  |     page,
  24  |     "**/v1/auth/refresh",
  25  |     200,
  26  |     apiSuccess(refreshResponseBody(fakeJwt({ role: "FINANCE_STAFF" }))),
  27  |   );
  28  |   await page.route(
  29  |     "**/api/v1/reports/registrations/**",
  30  |     async (route: Route) => {
  31  |       const request = route.request();
  32  |       const url = new URL(request.url());
  33  |       const path = url.pathname.replace(
  34  |         /^.*\/api\/v1\/reports\/registrations/,
  35  |         "",
  36  |       );
  37  |       if (path === "/options") {
  38  |         return fulfillJson(
  39  |           route,
  40  |           200,
  41  |           apiSuccess({
  42  |             courses: [COURSE],
  43  |             operators: [OPERATOR],
  44  |             registrationMethods: ["SELF_REGISTERED", "ADMIN_CREATED"],
  45  |           }),
  46  |         );
  47  |       }
  48  |       if (path === "/runs" && request.method() === "POST") {
  49  |         return fulfillJson(
  50  |           route,
  51  |           201,
  52  |           apiSuccess({
  53  |             runId: RUN_ID,
  54  |             expiresAt: "2099-01-01T00:15:00Z",
  55  |             generatedAt: "2026-10-25T04:30:00Z",
  56  |             timezone: "Asia/Colombo",
  57  |             institute: { name: "Example Institute" },
  58  |             filters: {
  59  |               courses: [COURSE],
  60  |               operator: OPERATOR,
  61  |               registrationMethod: "SELF_REGISTERED",
  62  |               startDate: "2026-10-01",
  63  |               endDate: "2026-10-31",
  64  |               selectedDayCount: 31,
  65  |             },
  66  |             currentFacts: {
  67  |               courseAssociationBasis: "current course_participation",
  68  |               lastPaymentDefinition: "not available",
  69  |               asOf: "2026-10-25T04:30:00Z",
  70  |             },
  71  |             totalRegistrations: 1,
  72  |           }),
  73  |         );
  74  |       }
  75  |       if (path === `/runs/${RUN_ID}/rows`) {
  76  |         return fulfillJson(
  77  |           route,
  78  |           200,
  79  |           apiSuccess({
  80  |             page: 0,
  81  |             size: 50,
  82  |             totalRows: 1,
  83  |             totalPages: 1,
  84  |             rows: [
  85  |               {
  86  |                 studentProfileId: "s1111111-1111-1111-1111-111111111111",
  87  |                 studentId: "u1111111-1111-1111-1111-111111111111",
  88  |                 indexNumber: "0013",
  89  |                 studentName: "Diyana Jayaweera",
  90  |                 email: "diyana@example.test",
  91  |                 mobile: "0771234567",
  92  |                 registeredById: OPERATOR.id,
  93  |                 registeredBy: OPERATOR.name,
  94  |                 registrationMethod: "SELF_REGISTERED",
  95  |                 courses: [COURSE],
  96  |                 lastPayment: null,
  97  |                 registrationDate: "2026-10-01T04:30:00Z",
  98  |               },
  99  |             ],
  100 |           }),
  101 |         );
  102 |       }
  103 |       return fulfillJson(route, 404, apiError("NOT_FOUND", `Unmocked ${path}`));
  104 |     },
  105 |   );
  106 | }
  107 | 
  108 | test("labels options, selects a range, and renders returned registrations", async ({
  109 |   page,
  110 | }) => {
  111 |   await installFake(page);
> 112 |   await page.goto("/tenant-admin/reports/registrations");
      |              ^ Error: page.goto: Protocol error (Page.navigate): Cannot navigate to invalid URL
  113 | 
  114 |   await expect(page.getByLabel("Course")).toContainText("All Courses");
  115 |   await page.getByLabel("Course").click();
  116 |   await page.getByRole("option", { name: COURSE.name }).click();
  117 |   await expect(page.getByLabel("Course")).toContainText(COURSE.name);
  118 |   await expect(page.getByLabel("Course")).not.toContainText(COURSE.id);
  119 | 
  120 |   await page.getByLabel("Operator").click();
  121 |   await page.getByRole("option", { name: OPERATOR.name }).click();
  122 |   await expect(page.getByLabel("Operator")).toContainText(OPERATOR.name);
  123 |   await expect(page.getByLabel("Operator")).not.toContainText(OPERATOR.id);
  124 | 
  125 |   await page.getByLabel("Registration Method").click();
  126 |   await page.getByRole("option", { name: "Self registered" }).click();
  127 |   await expect(page.getByLabel("Registration Method")).toContainText(
  128 |     "Self registered",
  129 |   );
  130 |   await expect(page.getByLabel("Registration Method")).not.toContainText(
  131 |     "SELF_REGISTERED",
  132 |   );
  133 | 
  134 |   const dates = page.locator('[role="group"][aria-label$="dates"] button');
  135 |   await dates.nth(0).click();
  136 |   await dates.nth(1).click();
  137 |   await expect(page.getByText("2 selected", { exact: true })).toBeVisible();
  138 | 
  139 |   await page.getByRole("button", { name: "Search" }).click();
  140 |   await expect(page.getByTestId("registration-results")).toBeVisible();
  141 |   await expect(page.getByRole("table")).toContainText("Diyana Jayaweera");
  142 | });
  143 | 
```