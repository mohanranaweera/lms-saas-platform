"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { useForm, useWatch, type FieldErrors, type UseFormRegister } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/states/error-state";
import { Stepper, type StepDefinition } from "@/components/courses/stepper";
import {
  CourseBasicsFields,
  CourseClassificationFields,
  CourseEnrollmentAccessFields,
  CoursePricingFields,
  CourseVisibilityFields,
} from "@/components/courses/course-form-fields";
import { useCreateCourse } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import {
  COURSE_CREATE_DEFAULT_VALUES,
  courseCreateSchema,
  toCourseCreateRequest,
  type CourseBasicsFormValues,
  type CourseClassificationFormValues,
  type CourseCreateFormValues,
  type CourseEnrollmentAccessFormValues,
  type CoursePricingFormValues,
} from "@/lib/validation/course";

const STEPS: StepDefinition[] = [
  { id: "basics", label: "Basics" },
  { id: "classification", label: "Classification" },
  { id: "pricing", label: "Pricing" },
  { id: "enrollment-access", label: "Enrollment & access" },
  { id: "visibility", label: "Visibility" },
];

/** Fields validated on "Next" for each step, and which step a backend field-error maps back to. */
const STEP_FIELDS: Array<Array<keyof CourseCreateFormValues>> = [
  ["name", "slug", "category", "description"],
  ["subject", "stream", "grade", "academicYear"],
  ["price"],
  ["enrollmentRule", "accessDurationDays"],
  ["status"],
];

function stepIndexForField(field: string): number {
  const index = STEP_FIELDS.findIndex((fields) => (fields as string[]).includes(field));
  return index === -1 ? 0 : index;
}

const KNOWN_FIELD_NAMES = new Set<string>(STEP_FIELDS.flat());

/**
 * Teacher-only Course Builder create flow (Tenant Admin has no create-course
 * screen at MVP). Multi-step form over a single RHF instance: "Next"
 * validates only the current step's fields via `trigger`, "Back" navigates
 * freely. Focus moves to the new step's heading on every transition; the
 * step indicator exposes `aria-current="step"`.
 */
export function CourseCreateForm() {
  const router = useRouter();
  const [stepIndex, setStepIndex] = useState(0);
  const [pageError, setPageError] = useState<{
    message: string;
    code?: string;
    fieldErrors?: FieldError[];
  } | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  const {
    register,
    handleSubmit,
    trigger,
    setError,
    control,
    setValue,
    formState: { errors },
  } = useForm<CourseCreateFormValues>({
    resolver: zodResolver(courseCreateSchema),
    defaultValues: COURSE_CREATE_DEFAULT_VALUES,
  });

  const mutation = useCreateCourse();

  useEffect(() => {
    headingRef.current?.focus();
  }, [stepIndex]);

  const goNext = async () => {
    const valid = await trigger(STEP_FIELDS[stepIndex]);
    if (valid) {
      setStepIndex((index) => Math.min(index + 1, STEPS.length - 1));
    }
  };

  const goBack = () => {
    setStepIndex((index) => Math.max(index - 1, 0));
  };

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      const created = await mutation.mutateAsync(toCourseCreateRequest(values));
      router.push(`/teacher/courses/${created.id}/modules?created=1`);
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.fieldErrors.length > 0) {
          const unmapped = error.fieldErrors.filter(
            (fieldError) => !KNOWN_FIELD_NAMES.has(fieldError.field)
          );
          let earliestStep = STEPS.length;
          for (const fieldError of error.fieldErrors) {
            if (KNOWN_FIELD_NAMES.has(fieldError.field)) {
              setError(fieldError.field as keyof CourseCreateFormValues, {
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

  const status = useWatch({ control, name: "status" });
  const isLastStep = stepIndex === STEPS.length - 1;
  const isSubmitting = mutation.isPending;

  // Each step component's `register`/`errors` are typed to that step's own
  // narrower schema; RHF's actual `register`/`errors` here (typed for the
  // full `CourseCreateFormValues`) are runtime-compatible with any subset —
  // `register` doesn't care about sibling fields, and `errors` is a plain
  // superset object — but `Path<T>` can't be resolved for an unconstrained
  // generic, so the narrowing is asserted explicitly per step instead.
  const basicsRegister = register as unknown as UseFormRegister<CourseBasicsFormValues>;
  const basicsErrors = errors as unknown as FieldErrors<CourseBasicsFormValues>;
  const classificationRegister = register as unknown as UseFormRegister<CourseClassificationFormValues>;
  const classificationErrors = errors as unknown as FieldErrors<CourseClassificationFormValues>;
  const pricingRegister = register as unknown as UseFormRegister<CoursePricingFormValues>;
  const pricingErrors = errors as unknown as FieldErrors<CoursePricingFormValues>;
  const enrollmentAccessRegister = register as unknown as UseFormRegister<CourseEnrollmentAccessFormValues>;
  const enrollmentAccessErrors = errors as unknown as FieldErrors<CourseEnrollmentAccessFormValues>;

  return (
    <div className="flex flex-col gap-6">
      <Stepper steps={STEPS} currentStepIndex={stepIndex} />

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
          {isSubmitting ? "Creating your course…" : ""}
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
              idPrefix="course-create"
            />
          ) : null}
          {stepIndex === 1 ? (
            <CourseClassificationFields
              register={classificationRegister}
              errors={classificationErrors}
              idPrefix="course-create"
            />
          ) : null}
          {stepIndex === 2 ? (
            <CoursePricingFields
              register={pricingRegister}
              errors={pricingErrors}
              idPrefix="course-create"
            />
          ) : null}
          {stepIndex === 3 ? (
            <CourseEnrollmentAccessFields
              register={enrollmentAccessRegister}
              errors={enrollmentAccessErrors}
              idPrefix="course-create"
            />
          ) : null}
          {stepIndex === 4 ? (
            <CourseVisibilityFields
              errors={errors}
              idPrefix="course-create"
              value={status}
              onChange={(value) => setValue("status", value ?? "", { shouldValidate: true })}
            />
          ) : null}
        </fieldset>

        <div className="flex flex-row justify-between gap-3">
          <Button type="button" variant="outline" onClick={goBack} disabled={stepIndex === 0 || isSubmitting}>
            Back
          </Button>
          {isLastStep ? (
            <Button type="submit" disabled={isSubmitting} aria-busy={isSubmitting}>
              {isSubmitting ? "Creating…" : "Create course"}
            </Button>
          ) : (
            <Button type="submit">Next</Button>
          )}
        </div>
      </form>
    </div>
  );
}
