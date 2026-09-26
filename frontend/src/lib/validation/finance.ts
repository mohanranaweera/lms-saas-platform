import { z } from "zod";

/**
 * Wave 7 Finance form schemas. Each mirrors the backend's Bean Validation
 * constraints (`ExpenseCreateRequest`, `ExpenseCategoryRequest`,
 * `ExpenseVoidRequest`, `TeacherShareRateRequest`,
 * `TeacherSettlementCalculateRequest`, `TeacherSettlementAdjustmentRequest`)
 * — UX convenience only; the backend re-validates authoritatively and its
 * field errors are mapped back onto these same field names.
 */

const POSITIVE_AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;
const SIGNED_AMOUNT = /^-?\d{1,10}(\.\d{1,2})?$/;
const PERCENT = /^\d{1,3}(\.\d{1,2})?$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export const AMOUNT_HELPER_TEXT = "Up to 10 digits, with up to 2 decimal places (e.g. 1500.00).";

export const EXPENSE_RECEIPT_MAX_BYTES = 10 * 1024 * 1024;
export const EXPENSE_RECEIPT_ACCEPT = "application/pdf,image/png,image/jpeg";

export const expenseSchema = z.object({
  categoryId: z.string().min(1, "Choose a category."),
  expenseDate: z.string().regex(ISO_DATE, "Enter the expense date."),
  description: z
    .string()
    .trim()
    .min(1, "A description is required.")
    .max(500, "Description must be 500 characters or fewer."),
  amount: z
    .string()
    .min(1, "Amount is required.")
    .regex(POSITIVE_AMOUNT, AMOUNT_HELPER_TEXT)
    .refine((value) => Number(value) > 0, "Amount must be greater than zero."),
  method: z.enum(["CASH", "BANK_TRANSFER", "CARD", "CHEQUE", "ONLINE", "OTHER"], {
    message: "Choose a payment method.",
  }),
  reference: z.string().max(255, "Reference must be 255 characters or fewer.").optional(),
});
export type ExpenseFormValues = z.infer<typeof expenseSchema>;

export const categorySchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, "A name is required.")
    .max(100, "Name must be 100 characters or fewer."),
  description: z.string().max(500, "Description must be 500 characters or fewer.").optional(),
});
export type CategoryFormValues = z.infer<typeof categorySchema>;

export const reasonSchema = z.object({
  reason: z
    .string()
    .trim()
    .min(1, "A reason is required.")
    .max(500, "Reason must be 500 characters or fewer."),
});
export type ReasonFormValues = z.infer<typeof reasonSchema>;

export const shareRateSchema = z.object({
  teacherId: z.string().min(1, "Choose a teacher."),
  sharePercent: z
    .string()
    .min(1, "Share percent is required.")
    .regex(PERCENT, "Enter a percentage with up to 2 decimal places.")
    .refine((value) => Number(value) >= 0 && Number(value) <= 100, "Must be between 0 and 100."),
  effectiveFrom: z.string().regex(ISO_DATE, "Enter the date this rate takes effect."),
});
export type ShareRateFormValues = z.infer<typeof shareRateSchema>;

export const calculateSettlementSchema = z
  .object({
    teacherId: z.string().min(1, "Choose a teacher."),
    periodStart: z.string().regex(ISO_DATE, "Enter the period start date."),
    periodEnd: z.string().regex(ISO_DATE, "Enter the period end date."),
  })
  .refine((values) => values.periodStart <= values.periodEnd, {
    message: "The end date must be on or after the start date.",
    path: ["periodEnd"],
  });
export type CalculateSettlementFormValues = z.infer<typeof calculateSettlementSchema>;

export const adjustmentSchema = z.object({
  amount: z
    .string()
    .min(1, "Amount is required.")
    .regex(SIGNED_AMOUNT, "Enter a positive or negative amount with up to 2 decimal places.")
    .refine((value) => Number(value) !== 0, "An adjustment cannot be zero."),
  reason: z
    .string()
    .trim()
    .min(1, "A reason is required.")
    .max(500, "Reason must be 500 characters or fewer."),
});
export type AdjustmentFormValues = z.infer<typeof adjustmentSchema>;

export const markPaidSchema = z.object({
  payoutReference: z.string().max(255, "Reference must be 255 characters or fewer.").optional(),
});
export type MarkPaidFormValues = z.infer<typeof markPaidSchema>;
