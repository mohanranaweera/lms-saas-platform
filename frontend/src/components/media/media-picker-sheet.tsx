"use client";

import { useDeferredValue, useId, useMemo, useState } from "react";
import Link from "next/link";
import { useSetVideoWatchTimeLimit } from "@/lib/api/videos";
import { parseLimitMinutes } from "@/components/media/watch-time-limit";
import { Film, Info, Loader2, Plus, Search, Video } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import {
  useAttachLessonMedia,
  useMediaList,
  useMediaTabs,
  type LessonMedia,
  type MediaAsset,
} from "@/lib/api/media";
import { formatDuration } from "@/lib/media-format";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { MediaDialog } from "@/components/media/media-dialog";
import { MediaFileUploadDialog } from "@/components/media/media-file-dialogs";
import { MediaUploadPanel } from "@/components/media/media-upload-panel";
import {
  AddedDate,
  DYNTUBE_SETTINGS_HREF,
  useMediaUploadUx,
  videoSourceOptions,
  videoSourceParams,
  type VideoSourceKey,
} from "@/components/media/media-picker-shared";

const selectClass =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30";

/**
 * VIDEO Phase 6: choose an already-ready Media Library asset for one lesson.
 * The backend remains authoritative for tenant visibility and attachment
 * authorization; the picker only filters the response for a useful UX.
 * Like the Media Library it filters by source (Uploads / DynTube / each
 * YouTube integration, from the server's tab list) and can upload a new video
 * to the selected source; a new video becomes attachable once it is READY.
 */
