"use client";

import { useState } from "react";
import { AlertCircle, CheckCircle2, CircleSlash, Pencil, PlugZap, RefreshCw, Trash2, TriangleAlert } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import {
  useBeginTenantYouTubeConnect,
  useDeleteVideoIntegration,
  useDisconnectVideoProvider,
  useTestVideoProvider,
  type VideoProviderAccountStatus,
  type VideoProviderAccountView,
} from "@/lib/api/video-providers";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
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
import { VideoIntegrationSheet } from "@/components/integrations/video-integration-sheet";

const STATUS: Record<
  VideoProviderAccountStatus | "UNAVAILABLE",
  { label: string; variant: "default" | "warning" | "outline" | "secondary" | "destructive"; Icon: typeof CheckCircle2 }
> = {
  CONNECTED: { label: "Connected", variant: "default", Icon: CheckCircle2 },
  EXPIRED: { label: "Needs reconnect", variant: "warning", Icon: RefreshCw },
  ERROR: { label: "Error", variant: "destructive", Icon: TriangleAlert },
  NOT_CONNECTED: { label: "Not connected", variant: "outline", Icon: PlugZap },
  UNAVAILABLE: { label: "Unavailable", variant: "secondary", Icon: CircleSlash },
};

/** Only ever navigate to an https consent page the backend returned. */
function isSafeConsentUrl(url: string): boolean {
  try {
    return new URL(url).protocol === "https:";
  } catch {
    return false;
  }
}

/**
 * The institute's own YouTube channel (VIDEO Phase 3): status, the connected
 * channel, and Connect / Reconnect / Test / Disconnect for a Tenant Admin.
 * Connecting sends the browser to Google; the page then shows the returned
 * channel for an explicit confirmation. No token ever reaches the browser.
 */
/** "All teachers" / "1 teacher" / "N teachers" - the card's Klass-style summary. */
export function uploadersSummary(view: VideoProviderAccountView): string {
  if (view.allTeachersAllowed) return "All teachers";
  const count = view.allowedTeachers.length;
  return count === 1 ? "1 teacher" : `${count} teachers`;
}

export function TenantYouTubeChannelCard({ view, canManage }: { view: VideoProviderAccountView; canManage: boolean }) {
  const { session } = useAuth();
  const connectMutation = useBeginTenantYouTubeConnect();
  const testMutation = useTestVideoProvider();
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const connection = view.connection && view.connection.status !== "DISCONNECTED" ? view.connection : null;
  const state = view.available ? view.status : "UNAVAILABLE";
  const { label, variant, Icon } = STATUS[state];
  const headingId = `integration-${view.integrationId}-heading`;
  const [editing, setEditing] = useState(false);

  async function handleConnect() {
    setMessage(null);
    try {
      const { authorizationUrl } = await connectMutation.mutateAsync(view.integrationId);
      if (!isSafeConsentUrl(authorizationUrl)) {
        setMessage({ text: "We couldn't open Google's sign-in page. Please try again.", error: true });
        return;
      }
      window.location.assign(authorizationUrl);
    } catch (error) {
      setMessage({
        text: isApiClientError(error) ? error.message : "Could not start connecting YouTube. Please try again.",
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
    <section
      aria-labelledby={headingId}
      className="flex flex-col gap-3 rounded-lg border border-border p-4"
    >
      <div className="flex flex-wrap items-center gap-2">
        <h2 id={headingId} className="text-base font-medium text-foreground">
          {view.name}
        </h2>
        <Badge variant={variant}>
          <Icon aria-hidden="true" />
          {label}
        </Badge>
      </div>
      <p className="text-sm text-muted-foreground">YouTube · {uploadersSummary(view)}</p>
      {view.description ? <p className="text-sm whitespace-pre-line text-foreground">{view.description}</p> : null}
      <p className="text-sm text-muted-foreground">
        Upload lesson videos to your institute&apos;s own YouTube channel. YouTube stores and streams them at no cost,
        and students can only reach them through your courses. Unlisted YouTube videos can still be watched by anyone
        who gets hold of the video link, so don&apos;t use this for content that must stay private.
      </p>

      {connection ? (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-muted-foreground">Channel</dt>
          <dd className="break-all text-foreground">{connection.accountLabel ?? "—"}</dd>
          <dt className="text-muted-foreground">Channel ID</dt>
          <dd className="break-all text-foreground">{connection.externalAccountIdMasked ?? "—"}</dd>
          <dt className="text-muted-foreground">Connected by</dt>
          <dd className="text-foreground">
            {view.connectedByUserId && view.connectedByUserId === session?.userId ? "You" : "An administrator"}
          </dd>
          <dt className="text-muted-foreground">Connected on</dt>
          <dd className="text-foreground">{formatDateTime(connection.connectedAt)}</dd>
          <dt className="text-muted-foreground">Last checked</dt>
          <dd className="text-foreground">
            {connection.lastVerifiedAt ? formatDateTime(connection.lastVerifiedAt) : "Not yet"}
          </dd>
        </dl>
      ) : null}

      {view.platformDisabled ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            Video uploads were switched off by the platform
            {view.platformDisabledReason ? ` (${view.platformDisabledReason})` : ""}. Contact platform support to
            switch them back on. Videos already uploaded keep working.
          </AlertDescription>
        </Alert>
      ) : null}
      {state === "CONNECTED" && !view.platformDisabled && !view.canEditVideos ? (
        <Alert>
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            Reconnect YouTube to allow editing video titles, descriptions and visibility from the Media Library.
            Uploads keep working meanwhile.
          </AlertDescription>
        </Alert>
      ) : null}
      {state === "EXPIRED" ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            YouTube no longer accepts this connection{connection?.lastError ? ` (${connection.lastError})` : ""}.
            Reconnect the same channel to keep uploading.
          </AlertDescription>
        </Alert>
      ) : null}
      {state === "ERROR" && connection?.lastError ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>The last check failed: {connection.lastError}</AlertDescription>
        </Alert>
      ) : null}
      {state === "UNAVAILABLE" ? (
        <p className="text-sm text-muted-foreground">YouTube isn&apos;t available on this platform right now.</p>
      ) : null}
      {!connection && view.connection?.status === "DISCONNECTED" && view.connection.disconnectedAt ? (
        <p className="text-sm text-muted-foreground">
          Disconnected {formatDateTime(view.connection.disconnectedAt)}
          {view.connection.disconnectedReason ? `: ${view.connection.disconnectedReason}` : "."}
        </p>
      ) : null}

      {canManage && state !== "UNAVAILABLE" ? (
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant={connection ? "outline" : "default"}
            onClick={handleConnect}
            disabled={connectMutation.isPending}
            aria-busy={connectMutation.isPending}
          >
            {connectMutation.isPending ? "Opening Google…" : connection ? "Reconnect YouTube" : "Connect YouTube"}
          </Button>
          {connection ? (
            <Button
              type="button"
              variant="outline"
              onClick={handleTest}
              disabled={testMutation.isPending}
              aria-busy={testMutation.isPending}
            >
              {testMutation.isPending ? "Testing…" : "Test connection"}
            </Button>
          ) : null}
          {connection ? <DisconnectChannelDialog connectionId={connection.id} /> : null}
        </div>
      ) : null}
      {canManage ? (
        <div className="flex flex-wrap gap-2 border-t border-border pt-3">
          <Button type="button" variant="outline" size="sm" onClick={() => setEditing(true)}>
            <Pencil aria-hidden="true" />
            Edit<span className="sr-only"> {view.name}</span>
          </Button>
          <DeleteIntegrationDialog view={view} />
          <VideoIntegrationSheet open={editing} onOpenChange={setEditing} integration={view} />
        </div>
      ) : null}

      {message ? (
        <p
          role={message.error ? "alert" : "status"}
          className={message.error ? "text-sm text-destructive" : "text-sm text-muted-foreground"}
        >
          {message.text}
        </p>
      ) : null}
    </section>
  );
}

