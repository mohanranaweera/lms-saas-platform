"use client";

import { useAuth } from "@/lib/auth/auth-context";
import {
  canMarkAttendanceStaff,
  canProcessRefunds,
  canViewAccessExpiryQueue,
  canViewAttendanceReports,
  canViewAuditLog,
  canViewExamsStaff,
  canViewFinance,
  canViewInstituteConfig,
  canViewLiveClassesStaff,
  canViewPaymentDashboard,
  canViewStaff,
  canViewTeachers,
} from "@/lib/auth/permissions";
import { NavGroup, type NavItem } from "./nav-links";

/**
 * Six-group Tenant Admin nav shell (Wave 1 — Tenant Admin IA + Configuration
 * Framework, `docs/parity/KLASS-PARITY-MASTER-INSTRUCTION.md` §6): Dashboard,
 * Academic, Finance, Communication, Administration, Institute Configuration.
 *
 * Every gate below is the exact same helper/grant this nav already used
 * before the regrouping — re-bucketed into sections, never re-derived —
 * plus two new grants backing Wave 1's new screens:
 *   - "Staff" / "Roles & Permissions" (Administration) — both gated on
 *     `canViewStaff` (`STAFF_AND_ROLES`/`VIEW`: Tenant Admin, Read-only
 *     Auditor), since the same backend grant backs both endpoints.
 *   - "General" / "Branding" (Institute Configuration) — both gated on
 *     `canViewInstituteConfig` (`BRANDING_SETTINGS`/`VIEW`: Tenant Admin,
 *     Read-only Auditor). No entry is added for the other 15 typed
 *     configuration domains (`ACADEMIC`, `STUDENT`, `TEACHER`, `COURSE`,
 *     `PAYMENT`, `FINANCE`, `ATTENDANCE`, `EXAM`, `CONTENT`, `VIDEO`,
 *     `NOTIFICATION`, `SECURITY`, `DEVICE`, `DOMAIN`, `INTEGRATION`) —
 *     nothing is built to configure there yet (Wave-1 explicit scope, not an
 *     oversight; see `lib/api/tenant-config.ts`'s own doc comment).
 *
 * "Communication" has no built destination yet (no notifications/email/SMS
 * admin screen exists anywhere in this codebase) — the group is omitted
 * entirely rather than rendered with placeholder "Soon" links, per this
 * module's explicit scope instruction.
 *
 * The two previous dead-end `{ label: "Profile" }` / `{ label: "Settings" }`
 * placeholder entries (no `href`, never wired to any real destination) are
 * removed outright: this codebase's own "a hidden/absent nav entry is UX
 * convenience, the real 403 is enforcement" framing presupposes a real
 * destination exists to be shown or hidden — these never had one, so keeping
 * them was never actually exercising that convention, just dead UI.
 *
 * Every gate here is pure UX convenience — the real enforcement is each
 * destination page's own backend 403, rendered via `PermissionDeniedState`/
 * `QueryStateBoundary` regardless of what this nav shows or hides (see
 * `.claude/rules/frontend.md`).
 */
export function TenantAdminNav({ onNavigate }: { onNavigate?: () => void }) {
  const { session } = useAuth();
  const role = session?.role ?? null;

  const dashboardItems: NavItem[] = [{ label: "Dashboard", href: "/tenant-admin/dashboard" }];

  const academicItems: NavItem[] = [{ label: "Students", href: "/tenant-admin/students" }];
  if (canViewTeachers(role)) {
    academicItems.push({ label: "Teachers", href: "/tenant-admin/teachers" });
  }
  academicItems.push({ label: "Courses", href: "/tenant-admin/courses" });
  if (canViewAttendanceReports(role)) {
    academicItems.push({ label: "Attendance Reports", href: "/tenant-admin/attendance/reports" });
  }
  if (canMarkAttendanceStaff(role)) {
    academicItems.push({ label: "Mark Attendance", href: "/tenant-admin/attendance/mark" });
  }
  if (canViewExamsStaff(role)) {
    academicItems.push({ label: "Exams", href: "/tenant-admin/exams" });
  }
  if (canViewLiveClassesStaff(role)) {
    academicItems.push({ label: "Live Classes", href: "/tenant-admin/live-classes" });
  }

  const financeItems: NavItem[] = [];
  if (canViewPaymentDashboard(role)) {
    financeItems.push({ label: "Payments", href: "/tenant-admin/payments/dashboard" });
  }
  if (canProcessRefunds(role)) {
    financeItems.push({ label: "Refunds", href: "/tenant-admin/payments/refunds" });
  }
  if (canViewPaymentDashboard(role)) {
    financeItems.push({ label: "Payment Slips", href: "/tenant-admin/payments/slip-review" });
  }
  if (canViewAccessExpiryQueue(role)) {
    financeItems.push({
      label: "Reactivation Approvals",
      href: "/tenant-admin/access-expiry/reactivation-approvals",
    });
  }
  // Wave 7 — FINANCE_EXPENSES/VIEW (Tenant Admin, Finance Staff, Read-only
  // Auditor). UX only; every /api/v1/finance/** endpoint re-enforces it.
  if (canViewFinance(role)) {
    financeItems.push(
      { label: "Finance Summary", href: "/tenant-admin/finance" },
      { label: "Expenses", href: "/tenant-admin/finance/expenses" },
      { label: "Expense Categories", href: "/tenant-admin/finance/categories" },
      { label: "Finance Reports", href: "/tenant-admin/finance/reports" },
      { label: "Teacher Payouts", href: "/tenant-admin/finance/teacher-payouts" }
    );
  }

  const administrationItems: NavItem[] = [];
  if (canViewStaff(role)) {
    administrationItems.push(
      { label: "Staff", href: "/tenant-admin/staff" },
      { label: "Roles & Permissions", href: "/tenant-admin/roles-permissions" }
    );
  }
  if (canViewAuditLog(role)) {
    administrationItems.push({ label: "Audit Log", href: "/tenant-admin/audit-log" });
  }

  const instituteConfigItems: NavItem[] = [];
  if (canViewInstituteConfig(role)) {
    instituteConfigItems.push(
      { label: "General", href: "/tenant-admin/settings/general" },
      { label: "Branding", href: "/tenant-admin/settings/branding" }
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <NavGroup label="Dashboard" items={dashboardItems} onNavigate={onNavigate} />
      <NavGroup label="Academic" items={academicItems} onNavigate={onNavigate} />
      <NavGroup label="Finance" items={financeItems} onNavigate={onNavigate} />
      <NavGroup label="Administration" items={administrationItems} onNavigate={onNavigate} />
      <NavGroup label="Institute Configuration" items={instituteConfigItems} onNavigate={onNavigate} />
    </div>
  );
}
