"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm, useWatch, type FieldErrors, type Resolver, type UseFormRegister } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/states/error-state";
import { Stepper, type StepDefinition } from "@/components/courses/stepper";
import {
  CourseBasicsFields,
  CourseClassificationFields,
  CourseEnrollmentAccessFields,
  CoursePricingFields,
  CoursePricingModelFields,
  CourseStaffTeacherFields,
  CourseVisibilityFields,
} from "@/components/courses/course-form-fields";
import {
  useChangeCoursePricingModelForCourse,
  useCreateCourse,
  type CoursePricingModel,
} from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import type { FieldError } from "@/lib/api/types";
import {
  COURSE_CREATE_DEFAULT_VALUES,
  COURSE_STAFF_CREATE_DEFAULT_VALUES,
  courseCreateSchema,
  courseStaffCreateSchema,
  toCourseCreateRequest,
  toCourseStaffCreateRequest,
  type CourseBasicsFormValues,
  type CourseClassificationFormValues,
  type CourseEnrollmentAccessFormValues,
  type CoursePricingFormValues,
  type CourseStaffCreateFormValues,
  type CourseStaffTeacherFormValues,
} from "@/lib/validation/course";

const BASE_STEPS: StepDefinition[] = [
  { id: "basics", label: "Basics" },
  { id: "classification", label: "Classification" },
  { id: "pricing", label: "Pricing" },
  { id: "enrollment-access", label: "Enrollment & access" },
  { id: "visibility", label: "Visibility" },
];
const STAFF_TEACHER_STEP: StepDefinition = { id: "teacher", label: "Teacher" };

/** Fields validated on "Next" for each step, and which step a backend field-error maps back to. */
const BASE_STEP_FIELDS: Array<Array<keyof CourseStaffCreateFormValues>> = [
  ["name", "slug", "category", "description"],
  ["subject", "stream", "grade", "academicYear"],
  ["pricingModel", "price"],
  ["enrollmentRule", "accessDurationDays"],
  ["status"],
];

const KNOWN_FIELD_NAMES = new Set<string>([...BASE_STEP_FIELDS.flat(), "teacherId"]);

export interface CourseCreateFormProps {
  /**
   * `"teacher"` (default): the Teacher self-create flow
   * (`teacher/courses/new`) — no `teacherId` field, redirects to the new
   * course's Modules & Lessons editor on success.
   *
   * `"staff"`: PAR-05-02's staff-facing create flow
   * (`tenant-admin/courses/new`) — adds the required `teacherId` step and
   * redirects to the new course's workspace (Overview tab) on success.
   */
  mode?: "teacher" | "staff";
}

/**
 * Course Builder create flow, shared by the Teacher and Staff create routes
 * (see `mode` above). Multi-step form over a single RHF instance: "Next"
 * validates only the current step's fields via `trigger`, "Back" navigates
 * freely. Focus moves to the new step's heading on every transition; the
 * step indicator exposes `aria-current="step"`.
 *
 * Always uses `CourseStaffCreateFormValues` (the `teacherId`-inclusive
 * superset) as RHF's value type regardless of `mode`, resolved against
 * whichever schema matches `mode` — this keeps a single `useForm` call
 * (required by the Rules of Hooks) working for both variants; in
 * `mode="teacher"`, `teacherId` is simply never rendered, never validated
 * (`courseCreateSchema` has no such field), and never sent.
 *
 * `CourseCreateRequest` has no `pricingModel` field on the backend — every
 * course is created `ONE_TIME` (see `toCourseCreateRequest`'s doc comment).
 * When a different model was chosen on the Pricing step, this composes a
 * second call, `PATCH .../pricing-model`, immediately after `POST
 * /v1/courses` succeeds, before navigating away. If that second call fails,
 * the course itself was still created successfully (never rolled back — an
 * extra "undo the creation" DELETE call would be its own new failure mode,
 * and the course is easy to fix up afterward) — the error is surfaced with a
 * link straight to the new course's Fees & Billing tab instead of silently
 * discarding the mismatch or leaving the user stuck with no visible
 * feedback.
 */
