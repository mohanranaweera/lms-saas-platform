"use client";

import { useEffect, useRef, useState } from "react";
import { useForm, type FieldErrors, type UseFormRegister } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ErrorState } from "@/components/states/error-state";
import { Stepper, type StepDefinition } from "@/components/courses/stepper";
import {
  CourseBasicsFields,
  CourseClassificationFields,
  CourseEnrollmentAccessFields,
} from "@/components/courses/course-form-fields";
import { useUpdateCourse, type CourseResponse } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import {
  courseEditSchema,
  courseToEditFormValues,
  toCourseUpdateRequest,
  type CourseBasicsFormValues,
  type CourseClassificationFormValues,
  type CourseEditFormValues,
  type CourseEnrollmentAccessFormValues,
} from "@/lib/validation/course";

const STEPS: StepDefinition[] = [
  { id: "basics", label: "Basics" },
  { id: "classification", label: "Classification" },
  { id: "enrollment-access", label: "Enrollment & access" },
];

const STEP_FIELDS: Array<Array<keyof CourseEditFormValues>> = [
  ["name", "slug", "category", "description"],
  ["subject", "stream", "grade", "academicYear"],
  ["enrollmentRule", "accessDurationDays"],
];

function stepIndexForField(field: string): number {
  const index = STEP_FIELDS.findIndex((fields) => (fields as string[]).includes(field));
  return index === -1 ? 0 : index;
}

const KNOWN_FIELD_NAMES = new Set<string>(STEP_FIELDS.flat());

/**
 * Course Builder edit flow — reused, unmodified, by both the Teacher edit
 * page (`teacher/courses/[courseId]/edit`) and the Tenant Admin course
 * detail page (`tenant-admin/courses/[courseId]`). Deliberately omits the
 * visibility (status) step and never renders `price` — `PATCH
 * /api/v1/courses/{id}` silently drops both if sent; those have their own
 * dedicated actions rendered by the caller outside this component (price
 * change, publish/unpublish).
 *
 * Callers should mount this with `key={course.id}` so RHF re-initializes
 * cleanly whenever the underlying course identity changes.
 */
