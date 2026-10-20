"use client";

import { useEffect, useId, useState } from "react";
import { InternalVideoUploadForm } from "./internal-video-upload-form";
import { Controller, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Loader2, UploadCloud } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { isApiClientError } from "@/lib/api/error";
import { formatFileSize } from "@/lib/api/materials";
import {
  MEDIA_FILE_ACCEPT,
  lessonCountLabel,
  mediaFileDeleteErrorMessage,
  mediaFileRenameErrorMessage,
  mediaFileRenameSchema,
  mediaFileUploadErrorMessage,
  mediaFileUploadSchema,
  useDeleteMediaFile,
  useRenameMediaFile,
  useUploadMediaFile,
  type MediaFile,
  type MediaFileRenameFormValues,
  type MediaFileUploadFormOutput,
  type MediaFileUploadFormValues,
} from "@/lib/api/media-files";
import { MediaDialog } from "./media-dialog";

/** The server's field error for `field`, when a 400 named one. */
function serverFieldError(error: unknown, field: string): string | null {
  if (!isApiClientError(error)) return null;
  return error.fieldErrors?.find((fieldError) => fieldError.field === field)?.message ?? null;
}

const NEW_MEDIA_ACCEPT = `${MEDIA_FILE_ACCEPT},video/*`;

function isVideoFile(file: File): boolean {
  return file.type.startsWith("video/") || /\.(mp4|mov|webm|mkv|avi|m4v|mpeg|mpg)$/i.test(file.name);
}

/**
 * Uploads-tab New Media: documents/images use the file library; videos use tenant internal storage.
 * The accept list and the 25 MB check are UX only - the server sniffs the
 * content and enforces the limits (415 / 413 / 400 are mapped to clear copy).
 * Mount it only while open so every opening starts from an empty form.
 */
