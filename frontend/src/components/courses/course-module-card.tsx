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
import { useDeleteCourseModule } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import { nextSequence, sortBySequence } from "@/lib/courses/reorder";
import { useKlassLessons, useReorderLessons, useUpdateKlassModule, type KlassModule, type TenantTimeZone } from "@/lib/api/klass-admin";
import { SessionFormDialog } from "@/components/courses/admin/session-form-dialog";

interface CourseModuleCardProps {
  courseId: string;
  module: KlassModule;
  timeZone: TenantTimeZone;
  dashboardHref: string;
  isFirst: boolean;
  isLast: boolean;
  /** True while the course's atomic module-order request is in flight. */
  reorderBusy: boolean;
  onMoveUp: () => void;
  onMoveDown: () => void;
}

/**
 * Single module card: metadata-preserving rename, accessible atomic reorder,
 * confirmed delete, and an always-open session list. Add/edit shares the full
 * Session form, while the list keeps supplementary materials and videos visible.
 */
export function CourseModuleCard({
  courseId,
  module: courseModule,
  isFirst,
  isLast,
  reorderBusy,
  onMoveUp,
  onMoveDown,
  timeZone,
  dashboardHref,
}: CourseModuleCardProps) {
  const [deleteOpen, setDeleteOpen] = useState(false);
  const renameMutation = useUpdateKlassModule(courseId);
  const deleteMutation = useDeleteCourseModule(courseId);

  const lessonsQuery = useKlassLessons(courseId, courseModule.id);
  const moveLessonMutation = useReorderLessons(courseId, courseModule.id);
  const [createOpen, setCreateOpen] = useState(false);
  const [createKey, setCreateKey] = useState(0);

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
      const reordered = [...sorted];
      const [moved] = reordered.splice(index, 1);
      reordered.splice(targetIndex, 0, moved);
      await moveLessonMutation.mutateAsync(reordered.map((item) => item.id));
    } catch (error) {
      setLessonReorderError(
        isApiClientError(error) ? error.message : "Couldn't reorder sessions. Please try again."
      );
    } finally {
      // The server's complete ordering remains authoritative after a failed request.
      await lessonsQuery.refetch();
      setLessonReorderBusyIds(new Set());
    }
  };

  const moduleDisabled = reorderBusy || deleteMutation.isPending || renameMutation.isPending;

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
                body: { title, sequence: courseModule.sequence, price: courseModule.price == null ? null : String(courseModule.price) },
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
                  This permanently deletes the module and all of its sessions. This action cannot
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
          Sessions
        </h3>

        <QueryStateBoundary
          query={lessonsQuery}
          loadingLabel={`Loading sessions for "${courseModule.title}"…`}
          loginPath="/login"
          permissionDenied={{ dashboardHref }}
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
                  {lessonReorderBusyIds.size > 0 ? "Reordering sessions…" : ""}
                </span>

                {sortedLessons.length === 0 ? (
                  <EmptyState
                    title="No sessions yet"
                    description={`Add the first session to "${courseModule.title}" to start building its content.`}
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
                          timeZone={timeZone}
                        />
                      </li>
                    ))}
                  </ol>
                )}

                <div className="rounded-md border border-dashed border-border p-3">
                  <Button type="button" variant="outline" disabled={lessonReorderBusyIds.size > 0 || moduleDisabled} onClick={() => { setCreateKey((key) => key + 1); setCreateOpen(true); }}>
                    Add session
                  </Button>
                  {createOpen ? (
                    <SessionFormDialog key={createKey} courseId={courseId} moduleId={courseModule.id} open onOpenChange={setCreateOpen} lesson={null} nextSequence={nextSequence(lessonsQuery.data ?? [])} timeZone={timeZone} onSaved={() => setCreateOpen(false)} />
                  ) : null}
                </div>
              </div>
            );
          }}
        </QueryStateBoundary>
      </div>
    </div>
  );
}