export function CourseEditForm({
  courseId,
  course,
  onSaved,
}: {
  courseId: string;
  course: CourseResponse;
  onSaved?: (updated: CourseResponse) => void;
}) {
  const [stepIndex, setStepIndex] = useState(0);
  const [pageError, setPageError] = useState<{
    message: string;
    code?: string;
    fieldErrors?: FieldError[];
  } | null>(null);
  const [saved, setSaved] = useState(false);
  const headingRef = useRef<HTMLHeadingElement>(null);

  const {
    register,
    handleSubmit,
    trigger,
    setError,
    formState: { errors },
  } = useForm<CourseEditFormValues>({
    resolver: zodResolver(courseEditSchema),
    defaultValues: courseToEditFormValues(course),
  });

  const mutation = useUpdateCourse(courseId);

  useEffect(() => {
    headingRef.current?.focus();
  }, [stepIndex]);

  const goNext = async () => {
    const valid = await trigger(STEP_FIELDS[stepIndex]);
    if (valid) {
      setStepIndex((index) => Math.min(index + 1, STEPS.length - 1));
    }
  };

  const goBack = () => setStepIndex((index) => Math.max(index - 1, 0));

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    try {
      const updated = await mutation.mutateAsync(toCourseUpdateRequest(values));
      setSaved(true);
      onSaved?.(updated);
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.fieldErrors.length > 0) {
          const unmapped = error.fieldErrors.filter(
            (fieldError) => !KNOWN_FIELD_NAMES.has(fieldError.field)
          );
          let earliestStep = STEPS.length;
          for (const fieldError of error.fieldErrors) {
            if (KNOWN_FIELD_NAMES.has(fieldError.field)) {
              setError(fieldError.field as keyof CourseEditFormValues, {
                type: "server",
                message: fieldError.message,
              });
              earliestStep = Math.min(earliestStep, stepIndexForField(fieldError.field));
            }
          }
          if (earliestStep < STEPS.length) {
            setStepIndex(earliestStep);
          }
          if (unmapped.length > 0) {
            setPageError({
              message: "Some information couldn't be validated.",
              code: error.code,
              fieldErrors: unmapped,
            });
          }
          return;
        }

        if (error.code === "CONFLICT") {
          setError("slug", { type: "server", message: error.message });
          setStepIndex(stepIndexForField("slug"));
          return;
        }

        setPageError({ message: error.message, code: error.code });
        return;
      }

      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  const isLastStep = stepIndex === STEPS.length - 1;
  const isSubmitting = mutation.isPending;

  // Same narrowing rationale as `course-create-form.tsx`: RHF's actual
  // `register`/`errors` here are typed for the full `CourseEditFormValues`
  // but are runtime-compatible with any subset; `Path<T>` can't resolve for
  // an unconstrained generic, so each step's narrower view is asserted here.
  const basicsRegister = register as unknown as UseFormRegister<CourseBasicsFormValues>;
  const basicsErrors = errors as unknown as FieldErrors<CourseBasicsFormValues>;
  const classificationRegister = register as unknown as UseFormRegister<CourseClassificationFormValues>;
  const classificationErrors = errors as unknown as FieldErrors<CourseClassificationFormValues>;
  const enrollmentAccessRegister = register as unknown as UseFormRegister<CourseEnrollmentAccessFormValues>;
  const enrollmentAccessErrors = errors as unknown as FieldErrors<CourseEnrollmentAccessFormValues>;

  return (
    <div className="flex flex-col gap-6">
      <Stepper steps={STEPS} currentStepIndex={stepIndex} />

      {saved ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>Course details saved.</AlertDescription>
        </Alert>
      ) : null}

      {pageError ? (
        <ErrorState
          message={pageError.message}
          code={pageError.code}
          fieldErrors={pageError.fieldErrors}
          onRetry={() => setPageError(null)}
        />
      ) : null}

      <form
        className="flex flex-col gap-6"
        noValidate
        aria-busy={isSubmitting}
        onSubmit={(event) => {
          event.preventDefault();
          if (isLastStep) {
            void onSubmit(event);
          } else {
            void goNext();
          }
        }}
      >
        <span role="status" aria-live="polite" className="sr-only">
          {isSubmitting ? "Saving course details…" : ""}
        </span>

        <fieldset disabled={isSubmitting} className="flex flex-col gap-4">
          <legend className="sr-only">{STEPS[stepIndex].label}</legend>
          <h2
            ref={headingRef}
            tabIndex={-1}
            className="text-base font-medium text-foreground outline-none"
          >
            {STEPS[stepIndex].label}
          </h2>

          {stepIndex === 0 ? (
            <CourseBasicsFields
              register={basicsRegister}
              errors={basicsErrors}
              idPrefix="course-edit"
            />
          ) : null}
          {stepIndex === 1 ? (
            <CourseClassificationFields
              register={classificationRegister}
              errors={classificationErrors}
              idPrefix="course-edit"
            />
          ) : null}
          {stepIndex === 2 ? (
            <CourseEnrollmentAccessFields
              register={enrollmentAccessRegister}
              errors={enrollmentAccessErrors}
              idPrefix="course-edit"
            />
          ) : null}
        </fieldset>

        <div className="flex flex-row justify-between gap-3">
          <Button type="button" variant="outline" onClick={goBack} disabled={stepIndex === 0 || isSubmitting}>
            Back
          </Button>
          {isLastStep ? (
            <Button type="submit" disabled={isSubmitting} aria-busy={isSubmitting}>
              {isSubmitting ? "Saving…" : "Save changes"}
            </Button>
          ) : (
            <Button type="submit">Next</Button>
          )}
        </div>
      </form>
    </div>
  );
}
