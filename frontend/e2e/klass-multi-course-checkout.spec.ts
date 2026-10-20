import { test, expect, type Locator, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Multi-course checkout composer (docs/plans/multi-course-checkout-fees-postage.md
 * §4). Mock-only: the API is mocked at the network boundary so the UI contract
 * is exercised without depending on the local backend's deployed version.
 * Every amount on screen comes from the (mocked) server quote; the client
 * only sends identifiers (`courseId` on every item, `postageCourseIds`) and
 * the latest `quoteToken`.
 */

const ICT = { courseId: "11111111-1111-1111-1111-111111111111", title: "2026 A/L ICT", tags: [] };
const BIO = { courseId: "22222222-2222-2222-2222-222222222222", title: "2026 A/L Biology", tags: [] };
const PRICES: Record<string, number> = { [ICT.courseId]: 3500, [BIO.courseId]: 2000 };
const POSTAGE: Record<string, number> = { [ICT.courseId]: 0, [BIO.courseId]: 350 };
const TITLES: Record<string, string> = { [ICT.courseId]: ICT.title, [BIO.courseId]: BIO.title };
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

interface QuoteBody {
  items: { courseId: string; scope: string; year?: number; month?: number }[];
  postageCourseIds: string[];
  quoteToken?: string;
  idempotencyKey?: string;
}

interface MockState {
  quoteBodies: QuoteBody[];
  orderBodies: QuoteBody[];
  address: { addressLine: string | null; city: string | null };
  /** Extra amount added to every course subtotal (simulates a price change). */
  priceBump: number;
  quoteError: { status: number; code: string; message: string } | null;
  orderConflictOnce: boolean;
}

const money = (value: number) => value.toFixed(2);

function buildQuote(body: QuoteBody, state: MockState) {
  const courseIds = [...new Set(body.items.map((item) => item.courseId))];
  const lines = body.items.map((item, index) => {
    const unit = PRICES[item.courseId] + state.priceBump;
    return {
      lineNo: index + 1,
      scope: item.scope,
      label: `${item.year} ${MONTHS[(item.month ?? 1) - 1]}`,
      year: item.year ?? null,
      month: item.month ?? null,
      moduleId: null,
      lessonId: null,
      unitAmount: money(unit),
      facilityFee: "0.00",
      lineTotal: money(unit),
      courseId: item.courseId,
      courseTitle: TITLES[item.courseId],
    };
  });
  const courses = courseIds.map((courseId) => {
    const subtotal = lines.filter((entry) => entry.courseId === courseId).reduce((sum, entry) => sum + Number(entry.unitAmount), 0);
    const selected = body.postageCourseIds.includes(courseId);
    return {
      courseId,
      courseTitle: TITLES[courseId],
      currency: "LKR",
      subtotal: money(subtotal),
      facilityFee: "100.00",
      effectiveFacilityFee: "100.00",
      facilityFeeSource: courseId === ICT.courseId ? "COURSE_OVERRIDE" : "TENANT_DEFAULT",
      postageFee: money(POSTAGE[courseId]),
      postageSelected: selected,
      postageCharge: money(selected ? POSTAGE[courseId] : 0),
    };
  });
  const subtotal = courses.reduce((sum, entry) => sum + Number(entry.subtotal), 0);
  const facility = courses.length * 100;
  const postage = courses.reduce((sum, entry) => sum + Number(entry.postageCharge), 0);
  return {
    courseId: courseIds.length === 1 ? courseIds[0] : null,
    currency: "LKR",
    lines,
    courses,
    subtotal: money(subtotal),
    facilityFeeTotal: money(facility),
    postageTotal: money(postage),
    discountTotal: "0.00",
    total: money(subtotal + facility + postage),
    feeBasis: "PER_COURSE",
    feeNote: "A facility fee is added once per course.",
    deliveryRequired: body.postageCourseIds.length > 0,
    delivery: state.address.addressLine
      ? { recipientName: "Test Student", addressLine: state.address.addressLine, city: state.address.city, phone: "0770000000" }
      : null,
    quoteToken: `token-${state.priceBump}-${JSON.stringify(body.items)}-${body.postageCourseIds.join(",")}`,
    methods: { online: false, slip: true },
    bankDetails: { bankName: "Test Bank", branch: "Test Branch", accountHolder: "Test Institute", accountNumber: "000-TEST" },
  };
}

async function openComposer(page: Page, overrides: Partial<MockState> = {}) {
  const state: MockState = {
    quoteBodies: [],
    orderBodies: [],
    address: { addressLine: "12 Test Road", city: "Colombo" },
    priceBump: 0,
    quoteError: null,
    orderConflictOnce: false,
    ...overrides,
  };
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "STUDENT" }))));
  await mockJson(page, "**/api/v1/course-tags", 200, apiSuccess([]));
  await mockJson(
    page,
    "**/api/v1/enrollments/my/catalog*",
    200,
    apiSuccess({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
  );
  await page.route("**/api/v1/checkout/courses*", (route: Route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([ICT, BIO])) })
  );
  await page.route("**/api/v1/checkout/offers*", (route: Route) => {
    const url = new URL(route.request().url());
    const courseId = url.searchParams.get("courseId")!;
    return route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        apiSuccess({
          scope: "MONTHLY",
          courseId,
          currency: "LKR",
          supported: true,
          years: [2026],
          items: [10, 11].map((month) => ({
            scope: "MONTHLY",
            year: 2026,
            month,
            moduleId: null,
            lessonId: null,
            label: `2026 ${MONTHS[month - 1]}`,
            amount: money(PRICES[courseId]),
            state: "AVAILABLE",
          })),
        })
      ),
    });
  });
  await page.route("**/api/v1/checkout/quote", (route: Route) => {
    const body = route.request().postDataJSON() as QuoteBody;
    state.quoteBodies.push(body);
    if (state.quoteError) {
      return route.fulfill({
        status: state.quoteError.status,
        contentType: "application/json",
        body: JSON.stringify(apiError(state.quoteError.code, state.quoteError.message)),
      });
    }
    if (body.postageCourseIds.length > 0 && !state.address.addressLine) {
      return route.fulfill({
        status: 422,
        contentType: "application/json",
        body: JSON.stringify(apiError("DELIVERY_DETAILS_REQUIRED", "A delivery address is required for postage")),
      });
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(buildQuote(body, state))) });
  });
  await page.route("**/api/v1/checkout/orders", (route: Route) => {
    const body = route.request().postDataJSON() as QuoteBody;
    state.orderBodies.push(body);
    if (state.orderConflictOnce) {
      state.orderConflictOnce = false;
      state.priceBump = 100;
      const fresh = buildQuote(body, state);
      const envelope = apiError("QUOTE_CHANGED", "Prices or charges changed since your quote - please review the updated total");
      return route.fulfill({
        status: 409,
        contentType: "application/json",
        body: JSON.stringify({ ...envelope, error: { ...envelope.error, details: { quote: fresh } } }),
      });
    }
    return route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify(apiSuccess({ id: "order-1", courseId: null, amount: "0.00", currency: "LKR", status: "PLACED" })),
    });
  });
  await page.route("**/api/v1/orders/order-1/slips", (route: Route) =>
    route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify(apiSuccess({ id: "slip-1", orderId: "order-1", status: "SUBMITTED" })),
    })
  );
  await page.route("**/api/v1/students/me/billing-address", async (route: Route) => {
    if (route.request().method() === "PUT") {
      const body = route.request().postDataJSON() as { addressLine: string; city: string };
      state.address = { addressLine: body.addressLine, city: body.city };
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(state.address)) });
  });

  await page.goto("/student/courses");
  await page.getByTestId("global-pay-button").click();
  const composer = page.getByTestId("payment-composer");
  await expect(composer).toBeVisible();
  return { composer, state };
}

