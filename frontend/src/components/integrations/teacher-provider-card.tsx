"use client";

import { useState } from "react";
import { AlertCircle } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import {
  PROVIDER_LABELS,
  useBeginConnect,
  useDisconnectTeacherConnection,
  useTeacherConnectionImpact,
  useTestTeacherConnection,
  type TeacherProviderView,
} from "@/lib/api/integrations";
import { ConnectionStatusBadge, connectionDisplayState } from "@/components/integrations/connection-status-badge";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { LiveRegion } from "@/components/ui/live-region";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";

import { useT } from "@/i18n/i18n-provider";
const DESCRIPTIONS: Record<TeacherProviderView["provider"], string> = {
  ZOOM: "Host your live classes on your own Zoom account. Each student gets a personal join link. A licensed (paid) Zoom account is required.",
  VIMEO: "Link your own Vimeo account so you can use your private videos in lessons (coming soon).",
};

/**
 * Only ever navigate to an https consent page the backend returned — never a
 * javascript:/relative URL, even if a response were tampered with.
 */
function isSafeConsentUrl(url: string): boolean {
  try {
    return new URL(url).protocol === "https:";
  } catch {
    return false;
  }
}

function disconnectedNote(view: TeacherProviderView): string | null {
  const connection = view.connection;
  if (!connection || connection.status !== "DISCONNECTED") return null;
  switch (connection.disconnectedBy) {
    case "TENANT_ADMIN":
      return `Disconnected by your institute${connection.disconnectedReason ? `: ${connection.disconnectedReason}` : "."}`;
    case "PROVIDER":
      return `The app was removed from your ${PROVIDER_LABELS[view.provider]} account. Connect again to continue.`;
    default:
      return null;
  }
}

/**
 * One teacher-level provider (MEDIA-INT-01): status, the connected account,
 * and Connect / Reconnect / Test / Disconnect. Connecting sends the browser
 * to the provider's consent page; the backend finishes the connection and
 * redirects back here. No token ever reaches the browser.
 */
export function TeacherProviderCard({ view }: { view: TeacherProviderView }) {
  const t = useT();
  const label = PROVIDER_LABELS[view.provider];
  const state = connectionDisplayState(view.connection, {
    enabledByTenant: view.enabledByTenant,
    available: view.available,
  });
  const connection = view.connection && view.connection.status !== "DISCONNECTED" ? view.connection : null;
  const connectMutation = useBeginConnect();
  const testMutation = useTestTeacherConnection();
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const headingId = `provider-${view.provider.toLowerCase()}-heading`;
  const canConnect = state !== "NOT_ENABLED" && state !== "UNAVAILABLE";
  const basicZoom = view.provider === "ZOOM" && connection?.status === "ACTIVE" && connection.accountType === "BASIC";
  const note = disconnectedNote(view);

  async function handleConnect() {
    setMessage(null);
    try {
      const { authorizationUrl } = await connectMutation.mutateAsync(view.provider);
      if (!isSafeConsentUrl(authorizationUrl)) {
        setMessage({ text: `We couldn't open ${label}'s sign-in page. Please try again.`, error: true });
        return;
      }
      window.location.assign(authorizationUrl);
    } catch (error) {
      setMessage({
        text: isApiClientError(error) ? error.message : `Could not start connecting ${label}. Please try again.`,
        error: true,
      });
    }
  }

  async function handleTest() {
    if (!connection) return;
    setMessage(null);
    try {
      const result = await testMutation.mutateAsync(connection.id);
      setMessage({ text: result.message, error: !result.ok });
    } catch (error) {
      setMessage({ text: isApiClientError(error) ? error.message : "The test could not be run.", error: true });
    }
  }

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id={headingId} className="text-base font-medium text-foreground">
          {label}
        </h2>
        <ConnectionStatusBadge state={state} />
      </div>
      <p className="text-sm text-muted-foreground">{DESCRIPTIONS[view.provider]}</p>

      {connection ? (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-muted-foreground">{t("teacher.providerCard.account")}</dt>
          <dd className="break-all text-foreground">{connection.accountLabel ?? "—"}</dd>
          <dt className="text-muted-foreground">{t("teacher.providerCard.lastChecked")}</dt>
          <dd className="text-foreground">
            {connection.lastVerifiedAt ? formatDateTime(connection.lastVerifiedAt) : t("teacher.providerCard.notYet")}
          </dd>
        </dl>
      ) : null}

      {basicZoom ? (
        <Alert role="status">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            {t("teacher.providerCard.thisIsAFree")}
          </AlertDescription>
        </Alert>
      ) : null}

      {state === "NEEDS_RECONNECT" ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            {label} no longer accepts this connection{connection?.lastError ? ` (${connection.lastError})` : ""}.
            Reconnect to keep using it.
          </AlertDescription>
        </Alert>
      ) : null}

      {note ? <p className="text-sm text-muted-foreground">{note}</p> : null}
      {state === "NOT_ENABLED" ? (
        <p className="text-sm text-muted-foreground">Your institute has switched {label} off. Contact your institute admin.</p>
      ) : null}
      {state === "UNAVAILABLE" ? (
        <p className="text-sm text-muted-foreground">{label} isn&apos;t available on this platform right now.</p>
      ) : null}

      <div className="flex flex-wrap gap-2">
        {canConnect ? (
          <Button
            type="button"
            variant={connection ? "outline" : "default"}
            onClick={handleConnect}
            disabled={connectMutation.isPending}
            aria-busy={connectMutation.isPending}
          >
            {connectMutation.isPending ? `Opening ${label}…` : connection ? `Reconnect ${label}` : `Connect ${label}`}
          </Button>
        ) : null}
        {connection ? (
          <Button
            type="button"
            variant="outline"
            onClick={handleTest}
            disabled={testMutation.isPending}
            aria-busy={testMutation.isPending}
          >
            {testMutation.isPending ? t("teacher.providerCard.testing") : t("teacher.providerCard.testConnection")}
          </Button>
        ) : null}
        {connection ? <DisconnectDialog view={view} connectionId={connection.id} /> : null}
      </div>

      {message ? (
        <p role={message.error ? "alert" : "status"} className={message.error ? "text-sm text-destructive" : "text-sm text-muted-foreground"}>
          {message.text}
        </p>
      ) : null}
    </section>
  );
}

