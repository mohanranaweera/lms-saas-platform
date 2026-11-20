"use client";

import { useEffect, useId, useState } from "react";
import { Check, Loader2, Plus, Search, Sparkles } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { MEDIA_FILE_KIND_LABELS, MediaFileKindIcon } from "@/components/media/media-file-card";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { MediaDialog } from "@/components/media/media-dialog";
import { MediaFileUploadDialog } from "@/components/media/media-file-dialogs";
import { AddedDate, useMediaUploadUx } from "@/components/media/media-picker-shared";
import { isApiClientError } from "@/lib/api/error";
import { formatFileSize, useAttachLibraryMaterial } from "@/lib/api/materials";
import { useMediaFiles, type MediaFile, type MediaFileKind } from "@/lib/api/media-files";

const PAGE_SIZE = 20;

type KindFilter = "ALL" | MediaFileKind;

const KIND_OPTIONS: { value: KindFilter; label: string }[] = [
  { value: "ALL", label: "All files" },
  { value: "DOCUMENT", label: "Documents" },
  { value: "IMAGE", label: "Images" },
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

function attachErrorMessage(error: unknown): string {
  if (isApiClientError(error)) {
    if (error.status === 409) return "Already attached to this session.";
    if (error.status === 404) return "This file is no longer in the Media Library. The list has been refreshed.";
    if (error.status === 403) return "You don't have permission to add materials to this session.";
    return error.message;
  }
  return "Couldn't add the file. Please try again.";
}

/**
 * Klass gaps round 2 (G1): attach an existing Media Library document or image
 * to one lesson ("session") without copying it. The server decides what is
 * visible and whether the caller may attach; `attachedMediaFileIds` only marks
 * files already on this lesson for a clearer list (the server answers 409 anyway).
 */
export function LibraryFilePickerSheet({
  open,
  onOpenChange,
  courseId,
  moduleId,
  lessonId,
  attachedMediaFileIds,
  dashboardHref,
  onAttached,
  onSelect,
  initialKind = "ALL",
  lockedKind,
  onRequestVideos,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  courseId: string;
  moduleId: string;
  lessonId: string;
  attachedMediaFileIds: ReadonlySet<string>;
  dashboardHref: string;
  onAttached?: (file: MediaFile) => void;
  /** Draft-selection mode: no request is sent; the parent attaches after the session exists. */
  onSelect?: (file: MediaFile) => void;
  initialKind?: KindFilter;
  lockedKind?: MediaFileKind;
  /** After a video upload: switch to the video picker. Omitted = no "View videos" step. */
  onRequestVideos?: () => void;
}) {
  const ids = { search: useId(), kind: useId() };
  const [searchInput, setSearchInput] = useState("");
  const search = useDebounced(searchInput, 350);
  const [kind, setKind] = useState<KindFilter>(lockedKind ?? initialKind);
  const [page, setPage] = useState(0);
  const [pagedFilters, setPagedFilters] = useState({ search, kind });
  if (pagedFilters.search !== search || pagedFilters.kind !== kind) {
    setPagedFilters({ search, kind });
    setPage(0);
  }

  const query = useMediaFiles(
    { q: search, kind: kind === "ALL" ? undefined : kind, page, size: PAGE_SIZE },
    open
  );
  const attach = useAttachLibraryMaterial(courseId, moduleId, lessonId);
  const [pendingId, setPendingId] = useState<string | null>(null);
  const filtered = Boolean(search.trim() || (!lockedKind && kind !== "ALL"));
  const { canUpload } = useMediaUploadUx();
  const [uploadOpen, setUploadOpen] = useState(false);
  const [uploadBusy, setUploadBusy] = useState(false);
  const [uploadStatus, setUploadStatus] = useState("");
  const [newFileId, setNewFileId] = useState<string | null>(null);

  function handleUploaded(file: MediaFile) {
    void query.refetch();
    setNewFileId(file.id);
    if (lockedKind && file.kind !== lockedKind) {
      setUploadStatus(
        `Uploaded "${file.title}". It is ${file.kind === "IMAGE" ? "an image" : "a document"}, so it isn't listed in this ${lockedKind === "IMAGE" ? "image" : "document"} picker.`
      );
      return;
    }
    if (!lockedKind && kind !== "ALL" && kind !== file.kind) setKind("ALL");
    setUploadStatus(`Uploaded "${file.title}". ${onSelect ? "Choose Select to use it in this session." : "Select Add to attach it."}`);
  }

  async function handleAdd(file: MediaFile) {
    if (onSelect) {
      onSelect(file);
      onOpenChange(false);
      return;
    }
    setPendingId(file.id);
    try {
      await attach.mutateAsync({ mediaFileId: file.id, title: file.title });
      onAttached?.(file);
      onOpenChange(false);
    } catch (error) {
      if (isApiClientError(error) && error.status === 404) void query.refetch();
    } finally {
      setPendingId(null);
    }
  }

  function handleOpenChange(next: boolean) {
    if (attach.isPending || uploadBusy) return;
    if (!next) attach.reset();
    onOpenChange(next);
  }

  return (
    <MediaDialog
      open={open}
      onOpenChange={handleOpenChange}
      title="Add from Media Library"
      description={<>{onSelect ? "Select" : "Attach"}{" "}{lockedKind === "DOCUMENT" ? "a document" : lockedKind === "IMAGE" ? "an image" : "a document or image"}{" "}from your institute&apos;s Media Library. {onSelect ? "Selection is saved only when you save the session." : "It is linked, not copied: removing it from this session later keeps it in the library."}</>}
      testId="library-file-picker"
      className="sm:max-w-3xl"
      footer={null}
    >
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto">
          <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_10rem]">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={ids.search}>Search files</Label>
              <div className="relative">
                <Search
                  aria-hidden="true"
                  className="pointer-events-none absolute top-2 left-2.5 size-4 text-muted-foreground"
                />
                <Input
                  id={ids.search}
                  type="search"
                  value={searchInput}
                  maxLength={200}
                  placeholder="Title or file name"
                  className="pl-8"
                  data-testid="library-file-picker-search"
                  onChange={(event) => setSearchInput(event.target.value)}
                />
              </div>
            </div>
            {lockedKind ? (
              <div className="flex flex-col gap-1.5">
                <Label>Type</Label>
                <div className="flex h-8 items-center rounded-lg border border-input px-2.5 text-sm" data-testid="library-file-picker-type-locked">
                  {KIND_OPTIONS.find((option) => option.value === lockedKind)?.label ?? lockedKind}
                </div>
              </div>
            ) : (
              <div className="flex flex-col gap-1.5">
                <Label htmlFor={ids.kind}>Type</Label>
                <select
                  id={ids.kind}
                  value={kind}
                  className={selectClass}
                  data-testid="library-file-picker-type"
                  onChange={(event) => setKind(event.target.value as KindFilter)}
                >
                  {KIND_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </div>
            )}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {query.data ? (
              <Badge variant="secondary" className="h-8 px-3" data-testid="library-file-picker-count">
                <span aria-live="polite">
                  {query.data.totalElements} {query.data.totalElements === 1 ? "file" : "files"}
                </span>
              </Badge>
            ) : null}
            {canUpload ? (
              <Button
                type="button"
                variant="outline"
                className="sm:ml-auto"
                onClick={() => setUploadOpen(true)}
                data-testid="library-file-picker-new"
              >
                <Plus aria-hidden="true" />
                New media
              </Button>
            ) : null}
          </div>

          <p role="status" aria-live="polite" className={uploadStatus ? "rounded-md border border-border bg-muted/40 p-3 text-sm" : "sr-only"} data-testid="library-file-picker-status">
            {uploadStatus}
          </p>

          {canUpload && uploadOpen ? (
            <MediaFileUploadDialog
              open
              onOpenChange={setUploadOpen}
              onUploaded={handleUploaded}
              onBusyChange={setUploadBusy}
              onVideoLibrary={
                onRequestVideos
                  ? () => {
                      setUploadOpen(false);
                      onRequestVideos();
                    }
                  : undefined
              }
            />
          ) : null}

          {attach.isError ? (
            <div
              role="alert"
              className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive"
              data-testid="library-file-picker-error"
            >
              {attachErrorMessage(attach.error)}
            </div>
          ) : null}

          <QueryStateBoundary
            query={query}
            loadingLabel="Loading library files…"
            loginPath="/login"
            permissionDenied={{ dashboardHref }}
            isEmpty={(data) => data.content.length === 0}
            emptyState={
              filtered
                ? {
                    title: "No files match",
                    description: "Try a different search or type.",
                    action: {
                      label: "Clear filters",
                      onClick: () => {
                        setSearchInput("");
                        setKind(lockedKind ?? "ALL");
                      },
                    },
                  }
                : {
                    title: `No ${lockedKind === "IMAGE" ? "images" : lockedKind === "DOCUMENT" ? "documents" : "files"} in the Media Library yet`,
                    description: canUpload
                      ? "Upload a document or image with New media, then add it to this session."
                      : "Documents and images uploaded to the Media Library can be added to sessions here.",
                    action: canUpload ? { label: "New media", onClick: () => setUploadOpen(true) } : undefined,
                  }
            }
          >
            {(data) => (
              <div className="flex flex-col gap-3">
                <ul aria-label="Library files" className="flex flex-col gap-2">
                  {data.content.map((file) => {
                    const alreadyAttached = attachedMediaFileIds.has(file.id);
                    const adding = pendingId === file.id;
                    const isNew = newFileId === file.id;
                    return (
                      <li
                        key={file.id}
                        className={isNew ? "flex items-center gap-3 rounded-md border border-primary bg-primary/5 p-3 ring-2 ring-primary/30" : "flex items-center gap-3 rounded-md border border-border p-3"}
                        data-new={isNew ? "true" : undefined}
                        data-testid="library-file-picker-item"
                        data-media-file-id={file.id}
                      >
                        <div className="flex size-14 shrink-0 items-center justify-center overflow-hidden rounded bg-muted">
                          {file.kind === "IMAGE" && file.thumbnailUrl ? (
                            // eslint-disable-next-line @next/next/no-img-element -- short-lived signed URL; never cached by next/image
                            <img
                              src={file.thumbnailUrl}
                              alt=""
                              className="h-full w-full object-cover"
                              onError={(event) => {
                                event.currentTarget.style.display = "none";
                              }}
                            />
                          ) : (
                            <MediaFileKindIcon file={file} className="size-6 text-muted-foreground" />
                          )}
                        </div>
                        <div className="min-w-0 flex-1">
                          <p className="truncate font-medium text-foreground" title={file.title}>
                            {file.title}
                          </p>
                          <div className="mt-1 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                            {isNew ? (
                              <Badge data-testid="library-file-picker-new-badge">
                                <Sparkles aria-hidden="true" />
                                New
                              </Badge>
                            ) : null}
                            <Badge variant="outline">{MEDIA_FILE_KIND_LABELS[file.kind]}</Badge>
                            <span>{formatFileSize(file.sizeBytes)}</span>
                            {file.uploaderName ? <span>· {file.uploaderName}</span> : null}
                            <span aria-hidden="true">·</span>
                            <AddedDate iso={file.createdAt} />
                          </div>
                        </div>
                        <Button
                          type="button"
                          size="sm"
                          variant={alreadyAttached ? "outline" : "default"}
                          disabled={alreadyAttached || (!onSelect && attach.isPending)}
                          aria-busy={adding}
                          data-testid="library-file-picker-add"
                          onClick={() => void handleAdd(file)}
                        >
                          {adding ? (
                            <>
                              <Loader2 aria-hidden="true" className="animate-spin" /> Adding…
                            </>
                          ) : alreadyAttached ? (
                            <>
                              <Check aria-hidden="true" /> Attached
                            </>
                          ) : (
                            <>
                              <Plus aria-hidden="true" /> {onSelect ? "Select" : "Add"}
                            </>
                          )}
                          <span className="sr-only"> {file.title}</span>
                        </Button>
                      </li>
                    );
                  })}
                </ul>
                {data.totalPages > 1 ? (
                  <nav aria-label="Library file pages" className="flex items-center justify-between gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      disabled={page === 0}
                      onClick={() => setPage((p) => Math.max(0, p - 1))}
                    >
                      Previous
                    </Button>
                    <span className="text-xs text-muted-foreground">
                      Page {data.page + 1} of {data.totalPages}
                    </span>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
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
    </MediaDialog>
  );
}
