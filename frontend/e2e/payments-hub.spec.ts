import { test, expect, type Page, type Route } from "@playwright/test";
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
 * PAY-HUB-01 Payments hub (docs/plans/PAY-HUB-01 Payments Hub (Klass parity).md):
 * one /tenant-admin/payments page with All Orders, Payment Slips, Free Cards
 * and Refunds tabs. Every `/api/v1/**` call is mocked (no real backend), like
 * the sibling payment specs. Role gating in the UI is UX only - the
 * server-verified 403 cases prove the page defers to the backend.
 */

async function session(page: Page, role: string): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
}

function isOrdersList(url: URL): boolean {
  return url.pathname.endsWith("/v1/ledger/orders");
}

function isReviewQueueList(url: URL): boolean {
  return url.pathname.endsWith("/payment-slips/review-queue");
}

async function mockPendingCount(page: Page, pending: number): Promise<void> {
  await mockJson(page, "**/api/v1/payment-slips/review-queue/count", 200, apiSuccess({ pending }));
}

function orderRow(overrides: Record<string, unknown> = {}) {
  return {
    orderId: "order-1",
    orderNumber: 123,
    displayOrderNumber: "ORD-000123",
    createdAt: "2026-10-20T04:30:00Z",
    studentId: "profile-1",
    studentName: "Kamala Perera",
    itemCount: 2,
    firstItemTitle: "Grade 11 Maths",
    amount: 3500,
    currency: "LKR",
    operationalState: "UNDER_REVIEW",
    method: "MANUAL_SLIP",
    reference: null,
    ...overrides,
  };
}

const ORDER_DETAIL = {
  orderId: "order-1",
  orderNumber: 123,
  displayOrderNumber: "ORD-000123",
  createdAt: "2026-10-20T04:30:00Z",
  orderStatus: "PLACED",
  operationalState: "PAID",
  student: { studentId: "profile-1", name: "Kamala Perera", email: "kamala@example.test", mobile: "0771234567" },
  currency: "LKR",
  lines: [
    {
      lineNo: 1,
      courseId: "course-a",
      courseTitle: "Grade 11 Maths",
      label: "2026 October",
      scope: "MONTH",
      unitAmount: 3000,
      facilityFee: 100,
      discountAmount: 0,
      lineTotal: 3100,
    },
    {
      lineNo: 2,
      courseId: "course-b",
      courseTitle: "Grade 11 Science",
      label: "Tute pack",
      scope: "COURSE",
      unitAmount: 0,
      facilityFee: 0,
      discountAmount: 0,
      lineTotal: 0,
    },
  ],
  subtotal: 3000,
  facilityFee: 100,
  postage: 400,
  discount: 0,
  total: 3500,
  refunded: 0,
  remaining: 3500,
  delivery: { recipientName: "Kamala Perera", addressLine: "12 Temple Road", city: "Kandy", phone: "0771234567" },
  payments: [
    {
      paymentId: "payment-1",
      method: "MANUAL_SLIP",
      status: "CONFIRMED",
      amount: 3500,
      reference: "BOC-77812",
      createdAt: "2026-10-21T04:30:00Z",
    },
  ],
  slips: [{ slipId: "slip-1", referenceNumber: "BOC-77812", status: "APPROVED", submittedAt: "2026-10-20T05:00:00Z" }],
};

