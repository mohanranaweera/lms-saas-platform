import { NavLinks, type NavItem } from "./nav-links";
import { NotificationsNavBadge } from "./notifications-nav-badge";

export function StudentNav({ onNavigate }: { onNavigate?: () => void }) {
  const items: NavItem[] = [
    { label: "Dashboard", href: "/student/dashboard" },
    { label: "My Courses", href: "/student/courses" },
    // In-portal catalog (the public /courses page drops the portal shell);
    // the only place a Student starts a new purchase.
    { label: "Browse Courses", href: "/student/browse" },
    { label: "My Attendance", href: "/student/attendance" },
    { label: "Exams", href: "/student/exams" },
    { label: "Payments", href: "/student/payments/history" },
    { label: "Reactivation", href: "/student/payments/reactivation" },
    { label: "Notifications", href: "/student/notifications", badge: <NotificationsNavBadge /> },
    { label: "My Devices", href: "/student/devices" },
    { label: "AOC Player", href: "/student/player" },
    { label: "Profile", href: "/student/profile" },
  ];

  return <NavLinks items={items} onNavigate={onNavigate} />;
}
