"use client";

import { useId, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Controller, useFieldArray, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, CheckCircle2, Plus, Save, Star, Trash2 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { RichTextEditor } from "@/components/exams/rich-text-editor";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { isApiClientError } from "@/lib/api/error";
import {
  useCreateBankQuestion,
  useUpdateBankQuestion,
  type BankQuestion,
} from "@/lib/api/question-bank";
import {
  MAX_ANSWERS,
  emptyQuestionForm,
  mapServerField,
  questionFormSchema,
  questionToForm,
  toUpsertRequest,
  type QuestionFormValues,
} from "@/lib/validation/question-bank";
import { cn } from "@/lib/utils";

/**
 * Shared create/edit form for one module-bank question (Klass "Add a
 * Question to {course}"). Exactly one answer can be marked correct (D1): the
 * answer checkboxes look like Klass's but are mutually exclusive. Validation
 * mirrors the server (React Hook Form + Zod); server 400 field errors are
 * mapped back onto the matching field.
 */
export function QuestionForm({
  courseId,
  moduleId,
  question,
  cancelHref,
  onSaved,
}: {
  courseId: string;
  moduleId: string;
  /** Present when editing. */
  question?: BankQuestion;
  cancelHref: string;
  onSaved?: (saved: BankQuestion, mode: "create" | "edit") => void;
}) {
  const router = useRouter();
  const ids = useId();
  const create = useCreateBankQuestion(courseId, moduleId);
  const update = useUpdateBankQuestion(courseId, moduleId, question?.id ?? "");
  const [formError, setFormError] = useState<string | null>(null);
  const [answersServerError, setAnswersServerError] = useState<string | null>(null);

  const {
    control,
    register,
    handleSubmit,
    setError,
    setValue,
    getValues,
    trigger,
    formState: { errors, isSubmitting, isSubmitted },
  } = useForm<QuestionFormValues>({
    resolver: zodResolver(questionFormSchema),
    defaultValues: question ? questionToForm(question) : emptyQuestionForm(),
    mode: "onSubmit",
    reValidateMode: "onChange",
  });
  const { fields, append, remove } = useFieldArray({ control, name: "answers" });
  const answers = useWatch({ control, name: "answers" });
  const pending = isSubmitting || create.isPending || update.isPending;

  const questionHeadingId = `${ids}-question`;
  const questionHelpId = `${ids}-question-help`;
  const questionErrorId = `${ids}-question-error`;
  const marksId = `${ids}-marks`;
  const marksErrorId = `${ids}-marks-error`;
  const answersHelpId = `${ids}-answers-help`;
  const answersErrorId = `${ids}-answers-error`;
  const explanationHeadingId = `${ids}-explanation`;
  const explanationHelpId = `${ids}-explanation-help`;
  const explanationErrorId = `${ids}-explanation-error`;

  const answersListError =
    answersServerError ??
    errors.answers?.root?.message ??
    (errors.answers as { message?: string } | undefined)?.message ??
    null;

  const focusFirstInvalid = () => {
    requestAnimationFrame(() => {
      const target = document.querySelector<HTMLElement>('[data-question-form] [aria-invalid="true"]');
      target?.focus();
      target?.scrollIntoView({ block: "center", behavior: "smooth" });
    });
  };

  const markCorrect = (index: number, checked: boolean) => {
    const current = getValues("answers");
    current.forEach((_answer, other) => {
      setValue(`answers.${other}.correct`, other === index ? checked : false, { shouldDirty: true });
    });
    setAnswersServerError(null);
    if (isSubmitted) void trigger("answers");
  };

  const onSubmit = handleSubmit(
    async (values) => {
      setFormError(null);
      setAnswersServerError(null);
      const body = toUpsertRequest(values);
      try {
        const saved = question ? await update.mutateAsync(body) : await create.mutateAsync(body);
        if (onSaved) onSaved(saved, question ? "edit" : "create");
        else router.push(`${cancelHref}?saved=${question ? "updated" : "created"}`);
      } catch (error) {
        if (isApiClientError(error) && error.status === 400 && error.fieldErrors.length > 0) {
          const unmatched: string[] = [];
          for (const fieldError of error.fieldErrors) {
            const target = mapServerField(fieldError.field);
            if (!target) unmatched.push(fieldError.message);
            else if (target.kind === "field") setError(target.name, { type: "server", message: fieldError.message });
            else if (target.kind === "answer" && target.index < values.answers.length)
              setError(`answers.${target.index}.html`, { type: "server", message: fieldError.message });
            else setAnswersServerError(fieldError.message);
          }
          setFormError(unmatched.length > 0 ? unmatched.join(" ") : "Fix the highlighted fields and save again.");
          focusFirstInvalid();
        } else {
          setFormError(mutationErrorMessage(error));
        }
      }
    },
    () => {
      setFormError(null);
      focusFirstInvalid();
    }
  );

  const bodyError = errors.bodyHtml?.message ?? null;
  const marksError = errors.marks?.message ?? null;
  const explanationError = errors.explanationHtml?.message ?? null;

  return (
    <form noValidate onSubmit={onSubmit} className="flex flex-col gap-5" data-question-form data-testid="question-form">
      {formError ? (
        <Alert variant="destructive" data-testid="question-form-error">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{formError}</AlertDescription>
        </Alert>
      ) : null}

      <fieldset disabled={pending} className="flex min-w-0 flex-col gap-5">
        {/* QUESTION */}
        <section aria-labelledby={questionHeadingId} className="flex flex-col gap-4 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
            <div className="flex flex-col gap-1">
              <h2 id={questionHeadingId} className="text-sm font-semibold tracking-wide text-muted-foreground uppercase">
                Question
              </h2>
              <p id={questionHelpId} className="text-sm text-muted-foreground">
                Write the question below. You can use rich text, images and math.
              </p>
            </div>
            <div className="flex w-full flex-col gap-1 sm:w-36">
              <div className="relative">
                <label
                  htmlFor={marksId}
                  className={cn(
                    "absolute -top-2 left-2.5 bg-card px-1 text-xs text-muted-foreground",
                    marksError && "text-destructive"
                  )}
                >
                  Marks
                </label>
                <Star aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-muted-foreground" />
                <input
                  id={marksId}
                  type="text"
                  inputMode="decimal"
                  autoComplete="off"
                  aria-required="true"
                  aria-invalid={Boolean(marksError)}
                  aria-describedby={marksError ? marksErrorId : undefined}
                  className="h-10 w-full rounded-lg border border-input bg-transparent pr-3 pl-9 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 aria-invalid:border-destructive aria-invalid:ring-3 aria-invalid:ring-destructive/20"
                  data-testid="question-marks"
                  {...register("marks")}
                />
              </div>
              {marksError ? (
                <p id={marksErrorId} role="alert" className="text-xs text-destructive">
                  {marksError}
                </p>
              ) : null}
            </div>
          </div>
          <Controller
            control={control}
            name="bodyHtml"
            render={({ field }) => (
              <RichTextEditor
                value={field.value}
                onChange={field.onChange}
                ariaLabel="Question"
                ariaDescribedBy={[questionHelpId, bodyError ? questionErrorId : null].filter(Boolean).join(" ")}
                invalid={Boolean(bodyError)}
                disabled={pending}
                testId="question-body-editor"
              />
            )}
          />
          {bodyError ? (
            <p id={questionErrorId} role="alert" className="-mt-2 text-xs text-destructive">
              {bodyError}
            </p>
          ) : null}
        </section>

        {/* ANSWERS */}
        <section aria-labelledby={`${ids}-answers`} className="flex flex-col gap-4 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5">
          <div className="flex flex-col gap-1">
            <h2 id={`${ids}-answers`} className="text-sm font-semibold tracking-wide text-muted-foreground uppercase">
              Answers
            </h2>
            <p id={answersHelpId} className="text-sm text-muted-foreground">
              Check the checkbox on the correct answer. Students will see these options in a randomised order.
            </p>
          </div>
          {answersListError ? (
            <p id={answersErrorId} role="alert" className="text-sm text-destructive" data-testid="answers-error">
              {answersListError}
            </p>
          ) : null}
          <ol className="flex flex-col gap-4">
            {fields.map((field, index) => {
              const correct = Boolean(answers?.[index]?.correct);
              const answerError = errors.answers?.[index]?.html?.message ?? null;
              const checkboxId = `${ids}-answer-${field.id}-correct`;
              const answerErrorId = `${ids}-answer-${field.id}-error`;
              return (
                <li
                  key={field.id}
                  className={cn(
                    "overflow-hidden rounded-lg border border-border",
                    answerError && "border-destructive/60"
                  )}
                  data-testid="answer-block"
                >
                  <div className={cn("flex items-center gap-3 border-b border-border bg-muted/40 px-3 py-2", correct && "bg-green-600/5")}>
                    <input
                      id={checkboxId}
                      type="checkbox"
                      checked={correct}
                      onChange={(event) => markCorrect(index, event.target.checked)}
                      aria-describedby={[answersHelpId, answersListError ? answersErrorId : null].filter(Boolean).join(" ")}
                      className="size-4 shrink-0 accent-primary"
                      data-testid="answer-correct"
                    />
                    <label htmlFor={checkboxId} className="text-sm font-medium text-foreground">
                      Answer {index + 1}
                      <span className="sr-only"> is the correct answer</span>
                    </label>
                    {correct ? (
                      <span className="inline-flex items-center gap-1 rounded-full bg-green-600/10 px-2 py-0.5 text-xs font-medium text-green-700 dark:text-green-400" data-testid="answer-correct-chip">
                        <CheckCircle2 className="size-3.5" aria-hidden="true" />
                        Correct
                      </span>
                    ) : null}
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      className="ml-auto"
                      aria-label={`Delete answer ${index + 1}`}
                      title={fields.length <= 2 ? "A question needs at least 2 answers" : "Delete answer"}
                      disabled={fields.length <= 2}
                      onClick={() => {
                        remove(index);
                        setAnswersServerError(null);
                        if (isSubmitted) void trigger("answers");
                      }}
                      data-testid="answer-delete"
                    >
                      <Trash2 className="text-destructive" aria-hidden="true" />
                    </Button>
                  </div>
                  <div className="flex flex-col gap-1.5 p-3">
                    <Controller
                      control={control}
                      name={`answers.${index}.html`}
                      render={({ field: editorField }) => (
                        <RichTextEditor
                          value={editorField.value}
                          onChange={editorField.onChange}
                          ariaLabel={`Answer ${index + 1}`}
                          ariaDescribedBy={answerError ? answerErrorId : undefined}
                          invalid={Boolean(answerError)}
                          disabled={pending}
                          testId={`answer-editor-${index + 1}`}
                        />
                      )}
                    />
                    {answerError ? (
                      <p id={answerErrorId} role="alert" className="text-xs text-destructive">
                        {answerError}
                      </p>
                    ) : null}
                  </div>
                </li>
              );
            })}
          </ol>
          <div>
            <Button
              type="button"
              variant="outline"
              className="h-9 border-primary/60 px-4 tracking-wide text-primary uppercase hover:text-primary"
              disabled={fields.length >= MAX_ANSWERS}
              onClick={() => {
                append({ optionId: null, html: "", correct: false });
                setAnswersServerError(null);
              }}
              data-testid="answer-add"
            >
              <Plus aria-hidden="true" />
              Add answer
            </Button>
            {fields.length >= MAX_ANSWERS ? (
              <p className="mt-1 text-xs text-muted-foreground">A question can have at most {MAX_ANSWERS} answers.</p>
            ) : null}
          </div>
        </section>

        {/* EXPLANATION */}
        <section aria-labelledby={explanationHeadingId} className="flex flex-col gap-4 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5">
          <div className="flex flex-col gap-1">
            <h2 id={explanationHeadingId} className="text-sm font-semibold tracking-wide text-muted-foreground">
              <span className="uppercase">Explanation</span>{" "}
              <span className="text-xs font-normal tracking-normal">(optional)</span>
            </h2>
            <p id={explanationHelpId} className="text-sm text-muted-foreground">
              Shown to students after they submit, to explain the correct answer.
            </p>
          </div>
          <Controller
            control={control}
            name="explanationHtml"
            render={({ field }) => (
              <RichTextEditor
                value={field.value}
                onChange={field.onChange}
                ariaLabel="Explanation"
                ariaDescribedBy={[explanationHelpId, explanationError ? explanationErrorId : null].filter(Boolean).join(" ")}
                invalid={Boolean(explanationError)}
                disabled={pending}
                testId="question-explanation-editor"
              />
            )}
          />
          {explanationError ? (
            <p id={explanationErrorId} role="alert" className="-mt-2 text-xs text-destructive">
              {explanationError}
            </p>
          ) : null}
        </section>
      </fieldset>

      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Link
          href={cancelHref}
          className={cn(
            buttonVariants({ variant: "outline" }),
            "h-9 border-destructive/60 px-4 tracking-wide text-destructive uppercase hover:bg-destructive/10 hover:text-destructive",
            pending && "pointer-events-none opacity-50"
          )}
          aria-disabled={pending || undefined}
          data-testid="question-cancel"
        >
          Cancel
        </Link>
        <Button
          type="submit"
          className="h-9 bg-green-700 px-4 tracking-wide text-white uppercase hover:bg-green-800"
          disabled={pending}
          aria-busy={pending}
          data-testid="question-save"
        >
          <Save aria-hidden="true" />
          {pending ? "Saving…" : "Save question"}
        </Button>
      </div>
      <span className="sr-only" role="status" aria-live="polite">
        {pending ? "Saving question" : ""}
      </span>
    </form>
  );
}
