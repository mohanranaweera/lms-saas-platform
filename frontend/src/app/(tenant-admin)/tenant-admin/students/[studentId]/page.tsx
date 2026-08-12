"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { ArrowLeft } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageStudents } from "@/lib/auth/permissions";
import { useStudent, useUpdateStudent, type StudentResponse } from "@/lib/api/students";
import { studentUpdateSchema, type StudentUpdateFormValues } from "@/lib/validation/students";
import { isApiClientError } from "@/lib/api/error";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { StudentStatusBadge } from "@/components/students/student-status-badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorState } from "@/components/states/error-state";

/**
 * Student Detail/Edit (MVP-006, Tenant Admin). `email`/`roleCode`/`status`
 * are read-only (the backend's `PATCH /v1/students/{id}` only ever accepts
 * `name`, per `StudentUpdateRequest`) — only `name` renders as an editable
 * field.
 *
 * A 404 for both a nonexistent id and a cross-tenant id is already uniform
 * on the backend ("Student account not found") and rendered as-is via
 * `QueryStateBoundary`'s generic `ErrorState` — no special-casing here that
 * could distinguish the two.
 */
export default function StudentDetailPage() {
  const params = useParams<{ studentId: string }>();
  const studentId = params.studentId;
  const { session } = useAuth();
  const canManage = canManageStudents(session?.role ?? null);

  const studentQuery = useStudent(studentId);
  const updateMutation = useUpdateStudent(studentId);

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/tenant-admin/students"
          className="inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-4" aria-hidden="true" />
          Back to students
        </Link>
      </div>

      <QueryStateBoundary
        query={studentQuery}
        loadingLabel="Loading student…"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        loginPath="/login"
      >
        {(student) => (
          <StudentDetailForm student={student} canManage={canManage} updateMutation={updateMutation} />
        )}
      </QueryStateBoundary>
    </div>
  );
}

function StudentDetailForm({
  student,
  canManage,
  updateMutation,
}: {
  student: StudentResponse;
  canManage: boolean;
  updateMutation: ReturnType<typeof useUpdateStudent>;
}) {
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const [saved, setSaved] = useState(false);

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors, isDirty },
  } = useForm<StudentUpdateFormValues>({
    resolver: zodResolver(studentUpdateSchema),
    defaultValues: { name: student.name },
  });

  // Keep the form's baseline in sync if the underlying record changes (e.g.
  // navigating between two students without a full page reload).
  useEffect(() => {
    reset({ name: student.name });
  }, [student.id, student.name, reset]);

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    try {
      await updateMutation.mutateAsync(values);
      setSaved(true);
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.fieldErrors.length > 0) {
          const unmapped = error.fieldErrors.filter((fieldError) => fieldError.field !== "name");
          for (const fieldError of error.fieldErrors) {
            if (fieldError.field === "name") {
              setError("name", { type: "server", message: fieldError.message });
            }
          }
          // `name` is the only editable field this form renders — a field
          // error for anything else would otherwise be silently dropped,
          // leaving no visible feedback at all. Mirrors
          // create-student-sheet.tsx's unmapped-field handling.
          if (unmapped.length > 0) {
            setPageError({
              message: "Some information couldn't be validated.",
              code: error.code,
            });
          }
          return;
        }
        // A stale UI could still let a PATCH through even when the form is
        // hidden client-side — a real 403 must still be handled gracefully,
        // announced via role="alert", not crash the page.
        setPageError({ message: error.message, code: error.code });
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <div className="flex max-w-xl flex-col gap-6">
      <div className="flex items-center justify-between gap-3">
        <h1 className="text-xl font-semibold text-foreground">{student.name}</h1>
        <StudentStatusBadge status={student.status} />
      </div>

      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-2 text-sm">
        <dt className="font-medium text-muted-foreground">Email</dt>
        <dd className="text-foreground">{student.email}</dd>
        <dt className="font-medium text-muted-foreground">Role</dt>
        <dd className="text-foreground">{student.roleCode}</dd>
      </dl>

      {pageError ? (
        <ErrorState
          message={pageError.message}
          code={pageError.code}
          // A failed *submission*, not a failed load — retry means
          // resubmitting the form's current (still-populated) values, not
          // just dismissing the banner. See create-student-sheet.tsx's
          // identical reasoning.
          onRetry={() => {
            setPageError(null);
            void onSubmit();
          }}
        />
      ) : null}

      {canManage ? (
        <form
          className="flex flex-col gap-4"
          onSubmit={onSubmit}
          aria-busy={updateMutation.isPending}
          noValidate
        >
          <span role="status" aria-live="polite" className="sr-only">
            {updateMutation.isPending ? "Saving…" : saved ? "Saved." : ""}
          </span>
          <fieldset disabled={updateMutation.isPending} className="flex flex-col gap-4">
            <legend className="sr-only">Edit student</legend>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="student-detail-name">Name</Label>
              <Input
                id="student-detail-name"
                type="text"
                autoComplete="name"
                aria-invalid={errors.name ? true : undefined}
                aria-describedby={errors.name ? "student-detail-name-error" : undefined}
                {...register("name")}
              />
              {errors.name ? (
                <p id="student-detail-name-error" role="alert" className="text-xs text-destructive">
                  {errors.name.message}
                </p>
              ) : null}
            </div>
          </fieldset>
          <div className="flex items-center gap-3">
            <Button type="submit" disabled={updateMutation.isPending || !isDirty}>
              {updateMutation.isPending ? "Saving…" : "Save changes"}
            </Button>
            {saved && !isDirty ? (
              <span role="status" className="text-sm text-muted-foreground">
                Saved.
              </span>
            ) : null}
          </div>
        </form>
      ) : (
        <p className="text-sm text-muted-foreground">
          You don&apos;t have permission to edit this student&apos;s details.
        </p>
      )}
    </div>
  );
}
