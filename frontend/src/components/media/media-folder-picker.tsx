"use client";

import { useId, useState, type ReactNode } from "react";
import { ChevronRight, Folder, FolderInput, Home } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { isApiClientError } from "@/lib/api/error";
import {
  useInvalidateMediaLibrary,
  useMediaFolders,
  useMoveMediaFolder,
  type MediaFolder,
  type MediaFolderCrumb,
} from "@/lib/api/media-folders";
import { mediaFileMoveErrorMessage, useMoveMediaFile, type MediaFile } from "@/lib/api/media-files";
import { FolderMutationError } from "./media-folder-dialogs";
import { MediaDialog } from "./media-dialog";

/** A chosen destination: `id: null` is the Uploads root / Unfiled. */
export interface FolderDestination {
  id: string | null;
  name: string;
}

/**
 * Destination picker shared by "Move folder" and "Move file": browse from the
 * Uploads root through the folders the caller can see (with breadcrumbs) and
 * pick one, or the root itself. The current location, the moving folder (and
 * therefore everything inside it) and folders the caller may not move into
 * are disabled. The server re-checks every move (cycles, rights, names).
 */
function DestinationPicker({
  mode,
  movingFolderId,
  currentLocationId,
  value,
  onChange,
  disabled,
}: {
  mode: "folder" | "file";
  movingFolderId?: string;
  currentLocationId: string | null;
  value: FolderDestination | null;
  onChange: (destination: FolderDestination) => void;
  disabled?: boolean;
}) {
  const groupName = useId();
  const [trail, setTrail] = useState<MediaFolderCrumb[]>([]);
  const browsingId = trail.length ? trail[trail.length - 1].id : null;
  const folders = useMediaFolders(browsingId);
  const rootLabel = mode === "folder" ? "Uploads (root)" : "Unfiled";

  const blockedReason = (folder: MediaFolder): string | null => {
    if (mode === "folder" && folder.id === movingFolderId) return "the folder being moved";
    if (folder.id === currentLocationId) return "current location";
    if (mode === "folder" && !folder.canManage) return "you can't manage this folder";
    if (mode === "file" && !folder.canUpload) return "you can't add files here";
    return null;
  };

  const option = (destination: FolderDestination, reason: string | null, icon: ReactNode, extra?: ReactNode) => {
    const radioId = `${groupName}-${destination.id ?? "root"}`;
    const checked = value?.id === destination.id && value !== null;
    return (
      <li
        key={radioId}
        className="flex items-center gap-1 border-b border-border last:border-b-0"
        data-testid="media-folder-picker-option"
        data-folder-id={destination.id ?? "root"}
      >
        <label
          htmlFor={radioId}
          className={`flex min-w-0 flex-1 items-center gap-3 px-3 py-2 ${reason ? "cursor-not-allowed opacity-60" : "cursor-pointer hover:bg-muted/50"}`}
        >
          <input
            id={radioId}
            type="radio"
            name={groupName}
            className="size-4 shrink-0 accent-primary"
            checked={checked}
            disabled={disabled || Boolean(reason)}
            onChange={() => onChange(destination)}
          />
          {icon}
          <span className="min-w-0 flex-1 break-words">
            {destination.name}
            {reason ? <span className="ml-1 text-xs text-muted-foreground">({reason})</span> : null}
          </span>
        </label>
        {extra}
      </li>
    );
  };

  return (
    <div className="flex flex-col gap-3">
      <nav aria-label="Destination folder path">
        <ol className="flex flex-wrap items-center gap-1 text-sm text-muted-foreground">
          <li>
            {trail.length === 0 ? (
              <span aria-current="location" className="font-medium text-foreground">Uploads</span>
            ) : (
              <Button type="button" variant="link" size="sm" className="h-auto px-0" onClick={() => setTrail([])}>
                Uploads
              </Button>
            )}
          </li>
          {trail.map((crumb, index) => (
            <li key={crumb.id} className="flex min-w-0 items-center gap-1">
              <ChevronRight aria-hidden="true" className="size-3.5 shrink-0" />
              {index === trail.length - 1 ? (
                <span aria-current="location" className="font-medium break-all text-foreground">{crumb.name}</span>
              ) : (
                <Button
                  type="button"
                  variant="link"
                  size="sm"
                  className="h-auto px-0 break-all whitespace-normal"
                  onClick={() => setTrail(trail.slice(0, index + 1))}
                >
                  {crumb.name}
                </Button>
              )}
            </li>
          ))}
        </ol>
      </nav>
      <fieldset className="min-w-0">
        <legend className="sr-only">Destination</legend>
        <ul className="flex max-h-72 flex-col overflow-y-auto rounded-md border border-border" data-testid="media-folder-picker-list">
          {trail.length === 0
            ? option(
                { id: null, name: rootLabel },
                currentLocationId === null ? "current location" : null,
                <Home aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
              )
            : null}
          {folders.status === "pending" ? (
            <li>
              <LoadingState label="Loading folders…" />
            </li>
          ) : folders.status === "error" ? (
            <li className="p-2">
              <ErrorState
                message={isApiClientError(folders.error) ? folders.error.message : "Couldn't load folders."}
                onRetry={() => void folders.refetch()}
              />
            </li>
          ) : folders.data.length === 0 ? (
            <li className="px-3 py-4 text-center text-sm text-muted-foreground">
              {trail.length === 0 ? "No folders yet." : "No folders inside this one."}
            </li>
          ) : (
            folders.data.map((folder) => {
              const isMoving = mode === "folder" && folder.id === movingFolderId;
              const canBrowse = !isMoving && folder.childFolderCount > 0;
              return option(
                { id: folder.id, name: folder.name },
                blockedReason(folder),
                <Folder aria-hidden="true" className="size-4 shrink-0 text-primary" />,
                canBrowse ? (
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    className="mr-1 shrink-0"
                    aria-label={`Show folders in ${folder.name}`}
                    disabled={disabled}
                    onClick={() => setTrail([...trail, { id: folder.id, name: folder.name }])}
                  >
                    <ChevronRight aria-hidden="true" />
                  </Button>
                ) : null
              );
            })
          )}
        </ul>
      </fieldset>
    </div>
  );
}

