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

/**
 * SET-PAY-01: institute public information (Settings → General). Every field
 * is `null` when unset. `introduction` is plain text (render as text, never
 * HTML); the social links are backend-validated `https` URLs on
 * facebook.com/fb.com and youtube.com/youtu.be.
 */
export interface PublicInstituteInfo {
  introduction: string | null;
  addressLine1: string | null;
  addressLine2: string | null;
  city: string | null;
  postalCode: string | null;
  facebookUrl: string | null;
  youtubeUrl: string | null;
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
  institute?: PublicInstituteInfo | null;
  /** LOGIN-01 Phase B: which institute policies are published (text via `usePublicPolicy`). */
  policies?: PublicPolicyFlags | null;
  /** ADR-031: whether the login page offers the MOBILE tab. */
  loginOptions?: { mobileEnabled: boolean } | null;
}

export interface PublicPolicyFlags {
  termsOfService: boolean;
  refundPolicy: boolean;
  privacyPolicy: boolean;
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

// ---------------------------------------------------------------------------
// LOGIN-01 Phase B: institute policies
// ---------------------------------------------------------------------------

export type PolicyKind = "terms" | "refund" | "privacy";

/** Route, title and published-flag key for each policy kind. */
export const POLICY_PAGES: Record<PolicyKind, { href: string; title: string; flag: keyof PublicPolicyFlags }> = {
  terms: { href: "/terms", title: "Terms of Service", flag: "termsOfService" },
  refund: { href: "/refund-policy", title: "Refund Policy", flag: "refundPolicy" },
  privacy: { href: "/privacy", title: "Privacy Policy", flag: "privacyPolicy" },
};

/** `GET /api/v1/public/tenant-config/policies/{kind}` — `text` is plain text, `null` when unpublished. */
export interface PublicPolicy {
  kind: PolicyKind;
  text: string | null;
}

export function usePublicPolicy(kind: PolicyKind) {
  return useQuery({
    queryKey: [...publicHomeKeys.host(currentHost()), "policy", kind] as const,
    queryFn: () => apiFetch<PublicPolicy>(`/v1/public/tenant-config/policies/${kind}`),
    retry: false,
  });
}
