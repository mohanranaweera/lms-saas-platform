import { z } from "zod";
import type { MaterialCreateInput, MaterialType } from "@/lib/api/materials";
import type { VideoPlaybackPolicyRequest } from "@/lib/api/videos";

/**
 * Zod schema + shared constants for the Teacher material create/upload form
 * (Wave 5, plan §3/§5). Mirrors `frontend/src/lib/validation/course.ts`'s
 * conventions (helper-text constants exported alongside the schema, `toXRequest`
 * mapper at submission time).
 *
 * Extended into a `z.discriminatedUnion` keyed on `kind` — a UI-facing
 * discriminant distinct from the backend's own `MaterialType` enum (see
 * `kind`'s own doc comment below for why). This is the first per-type-field
 * form in this codebase (no prior `z.discriminatedUnion` usage found), so its
 * shape here is the new precedent: one literal-keyed variant per Teacher
 * -facing choice, a `defaultValuesForKind` factory so switching the type
 * selector calls RHF's `reset()` with a clean slate for the new kind (rather
 * than trying to keep one shared, mostly-undefined values object valid across
 * every variant at once), and `toMaterialCreateRequest`/`toVideoPolicyRequest`
 * mappers that are the only place raw string form values (numbers, datetimes)
 * are converted to the wire shapes `lib/api/materials.ts`/`lib/api/videos.ts`
 * expect.
 *
 * This client-side check is UX convenience only (`.claude/rules/frontend.md`)
 * — the backend independently re-validates every one of these rules
 * (`MaterialService#validateFieldsForType`, `ContentSniffer`'s magic-byte
 * sniff for uploaded files, `VideoAccessApi`'s tenant/status check for
 * `videoAssetId`) and any backend rejection renders through the same
 * `role="alert"` region a client-side Zod failure would, per
 * `material-upload-form.tsx`'s existing convention.
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

/**
 * A video's accepted formats, mirroring the backend's video allow-list
 * (mp4/webm/quicktime) — used only for the native `<input accept>` hint and
 * this form's own client-side helper text; the backend's `VideoContentSniffer`
 * remains the authority (magic-byte sniff, never the browser-reported
 * `File.type`).
 */
export const ACCEPTED_VIDEO_MIME_TYPES = ["video/mp4", "video/webm", "video/quicktime"] as const;
export const ACCEPTED_VIDEO_FORMATS_LABEL = "MP4, WebM, or QuickTime (.mov)";

/**
 * UI-facing discriminant for the material-create form — deliberately NOT the
 * same set of values as the backend's `MaterialType` enum. Design choice
 * (documented per the task brief's request to record ambiguous-point
 * decisions): `PDF`/`IMAGE`/`DOCUMENT`/`OTHER` are collapsed into a single
 * "Upload a file" choice (`kind: "FILE"`) that always sends
 * `materialType: "OTHER"` to the backend — the simplest correct option per
 * the task brief, since the backend's `ContentSniffer` already independently
 * determines the real MIME type from file content regardless of what
 * `materialType` label is sent, and asking a Teacher to correctly
 * pre-classify "is this a PDF, an image, or a document" adds a UI decision
 * with no enforcement benefit. `kind: "VIDEO"` covers both backend
 * `VIDEO`/`RECORDING` types via a nested `videoKind` field (see the `video`
 * variant below) rather than being two separate top-level kinds, since the
 * two-step upload-then-attach flow and playback-policy sub-form are
 * identical for both.
 */
export type MaterialFormKind = "FILE" | "LINK" | "NOTE" | "VIDEO";

const availabilityFields = {
  /** `<input type="datetime-local">` value, or `""` when unset — converted to an ISO instant only in `toMaterialCreateRequest`. */
  availableFromAt: z.string().optional(),
  expiryAt: z.string().optional(),
  /** Raw digit string, or `""` when unset — see `toMaterialCreateRequest` for the int-parse + positivity check. */
  maxDownloads: z
    .string()
    .optional()
    .refine((value) => !value || /^\d+$/.test(value), "Enter a whole number greater than 0.")
    .refine((value) => !value || Number(value) > 0, "Enter a whole number greater than 0."),
};

