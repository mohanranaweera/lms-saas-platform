"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Controller, useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { AlertCircle, CheckCircle2, UploadCloud } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import {
  mediaKeys,
  useMediaUploadApi,
  useMediaUploadLimits,
  type MediaAsset,
  type MediaUploadLimits,
} from "@/lib/api/media";
import { uploadFileParts, UploadAbortedError } from "@/lib/api/upload-multipart";
import { formatBytes, percent } from "@/lib/media-format";
import { buildMediaUploadSchema, type MediaUploadFormValues } from "@/lib/validation/media";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";

type Phase =
  | { kind: "idle" }
  | { kind: "uploading"; sent: number; total: number }
  | { kind: "finishing" }
  | { kind: "done"; asset: MediaAsset }
  | { kind: "error"; message: string };

const selectClass =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30";

const EMPTY: MediaUploadFormValues = { title: "", description: "", privacyStatus: "UNLISTED", file: null };

/** Why uploading is not possible right now, from the server's limits (or null). */
function blockedReason(limits: MediaUploadLimits): string | null {
  if (!limits.canUpload) {
    return limits.notAllowedReason ?? "Your institute hasn't given you access to upload videos. Ask your institute admin.";
  }
  if (!limits.uploadsEnabled) return "Video uploads are switched off for your institute.";
  if (limits.channel === "NOT_CONNECTED") return "Your institute hasn't connected a YouTube channel yet.";
  if (limits.channel === "EXPIRED") return "YouTube needs to be reconnected before videos can be uploaded.";
  if (limits.channel === "DISABLED") return "Video uploads were switched off by the platform for your institute.";
  if (limits.usedToday >= limits.dailyLimit) {
    return `Your institute has used today's ${limits.dailyLimit} uploads. Try again tomorrow.`;
  }
  return null;
}

/**
 * Upload a video to the institute's YouTube channel (VIDEO Phases 4-5). The
 * file goes from this browser straight to temporary storage in parts; the
 * server checks it and sends it on to YouTube in the background, so the page
 * can be closed once "Upload received" shows. Every rule (type, size, daily
 * limit, who may upload) is enforced again by the server.
 */
export function MediaUploadPanel({
  settingsHref,
  onClose,
}: {
  /** Where the YouTube channel is managed, shown to those who may manage it. */
  settingsHref?: string;
  onClose: () => void;
}) {
  const limitsQuery = useMediaUploadLimits();

  if (limitsQuery.isPending) {
    return (
      <p role="status" aria-busy="true" className="text-sm text-muted-foreground">
        Checking upload settings…
      </p>
    );
  }
  if (limitsQuery.isError) {
    const forbidden = isApiClientError(limitsQuery.error) && limitsQuery.error.status === 403;
    return (
      <Alert variant="destructive">
        <AlertCircle aria-hidden="true" />
        <AlertDescription>
          {forbidden ? "You don't have permission to upload videos." : "Upload settings could not be loaded."}
        </AlertDescription>
      </Alert>
    );
  }
  return <UploadForm limits={limitsQuery.data} settingsHref={settingsHref} onClose={onClose} />;
}

