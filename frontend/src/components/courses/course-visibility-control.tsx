"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/states/error-state";
import { CourseStatusBadge } from "@/components/courses/course-status-badge";
import { usePublishCourse, useUnpublishCourse, type CourseResponse } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";

import { useT } from "@/i18n/i18n-provider";
/**
 * Publish/Unpublish action, rendered on both the Teacher edit page and the
 * Tenant Admin course detail page. `publish` always sets status -> `PUBLIC`;
 * `unpublish` always sets status -> `DRAFT` (never `PRIVATE` — there is no
 * server-side path back to `PRIVATE` after creation), so the action is
 * labeled honestly rather than implying a "make private" capability that
 * doesn't exist.
 */
export function CourseVisibilityControl({ courseId, course }: { courseId: string; course: CourseResponse }) {
  const t = useT();
  const [pageError, setPageError] = useState<string | null>(null);
  const publish = usePublishCourse(courseId);
  const unpublish = useUnpublishCourse(courseId);

  const isPending = publish.isPending || unpublish.isPending;

  const handlePublish = async () => {
    setPageError(null);
    try {
      await publish.mutateAsync();
    } catch (error) {
      setPageError(isApiClientError(error) ? error.message : t("courses.visibility.anUnexpectedErrorOccurred"));
    }
  };

  const handleUnpublish = async () => {
    setPageError(null);
    try {
      await unpublish.mutateAsync();
    } catch (error) {
      setPageError(isApiClientError(error) ? error.message : t("courses.visibility.anUnexpectedErrorOccurred"));
    }
  };

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="text-sm font-medium text-foreground">{t("courses.visibility.visibility")}</h3>
          <p className="text-xs text-muted-foreground">{t("courses.visibility.currentStatus")}</p>
        </div>
        <CourseStatusBadge status={course.status} />
      </div>

      {pageError ? <ErrorState message={pageError} onRetry={() => setPageError(null)} /> : null}

      <span role="status" aria-live="polite" className="sr-only">
        {isPending ? t("courses.visibility.updatingCourseVisibility") : ""}
      </span>

      <div className="flex flex-wrap gap-2">
        {course.status !== "PUBLIC" ? (
          <Button type="button" onClick={handlePublish} disabled={isPending} aria-busy={isPending}>
            {publish.isPending ? t("courses.visibility.publishing") : t("courses.visibility.publish")}
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
            {unpublish.isPending ? t("courses.visibility.unpublishing") : t("courses.visibility.unpublishRevertToDraft")}
          </Button>
        ) : null}
      </div>
    </div>
  );
}
