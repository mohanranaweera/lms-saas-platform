import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  fulfillJson,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * MVP-010 Order and Payment Foundation — highest-risk frontend behaviors:
 *
 * 1. Checkout never renders a student-editable price input (PAY-1).
 * 2. Awaiting-confirmation never derives "confirmed" from a redirect query
 *    param — only from the polled `payment-status` API response (PAY-2).
 * 3. Payment History's zero-data empty state has distinct, contextual copy
 *    (PAY-3).
 * 4. Refunds: the refund action does not render for a role without
 *    `PAYMENTS_SLIPS`/`APPROVE`, and a forced 403 from a permitted role's
 *    own dialog is surfaced inline, never silently swallowed (PAY-4).
 *
 * No real backend runs in this environment (see `fixtures/auth-mocks.ts`'s
 * module doc) — every test mocks `/api/v1/**` responses shaped like the
 * `ApiResponse<T>` envelope. The `(student)`/`(tenant-admin)` route groups
 * are wrapped in `RouteGuard` (`kind="tenant"`), so every test mocks a
 * successful `POST /v1/auth/refresh` first, mirroring
 * `teacher-management.spec.ts`.
 */

async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
  // PAY-GW-01 default for this suite: the institute has an active PayHere
  // account and the student has a billing address, so paid checkout offers
  // "Pay online (PayHere)". Tests override these routes for other cases.
  await mockJson(
    page,
    "**/v1/checkout/payment-options",
    200,
    apiSuccess({ bankSlip: true, onlinePayment: { provider: "PAYHERE", mode: "SANDBOX" } })
  );
  await mockJson(
    page,
    "**/v1/students/me/billing-address",
    200,
    apiSuccess({ addressLine: "No. 1, Galle Road", city: "Colombo" })
  );
}

const COURSE = {
  id: "course-1",
  teacherId: "teacher-1",
  name: "Intro to Biology",
  slug: "intro-to-biology",
  category: "Science",
  subject: "Biology",
  stream: null,
  grade: "Grade 9",
  academicYear: "2026",
  description: "A beginner-friendly introduction to biology.",
  price: 49.99,
  accessDurationDays: 180,
  enrollmentRule: null,
  status: "PUBLIC" as const,
  pricingModel: "ONE_TIME" as const,
  resolvedAmount: 49.99,
  currency: "USD",
  requiresManualQuote: false,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

test.describe("checkout — no editable price field", () => {
  test("the checkout screen displays price as read-only text, never a form input", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, `**/api/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));

    await page.goto(`/student/checkout/${COURSE.id}`);

    await expect(page.getByRole("heading", { name: COURSE.name })).toBeVisible();
    await expect(page.getByText("49.99")).toBeVisible();

    // Structural assertion: no <input> element exists anywhere on this page,
    // not just "no input named price" — the price is never bound to a form
    // field at all.
    await expect(page.locator("input")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Pay online (PayHere)" })).toBeEnabled();
  });

  test("enrolling creates an order, initiates payment, and redirects to awaiting-confirmation — never redirects to a gateway URL directly", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, `**/api/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));

    const order = {
      id: "order-1",
      studentId: "student-1",
      courseId: COURSE.id,
      amount: COURSE.price,
      currency: "USD",
      status: "PLACED",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    await mockJson(page, "**/api/v1/orders", 201, apiSuccess(order));
    await mockJson(
      page,
      `**/api/v1/orders/${order.id}/payments`,
      201,
      apiSuccess({
        paymentId: "payment-1",
        orderId: order.id,
        status: "PENDING",
        gatewayReference: "gw-ref-1",
        redirectTarget: "https://example-gateway.test/pay/gw-ref-1",
      })
    );
    await mockJson(
      page,
      `**/api/v1/orders/${order.id}/payment-status`,
      200,
      apiSuccess({ hasPaymentAttempt: true, paymentId: "payment-1", status: "PENDING", confirmedAt: null })
    );
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));

    await page.goto(`/student/checkout/${COURSE.id}`);
    await page.getByRole("button", { name: "Pay online (PayHere)" }).click();

    await expect(page).toHaveURL(`/student/payments/awaiting-confirmation/${order.id}`);
  });
});

