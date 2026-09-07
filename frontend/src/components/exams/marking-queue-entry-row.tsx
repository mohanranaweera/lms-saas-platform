"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { useAuth } from "@/lib/auth/auth-context";
import { canMarkExamAnswers } from "@/lib/auth/permissions";
import { useMarkAnswer, type MarkingQueueEntryResponse } from "@/lib/api/exams";
import { isApiClientError } from "@/lib/api/error";
import { markingScoreSchema, type MarkingScoreFormValues } from "@/lib/validation/exams";

/**
 * One Marking Queue row (screen #6) — a real, labeled, keyboard-operable
 * numeric score input (`.claude/rules/ui-ux.md` §4), never an unlabeled
 * widget. Every question (MCQ or STRUCTURED) is worth a fixed one point
 * (`MarkAnswerRequest`'s `@DecimalMax(1.00)`,
 * `ResultsPublishingService.POINTS_PER_QUESTION`) — the label/description
 * say "out of 1 point" accordingly, and the input's `max` attribute mirrors
 * the same bound as a UX convenience (the backend independently re-validates
 * it).
 *
 * The score input and "Save score" action are hidden entirely (not just
 * disabled) for a caller who cannot mark (`canMarkExamAnswers`) — in
 * particular a `READ_ONLY_AUDITOR`, who holds `DomainArea.EXAMS`/`VIEW` only
 * per `MarkingQueueService#requireMarkingAccess`, must still see this row's
 * question/response data but no way to submit a score. UX convenience only;
 * the real enforcement is that service's own server-side check.
 */
export function MarkingQueueEntryRow({ examId, entry }: { examId: string; entry: MarkingQueueEntryResponse }) {
  const { session } = useAuth();
  const canMark = canMarkExamAnswers(session?.role ?? null);
  const mutation = useMarkAnswer(examId);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [marked, setMarked] = useState(false);

  const inputId = `marking-score-${entry.answerId}`;
  const helpId = `${inputId}-help`;
  const errorId = `${inputId}-error`;

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<MarkingScoreFormValues>({
    resolver: zodResolver(markingScoreSchema),
    defaultValues: { manualScore: "0" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setSubmitError(null);
    try {
      await mutation.mutateAsync({ answerId: entry.answerId, body: { manualScore: Number(values.manualScore) } });
      setMarked(true);
    } catch (error) {
      setSubmitError(isApiClientError(error) ? error.message : "Could not save this score. Please try again.");
    }
  });

  if (marked) {
    return (
      <li className="rounded-lg border border-border bg-muted/30 p-4">
        <p className="text-sm text-muted-foreground">Marked. This answer has left the queue.</p>
      </li>
    );
  }

  return (
    <li className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <p className="text-sm text-foreground">{entry.questionBody}</p>
      <p className="whitespace-pre-wrap rounded-md bg-muted/40 p-2 text-sm text-muted-foreground">
        {entry.response ?? <span className="italic">No answer submitted.</span>}
      </p>
      {canMark ? (
        <form
          className="flex flex-col gap-2 sm:flex-row sm:items-end sm:gap-3"
          noValidate
          aria-busy={mutation.isPending}
          onSubmit={onSubmit}
        >
          <LiveRegion message={mutation.isPending ? "Saving score…" : ""} />
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={inputId}>{`Score for question, out of 1 point — ${entry.questionBody.slice(0, 40)}`}</Label>
            <Input
              id={inputId}
              type="number"
              min={0}
              max={1}
              step="0.5"
              aria-required="true"
              aria-invalid={!!errors.manualScore}
              aria-describedby={[helpId, errors.manualScore ? errorId : undefined].filter(Boolean).join(" ")}
              {...register("manualScore")}
            />
            <p id={helpId} className="text-xs text-muted-foreground">
              Enter a score between 0 and 1 for this answer.
            </p>
            {errors.manualScore ? (
              <p id={errorId} role="alert" className="text-xs text-destructive">
                {errors.manualScore.message}
              </p>
            ) : null}
          </div>
          <Button type="submit" disabled={mutation.isPending} aria-busy={mutation.isPending} className="sm:w-fit">
            {mutation.isPending ? "Saving…" : "Save score"}
          </Button>
        </form>
      ) : (
        <p className="text-xs text-muted-foreground">You do not have permission to mark this answer.</p>
      )}
      {submitError ? (
        <p role="alert" className="text-sm text-destructive">
          {submitError}
        </p>
      ) : null}
    </li>
  );
}