function monthButton(composer: Locator, month: number) {
  return composer.locator(`[data-testid="composer-month"][data-month="${month}"]`);
}

function courseGroup(composer: Locator, courseId: string) {
  return composer.locator(`[data-testid="composer-summary-course"][data-course-id="${courseId}"]`);
}

async function addOctoberFromBothCourses(composer: Locator) {
  const select = composer.getByTestId("composer-course-select");
  await select.selectOption(ICT.courseId);
  await monthButton(composer, 10).click();
  // Switching course keeps the cart: no destructive confirmation.
  await select.selectOption(BIO.courseId);
  await expect(composer.getByText("A payment covers one course at a time")).toHaveCount(0);
  await expect(monthButton(composer, 10)).toHaveAttribute("aria-pressed", "false");
  await monthButton(composer, 10).click();
  await expect(composer.getByTestId("composer-summary-line")).toHaveCount(2);
}

test("multi-course cart: switching course keeps the cart, same month in two courses, grouped server-quoted summary", async ({ page }) => {
  const { composer, state } = await openComposer(page);
  await addOctoberFromBothCourses(composer);

  await expect(courseGroup(composer, ICT.courseId)).toContainText(ICT.title);
  await expect(courseGroup(composer, BIO.courseId)).toContainText(BIO.title);
  await expect(composer.getByTestId("composer-multi-course-note")).toBeVisible();
  // ICT postage is 0 -> no postage option at all; Biology shows its amount, unticked by default.
  await expect(courseGroup(composer, ICT.courseId).getByTestId("composer-postage")).toHaveCount(0);
  await expect(courseGroup(composer, BIO.courseId).getByTestId("composer-postage-amount")).toHaveText("LKR 350.00");
  await expect(composer.getByTestId("composer-postage-checkbox").first()).not.toBeChecked();
  await expect(composer.getByTestId("composer-total")).toHaveText("LKR 5,700.00");
  // The ICT course is still marked as having an item in the cart when we go back.
  await expect(composer.getByTestId("composer-course-select").locator("option", { hasText: "(1 in cart)" })).toHaveCount(2);

  const last = state.quoteBodies[state.quoteBodies.length - 1];
  expect(last.items).toEqual([
    { courseId: ICT.courseId, scope: "MONTHLY", year: 2026, month: 10 },
    { courseId: BIO.courseId, scope: "MONTHLY", year: 2026, month: 10 },
  ]);
  expect(last.postageCourseIds).toEqual([]);
  expect(JSON.stringify(last)).not.toMatch(/amount|price|total/i);

  // Back to ICT: its October is still selected; toggling it again never duplicates.
  await composer.getByTestId("composer-course-select").selectOption(ICT.courseId);
  await expect(monthButton(composer, 10)).toHaveAttribute("aria-pressed", "true");
});

