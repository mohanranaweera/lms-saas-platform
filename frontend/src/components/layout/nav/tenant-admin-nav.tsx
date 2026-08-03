import { NavLinks, type NavItem } from "./nav-links";

const items: NavItem[] = [
  { label: "Dashboard", href: "/tenant-admin/dashboard" },
  { label: "Profile" },
  { label: "Settings" },
];

export function TenantAdminNav() {
  return <NavLinks items={items} />;
}
