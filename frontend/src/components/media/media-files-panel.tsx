"use client";

import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";
import { Plus, Search } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useMediaFiles, type MediaFileKind } from "@/lib/api/media-files";
import { MediaFileCard } from "./media-file-card";
import { MediaFileUploadDialog } from "./media-file-dialogs";

const PAGE_SIZE = 24;
/** Don't refetch for fresh thumbnail URLs more than once a minute (a broken image would otherwise loop). */
const THUMBNAIL_REFETCH_MIN_MS = 60_000;

export type UploadsTypeFilter = "ALL" | "DOCUMENT" | "IMAGE" | "VIDEO";

const TYPE_OPTIONS: { value: UploadsTypeFilter; label: string }[] = [
  { value: "ALL", label: "All types" },
  { value: "DOCUMENT", label: "Documents" },
  { value: "IMAGE", label: "Images" },
  { value: "VIDEO", label: "Videos" },
];

const selectClass =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30";

function useDebounced<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

/**
 * The Uploads tab searches files and internal videos together. All types
 * keeps each server-paginated source in its own labeled section.
 */
export function MediaFilesPanel({
  dashboardHref,
  canUpload,
  videos,
  onBusyChange,
}: {
  dashboardHref: string;
  canUpload: boolean;
  videos: (filters: { search: string; onClearSearch: () => void }) => ReactNode;
  onBusyChange: (busy: boolean) => void;
}) {
  const ids = { search: useId(), type: useId() };
  const [type, setType] = useState<UploadsTypeFilter>("ALL");
  const [searchInput, setSearchInput] = useState("");
  const search = useDebounced(searchInput, 350);
  const [page, setPage] = useState(0);
  const [pagedFilters, setPagedFilters] = useState({ type, search });
  const [uploadOpen, setUploadOpen] = useState(false);

  // Any filter change resets to the first page (derived-state pattern, no effect).
  if (pagedFilters.type !== type || pagedFilters.search !== search) {
    setPagedFilters({ type, search });
    setPage(0);
  }

  const showingVideos = type === "VIDEO";
  const kind: MediaFileKind | undefined = type === "DOCUMENT" || type === "IMAGE" ? type : undefined;
  const query = useMediaFiles({ q: search, kind, page, size: PAGE_SIZE }, !showingVideos);
  const filtered = Boolean(search.trim() || kind);

  // Deleting the last file on a later page leaves it empty: step back a page.
  if (!showingVideos && query.data && query.data.content.length === 0 && page > 0 && query.data.page === page) {
    setPage(Math.max(0, Math.min(page - 1, query.data.totalPages - 1)));
  }

  const lastThumbnailRefetch = useRef(0);
  const { refetch } = query;
  const handleThumbnailExpired = useCallback(() => {
    const now = Date.now();
    if (now - lastThumbnailRefetch.current < THUMBNAIL_REFETCH_MIN_MS) return;
    lastThumbnailRefetch.current = now;
    void refetch();
  }, [refetch]);

  const clearFilters = () => {
    setSearchInput("");
    setType("ALL");
  };

  return (
    <div className="flex flex-col gap-4" data-testid="media-uploads-panel">
      <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
          <div className="flex flex-col gap-1.5 sm:w-72">
            <Label htmlFor={ids.search}>Search media</Label>
            <div className="relative">
              <Search
                aria-hidden="true"
                className="pointer-events-none absolute top-2 left-2.5 size-4 text-muted-foreground"
              />
              <Input
                id={ids.search}
                type="search"
                placeholder="Title or file name"
                className="pl-8"
                maxLength={200}
                value={searchInput}
                data-testid="media-files-search"
                onChange={(event) => setSearchInput(event.target.value)}
              />
            </div>
          </div>
        <div className="flex flex-col gap-1.5 sm:w-44">
          <Label htmlFor={ids.type}>Type</Label>
          <select
            id={ids.type}
            value={type}
            className={selectClass}
            data-testid="media-files-type"
            onChange={(event) => setType(event.target.value as UploadsTypeFilter)}
          >
            {TYPE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
        {!showingVideos && query.data ? (
          <Badge variant="secondary" className="h-8 px-3 sm:mb-0" data-testid="media-files-count">
            <span aria-live="polite">
              {query.data.totalElements} {query.data.totalElements === 1 ? "file" : "files"}
            </span>
          </Badge>
        ) : null}
        {canUpload ? (
          <Button
            type="button"
            className="sm:ml-auto"
            onClick={() => setUploadOpen(true)}
            data-testid="media-files-new"
          >
            <Plus aria-hidden="true" />
            New media
          </Button>
        ) : null}
      </div>

      {canUpload && uploadOpen ? (
        <MediaFileUploadDialog
          open
          onOpenChange={setUploadOpen}
          onVideoLibrary={() => { setUploadOpen(false); setType("VIDEO"); }}
          onBusyChange={onBusyChange}
        />
      ) : null}

      {!showingVideos ? (
        <section aria-label="Documents and images" className="flex flex-col gap-3">
        {type === "ALL" ? <h2 className="font-medium">Documents and images</h2> : null}
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading media files…"
          loginPath="/login"
          permissionDenied={{ dashboardHref }}
          isEmpty={(data) => data.content.length === 0}
          emptyState={
            filtered
              ? {
                  title: "No files match",
                  description: "Try another search or type.",
                  action: { label: "Clear filters", onClick: clearFilters },
                }
              : {
                  title: "No files yet",
                  description: "Upload documents or images your sessions can use.",
                  action: canUpload ? { label: "New media", onClick: () => setUploadOpen(true) } : undefined,
                }
          }
        >
          {(data) => (
            <div className="flex flex-col gap-4">
              <ul
                aria-label="Media files"
                className="grid grid-cols-1 gap-4 sm:grid-cols-2 md:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5"
                data-testid="media-files-grid"
              >
                {data.content.map((file) => (
                  <MediaFileCard key={file.id} file={file} onThumbnailExpired={handleThumbnailExpired} />
                ))}
              </ul>
              {data.totalPages > 1 ? (
                <nav aria-label="Media file pages" className="flex items-center justify-between gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    disabled={page === 0}
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                  >
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
        </section>
      ) : null}
      {type === "ALL" || showingVideos ? (
        <section key={type} aria-label="Internal videos" className="flex flex-col gap-3" data-testid="media-uploads-videos">
          <h2 className="font-medium">Internal videos</h2>
          {videos({ search, onClearSearch: () => setSearchInput("") })}
        </section>
      ) : null}
    </div>
  );
}