function slip(overrides: Record<string, unknown> = {}) {
  return {
    id: "slip-1",
    orderId: "order-1",
    studentId: "user-1",
    referenceNumber: "BOC-77812",
    status: "UNDER_REVIEW",
    submittedAt: "2026-10-20T05:00:00Z",
    reviewerId: null,
    reviewedAt: null,
    flags: [],
    studentEmail: "kamala@example.test",
    reviewerEmail: null,
    orderAmount: 3000,
    orderCurrency: "LKR",
    orderLines: [],
    orderSubtotal: 3000,
    orderFacilityFee: 0,
    orderDiscount: 500,
    orderPostage: null,
    orderDelivery: null,
    studentProfileId: "profile-1",
    studentName: "Kamala Perera",
    studentMobile: "765656556",
    orderNumber: 123,
    displayOrderNumber: "ORD-000123",
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// Hub shell, tabs and old-route redirects
// ---------------------------------------------------------------------------

test.describe("payments hub — tabs, header and old routes", () => {
  test("a Tenant Admin sees the four tabs, All Orders first, and the pending-slips header", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 3);
    await page.route(isOrdersList, (route) => fulfillJson(route, 200, apiPageSuccess([orderRow()])));

    await page.goto("/tenant-admin/payments");

    await expect(page.getByRole("heading", { name: "Payments", level: 1 })).toBeVisible();
    const tabs = page.getByRole("tab");
    await expect(tabs).toHaveText([/All Orders/, /Payment Slips/, /Free Cards/, /Refunds/]);
    await expect(page.getByRole("tab", { name: /All Orders/, selected: true })).toBeVisible();
    await expect(page.getByTestId("payments-pending-card")).toContainText("3 pending");
    await expect(page.getByTestId("payments-pending-card")).toContainText("Payment slips awaiting review");
    // The sidebar has exactly one Payments entry.
    await expect(page.getByRole("link", { name: "Payments", exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "Payment Slips", exact: true })).toHaveCount(0);
  });

  test("the old Payment dashboard and Refunds URLs redirect to the hub tabs", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 0);
    await page.route(isOrdersList, (route) => fulfillJson(route, 200, apiPageSuccess([orderRow()])));
    await mockJson(page, "**/api/v1/ledger/dashboard*", 200, apiPageSuccess([]));

    await page.goto("/tenant-admin/payments/dashboard");
    await expect(page).toHaveURL(/\/tenant-admin\/payments$/);
    await expect(page.getByRole("tab", { name: /All Orders/, selected: true })).toBeVisible();

    await page.goto("/tenant-admin/payments/refunds");
    await expect(page).toHaveURL(/\/tenant-admin\/payments\?tab=refunds$/);
    await expect(page.getByRole("tab", { name: "Refunds", selected: true })).toBeVisible();
  });

  test("a Read-only Auditor sees every tab read-only (no refund or slip actions)", async ({ page }) => {
    await session(page, "READ_ONLY_AUDITOR");
    await mockPendingCount(page, 1);
    await page.route(isReviewQueueList, (route) => fulfillJson(route, 200, apiPageSuccess([slip()])));

    await page.goto("/tenant-admin/payments?tab=slips");

    await expect(page.getByRole("table").getByText("Kamala Perera")).toBeVisible();
    await expect(page.getByTestId("slip-approve")).toHaveCount(0);
    await expect(page.getByTestId("slip-decline")).toHaveCount(0);
    await expect(page.getByTestId("slip-add-note")).toHaveCount(0);
    // The slip itself can still be viewed.
    await expect(page.getByRole("button", { name: "View payment slip BOC-77812" })).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// All Orders
// ---------------------------------------------------------------------------

test.describe("payments hub — All Orders", () => {
  test("a role without PAYMENTS_SLIPS/VIEW gets the server's 403 as PermissionDeniedState", async ({ page }) => {
    await session(page, "STUDENT");
    await page.route(isOrdersList, (route) =>
      fulfillJson(route, 403, apiError("FORBIDDEN", "You do not have permission to view orders."))
    );

    await page.goto("/tenant-admin/payments");

    await expect(page.getByRole("alert").filter({ hasText: "You don't have permission to view this." })).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
  });

  test("renders one row per order with student, items summary, status and method", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 0);
    await page.route(isOrdersList, (route) => fulfillJson(route, 200, apiPageSuccess([orderRow()])));

    await page.goto("/tenant-admin/payments");

    const table = page.getByRole("table");
    await expect(table.getByText("ORD-000123")).toBeVisible();
    await expect(table.getByRole("link", { name: "Kamala Perera" })).toHaveAttribute(
      "href",
      "/tenant-admin/students/profile-1"
    );
    await expect(table.getByText("Grade 11 Maths +1 more")).toBeVisible();
    await expect(table.getByText("Under review")).toBeVisible();
    await expect(table.getByText("Manual bank transfer")).toBeVisible();
  });

  test("order-number search sends orderNumber; an invalid number is caught inline without a request", async ({
    page,
  }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 0);
    const requests: URLSearchParams[] = [];
    await page.route(isOrdersList, async (route: Route) => {
      requests.push(new URL(route.request().url()).searchParams);
      await fulfillJson(route, 200, apiPageSuccess([orderRow()]));
    });

    await page.goto("/tenant-admin/payments");
    await expect(page.getByRole("table")).toBeVisible();

    await page.getByLabel("Order number").fill("ORD-12a");
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect(page.getByText("Enter an order number like ORD-000123 or 123.")).toBeVisible();
    const before = requests.length;

    await page.getByLabel("Order number").fill("ORD-000123");
    await page.getByRole("button", { name: "Search", exact: true }).click();
    await expect.poll(() => requests.length).toBeGreaterThan(before);
    expect(requests[requests.length - 1].get("orderNumber")).toBe("ORD-000123");
    expect(requests[requests.length - 1].get("page")).toBe("0");
  });

  test("the order-date range sends from/to, and an inverted range is refused before any request", async ({
    page,
  }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 0);
    const requests: URLSearchParams[] = [];
    await page.route(isOrdersList, async (route: Route) => {
      requests.push(new URL(route.request().url()).searchParams);
      await fulfillJson(route, 200, apiPageSuccess([orderRow()]));
    });

    await page.goto("/tenant-admin/payments");
    await expect(page.getByRole("table")).toBeVisible();

    await page.getByLabel("Order date from").fill("2026-10-01");
    await page.getByLabel("Order date to").fill("2026-10-31");
    await expect
      .poll(() => requests.some((params) => params.get("from") === "2026-10-01" && params.get("to") === "2026-10-31"))
      .toBe(true);

    const before = requests.length;
    await page.getByLabel("Order date from").fill("2026-11-15");
    await expect(page.getByText("“From” must be on or before “To”.")).toBeVisible();
    expect(requests.filter((params) => params.get("from") === "2026-11-15")).toHaveLength(0);
    expect(requests.length).toBe(before);
  });

  test("a filter with no matches shows the filtered-empty copy, distinct from 'No orders yet'", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 0);
    await page.route(isOrdersList, async (route: Route) => {
      const params = new URL(route.request().url()).searchParams;
      await fulfillJson(route, 200, apiPageSuccess(params.get("status") === "REFUNDED" ? [] : [orderRow()]));
    });

    await page.goto("/tenant-admin/payments");
    await expect(page.getByRole("table")).toBeVisible();

    await page.getByLabel("Status").click();
    await page.getByRole("option", { name: "Refunded" }).click();

    await expect(page.getByText("No orders match your search")).toBeVisible();
    await expect(page.getByText("No orders yet")).toHaveCount(0);
    await page.getByRole("button", { name: "Reset filters" }).click();
    await expect(page.getByRole("table")).toBeVisible();
  });

  test("View order opens the full order: every product, fees, postage, delivery and payments", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 0);
    await page.route(isOrdersList, (route) => fulfillJson(route, 200, apiPageSuccess([orderRow()])));
    await mockJson(page, "**/api/v1/orders/order-1/admin-detail", 200, apiSuccess(ORDER_DETAIL));

    await page.goto("/tenant-admin/payments");
    await page.getByRole("table").getByRole("button", { name: "View order ORD-000123" }).click();

    const drawer = page.getByTestId("order-details-drawer");
    await expect(drawer.getByText("ORD-000123")).toBeVisible();
    await expect(drawer.getByTestId("order-lines").getByRole("listitem")).toHaveCount(2);
    await expect(drawer.getByText("Grade 11 Science")).toBeVisible();
    await expect(drawer.getByTestId("order-postage")).toContainText("400");
    await expect(drawer.getByTestId("order-totals")).toContainText("Facility fee");
    await expect(drawer.getByTestId("order-delivery")).toContainText("12 Temple Road");
    await expect(drawer.getByText("Ref BOC-77812", { exact: false })).toBeVisible();
  });

  test("another tenant's (or unknown) order id is shown as not found, never as data", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 0);
    await page.route(isOrdersList, (route) => fulfillJson(route, 200, apiPageSuccess([orderRow()])));
    await mockJson(page, "**/api/v1/orders/order-1/admin-detail", 404, apiError("NOT_FOUND", "Order not found"));

    await page.goto("/tenant-admin/payments");
    await page.getByRole("table").getByRole("button", { name: "View order ORD-000123" }).click();

    await expect(page.getByTestId("order-details-drawer").getByRole("alert")).toContainText(
      "This order was not found in your institute."
    );
  });
});

