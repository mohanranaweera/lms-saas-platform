import { z } from "zod";
import { isRichContentEmpty, toServerHtml } from "@/lib/exams/rich-content";
import type { BankQuestion, QuestionUpsertRequest } from "@/lib/api/question-bank";

/**
 * Client-side mirror of the server's `QuestionUpsertRequest` rules
 * (`docs/api/exam-management-v2.md`, D1). UX only — the backend re-validates
 * every rule and is authoritative.
 */

export const MIN_ANSWERS = 2;
export const MAX_ANSWERS = 10;
export const MAX_MARKS = 100;

export interface AnswerFormValue {
  /** Server option id (edit only); `null` for new answers. (`id` is reserved by useFieldArray.) */
  optionId: string | null;
  html: string;
  correct: boolean;
}

export interface QuestionFormValues {
  bodyHtml: string;
  marks: string;
  explanationHtml: string;
  answers: AnswerFormValue[];
}

const MARKS_PATTERN = /^\d+(\.\d{1,2})?$/;

export const questionFormSchema = z
  .object({
    bodyHtml: z.string().refine((html) => !isRichContentEmpty(html), "Question is required."),
    marks: z
      .string()
      .trim()
      .min(1, "Marks are required.")
      .refine((value) => /^\d*\.?\d*$/.test(value) && value !== ".", "Marks must be a number.")
      .refine((value) => !MARKS_PATTERN.test(value) || Number(value) > 0, "Marks must be greater than 0.")
      .refine((value) => !MARKS_PATTERN.test(value) || Number(value) <= MAX_MARKS, `Marks must be ${MAX_MARKS} or less.`)
      .refine((value) => !/^\d*\.\d{3,}$/.test(value), "Marks can have at most 2 decimal places."),
    explanationHtml: z.string(),
    answers: z
      .array(
        z.object({
          optionId: z.string().nullable(),
          html: z.string(),
          correct: z.boolean(),
        })
      )
      .min(MIN_ANSWERS, `Add at least ${MIN_ANSWERS} answers.`)
      .max(MAX_ANSWERS, `A question can have at most ${MAX_ANSWERS} answers.`),
  })
  .superRefine((values, ctx) => {
    values.answers.forEach((answer, index) => {
      if (isRichContentEmpty(answer.html)) {
        ctx.addIssue({ code: "custom", path: ["answers", index, "html"], message: `Answer ${index + 1} is required.` });
      }
    });
    const correctCount = values.answers.filter((answer) => answer.correct).length;
    if (correctCount !== 1) {
      ctx.addIssue({
        code: "custom",
        path: ["answers"],
        message: correctCount === 0 ? "Select the correct answer." : "Select exactly one correct answer.",
      });
    }
  });

/**
 * Maps a server 400 field name onto the form error keys. Accepts
 * `options[1].html`, `options.1.html`, `options[1]`, `options`, `bodyHtml`,
 * `marks`, `explanationHtml`. Returns `null` for unknown fields.
 */
export type ServerFieldTarget =
  | { kind: "field"; name: "bodyHtml" | "marks" | "explanationHtml" }
  | { kind: "answer"; index: number }
  | { kind: "answers" };

export function mapServerField(field: string): ServerFieldTarget | null {
  if (field === "bodyHtml" || field === "marks" || field === "explanationHtml") return { kind: "field", name: field };
  const option = /^options(?:\[(\d+)\]|\.(\d+))?(?:\..*)?$/.exec(field);
  if (option) {
    const index = option[1] ?? option[2];
    return index === undefined ? { kind: "answers" } : { kind: "answer", index: Number(index) };
  }
  return null;
}

export function toUpsertRequest(values: QuestionFormValues): QuestionUpsertRequest {
  const explanation = toServerHtml(values.explanationHtml);
  return {
    bodyHtml: toServerHtml(values.bodyHtml),
    marks: Number(values.marks.trim()),
    explanationHtml: explanation ? explanation : null,
    options: values.answers.map((answer) => ({ id: answer.optionId, html: toServerHtml(answer.html), correct: answer.correct })),
  };
}

export function emptyQuestionForm(): QuestionFormValues {
  return {
    bodyHtml: "",
    marks: "1",
    explanationHtml: "",
    answers: [
      { optionId: null, html: "", correct: true },
      { optionId: null, html: "", correct: false },
    ],
  };
}

export function questionToForm(question: BankQuestion): QuestionFormValues {
  const marks = Number(question.marks);
  return {
    bodyHtml: question.bodyHtml ?? "",
    marks: Number.isFinite(marks) ? String(marks) : "1",
    explanationHtml: question.explanationHtml ?? "",
    answers: [...question.options]
      .sort((a, b) => a.sequence - b.sequence)
      .map((option) => ({ optionId: option.id, html: option.html, correct: option.correct })),
  };
}
