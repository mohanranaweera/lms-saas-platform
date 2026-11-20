"use client";

import { formatDateTime } from "@/lib/format";
import type { ManagedHostTab, MediaListParams, MediaTab, MediaTabs } from "@/lib/api/media";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageIntegrations, canManageMedia } from "@/lib/auth/permissions";

/** "Added <date>" line shared by Media Library cards and the session pickers. */
export function AddedDate({ iso, className }: { iso: string; className?: string }) {
  return (
    <span className={className} data-testid="media-added-date">
      Added {formatDateTime(iso)}
    </span>
  );
}

/** Where YouTube integrations are managed (same as the Tenant Admin Media Library page). */
export const VIDEO_PROVIDERS_SETTINGS_HREF = "/tenant-admin/settings/video";
/** VIDEO Phase 13: where a Tenant Admin sets up the DynTube account. */
export const DYNTUBE_SETTINGS_HREF = "/tenant-admin/settings/integrations";

/**
 * Whether to offer upload controls in the session pickers, derived exactly as
 * the Media Library pages do. UX only: every upload endpoint re-checks.
 */
export function useMediaUploadUx(): { canUpload: boolean; settingsHref?: string } {
  const { session } = useAuth();
  const role = session?.role ?? null;
  return {
    canUpload: canManageMedia(role),
    settingsHref: canManageIntegrations(role) ? VIDEO_PROVIDERS_SETTINGS_HREF : undefined,
  };
}

/** One Media Library video source, mirroring the library tabs (Archived excluded). */
export type VideoSourceKey = "ALL" | "INTERNAL" | "DYNTUBE" | `integration:${string}`;

export interface VideoSourceOption {
  value: VideoSourceKey;
  label: string;
  tab: MediaTab | null;
  managed: ManagedHostTab | null;
}

/**
 * Source filter options from the server's tab list. All sources and Uploads
 * are always offered (Uploads is always a library tab); DynTube and each
 * YouTube integration only when the server lists them.
 */
export function videoSourceOptions(tabs: MediaTabs | undefined): VideoSourceOption[] {
  const options: VideoSourceOption[] = [
    { value: "ALL", label: "All sources", tab: null, managed: null },
    { value: "INTERNAL", label: "Uploads (internal storage)", tab: null, managed: null },
  ];
  if (tabs?.managedHost) {
    options.push({ value: "DYNTUBE", label: tabs.managedHost.name, tab: null, managed: tabs.managedHost });
  }
  for (const tab of tabs?.integrations ?? []) {
    options.push({ value: `integration:${tab.integrationId}`, label: `${tab.name} (YouTube)`, tab, managed: null });
  }
  return options;
}

/** The `useMediaList` filter for a source, matching the library's per-tab query. */
export function videoSourceParams(key: VideoSourceKey): Pick<MediaListParams, "provider" | "integrationId"> {
  if (key === "INTERNAL") return { provider: "INTERNAL" };
  if (key === "DYNTUBE") return { provider: "DYNTUBE" };
  if (key.startsWith("integration:")) return { integrationId: key.slice("integration:".length) };
  return {};
}
