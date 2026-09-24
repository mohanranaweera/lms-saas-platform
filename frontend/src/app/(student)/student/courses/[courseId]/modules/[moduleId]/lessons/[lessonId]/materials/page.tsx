"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Accordion } from "@/components/ui/accordion";
import { LoadingState } from "@/components/states/loading-state";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { SecureVideoPlayer } from "@/components/courses/secure-video-player";
import {
  formatFileSize,
  materialDownloadErrorMessage,
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
 *
 * Wave 5 (plan §4/§5) extends per-item rendering by `materialType`: `LINK`
 * opens `externalUrl` in a new tab (fetched fresh through the download-url
 * endpoint, never the list response's own `externalUrl` field directly,
 * since the availability/limit guards only run on that endpoint); `NOTE`
 * renders `noteContent` inline as plain, whitespace-preserving text (never
 * as HTML/markdown — it isn't meant to be rich text); `VIDEO`/`RECORDING`
 * renders the new `SecureVideoPlayer`, mounted only once the Student expands
 * an "Open video" accordion (never eagerly for every video material in the
 * list, so a playback session is issued only for a video the Student
 * actually opens — see `secure-video-player.tsx`'s own doc comment for this
 * same convention on the Teacher side). Uploaded-file/`LINK` download-url
 * failures now branch on `error.code` via the shared
 * `materialDownloadErrorMessage` helper for the three type-dependent 403s
 * (not-yet-available/expired/download-limit-reached), falling back to this
 * page's pre-existing fixed anti-enumeration copy for anything else (a
 * generic 403/404) — see that helper's own doc comment.
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

const MATERIAL_TYPE_LABELS: Record<MaterialResponse["materialType"], string> = {
  PDF: "PDF",
  IMAGE: "Image",
  DOCUMENT: "Document",
  OTHER: "File",
  LINK: "Link",
  NOTE: "Note",
  VIDEO: "Video",
  RECORDING: "Recording",
};

function materialTypeLabel(material: MaterialResponse): string {
  if (material.materialType === "OTHER" && material.mimeType) {
    if (material.mimeType === "application/pdf") return "PDF";
    if (material.mimeType.startsWith("image/")) return "Image";
    if (material.mimeType === "text/plain") return "Text";
  }
  return MATERIAL_TYPE_LABELS[material.materialType];
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
  // Controlled so `SecureVideoPlayer` mounts (and issues a playback session)
  // only once the Student actually expands "Open video" — see
  // `material-row.tsx`'s identical pattern/doc comment for why `Accordion`'s
  // own internal state isn't enough on its own.
  const [videoOpen, setVideoOpen] = useState(false);

  const ANTI_ENUMERATION_FALLBACK =
    "This material isn't available. It may have been removed, or you may not have access to it.";

  const handleOpen = async () => {
    setViewError(null);
    try {
      const result = await downloadUrlMutation.mutateAsync(material.id);
      window.open(result.url, "_blank", "noopener,noreferrer");
    } catch (error) {
      setViewError(materialDownloadErrorMessage(error, ANTI_ENUMERATION_FALLBACK));
    }
  };

  return (
    <li className="flex flex-col gap-2 rounded-md border border-border p-3">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <p className="truncate text-sm font-medium text-foreground">{material.title}</p>
            <Badge variant="outline">{materialTypeLabel(material)}</Badge>
          </div>
          {material.materialType !== "LINK" && material.materialType !== "NOTE" ? (
            <p className="text-xs text-muted-foreground">
              {material.originalFilename} &middot; {formatFileSize(material.sizeBytes)}
            </p>
          ) : null}
          {viewError ? (
            <p role="alert" className="mt-1 text-xs text-destructive">
              {viewError}
            </p>
          ) : null}
        </div>

        {material.materialType !== "NOTE" &&
        material.materialType !== "VIDEO" &&
        material.materialType !== "RECORDING" ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            className="shrink-0"
            onClick={handleOpen}
            disabled={downloadUrlMutation.isPending}
            aria-busy={downloadUrlMutation.isPending}
          >
            {downloadUrlMutation.isPending
              ? "Opening…"
              : material.materialType === "LINK"
                ? "Open link"
                : "View"}
          </Button>
        ) : null}
      </div>

      {material.materialType === "NOTE" ? (
        <p className="whitespace-pre-wrap rounded-md border border-border/70 bg-muted/20 p-2 text-sm text-foreground">
          {material.noteContent}
        </p>
      ) : null}

      {(material.materialType === "VIDEO" || material.materialType === "RECORDING") &&
      material.videoAssetId ? (
        <Accordion title="Open video" open={videoOpen} onOpenChange={setVideoOpen}>
          {videoOpen ? (
            <SecureVideoPlayer videoAssetId={material.videoAssetId} title={material.title} />
          ) : null}
        </Accordion>
      ) : null}
    </li>
  );
}
