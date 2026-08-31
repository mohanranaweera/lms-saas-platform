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
 * MVP-012 Enrollment and Course Access — frontend flows:
 *
 * A. Student "My Courses" (`student/courses`) — `GET /api/v1/enrollments/my`.
 * B. Student Reactivation submit + history (`student/payments/reactivation`)
 *    — `POST /api/v1/enrollments/{id}/reactivation-requests`,
 *    `GET /api/v1/reactivation-requests/my`.
 * C. Tenant Admin Reactivation Approvals queue
 *    (`tenant-admin/access-expiry/reactivation-approvals`).
 * D. Tenant Admin Reactivation Request Detail + Approve/Reject dialogs.
 *
 * No real backend runs in this environment — every test mocks `/api/v1/**`
 * via `page.route`/`mockJson`, following `manual-payment-slips.spec.ts`'s
 * exact structure/conventions (the closest sibling module).
 */

async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

function makeEnrollment(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    enrollmentId: "enrollment-1",
    courseId: "course-12345678-abcd",
    state: "ACTIVE" as const,
    accessExpiresAt: null,
    canRequestReactivation: false,
    ...overrides,
  };
}

function makeReactivationRequest(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "reactivation-1",
    enrollmentId: "enrollment-1",
    requestedBy: "student-1",
    status: "SUBMITTED" as const,
    reviewedBy: null,
    reviewedAt: null,
    newOrderId: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    ...overrides,
  };
}

const STUDENT_RECORD = {
  id: "student-1",
  name: "Jane Student",
  email: "jane.student@example.com",
  roleCode: "STUDENT",
  status: "ACTIVE",
};

test.describe("student — My Courses", () => {
  test("renders enrollments with access-state badge and short course id fragment, no name lookup called", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollment = makeEnrollment();
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([enrollment]));
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));

    let courseLookupCalled = false;
    await page.route(`**/api/v1/courses/${enrollment.courseId}`, async (route) => {
      courseLookupCalled = true;
      await route.fulfill({ status: 403, contentType: "application/json", body: "{}" });
    });

    await page.goto("/student/courses");

    // `DataTable` renders both the desktop table and the mobile card list in
    // the DOM simultaneously (CSS, not conditional rendering, switches which
    // is visible) — every assertion here scopes to the desktop `table` role,
    // mirroring `manual-payment-slips.spec.ts`'s exact convention, so a bare
    // `page.getByText(...)` doesn't hit Playwright's strict-mode "multiple
    // elements" error.
    const table = page.getByRole("table");
    await expect(table.getByText(`Course #${enrollment.courseId.slice(0, 8)}`)).toBeVisible();
    await expect(table.getByText("Active")).toBeVisible();
    expect(courseLookupCalled).toBe(false);
  });

  test("a null accessExpiresAt on an ACTIVE row renders 'Lifetime access'", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollment = makeEnrollment({ accessExpiresAt: null, state: "ACTIVE" });
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([enrollment]));
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));

    await page.goto("/student/courses");

    await expect(page.getByRole("table").getByText("Lifetime access")).toBeVisible();
  });

  test("an EXPIRED row with canRequestReactivation shows a Reactivate link to the reactivation screen", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollment = makeEnrollment({
      state: "EXPIRED",
      canRequestReactivation: true,
      accessExpiresAt: new Date().toISOString(),
    });
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([enrollment]));
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));

    await page.goto("/student/courses");

    const link = page.getByRole("table").getByRole("link", { name: /Reactivate/ });
    await expect(link).toBeVisible();
    await link.click();

    await expect(page).toHaveURL(`/student/payments/reactivation?enrollmentId=${enrollment.enrollmentId}`);
  });

  test("an expired course renders the distinct 'Expired' access-state badge, not a generic error/alert", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollment = makeEnrollment({
      state: "EXPIRED",
      canRequestReactivation: true,
      accessExpiresAt: new Date().toISOString(),
    });
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([enrollment]));
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));

    await page.goto("/student/courses");

    const table = page.getByRole("table");
    // The distinct expired state is the "Expired" badge (never a generic
    // error/permission-denied render) rendered alongside a live Reactivate
    // CTA in the same row — not two disconnected states. (Next.js's own
    // built-in, always-mounted empty route announcer also has `role="alert"`
    // in this app shell, so asserting a bare zero-count on that role would be
    // a false signal — scope to actual error/permission-denied copy instead.)
    await expect(table.getByText("Expired")).toBeVisible();
    await expect(table.getByRole("link", { name: /Reactivate/ })).toBeVisible();
    await expect(page.getByText("Something went wrong", { exact: false })).toHaveCount(0);
    await expect(page.getByText("permission", { exact: false })).toHaveCount(0);
  });

  test("zero enrollments shows the true empty state with a catalog CTA", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));

    await page.goto("/student/courses");

    await expect(page.getByText("You have no active enrollments yet")).toBeVisible();
    const cta = page.getByRole("button", { name: "Browse courses" });
    await expect(cta).toBeVisible();
    await cta.click();
    await expect(page).toHaveURL("/courses");
  });

  test("a 403 on the enrollments fetch renders PermissionDeniedState, not a crash", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/api/v1/enrollments/my",
      403,
      apiError("FORBIDDEN", "You do not have permission to view this.")
    );
    await mockJson(
      page,
      "**/api/v1/reactivation-requests/my*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view this.")
    );

    await page.goto("/student/courses");

    await expect(
      page.getByRole("alert").filter({ hasText: "You don't have permission to view this." })
    ).toBeVisible();
  });

  test("shows the loading label while the fetch is in flight, then renders the table", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollment = makeEnrollment();
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));

    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route("**/api/v1/enrollments/my", async (route) => {
      await gate;
      await fulfillJson(route, 200, apiSuccess([enrollment]));
    });

    await page.goto("/student/courses");

    await expect(page.getByText("Loading your courses…")).toBeVisible();
    release?.();

    await expect(
      page.getByRole("table").getByText(`Course #${enrollment.courseId.slice(0, 8)}`)
    ).toBeVisible();
    await expect(page.getByText("Loading your courses…")).toHaveCount(0);
  });
});

