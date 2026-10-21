"use client";

import { useState } from "react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { CourseModuleCard } from "@/components/courses/course-module-card";
import { TitleInlineForm } from "@/components/courses/title-inline-form";
import { useCreateCourseModule } from "@/lib/api/courses";
import { useKlassModules, useReorderModules, useTenantTimeZone } from "@/lib/api/klass-admin";
import { useAuth } from "@/lib/auth/auth-context";
import { tenantDashboardPath } from "@/lib/auth/dashboard-paths";
import { isApiClientError } from "@/lib/api/error";
import { nextSequence, sortBySequence } from "@/lib/courses/reorder";

/**
 * Shared Module & Session editor for the Teacher and Tenant Admin course
 * content routes. It renders each module as a `CourseModuleCard`, whose
 * sessions use the same full Session form as the Klass table. Ordering is
 * persisted with the dedicated atomic module/session order endpoints.
 */
export function ModuleLessonEditor({ courseId }: { courseId: string }) {
  const modulesQuery = useKlassModules(courseId);
  const timeZone = useTenantTimeZone();
  const { session } = useAuth();
  const dashboardHref = tenantDashboardPath(session?.role ?? null) ?? "/login";
  const createModuleMutation = useCreateCourseModule(courseId);
  const moveModuleMutation = useReorderModules(courseId);

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
      const reordered = [...sorted];
      const [moved] = reordered.splice(index, 1);
      reordered.splice(targetIndex, 0, moved);
      await moveModuleMutation.mutateAsync(reordered.map((item) => item.id));
    } catch (error) {
      setReorderError(
        isApiClientError(error) ? error.message : "Couldn't reorder modules. Please try again."
      );
    } finally {
      // Refetch even on failure: the server's complete ordering is authoritative.
      await modulesQuery.refetch();
      setReorderBusyIds(new Set());
    }
  };

  return (
    <QueryStateBoundary
      query={modulesQuery}
      loadingLabel="Loading modules…"
      loginPath="/login"
      permissionDenied={{ dashboardHref }}
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
                description="Add your first module to start structuring this course. Each module can then hold its own ordered list of sessions."
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
                      timeZone={timeZone}
                      dashboardHref={dashboardHref}
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
