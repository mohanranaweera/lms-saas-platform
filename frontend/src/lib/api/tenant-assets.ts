import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "./error";
import type { TenantAssetType } from "@/lib/branding/asset-limits";

export { TENANT_ASSET_TYPES, TENANT_ASSET_MAX_BYTES, MAX_BANNERS_PER_TYPE } from "@/lib/branding/asset-limits";
export type { TenantAssetType } from "@/lib/branding/asset-limits";

export interface TenantAsset {
  id: string;
  assetType: TenantAssetType;
  contentType: string;
  sizeBytes: number;
  createdAt: string;
  previewUrl: string;
  previewExpiresAt: string | null;
}

export const tenantAssetKeys = {
  all: ["tenant-assets"] as const,
  purpose: (type: TenantAssetType) => [...tenantAssetKeys.all, type] as const,
  list: (type: TenantAssetType) => [...tenantAssetKeys.all, type, "list"] as const,
};

/**
 * `GET /api/v1/tenant-assets/by-type/{type}` — the current singleton asset
 * (logo/favicon/receipt logo). A `404` means "none uploaded yet" and resolves
 * to `null`; any other failure (403 included) is a real error.
 */
export function useTenantAsset(type: TenantAssetType) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: tenantAssetKeys.purpose(type),
    queryFn: async () => {
      try {
        return await authorizedFetch<TenantAsset>("tenant", `/v1/tenant-assets/by-type/${type}`);
      } catch (error) {
        if (isApiClientError(error) && error.status === 404) return null;
        throw error;
      }
    },
    retry: false,
    // Preview URLs are signed and short-lived; refresh before they expire.
    staleTime: 4 * 60_000,
  });
}

/** `GET /api/v1/tenant-assets?type=` — live assets of a banner type, oldest first. */
export function useTenantAssetList(type: TenantAssetType) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: tenantAssetKeys.list(type),
    queryFn: () => authorizedFetch<TenantAsset[]>("tenant", `/v1/tenant-assets?type=${encodeURIComponent(type)}`),
    retry: false,
    staleTime: 4 * 60_000,
  });
}

/** `POST /api/v1/tenant-assets` (multipart). Saves immediately — independent of the config Save button. */
export function useUploadTenantAsset(type: TenantAssetType) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => {
      const body = new FormData();
      body.append("type", type);
      body.append("file", file);
      // `type` travels only as a multipart field: Spring binds `@RequestParam type` from
      // either the query string or the form, and sending both would yield two values.
      return authorizedFetch<TenantAsset>("tenant", "/v1/tenant-assets", { method: "POST", body });
    },
    onSuccess: (asset) => {
      queryClient.setQueryData(tenantAssetKeys.purpose(type), asset);
      void queryClient.invalidateQueries({ queryKey: tenantAssetKeys.list(type) });
      void queryClient.invalidateQueries({ queryKey: ["public-home"] });
    },
  });
}

export function useDeleteTenantAsset() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (asset: Pick<TenantAsset, "id" | "assetType">) =>
      authorizedFetch<null>("tenant", `/v1/tenant-assets/${asset.id}`, { method: "DELETE" }),
    onSuccess: (_data, asset) => {
      queryClient.setQueryData(tenantAssetKeys.purpose(asset.assetType), null);
      void queryClient.invalidateQueries({ queryKey: tenantAssetKeys.list(asset.assetType) });
      void queryClient.invalidateQueries({ queryKey: ["public-home"] });
    },
  });
}
