"use client";

import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, Clock, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Alert, AlertDescription } from "@/components/ui/alert";
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
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { isApiClientError } from "@/lib/api/error";
import {
  formatWatchSeconds,
  useGrantWatchTime,
  useStudentWatchTime,
  type VideoWatchTime,
} from "@/lib/api/watch-time";
import { formatDateTime } from "@/lib/format";
import { grantWatchTimeSchema, type GrantWatchTimeFormValues } from "@/lib/validation/watch-time";

/**
 * Student detail - Watch time (VIDEO Phase 12). Visible to roles with
 * `ACCESS_EXPIRY`/`VIEW`; "Add time" only for `canGrant` (Tenant Admin,
 * `ACCESS_EXPIRY`/`CREATE_EDIT`). Extra time is a new, audited grant with a
 * reason - time already watched is never reduced, and grants can't be edited
 * or removed. The backend enforces all of it regardless of this screen.
 */
export function WatchTimeTab({ studentId, canGrant }: { studentId: string; canGrant: boolean }) {
  const query = useStudentWatchTime(studentId);
  const [notice, setNotice] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [onlyBlocked, setOnlyBlocked] = useState(false);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [notice]);

  return (
    <QueryStateBoundary query={query} loadingLabel="Loading watch time…" loginPath="/login">
      {(videos) => (
        <div className="flex flex-col gap-4">
          <p className="text-sm text-muted-foreground">
            Time this student has watched each video, against the limit your institute set. Adding time never removes
            what was already watched.
          </p>
          <div role="status" aria-live="polite">
            {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
          </div>
          {videos.length === 0 ? (
            <p className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
              This student hasn&apos;t watched any videos yet. A video appears here once they start watching it.
            </p>
          ) : (
            <FilteredVideos
              videos={videos}
              search={search}
              onSearch={setSearch}
              onlyBlocked={onlyBlocked}
              onOnlyBlocked={setOnlyBlocked}
              render={(visible) =>
                visible.map((video) => (
                  <WatchTimeRow
                    key={video.videoAssetId}
                    studentId={studentId}
                    video={video}
                    canGrant={canGrant}
                    onGranted={setNotice}
                  />
                ))
              }
            />
          )}
        </div>
      )}
    </QueryStateBoundary>
  );
}

/**
 * Search by video name and "only used up" - so staff can find the one video a
 * student asked about. Filters this student's own list only (already
 * authorized by the backend).
 */
function FilteredVideos({
  videos,
  search,
  onSearch,
  onlyBlocked,
  onOnlyBlocked,
  render,
}: {
  videos: VideoWatchTime[];
  search: string;
  onSearch: (value: string) => void;
  onlyBlocked: boolean;
  onOnlyBlocked: (value: boolean) => void;
  render: (visible: VideoWatchTime[]) => React.ReactNode;
}) {
  const term = search.trim().toLowerCase();
  const visible = videos.filter(
    (video) =>
      (!onlyBlocked || video.exhausted) && (term === "" || (video.title ?? "").toLowerCase().includes(term))
  );
  const blockedCount = videos.filter((video) => video.exhausted).length;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <div className="relative sm:w-80">
          <Search aria-hidden="true" className="absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Label htmlFor="watch-time-search" className="sr-only">
            Search videos
          </Label>
          <Input
            id="watch-time-search"
            type="search"
            placeholder="Search videos by name"
            className="pl-8"
            value={search}
            onChange={(event) => onSearch(event.target.value)}
          />
        </div>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={onlyBlocked} onChange={(event) => onOnlyBlocked(event.target.checked)} />
          Only used up ({blockedCount})
        </label>
      </div>
      <p className="text-xs text-muted-foreground" aria-live="polite">
        Showing {visible.length} of {videos.length} {videos.length === 1 ? "video" : "videos"}
      </p>
      {visible.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted-foreground">
          <p>No videos match your search or filter.</p>
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => {
              onSearch("");
              onOnlyBlocked(false);
            }}
          >
            Clear filters
          </Button>
        </div>
      ) : (
        <ul className="flex flex-col gap-3" aria-label="Watch time by video">
          {render(visible)}
        </ul>
      )}
    </div>
  );
}

