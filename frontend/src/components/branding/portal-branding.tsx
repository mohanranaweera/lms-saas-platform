"use client";

import type { ComponentProps } from "react";
import { Mail, Phone } from "lucide-react";
import { DashboardShell } from "@/components/layout/dashboard-shell";
import { Skeleton } from "@/components/ui/skeleton";
import { PublicLogo, toLogoDisplaySize } from "@/components/branding/public-logo";
import { TENANT_FALLBACK_NAME } from "@/lib/branding/names";
import { usePublicHome, type PublicHomeResponse } from "@/lib/api/public-home";
import { useTenantDocumentTitle, useTenantFavicon } from "@/lib/branding/use-tenant-head";

/**
 * Tenant branding for the signed-in Student and Teacher portals: the same
 * logo (and logo size), favicon and contact footer as the public site, read
 * from the host-scoped public home projection (cached per host). Theme colors
 * and fonts stay platform defaults inside portals. If the tenant can't be
 * resolved, the portal keeps its neutral label — never another tenant's
 * branding (.claude/rules/ui-ux.md §2).
 */
export function usePortalBranding(portalLabel: string) {
  const home = usePublicHome();
  const data = home.data;
  useTenantFavicon(data?.faviconUrl);
  useTenantDocumentTitle(() => {
    const name = data?.instituteName?.trim();
    return name ? `${portalLabel} | ${name}` : null;
  });
  return { home: data, pending: home.isPending };
}

function instituteName(home: PublicHomeResponse) {
  return home.instituteName?.trim() || TENANT_FALLBACK_NAME;
}

/** Sidebar / drawer brand block: tenant logo with the portal scope label beneath it. */
export function PortalBrand({
  home,
  pending,
  portalLabel,
  compact = false,
}: {
  home: PublicHomeResponse | undefined;
  pending: boolean;
  portalLabel: string;
  /** Mobile header: smaller logo, no label (the label is shown in the drawer). */
  compact?: boolean;
}) {
  if (pending) {
    return <Skeleton className={compact ? "h-8 w-28" : "h-11 w-40"} aria-label="Loading institute" />;
  }
  if (!home) {
    return <span className="text-sm font-semibold text-foreground">{portalLabel}</span>;
  }
  return (
    <span className="flex min-w-0 flex-col gap-1.5" data-testid={compact ? "portal-brand-compact" : "portal-brand"}>
      <PublicLogo
        logoUrl={home.logoUrl}
        instituteName={instituteName(home)}
        size={compact ? "SMALL" : toLogoDisplaySize(home.logoDisplaySize)}
      />
      {compact ? null : (
        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">{portalLabel}</span>
      )}
    </span>
  );
}

/** Compact portal footer: institute name, public support contacts and copyright. */
export function PortalFooter({ home }: { home: PublicHomeResponse | undefined }) {
  if (!home) return null;
  const name = instituteName(home);
  const phone = home.supportPhone?.trim();
  const email = home.supportEmail?.trim();
  return (
    <footer
      className="flex flex-col gap-3 border-t border-border px-4 py-4 text-sm text-muted-foreground sm:flex-row sm:items-center sm:justify-between md:px-6"
      data-testid="portal-footer"
    >
      <p>
        &copy; {new Date().getFullYear()} {name}. All rights reserved.
      </p>
      {phone || email ? (
        <ul className="flex flex-wrap items-center gap-x-5 gap-y-2" aria-label="Contact us">
          {phone ? (
            <li>
              <a href={`tel:${phone.replace(/[^\d+]/g, "")}`} className="inline-flex items-center gap-1.5 hover:text-foreground">
                <Phone className="size-3.5" aria-hidden="true" />
                {phone}
              </a>
            </li>
          ) : null}
          {email ? (
            <li className="min-w-0">
              <a href={`mailto:${email}`} className="inline-flex min-w-0 items-center gap-1.5 break-all hover:text-foreground">
                <Mail className="size-3.5 shrink-0" aria-hidden="true" />
                {email}
              </a>
            </li>
          ) : null}
        </ul>
      ) : null}
    </footer>
  );
}

/** `DashboardShell` with the tenant's logo, favicon, tab title and footer (Student and Teacher portals). */
export function BrandedDashboardShell(props: Omit<ComponentProps<typeof DashboardShell>, "brand" | "mobileBrand" | "footer">) {
  const { home, pending } = usePortalBranding(props.portalLabel);
  return (
    <DashboardShell
      {...props}
      brand={<PortalBrand home={home} pending={pending} portalLabel={props.portalLabel} />}
      mobileBrand={<PortalBrand home={home} pending={pending} portalLabel={props.portalLabel} compact />}
      footer={<PortalFooter home={home} />}
    />
  );
}
