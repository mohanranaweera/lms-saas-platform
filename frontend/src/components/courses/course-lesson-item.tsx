"use client";

import { useState } from "react";
import { ArrowDown, ArrowUp, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
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
import { TitleInlineForm } from "@/components/courses/title-inline-form";
import { MaterialsSection } from "@/components/courses/materials-section";
import {
  useDeleteCourseLesson,
  useUpdateCourseLesson,
  type CourseLessonResponse,
} from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";

interface CourseLessonItemProps {
  courseId: string;
  moduleId: string;
  lesson: CourseLessonResponse;
  isFirst: boolean;
  isLast: boolean;
  /**
   * True while *any* lesson reorder is in flight within this lesson's own
   * module (not just the two rows actually moving) — every Move Up/Down and
   * Delete button in this module's lesson list must be disabled during the
   * whole reorder dance, not only the two rows involved.
   */
  reorderBusy: boolean;
  onMoveUp: () => void;
  onMoveDown: () => void;
}

/**
 * Single lesson row: inline rename form, keyboard-reachable Move Up/Down
 * (per module plan §11 — the explicit alternative to any drag-and-drop
 * reordering), and a delete action confirmed via `AlertDialog`, copying the
 * confirmation pattern from `course-delete-action.tsx`.
 */
export function CourseLessonItem({
  courseId,
  moduleId,
  lesson,
  isFirst,
  isLast,
  reorderBusy,
  onMoveUp,
  onMoveDown,
}: CourseLessonItemProps) {
  const [deleteOpen, setDeleteOpen] = useState(false);
  const renameMutation = useUpdateCourseLesson(courseId, moduleId);
  const deleteMutation = useDeleteCourseLesson(courseId, moduleId);

  const deleteErrorMessage = deleteMutation.isError
    ? isApiClientError(deleteMutation.error)
      ? deleteMutation.error.message
      : "An unexpected error occurred. Please try again."
    : null;

  const handleDelete = async () => {
    try {
      await deleteMutation.mutateAsync(lesson.id);
      setDeleteOpen(false);
    } catch {
      // Surfaced via deleteErrorMessage below; keep the dialog open so the
      // teacher can see the failure and retry or cancel.
    }
  };

  const disabled = reorderBusy || deleteMutation.isPending;

  return (
    <div className="flex flex-col gap-3 rounded-md border border-border/70 bg-muted/20 p-3">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex-1">
          <TitleInlineForm
            idPrefix={`lesson-${lesson.id}`}
            label="Lesson title"
            initialTitle={lesson.title}
            submitLabel="Save"
            pendingLabel={`Saving lesson "${lesson.title}"…`}
            disabled={disabled}
            onSubmit={async (title) => {
              await renameMutation.mutateAsync({
                lessonId: lesson.id,
                body: { title, sequence: lesson.sequence },
              });
            }}
          />
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <Button
            type="button"
            variant="outline"
            size="icon-xs"
            aria-label={`Move "${lesson.title}" up`}
            disabled={isFirst || disabled}
            onClick={onMoveUp}
          >
            <ArrowUp aria-hidden="true" />
          </Button>
          <Button
            type="button"
            variant="outline"
            size="icon-xs"
            aria-label={`Move "${lesson.title}" down`}
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
                  aria-label={`Delete lesson "${lesson.title}"`}
                  disabled={disabled}
                />
              }
            >
              <Trash2 aria-hidden="true" />
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete &ldquo;{lesson.title}&rdquo;?</AlertDialogTitle>
                <AlertDialogDescription>
                  This permanently deletes the lesson. This action cannot be undone.
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
                  {deleteMutation.isPending ? "Deleting…" : "Delete lesson"}
                </Button>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </div>
      </div>

      <MaterialsSection
        courseId={courseId}
        moduleId={moduleId}
        lessonId={lesson.id}
        lessonTitle={lesson.title}
      />
    </div>
  );
}
