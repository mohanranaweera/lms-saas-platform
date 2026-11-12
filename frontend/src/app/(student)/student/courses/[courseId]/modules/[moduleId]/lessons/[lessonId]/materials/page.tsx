"use client";

import { useParams } from "next/navigation";
import { LessonMaterialsView } from "@/components/courses/lesson-materials-view";

/** Student "Lesson/Material View" - see `LessonMaterialsView`. */
export default function StudentLessonMaterialsPage() {
  const { courseId, moduleId, lessonId } = useParams<{ courseId: string; moduleId: string; lessonId: string }>();
  return (
    <LessonMaterialsView
      courseId={courseId}
      moduleId={moduleId}
      lessonId={lessonId}
      backHref={`/student/courses/${courseId}`}
    />
  );
}
