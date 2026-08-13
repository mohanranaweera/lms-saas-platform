import { NavLinks, type NavItem } from "./nav-links";

const items: NavItem[] = [
  { label: "Dashboard", href: "/tenant-admin/dashboard" },
  { label: "Students", href: "/tenant-admin/students" },
  { label: "Profile" },
  { label: "Settings" },
];

export function TenantAdminNav({ onNavigate }: { onNavigate?: () => void }) {
  return <NavLinks items={items} onNavigate={onNavigate} />;
}
