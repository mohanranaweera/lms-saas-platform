"use client";

import { useParams } from "next/navigation";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageIntegrations, canManageMedia } from "@/lib/auth/permissions";
import { MediaDetail } from "@/components/media/media-detail";

/** Tenant Admin → Media Library → one video (VIDEO Phase 5). */
export default function TenantAdminMediaDetailPage() {
  const { mediaId } = useParams<{ mediaId: string }>();
  const { session } = useAuth();
  const role = session?.role ?? null;
  return (
    <MediaDetail
      mediaId={mediaId}
      listHref="/tenant-admin/media"
      dashboardHref="/tenant-admin/dashboard"
      canManage={canManageMedia(role)}
      settingsHref={canManageIntegrations(role) ? "/tenant-admin/settings/video-providers" : undefined}
    />
  );
}
