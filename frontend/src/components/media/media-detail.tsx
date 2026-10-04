"use client";

import { useId, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, CheckCircle2, Film } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import {
  useCancelMediaUpload,
  useDeleteMedia,
  useMediaAsset,
  useMediaUploadLimits,
  useRetryMediaUpload,
  useUpdateMedia,
  type MediaAsset,
} from "@/lib/api/media";
import { formatDateTime } from "@/lib/format";
import { formatBytes, formatDuration, percent } from "@/lib/media-format";
import { mediaMetadataSchema, type MediaMetadataFormValues } from "@/lib/validation/media";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { MediaStatusBadge } from "./media-status-badge";

const selectClass =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30";

const RECONNECT_CODES = new Set(["VIDEO_PROVIDER_SCOPE_MISSING", "VIDEO_PROVIDER_AUTH_EXPIRED"]);

function errorText(error: unknown, fallback: string): string {
  return isApiClientError(error) ? error.message : fallback;
}

/** Plain-language explanation of where an upload stands, when there is something to say. */
function UploadExplanation({ asset, settingsHref }: { asset: MediaAsset; settingsHref?: string }) {
  const progress = asset.uploadProgress;
  if (asset.processingStatus === "FAILED") {
    return (
      <Alert variant="destructive">
        <AlertCircle aria-hidden="true" />
        <AlertDescription>{asset.failureReason ?? progress?.message ?? "This video failed."}</AlertDescription>
      </Alert>
    );
  }
  if (asset.processingStatus === "CREATED") {
    return (
      <Alert>
        <AlertCircle aria-hidden="true" />
        <AlertDescription>
          The file was never finished uploading. Cancel this upload and upload the video again.
        </AlertDescription>
      </Alert>
    );
  }
  if (asset.processingStatus === "UPLOADING" && progress) {
    if (progress.state === "QUOTA_PAUSED") {
      return (
        <Alert>
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            YouTube&apos;s upload limit for today has been reached. This video continues automatically tomorrow.
          </AlertDescription>
        </Alert>
      );
    }
    if (progress.state === "PARKED_RECONNECT") {
      return (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            {progress.message ?? "YouTube needs to be reconnected."} The upload continues once the same channel is
            connected again.{" "}
            {settingsHref ? (
              <Link href={settingsHref} className="font-medium underline">
                Open video providers
              </Link>
            ) : null}
          </AlertDescription>
        </Alert>
      );
    }
    return (
      <div className="flex flex-col gap-1.5">
        <div
          role="progressbar"
          aria-label="Sending to YouTube"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={percent(progress.bytesSent, progress.totalBytes)}
          className="h-2 w-full overflow-hidden rounded-full bg-muted"
        >
          <div
            className="h-full bg-primary"
            style={{ width: `${percent(progress.bytesSent, progress.totalBytes)}%` }}
          />
        </div>
        <p className="text-sm text-muted-foreground">
          {progress.state === "RETRY_WAIT" ? "YouTube didn't respond; trying again shortly. " : ""}
          {formatBytes(progress.bytesSent)} of {formatBytes(progress.totalBytes)} sent to YouTube.
        </p>
      </div>
    );
  }
  if (asset.processingStatus === "UPLOADED" || asset.processingStatus === "PROCESSING") {
    return (
      <p className="text-sm text-muted-foreground" role="status">
        YouTube is processing this video. This usually takes a few minutes; the page updates by itself.
      </p>
    );
  }
  return null;
}

