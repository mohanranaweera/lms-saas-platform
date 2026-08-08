import { NavLinks, type NavItem } from "./nav-links";

const items: NavItem[] = [
  { label: "Dashboard", href: "/platform-admin/dashboard" },
  { label: "Tenants", href: "/platform-admin/tenants" },
  { label: "Profile" },
  { label: "Settings" },
];

export function PlatformAdminNav({ onNavigate }: { onNavigate?: () => void }) {
  return <NavLinks items={items} onNavigate={onNavigate} />;
}
