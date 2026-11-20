"use client";

import { useCallback, useEffect, useId, useRef, useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { FolderOpen, FolderPlus, FolderX, Plus, Search } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Badge } from "@/components/ui/badge";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { isApiClientError } from "@/lib/api/error";
import { useMediaFileUploaders, useMediaFiles, type MediaFileKind } from "@/lib/api/media-files";
import { useMediaFolder, useMediaFolders, type MediaFolderDetail } from "@/lib/api/media-folders";
import { useCapabilities } from "@/lib/api/my-permissions";
import { CAP } from "@/lib/auth/capabilities";
import { MediaFileCard } from "./media-file-card";
import { MediaFileUploadDialog } from "./media-file-dialogs";
import { MediaFolderCard } from "./media-folder-card";
import { MediaFolderNameDialog } from "./media-folder-dialogs";

const PAGE_SIZE = 24;
/** Don't refetch for fresh thumbnail URLs more than once a minute (a broken image would otherwise loop). */
const THUMBNAIL_REFETCH_MIN_MS = 60_000;
/** URL search param holding the open folder (MEDIA-FOLDERS-01 §8). */
export const FOLDER_PARAM = "folder";

export type UploadsTypeFilter = "ALL" | "DOCUMENT" | "IMAGE" | "VIDEO";

const TYPE_OPTIONS: { value: UploadsTypeFilter; label: string }[] = [
  { value: "ALL", label: "All types" },
  { value: "DOCUMENT", label: "Documents" },
  { value: "IMAGE", label: "Images" },
  { value: "VIDEO", label: "Videos" },
];

const selectClass =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50 dark:bg-input/30";

const gridClass = "grid grid-cols-1 gap-4 sm:grid-cols-2 md:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5";

function useDebounced<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