// ---------------------------------------------------------------------------
// Payment Slips (Klass "Pending" grid)
// ---------------------------------------------------------------------------

test.describe("payments hub — Payment Slips", () => {
  test("the pending grid shows student name, contact, price, discount and amount to pay", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 1);
    await page.route(isReviewQueueList, (route) => fulfillJson(route, 200, apiPageSuccess([slip()])));

    await page.goto("/tenant-admin/payments?tab=slips");

    const row = page.getByRole("table").getByRole("row").nth(1);
    await expect(row.getByRole("link", { name: "Kamala Perera" })).toHaveAttribute("href", "/tenant-admin/students/profile-1");
    await expect(row).toContainText("765656556");
    await expect(row).toContainText("ORD-000123");
    await expect(row).toContainText("3500.00 LKR");
    await expect(row).toContainText("500.00 LKR");
    await expect(row).toContainText("3000.00 LKR");
    await expect(page.getByRole("tab", { name: /Payment Slips/ })).toContainText("1");
  });

  test("search is sent to the server (debounced) and resets to page 0", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 1);
    const searches: Array<string | null> = [];
    await page.route(isReviewQueueList, async (route: Route) => {
      const params = new URL(route.request().url()).searchParams;
      searches.push(params.get("search"));
      await fulfillJson(route, 200, apiPageSuccess([slip()]));
    });

    await page.goto("/tenant-admin/payments?tab=slips");
    await expect(page.getByRole("table")).toBeVisible();
    await page.getByLabel("Search").fill("0765");

    await expect.poll(() => searches[searches.length - 1]).toBe("0765");
    // Typing a whole term fires one search, not one per keystroke.
    expect(searches.filter((value) => value === "076")).toHaveLength(0);
  });

  test("the slip viewer shows the slip inline with a read-only reference and Approve/Reject/Close/Download", async ({
    page,
  }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 1);
    await page.route(isReviewQueueList, (route) => fulfillJson(route, 200, apiPageSuccess([slip()])));
    let urlFetches = 0;
    await page.route("**/api/v1/payment-slips/slip-1/download-url", async (route) => {
      urlFetches += 1;
      await fulfillJson(
        route,
        200,
        apiSuccess({ url: "http://localhost:3000/__slip-test.png", expiresAt: new Date().toISOString(), contentType: "image/png" })
      );
    });
    await page.route("**/__slip-test.png", (route) =>
      route.fulfill({
        status: 200,
        contentType: "image/png",
        body: Buffer.from(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
          "base64"
        ),
      })
    );

    await page.goto("/tenant-admin/payments?tab=slips");
    await page.getByRole("button", { name: "View payment slip BOC-77812" }).click();

    const viewer = page.getByTestId("slip-viewer");
    await expect(viewer.getByTestId("slip-viewer-image")).toHaveAttribute("src", /__slip-test\.png$/);
    await expect(viewer.getByTestId("slip-viewer-reference")).toHaveValue("BOC-77812");
    await expect(viewer.getByTestId("slip-viewer-reference")).toHaveAttribute("readonly", "");
    await expect(viewer.getByRole("button", { name: "Approve" })).toBeVisible();
    await expect(viewer.getByRole("button", { name: "Reject" })).toBeVisible();
    await expect(viewer.getByRole("button", { name: "Download" })).toBeVisible();
    // A fresh signed URL per open (React StrictMode may run the effect twice in dev).
    expect(urlFetches).toBeGreaterThanOrEqual(1);

    await viewer.getByRole("button", { name: "Close", exact: true }).first().click();
    await expect(viewer).toHaveCount(0);
  });

  test("approving from the grid posts the approval and the row's actions are the backend's decision", async ({
    page,
  }) => {
    await session(page, "FINANCE_STAFF");
    await mockPendingCount(page, 1);
    let approved = false;
    await page.route(isReviewQueueList, (route) =>
      fulfillJson(route, 200, apiPageSuccess(approved ? [] : [slip()]))
    );
    await page.route("**/api/v1/payment-slips/slip-1/approve", async (route) => {
      approved = true;
      await fulfillJson(route, 200, apiSuccess(slip({ status: "APPROVED" })));
    });

    await page.goto("/tenant-admin/payments?tab=slips");
    await page.getByRole("table").getByTestId("slip-approve").click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Approve", exact: true }).click();

    await expect.poll(() => approved).toBe(true);
    await expect(page.getByText("No slips waiting for approval")).toBeVisible();
  });

  test("declining requires a reason, and a SUBMITTED slip shows no actions until the duplicate check ends", async ({
    page,
  }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 2);
    await page.route(isReviewQueueList, (route) =>
      fulfillJson(
        route,
        200,
        apiPageSuccess([slip(), slip({ id: "slip-2", referenceNumber: "REF-NEW", status: "SUBMITTED", studentName: "Nimal" })])
      )
    );
    let rejectBody: unknown = null;
    await page.route("**/api/v1/payment-slips/slip-1/reject", async (route) => {
      rejectBody = route.request().postDataJSON();
      await fulfillJson(route, 200, apiSuccess(slip({ status: "REJECTED" })));
    });

    await page.goto("/tenant-admin/payments?tab=slips");
    const submittedRow = page.getByRole("row").filter({ hasText: "Nimal" });
    await expect(submittedRow.getByText("Checking…")).toBeVisible();
    await expect(submittedRow.getByTestId("slip-approve")).toHaveCount(0);

    await page.getByRole("button", { name: "Decline payment BOC-77812" }).click();
    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: /Reject/ }).last().click();
    expect(rejectBody).toBeNull();
    await dialog.getByLabel(/reason/i).fill("Amount does not match");
    await dialog.getByRole("button", { name: /Reject/ }).last().click();
    await expect.poll(() => rejectBody).toEqual({ reason: "Amount does not match" });
  });

  test("Add note saves an internal note and the row expander lists it", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 1);
    await page.route(isReviewQueueList, (route) => fulfillJson(route, 200, apiPageSuccess([slip()])));
    const notes: Array<Record<string, unknown>> = [];
    await page.route("**/api/v1/payment-slips/slip-1/notes", async (route) => {
      if (route.request().method() === "POST") {
        const body = route.request().postDataJSON() as { body: string };
        const note = {
          id: `note-${notes.length + 1}`,
          slipId: "slip-1",
          body: body.body,
          authorUserId: "admin-1",
          authorEmail: "admin@example.test",
          createdAt: new Date().toISOString(),
        };
        notes.push(note);
        await fulfillJson(route, 201, apiSuccess(note));
      } else {
        await fulfillJson(route, 200, apiSuccess(notes));
      }
    });

    await page.goto("/tenant-admin/payments?tab=slips");
    await page.getByRole("table").getByTestId("slip-add-note").click();
    await page.getByTestId("slip-note-save").click();
    await expect(page.getByText("Write a note first.")).toBeVisible();
    expect(notes).toHaveLength(0);

    await page.getByTestId("slip-note-body").fill("Called the student, amount confirmed");
    await page.getByTestId("slip-note-save").click();
    await expect.poll(() => notes.length).toBe(1);

    await page.getByRole("button", { name: "Expand row details" }).first().click();
    await expect(page.getByRole("table").getByTestId("slip-notes")).toContainText("Called the student, amount confirmed");
    await expect(page.getByRole("table").getByTestId("slip-notes")).toContainText("admin@example.test");
  });
});