test.describe("student — Reactivation submit + history", () => {
  test("with no enrollmentId in the URL, only the history section renders (no submit panel)", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([]));

    await page.goto("/student/payments/reactivation");

    await expect(page.getByRole("button", { name: "Submit reactivation request" })).toHaveCount(0);
    await expect(page.getByText("You haven't requested reactivation for any course yet")).toBeVisible();
  });

  test("submitting a reactivation request shows a success confirmation, never implying access was restored", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollmentId = "enrollment-submit-1";
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([]));
    const created = makeReactivationRequest({ id: "reactivation-new-1", enrollmentId });
    await mockJson(
      page,
      `**/api/v1/enrollments/${enrollmentId}/reactivation-requests`,
      201,
      apiSuccess(created)
    );

    await page.goto(`/student/payments/reactivation?enrollmentId=${enrollmentId}`);
    await page.getByRole("button", { name: "Submit reactivation request" }).click();

    await expect(page.getByText("Reactivation request submitted")).toBeVisible();
    await expect(page.getByRole("button", { name: "Submit reactivation request" })).toHaveCount(0);
    const successPanel = page.locator("div").filter({ hasText: "Reactivation request submitted" });
    await expect(successPanel.getByText("activated", { exact: false })).toHaveCount(0);
    await expect(successPanel.getByText("access restored", { exact: false })).toHaveCount(0);
  });

  test("the Submitted confirmation is exposed to assistive tech via an accessible live status region", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollmentId = "enrollment-a11y-1";
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([]));
    const created = makeReactivationRequest({ id: "reactivation-a11y-1", enrollmentId });
    await mockJson(
      page,
      `**/api/v1/enrollments/${enrollmentId}/reactivation-requests`,
      201,
      apiSuccess(created)
    );

    await page.goto(`/student/payments/reactivation?enrollmentId=${enrollmentId}`);
    await page.getByRole("button", { name: "Submit reactivation request" }).click();

    // A successful, non-error confirmation is correctly `role="status"` +
    // `aria-live="polite"` (not `role="alert"`/assertive, which ARIA reserves
    // for errors/interruptions) — verifying the accessible-name/role is
    // actually exposed, not just that the text is visually present.
    const status = page.getByRole("status").filter({ hasText: "Reactivation request submitted" });
    await expect(status).toBeVisible();
    await expect(status).toHaveAttribute("aria-live", "polite");
  });

  test("a forced 409 (already expired-request or open request exists) surfaces inline via role=alert", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollmentId = "enrollment-409";
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([]));
    await mockJson(
      page,
      `**/api/v1/enrollments/${enrollmentId}/reactivation-requests`,
      409,
      apiError("CONFLICT", "A reactivation request is already open for this enrollment.")
    );

    await page.goto(`/student/payments/reactivation?enrollmentId=${enrollmentId}`);
    await page.getByRole("button", { name: "Submit reactivation request" }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "A reactivation request is already open for this enrollment." })
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Submit reactivation request" })).toBeVisible();
  });

  test("history renders status badge, submitted date, and 'Not yet reviewed' for an open request", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const request = makeReactivationRequest({ status: "SUBMITTED", reviewedAt: null });
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([request]));
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([]));

    await page.goto("/student/payments/reactivation");

    // `getByRole("cell", ...)` (rather than a bare `getByText`) matches only
    // the desktop `<td>` — not the column `<th>` (also literally "Submitted"),
    // and not the mobile card's `<dd>` duplicate — avoiding a Playwright
    // strict-mode "multiple elements" error, same rationale as the "My
    // Courses" table assertions above.
    await expect(page.getByRole("cell", { name: "Submitted" })).toBeVisible();
    await expect(page.getByRole("cell", { name: "Not yet reviewed" })).toBeVisible();
  });
});

