"use client";

import { useState } from "react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { CourseModuleCard } from "@/components/courses/course-module-card";
import { TitleInlineForm } from "@/components/courses/title-inline-form";
import { useCourseModules, useCreateCourseModule, useUpdateCourseModule } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import { nextSequence, reorderAdjacent, sortBySequence } from "@/lib/courses/reorder";

/**
 * Teacher Module & Lesson Editor — top-level orchestrator for
 * `teacher/courses/[courseId]/modules`. Fetches this course's modules
 * (`GET /api/v1/courses/{courseId}/modules`, **not** guaranteed ordered by
 * the backend — sorted client-side by `sequence` below), renders each as a
 * `CourseModuleCard` (which owns its own lessons), and owns the "Add
 * module" affordance plus the module-list reorder (Move Up/Down) dance.
 */
export function ModuleLessonEditor({ courseId }: { courseId: string }) {
  const modulesQuery = useCourseModules(courseId);
  const createModuleMutation = useCreateCourseModule(courseId);
  const moveModuleMutation = useUpdateCourseModule(courseId);

  const [reorderBusyIds, setReorderBusyIds] = useState<Set<string>>(new Set());
  const [reorderError, setReorderError] = useState<string | null>(null);

  const handleMoveModule = async (moduleId: string, direction: "up" | "down") => {
    const sorted = sortBySequence(modulesQuery.data ?? []);
    const index = sorted.findIndex((module) => module.id === moduleId);
    if (index === -1) return;
    const targetIndex = direction === "up" ? index - 1 : index + 1;
    if (targetIndex < 0 || targetIndex >= sorted.length) return;

    setReorderBusyIds(new Set([sorted[index].id, sorted[targetIndex].id]));
    setReorderError(null);
    try {
      await reorderAdjacent(sorted, moduleId, direction, (id, body) =>
        moveModuleMutation.mutateAsync({ moduleId: id, body })
      );
    } catch (error) {
      setReorderError(
        isApiClientError(error) ? error.message : "Couldn't reorder modules. Please try again."
      );
    } finally {
      // Refetch regardless of outcome: if the 2nd/3rd PATCH failed after the
      // 1st succeeded, the list is transiently "off by one" server-side —
      // only a refetch (not local state) reflects reality.
      await modulesQuery.refetch();
      setReorderBusyIds(new Set());
    }
  };

  return (
    <QueryStateBoundary
      query={modulesQuery}
      loadingLabel="Loading modules…"
      loginPath="/login"
      permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
    >
      {(modules) => {
        const sorted = sortBySequence(modules);
        return (
          <div className="flex flex-col gap-4">
            {reorderError ? (
              <ErrorState message={reorderError} onRetry={() => setReorderError(null)} />
            ) : null}
            <span role="status" aria-live="polite" className="sr-only">
              {reorderBusyIds.size > 0 ? "Reordering modules…" : ""}
            </span>

            {sorted.length === 0 ? (
              <EmptyState
                title="No modules yet"
                description="Add your first module to start structuring this course. Each module can then hold its own ordered list of lessons."
              />
            ) : (
              <ol className="flex flex-col gap-4">
                {sorted.map((module, index) => (
                  <li key={module.id}>
                    <CourseModuleCard
                      courseId={courseId}
                      module={module}
                      isFirst={index === 0}
                      isLast={index === sorted.length - 1}
                      reorderBusy={reorderBusyIds.size > 0}
                      onMoveUp={() => handleMoveModule(module.id, "up")}
                      onMoveDown={() => handleMoveModule(module.id, "down")}
                    />
                  </li>
                ))}
              </ol>
            )}

            <div className="rounded-lg border border-dashed border-border p-4">
              <h2 className="mb-2 text-sm font-medium text-foreground">Add module</h2>
              <TitleInlineForm
                idPrefix={`add-module-${courseId}`}
                label="Module title"
                submitLabel="Add module"
                pendingLabel="Adding module…"
                disabled={reorderBusyIds.size > 0}
                clearOnSuccess
                onSubmit={async (title) => {
                  await createModuleMutation.mutateAsync({
                    title,
                    sequence: nextSequence(modulesQuery.data ?? []),
                  });
                }}
              />
            </div>
          </div>
        );
      }}
    </QueryStateBoundary>
  );
}
