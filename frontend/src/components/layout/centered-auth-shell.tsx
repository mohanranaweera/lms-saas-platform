"use client";

import Link from "next/link";
import type { ReactNode } from "react";
import { PublicLogo } from "@/components/branding/public-logo";
import { PublicThemeScope } from "@/components/branding/public-theme-scope";
import { PolicyConsent } from "@/components/public/policy-consent";
import { ThemeToggle } from "@/components/theme/theme-toggle";
import { Skeleton } from "@/components/ui/skeleton";
import { usePublicHome } from "@/lib/api/public-home";
import { PLATFORM_DEFAULT_NAME, TENANT_FALLBACK_NAME } from "@/lib/branding/names";

interface CenteredAuthShellProps {
  /**
   * Platform-branded text above the card (Platform Admin login). Ignored when
   * `tenantBranding` is set.
   */
  homeLabel?: string;
  /**
   * LOGIN-01 Phase A: brand the page with the host tenant's logo and public
   * theme (from `GET /v1/public/tenant-config/home`, tenant resolved from the
   * host) instead of platform text.
   */
  tenantBranding?: boolean;
  children: ReactNode;
}

/**
 * Shared centered-card chrome for chrome-less auth surfaces (tenant login,
 * register, forgot/reset password, account setup, Platform Admin login) —
 * mobile-first, single column, per .claude/rules/ui-ux.md §5. A light/dark
 * switch sits top-right on every variant.
 */
export function CenteredAuthShell({ homeLabel = PLATFORM_DEFAULT_NAME, tenantBranding = false, children }: CenteredAuthShellProps) {
  if (tenantBranding) {
    return <TenantAuthShell>{children}</TenantAuthShell>;
  }
  return (
    <ShellFrame
      brand={
        <Link href="/" className="text-lg font-semibold text-foreground">
          {homeLabel}
        </Link>
      }
    >
      {children}
    </ShellFrame>
  );
}

function ShellFrame({ brand, footer, children }: { brand: ReactNode; footer?: ReactNode; children: ReactNode }) {
  return (
    <div className="relative flex min-h-svh flex-col items-center justify-center gap-8 bg-muted/30 px-4 py-12">
      <div className="absolute right-3 top-3 sm:right-5 sm:top-5">
        <ThemeToggle />
      </div>
      {brand}
      <div className="flex w-full max-w-sm flex-col gap-4">
        {children}
        {footer}
      </div>
    </div>
  );
}

/**
 * Tenant variant. While the institute is loading a neutral skeleton is shown
 * (never another tenant's or the platform's branding); if it can't be
 * resolved, neutral platform text is used (.claude/rules/ui-ux.md §2).
 */
function TenantAuthShell({ children }: { children: ReactNode }) {
  const home = usePublicHome();

  let brand: ReactNode;
  if (home.isPending) {
    brand = <Skeleton className="h-14 w-44 rounded-lg" aria-label="Loading institute" data-testid="auth-brand-loading" />;
  } else if (home.isError || !home.data) {
    brand = (
      <Link href="/" className="text-lg font-semibold text-foreground" data-testid="auth-brand-fallback">
        {PLATFORM_DEFAULT_NAME}
      </Link>
    );
  } else {
    const name = home.data.instituteName?.trim() || TENANT_FALLBACK_NAME;
    brand = (
      <Link
        href="/"
        aria-label={`${name} home`}
        // `public-chrome` is an inline-size container (logo size tokens), so it
        // needs an explicit width — it can't size to its content.
        className="public-chrome flex w-full max-w-sm justify-center rounded-lg focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        data-testid="auth-brand"
      >
        <PublicLogo logoUrl={home.data.logoUrl} instituteName={name} size="LARGE" />
      </Link>
    );
  }

  return (
    <PublicThemeScope theme={home.data?.theme}>
      <ShellFrame brand={brand} footer={<PolicyConsent />}>
        {children}
      </ShellFrame>
    </PublicThemeScope>
  );
}