function DeleteIntegrationDialog({ view }: { view: VideoProviderAccountView }) {
  const remove = useDeleteVideoIntegration();
  const [open, setOpen] = useState(false);

  async function handleConfirm() {
    try {
      await remove.mutateAsync(view.integrationId);
      setOpen(false);
    } catch {
      // Shown below.
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger render={<Button type="button" variant="outline" size="sm" />}>
        <Trash2 aria-hidden="true" />
        Delete<span className="sr-only"> {view.name}</span>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete &ldquo;{view.name}&rdquo;?</AlertDialogTitle>
          <AlertDialogDescription>
            {view.connection && view.connection.status !== "DISCONNECTED"
              ? "Its YouTube channel is disconnected and Google is asked to revoke this platform's access. "
              : ""}
            Videos already uploaded stay on YouTube and in lessons; the Media Library lists them under Archived.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {remove.isError ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>
              {isApiClientError(remove.error) ? remove.error.message : "The integration could not be deleted."}
            </AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            onClick={handleConfirm}
            disabled={remove.isPending}
            aria-busy={remove.isPending}
          >
            {remove.isPending ? "Deleting…" : "Delete integration"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function DisconnectChannelDialog({ connectionId }: { connectionId: string }) {
  const mutation = useDisconnectVideoProvider();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  function handleOpenChange(next: boolean) {
    setOpen(next);
    setErrorMessage(null);
    if (!next) setReason("");
  }

  async function handleConfirm() {
    setErrorMessage(null);
    try {
      await mutation.mutateAsync({ connectionId, reason });
      setOpen(false);
    } catch (error) {
      setErrorMessage(isApiClientError(error) ? error.message : "Could not disconnect. Please try again.");
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={handleOpenChange}>
      <AlertDialogTrigger render={<Button type="button" variant="outline" />}>Disconnect</AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Disconnect your YouTube channel?</AlertDialogTitle>
          <AlertDialogDescription>
            New videos can&apos;t be uploaded until you connect a channel again, and Google will be asked to revoke
            this platform&apos;s access. Videos already on the channel stay on YouTube.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="youtube-disconnect-reason">Reason (optional)</Label>
          <Textarea
            id="youtube-disconnect-reason"
            maxLength={500}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </div>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            onClick={handleConfirm}
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? "Disconnecting…" : "Disconnect YouTube"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
