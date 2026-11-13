"use client";

import Link from "next/link";
import { RefreshCw } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { isTeacherRole } from "@/lib/auth/permissions";
import { PROVIDER_LABELS, useTeacherIntegrations } from "@/lib/api/integrations";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { useT } from "@/i18n/i18n-provider";

/**
 * Teacher-portal banner (MEDIA-INT-01): shown on every teacher page while any
 * of the teacher's own connections needs reconnecting, so a revoked Zoom
 * token is noticed before a live class, not during it. Driven only by the
 * backend's connection status; renders nothing while loading or on error.
 */
export function ReconnectBanner() {
  const { session } = useAuth();
  const isTeacher = isTeacherRole(session?.role ?? null);
  const query = useTeacherIntegrations({ enabled: isTeacher });
  const t = useT();
  if (!isTeacher || !query.data) return null;

  const needsReconnect = query.data.providers
    .filter((provider) => provider.connection?.status === "NEEDS_RECONNECT")
    .map((provider) => PROVIDER_LABELS[provider.provider]);
  if (needsReconnect.length === 0) return null;

  return (
    <Alert variant="destructive" className="mb-4">
      <RefreshCw aria-hidden="true" />
      <AlertDescription>
        {t("common.integrations.reconnectNeeded", {
          count: needsReconnect.length,
          providers: needsReconnect.join(t("common.integrations.listSeparator")),
        })}{" "}
        <Link href="/teacher/settings/integrations" className="font-medium underline">
          {t("common.integrations.goToIntegrations")}
        </Link>
      </AlertDescription>
    </Alert>
  );
}
