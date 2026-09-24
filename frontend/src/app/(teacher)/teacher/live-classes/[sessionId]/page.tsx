"use client";

import { Suspense, useState } from "react";
import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { CheckCircle2, RotateCcw } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { LoadingState } from "@/components/states/loading-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ClassSessionStatusBadge } from "@/components/live-classes/class-session-status-badge";
import { ProviderStatusBadge } from "@/components/live-classes/provider-status-badge";
import {
  useCancelClassSession,
  useClassSession,
  useClassSessionRecording,
  useCompleteClassSession,
  useJoinClassSession,
  useRetryClassSessionProvisioning,
  useStartClassSession,
  type ClassSessionResponse,
} from "@/lib/api/class-sessions";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";

function RetryProvisioningAction({ session }: { session: ClassSessionResponse }) {
  const mutation = useRetryClassSessionProvisioning(session.id);
  const [error, setError] = useState<string | null>(null);
  if (session.providerStatus !== "FAILED" && session.providerStatus !== "PENDING") return null;

  return (
    <div className="flex flex-col gap-2">
      <Button
        type="button"
        variant="outline"
        disabled={mutation.isPending}
        aria-busy={mutation.isPending}
        onClick={async () => {
          setError(null);
          try {
            await mutation.mutateAsync();
          } catch (err) {
            setError(isApiClientError(err) ? err.message : "Could not retry provisioning. Please try again.");
          }
        }}
      >
        <RotateCcw aria-hidden="true" />
        {mutation.isPending ? "Retrying…" : "Retry provisioning"}
      </Button>
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function StatusTransitionActions({ session }: { session: ClassSessionResponse }) {
  const startMutation = useStartClassSession(session.id);
  const completeMutation = useCompleteClassSession(session.id);
  const cancelMutation = useCancelClassSession(session.id);
  const [error, setError] = useState<string | null>(null);

  const isPending = startMutation.isPending || completeMutation.isPending || cancelMutation.isPending;

  async function run(mutation: { mutateAsync: () => Promise<ClassSessionResponse> }) {
    setError(null);
    try {
      await mutation.mutateAsync();
    } catch (err) {
      setError(isApiClientError(err) ? err.message : "That action could not be completed. Please try again.");
    }
  }

  const canStart = session.status === "SCHEDULED";
  const canComplete = session.status === "LIVE";
  const canCancel = session.status === "SCHEDULED" || session.status === "LIVE";

  if (!canStart && !canComplete && !canCancel) return null;

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap gap-2">
        {canStart ? (
          <Button type="button" disabled={isPending} aria-busy={isPending} onClick={() => run(startMutation)}>
            {startMutation.isPending ? "Starting…" : "Start class"}
          </Button>
        ) : null}
        {canComplete ? (
          <Button type="button" disabled={isPending} aria-busy={isPending} onClick={() => run(completeMutation)}>
            {completeMutation.isPending ? "Completing…" : "Mark completed"}
          </Button>
        ) : null}
        {canCancel ? (
          <Button
            type="button"
            variant="outline"
            disabled={isPending}
            aria-busy={isPending}
            onClick={() => run(cancelMutation)}
          >
            {cancelMutation.isPending ? "Cancelling…" : "Cancel class"}
          </Button>
        ) : null}
      </div>
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function JoinAsHostAction({ session }: { session: ClassSessionResponse }) {
  const mutation = useJoinClassSession(session.id);
  const [error, setError] = useState<string | null>(null);
  if (session.status !== "LIVE" || session.providerStatus !== "PROVISIONED") return null;

  return (
    <div className="flex flex-col gap-2">
      <Button
        type="button"
        disabled={mutation.isPending}
        aria-busy={mutation.isPending}
        onClick={async () => {
          setError(null);
          try {
            const result = await mutation.mutateAsync();
            window.open(result.joinUrl, "_blank", "noopener,noreferrer");
          } catch (err) {
            setError(isApiClientError(err) ? err.message : "Could not join the class. Please try again.");
          }
        }}
      >
        {mutation.isPending ? "Preparing join link…" : "Join as host"}
      </Button>
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function RecordingSection({ session }: { session: ClassSessionResponse }) {
  const mutation = useClassSessionRecording(session.id);
  const [error, setError] = useState<string | null>(null);
  if (session.status !== "COMPLETED") return null;

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div>
        <h2 className="text-sm font-medium text-foreground">Recording</h2>
        <p className="text-xs text-muted-foreground">
          Playback links are minted fresh each time and expire shortly after — request one only when
          you&apos;re ready to watch.
        </p>
      </div>
      <Button
        type="button"
        variant="outline"
        className="w-fit"
        disabled={mutation.isPending}
        aria-busy={mutation.isPending}
        onClick={async () => {
          setError(null);
          try {
            const result = await mutation.mutateAsync();
            window.open(result.playbackUrl, "_blank", "noopener,noreferrer");
          } catch (err) {
            setError(
              isApiClientError(err) ? err.message : "Could not load the recording. Please try again."
            );
          }
        }}
      >
        {mutation.isPending ? "Preparing playback link…" : "Watch recording"}
      </Button>
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function LiveClassDetailContent() {
  const params = useParams<{ sessionId: string }>();
  const sessionId = params.sessionId;
  const searchParams = useSearchParams();
  const justCreated = searchParams.get("created") === "1";
  const query = useClassSession(sessionId);

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/teacher/live-classes"
          className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          ← Back to live classes
        </Link>
      </div>

      {justCreated ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>Live class scheduled.</AlertDescription>
        </Alert>
      ) : null}

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading live class…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
        genericErrorMessage="This live class could not be found."
      >
        {(session) => (
          <div className="flex flex-col gap-6">
            <div className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-xl font-semibold text-foreground">{session.title}</h1>
                <ClassSessionStatusBadge status={session.status} />
                <ProviderStatusBadge status={session.providerStatus} />
              </div>
              {session.description ? (
                <p className="text-sm text-muted-foreground">{session.description}</p>
              ) : null}
              <p className="text-sm text-muted-foreground">
                {formatDateTime(session.scheduledStart)} – {formatDateTime(session.scheduledEnd)}
              </p>
              {session.providerStatus === "FAILED" && session.providerFailureReason ? (
                <Alert>
                  <AlertDescription>
                    Meeting provisioning failed: {session.providerFailureReason}
                  </AlertDescription>
                </Alert>
              ) : null}
            </div>

            <div className="flex flex-wrap items-start gap-3">
              {session.status === "SCHEDULED" ? (
                <Link
                  href={`/teacher/live-classes/${session.id}/edit`}
                  className={buttonVariants({ variant: "outline" })}
                >
                  Edit
                </Link>
              ) : null}
              <RetryProvisioningAction session={session} />
              <StatusTransitionActions session={session} />
              <JoinAsHostAction session={session} />
            </div>

            <RecordingSection session={session} />
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}

export default function LiveClassDetailPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading live class…" />}>
      <LiveClassDetailContent />
    </Suspense>
  );
}