/** Move a folder under another folder or to the Uploads root (needs `canManage` on both ends). */
export function MoveMediaFolderDialog({
  folder,
  open,
  onOpenChange,
}: {
  folder: MediaFolder;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const move = useMoveMediaFolder();
  const invalidate = useInvalidateMediaLibrary();
  const [destination, setDestination] = useState<FolderDestination | null>(null);

  function handleOpenChange(next: boolean) {
    if (!next && move.isPending) return;
    onOpenChange(next);
  }

  async function confirm() {
    if (!destination) return;
    try {
      await move.mutateAsync({ id: folder.id, parentId: destination.id, expectedVersion: folder.version });
      onOpenChange(false);
    } catch {
      // Rendered below; the dialog stays open.
    }
  }

  return (
    <MediaDialog
      open={open}
      onOpenChange={handleOpenChange}
      title={`Move “${folder.name}”`}
      description="Choose where this folder should go. Its subfolders and files move with it."
      testId="media-folder-move-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => handleOpenChange(false)} disabled={move.isPending}>
            Cancel
          </Button>
          <Button
            type="button"
            disabled={!destination || move.isPending}
            aria-busy={move.isPending}
            onClick={() => void confirm()}
            data-testid="media-folder-move-submit"
          >
            <FolderInput aria-hidden="true" />
            {move.isPending ? "Moving…" : "Move here"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <DestinationPicker
          mode="folder"
          movingFolderId={folder.id}
          currentLocationId={folder.parentId}
          value={destination}
          onChange={(next) => {
            move.reset();
            setDestination(next);
          }}
          disabled={move.isPending}
        />
        {destination ? (
          <p className="text-sm text-muted-foreground" data-testid="media-folder-move-summary">
            Move to <span className="font-medium break-words text-foreground">{destination.name}</span>. Who can see
            it doesn&apos;t change: access is set per folder.
          </p>
        ) : null}
        {move.isError ? (
          <FolderMutationError
            error={move.error}
            fallback="Couldn't move the folder. Please try again."
            onRefresh={() => {
              invalidate();
              onOpenChange(false);
            }}
            testId="media-folder-move-error"
          />
        ) : null}
      </div>
    </MediaDialog>
  );
}

/**
 * Move a file into a folder or back to Unfiled. Before committing it states
 * how the file's audience changes; lesson attachments keep working because
 * the file keeps its id.
 */
export function MoveMediaFileDialog({
  file,
  open,
  onOpenChange,
}: {
  file: MediaFile;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const move = useMoveMediaFile();
  const [destination, setDestination] = useState<FolderDestination | null>(null);

  function handleOpenChange(next: boolean) {
    if (!next && move.isPending) return;
    onOpenChange(next);
  }

  async function confirm() {
    if (!destination) return;
    try {
      await move.mutateAsync({ id: file.id, folderId: destination.id });
      onOpenChange(false);
    } catch {
      // Rendered below; the dialog stays open.
    }
  }

  return (
    <MediaDialog
      open={open}
      onOpenChange={handleOpenChange}
      title={`Move “${file.title}”`}
      description="Choose a folder for this file, or Unfiled."
      testId="media-file-move-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" onClick={() => handleOpenChange(false)} disabled={move.isPending}>
            Cancel
          </Button>
          <Button
            type="button"
            disabled={!destination || move.isPending}
            aria-busy={move.isPending}
            onClick={() => void confirm()}
            data-testid="media-file-move-submit"
          >
            <FolderInput aria-hidden="true" />
            {move.isPending ? "Moving…" : "Move file"}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <DestinationPicker
          mode="file"
          currentLocationId={file.folderId ?? null}
          value={destination}
          onChange={(next) => {
            move.reset();
            setDestination(next);
          }}
          disabled={move.isPending}
        />
        {destination ? (
          <Alert data-testid="media-file-move-audience">
            <AlertDescription className="flex flex-col gap-1">
              <span>
                {destination.id === null ? (
                  <>In Unfiled, it will be visible to everyone who can view the Media Library.</>
                ) : (
                  <>
                    Only people with access to <span className="font-medium break-words">{destination.name}</span> will
                    see it in the Media Library.
                  </>
                )}
              </span>
              <span>Course and session attachments that use this file keep working.</span>
            </AlertDescription>
          </Alert>
        ) : null}
        {move.isError ? (
          <Alert variant="destructive" role="alert" data-testid="media-file-move-error">
            <AlertDescription>{mediaFileMoveErrorMessage(move.error)}</AlertDescription>
          </Alert>
        ) : null}
      </div>
    </MediaDialog>
  );
}
