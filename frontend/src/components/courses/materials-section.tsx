"use client";

import { useMemo, useState } from "react";
import { Library, Video } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { MaterialRow } from "@/components/courses/material-row";
import { MediaPickerSheet } from "@/components/media/media-picker-sheet";
import { LessonMediaAttachments } from "@/components/media/lesson-media-attachments";
import { LibraryFilePickerSheet } from "@/components/courses/library-file-picker-sheet";
import { Badge } from "@/components/ui/badge";
import { useMaterials, useUpdateMaterial } from "@/lib/api/materials";
import { useLessonMedia } from "@/lib/api/media";
import { isApiClientError } from "@/lib/api/error";
import { useAuth } from "@/lib/auth/auth-context";
import { tenantDashboardPath } from "@/lib/auth/dashboard-paths";
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
 * fetch/reorder/delete (new resources come from the session dialog or a library picker), mirroring `CourseModuleCard`'s lessons block
 * (`handleMoveLesson`) exactly but via the materials-specific reorder helper
 * that also resends `visibility` on every PATCH.
 */
export function MaterialsSection({ courseId, moduleId, lessonId, lessonTitle }: MaterialsSectionProps) {
  const { session } = useAuth();
  const dashboardHref = tenantDashboardPath(session?.role ?? null) ?? "/login";
  const materialsQuery = useMaterials(courseId, moduleId, lessonId);
  const lessonMediaQuery = useLessonMedia(courseId, lessonId);
  const moveMaterialMutation = useUpdateMaterial(courseId, moduleId, lessonId);

  const [pickerOpen, setPickerOpen] = useState(false);
  const [libraryPickerOpen, setLibraryPickerOpen] = useState(false);
  const [libraryNotice, setLibraryNotice] = useState<string | null>(null);
  // Library files already on this lesson (a UX hint only; the server answers 409 for a duplicate).
  const attachedMediaFileIds = useMemo(
    () =>
      new Set(
        (materialsQuery.data ?? [])
          .map((material) => material.mediaFileId)
          .filter((id): id is string => typeof id === "string" && id.length > 0)
      ),
    [materialsQuery.data]
  );
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
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Materials</h4>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="inline-flex items-center gap-1.5 rounded-md border border-border px-2.5 py-1.5 text-xs font-medium text-foreground hover:bg-muted focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            onClick={() => setPickerOpen(true)}
            disabled={reorderBusyIds.size > 0}
          >
            <Video aria-hidden="true" className="size-3.5" />
            Choose from Media Library
          </button>
          <button
            type="button"
            className="inline-flex items-center gap-1.5 rounded-md border border-border px-2.5 py-1.5 text-xs font-medium text-foreground hover:bg-muted focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            onClick={() => {
              setLibraryNotice(null);
              setLibraryPickerOpen(true);
            }}
            disabled={reorderBusyIds.size > 0}
            data-testid="materials-add-from-library"
          >
            <Library aria-hidden="true" className="size-3.5" />
            Add from library
          </button>
        </div>
      </div>

      <LibraryFilePickerSheet
        open={libraryPickerOpen}
        onOpenChange={setLibraryPickerOpen}
        courseId={courseId}
        moduleId={moduleId}
        lessonId={lessonId}
        attachedMediaFileIds={attachedMediaFileIds}
        dashboardHref={dashboardHref}
        onAttached={(file) => setLibraryNotice(`Added "${file.title}" to this session.`)}
        onRequestVideos={() => {
          setLibraryPickerOpen(false);
          setPickerOpen(true);
        }}
      />
      <span role="status" aria-live="polite" className="sr-only" data-testid="materials-library-status">
        {libraryNotice ?? ""}
      </span>

      <MediaPickerSheet
        open={pickerOpen}
        onOpenChange={setPickerOpen}
        courseId={courseId}
        lessonId={lessonId}
        attached={lessonMediaQuery.data ?? []}
        onAttached={() => {
          void lessonMediaQuery.refetch();
          void materialsQuery.refetch();
        }}
      />

      <LessonMediaAttachments courseId={courseId} lessonId={lessonId} />

      <QueryStateBoundary
        query={materialsQuery}
        loadingLabel={`Loading materials for "${lessonTitle}"…`}
        loginPath="/login"
        permissionDenied={{ dashboardHref }}
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
                  description="Add resources from Edit session, or use Choose from Media Library / Add from library above."
                  className="py-6"
                />
              ) : (
                <ol className="flex flex-col gap-2">
                  {sorted.map((material, index) => (
                    <li
                      key={material.id}
                      data-testid={material.mediaFileId ? "material-row-library" : undefined}
                      className="flex flex-col gap-1"
                    >
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
                      {material.mediaFileId ? (
                        <p className="flex flex-wrap items-center gap-1.5 px-1 text-xs text-muted-foreground">
                          <Badge variant="secondary" data-testid="material-library-badge">
                            <Library aria-hidden="true" />
                            Library
                          </Badge>
                          Deleting removes it from this session only; it stays in the Media Library.
                        </p>
                      ) : null}
                    </li>
                  ))}
                </ol>
              )}
            </div>
          );
        }}
      </QueryStateBoundary>
    </div>
  );
}
