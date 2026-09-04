"use client";

import { useAuth } from "@/lib/auth/auth-context";
import {
  canMarkAttendanceStaff,
  canProcessRefunds,
  canViewAccessExpiryQueue,
  canViewAttendanceReports,
  canViewPaymentDashboard,
  canViewTeachers,
} from "@/lib/auth/permissions";
import { NavLinks, type NavItem } from "./nav-links";

/**
 * "Teachers" (gated by `canViewTeachers` — Tenant Admin, Course Coordinator,
 * Student Support, Read-only Auditor hold `TEACHERS`/`VIEW`; Finance Staff,
 * Content Manager, Exam Manager, Attendance Operator do not),
 * "Payments"/"Refunds"/"Payment Slips"/"Reactivation Approvals", and
 * "Attendance Reports"/"Mark Attendance" (MVP-016, gated by
 * `canViewAttendanceReports`/`canMarkAttendanceStaff` — Tenant Admin,
 * Attendance Operator, and for reports only, Read-only Auditor) are appended
 * conditionally on the caller's role — pure UX convenience so a role with no
 * server-side access to a screen isn't shown a dead-end nav entry. This is
 * not the authorization mechanism: every destination page still
 * independently renders `PermissionDeniedState` from a real backend 403
 * regardless of this nav's contents (per `.claude/rules/frontend.md`).
 */
export function TenantAdminNav({ onNavigate }: { onNavigate?: () => void }) {
  const { session } = useAuth();
  const role = session?.role ?? null;

  const items: NavItem[] = [
    { label: "Dashboard", href: "/tenant-admin/dashboard" },
    { label: "Students", href: "/tenant-admin/students" },
  ];
  if (canViewTeachers(role)) {
    items.push({ label: "Teachers", href: "/tenant-admin/teachers" });
  }
  items.push(
    { label: "Courses", href: "/tenant-admin/courses" },
    { label: "Profile" },
    { label: "Settings" }
  );
  if (canViewPaymentDashboard(role)) {
    items.push({ label: "Payments", href: "/tenant-admin/payments/dashboard" });
  }
  if (canProcessRefunds(role)) {
    items.push({ label: "Refunds", href: "/tenant-admin/payments/refunds" });
  }
  if (canViewPaymentDashboard(role)) {
    items.push({ label: "Payment Slips", href: "/tenant-admin/payments/slip-review" });
  }
  if (canViewAccessExpiryQueue(role)) {
    items.push({
      label: "Reactivation Approvals",
      href: "/tenant-admin/access-expiry/reactivation-approvals",
    });
  }
  if (canViewAttendanceReports(role)) {
    items.push({ label: "Attendance Reports", href: "/tenant-admin/attendance/reports" });
  }
  if (canMarkAttendanceStaff(role)) {
    items.push({ label: "Mark Attendance", href: "/tenant-admin/attendance/mark" });
  }

  return <NavLinks items={items} onNavigate={onNavigate} />;
}
