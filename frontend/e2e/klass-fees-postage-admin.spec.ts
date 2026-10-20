import { test, expect, type Page, type Route } from "@playwright/test";
import { apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Multi-course checkout admin surfaces (docs/plans/multi-course-checkout-fees-postage.md):
 * course facility-fee override + postage, the institute facility-fee setting
 * (tenant config PAYMENT) and the slip review breakdown (course per line,
 * postage + delivery). Mock-only; the backend validates, authorizes and
 * audit-logs every change for real.
 */

const COURSE_ID = "course-fees-1";

function course(overrides: Record<string, unknown> = {}) {
  return {
    id: COURSE_ID,
    teacherId: "teacher-1",
    name: "Fees Algebra",
    slug: "fees-algebra",
    category: "Math",
    subject: null,
    stream: null,
    grade: null,
    academicYear: null,
    description: null,
    price: 3500,
    accessDurationDays: null,
    enrollmentRule: null,
    status: "DRAFT",
    pricingModel: "ONE_TIME",
    archivedAt: null,
    createdAt: "2026-10-06T08:00:00Z",
    updatedAt: "2026-10-06T08:00:00Z",
    resolvedAmount: 3500,
    currency: "LKR",
    requiresManualQuote: false,
    registrationEnabled: true,
    enrollmentFee: null,
    coverImageUrl: null,
    whatsappInviteUrl: null,
    telegramInviteUrl: null,
    paymentValidDays: null,
    commissionRate: null,
    teachers: [{ id: "teacher-1", name: "Teacher One", primary: true }],
    tags: [],
    linkedCourses: [],
    displayOrder: null,
    priceDisplay: { amount: 3500, currency: "LKR", period: null },
    facilityFeeOverride: null,
    tenantDefaultFacilityFee: "100.00",
    facilityFeeBasis: "PER_COURSE",
    effectiveFacilityFee: "100.00",
    postageFee: "250.00",
    ...overrides,
  };
}

async function asTenantAdmin(page: Page) {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TENANT_ADMIN" }))));
}

test("course fees: inherit by default; switching it off and saving an override of 0 sends 0 (never treated as empty)", async ({
  page,
}) => {
  await asTenantAdmin(page);
  const patches: Record<string, unknown>[] = [];
  let current = course();
  await page.route("**/api/v1/courses/**", async (route: Route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}`) && request.method() === "PATCH") {
      const body = request.postDataJSON() as Record<string, unknown>;
      patches.push(body);
      current = course({
        facilityFeeOverride: body.facilityFeeInherit === false ? body.facilityFeeOverride : null,
        effectiveFacilityFee: body.facilityFeeInherit === false ? body.facilityFeeOverride : "100.00",
        postageFee: body.postageFee ?? current.postageFee,
      });
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(current)) });
      return;
    }
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}`)) {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(current)) });
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
  });

  await page.goto(`/tenant-admin/courses/${COURSE_ID}`);
  const section = page.getByTestId("course-charges");
  await expect(section).toBeVisible();
  await expect(section.getByTestId("course-charges-tenant-default")).toHaveText("LKR 100.00");
  await expect(section.getByTestId("course-charges-effective")).toHaveText("LKR 100.00");
  const inherit = section.getByRole("switch", { name: "Use institute default facility fee" });
  await expect(inherit).toHaveAttribute("aria-checked", "true");
  await expect(section.getByTestId("course-charges-override")).toHaveCount(0);

  await inherit.click();
  const override = section.getByTestId("course-charges-override");
  await expect(override).toBeVisible();

  // More than 2 decimals is rejected client-side.
  await override.fill("1.234");
  await section.getByTestId("course-charges-submit").click();
  await expect(section.getByText("Enter an amount of 0 or more with up to 2 decimals")).toBeVisible();
  expect(patches).toHaveLength(0);

  // A minus sign can't be typed into the amount field.
  await override.fill("-5");
  await expect(override).toHaveValue("5");

  await override.fill("0");
  await section.getByLabel("Postage fee").fill("0");
  await section.getByTestId("course-charges-submit").click();
  await expect.poll(() => patches.length).toBe(1);
  expect(patches[0]).toEqual({ facilityFeeInherit: false, facilityFeeOverride: "0.00", postageFee: "0.00" });
  await expect(section.getByTestId("course-charges-effective")).toHaveText("LKR 0.00");
  await expect(override).toHaveValue("0.00");
});

