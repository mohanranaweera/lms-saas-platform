"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { XCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { useOrder } from "@/lib/api/payments";

/**
 * PayHere `cancel_url` landing (PAY-GW-01). Reaching this page proves
 * nothing about payment state and changes nothing - it only offers the
 * student a way back. The course link needs the order's course id, read from
 * the student's own order; if that read fails the page still renders with a
 * generic "My courses" link.
 */
export default function PaymentCancelledPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const orderQuery = useOrder(orderId);
  const courseId = orderQuery.data?.courseId;

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Payment cancelled</h1>
        <p className="text-sm text-muted-foreground">
          You left the PayHere payment page before completing the payment.
        </p>
      </div>

      <Alert role="status">
        <XCircle aria-hidden="true" />
        <AlertDescription>
          No money was taken for this attempt. You can try again, or pay by bank transfer instead.
        </AlertDescription>
      </Alert>

      <div className="flex flex-wrap gap-3">
        {courseId ? (
          <Link
            href={`/student/checkout/${courseId}`}
            className="inline-flex rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/80"
          >
            Back to checkout
          </Link>
        ) : null}
        <Link
          href="/student/courses"
          className="inline-flex rounded-lg border border-border px-3 py-1.5 text-sm font-medium text-foreground hover:bg-muted"
        >
          My courses
        </Link>
      </div>
    </div>
  );
}
