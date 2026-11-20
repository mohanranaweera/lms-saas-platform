"use client";

import { useRouter } from "next/navigation";
import { CourseCreateDialog } from "@/components/courses/admin/course-create-dialog";

import { useT } from "@/i18n/i18n-provider";
/** Teacher course creation uses same current workflow as Tenant Admin, with teacher ownership server-enforced. */
export default function TeacherNewCoursePage() {
  const t = useT();
  const router = useRouter();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">{t("teacher.page.coursesNew.createACourse")}</h1>
        <p className="text-sm text-muted-foreground">
          {t("teacher.page.coursesNew.useCurrentCourseWorkflow")}
        </p>
      </div>
      <CourseCreateDialog
        open
        mode="teacher"
        onOpenChange={(open) => {
          if (!open) router.push("/teacher/courses");
        }}
        onCreated={(course) => {
          router.push(`/teacher/courses/${course.id}/edit?created=1`);
        }}
      />
    </div>
  );
}