test("postage: per course, shows the on-record delivery address, dropped when the course's last item is removed", async ({ page }) => {
  const { composer, state } = await openComposer(page);
  await addOctoberFromBothCourses(composer);

  await courseGroup(composer, BIO.courseId).getByTestId("composer-postage-checkbox").check();
  await expect(composer.getByTestId("composer-postage-total")).toHaveText("LKR 350.00");
  await expect(composer.getByTestId("composer-delivery-address")).toContainText("12 Test Road");
  await expect(composer.getByTestId("composer-total")).toHaveText("LKR 6,050.00");
  expect(state.quoteBodies[state.quoteBodies.length - 1].postageCourseIds).toEqual([BIO.courseId]);

  // Removing Biology's only item drops its postage; the next quote never sends it.
  await courseGroup(composer, BIO.courseId).getByTestId("composer-summary-remove").click();
  await expect(courseGroup(composer, BIO.courseId)).toHaveCount(0);
  await expect(composer.getByTestId("composer-delivery")).toHaveCount(0);
  await expect.poll(() => state.quoteBodies[state.quoteBodies.length - 1].postageCourseIds).toEqual([]);
  expect(state.quoteBodies[state.quoteBodies.length - 1].items).toEqual([
    { courseId: ICT.courseId, scope: "MONTHLY", year: 2026, month: 10 },
  ]);
});

