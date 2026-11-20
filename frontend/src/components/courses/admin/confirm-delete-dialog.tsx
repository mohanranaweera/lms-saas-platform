"use client";

import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
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
 * Human-readable message for a failed mutation. A 409 means the server
 * refused because something still references the record (payments, orders,
 * materials), so the generic fallback says that rather than "try again".
 */
export function mutationErrorMessage(error: unknown, conflictFallback?: string): string {
  if (isApiClientError(error)) {
    if (error.status === 403) return "You don't have permission to do this.";
    if (error.status === 409) {
      return conflictFallback ? `${error.message} ${conflictFallback}`.trim() : error.message;
    }
    return error.message;
  }
  return "Something went wrong. Please try again.";
}

/**
 * Destructive confirmation. Stays open on failure so the reason (e.g. a 409
 * "still referenced") is visible inside the dialog.
 */
export function ConfirmDeleteDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  pending,
  error,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: string;
  confirmLabel: string;
  pending: boolean;
  error: string | null;
  onConfirm: () => void;
}) {
  return (
    <AlertDialog open={open} onOpenChange={(next) => onOpenChange(next)}>
      <AlertDialogContent data-testid="confirm-delete-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>{title}</AlertDialogTitle>
          <AlertDialogDescription>{description}</AlertDialogDescription>
        </AlertDialogHeader>
        {error ? (
          <Alert variant="destructive" data-testid="confirm-delete-error">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            onClick={onConfirm}
            disabled={pending}
            aria-busy={pending}
            data-testid="confirm-delete-submit"
          >
            {pending ? "Deleting…" : confirmLabel}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
