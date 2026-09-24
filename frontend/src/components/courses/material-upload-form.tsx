"use client";

import { useEffect, useId, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2, Loader2, UploadCloud, XCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Accordion } from "@/components/ui/accordion";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { useCreateMaterial } from "@/lib/api/materials";
import { useUploadVideo, useUpsertVideoPolicy, type VideoAssetResponse } from "@/lib/api/videos";
import { isApiClientError } from "@/lib/api/error";
import { cn } from "@/lib/utils";
import {
  ACCEPTED_MATERIAL_MIME_TYPES,
  ACCEPTED_VIDEO_MIME_TYPES,
  ACCEPTED_VIDEO_FORMATS_LABEL,
  MATERIAL_UPLOAD_HELPER_TEXT,
  defaultValuesForKind,
  materialUploadSchema,
  toMaterialCreateRequest,
  toVideoPolicyRequest,
  type MaterialFormKind,
  type MaterialUploadFormValues,
  type VideoMaterialFormValues,
} from "@/lib/validation/material";

interface MaterialUploadFormProps {
  courseId: string;
  moduleId: string;
  lessonId: string;
  /** Disables the whole form while a sibling reorder is in flight (mirrors every other control in this lesson's materials list). */
  disabled?: boolean;
}

const MATERIAL_KIND_OPTIONS: Array<{ value: MaterialFormKind; label: string }> = [
  { value: "FILE", label: "Upload a file" },
  { value: "LINK", label: "External link" },
  { value: "NOTE", label: "Note" },
  { value: "VIDEO", label: "Video" },
];

const MATERIAL_KIND_LABELS: Record<MaterialFormKind, string> = {
  FILE: "Upload a file",
  LINK: "External link",
  NOTE: "Note",
  VIDEO: "Video",
};

/**
 * Upload/create form for a single lesson's materials — extended in Wave 5
 * (plan §3/§5) with a material-type selector (`kind`, see
 * `lib/validation/material.ts`'s doc comment for the UI-facing-vs-backend
 * -enum distinction) and per-type fields: the pre-existing dashed dropzone
 * for "Upload a file" (`materialType=OTHER`), a URL input for "External
 * link" (`LINK`), a textarea for "Note" (`NOTE`), and a two-step upload
 * -then-attach flow plus a collapsible "Playback rules" policy sub-form for
 * "Video" (`VIDEO`/`RECORDING`).
 *
 * RHF + Zod validation (`lib/validation/material.ts`'s
 * `z.discriminatedUnion`) — a single `role="alert"` region for both a
 * client-side Zod failure and a backend rejection, matching the pre-Wave-5
 * form's convention exactly; a second failed attempt replaces the previous
 * message rather than stacking, and focus moves to the alert on each new
 * failure.
 *
 * Switching `kind` calls `reset(defaultValuesForKind(kind))` rather than
 * trying to keep one shared values object simultaneously valid for every
 * variant — this is also why `register`/`formState.errors` below are
 * accessed through a couple of narrow `as unknown as` casts: React Hook
 * Form's `Path<T>`/`FieldErrors<T>` helper types don't fully distribute over
 * a `z.discriminatedUnion`-inferred union the way plain object types do, and
 * this form is this codebase's first use of that combination (documented
 * here rather than fought line-by-line at every field).
 */