test("postage without an address on record: the billing address form is shown inline, then the total is re-quoted", async ({ page }) => {
  const { composer, state } = await openComposer(page, { address: { addressLine: null, city: null } });
  await addOctoberFromBothCourses(composer);

  await courseGroup(composer, BIO.courseId).getByTestId("composer-postage-checkbox").check();
  await expect(composer.getByTestId("composer-quote-error")).toContainText("Postage needs a delivery address");
  const form = composer.getByTestId("composer-delivery-address-form");
  await expect(form).toBeVisible();
  await form.getByLabel("Address").fill("45 Lake Road");
  await form.getByLabel("City").fill("Kandy");
  await form.getByTestId("composer-delivery-address-save").click();

  await expect(composer.getByTestId("composer-delivery-address")).toContainText("45 Lake Road");
  await expect(composer.getByTestId("composer-total")).toHaveText("LKR 6,050.00");
  expect(state.address).toEqual({ addressLine: "45 Lake Road", city: "Kandy" });
});

test("CURRENCY_MISMATCH: the server message is shown and the cart is kept", async ({ page }) => {
  const message = "2026 A/L ICT is priced in LKR but 2026 A/L Biology is priced in USD. Pay for them separately.";
  const { composer } = await openComposer(page, { quoteError: { status: 422, code: "CURRENCY_MISMATCH", message } });
  const select = composer.getByTestId("composer-course-select");
  await select.selectOption(ICT.courseId);
  await monthButton(composer, 10).click();
  await select.selectOption(BIO.courseId);
  await monthButton(composer, 10).click();

  await expect(composer.getByTestId("composer-quote-error")).toContainText(message);
  await expect(courseGroup(composer, ICT.courseId)).toBeVisible();
  await expect(courseGroup(composer, BIO.courseId)).toBeVisible();
  await expect(composer.getByTestId("composer-submit-slip")).toHaveCount(0);
});

test("QUOTE_CHANGED: the fresh quote is shown and the student must confirm again (never auto-submitted)", async ({ page }) => {
  const { composer, state } = await openComposer(page, { orderConflictOnce: true });
  await addOctoberFromBothCourses(composer);
  await expect(composer.getByTestId("composer-total")).toHaveText("LKR 5,700.00");

  await composer.getByTestId("composer-method-slip").click();
  await composer.getByTestId("composer-slip-reference").fill("REF-TEST-1");
  await composer.getByTestId("composer-slip-file").setInputFiles({
    name: "slip.png",
    mimeType: "image/png",
    buffer: Buffer.from("89504e470d0a1a0a", "hex"),
  });
  await composer.getByTestId("composer-submit-slip").click();

  await expect(composer.getByTestId("composer-price-changed")).toContainText("Prices changed — review the updated total");
  await expect(composer.getByTestId("composer-total")).toHaveText("LKR 5,900.00");
  // Exactly one order attempt so far, carrying the original quote token.
  expect(state.orderBodies).toHaveLength(1);
  const firstToken = state.orderBodies[0].quoteToken;
  expect(firstToken).toBeTruthy();
  await expect(composer.getByTestId("composer-slip-submitted")).toHaveCount(0);

  // The student confirms again: a second order request with the fresh token.
  await composer.getByTestId("composer-submit-slip").click();
  await expect(composer.getByTestId("composer-slip-submitted")).toBeVisible();
  expect(state.orderBodies).toHaveLength(2);
  expect(state.orderBodies[1].quoteToken).not.toBe(firstToken);
  expect(state.orderBodies[1].items.every((item) => Boolean(item.courseId))).toBe(true);
});