export function MediaPickerSheet({
  open,
  onOpenChange,
  courseId,
  lessonId,
  attached,
  onAttached,
  onSelect,
  selectedIds,
  dashboardHref = "/teacher/dashboard",
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  courseId: string;
  lessonId: string;
  attached: LessonMedia[];
  onAttached?: (media: LessonMedia) => void;
  onSelect?: (asset: MediaAsset) => void;
  selectedIds?: ReadonlySet<string>;
  dashboardHref?: string;
}) {
  const searchId = useId();
  const sourceId = useId();
  const uploadHintId = useId();
  const [search, setSearch] = useState("");
  const deferredSearch = useDeferredValue(search);
  const [source, setSource] = useState<VideoSourceKey>("ALL");
  const [page, setPage] = useState(0);
  const [pagedFilters, setPagedFilters] = useState({ search: deferredSearch, source });
  if (pagedFilters.search !== deferredSearch || pagedFilters.source !== source) {
    setPagedFilters({ search: deferredSearch, source });
    setPage(0);
  }
  const tabsQuery = useMediaTabs(open);
  const sourceOptions = useMemo(() => videoSourceOptions(tabsQuery.data), [tabsQuery.data]);
  const selectedSource = sourceOptions.find((option) => option.value === source) ?? sourceOptions[0];
  const mediaQuery = useMediaList(
    { q: deferredSearch, status: "READY", page, size: 20, ...videoSourceParams(selectedSource.value) },
    open
  );
  const filtered = Boolean(search.trim() || selectedSource.value !== "ALL");
  const attachMutation = useAttachLessonMedia(courseId, lessonId);
  const limitMutation = useSetVideoWatchTimeLimit();
  const limitId = useId();
  const [limitMinutes, setLimitMinutes] = useState("");
  const parsedLimit = parseLimitMinutes(limitMinutes);
  const attachedIds = useMemo(() => new Set(attached.map((item) => item.mediaAssetId)), [attached]);

  // Uploading mirrors the Media Library tab of the selected source (UX only; the server re-checks).
  const { canUpload, settingsHref } = useMediaUploadUx();
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadBusy, setUploadBusy] = useState(false);
  const [uploadStatus, setUploadStatus] = useState("");
  const { tab, managed } = selectedSource;
  const uploadTarget: "internal" | "provider" | null = !canUpload
    ? null
    : selectedSource.value === "INTERNAL"
      ? "internal"
      : tab || managed?.canUpload
        ? "provider"
        : null;
  // VIDEO Phase 13: say why DynTube can't take an upload, as the library tab does.
  const uploadBlockedReason =
    managed && !uploadTarget ? (managed.uploadBlockedReason ?? "You don't have permission to upload videos.") : null;
  const uploadHint = canUpload && selectedSource.value === "ALL" ? "Choose a source to upload a new video to it." : null;

  function handleUploadOpenChange(next: boolean) {
    if (!next && uploadBusy) return;
    setUploadOpen(next);
  }

  function announceVideoReceived(title: string | null) {
    void mediaQuery.refetch();
    setUploadStatus(
      `${title ? `"${title}" was uploaded` : "Video uploaded"}. It becomes attachable here once processing finishes.`
    );
  }

  async function attach(asset: MediaAsset) {
    if (onSelect) {
      onSelect(asset);
      onOpenChange(false);
      return;
    }
    if (!parsedLimit.ok) return;
    limitMutation.reset();
    let lessonMedia: LessonMedia;
    try {
      lessonMedia = await attachMutation.mutateAsync({ mediaAssetId: asset.id });
    } catch {
      // The mutation error is rendered below; no optimistic authorization is attempted.
      return;
    }
    onAttached?.(lessonMedia);
    // VIDEO Phase 12: this video's own watch-time limit, set as it is added.
    if (parsedLimit.seconds !== null) {
      try {
        await limitMutation.mutateAsync({ videoAssetId: asset.id, limitSeconds: parsedLimit.seconds });
      } catch {
        // Attachment succeeded; the separate policy failure is shown below.
        return;
      }
    }
    setLimitMinutes("");
    onOpenChange(false);
  }

  return (
    <MediaDialog
      open={open}
      onOpenChange={(next) => {
        if (uploadBusy) return;
        if (!onSelect && (attachMutation.isPending || limitMutation.isPending)) return;
        onOpenChange(next);
      }}
      title="Choose session video"
      description={<>Select a ready video from the Media Library. Access and tenant permissions are checked by the server.{onSelect ? " It will be attached when you save the session." : ""}</>}
      className="sm:max-w-3xl"
      testId="media-video-picker"
      footer={null}
    >
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto">
          <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_14rem]">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={searchId}>Search ready videos</Label>
              <div className="relative">
                <Search aria-hidden="true" className="pointer-events-none absolute top-2.5 left-2.5 size-4 text-muted-foreground" />
                <Input
                  id={searchId}
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  placeholder="Search by title or filename"
                  className="pl-9"
                  data-testid="media-video-picker-search"
                />
              </div>
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={sourceId}>Source</Label>
              <select
                id={sourceId}
                value={selectedSource.value}
                className={selectClass}
                data-testid="media-video-picker-source"
                onChange={(event) => setSource(event.target.value as VideoSourceKey)}
              >
                {sourceOptions.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </div>
          </div>

          <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
            <p className="text-xs text-muted-foreground" data-testid="media-video-picker-ready-note">
              Only ready videos can be attached. Videos still uploading or processing appear here once they&apos;re ready.
            </p>
            {canUpload ? (
              <div className="flex flex-col items-start gap-1 sm:items-end">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={!uploadTarget}
                  aria-describedby={uploadHint ? uploadHintId : undefined}
                  onClick={() => setUploadOpen(true)}
                  data-testid="media-video-picker-new"
                >
                  <Plus aria-hidden="true" />
                  New media
                </Button>
                {uploadHint ? (
                  <p id={uploadHintId} className="text-xs text-muted-foreground" data-testid="media-video-picker-upload-hint">
                    {uploadHint}
                  </p>
                ) : null}
              </div>
            ) : null}
          </div>

          {uploadBlockedReason ? (
            <p
              role="note"
              className="flex items-start gap-2 rounded-md border border-border bg-muted/40 px-3 py-2 text-sm text-muted-foreground"
              data-testid="media-video-picker-upload-blocked"
            >
              <Info className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
              <span>
                <span className="font-medium text-foreground">Uploading is unavailable.</span> {uploadBlockedReason}{" "}
                {settingsHref ? (
                  <Link href={DYNTUBE_SETTINGS_HREF} className="font-medium underline">
                    Open integrations
                  </Link>
                ) : null}
              </span>
            </p>
          ) : null}

          <p
            role="status"
            aria-live="polite"
            className={uploadStatus ? "rounded-md border border-border bg-muted/40 p-3 text-sm" : "sr-only"}
            data-testid="media-video-picker-status"
          >
            {uploadStatus}
          </p>

          {uploadOpen && uploadTarget === "internal" ? (
            <MediaFileUploadDialog
              open
              onOpenChange={handleUploadOpenChange}
              onBusyChange={setUploadBusy}
              onVideoUploaded={() => announceVideoReceived(null)}
              onVideoLibrary={() => setUploadOpen(false)}
              onUploaded={(file) =>
                setUploadStatus(
                  `Uploaded "${file.title}" to the Media Library as ${file.kind === "IMAGE" ? "an image" : "a document"}. Attach it with Add from library.`
                )
              }
            />
          ) : null}
          {uploadOpen && uploadTarget === "provider" ? (
            <MediaDialog
              open
              onOpenChange={handleUploadOpenChange}
              title="New media"
              description={tab ? `Upload a video to ${tab.name} on YouTube.` : "Upload a video to your institute's DynTube account."}
              testId="media-provider-upload-dialog"
              className="sm:max-w-2xl"
              footer={null}
            >
              <MediaUploadPanel
                destination={tab ? { kind: "youtube", integrationId: tab.integrationId, name: tab.name } : { kind: "dyntube" }}
                // DynTube is set up under Settings → Integrations (only shown to those who manage settings).
                settingsHref={tab ? settingsHref : settingsHref ? DYNTUBE_SETTINGS_HREF : undefined}
                onBusyChange={setUploadBusy}
                onReceived={(asset) => announceVideoReceived(asset.title)}
                onClose={() => handleUploadOpenChange(false)}
              />
            </MediaDialog>
          ) : null}

          {!onSelect ? <div className="flex flex-col gap-1.5">
            <Label htmlFor={limitId}>
              Shared video watch-time limit (minutes)
              <span className="font-normal text-muted-foreground"> (optional)</span>
            </Label>
            <Input
              id={limitId}
              type="number"
              inputMode="numeric"
              min={1}
              step={1}
              className="w-40"
              placeholder="Keep current rule"
              value={limitMinutes}
              aria-invalid={parsedLimit.ok ? undefined : true}
              aria-describedby={`${limitId}-hint`}
              onChange={(event) => setLimitMinutes(event.target.value)}
            />
            <p id={`${limitId}-hint`} className={parsedLimit.ok ? "text-xs text-muted-foreground" : "text-xs text-destructive"} role={parsedLimit.ok ? undefined : "alert"}>
              {parsedLimit.ok
                ? "Leave empty to attach without changing the video's rules. Setting a limit changes this shared video for every student and requires separate policy-edit permission."
                : parsedLimit.message}
            </p>
          </div> : null}

          {limitMutation.isError ? (
            <div role="alert" className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
              The video was added, but its shared watch-time limit wasn&apos;t saved
              {isApiClientError(limitMutation.error) ? `: ${limitMutation.error.message}` : "."}{" "}
              Its existing playback rules remain unchanged. Attaching a video does not necessarily give you permission
              to change its rules.
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="mt-2"
                onClick={() => {
                  setLimitMinutes("");
                  limitMutation.reset();
                  onOpenChange(false);
                }}
              >
                Done
              </Button>
            </div>
          ) : null}

          {attachMutation.isError ? (
            <div role="alert" className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
              {isApiClientError(attachMutation.error)
                ? attachMutation.error.message
                : "Couldn’t attach this video. Please try again."}
            </div>
          ) : null}

          <QueryStateBoundary
            query={mediaQuery}
            loadingLabel="Loading ready videos…"
            loginPath="/login"
            permissionDenied={{ dashboardHref }}
            isEmpty={(data) => data.content.length === 0}
            emptyState={
              filtered
                ? {
                    title: "No ready videos match",
                    description: "Try a different title or source. Videos still processing appear once they're ready.",
                    action: {
                      label: "Clear filters",
                      onClick: () => {
                        setSearch("");
                        setSource("ALL");
                      },
                    },
                  }
                : {
                    title: "No ready videos yet",
                    description: canUpload
                      ? "Choose a source and use New media to upload a video, then wait for it to become ready before attaching it."
                      : "Upload a video to the Media Library and wait for it to become ready before attaching it.",
                  }
            }
          >
            {(data) => (
              <div className="flex flex-col gap-3">
              <p className="text-sm text-muted-foreground" aria-live="polite" data-testid="media-video-picker-count">
                {data.totalElements} ready {data.totalElements === 1 ? "video" : "videos"}
              </p>
              <ul aria-label="Ready media" className="flex flex-col gap-2">
                {data.content.map((asset) => {
                  const alreadyAttached = attachedIds.has(asset.id) || Boolean(selectedIds?.has(asset.id));
                  return (
                    <li
                      key={asset.id}
                      className="flex items-center gap-3 rounded-md border border-border p-3"
                      data-testid="media-video-picker-item"
                      data-media-asset-id={asset.id}
                    >
                      <div className="flex size-16 shrink-0 items-center justify-center overflow-hidden rounded bg-muted">
                        {asset.thumbnailUrl ? (
                          // eslint-disable-next-line @next/next/no-img-element -- provider thumbnail already authorized by Media Library
                          <img src={asset.thumbnailUrl} alt="" className="h-full w-full object-cover" />
                        ) : (
                          <Film aria-hidden="true" className="size-6 text-muted-foreground" />
                        )}
                      </div>
                      <div className="min-w-0 flex-1">
                        <p className="truncate font-medium text-foreground">{asset.title}</p>
                        <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                          <Badge variant="outline">{asset.provider}</Badge>
                          <Badge variant="outline">Ready</Badge>
                          {asset.durationSeconds !== null ? <span>{formatDuration(asset.durationSeconds)}</span> : null}
                          <AddedDate iso={asset.createdAt} />
                        </div>
                      </div>
                      <Button
                        type="button"
                        size="sm"
                        variant={alreadyAttached ? "outline" : "default"}
                        disabled={alreadyAttached || (!onSelect && (attachMutation.isPending || limitMutation.isPending || !parsedLimit.ok))}
                        aria-busy={!onSelect && attachMutation.isPending && attachMutation.variables?.mediaAssetId === asset.id}
                        onClick={() => void attach(asset)}
                      >
                        {attachMutation.isPending && attachMutation.variables?.mediaAssetId === asset.id ? (
                          <><Loader2 className="size-3.5 animate-spin" aria-hidden="true" /> Attaching…</>
                        ) : alreadyAttached ? (
                          "Attached"
                        ) : (
                          <><Video aria-hidden="true" /> {onSelect ? "Select" : "Add"}</>
                        )}
                      </Button>
                    </li>
                  );
                })}
              </ul>
              {data.totalPages > 1 ? (
                <nav aria-label="Ready video pages" className="flex items-center justify-between gap-2">
                  <Button type="button" variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((value) => Math.max(0, value - 1))}>
                    Previous
                  </Button>
                  <span className="text-xs text-muted-foreground">Page {data.page + 1} of {data.totalPages}</span>
                  <Button type="button" variant="outline" size="sm" disabled={page + 1 >= data.totalPages} onClick={() => setPage((value) => value + 1)}>
                    Next
                  </Button>
                </nav>
              ) : null}
              </div>
            )}
          </QueryStateBoundary>
        </div>
    </MediaDialog>
  );
}
