"use client";

import { useState } from "react";
import Link from "next/link";
import {
  AlertCircle,
  Archive,
  ArchiveRestore,
  CalendarClock,
  EyeOff,
  Globe,
  ListTree,
  MoreHorizontal,
  Pencil,
  Trash2,
  Users,
} from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLinkItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { cn } from "@/lib/utils";
import {
  useArchiveCourse,
  useDeleteCourse,
  usePublishCourse,
  useUnarchiveCourse,
  useUnpublishCourse,
  type CourseStatus,
} from "@/lib/api/courses";
import { useSetCourseRegistration, type KlassCourse } from "@/lib/api/klass-admin";
import { ConfirmDeleteDialog, mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import type { AdminToastMessage } from "@/components/courses/admin/admin-toast";
import { isApiClientError } from "@/lib/api/error";

export const REGISTRATION_HELP =
  "Turning this off blocks new enrollments only. Students already enrolled keep their access and anything they paid for. Students only see the course in Available Courses once it is Published (and not archived).";

export const UNPUBLISH_HELP =
  "Students who haven't enrolled will no longer see this course. Enrolled students keep their access.";

/**
 * Publish/unpublish failure text. A 403 or 409 carries the server's own
 * explanation (e.g. who may publish, or why the course can't be published
 * yet), so that message is shown as-is; anything else uses the shared mapping.
 */
export function publishErrorMessage(error: unknown): string {
  if (isApiClientError(error) && (error.status === 403 || error.status === 409) && error.message.trim()) {
    return error.message;
  }
  return mutationErrorMessage(error);
}

const STATUS_LABELS: Record<CourseStatus, string> = {
  DRAFT: "Draft",
  PUBLIC: "Published",
  PRIVATE: "Private",
};

/** Course status chip: Draft (neutral), Published (green), Private. Text label, never color alone. */
export function CourseStatusBadge({ status }: { status: CourseStatus }) {
  return (
    <Badge
      variant={status === "PUBLIC" ? "default" : status === "PRIVATE" ? "secondary" : "outline"}
      className={cn(
        status === "PUBLIC" &&
          "bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300 [a]:hover:bg-green-100"
      )}
      data-testid="course-status-badge"
      data-status={status}
    >
      {STATUS_LABELS[status] ?? status}
    </Badge>
  );
}

export const REGISTRATION_ARCHIVED_HELP =
  "Registration can't be changed while the course is archived. Unarchive the course to change it.";

/**
 * "Enabled for Registration" switch. Optimistic (the hook flips the cached
 * value and rolls back on failure); the server decides, and an error toast
 * explains a refusal. Uses `aria-disabled` (still focusable, so the reason is
 * reachable) while a change is saving and for archived courses.
 *
 * Rendered once per layout (table and card are both in the DOM), so `layout`
 * keeps the help element ids unique.
 */
export function RegistrationSwitch({
  course,
  onToast,
  layout,
}: {
  course: KlassCourse;
  onToast: (message: AdminToastMessage) => void;
  layout: "table" | "card";
}) {
  const mutation = useSetCourseRegistration();
  const enabled = course.registrationEnabled;
  const archived = Boolean(course.archivedAt);
  const unavailable = archived || mutation.isPending;
  const helpId = `registration-help-${course.id}-${layout}`;

  const toggle = () => {
    if (unavailable) return;
    const next = !enabled;
    mutation.mutate(
      { courseId: course.id, enabled: next },
      {
        onSuccess: () =>
          onToast({
            kind: "success",
            text: next
              ? `Registration opened for “${course.name}”.`
              : `Registration closed for “${course.name}”. Enrolled students keep their access.`,
          }),
        onError: (error) =>
          onToast({
            kind: "error",
            text: `Could not change registration for “${course.name}”: ${mutationErrorMessage(error)}`,
          }),
      }
    );
  };

  return (
    <>
      <button
        type="button"
        role="switch"
        aria-checked={enabled}
        aria-label={`Enabled for registration: ${course.name}`}
        aria-describedby={helpId}
        aria-disabled={unavailable || undefined}
        aria-busy={mutation.isPending || undefined}
        onClick={toggle}
        data-testid="course-registration-switch"
        data-state={enabled ? "on" : "off"}
        className={cn(
          "relative inline-flex h-5 w-9 shrink-0 items-center rounded-full border-2 border-transparent transition-colors outline-none focus-visible:ring-3 focus-visible:ring-ring/50 aria-disabled:cursor-not-allowed aria-disabled:opacity-60 aria-busy:cursor-wait",
          enabled ? "bg-primary" : "bg-muted-foreground/40"
        )}
      >
        <span
          aria-hidden="true"
          className={cn(
            "inline-block size-4 rounded-full bg-background shadow transition-transform",
            enabled ? "translate-x-4" : "translate-x-0"
          )}
        />
      </button>
      <span id={helpId} className="sr-only">
        {archived ? REGISTRATION_ARCHIVED_HELP : REGISTRATION_HELP}
      </span>
    </>
  );
}

/**
 * Overflow ("⋯") actions menu for one course row (G5): Modules, Edit, Edit
 * class schedule (the course's live-class sessions), Roster,
 * Publish/Unpublish, Archive/Unarchive, Delete. Keyboard: Enter/Space/arrow
 * keys open and move through the menu, Escape closes it and focus returns to
 * the trigger. Tute Management is omitted until that module exists (R2-D4).
 * Every action is authorized by the backend; a refusal shows as a toast or
 * in the confirmation dialog.
 */
export function CourseRowActions({
  course,
  onToast,
}: {
  course: KlassCourse;
  onToast: (message: AdminToastMessage) => void;
}) {
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [unpublishOpen, setUnpublishOpen] = useState(false);
  const publish = usePublishCourse(course.id);
  const unpublish = useUnpublishCourse(course.id);
  const published = course.status === "PUBLIC";
  const publishPending = publish.isPending || unpublish.isPending;
  const archive = useArchiveCourse(course.id);
  const unarchive = useUnarchiveCourse(course.id);
  const remove = useDeleteCourse(course.id);
  const archived = Boolean(course.archivedAt);
  const archivePending = archive.isPending || unarchive.isPending;

  const toggleArchive = () => {
    if (archivePending) return;
    const mutation = archived ? unarchive : archive;
    mutation.mutate(undefined, {
      onSuccess: () =>
        onToast({
          kind: "success",
          text: archived ? `“${course.name}” moved back to Active.` : `“${course.name}” archived.`,
        }),
      onError: (error) => onToast({ kind: "error", text: mutationErrorMessage(error) }),
    });
  };

  const doPublish = () => {
    if (publishPending) return;
    publish.mutate(undefined, {
      onSuccess: () =>
        onToast({ kind: "success", text: `“${course.name}” published. Students can now see it in Available Courses.` }),
      onError: (error) =>
        onToast({ kind: "error", text: `Could not publish “${course.name}”: ${publishErrorMessage(error)}` }),
    });
  };

  const confirmUnpublish = () => {
    if (publishPending) return;
    unpublish.mutate(undefined, {
      onSuccess: () => {
        setUnpublishOpen(false);
        onToast({ kind: "success", text: `“${course.name}” unpublished and moved back to Draft.` });
      },
    });
  };

  const confirmDelete = () => {
    remove.mutate(undefined, {
      onSuccess: () => {
        setConfirmOpen(false);
        onToast({ kind: "success", text: `“${course.name}” deleted.` });
      },
    });
  };

  const base = `/tenant-admin/courses/${course.id}`;

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger
          render={<Button type="button" variant="ghost" size="icon-sm" />}
          aria-label={`Actions for ${course.name}`}
          data-testid="course-actions-menu"
        >
          <MoreHorizontal aria-hidden="true" />
        </DropdownMenuTrigger>
        <DropdownMenuContent data-testid="course-actions-menu-content">
          <DropdownMenuLinkItem render={<Link href={`${base}/modules`} />} data-testid="course-action-modules">
            <ListTree className="text-green-700 dark:text-green-500" aria-hidden="true" />
            Modules
          </DropdownMenuLinkItem>
          <DropdownMenuLinkItem render={<Link href={base} />} data-testid="course-action-edit">
            <Pencil className="text-primary" aria-hidden="true" />
            Edit
          </DropdownMenuLinkItem>
          <DropdownMenuLinkItem render={<Link href={`${base}/sessions`} />} data-testid="course-action-schedule">
            <CalendarClock aria-hidden="true" />
            Edit class schedule
          </DropdownMenuLinkItem>
          <DropdownMenuLinkItem render={<Link href={`${base}/roster`} />} data-testid="course-action-roster">
            <Users aria-hidden="true" />
            Roster
          </DropdownMenuLinkItem>
          <DropdownMenuSeparator />
          {published ? (
            <DropdownMenuItem
              disabled={publishPending}
              onClick={() => {
                if (publishPending) return;
                unpublish.reset();
                setUnpublishOpen(true);
              }}
              data-testid="course-action-unpublish"
            >
              <EyeOff aria-hidden="true" />
              Unpublish (revert to Draft)
            </DropdownMenuItem>
          ) : (
            <DropdownMenuItem disabled={publishPending} onClick={doPublish} data-testid="course-action-publish">
              <Globe className="text-green-700 dark:text-green-500" aria-hidden="true" />
              Publish
            </DropdownMenuItem>
          )}
          <DropdownMenuItem
            disabled={archivePending}
            onClick={toggleArchive}
            data-testid={archived ? "course-action-unarchive" : "course-action-archive"}
          >
            {archived ? <ArchiveRestore aria-hidden="true" /> : <Archive aria-hidden="true" />}
            {archived ? "Unarchive" : "Archive"}
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            variant="destructive"
            onClick={() => {
              remove.reset();
              setConfirmOpen(true);
            }}
            data-testid="course-action-delete"
          >
            <Trash2 aria-hidden="true" />
            Delete
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      <ConfirmDeleteDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title={`Delete “${course.name}”?`}
        description="This permanently deletes the course. Courses with enrollments, payments or attached materials cannot be deleted; archive them instead."
        confirmLabel="Delete course"
        pending={remove.isPending}
        error={
          remove.isError
            ? mutationErrorMessage(remove.error, "Archive the course instead to hide it from this list.")
            : null
        }
        onConfirm={confirmDelete}
      />
      <AlertDialog open={unpublishOpen} onOpenChange={(next) => setUnpublishOpen(next)}>
        <AlertDialogContent data-testid="confirm-unpublish-dialog">
          <AlertDialogHeader>
            <AlertDialogTitle>{`Unpublish “${course.name}”?`}</AlertDialogTitle>
            <AlertDialogDescription>
              {UNPUBLISH_HELP} The course returns to Draft.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {unpublish.isError ? (
            <Alert variant="destructive" data-testid="confirm-unpublish-error">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>{publishErrorMessage(unpublish.error)}</AlertDescription>
            </Alert>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
            <Button
              type="button"
              onClick={confirmUnpublish}
              disabled={unpublish.isPending}
              aria-busy={unpublish.isPending}
              data-testid="confirm-unpublish-submit"
            >
              {unpublish.isPending ? "Unpublishing…" : "Unpublish"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
