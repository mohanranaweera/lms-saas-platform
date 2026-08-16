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
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { TitleInlineForm } from "@/components/courses/title-inline-form";
import { CourseLessonItem } from "@/components/courses/course-lesson-item";
import {
  useCourseLessons,
  useCreateCourseLesson,
  useDeleteCourseModule,
  useUpdateCourseLesson,
  useUpdateCourseModule,
  type CourseModuleResponse,
} from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import { nextSequence, reorderAdjacent, sortBySequence } from "@/lib/courses/reorder";

interface CourseModuleCardProps {
  courseId: string;
  module: CourseModuleResponse;
  isFirst: boolean;
  isLast: boolean;
  /**
   * True while *any* module reorder is in flight in this course's module
   * list (not just the two rows actually moving) — every Move Up/Down and
   * Delete button in the list must be disabled during the whole reorder
   * dance, not only the two rows involved, since a second in-flight PATCH
   * against a still-shifting sequence would otherwise be racy.
   */
  reorderBusy: boolean;
  onMoveUp: () => void;
  onMoveDown: () => void;
}

/**
 * Single module card: inline rename form, keyboard-reachable Move Up/Down,
 * a delete action (confirmed via `AlertDialog`, copying
 * `course-delete-action.tsx`'s pattern), and this module's own always-open
 * lesson list (its own fetch/create/reorder/delete — see module plan §11,
 * "always-open" chosen over expand/collapse since it stays fully
 * keyboard-navigable either way and lesson lists are expected to be short).
 */
