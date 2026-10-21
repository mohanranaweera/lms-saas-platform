"use client";

import Link from "next/link";
import { AlertCircle } from "lucide-react";
import { useTeacherIntegrations } from "@/lib/api/integrations";
import { Alert, AlertDescription } from "@/components/ui/alert";

const INTEGRATIONS_HREF = "/teacher/settings/integrations";

/** Backend error codes that mean "fix your Zoom connection" (docs/api/integrations.md). */
export function isProviderErrorCode(code: string | undefined): boolean {
  return (
    code === "PROVIDER_NOT_CONNECTED" ||
    code === "PROVIDER_LICENSE_REQUIRED" ||
    code === "PROVIDER_RECONNECT_REQUIRED" ||
    code === "PROVIDER_DISABLED_BY_TENANT"
  );
}

/**
 * A `PROVIDER_*` error from a live-class action, with the way to fix it. The
 * message is the backend's own client-safe text.
 */
export function ProviderErrorAlert({ code, message }: { code: string; message: string }) {
  const fixable = code !== "PROVIDER_DISABLED_BY_TENANT";
  return (
    <Alert variant="destructive">
      <AlertCircle aria-hidden="true" />
      <AlertDescription>
        {message}{" "}
        {fixable ? (
          <Link href={INTEGRATIONS_HREF} className="font-medium underline">
            Open Settings → Integrations
          </Link>
        ) : null}
      </AlertDescription>
    </Alert>
  );
}

/**
 * Up-front notice on the teacher's schedule form (MEDIA-INT-01): explains
 * why scheduling will be refused before the teacher fills the form in. UX
 * only - the backend re-checks on submit. Renders nothing while loading, on
 * error, when Zoom isn't set up on the platform, or when the teacher is ready.
 */
export function ZoomReadinessNotice() {
  const query = useTeacherIntegrations();
  const zoom = query.data?.providers.find((provider) => provider.provider === "ZOOM");
  if (!zoom || !zoom.available) return null;

  const connection = zoom.connection?.status === "DISCONNECTED" ? null : zoom.connection;
  let text: string | null = null;
  if (!zoom.enabledByTenant) {
    text = "Your institute has switched Zoom off, so live classes can't be scheduled right now.";
  } else if (!connection) {
    text = "Connect your Zoom account before scheduling. Live classes are hosted on your own Zoom account.";
  } else if (connection.status === "NEEDS_RECONNECT") {
    text = "Your Zoom account needs to be reconnected before you can schedule.";
  } else if (connection.accountType !== "LICENSED") {
    text = "Live classes need a licensed (paid) Zoom account. Your connected Zoom account is a free (Basic) account.";
  }
  if (!text) return null;

  return (
    <Alert role="status">
      <AlertCircle aria-hidden="true" />
      <AlertDescription>
        {text}{" "}
        {zoom.enabledByTenant ? (
          <Link href={INTEGRATIONS_HREF} className="font-medium underline">
            Open Settings → Integrations
          </Link>
        ) : null}
      </AlertDescription>
    </Alert>
  );
}
