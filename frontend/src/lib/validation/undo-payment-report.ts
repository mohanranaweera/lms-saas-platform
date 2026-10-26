import { z } from "zod";
import type { UndoPaymentRunRequest } from "@/lib/api/undo-payment-report";
export const undoPaymentFilterSchema = z.object({ courseId: z.string(), startDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Choose a start date."), endDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, "Choose an end date.") }).superRefine((value, ctx) => { if (value.startDate > value.endDate) ctx.addIssue({ code: "custom", path: ["endDate"], message: "End date must be on or after start date." }); });
export type UndoPaymentFilterValues = z.infer<typeof undoPaymentFilterSchema>;
export const DEFAULT_UNDO_PAYMENT_FILTER: UndoPaymentFilterValues = { courseId: "", startDate: "", endDate: "" };
export function toUndoPaymentRunRequest(values: UndoPaymentFilterValues): UndoPaymentRunRequest { return { courseIds: values.courseId ? [values.courseId] : [], startDate: values.startDate, endDate: values.endDate }; }
export function undoPaymentRequestKey(request: UndoPaymentRunRequest) { return JSON.stringify(request); }
