import { z } from "zod";

/**
 * MEDIA-INT-01 form schemas, mirroring `IntegrationRequests` and the service
 * rules in `MediaConnectionService`. Keys may be blank to keep the stored key;
 * "required on first save" depends on whether one is stored, so it is applied
 * in the form and re-enforced by the backend.
 */

export const dynTubeSchema = z.object({
  apiKey: z.string().max(512, "API key must be 512 characters or fewer."),
});

export type DynTubeFormValues = z.infer<typeof dynTubeSchema>;

/** MEDIA-INT-01A: a bunny.net account API key only - libraries are created per teacher. */
export const bunnySchema = z.object({
  apiKey: z.string().max(512, "API key must be 512 characters or fewer."),
});

export type BunnyFormValues = z.infer<typeof bunnySchema>;

export const disconnectReasonSchema = z.object({
  reason: z.string().trim().min(1, "A reason is required.").max(500, "Reason must be 500 characters or fewer."),
});