export function MediaFileUploadDialog({
  open,
  onOpenChange,
  onUploaded,
  onVideoLibrary,
  onVideoUploaded,
  onBusyChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onUploaded?: (file: MediaFile) => void;
  /** "View videos" after an internal video upload; omitted = no such button (the dialog's Close still works). */
  onVideoLibrary?: () => void;
  /** An internal video was received by the server (it may still be processing). */
  onVideoUploaded?: () => void;
  onBusyChange?: (busy: boolean) => void;
}) {
  const formId = useId();
  const ids = { title: useId(), file: useId(), hint: useId(), fileError: useId(), titleError: useId() };
  const upload = useUploadMediaFile();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    register,
    control,
    handleSubmit,
    setError,
    setValue,
    formState: { errors },
  } = useForm<MediaFileUploadFormValues, unknown, MediaFileUploadFormOutput>({
    resolver: zodResolver(mediaFileUploadSchema),
    defaultValues: { title: "", file: null },
  });
  const chosen = useWatch({ control, name: "file" });
  const video = chosen instanceof File && isVideoFile(chosen) ? chosen : null;
  const [videoBusy, setVideoBusy] = useState(false);
  const [received, setReceived] = useState(false);
  const busy = upload.isPending || videoBusy;
  useEffect(() => {
    onBusyChange?.(busy);
  }, [busy, onBusyChange]);
  useEffect(() => () => onBusyChange?.(false), [onBusyChange]);

  function handleOpenChange(next: boolean) {
    if (!next && (upload.isPending || videoBusy)) return; // keep the dialog while the request is in flight
    onOpenChange(next);
  }

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    if (!values.file) return;
    try {
      const created = await upload.mutateAsync({ file: values.file, title: values.title.trim() || undefined });
      onUploaded?.(created);
      onOpenChange(false);
    } catch (error) {
      const titleError = serverFieldError(error, "title");
      if (titleError) {
        setError("title", { type: "server", message: titleError });
        return;
      }
      setServerError(mediaFileUploadErrorMessage(error));
    }
  });

  if (video) {
    return (
      <MediaDialog
        open={open}
        onOpenChange={handleOpenChange}
        title="New media"
        description="Upload this video to tenant internal storage, matching the Uploads tab."
        testId="media-file-upload-dialog"
        className="sm:max-w-2xl"
        footer={received && onVideoLibrary ? (
          <Button type="button" disabled={videoBusy} onClick={() => { if (!videoBusy) onVideoLibrary(); }} data-testid="media-file-upload-view-videos">View videos</Button>
        ) : null}
      >
        <div className="flex flex-col gap-4">
          <p className="text-sm break-words">Selected file: {video.name} · {formatFileSize(video.size)}</p>
          <Button type="button" variant="outline" disabled={videoBusy} onClick={() => {
            setReceived(false);
            setValue("file", null);
          }}>Choose a different file</Button>
          <InternalVideoUploadForm
            file={video}
            onBusyChange={setVideoBusy}
            onReceived={() => {
              setReceived(true);
              onVideoUploaded?.();
            }}
          />
        </div>
      </MediaDialog>
    );
  }

  return (
    <MediaDialog
      open={open}
      onOpenChange={handleOpenChange}
      title="New media"
      description="Upload a video, document or image. Documents/images: PDF, PNG, JPEG, GIF or plain text, up to 25 MB. Internal videos: MP4, WebM or MOV, up to 25 MiB."
      testId="media-file-upload-dialog"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => handleOpenChange(false)} disabled={upload.isPending}>
            Cancel
          </Button>
          <Button
            type="submit"
            form={formId}
            disabled={upload.isPending}
            aria-busy={upload.isPending}
            data-testid="media-file-upload-submit"
          >
            {upload.isPending ? (
              <>
                <Loader2 aria-hidden="true" className="animate-spin" /> Uploading…
              </>
            ) : (
              <>
                <UploadCloud aria-hidden="true" /> Upload
              </>
            )}
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} noValidate aria-busy={upload.isPending} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.file}>File</Label>
          <Controller
            control={control}
            name="file"
            render={({ field }) => (
              <Input
                id={ids.file}
                ref={field.ref}
                name={field.name}
                onBlur={field.onBlur}
                type="file"
                accept={NEW_MEDIA_ACCEPT}
                required
                disabled={upload.isPending}
                aria-invalid={errors.file ? true : undefined}
                aria-describedby={`${ids.hint}${errors.file ? ` ${ids.fileError}` : ""}`}
                data-testid="media-file-upload-input"
                onChange={(event) => {
                  setServerError(null);
                  field.onChange(event.target.files?.[0] ?? null);
                }}
              />
            )}
          />
          <p id={ids.hint} className="text-xs text-muted-foreground">
            {chosen ? `${chosen.name} · ${formatFileSize(chosen.size)}` : "Video, PDF, PNG, JPEG, GIF or .txt. Documents/images up to 25 MB; internal videos up to 25 MiB."}
          </p>
          {errors.file?.message ? (
            <p
              id={ids.fileError}
              role="alert"
              className="text-xs text-destructive"
              data-testid="media-file-upload-file-error"
            >
              {errors.file.message}
            </p>
          ) : null}
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.title}>
            Title <span className="font-normal text-muted-foreground">(optional)</span>
          </Label>
          <Input
            id={ids.title}
            maxLength={255}
            disabled={upload.isPending}
            placeholder="Defaults to the file name"
            aria-invalid={errors.title ? true : undefined}
            aria-describedby={errors.title ? ids.titleError : undefined}
            data-testid="media-file-upload-title"
            {...register("title")}
          />
          {errors.title?.message ? (
            <p id={ids.titleError} role="alert" className="text-xs text-destructive">
              {errors.title.message}
            </p>
          ) : null}
        </div>
        {upload.isPending ? (
          <div role="status" aria-live="polite" className="flex flex-col gap-1.5">
            <span className="text-xs text-muted-foreground">Uploading {chosen?.name}…</span>
            <div className="h-1.5 w-full overflow-hidden rounded-full bg-muted">
              <div className="h-full w-1/3 animate-pulse rounded-full bg-primary" />
            </div>
          </div>
        ) : null}
        {serverError ? (
          <Alert variant="destructive" data-testid="media-file-upload-error">
            <AlertDescription>{serverError}</AlertDescription>
          </Alert>
        ) : null}
      </form>
    </MediaDialog>
  );
}

