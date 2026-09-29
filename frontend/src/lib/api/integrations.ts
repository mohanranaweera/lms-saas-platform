import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { classSessionKeys } from "@/lib/api/class-sessions";

/**
 * MEDIA-INT-01 / ADR-018 — media & meeting provider connections. Contract:
 * `docs/api/integrations.md`.
 *
 * - Teachers connect their OWN Zoom / YouTube / Vimeo accounts through the
 *   provider's OAuth consent page; the backend returns the consent URL and the
 *   browser is sent there with a full-page redirect.
 * - The Tenant Admin configures the tenant-wide DynTube / Bunny.net accounts.
 *
 * Tokens and API keys are write-only: no response ever contains one
 * (`secretConfigured` is the only signal). Every permission/ownership decision
 * is re-made by the backend; these hooks only reflect it.
 */

export type MediaProvider = "ZOOM" | "YOUTUBE" | "VIMEO" | "DYNTUBE" | "BUNNY";
export type TeacherMediaProvider = "ZOOM" | "YOUTUBE" | "VIMEO";
export type ConnectionStatus = "ACTIVE" | "NEEDS_RECONNECT" | "DISCONNECTED";
export type VideoHost = "NONE" | "DYNTUBE" | "BUNNY";

/** Mirrors `ConnectionView`. Never carries a token or key. */
export interface ConnectionView {
  id: string;
  provider: MediaProvider;
  scope: "TEACHER" | "TENANT";
  status: ConnectionStatus;
  accountLabel: string | null;
  externalAccountIdMasked: string | null;
  /** Zoom only: "BASIC" | "LICENSED". */
  accountType: string | null;
  grantedScopes: string[];
  /** Bunny only: `{ libraryId, cdnHostname, tokenAuthConfigured }`. */
  settings: { libraryId?: string; cdnHostname?: string; tokenAuthConfigured?: boolean } | null;
  secretConfigured: boolean;
  connectedAt: string;
  lastVerifiedAt: string | null;
  lastError: string | null;
  disconnectedBy: "OWNER" | "TENANT_ADMIN" | "PROVIDER" | "SYSTEM" | null;
  disconnectedAt: string | null;
  disconnectedReason: string | null;
}

export interface ConnectionTestResult {
  ok: boolean;
  status: ConnectionStatus;
  lastVerifiedAt: string | null;
  message: string;
}

export interface TeacherProviderView {
  provider: TeacherMediaProvider;
  enabledByTenant: boolean;
  /** Platform OAuth app + encryption key configured. */
  available: boolean;
  /** Current connection, else the latest disconnected one, else null. */
  connection: ConnectionView | null;
}

export interface TeacherIntegrationsView {
  providers: TeacherProviderView[];
}

export interface ProviderToggleView {
  enabled: boolean;
  available: boolean;
}

export interface TeacherProvidersView {
  zoom: ProviderToggleView;
  youtube: ProviderToggleView;
  vimeo: ProviderToggleView;
}

export interface TeacherConnectionRow {
  teacherId: string;
  teacherName: string;
  connection: ConnectionView;
}

export interface TenantIntegrationsView {
  teacherProviders: TeacherProvidersView;
  videoHost: VideoHost;
  dyntube: ConnectionView | null;
  bunny: ConnectionView | null;
  teacherConnections: TeacherConnectionRow[];
}

export interface SaveBunnyInput {
  apiKey?: string;
  libraryId: string;
  cdnHostname: string;
  tokenAuthKey?: string;
}

/** Zoom needs a licensed account to host live classes with personal join links (ADR-018 §4). */
export function isZoomHostReady(connection: ConnectionView | null | undefined): boolean {
  return connection?.status === "ACTIVE" && connection.accountType === "LICENSED";
}

export const PROVIDER_LABELS: Record<MediaProvider, string> = {
  ZOOM: "Zoom",
  YOUTUBE: "YouTube",
  VIMEO: "Vimeo",
  DYNTUBE: "DynTube",
  BUNNY: "Bunny.net",
};

export const integrationKeys = {
  all: ["integrations"] as const,
  teacher: () => [...integrationKeys.all, "teacher"] as const,
  admin: () => [...integrationKeys.all, "admin"] as const,
};

function blankToNull(value: string | undefined): string | null {
  return value && value.trim() ? value.trim() : null;
}

// ---------------------------------------------------------------------------
// Teacher — own connections
// ---------------------------------------------------------------------------

