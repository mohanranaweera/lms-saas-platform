import { test, expect, type Locator, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * DISC-01 checkout composer discounts (docs/plans/klass-discount-module.md §5).
 * Mock-only: the API is mocked at the network boundary. The client only sends
 * the code the student typed and the free-course offers they accepted; every
 * status, amount, line discount and notice comes from the (mocked) server
 * quote. The discount rules themselves (eligibility, best-discount choice,
 * limits, rate limiting) are proven by the backend tests.
 */

const ICT = { courseId: "11111111-1111-1111-1111-111111111111", title: "2026 A/L ICT", tags: [] };
const BIO = { courseId: "22222222-2222-2222-2222-222222222222", title: "2026 A/L Biology", tags: [] };
const PRICE = 3500;
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const CODE_RULE = "aaaaaaaa-0000-0000-0000-00000000c0de";
const FREE_RULE = "aaaaaaaa-0000-0000-0000-00000000f4ee";

interface QuoteBody {
  items: { courseId: string; scope: string; year?: number; month?: number }[];
  postageCourseIds: string[];
  discountCode?: string;
  acceptedRewardRuleIds?: string[];
  quoteToken?: string;
}

interface MockState {
  quoteBodies: QuoteBody[];
  orderBodies: QuoteBody[];
  /** The server's verdict for the code SAVE10X (any other code is INVALID). */
  codeStatus: "APPLIED" | "USAGE_LIMIT_REACHED";
  rateLimited: boolean;
  /** Offer a free Biology month when ICT is in the cart. */
  offerReward: boolean;
  /** The code's discount covers the full price (total 0). */
  fullDiscount: boolean;
}

const money = (value: number) => value.toFixed(2);

function buildQuote(body: QuoteBody, state: MockState) {
  const code = body.discountCode?.trim().toUpperCase() ?? null;
  const codeApplies = code === "SAVE10X" && state.codeStatus === "APPLIED";
  const codeDiscount = codeApplies ? (state.fullDiscount ? PRICE : 350) : 0;
  const rewardAccepted = state.offerReward && (body.acceptedRewardRuleIds ?? []).includes(FREE_RULE);

  const lines = body.items.map((item, index) => {
    const discount = index === 0 ? codeDiscount : 0;
    return {
      lineNo: index + 1,
      scope: item.scope,
      label: `${item.year} ${MONTHS[(item.month ?? 1) - 1]}`,
      year: item.year ?? null,
      month: item.month ?? null,
      moduleId: null,
      lessonId: null,
      unitAmount: money(PRICE),
      facilityFee: "0.00",
      lineTotal: money(PRICE - discount),
      courseId: item.courseId,
      courseTitle: ICT.title,
      discountAmount: money(discount),
      reward: false,
      discountName: discount > 0 ? "Ten off" : null,
    };
  });
  if (rewardAccepted) {
    lines.push({
      lineNo: lines.length + 1,
      scope: "MONTHLY",
      label: "2026 Oct",
      year: 2026,
      month: 10,
      moduleId: null,
      lessonId: null,
      unitAmount: money(2000),
      facilityFee: "0.00",
      lineTotal: "0.00",
      courseId: BIO.courseId,
      courseTitle: BIO.title,
      discountAmount: money(2000),
      reward: true,
      discountName: "Bio bonus",
    });
  }

  const discounts = [];
  if (codeApplies) {
    discounts.push({
      ruleId: CODE_RULE,
      ruleVersionId: "v-1",
      type: "DISCOUNT_CODE",
      name: "Ten off",
      code: "SAVE10X",
      amount: money(codeDiscount),
      lineNos: [1],
      rewardCourseId: null,
      rewardCourseIds: [],
      explanation: "Code SAVE10X",
    });
  }
  if (rewardAccepted) {
    discounts.push({
      ruleId: FREE_RULE,
      ruleVersionId: "v-2",
      type: "BUNDLE_FREE_COURSE",
      name: "Bio bonus",
      code: null,
      amount: money(2000),
      lineNos: [lines.length],
      rewardCourseId: BIO.courseId,
      rewardCourseIds: [BIO.courseId],
      explanation: "Biology free for 1 month",
    });
  }
  const courseIds = [...new Set(lines.map((line) => line.courseId))];
  const subtotal = lines.reduce((sum, line) => sum + Number(line.unitAmount), 0);
  const discountTotal = discounts.reduce((sum, entry) => sum + Number(entry.amount), 0);
  const total = subtotal - discountTotal;

  const codeResult = code
    ? code === "SAVE10X"
      ? {
          code,
          status: state.codeStatus,
          message: state.codeStatus === "APPLIED" ? "Code applied: LKR 350.00 off" : null,
        }
      : { code, status: "INVALID", message: "This code isn't valid." }
    : null;

  return {
    courseId: courseIds.length === 1 ? courseIds[0] : null,
    currency: "LKR",
    lines,
    courses: courseIds.map((courseId) => ({
      courseId,
      courseTitle: courseId === ICT.courseId ? ICT.title : BIO.title,
      currency: "LKR",
      subtotal: money(
        lines.filter((line) => line.courseId === courseId).reduce((sum, line) => sum + Number(line.unitAmount), 0)
      ),
      facilityFee: "0.00",
      effectiveFacilityFee: "0.00",
      facilityFeeSource: "NOT_APPLICABLE",
      postageFee: "0.00",
      postageSelected: false,
      postageCharge: "0.00",
    })),
    subtotal: money(subtotal),
    facilityFeeTotal: "0.00",
    postageTotal: "0.00",
    discountTotal: money(discountTotal),
    total: money(total),
    feeBasis: "NONE",
    feeNote: null,
    deliveryRequired: false,
    delivery: null,
    quoteToken: `token-${JSON.stringify(body)}`,
    methods: { online: false, slip: true },
    bankDetails: { bankName: "Test Bank", branch: "Test Branch", accountHolder: "Test Institute", accountNumber: "000-TEST" },
    discounts,
    codeResult,
    rewardOffers:
      state.offerReward && !rewardAccepted
        ? [
            {
              ruleId: FREE_RULE,
              name: "Bio bonus",
              courseId: BIO.courseId,
              courseTitle: BIO.title,
              months: [{ year: 2026, month: 10, label: "October 2026" }],
              originalValue: "2000.00",
              facilityFee: "0.00",
              explanation: "Buy ICT and get Biology free for 1 month",
            },
          ]
        : [],
    notices: [],
    blockingIssue: total === 0 ? "ORDER_TOTAL_ZERO" : null,
    blockingMessage:
      total === 0
        ? "Your discounts cover the whole amount, and an order with a zero total cannot be placed. Remove a discount or add another item to continue."
        : null,
  };
}

async function openComposer(page: Page, overrides: Partial<MockState> = {}) {
  const state: MockState = {
    quoteBodies: [],
    orderBodies: [],
    codeStatus: "APPLIED",
    rateLimited: false,
    offerReward: false,
    fullDiscount: false,
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
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([ICT])) })
  );
  await page.route("**/api/v1/checkout/offers*", (route: Route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(
        apiSuccess({
          scope: "MONTHLY",
          courseId: ICT.courseId,
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
            amount: money(PRICE),
            state: "AVAILABLE",
          })),
        })
      ),
    })
  );
  await page.route("**/api/v1/checkout/quote", (route: Route) => {
    const body = route.request().postDataJSON() as QuoteBody;
    state.quoteBodies.push(body);
    if (state.rateLimited && body.discountCode) {
      return route.fulfill({
        status: 429,
        contentType: "application/json",
        body: JSON.stringify(apiError("DISCOUNT_CODE_RATE_LIMITED", "Too many invalid discount codes")),
      });
    }
    return route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(buildQuote(body, state))) });
  });
  await page.route("**/api/v1/checkout/orders", (route: Route) => {
    state.orderBodies.push(route.request().postDataJSON() as QuoteBody);
    return route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify(apiSuccess({ id: "order-1", courseId: ICT.courseId, amount: "0.00", currency: "LKR", status: "PLACED" })),
    });
  });
  await page.route("**/api/v1/orders/order-1/slips", (route: Route) =>
    route.fulfill({
      status: 201,
      contentType: "application/json",
      body: JSON.stringify(apiSuccess({ id: "slip-1", orderId: "order-1", status: "SUBMITTED" })),
    })
  );

  await page.goto("/student/courses");
  await page.getByTestId("global-pay-button").click();
  const composer = page.getByTestId("payment-composer");
  await expect(composer).toBeVisible();
  await composer.getByTestId("composer-course-select").selectOption(ICT.courseId);
  await composer.locator('[data-testid="composer-month"][data-month="10"]').click();
  await expect(composer.getByTestId("composer-total")).toHaveText("LKR 3,500.00");
  return { composer, state };
}

