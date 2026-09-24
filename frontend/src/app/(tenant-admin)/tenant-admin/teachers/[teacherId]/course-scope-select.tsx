"use client";

import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Label } from "@/components/ui/label";
import type { CourseResponse } from "@/lib/api/courses";

/**
 * Shared "pick one of this teacher's own courses" control — reused by the
 * Roster/Attendance/Exams tabs on the Wave 3 Teacher Detail page, none of
 * which have a dedicated teacherId-scoped read on the backend (unlike
 * Student Detail's studentId-scoped reads); each instead scopes an existing
 * courseId-filtered endpoint to one of this teacher's own courses, picked
 * here.
 */
export function CourseScopeSelect({
  idPrefix,
  courses,
  value,
  onChange,
}: {
  idPrefix: string;
  courses: CourseResponse[];
  value: string;
  onChange: (courseId: string) => void;
}) {
  return (
    <div className="flex flex-col gap-1.5 sm:w-72">
      <Label htmlFor={`${idPrefix}-course`}>Course</Label>
      <Select value={value} onValueChange={(next) => onChange(next ?? "")}>
        <SelectTrigger id={`${idPrefix}-course`} className="w-full">
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
    </div>
  );
}
