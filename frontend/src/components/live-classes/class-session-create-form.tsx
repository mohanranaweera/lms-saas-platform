"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { ErrorState } from "@/components/states/error-state";
import { LiveRegion } from "@/components/ui/live-region";
import { useCourses, useCourseLessons, useCourseModules } from "@/lib/api/courses";
import { useCreateClassSession } from "@/lib/api/class-sessions";
import { isApiClientError } from "@/lib/api/error";
import { datetimeLocalToIso } from "@/lib/datetime";
import type { FieldError } from "@/lib/api/types";
import {
  CLASS_SESSION_FORM_DEFAULT_VALUES,
  classSessionFormSchema,
  type ClassSessionFormValues,
} from "@/lib/validation/live-class";

const KNOWN_FIELD_NAMES = new Set<string>(["courseId", "lessonId", "title", "description", "scheduledStart", "scheduledEnd"]);

const NO_LESSON_VALUE = "__no-lesson__";
const NO_MODULE_VALUE = "__no-module__";

/**
 * Teacher "Schedule Live Class" form (Wave 4 plan §5) — course picker
 * (this Teacher's own courses only, `useCourses()`'s server-enforced
 * ownership scoping, per `.claude/rules/ui-ux.md` §1), an optional module ->
 * lesson cascade (mirrors `attendance-filter-form.tsx`/`MarkAttendancePanel`'s
 * established course -> module -> lesson pattern; a session need not be tied
 * to a specific lesson at all), title, description, and start/end
 * `datetime-local` pickers.
 *
 * Unsaved-change protection mirrors `(auth)/register/page.tsx`'s established
 * pattern exactly: a `beforeunload` listener while the form is dirty, plus a
 * `window.confirm` guard on the "Back" link's in-app navigation (no
 * confirmation-dialog primitive already exists in this codebase for
 * "leave a dirty form via in-app navigation").
 */