/**
 * Rename one library file (offered only when the server says `canEdit`; the
 * server re-checks). Mount it only while open so it starts from the current title.
 */
export function RenameMediaFileDialog({
  file,
  open,
  onOpenChange,
}: {
  file: MediaFile;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const formId = useId();
  const ids = { title: useId(), error: useId() };
  const rename = useRenameMediaFile();
  const [serverError, setServerError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<MediaFileRenameFormValues>({
    resolver: zodResolver(mediaFileRenameSchema),
    defaultValues: { title: file.title },
  });

  function handleOpenChange(next: boolean) {
    if (!next && rename.isPending) return;
    onOpenChange(next);
  }

  const submit = handleSubmit(async (values) => {
    setServerError(null);
    const title = values.title.trim();
    if (title === file.title) {
      onOpenChange(false);
      return;
    }
    try {
      await rename.mutateAsync({ id: file.id, title });
      onOpenChange(false);
    } catch (error) {
      const titleError = serverFieldError(error, "title");
      if (titleError) {
        setError("title", { type: "server", message: titleError });
        return;
      }
      setServerError(mediaFileRenameErrorMessage(error));
    }
  });

  return (
    <MediaDialog
      open={open}
      onOpenChange={handleOpenChange}
      title="Rename file"
      testId="media-file-rename-dialog"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => handleOpenChange(false)} disabled={rename.isPending}>
            Cancel
          </Button>
          <Button
            type="submit"
            form={formId}
            disabled={rename.isPending}
            aria-busy={rename.isPending}
            data-testid="media-file-rename-submit"
          >
            {rename.isPending ? "Saving…" : "Save"}
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} noValidate aria-busy={rename.isPending} className="flex flex-col gap-4">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.title}>Title</Label>
          <Input
            id={ids.title}
            maxLength={255}
            required
            disabled={rename.isPending}
            aria-invalid={errors.title ? true : undefined}
            aria-describedby={errors.title ? ids.error : undefined}
            data-testid="media-file-rename-input"
            {...register("title")}
          />
          {errors.title?.message ? (
            <p id={ids.error} role="alert" className="text-xs text-destructive">
              {errors.title.message}
            </p>
          ) : null}
        </div>
        {serverError ? (
          <Alert variant="destructive" data-testid="media-file-rename-error">
            <AlertDescription>{serverError}</AlertDescription>
          </Alert>
        ) : null}
      </form>
    </MediaDialog>
  );
}

/**
 * Confirm deleting a library file. A file still used by lessons is refused by
 * the server (409 `MEDIA_ATTACHED`), shown as "Used in N lessons - remove it
 * from those sessions first".
 */
export function DeleteMediaFileDialog({
  file,
  open,
  onOpenChange,
}: {
  file: MediaFile;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const remove = useDeleteMediaFile();

  function handleOpenChange(next: boolean) {
    if (!next && remove.isPending) return;
    onOpenChange(next);
  }

  async function handleDelete() {
    try {
      await remove.mutateAsync(file.id);
      onOpenChange(false);
    } catch {
      // Rendered below; the dialog stays open.
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={handleOpenChange}>
      <AlertDialogContent data-testid="media-file-delete-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>Delete &ldquo;{file.title}&rdquo;?</AlertDialogTitle>
          <AlertDialogDescription>
            {file.attachedLessonCount > 0
              ? `This file is used in ${lessonCountLabel(file.attachedLessonCount)}. Remove it from those sessions before deleting it.`
              : "The file is removed from the Media Library and can no longer be attached to sessions."}
          </AlertDialogDescription>
        </AlertDialogHeader>
        {remove.isError ? (
          <Alert variant="destructive" role="alert" data-testid="media-file-delete-error">
            <AlertDescription>{mediaFileDeleteErrorMessage(remove.error, file.attachedLessonCount)}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />} disabled={remove.isPending}>
            Cancel
          </AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            onClick={() => void handleDelete()}
            disabled={remove.isPending}
            aria-busy={remove.isPending}
            data-testid="media-file-delete-confirm"
          >
            {remove.isPending ? "Deleting…" : "Delete file"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
