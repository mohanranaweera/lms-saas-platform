"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { AlertCircle, CheckCircle2 } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import {
  PROVIDER_LABELS,
  useConfirmPendingConnection,
  useTeacherIntegrations,
  type TeacherMediaProvider,
} from "@/lib/api/integrations";
import { TeacherVideoLibraryCard } from "@/components/integrations/bunny-libraries-section";
import { TeacherProviderCard } from "@/components/integrations/teacher-provider-card";
import { LoadingState } from "@/components/states/loading-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Alert, AlertDescription } from "@/components/ui/alert";

type CallbackResult =
  | { kind: "pending"; pendingId: string; provider: string }
  | { kind: "denied" | "failed"; provider: string };

function providerLabel(value: string | null): string {
  const key = (value ?? "").toUpperCase() as TeacherMediaProvider;
  return PROVIDER_LABELS[key] ?? "the provider";
}

/**
 * Handles the one-time result the backend's OAuth callback redirect added:
 * `?pending=<id>&provider=` (finish connecting) or `?error=denied|failed`.
 *
 * A pending result is confirmed exactly once with the signed-in teacher's
 * own session - the backend saves the connection only for the teacher who
 * started it (security review S1). The ref guard stops a second POST (e.g.
 * React StrictMode re-running effects), which would hit the already-consumed
 * id. The query string is then removed from the URL.
 */
function useCallbackResult() {
  const searchParams = useSearchParams();
  const pathname = usePathname();
  const confirm = useConfirmPendingConnection();
  const started = useRef(false);
  const [result] = useState<CallbackResult | null>(() => {
    const pendingId = searchParams.get("pending");
    const error = searchParams.get("error");
    const provider = providerLabel(searchParams.get("provider"));
    if (pendingId) return { kind: "pending", pendingId, provider };
    if (error) return { kind: error === "denied" ? "denied" : "failed", provider };
    return null;
  });
  const { mutate } = confirm;

  useEffect(() => {
    if (result?.kind === "pending" && !started.current) {
      started.current = true;
      mutate(result.pendingId);
    }
    if (searchParams.get("pending") || searchParams.get("error")) {
      // Native history API: the App Router keeps useSearchParams in sync, and
      // unlike router.replace to the same pathname it reliably drops the query.
      window.history.replaceState(null, "", pathname);
    }
  }, [result, mutate, searchParams, pathname]);

  return { result, confirm };
}

function CallbackAlert({
  result,
  confirm,
}: {
  result: CallbackResult;
  confirm: ReturnType<typeof useConfirmPendingConnection>;
}) {
  if (result.kind === "pending") {
    if (confirm.isSuccess) {
      return (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>{result.provider} is now connected.</AlertDescription>
        </Alert>
      );
    }
    if (confirm.isError) {
      return (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            {isApiClientError(confirm.error)
              ? confirm.error.message
              : `${result.provider} could not be connected. Please try again.`}
          </AlertDescription>
        </Alert>
      );
    }
    return (
      <p role="status" aria-busy="true" className="text-sm text-muted-foreground">
        Finishing connecting {result.provider}…
      </p>
    );
  }
  return (
    <Alert variant="destructive">
      <AlertCircle aria-hidden="true" />
      <AlertDescription>
        {result.kind === "denied"
          ? `${result.provider} was not connected because access wasn't allowed.`
          : `${result.provider} could not be connected. Please try again.`}
      </AlertDescription>
    </Alert>
  );
}

function TeacherIntegrationsContent() {
  const query = useTeacherIntegrations();
  const { result, confirm } = useCallbackResult();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/teacher/settings"
          className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          ← Settings
        </Link>
        <h1 className="mt-2 text-xl font-semibold text-foreground">Integrations</h1>
        <p className="text-sm text-muted-foreground">
          Connect your own accounts. Only you can connect them; your institute can see which accounts are connected
          but never your passwords or access tokens.
        </p>
      </div>

      {result ? <CallbackAlert result={result} confirm={confirm} /> : null}

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading your integrations…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
      >
        {(data) => (
          <div className="flex max-w-2xl flex-col gap-4">
            {data.providers.map((provider) => (
              <TeacherProviderCard key={provider.provider} view={provider} />
            ))}
            <TeacherVideoLibraryCard bunnyConnected={data.bunnyConnected} library={data.bunnyLibrary} />
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}

/** Teacher → Settings → Integrations (MEDIA-INT-01 / ADR-018). */
export default function TeacherIntegrationsPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading your integrations…" />}>
      <TeacherIntegrationsContent />
    </Suspense>
  );
}