async function applyCode(composer: Locator, code: string) {
  await composer.getByTestId("composer-code-input").fill(code);
  await composer.getByTestId("composer-code-apply").click();
}

const lastQuote = (state: MockState) => state.quoteBodies[state.quoteBodies.length - 1];

test("a valid code is sent with the quote and the server's discount, status and total are shown", async ({ page }) => {
  const { composer, state } = await openComposer(page);
  // No code yet: the request carries no discount field at all.
  expect(lastQuote(state).discountCode).toBeUndefined();
  await expect(composer.getByLabel("Discount code")).toBeVisible();

  await applyCode(composer, "save10x");
  await expect(composer.getByTestId("composer-code-status")).toHaveAttribute("data-status", "APPLIED");
  await expect(composer.getByTestId("composer-code-status")).toHaveText("Code applied: LKR 350.00 off");
  await expect(composer.getByTestId("composer-applied-discount")).toContainText("Ten off");
  await expect(composer.getByTestId("composer-line-discount")).toContainText("350.00");
  await expect(composer.getByTestId("composer-discount-total")).toHaveText("− LKR 350.00");
  await expect(composer.getByTestId("composer-total")).toHaveText("LKR 3,150.00");
  expect(lastQuote(state).discountCode).toBe("save10x");
  // The client never sends an amount.
  expect(JSON.stringify(lastQuote(state))).not.toMatch(/amount|price|total/i);

  // Removing the code re-quotes without it.
  await composer.getByTestId("composer-code-remove").click();
  await expect(composer.getByTestId("composer-total")).toHaveText("LKR 3,500.00");
  expect(lastQuote(state).discountCode).toBeUndefined();
  await expect(composer.getByTestId("composer-code-input")).toBeVisible();
});

