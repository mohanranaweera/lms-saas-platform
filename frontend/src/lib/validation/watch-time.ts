import { z } from "zod";

/**
 * VIDEO Phase 12 - "Add watch time" form. Mirrors the backend's
 * `POST /students/{id}/watch-time/{videoAssetId}/grants` rules (1-6000 whole
 * minutes, reason 1-500 characters). UX convenience only - the backend
 * re-validates every field.
 */
export const MAX_EXTRA_MINUTES = 6000;

export const grantWatchTimeSchema = z.object({
  extraMinutes: z
    .string()
    .trim()
    .min(1, "Enter the extra time in minutes.")
    .refine((value) => /^\d+$/.test(value), "Enter a whole number of minutes.")
    .refine((value) => {
      const minutes = Number(value);
      return minutes >= 1 && minutes <= MAX_EXTRA_MINUTES;
    }, `Enter between 1 and ${MAX_EXTRA_MINUTES} minutes.`),
  reason: z.string().trim().min(1, "A reason is required.").max(500, "Reason must be 500 characters or fewer."),
});

export type GrantWatchTimeFormValues = z.infer<typeof grantWatchTimeSchema>;