export function useTeacherIntegrations(options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: integrationKeys.teacher(),
    queryFn: () => authorizedFetch<TeacherIntegrationsView>("tenant", "/v1/teacher/integrations"),
    enabled: options?.enabled ?? true,
  });
}

/** Returns the provider's consent URL; the caller performs a full-page redirect to it. */
export function useBeginConnect() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (provider: TeacherMediaProvider) =>
      authorizedFetch<{ authorizationUrl: string }>(
        "tenant",
        `/v1/teacher/integrations/${provider.toLowerCase()}/connect`,
        { method: "POST" }
      ),
  });
}

/**
 * Saves a finished provider authorization. The backend saves it only for the
 * signed-in teacher who started it (security review S1); anyone else gets a
 * 404 "expired" and the grant is revoked.
 */
export function useConfirmPendingConnection() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (pendingId: string) =>
      authorizedFetch<ConnectionView>(
        "tenant",
        `/v1/teacher/integrations/pending/${encodeURIComponent(pendingId)}/confirm`,
        { method: "POST" }
      ),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: integrationKeys.teacher() });
    },
  });
}

export function useTestTeacherConnection() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (connectionId: string) =>
      authorizedFetch<ConnectionTestResult>("tenant", `/v1/teacher/integrations/connections/${connectionId}/test`, {
        method: "POST",
      }),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: integrationKeys.teacher() });
    },
  });
}

export function useTeacherConnectionImpact() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (connectionId: string) =>
      authorizedFetch<{ upcomingSessionCount: number }>(
        "tenant",
        `/v1/teacher/integrations/connections/${connectionId}/impact`
      ),
  });
}

export function useDisconnectTeacherConnection() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (connectionId: string) =>
      authorizedFetch<ConnectionView>("tenant", `/v1/teacher/integrations/connections/${connectionId}/disconnect`, {
        method: "POST",
        body: JSON.stringify({ reason: null }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: integrationKeys.teacher() });
      void queryClient.invalidateQueries({ queryKey: classSessionKeys.all });
    },
  });
}

// ---------------------------------------------------------------------------
// Tenant Admin (INTEGRATIONS permission, re-checked by the backend)
// ---------------------------------------------------------------------------

export function useTenantIntegrations() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: integrationKeys.admin(),
    queryFn: () => authorizedFetch<TenantIntegrationsView>("tenant", "/v1/tenant-admin/integrations"),
  });
}

function useAdminMutation<TInput, TResult>(request: (input: TInput) => [string, RequestInit]) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TInput) => {
      const [path, init] = request(input);
      return authorizedFetch<TResult>("tenant", path, init);
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: integrationKeys.admin() });
    },
  });
}

export function useUpdateTeacherProviders() {
  return useAdminMutation<{ zoomEnabled: boolean; youtubeEnabled: boolean; vimeoEnabled: boolean }, TeacherProvidersView>(
    (input) => ["/v1/tenant-admin/integrations/teacher-providers", { method: "PUT", body: JSON.stringify(input) }]
  );
}

export function useSaveDynTube() {
  return useAdminMutation<{ apiKey?: string }, ConnectionView>((input) => [
    "/v1/tenant-admin/integrations/dyntube",
    { method: "PUT", body: JSON.stringify({ apiKey: blankToNull(input.apiKey) }) },
  ]);
}

export function useSaveBunny() {
  return useAdminMutation<SaveBunnyInput, ConnectionView>((input) => [
    "/v1/tenant-admin/integrations/bunny",
    {
      method: "PUT",
      body: JSON.stringify({
        apiKey: blankToNull(input.apiKey),
        libraryId: input.libraryId.trim(),
        cdnHostname: input.cdnHostname.trim().toLowerCase(),
        tokenAuthKey: blankToNull(input.tokenAuthKey),
      }),
    },
  ]);
}

export function useAdminTestConnection() {
  return useAdminMutation<string, ConnectionTestResult>((connectionId) => [
    `/v1/tenant-admin/integrations/connections/${connectionId}/test`,
    { method: "POST" },
  ]);
}

export function useAdminDisconnect() {
  return useAdminMutation<{ connectionId: string; reason?: string }, ConnectionView>((input) => [
    `/v1/tenant-admin/integrations/connections/${input.connectionId}/disconnect`,
    { method: "POST", body: JSON.stringify({ reason: blankToNull(input.reason) }) },
  ]);
}

export function useSetVideoHost() {
  return useAdminMutation<VideoHost, { videoHost: VideoHost }>((videoHost) => [
    "/v1/tenant-admin/integrations/video-host",
    { method: "PUT", body: JSON.stringify({ videoHost }) },
  ]);
}
