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
import { CAP, REPORT_CAPABILITIES } from "@/lib/auth/capabilities";
import { useCapabilities } from "@/lib/api/my-permissions";
import { TRANSLATION_CAP } from "@/lib/i18n-admin/use-translation-capabilities";
import { TUTE_CAP, useTuteCapabilities } from "@/lib/tutes/use-tute-capabilities";
import { NavGroup, type NavItem } from "./nav-links";

/**
 * Six-group Tenant Admin nav shell (Wave 1 — Tenant Admin IA + Configuration
 * Framework, `docs/parity/KLASS-PARITY-MASTER-INSTRUCTION.md` §6): Dashboard,
 * Academic, Finance, Communication, Administration, Institute Configuration.
 *
 * Every gate below is the exact same helper/grant this nav already used
 * before the regrouping — re-bucketed into sections, never re-derived —
 * plus two new grants backing Wave 1's new screens:
 *   - "Staff" (Administration) — gated on `canViewStaff`
 *     (`STAFF_AND_ROLES`/`VIEW`: Tenant Admin, Read-only Auditor).
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
 * STAFF-MGMT-02/03: every gate with a catalogued capability goes through
 * `can(capability, legacyCheck)`. Tenant Admin gets exactly the legacy role
 * helper it always had; OPERATOR / MANAGER and the seven seeded sub-roles
 * (whose access a Tenant Admin can now edit) see an item only when their
 * effective capabilities (`GET /v1/me/permissions`) include the matching
 * capability. Entries with no capability in the catalog keep the role helper. "Roles & Permissions" is now Staff → Permissions.
 *
 * Every gate here is pure UX convenience — the real enforcement is each
 * destination page's own backend 403, rendered via `PermissionDeniedState`/
 * `QueryStateBoundary` regardless of what this nav shows or hides (see
 * `.claude/rules/frontend.md`).
 */