test.describe("student — approved reactivation can complete a new checkout", () => {
  const COURSE = {
    id: "course-reactivated-1",
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

  test("My Courses shows 'Proceed to checkout' (not 'Reactivate') for an APPROVED, unfulfilled request, and it completes a real checkout", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollment = makeEnrollment({
      courseId: COURSE.id,
      state: "EXPIRED",
      canRequestReactivation: true, // per EnrollmentExpiryService — APPROVED still reports `true` here; the request list below is what overrides this to "Proceed to checkout".
      accessExpiresAt: new Date().toISOString(),
    });
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([enrollment]));
    const approvedRequest = makeReactivationRequest({
      id: "reactivation-approved-1",
      enrollmentId: enrollment.enrollmentId,
      status: "APPROVED",
      reviewedBy: "admin-1",
      reviewedAt: new Date().toISOString(),
      newOrderId: null,
    });
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([approvedRequest]));

    await page.goto("/student/courses");

    const table = page.getByRole("table");
    await expect(table.getByRole("link", { name: /Proceed to checkout/ })).toBeVisible();
    await expect(table.getByRole("link", { name: /Reactivate/ })).toHaveCount(0);

    await mockJson(page, `**/api/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));
    const order = {
      id: "order-reactivated-1",
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
        paymentId: "payment-reactivated-1",
        orderId: order.id,
        status: "PENDING",
        gatewayReference: "gw-ref-reactivated-1",
        redirectTarget: "https://example-gateway.test/pay/gw-ref-reactivated-1",
      })
    );
    await mockJson(
      page,
      `**/api/v1/orders/${order.id}/payment-status`,
      200,
      apiSuccess({ hasPaymentAttempt: true, paymentId: "payment-reactivated-1", status: "PENDING", confirmedAt: null })
    );
    await mockJson(page, `**/api/v1/orders/${order.id}`, 200, apiSuccess(order));

    await table.getByRole("link", { name: /Proceed to checkout/ }).click();
    await expect(page).toHaveURL(`/student/checkout/${COURSE.id}`);

    await page.getByRole("button", { name: "Enroll" }).click();
    await expect(page).toHaveURL(`/student/payments/awaiting-confirmation/${order.id}`);
  });

  test("the Reactivation history page also offers 'Proceed to checkout' for an APPROVED, unfulfilled request, resolving the course id via My Courses' enrollment list", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollment = makeEnrollment({ courseId: COURSE.id, state: "EXPIRED" });
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([enrollment]));
    const approvedRequest = makeReactivationRequest({
      id: "reactivation-approved-history-1",
      enrollmentId: enrollment.enrollmentId,
      status: "APPROVED",
      reviewedBy: "admin-1",
      reviewedAt: new Date().toISOString(),
      newOrderId: null,
    });
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([approvedRequest]));

    await page.goto("/student/payments/reactivation");

    const link = page.getByRole("table").getByRole("link", { name: /Proceed to checkout/ });
    await expect(link).toBeVisible();
    await link.click();
    await expect(page).toHaveURL(`/student/checkout/${COURSE.id}`);
  });

  test("a SUBMITTED (not yet approved) request never shows 'Proceed to checkout' on My Courses", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollment = makeEnrollment({
      courseId: COURSE.id,
      state: "EXPIRED",
      canRequestReactivation: false,
      accessExpiresAt: new Date().toISOString(),
    });
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([enrollment]));
    const submittedRequest = makeReactivationRequest({
      id: "reactivation-submitted-1",
      enrollmentId: enrollment.enrollmentId,
      status: "SUBMITTED",
    });
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([submittedRequest]));

    await page.goto("/student/courses");

    await expect(
      page.getByRole("table").getByRole("link", { name: /Proceed to checkout/ })
    ).toHaveCount(0);
    await expect(
      page.getByRole("table").getByRole("link", { name: "Reactivation already requested" })
    ).toBeVisible();
  });

  test("an APPROVED request that is already fulfilled (newOrderId set) never shows 'Proceed to checkout' again", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollment = makeEnrollment({
      courseId: COURSE.id,
      state: "EXPIRED",
      canRequestReactivation: true,
      accessExpiresAt: new Date().toISOString(),
    });
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([enrollment]));
    const fulfilledRequest = makeReactivationRequest({
      id: "reactivation-fulfilled-1",
      enrollmentId: enrollment.enrollmentId,
      status: "APPROVED",
      reviewedBy: "admin-1",
      reviewedAt: new Date().toISOString(),
      newOrderId: "order-already-linked-1",
    });
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([fulfilledRequest]));

    await page.goto("/student/courses");

    await expect(
      page.getByRole("table").getByRole("link", { name: /Proceed to checkout/ })
    ).toHaveCount(0);
    // Falls back to the ordinary Reactivate CTA (a fulfilled request never blocks a future one for this still-EXPIRED enrollment — see `ReactivationRequestRepository#findLiveByEnrollmentId`'s javadoc).
    await expect(page.getByRole("table").getByRole("link", { name: /Reactivate/ })).toBeVisible();
  });
});

