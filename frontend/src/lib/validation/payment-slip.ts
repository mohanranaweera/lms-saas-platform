import { z } from "zod";
import type { SlipUploadInput } from "@/lib/api/payment-slips";

/**
 * Zod schemas + shared constants for the manual payment slip flows (upload,
 * override-with-reason approve, reject). Mirrors `lib/validation/material.ts`
 * and `lib/validation/refund.ts`'s conventions exactly (constants exported
 * alongside the schema, a `toXInput`/`toXRequest` mapper at submission time).
 *
 * `ACCEPTED_SLIP_MIME_TYPES`/`MAX_SLIP_FILE_SIZE_BYTES` mirror the backend's
 * own allow-list (`SlipContentSniffer`) and
 * `app.payment.slip.max-file-size-bytes` (25 MiB). This client-side check is
 * UX convenience only (`.claude/rules/frontend.md`) — the backend
 * independently and authoritatively re-validates via magic-byte content
 * sniffing on the actual byte stream, never the browser-reported
 * `File.type`/extension. A renamed/mismatched file that passes this Zod
 * check can still be rejected server-side with `UNSUPPORTED_MEDIA_TYPE`
 * (415) or `PAYLOAD_TOO_LARGE` (413), and that rejection must render through
 * the exact same alert region as a client-side Zod failure — see
 * `slip-upload-form.tsx`'s single `role="alert"` region, not a second
 * bespoke error path.
 */

export const ACCEPTED_SLIP_MIME_TYPES = [
  "application/pdf",
  "image/png",
  "image/jpeg",
  "image/gif",
] as const;

/** 25 MiB, mirroring `app.payment.slip.max-file-size-bytes` in `application.yml`. */
export const MAX_SLIP_FILE_SIZE_BYTES = 26_214_400;

export const ACCEPTED_SLIP_FORMATS_LABEL = "PDF, PNG, JPEG, or GIF";
export const MAX_SLIP_FILE_SIZE_LABEL = "25 MB";

export const SLIP_UPLOAD_HELPER_TEXT = `Accepted formats: ${ACCEPTED_SLIP_FORMATS_LABEL}. Maximum size: ${MAX_SLIP_FILE_SIZE_LABEL}.`;

const SLIP_FILE_REQUIRED_MESSAGE = "Select a file to upload.";

export const slipUploadSchema = z.object({
  referenceNumber: z
    .string()
    .min(1, "Reference number is required.")
    .max(255, "Reference number must be 255 characters or fewer."),
  // Typed `z.any()` rather than `z.instanceof(File)` so `defaultValues` can
  // start as `undefined` (no file selected yet) without fighting React Hook
  // Form's inferred type — mirrors `materialUploadSchema`'s exact rationale.
  file: z
    .any()
    // `abort: true` stops the chain here on failure — without it, Zod v4
    // still runs every later `.refine()` against the same (non-File) value
    // even after this one fails, and the next refine's `file.size` throws a
    // raw `TypeError` on `undefined` instead of surfacing
    // `SLIP_FILE_REQUIRED_MESSAGE`.
    .refine((value: unknown): value is File => value instanceof File, {
      message: SLIP_FILE_REQUIRED_MESSAGE,
      abort: true,
    })
    .refine((file: File) => file.size > 0, "The selected file is empty.")
    .refine(
      (file: File) => file.size <= MAX_SLIP_FILE_SIZE_BYTES,
      `File is too large. Maximum size is ${MAX_SLIP_FILE_SIZE_LABEL}.`
    )
    .refine(
      (file: File) => (ACCEPTED_SLIP_MIME_TYPES as readonly string[]).includes(file.type),
      `Unsupported file type. Accepted formats: ${ACCEPTED_SLIP_FORMATS_LABEL}.`
    ),
});
export type SlipUploadFormValues = z.infer<typeof slipUploadSchema>;

export const SLIP_UPLOAD_DEFAULT_VALUES: SlipUploadFormValues = {
  referenceNumber: "",
  file: undefined,
};

export function toSlipUploadInput(values: SlipUploadFormValues): SlipUploadInput {
  return { referenceNumber: values.referenceNumber.trim(), file: values.file as File };
}

/**
 * Backs the Slip Detail "Approve anyway" flow, when the target slip carries
 * one or more active flags — the "Approve anyway" submit button stays
 * `disabled` until this passes (UX convenience only; the backend
 * independently rejects a reasonless override with `409` before any state
 * change, per `.claude/rules/payments.md` §3).
 */
export const slipOverrideReasonSchema = z.object({
  overrideReason: z
    .string()
    .min(1, "A reason is required to approve a flagged slip.")
    .max(1000, "Reason must be 1000 characters or fewer."),
});
export type SlipOverrideReasonFormValues = z.infer<typeof slipOverrideReasonSchema>;

/** Mirrors `refundSchema`'s `reason` field exactly. */
export const slipRejectSchema = z.object({
  reason: z
    .string()
    .min(1, "A reason is required.")
    .max(1000, "Reason must be 1000 characters or fewer."),
});
export type SlipRejectFormValues = z.infer<typeof slipRejectSchema>;
