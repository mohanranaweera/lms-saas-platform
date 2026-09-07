"use client";

import { useState } from "react";
import { useFieldArray, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/states/error-state";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { QuestionOptionsEditor } from "@/components/exams/question-options-editor";
import { useCreateQuestion } from "@/lib/api/exams";
import { isApiClientError } from "@/lib/api/error";
import {
  QUESTION_CREATE_DEFAULT_VALUES,
  questionCreateSchema,
  type QuestionCreateFormValues,
} from "@/lib/validation/exams";

const TEXTAREA_CLASSNAME =
  "w-full rounded-lg border border-input bg-transparent px-2.5 py-1.5 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30";

/**
 * Create-question form for the Teacher Question Bank (screen #4). Full
 * keyboard navigability, `fieldset`/`legend` for the MCQ option group
 * (`QuestionOptionsEditor`), `aria-required`/`aria-invalid`/
 * `aria-describedby` wiring throughout (MVP-017 plan §11).
 */
export function QuestionCreateForm({ courseId, onCreated }: { courseId: string; onCreated?: () => void }) {
  const [pageError, setPageError] = useState<string | null>(null);
  const mutation = useCreateQuestion(courseId);

  const {
    register,
    handleSubmit,
    setValue,
    control,
    reset,
    formState: { errors },
  } = useForm<QuestionCreateFormValues>({
    resolver: zodResolver(questionCreateSchema),
    defaultValues: QUESTION_CREATE_DEFAULT_VALUES,
  });

  const fieldArray = useFieldArray({ control, name: "options" });
  const questionType = useWatch({ control, name: "questionType" });
  const isSubmitting = mutation.isPending;

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync({
        questionType: values.questionType,
        body: values.body,
        options: values.questionType === "MCQ" ? values.options : null,
      });
      reset(QUESTION_CREATE_DEFAULT_VALUES);
      onCreated?.();
    } catch (error) {
      setPageError(isApiClientError(error) ? error.message : "An unexpected error occurred. Please try again.");
    }
  });

  return (
    <form className="flex flex-col gap-4" noValidate aria-busy={isSubmitting} onSubmit={onSubmit}>
      <span role="status" aria-live="polite" className="sr-only">
        {isSubmitting ? "Creating question…" : ""}
      </span>
      {pageError ? <ErrorState message={pageError} onRetry={() => setPageError(null)} /> : null}

      <fieldset disabled={isSubmitting} className="flex flex-col gap-4">
        <legend className="sr-only">New question</legend>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="question-create-type">Question type</Label>
          <Select
            value={questionType}
            onValueChange={(value) => {
              if (value === "MCQ" || value === "STRUCTURED") {
                setValue("questionType", value, { shouldValidate: true });
              }
            }}
          >
            <SelectTrigger id="question-create-type" aria-label="Question type">
              <SelectValue placeholder="Select a type" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="MCQ">Multiple choice</SelectItem>
              <SelectItem value="STRUCTURED">Structured (free text)</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="flex flex-col gap-1.5">
          <Label htmlFor="question-create-body">Question text</Label>
          <textarea
            id="question-create-body"
            rows={3}
            aria-required="true"
            aria-invalid={!!errors.body}
            aria-describedby={errors.body ? "question-create-body-error" : undefined}
            className={TEXTAREA_CLASSNAME}
            {...register("body")}
          />
          {errors.body ? (
            <p id="question-create-body-error" role="alert" className="text-xs text-destructive">
              {errors.body.message}
            </p>
          ) : null}
        </div>

        {questionType === "MCQ" ? (
          <QuestionOptionsEditor
            idPrefix="question-create"
            fieldArray={fieldArray}
            register={register}
            errors={errors}
            disabled={isSubmitting}
          />
        ) : (
          <p className="text-xs text-muted-foreground">
            Structured questions are graded manually via the Marking Queue — no answer options are collected.
          </p>
        )}
      </fieldset>

      <Button type="submit" disabled={isSubmitting} aria-busy={isSubmitting} className="w-full sm:w-fit">
        {isSubmitting ? "Creating…" : "Create question"}
      </Button>
    </form>
  );
}