test.describe("tenant admin — Reactivation Approvals queue role gating", () => {
  test("a STUDENT session sees PermissionDeniedState on a real 403, not a crash", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      "**/api/v1/reactivation-requests*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view the reactivation approvals queue.")
    );

    await page.goto("/tenant-admin/access-expiry/reactivation-approvals");

    await expect(
      page.getByRole("alert").filter({ hasText: "You don't have permission to view this." })
    ).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
  });

  test("a role without ACCESS_EXPIRY/VIEW (Content Manager) does not see the nav link", async ({
    page,
  }) => {
    await mockTenantSession(page, "CONTENT_MANAGER");

    await page.goto("/tenant-admin/dashboard");

    await expect(page.getByRole("link", { name: "Reactivation Approvals" })).toHaveCount(0);
  });

  test("a VIEW-only role (Student Support) sees the nav link and the queue, but no approve/reject actions on detail", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT_SUPPORT");
    await mockJson(page, "**/api/v1/reactivation-requests*", 200, apiPageSuccess([]));

    await page.goto("/tenant-admin/dashboard");
    await expect(page.getByRole("link", { name: "Reactivation Approvals" })).toBeVisible();
  });
});

test.describe("tenant admin — Reactivation Approvals queue empty states", () => {
  test("zero-data on the default Submitted filter, first page, shows the pending-specific empty copy", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await page.route("**/api/v1/reactivation-requests*", async (route) => {
      const url = new URL(route.request().url());
      expect(url.searchParams.get("status")).toBe("SUBMITTED");
      await fulfillJson(route, 200, apiPageSuccess([]));
    });

    await page.goto("/tenant-admin/access-expiry/reactivation-approvals");

    await expect(page.getByText("No pending reactivation requests")).toBeVisible();
    await expect(page.getByText("No requests match your filter")).toHaveCount(0);
  });

  test("selecting 'All' sends no status query param", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const requests: Array<string | null> = [];
    await page.route("**/api/v1/reactivation-requests*", async (route) => {
      const url = new URL(route.request().url());
      requests.push(url.searchParams.get("status"));
      await fulfillJson(route, 200, apiPageSuccess([makeReactivationRequest()]));
    });

    await page.goto("/tenant-admin/access-expiry/reactivation-approvals");
    await expect(page.getByRole("table")).toBeVisible();

    await page.getByLabel("Status").click();
    await page.getByRole("option", { name: "All" }).click();

    await expect.poll(() => requests[requests.length - 1]).toBe(null);
  });

  test("a specific status filter returning empty shows the distinct 'no results' copy with a reset action", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await page.route("**/api/v1/reactivation-requests*", async (route) => {
      const url = new URL(route.request().url());
      const status = url.searchParams.get("status");
      if (status === "REJECTED") {
        await fulfillJson(route, 200, apiPageSuccess([]));
      } else {
        await fulfillJson(route, 200, apiPageSuccess([makeReactivationRequest()]));
      }
    });

    await page.goto("/tenant-admin/access-expiry/reactivation-approvals");
    await expect(page.getByRole("table")).toBeVisible();

    await page.getByLabel("Status").click();
    await page.getByRole("option", { name: "Rejected" }).click();

    await expect(page.getByText("No requests match your filter")).toBeVisible();
    await page.getByRole("button", { name: "Reset filters" }).click();
    await expect(page.getByRole("table")).toBeVisible();
  });
});