function EditForm({
  asset,
  editingEnabled,
  settingsHref,
  onCancel,
  onSaved,
}: {
  asset: MediaAsset;
  editingEnabled: boolean;
  settingsHref?: string;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const ids = { title: useId(), description: useId(), privacy: useId() };
  const update = useUpdateMedia(asset.id);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<MediaMetadataFormValues>({
    resolver: zodResolver(mediaMetadataSchema),
    defaultValues: {
      title: asset.title,
      description: asset.description ?? "",
      privacyStatus: asset.privacyStatus === "PRIVATE" ? "PRIVATE" : "UNLISTED",
    },
  });
  const onYouTube =
    asset.provider === "YOUTUBE" && asset.processingStatus !== "CREATED" && asset.processingStatus !== "UPLOADING";
  const serverFieldError = isApiClientError(update.error) && update.error.fieldErrors.length > 0;

  const onSubmit = handleSubmit((values) => {
    update.mutate(
      {
        title: values.title.trim(),
        description: values.description.trim(),
        privacyStatus: asset.provider === "YOUTUBE" ? values.privacyStatus : undefined,
      },
      {
        onSuccess: onSaved,
        onError: (error) => {
          if (!isApiClientError(error)) return;
          for (const fieldError of error.fieldErrors) {
            if (["title", "description", "privacyStatus"].includes(fieldError.field)) {
              setError(fieldError.field as keyof MediaMetadataFormValues, { message: fieldError.message });
            }
          }
        },
      }
    );
  });

  return (
    <form
      aria-label="Edit details"
      noValidate
      onSubmit={onSubmit}
      className="flex max-w-2xl flex-col gap-4 rounded-lg border border-border p-4"
    >
      {onYouTube ? <p className="text-sm text-muted-foreground">Changes are also made on YouTube.</p> : null}
      {onYouTube && !editingEnabled ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            Your YouTube channel was connected before editing was available. Reconnect it to edit videos.{" "}
            {settingsHref ? (
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
        <Label htmlFor={ids.description}>Description</Label>
        <Textarea
          id={ids.description}
          rows={4}
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
      {asset.provider === "YOUTUBE" ? (
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.privacy}>Visibility on YouTube</Label>
          <select id={ids.privacy} className={selectClass} {...register("privacyStatus")}>
            <option value="UNLISTED">Unlisted - only people with the link</option>
            <option value="PRIVATE">Private - only your channel</option>
          </select>
        </div>
      ) : null}
      {update.isError && !serverFieldError ? (
        <Alert variant="destructive" role="alert">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            {errorText(update.error, "The changes could not be saved.")}{" "}
            {isApiClientError(update.error) && RECONNECT_CODES.has(update.error.code) && settingsHref ? (
              <Link href={settingsHref} className="font-medium underline">
                Open video providers
              </Link>
            ) : null}
          </AlertDescription>
        </Alert>
      ) : null}
      <div className="flex flex-wrap gap-2">
        <Button type="submit" disabled={update.isPending} aria-busy={update.isPending}>
          {update.isPending ? "Saving…" : "Save changes"}
        </Button>
        <Button type="button" variant="outline" onClick={onCancel} disabled={update.isPending}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function DeleteMediaDialog({ asset, listHref }: { asset: MediaAsset; listHref: string }) {
  const router = useRouter();
  const remove = useDeleteMedia();
  const [open, setOpen] = useState(false);
  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger render={<Button type="button" variant="destructive" />}>Delete</AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete &ldquo;{asset.title}&rdquo;?</AlertDialogTitle>
          <AlertDialogDescription>
            It is removed from the Media Library.
            {asset.provider === "YOUTUBE"
              ? " The video stays on your YouTube channel - delete it in YouTube Studio if you no longer want it there."
              : ""}
          </AlertDialogDescription>
        </AlertDialogHeader>
        {remove.isError ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorText(remove.error, "The video could not be deleted.")}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Keep it</AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            disabled={remove.isPending}
            aria-busy={remove.isPending}
            onClick={() => remove.mutate(asset.id, { onSuccess: () => router.push(listHref) })}
          >
            {remove.isPending ? "Deleting…" : "Delete"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

function MediaDetailBody({
  asset,
  canManage,
  listHref,
  settingsHref,
}: {
  asset: MediaAsset;
  canManage: boolean;
  listHref: string;
  settingsHref?: string;
}) {
  const [editing, setEditing] = useState(false);
  const [saved, setSaved] = useState(false);
  const limits = useMediaUploadLimits(canManage);
  const cancel = useCancelMediaUpload();
  const retry = useRetryMediaUpload();
  const live =
    asset.uploadProgress != null && !["COMPLETED", "FAILED", "CANCELLED"].includes(asset.uploadProgress.state);
  const canRetry = asset.processingStatus === "FAILED" && asset.uploadProgress?.state === "FAILED";
  const sending = asset.processingStatus === "UPLOADING" || asset.processingStatus === "UPLOADED";
  const duration = formatDuration(asset.durationSeconds);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-4 sm:flex-row">
        <div className="flex aspect-video w-full max-w-sm shrink-0 items-center justify-center overflow-hidden rounded-lg bg-muted">
          {asset.thumbnailUrl ? (
            // eslint-disable-next-line @next/next/no-img-element -- provider thumbnail on a provider CDN
            <img src={asset.thumbnailUrl} alt="" className="h-full w-full object-cover" />
          ) : (
            <Film aria-hidden="true" className="size-10 text-muted-foreground" />
          )}
        </div>
        <div className="flex min-w-0 flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <MediaStatusBadge asset={asset} />
            {asset.attached ? <span className="text-sm text-muted-foreground">Used in a lesson</span> : null}
          </div>
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
            <dt className="text-muted-foreground">Stored on</dt>
            <dd className="text-foreground">{asset.provider === "YOUTUBE" ? "YouTube" : "Institute storage"}</dd>
            {asset.privacyStatus ? (
              <>
                <dt className="text-muted-foreground">Visibility</dt>
                <dd className="text-foreground">{asset.privacyStatus === "PRIVATE" ? "Private" : "Unlisted"}</dd>
              </>
            ) : null}
            {duration ? (
              <>
                <dt className="text-muted-foreground">Length</dt>
                <dd className="text-foreground">{duration}</dd>
              </>
            ) : null}
            <dt className="text-muted-foreground">Added</dt>
            <dd className="text-foreground">{formatDateTime(asset.createdAt)}</dd>
          </dl>
          {asset.description ? (
            <p className="text-sm whitespace-pre-line text-foreground">{asset.description}</p>
          ) : null}
        </div>
      </div>

      <UploadExplanation asset={asset} settingsHref={settingsHref} />

      {saved ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>Changes saved.</AlertDescription>
        </Alert>
      ) : null}
      {cancel.isError || retry.isError ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            {errorText(cancel.error ?? retry.error, "That didn't work. Please try again.")}
          </AlertDescription>
        </Alert>
      ) : null}

      {canManage && editing ? (
        <EditForm
          asset={asset}
          editingEnabled={limits.data?.editingEnabled ?? true}
          settingsHref={settingsHref}
          onCancel={() => setEditing(false)}
          onSaved={() => {
            setEditing(false);
            setSaved(true);
          }}
        />
      ) : null}

      {canManage && !editing ? (
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant="outline"
            disabled={sending}
            onClick={() => {
              setSaved(false);
              setEditing(true);
            }}
          >
            Edit details
          </Button>
          {live ? (
            <Button
              type="button"
              variant="outline"
              disabled={cancel.isPending}
              aria-busy={cancel.isPending}
              onClick={() => cancel.mutate(asset.id)}
            >
              {cancel.isPending ? "Cancelling…" : "Cancel upload"}
            </Button>
          ) : null}
          {canRetry ? (
            <Button
              type="button"
              disabled={retry.isPending}
              aria-busy={retry.isPending}
              onClick={() => retry.mutate(asset.id)}
            >
              {retry.isPending ? "Retrying…" : "Retry upload"}
            </Button>
          ) : null}
          {!live && !asset.attached ? <DeleteMediaDialog asset={asset} listHref={listHref} /> : null}
        </div>
      ) : null}
      {canManage && sending ? (
        <p className="text-sm text-muted-foreground">Details can be edited once the video has reached YouTube.</p>
      ) : null}
      {canManage && asset.attached ? (
        <p className="text-sm text-muted-foreground">
          This video is used in a lesson. Remove it from the lesson before deleting it.
        </p>
      ) : null}
    </div>
  );
}

/** One media item: details, upload status, and edit / cancel / retry / delete (VIDEO Phase 5). */
export function MediaDetail({
  mediaId,
  listHref,
  dashboardHref,
  canManage,
  settingsHref,
}: {
  mediaId: string;
  listHref: string;
  dashboardHref: string;
  canManage: boolean;
  settingsHref?: string;
}) {
  const query = useMediaAsset(mediaId);
  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link href={listHref} className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline">
          ← Media Library
        </Link>
        <h1 className="mt-2 text-xl font-semibold break-words text-foreground">{query.data?.title ?? "Media"}</h1>
      </div>
      <QueryStateBoundary query={query} loadingLabel="Loading media…" loginPath="/login" permissionDenied={{ dashboardHref }}>
        {(asset) => (
          <MediaDetailBody asset={asset} canManage={canManage} listHref={listHref} settingsHref={settingsHref} />
        )}
      </QueryStateBoundary>
    </div>
  );
}
