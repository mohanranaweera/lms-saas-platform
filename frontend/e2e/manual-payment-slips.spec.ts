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
 * MVP-011 Manual Payment Slips — frontend flows:
 *
 * A. Checkout's "Pay by bank transfer" action (no gateway call, redirects to
 *    slip-upload).
 * B. Student Payment Slip Upload (`slip-upload/[orderId]`).
 * C. Tenant Admin Manual Slip Review Queue (`slip-review`).
 * D. Tenant Admin Slip Detail + Approve/Reject dialogs
 *    (`slip-review/[slipId]`).
 *
 * No real backend runs in this environment — every test mocks `/api/v1/**`
 * via `page.route`/`mockJson`, following `order-and-payment.spec.ts`'s exact
 * structure/conventions (that file is MVP-010, the closest sibling in the
 * same payment domain).
 */

async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
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
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
};

function makeOrder(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "order-slip-1",
    studentId: "student-1",
    courseId: COURSE.id,
    amount: COURSE.price,
    currency: "USD",
    status: "PLACED",
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...overrides,
  };
}

interface SlipFlag {
  id: string;
  flagType: "DUPLICATE_REFERENCE" | "DUPLICATE_IMAGE_HASH";
  detectedAt: string;
}

function makeSlip(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "slip-1",
    orderId: "order-slip-1",
    studentId: "student-1",
    referenceNumber: "REF-12345",
    status: "SUBMITTED" as const,
    submittedAt: new Date().toISOString(),
    reviewerId: null,
    reviewedAt: null,
    flags: [] as SlipFlag[],
    studentEmail: "student@example.com",
    reviewerEmail: null,
    orderAmount: 49.99,
    orderCurrency: "USD",
    ...overrides,
  };
}

async function selectSlipFile(page: Page, filename = "slip.png") {
  await page.locator('input[type="file"]').setInputFiles({
    name: filename,
    mimeType: "image/png",
    buffer: Buffer.from("fake png content"),
  });
}

test.describe("checkout — Pay by bank transfer (MVP-011)", () => {
  test("creates an order and redirects to slip-upload, never calling the gateway payments endpoint", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, `**/api/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));

    const order = makeOrder();
    await mockJson(page, "**/api/v1/orders", 201, apiSuccess(order));

    let paymentsCalled = false;
    await page.route(`**/api/v1/orders/${order.id}/payments`, async (route) => {
      paymentsCalled = true;
      await fulfillJson(
        route,
        201,
        apiSuccess({
          paymentId: "payment-should-not-happen",
          orderId: order.id,
          status: "PENDING",
          gatewayReference: "gw-ref-should-not-happen",
          redirectTarget: "https://example-gateway.test/pay/should-not-happen",
        })
      );
    });

    await page.goto(`/student/checkout/${COURSE.id}`);
    await page.getByRole("button", { name: "Pay by bank transfer" }).click();

    await expect(page).toHaveURL(`/student/payments/slip-upload/${order.id}`);
    expect(paymentsCalled).toBe(false);
  });

  test("both Enroll and Pay by bank transfer are disabled while either submission is in flight", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, `**/api/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));

    const order = makeOrder();
    let releaseOrderCreate: (() => void) | undefined;
    const orderCreateGate = new Promise<void>((resolve) => {
      releaseOrderCreate = resolve;
    });
    await page.route("**/api/v1/orders", async (route) => {
      if (route.request().method() !== "POST") {
        await route.fallback();
        return;
      }
      await orderCreateGate;
      await fulfillJson(route, 201, apiSuccess(order));
    });

    await page.goto(`/student/checkout/${COURSE.id}`);
    await page.getByRole("button", { name: "Pay by bank transfer" }).click();

    const enrollButton = page.getByRole("button", { name: /Enroll|Starting checkout/ });
    const slipButton = page.getByRole("button", { name: /Pay by bank transfer|Preparing/ });
    await expect(slipButton).toBeDisabled();
    await expect(enrollButton).toBeDisabled();
    await expect(page.getByText("Preparing…")).toBeVisible();

    releaseOrderCreate?.();
    await expect(page).toHaveURL(`/student/payments/slip-upload/${order.id}`);
  });
});

test.describe("student payment slip upload — order fetch states", () => {
  test("a 403 on the underlying order fetch renders PermissionDeniedState, not a crash", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const orderId = "order-foreign-403";
    await mockJson(
      page,
      `**/api/v1/orders/${orderId}`,
      403,
      apiError("FORBIDDEN", "You do not have permission to view this order.")
    );

    await page.goto(`/student/payments/slip-upload/${orderId}`);

    await expect(
      page.getByRole("alert").filter({ hasText: "You don't have permission to view this." })
    ).toBeVisible();
  });

  test("a 404 on the underlying order fetch renders the generic retryable error state", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const orderId = "order-does-not-exist";
    await mockJson(page, `**/api/v1/orders/${orderId}`, 404, apiError("NOT_FOUND", "Order not found."));

    await page.goto(`/student/payments/slip-upload/${orderId}`);

    const alert = page.getByRole("alert").filter({ hasText: "Order not found." });
    await expect(alert).toBeVisible();
    await expect(alert.getByRole("button", { name: "Try again" })).toBeVisible();
  });

  test("shows the 'Loading order…' label while the order fetch is in flight, then renders the form", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const order = makeOrder({ id: "order-slip-loading" });

    let releaseOrderFetch: (() => void) | undefined;
    const orderFetchGate = new Promise<void>((resolve) => {
      releaseOrderFetch = resolve;
    });
    await page.route(`**/api/v1/orders/${order.id}`, async (route) => {
      await orderFetchGate;
      await fulfillJson(route, 200, apiSuccess(order));
    });

    await page.goto(`/student/payments/slip-upload/${order.id}`);

    await expect(page.getByText("Loading order…")).toBeVisible();
    await expect(page.getByLabel("Reference number")).toHaveCount(0);

    releaseOrderFetch?.();

    await expect(page.getByLabel("Reference number")).toBeVisible();
    await expect(page.getByText("Loading order…")).toHaveCount(0);
  });
});

