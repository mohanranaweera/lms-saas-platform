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
 * Wave 1 (Tenant Admin IA + Configuration Framework) regrouped this nav into
 * six labeled sections (Dashboard, Academic, Finance, Communication,
 * Administration, Institute Configuration —
 * `docs/parity/KLASS-PARITY-MASTER-INSTRUCTION.md` §6) and added two new
 * gated entries, extended into this same matrix rather than a separate file:
 *   - `staff` (Administration: "Staff", "Roles & Permissions") —
 *     `canViewStaff` (`STAFF_AND_ROLES`/`VIEW`: Tenant Admin, Read-only
 *     Auditor only — both entries share this one gate).
 *   - `instituteConfig` (Institute Configuration: "General", "Branding") —
 *     `canViewInstituteConfig` (`BRANDING_SETTINGS`/`VIEW`: Tenant Admin,
 *     Read-only Auditor only — both entries share this one gate).
 * "Communication" has no built destination yet and renders nothing (see
 * `tenant-admin-nav.tsx`'s own doc comment) — not asserted here as it has no
 * observable content either way.
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
 *   - `exams` (MVP-017 code-review Fix 2): `canViewExamsStaff` — every role
 *     `canManageExamsStaff` includes (Tenant Admin, Exam Manager) PLUS
 *     Read-only Auditor, who holds `EXAMS`/`VIEW` only. This is the exact
 *     regression this matrix entry guards: before Fix 2, the nav gated
 *     "Exams" on `canManageExamsStaff` alone, so a Read-only Auditor had no
 *     nav path into a module their role can otherwise view real data in.
 *   - `auditLog` (MVP-019): `canViewAuditLog` — Tenant Admin and Read-only
 *     Auditor only, mirroring the backend's own interim allowlist
 *     (`docs/api/audit-log-management.md`'s "Authorization model"), which is
 *     narrower than the general `DomainArea.AUDIT_LOG`/`VIEW` grant every
 *     other role in this matrix (e.g. Finance Staff) also happens to hold.
 */
const NAV_ITEM_VISIBILITY_MATRIX: Array<{
  role: string;
  teachers: boolean;
  payments: boolean;
  refunds: boolean;
  paymentSlips: boolean;
  reactivationApprovals: boolean;
  exams: boolean;
  auditLog: boolean;
  staff: boolean;
  instituteConfig: boolean;
}> = [
  {
    role: "TENANT_ADMIN",
    teachers: true,
    payments: true,
    refunds: true,
    paymentSlips: true,
    reactivationApprovals: true,
    exams: true,
    auditLog: true,
    staff: true,
    instituteConfig: true,
  },
  {
    role: "FINANCE_STAFF",
    teachers: false,
    payments: true,
    refunds: true,
    paymentSlips: true,
    reactivationApprovals: true,
    exams: false,
    auditLog: false,
    staff: false,
    instituteConfig: false,
  },
  {
    role: "COURSE_COORDINATOR",
    teachers: true,
    payments: false,
    refunds: false,
    paymentSlips: false,
    reactivationApprovals: false,
    exams: false,
    auditLog: false,
    staff: false,
    instituteConfig: false,
  },
  {
    role: "STUDENT_SUPPORT",
    teachers: true,
    payments: true,
    refunds: false,
    paymentSlips: true,
    reactivationApprovals: true,
    exams: false,
    auditLog: false,
    staff: false,
    instituteConfig: false,
  },
  {
    role: "CONTENT_MANAGER",
    teachers: false,
    payments: false,
    refunds: false,
    paymentSlips: false,
    reactivationApprovals: false,
    exams: false,
    auditLog: false,
    staff: false,
    instituteConfig: false,
  },
  {
    role: "EXAM_MANAGER",
    teachers: false,
    payments: false,
    refunds: false,
    paymentSlips: false,
    reactivationApprovals: false,
    exams: true,
    auditLog: false,
    staff: false,
    instituteConfig: false,
  },
  {
    role: "ATTENDANCE_OPERATOR",
    teachers: false,
    payments: false,
    refunds: false,
    paymentSlips: false,
    reactivationApprovals: false,
    exams: false,
    auditLog: false,
    staff: false,
    instituteConfig: false,
  },
  {
    role: "READ_ONLY_AUDITOR",
    teachers: true,
    payments: true,
    refunds: false,
    paymentSlips: true,
    reactivationApprovals: true,
    exams: true,
    auditLog: true,
    staff: true,
    instituteConfig: true,
  },
];

test.describe("tenant admin nav — full 8-nav-item x 8-role visibility matrix (TADASH-2, plan §18; audit log entry added by MVP-019)", () => {
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
        ["Exams", entry.exams],
        ["Audit Log", entry.auditLog],
        ["Staff", entry.staff],
        ["Roles & Permissions", entry.staff],
        ["General", entry.instituteConfig],
        ["Branding", entry.instituteConfig],
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

test.describe("tenant admin nav — six-group regrouping (Wave 1)", () => {
  test.beforeEach(async ({ page }) => {
    await mockDashboardReads(page);
  });

  test("Tenant Admin sees every non-empty group heading; Communication (no built destination) renders no heading", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/dashboard");

    const nav = page.getByRole("navigation", { name: "Primary" });
    // "Dashboard" is both this group's own heading text AND its single
    // link's accessible name, so `getByText` resolves to 2 matches for it
    // specifically (a `getByRole("heading")` locator doesn't apply either —
    // the group heading is a plain `<span>`, not a semantic heading element)
    // — assert it's present at least once rather than exactly-one for that
    // single case; every other group heading has no same-named link, so
    // exactly one match is the correct assertion there.
    await expect(nav.getByText("Dashboard", { exact: true }).first()).toBeVisible();
    for (const heading of ["Academic", "Finance", "Administration", "Institute Configuration"]) {
      await expect(nav.getByText(heading, { exact: true })).toBeVisible();
    }
    await expect(nav.getByText("Communication", { exact: true })).toHaveCount(0);
  });

  test("a role without BRANDING_SETTINGS/STAFF_AND_ROLES (Course Coordinator) sees no Institute Configuration/Staff/Roles & Permissions links or headings", async ({
    page,
  }) => {
    await mockTenantSession(page, "COURSE_COORDINATOR");
    await page.goto("/tenant-admin/dashboard");

    await expect(page.getByRole("link", { name: "Staff" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Roles & Permissions" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "General" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Branding" })).toHaveCount(0);
    // Institute Configuration's heading is only rendered when it has at
    // least one item (`NavGroup` renders nothing for an empty group) — this
    // role has zero items in that group, so the heading itself must be gone
    // too, not just the links inside it.
    await expect(page.getByText("Institute Configuration", { exact: true })).toHaveCount(0);
  });
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
