"use client";

import { useParams } from "next/navigation";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";
import { ClassSchedulePanel } from "@/components/calendar/class-schedule-panel";

/** CAL-01: the course's weekly class schedule (summary + shared editor). */
export default function TenantAdminCourseSchedulePage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;

  return (
    <CourseWorkspaceShell
      courseId={courseId}
      basePath={`/tenant-admin/courses/${courseId}`}
      dashboardHref="/tenant-admin/dashboard"
    >
      {(course) => (
        <ClassSchedulePanel
          courseId={courseId}
          courseName={course.name}
          calendarHref="/tenant-admin/calendar"
          dashboardHref="/tenant-admin/dashboard"
        />
      )}
    </CourseWorkspaceShell>
  );
}
