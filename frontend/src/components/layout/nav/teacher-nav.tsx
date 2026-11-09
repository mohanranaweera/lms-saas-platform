"use client";

import { NavLinks, type NavItem } from "./nav-links";
import { NotificationsNavBadge } from "./notifications-nav-badge";
import { TUTE_CAP, useTuteCapabilities } from "@/lib/tutes/use-tute-capabilities";

export function TeacherNav({ onNavigate }: { onNavigate?: () => void }) {
  // TUTE-01: Teachers hold no Tute capability by default; a Tenant Admin may
  // grant TUTE_DISPATCH_VIEW (own courses only). UX only - the backend enforces it.
  const tutes = useTuteCapabilities();
  const items: NavItem[] = [
    { label: "Dashboard", href: "/teacher/dashboard" },
    { label: "Courses", href: "/teacher/courses" },
    { label: "Calendar", href: "/teacher/calendar" },
    { label: "Media Library", href: "/teacher/media" },
    { label: "Live Classes", href: "/teacher/live-classes" },
    { label: "Mark Attendance", href: "/teacher/attendance/mark" },
    { label: "Attendance Reports", href: "/teacher/attendance/reports" },
    { label: "Exams", href: "/teacher/exams", activePrefixes: ["/teacher/exams"] },
    ...(tutes.can(TUTE_CAP.DISPATCH_VIEW) ? [{ label: "Tute Dispatch", href: "/teacher/tute-dispatch" }] : []),
    { label: "Messages", href: "/teacher/communications" },
    { label: "Notifications", href: "/teacher/notifications", badge: <NotificationsNavBadge /> },
    { label: "Settings", href: "/teacher/settings" },
    { label: "Profile" },
  ];

  return <NavLinks items={items} onNavigate={onNavigate} />;
}