test("course fees: PER_ORDER basis explains that course overrides don't apply", async ({ page }) => {
  await asTenantAdmin(page);
  await page.route("**/api/v1/courses/**", async (route: Route) => {
    const path = new URL(route.request().url()).pathname;
    const data = path.endsWith(`/api/v1/courses/${COURSE_ID}`) ? course({ facilityFeeBasis: "PER_ORDER" }) : [];
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(data)) });
  });
  await page.goto(`/tenant-admin/courses/${COURSE_ID}`);
  await expect(page.getByTestId("course-charges-override-note")).toContainText("a course facility fee isn't used");
});

test("institute facility fee: mode + default amount saved through tenant config PAYMENT; only changed keys are sent", async ({
  page,
}) => {
  await asTenantAdmin(page);
  await mockJson(page, "**/api/v1/payment-gateway-accounts", 200, apiSuccess([]));
  const puts: Record<string, unknown>[] = [];
  let properties = [
    { key: "facility_fee_mode", value: null, type: "STRING", defaultValue: "NONE", sensitive: false },
    { key: "facility_fee_amount", value: null, type: "NUMBER", defaultValue: 0, sensitive: false },
  ];
  await page.route("**/api/v1/tenant-config/PAYMENT", async (route: Route) => {
    if (route.request().method() === "PUT") {
      const body = route.request().postDataJSON() as Record<string, unknown>;
      puts.push(body);
      properties = properties.map((entry) => (entry.key in body ? { ...entry, value: body[entry.key] as never } : entry));
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(properties)) });
  });

  await page.goto("/tenant-admin/settings/payments");
  const section = page.getByTestId("facility-fee-settings");
  await expect(section.getByTestId("facility-fee-mode-NONE")).toBeChecked();
  await expect(section.getByLabel("Default facility fee")).toHaveValue("0.00");

  await section.getByLabel("Default facility fee").fill("-1");
  await section.getByTestId("facility-fee-save").click();
  await expect(section.getByText("Enter an amount of 0 or more with up to 2 decimals")).toBeVisible();
  expect(puts).toHaveLength(0);

  await section.getByLabel("Default facility fee").fill("0");
  await section.getByTestId("facility-fee-mode-PER_COURSE").check();
  await section.getByTestId("facility-fee-save").click();
  await expect.poll(() => puts.length).toBe(1);
  expect(puts[0]).toEqual({ facility_fee_mode: "PER_COURSE" });
  await expect(section.getByText("Facility fee saved.")).toBeVisible();
});

test("slip review: each order line names its course; postage and the delivery address are shown", async ({ page }) => {
  await asTenantAdmin(page);
  await mockJson(
    page,
    "**/api/v1/payment-slips/slip-mc-1",
    200,
    apiSuccess({
      id: "slip-mc-1",
      orderId: "order-mc-1",
      studentId: "student-1",
      referenceNumber: "REF-MC-1",
      status: "UNDER_REVIEW",
      submittedAt: "2026-10-19T08:00:00Z",
      reviewerId: null,
      reviewedAt: null,
      flags: [],
      studentEmail: "student@example.test",
      reviewerEmail: null,
      orderAmount: 6050,
      orderCurrency: "LKR",
      orderLines: [
        { lineNo: 1, scope: "MONTHLY", label: "2026 Oct", unitAmount: 3500, facilityFee: 100, lineTotal: 3600, courseId: "c-ict", courseTitle: "2026 A/L ICT" },
        { lineNo: 2, scope: "MONTHLY", label: "2026 Oct", unitAmount: 2000, facilityFee: 100, lineTotal: 2100, courseId: "c-bio", courseTitle: "2026 A/L Biology" },
      ],
      orderSubtotal: 5500,
      orderFacilityFee: 200,
      orderDiscount: 0,
      orderPostage: 350,
      orderDelivery: { recipientName: "Test Student", addressLine: "12 Test Road", city: "Colombo", phone: "0770000000" },
    })
  );
  await page.goto("/tenant-admin/payments/slip-review/slip-mc-1");
  const breakdown = page.getByTestId("slip-order-breakdown");
  await expect(breakdown.getByTestId("slip-order-line-course")).toHaveText(["2026 A/L ICT", "2026 A/L Biology"]);
  await expect(breakdown.getByTestId("slip-order-postage")).toHaveText("350.00 LKR");
  await expect(breakdown.getByTestId("slip-order-delivery")).toContainText("12 Test Road");
  await expect(breakdown.getByTestId("slip-order-delivery")).toContainText("Colombo");
});
