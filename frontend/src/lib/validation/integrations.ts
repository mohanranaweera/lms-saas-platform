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

export const bunnySchema = z.object({
  apiKey: z.string().max(512, "API key must be 512 characters or fewer."),
  libraryId: z
    .string()
    .trim()
    .regex(/^\d{1,20}$/, "Library ID is the number shown in your Bunny Stream library settings."),
  cdnHostname: z
    .string()
    .trim()
    .toLowerCase()
    .max(253, "Hostname is too long.")
    .regex(
      /^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$/,
      "Enter just the hostname, e.g. vz-abc123.b-cdn.net (no https:// or path)."
    ),
  tokenAuthKey: z.string().max(512, "Token authentication key must be 512 characters or fewer."),
});

export type BunnyFormValues = z.infer<typeof bunnySchema>;

export const disconnectReasonSchema = z.object({
  reason: z.string().trim().min(1, "A reason is required.").max(500, "Reason must be 500 characters or fewer."),
});