const fileVariant = z.object({
  kind: z.literal("FILE"),
  title: z.string().min(1, "Title is required.").max(255, "Title must be 255 characters or fewer."),
  file: z
    .any()
    .refine((value: unknown): value is File => value instanceof File, {
      message: MATERIAL_FILE_REQUIRED_MESSAGE,
      abort: true,
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
  ...availabilityFields,
});

const linkVariant = z.object({
  kind: z.literal("LINK"),
  title: z.string().min(1, "Title is required.").max(255, "Title must be 255 characters or fewer."),
  externalUrl: z
    .string()
    .min(1, "A URL is required for a link material.")
    .url("Enter a valid absolute URL, e.g. https://example.com/video."),
  ...availabilityFields,
});

const noteVariant = z.object({
  kind: z.literal("NOTE"),
  title: z.string().min(1, "Title is required.").max(255, "Title must be 255 characters or fewer."),
  noteContent: z.string().min(1, "Note content is required."),
});

const videoVariant = z.object({
  kind: z.literal("VIDEO"),
  title: z.string().min(1, "Title is required.").max(255, "Title must be 255 characters or fewer."),
  /** Backend `MaterialType.VIDEO` ("an original instructional video") vs. `RECORDING` ("a recorded class session made available afterward") — purely semantic labeling, identical security posture (see `MaterialType`'s backend javadoc). */
  videoKind: z.enum(["VIDEO", "RECORDING"]),
  /** Populated by the two-step upload flow (`useUploadVideo`) before this form can be submitted — never typed in directly. */
  videoAssetId: z.string().min(1, "Upload a video file first."),
  // Playback policy sub-form ("Playback rules", collapsible) — every
  // numeric/datetime field optional (mirrors `VideoPlaybackPolicyRequest`'s
  // own nullability), the three booleans + concurrency cap always present
  // with the same defaults the backend would otherwise apply.
  accessStartAt: z.string().optional(),
  accessEndAt: z.string().optional(),
  maxViewsPerStudent: z
    .string()
    .optional()
    .refine((value) => !value || /^\d+$/.test(value), "Enter a whole number greater than 0.")
    .refine((value) => !value || Number(value) > 0, "Enter a whole number greater than 0."),
  maxWatchDurationSeconds: z
    .string()
    .optional()
    .refine((value) => !value || /^\d+$/.test(value), "Enter a whole number of seconds greater than 0.")
    .refine((value) => !value || Number(value) > 0, "Enter a whole number of seconds greater than 0."),
  allowSeeking: z.boolean(),
  allowDownload: z.boolean(),
  watermarkEnabled: z.boolean(),
  maxConcurrentSessions: z
    .string()
    .optional()
    .refine((value) => !value || /^\d+$/.test(value), "Enter a whole number greater than 0.")
    .refine((value) => !value || Number(value) > 0, "Enter a whole number greater than 0."),
});

export const materialUploadSchema = z.discriminatedUnion("kind", [
  fileVariant,
  linkVariant,
  noteVariant,
  videoVariant,
]);

export type MaterialUploadFormValues = z.infer<typeof materialUploadSchema>;
export type FileMaterialFormValues = z.infer<typeof fileVariant>;
export type LinkMaterialFormValues = z.infer<typeof linkVariant>;
export type NoteMaterialFormValues = z.infer<typeof noteVariant>;
export type VideoMaterialFormValues = z.infer<typeof videoVariant>;

export function defaultValuesForKind(kind: MaterialFormKind): MaterialUploadFormValues {
  switch (kind) {
    case "FILE":
      return { kind: "FILE", title: "", file: undefined, availableFromAt: "", expiryAt: "", maxDownloads: "" };
    case "LINK":
      return { kind: "LINK", title: "", externalUrl: "", availableFromAt: "", expiryAt: "", maxDownloads: "" };
    case "NOTE":
      return { kind: "NOTE", title: "", noteContent: "" };
    case "VIDEO":
      return {
        kind: "VIDEO",
        title: "",
        videoKind: "VIDEO",
        videoAssetId: "",
        accessStartAt: "",
        accessEndAt: "",
        maxViewsPerStudent: "",
        maxWatchDurationSeconds: "",
        allowSeeking: true,
        allowDownload: false,
        watermarkEnabled: true,
        maxConcurrentSessions: "",
      };
  }
}

export const MATERIAL_UPLOAD_DEFAULT_VALUES: MaterialUploadFormValues = defaultValuesForKind("FILE");

/** `""`/`undefined` -> `undefined`; a validated digit string -> its parsed `number`. Assumes the Zod digit-string refinement above already ran. */
function toOptionalInt(value: string | undefined): number | undefined {
  if (!value) return undefined;
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : undefined;
}

/** `""`/`undefined` -> `undefined`; a `datetime-local` value -> its ISO-8601 instant, per `MaterialController#parseInstant`'s expected wire format. */
function toOptionalIsoInstant(value: string | undefined): string | undefined {
  if (!value) return undefined;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

export function toMaterialCreateRequest(values: MaterialUploadFormValues): MaterialCreateInput {
  const title = values.title.trim();
  switch (values.kind) {
    case "FILE":
      return {
        title,
        materialType: "OTHER" as MaterialType,
        file: values.file as File,
        maxDownloads: toOptionalInt(values.maxDownloads),
        availableFromAt: toOptionalIsoInstant(values.availableFromAt),
        expiryAt: toOptionalIsoInstant(values.expiryAt),
      };
    case "LINK":
      return {
        title,
        materialType: "LINK",
        externalUrl: values.externalUrl.trim(),
        maxDownloads: toOptionalInt(values.maxDownloads),
        availableFromAt: toOptionalIsoInstant(values.availableFromAt),
        expiryAt: toOptionalIsoInstant(values.expiryAt),
      };
    case "NOTE":
      return {
        title,
        materialType: "NOTE",
        noteContent: values.noteContent,
      };
    case "VIDEO":
      return {
        title,
        materialType: values.videoKind,
        videoAssetId: values.videoAssetId,
      };
  }
}

/** Maps the `video` variant's "Playback rules" sub-form to `PUT /videos/{id}/policy`'s request body. */
export function toVideoPolicyRequest(values: VideoMaterialFormValues): VideoPlaybackPolicyRequest {
  return {
    accessStartAt: toOptionalIsoInstant(values.accessStartAt) ?? null,
    accessEndAt: toOptionalIsoInstant(values.accessEndAt) ?? null,
    maxViewsPerStudent: toOptionalInt(values.maxViewsPerStudent) ?? null,
    maxWatchDurationSeconds: toOptionalInt(values.maxWatchDurationSeconds) ?? null,
    allowSeeking: values.allowSeeking,
    allowDownload: values.allowDownload,
    watermarkEnabled: values.watermarkEnabled,
    maxConcurrentSessions: toOptionalInt(values.maxConcurrentSessions) ?? null,
  };
}
