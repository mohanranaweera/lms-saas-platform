import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { ConnectionTestResult, ConnectionView } from "@/lib/api/integrations";

/**
 * VIDEO Phase 3 - the institute's own video provider accounts and its
 * tenant-wide video settings. Contracts: `docs/api/integrations.md` (Tenant
 * Admin: video providers) and `docs/api/video-access-management.md` (Tenant
 * video configuration).
 *
 * The institute's YouTube channel is connected through Google's consent page
 * (a full-page redirect); the backend parks the result and the SAME admin
 * previews and confirms it here. No token ever reaches the browser. Every
 * permission decision is re-made by the backend.
 */

export type VideoProviderAccountStatus = "NOT_CONNECTED" | "CONNECTED" | "EXPIRED" | "ERROR";

/** VIDEO Phase 5d: one named YouTube integration (Klass-style card) and its channel. */
export interface VideoProviderAccountView {
  integrationId: string;
  name: string;
  description: string | null;
  /** No allowed-teacher list = every teacher may upload. */
  allTeachersAllowed: boolean;
  allowedTeachers: ChannelTeacher[];
  provider: "YOUTUBE";
  /** Platform Google OAuth app + credential key configured. */
  available: boolean;
  status: VideoProviderAccountStatus;
  /** Current connection, else the latest disconnected one, else null. */
  connection: ConnectionView | null;
  connectedByUserId: string | null;
  /** VIDEO Phase 4 (TD-008): a Platform Admin switched the channel off; only they can switch it back on. */
  platformDisabled: boolean;
  platformDisabledReason: string | null;
  /** VIDEO Phase 5: false for a channel connected before editing was allowed - reconnect to edit videos. */
  canEditVideos: boolean;
}

export interface PendingChannelView {
  /** VIDEO Phase 5d: the card being connected. */
  integrationId: string;
  integrationName: string;
  channelId: string;
  channelTitle: string | null;
  replacesCurrentChannel: boolean;
  currentChannelTitle: string | null;
}

export type MediaAssetProvider = "INTERNAL" | "YOUTUBE";
export type DefaultPrivacyStatus = "UNLISTED" | "PRIVATE";
export type WatchTimePolicy = "NONE" | "VIDEO_DURATION_MULTIPLIER" | "FIXED_MINUTES";
export type WatermarkField = "STUDENT_DISPLAY_ID" | "MASKED_EMAIL" | "MASKED_PHONE" | "SESSION_ID" | "TIMESTAMP";

/** Mirrors `TenantVideoSettings`; also the `PUT` body. */
export interface TenantVideoSettings {
  defaultProvider: MediaAssetProvider;
  defaultPrivacyStatus: DefaultPrivacyStatus;
  uploadsEnabled: boolean;
  nativePlayerRequired: boolean;
  watermarkEnabled: boolean;
  watermarkOpacity: number;
  watermarkMovementIntervalSeconds: number;
  watermarkFields: WatermarkField[];
  captureProtectionEnabled: boolean;
  captureProtectionRequired: boolean;
  maxConcurrentSessions: number;
  watchTimePolicy: WatchTimePolicy;
  watchTimeMultiplier: number | null;
  watchTimeFixedMinutes: number | null;
  launchCodeTtlSeconds: number;
  heartbeatIntervalSeconds: number;
}

export interface TenantVideoConfigurationView {
  settings: TenantVideoSettings;
  /** false = the platform defaults are in effect (never saved). */
  customized: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
}

/** VIDEO Phase 5b - a teacher by USER id (not the `/v1/teachers` profile id). */
export interface ChannelTeacher {
  userId: string;
  name: string | null;
  email: string | null;
}

/** VIDEO Phase 5d: create / edit an integration card. Empty `allowedTeacherIds` = every teacher. */
export interface VideoIntegrationInput {
  name: string;
  description: string | null;
  allowedTeacherIds: string[];
}

/** VIDEO Phase 5c: which Google app this institute's YouTube channel uses. */
export type TenantGoogleAppSource = "TENANT" | "PLATFORM" | "NOT_CONFIGURED";

export interface TenantGoogleAppView {
  source: TenantGoogleAppSource;
  /** The institute's own client id; null when it uses the platform app. */
  clientId: string | null;
  /** The institute's own secret is saved; its value is never returned. */
  secretSaved: boolean;
  platformAppAvailable: boolean;
  /** To register in the institute's Google Cloud project; null when the platform callback URL isn't set. */
  redirectUri: string | null;
  encryptionAvailable: boolean;
  updatedAt: string | null;
  /** A live channel exists - changing the app means connecting it again. */
  channelConnected: boolean;
}

export interface TenantGoogleAppVerify {
  result: "VALID" | "REJECTED" | "INCONCLUSIVE";
  source: TenantGoogleAppSource;
  message: string;
}

export const videoSettingsKeys = {
  all: ["video-settings"] as const,
  providers: () => [...videoSettingsKeys.all, "providers"] as const,
  teachers: () => [...videoSettingsKeys.all, "teachers"] as const,
  googleApp: () => [...videoSettingsKeys.all, "google-app"] as const,
  pending: (pendingId: string) => [...videoSettingsKeys.all, "pending", pendingId] as const,
  configuration: () => [...videoSettingsKeys.all, "configuration"] as const,
};

const BASE = "/v1/tenant-admin/video-providers";

export function useTenantVideoProviders() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: videoSettingsKeys.providers(),
    queryFn: () => authorizedFetch<VideoProviderAccountView[]>("tenant", BASE),
  });
}

