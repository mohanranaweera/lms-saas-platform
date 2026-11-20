import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * VIDEO Phase 5c (ADR-018 Amendment 5) - the platform's Google OAuth app,
 * used by every institute's YouTube channel. Platform Admin only
 * (`/api/v1/platform-admin/oauth-apps/google`). The client secret is
 * write-only: it is sent on save and never returned.
 */

export type GoogleOAuthAppSource = "SAVED" | "ENVIRONMENT" | "NOT_CONFIGURED";

export interface GoogleOAuthAppChange {
  action: "SAVED" | "REMOVED";
  /** Masked, e.g. `123456…ontent.com`. */
  clientIdBefore: string | null;
  clientIdAfter: string | null;
  secretChanged: boolean;
  occurredAt: string;
}

export interface GoogleOAuthAppView {
  source: GoogleOAuthAppSource;
  /** The effective client id (public - Google shows it in every consent URL). */
  clientId: string | null;
  /** A secret is saved on this screen; its value is never returned. */
  secretSaved: boolean;
  environmentConfigured: boolean;
  /** To register in Google Cloud; null when the platform callback URL isn't configured. */
  redirectUri: string | null;
  /** False = the credential encryption key is missing; nothing can be saved. */
  encryptionAvailable: boolean;
  updatedAt: string | null;
  history: GoogleOAuthAppChange[];
}

export interface GoogleOAuthAppVerify {
  result: "VALID" | "REJECTED" | "INCONCLUSIVE";
  source: GoogleOAuthAppSource;
  message: string;
}

const PATH = "/v1/platform-admin/oauth-apps/google";

export const platformOAuthAppKeys = {
  google: ["platform-admin", "oauth-apps", "google"] as const,
};

export function useGoogleOAuthApp() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: platformOAuthAppKeys.google,
    queryFn: () => authorizedFetch<GoogleOAuthAppView>("platform-admin", PATH),
  });
}

export function useSaveGoogleOAuthApp() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { clientId: string; clientSecret: string }) =>
      authorizedFetch<GoogleOAuthAppView>("platform-admin", PATH, { method: "PUT", body: JSON.stringify(input) }),
    onSuccess: (data) => queryClient.setQueryData(platformOAuthAppKeys.google, data),
  });
}

export function useRemoveGoogleOAuthApp() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => authorizedFetch<GoogleOAuthAppView>("platform-admin", PATH, { method: "DELETE" }),
    onSuccess: (data) => queryClient.setQueryData(platformOAuthAppKeys.google, data),
  });
}

export function useVerifyGoogleOAuthApp() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: () => authorizedFetch<GoogleOAuthAppVerify>("platform-admin", `${PATH}/verify`, { method: "POST" }),
  });
}
