"use client";

import { useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { z } from "zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ConfirmDeleteDialog, mutationErrorMessage } from "./confirm-delete-dialog";
import { LibraryFilePickerSheet } from "@/components/courses/library-file-picker-sheet";
import { MediaPickerSheet } from "@/components/media/media-picker-sheet";
import { useAuth } from "@/lib/auth/auth-context";
import { materialKeys, useMaterials, useCreateMaterial, useAttachLibraryMaterial, useDeleteMaterial, type MaterialResponse } from "@/lib/api/materials";
import { mediaKeys, useLessonMedia, useAttachLessonMedia, useDetachLessonMedia, type LessonMedia } from "@/lib/api/media";

export type ResourceDraft =
  | { kind: "file"; id: string; title: string }
  | { kind: "video"; id: string; title: string }
  | { kind: "link"; id: string; title: string; url: string };
export type ResourcePicker = "DOCUMENT" | "IMAGE" | "SECURE_VIDEO" | "ZOOM_RECORDING" | null;
export const LINK_RESOURCE_TYPES = new Set(["VIMEO_VIDEO", "ZOOM_LINK", "ZOOM_WEBINAR", "VIDEO_LINK", "GOOGLE_DRIVE_LINK", "MS_TEAMS_LINK", "GOOGLE_MEET"]);

const linkSchema = z.object({
  title: z.string().trim().min(1, "Enter a link title.").max(255),
  url: z.string().trim().max(2048).refine((value) => {
    try {
      const url = new URL(value);
      return url.protocol === "https:" && Boolean(url.hostname) && !url.username && !url.password;
    } catch {
      return false;
    }
  }, "Enter a full https:// link without credentials."),
});
type LinkValues = z.infer<typeof linkSchema>;

/** Session metadata and attachments use separate existing endpoints; retain completed work on failure. */
export function useSessionResourceDrafts(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  const [drafts, setDrafts] = useState<ResourceDraft[]>([]);
  const draftsRef = useRef<ResourceDraft[]>([]);
  const attachFile = useAttachLibraryMaterial(courseId, moduleId);
  const attachVideo = useAttachLessonMedia(courseId);
  const createLink = useCreateMaterial(courseId, moduleId, "");

  function add(draft: ResourceDraft) {
    if (draftsRef.current.some((item) => item.kind === draft.kind && (item.id === draft.id || (item.kind === "link" && draft.kind === "link" && item.url === draft.url && item.title === draft.title)))) return;
    draftsRef.current = [...draftsRef.current, draft];
    setDrafts(draftsRef.current);
  }
  function remove(id: string) {
    draftsRef.current = draftsRef.current.filter((item) => item.id !== id);
    setDrafts(draftsRef.current);
  }

  async function save(lessonId: string) {
    const materialsPath = `/v1/courses/${courseId}/modules/${moduleId}/lessons/${lessonId}/materials`;
    const videosPath = `/v1/courses/${courseId}/lessons/${lessonId}/media`;
    // Re-read before every retry: a previous response may have been lost after the server committed.
    const [materials, videos] = await Promise.all([
      authorizedFetch<MaterialResponse[]>("tenant", materialsPath),
      authorizedFetch<LessonMedia[]>("tenant", videosPath),
    ]);
    const failures: string[] = [];
    for (const draft of [...draftsRef.current]) {
      const exists = draft.kind === "file"
        ? materials.some((item) => item.mediaFileId === draft.id)
        : draft.kind === "video"
          ? videos.some((item) => item.mediaAssetId === draft.id)
          : materials.some((item) => item.materialType === "LINK" && item.externalUrl === draft.url && item.title === draft.title);
      if (exists) {
        remove(draft.id);
        continue;
      }
      try {
        if (draft.kind === "file") {
          await attachFile.mutateAsync({ mediaFileId: draft.id, title: draft.title, targetLessonId: lessonId });
        } else if (draft.kind === "video") {
          await attachVideo.mutateAsync({ mediaAssetId: draft.id, targetLessonId: lessonId });
        } else {
          await createLink.mutateAsync({ title: draft.title, materialType: "LINK", externalUrl: draft.url, targetLessonId: lessonId });
        }
        remove(draft.id);
      } catch (error) {
        failures.push(`${draft.title}: ${mutationErrorMessage(error)}`);
      }
    }
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: materialKeys.list(courseId, moduleId, lessonId) }),
      queryClient.invalidateQueries({ queryKey: mediaKeys.lesson(courseId, lessonId) }),
    ]);
    if (failures.length) throw new Error(failures.join(" "));
  }

  return { drafts, add, remove, save };
}