export function MaterialUploadForm({
  courseId,
  moduleId,
  lessonId,
  disabled: reorderBusy = false,
}: MaterialUploadFormProps) {
  const idPrefix = useId();
  const createMutation = useCreateMaterial(courseId, moduleId, lessonId);
  const uploadVideoMutation = useUploadVideo();
  const upsertPolicyMutation = useUpsertVideoPolicy();

  const [kind, setKind] = useState<MaterialFormKind>("FILE");
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const [selectedFile, setSelectedFile] = useState<File | undefined>(undefined);
  const [selectedVideoFile, setSelectedVideoFile] = useState<File | undefined>(undefined);
  const [uploadedVideo, setUploadedVideo] = useState<VideoAssetResponse | null>(null);
  const [videoUploadError, setVideoUploadError] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const videoFileInputRef = useRef<HTMLInputElement>(null);
  const errorRef = useRef<HTMLDivElement>(null);
  const [fileInputKey, setFileInputKey] = useState(0);
  const [videoFileInputKey, setVideoFileInputKey] = useState(0);

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    reset,
    formState: { errors: rawErrors },
  } = useForm<MaterialUploadFormValues>({
    resolver: zodResolver(materialUploadSchema),
    defaultValues: defaultValuesForKind("FILE"),
  });
  // See the module doc comment above for why this cast exists.
  const errors = rawErrors as Record<string, { message?: unknown } | undefined>;
  const untypedRegister = register as unknown as (name: string) => ReturnType<typeof register>;
  const untypedSetValue = setValue as unknown as (name: string, value: unknown, options?: unknown) => void;
  const watchedVideoAssetId = watch("videoAssetId" as never) as unknown as string | undefined;

  useEffect(() => {
    if (submitError) {
      errorRef.current?.focus();
    }
  }, [submitError]);

  const busy =
    createMutation.isPending ||
    uploadVideoMutation.isPending ||
    upsertPolicyMutation.isPending ||
    reorderBusy;

  const handleKindChange = (nextKind: string | null) => {
    if (!nextKind || nextKind === kind) return;
    const next = nextKind as MaterialFormKind;
    setKind(next);
    reset(defaultValuesForKind(next));
    setSubmitError(null);
    setSelectedFile(undefined);
    setSelectedVideoFile(undefined);
    setUploadedVideo(null);
    setVideoUploadError(null);
    setFileInputKey((key) => key + 1);
    setVideoFileInputKey((key) => key + 1);
  };

  const handleFiles = (files: FileList | null) => {
    const file = files?.[0];
    if (file) {
      untypedSetValue("file", file, { shouldValidate: true });
      setSelectedFile(file);
    }
  };

  const handleVideoFileSelect = (files: FileList | null) => {
    const file = files?.[0];
    if (file) {
      setSelectedVideoFile(file);
      setUploadedVideo(null);
      untypedSetValue("videoAssetId", "", { shouldValidate: false });
    }
  };

  const handleUploadVideoFile = async () => {
    if (!selectedVideoFile) return;
    setVideoUploadError(null);
    try {
      const asset = await uploadVideoMutation.mutateAsync(selectedVideoFile);
      setUploadedVideo(asset);
      untypedSetValue("videoAssetId", asset.id, { shouldValidate: true });
    } catch (error) {
      setVideoUploadError(
        isApiClientError(error) ? error.message : "Couldn't upload this video. Please try again."
      );
    }
  };

  const submit = handleSubmit(
    async (values) => {
      setSubmitError(null);
      try {
        if (values.kind === "VIDEO") {
          const videoValues = values as VideoMaterialFormValues;
          try {
            await upsertPolicyMutation.mutateAsync({
              videoAssetId: videoValues.videoAssetId,
              body: toVideoPolicyRequest(videoValues),
            });
          } catch (policyError) {
            setSubmitError(
              isApiClientError(policyError)
                ? `Couldn't save playback rules: ${policyError.message}`
                : "Couldn't save playback rules. Please try again."
            );
            return;
          }
        }
        await createMutation.mutateAsync(toMaterialCreateRequest(values));
        reset(defaultValuesForKind(kind));
        setSelectedFile(undefined);
        setSelectedVideoFile(undefined);
        setUploadedVideo(null);
        setFileInputKey((key) => key + 1);
        setVideoFileInputKey((key) => key + 1);
      } catch (error) {
        setSubmitError(
          isApiClientError(error)
            ? error.message
            : "Couldn't add this material. Please try again."
        );
      }
    },
    (formErrors) => {
      const firstMessage = Object.values(formErrors).find(
        (fieldError) => typeof fieldError?.message === "string"
      )?.message as string | undefined;
      setSubmitError(firstMessage ?? "Please fix the highlighted fields and try again.");
    }
  );

  // Kept as "Upload material"/"Uploading…" specifically for the `FILE` kind
  // (the pre-Wave-5 form's only kind) so every pre-existing Playwright spec
  // asserting on those exact strings (`material-upload-states.spec.ts` et
  // al.) keeps passing unchanged; every other kind uses the more accurate
  // "Add material"/"Saving…" copy, since nothing is actually being
  // "uploaded" for a link/note/already-uploaded-video attach action.
  const isFileKind = kind === "FILE";
  const submitIdleLabel = isFileKind ? "Upload material" : "Add material";
  const submitBusyLabel = isFileKind ? "Uploading…" : "Saving…";

  return (
    <div className="rounded-md border border-dashed border-border p-3">
      <h5 className="mb-2 text-xs font-medium text-foreground">Upload material</h5>
      <form className="flex flex-col gap-3" noValidate aria-busy={busy} onSubmit={submit}>
        <span role="status" aria-live="polite" className="sr-only">
          {busy ? submitBusyLabel : ""}
        </span>

        <fieldset disabled={busy} className="flex flex-col gap-3">
          <legend className="sr-only">Add a lesson material</legend>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-kind`}>Material type</Label>
            <Select value={kind} onValueChange={handleKindChange} disabled={busy}>
              <SelectTrigger id={`${idPrefix}-kind`} size="sm" className="w-full sm:w-56">
                <SelectValue>
                  {(selected: string | null) =>
                    selected ? MATERIAL_KIND_LABELS[selected as MaterialFormKind] : "Select a type"
                  }
                </SelectValue>
              </SelectTrigger>
              <SelectContent>
                {MATERIAL_KIND_OPTIONS.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-title`}>Title</Label>
            <Input
              id={`${idPrefix}-title`}
              aria-invalid={!!errors.title}
              aria-describedby={errors.title ? `${idPrefix}-title-error` : undefined}
              {...untypedRegister("title")}
            />
            {errors.title ? (
              <p id={`${idPrefix}-title-error`} role="alert" className="text-xs text-destructive">
                {String(errors.title.message)}
              </p>
            ) : null}
          </div>

          {kind === "FILE" ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={`${idPrefix}-file`}>File</Label>
              <div
                className={cn(
                  "flex flex-col items-center gap-2 rounded-md border-2 border-dashed border-border px-4 py-6 text-center transition-colors",
                  isDragOver && "border-primary bg-primary/5"
                )}
                onDragOver={(event) => {
                  event.preventDefault();
                  if (!busy) setIsDragOver(true);
                }}
                onDragLeave={() => setIsDragOver(false)}
                onDrop={(event) => {
                  event.preventDefault();
                  setIsDragOver(false);
                  if (!busy) handleFiles(event.dataTransfer.files);
                }}
              >
                <UploadCloud className="size-5 text-muted-foreground" aria-hidden="true" />
                <p className="text-xs text-muted-foreground">Drag and drop a file here, or</p>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => fileInputRef.current?.click()}
                >
                  Browse files
                </Button>
                <input
                  key={fileInputKey}
                  ref={fileInputRef}
                  id={`${idPrefix}-file`}
                  type="file"
                  className="sr-only"
                  accept={ACCEPTED_MATERIAL_MIME_TYPES.join(",")}
                  aria-invalid={!!errors.file}
                  aria-describedby={[
                    `${idPrefix}-file-helper`,
                    errors.file ? `${idPrefix}-file-error` : undefined,
                  ]
                    .filter(Boolean)
                    .join(" ")}
                  onChange={(event) => handleFiles(event.target.files)}
                />
                {selectedFile ? (
                  <p className="text-xs font-medium text-foreground">Selected: {selectedFile.name}</p>
                ) : null}
                <span role="status" aria-live="polite" className="sr-only">
                  {isDragOver ? "File ready to drop" : ""}
                </span>
              </div>
              <p id={`${idPrefix}-file-helper`} className="text-xs text-muted-foreground">
                {MATERIAL_UPLOAD_HELPER_TEXT}
              </p>
              {errors.file ? (
                <p id={`${idPrefix}-file-error`} role="alert" className="text-xs text-destructive">
                  {String(errors.file.message)}
                </p>
              ) : null}

              <AvailabilityAndLimitsFields idPrefix={idPrefix} register={untypedRegister} errors={errors} />
            </div>
          ) : null}

          {kind === "LINK" ? (
            <div className="flex flex-col gap-3">
              <div className="flex flex-col gap-1.5">
                <Label htmlFor={`${idPrefix}-external-url`}>Link URL</Label>
                <Input
                  id={`${idPrefix}-external-url`}
                  type="url"
                  placeholder="https://example.com/video"
                  aria-invalid={!!errors.externalUrl}
                  aria-describedby={errors.externalUrl ? `${idPrefix}-external-url-error` : undefined}
                  {...untypedRegister("externalUrl")}
                />
                {errors.externalUrl ? (
                  <p id={`${idPrefix}-external-url-error`} role="alert" className="text-xs text-destructive">
                    {String(errors.externalUrl.message)}
                  </p>
                ) : null}
              </div>
              <AvailabilityAndLimitsFields idPrefix={idPrefix} register={untypedRegister} errors={errors} />
            </div>
          ) : null}

          {kind === "NOTE" ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={`${idPrefix}-note-content`}>Note content</Label>
              <Textarea
                id={`${idPrefix}-note-content`}
                rows={4}
                aria-invalid={!!errors.noteContent}
                aria-describedby={errors.noteContent ? `${idPrefix}-note-content-error` : undefined}
                {...untypedRegister("noteContent")}
              />
              {errors.noteContent ? (
                <p id={`${idPrefix}-note-content-error`} role="alert" className="text-xs text-destructive">
                  {String(errors.noteContent.message)}
                </p>
              ) : null}
            </div>
          ) : null}

          {kind === "VIDEO" ? (
            <div className="flex flex-col gap-3">
              <div className="flex flex-col gap-1.5 rounded-md border border-border/70 p-3">
                <p className="text-xs font-semibold text-foreground">Step 1: Upload video file</p>
                <Label htmlFor={`${idPrefix}-video-file`}>Video file</Label>
                <input
                  key={videoFileInputKey}
                  ref={videoFileInputRef}
                  id={`${idPrefix}-video-file`}
                  type="file"
                  accept={ACCEPTED_VIDEO_MIME_TYPES.join(",")}
                  className="text-xs"
                  onChange={(event) => handleVideoFileSelect(event.target.files)}
                />
                <p className="text-xs text-muted-foreground">
                  Accepted formats: {ACCEPTED_VIDEO_FORMATS_LABEL}.
                </p>
                <div>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={!selectedVideoFile || uploadVideoMutation.isPending || busy}
                    aria-busy={uploadVideoMutation.isPending}
                    onClick={handleUploadVideoFile}
                  >
                    {uploadVideoMutation.isPending ? (
                      <>
                        <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
                        Uploading video…
                      </>
                    ) : (
                      "Upload video"
                    )}
                  </Button>
                </div>
                <span role="status" aria-live="polite" className="text-xs">
                  {uploadedVideo ? (
                    <span className="inline-flex items-center gap-1 text-emerald-700 dark:text-emerald-400">
                      <CheckCircle2 className="size-3.5" aria-hidden="true" />
                      Uploaded: {uploadedVideo.originalFilename} ({uploadedVideo.status})
                    </span>
                  ) : null}
                </span>
                {videoUploadError ? (
                  <p role="alert" className="inline-flex items-center gap-1 text-xs text-destructive">
                    <XCircle className="size-3.5" aria-hidden="true" />
                    {videoUploadError}
                  </p>
                ) : null}
                {errors.videoAssetId ? (
                  <p role="alert" className="text-xs text-destructive">
                    {String(errors.videoAssetId.message)}
                  </p>
                ) : null}
              </div>

              <div
                className={cn(
                  "flex flex-col gap-3 rounded-md border border-border/70 p-3",
                  !watchedVideoAssetId && "opacity-60"
                )}
              >
                <p className="text-xs font-semibold text-foreground">Step 2: Attach as material</p>
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor={`${idPrefix}-video-kind`}>Video type</Label>
                  <Select
                    value={(watch("videoKind" as never) as unknown as string) ?? "VIDEO"}
                    onValueChange={(value) => value && untypedSetValue("videoKind", value)}
                    disabled={busy || !watchedVideoAssetId}
                  >
                    <SelectTrigger id={`${idPrefix}-video-kind`} size="sm" className="w-full sm:w-56">
                      <SelectValue>
                        {(selected: string | null) =>
                          selected === "RECORDING" ? "Recorded class session" : "Instructional video"
                        }
                      </SelectValue>
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="VIDEO">Instructional video</SelectItem>
                      <SelectItem value="RECORDING">Recorded class session</SelectItem>
                    </SelectContent>
                  </Select>
                </div>

                <Accordion title="Playback rules" defaultOpen={false}>
                  <VideoPolicyFields idPrefix={idPrefix} register={untypedRegister} disabled={busy} />
                </Accordion>
              </div>
            </div>
          ) : null}

          <div>
            <Button
              type="submit"
              size="sm"
              disabled={busy || (kind === "VIDEO" && !watchedVideoAssetId)}
              aria-busy={busy}
            >
              {busy ? (
                <>
                  <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
                  {submitBusyLabel}
                </>
              ) : (
                submitIdleLabel
              )}
            </Button>
          </div>
        </fieldset>
      </form>

      {submitError ? (
        <div
          ref={errorRef}
          role="alert"
          tabIndex={-1}
          className="mt-2 rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive outline-none"
        >
          {submitError}
        </div>
      ) : null}
    </div>
  );
}

