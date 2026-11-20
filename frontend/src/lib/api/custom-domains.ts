import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * TEN-DOM-01 / ADR-021 — tenant custom domains.
 * Contract: `docs/api/tenant-management.md` (custom domains).
 *
 * The tenant is always the one the backend resolved from the site's address;
 * the backend re-checks `CUSTOM_DOMAINS` (Tenant Admin edit, Auditor view) and
 * the Platform Admin role on every call. These hooks only reflect it.
 */

export type CustomDomainStatus = "PENDING_VERIFICATION" | "ACTIVE" | "DNS_BROKEN" | "SUSPENDED";

export interface DnsRecord {
  type: "CNAME" | "TXT";
  name: string;
  /** Null for the CNAME while the platform has no target configured. */
  value: string | null;
}

/** Mirrors `CustomDomainResponse`. */
export interface CustomDomain {
  id: string;
  hostname: string;
  status: CustomDomainStatus;
  primary: boolean;
  dnsRecords: DnsRecord[];
  verifiedAt: string | null;
  lastCheckedAt: string | null;
  lastError: string | null;
  suspendedReason: string | null;
  createdAt: string;
}

/** Mirrors `CustomDomainsResponse`. */
export interface CustomDomainsOverview {
  /** The Platform Admin switch for this institute. */
  enabled: boolean;
  /** Whether the platform supports custom domains at all. */
  available: boolean;
  maxDomains: number;
  domains: CustomDomain[];
}

export const customDomainKeys = {
  all: ["custom-domains"] as const,
  tenant: () => [...customDomainKeys.all, "tenant"] as const,
  platformTenant: (tenantId: string) => [...customDomainKeys.all, "platform", tenantId] as const,
};

const TENANT_PATH = "/v1/tenant-config/custom-domains";

// ---------------------------------------------------------------------------
// Tenant Admin — own institute only
// ---------------------------------------------------------------------------

export function useCustomDomains() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: customDomainKeys.tenant(),
    queryFn: () => authorizedFetch<CustomDomainsOverview>("tenant", TENANT_PATH),
  });
}

function useTenantDomainMutation<TInput, TResult = CustomDomain>(
  request: (input: TInput) => { path: string; init: RequestInit }
) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TInput) => {
      const { path, init } = request(input);
      return authorizedFetch<TResult>("tenant", path, init);
    },
    onSettled: () => {
      // Verify records its result even when it fails; always refresh.
      void queryClient.invalidateQueries({ queryKey: customDomainKeys.tenant() });
    },
  });
}

export function useAddCustomDomain() {
  return useTenantDomainMutation((hostname: string) => ({
    path: TENANT_PATH,
    init: { method: "POST", body: JSON.stringify({ hostname }) },
  }));
}

export function useVerifyCustomDomain() {
  return useTenantDomainMutation((id: string) => ({
    path: `${TENANT_PATH}/${id}/verify`,
    init: { method: "POST" },
  }));
}

export function useMakePrimaryCustomDomain() {
  return useTenantDomainMutation((id: string) => ({
    path: `${TENANT_PATH}/${id}/primary`,
    init: { method: "POST" },
  }));
}

/** `200` with an empty `data` (the project's DELETE convention); the row is kept as REMOVED. */
export function useRemoveCustomDomain() {
  return useTenantDomainMutation<string, null>((id: string) => ({
    path: `${TENANT_PATH}/${id}`,
    init: { method: "DELETE" },
  }));
}

// ---------------------------------------------------------------------------
// Platform Admin — any institute, by id
// ---------------------------------------------------------------------------

export function usePlatformCustomDomains(tenantId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: customDomainKeys.platformTenant(tenantId),
    queryFn: () =>
      authorizedFetch<CustomDomainsOverview>(
        "platform-admin",
        `/v1/platform-admin/tenants/${tenantId}/custom-domains`
      ),
    enabled: tenantId.length > 0,
  });
}

export function usePlatformSetCustomDomainsEnabled(tenantId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (enabled: boolean) =>
      authorizedFetch<CustomDomainsOverview>(
        "platform-admin",
        `/v1/platform-admin/tenants/${tenantId}/custom-domains-enabled`,
        { method: "PUT", body: JSON.stringify({ enabled }) }
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: customDomainKeys.platformTenant(tenantId) });
    },
  });
}

export function usePlatformSuspendCustomDomain(tenantId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { domainId: string; reason: string }) =>
      authorizedFetch<CustomDomain>(
        "platform-admin",
        `/v1/platform-admin/tenants/${tenantId}/custom-domains/${input.domainId}/suspend`,
        { method: "POST", body: JSON.stringify({ reason: input.reason }) }
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: customDomainKeys.platformTenant(tenantId) });
    },
  });
}
