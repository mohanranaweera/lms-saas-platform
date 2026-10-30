"use client";

import { useId, useState } from "react";
import { AlertCircle, BellRing, History, Send, UserCheck, UserMinus } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { AdminFormDialog } from "@/components/courses/admin/admin-dialog";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { ErrorState } from "@/components/states/error-state";
import { formatExamDateTime } from "@/lib/exams/exam-time";
import { randomUuid } from "@/lib/uuid";
import {
  notifiedCount,
  notifyConflictCode,
  useNotifyMarksPreview,
  useSendNotifyMarks,
} from "@/lib/api/exam-results";
import type { ExamDetail } from "@/lib/api/exam-config";

function students(n: number): string {
  return `${n} ${n === 1 ? "student" : "students"}`;
}

/**
 * Notify Marks (Slice E). Mounted once per opening: the `Idempotency-Key` is
 * generated here and reused for every retry of this opening, so a lost
 * response never notifies anyone twice (the server replays the original
 * answer for the same key). The audience comes from the server preview;
 * the server also publishes the results and decides whether a send is
 * allowed (409 `NOT_ENDED` / `ALREADY_NOTIFIED`). A send after an earlier
 * one requires the explicit "Send again" confirmation (`resend: true`).
 */
export function NotifyMarksDialog({
  exam,
  open,
  onOpenChange,
  onSent,
}: {
  exam: ExamDetail;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSent: (message: string) => void;
}) {
  const preview = useNotifyMarksPreview(exam.id, { enabled: open });
  const send = useSendNotifyMarks(exam.id);
  const [idempotencyKey, setIdempotencyKey] = useState(() => randomUuid());
  const [resendChecked, setResendChecked] = useState(false);
  const [resendError, setResendError] = useState<string | null>(null);
  const [error, setError] = useState<{ title: string; text: string } | null>(null);
  const resendId = useId();

  const data = preview.data;
  const alreadyNotifiedAt = data?.alreadyNotifiedAt ?? null;
  const noRecipients = data !== undefined && data.recipients === 0;
  const notEnded = data !== undefined && data.ended === false;

  async function confirm() {
    if (!data) return;
    setError(null);
    if (alreadyNotifiedAt && !resendChecked) {
      setResendError("Tick “Send again” to notify the students a second time.");
      return;
    }
    try {
      const result = await send.mutateAsync({ idempotencyKey, resend: Boolean(alreadyNotifiedAt) });
      const count = notifiedCount(result, data.recipients);
      onSent(
        result?.replayed
          ? `These marks were already sent to ${students(count)}; nothing was sent twice.`
          : `Marks sent to ${students(count)}. Results are now published.`
      );
      onOpenChange(false);
    } catch (failure) {
      const code = notifyConflictCode(failure);
      if (code === "NOT_ENDED") {
        setError({
          title: "The exam hasn't ended yet",
          text: `Marks can be sent once the exam closes on ${formatExamDateTime(exam.endAt)}.`,
        });
      } else if (code === "NO_RECIPIENTS") {
        setError({ title: "No student has marks yet", text: "Enter marks before notifying students. Nothing was sent or published." });
        void preview.refetch();
      } else if (code === "ALREADY_NOTIFIED") {
        // Nothing was sent for this key, so a fresh key is safe for the deliberate resend.
        setIdempotencyKey(randomUuid());
        setResendChecked(false);
        setError({
          title: "Students were already notified",
          text: "Someone sent these marks a moment ago. Review the details below and tick “Send again” only if they should be notified a second time.",
        });
        void preview.refetch();
      } else {
        // Network / server error: keep the same key so a retry can't double-send.
        setError({ title: "The marks weren't sent", text: `${mutationErrorMessage(failure)} You can try again safely.` });
      }
    }
  }

  let body;
  if (preview.status === "pending") {
    body = (
      <div role="status" aria-busy="true" className="flex flex-col gap-3" data-testid="notify-preview-loading">
        <span className="sr-only">Loading who will be notified…</span>
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-10 w-full" />
      </div>
    );
  } else if (preview.status === "error") {
    body = (
      <ErrorState
        message={mutationErrorMessage(preview.error)}
        onRetry={() => void preview.refetch()}
      />
    );
  } else {
    const current = preview.data;
    body = (
      <div className="flex min-w-0 flex-col gap-4" data-testid="notify-preview">
        <dl className="grid gap-3 sm:grid-cols-2">
          <div className="flex min-w-0 items-start gap-3 rounded-lg bg-primary/5 p-3 ring-1 ring-primary/10">
            <UserCheck className="mt-0.5 size-5 shrink-0 text-primary" aria-hidden="true" />
            <div className="min-w-0">
              <dt className="text-sm text-muted-foreground">Will be notified</dt>
              <dd className="text-lg font-semibold text-foreground tabular-nums" data-testid="notify-recipients">
                {students(current.recipients)}
              </dd>
              <dd className="text-xs text-muted-foreground">Enrolled students who have marks</dd>
            </div>
          </div>
          <div className="flex min-w-0 items-start gap-3 rounded-lg bg-muted/50 p-3 ring-1 ring-foreground/10">
            <UserMinus className="mt-0.5 size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <div className="min-w-0">
              <dt className="text-sm text-muted-foreground">Without marks</dt>
              <dd className="text-lg font-semibold text-foreground tabular-nums" data-testid="notify-without-marks">
                {students(current.withoutMarks)}
              </dd>
              <dd className="text-xs text-muted-foreground">Not notified — enter their marks first</dd>
            </div>
          </div>
        </dl>
        <p className="text-sm text-muted-foreground">
          Sending publishes the results: students can see their marks, and answer sheets can no longer be deleted.
        </p>
        {notEnded ? (
          <Alert data-testid="notify-not-ended">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>
              The exam hasn&apos;t ended yet. Marks can be sent once it closes
              {current.endAt ? ` on ${formatExamDateTime(current.endAt)}` : ""}.
            </AlertDescription>
          </Alert>
        ) : noRecipients ? (
          <Alert data-testid="notify-no-recipients">
            <AlertCircle aria-hidden="true" />
            <AlertTitle>No student has marks yet</AlertTitle>
            <AlertDescription>Enter marks first — there is no one to notify, and nothing will be published.</AlertDescription>
          </Alert>
        ) : null}
        {alreadyNotifiedAt ? (
          <div className="flex flex-col gap-2 rounded-lg border border-amber-500/40 bg-amber-500/5 p-3" data-testid="notify-already">
            <p className="flex items-start gap-2 text-sm font-medium text-foreground">
              <History className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
              <span data-testid="notify-already-text">Already notified on {formatExamDateTime(alreadyNotifiedAt)}</span>
            </p>
            <div className="flex items-start gap-2">
              <input
                id={resendId}
                type="checkbox"
                checked={resendChecked}
                onChange={(event) => {
                  setResendChecked(event.target.checked);
                  setResendError(null);
                }}
                required
                aria-required="true"
                aria-invalid={resendError ? true : undefined}
                aria-describedby={resendError ? `${resendId}-error` : `${resendId}-help`}
                className="mt-0.5 size-4 shrink-0 accent-primary"
                data-testid="notify-resend"
              />
              <div className="flex min-w-0 flex-col gap-0.5">
                <label htmlFor={resendId} className="text-sm font-medium text-foreground">
                  Send again
                </label>
                <p id={`${resendId}-help`} className="text-xs text-muted-foreground">
                  Every student with marks gets the notification again, including those already notified.
                </p>
                {resendError ? (
                  <p id={`${resendId}-error`} role="alert" className="text-xs text-destructive" data-testid="notify-resend-error">
                    {resendError}
                  </p>
                ) : null}
              </div>
            </div>
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <AdminFormDialog
      open={open}
      onOpenChange={(next) => {
        if (!send.isPending) onOpenChange(next);
      }}
      title="Notify marks"
      description={`Send ${exam.name} marks to students by notification.`}
      testId="notify-marks-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={send.isPending}>
            Cancel
          </Button>
          <Button
            type="button"
            onClick={() => void confirm()}
            disabled={send.isPending || preview.status !== "success" || noRecipients || notEnded}
            aria-busy={send.isPending}
            data-testid="notify-confirm"
          >
            {alreadyNotifiedAt ? <BellRing aria-hidden="true" /> : <Send aria-hidden="true" />}
            {send.isPending ? "Sending…" : alreadyNotifiedAt ? "Send again" : "Send marks"}
          </Button>
        </>
      }
    >
      <div className="flex min-w-0 flex-col gap-4">
        {error ? (
          <Alert variant="destructive" data-testid="notify-error">
            <AlertCircle aria-hidden="true" />
            <AlertTitle>{error.title}</AlertTitle>
            <AlertDescription>{error.text}</AlertDescription>
          </Alert>
        ) : null}
        {body}
      </div>
    </AdminFormDialog>
  );
}
