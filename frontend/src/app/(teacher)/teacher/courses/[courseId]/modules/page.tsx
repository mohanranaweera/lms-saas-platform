"use client";

import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { ArrowLeft, CheckCircle2 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ModuleLessonEditor } from "@/components/courses/module-lesson-editor";
import { useCourse } from "@/lib/api/courses";

/**
 * Teacher "Module & Lesson Editor" — `teacher/courses/[courseId]/modules`.
 * Fetches the parent course only for the heading/breadcrumb (per
 * `edit/page.tsx`'s established pattern); all module/lesson data and
 * mutations live in `ModuleLessonEditor`.
 *
 * `?created=1` is a one-time, purely-informational UI confirmation appended
 * by `course-create-form.tsx`'s redirect after a successful `POST
 * /api/v1/courses` — it never activates or confirms anything itself (the
 * course was already created server-side by the time this page renders); it
 * only decides whether to show the "Course created." banner, matching
 * `course-edit-form.tsx`'s "Course details saved." confirmation pattern.
 */
export default function CourseModulesPage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;
  const query = useCourse(courseId);
  const searchParams = useSearchParams();
  const justCreated = searchParams.get("created") === "1";

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <Link
          href={`/teacher/courses/${courseId}/edit`}
          className="inline-flex w-fit items-center gap-1 text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          <ArrowLeft className="size-4" aria-hidden="true" />
          Back to course
        </Link>
        <div>
          <h1 className="text-xl font-semibold text-foreground">Modules & lessons</h1>
          <p className="text-sm text-muted-foreground">
            Structure this course into modules, each holding its own ordered list of lessons.
          </p>
        </div>
      </div>

      {justCreated ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>Course created.</AlertDescription>
        </Alert>
      ) : null}

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading course…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
      >
        {(course) => (
          <div className="flex flex-col gap-4">
            <p className="text-sm text-muted-foreground">
              Editing modules for{" "}
              <span className="font-medium text-foreground">{course.name}</span>.
            </p>
            <ModuleLessonEditor courseId={course.id} />
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
