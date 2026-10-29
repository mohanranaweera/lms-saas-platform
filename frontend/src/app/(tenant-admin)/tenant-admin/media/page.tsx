"use client";

import { useAuth } from "@/lib/auth/auth-context";
import { canManageIntegrations, canManageMedia } from "@/lib/auth/permissions";
import { MediaLibrary } from "@/components/media/media-library";

/** Tenant Admin → Academic → Media Library (VIDEO Phase 5, Klass G1): the whole institute's files and videos. */
export default function TenantAdminMediaPage() {
  const { session } = useAuth();
  const role = session?.role ?? null;
  return (
    <MediaLibrary
      basePath="/tenant-admin/media"
      dashboardHref="/tenant-admin/dashboard"
      canUpload={canManageMedia(role)}
      settingsHref={canManageIntegrations(role) ? "/tenant-admin/settings/video" : undefined}
      scopeNote="Every document, image and lesson video your institute has uploaded."
    />
  );
}