test.describe("student payment slip upload — successful submission", () => {
  test("replaces the form with a success panel showing submitted/under-review copy, never 'paid' or 'confirmed'", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const order = makeOrder({ id: "order-slip-success" });
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));

    const slip = makeSlip({ orderId: order.id, referenceNumber: "REF-99999" });
    await mockJson(page, `**/api/v1/orders/${order.id}/slips`, 201, apiSuccess(slip));

    await page.goto(`/student/payments/slip-upload/${order.id}`);
    await page.getByLabel("Reference number").fill("REF-99999");
    await selectSlipFile(page);
    await page.getByRole("button", { name: "Submit payment slip" }).click();

    await expect(
      page.getByText("Your course access will unlock once a reviewer approves this slip.", {
        exact: false,
      })
    ).toBeVisible();
    await expect(page.getByText("Reference: REF-99999")).toBeVisible();
    await expect(page.getByRole("button", { name: "Submit payment slip" })).toHaveCount(0);
    // Scoped to the success panel itself (rather than the whole page) so a
    // future, unrelated page addition containing "paid"/"confirmed" text
    // elsewhere can't cause a false pass/fail here.
    const successPanel = page.locator("div").filter({
      hasText: "Your course access will unlock once a reviewer approves this slip.",
    });
    await expect(successPanel.getByText("paid", { exact: false })).toHaveCount(0);
    await expect(successPanel.getByText("confirmed", { exact: false })).toHaveCount(0);
  });

  test("a non-empty flags array on the upload response renders the flag badge(s) on the success panel", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const order = makeOrder({ id: "order-slip-flagged" });
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));

    const slip = makeSlip({
      orderId: order.id,
      status: "UNDER_REVIEW",
      flags: [
        { id: "flag-1", flagType: "DUPLICATE_REFERENCE", detectedAt: new Date().toISOString() },
      ],
    });
    await mockJson(page, `**/api/v1/orders/${order.id}/slips`, 201, apiSuccess(slip));

    await page.goto(`/student/payments/slip-upload/${order.id}`);
    await page.getByLabel("Reference number").fill(slip.referenceNumber);
    await selectSlipFile(page);
    await page.getByRole("button", { name: "Submit payment slip" }).click();

    await expect(page.getByText("Duplicate reference")).toBeVisible();
    await expect(
      page.getByText("automatically flagged for a reviewer", { exact: false })
    ).toBeVisible();
  });

  test("a slip with no flags shows no flag badge on the success panel", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    const order = makeOrder({ id: "order-slip-unflagged" });
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));

    const slip = makeSlip({ orderId: order.id, flags: [] });
    await mockJson(page, `**/api/v1/orders/${order.id}/slips`, 201, apiSuccess(slip));

    await page.goto(`/student/payments/slip-upload/${order.id}`);
    await page.getByLabel("Reference number").fill(slip.referenceNumber);
    await selectSlipFile(page);
    await page.getByRole("button", { name: "Submit payment slip" }).click();

    await expect(
      page.getByText("Your course access will unlock once a reviewer approves this slip.", {
        exact: false,
      })
    ).toBeVisible();
    await expect(page.getByText("Duplicate reference")).toHaveCount(0);
    await expect(page.getByText("Duplicate image")).toHaveCount(0);
  });
});

