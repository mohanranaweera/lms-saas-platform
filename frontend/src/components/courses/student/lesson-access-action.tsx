"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { ChevronRight, Clock, CreditCard, Loader2, Lock } from "lucide-react";
import { cn } from "@/lib/utils";
import type { LessonNode } from "@/lib/api/klass-student";
import { usePaymentComposer } from "@/components/payments/composer/payment-composer-provider";
import { presetFromPurchase } from "@/components/payments/composer/composer-model";

import { useT, type TranslateFn } from "@/i18n/i18n-provider";
const pill =
  "inline-flex shrink-0 items-center gap-1 rounded-full px-3 py-1 text-xs font-semibold whitespace-nowrap";

/** Red "Pay now" pill (period header, lesson rows, live classes). */
export function PayNowButton({
  onClick,
  label,
  testId,
}: {
  onClick: () => void;
  label: string;
  testId: string;
}) {
  const t = useT();
  return (
    <button
      type="button"
      onClick={onClick}
      aria-label={label}
      aria-haspopup="dialog"
      data-testid={testId}
      className={cn(
        pill,
        "bg-red-600 text-white outline-none hover:bg-red-700 focus-visible:ring-3 focus-visible:ring-red-500/50"
      )}
    >
      <CreditCard className="size-3.5" aria-hidden="true" />
      {t("student.lessonAccessAction.payNow")}
    </button>
  );
}

export function StatusPill({
  tone,
  children,
  testId,
}: {
  tone: "pending" | "paid" | "muted" | "danger";
  children: ReactNode;
  testId?: string;
}) {
  return (
    <span
      data-testid={testId}
      className={cn(
        pill,
        tone === "pending" && "bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300",
        tone === "paid" && "bg-green-100 text-green-800 dark:bg-green-950 dark:text-green-300",
        tone === "muted" && "bg-muted text-muted-foreground",
        tone === "danger" && "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-300"
      )}
    >
      {children}
    </span>
  );
}

/**
 * Right-hand action for a lesson, driven only by the server's
 * `access.status`. ALLOWED lessons open on row click (the caller wires it);
 * this renders the affordance. FREE lessons never show Pay now.
 */
export function LessonAccessAction({
  lesson,
  launching,
  onPay,
  allowedLabel,
}: {
  lesson: LessonNode;
  launching: boolean;
  onPay: (() => void) | null;
  allowedLabel?: string;
}) {
  const t = useT();
  const composer = usePaymentComposer();
  const status = lesson.access.status;
  if (launching) {
    return (
      <span className={cn(pill, "text-muted-foreground")} role="status">
        <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
        {t("student.lessonAccessAction.opening")}
      </span>
    );
  }
  switch (status) {
    case "ALLOWED":
      return (
        <span className={cn(pill, "text-blue-700 dark:text-blue-300")} aria-hidden="true">
          {allowedLabel ?? t("student.lessonAccess.open")}
          <ChevronRight className="size-3.5" />
        </span>
      );
    case "PAYMENT_REQUIRED":
      if (lesson.paymentType !== "FREE" && onPay) {
        return <PayNowButton onClick={onPay} label={t("student.payNowFor", { item: lesson.title })} testId="lesson-pay-now" />;
      }
      if (lesson.paymentType !== "FREE" && composer && lesson.access.requiredPurchase) {
        return <PayNowButton onClick={() => composer.openComposer(presetFromPurchase(lesson.access.requiredPurchase!, { itemLabel: lesson.title }))} label={t("student.payNowFor", { item: lesson.title })} testId="lesson-pay-now" />;
      }
      return (
        <StatusPill tone="danger" testId="lesson-status">
          <Lock className="size-3" aria-hidden="true" />
          {t("student.lessonAccessAction.paymentRequired")}
        </StatusPill>
      );
    case "PAYMENT_PENDING":
      return (
        <StatusPill tone="pending" testId="lesson-status">
          <Clock className="size-3" aria-hidden="true" />
          {t("student.lessonAccessAction.pending")}
        </StatusPill>
      );
    case "NOT_STARTED":
      return (
        <StatusPill tone="muted" testId="lesson-status">
          {t("student.lessonAccessAction.startsSoon")}
        </StatusPill>
      );
    case "EXPIRED":
      return (
        <StatusPill tone="muted" testId="lesson-status">
          {t("student.lessonAccessAction.expired")}
        </StatusPill>
      );
    case "DEVICE_NOT_AUTHORIZED":
      return (
        <Link href="/student/devices" className={cn(pill, "bg-muted text-foreground underline")} data-testid="lesson-status">
          {t("student.lessonAccessAction.deviceNotAllowed")}
        </Link>
      );
    case "NOT_ENROLLED":
      return (
        <StatusPill tone="muted" testId="lesson-status">
          {t("student.lessonAccessAction.enrollToOpen")}
        </StatusPill>
      );
    default:
      return (
        <StatusPill tone="muted" testId="lesson-status">
          {t("student.lessonAccessAction.unavailable")}
        </StatusPill>
      );
  }
}

/** Screen-reader text for a lesson's state. */
export function accessStatusText(lesson: LessonNode, t: TranslateFn): string {
  switch (lesson.access.status) {
    case "ALLOWED":
      return t("student.lessonState.available");
    case "PAYMENT_REQUIRED":
      return t("student.lessonState.paymentRequired");
    case "PAYMENT_PENDING":
      return t("student.lessonState.paymentPending");
    case "NOT_STARTED":
      return t("student.lessonState.notStarted");
    case "EXPIRED":
      return t("student.lessonState.expired");
    case "DEVICE_NOT_AUTHORIZED":
      return t("student.lessonState.device");
    case "NOT_ENROLLED":
      return t("student.lessonState.notEnrolled");
    default:
      return t("student.lessonState.unavailable");
  }
}