function DisconnectDialog({ view, connectionId }: { view: TeacherProviderView; connectionId: string }) {
  const t = useT();
  const label = PROVIDER_LABELS[view.provider];
  const disconnectMutation = useDisconnectTeacherConnection();
  const impactMutation = useTeacherConnectionImpact();
  const [open, setOpen] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const impact = impactMutation.data?.upcomingSessionCount ?? 0;

  function handleOpenChange(next: boolean) {
    setOpen(next);
    setErrorMessage(null);
    if (next && view.provider === "ZOOM") {
      impactMutation.mutate(connectionId);
    }
  }

  async function handleConfirm() {
    setErrorMessage(null);
    try {
      await disconnectMutation.mutateAsync(connectionId);
      setOpen(false);
    } catch (error) {
      setErrorMessage(isApiClientError(error) ? error.message : t("teacher.providerCard.couldNotDisconnectPlease"));
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={handleOpenChange}>
      <AlertDialogTrigger render={<Button type="button" variant="outline" />}>{t("teacher.providerCard.disconnect")}</AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Disconnect {label}?</AlertDialogTitle>
          <AlertDialogDescription>
            The platform will stop using your {label} account and ask {label} to revoke its access. You can connect
            again at any time.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {view.provider === "ZOOM" ? (
          <div aria-busy={impactMutation.isPending}>
            {impactMutation.isPending ? (
              <p className="text-sm text-muted-foreground">{t("teacher.providerCard.checkingYourUpcomingLive")}</p>
            ) : impact > 0 ? (
              <Alert variant="destructive">
                <AlertCircle aria-hidden="true" />
                <AlertDescription>
                  {impact === 1 ? "1 upcoming live class uses" : `${impact} upcoming live classes use`} this Zoom
                  account. Students won&apos;t be able to join until you reconnect the same account, or connect
                  another one and recreate the meeting.
                </AlertDescription>
              </Alert>
            ) : impactMutation.isSuccess ? (
              <p className="text-sm text-muted-foreground">{t("teacher.providerCard.noUpcomingLiveClasses")}</p>
            ) : null}
            <LiveRegion message={impactMutation.isSuccess ? `${impact} upcoming live classes affected` : ""} />
          </div>
        ) : null}
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>{t("teacher.providerCard.cancel")}</AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            onClick={handleConfirm}
            disabled={disconnectMutation.isPending}
            aria-busy={disconnectMutation.isPending}
          >
            {disconnectMutation.isPending ? t("teacher.providerCard.disconnecting") : `Disconnect ${label}`}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
