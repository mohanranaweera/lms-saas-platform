import { useMutation } from "@tanstack/react-query";
import { apiFetch } from "./client";

/**
 * Typed client for `POST /api/v1/tenant-registrations` (public, anonymous — the
 * platform's one legitimate anonymous endpoint). This is the first real
 * data-fetching module in this app; everything before it was a disabled
 * placeholder. Kept intentionally small — a single request function plus a React
 * Query mutation hook — since there's no established pattern yet beyond
 * `apiFetch`/`ApiClientError` themselves.
 */

export interface TenantRegistrationInput {
  name: string;
  subdomain: string;
  requestedPlan: string;
  contactName: string;
  contactEmail: string;
  contactPhone?: string;
}

/** Mirrors the backend's `TenantRegistrationResponse`. `status` is always
 * `"pending_approval"` for a registration created via this endpoint — no other
 * status is reachable here. */
export interface TenantRegistrationResult {
  id: string;
  subdomain: string;
  status: string;
}

export function registerTenant(
  input: TenantRegistrationInput
): Promise<TenantRegistrationResult> {
  return apiFetch<TenantRegistrationResult>("/v1/tenant-registrations", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function useRegisterTenant() {
  return useMutation({
    mutationFn: registerTenant,
  });
}