/** Shared "Availability & limits" fields for `FILE`/`LINK` kinds — the only two types `MaterialService#getDownloadUrl` actually enforces `availableFromAt`/`expiryAt`/`maxDownloads` against (see `lib/validation/material.ts`'s doc comment for why `NOTE`/`VIDEO` don't get this section). */
function AvailabilityAndLimitsFields({
  idPrefix,
  register,
  errors,
}: {
  idPrefix: string;
  register: (name: string) => ReturnType<ReturnType<typeof useForm>["register"]>;
  errors: Record<string, { message?: unknown } | undefined>;
}) {
  return (
    <Accordion title="Availability & limits" defaultOpen={false}>
      <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-available-from`}>Available from</Label>
          <Input id={`${idPrefix}-available-from`} type="datetime-local" {...register("availableFromAt")} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-expiry`}>Expires at</Label>
          <Input id={`${idPrefix}-expiry`} type="datetime-local" {...register("expiryAt")} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-max-downloads`}>Max downloads</Label>
          <Input
            id={`${idPrefix}-max-downloads`}
            type="number"
            min={1}
            step={1}
            className="w-28"
            aria-invalid={!!errors.maxDownloads}
            aria-describedby={errors.maxDownloads ? `${idPrefix}-max-downloads-error` : undefined}
            {...register("maxDownloads")}
          />
          {errors.maxDownloads ? (
            <p id={`${idPrefix}-max-downloads-error`} role="alert" className="text-xs text-destructive">
              {String(errors.maxDownloads.message)}
            </p>
          ) : null}
        </div>
      </div>
    </Accordion>
  );
}

/** The Teacher-facing "Playback rules" sub-form for a `VIDEO`/`RECORDING` material — maps to `VideoPlaybackPolicyRequest` via `toVideoPolicyRequest`. */
function VideoPolicyFields({
  idPrefix,
  register,
  disabled,
}: {
  idPrefix: string;
  register: (name: string) => ReturnType<ReturnType<typeof useForm>["register"]>;
  disabled: boolean;
}) {
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-access-start`}>Access start</Label>
          <Input id={`${idPrefix}-access-start`} type="datetime-local" disabled={disabled} {...register("accessStartAt")} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-access-end`}>Access end</Label>
          <Input id={`${idPrefix}-access-end`} type="datetime-local" disabled={disabled} {...register("accessEndAt")} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-max-views`}>Max views per student</Label>
          <Input id={`${idPrefix}-max-views`} type="number" min={1} step={1} className="w-28" disabled={disabled} {...register("maxViewsPerStudent")} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-max-watch-duration`}>Max watch duration (seconds)</Label>
          <Input id={`${idPrefix}-max-watch-duration`} type="number" min={1} step={1} className="w-32" disabled={disabled} {...register("maxWatchDurationSeconds")} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-max-concurrent`}>Max concurrent sessions</Label>
          <Input id={`${idPrefix}-max-concurrent`} type="number" min={1} step={1} className="w-28" disabled={disabled} {...register("maxConcurrentSessions")} />
        </div>
      </div>
      <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:gap-4">
        <label className="flex items-center gap-2 text-sm text-foreground">
          <input type="checkbox" disabled={disabled} {...register("allowSeeking")} />
          Allow seeking
        </label>
        <label className="flex items-center gap-2 text-sm text-foreground">
          <input type="checkbox" disabled={disabled} {...register("allowDownload")} />
          Allow download
        </label>
        <label className="flex items-center gap-2 text-sm text-foreground">
          <input type="checkbox" disabled={disabled} {...register("watermarkEnabled")} />
          Watermark enabled
        </label>
      </div>
    </div>
  );
}