function WatchTimeRow({
  studentId,
  video,
  canGrant,
  onGranted,
}: {
  studentId: string;
  video: VideoWatchTime;
  canGrant: boolean;
  onGranted: (message: string) => void;
}) {
  const title = video.title ?? "Untitled video";
  return (
    <li className="flex flex-col gap-3 rounded-lg border border-border p-4 sm:flex-row sm:items-start sm:justify-between">
      <div className="flex min-w-0 flex-col gap-1">
        <p className="truncate font-medium text-foreground">{title}</p>
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 text-sm">
          <dt className="text-muted-foreground">Watched</dt>
          <dd>{formatWatchSeconds(video.usedSeconds)}</dd>
          <dt className="text-muted-foreground">Allowed</dt>
          <dd>{allowedLabel(video)}</dd>
          {video.remainingSeconds !== null ? (
            <>
              <dt className="text-muted-foreground">Remaining</dt>
              <dd className="flex items-center gap-2">
                {formatWatchSeconds(video.remainingSeconds)}
                {video.exhausted ? (
                  <span className="rounded-full border border-destructive/40 px-2 py-0.5 text-xs text-destructive">
                    Used up - playback blocked
                  </span>
                ) : null}
              </dd>
            </>
          ) : null}
          {video.lastWatchedAt ? (
            <>
              <dt className="text-muted-foreground">Last watched</dt>
              <dd>{formatDateTime(video.lastWatchedAt)}</dd>
            </>
          ) : null}
        </dl>
        {video.grants.length > 0 ? (
          <details className="text-sm">
            <summary className="cursor-pointer text-muted-foreground">
              Extra time added ({video.grants.length})
            </summary>
            <ul className="mt-1 flex flex-col gap-1 text-xs text-muted-foreground">
              {video.grants.map((grant) => (
                <li key={grant.id}>
                  {formatDateTime(grant.grantedAt)}: +{formatWatchSeconds(grant.extraSeconds)} ({grant.reason})
                </li>
              ))}
            </ul>
          </details>
        ) : null}
      </div>
      {canGrant && video.limit === "LIMITED" ? (
        <GrantWatchTimeDialog studentId={studentId} video={video} title={title} onSuccess={onGranted} />
      ) : null}
    </li>
  );
}

function allowedLabel(video: VideoWatchTime): string {
  if (video.limit === "NONE") return "No limit";
  if (video.limit === "UNKNOWN") return "Not known yet (video length still being read)";
  const allowed = formatWatchSeconds(video.allowedSeconds ?? 0);
  return video.grantedSeconds > 0
    ? `${allowed} (limit ${formatWatchSeconds(video.baseSeconds ?? 0)} + ${formatWatchSeconds(video.grantedSeconds)} added)`
    : allowed;
}

function GrantWatchTimeDialog({
  studentId,
  video,
  title,
  onSuccess,
}: {
  studentId: string;
  video: VideoWatchTime;
  title: string;
  onSuccess: (message: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const mutation = useGrantWatchTime(studentId);
  const idBase = `grant-${video.videoAssetId}`;
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<GrantWatchTimeFormValues>({
    resolver: zodResolver(grantWatchTimeSchema),
    defaultValues: { extraMinutes: "", reason: "" },
  });

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? (mutation.error.fieldErrors[0]?.message ?? mutation.error.message)
      : "Something went wrong. Please try again."
    : null;

  const onSubmit = handleSubmit((values) => {
    mutation.mutate(
      { videoAssetId: video.videoAssetId, extraMinutes: Number(values.extraMinutes), reason: values.reason },
      {
        onSuccess: (updated) => {
          reset();
          setOpen(false);
          onSuccess(
            `Added ${values.extraMinutes} min to "${title}". Remaining: ${formatWatchSeconds(updated.remainingSeconds ?? 0)}.`
          );
        },
      }
    );
  });

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (!next) {
          mutation.reset();
          reset();
        }
      }}
    >
      <AlertDialogTrigger
        render={<Button type="button" variant="outline" size="sm" aria-label={`Add watch time to ${title}`} />}
      >
        <Clock aria-hidden="true" />
        Add time
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Add watch time to {title}</AlertDialogTitle>
          <AlertDialogDescription>
            Watched {formatWatchSeconds(video.usedSeconds)} of {formatWatchSeconds(video.allowedSeconds ?? 0)}. The
            extra time is added to the limit; it can&apos;t be removed later, and it&apos;s recorded with your name and
            reason.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <form onSubmit={onSubmit} className="flex flex-col gap-3" noValidate>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idBase}-minutes`}>Extra time (minutes)</Label>
            <Input
              id={`${idBase}-minutes`}
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              aria-required="true"
              aria-invalid={errors.extraMinutes ? true : undefined}
              aria-describedby={errors.extraMinutes ? `${idBase}-minutes-error` : undefined}
              {...register("extraMinutes")}
            />
            {errors.extraMinutes ? (
              <p id={`${idBase}-minutes-error`} role="alert" className="text-xs text-destructive">
                {errors.extraMinutes.message}
              </p>
            ) : null}
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idBase}-reason`}>
              Reason<span className="font-normal text-muted-foreground"> (required)</span>
            </Label>
            <Textarea
              id={`${idBase}-reason`}
              aria-required="true"
              aria-invalid={errors.reason ? true : undefined}
              aria-describedby={errors.reason ? `${idBase}-reason-error` : undefined}
              placeholder="e.g. Revision before the final exam"
              {...register("reason")}
            />
            {errors.reason ? (
              <p id={`${idBase}-reason-error`} role="alert" className="text-xs text-destructive">
                {errors.reason.message}
              </p>
            ) : null}
          </div>
        </form>
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button
            type="button"
            onClick={() => void onSubmit()}
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? "Adding…" : "Add time"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
