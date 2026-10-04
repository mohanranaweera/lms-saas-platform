"use client";

import { useAuth } from "@/lib/auth/auth-context";
import { canManageIntegrations, canManageMedia } from "@/lib/auth/permissions";
import { MediaLibrary } from "@/components/media/media-library";

/** Tenant Admin → Academic → Media Library (VIDEO Phase 5): the whole institute's videos. */
export default function TenantAdminMediaPage() {
  const { session } = useAuth();
  const role = session?.role ?? null;
  return (
    <MediaLibrary
      basePath="/tenant-admin/media"
      dashboardHref="/tenant-admin/dashboard"
      canUpload={canManageMedia(role)}
      settingsHref={canManageIntegrations(role) ? "/tenant-admin/settings/video-providers" : undefined}
      scopeNote="Every lesson video your institute has uploaded."
    />
  );
}
