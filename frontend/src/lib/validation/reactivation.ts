import { z } from "zod";

/**
 * Zod schemas for the Reactivation Approvals detail screen's Approve/Reject
 * dialogs (MVP-012). Mirrors `lib/validation/payment-slip.ts`'s conventions
 * exactly.
 */

/** Mirrors `slipRejectSchema` exactly — `reason` is required, non-blank, max 1000 chars. */
export const reactivationRejectSchema = z.object({
  reason: z
    .string()
    .min(1, "A reason is required.")
    .max(1000, "Reason must be 1000 characters or fewer."),
});
export type ReactivationRejectFormValues = z.infer<typeof reactivationRejectSchema>;

/**
 * Mirrors `slipOverrideReasonSchema`'s shape, but the note here is OPTIONAL —
 * `ReactivationApproveRequest.note` has no `@NotBlank` server-side, unlike the
 * slip override reason. An empty string is valid; only the max-length rule
 * applies.
 */
export const reactivationApproveNoteSchema = z.object({
  note: z.string().max(1000, "Note must be 1000 characters or fewer."),
});
export type ReactivationApproveNoteFormValues = z.infer<typeof reactivationApproveNoteSchema>;
