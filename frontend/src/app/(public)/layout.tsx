import { TenantAnalytics } from "@/components/analytics/tenant-analytics";
import { PublicChrome } from "@/components/public/public-chrome";

/**
 * Public route group (`/`, `/courses`, `/teachers`, …). The tenant-themed
 * header/footer and theme scope live in `PublicChrome`; pages render body
 * content only (no page renders its own header/footer). `TenantAnalytics`
 * loads the tenant's GA4 tag when one is configured (SET-CFG-01).
 */
export default function PublicLayout({ children }: { children: React.ReactNode }) {
  return (
    <>
      <TenantAnalytics />
      <PublicChrome>{children}</PublicChrome>
    </>
  );
}
