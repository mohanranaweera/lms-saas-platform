"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { LoadingState } from "@/components/states/loading-state";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import {
  formatFileSize,
  useMaterialDownloadUrl,
  useMaterials,
  type MaterialResponse,
} from "@/lib/api/materials";
import { isApiClientError } from "@/lib/api/error";

/**
 * Student "Lesson/Material View" —
 * `student/courses/[courseId]/modules/[moduleId]/lessons/[lessonId]/materials`.
 * The plan's own draft route (`docs/plans/MVP-009 …md` §11) omitted
 * `moduleId`, which the real API requires as a path segment
 * (`/api/v1/courses/{courseId}/modules/{moduleId}/lessons/{lessonId}/materials`)
 * — this route's shape is corrected to match, mirroring the Teacher route
 * family's own nesting.
 *
 * `GET .../materials` is already filtered server-side to `VISIBLE`-only
 * materials for a Student caller (see `lib/api/materials.ts`'s doc comment)
 * — this page never re-filters or infers visibility.
 *
 * Branches on the query's real HTTP status manually (rather than
 * `QueryStateBoundary` as-is) because a 404 here needs its own calm,
 * non-alarming surface — `QueryStateBoundary`'s built-in branches only cover
 * unauthenticated/forbidden/generic-error. The anti-enumeration design
 * means every denial reason (cross-tenant lesson, wrong course, unpublished
 * course, hidden material, or a truly nonexistent id) returns the exact same
 * generic 404 — there is nothing to distinguish, so this screen renders
 * **fixed local copy**, never `error.message`, so its safety doesn't
 * silently depend on the backend's message text never changing.
 *
 * A 403 is folded into that exact same fixed-copy branch rather than
 * `PermissionDeniedState` (which renders the backend's raw `error.message`).
 * Traced against the backend, a Student's only action here is `VIEW`, and
 * every Student-role denial on this endpoint throws `NotFoundException`
 * (404) — a real 403 is not currently reachable — but *if* backend behavior
 * ever changed to produce one, it must not become a path that leaks
 * backend-controlled text into this anti-enumeration surface.
 */
export default function StudentLessonMaterialsPage() {
  const params = useParams<{ courseId: string; moduleId: string; lessonId: string }>();
  const { courseId, moduleId, lessonId } = params;
  const materialsQuery = useMaterials(courseId, moduleId, lessonId);

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Lesson materials</h1>
        <p className="text-sm text-muted-foreground">
          Files your teacher has shared for this lesson.
        </p>
      </div>

      <MaterialsBody
        courseId={courseId}
        moduleId={moduleId}
        lessonId={lessonId}
        query={materialsQuery}
      />
    </div>
  );
}

function MaterialsBody({
  courseId,
  moduleId,
  lessonId,
  query,
}: {
  courseId: string;
  moduleId: string;
  lessonId: string;
  query: ReturnType<typeof useMaterials>;
}) {
  if (query.status === "pending") {
    return <LoadingState label="Loading materials…" />;
  }

  if (query.status === "error") {
    const error = query.error;
    const status = isApiClientError(error) ? error.status : undefined;

    if (status === 403 || status === 404) {
      return (
        <EmptyState
          title="This material isn't available"
          description="It may have been removed, or you may not have access to it."
        />
      );
    }

    return (
      <ErrorState
        message={isApiClientError(error) ? error.message : "Something went wrong. Please try again."}
        code={isApiClientError(error) ? error.code : undefined}
        onRetry={() => query.refetch()}
      />
    );
  }

  const materials = query.data ?? [];

  if (materials.length === 0) {
    return (
      <EmptyState
        title="No materials yet"
        description="Your teacher hasn't added any materials to this lesson yet. Check back later."
      />
    );
  }

  return (
    <ol className="flex flex-col gap-2">
      {materials.map((material) => (
        <StudentMaterialRow
          key={material.id}
          courseId={courseId}
          moduleId={moduleId}
          lessonId={lessonId}
          material={material}
        />
      ))}
    </ol>
  );
}

function materialTypeLabel(mimeType: string): string {
  if (mimeType === "application/pdf") return "PDF";
  if (mimeType.startsWith("image/")) return "Image";
  if (mimeType === "text/plain") return "Text";
  return mimeType;
}

function StudentMaterialRow({
  courseId,
  moduleId,
  lessonId,
  material,
}: {
  courseId: string;
  moduleId: string;
  lessonId: string;
  material: MaterialResponse;
}) {
  const downloadUrlMutation = useMaterialDownloadUrl(courseId, moduleId, lessonId);
  const [viewError, setViewError] = useState<string | null>(null);

  const handleView = async () => {
    setViewError(null);
    try {
      const result = await downloadUrlMutation.mutateAsync(material.id);
      window.open(result.url, "_blank", "noopener,noreferrer");
    } catch {
      // Deliberately fixed, generic copy — never the raw `error.message` —
      // for the same anti-enumeration reason as `MaterialsBody`'s 404
      // branch above: this per-item download-url fetch is independently
      // re-verified server-side on every call (module plan's fetch-time
      // visibility rule), and a Student must never be able to distinguish
      // "hidden," "no longer available," or "never had access" from this
      // response either.
      setViewError("This material isn't available. It may have been removed, or you may not have access to it.");
    }
  };

  return (
    <li className="flex flex-col gap-2 rounded-md border border-border p-3 sm:flex-row sm:items-center sm:justify-between">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2">
          <p className="truncate text-sm font-medium text-foreground">{material.title}</p>
          <Badge variant="outline">{materialTypeLabel(material.mimeType)}</Badge>
        </div>
        <p className="text-xs text-muted-foreground">
          {material.originalFilename} &middot; {formatFileSize(material.sizeBytes)}
        </p>
        {viewError ? (
          <p role="alert" className="mt-1 text-xs text-destructive">
            {viewError}
          </p>
        ) : null}
      </div>
      <Button
        type="button"
        variant="outline"
        size="sm"
        className="shrink-0"
        onClick={handleView}
        disabled={downloadUrlMutation.isPending}
        aria-busy={downloadUrlMutation.isPending}
      >
        {downloadUrlMutation.isPending ? "Opening…" : "View"}
      </Button>
    </li>
  );
}