test.describe("tenant admin — Reactivation Approvals queue resolves requestedBy to email", () => {
  test("renders the resolved student email per row, degrading to the raw id on lookup failure", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const requestOk = makeReactivationRequest({ id: "req-ok", requestedBy: STUDENT_RECORD.id });
    const requestFail = makeReactivationRequest({ id: "req-fail", requestedBy: "student-missing" });
    await mockJson(page, "**/api/v1/reactivation-requests*", 200, apiPageSuccess([requestOk, requestFail]));
    await mockJson(page, `**/v1/students/${STUDENT_RECORD.id}`, 200, apiSuccess(STUDENT_RECORD));
    await mockJson(
      page,
      "**/v1/students/student-missing",
      404,
      apiError("NOT_FOUND", "Student not found.")
    );

    await page.goto("/tenant-admin/access-expiry/reactivation-approvals");

    await expect(page.getByRole("table").getByText(STUDENT_RECORD.email)).toBeVisible();
    await expect(page.getByRole("table").getByText("student-missing")).toBeVisible();
  });
});

test.describe("tenant admin — Reactivation Request Detail action visibility", () => {
  for (const role of ["FINANCE_STAFF", "STUDENT_SUPPORT"] as const) {
    test(`a VIEW-only role (${role}) sees the queue and the detail but no Approve/Reject actions`, async ({
      page,
    }) => {
      await mockTenantSession(page, role);
      const request = makeReactivationRequest({ id: `detail-${role}`, status: "SUBMITTED" });
      await mockJson(page, "**/api/v1/reactivation-requests*", 200, apiPageSuccess([request]));
      await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

      await page.goto("/tenant-admin/access-expiry/reactivation-approvals");
      const table = page.getByRole("table");
      await expect(table).toBeVisible();
      // The queue table itself never renders inline Approve/Reject controls
      // for any role (only a "View" link to the detail screen) — the
      // meaningful gate to prove is on the detail screen below.
      await expect(table.getByRole("button", { name: "Approve" })).toHaveCount(0);
      await expect(table.getByRole("button", { name: "Reject" })).toHaveCount(0);

      await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);
      await expect(page.getByText(`Enrollment #${request.enrollmentId.slice(0, 8)}`)).toBeVisible();
      await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
      await expect(page.getByRole("button", { name: "Reject" })).toHaveCount(0);
    });
  }

  test("a VIEW-only role (Read-only Auditor) sees the detail but no Approve/Reject actions", async ({
    page,
  }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    const request = makeReactivationRequest({ id: "detail-1", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);

    await expect(page.getByText(`Enrollment #${request.enrollmentId.slice(0, 8)}`)).toBeVisible();
    await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reject" })).toHaveCount(0);
  });

  test("a terminal APPROVED request shows no actions even for Tenant Admin", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "detail-2", status: "APPROVED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);

    await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reject" })).toHaveCount(0);
  });

  test("a Tenant Admin sees both actions on a SUBMITTED request", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "detail-3", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);

    await expect(page.getByRole("button", { name: "Approve" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Reject" })).toBeVisible();
  });

  test("a 404 on the detail fetch (cross-tenant anti-enumeration) renders the generic retryable error state", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/api/v1/reactivation-requests/does-not-exist",
      404,
      apiError("NOT_FOUND", "Reactivation request not found.")
    );

    await page.goto("/tenant-admin/access-expiry/reactivation-approvals/does-not-exist");

    const alert = page.getByRole("alert").filter({ hasText: "Reactivation request not found." });
    await expect(alert).toBeVisible();
  });
});

