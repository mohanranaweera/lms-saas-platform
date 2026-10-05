"use client";

import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { AlertCircle, CheckCircle2 } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageIntegrations } from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import {
  useConfirmTenantYouTubeChannel,
  usePendingYouTubeChannel,
  useTenantVideoProviders,
  useVideoChannelSettings,
} from "@/lib/api/video-providers";
import { TenantYouTubeChannelCard } from "@/components/integrations/tenant-youtube-channel-card";
import {
  TenantYouTubeChannelSettings,
  uploadersSummary,
} from "@/components/integrations/tenant-youtube-channel-settings";
import { LoadingState } from "@/components/states/loading-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";

type CallbackResult = { kind: "pending"; pendingId: string } | { kind: "denied" | "missing_scopes" | "failed" };

/**
 * Reads the one-time result Google's redirect (via the backend callback)
 * added - `?pending=<id>` or `?error=denied|missing_scopes|failed` - and then
 * removes it from the address bar so a reload or a shared link can't replay it.
 */
function useCallbackResult(): [CallbackResult | null, () => void] {
  const searchParams = useSearchParams();
  const pathname = usePathname();
  const [result, setResult] = useState<CallbackResult | null>(() => {
    const pendingId = searchParams.get("pending");
    const error = searchParams.get("error");
    if (pendingId) return { kind: "pending", pendingId };
    if (error === "denied" || error === "missing_scopes") return { kind: error };
    if (error) return { kind: "failed" };
    return null;
  });

  useEffect(() => {
    if (searchParams.get("pending") || searchParams.get("error")) {
      window.history.replaceState(null, "", pathname);
    }
  }, [searchParams, pathname]);

  return [result, () => setResult(null)];
}

const ERROR_TEXT: Record<"denied" | "missing_scopes" | "failed", string> = {
  denied: "YouTube was not connected because access wasn't allowed.",
  missing_scopes:
    "YouTube was not connected because upload permission wasn't granted. Connect again and allow every permission Google asks for.",
  failed: "YouTube could not be connected. Please try again.",
};

/**
 * The admin who started the connection sees which channel Google returned and
 * confirms it explicitly. The backend accepts the confirmation only from that
 * same admin of this institute.
 */
function ConfirmChannelPanel({ pendingId, onDone }: { pendingId: string; onDone: () => void }) {
  const preview = usePendingYouTubeChannel(pendingId);
  const confirm = useConfirmTenantYouTubeChannel();

  if (confirm.isSuccess) {
    return (
      <Alert role="status">
        <CheckCircle2 aria-hidden="true" />
        <AlertDescription>
          {confirm.data.connection?.accountLabel ?? "Your YouTube channel"} is now connected.
        </AlertDescription>
      </Alert>
    );
  }
  if (preview.isPending) {
    return (
      <p role="status" aria-busy="true" className="text-sm text-muted-foreground">
        Checking the YouTube channel…
      </p>
    );
  }
  if (preview.isError) {
    return (
      <Alert variant="destructive">
        <AlertCircle aria-hidden="true" />
        <AlertDescription>
          This connection request has expired or was already used. Choose Connect YouTube to try again.
        </AlertDescription>
      </Alert>
    );
  }

  const channel = preview.data;
  const name = channel.channelTitle ?? channel.channelId;
  return (
    <section
      aria-labelledby="confirm-channel-heading"
      className="flex max-w-2xl flex-col gap-3 rounded-lg border border-border p-4"
    >
      <h2 id="confirm-channel-heading" className="text-base font-medium text-foreground">
        Connect this YouTube channel?
      </h2>
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-muted-foreground">Channel</dt>
        <dd className="break-all text-foreground">{channel.channelTitle ?? "—"}</dd>
        <dt className="text-muted-foreground">Channel ID</dt>
        <dd className="break-all text-foreground">{channel.channelId}</dd>
      </dl>
      {channel.replacesCurrentChannel ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            This replaces your current channel{channel.currentChannelTitle ? `, ${channel.currentChannelTitle}` : ""}.
            New uploads will go to {name}. Videos already uploaded stay on the old channel.
          </AlertDescription>
        </Alert>
      ) : null}
      {confirm.isError ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            {isApiClientError(confirm.error) ? confirm.error.message : "The channel could not be connected."}
          </AlertDescription>
        </Alert>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <Button
          type="button"
          onClick={() => confirm.mutate({ pendingId, replaceChannel: channel.replacesCurrentChannel })}
          disabled={confirm.isPending}
          aria-busy={confirm.isPending}
        >
          {confirm.isPending
            ? "Connecting…"
            : channel.replacesCurrentChannel
              ? `Replace with ${name}`
              : "Connect this channel"}
        </Button>
        <Button type="button" variant="outline" onClick={onDone} disabled={confirm.isPending}>
          Cancel
        </Button>
      </div>
    </section>
  );
}

function VideoProvidersContent() {
  const { session } = useAuth();
  const canManage = canManageIntegrations(session?.role ?? null);
  const query = useTenantVideoProviders();
  // Loaded once the provider list has loaded (same INTEGRATIONS VIEW permission).
  const channelSettings = useVideoChannelSettings(query.isSuccess);
  const [result, clearResult] = useCallbackResult();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/tenant-admin/settings"
          className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          ← Institute configuration
        </Link>
        <h1 className="mt-2 text-xl font-semibold text-foreground">Video providers</h1>
        <p className="text-sm text-muted-foreground">
          Where your institute&apos;s lesson videos are stored. Access tokens are never shown or sent to your browser.
        </p>
      </div>

      {result?.kind === "pending" ? (
        <ConfirmChannelPanel pendingId={result.pendingId} onDone={clearResult} />
      ) : result ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{ERROR_TEXT[result.kind]}</AlertDescription>
        </Alert>
      ) : null}

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading video providers…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(accounts) => (
          <div className="flex max-w-2xl flex-col gap-4">
            {accounts.map((account) => (
              <TenantYouTubeChannelCard
                key={account.provider}
                view={account}
                canManage={canManage}
                displayName={channelSettings.data?.displayName ?? null}
                uploaders={channelSettings.data ? uploadersSummary(channelSettings.data) : null}
              />
            ))}
            {channelSettings.isPending ? (
              <p role="status" aria-busy="true" className="text-sm text-muted-foreground">
                Loading channel settings…
              </p>
            ) : channelSettings.isError ? (
              <Alert variant="destructive">
                <AlertCircle aria-hidden="true" />
                <AlertDescription>
                  {isApiClientError(channelSettings.error) && channelSettings.error.status === 403
                    ? "You don't have access to the channel settings."
                    : "The channel settings could not be loaded. Refresh the page to try again."}
                </AlertDescription>
              </Alert>
            ) : (
              <TenantYouTubeChannelSettings
                settings={channelSettings.data}
                canManage={canManage}
              />
            )}
            {!canManage ? (
              <p className="text-sm text-muted-foreground">Only a Tenant Admin can change video providers.</p>
            ) : null}
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}

/** Tenant Admin → Institute configuration → Video providers (VIDEO Phase 3, ADR-018 Amendment 2). */
export default function VideoProvidersPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading video providers…" />}>
      <VideoProvidersContent />
    </Suspense>
  );
}
