"use client";

import { useDeferredValue, useId, useState } from "react";
import Link from "next/link";
import { ExternalLink, Eye, Film, Info, Link2, Plus } from "lucide-react";
import {
  useMediaList,
  useMediaTabs,
  type ManagedHostTab,
  type MediaAsset,
  type MediaProcessingStatus,
  type MediaTab,
} from "@/lib/api/media";
import { formatDateTime } from "@/lib/format";
import { formatDuration, percent } from "@/lib/media-format";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tabs } from "@/components/ui/tabs";
import { MediaStatusBadge } from "./media-status-badge";
import { MediaUploadPanel } from "./media-upload-panel";
import { MediaFilesPanel } from "./media-files-panel";
import { MediaLibrarySummary } from "./media-library-summary";
import { MediaDialog } from "./media-dialog";

const PAGE_SIZE = 24;

/** VIDEO Phase 13: where a Tenant Admin sets up the DynTube account. */
const DYNTUBE_SETTINGS_HREF = "/tenant-admin/settings/integrations";

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
            {asset.canEdit === false && asset.canDelete === false ? (
              <Badge variant="outline" data-testid="media-video-view-only">
                <Eye aria-hidden="true" />
                View only
              </Badge>
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

type TabKey = "uploads" | "archived" | "dyntube" | `integration:${string}`;

/**
 * The Media Library shared by the Tenant Admin and Teacher pages (VIDEO
 * Phases 5 / 5d, Klass gaps round 2 G1). Klass layout: a summary card, then
 * "Uploads" first (documents/images and internal videos, independently
 * paginated under All types), then one tab per video
 * host (DynTube, each YouTube integration) and, when any exist, "Archived".
 * Which tabs, files and videos appear - and which may be renamed or deleted -
 * is decided by the server (R2-D1/R2-D2: the library is institute-wide;
 * teachers change only their own uploads).
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
  const tabsQuery = useMediaTabs();
  const [selected, setSelected] = useState<TabKey | null>(null);
  const [uploadBusy, setUploadBusy] = useState(false);

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Media Library</h1>
        <p className="text-sm text-muted-foreground">{scopeNote}</p>
      </div>
      <QueryStateBoundary
        query={tabsQuery}
        loadingLabel="Loading media…"
        loginPath="/login"
        permissionDenied={{ dashboardHref }}
      >
        {(tabs) => {
          const managed = tabs.managedHost;
          const active: TabKey = selected ?? "uploads";
          const panel = (key: TabKey) =>
            key === "uploads" ? (
              active === "uploads" ? (
                <MediaFilesPanel
                  dashboardHref={dashboardHref}
                  canUpload={canUpload}
                  onBusyChange={setUploadBusy}
                  videos={({ search, onClearSearch }) => (
                    <MediaTabPanel
                      uploadsSearch={search}
                      onClearUploadsSearch={onClearSearch}
                      tabKey="uploads"
                      tab={null}
                      managed={null}
                      basePath={basePath}
                      dashboardHref={dashboardHref}
                      canUpload={canUpload}
                      settingsHref={settingsHref}
                    />
                  )}
                />
              ) : null
            ) : key === active ? (
              <MediaTabPanel
                key={key}
                tabKey={key}
                onBusyChange={setUploadBusy}
                tab={tabs.integrations.find((t) => `integration:${t.integrationId}` === key) ?? null}
                managed={key === "dyntube" ? managed : null}
                basePath={basePath}
                dashboardHref={dashboardHref}
                canUpload={canUpload}
                settingsHref={settingsHref}
              />
            ) : null;
          const items = [
            { value: "uploads" as TabKey, label: "Uploads" },
            ...(managed
              ? [
                  {
                    value: "dyntube" as TabKey,
                    label: managed.name,
                    badge: !managed.connected ? (
                      <Badge variant="outline" className="text-xs">
                        Not connected
                      </Badge>
                    ) : undefined,
                  },
                ]
              : []),
            ...tabs.integrations.map((tab) => ({
              value: `integration:${tab.integrationId}` as TabKey,
              label: tab.name,
              badge: !tab.connected ? (
                <Badge variant="outline" className="text-xs">
                  Not connected
                </Badge>
              ) : undefined,
            })),
            ...(tabs.hasArchived ? [{ value: "archived" as TabKey, label: "Archived" }] : []),
          ].map((item) => ({ ...item, content: panel(item.value) }));
          return (
            <div className="flex flex-col gap-4">
              <MediaLibrarySummary />
              {uploadBusy ? <p role="status" className="text-sm text-muted-foreground">Upload in progress. Finish or cancel it before switching tabs.</p> : null}
              {tabs.integrations.length === 0 && !managed && settingsHref ? (
                <p className="text-sm text-muted-foreground">
                  No YouTube integration yet.{" "}
                  <Link href={settingsHref} className="font-medium underline">
                    Add one in Video providers
                  </Link>{" "}
                  for YouTube uploads. Internal storage uploads are available in Uploads.
                </p>
              ) : null}
              <Tabs
                aria-label="Media sources"
                items={items}
                value={active}
                onValueChange={(value) => { if (!uploadBusy) setSelected(value as TabKey); }}
              />
            </div>
          );
        }}
      </QueryStateBoundary>
    </div>
  );
}

/**
 * One tab: its videos with search / status filters, and uploading to it when
 * it is a YouTube integration or (VIDEO Phase 13) the institute's DynTube
 * account.
 */
function MediaTabPanel({
  tabKey,
  tab,
  managed,
  onBusyChange,
  basePath,
  dashboardHref,
  canUpload,
  settingsHref,
  uploadsSearch,
  onClearUploadsSearch,
}: {
  uploadsSearch?: string;
  onClearUploadsSearch?: () => void;
  tabKey: TabKey;
  onBusyChange?: (busy: boolean) => void;
  tab: MediaTab | null;
  managed: ManagedHostTab | null;
  basePath: string;
  dashboardHref: string;
  canUpload: boolean;
  settingsHref?: string;
}) {
  const ids = { search: useId(), status: useId() };
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState<"" | MediaProcessingStatus>("");
  const [page, setPage] = useState(0);
  const [showUpload, setShowUpload] = useState(false);
  const [uploadBusy, setUploadBusy] = useState(false);
  const deferredSearch = useDeferredValue(uploadsSearch ?? search);
  const [pagedFilters, setPagedFilters] = useState({ search: deferredSearch, status });
  if (pagedFilters.search !== deferredSearch || pagedFilters.status !== status) {
    setPagedFilters({ search: deferredSearch, status });
    setPage(0);
  }
  const query = useMediaList({
    q: deferredSearch,
    status: status || undefined,
    page,
    size: PAGE_SIZE,
    integrationId: tab?.integrationId,
    provider: tabKey === "uploads" ? "INTERNAL" : tabKey === "dyntube" ? "DYNTUBE" : undefined,
    archived: tabKey === "archived",
  });
  if (query.data && query.data.page === page && page > 0 && query.data.content.length === 0) {
    setPage(Math.max(0, Math.min(page - 1, query.data.totalPages - 1)));
  }
  const handleUploadOpenChange = (open: boolean) => {
    if (!open && uploadBusy) return;
    setShowUpload(open);
  };
  const handleBusyChange = (busy: boolean) => {
    setUploadBusy(busy);
    onBusyChange?.(busy);
  };
  const filtered = Boolean(deferredSearch.trim() || status);
  const uploadHere = canUpload && (tab !== null || Boolean(managed?.canUpload));
  // VIDEO Phase 13: the DynTube tab says why there's no Upload button instead of hiding it silently.
  const uploadBlockedReason =
    managed && !uploadHere
      ? (managed.uploadBlockedReason ?? "You don't have permission to upload videos.")
      : null;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <p className="text-sm text-muted-foreground">
          {tab
            ? `Videos on the "${tab.name}" YouTube channel.`
            : managed
              ? "Videos hosted on your institute's DynTube account. Students play them through short-lived signed links."
              : tabKey === "uploads"
              ? "Videos stored in your institute's own storage on this platform. Use New media to upload a video to tenant internal storage (up to 25 MiB)."
              : "Videos from YouTube integrations that were removed. They stay on YouTube and in the lessons that use them."}
        </p>
        {uploadHere ? (
          <Button type="button" onClick={() => setShowUpload(true)}>
            <Plus aria-hidden="true" />
            New media
          </Button>
        ) : null}
        {uploadBlockedReason ? (
          <p
            role="note"
            className="flex max-w-md items-start gap-2 rounded-md border border-border bg-muted/40 px-3 py-2 text-sm text-muted-foreground"
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
      </div>

      {uploadHere && showUpload ? (
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
            destination={
              tab ? { kind: "youtube", integrationId: tab.integrationId, name: tab.name } : { kind: "dyntube" }
            }
            // DynTube is set up under Settings → Integrations (only shown to those who manage settings).
            settingsHref={tab ? settingsHref : settingsHref ? DYNTUBE_SETTINGS_HREF : undefined}
            onBusyChange={handleBusyChange}
            onClose={() => handleUploadOpenChange(false)}
          />
        </MediaDialog>
      ) : null}

      <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_14rem]">
        {uploadsSearch === undefined ? <div className="flex flex-col gap-1.5">
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
        </div> : null}
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={ids.status}>{tabKey === "uploads" ? "Video status" : "Status"}</Label>
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
                    onClearUploadsSearch?.();
                    setStatus("");
                    setPage(0);
                  },
                },
              }
            : managed
              ? {
                  title: "No DynTube videos yet",
                  description: uploadHere
                    ? "Upload a lesson video to send it to DynTube."
                    : "Videos uploaded to DynTube will appear here.",
                  action: uploadHere ? { label: "Upload video", onClick: () => setShowUpload(true) } : undefined,
                }
              : tab
              ? {
                  title: "No videos on this channel yet",
                  description: uploadHere
                    ? "Upload a lesson video to add it to this channel."
                    : "Videos uploaded to this channel will appear here.",
                  action: uploadHere ? { label: "Upload video", onClick: () => setShowUpload(true) } : undefined,
                }
              : tabKey === "uploads"
                ? {
                    title: "No stored videos",
                    description: "Videos kept in your institute's own storage will appear here.",
                  }
                : {
                    title: "No archived videos",
                    description: "Videos of removed YouTube integrations will appear here.",
                  }
        }
      >
        {(data) => (
          <div className="flex flex-col gap-4">
            <p className="text-sm text-muted-foreground" aria-live="polite">
              {data.totalElements} {data.totalElements === 1 ? "video" : "videos"}
            </p>
            <ul aria-label="Media" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
              {data.content.map((asset) => (
                <MediaCard key={asset.id} asset={asset} href={`${basePath}/${asset.id}`} />
              ))}
            </ul>
            {data.totalPages > 1 ? (
              <nav aria-label="Video pages" className="flex items-center justify-between gap-2">
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
