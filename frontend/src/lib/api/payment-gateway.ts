import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * PAY-GW-01 / ADR-017 — a tenant's own online payment account (PayHere).
 *
 * The merchant secret is write-only: it is sent once on save and never comes
 * back in any response (`secretConfigured` is the only signal). Every
 * permission decision is re-made by the backend (`PAYMENT_GATEWAY` domain
 * area); these hooks only reflect it.
 */

export type GatewayMode = "SANDBOX" | "LIVE";

/** Mirrors `PaymentGatewayAccountView`. */
export interface PaymentGatewayAccount {
  id: string;
  provider: "PAYHERE";
  mode: GatewayMode;
  merchantIdMasked: string;
  status: "ACTIVE" | "DISABLED";
  secretConfigured: boolean;
  disabledBy: "TENANT" | "PLATFORM" | null;
  disabledReason: string | null;
  disabledAt: string | null;
  updatedAt: string;
  /**
   * `true` only when the account is ACTIVE and the platform's PayHere setup
   * (public notify URL, return URLs, encryption key) is complete, i.e.
   * students actually see "Pay online".
   */
  checkoutReady: boolean;
}

/** Mirrors `CheckoutPaymentOptionsResponse`. `onlinePayment` is `null` = bank-slip only. */
export interface CheckoutPaymentOptions {
  bankSlip: boolean;
  onlinePayment: { provider: "PAYHERE"; mode: GatewayMode } | null;
}

export interface SavePayHereAccountInput {
  merchantId: string;
  /** Omit/blank to keep the stored secret (required on first save). */
  merchantSecret?: string;
  mode: GatewayMode;
}

export const paymentGatewayKeys = {
  all: ["payment-gateway"] as const,
  tenantAccounts: () => [...paymentGatewayKeys.all, "tenant-accounts"] as const,
  checkoutOptions: () => [...paymentGatewayKeys.all, "checkout-options"] as const,
  platformTenant: (tenantId: string) => [...paymentGatewayKeys.all, "platform", tenantId] as const,
};

// ---------------------------------------------------------------------------
// Tenant Admin — own tenant only (tenant resolved server-side)
// ---------------------------------------------------------------------------

export function useTenantGatewayAccounts() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: paymentGatewayKeys.tenantAccounts(),
    queryFn: () => authorizedFetch<PaymentGatewayAccount[]>("tenant", "/v1/payment-gateway-accounts"),
  });
}

export function useSavePayHereAccount() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: SavePayHereAccountInput) =>
      authorizedFetch<PaymentGatewayAccount>("tenant", "/v1/payment-gateway-accounts/payhere", {
        method: "PUT",
        body: JSON.stringify({
          merchantId: input.merchantId,
          merchantSecret: input.merchantSecret?.trim() ? input.merchantSecret : null,
          mode: input.mode,
        }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: paymentGatewayKeys.all });
    },
  });
}

export function useDisablePayHereAccount() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reason: string | undefined) =>
      authorizedFetch<PaymentGatewayAccount>("tenant", "/v1/payment-gateway-accounts/payhere/disable", {
        method: "POST",
        body: JSON.stringify({ reason: reason?.trim() || null }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: paymentGatewayKeys.all });
    },
  });
}

// ---------------------------------------------------------------------------
// Student checkout
// ---------------------------------------------------------------------------

export function useCheckoutPaymentOptions() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: paymentGatewayKeys.checkoutOptions(),
    queryFn: () => authorizedFetch<CheckoutPaymentOptions>("tenant", "/v1/checkout/payment-options"),
  });
}

// ---------------------------------------------------------------------------
// Platform Admin — any tenant, by id
// ---------------------------------------------------------------------------

export function usePlatformTenantGateway(tenantId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: paymentGatewayKeys.platformTenant(tenantId),
    queryFn: () =>
      authorizedFetch<PaymentGatewayAccount[]>(
        "platform-admin",
        `/v1/platform-admin/tenants/${tenantId}/payment-gateway`
      ),
    enabled: tenantId.length > 0,
  });
}

export function usePlatformDisableTenantGateway(tenantId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (reason: string) =>
      authorizedFetch<PaymentGatewayAccount>(
        "platform-admin",
        `/v1/platform-admin/tenants/${tenantId}/payment-gateway/disable`,
        { method: "POST", body: JSON.stringify({ reason }) }
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: paymentGatewayKeys.platformTenant(tenantId) });
    },
  });
}

export function usePlatformEnableTenantGateway(tenantId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<PaymentGatewayAccount>(
        "platform-admin",
        `/v1/platform-admin/tenants/${tenantId}/payment-gateway/enable`,
        { method: "POST" }
      ),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: paymentGatewayKeys.platformTenant(tenantId) });
    },
  });
}
