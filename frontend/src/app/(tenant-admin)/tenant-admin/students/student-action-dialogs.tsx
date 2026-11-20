"use client";

import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { LogIn, MessageSquarePlus, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { StudentDialog } from "@/components/students/student-dialog";
import {
  useAddStudentRemark,
  useDeleteStudent,
  useStartImpersonation,
  studentErrorMessage,
} from "@/lib/api/student-management";
import { reasonSchema, remarkSchema } from "@/lib/validation/student-management";

export interface StudentRef {
  id: string;
  name: string;
  indexNumber: string | null;
}

function describe(student: StudentRef): string {
  return student.indexNumber ? `${student.indexNumber} · ${student.name}` : student.name;
}

function errorMessage(error: unknown): string | null {
  if (!error) return null;
  return studentErrorMessage(error);
}

/** Add Remark (staff-only internal note) → `POST /students/{id}/remarks`. Rendered as plain text, never HTML. */
export function AddRemarkDialog({
  student,
  open,
  onOpenChange,
  onAdded,
}: {
  student: StudentRef;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onAdded?: (message: string) => void;
}) {
  const mutation = useAddStudentRemark(student.id);
  const form = useForm<{ body: string }>({ resolver: zodResolver(remarkSchema), defaultValues: { body: "" } });
  const { reset } = form;
  const resetMutation = mutation.reset;
  useEffect(() => {
    if (!open) {
      reset({ body: "" });
      resetMutation();
    }
  }, [open, reset, resetMutation]);

  const submit = form.handleSubmit(async ({ body }) => {
    await mutation.mutateAsync(body.trim()).then(
      () => {
        onAdded?.("Remark added.");
        onOpenChange(false);
      },
      () => undefined
    );
  });
  const error = errorMessage(mutation.error);
  const fieldError = form.formState.errors.body?.message;

  return (
    <StudentDialog
      open={open}
      onOpenChange={(next) => (!next && mutation.isPending ? undefined : onOpenChange(next))}
      title="Add Remark"
      description={`Internal note about ${describe(student)}. Visible to staff only.`}
      icon={<MessageSquarePlus className="size-5 text-primary" aria-hidden="true" />}
      testId="add-remark-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" form="add-remark-form" disabled={mutation.isPending}>
            {mutation.isPending ? "Saving…" : "Add Remark"}
          </Button>
        </>
      }
    >
      <form id="add-remark-form" onSubmit={submit} noValidate className="flex flex-col gap-1.5">
        <Label htmlFor="remark-body">Remark</Label>
        <Textarea
          id="remark-body"
          rows={5}
          maxLength={2000}
          aria-invalid={fieldError ? true : undefined}
          aria-describedby={fieldError ? "remark-body-error" : undefined}
          {...form.register("body")}
        />
        {fieldError ? (
          <p id="remark-body-error" role="alert" className="text-xs text-destructive">
            {fieldError}
          </p>
        ) : null}
        {error ? (
          <Alert variant="destructive">
            <AlertDescription role="alert">{error}</AlertDescription>
          </Alert>
        ) : null}
      </form>
    </StudentDialog>
  );
}

/**
 * Delete Student = remove from this tenant (soft): the account is suspended,
 * sessions revoked and the student hidden from lists; payments, ledger,
 * exams, attendance and audit history are kept. Reason required.
 */
export function DeleteStudentDialog({
  student,
  open,
  onOpenChange,
  onDeleted,
}: {
  student: StudentRef;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onDeleted?: (message: string) => void;
}) {
  const mutation = useDeleteStudent();
  const form = useForm<{ reason: string }>({ resolver: zodResolver(reasonSchema), defaultValues: { reason: "" } });
  const { reset } = form;
  const resetMutation = mutation.reset;
  useEffect(() => {
    if (!open) {
      reset({ reason: "" });
      resetMutation();
    }
  }, [open, reset, resetMutation]);

  const submit = form.handleSubmit(async ({ reason }) => {
    await mutation.mutateAsync({ id: student.id, reason: reason.trim() }).then(
      () => {
        onDeleted?.(`${student.name} was removed from this institute.`);
        onOpenChange(false);
      },
      () => undefined
    );
  });
  const error = errorMessage(mutation.error);
  const fieldError = form.formState.errors.reason?.message;

  return (
    <StudentDialog
      open={open}
      onOpenChange={(next) => (!next && mutation.isPending ? undefined : onOpenChange(next))}
      title="Delete Student"
      icon={<Trash2 className="size-5 text-destructive" aria-hidden="true" />}
      testId="delete-student-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" form="delete-student-form" variant="destructive" disabled={mutation.isPending}>
            {mutation.isPending ? "Deleting…" : "Delete Student"}
          </Button>
        </>
      }
    >
      <form id="delete-student-form" onSubmit={submit} noValidate className="flex flex-col gap-3">
        <p className="text-sm text-foreground">
          Remove <strong>{describe(student)}</strong> from this institute?
        </p>
        <p className="text-sm text-muted-foreground">
          Their account is suspended, their sign-ins are ended and they disappear from student lists. Payment,
          ledger, exam, attendance and audit history is kept.
        </p>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="delete-student-reason">Reason</Label>
          <Textarea
            id="delete-student-reason"
            rows={3}
            maxLength={500}
            aria-invalid={fieldError ? true : undefined}
            aria-describedby={fieldError ? "delete-student-reason-error" : undefined}
            {...form.register("reason")}
          />
          {fieldError ? (
            <p id="delete-student-reason-error" role="alert" className="text-xs text-destructive">
              {fieldError}
            </p>
          ) : null}
        </div>
        {error ? (
          <Alert variant="destructive">
            <AlertDescription role="alert">{error}</AlertDescription>
          </Alert>
        ) : null}
      </form>
    </StudentDialog>
  );
}

