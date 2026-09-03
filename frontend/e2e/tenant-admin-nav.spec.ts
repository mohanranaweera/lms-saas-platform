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
 * MVP-015 Tenant Admin Dashboard — TADASH-2 nav-shell Teachers-visibility fix
 * (`components/layout/nav/tenant-admin-nav.tsx`, `lib/auth/permissions.ts`'s
 * new `canViewTeachers`).
 *
 * `TenantAdminNav`'s "Teachers" link is now gated by `canViewTeachers(role)`
 * — visible for Tenant Admin, Course Coordinator, Student Support, Read-only
 * Auditor (the exact `TEACHERS`/`VIEW` grant set); hidden for Finance Staff,
 * Content Manager, Exam Manager, Attendance Operator (plan §2/§11). This is
 * a pure UX-visibility convenience, never the authorization mechanism — see
 * the "hidden link is not access control" test below, which is this
 * module's own explicit acceptance criterion (plan §4.2 step 4).
 *
 * The full 7-nav-item x 8-role matrix below (plan §18 "Nav shell: for each
 * of the 8 in-scope role fixtures... assert exactly which of
 * Students/Teachers/Courses/Payments/Refunds/Payment
 * Slips/Reactivation Approvals render") is asserted in one place here rather
 * than left partially covered by unrelated specs — individual
 * Payments/Refunds/Payment Slips/Reactivation Approvals link-visibility
 * assertions also incidentally exist in `manual-payment-slips.spec.ts` and
 * `order-and-payment.spec.ts` as a side effect of those modules' own flows;
 * this file is the single source of truth for the full matrix, not a
 * duplicate of those.
 *
 * No real backend runs in this environment (see `fixtures/auth-mocks.ts`'s
 * module doc) — every test mocks `/v1/**`/`/api/v1/**` responses shaped like
 * the documented `ApiResponse<T>` envelope.
 */

/** Establishes a session for the given role by mocking `POST /v1/auth/refresh`, so `RouteGuard` resolves `ready` on direct navigation. */
async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

/** The nav renders regardless of the dashboard content's own load state, but mock all three Overview reads to zero-data so the page settles deterministically instead of hitting a real network request. */
async function mockDashboardReads(page: Page): Promise<void> {
  await mockJson(page, "**/v1/students", 200, apiSuccess([]));
  await mockJson(page, "**/api/v1/ledger/dashboard*", 200, apiPageSuccess([]));
  await page.route("**/api/v1/courses*", async (route) => {
    await fulfillJson(route, 200, apiPageSuccess([]));
  });
}

/**
 * Full nav-item visibility matrix, one row per in-scope role (plan §2/§18).
 * Students/Courses are asserted as always-visible separately below (never
 * gated by role) rather than encoded per-row here, mirroring the plan's own
 * framing of those two as the "regression" baseline the other five vary
 * against.
 *
 * Derived directly from `lib/auth/permissions.ts`'s exported helpers, not
 * re-guessed:
 *   - `teachers`: `canViewTeachers` — Tenant Admin, Course Coordinator,
 *     Student Support, Read-only Auditor.
 *   - `payments` / `paymentSlips`: both driven by `canViewPaymentDashboard`
 *     (the nav wires the exact same helper to both entries) — Tenant Admin,
 *     Finance Staff, Student Support, Read-only Auditor.
 *   - `refunds`: `canProcessRefunds` — Tenant Admin, Finance Staff only.
 *   - `reactivationApprovals`: `canViewAccessExpiryQueue` — Tenant Admin,
 *     Finance Staff, Student Support, Read-only Auditor.
 */
const NAV_ITEM_VISIBILITY_MATRIX: Array<{
  role: string;
  teachers: boolean;
  payments: boolean;
  refunds: boolean;
  paymentSlips: boolean;
  reactivationApprovals: boolean;
}> = [
  {
    role: "TENANT_ADMIN",
    teachers: true,
    payments: true,
    refunds: true,
    paymentSlips: true,
    reactivationApprovals: true,
  },
  {
    role: "FINANCE_STAFF",
    teachers: false,
    payments: true,
    refunds: true,
    paymentSlips: true,
    reactivationApprovals: true,
  },
  {
    role: "COURSE_COORDINATOR",
    teachers: true,
    payments: false,
    refunds: false,
    paymentSlips: false,
    reactivationApprovals: false,
  },
  {
    role: "STUDENT_SUPPORT",
    teachers: true,
    payments: true,
    refunds: false,
    paymentSlips: true,
    reactivationApprovals: true,
  },
  {
    role: "CONTENT_MANAGER",
    teachers: false,
    payments: false,
    refunds: false,
    paymentSlips: false,
    reactivationApprovals: false,
  },
  {
    role: "EXAM_MANAGER",
    teachers: false,
    payments: false,
    refunds: false,
    paymentSlips: false,
    reactivationApprovals: false,
  },
  {
    role: "ATTENDANCE_OPERATOR",
    teachers: false,
    payments: false,
    refunds: false,
    paymentSlips: false,
    reactivationApprovals: false,
  },
  {
    role: "READ_ONLY_AUDITOR",
    teachers: true,
    payments: true,
    refunds: false,
    paymentSlips: true,
    reactivationApprovals: true,
  },
];

test.describe("tenant admin nav — full 7-nav-item x 8-role visibility matrix (TADASH-2, plan §18)", () => {
  test.beforeEach(async ({ page }) => {
    await mockDashboardReads(page);
  });

  for (const entry of NAV_ITEM_VISIBILITY_MATRIX) {
    test(`${entry.role}: nav items match this role's exact permission grants`, async ({ page }) => {
      await mockTenantSession(page, entry.role);
      await page.goto("/tenant-admin/dashboard");

      // Students and Courses hold `VIEW` for every one of the 8 roles in
      // scope (plan's Grounding note) — never gated by role, unlike the
      // other five items below.
      await expect(page.getByRole("link", { name: "Students" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Courses" })).toBeVisible();

      const gatedItems: Array<[string, boolean]> = [
        ["Teachers", entry.teachers],
        ["Payments", entry.payments],
        ["Refunds", entry.refunds],
        ["Payment Slips", entry.paymentSlips],
        ["Reactivation Approvals", entry.reactivationApprovals],
      ];
      for (const [label, visible] of gatedItems) {
        const link = page.getByRole("link", { name: label });
        if (visible) {
          await expect(link).toBeVisible();
        } else {
          await expect(link).toHaveCount(0);
        }
      }
    });
  }
});

test.describe("tenant admin nav — hidden link is not access control", () => {
  test("Finance Staff (no TEACHERS/VIEW) navigating directly to /tenant-admin/teachers by URL still gets a real, server-verified 403 — not a client-side redirect and not silently-empty data", async ({
    page,
  }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await mockJson(
      page,
      "**/v1/teachers",
      403,
      apiError("FORBIDDEN", "You do not have permission to view teachers.")
    );

    await page.goto("/tenant-admin/teachers");

    // Still on the destination route — no client-side redirect away from it.
    await expect(page).toHaveURL(/\/tenant-admin\/teachers$/);

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission to view this." });
    await expect(denied).toBeVisible();
    await expect(denied).toContainText("You do not have permission to view teachers.");
    // Not a silent 200 with unauthorized data — no teacher table/rows render.
    await expect(page.getByRole("table")).toHaveCount(0);

    // Same role's Teachers nav link is indeed hidden too, confirming the two
    // facts (hidden link, real backend 403) hold together for this role.
    await expect(page.getByRole("link", { name: "Teachers" })).toHaveCount(0);
  });
});

/**
 * Read-only Auditor "zero mutating controls" sweep (plan §18 "Nav shell:
 * Read-only Auditor renders zero mutating controls across every reachable
 * destination in this shell (regression sweep across Teachers/Payment
 * Slips/Reactivation Approvals/Refunds)").
 *
 * Read-only Auditor holds `VIEW`-only grants everywhere in this shell
 * (`canViewTeachers`, `canViewPaymentDashboard`, `canViewAccessExpiryQueue`
 * all `true`; `canProcessRefunds`/`canReviewSlips`/`canApproveReactivation`
 * all `false`) — every one of the four destinations below is reachable via
 * the nav for this role (see the matrix above), so a mutating control
 * leaking through would be a real, reachable regression, not a dead code
 * path.
 *
 * Two of the four destinations already have dedicated Read-only Auditor
 * "no mutating control" coverage in already-shipped spec files — verified,
 * not duplicated here:
 *   - Refunds: `order-and-payment.spec.ts`, "a READ_ONLY_AUDITOR session
 *     sees no Refund action either" (table + card view, both check).
 *   - Reactivation Approvals: `enrollment-and-course-access.spec.ts`, "a
 *     VIEW-only role (Read-only Auditor) sees the detail but no
 *     Approve/Reject actions".
 * The two genuinely missing pieces — Teachers (only Course Coordinator was
 * covered as the "role without the grant" case in
 * `teacher-management.spec.ts`) and the Payment Slip detail's Approve/Reject
 * actions (only Student Support was covered in
 * `manual-payment-slips.spec.ts`) — are added below.
 */
test.describe("tenant admin nav — Read-only Auditor sees zero mutating controls (sweep)", () => {
  test.beforeEach(async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
  });

  test("Teachers list and detail render no Approve/Reject actions, even for a PENDING teacher", async ({
    page,
  }) => {
    const pending = {
      id: "teacher-ro-1",
      name: "Read Only Target",
      email: "read-only-target@example.test",
      approvalStatus: "PENDING",
      accountStatus: "ACTIVE",
      approvedBy: null,
      approvedAt: null,
    };
    await mockJson(page, "**/v1/teachers", 200, apiSuccess([pending]));
    await mockJson(page, `**/v1/teachers/${pending.id}`, 200, apiSuccess(pending));

    await page.goto("/tenant-admin/teachers");
    await expect(page.getByRole("table").getByText(pending.name)).toBeVisible();
    await expect(page.getByRole("button", { name: /^Approve teacher/ })).toHaveCount(0);
    await expect(page.getByRole("button", { name: /^Reject teacher/ })).toHaveCount(0);

    await page.goto(`/tenant-admin/teachers/${pending.id}`);
    await expect(page.getByText(pending.name).first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Approve", exact: true })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reject", exact: true })).toHaveCount(0);
  });

  test("Payment slip detail (UNDER_REVIEW) renders no Approve/Reject actions", async ({ page }) => {
    const slip = {
      id: "slip-ro-1",
      orderId: "order-ro-1",
      studentId: "student-ro-1",
      referenceNumber: "REF-RO-1",
      status: "UNDER_REVIEW",
      submittedAt: new Date().toISOString(),
      reviewerId: null,
      reviewedAt: null,
      flags: [] as unknown[],
      studentEmail: "student-ro@example.com",
      reviewerEmail: null,
      orderAmount: 20,
      orderCurrency: "USD",
    };
    await mockJson(page, `**/api/v1/payment-slips/${slip.id}`, 200, apiSuccess(slip));

    await page.goto(`/tenant-admin/payments/slip-review/${slip.id}`);

    await expect(page.getByText(`Reference: ${slip.referenceNumber}`)).toBeVisible();
    await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reject" })).toHaveCount(0);
  });
});
