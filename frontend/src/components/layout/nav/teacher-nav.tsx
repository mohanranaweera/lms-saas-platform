"use client";

import { NavLinks, type NavItem } from "./nav-links";
import { NotificationsNavBadge } from "./notifications-nav-badge";
import { TUTE_CAP, useTuteCapabilities } from "@/lib/tutes/use-tute-capabilities";
import { useT } from "@/i18n/i18n-provider";

export function TeacherNav({ onNavigate }: { onNavigate?: () => void }) {
  // TUTE-01: Teachers hold no Tute capability by default; a Tenant Admin may
  // grant TUTE_DISPATCH_VIEW (own courses only). UX only - the backend enforces it.
  const tutes = useTuteCapabilities();
  const t = useT();
  const items: NavItem[] = [
    { label: t("teacher.nav.dashboard"), href: "/teacher/dashboard" },
    { label: t("teacher.nav.courses"), href: "/teacher/courses" },
    { label: t("teacher.nav.calendar"), href: "/teacher/calendar" },
    { label: t("teacher.nav.mediaLibrary"), href: "/teacher/media" },
    { label: t("teacher.nav.liveClasses"), href: "/teacher/live-classes" },
    { label: t("teacher.nav.markAttendance"), href: "/teacher/attendance/mark" },
    { label: t("teacher.nav.attendanceReports"), href: "/teacher/attendance/reports" },
    { label: t("teacher.nav.exams"), href: "/teacher/exams", activePrefixes: ["/teacher/exams"] },
    ...(tutes.can(TUTE_CAP.DISPATCH_VIEW) ? [{ label: t("teacher.nav.tuteDispatch"), href: "/teacher/tute-dispatch" }] : []),
    { label: t("teacher.nav.messages"), href: "/teacher/communications" },
    { label: t("teacher.nav.notifications"), href: "/teacher/notifications", badge: <NotificationsNavBadge /> },
    { label: t("teacher.nav.settings"), href: "/teacher/settings" },
    { label: t("teacher.nav.myProfile"), href: "/teacher/profile" },
  ];

  return <NavLinks items={items} onNavigate={onNavigate} />;
}
