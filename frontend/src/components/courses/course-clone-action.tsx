"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { Copy, AlertCircle } from "lucide-react";
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
import { useCloneCourse, type CourseResponse } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";

/**
 * `POST /api/v1/courses/{id}/clone` (Wave 2) — creates a brand-new course (a
 * new id, `DRAFT` status, own content copy) and navigates to it on success.
 * Copy is deliberately explicit that the clone starts fresh: NO enrollment,
 * payment, or billing-period history carries over, even though
 * classification/content structure and the source's `pricingModel` do.
 */
export function CourseCloneAction({
  courseId,
  course,
  onCloned,
}: {
  courseId: string;
  course: CourseResponse;
  /**
   * Builds the redirect path for the newly-cloned course's id — the two
   * callers land in different places (the Tenant Admin course workspace vs.
   * the Teacher edit page, which has no tabbed workspace — see that page's
   * doc comment). Defaults to the Tenant Admin workspace's Overview tab.
   */
  onCloned?: (clonedId: string) => string;
}) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const mutation = useCloneCourse(courseId);

  const errorMessage = mutation.isError
    ? isApiClientError(mutation.error)
      ? mutation.error.message
      : "An unexpected error occurred. Please try again."
    : null;

  const buildRedirect = onCloned ?? ((clonedId: string) => `/tenant-admin/courses/${clonedId}?cloned=1`);

  const handleClone = async () => {
    try {
      const cloned = await mutation.mutateAsync();
      router.push(buildRedirect(cloned.id));
    } catch {
      // Surfaced via mutation.error below; dialog stays open.
    }
  };

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div>
        <h3 className="text-sm font-medium text-foreground">Clone course</h3>
        <p className="text-xs text-muted-foreground">
          Creates a new course with its own copy of this course&apos;s classification and content
          structure, starting as a fresh Draft. The clone has no enrollment, payment, or
          billing-period history — those never carry over.
        </p>
      </div>

      <AlertDialog open={open} onOpenChange={setOpen}>
        <AlertDialogTrigger render={<Button type="button" variant="outline" className="w-fit" />}>
          <Copy aria-hidden="true" />
          Clone course
        </AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Clone &ldquo;{course.name}&rdquo;?</AlertDialogTitle>
            <AlertDialogDescription>
              This creates a new, separate Draft course with its own copy of this course&apos;s
              content. Enrollment, payment, and billing-period history are never copied.
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
            <Button type="button" onClick={handleClone} disabled={mutation.isPending} aria-busy={mutation.isPending}>
              {mutation.isPending ? "Cloning…" : "Clone course"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
