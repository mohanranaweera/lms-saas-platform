"use client";

import { useForm, Controller } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
  SheetDescription,
  SheetFooter,
} from "@/components/ui/sheet";
import { Button } from "@/components/ui/button";
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
import { LoadingState } from "@/components/states/loading-state";
import { useCourses } from "@/lib/api/courses";
import { useEnrollStudent, type StudentResponse } from "@/lib/api/students";
import {
  enrollStudentSchema,
  type EnrollStudentFormValues,
} from "@/lib/validation/student-actions";
import { isApiClientError } from "@/lib/api/error";

interface EnrollStudentSheetProps {
  student: StudentResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onEnrolled?: () => void;
}

/**
 * "Enroll" action (Wave 3, PAR-03-05 — ADR-016 decision 1: a real staff-
 * granted `Order`+`Payment`, not a bypass of the payment/ledger trail). Full-
 * screen-on-mobile `Sheet` (course picker + mandatory reason is "meaningful
 * input," per `.claude/rules/ui-ux.md` §5), mirroring `create-student-
 * sheet.tsx`'s structure/dismissal-guard/error-mapping conventions.
 */
export function EnrollStudentSheet({
  student,
  open,
  onOpenChange,
  onEnrolled,
}: EnrollStudentSheetProps) {
  const coursesQuery = useCourses({ size: 100 }, { enabled: open });
  const mutation = useEnrollStudent(student.id);

  const {
    control,
    register,
    handleSubmit,
    reset,
    setError,
    formState: { errors },
  } = useForm<EnrollStudentFormValues>({
    resolver: zodResolver(enrollStudentSchema),
    defaultValues: { courseId: "", reason: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    try {
      await mutation.mutateAsync(values);
      reset();
      onEnrolled?.();
      onOpenChange(false);
    } catch (error) {
      if (isApiClientError(error)) {
        if (error.fieldErrors.length > 0) {
          for (const fieldError of error.fieldErrors) {
            if (fieldError.field === "courseId" || fieldError.field === "reason") {
              setError(fieldError.field, { type: "server", message: fieldError.message });
            }
          }
          return;
        }
        setError("reason", { type: "server", message: error.message });
        return;
      }
      setError("reason", { type: "server", message: "An unexpected error occurred. Please try again." });
    }
  });

  const courses = coursesQuery.data?.content ?? [];

  return (
    <Sheet
      open={open}
      onOpenChange={(next) => {
        if (!next && mutation.isPending) return;
        if (!next) {
          reset();
          mutation.reset();
        }
        onOpenChange(next);
      }}
    >
      <SheetContent
        side="right"
        className="w-full data-[side=right]:w-full data-[side=right]:sm:max-w-full data-[side=right]:md:max-w-md"
      >
        <SheetHeader>
          <SheetTitle>Enroll {student.name}</SheetTitle>
          <SheetDescription>
            Grants access to a course outside the normal checkout flow. This creates a real,
            confirmed payment record (staff-granted) — a reason is required for the audit trail.
          </SheetDescription>
        </SheetHeader>
        <div className="flex flex-1 flex-col gap-4 overflow-y-auto px-4">
          {coursesQuery.status === "pending" ? (
            <LoadingState label="Loading courses…" />
          ) : coursesQuery.status === "error" ? (
            <ErrorState
              message={
                isApiClientError(coursesQuery.error)
                  ? coursesQuery.error.message
                  : "Couldn't load the course list."
              }
              onRetry={() => coursesQuery.refetch()}
            />
          ) : (
            <form
              id="enroll-student-form"
              className="flex flex-col gap-4"
              onSubmit={onSubmit}
              aria-busy={mutation.isPending}
              noValidate
            >
              <span role="status" aria-live="polite" className="sr-only">
                {mutation.isPending ? "Enrolling…" : ""}
              </span>
              <fieldset disabled={mutation.isPending} className="flex flex-col gap-4">
                <legend className="sr-only">Enroll {student.name}</legend>
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="enroll-student-course">Course</Label>
                  <Controller
                    control={control}
                    name="courseId"
                    render={({ field }) => (
                      <Select value={field.value} onValueChange={(value) => field.onChange(value ?? "")}>
                        <SelectTrigger id="enroll-student-course" className="w-full">
                          <SelectValue placeholder="Choose a course" />
                        </SelectTrigger>
                        <SelectContent>
                          {courses.map((course) => (
                            <SelectItem key={course.id} value={course.id}>
                              {course.name}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    )}
                  />
                  {errors.courseId ? (
                    <p role="alert" className="text-xs text-destructive">
                      {errors.courseId.message}
                    </p>
                  ) : null}
                </div>
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="enroll-student-reason">
                    Reason<span className="font-normal text-muted-foreground"> (required)</span>
                  </Label>
                  <Textarea
                    id="enroll-student-reason"
                    aria-required="true"
                    aria-invalid={errors.reason ? true : undefined}
                    aria-describedby={errors.reason ? "enroll-student-reason-error" : undefined}
                    placeholder="e.g. Scholarship grant approved by finance"
                    {...register("reason")}
                  />
                  {errors.reason ? (
                    <p id="enroll-student-reason-error" role="alert" className="text-xs text-destructive">
                      {errors.reason.message}
                    </p>
                  ) : null}
                </div>
              </fieldset>
            </form>
          )}
        </div>
        <SheetFooter className="flex-row justify-end gap-2">
          <Button type="button" variant="outline" disabled={mutation.isPending} onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" form="enroll-student-form" disabled={mutation.isPending || coursesQuery.status !== "success"}>
            {mutation.isPending ? "Enrolling…" : "Enroll"}
          </Button>
        </SheetFooter>
      </SheetContent>
    </Sheet>
  );
}