test("the code is part of the order request exactly as quoted", async ({ page }) => {
  const { composer, state } = await openComposer(page);
  await applyCode(composer, "SAVE10X");
  await expect(composer.getByTestId("composer-total")).toHaveText("LKR 3,150.00");

  await composer.getByTestId("composer-method-slip").click();
  await composer.getByTestId("composer-slip-reference").fill("REF-TEST-1");
  await composer.getByTestId("composer-slip-file").setInputFiles({
    name: "slip.png",
    mimeType: "image/png",
    buffer: Buffer.from("89504e470d0a1a0a", "hex"),
  });
  await composer.getByTestId("composer-submit-slip").click();
  await expect(composer.getByTestId("composer-slip-submitted")).toBeVisible();
  expect(state.orderBodies).toHaveLength(1);
  expect(state.orderBodies[0].discountCode).toBe("SAVE10X");
  expect(state.orderBodies[0].quoteToken).toBeTruthy();
});

test("an invalid or used-up code shows the server status as an alert and charges full price", async ({ page }) => {
  const { composer } = await openComposer(page, { codeStatus: "USAGE_LIMIT_REACHED" });
  await applyCode(composer, "NOPE-123");
  const status = composer.getByTestId("composer-code-status");
  await expect(status).toHaveAttribute("data-status", "INVALID");
  await expect(status).toHaveAttribute("role", "alert");
  await expect(status).toHaveText("This code isn't valid.");
  await expect(composer.getByTestId("composer-total")).toHaveText("LKR 3,500.00");

  await composer.getByTestId("composer-code-remove").click();
  await applyCode(composer, "SAVE10X");
  await expect(status).toHaveAttribute("data-status", "USAGE_LIMIT_REACHED");
  await expect(status).toHaveText("This discount code can't be used any more.");
});

test("an empty code is rejected before any request", async ({ page }) => {
  const { composer, state } = await openComposer(page);
  const before = state.quoteBodies.length;
  await composer.getByTestId("composer-code-apply").click();
  await expect(composer.getByText("Enter a discount code.")).toBeVisible();
  expect(state.quoteBodies.length).toBe(before);
});

test("429 DISCOUNT_CODE_RATE_LIMITED: explained, no retry loop, and removing the code recovers", async ({ page }) => {
  const { composer } = await openComposer(page, { rateLimited: true });
  await applyCode(composer, "GUESS-1");
  await expect(composer.getByTestId("composer-code-status")).toHaveAttribute("data-status", "RATE_LIMITED");
  await expect(composer.getByTestId("composer-quote-error")).toContainText("Too many incorrect discount codes");
  await expect(composer.getByTestId("composer-quote-error").getByRole("button", { name: "Try again" })).toHaveCount(0);

  await composer.getByTestId("composer-code-remove").click();
  await expect(composer.getByTestId("composer-total")).toHaveText("LKR 3,500.00");
  await expect(composer.getByTestId("composer-quote-error")).toHaveCount(0);
});

test("a free-course offer is added only when accepted, and can be removed again", async ({ page }) => {
  const { composer, state } = await openComposer(page, { offerReward: true });
  const offer = composer.getByTestId("composer-reward-offer");
  await expect(offer).toContainText("Bio bonus");
  await expect(offer).toContainText("October 2026");
  expect(lastQuote(state).acceptedRewardRuleIds).toBeUndefined();

  await offer.getByTestId("composer-reward-accept").click();
  await expect(composer.locator('[data-testid="composer-summary-line"][data-reward="true"]')).toContainText("Free");
  await expect(composer.getByTestId("composer-reward-offer")).toHaveCount(0);
  await expect(composer.getByTestId("composer-total")).toHaveText("LKR 3,500.00");
  expect(lastQuote(state).acceptedRewardRuleIds).toEqual([FREE_RULE]);

  await composer.getByTestId("composer-reward-remove").click();
  await expect(composer.getByTestId("composer-reward-offer")).toBeVisible();
  expect(lastQuote(state).acceptedRewardRuleIds).toBeUndefined();
});

test("ORDER_TOTAL_ZERO blocks payment with the server's explanation", async ({ page }) => {
  const { composer } = await openComposer(page, { fullDiscount: true });
  await applyCode(composer, "SAVE10X");
  await expect(composer.getByTestId("composer-blocking-issue")).toContainText("zero total");
  await expect(composer.getByTestId("composer-method-slip")).toHaveCount(0);

  await composer.getByTestId("composer-code-remove").click();
  await expect(composer.getByTestId("composer-blocking-issue")).toHaveCount(0);
  await expect(composer.getByTestId("composer-method-slip")).toBeVisible();
});
