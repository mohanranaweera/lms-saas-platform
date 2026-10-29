"use client";

import { useAuth } from "@/lib/auth/auth-context";
import {
  canMarkAttendanceStaff,
  canProcessRefunds,
  canViewAccessExpiryQueue,
  canViewAttendanceReports,
  canViewAuditLog,
  canViewDiscounts,
  canViewExamsStaff,
  canViewFinance,
  canViewInstituteConfig,
  canViewPaymentGateway,
  canViewCustomDomains,
  canViewIntegrations,
  canViewLiveClassesStaff,
  canViewPaymentDashboard,
  canViewStaff,
  canViewTeachers,
  canViewAccessPolicy,
  canViewDevices,
  canViewCommunications,
  canViewMedia,
  canViewStudentPayments,
  canViewFreeCards,
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
 * "Communication" (Wave 11) - broadcasts and message templates, gated on
 * `COMMUNICATIONS`/`VIEW` (Tenant Admin, Student Support, Read-only
 * Auditor). SMS / WhatsApp provider settings live under Institute
 * Configuration ("Messaging", `INTEGRATIONS`/`VIEW`). The group is omitted
 * entirely for roles without the grant.
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
  if (canViewMedia(role)) {
    academicItems.push({ label: "Media Library", href: "/tenant-admin/media" });
  }
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
  // ADR-030 - PAYMENTS_SLIPS/VIEW and FREE_CARDS/VIEW. UX only; the backend re-checks.
  if (canViewStudentPayments(role)) {
    financeItems.push({ label: "Student Payments", href: "/tenant-admin/payments/students" });
  }
  if (canViewFreeCards(role)) {
    financeItems.push({ label: "Free Cards", href: "/tenant-admin/payments/free-cards" });
  }
  // DISC-01 - DISCOUNTS/VIEW. UX only; /api/v1/admin/discounts re-enforces it.
  if (canViewDiscounts(role)) {
    financeItems.push({ label: "Discounts", href: "/tenant-admin/payments/discounts" });
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
      { label: "Teacher Payouts", href: "/tenant-admin/finance/teacher-payouts" },
      // RPT-01 - report catalog; /api/v1/reports/** re-enforces FINANCE_EXPENSES/VIEW.
      { label: "Reports", href: "/tenant-admin/reports" }
    );
  }

  const communicationItems: NavItem[] = [];
  if (canViewCommunications(role)) {
    communicationItems.push(
      { label: "Broadcasts", href: "/tenant-admin/communications" },
      { label: "Message Templates", href: "/tenant-admin/communications/templates" }
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
      // General, Student fields and Integrations are tabs inside Settings (settings-tab-strip.tsx).
      {
        label: "Settings",
        href: "/tenant-admin/settings/general",
        activePrefixes: [
          "/tenant-admin/settings/student-fields",
          "/tenant-admin/settings/site-media",
          "/tenant-admin/settings/integrations",
          "/tenant-admin/settings/video",
          "/tenant-admin/settings/video-providers",
          "/tenant-admin/settings/video-configuration",
          "/tenant-admin/settings/branding",
        ],
      },
      { label: "Appearance", href: "/tenant-admin/settings/appearance" }
    );
  }
  if (canViewPaymentGateway(role)) {
    instituteConfigItems.push({ label: "Online Payments", href: "/tenant-admin/settings/payments" });
  }
  // MEDIA-INT-01 - INTEGRATIONS/VIEW (Tenant Admin, Read-only Auditor).
  if (canViewIntegrations(role)) {
    // Wave 11 - SMS / WhatsApp provider accounts (same INTEGRATIONS grant).
    instituteConfigItems.push({ label: "Messaging (SMS & WhatsApp)", href: "/tenant-admin/settings/messaging" });
    // VIDEO Phase 3 - video providers and video settings are the Settings → Video tab now.
  }
  // TEN-DOM-01 - CUSTOM_DOMAINS/VIEW (Tenant Admin, Read-only Auditor).
  if (canViewCustomDomains(role)) {
    instituteConfigItems.push({ label: "Custom Domain", href: "/tenant-admin/settings/domains" });
  }
  // Wave 10 - DEVICES/VIEW or ACCESS_EXPIRY/VIEW. UX only; the backend re-checks every request.
  if (canViewDevices(role) || canViewAccessPolicy(role)) {
    instituteConfigItems.push({ label: "Devices & Access", href: "/tenant-admin/settings/devices-access" });
  }

  return (
    <div className="flex flex-col gap-4">
      <NavGroup label="Dashboard" items={dashboardItems} onNavigate={onNavigate} />
      <NavGroup label="Academic" items={academicItems} onNavigate={onNavigate} />
      <NavGroup label="Finance" items={financeItems} onNavigate={onNavigate} />
      {communicationItems.length > 0 ? (
        <NavGroup label="Communication" items={communicationItems} onNavigate={onNavigate} />
      ) : null}
      <NavGroup label="Administration" items={administrationItems} onNavigate={onNavigate} />
      <NavGroup label="Institute Configuration" items={instituteConfigItems} onNavigate={onNavigate} />
    </div>
  );
}
