"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { CheckCircle2, RotateCcw, XCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { LiveRegion } from "@/components/ui/live-region";
import { LoadingState } from "@/components/states/loading-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useOrder, useOrderPaymentStatus } from "@/lib/api/payments";

/**
 * Awaiting-confirmation screen (PAY-2). Polls `GET
 * /api/v1/orders/{orderId}/payment-status` every 3s (see
 * `useOrderPaymentStatus`'s `refetchInterval`) and renders exclusively based
 * on that response's `status` field.
 *
 * CRITICAL, structural invariant: this component never calls
 * `useSearchParams()` and never reads any redirect/query-string value to
 * decide success — there is no code path here that can be influenced by a
 * spoofed `?status=success`-style param. The ONLY source of truth is the
 * polled API response rendered via `QueryStateBoundary` below.
 *
 * This screen also never claims "you are enrolled" — this module has no
 * student-facing enrollment read endpoint; it only ever reports payment
 * status and links to Payment History once confirmed.
 */
export default function AwaitingConfirmationPage() {
  const params = useParams<{ orderId: string }>();
  const orderId = params.orderId;

  const statusQuery = useOrderPaymentStatus(orderId);
  // Fetched only to build a "Try again" link back to the right checkout
  // page on rejection — never consulted for payment/confirmation state.
  const orderQuery = useOrder(orderId);

  // A background poll (every 3s) can swap the visual branch below from
  // LoadingState to a brand-new Alert element the instant a terminal status
  // arrives — some AT/browser combinations are inconsistent about announcing
  // a freshly-inserted live region versus one that already exists and just
  // has its text mutated. This region is mounted once, at the page's top
  // level (never inside the branch that swaps), and only its text content
  // changes across polls — the same always-mounted pattern already used by
  // `checkout/[courseId]/page.tsx`'s own `LiveRegion`. Wording is
  // deliberately distinct from the visible Alert copy below so this doesn't
  // create a second element matching the same text.
  const liveStatus = statusQuery.data?.status;
  const liveMessage =
    liveStatus === "CONFIRMED"
      ? "Payment status updated: confirmed."
      : liveStatus === "REJECTED"
        ? "Payment status updated: rejected."
        : liveStatus === "REFUNDED"
          ? "Payment status updated: refunded."
          : "";

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Payment status</h1>
        <p className="text-sm text-muted-foreground">
          This page reflects only what our servers have confirmed — it never guesses from a
          redirect.
        </p>
      </div>

      <LiveRegion message={liveMessage} />

      <QueryStateBoundary
        query={statusQuery}
        loadingLabel="Checking payment status…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
      >
        {(paymentStatus) => {
          if (paymentStatus.status === "CONFIRMED") {
            return (
              <Alert role="status">
                <CheckCircle2 aria-hidden="true" />
                <AlertDescription>
                  Payment confirmed. You can view the full record in your{" "}
                  <Link href="/student/payments/history" className="font-medium underline">
                    payment history
                  </Link>
                  .
                </AlertDescription>
              </Alert>
            );
          }

          if (paymentStatus.status === "REJECTED") {
            const retryHref = orderQuery.data
              ? `/student/checkout/${orderQuery.data.courseId}`
              : "/courses";
            return (
              <Alert role="alert" variant="destructive">
                <XCircle aria-hidden="true" />
                <AlertDescription>
                  Your payment was rejected. Nothing was charged and no enrollment was created.{" "}
                  <Link href={retryHref} className="font-medium underline">
                    Try again
                  </Link>
                  .
                </AlertDescription>
              </Alert>
            );
          }

          if (paymentStatus.status === "REFUNDED") {
            return (
              <Alert role="status">
                <RotateCcw aria-hidden="true" />
                <AlertDescription>
                  This payment was refunded. See your{" "}
                  <Link href="/student/payments/history" className="font-medium underline">
                    payment history
                  </Link>{" "}
                  for the full record.
                </AlertDescription>
              </Alert>
            );
          }

          // `hasPaymentAttempt === false` or `status === "PENDING"`: still
          // waiting on the gateway/webhook. Polling continues automatically.
          return (
            <LoadingState label="Awaiting payment confirmation… this page updates automatically." />
          );
        }}
      </QueryStateBoundary>
    </div>
  );
}