test.describe("checkout — idempotency (Wave 6 §3.3/§4/§5)", () => {
  /**
   * Regression: tenant portals are served per-subdomain over plain HTTP in
   * local dev (`http://demo.lms.test`), which is not a secure context, so
   * `crypto.randomUUID` is undefined there and the checkout page used to
   * crash on mount. This suite runs on `localhost` (a secure context), so the
   * API is removed before any page script runs to reproduce that condition.
   */
  test("checkout still renders and sends a v4 idempotencyKey when crypto.randomUUID is unavailable", async ({
    page,
  }) => {
    await page.addInitScript(() => {
      Object.defineProperty(crypto, "randomUUID", { value: undefined, configurable: true });
    });
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, `**/api/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));
    let orderRequestKey: string | undefined;
    await page.route("**/api/v1/orders", async (route) => {
      orderRequestKey = (route.request().postDataJSON() as { idempotencyKey?: string }).idempotencyKey;
      await fulfillJson(route, 500, apiError("INTERNAL_ERROR", "stop here"));
    });

    await page.goto(`/student/checkout/${COURSE.id}`);
    await expect(page.getByRole("heading", { name: COURSE.name })).toBeVisible();
    await page.getByRole("button", { name: "Pay online (PayHere)" }).click();

    await expect.poll(() => orderRequestKey).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
    );
  });

  test("Enroll sends a non-empty idempotencyKey on both order creation and payment initiation", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, `**/api/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));

    const order = {
      id: "order-idem-1",
      studentId: "student-1",
      courseId: COURSE.id,
      amount: COURSE.price,
      currency: "USD",
      billingPeriodId: null,
      status: "PLACED",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    let orderRequestKey: string | undefined;
    await page.route("**/api/v1/orders", async (route) => {
      const body = route.request().postDataJSON() as { idempotencyKey?: string };
      orderRequestKey = body.idempotencyKey;
      await fulfillJson(route, 201, apiSuccess(order));
    });
    let paymentRequestKey: string | undefined;
    await page.route(`**/api/v1/orders/${order.id}/payments`, async (route) => {
      const body = route.request().postDataJSON() as { idempotencyKey?: string };
      paymentRequestKey = body.idempotencyKey;
      await fulfillJson(
        route,
        201,
        apiSuccess({
          paymentId: "payment-idem-1",
          orderId: order.id,
          status: "PENDING",
          gatewayReference: "gw-ref-idem-1",
          redirectTarget: "https://example-gateway.test/pay/gw-ref-idem-1",
        })
      );
    });
    await mockJson(
      page,
      `**/api/v1/orders/${order.id}/payment-status`,
      200,
      apiSuccess({ hasPaymentAttempt: true, paymentId: "payment-idem-1", status: "PENDING", confirmedAt: null })
    );
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));

    await page.goto(`/student/checkout/${COURSE.id}`);
    await page.getByRole("button", { name: "Pay online (PayHere)" }).click();

    await expect(page).toHaveURL(`/student/payments/awaiting-confirmation/${order.id}`);
    expect(orderRequestKey).toBeTruthy();
    expect(paymentRequestKey).toBeTruthy();
    // Two structurally distinct submit actions (order creation vs. payment
    // initiation) get their own key — never the same value reused across
    // different request shapes.
    expect(orderRequestKey).not.toBe(paymentRequestKey);
  });

  test("a double-click on Enroll cannot submit a second request — the button disables synchronously on the first click", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, `**/api/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));

    let orderRequestCount = 0;
    const orderRequestKeys: string[] = [];
    let releaseOrder: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      releaseOrder = resolve;
    });
    const order = {
      id: "order-double-click",
      studentId: "student-1",
      courseId: COURSE.id,
      amount: COURSE.price,
      currency: "USD",
      billingPeriodId: null,
      status: "PLACED",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    };
    await page.route("**/api/v1/orders", async (route) => {
      orderRequestCount += 1;
      const body = route.request().postDataJSON() as { idempotencyKey?: string };
      if (body.idempotencyKey) orderRequestKeys.push(body.idempotencyKey);
      await gate;
      await fulfillJson(route, 201, apiSuccess(order));
    });
    await mockJson(
      page,
      `**/api/v1/orders/${order.id}/payments`,
      201,
      apiSuccess({
        paymentId: "payment-double-click",
        orderId: order.id,
        status: "PENDING",
        gatewayReference: "gw-ref-double-click",
        redirectTarget: "https://example-gateway.test/pay/gw-ref-double-click",
      })
    );
    await mockJson(
      page,
      `**/api/v1/orders/${order.id}/payment-status`,
      200,
      apiSuccess({ hasPaymentAttempt: true, paymentId: "payment-double-click", status: "PENDING", confirmedAt: null })
    );
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));

    await page.goto(`/student/checkout/${COURSE.id}`);
    // Matches the button under both its labels (`Pay online (PayHere)` before
    // submit, `Starting checkout…` while in flight) — the same underlying
    // <button> element throughout, never a re-query by a name that stops
    // matching once the label changes.
    const enrollButton = page.getByRole("button", {
      name: /^(Pay online \(PayHere\)|Starting checkout…)$/,
    });

    await enrollButton.click();
    // The button is disabled (and its label changes) synchronously as part
    // of the same click's state update — a genuine second user click can no
    // longer reach the handler at all while the first request is in flight.
    await expect(enrollButton).toHaveText("Starting checkout…");
    await expect(enrollButton).toBeDisabled();
    // A raw dispatched click on the now-disabled native <button> is a no-op
    // in every real browser (disabled elements never fire click), so this
    // proves the UI-level protection rather than merely asserting a visual
    // "disabled" attribute.
    await enrollButton.dispatchEvent("click");

    releaseOrder?.();
    await expect(page).toHaveURL(`/student/payments/awaiting-confirmation/${order.id}`);

    expect(orderRequestCount).toBe(1);
    expect(orderRequestKeys).toHaveLength(1);
    expect(orderRequestKeys[0]).toBeTruthy();
  });
});

test.describe("checkout — pricing-model-aware rendering (course-management gap fix)", () => {
  test("a FREE course shows \"Free\" and keeps the Enroll/Pay-by-bank-transfer actions enabled", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const freeCourse = {
      ...COURSE,
      id: "course-free",
      pricingModel: "FREE" as const,
      resolvedAmount: 0,
      currency: "USD",
      requiresManualQuote: false,
    };
    await mockJson(page, `**/api/v1/courses/${freeCourse.id}`, 200, apiSuccess(freeCourse));

    await page.goto(`/student/checkout/${freeCourse.id}`);

    await expect(page.getByText("Free", { exact: true })).toBeVisible();
    // A FREE course keeps the single "Enroll" action (no online payment).
    await expect(page.getByRole("button", { name: "Enroll" })).toBeEnabled();
    await expect(page.getByRole("button", { name: "Pay by bank transfer" })).toBeEnabled();
  });

  test("a MONTHLY course with no configured billing period shows a not-yet-available state and disables checkout rather than letting it fail", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const unconfigured = {
      ...COURSE,
      id: "course-monthly-unconfigured",
      pricingModel: "MONTHLY" as const,
      resolvedAmount: null,
      currency: "USD",
      requiresManualQuote: false,
    };
    await mockJson(page, `**/api/v1/courses/${unconfigured.id}`, 200, apiSuccess(unconfigured));

    await page.goto(`/student/checkout/${unconfigured.id}`);

    await expect(page.getByText("Pricing not yet available")).toBeVisible();
    await expect(
      page.getByText("Pricing for this course hasn't been configured yet", { exact: false })
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Pay online (PayHere)" })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Pay by bank transfer" })).toBeDisabled();
  });

  test("a CUSTOM-priced course shows contact-us messaging and never renders the Enroll/Pay-by-bank-transfer form at all", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const customCourse = {
      ...COURSE,
      id: "course-custom",
      pricingModel: "CUSTOM" as const,
      resolvedAmount: null,
      currency: "USD",
      requiresManualQuote: true,
    };
    await mockJson(page, `**/api/v1/courses/${customCourse.id}`, 200, apiSuccess(customCourse));

    await page.goto(`/student/checkout/${customCourse.id}`);

    await expect(page.getByText("Contact us for pricing")).toBeVisible();
    await expect(
      page.getByText("pricing and enrollment are arranged manually by our staff", { exact: false })
    ).toBeVisible();
    // The actual bug fix under test: no checkout form/buttons for a
    // manual-quote course, since order creation would always 409.
    await expect(page.getByRole("button", { name: "Pay online (PayHere)" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Pay by bank transfer" })).toHaveCount(0);
    await expect(page.locator("input")).toHaveCount(0);
  });
});

test.describe("awaiting-confirmation — never trusts a redirect query param", () => {
  test("a spoofed ?status=success param renders no confirmation; only a mocked CONFIRMED API response does", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const orderId = "order-spoofed";

    // Stateful: PENDING for the first calls, CONFIRMED afterward — proves
    // the page updates from the polled response, not from anything static.
    let callCount = 0;
    await page.route(`**/api/v1/orders/${orderId}/payment-status`, async (route) => {
      callCount += 1;
      const status = callCount <= 1 ? "PENDING" : "CONFIRMED";
      await fulfillJson(
        route,
        200,
        apiSuccess({
          hasPaymentAttempt: true,
          paymentId: "payment-spoofed",
          status,
          confirmedAt: status === "CONFIRMED" ? new Date().toISOString() : null,
        })
      );
    });
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}`,
      200,
      apiSuccess({
        id: orderId,
        studentId: "student-1",
        courseId: COURSE.id,
        amount: COURSE.price,
        currency: "USD",
        status: "PENDING",
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      })
    );

    // A classic "gateway redirect return" URL shape — the page must not read
    // this at all.
    await page.goto(`/student/payments/awaiting-confirmation/${orderId}?status=success&paid=true`);

    // First render: still PENDING per the mocked API — must show the
    // awaiting/loading state, never a confirmed message, despite the query
    // string claiming success.
    await expect(
      page.getByText("Awaiting payment confirmation", { exact: false })
    ).toBeVisible();
    await expect(page.getByText("Payment confirmed", { exact: false })).toHaveCount(0);

    // Once the polled API genuinely reports CONFIRMED, the page updates —
    // proving success is driven only by that response.
    await expect(page.getByText("Payment confirmed.", { exact: false })).toBeVisible({
      timeout: 10_000,
    });
  });

  test("a REJECTED payment-status response renders a role=alert failure with a Try again link, never a success state", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const orderId = "order-rejected";
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}/payment-status`,
      200,
      apiSuccess({ hasPaymentAttempt: true, paymentId: "payment-rejected", status: "REJECTED", confirmedAt: null })
    );
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}`,
      200,
      apiSuccess({
        id: orderId,
        studentId: "student-1",
        courseId: COURSE.id,
        amount: COURSE.price,
        currency: "USD",
        status: "PENDING",
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      })
    );

    await page.goto(`/student/payments/awaiting-confirmation/${orderId}?status=success`);

    const alert = page.getByRole("alert").filter({ hasText: "Your payment was rejected" });
    await expect(alert).toBeVisible();
    await expect(alert.getByRole("link", { name: "Try again" })).toHaveAttribute(
      "href",
      `/student/checkout/${COURSE.id}`
    );
    await expect(page.getByText("Payment confirmed", { exact: false })).toHaveCount(0);
  });

  test("a REFUNDED payment-status response renders its own distinct status, not the CONFIRMED or REJECTED copy", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const orderId = "order-refunded";
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}/payment-status`,
      200,
      apiSuccess({
        hasPaymentAttempt: true,
        paymentId: "payment-refunded",
        status: "REFUNDED",
        confirmedAt: new Date().toISOString(),
      })
    );
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}`,
      200,
      apiSuccess({
        id: orderId,
        studentId: "student-1",
        courseId: COURSE.id,
        amount: COURSE.price,
        currency: "USD",
        status: "PENDING",
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      })
    );

    await page.goto(`/student/payments/awaiting-confirmation/${orderId}`);

    await expect(page.getByText("This payment was refunded", { exact: false })).toBeVisible();
    await expect(
      page.getByRole("link", { name: "payment history" })
    ).toHaveAttribute("href", "/student/payments/history");
    await expect(page.getByText("Payment confirmed", { exact: false })).toHaveCount(0);
    await expect(page.getByText("Your payment was rejected", { exact: false })).toHaveCount(0);
  });
});

