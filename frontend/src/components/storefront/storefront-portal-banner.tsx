"use client";

import { Store } from "lucide-react";
import { useCurrentStorefront } from "@/lib/api/site-context";
import { useTenantSiteUrl } from "@/lib/storefront/use-tenant-site-url";

/**
 * ADR-037 decision 9 (amended): on a teacher storefront host the student
 * portal's course lists show only that teacher's courses (filtered by the
 * backend). This notice says so and links to the institute site, where every
 * course is listed. Renders nothing on a tenant host. Plain English for now
 * (I18N-01 paused).
 */
export function StorefrontPortalBanner() {
  const storefront = useCurrentStorefront();
  const allCourses = useTenantSiteUrl("/student/courses");
  if (!storefront) return null;

  return (
    <div
      role="status"
      data-testid="storefront-portal-banner"
      className="mb-4 flex flex-col gap-2 rounded-md border border-border bg-muted/40 px-3 py-3 text-sm text-foreground sm:flex-row sm:items-center sm:justify-between"
    >
      <div className="flex items-start gap-3">
        <Store className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
        <p>Showing your courses from {storefront.displayName}&apos;s page.</p>
      </div>
      {allCourses ? (
        // Another origin (the tenant site): a full navigation, not a client-side Link.
        <a href={allCourses} className="shrink-0 font-medium text-foreground underline underline-offset-4">
          See all your courses
        </a>
      ) : null}
    </div>
  );
}