function UploadForm({
  limits,
  settingsHref,
  onClose,
}: {
  limits: MediaUploadLimits;
  settingsHref?: string;
  onClose: () => void;
}) {
  const api = useMediaUploadApi();
  const queryClient = useQueryClient();
  const ids = { form: useId(), title: useId(), description: useId(), privacy: useId(), file: useId() };
  const schema = useMemo(
    () => buildMediaUploadSchema(limits.allowedMimeTypes, limits.maxFileSizeBytes),
    [limits.allowedMimeTypes, limits.maxFileSizeBytes]
  );
  const {
    register,
    control,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<MediaUploadFormValues>({ resolver: zodResolver(schema), defaultValues: EMPTY });
  const [phase, setPhase] = useState<Phase>({ kind: "idle" });
  const abortRef = useRef<AbortController | null>(null);
  const assetIdRef = useRef<string | null>(null);

  const busy = phase.kind === "uploading" || phase.kind === "finishing";
  const blocked = blockedReason(limits);
  const disabled = busy || Boolean(blocked);

  useEffect(() => {
    if (!busy) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [busy]);

  useEffect(() => () => abortRef.current?.abort(), []);

  const cancelOnServer = async () => {
    const assetId = assetIdRef.current;
    assetIdRef.current = null;
    if (assetId) {
      try {
        await api.cancel(assetId);
      } catch {
        // The server also cleans up unfinished uploads on its own.
      }
    }
  };

  const upload = async (values: MediaUploadFormValues) => {
    const file = values.file;
    if (!file) return;
    const controller = new AbortController();
    abortRef.current = controller;
    setPhase({ kind: "uploading", sent: 0, total: file.size });
    try {
      const started = await api.start({
        title: values.title.trim(),
        description: values.description.trim() || undefined,
        privacyStatus: values.privacyStatus,
        fileName: file.name,
        mimeType: file.type,
        sizeBytes: file.size,
      });
      assetIdRef.current = started.mediaAsset.id;
      const parts = await uploadFileParts({
        file,
        partSizeBytes: started.upload.partSizeBytes,
        partCount: started.upload.partCount,
        initialParts: started.upload.parts,
        presign: async (numbers) => (await api.presign(started.mediaAsset.id, numbers)).parts,
        onProgress: (sent, total) => setPhase({ kind: "uploading", sent, total }),
        signal: controller.signal,
      });
      setPhase({ kind: "finishing" });
      const asset = await api.complete(started.mediaAsset.id, parts);
      assetIdRef.current = null;
      setPhase({ kind: "done", asset });
    } catch (error) {
      if (error instanceof UploadAbortedError) {
        await cancelOnServer();
        setPhase({ kind: "idle" });
        return;
      }
      if (isApiClientError(error)) {
        for (const fieldError of error.fieldErrors) {
          if (["title", "description", "privacyStatus"].includes(fieldError.field)) {
            setError(fieldError.field as "title" | "description" | "privacyStatus", { message: fieldError.message });
          }
        }
      }
      // A rejected file is already discarded by the server; anything else is cancelled so the slot comes back.
      if (!(isApiClientError(error) && error.code === "MEDIA_UPLOAD_FAILED")) {
        await cancelOnServer();
      }
      assetIdRef.current = null;
      setPhase({
        kind: "error",
        message:
          error instanceof Error && error.message ? error.message : "The video could not be uploaded. Try again.",
      });
    } finally {
      abortRef.current = null;
      void queryClient.invalidateQueries({ queryKey: mediaKeys.all });
    }
  };

  if (phase.kind === "done") {
    return (
      <section aria-label="Upload received" className="flex flex-col gap-3 rounded-lg border border-border p-4">
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>
            Upload received. &ldquo;{phase.asset.title}&rdquo; is being sent to YouTube. You can leave this page; its
            status updates in the library.
          </AlertDescription>
        </Alert>
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            onClick={() => {
              reset(EMPTY);
              setPhase({ kind: "idle" });
            }}
          >
            Upload another video
          </Button>
          <Button type="button" variant="outline" onClick={onClose}>
            Close
          </Button>
        </div>
      </section>
    );
  }

  return (
    <form
      id={ids.form}
      aria-label="Upload a video"
      noValidate
      onSubmit={(event) => void handleSubmit(upload)(event)}
      className="flex flex-col gap-4 rounded-lg border border-border p-4"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-base font-medium text-foreground">Upload a video</h2>
        <p className="text-sm text-muted-foreground">
          {limits.usedToday} of {limits.dailyLimit} uploads used today · up to {formatBytes(limits.maxFileSizeBytes)}
        </p>
      </div>

      {blocked ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            {blocked}{" "}
            {settingsHref && (limits.channel === "NOT_CONNECTED" || limits.channel === "EXPIRED") ? (
              <Link href={settingsHref} className="font-medium underline">
                Open video providers
              </Link>
            ) : null}
          </AlertDescription>
        </Alert>
      ) : null}

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={ids.title}>Title</Label>
        <Input
          id={ids.title}
          maxLength={100}
          disabled={disabled}
          aria-invalid={Boolean(errors.title)}
          aria-describedby={errors.title ? `${ids.title}-error` : undefined}
          {...register("title")}
        />
        {errors.title ? (
          <p id={`${ids.title}-error`} className="text-sm text-destructive">
            {errors.title.message}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={ids.description}>Description (optional)</Label>
        <Textarea
          id={ids.description}
          rows={3}
          disabled={disabled}
          aria-invalid={Boolean(errors.description)}
          aria-describedby={errors.description ? `${ids.description}-error` : undefined}
          {...register("description")}
        />
        {errors.description ? (
          <p id={`${ids.description}-error`} className="text-sm text-destructive">
            {errors.description.message}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor={ids.privacy}>Visibility on YouTube</Label>
        <select
          id={ids.privacy}
          disabled={disabled}
          aria-describedby={`${ids.privacy}-hint`}
          className={selectClass}
          {...register("privacyStatus")}
        >
          <option value="UNLISTED">Unlisted - only people with the link</option>
          <option value="PRIVATE">Private - only your channel</option>
        </select>
        <p id={`${ids.privacy}-hint`} className="text-sm text-muted-foreground">
          Students watch through the LMS. An unlisted video can still be opened on YouTube by anyone who has its link.
        </p>
      </div>

      <Controller
        control={control}
        name="file"
        render={({ field, fieldState }) => (
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={ids.file}>Video file</Label>
            <input
              id={ids.file}
              type="file"
              accept={limits.allowedMimeTypes.join(",")}
              disabled={disabled}
              onBlur={field.onBlur}
              onChange={(e) => field.onChange(e.target.files?.[0] ?? null)}
              aria-invalid={Boolean(fieldState.error)}
              aria-describedby={fieldState.error ? `${ids.file}-error` : undefined}
              className="text-sm file:mr-3 file:rounded-md file:border file:border-border file:bg-muted file:px-3 file:py-1 file:text-sm"
            />
            {field.value ? (
              <p className="text-sm text-muted-foreground">
                {field.value.name} · {formatBytes(field.value.size)}
              </p>
            ) : null}
            {fieldState.error ? (
              <p id={`${ids.file}-error`} className="text-sm text-destructive">
                {fieldState.error.message}
              </p>
            ) : null}
          </div>
        )}
      />

      {phase.kind === "uploading" ? (
        <div className="flex flex-col gap-1.5">
          <div
            role="progressbar"
            aria-label="Upload progress"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={percent(phase.sent, phase.total)}
            className="h-2 w-full overflow-hidden rounded-full bg-muted"
          >
            <div className="h-full bg-primary transition-all" style={{ width: `${percent(phase.sent, phase.total)}%` }} />
          </div>
          <p className="text-sm text-muted-foreground" aria-live="polite">
            Uploading… {percent(phase.sent, phase.total)}% ({formatBytes(phase.sent)} of {formatBytes(phase.total)}).
            Keep this page open until the upload is received.
          </p>
        </div>
      ) : null}
      {phase.kind === "finishing" ? (
        <p role="status" className="text-sm text-muted-foreground">
          Checking the file…
        </p>
      ) : null}
      {phase.kind === "error" ? (
        <Alert variant="destructive" role="alert">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{phase.message}</AlertDescription>
        </Alert>
      ) : null}

      <div className="flex flex-wrap gap-2">
        {busy ? (
          <Button
            type="button"
            variant="outline"
            onClick={() => abortRef.current?.abort()}
            disabled={phase.kind === "finishing"}
          >
            Cancel upload
          </Button>
        ) : (
          <>
            <Button type="submit" disabled={Boolean(blocked)}>
              <UploadCloud aria-hidden="true" />
              Upload video
            </Button>
            <Button type="button" variant="outline" onClick={onClose}>
              Close
            </Button>
          </>
        )}
      </div>
    </form>
  );
}
