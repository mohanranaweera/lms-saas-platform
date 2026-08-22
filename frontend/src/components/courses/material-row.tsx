"use client";

import { useId, useState } from "react";
import { ArrowDown, ArrowUp, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { TitleInlineForm } from "@/components/courses/title-inline-form";
import {
  formatFileSize,
  useDeleteMaterial,
  useMaterialDownloadUrl,
  useUpdateMaterial,
  type MaterialResponse,
  type MaterialVisibility,
} from "@/lib/api/materials";
import { isApiClientError } from "@/lib/api/error";

interface MaterialRowProps {
  courseId: string;
  moduleId: string;
  lessonId: string;
  material: MaterialResponse;
  isFirst: boolean;
  isLast: boolean;
  /** True while any material reorder is in flight in this lesson's list — see `CourseLessonItem`'s equivalent prop. */
  reorderBusy: boolean;
  onMoveUp: () => void;
  onMoveDown: () => void;
}

const VISIBILITY_OPTIONS: Array<{ value: MaterialVisibility; label: string }> = [
  { value: "VISIBLE", label: "Visible" },
  { value: "HIDDEN", label: "Hidden" },
];

const VISIBILITY_LABELS: Record<MaterialVisibility, string> = {
  VISIBLE: "Visible",
  HIDDEN: "Hidden",
};

/**
 * Single material row: filename/mime/size metadata, inline title-rename
 * (reusing `TitleInlineForm`, wrapping `onSubmit` to also resend the current
 * `sequence` + `visibility`, since `PATCH .../materials/{id}` is a
 * full-resource replace — see `MaterialUpdateRequest`), a visibility
 * `Select` that immediately PATCHes with the current `title` + `sequence`
 * preserved, keyboard-reachable Move Up/Down, a "View" action that fetches a
 * fresh signed download URL on click and opens it in a new tab (never
 * rendered/stored beyond that one action), and an `AlertDialog`-confirmed
 * delete copying `CourseLessonItem`'s exact structure.
 */
export function MaterialRow({
  courseId,
  moduleId,
  lessonId,
  material,
  isFirst,
  isLast,
  reorderBusy,
  onMoveUp,
  onMoveDown,
}: MaterialRowProps) {
  const visibilityId = useId();
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [visibilityError, setVisibilityError] = useState<string | null>(null);
  const [viewError, setViewError] = useState<string | null>(null);

  const renameMutation = useUpdateMaterial(courseId, moduleId, lessonId);
  const visibilityMutation = useUpdateMaterial(courseId, moduleId, lessonId);
  const deleteMutation = useDeleteMaterial(courseId, moduleId, lessonId);
  const downloadUrlMutation = useMaterialDownloadUrl(courseId, moduleId, lessonId);

  const deleteErrorMessage = deleteMutation.isError
    ? isApiClientError(deleteMutation.error)
      ? deleteMutation.error.message
      : "An unexpected error occurred. Please try again."
    : null;

  const disabled = reorderBusy || deleteMutation.isPending || visibilityMutation.isPending;

  const handleDelete = async () => {
    try {
      await deleteMutation.mutateAsync(material.id);
      setDeleteOpen(false);
    } catch {
      // Surfaced via deleteErrorMessage below; keep the dialog open so the
      // teacher can see the failure and retry or cancel.
    }
  };

  const handleVisibilityChange = async (visibility: string | null) => {
    if (!visibility || visibility === material.visibility) return;
    setVisibilityError(null);
    try {
      await visibilityMutation.mutateAsync({
        materialId: material.id,
        body: { title: material.title, sequence: material.sequence, visibility: visibility as MaterialVisibility },
      });
    } catch (error) {
      setVisibilityError(
        isApiClientError(error) ? error.message : "Couldn't update visibility. Please try again."
      );
    }
  };

  const handleView = async () => {
    setViewError(null);
    try {
      const result = await downloadUrlMutation.mutateAsync(material.id);
      window.open(result.url, "_blank", "noopener,noreferrer");
    } catch (error) {
      setViewError(
        isApiClientError(error) ? error.message : "Couldn't open this material. Please try again."
      );
    }
  };

  return (
    <div className="flex flex-col gap-2 rounded-md border border-border/70 bg-muted/20 p-3 sm:flex-row sm:items-start sm:justify-between">
      <div className="min-w-0 flex-1">
        <TitleInlineForm
          idPrefix={`material-${material.id}`}
          label="Material title"
          initialTitle={material.title}
          submitLabel="Save"
          pendingLabel={`Saving material "${material.title}"…`}
          disabled={disabled}
          onSubmit={async (title) => {
            await renameMutation.mutateAsync({
              materialId: material.id,
              body: { title, sequence: material.sequence, visibility: material.visibility },
            });
          }}
        />
        <p className="mt-1 truncate text-xs text-muted-foreground">
          {material.originalFilename} &middot; {material.mimeType} &middot;{" "}
          {formatFileSize(material.sizeBytes)}
        </p>
        {visibilityError ? (
          <Alert variant="destructive" className="mt-2">
            <AlertDescription>{visibilityError}</AlertDescription>
          </Alert>
        ) : null}
        {viewError ? (
          <Alert variant="destructive" className="mt-2">
            <AlertDescription>{viewError}</AlertDescription>
          </Alert>
        ) : null}
      </div>

      <div className="flex shrink-0 flex-wrap items-center gap-1">
        <div className="flex flex-col gap-1">
          <Label htmlFor={visibilityId} className="sr-only">
            Visibility for &ldquo;{material.title}&rdquo;
          </Label>
          <Select
            value={material.visibility}
            onValueChange={handleVisibilityChange}
            disabled={disabled}
          >
            <SelectTrigger
              id={visibilityId}
              size="sm"
              className="w-28"
              aria-busy={visibilityMutation.isPending}
            >
              <SelectValue>
                {(selected: string | null) =>
                  selected ? VISIBILITY_LABELS[selected as MaterialVisibility] : "Visibility"
                }
              </SelectValue>
            </SelectTrigger>
            <SelectContent>
              {VISIBILITY_OPTIONS.map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={handleView}
          disabled={disabled || downloadUrlMutation.isPending}
          aria-busy={downloadUrlMutation.isPending}
        >
          {downloadUrlMutation.isPending ? "Opening…" : "View"}
        </Button>

        <Button
          type="button"
          variant="outline"
          size="icon-xs"
          aria-label={`Move "${material.title}" up`}
          disabled={isFirst || disabled}
          onClick={onMoveUp}
        >
          <ArrowUp aria-hidden="true" />
        </Button>
        <Button
          type="button"
          variant="outline"
          size="icon-xs"
          aria-label={`Move "${material.title}" down`}
          disabled={isLast || disabled}
          onClick={onMoveDown}
        >
          <ArrowDown aria-hidden="true" />
        </Button>

        <AlertDialog open={deleteOpen} onOpenChange={setDeleteOpen}>
          <AlertDialogTrigger
            render={
              <Button
                type="button"
                variant="destructive"
                size="icon-xs"
                aria-label={`Delete material "${material.title}"`}
                disabled={disabled}
              />
            }
          >
            <Trash2 aria-hidden="true" />
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Delete &ldquo;{material.title}&rdquo;?</AlertDialogTitle>
              <AlertDialogDescription>
                This permanently deletes the material. This action cannot be undone.
              </AlertDialogDescription>
            </AlertDialogHeader>
            {deleteErrorMessage ? (
              <Alert variant="destructive">
                <AlertDescription>{deleteErrorMessage}</AlertDescription>
              </Alert>
            ) : null}
            <AlertDialogFooter>
              <AlertDialogClose render={<Button type="button" variant="outline" />}>
                Cancel
              </AlertDialogClose>
              <Button
                type="button"
                variant="destructive"
                onClick={handleDelete}
                disabled={deleteMutation.isPending}
                aria-busy={deleteMutation.isPending}
              >
                {deleteMutation.isPending ? "Deleting…" : "Delete material"}
              </Button>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </div>
  );
}