export function ClassSessionCreateForm() {
  const router = useRouter();
  const coursesQuery = useCourses();
  const [selectedModuleId, setSelectedModuleId] = useState<string>(NO_MODULE_VALUE);
  const [pageError, setPageError] = useState<{
    message: string;
    code?: string;
    fieldErrors?: FieldError[];
  } | null>(null);

  const {
    register,
    handleSubmit,
    control,
    setValue,
    setError,
    formState: { errors, isDirty },
  } = useForm<ClassSessionFormValues>({
    resolver: zodResolver(classSessionFormSchema),
    defaultValues: CLASS_SESSION_FORM_DEFAULT_VALUES,
  });

  const courseId = useWatch({ control, name: "courseId" });
  const lessonId = useWatch({ control, name: "lessonId" });

  const modulesQuery = useCourseModules(courseId);
  const lessonsQuery = useCourseLessons(courseId, selectedModuleId, {
    enabled: selectedModuleId !== NO_MODULE_VALUE,
  });

  const mutation = useCreateClassSession();
  const isSubmitting = mutation.isPending;

  // Changing the course invalidates any previously chosen module/lesson —
  // both are scoped to the course they belong to. Reset happens directly in
  // the course `Select`'s `onValueChange` handler (below) rather than an
  // effect keyed on `courseId`, to avoid a synchronous-setState-in-effect
  // cascading-render lint violation.
  function handleCourseChange(value: string | null) {
    setValue("courseId", value ?? "", { shouldValidate: true, shouldDirty: true });
    setSelectedModuleId(NO_MODULE_VALUE);
    setValue("lessonId", "");
  }

  useEffect(() => {
    if (!isDirty) return;
    function handleBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault();
    }
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [isDirty]);

  function confirmDiscardUnsavedChanges(): boolean {
    if (!isDirty) return true;
    return window.confirm("You have unsaved changes to this schedule form. Leave without saving?");
  }

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      const created = await mutation.mutateAsync({
        courseId: values.courseId,
        lessonId: values.lessonId ? values.lessonId : undefined,
        title: values.title,
        description: values.description ? values.description : undefined,
        scheduledStart: datetimeLocalToIso(values.scheduledStart),
        scheduledEnd: datetimeLocalToIso(values.scheduledEnd),
      });
      router.push(`/teacher/live-classes/${created.id}?created=1`);
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.fieldErrors.length > 0) {
          const unmapped = error.fieldErrors.filter((fieldError) => !KNOWN_FIELD_NAMES.has(fieldError.field));
          for (const fieldError of error.fieldErrors) {
            if (KNOWN_FIELD_NAMES.has(fieldError.field)) {
              setError(fieldError.field as keyof ClassSessionFormValues, {
                type: "server",
                message: fieldError.message,
              });
            }
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
        setPageError({ message: error.message, code: error.code });
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  const courseOptions = coursesQuery.data?.content ?? [];
  const moduleOptions = modulesQuery.data ?? [];
  const lessonOptions = lessonsQuery.data ?? [];

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/teacher/live-classes"
          onClick={(event) => {
            if (!confirmDiscardUnsavedChanges()) {
              event.preventDefault();
            }
          }}
          className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          ← Back to live classes
        </Link>
      </div>

      {pageError ? (
        <ErrorState
          message={pageError.message}
          code={pageError.code}
          fieldErrors={pageError.fieldErrors}
          onRetry={() => setPageError(null)}
        />
      ) : null}

      <form className="flex flex-col gap-4" noValidate aria-busy={isSubmitting} onSubmit={onSubmit}>
        <LiveRegion message={isSubmitting ? "Scheduling your class…" : ""} />
        <fieldset disabled={isSubmitting} className="flex flex-col gap-4">
          <legend className="sr-only">Schedule a live class</legend>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="live-class-course">Course</Label>
            <Select value={courseId} onValueChange={handleCourseChange}>
              <SelectTrigger id="live-class-course" aria-invalid={!!errors.courseId} className="w-full">
                <SelectValue placeholder="Select a course">
                  {(selected: string | null) =>
                    courseOptions.find((course) => course.id === selected)?.name ?? "Select a course"
                  }
                </SelectValue>
              </SelectTrigger>
              <SelectContent>
                {courseOptions.map((course) => (
                  <SelectItem key={course.id} value={course.id}>
                    {course.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {errors.courseId ? (
              <p role="alert" className="text-xs text-destructive">
                {errors.courseId.message}
              </p>
            ) : null}
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="live-class-module">Module (optional — to link a lesson)</Label>
              <Select
                value={selectedModuleId}
                onValueChange={(value) => setSelectedModuleId(value ?? NO_MODULE_VALUE)}
              >
                <SelectTrigger id="live-class-module" className="w-full" disabled={!courseId}>
                  <SelectValue placeholder="No module selected">
                    {(selected: string | null) =>
                      selected && selected !== NO_MODULE_VALUE
                        ? moduleOptions.find((m) => m.id === selected)?.title ?? selected
                        : "No module selected"
                    }
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NO_MODULE_VALUE}>No module selected</SelectItem>
                  {moduleOptions.map((module) => (
                    <SelectItem key={module.id} value={module.id}>
                      {module.title}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="live-class-lesson">Lesson (optional)</Label>
              <Select
                value={lessonId || NO_LESSON_VALUE}
                onValueChange={(value) =>
                  setValue("lessonId", value && value !== NO_LESSON_VALUE ? value : "", { shouldDirty: true })
                }
              >
                <SelectTrigger id="live-class-lesson" className="w-full" disabled={selectedModuleId === NO_MODULE_VALUE}>
                  <SelectValue placeholder="No lesson linked">
                    {(selected: string | null) =>
                      selected && selected !== NO_LESSON_VALUE
                        ? lessonOptions.find((l) => l.id === selected)?.title ?? selected
                        : "No lesson linked"
                    }
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NO_LESSON_VALUE}>No lesson linked</SelectItem>
                  {lessonOptions.map((lesson) => (
                    <SelectItem key={lesson.id} value={lesson.id}>
                      {lesson.title}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="live-class-title">Title</Label>
            <Input
              id="live-class-title"
              aria-required="true"
              aria-invalid={!!errors.title}
              aria-describedby={errors.title ? "live-class-title-error" : undefined}
              {...register("title")}
            />
            {errors.title ? (
              <p id="live-class-title-error" role="alert" className="text-xs text-destructive">
                {errors.title.message}
              </p>
            ) : null}
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor="live-class-description">Description (optional)</Label>
            <Textarea
              id="live-class-description"
              aria-invalid={!!errors.description}
              aria-describedby={errors.description ? "live-class-description-error" : undefined}
              {...register("description")}
            />
            {errors.description ? (
              <p id="live-class-description-error" role="alert" className="text-xs text-destructive">
                {errors.description.message}
              </p>
            ) : null}
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="live-class-start">Start</Label>
              <Input
                id="live-class-start"
                type="datetime-local"
                aria-required="true"
                aria-invalid={!!errors.scheduledStart}
                aria-describedby={errors.scheduledStart ? "live-class-start-error" : undefined}
                {...register("scheduledStart")}
              />
              {errors.scheduledStart ? (
                <p id="live-class-start-error" role="alert" className="text-xs text-destructive">
                  {errors.scheduledStart.message}
                </p>
              ) : null}
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="live-class-end">End</Label>
              <Input
                id="live-class-end"
                type="datetime-local"
                aria-required="true"
                aria-invalid={!!errors.scheduledEnd}
                aria-describedby={errors.scheduledEnd ? "live-class-end-error" : undefined}
                {...register("scheduledEnd")}
              />
              {errors.scheduledEnd ? (
                <p id="live-class-end-error" role="alert" className="text-xs text-destructive">
                  {errors.scheduledEnd.message}
                </p>
              ) : null}
            </div>
          </div>
        </fieldset>

        <Button type="submit" disabled={isSubmitting} aria-busy={isSubmitting} className="w-full sm:w-fit">
          {isSubmitting ? "Scheduling…" : "Schedule class"}
        </Button>
      </form>
    </div>
  );
}
