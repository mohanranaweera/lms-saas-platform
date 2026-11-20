"use client";

import { Menu } from "lucide-react";
import { PublicLogo, type LogoDisplaySize } from "@/components/branding/public-logo";
import { PublicThemeScope, type PublicThemeValues } from "@/components/branding/public-theme-scope";

/**
 * Actual-size header previews rendered through the same `PublicThemeScope` +
 * `PublicLogo` the live site uses. Each frame is a `.public-chrome` container,
 * so the 390 px frame shows the real mobile token size at any viewport.
 */
export function LogoSizePreview({
  logoUrl,
  instituteName,
  size,
  theme,
}: {
  logoUrl: string | null;
  instituteName: string;
  size: LogoDisplaySize;
  theme: PublicThemeValues;
}) {
  return (
    <div className="flex flex-col gap-4">
      <figure className="flex flex-col gap-1.5">
        <figcaption className="text-xs font-medium text-muted-foreground">Desktop header (actual size)</figcaption>
        <div className="overflow-x-auto rounded-lg border border-border">
          <PublicThemeScope theme={theme} className="public-chrome min-w-[1024px]" aria-hidden="true">
            <div className="flex h-20 items-center justify-between gap-6 border-b border-border px-6">
              <div className="flex items-center gap-8">
                <PublicLogo logoUrl={logoUrl} instituteName={instituteName} size={size} />
                <span className="flex gap-6 text-sm font-medium">
                  <span>Courses</span>
                  <span>Teachers</span>
                  <span>Support</span>
                </span>
              </div>
              <span className="flex gap-2 text-sm font-medium">
                <span className="rounded-lg border border-primary px-3 py-1.5 text-primary">Login</span>
                <span className="rounded-lg bg-primary px-3 py-1.5 text-primary-foreground">Register</span>
              </span>
            </div>
          </PublicThemeScope>
        </div>
      </figure>
      <figure className="flex flex-col gap-1.5">
        <figcaption className="text-xs font-medium text-muted-foreground">Mobile header (390 px)</figcaption>
        <div className="w-[390px] max-w-full overflow-hidden rounded-lg border border-border">
          <PublicThemeScope theme={theme} className="public-chrome" aria-hidden="true">
            <div className="flex h-16 items-center justify-between gap-3 border-b border-border px-4">
              <PublicLogo logoUrl={logoUrl} instituteName={instituteName} size={size} />
              <Menu className="size-5 shrink-0" />
            </div>
          </PublicThemeScope>
        </div>
      </figure>
    </div>
  );
}
