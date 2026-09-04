import { NavLinks, type NavItem } from "./nav-links";

const items: NavItem[] = [
  { label: "Dashboard", href: "/teacher/dashboard" },
  { label: "Courses", href: "/teacher/courses" },
  { label: "Mark Attendance", href: "/teacher/attendance/mark" },
  { label: "Attendance Reports", href: "/teacher/attendance/reports" },
  { label: "Profile" },
];

export function TeacherNav({ onNavigate }: { onNavigate?: () => void }) {
  return <NavLinks items={items} onNavigate={onNavigate} />;
}
