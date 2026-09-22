"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ErrorState } from "@/components/states/error-state";
import { CourseEnrollmentAccessFields } from "@/components/courses/course-form-fields";
import { useUpdateCourse, type CourseResponse } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import {
  courseAccessSchema,
  courseAndAccessValuesToUpdateRequest,
  courseToAccessFormValues,
  type CourseAccessFormValues,
} from "@/lib/validation/course";
import type { FieldError } from "@/lib/api/types";

/**
 * Access tab (Wave 2): a dedicated read/edit view for `accessDurationDays`/
 * `enrollmentRule`, both already present on `CourseUpdateRequest`/
 * `CourseResponse` — no backend change needed. `PATCH /api/v1/courses/{id}`
 * has no partial-patch semantics (`name`/`slug`/`category` are `@NotBlank`
 * server-side), so submitting this form sends a full `CourseUpdateRequest`
 * built from the already-loaded `course`'s own values plus these two fields
 * — see `courseAndAccessValuesToUpdateRequest`.
 */
export function CourseAccessForm({ courseId, course }: { courseId: string; course: CourseResponse }) {
  const [saved, setSaved] = useState(false);
  const [pageError, setPageError] = useState<{ message: string; fieldErrors?: FieldError[] } | null>(null);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<CourseAccessFormValues>({
    resolver: zodResolver(courseAccessSchema),
    defaultValues: courseToAccessFormValues(course),
  });

  const mutation = useUpdateCourse(courseId);

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    try {
      await mutation.mutateAsync(courseAndAccessValuesToUpdateRequest(course, values));
      setSaved(true);
    } catch (error) {
      if (isApiClientError(error)) {
        setPageError({ message: error.message, fieldErrors: error.fieldErrors });
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">
        Controls how long a student keeps access after enrolling, and any free-text enrollment
        notes shown to staff/students.
      </p>

      {saved ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>Access settings saved.</AlertDescription>
        </Alert>
      ) : null}
      {pageError ? (
        <ErrorState
          message={pageError.message}
          fieldErrors={pageError.fieldErrors}
          onRetry={() => setPageError(null)}
        />
      ) : null}

      <form
        className="flex flex-col gap-4"
        noValidate
        aria-busy={mutation.isPending}
        onSubmit={onSubmit}
      >
        <span role="status" aria-live="polite" className="sr-only">
          {mutation.isPending ? "Saving access settings…" : ""}
        </span>
        <CourseEnrollmentAccessFields
          register={register}
          errors={errors}
          disabled={mutation.isPending}
          idPrefix="course-access"
        />
        <Button type="submit" className="w-fit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
          {mutation.isPending ? "Saving…" : "Save access settings"}
        </Button>
      </form>
    </div>
  );
}