test.describe("student payment slip upload — backend rejection surfaces through the same alert region", () => {
  test("a 415 UNSUPPORTED_MEDIA_TYPE rejection renders inline via role=alert, form stays and no success panel appears", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const order = makeOrder({ id: "order-slip-415" });
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));
    await mockJson(
      page,
      `**/api/v1/orders/${order.id}/slips`,
      415,
      apiError("UNSUPPORTED_MEDIA_TYPE", "This file's actual content doesn't match an accepted format.")
    );

    await page.goto(`/student/payments/slip-upload/${order.id}`);
    await page.getByLabel("Reference number").fill("REF-415");
    // A client-side-valid file (correct extension/MIME per the browser) —
    // the backend's magic-byte sniffing is what rejects it, proving this
    // isn't only exercising the Zod client-side gate.
    await selectSlipFile(page);
    await page.getByRole("button", { name: "Submit payment slip" }).click();

    await expect(
      page
        .getByRole("alert")
        .filter({ hasText: "This file's actual content doesn't match an accepted format." })
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Submit payment slip" })).toBeVisible();
    await expect(page.getByText("Submitted — under review", { exact: false })).toHaveCount(0);
  });

  test("a 413 PAYLOAD_TOO_LARGE rejection renders inline via the same alert region", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const order = makeOrder({ id: "order-slip-413" });
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));
    await mockJson(
      page,
      `**/api/v1/orders/${order.id}/slips`,
      413,
      apiError("PAYLOAD_TOO_LARGE", "This file exceeds the maximum upload size.")
    );

    await page.goto(`/student/payments/slip-upload/${order.id}`);
    await page.getByLabel("Reference number").fill("REF-413");
    await selectSlipFile(page);
    await page.getByRole("button", { name: "Submit payment slip" }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "This file exceeds the maximum upload size." })
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Submit payment slip" })).toBeVisible();
  });

  test("a backend referenceNumber field-validation error is mapped onto the reference-number field, not shown as a generic alert", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const order = makeOrder({ id: "order-slip-field-error" });
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));
    await mockJson(
      page,
      `**/api/v1/orders/${order.id}/slips`,
      400,
      apiError("VALIDATION_ERROR", "Request validation failed", [
        { field: "uploadSlip.referenceNumber", message: "must not be blank" },
      ])
    );

    await page.goto(`/student/payments/slip-upload/${order.id}`);
    await page.getByLabel("Reference number").fill("REF-FIELD-ERROR");
    await selectSlipFile(page);
    await page.getByRole("button", { name: "Submit payment slip" }).click();

    const referenceField = page.getByLabel("Reference number");
    await expect(referenceField).toHaveAttribute("aria-invalid", "true");
    await expect(page.getByText("must not be blank")).toBeVisible();
    // Mapped to the field, not the generic top-level alert region.
    await expect(page.getByRole("alert").filter({ hasText: "must not be blank" })).toBeVisible();
    await expect(
      page.getByText("Please fix the highlighted fields and try again.")
    ).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Submit payment slip" })).toBeVisible();
  });

  test("the file input's accept attribute matches the allow-listed types exported by lib/validation/payment-slip.ts", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const order = makeOrder({ id: "order-slip-accept" });
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));

    await page.goto(`/student/payments/slip-upload/${order.id}`);

    // Intentionally a literal string, not an import of `ACCEPTED_SLIP_MIME_TYPES`
    // from `@/lib/validation/payment-slip` — no spec in this `e2e/` suite
    // imports from `src/lib` (checked across the whole directory), and Playwright
    // specs here run against the built app rather than sharing a module graph
    // with it. Keep this literal in sync with `ACCEPTED_SLIP_MIME_TYPES` by hand
    // if that constant's value ever changes.
    await expect(page.locator('input[type="file"]')).toHaveAttribute(
      "accept",
      "application/pdf,image/png,image/jpeg,image/gif"
    );
  });
});

test.describe("student payment slip upload — client-side validation (regression)", () => {
  // Regression coverage for the fixed Critical bug: `slipUploadSchema.file`'s
  // first refine (`value instanceof File`) now passes `abort: true`, so a
  // missing file no longer lets a later `.refine()` throw a raw `TypeError`
  // on `undefined` — it now surfaces the intended
  // "Select a file to upload." message instead.
  test("submitting with no file selected shows the Zod file-required message, not a crash, and never calls the upload endpoint", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const order = makeOrder({ id: "order-slip-no-file" });
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));

    let uploadCalled = false;
    await page.route(`**/api/v1/orders/${order.id}/slips`, async (route) => {
      uploadCalled = true;
      await fulfillJson(route, 201, apiSuccess(makeSlip({ orderId: order.id })));
    });

    await page.goto(`/student/payments/slip-upload/${order.id}`);
    await page.getByLabel("Reference number").fill("REF-NO-FILE");
    await page.getByRole("button", { name: "Submit payment slip" }).click();

    // Renders twice (the field-level paragraph and the shared submit-error
    // banner) — `.first()` just needs one of them visible and non-crashing.
    await expect(page.getByText("Select a file to upload.").first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Submit payment slip" })).toBeVisible();
    expect(uploadCalled).toBe(false);
  });

  test("submitting with a blank reference number shows the Zod required message and never calls the upload endpoint", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const order = makeOrder({ id: "order-slip-no-ref" });
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));

    let uploadCalled = false;
    await page.route(`**/api/v1/orders/${order.id}/slips`, async (route) => {
      uploadCalled = true;
      await fulfillJson(route, 201, apiSuccess(makeSlip({ orderId: order.id })));
    });

    await page.goto(`/student/payments/slip-upload/${order.id}`);
    await selectSlipFile(page);
    await page.getByRole("button", { name: "Submit payment slip" }).click();

    await expect(page.getByText("Reference number is required.").first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Submit payment slip" })).toBeVisible();
    expect(uploadCalled).toBe(false);
  });
});

