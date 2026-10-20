"use client";

import { XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { isApiClientError } from "@/lib/api/error";

/**
 * Klass "Are you sure?" enrollment confirmation. NO / X / Escape leave
 * enrollment unchanged; YES hands control back to the caller, which performs
 * the single POST and shows the spinner on the card's Enroll button.
 */
export function EnrollConfirmDialog({
  courseTitle,
  open,
  onOpenChange,
  onConfirm,
}: {
  courseTitle: string | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: () => void;
}) {
  return (
    <AlertDialog open={open} onOpenChange={(next) => onOpenChange(next)}>
      <AlertDialogContent data-testid="enroll-confirm-dialog">
        <AlertDialogHeader>
          <div className="flex items-start justify-between gap-2">
            <AlertDialogTitle>Are you sure?</AlertDialogTitle>
            <AlertDialogClose
              render={<Button type="button" variant="ghost" size="icon-sm" aria-label="Close" />}
              data-testid="enroll-confirm-close"
            >
              <XIcon aria-hidden="true" />
            </AlertDialogClose>
          </div>
          <AlertDialogDescription>Enroll in {courseTitle ?? "this course"}?</AlertDialogDescription>
        </AlertDialogHeader>
        <p className="text-xs text-muted-foreground">
          Enrolling is free. You can browse the course right away; paid lessons ask for payment when you open them.
        </p>
        <AlertDialogFooter>
          <AlertDialogClose
            render={<Button type="button" variant="outline" data-testid="enroll-confirm-no" />}
          >
            NO
          </AlertDialogClose>
          <Button
            type="button"
            className="bg-blue-600 text-white hover:bg-blue-700"
            data-testid="enroll-confirm-yes"
            onClick={onConfirm}
          >
            YES
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** Human copy for a failed free enrollment (never exposes raw server text for 404s). */
export function enrollErrorMessage(error: unknown): string {
  if (isApiClientError(error)) {
    if (error.code === "REGISTRATION_CLOSED") {
      return "Registration for this course is closed right now. Contact your institute if you need to join.";
    }
    if (error.status === 404) {
      return "This course isn't available to join any more.";
    }
    if (error.status === 0) {
      return "We couldn't reach the server. Check your connection and try again.";
    }
  }
  return "We couldn't enroll you. Please try again.";
}
