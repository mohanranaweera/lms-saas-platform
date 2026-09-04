import { NavLinks, type NavItem } from "./nav-links";

const items: NavItem[] = [
  { label: "Dashboard", href: "/student/dashboard" },
  { label: "My Courses", href: "/student/courses" },
  { label: "My Attendance", href: "/student/attendance" },
  { label: "Payments", href: "/student/payments/history" },
  { label: "Reactivation", href: "/student/payments/reactivation" },
  { label: "Profile", href: "/student/profile" },
];

export function StudentNav({ onNavigate }: { onNavigate?: () => void }) {
  return <NavLinks items={items} onNavigate={onNavigate} />;
}
