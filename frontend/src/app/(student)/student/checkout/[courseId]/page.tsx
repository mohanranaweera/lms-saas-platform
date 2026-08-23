"use client";

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { LiveRegion } from "@/components/ui/live-region";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useCourse } from "@/lib/api/courses";
import { useCreateOrder, useInitiatePayment } from "@/lib/api/payments";
import { isApiClientError } from "@/lib/api/error";

/**
 * Checkout entry point (PAY-1/PAY-2). Shows the course's server-computed
 * name/price/access as read-only display only — there is no editable price
 * field anywhere on this screen, structurally: `CourseResponse.price` is
 * only ever rendered via `.toFixed(2)` text, never bound to a form input,
 * and `OrderCreateRequest`/`useCreateOrder` only ever sends `{ courseId }`
 * (see `lib/api/payments.ts` — the type has no `price`/`amount` field to
 * even populate).
 *
 * "Enroll" creates the order, then immediately initiates a gateway payment
 * attempt for it, then redirects to the awaiting-confirmation screen for
 * that order id. It never redirects to `PaymentInitiationResponse
 * .redirectTarget` itself and never marks anything "paid" here — that is
 * exclusively the awaiting-confirmation screen's job, reading only the
 * polled `GET /api/v1/orders/{id}/payment-status` response.
 */
export default function CheckoutPage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;
  const router = useRouter();

  const courseQuery = useCourse(courseId);
  const createOrder = useCreateOrder();
  const initiatePayment = useInitiatePayment();
  const [flowError, setFlowError] = useState<string | null>(null);

  const isSubmitting = createOrder.isPending || initiatePayment.isPending;

  async function handleEnroll() {
    setFlowError(null);
    try {
      const order = await createOrder.mutateAsync({ courseId });
      await initiatePayment.mutateAsync(order.id);
      router.push(`/student/payments/awaiting-confirmation/${order.id}`);
    } catch (error) {
      setFlowError(
        isApiClientError(error) ? error.message : "Something went wrong. Please try again."
      );
    }
  }

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Checkout</h1>
        <p className="text-sm text-muted-foreground">
          Review the course details below, then continue to payment.
        </p>
      </div>

      <QueryStateBoundary
        query={courseQuery}
        loadingLabel="Loading course…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
      >
        {(course) => (
          <div className="flex flex-col gap-4 rounded-lg border border-border p-4">
            <div>
              <h2 className="text-lg font-medium text-foreground">{course.name}</h2>
              <p className="text-sm text-muted-foreground">{course.category}</p>
            </div>

            <dl className="grid grid-cols-2 gap-4 rounded-lg border border-border p-4 text-sm">
              <div>
                <dt className="text-xs font-medium text-muted-foreground">Price</dt>
                {/* Read-only display only — never a student-editable input. */}
                <dd className="text-base font-semibold text-foreground">
                  {course.price.toFixed(2)}
                </dd>
              </div>
              <div>
                <dt className="text-xs font-medium text-muted-foreground">Access</dt>
                <dd className="text-foreground">
                  {course.accessDurationDays
                    ? `${course.accessDurationDays} days`
                    : "Lifetime access"}
                </dd>
              </div>
            </dl>

            {course.status !== "PUBLIC" ? (
              <Alert role="status">
                <AlertCircle aria-hidden="true" />
                <AlertDescription>
                  This course isn&apos;t currently open for enrollment.
                </AlertDescription>
              </Alert>
            ) : null}

            {flowError ? (
              <Alert variant="destructive">
                <AlertCircle aria-hidden="true" />
                <AlertDescription>{flowError}</AlertDescription>
              </Alert>
            ) : null}

            <LiveRegion message={isSubmitting ? "Starting checkout…" : ""} />

            <Button
              type="button"
              onClick={handleEnroll}
              disabled={isSubmitting || course.status !== "PUBLIC"}
              aria-busy={isSubmitting}
            >
              {isSubmitting ? "Starting checkout…" : "Enroll"}
            </Button>
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
