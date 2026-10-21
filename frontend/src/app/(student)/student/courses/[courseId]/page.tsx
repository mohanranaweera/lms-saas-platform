"use client";

import { Suspense } from "react";
import { useParams } from "next/navigation";
import { LoadingState } from "@/components/states/loading-state";
import { StudentCourseDetailView } from "@/components/courses/student/student-course-detail";

/**
 * Student course detail - Klass layout (KLASS-T5b, CM-S02..S06, CM-R01..R03):
 * header card + Course content | Exams | Live Classes | Tute Delivery | About
 * this course (`?tab=`). The content tree / live classes / lesson launch are
 * the server evaluator's answers (ADR-028); the existing lesson materials
 * route (`modules/[moduleId]/lessons/[lessonId]/materials`) is still where
 * launched materials open.
 */
export default function StudentCourseDetailPage() {
  const { courseId } = useParams<{ courseId: string }>();
  return (
    <Suspense fallback={<LoadingState label="Loading course…" />}>
      <StudentCourseDetailView courseId={courseId} />
    </Suspense>
  );
}