export function CourseModuleCard({
  courseId,
  module: courseModule,
  isFirst,
  isLast,
  reorderBusy,
  onMoveUp,
  onMoveDown,
}: CourseModuleCardProps) {
  const [deleteOpen, setDeleteOpen] = useState(false);
  const renameMutation = useUpdateCourseModule(courseId);
  const deleteMutation = useDeleteCourseModule(courseId);

  const lessonsQuery = useCourseLessons(courseId, courseModule.id);
  const createLessonMutation = useCreateCourseLesson(courseId, courseModule.id);
  const moveLessonMutation = useUpdateCourseLesson(courseId, courseModule.id);

  const [lessonReorderBusyIds, setLessonReorderBusyIds] = useState<Set<string>>(new Set());
  const [lessonReorderError, setLessonReorderError] = useState<string | null>(null);

  const deleteErrorMessage = deleteMutation.isError
    ? isApiClientError(deleteMutation.error)
      ? deleteMutation.error.message
      : "An unexpected error occurred. Please try again."
    : null;

  const handleDelete = async () => {
    try {
      await deleteMutation.mutateAsync(courseModule.id);
      setDeleteOpen(false);
    } catch {
      // Surfaced via deleteErrorMessage below; keep the dialog open so the
      // teacher can see the failure and retry or cancel.
    }
  };

  const handleMoveLesson = async (lessonId: string, direction: "up" | "down") => {
    const sorted = sortBySequence(lessonsQuery.data ?? []);
    const index = sorted.findIndex((lesson) => lesson.id === lessonId);
    if (index === -1) return;
    const targetIndex = direction === "up" ? index - 1 : index + 1;
    if (targetIndex < 0 || targetIndex >= sorted.length) return;

    setLessonReorderBusyIds(new Set([sorted[index].id, sorted[targetIndex].id]));
    setLessonReorderError(null);
    try {
      await reorderAdjacent(sorted, lessonId, direction, (id, body) =>
        moveLessonMutation.mutateAsync({ lessonId: id, body })
      );
    } catch (error) {
      setLessonReorderError(
        isApiClientError(error) ? error.message : "Couldn't reorder lessons. Please try again."
      );
    } finally {
      // Refetch regardless of outcome: if the 2nd/3rd PATCH failed after the
      // 1st succeeded, the list is transiently "off by one" server-side —
      // only a refetch (not local state) reflects reality.
      await lessonsQuery.refetch();
      setLessonReorderBusyIds(new Set());
    }
  };

  const moduleDisabled = reorderBusy || deleteMutation.isPending;

  return (
    <div className="flex flex-col gap-4 rounded-lg border border-border p-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex-1">
          <TitleInlineForm
            idPrefix={`module-${courseModule.id}`}
            label="Module title"
            initialTitle={courseModule.title}
            submitLabel="Save"
            pendingLabel={`Saving module "${courseModule.title}"…`}
            disabled={moduleDisabled}
            onSubmit={async (title) => {
              await renameMutation.mutateAsync({
                moduleId: courseModule.id,
                body: { title, sequence: courseModule.sequence },
              });
            }}
          />
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <Button
            type="button"
            variant="outline"
            size="icon-sm"
            aria-label={`Move "${courseModule.title}" up`}
            disabled={isFirst || moduleDisabled}
            onClick={onMoveUp}
          >
            <ArrowUp aria-hidden="true" />
          </Button>
          <Button
            type="button"
            variant="outline"
            size="icon-sm"
            aria-label={`Move "${courseModule.title}" down`}
            disabled={isLast || moduleDisabled}
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
                  size="icon-sm"
                  aria-label={`Delete module "${courseModule.title}"`}
                  disabled={moduleDisabled}
                />
              }
            >
              <Trash2 aria-hidden="true" />
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete &ldquo;{courseModule.title}&rdquo;?</AlertDialogTitle>
                <AlertDialogDescription>
                  This permanently deletes the module and all of its lessons. This action cannot
                  be undone.
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
                  {deleteMutation.isPending ? "Deleting…" : "Delete module"}
                </Button>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </div>
      </div>

      <div className="flex flex-col gap-3 border-t border-border pt-4">
        <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
          Lessons
        </h3>

        <QueryStateBoundary
          query={lessonsQuery}
          loadingLabel={`Loading lessons for "${courseModule.title}"…`}
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
        >
          {(lessons) => {
            const sortedLessons = sortBySequence(lessons);
            return (
              <div className="flex flex-col gap-3">
                {lessonReorderError ? (
                  <ErrorState
                    message={lessonReorderError}
                    onRetry={() => setLessonReorderError(null)}
                  />
                ) : null}
                <span role="status" aria-live="polite" className="sr-only">
                  {lessonReorderBusyIds.size > 0 ? "Reordering lessons…" : ""}
                </span>

                {sortedLessons.length === 0 ? (
                  <EmptyState
                    title="No lessons yet"
                    description={`Add the first lesson to "${courseModule.title}" to start building its content.`}
                    className="py-6"
                  />
                ) : (
                  <ol className="flex flex-col gap-2">
                    {sortedLessons.map((lesson, index) => (
                      <li key={lesson.id}>
                        <CourseLessonItem
                          courseId={courseId}
                          moduleId={courseModule.id}
                          lesson={lesson}
                          isFirst={index === 0}
                          isLast={index === sortedLessons.length - 1}
                          reorderBusy={lessonReorderBusyIds.size > 0}
                          onMoveUp={() => handleMoveLesson(lesson.id, "up")}
                          onMoveDown={() => handleMoveLesson(lesson.id, "down")}
                        />
                      </li>
                    ))}
                  </ol>
                )}

                <div className="rounded-md border border-dashed border-border p-3">
                  <h4 className="mb-2 text-xs font-medium text-foreground">Add lesson</h4>
                  <TitleInlineForm
                    idPrefix={`add-lesson-${courseModule.id}`}
                    label="Lesson title"
                    submitLabel="Add lesson"
                    pendingLabel="Adding lesson…"
                    disabled={lessonReorderBusyIds.size > 0 || moduleDisabled}
                    clearOnSuccess
                    onSubmit={async (title) => {
                      await createLessonMutation.mutateAsync({
                        title,
                        sequence: nextSequence(lessonsQuery.data ?? []),
                      });
                    }}
                  />
                </div>
              </div>
            );
          }}
        </QueryStateBoundary>
      </div>
    </div>
  );
}
