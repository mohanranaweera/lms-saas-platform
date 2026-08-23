import { NavLinks, type NavItem } from "./nav-links";

const items: NavItem[] = [
  { label: "Dashboard", href: "/student/dashboard" },
  { label: "Payments", href: "/student/payments/history" },
  { label: "Profile", href: "/student/profile" },
];

export function StudentNav({ onNavigate }: { onNavigate?: () => void }) {
  return <NavLinks items={items} onNavigate={onNavigate} />;
}
