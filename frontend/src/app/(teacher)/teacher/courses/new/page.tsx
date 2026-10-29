"use client";

import { useRouter } from "next/navigation";
import { CourseCreateDialog } from "@/components/courses/admin/course-create-dialog";

/** Teacher course creation uses same current workflow as Tenant Admin, with teacher ownership server-enforced. */
export default function TeacherNewCoursePage() {
  const router = useRouter();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Create a course</h1>
        <p className="text-sm text-muted-foreground">
          Use current course workflow. Course assignment stays tied to your authenticated teacher account.
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
