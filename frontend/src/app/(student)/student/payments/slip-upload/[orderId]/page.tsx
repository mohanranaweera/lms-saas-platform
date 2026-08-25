"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { SlipStatusBadge, SlipFlagBadge } from "@/components/payments/status-badges";
import { useOrder } from "@/lib/api/payments";
import type { PaymentSlipResponse } from "@/lib/api/payment-slips";
import { SlipUploadForm } from "./slip-upload-form";

/**
 * Student Payment Slip Upload (SLIP-1). Route param `orderId` — the order
 * must already exist and belong to the caller (created via the checkout
 * page's "Pay by bank transfer" action). Order fetch/loading/error/
 * permission-denied states are handled by `QueryStateBoundary`, exactly like
 * `checkout/[courseId]/page.tsx`.
 *
 * On a successful upload, the form is replaced by a success panel — this
 * page never redirects automatically and never claims "paid"/"confirmed":
 * enrollment activates only after a backend-confirmed reviewer approval,
 * never from this screen (`.claude/rules/payments.md` §2). There is also no
 * link to Payment History here — a slip-based order never produces a ledger
 * entry in this backend's current shape (module plan §17), so linking there
 * would be misleading; the way back is the student dashboard instead.
 */
export default function SlipUploadPage() {
  const params = useParams<{ orderId: string }>();
  const orderId = params.orderId;

  const orderQuery = useOrder(orderId);
  const [uploadedSlip, setUploadedSlip] = useState<PaymentSlipResponse | null>(null);

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Upload payment slip</h1>
        <p className="text-sm text-muted-foreground">
          Already sent a bank transfer? Enter the reference number and upload your slip for
          review.
        </p>
      </div>

      <QueryStateBoundary
        query={orderQuery}
        loadingLabel="Loading order…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
      >
        {() =>
          uploadedSlip ? (
            <div className="flex flex-col gap-4 rounded-lg border border-border p-4">
              <div className="flex flex-wrap items-center gap-2">
                <SlipStatusBadge status={uploadedSlip.status} />
                <span className="text-sm text-muted-foreground">
                  Reference: {uploadedSlip.referenceNumber}
                </span>
              </div>

              <p className="text-sm text-foreground">
                Submitted — under review. Your course access will unlock once a reviewer approves
                this slip.
              </p>

              {uploadedSlip.flags.length > 0 ? (
                <div className="flex flex-col gap-2 rounded-md border border-warning/30 bg-warning/5 p-3">
                  <p className="text-xs text-muted-foreground">
                    This slip was automatically flagged for a reviewer to take a closer look. This
                    is informational only — it is not a rejection, and no action is needed from
                    you.
                  </p>
                  <div className="flex flex-wrap gap-2">
                    {uploadedSlip.flags.map((flag) => (
                      <SlipFlagBadge key={flag.id} flagType={flag.flagType} />
                    ))}
                  </div>
                </div>
              ) : null}

              <Link
                href="/student/dashboard"
                className="text-sm font-medium text-foreground hover:underline"
              >
                Back to dashboard
              </Link>
            </div>
          ) : (
            <SlipUploadForm orderId={orderId} onUploaded={setUploadedSlip} />
          )
        }
      </QueryStateBoundary>
    </div>
  );
}
