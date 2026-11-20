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
import { useT, type TranslateFn } from "@/i18n/i18n-provider";

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
  const t = useT();
  return (
    <AlertDialog open={open} onOpenChange={(next) => onOpenChange(next)}>
      <AlertDialogContent data-testid="enroll-confirm-dialog">
        <AlertDialogHeader>
          <div className="flex items-start justify-between gap-2">
            <AlertDialogTitle>{t("common.enroll.confirmTitle")}</AlertDialogTitle>
            <AlertDialogClose
              render={<Button type="button" variant="ghost" size="icon-sm" aria-label={t("common.actions.close")} />}
              data-testid="enroll-confirm-close"
            >
              <XIcon aria-hidden="true" />
            </AlertDialogClose>
          </div>
          <AlertDialogDescription>{courseTitle ? t("common.enroll.confirmQuestion", { course: courseTitle }) : t("common.enroll.confirmQuestionGeneric")}</AlertDialogDescription>
        </AlertDialogHeader>
        <p className="text-xs text-muted-foreground">
          {t("common.enroll.freeNote")}
        </p>
        <AlertDialogFooter>
          <AlertDialogClose
            render={<Button type="button" variant="outline" data-testid="enroll-confirm-no" />}
          >
            {t("common.enroll.no")}
          </AlertDialogClose>
          <Button
            type="button"
            className="bg-blue-600 text-white hover:bg-blue-700"
            data-testid="enroll-confirm-yes"
            onClick={onConfirm}
          >
            {t("common.enroll.yes")}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

/** Human copy for a failed free enrollment (never exposes raw server text for 404s). */
/** Enrollment error copy; pass `t` for the localized text (the English source is unchanged). */
export function enrollErrorMessage(error: unknown, t?: TranslateFn): string {
  if (isApiClientError(error)) {
    if (error.code === "SELF_ENROLLMENT_DISABLED") {
      return t ? t("common.enroll.error.selfDisabled") : "Your institute enrolls students for you. Contact your institute to join this course.";
    }
    if (error.code === "REGISTRATION_CLOSED") {
      return t ? t("common.enroll.error.closed") : "Registration for this course is closed right now. Contact your institute if you need to join.";
    }
    if (error.status === 404) {
      return t ? t("common.enroll.error.notFound") : "This course isn't available to join any more.";
    }
    if (error.status === 0) {
      return t ? t("common.enroll.error.network") : "We couldn't reach the server. Check your connection and try again.";
    }
  }
  return t ? t("common.enroll.error.generic") : "We couldn't enroll you. Please try again.";
}
