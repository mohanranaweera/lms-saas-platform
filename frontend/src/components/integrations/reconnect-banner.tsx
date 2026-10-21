"use client";

import Link from "next/link";
import { RefreshCw } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { isTeacherRole } from "@/lib/auth/permissions";
import { PROVIDER_LABELS, useTeacherIntegrations } from "@/lib/api/integrations";
import { Alert, AlertDescription } from "@/components/ui/alert";

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
  if (!isTeacher || !query.data) return null;

  const needsReconnect = query.data.providers
    .filter((provider) => provider.connection?.status === "NEEDS_RECONNECT")
    .map((provider) => PROVIDER_LABELS[provider.provider]);
  if (needsReconnect.length === 0) return null;

  return (
    <Alert variant="destructive" className="mb-4">
      <RefreshCw aria-hidden="true" />
      <AlertDescription>
        Your {needsReconnect.join(" and ")} {needsReconnect.length === 1 ? "account needs" : "accounts need"} to be
        reconnected.{" "}
        <Link href="/teacher/settings/integrations" className="font-medium underline">
          Go to Integrations
        </Link>
      </AlertDescription>
    </Alert>
  );
}
