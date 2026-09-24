"use client";

import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useUpdateStudent, type StudentResponse } from "@/lib/api/students";
import { studentUpdateSchema, type StudentUpdateFormValues } from "@/lib/validation/students";
import { isApiClientError } from "@/lib/api/error";
import { StudentStatusBadge } from "@/components/students/student-status-badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { ErrorState } from "@/components/states/error-state";

/**
 * Profile tab (Wave 3 tabbed rebuild) — the pre-Wave-3 detail page's own
 * content, unchanged: `email`/`roleCode`/`status` read-only,
 * `PATCH /v1/students/{id}` (`name` only) for the editable field.
 */
export function ProfileTab({ student, canManage }: { student: StudentResponse; canManage: boolean }) {
  const updateMutation = useUpdateStudent(student.id);
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
          if (unmapped.length > 0) {
            setPageError({ message: "Some information couldn't be validated.", code: error.code });
          }
          return;
        }
        setPageError({ message: error.message, code: error.code });
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <div className="flex max-w-xl flex-col gap-6">
      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-2 text-sm">
        <dt className="font-medium text-muted-foreground">Email</dt>
        <dd className="text-foreground">{student.email}</dd>
        <dt className="font-medium text-muted-foreground">Role</dt>
        <dd className="text-foreground">{student.roleCode}</dd>
        <dt className="font-medium text-muted-foreground">Status</dt>
        <dd>
          <StudentStatusBadge status={student.status} />
        </dd>
      </dl>

      {pageError ? (
        <ErrorState
          message={pageError.message}
          code={pageError.code}
          onRetry={() => {
            setPageError(null);
            void onSubmit();
          }}
        />
      ) : null}

      {canManage ? (
        <form className="flex flex-col gap-4" onSubmit={onSubmit} aria-busy={updateMutation.isPending} noValidate>
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
