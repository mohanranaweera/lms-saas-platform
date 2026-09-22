"use client";

import { useState } from "react";
import { Archive, ArchiveRestore, AlertCircle } from "lucide-react";
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
import { useArchiveCourse, useUnarchiveCourse, type CourseResponse } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";

/**
 * `POST /api/v1/courses/{id}/archive` / `.../unarchive` (Wave 2) — a purely
 * listing-visibility flag, never a delete (no enrollment/payment/content
 * data is touched). Reversible, but still confirmed before applying (a
 * course disappearing from the default list view without warning is
 * disruptive enough to warrant the same confirm-before-destructive-looking-
 * action pattern `CourseDeleteAction` uses, even though this specific action
 * can always be undone from this same screen).
 */
export function CourseArchiveControl({ courseId, course }: { courseId: string; course: CourseResponse }) {
  const [open, setOpen] = useState(false);
  const archive = useArchiveCourse(courseId);
  const unarchive = useUnarchiveCourse(courseId);
  const isArchived = course.archivedAt != null;
  const mutation = isArchived ? unarchive : archive;

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "An unexpected error occurred. Please try again."
    : null;

  const handleConfirm = async () => {
    try {
      await mutation.mutateAsync();
      setOpen(false);
    } catch {
      // Surfaced via mutation.error below; dialog stays open so the caller
      // can see the failure and retry or cancel.
    }
  };

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div>
        <h3 className="text-sm font-medium text-foreground">{isArchived ? "Unarchive course" : "Archive course"}</h3>
        <p className="text-xs text-muted-foreground">
          {isArchived
            ? `Archived on ${course.archivedAt ? formatDateTime(course.archivedAt) : "unknown date"}. Unarchiving makes it visible in the default course list again.`
            : "Archiving hides this course from the default course list (staff can still find it with “Show archived courses”). It never deletes enrollment, payment, or content data, and can be undone at any time."}
        </p>
      </div>

      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogTrigger
          render={<Button type="button" variant={isArchived ? "outline" : "secondary"} className="w-fit" />}
        >
          {isArchived ? <ArchiveRestore aria-hidden="true" /> : <Archive aria-hidden="true" />}
          {isArchived ? "Unarchive course" : "Archive course"}
        </AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {isArchived ? `Unarchive "${course.name}"?` : `Archive "${course.name}"?`}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {isArchived
                ? "This makes the course visible in the default course list again."
                : "This hides the course from the default course list. No enrollment, payment, or content data is affected, and this can be undone at any time."}
            </AlertDialogDescription>
          </AlertDialogHeader>
          {errorMessage ? (
            <Alert variant="destructive">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>{errorMessage}</AlertDescription>
            </Alert>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
            <Button
              type="button"
              onClick={handleConfirm}
              disabled={mutation.isPending}
              aria-busy={mutation.isPending}
            >
              {mutation.isPending ? "Saving…" : isArchived ? "Unarchive course" : "Archive course"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
