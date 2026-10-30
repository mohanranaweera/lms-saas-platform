"use client";

import Link from "next/link";
import { Settings } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { canViewInstituteConfig, canViewIntegrations, canViewPaymentGateway } from "@/lib/auth/permissions";
import { cn } from "@/lib/utils";

/**
 * Settings section tabs (links, not an ARIA tablist — each tab is its own
 * route). The sidebar has a single "Settings" entry; General, Payments,
 * Appearance, Student fields, Integrations and Video live here as tabs. Each
 * tab has its own visibility rule (UX only — the backend re-checks every
 * request). Payments (SET-PAY-01) is also shown to Finance Staff, who can see
 * the PayHere status but no institute configuration.
 * `/settings/branding` redirects to Appearance, so it has no tab of its own.
 */
const SETTINGS_TABS = [
  { label: "General", href: "/tenant-admin/settings/general", visible: canViewInstituteConfig },
  {
    label: "Payments",
    href: "/tenant-admin/settings/payments",
    visible: (role: string | null) => canViewInstituteConfig(role) || canViewPaymentGateway(role),
  },
  { label: "Appearance", href: "/tenant-admin/settings/appearance", visible: canViewInstituteConfig },
  { label: "Student fields", href: "/tenant-admin/settings/student-fields", visible: canViewInstituteConfig },
  // Site files (logos, banners, teacher photos, support video): never in the Media Library.
  { label: "Site media", href: "/tenant-admin/settings/site-media", visible: canViewInstituteConfig },
  // LOGIN-01 Phase B: Terms of Service, Refund Policy, Privacy Policy.
  { label: "Policies", href: "/tenant-admin/settings/policies", visible: canViewInstituteConfig },
  { label: "Integrations", href: "/tenant-admin/settings/integrations", visible: canViewIntegrations },
  // Video providers + video settings share the INTEGRATIONS grant.
  { label: "Video", href: "/tenant-admin/settings/video", visible: canViewIntegrations },
] as const;

export type SettingsTabHref = (typeof SETTINGS_TABS)[number]["href"];

export function SettingsTabStrip({ active }: { active: SettingsTabHref }) {
  const { session } = useAuth();
  const role = session?.role ?? null;
  const tabs = SETTINGS_TABS.filter((tab) => tab.visible(role));
  return (
    <nav aria-label="Settings sections" className="-mx-1 overflow-x-auto">
      <ul className="flex min-w-max gap-1 border-b border-border px-1">
        {tabs.map((tab) => {
          const current = tab.href === active;
          return (
            <li key={tab.href}>
              <Link
                href={tab.href}
                aria-current={current ? "page" : undefined}
                className={cn(
                  "-mb-px inline-flex h-10 items-center border-b-2 px-3 text-sm font-medium transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
                  current
                    ? "border-primary text-foreground"
                    : "border-transparent text-muted-foreground hover:border-border hover:text-foreground"
                )}
              >
                {tab.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

/** Shared "Settings" page heading + tabs, used by every settings tab page. */
export function SettingsHeader({ active, description }: { active: SettingsTabHref; description: string }) {
  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center gap-3">
        <span className="flex size-10 items-center justify-center rounded-lg bg-primary text-primary-foreground">
          <Settings className="size-5" aria-hidden="true" />
        </span>
        <div>
          <h1 className="text-xl font-semibold text-foreground">Settings</h1>
          <p className="text-sm text-muted-foreground">{description}</p>
        </div>
      </div>
      <SettingsTabStrip active={active} />
    </div>
  );
}
