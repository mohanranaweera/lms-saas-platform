"use client";

import { Suspense } from "react";
import { useParams } from "next/navigation";
import { LoadingState } from "@/components/states/loading-state";
import { TeacherCourseView } from "@/components/courses/teacher/teacher-course-view";

import { useT } from "@/i18n/i18n-provider";
/**
 * Teacher Portal "Open course" (TP-02): the student course layout for an
 * assigned course. Management actions live behind "Manage course".
 */
export default function TeacherCoursePage() {
  const t = useT();
  const { courseId } = useParams<{ courseId: string }>();
  return (
    <Suspense fallback={<LoadingState label={t("teacher.page.courses.loadingCourse")} />}>
      <TeacherCourseView courseId={courseId} />
    </Suspense>
  );
}
