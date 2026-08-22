"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/states/error-state";
import { CourseStatusBadge } from "@/components/courses/course-status-badge";
import { usePublishCourse, useUnpublishCourse, type CourseResponse } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";

/**
 * Publish/Unpublish action, rendered on both the Teacher edit page and the
 * Tenant Admin course detail page. `publish` always sets status -> `PUBLIC`;
 * `unpublish` always sets status -> `DRAFT` (never `PRIVATE` — there is no
 * server-side path back to `PRIVATE` after creation), so the action is
 * labeled honestly rather than implying a "make private" capability that
 * doesn't exist.
 */
export function CourseVisibilityControl({ courseId, course }: { courseId: string; course: CourseResponse }) {
  const [pageError, setPageError] = useState<string | null>(null);
  const publish = usePublishCourse(courseId);
  const unpublish = useUnpublishCourse(courseId);

  const isPending = publish.isPending || unpublish.isPending;

  const handlePublish = async () => {
    setPageError(null);
    try {
      await publish.mutateAsync();
    } catch (error) {
      setPageError(isApiClientError(error) ? error.message : "An unexpected error occurred. Please try again.");
    }
  };

  const handleUnpublish = async () => {
    setPageError(null);
    try {
      await unpublish.mutateAsync();
    } catch (error) {
      setPageError(isApiClientError(error) ? error.message : "An unexpected error occurred. Please try again.");
    }
  };

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="text-sm font-medium text-foreground">Visibility</h3>
          <p className="text-xs text-muted-foreground">Current status:</p>
        </div>
        <CourseStatusBadge status={course.status} />
      </div>

      {pageError ? <ErrorState message={pageError} onRetry={() => setPageError(null)} /> : null}

      <span role="status" aria-live="polite" className="sr-only">
        {isPending ? "Updating course visibility…" : ""}
      </span>

      <div className="flex flex-wrap gap-2">
        {course.status !== "PUBLIC" ? (
          <Button type="button" onClick={handlePublish} disabled={isPending} aria-busy={isPending}>
            {publish.isPending ? "Publishing…" : "Publish"}
          </Button>
        ) : null}
        {course.status === "PUBLIC" ? (
          <Button
            type="button"
            variant="outline"
            onClick={handleUnpublish}
            disabled={isPending}
            aria-busy={isPending}
          >
            {unpublish.isPending ? "Unpublishing…" : "Unpublish (revert to Draft)"}
          </Button>
        ) : null}
      </div>
    </div>
  );
}
