"use client";

import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Filter, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import type { AttendanceListParams } from "@/lib/api/attendance";
import {
  ALL_COURSES_VALUE,
  attendanceFilterSchema,
  ATTENDANCE_FILTER_DEFAULT_VALUES,
  toAttendanceQueryParams,
  type AttendanceFilterFormValues,
} from "@/lib/validation/attendance";

export interface AttendanceCourseOption {
  id: string;
  label: string;
}

interface AttendanceFilterFormProps {
  /** Unique per page instance, so ids don't collide if this form is ever rendered twice on one page. */
  idPrefix: string;
  /** Populated from a backend endpoint already scoped to the caller's own courses (Teacher/Student) or tenant-wide (staff) — never an unfiltered list filtered client-side. */
  courseOptions: AttendanceCourseOption[];
  onApply: (params: Pick<AttendanceListParams, "courseId" | "from" | "to">) => void;
  onClear: () => void;
  /** Disables Apply/Clear while the list query they drive is already fetching (initial load or a background page/filter refetch), so a rapid double-click can't fire overlapping requests. Defaults to `false`. */
  disabled?: boolean;
}

/**
 * Shared course + date-range filter form for the three Attendance list
 * screens (Teacher Reports, Student My Attendance, Tenant Admin Reports) —
 * extracted here rather than duplicated per role group, per
 * `.claude/rules/frontend.md`'s "needs data/behavior from more than one role
 * group" signal. React Hook Form + Zod (`lib/validation/attendance.ts`) per
 * `.claude/rules/frontend.md`'s "every form uses RHF + a Zod schema" rule —
 * the only client-side rule enforced is "from must not be after to"; the
 * backend independently re-validates. The course `Select` follows
 * `course-form-fields.tsx`'s established `watch`/`setValue` wiring (a
 * `base-ui` `Select` isn't a bare `<input>`, so it can't use `register`
 * directly).
 */
export function AttendanceFilterForm({
  idPrefix,
  courseOptions,
  onApply,
  onClear,
  disabled = false,
}: AttendanceFilterFormProps) {
  const {
    register,
    control,
    setValue,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<AttendanceFilterFormValues>({
    resolver: zodResolver(attendanceFilterSchema),
    defaultValues: ATTENDANCE_FILTER_DEFAULT_VALUES,
  });

  const courseId = useWatch({ control, name: "courseId" });

  const onSubmit = handleSubmit((values) => {
    onApply(toAttendanceQueryParams(values));
  });

  function handleClear() {
    reset(ATTENDANCE_FILTER_DEFAULT_VALUES);
    onClear();
  }

  return (
    <form
      noValidate
      onSubmit={onSubmit}
      className="flex flex-col gap-3 rounded-lg border border-border p-4 sm:flex-row sm:flex-wrap sm:items-end"
    >
      <div className="flex flex-1 flex-col gap-1.5 sm:min-w-48">
        <Label htmlFor={`${idPrefix}-course`}>Course</Label>
        <Select
          value={courseId}
          onValueChange={(value) =>
            setValue("courseId", value ?? ALL_COURSES_VALUE, { shouldValidate: true })
          }
        >
          <SelectTrigger id={`${idPrefix}-course`} className="w-full">
            <SelectValue placeholder="All courses">
              {(selected: string | null) =>
                selected && selected !== ALL_COURSES_VALUE
                  ? courseOptions.find((c) => c.id === selected)?.label ?? selected
                  : "All courses"
              }
            </SelectValue>
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL_COURSES_VALUE}>All courses</SelectItem>
            {courseOptions.map((course) => (
              <SelectItem key={course.id} value={course.id}>
                {course.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      <fieldset className="flex flex-col gap-1.5 border-0 p-0 m-0 sm:flex-row sm:gap-3">
        <legend className="sr-only">Date range</legend>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-from`}>From</Label>
          <Input
            id={`${idPrefix}-from`}
            type="date"
            aria-invalid={!!errors.to}
            aria-describedby={errors.to ? `${idPrefix}-date-range-error` : undefined}
            {...register("from")}
          />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-to`}>To</Label>
          <Input
            id={`${idPrefix}-to`}
            type="date"
            aria-invalid={!!errors.to}
            aria-describedby={errors.to ? `${idPrefix}-date-range-error` : undefined}
            {...register("to")}
          />
        </div>
        {errors.to ? (
          <p
            id={`${idPrefix}-date-range-error`}
            role="alert"
            className="text-xs text-destructive sm:basis-full"
          >
            {errors.to.message}
          </p>
        ) : null}
      </fieldset>
      <div className="flex gap-2">
        <Button type="submit" size="sm" disabled={disabled} aria-busy={disabled}>
          <Filter aria-hidden="true" />
          Apply filters
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={handleClear}
          disabled={disabled}
          aria-busy={disabled}
        >
          <X aria-hidden="true" />
          Clear filters
        </Button>
      </div>
    </form>
  );
}