test.describe("student payment slip upload — no client-side duplicate-detection network calls", () => {
  test("between form submission and the success panel, the only /api/v1 request fired is the upload POST itself", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const order = makeOrder({ id: "order-slip-network-check" });
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));

    const slip = makeSlip({
      orderId: order.id,
      status: "UNDER_REVIEW",
      flags: [
        { id: "flag-1", flagType: "DUPLICATE_REFERENCE", detectedAt: new Date().toISOString() },
      ],
    });
    await mockJson(page, `**/api/v1/orders/${order.id}/slips`, 201, apiSuccess(slip));

    await page.goto(`/student/payments/slip-upload/${order.id}`);
    // Attach the request listener only once the initial order fetch (and the
    // session's auth refresh, which hits `/v1/auth/refresh` — a different
    // path prefix, not `/api/v1/**`) have already resolved, so it isolates
    // exactly the upload interaction that follows.
    await expect(page.getByLabel("Reference number")).toBeVisible();

    const apiV1Requests: Array<{ method: string; path: string }> = [];
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (url.pathname.includes("/api/v1/")) {
        apiV1Requests.push({ method: request.method(), path: url.pathname });
      }
    });

    await page.getByLabel("Reference number").fill(slip.referenceNumber);
    await selectSlipFile(page);
    await page.getByRole("button", { name: "Submit payment slip" }).click();

    await expect(page.getByText("Duplicate reference")).toBeVisible();

    // The frontend renders `flags` verbatim from the upload response — it
    // never independently queries any other endpoint to compute or confirm
    // duplicate-detection itself. Matched by suffix (rather than an exact
    // full path) because `NEXT_PUBLIC_API_BASE_URL` in this dev/test
    // environment already ends in `/api`, so the real request path is
    // `/api/api/v1/...` — an environment quirk, not something this test
    // should hard-code.
    expect(apiV1Requests).toHaveLength(1);
    expect(apiV1Requests[0].method).toBe("POST");
    expect(apiV1Requests[0].path.endsWith(`/api/v1/orders/${order.id}/slips`)).toBe(true);
  });
});

test.describe("tenant admin — Manual Slip Review Queue role gating", () => {
  test("a STUDENT session sees PermissionDeniedState on a real 403, not a crash", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      "**/api/v1/payment-slips/review-queue*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view the slip review queue.")
    );

    await page.goto("/tenant-admin/payments/slip-review");

    await expect(
      page.getByRole("alert").filter({ hasText: "You don't have permission to view this." })
    ).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
  });

  test("a role without PAYMENTS_SLIPS/VIEW (Content Manager) does not see the Payment Slips nav link", async ({
    page,
  }) => {
    // `canViewPaymentDashboard` (lib/auth/permissions.ts), which also gates
    // this nav entry, grants TENANT_ADMIN/FINANCE_STAFF/STUDENT_SUPPORT/
    // READ_ONLY_AUDITOR — every role at or above STUDENT_SUPPORT holds VIEW.
    // CONTENT_MANAGER is explicitly called out in
    // `tenant-admin-nav.tsx`'s own doc comment as an excluded staff role, so
    // it is used here rather than STUDENT (a different, non-staff role
    // group already covered by the 403 test above).
    await mockTenantSession(page, "CONTENT_MANAGER");

    await page.goto("/tenant-admin/dashboard");

    await expect(page.getByRole("link", { name: "Payment Slips" })).toHaveCount(0);
  });
});

test.describe("tenant admin — Manual Slip Review Queue empty states are distinct", () => {
  test("zero-data on the default Pending review filter, first page, shows 'No pending slips'", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await page.route("**/api/v1/payment-slips/review-queue*", async (route) => {
      const url = new URL(route.request().url());
      expect(url.searchParams.has("status")).toBe(false);
      expect(url.searchParams.get("page")).toBe("0");
      await fulfillJson(route, 200, apiPageSuccess([]));
    });

    await page.goto("/tenant-admin/payments/slip-review");

    await expect(page.getByText("No pending slips")).toBeVisible();
    await expect(
      page.getByText("Once a student submits a manual payment slip", { exact: false })
    ).toBeVisible();
    await expect(page.getByText("No slips match your filter")).toHaveCount(0);
    await expect(page.getByRole("table")).toHaveCount(0);
  });

  test("a specific status filter returning empty shows the distinct 'No slips match your filter' copy", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await page.route("**/api/v1/payment-slips/review-queue*", async (route) => {
      const url = new URL(route.request().url());
      const status = url.searchParams.get("status");
      if (status === "REJECTED") {
        await fulfillJson(route, 200, apiPageSuccess([]));
      } else {
        await fulfillJson(route, 200, apiPageSuccess([makeSlip({ id: "slip-pending-1" })]));
      }
    });

    await page.goto("/tenant-admin/payments/slip-review");
    await expect(page.getByRole("table")).toBeVisible();

    await page.getByLabel("Status").click();
    await page.getByRole("option", { name: "Rejected" }).click();

    await expect(page.getByText("No slips match your filter")).toBeVisible();
    await expect(
      page.getByText("Try a different status filter or go back to an earlier page.")
    ).toBeVisible();
    // Distinct from the zero-data copy — never reused for this different situation.
    await expect(page.getByText("No pending slips")).toHaveCount(0);
    await expect(page.getByRole("table")).toHaveCount(0);

    // The empty state's own "Reset filters" action returns to the default filter.
    await page.getByRole("button", { name: "Reset filters" }).click();
    await expect(page.getByRole("table")).toBeVisible();
  });
});

