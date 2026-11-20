"use client";

import { useParams } from "next/navigation";
import { LessonMaterialsView } from "@/components/courses/lesson-materials-view";

import { useT } from "@/i18n/i18n-provider";
/**
 * Teacher Portal lesson materials (TP-02), opened from the course view. The
 * material and video endpoints check the caller's course assignment.
 */
export default function TeacherLessonMaterialsPage() {
  const t = useT();
  const { courseId, moduleId, lessonId } = useParams<{ courseId: string; moduleId: string; lessonId: string }>();
  return (
    <LessonMaterialsView
      courseId={courseId}
      moduleId={moduleId}
      lessonId={lessonId}
      backHref={`/teacher/courses/${courseId}`}
      description={t("teacher.page.coursesModulesLessonsMaterials.theMaterialsStudentsSee")}
    />
  );
}