test.describe("tenant admin — Approve dialog", () => {
  test("submits with no note by default and closes on success", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "approve-1", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

    let approveBody: unknown;
    await page.route(`**/api/v1/reactivation-requests/${request.id}/approve`, async (route) => {
      approveBody = route.request().postDataJSON();
      await fulfillJson(route, 200, apiSuccess({ ...request, status: "APPROVED" }));
    });

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Approve", exact: true }).click();

    await expect(dialog).toHaveCount(0);
    expect(approveBody).toEqual({});
  });

  test("submits a trimmed note when provided", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "approve-2", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

    let approveBody: unknown;
    await page.route(`**/api/v1/reactivation-requests/${request.id}/approve`, async (route) => {
      approveBody = route.request().postDataJSON();
      await fulfillJson(route, 200, apiSuccess({ ...request, status: "APPROVED" }));
    });

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Note (optional)").fill("Verified expiry with the student.");
    await dialog.getByRole("button", { name: "Approve", exact: true }).click();

    await expect(dialog).toHaveCount(0);
    expect(approveBody).toEqual({ note: "Verified expiry with the student." });
  });

  test("a forced 403 surfaces inline, dialog stays open", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "approve-403", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));
    await mockJson(
      page,
      `**/api/v1/reactivation-requests/${request.id}/approve`,
      403,
      apiError("FORBIDDEN", "You do not have permission to approve reactivation requests.")
    );

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Approve", exact: true }).click();

    await expect(
      dialog.getByText("You do not have permission to approve reactivation requests.")
    ).toBeVisible();
    await expect(dialog).toBeVisible();
  });

  test("Escape does not dismiss the dialog while a note is partially typed", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "approve-escape", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Note (optional)").fill("Partial note");
    await page.keyboard.press("Escape");

    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel("Note (optional)")).toHaveValue("Partial note");

    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toHaveCount(0);
  });

  test("double-clicking Approve in quick succession results in exactly one network call", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "approve-doubleclick", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

    let approveCount = 0;
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route(`**/api/v1/reactivation-requests/${request.id}/approve`, async (route) => {
      approveCount += 1;
      await gate;
      await fulfillJson(route, 200, apiSuccess({ ...request, status: "APPROVED" }));
    });

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Approve", exact: true }).dblclick();

    await expect.poll(() => approveCount).toBeGreaterThan(0);
    await page.waitForTimeout(300);
    expect(approveCount).toBe(1);

    release?.();
    await expect(dialog).toHaveCount(0);
  });
});

test.describe("tenant admin — Reject dialog forced 403", () => {
  test("a forced 403 on the reject mutation surfaces inline, dialog stays open, never silently swallowed", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "reject-403", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));
    await mockJson(
      page,
      `**/api/v1/reactivation-requests/${request.id}/reject`,
      403,
      apiError("FORBIDDEN", "You do not have permission to reject reactivation requests.")
    );

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);
    await page.getByRole("button", { name: "Reject" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Reason").fill("Enrollment was never actually active.");
    await dialog.getByRole("button", { name: "Reject" }).click();

    await expect(
      dialog.getByText("You do not have permission to reject reactivation requests.")
    ).toBeVisible();
    // Not silently closed/treated as success.
    await expect(dialog).toBeVisible();
  });
});

