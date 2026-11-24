"use client";

import { useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { apiFetch } from "./client";

/**
 * INST-QR-01 — Institute QR / connection link.
 * Contract: `docs/api/institute-connection.md`.
 *
 * The QR PNG is rendered by the backend (ZXing, quiet zone included) from the
 * same canonical `connectionUrl` it returns, so both admin placements show an
 * identical code and no URL ever leaves for an external QR service. The
 * backend resolves the tenant (Tenant Admin) or re-checks the Platform Admin
 * role on every call; these hooks only reflect it.
 */

export interface MobileApps {
  /** Our student app's store listing; `null` until a release is published. */
  androidUrl: string | null;
  iosUrl: string | null;
}

/** Mirrors `InstituteConnectionService.InstituteConnection`. */
export interface InstituteConnection {
  tenantId: string;
  instituteName: string;
  logoUrl: string | null;
  connectionCode: string;
  connectionUrl: string;
  /** PNG of exactly `connectionUrl`, base64 (no data: prefix). */
  qrPngBase64: string;
  /** False while the institute is pending, suspended, etc. — the link won't resolve then. */
  connectable: boolean;
  mobileApps: MobileApps;
}

/** Mirrors `InstituteConnectionService.PublicInstituteConnection` — public, allowlisted. */
export interface PublicInstituteConnection {
  version: number;
  connectionCode: string;
  connectionUrl: string;
  instituteName: string;
  logoUrl: string | null;
  siteOrigin: string | null;
  signInUrl: string | null;
  mobileApps: MobileApps;
}

export const instituteConnectionKeys = {
  all: ["institute-connection"] as const,
  tenant: () => [...instituteConnectionKeys.all, "tenant"] as const,
  platformTenant: (tenantId: string) => [...instituteConnectionKeys.all, "platform", tenantId] as const,
  publicCode: (code: string) => [...instituteConnectionKeys.all, "public", code] as const,
};

/** Tenant Admin: the signed-in institute's own QR (no tenant parameter exists). */
export function useInstituteConnection(options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: instituteConnectionKeys.tenant(),
    queryFn: () => authorizedFetch<InstituteConnection>("tenant", "/v1/tenant-config/institute-connection"),
    enabled: options?.enabled ?? true,
    staleTime: 5 * 60_000,
  });
}

/**
 * Platform Admin: the selected institute's QR. Keyed by `tenantId`, and the
 * result is rejected unless it echoes the same `tenantId`, so a QR for a
 * previously selected tenant can never be shown under another tenant's name.
 */
export function usePlatformInstituteConnection(tenantId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: instituteConnectionKeys.platformTenant(tenantId),
    queryFn: async () => {
      const data = await authorizedFetch<InstituteConnection>(
        "platform-admin",
        `/v1/platform-admin/tenants/${encodeURIComponent(tenantId)}/institute-connection`
      );
      if (data.tenantId !== tenantId) {
        throw new Error("The institute QR returned doesn't match the selected institute.");
      }
      return data;
    },
    staleTime: 5 * 60_000,
  });
}

/** Anonymous resolver for the `/connect/v1/{code}` web fallback page. */
export function usePublicInstituteConnection(code: string) {
  return useQuery({
    queryKey: instituteConnectionKeys.publicCode(code),
    queryFn: () =>
      apiFetch<PublicInstituteConnection>(`/v1/public/institute-connections/${encodeURIComponent(code)}`),
    retry: false,
  });
}
