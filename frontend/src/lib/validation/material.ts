import { z } from "zod";
import type { MaterialCreateInput } from "@/lib/api/materials";

/**
 * Zod schema + shared constants for the Teacher material upload form.
 * Mirrors `frontend/src/lib/validation/course.ts`'s conventions
 * (helper-text constants exported alongside the schema, `toXRequest` mapper
 * at submission time).
 *
 * `ACCEPTED_MATERIAL_MIME_TYPES`/`MAX_MATERIAL_FILE_SIZE_BYTES` are this
 * implementation's own MVP working defaults (mirrored from the backend's
 * `ContentSniffer` allow-list and `app.content.material.max-file-size-bytes`
 * — both explicitly flagged non-authoritative in the backend's own
 * comments, not a ratified business spec). Defined once here so the Zod
 * schema and the accepted-format/size-limit label text shown in the upload
 * form can never drift from each other.
 *
 * This client-side check is UX convenience only (`.claude/rules/frontend.md`)
 * — the backend independently re-validates via magic-byte content sniffing
 * on the actual byte stream (`ContentSniffer`), never the browser-reported
 * `File.type`/extension. A renamed/mismatched file that passes this Zod
 * check can still be rejected server-side with `UNSUPPORTED_MEDIA_TYPE`
 * (415), and that rejection must render through the exact same error UI as
 * a client-side Zod failure — see `material-upload-form.tsx`'s single
 * `role="alert"` region, not a second bespoke error path.
 */

export const ACCEPTED_MATERIAL_MIME_TYPES = [
  "application/pdf",
  "image/png",
  "image/jpeg",
  "image/gif",
  "text/plain",
] as const;

/** 25 MiB, mirroring `app.content.material.max-file-size-bytes` in `application.yml`. */
export const MAX_MATERIAL_FILE_SIZE_BYTES = 26_214_400;

export const ACCEPTED_MATERIAL_FORMATS_LABEL = "PDF, PNG, JPEG, GIF, or plain text (.txt)";
export const MAX_MATERIAL_FILE_SIZE_LABEL = "25 MB";

export const MATERIAL_UPLOAD_HELPER_TEXT = `Accepted formats: ${ACCEPTED_MATERIAL_FORMATS_LABEL}. Maximum size: ${MAX_MATERIAL_FILE_SIZE_LABEL}.`;

const MATERIAL_FILE_REQUIRED_MESSAGE = "Select a file to upload.";

export const materialUploadSchema = z.object({
  title: z
    .string()
    .min(1, "Title is required.")
    .max(255, "Title must be 255 characters or fewer."),
  // Typed `z.any()` rather than `z.instanceof(File)` so `defaultValues` can
  // start as `undefined` (no file selected yet) without fighting React Hook
  // Form's inferred type — the `refine` chain below is what actually
  // enforces "a real, in-range, allow-listed `File` is present" at submit
  // time; `toMaterialCreateRequest` casts to `File` only after this schema
  // has already validated it.
  file: z
    .any()
    .refine((value: unknown): value is File => value instanceof File, {
      message: MATERIAL_FILE_REQUIRED_MESSAGE,
    })
    .refine((file: File) => file.size > 0, "The selected file is empty.")
    .refine(
      (file: File) => file.size <= MAX_MATERIAL_FILE_SIZE_BYTES,
      `File is too large. Maximum size is ${MAX_MATERIAL_FILE_SIZE_LABEL}.`
    )
    .refine(
      (file: File) => (ACCEPTED_MATERIAL_MIME_TYPES as readonly string[]).includes(file.type),
      `Unsupported file type. Accepted formats: ${ACCEPTED_MATERIAL_FORMATS_LABEL}.`
    ),
});
export type MaterialUploadFormValues = z.infer<typeof materialUploadSchema>;

export const MATERIAL_UPLOAD_DEFAULT_VALUES: MaterialUploadFormValues = {
  title: "",
  file: undefined,
};

export function toMaterialCreateRequest(values: MaterialUploadFormValues): MaterialCreateInput {
  return { title: values.title.trim(), file: values.file as File };
}