test.describe("tenant admin — Manual Slip Review Queue filter and pagination", () => {
  test("changing the status filter sends the right status query param and resets to page 0", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");

    const requests: Array<{ status: string | null; page: string | null }> = [];
    await page.route("**/api/v1/payment-slips/review-queue*", async (route) => {
      const url = new URL(route.request().url());
      requests.push({ status: url.searchParams.get("status"), page: url.searchParams.get("page") });
      await fulfillJson(
        route,
        200,
        apiPageSuccess([makeSlip({ id: `slip-${requests.length}` })], {
          page: Number(url.searchParams.get("page") ?? "0"),
          totalPages: 2,
          totalElements: 2,
        })
      );
    });

    await page.goto("/tenant-admin/payments/slip-review");
    await expect(page.getByRole("table")).toBeVisible();

    // Move off page 0 first, so we can prove the filter change resets it.
    await page.getByRole("button", { name: "Next", exact: true }).click();
    await expect(page.getByText("Page 2 of 2")).toBeVisible();

    await page.getByLabel("Status").click();
    await page.getByRole("option", { name: "Under review" }).click();

    await expect(page.getByText("Page 1 of 2")).toBeVisible();
    const lastRequest = requests[requests.length - 1];
    expect(lastRequest.status).toBe("UNDER_REVIEW");
    expect(lastRequest.page).toBe("0");
  });

  test("Next/Previous fetch and render a different page of slips", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");

    await page.route("**/api/v1/payment-slips/review-queue*", async (route) => {
      const url = new URL(route.request().url());
      const requestedPage = Number(url.searchParams.get("page") ?? "0");
      await fulfillJson(
        route,
        200,
        apiPageSuccess([makeSlip({ id: `slip-page-${requestedPage}`, referenceNumber: `REF-PAGE-${requestedPage}` })], {
          page: requestedPage,
          totalPages: 2,
          totalElements: 2,
        })
      );
    });

    await page.goto("/tenant-admin/payments/slip-review");

    const table = page.getByRole("table");
    await expect(table.getByText("REF-PAGE-0")).toBeVisible();
    await expect(page.getByText("Page 1 of 2")).toBeVisible();
    await expect(page.getByRole("button", { name: "Previous" })).toBeDisabled();

    await page.getByRole("button", { name: "Next", exact: true }).click();

    await expect(table.getByText("REF-PAGE-1")).toBeVisible();
    await expect(table.getByText("REF-PAGE-0")).toHaveCount(0);
    await expect(page.getByText("Page 2 of 2")).toBeVisible();
    await expect(page.getByRole("button", { name: "Next", exact: true })).toBeDisabled();

    await page.getByRole("button", { name: "Previous" }).click();

    await expect(table.getByText("REF-PAGE-0")).toBeVisible();
    await expect(page.getByText("Page 1 of 2")).toBeVisible();
  });
});

test.describe("tenant admin — Slip Detail fetch states", () => {
  test("shows the 'Loading slip…' label while the slip fetch is in flight, then renders the detail", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-loading", status: "UNDER_REVIEW" });

    let releaseSlipFetch: (() => void) | undefined;
    const slipFetchGate = new Promise<void>((resolve) => {
      releaseSlipFetch = resolve;
    });
    await page.route(`**/api/v1/payment-slips/${slip.id}`, async (route) => {
      await slipFetchGate;
      await fulfillJson(route, 200, apiSuccess(slip));
    });

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);

    await expect(page.getByText("Loading slip…")).toBeVisible();
    await expect(page.getByText(`Reference: ${slip.referenceNumber}`)).toHaveCount(0);

    releaseSlipFetch?.();

    await expect(page.getByText(`Reference: ${slip.referenceNumber}`)).toBeVisible();
    await expect(page.getByText("Loading slip…")).toHaveCount(0);
  });

  test("renders the student email, formatted order amount/currency, and order id — reviewer shows '—' before review", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({
      id: "slip-fields",
      status: "UNDER_REVIEW",
      studentEmail: "jane.student@example.com",
      orderAmount: 129.5,
      orderCurrency: "USD",
      reviewerEmail: null,
    });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);

    await expect(page.getByText("jane.student@example.com")).toBeVisible();
    await expect(page.getByText("129.50 USD")).toBeVisible();
    await expect(page.getByText(slip.orderId)).toBeVisible();
    const reviewerRow = page.locator("dt", { hasText: "Reviewer" }).locator("xpath=following-sibling::dd[1]");
    await expect(reviewerRow).toHaveText("—");
  });

  test("after a successful approve, the reviewer's email replaces the '—' placeholder", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({
      id: "slip-fields-approved",
      status: "UNDER_REVIEW",
      flags: [],
      reviewerEmail: null,
    });

    let approved = false;
    await page.route(`**/api/v1/payment-slips/${slip.id}`, async (route) => {
      await fulfillJson(
        route,
        200,
        apiSuccess(
          approved
            ? {
                ...slip,
                status: "APPROVED",
                reviewerId: "reviewer-1",
                reviewerEmail: "reviewer@example.com",
                reviewedAt: new Date().toISOString(),
              }
            : slip
        )
      );
    });
    await page.route(`**/api/v1/payment-slips/${slip.id}/approve`, async (route) => {
      approved = true;
      await fulfillJson(
        route,
        200,
        apiSuccess({
          ...slip,
          status: "APPROVED",
          reviewerId: "reviewer-1",
          reviewerEmail: "reviewer@example.com",
          reviewedAt: new Date().toISOString(),
        })
      );
    });

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    await page.getByRole("button", { name: "Approve" }).click();
    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Approve", exact: true }).click();

    await expect(dialog).toHaveCount(0);
    await expect(page.getByText("reviewer@example.com")).toBeVisible();
  });

  test("renders a 'Back to slip review queue' link that navigates back to the queue", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-back-link", status: "UNDER_REVIEW" });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));
    await mockJson(page, "**/api/v1/payment-slips/review-queue*", 200, apiPageSuccess([]));

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);

    const backLink = page.getByRole("link", { name: "Back to slip review queue" });
    await expect(backLink).toBeVisible();
    await backLink.click();

    await expect(page).toHaveURL("/tenant-admin/payments/slip-review");
  });

  test("a 403 on the slip detail fetch renders PermissionDeniedState, not a crash", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT_SUPPORT");
    const slipId = "slip-detail-403";
    await mockJson(
      page,
      `**/api/v1/payment-slips/${slipId}`,
      403,
      apiError("FORBIDDEN", "You do not have permission to view this payment slip.")
    );

    await page.goto(`/tenant-admin/payments/slip-review/${slipId}`);

    await expect(
      page.getByRole("alert").filter({ hasText: "You don't have permission to view this." })
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reject" })).toHaveCount(0);
  });
});

