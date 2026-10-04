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

export interface VideoProviderAccountView {
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

export const videoSettingsKeys = {
  all: ["video-settings"] as const,
  providers: () => [...videoSettingsKeys.all, "providers"] as const,
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

export function useBeginTenantYouTubeConnect() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<{ authorizationUrl: string }>("tenant", `${BASE}/youtube/connect`, { method: "POST" }),
  });
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
