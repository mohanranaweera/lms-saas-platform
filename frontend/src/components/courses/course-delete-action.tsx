"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Trash2, AlertCircle } from "lucide-react";
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
import { useDeleteCourse, type CourseResponse } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";

/**
 * Tenant-Admin-only delete action (`DELETE /api/v1/courses/{id}` — 403 for
 * everyone else, including the owning Teacher; also 409 when a lesson in
 * this course still has an attached material — `fk_material_lesson` has no
 * `ON DELETE CASCADE` by design). Rendered only on the Tenant Admin course
 * detail page.
 *
 * The failure `Alert` is rendered *inside* `AlertDialogContent` (mirroring
 * `MaterialRow`'s delete-confirmation `Alert` placement), not as a sibling
 * outside the dialog — the dialog deliberately stays open on failure so the
 * admin can see it and retry/cancel, and a modal's background content is
 * inert/covered while it's open, so an outside-the-dialog `Alert` would be
 * invisible and unannounced for exactly the case it exists to handle.
 */
export function CourseDeleteAction({ courseId, course }: { courseId: string; course: CourseResponse }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const mutation = useDeleteCourse(courseId);

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "An unexpected error occurred. Please try again."
    : null;

  const handleDelete = async () => {
    try {
      await mutation.mutateAsync();
      router.push("/tenant-admin/courses");
    } catch {
      // Error surfaced via mutation.error below; keep the dialog open so the
      // admin can see the failure and retry or cancel.
    }
  };

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-destructive/30 p-4">
      <div>
        <h3 className="text-sm font-medium text-foreground">Delete course</h3>
        <p className="text-xs text-muted-foreground">
          Permanently deletes &ldquo;{course.name}&rdquo;. This cannot be undone from this
          screen.
        </p>
      </div>

      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogTrigger render={<Button type="button" variant="destructive" className="w-fit" />}>
          <Trash2 aria-hidden="true" />
          Delete course
        </AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete &ldquo;{course.name}&rdquo;?</AlertDialogTitle>
            <AlertDialogDescription>
              This permanently deletes the course. This action cannot be undone.
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
              variant="destructive"
              onClick={handleDelete}
              disabled={mutation.isPending}
              aria-busy={mutation.isPending}
            >
              {mutation.isPending ? "Deleting…" : "Delete course"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