test.describe("tenant admin — Slip Detail action visibility", () => {
  test("a role without APPROVE (Student Support) sees the slip but no Approve/Reject actions", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT_SUPPORT");
    const slip = makeSlip({ id: "slip-detail-1", status: "UNDER_REVIEW" });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);

    await expect(page.getByText(`Reference: ${slip.referenceNumber}`)).toBeVisible();
    await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reject" })).toHaveCount(0);
  });

  test("a terminal APPROVED slip shows no actions even for a role with APPROVE (Tenant Admin)", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({
      id: "slip-detail-2",
      status: "APPROVED",
      reviewerId: "reviewer-1",
      reviewedAt: new Date().toISOString(),
    });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);

    await expect(page.getByText(`Reference: ${slip.referenceNumber}`)).toBeVisible();
    await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reject" })).toHaveCount(0);
  });

  test("a terminal REJECTED slip shows no actions", async ({ page }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    const slip = makeSlip({
      id: "slip-detail-3",
      status: "REJECTED",
      reviewerId: "reviewer-1",
      reviewedAt: new Date().toISOString(),
    });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);

    await expect(page.getByText(`Reference: ${slip.referenceNumber}`)).toBeVisible();
    await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reject" })).toHaveCount(0);
  });

  test("a role with APPROVE (Tenant Admin) and an UNDER_REVIEW slip sees both actions", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-detail-4", status: "UNDER_REVIEW" });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);

    await expect(page.getByRole("button", { name: "Approve" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Reject" })).toBeVisible();
  });
});

test.describe("tenant admin — Approve dialog, no active flags", () => {
  test("shows a plain confirmation with no reason input field", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-noflags", status: "UNDER_REVIEW", flags: [] });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await expect(dialog.locator("input")).toHaveCount(0);
    await expect(dialog.getByRole("button", { name: "Approve", exact: true })).toBeEnabled();
  });

  test("double-clicking Approve in quick succession results in exactly one network call", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-noflags-doubleclick", status: "UNDER_REVIEW", flags: [] });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    let approveRequestCount = 0;
    let releaseApprove: (() => void) | undefined;
    const approveGate = new Promise<void>((resolve) => {
      releaseApprove = resolve;
    });
    await page.route(`**/api/v1/payment-slips/${slip.id}/approve`, async (route) => {
      approveRequestCount += 1;
      await approveGate;
      await fulfillJson(route, 200, apiSuccess({ ...slip, status: "APPROVED" }));
    });

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Approve", exact: true }).dblclick();

    // Both requests (if the guard is broken) reach the route handler and
    // increment the counter before it awaits the gate, well before this
    // grace period elapses — so a short, explicit wait here (rather than an
    // arbitrary retry loop) is enough to prove no second request follows.
    await expect.poll(() => approveRequestCount).toBeGreaterThan(0);
    await page.waitForTimeout(300);
    expect(approveRequestCount).toBe(1);

    releaseApprove?.();
    await expect(dialog).toHaveCount(0);
  });

  test("a forced 403 from the approve endpoint (e.g. a stale session) surfaces inline, dialog stays open", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-noflags-403", status: "UNDER_REVIEW", flags: [] });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));
    await mockJson(
      page,
      `**/api/v1/payment-slips/${slip.id}/approve`,
      403,
      apiError("FORBIDDEN", "You do not have permission to approve payment slips.")
    );

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Approve", exact: true }).click();

    await expect(
      dialog.getByText("You do not have permission to approve payment slips.")
    ).toBeVisible();
    // Not silently closed/treated as success.
    await expect(dialog).toBeVisible();
  });

  test("the approve dialog traps focus while open and returns focus to the trigger button on Cancel", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-noflags-focus", status: "UNDER_REVIEW", flags: [] });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);

    const trigger = page.getByRole("button", { name: "Approve" });
    await trigger.focus();
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();

    // Focus starts inside the modal — verify it never lands on the now-inert
    // page content behind it. Tab repeatedly through every focusable control
    // in the dialog and confirm focus never leaves it.
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
});

