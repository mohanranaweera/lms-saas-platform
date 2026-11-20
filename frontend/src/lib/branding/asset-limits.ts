/**
 * Tenant branding asset types and limits (pure data, no React) — mirrored
 * from the server contract (docs/specs/tenant-appearance-public-home-parity.md §3)
 * only to fail fast before upload; the backend enforces them regardless.
 */
export const TENANT_ASSET_TYPES = [
  "SITE_LOGO",
  "SITE_FAVICON",
  "RECEIPT_LOGO",
  "DESKTOP_HERO_BANNER",
  "MOBILE_HERO_BANNER",
] as const;
export type TenantAssetType = (typeof TENANT_ASSET_TYPES)[number];

export const TENANT_ASSET_MAX_BYTES: Record<TenantAssetType, number> = {
  SITE_LOGO: 2 * 1024 * 1024,
  SITE_FAVICON: 1 * 1024 * 1024,
  RECEIPT_LOGO: 2 * 1024 * 1024,
  DESKTOP_HERO_BANNER: 2 * 1024 * 1024,
  MOBILE_HERO_BANNER: 2 * 1024 * 1024,
};

/** Live banners allowed per banner type (server returns 422 beyond). */
export const MAX_BANNERS_PER_TYPE = 6;
