import { NavLinks, type NavItem } from "./nav-links";
import { NotificationsNavBadge } from "./notifications-nav-badge";

export function StudentNav({ onNavigate }: { onNavigate?: () => void }) {
  const items: NavItem[] = [
    { label: "Dashboard", href: "/student/dashboard" },
    { label: "My Courses", href: "/student/courses" },
    // Klass catalog's Available view (free enrollment, ADR-028); the old
    // /student/browse route only redirects here.
    { label: "Available Courses", href: "/student/courses?view=available" },
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