test.describe("tenant admin — Approve dialog, active flags require an override reason", () => {
  test("the 'Approve anyway' submit button starts disabled and enables only once a non-blank reason is typed", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({
      id: "slip-flagged",
      status: "UNDER_REVIEW",
      flags: [
        { id: "flag-1", flagType: "DUPLICATE_REFERENCE", detectedAt: new Date().toISOString() },
      ],
    });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    const submit = dialog.getByRole("button", { name: "Approve anyway" });
    await expect(submit).toBeDisabled();

    await dialog.getByLabel("Reason for overriding the flag(s)").fill("Verified with the student directly.");
    await expect(submit).toBeEnabled();
  });

  test("a forced 409 (unresolved flags) surfaces inline in the dialog, which stays open", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({
      id: "slip-409",
      status: "UNDER_REVIEW",
      flags: [
        { id: "flag-1", flagType: "DUPLICATE_IMAGE_HASH", detectedAt: new Date().toISOString() },
      ],
    });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));
    await mockJson(
      page,
      `**/api/v1/payment-slips/${slip.id}/approve`,
      409,
      apiError("CONFLICT", "An override reason is required to approve a flagged slip.")
    );

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Reason for overriding the flag(s)").fill("Confirmed with student.");
    await dialog.getByRole("button", { name: "Approve anyway" }).click();

    await expect(
      dialog.getByText("An override reason is required to approve a flagged slip.")
    ).toBeVisible();
    await expect(dialog).toBeVisible();
  });

  test("Escape does not dismiss the approve dialog while a reason is partially typed", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({
      id: "slip-escape",
      status: "UNDER_REVIEW",
      flags: [
        { id: "flag-1", flagType: "DUPLICATE_REFERENCE", detectedAt: new Date().toISOString() },
      ],
    });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    const trigger = page.getByRole("button", { name: "Approve" });
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await dialog.getByLabel("Reason for overriding the flag(s)").fill("Partial reason typed so far");

    await page.keyboard.press("Escape");

    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel("Reason for overriding the flag(s)")).toHaveValue(
      "Partial reason typed so far"
    );

    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toHaveCount(0);
  });

  test("typing a too-long override reason and submitting renders the Zod max-length error inline, and never calls approve", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({
      id: "slip-flagged-toolong",
      status: "UNDER_REVIEW",
      flags: [
        { id: "flag-1", flagType: "DUPLICATE_REFERENCE", detectedAt: new Date().toISOString() },
      ],
    });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    let approveCalled = false;
    await page.route(`**/api/v1/payment-slips/${slip.id}/approve`, async (route) => {
      approveCalled = true;
      await fulfillJson(route, 200, apiSuccess({ ...slip, status: "APPROVED" }));
    });

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    // Non-blank, so the native `disabled` gate (UX convenience only) doesn't
    // block the click — it's the Zod `.max(1000, ...)` rule that must reject
    // this at submit time and render its own message.
    await dialog.getByLabel("Reason for overriding the flag(s)").fill("x".repeat(1001));
    const submit = dialog.getByRole("button", { name: "Approve anyway" });
    await expect(submit).toBeEnabled();
    await submit.click();

    await expect(dialog.getByText("Reason must be 1000 characters or fewer.")).toBeVisible();
    await expect(dialog).toBeVisible();
    expect(approveCalled).toBe(false);
  });

  test("double-clicking 'Approve anyway' in quick succession results in exactly one network call", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({
      id: "slip-flagged-doubleclick",
      status: "UNDER_REVIEW",
      flags: [
        { id: "flag-1", flagType: "DUPLICATE_REFERENCE", detectedAt: new Date().toISOString() },
      ],
    });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    let approveRequestCount = 0;
    let releaseApprove: (() => void) | undefined;
    const approveGate = new Promise<void>((resolve) => {
      releaseApprove = resolve;
    });
    await page.route(`**/api/v1/payment-slips/${slip.id}/approve`, async (route) => {
      approveRequestCount += 1;
      await approveGate;
      await fulfillJson(route, 200, apiSuccess({ ...slip, status: "APPROVED" }));
    });

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog
      .getByLabel("Reason for overriding the flag(s)")
      .fill("Verified with the student directly.");
    await dialog.getByRole("button", { name: "Approve anyway" }).dblclick();

    await expect.poll(() => approveRequestCount).toBeGreaterThan(0);
    await page.waitForTimeout(300);
    expect(approveRequestCount).toBe(1);

    releaseApprove?.();
    await expect(dialog).toHaveCount(0);
  });
});