// ---------------------------------------------------------------------------
// Refunds
// ---------------------------------------------------------------------------

test.describe("payments hub — Refunds", () => {
  test("View order details opens the shared order drawer, including postage", async ({ page }) => {
    await session(page, "TENANT_ADMIN");
    await mockPendingCount(page, 0);
    await mockJson(
      page,
      "**/api/v1/ledger/dashboard*",
      200,
      apiPageSuccess([
        {
          id: "ledger-1",
          orderId: "order-1",
          paymentId: "payment-1",
          entryType: "PAYMENT_CONFIRMED",
          amount: 3500,
          reversesEntryId: null,
          createdAt: "2026-10-21T04:30:00Z",
          courseId: null,
          courseTitle: "2 courses",
          billingPeriodId: null,
          operationalState: "PAID",
          method: "MANUAL_SLIP",
          reference: "BOC-77812",
        },
      ])
    );
    await mockJson(page, "**/api/v1/orders/order-1/admin-detail", 200, apiSuccess(ORDER_DETAIL));

    await page.goto("/tenant-admin/payments?tab=refunds");
    await page.getByRole("table").getByRole("button", { name: "View order details" }).click();

    const drawer = page.getByTestId("order-details-drawer");
    await expect(drawer.getByTestId("order-postage")).toContainText("400");
    await expect(drawer.getByTestId("order-delivery")).toContainText("Kandy");
  });
});