test.describe("awaiting-confirmation — no payment attempt yet", () => {
  test("hasPaymentAttempt: false, status: null renders the same awaiting state as PENDING, not an error or a crash", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const orderId = "order-no-attempt-yet";
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}/payment-status`,
      200,
      apiSuccess({ hasPaymentAttempt: false, paymentId: null, status: null, confirmedAt: null })
    );
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}`,
      200,
      apiSuccess({
        id: orderId,
        studentId: "student-1",
        courseId: COURSE.id,
        amount: COURSE.price,
        currency: "USD",
        status: "PLACED",
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      })
    );

    await page.goto(`/student/payments/awaiting-confirmation/${orderId}`);

    await expect(
      page.getByText("Awaiting payment confirmation", { exact: false })
    ).toBeVisible();
    await expect(page.getByText("Payment confirmed", { exact: false })).toHaveCount(0);
    await expect(page.getByText("Your payment was rejected", { exact: false })).toHaveCount(0);
    await expect(page.getByText("This payment was refunded", { exact: false })).toHaveCount(0);
  });
});

test.describe("payment history — zero-data empty state has distinct, contextual copy", () => {
  test("shows 'No payments yet' with copy explaining why, not a generic placeholder", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));

    await page.goto("/student/payments/history");

    await expect(page.getByText("No payments yet")).toBeVisible();
    await expect(
      page.getByText("You haven't made any payments yet", { exact: false })
    ).toBeVisible();
  });

  test("a non-empty history renders entry type, amount, and a link to the order", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      "**/api/v1/ledger/history",
      200,
      apiSuccess([
        {
          id: "ledger-1",
          orderId: "order-1",
          paymentId: "payment-1",
          entryType: "PAYMENT_CONFIRMED",
          amount: 49.99,
          reversesEntryId: null,
          createdAt: new Date().toISOString(),
        },
      ])
    );

    await page.goto("/student/payments/history");

    await expect(page.getByText("Payment confirmed")).toBeVisible();
    await expect(page.getByText("49.99")).toBeVisible();
    await expect(page.getByRole("link", { name: "View order status" })).toHaveAttribute(
      "href",
      "/student/payments/awaiting-confirmation/order-1"
    );
    await expect(page.getByText("No payments yet")).toHaveCount(0);
  });
});

