"use client";

import { useId, useState } from "react";
import { Folder, FolderInput, MoreVertical, Pencil, Trash2, UserPlus, Users } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { MediaFolder } from "@/lib/api/media-folders";
import {
  DeleteMediaFolderDialog,
  MediaFolderNameDialog,
  ShareMediaFolderDialog,
  TransferMediaFolderDialog,
} from "./media-folder-dialogs";
import { MoveMediaFolderDialog } from "./media-folder-picker";

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`;
}

const ACCESS_LABELS: Record<NonNullable<MediaFolder["access"]>, string | null> = {
  OWNER: "Yours",
  TENANT: null,
  UPLOAD: "Can upload",
  VIEW: "View only",
};

type FolderDialog = "rename" | "move" | "share" | "transfer" | "delete" | null;

/**
 * One folder on the Uploads tab. The card itself is a button: a single click,
 * Enter or Space opens the folder (Klass uses double-click; single click is
 * our deliberate choice). The separate kebab menu offers Rename / Move… /
 * Share… / Transfer ownership… / Delete per the server's `canManage` /
 * `canShare` / `canTransfer` flags - the backend re-checks each action.
 */
export function MediaFolderCard({ folder, onOpen }: { folder: MediaFolder; onOpen: (id: string) => void }) {
  const metaId = useId();
  const [dialog, setDialog] = useState<FolderDialog>(null);
  const accessLabel = folder.access ? ACCESS_LABELS[folder.access] : null;
  const hasActions = folder.canManage || folder.canShare || folder.canTransfer;
  const closeDialog = (open: boolean) => {
    if (!open) setDialog(null);
  };

  return (
    <li className="relative min-w-0" data-testid="media-folder-card" data-media-folder-id={folder.id}>
      <button
        type="button"
        className="flex w-full min-w-0 items-start gap-3 rounded-lg border border-border bg-card p-3 pr-11 text-left transition-colors hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
        aria-label={`Open folder ${folder.name}`}
        aria-describedby={metaId}
        onClick={() => onOpen(folder.id)}
        data-testid="media-folder-open"
      >
        <Folder aria-hidden="true" className="mt-0.5 size-6 shrink-0 fill-primary/15 text-primary" />
        <span className="flex min-w-0 flex-1 flex-col gap-1">
          <span className="line-clamp-2 font-medium break-words text-foreground" title={folder.name}>
            {folder.name}
          </span>
          <span id={metaId} className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
            <span>
              {folder.childFolderCount > 0 ? `${plural(folder.childFolderCount, "folder", "folders")} · ` : ""}
              {plural(folder.fileCount, "file", "files")}
            </span>
            {accessLabel ? (
              <Badge variant="outline" className="text-[0.7rem]">
                {accessLabel}
              </Badge>
            ) : folder.ownerName ? (
              <span className="break-words">Owner: {folder.ownerName}</span>
            ) : null}
          </span>
        </span>
      </button>
      {hasActions ? (
        <div className="absolute top-2 right-2">
          <DropdownMenu>
            <DropdownMenuTrigger
              render={
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Folder actions for ${folder.name}`}
                  data-testid="media-folder-actions"
                />
              }
            >
              <MoreVertical aria-hidden="true" />
            </DropdownMenuTrigger>
            <DropdownMenuContent>
              {folder.canManage ? (
                <>
                  <DropdownMenuItem onClick={() => setDialog("rename")}>
                    <Pencil aria-hidden="true" />
                    Rename
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={() => setDialog("move")}>
                    <FolderInput aria-hidden="true" />
                    Move…
                  </DropdownMenuItem>
                </>
              ) : null}
              {folder.canShare ? (
                <DropdownMenuItem onClick={() => setDialog("share")}>
                  <UserPlus aria-hidden="true" />
                  Share…
                </DropdownMenuItem>
              ) : null}
              {folder.canTransfer ? (
                <DropdownMenuItem onClick={() => setDialog("transfer")}>
                  <Users aria-hidden="true" />
                  Transfer ownership…
                </DropdownMenuItem>
              ) : null}
              {folder.canManage ? (
                <>
                  <DropdownMenuSeparator />
                  <DropdownMenuItem variant="destructive" onClick={() => setDialog("delete")}>
                    <Trash2 aria-hidden="true" />
                    Delete
                  </DropdownMenuItem>
                </>
              ) : null}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      ) : null}
      {dialog === "rename" ? <MediaFolderNameDialog mode="rename" folder={folder} open onOpenChange={closeDialog} /> : null}
      {dialog === "move" ? <MoveMediaFolderDialog folder={folder} open onOpenChange={closeDialog} /> : null}
      {dialog === "share" ? <ShareMediaFolderDialog folder={folder} open onOpenChange={closeDialog} /> : null}
      {dialog === "transfer" ? <TransferMediaFolderDialog folder={folder} open onOpenChange={closeDialog} /> : null}
      {dialog === "delete" ? <DeleteMediaFolderDialog folder={folder} open onOpenChange={closeDialog} /> : null}
    </li>
  );
}
