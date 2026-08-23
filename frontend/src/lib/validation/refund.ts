import { z } from "zod";

/**
 * Zod schema for the refund amount+reason form (Tenant Admin / Finance Staff
 * "Refunds" screen). Mirrors `RefundCreateRequest`'s Bean Validation
 * constraints (`amount > 0`, up to 2 decimals; `reason` required, non-blank,
 * max 1000 chars) — UX convenience only, the backend independently and
 * authoritatively re-validates (per `.claude/rules/frontend.md`), including
 * the cross-row "amount cannot exceed the refundable remainder" rule this
 * schema cannot express client-side.
 */

const AMOUNT_PATTERN = /^\d{1,10}(\.\d{1,2})?$/;
export const REFUND_AMOUNT_HELPER_TEXT =
  "Up to 10 digits, with up to 2 decimal places (e.g. 199.99).";

export const refundSchema = z.object({
  amount: z
    .string()
    .min(1, "Refund amount is required.")
    .regex(AMOUNT_PATTERN, REFUND_AMOUNT_HELPER_TEXT)
    .refine((value) => Number(value) > 0, "Refund amount must be greater than zero."),
  reason: z
    .string()
    .min(1, "A reason is required.")
    .max(1000, "Reason must be 1000 characters or fewer."),
});
export type RefundFormValues = z.infer<typeof refundSchema>;