export function SessionResources({
  courseId,
  moduleId,
  lessonId,
  resourceType,
  drafts,
  add,
  remove,
  picker,
  setPicker,
  disabled,
  dashboardHref,
}: {
  courseId: string;
  moduleId: string;
  lessonId: string;
  resourceType: string;
  drafts: ResourceDraft[];
  add: (draft: ResourceDraft) => void;
  remove: (id: string) => void;
  picker: ResourcePicker;
  setPicker: (picker: ResourcePicker) => void;
  disabled: boolean;
  dashboardHref: string;
}) {
  const materials = useMaterials(courseId, moduleId, lessonId, { enabled: Boolean(lessonId) });
  const videos = useLessonMedia(courseId, lessonId, Boolean(lessonId));
  const deleteMaterial = useDeleteMaterial(courseId, moduleId, lessonId);
  const detachVideo = useDetachLessonMedia(courseId, lessonId);
  const [target, setTarget] = useState<{ id: string; title: string; video: boolean } | null>(null);
  const [removeError, setRemoveError] = useState<string | null>(null);
  const link = useForm<LinkValues>({
    resolver: zodResolver(linkSchema),
    defaultValues: { title: "", url: "" },
  });
  const addLink = link.handleSubmit((values) => {
    add({ kind: "link", id: crypto.randomUUID(), title: values.title, url: values.url });
    link.reset();
  });
  const fileIds = new Set([
    ...drafts.filter((item) => item.kind === "file").map((item) => item.id),
    ...(materials.data ?? []).flatMap((item) => item.mediaFileId ? [item.mediaFileId] : []),
  ]);
  const videoIds = new Set(drafts.filter((item) => item.kind === "video").map((item) => item.id));
  const hasLinkFields = LINK_RESOURCE_TYPES.has(resourceType);

  async function confirmRemove() {
    if (!target) return;
    setRemoveError(null);
    try {
      if (target.video) await detachVideo.mutateAsync(target.id);
      else await deleteMaterial.mutateAsync(target.id);
      setTarget(null);
    } catch (error) {
      setRemoveError(mutationErrorMessage(error));
    }
  }

  const selectedPicker = resourceType === "IMAGE" || resourceType === "DOCUMENT" || resourceType === "SECURE_VIDEO"
    ? resourceType
    : null;
  const attachmentCount = drafts.length + (materials.data?.length ?? 0) + (videos.data?.length ?? 0);

  return (
    <div className="flex min-w-0 flex-col gap-3" aria-label="Session attachments">
      {attachmentCount > 0 ? (
        <p className="text-xs text-muted-foreground">Changing Resource Type keeps previous attachments. Remove any items you do not want before saving.</p>
      ) : null}
      {selectedPicker ? (
        <div className="flex items-center justify-between gap-3">
          <p className="text-xs text-muted-foreground">{attachmentCount ? `${attachmentCount} attachment${attachmentCount === 1 ? "" : "s"} selected or saved.` : "No attachments selected yet."}</p>
          <Button type="button" size="sm" variant="outline" disabled={disabled} onClick={() => setPicker(selectedPicker)}>
            {attachmentCount ? "Change / add" : `Choose ${selectedPicker === "SECURE_VIDEO" ? "Player Video" : selectedPicker.toLowerCase()}`}
          </Button>
        </div>
      ) : null}

      {resourceType === "ZOOM_MEETING" ? (
        <div className="flex flex-col gap-2 rounded border border-border p-3" data-testid="session-zoom-unavailable">
          <p role="status">Named Zoom integrations and these options are not available yet. Zoom integration work is parked; saving session details does not schedule a meeting.</p>
          <Label htmlFor="session-zoom-integration">Zoom Integration Name</Label>
          <Input id="session-zoom-integration" disabled placeholder="Not available yet" />
          <label className="flex items-center gap-2"><input type="checkbox" disabled /> Mark Attendance When Joining</label>
          <label className="flex items-center gap-2"><input type="checkbox" disabled /> Require Registration</label>
        </div>
      ) : null}

      {hasLinkFields ? (
        <div className="flex flex-col gap-2" data-testid="session-link-fields">
          <Label htmlFor="session-link-title">Link title</Label>
          <Input id="session-link-title" {...link.register("title")} aria-invalid={Boolean(link.formState.errors.title)} />
          {link.formState.errors.title ? <p role="alert" className="text-destructive">{link.formState.errors.title.message}</p> : null}
          <Label htmlFor="session-link-url">
            {resourceType === "GOOGLE_DRIVE_LINK" ? "Google Drive link" : resourceType === "MS_TEAMS_LINK" ? "Microsoft Teams link" : resourceType === "GOOGLE_MEET" ? "Google Meet link" : "Resource link"}
          </Label>
          <Input id="session-link-url" type="url" placeholder="https://" {...link.register("url")} aria-invalid={Boolean(link.formState.errors.url)} />
          {link.formState.errors.url ? <p role="alert" className="text-destructive">{link.formState.errors.url.message}</p> : null}
          <Button type="button" variant="outline" disabled={disabled} onClick={() => void addLink()}>Add link to session</Button>
          <p className="text-xs text-muted-foreground">Add the link to the pending list before saving. External sharing settings still apply.</p>
        </div>
      ) : null}

      {drafts.length ? (
        <ul aria-label="Pending resources" className="flex flex-col gap-2">
          {drafts.map((draft) => (
            <li key={draft.id} className="flex items-center justify-between gap-2 rounded bg-muted p-2">
              <span className="min-w-0 break-words">{draft.title} <span className="text-xs text-muted-foreground">({draft.kind}, pending)</span></span>
              <Button type="button" variant="ghost" size="sm" disabled={disabled} aria-label={`Remove pending ${draft.title}`} onClick={() => remove(draft.id)}>Remove</Button>
            </li>
          ))}
        </ul>
      ) : null}

      {lessonId ? (
        <div className="flex flex-col gap-2">
          <p className="text-xs text-muted-foreground">
            Removing a saved attachment takes effect immediately, even if you cancel this form. Library media is kept.
            Saved links are read-only; add a replacement, then remove the old link explicitly.
          </p>
          <QueryStateBoundary query={materials} loadingLabel="Loading saved materials…" loginPath="/login" permissionDenied={{ dashboardHref }}>
            {(items) => items.length ? (
              <ul aria-label="Saved materials">
                {items.map((item) => (
                  <li key={item.id} className="flex items-start justify-between gap-2 border-b border-border py-2">
                    <span className="min-w-0 break-words">
                      {item.title} <span className="text-xs">({item.materialType})</span>
                      {item.materialType === "LINK" ? <span className="block text-xs text-muted-foreground">{item.externalUrl}</span> : null}
                    </span>
                    <Button type="button" variant="outline" size="sm" disabled={disabled} aria-label={`Remove attachment ${item.title}`} onClick={() => { setRemoveError(null); setTarget({ id: item.id, title: item.title, video: false }); }}>Remove</Button>
                  </li>
                ))}
              </ul>
            ) : <p className="text-xs text-muted-foreground">No saved materials.</p>}
          </QueryStateBoundary>
          <QueryStateBoundary query={videos} loadingLabel="Loading saved videos…" loginPath="/login" permissionDenied={{ dashboardHref }}>
            {(items) => items.length ? (
              <ul aria-label="Saved videos">
                {items.map((item) => (
                  <li key={item.id} className="flex items-start justify-between gap-2 border-b border-border py-2">
                    <span>{item.title} <span className="text-xs">({item.provider})</span></span>
                    <Button type="button" variant="outline" size="sm" disabled={disabled} aria-label={`Remove attachment ${item.title}`} onClick={() => { setRemoveError(null); setTarget({ id: item.id, title: item.title, video: true }); }}>Remove</Button>
                  </li>
                ))}
              </ul>
            ) : <p className="text-xs text-muted-foreground">No saved player videos.</p>}
          </QueryStateBoundary>
        </div>
      ) : null}

      {picker === "DOCUMENT" || picker === "IMAGE" ? (
        <LibraryFilePickerSheet
          key={picker}
          open
          onOpenChange={(open) => { if (!open) setPicker(null); }}
          courseId={courseId}
          moduleId={moduleId}
          lessonId={lessonId}
          dashboardHref={dashboardHref}
          attachedMediaFileIds={fileIds}
          lockedKind={picker}
          onSelect={(file) => add({ kind: "file", id: file.id, title: file.title })}
        />
      ) : null}
      {picker === "SECURE_VIDEO" ? (
        <MediaPickerSheet
          open
          onOpenChange={(open) => { if (!open) setPicker(null); }}
          courseId={courseId}
          lessonId={lessonId}
          attached={videos.data ?? []}
          selectedIds={videoIds}
          dashboardHref={dashboardHref}
          onSelect={(asset) => add({ kind: "video", id: asset.id, title: asset.title })}
        />
      ) : null}
      {picker === "ZOOM_RECORDING" ? (
        <div role="status" className="rounded border border-border p-3" data-testid="zoom-recording-library-unavailable">
          <h4 className="font-semibold">Zoom recording library is not available yet</h4>
          <p>Cloud recording import is part of the parked Zoom integration. Download a recording from Zoom and upload it as a Player Video instead. This type cannot be saved.</p>
          <Button type="button" variant="outline" onClick={() => setPicker(null)}>Dismiss</Button>
        </div>
      ) : null}

      <ConfirmDeleteDialog
        open={target !== null}
        onOpenChange={(open) => { if (!open) setTarget(null); }}
        title={`Remove “${target?.title ?? ""}”?`}
        description="This immediately removes only the session attachment. Library media remains available. Canceling the session form will not undo this removal."
        confirmLabel="Remove attachment"
        pending={deleteMaterial.isPending || detachVideo.isPending}
        error={removeError}
        onConfirm={() => void confirmRemove()}
      />
    </div>
  );
}
