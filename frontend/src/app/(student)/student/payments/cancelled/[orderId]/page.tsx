"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { XCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { useOrder } from "@/lib/api/payments";
import { useT } from "@/i18n/i18n-provider";

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
  const t = useT();

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold break-words text-foreground">{t("student.payment.cancelled.title")}</h1>
        <p className="text-sm text-muted-foreground">{t("student.payment.cancelled.subtitle")}</p>
      </div>

      <Alert role="status">
        <XCircle aria-hidden="true" />
        <AlertDescription>
          {t("student.payment.cancelled.notice")}
        </AlertDescription>
      </Alert>

      <div className="flex flex-wrap gap-3">
        {courseId ? (
          <Link
            href={`/student/checkout/${courseId}`}
            className="inline-flex rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/80"
          >
            {t("student.payment.backToCheckout")}
          </Link>
        ) : null}
        <Link
          href="/student/courses"
          className="inline-flex rounded-lg border border-border px-3 py-1.5 text-sm font-medium text-foreground hover:bg-muted"
        >
          {t("student.payment.myCourses")}
        </Link>
      </div>
    </div>
  );
}
