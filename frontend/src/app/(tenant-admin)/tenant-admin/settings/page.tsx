import Link from "next/link";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * Institute Configuration index (Wave 1). Links to the two domains with a
 * real screen in this wave — General and Branding — only. No entry for any
 * of the other 15 typed configuration domains
 * (`lib/api/tenant-config.ts#TenantConfigDomain`): nothing is built to
 * configure there yet, per `components/layout/nav/tenant-admin-nav.tsx`'s
 * own scope note.
 *
 * No data fetch here — this is a pure navigation index, so there's no
 * loading/error/permission-denied state to wire (each destination page owns
 * its own `GET /api/v1/tenant-config/{domain}` request and its own
 * `QueryStateBoundary`). Rendering the two links unconditionally, rather
 * than gating them client-side, mirrors the nav's own "hidden link is UX
 * convenience only" framing without needing a redundant client-side role
 * check on a screen that fetches nothing itself.
 */
export default function InstituteConfigurationIndexPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Institute configuration</h1>
        <p className="text-sm text-muted-foreground">
          Tenant-wide settings for your institute. More configuration areas will be added over
          time.
        </p>
      </div>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Link href="/tenant-admin/settings/general" className="block">
          <Card className="h-full transition-colors hover:bg-muted/40">
            <CardHeader>
              <CardTitle>General</CardTitle>
              <CardDescription>
                Institute name, support contact details, default time zone, and default currency.
              </CardDescription>
            </CardHeader>
          </Card>
        </Link>
        <Link href="/tenant-admin/settings/branding" className="block">
          <Card className="h-full transition-colors hover:bg-muted/40">
            <CardHeader>
              <CardTitle>Branding</CardTitle>
              <CardDescription>
                Primary/secondary brand colors and your logo/favicon, with a live preview.
              </CardDescription>
            </CardHeader>
          </Card>
        </Link>
        <Link href="/tenant-admin/settings/integrations" className="block">
          <Card className="h-full transition-colors hover:bg-muted/40">
            <CardHeader>
              <CardTitle>Integrations</CardTitle>
              <CardDescription>
                Teachers&apos; Zoom, YouTube and Vimeo accounts, and your institute&apos;s DynTube and Bunny.net
                video hosting.
              </CardDescription>
            </CardHeader>
          </Card>
        </Link>
        <Link href="/tenant-admin/settings/domains" className="block">
          <Card className="h-full transition-colors hover:bg-muted/40">
            <CardHeader>
              <CardTitle>Custom domain</CardTitle>
              <CardDescription>
                Serve your site on your own address, such as learn.yourinstitute.lk.
              </CardDescription>
            </CardHeader>
          </Card>
        </Link>
        <Link href="/tenant-admin/settings/devices-access" className="block">
          <Card className="h-full transition-colors hover:bg-muted/40">
            <CardHeader>
              <CardTitle>Devices &amp; access</CardTitle>
              <CardDescription>
                Student device limits and resets, the grace period after expiry, and per-course overrides.
              </CardDescription>
            </CardHeader>
          </Card>
        </Link>
        <Link href="/tenant-admin/settings/student-fields" className="block">
          <Card className="h-full transition-colors hover:bg-muted/40">
            <CardHeader>
              <CardTitle>Student fields</CardTitle>
              <CardDescription>
                Which details students provide at registration and on their profile, and which are required.
              </CardDescription>
            </CardHeader>
          </Card>
        </Link>
      </div>
    </div>
  );
}
