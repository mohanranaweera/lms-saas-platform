import { useQuery } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { CoursePricingModel } from "./courses";

/**
 * Anonymous public home projection — `GET /api/v1/public/tenant-config/home`
 * (tenant resolved server-side from the request host; there is never a
 * tenant id to send). Shape per
 * docs/specs/tenant-appearance-public-home-parity.md §3.
 */

export interface PublicFeaturedCourse {
  id: string;
  name: string;
  slug: string;
  category: string;
  pricingModel: CoursePricingModel | null;
  amount: number | null;
  currency: string | null;
  requiresManualQuote: boolean;
  coverUrl: string | null;
}

export interface PublicHomeTheme {
  primaryColor: string | null;
  accentColor: string | null;
  backgroundColor: string | null;
  surfaceColor: string | null;
  textColor: string | null;
  headingFont: string | null;
  bodyFont: string | null;
}

export interface PublicHomeResponse {
  publicPageEnabled: boolean;
  instituteName: string;
  logoUrl: string | null;
  faviconUrl: string | null;
  homepageUrl: string | null;
  subHeroEnabled: boolean;
  ctaEnabled: boolean;
  supportVideoUrl: string | null;
  desktopBannerUrls: string[];
  mobileBannerUrls: string[];
  featuredCourses: PublicFeaturedCourse[];
  /** Optional on the client so an older backend build degrades to defaults rather than crashing. */
  theme?: PublicHomeTheme | null;
  logoDisplaySize?: "SMALL" | "STANDARD" | "LARGE" | null;
  teacherAutoRotate?: boolean | null;
  supportEmail?: string | null;
  supportPhone?: string | null;
}

/**
 * Keyed by the request host: the tenant is host-resolved, so a browser that
 * moves between tenant subdomains must never reuse another tenant's branding
 * (.claude/rules/ui-ux.md §2).
 */
export const publicHomeKeys = {
  all: ["public-home"] as const,
  host: (host: string) => ["public-home", host] as const,
};

export function getPublicHome(): Promise<PublicHomeResponse> {
  return apiFetch<PublicHomeResponse>("/v1/public/tenant-config/home");
}

function currentHost(): string {
  return typeof window === "undefined" ? "server" : window.location.host;
}

export function usePublicHome() {
  return useQuery({
    queryKey: publicHomeKeys.host(currentHost()),
    queryFn: getPublicHome,
    retry: false,
    // Asset URLs are signed for 60 min server-side; refresh well before that.
    staleTime: 10 * 60_000,
  });
}
