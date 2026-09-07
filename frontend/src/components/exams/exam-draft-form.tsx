"use client";

import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/states/error-state";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ExamQuestionPicker } from "@/components/exams/exam-question-picker";
import { useAuth } from "@/lib/auth/auth-context";
import { canAuthorExams } from "@/lib/auth/permissions";
import { useCourseQuestions, useUpdateDraftExam, type ExamResponse } from "@/lib/api/exams";
import { isApiClientError } from "@/lib/api/error";
import { datetimeLocalToIso, isoToDatetimeLocal } from "@/lib/datetime";
import { examDraftSchema, type ExamDraftFormValues } from "@/lib/validation/exams";

/**
 * Exam draft edit form (screen #5) — title/window/time-limit/linked-questions,
 * only legal while `status === "DRAFT"` (409 otherwise, per `ExamUpdateRequest`'s
 * contract). Fields are locked read-only once the exam has left `DRAFT`
 * rather than letting a submit round-trip fail with a confusing 409.
 *
 * Also read-only, regardless of `DRAFT` status, for a caller who cannot
 * author exams (`canAuthorExams` — UX convenience only; `ExamSchedulingService`'s
 * own `requireAuthoringAccess` remains the real enforcement on `PUT` for any
 * stale client that submits anyway).
 */
export function ExamDraftForm({ exam }: { exam: ExamResponse }) {
  const { session } = useAuth();
  const isAuthorized = canAuthorExams(session?.role ?? null);
  const isDraft = exam.status === "DRAFT";
  const questionsQuery = useCourseQuestions(exam.courseId, { size: 100 });
  const mutation = useUpdateDraftExam(exam.id);
  const [pageError, setPageError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    control,
    setValue,
    formState: { errors },
  } = useForm<ExamDraftFormValues>({
    resolver: zodResolver(examDraftSchema),
    defaultValues: {
      title: exam.title,
      scheduledStart: isoToDatetimeLocal(exam.scheduledStart),
      scheduledEnd: isoToDatetimeLocal(exam.scheduledEnd),
      timeLimitMinutes: String(exam.timeLimitMinutes),
      questionIds: exam.questions.map((question) => question.id),
    },
  });

  const questionIds = useWatch({ control, name: "questionIds" });
  const isSubmitting = mutation.isPending;
  const canEdit = isDraft && isAuthorized;
  const disabled = !canEdit || isSubmitting;

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync({
        title: values.title,
        scheduledStart: datetimeLocalToIso(values.scheduledStart),
        scheduledEnd: datetimeLocalToIso(values.scheduledEnd),
        timeLimitMinutes: Number(values.timeLimitMinutes),
        questionIds: values.questionIds,
      });
    } catch (error) {
      setPageError(isApiClientError(error) ? error.message : "An unexpected error occurred. Please try again.");
    }
  });

  return (
    <form className="flex flex-col gap-4" noValidate aria-busy={isSubmitting} onSubmit={onSubmit}>
      <LiveRegion message={isSubmitting ? "Saving exam details…" : ""} />
      {!isDraft ? (
        <Alert>
          <AlertDescription>
            This exam has left Draft status (it is now {exam.status}) and can no longer be edited here.
          </AlertDescription>
        </Alert>
      ) : null}
      {isDraft && !isAuthorized ? (
        <Alert>
          <AlertDescription>
            You do not have permission to edit this exam&apos;s details. Shown read-only.
          </AlertDescription>
        </Alert>
      ) : null}
      {pageError ? <ErrorState message={pageError} onRetry={() => setPageError(null)} /> : null}

      <fieldset disabled={disabled} className="flex flex-col gap-4">
        <legend className="sr-only">Exam details</legend>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="exam-draft-title">Title</Label>
          <Input
            id="exam-draft-title"
            aria-required="true"
            aria-invalid={!!errors.title}
            aria-describedby={errors.title ? "exam-draft-title-error" : undefined}
            {...register("title")}
          />
          {errors.title ? (
            <p id="exam-draft-title-error" role="alert" className="text-xs text-destructive">
              {errors.title.message}
            </p>
          ) : null}
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="exam-draft-start">Window start</Label>
            <Input
              id="exam-draft-start"
              type="datetime-local"
              aria-required="true"
              aria-invalid={!!errors.scheduledStart}
              aria-describedby={errors.scheduledStart ? "exam-draft-start-error" : undefined}
              {...register("scheduledStart")}
            />
            {errors.scheduledStart ? (
              <p id="exam-draft-start-error" role="alert" className="text-xs text-destructive">
                {errors.scheduledStart.message}
              </p>
            ) : null}
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="exam-draft-end">Window end</Label>
            <Input
              id="exam-draft-end"
              type="datetime-local"
              aria-required="true"
              aria-invalid={!!errors.scheduledEnd}
              aria-describedby={errors.scheduledEnd ? "exam-draft-end-error" : undefined}
              {...register("scheduledEnd")}
            />
            {errors.scheduledEnd ? (
              <p id="exam-draft-end-error" role="alert" className="text-xs text-destructive">
                {errors.scheduledEnd.message}
              </p>
            ) : null}
          </div>
        </div>

        <div className="flex flex-col gap-1.5 sm:max-w-xs">
          <Label htmlFor="exam-draft-time-limit">Time limit (minutes)</Label>
          <Input
            id="exam-draft-time-limit"
            type="number"
            min={1}
            step={1}
            aria-required="true"
            aria-invalid={!!errors.timeLimitMinutes}
            aria-describedby={errors.timeLimitMinutes ? "exam-draft-time-limit-error" : undefined}
            {...register("timeLimitMinutes")}
          />
          {errors.timeLimitMinutes ? (
            <p id="exam-draft-time-limit-error" role="alert" className="text-xs text-destructive">
              {errors.timeLimitMinutes.message}
            </p>
          ) : null}
        </div>

        <QueryStateBoundary
          query={questionsQuery}
          loadingLabel="Loading the question bank…"
          isEmpty={(data) => data.content.length === 0}
          emptyState={{
            title: "No questions in the bank yet",
            description: "Add questions in the Question Bank screen for this course before linking them here.",
          }}
        >
          {(data) => (
            <ExamQuestionPicker
              availableQuestions={data.content}
              selectedIds={questionIds}
              onChange={(ids) => setValue("questionIds", ids, { shouldValidate: true })}
              disabled={disabled}
            />
          )}
        </QueryStateBoundary>
      </fieldset>

      {canEdit ? (
        <Button type="submit" disabled={isSubmitting} aria-busy={isSubmitting} className="w-full sm:w-fit">
          {isSubmitting ? "Saving…" : "Save changes"}
        </Button>
      ) : null}
    </form>
  );
}
