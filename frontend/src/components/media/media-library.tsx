"use client";

import { useDeferredValue, useId, useState } from "react";
import Link from "next/link";
import { ExternalLink, Film, Link2, UploadCloud } from "lucide-react";
import { useMediaList, type MediaAsset, type MediaProcessingStatus } from "@/lib/api/media";
import { formatDateTime } from "@/lib/format";
import { formatDuration, percent } from "@/lib/media-format";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { MediaStatusBadge } from "./media-status-badge";
import { MediaUploadPanel } from "./media-upload-panel";

const PAGE_SIZE = 24;

const STATUS_OPTIONS: { value: "" | MediaProcessingStatus; label: string }[] = [
  { value: "", label: "All statuses" },
  { value: "READY", label: "Ready" },
  { value: "PROCESSING", label: "Processing" },
  { value: "UPLOADING", label: "Uploading" },
  { value: "CREATED", label: "Waiting for the file" },
  { value: "FAILED", label: "Failed" },
];

const selectClass =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30";

const cardLinkClass =
  "group flex flex-1 flex-col focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50";

/**
 * VIDEO Phase 5c: a video YouTube already has opens its YouTube page in a new
 * tab; "Details" (edit, delete, upload status) stays one click away. Anything
 * not on YouTube yet opens the details page as before.
 */
function MediaCard({ asset, href }: { asset: MediaAsset; href: string }) {
  if (!asset.youtubeUrl) {
    return (
      <li className="flex flex-col overflow-hidden rounded-lg border border-border">
        <Link href={href} className={cardLinkClass}>
          <MediaCardBody asset={asset} />
        </Link>
      </li>
    );
  }
  return (
    <li className="flex flex-col overflow-hidden rounded-lg border border-border">
      <a
        href={asset.youtubeUrl}
        target="_blank"
        rel="noopener noreferrer"
        aria-label={`${asset.title} - open on YouTube (new tab)`}
        className={cardLinkClass}
      >
        <MediaCardBody asset={asset} />
      </a>
      <div className="flex items-center justify-between gap-2 border-t border-border px-3 py-2 text-sm">
        <span className="inline-flex items-center gap-1 text-muted-foreground">
          <ExternalLink aria-hidden="true" className="size-3.5" />
          Opens on YouTube
        </span>
        <Link href={href} className="font-medium text-foreground hover:underline">
          Details<span className="sr-only"> for {asset.title}</span>
        </Link>
      </div>
    </li>
  );
}

function MediaCardBody({ asset }: { asset: MediaAsset }) {
  const progress = asset.uploadProgress;
  const transferring = asset.processingStatus === "UPLOADING" && progress && progress.totalBytes > 0;
  const duration = formatDuration(asset.durationSeconds);
  return (
    <>
        <div className="relative flex aspect-video items-center justify-center bg-muted">
          {asset.thumbnailUrl ? (
            // eslint-disable-next-line @next/next/no-img-element -- provider thumbnail on a provider CDN
            <img src={asset.thumbnailUrl} alt="" className="h-full w-full object-cover" loading="lazy" />
          ) : (
            <Film aria-hidden="true" className="size-8 text-muted-foreground" />
          )}
          {duration ? (
            <span className="absolute right-2 bottom-2 rounded bg-background/90 px-1.5 py-0.5 text-xs text-foreground">
              {duration}
            </span>
          ) : null}
        </div>
        <div className="flex flex-1 flex-col gap-2 p-3">
          <span className="line-clamp-2 font-medium text-foreground group-hover:underline">{asset.title}</span>
          <div className="flex flex-wrap gap-1.5">
            <MediaStatusBadge asset={asset} />
            {asset.attached ? (
              <Badge variant="outline">
                <Link2 aria-hidden="true" />
                In a lesson
              </Badge>
            ) : null}
            {asset.privacyStatus ? (
              <Badge variant="outline">{asset.privacyStatus === "PRIVATE" ? "Private" : "Unlisted"}</Badge>
            ) : null}
          </div>
          {transferring ? (
            <div
              role="progressbar"
              aria-label={`Sending ${asset.title} to YouTube`}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={percent(progress.bytesSent, progress.totalBytes)}
              className="h-1.5 w-full overflow-hidden rounded-full bg-muted"
            >
              <div
                className="h-full bg-primary"
                style={{ width: `${percent(progress.bytesSent, progress.totalBytes)}%` }}
              />
            </div>
          ) : null}
          <span className="mt-auto text-xs text-muted-foreground">Added {formatDateTime(asset.createdAt)}</span>
        </div>
    </>
  );
}

