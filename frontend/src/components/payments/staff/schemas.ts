import { z } from "zod";

/**
 * Zod schemas for the ADR-030 staff payment / free card forms. UX
 * convenience only - the backend re-validates everything (and owns the rules
 * a schema can't see: the quoted total, overlaps, registration state, the
 * tenant timezone's "today"). Server 422s are mapped back onto these fields.
 */

const AMOUNT_PATTERN = /^\d{1,10}(\.\d{1,2})?$/;

export function addPaymentSchema(bounds: { today: string; minReceivedOn: string }) {
  return z
    .object({
      courseIds: z.array(z.string()).min(1, "Select at least one course."),
      monthKeys: z.array(z.string()),
      postage: z.array(z.string()),
      discount: z.string(),
      reason: z.string().max(1000, "Use at most 1000 characters."),
      reference: z.string().max(100, "Use at most 100 characters."),
      method: z.enum(["", "CASH", "BANK_TRANSFER", "CARD"]),
      receivedOn: z.string(),
      notes: z.string().max(1000, "Use at most 1000 characters."),
    })
    .superRefine((values, ctx) => {
      if (!values.method) ctx.addIssue({ code: "custom", path: ["method"], message: "Select a payment method." });
      if (!values.receivedOn) {
        ctx.addIssue({ code: "custom", path: ["receivedOn"], message: "Enter the date the money was received." });
      } else if (values.receivedOn > bounds.today) {
        ctx.addIssue({ code: "custom", path: ["receivedOn"], message: "The received date can't be in the future." });
      } else if (values.receivedOn < bounds.minReceivedOn) {
        ctx.addIssue({
          code: "custom",
          path: ["receivedOn"],
          message: "The received date can't be more than a year ago.",
        });
      }
      const discount = values.discount.trim();
      // Ticking postage clears the discount (they can't be combined, 422 ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED).
      if (discount !== "") {
        if (!AMOUNT_PATTERN.test(discount)) {
          ctx.addIssue({ code: "custom", path: ["discount"], message: "Enter an amount like 500 or 500.00." });
        } else if (Number(discount) > 0 && values.reason.trim() === "") {
          ctx.addIssue({ code: "custom", path: ["reason"], message: "Enter a reason for the discount." });
        }
      }
    });
}
export type AddPaymentValues = z.infer<ReturnType<typeof addPaymentSchema>>;

export function issueFreeCardSchema(bounds: { minFrom: string; today: string }) {
  return z
    .object({
      courseId: z.string().min(1, "Select a course."),
      validFrom: z.string().min(1, "Required. Free cards always have an end date."),
      validTo: z.string().min(1, "Required. Free cards always have an end date."),
      cardType: z.enum(["FULL", "HALF"]),
      reason: z.string().trim().min(1, "Enter a reason.").max(1000, "Use at most 1000 characters."),
    })
    .superRefine((values, ctx) => {
      if (values.validFrom && values.validFrom < bounds.minFrom) {
        ctx.addIssue({
          code: "custom",
          path: ["validFrom"],
          message: "From can't be earlier than the first day of this month.",
        });
      }
      if (values.validFrom && values.validTo && values.validTo < values.validFrom) {
        ctx.addIssue({ code: "custom", path: ["validTo"], message: "To must be on or after From." });
      } else if (values.validTo && values.validTo < bounds.today) {
        ctx.addIssue({ code: "custom", path: ["validTo"], message: "To can't be in the past." });
      }
      if (values.cardType !== "FULL") {
        ctx.addIssue({ code: "custom", path: ["cardType"], message: "Only Full free cards are supported." });
      }
    });
}
export type IssueFreeCardValues = z.infer<ReturnType<typeof issueFreeCardSchema>>;

export const revokeFreeCardSchema = z.object({
  reason: z.string().trim().min(1, "Enter a reason for revoking this free card.").max(1000, "Use at most 1000 characters."),
});
export type RevokeFreeCardValues = z.infer<typeof revokeFreeCardSchema>;
