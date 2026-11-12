"use client";

import Script from "next/script";
import { usePublicHome } from "@/lib/api/public-home";
import { isImpersonationTab } from "@/lib/auth/impersonation-marker";
import { GA_MEASUREMENT_ID_PATTERN } from "@/lib/validation/tenant-config";

/**
 * SET-CFG-01: loads the tenant's Google Analytics 4 tag (Klass "Google
 * Analytics Measurement ID") on the public site and the student portal only —
 * never on staff/teacher/platform screens, and never in an ADR-033 support
 * (impersonation) tab, so a support session is not counted as student
 * activity. The id comes from the host-resolved public home, so a tenant only
 * ever loads its own tag. gtag tracks App Router navigations itself through
 * history events (GA4 Enhanced Measurement), so no per-route pageview call is
 * sent.
 */
export function TenantAnalytics() {
  const home = usePublicHome();
  const gaId = home.data?.analytics?.gaMeasurementId ?? null;
  if (!gaId || !GA_MEASUREMENT_ID_PATTERN.test(gaId) || isImpersonationTab()) {
    return null;
  }
  return (
    <>
      <Script
        id="tenant-ga-init"
        strategy="afterInteractive"
        dangerouslySetInnerHTML={{
          __html: `window.dataLayer=window.dataLayer||[];function gtag(){dataLayer.push(arguments);}gtag('js',new Date());gtag('config','${gaId}');`,
        }}
      />
      <Script
        id="tenant-ga"
        strategy="afterInteractive"
        src={`https://www.googletagmanager.com/gtag/js?id=${gaId}`}
        data-testid="tenant-ga-script"
      />
    </>
  );
}
