"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LiveRegion } from "@/components/ui/live-region";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { SlipStatusBadge, SlipFlagBadge } from "@/components/payments/status-badges";
import { formatDateTime, formatMoney } from "@/lib/format";
import { useAuth } from "@/lib/auth/auth-context";
import { canReviewSlips } from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import { useSlip, useSlipDownloadUrl } from "@/lib/api/payment-slips";
import { ApproveSlipDialog } from "./approve-slip-dialog";
import { RejectSlipDialog } from "./reject-slip-dialog";

/**
 * "View slip file" action — mirrors `useMaterialDownloadUrl`'s call site
 * pattern exactly: fetch a fresh, short-lived signed URL on click and
 * immediately `window.open` it, never render/store the raw URL.
 */
function ViewSlipFileButton({ slipId }: { slipId: string }) {
  const mutation = useSlipDownloadUrl();
  const [error, setError] = useState<string | null>(null);

  async function handleClick() {
    setError(null);
    try {
      const result = await mutation.mutateAsync(slipId);
      window.open(result.url, "_blank", "noopener,noreferrer");
    } catch (fetchError) {
      setError(
        isApiClientError(fetchError)
          ? fetchError.message
          : "Couldn't open the slip file. Please try again."
      );
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <Button
        type="button"
        variant="outline"
        onClick={handleClick}
        disabled={mutation.isPending}
        aria-busy={mutation.isPending}
      >
        {mutation.isPending ? "Opening…" : "View slip file"}
      </Button>
      <LiveRegion message={mutation.isPending ? "Opening slip file…" : ""} />
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/**
 * Tenant Admin Slip Detail (SLIP-3/SLIP-4). `GET
 * /api/v1/payment-slips/{slipId}` — owner student OR staff
 * `PAYMENTS_SLIPS`/`VIEW`. Renders the full, append-only flag history
 * verbatim (never deduplicated/hidden), and only ever what the backend
 * actually returned — this page computes zero duplicate-detection signal
 * itself.
 *
 * Approve/Reject actions render only when `canReviewSlips(role)` is true
 * AND `slip.status === "UNDER_REVIEW"` — both UX convenience only; the
 * mutation endpoints independently 403/409 otherwise, and both dialogs
 * surface a forced failure inline rather than silently swallowing it. A
 * terminal `APPROVED`/`REJECTED` slip shows no actions at all, since there
 * is no reversal endpoint.
 */
export default function TenantAdminSlipDetailPage() {
  const params = useParams<{ slipId: string }>();
  const slipId = params.slipId;
  const { session } = useAuth();
  const canReview = canReviewSlips(session?.role ?? null);

  const query = useSlip(slipId);

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-6">
      <div>
        <Link
          href="/tenant-admin/payments/slip-review"
          className="inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-4" aria-hidden="true" />
          Back to slip review queue
        </Link>
      </div>

      <div>
        <h1 className="text-xl font-semibold text-foreground">Payment slip detail</h1>
        <p className="text-sm text-muted-foreground">
          Full flag history and review actions for this manual payment slip.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading slip…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(slip) => (
          <div className="flex flex-col gap-6">
            <div className="flex flex-col gap-4 rounded-lg border border-border p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-lg font-medium text-foreground">
                  Reference: {slip.referenceNumber}
                </h2>
                <SlipStatusBadge status={slip.status} />
              </div>

              <dl className="grid grid-cols-1 gap-x-4 gap-y-2 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Student</dt>
                  <dd className="text-foreground">{slip.studentEmail ?? "—"}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Order</dt>
                  <dd className="text-foreground">{slip.orderId}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Amount</dt>
                  <dd className="text-foreground">
                    {formatMoney(slip.orderAmount, slip.orderCurrency)}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Submitted</dt>
                  <dd className="text-foreground">{formatDateTime(slip.submittedAt)}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Reviewed</dt>
                  <dd className="text-foreground">
                    {slip.reviewedAt ? formatDateTime(slip.reviewedAt) : "Not yet reviewed"}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Reviewer</dt>
                  <dd className="text-foreground">{slip.reviewerEmail ?? "—"}</dd>
                </div>
              </dl>

              <ViewSlipFileButton slipId={slip.id} />
            </div>

            <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
              <h3 className="text-sm font-medium text-foreground">Flag history</h3>
              {slip.flags.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  No duplicate flags were detected on this slip.
                </p>
              ) : (
                <ul className="flex flex-col gap-2">
                  {slip.flags.map((flag) => (
                    <li key={flag.id} className="flex flex-wrap items-center gap-2 text-sm">
                      <SlipFlagBadge flagType={flag.flagType} />
                      <span className="text-xs text-muted-foreground">
                        Detected {formatDateTime(flag.detectedAt)}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </div>

            {canReview && slip.status === "UNDER_REVIEW" ? (
              <div className="flex flex-wrap gap-2">
                <ApproveSlipDialog slip={slip} />
                <RejectSlipDialog slip={slip} />
              </div>
            ) : null}
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