export function CourseCreateForm({ mode = "teacher" }: CourseCreateFormProps) {
  const router = useRouter();
  const isStaff = mode === "staff";
  const STEPS = isStaff ? [...BASE_STEPS, STAFF_TEACHER_STEP] : BASE_STEPS;
  const STEP_FIELDS: Array<Array<keyof CourseStaffCreateFormValues>> = isStaff
    ? [...BASE_STEP_FIELDS, ["teacherId"]]
    : BASE_STEP_FIELDS;

  function stepIndexForField(field: string): number {
    const index = STEP_FIELDS.findIndex((fields) => (fields as string[]).includes(field));
    return index === -1 ? 0 : index;
  }

  const [stepIndex, setStepIndex] = useState(0);
  const [pageError, setPageError] = useState<{
    message: string;
    code?: string;
    fieldErrors?: FieldError[];
    createdCourseId?: string;
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
  } = useForm<CourseStaffCreateFormValues>({
    // `courseCreateSchema` (Teacher mode) infers a narrower type missing
    // `teacherId` — safe to widen here since RHF's resolver is only ever
    // called with a `CourseStaffCreateFormValues`-shaped object regardless
    // of `mode`, and `courseCreateSchema`'s own validation never reads
    // `teacherId` in the first place.
    resolver: (isStaff
      ? zodResolver(courseStaffCreateSchema)
      : zodResolver(courseCreateSchema)) as unknown as Resolver<CourseStaffCreateFormValues>,
    defaultValues: isStaff ? COURSE_STAFF_CREATE_DEFAULT_VALUES : { ...COURSE_CREATE_DEFAULT_VALUES, teacherId: "" },
  });

  const mutation = useCreateCourse();
  const pricingModelMutation = useChangeCoursePricingModelForCourse();

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
      const created = await mutation.mutateAsync(
        isStaff ? toCourseStaffCreateRequest(values) : toCourseCreateRequest(values)
      );

      if (values.pricingModel !== "ONE_TIME") {
        try {
          await pricingModelMutation.mutateAsync({
            courseId: created.id,
            pricingModel: values.pricingModel as CoursePricingModel,
          });
        } catch (pricingModelError) {
          setPageError({
            message: isApiClientError(pricingModelError)
              ? `The course was created, but setting its pricing model failed: ${pricingModelError.message} You can change it from the course's Fees & Billing tab.`
              : "The course was created, but setting its pricing model failed. You can change it from the course's Fees & Billing tab.",
            createdCourseId: created.id,
          });
          return;
        }
      }

      router.push(
        isStaff
          ? `/tenant-admin/courses/${created.id}?created=1`
          : `/teacher/courses/${created.id}/modules?created=1`
      );
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.fieldErrors.length > 0) {
          const unmapped = error.fieldErrors.filter(
            (fieldError) => !KNOWN_FIELD_NAMES.has(fieldError.field)
          );
          let earliestStep = STEPS.length;
          for (const fieldError of error.fieldErrors) {
            if (KNOWN_FIELD_NAMES.has(fieldError.field)) {
              setError(fieldError.field as keyof CourseStaffCreateFormValues, {
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
  const pricingModel = useWatch({ control, name: "pricingModel" });
  const isLastStep = stepIndex === STEPS.length - 1;
  const isSubmitting = mutation.isPending || pricingModelMutation.isPending;

  // Each step component's `register`/`errors` are typed to that step's own
  // narrower schema; RHF's actual `register`/`errors` here (typed for the
  // full `CourseStaffCreateFormValues`) are runtime-compatible with any
  // subset — `register` doesn't care about sibling fields, and `errors` is a
  // plain superset object — but `Path<T>` can't be resolved for an
  // unconstrained generic, so the narrowing is asserted explicitly per step.
  const basicsRegister = register as unknown as UseFormRegister<CourseBasicsFormValues>;
  const basicsErrors = errors as unknown as FieldErrors<CourseBasicsFormValues>;
  const classificationRegister = register as unknown as UseFormRegister<CourseClassificationFormValues>;
  const classificationErrors = errors as unknown as FieldErrors<CourseClassificationFormValues>;
  const pricingRegister = register as unknown as UseFormRegister<CoursePricingFormValues>;
  const pricingErrors = errors as unknown as FieldErrors<CoursePricingFormValues>;
  const enrollmentAccessRegister = register as unknown as UseFormRegister<CourseEnrollmentAccessFormValues>;
  const enrollmentAccessErrors = errors as unknown as FieldErrors<CourseEnrollmentAccessFormValues>;
  const staffTeacherRegister = register as unknown as UseFormRegister<CourseStaffTeacherFormValues>;
  const staffTeacherErrors = errors as unknown as FieldErrors<CourseStaffTeacherFormValues>;

  return (
    <div className="flex flex-col gap-6">
      <Stepper steps={STEPS} currentStepIndex={stepIndex} />

      {pageError ? (
        <ErrorState
          message={pageError.message}
          code={pageError.code}
          fieldErrors={pageError.fieldErrors}
          onRetry={pageError.createdCourseId ? undefined : () => setPageError(null)}
        />
      ) : null}
      {pageError?.createdCourseId ? (
        <Button
          type="button"
          variant="outline"
          render={
            <Link
              href={
                isStaff
                  ? `/tenant-admin/courses/${pageError.createdCourseId}/billing`
                  : `/teacher/courses/${pageError.createdCourseId}/edit`
              }
            />
          }
        >
          Go to the new course
        </Button>
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
            <div className="flex flex-col gap-4">
              <CoursePricingModelFields
                errors={errors}
                idPrefix="course-create"
                value={pricingModel}
                onChange={(value) => setValue("pricingModel", value ?? "", { shouldValidate: true })}
              />
              {pricingModel === "ONE_TIME" ? (
                <CoursePricingFields
                  register={pricingRegister}
                  errors={pricingErrors}
                  idPrefix="course-create"
                />
              ) : null}
            </div>
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
          {stepIndex === 5 && isStaff ? (
            <CourseStaffTeacherFields
              register={staffTeacherRegister}
              errors={staffTeacherErrors}
              idPrefix="course-create"
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
