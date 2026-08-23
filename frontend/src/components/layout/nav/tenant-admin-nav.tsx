"use client";

import { useAuth } from "@/lib/auth/auth-context";
import { canProcessRefunds, canViewPaymentDashboard } from "@/lib/auth/permissions";
import { NavLinks, type NavItem } from "./nav-links";

const BASE_ITEMS: NavItem[] = [
  { label: "Dashboard", href: "/tenant-admin/dashboard" },
  { label: "Students", href: "/tenant-admin/students" },
  { label: "Teachers", href: "/tenant-admin/teachers" },
  { label: "Courses", href: "/tenant-admin/courses" },
  { label: "Profile" },
  { label: "Settings" },
];

/**
 * "Payments"/"Refunds" are appended conditionally on the caller's role
 * (`canViewPaymentDashboard`/`canProcessRefunds`, `lib/auth/permissions.ts`)
 * — pure UX convenience so a role with no server-side access to either
 * screen (e.g. Content Manager, Exam Manager) isn't shown a dead-end nav
 * entry. This is not the authorization mechanism: both destination pages
 * still independently render `PermissionDeniedState` from a real backend
 * 403 regardless of this nav's contents (per `.claude/rules/frontend.md`).
 */
export function TenantAdminNav({ onNavigate }: { onNavigate?: () => void }) {
  const { session } = useAuth();
  const role = session?.role ?? null;

  const items: NavItem[] = [...BASE_ITEMS];
  if (canViewPaymentDashboard(role)) {
    items.push({ label: "Payments", href: "/tenant-admin/payments/dashboard" });
  }
  if (canProcessRefunds(role)) {
    items.push({ label: "Refunds", href: "/tenant-admin/payments/refunds" });
  }

  return <NavLinks items={items} onNavigate={onNavigate} />;
}
