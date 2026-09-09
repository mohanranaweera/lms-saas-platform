import { NavLinks, type NavItem } from "./nav-links";
import { NotificationsNavBadge } from "./notifications-nav-badge";

export function TeacherNav({ onNavigate }: { onNavigate?: () => void }) {
  const items: NavItem[] = [
    { label: "Dashboard", href: "/teacher/dashboard" },
    { label: "Courses", href: "/teacher/courses" },
    { label: "Mark Attendance", href: "/teacher/attendance/mark" },
    { label: "Attendance Reports", href: "/teacher/attendance/reports" },
    { label: "Exams", href: "/teacher/exams/questions" },
    { label: "Notifications", href: "/teacher/notifications", badge: <NotificationsNavBadge /> },
    { label: "Profile" },
  ];

  return <NavLinks items={items} onNavigate={onNavigate} />;
}
