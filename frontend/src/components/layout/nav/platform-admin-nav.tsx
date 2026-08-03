import { NavLinks, type NavItem } from "./nav-links";

const items: NavItem[] = [
  { label: "Dashboard", href: "/platform-admin/dashboard" },
  { label: "Profile" },
  { label: "Settings" },
];

export function PlatformAdminNav() {
  return <NavLinks items={items} />;
}