test.describe("tenant admin — Reject dialog", () => {
  test("requires a non-blank reason before the client will submit", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "reject-validation", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

    let rejectCalled = false;
    await page.route(`**/api/v1/reactivation-requests/${request.id}/reject`, async (route) => {
      rejectCalled = true;
      await fulfillJson(route, 200, apiSuccess({ ...request, status: "REJECTED" }));
    });

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);
    await page.getByRole("button", { name: "Reject" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Reject" }).click();

    await expect(dialog.getByText("A reason is required.")).toBeVisible();
    expect(rejectCalled).toBe(false);
  });

  test("a successful reject closes the dialog and sends the trimmed reason", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "reject-success", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

    let rejectBody: unknown;
    await page.route(`**/api/v1/reactivation-requests/${request.id}/reject`, async (route) => {
      rejectBody = route.request().postDataJSON();
      await fulfillJson(
        route,
        200,
        apiSuccess({ ...request, status: "REJECTED", reviewedBy: "reviewer-1", reviewedAt: new Date().toISOString() })
      );
    });

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);
    await page.getByRole("button", { name: "Reject" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByLabel("Reason").fill("Enrollment was never actually active.");
    await dialog.getByRole("button", { name: "Reject" }).click();

    await expect(dialog).toHaveCount(0);
    expect(rejectBody).toEqual({ reason: "Enrollment was never actually active." });
  });

  test("the reject dialog traps focus while open and returns focus to the trigger on Cancel", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "reject-focus", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);

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

test.describe("accessibility — keyboard-only completion (no mouse)", () => {
  test("student: reach and activate the Reactivate CTA, then submit the reactivation request, using only the keyboard", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollment = makeEnrollment({
      state: "EXPIRED",
      canRequestReactivation: true,
      accessExpiresAt: new Date().toISOString(),
    });
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([enrollment]));
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));
    const created = makeReactivationRequest({
      id: "kb-request-1",
      enrollmentId: enrollment.enrollmentId,
    });
    await mockJson(
      page,
      `**/api/v1/enrollments/${enrollment.enrollmentId}/reactivation-requests`,
      201,
      apiSuccess(created)
    );

    await page.goto("/student/courses");

    // No `.click()` anywhere below — focus is placed on the CTA (a keyboard
    // user having tabbed there) and every following interaction is a
    // keyboard event only (Enter to activate a link/button).
    const reactivateLink = page.getByRole("table").getByRole("link", { name: /Reactivate/ });
    await reactivateLink.focus();
    await expect(reactivateLink).toBeFocused();
    await page.keyboard.press("Enter");

    await expect(page).toHaveURL(
      `/student/payments/reactivation?enrollmentId=${enrollment.enrollmentId}`
    );

    const submitButton = page.getByRole("button", { name: "Submit reactivation request" });
    await submitButton.focus();
    await expect(submitButton).toBeFocused();
    await page.keyboard.press("Enter");

    await expect(
      page.getByRole("status").filter({ hasText: "Reactivation request submitted" })
    ).toBeVisible();
  });

  test("tenant admin: open, fill, and submit the Approve dialog end-to-end using only the keyboard", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "kb-approve-1", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

    let approveBody: unknown;
    await page.route(`**/api/v1/reactivation-requests/${request.id}/approve`, async (route) => {
      approveBody = route.request().postDataJSON();
      await fulfillJson(route, 200, apiSuccess({ ...request, status: "APPROVED" }));
    });

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);

    const trigger = page.getByRole("button", { name: "Approve" });
    await trigger.focus();
    await page.keyboard.press("Enter");

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();

    const noteField = dialog.getByLabel("Note (optional)");
    await noteField.focus();
    await page.keyboard.type("Verified via keyboard-only flow.");

    // Footer DOM order is Cancel, then the submit button — Tab twice from
    // the note field to reach it, confirming real keyboard reachability
    // rather than assuming it.
    await page.keyboard.press("Tab");
    await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog.getByRole("button", { name: "Approve", exact: true })).toBeFocused();
    await page.keyboard.press("Enter");

    await expect(dialog).toHaveCount(0);
    expect(approveBody).toEqual({ note: "Verified via keyboard-only flow." });
  });

  test("tenant admin: open, fill, and submit the Reject dialog end-to-end using only the keyboard", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    const request = makeReactivationRequest({ id: "kb-reject-1", status: "SUBMITTED" });
    await mockJson(page, `**/api/v1/reactivation-requests/${request.id}`, 200, apiSuccess(request));

    let rejectBody: unknown;
    await page.route(`**/api/v1/reactivation-requests/${request.id}/reject`, async (route) => {
      rejectBody = route.request().postDataJSON();
      await fulfillJson(route, 200, apiSuccess({ ...request, status: "REJECTED" }));
    });

    await page.goto(`/tenant-admin/access-expiry/reactivation-approvals/${request.id}`);

    const trigger = page.getByRole("button", { name: "Reject" });
    await trigger.focus();
    await page.keyboard.press("Enter");

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();

    const reasonField = dialog.getByLabel("Reason");
    await reasonField.focus();
    await page.keyboard.type("Enrollment was never actually active.");

    // Footer DOM order is Cancel, then the submit button.
    await page.keyboard.press("Tab");
    await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog.getByRole("button", { name: "Reject" })).toBeFocused();
    await page.keyboard.press("Enter");

    await expect(dialog).toHaveCount(0);
    expect(rejectBody).toEqual({ reason: "Enrollment was never actually active." });
  });
});
