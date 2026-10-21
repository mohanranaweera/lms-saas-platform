"use client";

import { useState } from "react";
import { ExternalLink, File as FileIcon, FileImage, FileText, Link2, Loader2, Pencil, Trash2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { formatFileSize } from "@/lib/api/materials";
import { isApiClientError } from "@/lib/api/error";
import { lessonCountLabel, useMediaFileOpenUrl, type MediaFile, type MediaFileKind } from "@/lib/api/media-files";
import { DeleteMediaFileDialog, RenameMediaFileDialog } from "./media-file-dialogs";
import { AddedDate } from "./media-picker-shared";

export const MEDIA_FILE_KIND_LABELS: Record<MediaFileKind, string> = {
  DOCUMENT: "Document",
  IMAGE: "Image",
  OTHER: "File",
};

/** Icon for a file without a preview: PDF / text documents, or a generic file. */
export function MediaFileKindIcon({ file, className }: { file: Pick<MediaFile, "kind" | "contentType">; className?: string }) {
  if (file.kind === "IMAGE") return <FileImage aria-hidden="true" className={className} />;
  if (file.kind === "DOCUMENT" || file.contentType.startsWith("text/") || file.contentType === "application/pdf") {
    return <FileText aria-hidden="true" className={className} />;
  }
  return <FileIcon aria-hidden="true" className={className} />;
}

function documentLabel(file: MediaFile): string | null {
  if (file.contentType === "application/pdf") return "PDF";
  if (file.contentType.startsWith("text/")) return "TXT";
  return null;
}

/**
 * One Media Library file (Klass card): type badge, image preview or document
 * icon, title, size, uploader, added date and usage, and Open / Rename / Delete. Open is
 * always offered; Rename / Delete follow the server's `canEdit` / `canDelete`
 * (the backend re-checks both). An expired signed thumbnail falls back to the
 * icon and asks the list to refetch fresh URLs.
 */
export function MediaFileCard({ file, onThumbnailExpired }: { file: MediaFile; onThumbnailExpired?: () => void }) {
  const [failedThumbnail, setFailedThumbnail] = useState<string | null>(null);
  const [renameOpen, setRenameOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [openError, setOpenError] = useState<string | null>(null);
  const openUrl = useMediaFileOpenUrl();
  const showThumbnail = file.kind === "IMAGE" && file.thumbnailUrl && failedThumbnail !== file.thumbnailUrl;
  const docLabel = documentLabel(file);

  async function handleOpen() {
    setOpenError(null);
    const tab = window.open("about:blank", "_blank");
    if (!tab) {
      setOpenError("Your browser blocked the new tab. Allow popups for this site and try again.");
      return;
    }
    tab.opener = null;
    const referrerPolicy = tab.document.createElement("meta");
    referrerPolicy.name = "referrer";
    referrerPolicy.content = "no-referrer";
    tab.document.head.appendChild(referrerPolicy);
    try {
      const result = await openUrl.mutateAsync(file.id);
      tab.location.replace(result.url);
    } catch (error) {
      tab.close();
      setOpenError(
        isApiClientError(error) && error.status === 404
          ? "This file is no longer available."
          : isApiClientError(error)
            ? error.message
            : "Couldn't open the file. Please try again."
      );
    }
  }

  return (
    <li
      className="flex flex-col overflow-hidden rounded-lg border border-border bg-card"
      data-testid="media-file-card"
      data-media-file-id={file.id}
    >
      <div className="relative flex aspect-[4/3] items-center justify-center bg-muted">
        {showThumbnail ? (
          // eslint-disable-next-line @next/next/no-img-element -- short-lived signed URL from our storage; next/image would cache it
          <img
            src={file.thumbnailUrl!}
            alt={file.title}
            className="h-full w-full object-cover"
            loading="lazy"
            onError={() => {
              setFailedThumbnail(file.thumbnailUrl);
              onThumbnailExpired?.();
            }}
          />
        ) : (
          <div className="flex flex-col items-center gap-1 text-muted-foreground">
            <MediaFileKindIcon file={file} className="size-12" />
            {docLabel ? <span className="text-xs font-semibold tracking-wide">{docLabel}</span> : null}
          </div>
        )}
        <Badge variant="secondary" className="absolute top-2 left-2 shadow-sm" data-testid="media-file-kind">
          <MediaFileKindIcon file={file} />
          {MEDIA_FILE_KIND_LABELS[file.kind]}
        </Badge>
      </div>
      <div className="flex flex-1 flex-col gap-1.5 p-3">
        <h3 className="line-clamp-2 font-medium break-words text-foreground" title={file.title}>
          {file.title}
        </h3>
        <p className="text-xs text-muted-foreground">
          {formatFileSize(file.sizeBytes)}
          {file.uploaderName ? ` · ${file.uploaderName}` : ""}
        </p>
        <AddedDate iso={file.createdAt} className="text-xs text-muted-foreground" />
        {file.attachedLessonCount > 0 ? (
          <Badge variant="outline" data-testid="media-file-usage">
            <Link2 aria-hidden="true" />
            Used in {lessonCountLabel(file.attachedLessonCount)}
          </Badge>
        ) : null}
        {openError ? (
          <p role="alert" className="text-xs text-destructive">
            {openError}
          </p>
        ) : null}
      </div>
      <div className="flex flex-wrap items-center gap-1 border-t border-border px-2 py-2">
        <Button
          type="button"
          variant="ghost"
          size="sm"
          onClick={() => void handleOpen()}
          disabled={openUrl.isPending}
          aria-busy={openUrl.isPending}
          data-testid="media-file-open"
        >
          {openUrl.isPending ? <Loader2 aria-hidden="true" className="animate-spin" /> : <ExternalLink aria-hidden="true" />}
          Open<span className="sr-only"> {file.title} (opens in a new tab)</span>
        </Button>
        {file.canEdit ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={() => setRenameOpen(true)}
            data-testid="media-file-rename"
          >
            <Pencil aria-hidden="true" />
            Rename<span className="sr-only"> {file.title}</span>
          </Button>
        ) : null}
        {file.canDelete ? (
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="text-destructive hover:text-destructive"
            onClick={() => setDeleteOpen(true)}
            data-testid="media-file-delete"
          >
            <Trash2 aria-hidden="true" />
            Delete<span className="sr-only"> {file.title}</span>
          </Button>
        ) : null}
      </div>
      {renameOpen ? <RenameMediaFileDialog file={file} open={renameOpen} onOpenChange={setRenameOpen} /> : null}
      {deleteOpen ? <DeleteMediaFileDialog file={file} open={deleteOpen} onOpenChange={setDeleteOpen} /> : null}
    </li>
  );
}
