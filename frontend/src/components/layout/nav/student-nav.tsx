import { NavLinks, type NavItem } from "./nav-links";

const items: NavItem[] = [
  { label: "Dashboard", href: "/student/dashboard" },
  { label: "Profile" },
];

export function StudentNav() {
  return <NavLinks items={items} />;
}
