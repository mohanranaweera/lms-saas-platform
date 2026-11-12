"use client";

import { useParams } from "next/navigation";
import { LessonMaterialsView } from "@/components/courses/lesson-materials-view";

/**
 * Teacher Portal lesson materials (TP-02), opened from the course view. The
 * material and video endpoints check the caller's course assignment.
 */
export default function TeacherLessonMaterialsPage() {
  const { courseId, moduleId, lessonId } = useParams<{ courseId: string; moduleId: string; lessonId: string }>();
  return (
    <LessonMaterialsView
      courseId={courseId}
      moduleId={moduleId}
      lessonId={lessonId}
      backHref={`/teacher/courses/${courseId}`}
      description="The materials students see for this session. Manage them from Manage course → Modules & sessions."
    />
  );
}
