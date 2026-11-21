"use client";

import { Store } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { useTenantSiteUrl } from "@/lib/storefront/use-tenant-site-url";
import { cn } from "@/lib/utils";

/**
 * STORE-01: shown for an unknown, disabled or retired storefront host - never
 * the tenant catalog (ADR-037 decision 3). Links to the tenant site (the host
 * minus its first label) with a full navigation, since that is another origin.
 */
export function StorefrontNotFound() {
  const tenantSite = useTenantSiteUrl();
  return (
    <main
      className="flex min-h-svh flex-1 items-center justify-center bg-background px-4 py-16"
      data-testid="storefront-not-found"
    >
      <section
        aria-labelledby="storefront-not-found-heading"
        className="flex w-full max-w-lg flex-col items-center gap-4 rounded-2xl border border-border bg-card p-8 text-center text-card-foreground shadow-sm"
      >
        <span className="flex size-14 items-center justify-center rounded-full bg-muted text-muted-foreground">
          <Store className="size-7" aria-hidden="true" />
        </span>
        <h1 id="storefront-not-found-heading" className="text-2xl font-semibold tracking-tight">
          Storefront not found
        </h1>
        <p className="text-muted-foreground">
          This teacher page doesn&apos;t exist or isn&apos;t available right now. Check the address, or visit the
          institute&apos;s main site.
        </p>
        {tenantSite ? (
          <a href={tenantSite} className={cn(buttonVariants({ size: "lg" }), "mt-2")}>
            Go to the institute site
          </a>
        ) : null}
      </section>
    </main>
  );
}