test.describe("tenant admin — Reject dialog", () => {
  test("requires a non-blank reason before the client will submit", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-reject-validation", status: "UNDER_REVIEW" });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    let rejectCalled = false;
    await page.route(`**/api/v1/payment-slips/${slip.id}/reject`, async (route) => {
      rejectCalled = true;
      await fulfillJson(
        route,
        200,
        apiSuccess({ ...slip, status: "REJECTED", reviewerId: "reviewer-1", reviewedAt: new Date().toISOString() })
      );
    });

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    await page.getByRole("button", { name: "Reject" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Reject" }).click();

    await expect(dialog.getByText("A reason is required.")).toBeVisible();
    await expect(dialog).toBeVisible();
    expect(rejectCalled).toBe(false);
  });

  test("a successful reject closes the dialog and triggers a refetch of the slip detail query", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-reject-success", status: "UNDER_REVIEW" });

    let detailRequestCount = 0;
    await page.route(`**/api/v1/payment-slips/${slip.id}`, async (route) => {
      detailRequestCount += 1;
      await fulfillJson(route, 200, apiSuccess(slip));
    });
    await mockJson(
      page,
      `**/api/v1/payment-slips/${slip.id}/reject`,
      200,
      apiSuccess({ ...slip, status: "REJECTED", reviewerId: "reviewer-1", reviewedAt: new Date().toISOString() })
    );

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    await expect(page.getByText(`Reference: ${slip.referenceNumber}`)).toBeVisible();
    const countBeforeReject = detailRequestCount;

    await page.getByRole("button", { name: "Reject" }).click();
    const dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Reason").fill("Illegible slip image.");
    await dialog.getByRole("button", { name: "Reject" }).click();

    await expect(dialog).toHaveCount(0);
    await expect
      .poll(() => detailRequestCount, { timeout: 5000 })
      .toBeGreaterThan(countBeforeReject);
  });

  test("double-clicking Reject in quick succession results in exactly one network call", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-reject-doubleclick", status: "UNDER_REVIEW" });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    let rejectRequestCount = 0;
    let releaseReject: (() => void) | undefined;
    const rejectGate = new Promise<void>((resolve) => {
      releaseReject = resolve;
    });
    await page.route(`**/api/v1/payment-slips/${slip.id}/reject`, async (route) => {
      rejectRequestCount += 1;
      await rejectGate;
      await fulfillJson(
        route,
        200,
        apiSuccess({
          ...slip,
          status: "REJECTED",
          reviewerId: "reviewer-1",
          reviewedAt: new Date().toISOString(),
        })
      );
    });

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    await page.getByRole("button", { name: "Reject" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Reason").fill("Illegible slip image.");
    await dialog.getByRole("button", { name: "Reject" }).dblclick();

    await expect.poll(() => rejectRequestCount).toBeGreaterThan(0);
    await page.waitForTimeout(300);
    expect(rejectRequestCount).toBe(1);

    releaseReject?.();
    await expect(dialog).toHaveCount(0);
  });

  test("a forced 403 from the reject endpoint (e.g. a stale session) surfaces inline, dialog stays open", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-reject-403", status: "UNDER_REVIEW" });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));
    await mockJson(
      page,
      `**/api/v1/payment-slips/${slip.id}/reject`,
      403,
      apiError("FORBIDDEN", "You do not have permission to reject payment slips.")
    );

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);
    await page.getByRole("button", { name: "Reject" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Reason").fill("Illegible slip image.");
    await dialog.getByRole("button", { name: "Reject" }).click();

    await expect(
      dialog.getByText("You do not have permission to reject payment slips.")
    ).toBeVisible();
    await expect(dialog).toBeVisible();
  });

  test("the reject dialog traps focus while open and returns focus to the trigger button on Cancel", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-reject-focus", status: "UNDER_REVIEW" });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);

    const trigger = page.getByRole("button", { name: "Reject" });
    await trigger.focus();
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();

    const focusableCount = await dialog
      .locator("button, input, a[href], [tabindex]:not([tabindex='-1'])")
      .count();
    for (let i = 0; i < focusableCount + 2; i += 1) {
      await page.keyboard.press("Tab");
      await expect(dialog.locator(":focus")).toHaveCount(1);
    }

    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });
});

test.describe("tenant admin — View slip file", () => {
  test("calls the download-url endpoint fresh on each click and opens the returned URL", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const slip = makeSlip({ id: "slip-view-file", status: "UNDER_REVIEW" });
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    let downloadCallCount = 0;
    await page.route(`**/api/v1/payment-slips/${slip.id}/download-url`, async (route) => {
      downloadCallCount += 1;
      await fulfillJson(
        route,
        200,
        apiSuccess({
          url: `https://storage.example.test/signed/${slip.id}?token=attempt-${downloadCallCount}`,
          expiresAt: new Date(Date.now() + 60_000).toISOString(),
        })
      );
    });

    await page.addInitScript(() => {
      (window as unknown as { __openedUrls: unknown[] }).__openedUrls = [];
      window.open = (...args: Parameters<typeof window.open>) => {
        (window as unknown as { __openedUrls: unknown[] }).__openedUrls.push(args);
        return null;
      };
    });

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);

    expect(downloadCallCount).toBe(0);

    await page.getByRole("button", { name: "View slip file" }).click();
    await expect.poll(() => downloadCallCount).toBe(1);

    await page.getByRole("button", { name: "View slip file" }).click();
    await expect.poll(() => downloadCallCount).toBe(2);

    const openedUrls = await page.evaluate(
      () => (window as unknown as { __openedUrls: unknown[] }).__openedUrls
    );
    expect(openedUrls).toEqual([
      [`https://storage.example.test/signed/${slip.id}?token=attempt-1`, "_blank", "noopener,noreferrer"],
      [`https://storage.example.test/signed/${slip.id}?token=attempt-2`, "_blank", "noopener,noreferrer"],
    ]);
  });
});
