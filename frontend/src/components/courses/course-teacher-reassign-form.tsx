"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2, Info } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ErrorState } from "@/components/states/error-state";
import { useReassignCourseTeacher, type CourseResponse } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import { teacherReassignSchema, type TeacherReassignFormValues } from "@/lib/validation/course";

/**
 * Tenant-Admin-only teacher reassignment control (`POST
 * /api/v1/courses/{id}/teacher`) — must render ONLY on the Tenant Admin
 * course detail page, never anywhere in Teacher UI (not even hidden/disabled).
 *
 * Deliberately a plain teacher-id (UUID) text input, not a searchable
 * picker: there is currently no "list teachers" REST endpoint exposed to the
 * frontend (`UserProvisioningApi.findTenantUsersByRole` is internal-only).
 * This is a known MVP limitation, not an oversight — see module report.
 */
export function CourseTeacherReassignForm({ courseId, course }: { courseId: string; course: CourseResponse }) {
  const [saved, setSaved] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<TeacherReassignFormValues>({
    resolver: zodResolver(teacherReassignSchema),
    defaultValues: { teacherId: "" },
  });

  const mutation = useReassignCourseTeacher(courseId);

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    try {
      await mutation.mutateAsync({ teacherId: values.teacherId });
      setSaved(true);
      reset({ teacherId: "" });
    } catch (error) {
      if (isApiClientError(error)) {
        const teacherError = error.fieldErrors.find((fieldError) => fieldError.field === "teacherId");
        if (teacherError) {
          setError("teacherId", { type: "server", message: teacherError.message });
          return;
        }
        setPageError(error.message);
        return;
      }
      setPageError("An unexpected error occurred. Please try again.");
    }
  });

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div>
        <h3 className="text-sm font-medium text-foreground">Reassign teacher</h3>
        <p className="text-xs text-muted-foreground">
          Currently owned by teacher <span className="font-mono">{course.teacherId}</span>.
        </p>
      </div>

      <div className="flex items-start gap-2 rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
        <Info className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
        <p>
          There is no teacher directory/picker yet — enter the target teacher&apos;s user ID
          directly. Ask your identity/user-management team for the ID if you don&apos;t have it
          on hand.
        </p>
      </div>

      {saved ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>Teacher reassigned.</AlertDescription>
        </Alert>
      ) : null}
      {pageError ? <ErrorState message={pageError} onRetry={() => setPageError(null)} /> : null}

      <form
        className="flex flex-col gap-2 sm:flex-row sm:items-end"
        noValidate
        aria-busy={mutation.isPending}
        onSubmit={onSubmit}
      >
        <span role="status" aria-live="polite" className="sr-only">
          {mutation.isPending ? "Reassigning teacher…" : ""}
        </span>
        <div className="flex flex-1 flex-col gap-1.5">
          <Label htmlFor={`teacher-reassign-${courseId}`}>New teacher ID (UUID)</Label>
          <Input
            id={`teacher-reassign-${courseId}`}
            autoComplete="off"
            disabled={mutation.isPending}
            aria-invalid={!!errors.teacherId}
            aria-describedby={errors.teacherId ? `teacher-reassign-${courseId}-error` : undefined}
            {...register("teacherId")}
          />
          {errors.teacherId ? (
            <p id={`teacher-reassign-${courseId}-error`} role="alert" className="text-xs text-destructive">
              {errors.teacherId.message}
            </p>
          ) : null}
        </div>
        <Button type="submit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
          {mutation.isPending ? "Reassigning…" : "Reassign"}
        </Button>
      </form>
    </div>
  );
}
