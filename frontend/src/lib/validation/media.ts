import { z } from "zod";
import { formatBytes } from "@/lib/media-format";

/**
 * VIDEO Phase 5 - Media Library forms. Mirrors the backend rules in
 * `MediaUploadService` / `MediaAssetService` (title 1-100, description
 * ≤ 5000, no `<`/`>`, UNLISTED|PRIVATE). UX convenience only - the backend
 * re-validates every field and its field errors are shown on the form.
 */

const NO_ANGLE_BRACKETS = /^[^<>]*$/;

export const mediaMetadataSchema = z.object({
  title: z
    .string()
    .trim()
    .min(1, "Enter a title.")
    .max(100, "Use at most 100 characters.")
    .regex(NO_ANGLE_BRACKETS, "The title can't contain < or >."),
  description: z
    .string()
    .max(5000, "Use at most 5000 characters.")
    .regex(NO_ANGLE_BRACKETS, "The description can't contain < or >."),
  privacyStatus: z.enum(["UNLISTED", "PRIVATE"]),
});

export type MediaMetadataFormValues = z.infer<typeof mediaMetadataSchema>;

/** The upload form: metadata plus a file the server's limits allow. */
export function buildMediaUploadSchema(allowedMimeTypes: string[], maxFileSizeBytes: number) {
  return mediaMetadataSchema.extend({
    file: z
      .custom<File | null>((value) => value === null || value instanceof File)
      .refine((file): file is File => file instanceof File, "Choose a video file.")
      .refine((file) => !file || allowedMimeTypes.includes(file.type), "Choose an MP4, WebM or MOV video.")
      .refine(
        (file) => !file || file.size <= maxFileSizeBytes,
        `This file is larger than the ${formatBytes(maxFileSizeBytes)} limit.`
      ),
  });
}

export type MediaUploadFormValues = MediaMetadataFormValues & { file: File | null };