/**
 * Log as Student (ADR-033): names the student, explains the audited,
 * read-only, 30-minute support session and requires a reason. On success the
 * server has set the HttpOnly handoff cookie; a new tab at `/impersonate`
 * exchanges it for an in-memory token. Nothing credential-like goes in the URL.
 */
export function LogAsStudentDialog({
  student,
  open,
  onOpenChange,
}: {
  student: StudentRef;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const mutation = useStartImpersonation();
  const [blocked, setBlocked] = useState(false);
  const form = useForm<{ reason: string }>({ resolver: zodResolver(reasonSchema), defaultValues: { reason: "" } });
  const { reset } = form;
  const resetMutation = mutation.reset;
  useEffect(() => {
    if (!open) {
      reset({ reason: "" });
      resetMutation();
    }
  }, [open, reset, resetMutation]);

  const submit = form.handleSubmit(async ({ reason }) => {
    await mutation.mutateAsync({ id: student.id, reason: reason.trim() }).then(
      () => {
        const tab = window.open("/impersonate", "_blank");
        if (tab) {
          tab.opener = null;
          setBlocked(false);
          onOpenChange(false);
        } else {
          // Popup blocked: offer a plain link (the session is already started server-side).
          setBlocked(true);
        }
      },
      () => undefined
    );
  });
  const error = errorMessage(mutation.error);
  const fieldError = form.formState.errors.reason?.message;

  return (
    <StudentDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && mutation.isPending) return;
        if (!next) setBlocked(false);
        onOpenChange(next);
      }}
      title="Log in as Student"
      icon={<LogIn className="size-5 text-primary" aria-hidden="true" />}
      testId="log-as-student-dialog"
      className="sm:max-w-lg"
      footer={
        <>
          <Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" form="log-as-student-form" disabled={mutation.isPending}>
            {mutation.isPending ? "Starting…" : "Start support session"}
          </Button>
        </>
      }
    >
      <form id="log-as-student-form" onSubmit={submit} noValidate className="flex flex-col gap-3">
        <p className="text-sm text-foreground" data-testid="log-as-student-target">
          View the student portal as <strong>{describe(student)}</strong>.
        </p>
        <ul className="list-disc pl-5 text-sm text-muted-foreground">
          <li>This is a read-only support session: nothing can be changed, paid, submitted or watched.</li>
          <li>It opens in a new tab and ends automatically after 30 minutes.</li>
          <li>Starting and ending it is recorded in the audit log with your name and the reason.</li>
        </ul>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="log-as-student-reason">Reason</Label>
          <Textarea
            id="log-as-student-reason"
            rows={3}
            maxLength={500}
            placeholder="e.g. Student reports a lesson is missing"
            aria-invalid={fieldError ? true : undefined}
            aria-describedby={fieldError ? "log-as-student-reason-error" : "log-as-student-reason-help"}
            {...form.register("reason")}
          />
          <p id="log-as-student-reason-help" className="text-xs text-muted-foreground">
            5–500 characters.
          </p>
          {fieldError ? (
            <p id="log-as-student-reason-error" role="alert" className="text-xs text-destructive">
              {fieldError}
            </p>
          ) : null}
        </div>
        {error ? (
          <Alert variant="destructive">
            <AlertDescription role="alert">{error}</AlertDescription>
          </Alert>
        ) : null}
        {blocked ? (
          <Alert>
            <AlertDescription role="status">
              The support session has started, but your browser blocked the new tab.{" "}
              <a
                href="/impersonate"
                target="_blank"
                rel="noopener noreferrer"
                className="font-medium underline"
                onClick={() => onOpenChange(false)}
              >
                Open the support tab
              </a>
            </AlertDescription>
          </Alert>
        ) : null}
      </form>
    </StudentDialog>
  );
}
