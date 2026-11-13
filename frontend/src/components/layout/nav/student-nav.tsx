"use client";

import { NavLinks, type NavItem } from "./nav-links";
import { NotificationsNavBadge } from "./notifications-nav-badge";
import { useT } from "@/i18n/i18n-provider";

export function StudentNav({ onNavigate }: { onNavigate?: () => void }) {
  const t = useT();
  const items: NavItem[] = [
    { label: t("student.nav.dashboard"), href: "/student/dashboard" },
    // Klass catalog's Available view (free enrollment, ADR-028); the old
    // /student/browse route only redirects here.
    { label: t("student.nav.availableCourses"), href: "/student/courses?view=available" },
    { label: t("student.nav.calendar"), href: "/student/calendar" },
    { label: t("student.nav.myAttendance"), href: "/student/attendance" },
    { label: t("student.nav.exams"), href: "/student/exams" },
    { label: t("student.nav.reactivation"), href: "/student/payments/reactivation" },
    { label: t("student.nav.notifications"), href: "/student/notifications", badge: <NotificationsNavBadge /> },
    { label: t("student.nav.myDevices"), href: "/student/devices" },
    { label: t("student.nav.aocPlayer"), href: "/student/player" },
    { label: t("student.nav.profile"), href: "/student/profile" },
  ];

  return <NavLinks items={items} onNavigate={onNavigate} />;
}