/**
 * The Media Library list shared by the Tenant Admin and Teacher pages (VIDEO
 * Phase 5). What the list contains is decided by the server: a Teacher gets
 * their own uploads, staff with `MATERIALS` the whole institute's.
 */
export function MediaLibrary({
  basePath,
  dashboardHref,
  canUpload,
  settingsHref,
  scopeNote,
}: {
  /** `/tenant-admin/media` or `/teacher/media`. */
  basePath: string;
  dashboardHref: string;
  canUpload: boolean;
  settingsHref?: string;
  scopeNote: string;
}) {
  const ids = { search: useId(), status: useId() };
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState<"" | MediaProcessingStatus>("");
  const [page, setPage] = useState(0);
  const [showUpload, setShowUpload] = useState(false);
  const deferredSearch = useDeferredValue(search);
  const query = useMediaList({ q: deferredSearch, status: status || undefined, page, size: PAGE_SIZE });
  const filtered = Boolean(deferredSearch.trim() || status);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Media Library</h1>
          <p className="text-sm text-muted-foreground">{scopeNote}</p>
        </div>
        {canUpload && !showUpload ? (
          <Button type="button" onClick={() => setShowUpload(true)}>
            <UploadCloud aria-hidden="true" />
            Upload video
          </Button>
        ) : null}
      </div>

      {canUpload && showUpload ? (
        <div className="max-w-2xl">
          <MediaUploadPanel settingsHref={settingsHref} onClose={() => setShowUpload(false)} />
        </div>
      ) : null}

      <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_14rem]">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.search}>Search</Label>
          <Input
            id={ids.search}
            type="search"
            placeholder="Title or file name"
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.status}>Status</Label>
          <select
            id={ids.status}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as "" | MediaProcessingStatus);
              setPage(0);
            }}
            className={selectClass}
          >
            {STATUS_OPTIONS.map((option) => (
              <option key={option.value || "all"} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading media…"
        loginPath="/login"
        permissionDenied={{ dashboardHref }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={
          filtered
            ? {
                title: "No media matches",
                description: "Try another search or status.",
                action: {
                  label: "Clear filters",
                  onClick: () => {
                    setSearch("");
                    setStatus("");
                    setPage(0);
                  },
                },
              }
            : {
                title: "No videos yet",
                description: canUpload
                  ? "Upload a lesson video to add it to the library."
                  : "Videos uploaded by your institute will appear here.",
                action: canUpload ? { label: "Upload video", onClick: () => setShowUpload(true) } : undefined,
              }
        }
      >
        {(data) => (
          <div className="flex flex-col gap-4">
            <ul aria-label="Media" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {data.content.map((asset) => (
                <MediaCard key={asset.id} asset={asset} href={`${basePath}/${asset.id}`} />
              ))}
            </ul>
            {data.totalPages > 1 ? (
              <nav aria-label="Pages" className="flex items-center justify-between gap-2">
                <Button type="button" variant="outline" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                  Previous
                </Button>
                <span className="text-sm text-muted-foreground">
                  Page {data.page + 1} of {data.totalPages}
                </span>
                <Button
                  type="button"
                  variant="outline"
                  disabled={page + 1 >= data.totalPages}
                  onClick={() => setPage((p) => p + 1)}
                >
                  Next
                </Button>
              </nav>
            ) : null}
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
