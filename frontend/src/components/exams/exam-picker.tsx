"use client";

import { useState } from "react";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useCourses } from "@/lib/api/courses";
import { useCourseExams, useExam } from "@/lib/api/exams";

/**
 * Course → exam dropdown picker, shared by the Teacher Marking Queue and
 * Results Publishing screens (MVP-017 plan §11 screens #6/#7). Replaces the
 * earlier manual exam-id-paste workaround (`ExamLookupForm`, removed) now
 * that `GET /courses/{courseId}/exams` exists — see `lib/api/exams.ts`'s doc
 * comment for the closed-gap record.
 *
 * If `initialExamId` is given (e.g. a `?examId=...` deep link, or the
 * `examId` route param on a revisit), this resolves that exam's own
 * `courseId` first so both dropdowns land on the right selection without
 * requiring the caller to already know it.
 */
export function ExamPicker({
  idPrefix,
  initialExamId,
  onSelect,
}: {
  idPrefix: string;
  initialExamId?: string;
  onSelect: (examId: string) => void;
}) {
  const coursesQuery = useCourses();
  const [manualCourseId, setManualCourseId] = useState<string | null>(null);
  const [examId, setExamId] = useState(initialExamId ?? "");

  const deepLinkExamQuery = useExam(manualCourseId ? "" : (initialExamId ?? ""));
  const courseId = manualCourseId ?? deepLinkExamQuery.data?.courseId ?? "";

  const examsQuery = useCourseExams(courseId, { size: 100 });

  function handleCourseChange(value: string) {
    setManualCourseId(value);
    setExamId("");
  }

  function handleExamChange(value: string) {
    setExamId(value);
    onSelect(value);
  }

  return (
    <QueryStateBoundary
      query={coursesQuery}
      loadingLabel="Loading your courses…"
      loginPath="/login"
      permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
      isEmpty={(data) => data.content.length === 0}
      emptyState={{ title: "No courses yet", description: "You need at least one course to browse exams." }}
    >
      {(courses) => (
        <div className="flex flex-col gap-4 sm:flex-row sm:items-end">
          <div className="flex flex-col gap-1.5 sm:max-w-sm sm:flex-1">
            <Label htmlFor={`${idPrefix}-course`}>Course</Label>
            <Select value={courseId} onValueChange={(value) => value && handleCourseChange(value)}>
              <SelectTrigger id={`${idPrefix}-course`} className="w-full">
                <SelectValue placeholder="Select a course" />
              </SelectTrigger>
              <SelectContent>
                {courses.content.map((course) => (
                  <SelectItem key={course.id} value={course.id}>
                    {course.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {courseId ? (
            <div className="flex flex-col gap-1.5 sm:max-w-sm sm:flex-1">
              <Label htmlFor={`${idPrefix}-exam`}>Exam</Label>
              <Select value={examId} onValueChange={(value) => value && handleExamChange(value)}>
                <SelectTrigger id={`${idPrefix}-exam`} className="w-full">
                  <SelectValue placeholder={examsQuery.isPending ? "Loading exams…" : "Select an exam"} />
                </SelectTrigger>
                <SelectContent>
                  {(examsQuery.data?.content ?? []).map((exam) => (
                    <SelectItem key={exam.id} value={exam.id}>
                      {exam.title}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          ) : null}
        </div>
      )}
    </QueryStateBoundary>
  );
}