export function TenantAdminNav({ onNavigate }: { onNavigate?: () => void }) {
  const { session } = useAuth();
  const role = session?.role ?? null;
  const { can } = useCapabilities();
  const tutes = useTuteCapabilities();

  const dashboardItems: NavItem[] = [{ label: "Dashboard", href: "/tenant-admin/dashboard" }];

  const academicItems: NavItem[] = [];
  if (can(CAP.STUDENTS_VIEW, true)) {
    academicItems.push({ label: "Students", href: "/tenant-admin/students" });
  }
  if (can(CAP.TEACHERS_VIEW, canViewTeachers(role))) {
    academicItems.push({ label: "Teachers", href: "/tenant-admin/teachers" });
  }
  if (can(CAP.COURSES_VIEW, true)) {
    academicItems.push({ label: "Courses", href: "/tenant-admin/courses" });
  }
  // CAL-01 - scope is server-derived; the calendar endpoints re-check COURSES/VIEW.
  if (can(CAP.TIMETABLE_VIEW, true)) {
    academicItems.push({ label: "Calendar", href: "/tenant-admin/calendar" });
  }
  if (can(CAP.MEDIA_VIEW, canViewMedia(role))) {
    academicItems.push({ label: "Media Library", href: "/tenant-admin/media" });
  }
  if (can(CAP.ATTENDANCE_VIEW, canViewAttendanceReports(role))) {
    academicItems.push({ label: "Attendance Reports", href: "/tenant-admin/attendance/reports" });
  }
  if (can(CAP.ATTENDANCE_ADD, canMarkAttendanceStaff(role))) {
    academicItems.push({ label: "Mark Attendance", href: "/tenant-admin/attendance/mark" });
  }
  if (can(CAP.EXAMS_VIEW, canViewExamsStaff(role))) {
    academicItems.push({ label: "Exams", href: "/tenant-admin/exams", activePrefixes: ["/tenant-admin/exams"] });
  }
  if (canViewLiveClassesStaff(role)) {
    academicItems.push({ label: "Live Classes", href: "/tenant-admin/live-classes" });
  }
  // TUTE-01 - TUTE_DISPATCH_VIEW (capability-native for every role; Tenant Admin by default).
  // UX only; /api/v1/tute-dispatch/** re-checks the capability and course scope.
  if (tutes.can(TUTE_CAP.DISPATCH_VIEW)) {
    academicItems.push({ label: "Tute Dispatch", href: "/tenant-admin/tute-dispatch" });
  }

  const financeItems: NavItem[] = [];
  // PAY-HUB-01: one Payments hub (All Orders, Payment Slips, Free Cards,
  // Refunds tabs). Shown when any of its tabs is visible to this role. UX
  // only; every tab's endpoints re-check permission server-side.
  if (
    can(
      CAP.PAYMENTS_VIEW,
      canViewPaymentDashboard(role) ||
        canViewStudentPayments(role) ||
        canViewFreeCards(role) ||
        canProcessRefunds(role)
    )
  ) {
    financeItems.push({
      label: "Payments",
      href: "/tenant-admin/payments",
      activePrefixes: ["/tenant-admin/payments/slip-review"],
    });
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
  // Wave 7 - FINANCE_EXPENSES/VIEW for Tenant Admin (role helper). STAFF-MGMT-03:
  // capability-driven roles (Operator, Manager, the seven seeded sub-roles)
  // get each entry from its own capability, so a revoked one disappears. UX
  // only; every /api/v1/finance/** and /api/v1/reports/** endpoint re-enforces it.
  const legacyFinance = canViewFinance(role);
  if (can([CAP.FINANCE_VIEW_EXPENSES, CAP.FINANCE_VIEW_INCOME_SUMMARY], legacyFinance)) {
    financeItems.push({ label: "Finance Summary", href: "/tenant-admin/finance" });
  }
  if (can(CAP.FINANCE_VIEW_EXPENSES, legacyFinance)) {
    financeItems.push({ label: "Expenses", href: "/tenant-admin/finance/expenses" });
  }
  if (can(CAP.FINANCE_VIEW_CATEGORY, legacyFinance)) {
    financeItems.push({ label: "Expense Categories", href: "/tenant-admin/finance/categories" });
  }
  if (can([CAP.FINANCE_DASHBOARD_ANNUAL_STATS, CAP.FINANCE_VIEW_INCOME_SUMMARY], legacyFinance)) {
    financeItems.push({ label: "Finance Reports", href: "/tenant-admin/finance/reports" });
  }
  if (can(CAP.TEACHERS_VIEW_WALLET, legacyFinance)) {
    financeItems.push({ label: "Teacher Payouts", href: "/tenant-admin/finance/teacher-payouts" });
  }
  // RPT-01 - report catalog; each /api/v1/reports/** endpoint carries its REPORTS_* capability.
  if (can(REPORT_CAPABILITIES, legacyFinance)) {
    financeItems.push({ label: "Reports", href: "/tenant-admin/reports" });
  }

  const communicationItems: NavItem[] = [];
  if (can(CAP.NOTIFICATIONS_VIEW, canViewCommunications(role))) {
    communicationItems.push(
      { label: "Broadcasts", href: "/tenant-admin/communications" },
      { label: "Message Templates", href: "/tenant-admin/communications/templates" }
    );
  }

  const administrationItems: NavItem[] = [];
  // STAFF-MGMT-02: Staff (Users / Permissions / Activities). STAFF_VIEW is
  // LOCKED, so OPERATOR / MANAGER never get it.
  if (can(CAP.STAFF_VIEW, canViewStaff(role))) {
    administrationItems.push({ label: "Staff", href: "/tenant-admin/staff" });
  }
  if (canViewAuditLog(role)) {
    administrationItems.push({ label: "Audit Log", href: "/tenant-admin/audit-log" });
  }

  const instituteConfigItems: NavItem[] = [];
  if (canViewInstituteConfig(role)) {
    instituteConfigItems.push(
      // General, Payments, Student fields and Integrations are tabs inside Settings (settings-tab-strip.tsx).
      {
        label: "Settings",
        href: "/tenant-admin/settings/general",
        activePrefixes: [
          "/tenant-admin/settings/payments",
          "/tenant-admin/settings/policies",
          "/tenant-admin/settings/student-fields",
          "/tenant-admin/settings/site-media",
          "/tenant-admin/settings/integrations",
          "/tenant-admin/settings/video",
          "/tenant-admin/settings/video-providers",
          "/tenant-admin/settings/video-configuration",
          "/tenant-admin/settings/branding",
          "/tenant-admin/settings/translations",
        ],
      },
      { label: "Appearance", href: "/tenant-admin/settings/appearance" }
    );
  } else if (can(CAP.FINANCE_ACCOUNT_VIEW_CONFIGURATION, canViewPaymentGateway(role))) {
    // SET-PAY-01: online payments are the Settings → Payments tab now. Finance
    // Staff (PAYMENT_GATEWAY/VIEW, no institute config) land straight on it.
    instituteConfigItems.push({ label: "Settings", href: "/tenant-admin/settings/payments" });
  }
  // I18N-01: a staff role granted TRANSLATIONS_VIEW without institute settings access.
  if (!canViewInstituteConfig(role) && can(TRANSLATION_CAP.VIEW, false)) {
    instituteConfigItems.push({ label: "Translations", href: "/tenant-admin/settings/translations" });
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
      {academicItems.length > 0 ? (
        <NavGroup label="Academic" items={academicItems} onNavigate={onNavigate} />
      ) : null}
      {financeItems.length > 0 ? (
        <NavGroup label="Finance" items={financeItems} onNavigate={onNavigate} />
      ) : null}
      {communicationItems.length > 0 ? (
        <NavGroup label="Communication" items={communicationItems} onNavigate={onNavigate} />
      ) : null}
      {administrationItems.length > 0 ? (
        <NavGroup label="Administration" items={administrationItems} onNavigate={onNavigate} />
      ) : null}
      <NavGroup label="Institute Configuration" items={instituteConfigItems} onNavigate={onNavigate} />
    </div>
  );
}
