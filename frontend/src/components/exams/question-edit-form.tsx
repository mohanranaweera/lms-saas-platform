"use client";

import { useState } from "react";
import { useFieldArray, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/states/error-state";
import { Label } from "@/components/ui/label";
import { QuestionOptionsEditor } from "@/components/exams/question-options-editor";
import { useUpdateQuestion, type ExamQuestionResponse } from "@/lib/api/exams";
import { isApiClientError } from "@/lib/api/error";
import { buildQuestionEditSchema, type QuestionEditFormValues } from "@/lib/validation/exams";

const TEXTAREA_CLASSNAME =
  "w-full rounded-lg border border-input bg-transparent px-2.5 py-1.5 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30";

/**
 * Edit-question form (screen #4). `questionType` is NOT editable — the
 * backend's `ExamQuestionUpdateRequest` has no such field, so it's rendered
 * read-only here rather than as an input (MVP-017 API contract). Surfaces the
 * backend's 409 `QUESTION_IN_USE` (an options-changing edit attempted after
 * the question is linked to a non-DRAFT exam or already answered) as a real,
 * distinct error, never silently retried.
 */
export function QuestionEditForm({
  courseId,
  question,
  onSaved,
  onCancel,
}: {
  courseId: string;
  question: ExamQuestionResponse;
  onSaved?: () => void;
  onCancel?: () => void;
}) {
  const [pageError, setPageError] = useState<string | null>(null);
  const mutation = useUpdateQuestion(courseId, question.id);
  const schema = buildQuestionEditSchema(question.questionType);

  const {
    register,
    handleSubmit,
    control,
    formState: { errors },
  } = useForm<QuestionEditFormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      body: question.body,
      options:
        question.options.length > 0
          ? question.options.map((option) => ({ optionText: option.optionText, isCorrect: false }))
          : [
              { optionText: "", isCorrect: false },
              { optionText: "", isCorrect: false },
            ],
    },
  });

  const fieldArray = useFieldArray({ control, name: "options" });
  const isSubmitting = mutation.isPending;

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync({
        body: values.body,
        options: question.questionType === "MCQ" ? values.options : null,
      });
      onSaved?.();
    } catch (error) {
      if (isApiClientError(error) && error.code === "QUESTION_IN_USE") {
        setPageError(
          `This question can't be edited this way: ${error.message}. It's already linked to a scheduled exam or has been answered.`
        );
        return;
      }
      setPageError(isApiClientError(error) ? error.message : "An unexpected error occurred. Please try again.");
    }
  });

  return (
    <form className="flex flex-col gap-4" noValidate aria-busy={isSubmitting} onSubmit={onSubmit}>
      <span role="status" aria-live="polite" className="sr-only">
        {isSubmitting ? "Saving question…" : ""}
      </span>
      {pageError ? <ErrorState message={pageError} onRetry={() => setPageError(null)} /> : null}

      <fieldset disabled={isSubmitting} className="flex flex-col gap-4">
        <legend className="sr-only">Edit question</legend>

        <div className="flex flex-col gap-1.5">
          <Label>Question type</Label>
          <p className="text-sm text-muted-foreground">
            {question.questionType === "MCQ" ? "Multiple choice" : "Structured (free text)"} (fixed at creation)
          </p>
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="question-edit-body">Question text</Label>
          <textarea
            id="question-edit-body"
            rows={3}
            aria-required="true"
            aria-invalid={!!errors.body}
            aria-describedby={errors.body ? "question-edit-body-error" : undefined}
            className={TEXTAREA_CLASSNAME}
            {...register("body")}
          />
          {errors.body ? (
            <p id="question-edit-body-error" role="alert" className="text-xs text-destructive">
              {errors.body.message}
            </p>
          ) : null}
        </div>

        {question.questionType === "MCQ" ? (
          <>
            <Alert role="status">
              <AlertDescription>
                For security, this screen never receives which option was previously marked correct — you must
                re-select the correct option(s) below before saving.
              </AlertDescription>
            </Alert>
            <QuestionOptionsEditor
              idPrefix="question-edit"
              fieldArray={fieldArray}
              register={register}
              errors={errors}
              disabled={isSubmitting}
            />
          </>
        ) : null}
      </fieldset>

      <div className="flex flex-row gap-2">
        <Button type="submit" disabled={isSubmitting} aria-busy={isSubmitting}>
          {isSubmitting ? "Saving…" : "Save changes"}
        </Button>
        <Button type="button" variant="outline" onClick={onCancel} disabled={isSubmitting}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
