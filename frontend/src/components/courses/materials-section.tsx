"use client";

import { useState } from "react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { MaterialRow } from "@/components/courses/material-row";
import { MaterialUploadForm } from "@/components/courses/material-upload-form";
import { useMaterials, useUpdateMaterial } from "@/lib/api/materials";
import { isApiClientError } from "@/lib/api/error";
import { reorderAdjacentMaterial, sortBySequence } from "@/lib/courses/material-reorder";

interface MaterialsSectionProps {
  courseId: string;
  moduleId: string;
  lessonId: string;
  lessonTitle: string;
}

/**
 * Nested inside `CourseLessonItem`, the same way `CourseModuleCard` nests
 * `CourseLessonItem` — always-visible (not collapsed), owning its own
 * fetch/upload/reorder/delete, mirroring `CourseModuleCard`'s lessons block
 * (`handleMoveLesson`) exactly but via the materials-specific reorder helper
 * that also resends `visibility` on every PATCH.
 */
export function MaterialsSection({ courseId, moduleId, lessonId, lessonTitle }: MaterialsSectionProps) {
  const materialsQuery = useMaterials(courseId, moduleId, lessonId);
  const moveMaterialMutation = useUpdateMaterial(courseId, moduleId, lessonId);

  const [reorderBusyIds, setReorderBusyIds] = useState<Set<string>>(new Set());
  const [reorderError, setReorderError] = useState<string | null>(null);

  const handleMoveMaterial = async (materialId: string, direction: "up" | "down") => {
    const sorted = sortBySequence(materialsQuery.data ?? []);
    const index = sorted.findIndex((material) => material.id === materialId);
    if (index === -1) return;
    const targetIndex = direction === "up" ? index - 1 : index + 1;
    if (targetIndex < 0 || targetIndex >= sorted.length) return;

    setReorderBusyIds(new Set([sorted[index].id, sorted[targetIndex].id]));
    setReorderError(null);
    try {
      await reorderAdjacentMaterial(sorted, materialId, direction, (id, body) =>
        moveMaterialMutation.mutateAsync({ materialId: id, body })
      );
    } catch (error) {
      setReorderError(
        isApiClientError(error) ? error.message : "Couldn't reorder materials. Please try again."
      );
    } finally {
      // Refetch regardless of outcome: if the 2nd/3rd PATCH failed after the
      // 1st succeeded, the list is transiently "off by one" server-side —
      // only a refetch (not local state) reflects reality.
      await materialsQuery.refetch();
      setReorderBusyIds(new Set());
    }
  };

  return (
    <div className="flex flex-col gap-3 border-t border-border pt-3">
      <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        Materials
      </h4>

      <QueryStateBoundary
        query={materialsQuery}
        loadingLabel={`Loading materials for "${lessonTitle}"…`}
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
      >
        {(materials) => {
          const sorted = sortBySequence(materials);
          return (
            <div className="flex flex-col gap-3">
              {reorderError ? (
                <ErrorState message={reorderError} onRetry={() => setReorderError(null)} />
              ) : null}
              <span role="status" aria-live="polite" className="sr-only">
                {reorderBusyIds.size > 0 ? "Reordering materials…" : ""}
              </span>

              {sorted.length === 0 ? (
                <EmptyState
                  title="No materials added to this lesson yet"
                  description="Use the upload form below to add a PDF, image, or text file to this lesson."
                  className="py-6"
                />
              ) : (
                <ol className="flex flex-col gap-2">
                  {sorted.map((material, index) => (
                    <li key={material.id}>
                      <MaterialRow
                        courseId={courseId}
                        moduleId={moduleId}
                        lessonId={lessonId}
                        material={material}
                        isFirst={index === 0}
                        isLast={index === sorted.length - 1}
                        reorderBusy={reorderBusyIds.size > 0}
                        onMoveUp={() => handleMoveMaterial(material.id, "up")}
                        onMoveDown={() => handleMoveMaterial(material.id, "down")}
                      />
                    </li>
                  ))}
                </ol>
              )}

              <MaterialUploadForm
                courseId={courseId}
                moduleId={moduleId}
                lessonId={lessonId}
                disabled={reorderBusyIds.size > 0}
              />
            </div>
          );
        }}
      </QueryStateBoundary>
    </div>
  );
}
