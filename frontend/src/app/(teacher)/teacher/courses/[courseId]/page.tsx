"use client";

import { Suspense } from "react";
import { useParams } from "next/navigation";
import { LoadingState } from "@/components/states/loading-state";
import { TeacherCourseView } from "@/components/courses/teacher/teacher-course-view";

/**
 * Teacher Portal "Open course" (TP-02): the student course layout for an
 * assigned course. Management actions live behind "Manage course".
 */
export default function TeacherCoursePage() {
  const { courseId } = useParams<{ courseId: string }>();
  return (
    <Suspense fallback={<LoadingState label="Loading course…" />}>
      <TeacherCourseView courseId={courseId} />
    </Suspense>
  );
}