const CONFIRMED_LEDGER_ENTRY = {
  id: "ledger-refund-1",
  orderId: "order-refund-1",
  paymentId: "payment-refund-1",
  entryType: "PAYMENT_CONFIRMED" as const,
  amount: 199.99,
  reversesEntryId: null,
  createdAt: new Date().toISOString(),
};

test.describe("awaiting-confirmation — polling stops once a terminal status is reached", () => {
  test("no further payment-status requests are made once the response reports CONFIRMED", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const orderId = "order-poll-stop";

    let callCount = 0;
    await page.route(`**/api/v1/orders/${orderId}/payment-status`, async (route) => {
      callCount += 1;
      const status = callCount <= 1 ? "PENDING" : "CONFIRMED";
      await fulfillJson(
        route,
        200,
        apiSuccess({
          hasPaymentAttempt: true,
          paymentId: "payment-poll-stop",
          status,
          confirmedAt: status === "CONFIRMED" ? new Date().toISOString() : null,
        })
      );
    });
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}`,
      200,
      apiSuccess({
        id: orderId,
        studentId: "student-1",
        courseId: COURSE.id,
        amount: COURSE.price,
        currency: "USD",
        status: "PENDING",
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      })
    );

    await page.goto(`/student/payments/awaiting-confirmation/${orderId}`);

    await expect(page.getByText("Payment confirmed.", { exact: false })).toBeVisible({
      timeout: 10_000,
    });
    const callCountAtConfirmation = callCount;

    // Give the 3s polling interval (see `useOrderPaymentStatus`'s
    // `refetchInterval`) a full extra cycle to prove it does NOT fire again
    // now that a terminal status has been reached — `refetchInterval`
    // returning `false` for a terminal status, not just "it happened not to
    // poll during the assertion window."
    await page.waitForTimeout(4000);
    expect(callCount).toBe(callCountAtConfirmation);
  });
});

test.describe("awaiting-confirmation — a foreign/guessed order id renders a generic state, never crashes or hangs the poll", () => {
  test("a 403 from payment-status renders the shared PermissionDeniedState, not an indefinite spinner", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const orderId = "order-foreign-403";
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}/payment-status`,
      403,
      apiError("FORBIDDEN", "You do not have permission to view this order.")
    );
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}`,
      403,
      apiError("FORBIDDEN", "You do not have permission to view this order.")
    );

    await page.goto(`/student/payments/awaiting-confirmation/${orderId}`);

    await expect(
      page.getByRole("alert").filter({ hasText: "You don't have permission to view this." })
    ).toBeVisible();
    await expect(page.getByText("Payment confirmed", { exact: false })).toHaveCount(0);
  });

  test("a 404 from payment-status renders the generic retryable error state, not a crash", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const orderId = "order-does-not-exist";
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}/payment-status`,
      404,
      apiError("NOT_FOUND", "Order not found.")
    );
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}`,
      404,
      apiError("NOT_FOUND", "Order not found.")
    );

    await page.goto(`/student/payments/awaiting-confirmation/${orderId}`);

    const alert = page.getByRole("alert").filter({ hasText: "Order not found." });
    await expect(alert).toBeVisible();
    await expect(alert.getByRole("button", { name: "Try again" })).toBeVisible();
    await expect(page.getByText("Payment confirmed", { exact: false })).toHaveCount(0);
  });
});

test.describe("payment history — a 403 on the underlying ledger read renders PermissionDeniedState, not a crash", () => {
  test("shows the generic permission-denied state, never leaked or guessed data", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      "**/api/v1/ledger/history",
      403,
      apiError("FORBIDDEN", "You do not have permission to view this.")
    );

    await page.goto("/student/payments/history");

    await expect(
      page.getByRole("alert").filter({ hasText: "You don't have permission to view this." })
    ).toBeVisible();
    await expect(page.getByText("No payments yet")).toHaveCount(0);
  });
});

test.describe("payment history — entry-type badge is distinct for PAYMENT_CONFIRMED vs REFUND, not color alone", () => {
  test("both badges render distinct text and their own icon within the same list", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      "**/api/v1/ledger/history",
      200,
      apiSuccess([
        {
          id: "ledger-confirmed-1",
          orderId: "order-1",
          paymentId: "payment-1",
          entryType: "PAYMENT_CONFIRMED",
          amount: 49.99,
          reversesEntryId: null,
          createdAt: new Date().toISOString(),
        },
        {
          id: "ledger-refund-1",
          orderId: "order-1",
          paymentId: "payment-1",
          entryType: "REFUND",
          amount: -49.99,
          reversesEntryId: "ledger-confirmed-1",
          createdAt: new Date().toISOString(),
        },
      ])
    );

    await page.goto("/student/payments/history");

    const confirmedBadge = page.getByText("Payment confirmed", { exact: true });
    const refundBadge = page.getByText("Refund", { exact: true });
    await expect(confirmedBadge).toBeVisible();
    await expect(refundBadge).toBeVisible();
    // Distinct icons too, not just distinct text — each badge renders its own <svg>.
    await expect(confirmedBadge.locator("svg")).toHaveCount(1);
    await expect(refundBadge.locator("svg")).toHaveCount(1);
  });
});

test.describe("payment history — Wave 6 extended fields, including the previously-broken manual-slip case", () => {
  test("renders course title, status, method, and reference for gateway, manual-slip, free, and staff-granted payments", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const baseEntry = {
      entryType: "PAYMENT_CONFIRMED" as const,
      reversesEntryId: null,
      createdAt: new Date().toISOString(),
    };
    await mockJson(
      page,
      "**/api/v1/ledger/history",
      200,
      apiSuccess([
        {
          ...baseEntry,
          id: "ledger-gateway",
          orderId: "order-gateway",
          paymentId: "payment-gateway",
          amount: 49.99,
          courseId: "course-gateway",
          courseTitle: "Intro to Biology",
          billingPeriodId: null,
          operationalState: "PAID",
          method: "GATEWAY",
          reference: "gw-ref-1",
        },
        {
          ...baseEntry,
          id: "ledger-manual-slip",
          orderId: "order-manual-slip",
          paymentId: "payment-manual-slip",
          amount: 79.0,
          courseId: "course-manual-slip",
          courseTitle: "Advanced Chemistry",
          // Wave 6 §1.2/§3.1 fix under test: a manually-approved slip payment
          // now resolves a real Payment/ledger row and is visible here, where
          // it was previously silently missing entirely.
          billingPeriodId: null,
          operationalState: "PAID",
          method: "MANUAL_SLIP",
          reference: "SLIP-REF-42",
        },
        {
          ...baseEntry,
          id: "ledger-free",
          orderId: "order-free",
          paymentId: "payment-free",
          amount: 0,
          courseId: "course-free",
          courseTitle: "Free Intro Workshop",
          billingPeriodId: null,
          operationalState: "PAID",
          method: "FREE",
          reference: "FREE-payment-free",
        },
        {
          ...baseEntry,
          id: "ledger-staff-granted",
          orderId: "order-staff-granted",
          paymentId: "payment-staff-granted",
          amount: 120.5,
          courseId: "course-staff-granted",
          courseTitle: "Monthly Coaching",
          billingPeriodId: "period-1",
          operationalState: "PAID",
          method: "STAFF_GRANTED",
          reference: "STAFF_GRANTED-payment-staff-granted",
        },
      ])
    );

    await page.goto("/student/payments/history");

    await expect(page.getByText("Intro to Biology")).toBeVisible();
    await expect(page.getByText("Advanced Chemistry")).toBeVisible();
    await expect(page.getByText("Free Intro Workshop")).toBeVisible();
    await expect(page.getByText("Monthly Coaching")).toBeVisible();

    await expect(page.getByText("Gateway", { exact: true })).toBeVisible();
    await expect(page.getByText("Manual bank transfer", { exact: true })).toBeVisible();
    await expect(page.getByText("Free enrollment", { exact: true })).toBeVisible();
    await expect(page.getByText("Staff granted", { exact: true })).toBeVisible();

    await expect(page.getByText("gw-ref-1")).toBeVisible();
    await expect(page.getByText("SLIP-REF-42")).toBeVisible();

    // Only the STAFF_GRANTED entry (the only one given a `billingPeriodId`
    // fixture value here) renders a billing period row — this is a per-entry
    // field, not a blanket page-level label.
    await expect(page.getByText("Billing period")).toBeVisible();

    // Every row renders a Paid status badge — icon + text, not color alone.
    const paidBadges = page.getByText("Paid", { exact: true });
    await expect(paidBadges).toHaveCount(4);
    for (let i = 0; i < 4; i += 1) {
      await expect(paidBadges.nth(i).locator("svg")).toHaveCount(1);
    }
  });
});

test.describe("refunds — filtered-empty state is distinct from the zero-data empty state", () => {
  test("a page with ledger entries but none PAYMENT_CONFIRMED shows the filtered-empty copy, not the zero-data copy", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    // Non-empty response, but every entry is a REFUND — none is
    // refund-eligible, so the page's client-side PAYMENT_CONFIRMED filter
    // produces zero rows even though `data.content.length > 0` (the
    // top-level QueryStateBoundary `isEmpty` check does not fire).
    await mockJson(
      page,
      "**/api/v1/ledger/dashboard*",
      200,
      apiPageSuccess([
        {
          id: "ledger-refund-only",
          orderId: "order-refund-only",
          paymentId: "payment-refund-only",
          entryType: "REFUND" as const,
          amount: -25,
          reversesEntryId: "ledger-some-confirmed",
          createdAt: new Date().toISOString(),
        },
      ])
    );

    await page.goto("/tenant-admin/payments?tab=refunds");

    await expect(page.getByText("No confirmed payments on this page")).toBeVisible();
    await expect(
      page.getByText("This page of results has no refund-eligible payments", { exact: false })
    ).toBeVisible();
    // Distinct from the zero-data "No confirmed payments yet" copy.
    await expect(page.getByText("No confirmed payments yet")).toHaveCount(0);
  });
});

test.describe("refunds — action visibility is role-gated, and a forced 403 is never silently swallowed", () => {
  test.beforeEach(async ({ page }) => {
    // The refund dialog verifies the payment/order before enabling submit.
    await mockJson(
      page,
      `**/api/v1/payments/${CONFIRMED_LEDGER_ENTRY.paymentId}/refund-verification`,
      200,
      apiSuccess({
        paymentId: CONFIRMED_LEDGER_ENTRY.paymentId,
        orderId: CONFIRMED_LEDGER_ENTRY.orderId,
        orderNumber: 1,
        displayOrderNumber: "ORD-000001",
        paymentAmount: CONFIRMED_LEDGER_ENTRY.amount,
        currency: "USD",
        paymentStatus: "CONFIRMED",
        gatewayReference: "GW-REF-1",
        paymentCreatedAt: CONFIRMED_LEDGER_ENTRY.createdAt,
        refundedAmount: 0,
        refundableAmount: CONFIRMED_LEDGER_ENTRY.amount,
        lines: [],
        postageAmount: null,
        delivery: null,
      })
    );
  });

  test("a STUDENT_SUPPORT session (VIEW only, no APPROVE) sees the payment but no Refund action", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT_SUPPORT");
    await mockJson(
      page,
      "**/api/v1/ledger/dashboard*",
      200,
      apiPageSuccess([CONFIRMED_LEDGER_ENTRY])
    );

    await page.goto("/tenant-admin/payments?tab=refunds");

    await expect(page.getByRole("table").getByRole("button", { name: "View order details" })).toBeVisible();
    await expect(page.getByRole("button", { name: /Refund/ })).toHaveCount(0);
    // PAY-HUB-01: Refunds is a tab of the single Payments hub - there is no separate nav link.
    await expect(page.getByRole("link", { name: "Refunds" })).toHaveCount(0);
  });

  test("a READ_ONLY_AUDITOR session sees no Refund action either", async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockJson(
      page,
      "**/api/v1/ledger/dashboard*",
      200,
      apiPageSuccess([CONFIRMED_LEDGER_ENTRY])
    );

    await page.goto("/tenant-admin/payments?tab=refunds");

    await expect(page.getByRole("table").getByRole("button", { name: "View order details" })).toBeVisible();
    await expect(page.getByRole("button", { name: /Refund/ })).toHaveCount(0);
  });

  test("a TENANT_ADMIN session sees the Refund action, and a forced 403 from the mutation is surfaced inline, not silently closed", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/api/v1/ledger/dashboard*",
      200,
      apiPageSuccess([CONFIRMED_LEDGER_ENTRY])
    );
    await mockJson(
      page,
      `**/api/v1/payments/${CONFIRMED_LEDGER_ENTRY.paymentId}/refunds`,
      403,
      apiError("FORBIDDEN", "You do not have permission to process refunds.")
    );

    await page.goto("/tenant-admin/payments?tab=refunds");

    const trigger = page.getByRole("button", {
      name: `Refund payment ${CONFIRMED_LEDGER_ENTRY.paymentId} for order ${CONFIRMED_LEDGER_ENTRY.orderId}`,
    });
    await expect(trigger).toBeVisible();
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("Refund amount").fill("199.99");
    await dialog.getByLabel("Reason").fill("Duplicate charge, refunding in full.");
    await dialog.getByRole("button", { name: "Submit refund" }).click();

    await expect(
      dialog.getByText("You do not have permission to process refunds.")
    ).toBeVisible();
    // Not silently closed/treated as success.
    await expect(dialog).toBeVisible();
  });

  test("Escape does not dismiss the refund confirmation — this destructive, money-moving dialog only closes via Cancel or a successful submit", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/api/v1/ledger/dashboard*",
      200,
      apiPageSuccess([CONFIRMED_LEDGER_ENTRY])
    );

    await page.goto("/tenant-admin/payments?tab=refunds");

    const trigger = page.getByRole("button", {
      name: `Refund payment ${CONFIRMED_LEDGER_ENTRY.paymentId} for order ${CONFIRMED_LEDGER_ENTRY.orderId}`,
    });
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("Refund amount").fill("50.00");

    await page.keyboard.press("Escape");

    // Still open, and the in-progress form value survives — an accidental
    // Escape must not silently discard a partially-filled destructive form.
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel("Refund amount")).toHaveValue("50.00");

    // The explicit Cancel control still works.
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toHaveCount(0);
  });

  test("a successful refund closes the dialog and triggers a refetch of the ledger list (query invalidation)", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");

    // The Refunds screen lists every PAYMENT_CONFIRMED ledger entry as
    // refund-eligible (there's no separate "refundable" flag — see
    // refunds/page.tsx's doc comment), so a just-refunded payment's row does
    // not disappear or visually change on this screen; what this test proves
    // instead is that `onSuccess`'s `invalidateQueries` actually fires a
    // second GET after a successful refund, not that the row's content changes.
    let dashboardRequestCount = 0;
    await page.route("**/api/v1/ledger/dashboard*", async (route) => {
      dashboardRequestCount += 1;
      await fulfillJson(route, 200, apiPageSuccess([CONFIRMED_LEDGER_ENTRY]));
    });
    await mockJson(
      page,
      `**/api/v1/payments/${CONFIRMED_LEDGER_ENTRY.paymentId}/refunds`,
      201,
      apiSuccess({
        id: "refund-1",
        originalPaymentId: CONFIRMED_LEDGER_ENTRY.paymentId,
        amount: 50,
        reason: "Duplicate charge, partial refund.",
        createdAt: new Date().toISOString(),
      })
    );

    await page.goto("/tenant-admin/payments?tab=refunds");
    await expect(page.getByRole("table").getByRole("button", { name: "View order details" })).toBeVisible();
    const requestCountBeforeRefund = dashboardRequestCount;

    const trigger = page.getByRole("button", {
      name: `Refund payment ${CONFIRMED_LEDGER_ENTRY.paymentId} for order ${CONFIRMED_LEDGER_ENTRY.orderId}`,
    });
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Refund amount").fill("50");
    await dialog.getByLabel("Reason").fill("Duplicate charge, partial refund.");
    await dialog.getByRole("button", { name: "Submit refund" }).click();

    await expect(dialog).toHaveCount(0);
    await expect
      .poll(() => dashboardRequestCount, { timeout: 5000 })
      .toBeGreaterThan(requestCountBeforeRefund);
  });

  test("a 409 refund-amount-exceeds-remainder error is mapped onto the amount field, not shown as a generic page error", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/api/v1/ledger/dashboard*",
      200,
      apiPageSuccess([CONFIRMED_LEDGER_ENTRY])
    );
    await mockJson(
      page,
      `**/api/v1/payments/${CONFIRMED_LEDGER_ENTRY.paymentId}/refunds`,
      409,
      apiError("CONFLICT", "Refund amount exceeds the refundable remainder", [
        { field: "amount", message: "Refund amount exceeds the refundable remainder" },
      ])
    );

    await page.goto("/tenant-admin/payments?tab=refunds");

    const trigger = page.getByRole("button", {
      name: `Refund payment ${CONFIRMED_LEDGER_ENTRY.paymentId} for order ${CONFIRMED_LEDGER_ENTRY.orderId}`,
    });
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Refund amount").fill("500");
    await dialog.getByLabel("Reason").fill("Attempting an over-refund.");
    await dialog.getByRole("button", { name: "Submit refund" }).click();

    const amountField = dialog.getByLabel("Refund amount");
    await expect(amountField).toHaveAttribute("aria-invalid", "true");
    await expect(
      dialog.getByText("Refund amount exceeds the refundable remainder")
    ).toBeVisible();
    // Dialog stays open — the error is field-level feedback, not a silently dropped failure.
    await expect(dialog).toBeVisible();
  });

  test("a FINANCE_STAFF session sees the Refund action too — the second of only two roles holding PAYMENTS_SLIPS/APPROVE", async ({
    page,
  }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await mockJson(
      page,
      "**/api/v1/ledger/dashboard*",
      200,
      apiPageSuccess([CONFIRMED_LEDGER_ENTRY])
    );

    await page.goto("/tenant-admin/payments?tab=refunds");

    await expect(
      page.getByRole("button", {
        name: `Refund payment ${CONFIRMED_LEDGER_ENTRY.paymentId} for order ${CONFIRMED_LEDGER_ENTRY.orderId}`,
      })
    ).toBeVisible();
    await expect(page.getByRole("tab", { name: "Refunds" })).toBeVisible();
  });

  test("the refund dialog traps focus while open and returns focus to the trigger button on Cancel", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/api/v1/ledger/dashboard*",
      200,
      apiPageSuccess([CONFIRMED_LEDGER_ENTRY])
    );

    await page.goto("/tenant-admin/payments?tab=refunds");

    const trigger = page.getByRole("button", {
      name: `Refund payment ${CONFIRMED_LEDGER_ENTRY.paymentId} for order ${CONFIRMED_LEDGER_ENTRY.orderId}`,
    });
    await trigger.focus();
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();

    // Focus starts inside the modal (@base-ui/react's AlertDialog moves
    // focus into the popup on open) — verify it never lands on the
    // now-inert page content behind it. Tab repeatedly through every
    // focusable control in the dialog and confirm focus never leaves it.
    const focusableCount = await dialog
      .locator("button, input, a[href], [tabindex]:not([tabindex='-1'])")
      .count();
    for (let i = 0; i < focusableCount + 2; i += 1) {
      await page.keyboard.press("Tab");
      await expect(dialog.locator(":focus")).toHaveCount(1);
    }

    // Closing via Cancel returns focus to the element that opened the
    // dialog, so a keyboard user isn't dropped back at the top of the page.
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });

  test("submitting a refund sends a non-empty idempotencyKey, and a fresh dialog-open uses a fresh key", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/api/v1/ledger/dashboard*",
      200,
      apiPageSuccess([CONFIRMED_LEDGER_ENTRY])
    );

    const sentKeys: string[] = [];
    await page.route(
      `**/api/v1/payments/${CONFIRMED_LEDGER_ENTRY.paymentId}/refunds`,
      async (route) => {
        const body = route.request().postDataJSON() as { idempotencyKey?: string };
        if (body.idempotencyKey) sentKeys.push(body.idempotencyKey);
        await fulfillJson(
          route,
          201,
          apiSuccess({
            id: `refund-${sentKeys.length}`,
            originalPaymentId: CONFIRMED_LEDGER_ENTRY.paymentId,
            amount: 25,
            reason: "Partial refund.",
            createdAt: new Date().toISOString(),
          })
        );
      }
    );

    await page.goto("/tenant-admin/payments?tab=refunds");

    const trigger = page.getByRole("button", {
      name: `Refund payment ${CONFIRMED_LEDGER_ENTRY.paymentId} for order ${CONFIRMED_LEDGER_ENTRY.orderId}`,
    });

    // First submit.
    await trigger.click();
    let dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Refund amount").fill("25");
    await dialog.getByLabel("Reason").fill("Partial refund.");
    await dialog.getByRole("button", { name: "Submit refund" }).click();
    await expect(dialog).toHaveCount(0);

    // Second, separate submit from a freshly-reopened dialog.
    await trigger.click();
    dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Refund amount").fill("25");
    await dialog.getByLabel("Reason").fill("Partial refund.");
    await dialog.getByRole("button", { name: "Submit refund" }).click();
    await expect(dialog).toHaveCount(0);

    expect(sentKeys).toHaveLength(2);
    expect(sentKeys[0]).toBeTruthy();
    expect(sentKeys[1]).toBeTruthy();
    // Two distinct dialog-opens are two distinct logical refund attempts —
    // each gets its own key, never reused across genuinely separate submits.
    expect(sentKeys[0]).not.toBe(sentKeys[1]);
  });
});