/** Starts connecting (or reconnecting) one card's channel - VIDEO Phase 5d. */
export function useBeginTenantYouTubeConnect() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (integrationId: string) =>
      authorizedFetch<{ authorizationUrl: string }>(
        "tenant",
        `${BASE}/youtube/integrations/${encodeURIComponent(integrationId)}/connect`,
        { method: "POST" }
      ),
  });
}

/** VIDEO Phase 5d: the institute's teachers by user id - choices for an integration's allowed teachers. */
export function useIntegrationTeacherChoices(enabled = true) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: videoSettingsKeys.teachers(),
    queryFn: () => authorizedFetch<ChannelTeacher[]>("tenant", `${BASE}/youtube/teachers`),
    enabled,
  });
}

function useIntegrationMutation<TInput, TResult>(request: (input: TInput) => [string, RequestInit]) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TInput) => {
      const [path, init] = request(input);
      return authorizedFetch<TResult>("tenant", path, init);
    },
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: videoSettingsKeys.providers() });
      // The Media Library tabs follow the integrations.
      void queryClient.invalidateQueries({ queryKey: ["media"] });
    },
  });
}

export function useCreateVideoIntegration() {
  return useIntegrationMutation<VideoIntegrationInput, VideoProviderAccountView>((input) => [
    `${BASE}/youtube/integrations`,
    { method: "POST", body: JSON.stringify(input) },
  ]);
}

export function useUpdateVideoIntegration() {
  return useIntegrationMutation<VideoIntegrationInput & { integrationId: string }, VideoProviderAccountView>(
    ({ integrationId, ...input }) => [
      `${BASE}/youtube/integrations/${encodeURIComponent(integrationId)}`,
      { method: "PUT", body: JSON.stringify(input) },
    ]
  );
}

/** Disconnects the card's channel and archives the card; its videos stay (Media Library → Archived). */
export function useDeleteVideoIntegration() {
  return useIntegrationMutation<string, null>((integrationId) => [
    `${BASE}/youtube/integrations/${encodeURIComponent(integrationId)}`,
    { method: "DELETE" },
  ]);
}

/** Non-consuming preview of a parked authorization (only for the admin who started it). */
export function usePendingYouTubeChannel(pendingId: string | null) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: videoSettingsKeys.pending(pendingId ?? ""),
    queryFn: () =>
      authorizedFetch<PendingChannelView>(
        "tenant",
        `${BASE}/youtube/pending/${encodeURIComponent(pendingId ?? "")}`
      ),
    enabled: Boolean(pendingId),
    retry: false,
  });
}

export function useConfirmTenantYouTubeChannel() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { pendingId: string; replaceChannel: boolean }) =>
      authorizedFetch<VideoProviderAccountView>(
        "tenant",
        `${BASE}/youtube/pending/${encodeURIComponent(input.pendingId)}/confirm`,
        { method: "POST", body: JSON.stringify({ replaceChannel: input.replaceChannel }) }
      ),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: videoSettingsKeys.providers() });
    },
  });
}

export function useTestVideoProvider() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (connectionId: string) =>
      authorizedFetch<ConnectionTestResult>("tenant", `${BASE}/${encodeURIComponent(connectionId)}/test`, {
        method: "POST",
      }),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: videoSettingsKeys.providers() });
    },
  });
}

export function useDisconnectVideoProvider() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { connectionId: string; reason?: string }) =>
      authorizedFetch<ConnectionView>("tenant", `${BASE}/${encodeURIComponent(input.connectionId)}/disconnect`, {
        method: "POST",
        body: JSON.stringify({ reason: input.reason?.trim() ? input.reason.trim() : null }),
      }),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: videoSettingsKeys.providers() });
    },
  });
}

export function useTenantVideoConfiguration() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: videoSettingsKeys.configuration(),
    queryFn: () => authorizedFetch<TenantVideoConfigurationView>("tenant", "/v1/tenant-admin/video-configuration"),
  });
}

export function useUpdateTenantVideoConfiguration() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (settings: TenantVideoSettings) =>
      authorizedFetch<TenantVideoConfigurationView>("tenant", "/v1/tenant-admin/video-configuration", {
        method: "PUT",
        body: JSON.stringify(settings),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(videoSettingsKeys.configuration(), data);
    },
  });
}

/** VIDEO Phase 5c - the institute's own Google app (Tenant Admin; INTEGRATIONS VIEW / CREATE_EDIT). */
export function useTenantGoogleApp(enabled = true) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: videoSettingsKeys.googleApp(),
    queryFn: () => authorizedFetch<TenantGoogleAppView>("tenant", `${BASE}/youtube/oauth-app`),
    enabled,
  });
}

function useGoogleAppMutation<TInput>(request: (input: TInput) => RequestInit) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TInput) =>
      authorizedFetch<TenantGoogleAppView>("tenant", `${BASE}/youtube/oauth-app`, request(input)),
    onSuccess: (data) => queryClient.setQueryData(videoSettingsKeys.googleApp(), data),
    // Availability and the channel's status may change with the app.
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: videoSettingsKeys.providers() });
    },
  });
}

export function useSaveTenantGoogleApp() {
  return useGoogleAppMutation((input: { clientId: string; clientSecret: string }) => ({
    method: "PUT",
    body: JSON.stringify(input),
  }));
}

export function useRemoveTenantGoogleApp() {
  return useGoogleAppMutation<void>(() => ({ method: "DELETE" }));
}

export function useVerifyTenantGoogleApp() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<TenantGoogleAppVerify>("tenant", `${BASE}/youtube/oauth-app/verify`, { method: "POST" }),
  });
}
