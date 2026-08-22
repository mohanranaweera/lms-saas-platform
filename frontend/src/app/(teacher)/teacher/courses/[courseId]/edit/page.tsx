"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { CourseEditForm } from "@/components/courses/course-edit-form";
import { CourseVisibilityControl } from "@/components/courses/course-visibility-control";
import { CoursePriceChangeForm } from "@/components/courses/course-price-change-form";
import { useCourse } from "@/lib/api/courses";

export default function EditCoursePage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;
  const query = useCourse(courseId);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Edit course</h1>
          <p className="text-sm text-muted-foreground">
            Update this course&apos;s details. Price and visibility have their own dedicated
            actions below.
          </p>
        </div>
        <Button render={<Link href={`/teacher/courses/${courseId}/modules`} />} variant="outline">
          Manage modules & lessons
        </Button>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading course…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
      >
        {(course) => (
          <div className="flex flex-col gap-6">
            <CourseEditForm key={course.id} courseId={course.id} course={course} />
            <div className="grid gap-4 sm:grid-cols-2">
              <CourseVisibilityControl courseId={course.id} course={course} />
              <CoursePriceChangeForm courseId={course.id} course={course} />
            </div>
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
