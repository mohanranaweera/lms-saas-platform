"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ClassSessionStatusBadge } from "@/components/live-classes/class-session-status-badge";
import { ProviderStatusBadge } from "@/components/live-classes/provider-status-badge";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageLiveClassesStaff } from "@/lib/auth/permissions";
import { useClassSession, useRetryClassSessionProvisioning } from "@/lib/api/class-sessions";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";

/**
 * Tenant Admin Live Class detail (Wave 4 plan §5) — deliberately its own
 * read-mostly route rather than reusing the Teacher route
 * (`teacher/live-classes/[sessionId]`): that route renders inside the
 * Teacher Portal shell/nav, and a Tenant Admin viewing it there would show
 * the wrong role/portal context (`.claude/rules/ui-ux.md` §1's "whose data
 * am I looking at, and as whom" requirement) despite the backend permitting
 * the read. No status-transition/edit/join-as-host actions here — this
 * screen is oversight-only; only "Retry provisioning" is offered, and only
 * for a caller holding `LIVE_CLASSES`/`CREATE_EDIT` (`canManageLiveClassesStaff`).
 */
export default function TenantAdminLiveClassDetailPage() {
  const params = useParams<{ sessionId: string }>();
  const sessionId = params.sessionId;
  const { session: authSession } = useAuth();
  const canManage = canManageLiveClassesStaff(authSession?.role ?? null);
  const query = useClassSession(sessionId);
  const retryMutation = useRetryClassSessionProvisioning(sessionId);
  const [retryError, setRetryError] = useState<string | null>(null);

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/tenant-admin/live-classes"
          className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          ← Back to live classes
        </Link>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading live class…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        genericErrorMessage="This live class could not be found."
      >
        {(classSession) => (
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-2">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-xl font-semibold text-foreground">{classSession.title}</h1>
                <ClassSessionStatusBadge status={classSession.status} />
                <ProviderStatusBadge status={classSession.providerStatus} />
              </div>
              {classSession.description ? (
                <p className="text-sm text-muted-foreground">{classSession.description}</p>
              ) : null}
              <p className="text-sm text-muted-foreground">
                {formatDateTime(classSession.scheduledStart)} – {formatDateTime(classSession.scheduledEnd)}
              </p>
              {classSession.providerStatus === "FAILED" && classSession.providerFailureReason ? (
                <Alert>
                  <AlertDescription>
                    Meeting provisioning failed: {classSession.providerFailureReason}
                  </AlertDescription>
                </Alert>
              ) : null}
            </div>

            {canManage && (classSession.providerStatus === "FAILED" || classSession.providerStatus === "PENDING") ? (
              <div className="flex flex-col items-start gap-1">
                <Button
                  type="button"
                  variant="outline"
                  disabled={retryMutation.isPending}
                  aria-busy={retryMutation.isPending}
                  onClick={async () => {
                    setRetryError(null);
                    try {
                      await retryMutation.mutateAsync();
                    } catch (err) {
                      setRetryError(
                        isApiClientError(err) ? err.message : "Retry failed. Please try again."
                      );
                    }
                  }}
                >
                  {retryMutation.isPending ? "Retrying…" : "Retry provisioning"}
                </Button>
                {retryError ? (
                  <p role="alert" className="text-xs text-destructive">
                    {retryError}
                  </p>
                ) : null}
              </div>
            ) : null}
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
