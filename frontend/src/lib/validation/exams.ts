import { z } from "zod";
import type { QuestionType } from "@/lib/api/exams";

/**
 * Zod schemas for MVP-017 Exams forms. UX convenience only (per
 * `.claude/rules/frontend.md`) — the backend independently and authoritatively
 * re-validates every one of these rules (`QuestionBankService`'s
 * at-least-one-correct-option check, `ExamSchedulingService`'s window/
 * time-limit checks, `MarkingQueueService`'s non-negative score check).
 */

// ---------------------------------------------------------------------------
// Question bank authoring (screen #4)
// ---------------------------------------------------------------------------

/**
 * Deliberately does NOT enforce non-empty `optionText` here — that rule only
 * applies to MCQ questions, which is a `questionType`-conditional invariant
 * and therefore belongs in `requireMcqInvariants` (run via `superRefine`
 * below), not in this base per-item shape. A STRUCTURED question legitimately
 * has zero options (`QuestionOptionsEditor` is never rendered for it), so
 * this base schema must accept an empty `options` array / unfilled option
 * rows without complaint — enforcing `.min(1, ...)` here unconditionally was
 * the bug: it rejected STRUCTURED submissions on the two blank placeholder
 * option rows the create/edit forms seed into `defaultValues`, fields the
 * user never sees or fills in for that question type.
 */
export const questionOptionSchema = z.object({
  optionText: z.string().trim(),
  isCorrect: z.boolean(),
});

export type QuestionOptionFormValues = z.infer<typeof questionOptionSchema>;

function requireMcqInvariants(
  values: { questionType: QuestionType; options: QuestionOptionFormValues[] },
  ctx: z.RefinementCtx
) {
  if (values.questionType !== "MCQ") {
    return;
  }
  values.options.forEach((option, index) => {
    if (option.optionText.length === 0) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: "Option text is required.",
        path: ["options", index, "optionText"],
      });
    }
  });
  if (values.options.length < 2) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "An MCQ question needs at least two options.",
      path: ["options"],
    });
  }
  if (!values.options.some((option) => option.isCorrect)) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: "At least one option must be marked correct.",
      path: ["options"],
    });
  }
}

/** Create form — `questionType` is chosen once at creation and never editable afterward. */
export const questionCreateSchema = z
  .object({
    questionType: z.enum(["MCQ", "STRUCTURED"]),
    body: z.string().trim().min(1, "Question text is required."),
    options: z.array(questionOptionSchema),
  })
  .superRefine(requireMcqInvariants);

export type QuestionCreateFormValues = z.infer<typeof questionCreateSchema>;

export const QUESTION_CREATE_DEFAULT_VALUES: QuestionCreateFormValues = {
  questionType: "MCQ",
  body: "",
  options: [
    { optionText: "", isCorrect: false },
    { optionText: "", isCorrect: false },
  ],
};

/**
 * Edit form — `questionType` is fixed/read-only (the backend's
 * `ExamQuestionUpdateRequest` has no such field at all), so the MCQ-only
 * invariants are applied conditionally based on the question's existing type,
 * passed in by the caller when building the schema.
 */
export function buildQuestionEditSchema(questionType: QuestionType) {
  return z
    .object({
      body: z.string().trim().min(1, "Question text is required."),
      options: z.array(questionOptionSchema),
    })
    .superRefine((values, ctx) => requireMcqInvariants({ questionType, options: values.options }, ctx));
}

export type QuestionEditFormValues = z.infer<ReturnType<typeof buildQuestionEditSchema>>;

// ---------------------------------------------------------------------------
// Exam draft edit (screen #5)
// ---------------------------------------------------------------------------

export const examDraftSchema = z
  .object({
    title: z
      .string()
      .trim()
      .min(1, "Title is required.")
      .max(255, "Title must be 255 characters or fewer."),
    /** `datetime-local` input value, e.g. `2026-09-06T14:30`. */
    scheduledStart: z.string().min(1, "Start date/time is required."),
    scheduledEnd: z.string().min(1, "End date/time is required."),
    /**
     * Kept as a string in the form schema (converted with `Number(...)` only
     * at submit time, in `ExamDraftForm`) rather than `z.coerce.number()` —
     * mirrors `lib/validation/course.ts`'s `price` field convention. RHF's
     * `zodResolver` requires the schema's input and output types to match
     * `useForm<T>()`'s single type parameter; `z.coerce.number()` breaks that
     * (input `unknown`, output `number`) and fails to type-check here.
     */
    timeLimitMinutes: z
      .string()
      .min(1, "Time limit is required.")
      .regex(/^\d+$/, "Time limit must be a whole number of minutes.")
      .refine((value) => Number(value) > 0, "Time limit must be a positive number of minutes."),
    questionIds: z.array(z.string()),
  })
  .refine(
    (values) => {
      const start = new Date(values.scheduledStart);
      const end = new Date(values.scheduledEnd);
      return !Number.isNaN(start.getTime()) && !Number.isNaN(end.getTime()) && end > start;
    },
    { message: "End time must be after start time.", path: ["scheduledEnd"] }
  );

export type ExamDraftFormValues = z.infer<typeof examDraftSchema>;

// ---------------------------------------------------------------------------
// Marking queue score input (screen #6)
// ---------------------------------------------------------------------------

/**
 * String-typed for the same `zodResolver`/`useForm<T>` reason as
 * `timeLimitMinutes` above. Capped at `1` — every question (MCQ or
 * STRUCTURED) is worth a fixed one point (backend `MarkAnswerRequest`'s
 * `@DecimalMax(1.00)`, `ResultsPublishingService.POINTS_PER_QUESTION`); this
 * is a UX convenience only, the backend independently re-validates the same
 * bound.
 */
export const markingScoreSchema = z.object({
  manualScore: z
    .string()
    .min(1, "Enter a score.")
    .regex(/^\d+(\.\d+)?$/, "Enter a non-negative number.")
    .refine((value) => Number(value) >= 0, "Score must be zero or greater.")
    .refine((value) => Number(value) <= 1, "Score must not exceed 1 point for this question."),
});

export type MarkingScoreFormValues = z.infer<typeof markingScoreSchema>;