/**
 * The Uploads tab: folders, then files, with search / type / uploader
 * filters. The open folder lives in the URL (`?folder=<id>`, pushed so
 * Back/Forward and refresh work). At the root it shows the folders the caller
 * can see, then Unfiled files and the Internal videos section; inside a
 * folder, a breadcrumb, the child folders and that folder's files. Internal
 * videos are never inside folders. Every visibility and action rule is the
 * server's; the flags read here only decide which buttons appear.
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
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const folderId = searchParams.get(FOLDER_PARAM) || null;
  const folderHref = useCallback(
    (id: string | null) => {
      const next = new URLSearchParams(searchParams.toString());
      if (id) next.set(FOLDER_PARAM, id);
      else next.delete(FOLDER_PARAM);
      const query = next.toString();
      return query ? `${pathname}?${query}` : pathname;
    },
    [pathname, searchParams]
  );
  const openFolder = useCallback((id: string | null) => router.push(folderHref(id), { scroll: false }), [router, folderHref]);

  const ids = { search: useId(), type: useId(), uploader: useId(), uploaderHint: useId() };
  const headingRef = useRef<HTMLHeadingElement>(null);
  const [type, setType] = useState<UploadsTypeFilter>("ALL");
  // The uploader choice belongs to one scope (folder or Unfiled): it lapses when the folder changes.
  const [uploaderFilter, setUploaderFilter] = useState({ folderId, value: "" });
  const uploadedBy = uploaderFilter.folderId === folderId ? uploaderFilter.value : "";
  const setUploadedBy = (value: string) => setUploaderFilter({ folderId, value });
  const [searchInput, setSearchInput] = useState("");
  const search = useDebounced(searchInput, 350);
  const [page, setPage] = useState(0);
  const [pagedFilters, setPagedFilters] = useState({ type, search, uploadedBy, folderId });
  const [uploadOpen, setUploadOpen] = useState(false);
  const [newFolderOpen, setNewFolderOpen] = useState(false);

  // Internal videos never live in folders, so "Videos" is a root-only type.
  const effectiveType: UploadsTypeFilter = folderId && type === "VIDEO" ? "ALL" : type;

  // Any filter or folder change resets to the first page (derived-state pattern, no effect).
  if (
    pagedFilters.type !== effectiveType ||
    pagedFilters.search !== search ||
    pagedFilters.uploadedBy !== uploadedBy ||
    pagedFilters.folderId !== folderId
  ) {
    setPagedFilters({ type: effectiveType, search, uploadedBy, folderId });
    setPage(0);
  }

  const { has } = useCapabilities({ fetchForAllRoles: true });
  const folderQuery = useMediaFolder(folderId);
  const folder: MediaFolderDetail | undefined = folderId ? folderQuery.data : undefined;
  const folderMissing =
    Boolean(folderId) &&
    folderQuery.status === "error" &&
    isApiClientError(folderQuery.error) &&
    (folderQuery.error.status === 404 || folderQuery.error.status === 400);
  // Inside a folder, nothing else loads until the server confirms the folder is visible.
  const scopeReady = !folderId || folderQuery.status === "success";

  const showingVideos = effectiveType === "VIDEO";
  const kind: MediaFileKind | undefined =
    effectiveType === "DOCUMENT" || effectiveType === "IMAGE" ? effectiveType : undefined;
  const query = useMediaFiles(
    {
      q: search,
      kind,
      page,
      size: PAGE_SIZE,
      folderId: folderId ?? undefined,
      unfiled: folderId ? undefined : true,
      uploadedBy: uploadedBy || undefined,
    },
    scopeReady && !showingVideos
  );
  const foldersQuery = useMediaFolders(folderId, scopeReady);
  const uploaders = useMediaFileUploaders(folderId ? { folderId } : { unfiled: true }, scopeReady && !showingVideos);
  const filtered = Boolean(search.trim() || kind || uploadedBy);

  const canUploadHere = folderId ? Boolean(folder?.canUpload) : canUpload;
  const canCreateFolderHere = folderId ? Boolean(folder?.canManage) : has(CAP.MEDIA_MANAGE_FOLDERS);

  // Deleting the last file on a later page leaves it empty: step back a page.
  if (!showingVideos && query.data && query.data.content.length === 0 && page > 0 && query.data.page === page) {
    setPage(Math.max(0, Math.min(page - 1, query.data.totalPages - 1)));
  }

  // Moving between folders moves focus to the new heading (not on first render / direct link).
  // The heading only mounts once the folder has loaded, so the focus move waits for it.
  const previousFolder = useRef(folderId);
  const focusPending = useRef(false);
  useEffect(() => {
    if (previousFolder.current !== folderId) {
      previousFolder.current = folderId;
      focusPending.current = true;
    }
    if (focusPending.current && scopeReady && headingRef.current) {
      focusPending.current = false;
      headingRef.current.focus();
    }
  }, [folderId, scopeReady]);

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
    setUploadedBy("");
  };

  if (folderMissing) {
    return (
      <div
        role="status"
        className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-12 text-center"
        data-testid="media-folder-not-found"
      >
        <FolderX aria-hidden="true" className="size-8 text-muted-foreground" />
        <div className="space-y-1">
          <p className="text-sm font-medium text-foreground">Folder not found</p>
          <p className="text-sm text-muted-foreground">
            This folder doesn&apos;t exist, was deleted, or hasn&apos;t been shared with you.
          </p>
        </div>
        <Link href={folderHref(null)} className={buttonVariants({ size: "sm" })}>
          Back to Uploads
        </Link>
      </div>
    );
  }

  if (folderId && folderQuery.status !== "success") {
    return (
      <QueryStateBoundary
        query={folderQuery}
        loadingLabel="Loading folder…"
        loginPath="/login"
        permissionDenied={{ dashboardHref }}
      >
        {() => null}
      </QueryStateBoundary>
    );
  }

  const folderCount = foldersQuery.data?.length ?? 0;
  const folderEmpty =
    Boolean(folder) &&
    !filtered &&
    foldersQuery.status === "success" &&
    folderCount === 0 &&
    query.status === "success" &&
    query.data.totalElements === 0;

  return (
    <div className="flex min-w-0 flex-col gap-4" data-testid="media-uploads-panel">
      {folder ? (
        <div className="flex min-w-0 flex-col gap-1.5" data-testid="media-folder-header">
          <div data-testid="media-folder-breadcrumb" className="min-w-0 break-all">
            <Breadcrumbs
              items={[
                { label: "Uploads", href: folderHref(null) },
                ...folder.breadcrumb.map((crumb) => ({ label: crumb.name, href: folderHref(crumb.id) })),
                { label: folder.name },
              ]}
            />
          </div>
          <h2
            ref={headingRef}
            tabIndex={-1}
            className="flex min-w-0 items-center gap-2 text-lg font-semibold break-words text-foreground outline-none"
            data-testid="media-folder-heading"
          >
            <FolderOpen aria-hidden="true" className="size-5 shrink-0 text-primary" />
            <span className="min-w-0">{folder.name}</span>
          </h2>
          <p className="text-sm text-muted-foreground" data-testid="media-folder-count">
            {folder.childFolderCount > 0 ? `${plural(folder.childFolderCount, "folder", "folders")} · ` : ""}
            {plural(folder.fileCount, "file", "files")}
            {folder.ownerName ? ` · Owner: ${folder.ownerName}` : ""}
          </p>
        </div>
      ) : (
        <h2 ref={headingRef} tabIndex={-1} className="sr-only">
          Uploads
        </h2>
      )}

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
            value={effectiveType}
            className={selectClass}
            data-testid="media-files-type"
            onChange={(event) => setType(event.target.value as UploadsTypeFilter)}
          >
            {TYPE_OPTIONS.filter((option) => !folderId || option.value !== "VIDEO").map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
        <div className="flex flex-col gap-1.5 sm:w-48">
          <Label htmlFor={ids.uploader}>Uploaded by</Label>
          <select
            id={ids.uploader}
            value={uploadedBy}
            className={selectClass}
            disabled={showingVideos}
            aria-describedby={uploaders.status === "error" ? ids.uploaderHint : undefined}
            data-testid="media-files-uploaded-by"
            onChange={(event) => setUploadedBy(event.target.value)}
          >
            <option value="">Anyone</option>
            {(uploaders.data ?? []).map((uploader) => (
              <option key={uploader.userId} value={uploader.userId}>
                {uploader.name ?? "Unknown uploader"}
              </option>
            ))}
            {uploadedBy && !uploaders.data?.some((uploader) => uploader.userId === uploadedBy) ? (
              <option value={uploadedBy}>Selected uploader</option>
            ) : null}
          </select>
          {uploaders.status === "error" ? (
            <p id={ids.uploaderHint} className="text-xs text-muted-foreground">
              Couldn&apos;t load uploaders.{" "}
              <button type="button" className="underline" onClick={() => void uploaders.refetch()}>
                Retry
              </button>
            </p>
          ) : null}
        </div>
        {!showingVideos && query.data ? (
          <Badge variant="secondary" className="h-8 px-3 sm:mb-0" data-testid="media-files-count">
            <span aria-live="polite">{plural(query.data.totalElements, "file", "files")}</span>
          </Badge>
        ) : null}
        <div className="flex flex-col gap-2 sm:ml-auto sm:flex-row">
          {canCreateFolderHere ? (
            <Button type="button" variant="outline" onClick={() => setNewFolderOpen(true)} data-testid="media-folders-new">
              <FolderPlus aria-hidden="true" />
              New folder
            </Button>
          ) : null}
          {canUploadHere ? (
            <Button type="button" onClick={() => setUploadOpen(true)} data-testid="media-files-new">
              <Plus aria-hidden="true" />
              New media
            </Button>
          ) : null}
        </div>
      </div>

      {canUploadHere && uploadOpen ? (
        <MediaFileUploadDialog
          open
          folder={folder ? { id: folder.id, name: folder.name } : null}
          onOpenChange={setUploadOpen}
          onVideoLibrary={() => {
            setUploadOpen(false);
            if (folderId) openFolder(null);
            setType("VIDEO");
          }}
          onBusyChange={onBusyChange}
        />
      ) : null}
      {canCreateFolderHere && newFolderOpen ? (
        <MediaFolderNameDialog mode="create" parentId={folderId} open onOpenChange={setNewFolderOpen} />
      ) : null}

      {folderEmpty ? (
        <div
          role="status"
          className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-12 text-center"
          data-testid="media-folder-empty"
        >
          <FolderOpen aria-hidden="true" className="size-8 text-muted-foreground" />
          <div className="space-y-1">
            <p className="text-sm font-medium text-foreground">This folder is empty</p>
            <p className="text-sm text-muted-foreground">
              {canUploadHere || canCreateFolderHere
                ? "Add documents and images, or create a folder inside it."
                : "Files and folders added here will appear in this folder."}
            </p>
          </div>
          {canUploadHere || canCreateFolderHere ? (
            <div className="flex flex-wrap justify-center gap-2">
              {canUploadHere ? (
                <Button type="button" size="sm" onClick={() => setUploadOpen(true)}>
                  <Plus aria-hidden="true" />
                  New media
                </Button>
              ) : null}
              {canCreateFolderHere ? (
                <Button type="button" size="sm" variant="outline" onClick={() => setNewFolderOpen(true)}>
                  <FolderPlus aria-hidden="true" />
                  New folder
                </Button>
              ) : null}
            </div>
          ) : null}
        </div>
      ) : (
        <>
          {foldersQuery.status === "success" && folderCount === 0 ? null : (
            <section aria-labelledby={`${ids.type}-folders`} className="flex flex-col gap-3" data-testid="media-folders-section">
              <h2 id={`${ids.type}-folders`} className="font-medium">
                Folders
              </h2>
              <QueryStateBoundary
                query={foldersQuery}
                loadingLabel="Loading folders…"
                loginPath="/login"
                permissionDenied={{ dashboardHref }}
                genericErrorMessage="Couldn't load folders."
              >
                {(folders) => (
                  <ul aria-label="Folders" className={gridClass} data-testid="media-folders-grid">
                    {folders.map((child) => (
                      <MediaFolderCard key={child.id} folder={child} onOpen={(id) => openFolder(id)} />
                    ))}
                  </ul>
                )}
              </QueryStateBoundary>
            </section>
          )}

          {!showingVideos ? (
            <section aria-labelledby={`${ids.type}-files`} className="flex flex-col gap-3" data-testid="media-files-section">
              <h2 id={`${ids.type}-files`} className="font-medium">
                {folderId ? "Files" : "Unfiled"}
              </h2>
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
                        description: "Try another search, type or uploader.",
                        action: { label: "Clear filters", onClick: clearFilters },
                      }
                    : folderId
                      ? {
                          title: "No files in this folder",
                          description: canUploadHere
                            ? "Upload documents or images into this folder."
                            : "Files added to this folder will appear here.",
                          action: canUploadHere ? { label: "New media", onClick: () => setUploadOpen(true) } : undefined,
                        }
                      : folderCount > 0
                        ? {
                            title: "No unfiled files",
                            description: "Files that aren't in a folder appear here. Open a folder to see its files.",
                            action: canUpload ? { label: "New media", onClick: () => setUploadOpen(true) } : undefined,
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
                    <ul aria-label="Media files" className={gridClass} data-testid="media-files-grid">
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
        </>
      )}

      {!folderId && (effectiveType === "ALL" || showingVideos) ? (
        <section key={effectiveType} aria-label="Internal videos" className="flex flex-col gap-3" data-testid="media-uploads-videos">
          <h2 className="font-medium">Internal videos</h2>
          {videos({ search, onClearSearch: () => setSearchInput("") })}
        </section>
      ) : null}
    </div>
  );
}
