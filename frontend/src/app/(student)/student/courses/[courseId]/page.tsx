"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Accordion } from "@/components/ui/accordion";
import { LoadingState } from "@/components/states/loading-state";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { AccessStateBadge } from "@/components/students/enrollment-status-badges";
import { formatDateTime } from "@/lib/format";
import { isApiClientError } from "@/lib/api/error";
import { useMyEnrollments, type EnrollmentSummaryResponse } from "@/lib/api/enrollments";
import {
  useCourse,
  useCourseLessons,
  useCourseModules,
  type CourseModuleResponse,
} from "@/lib/api/courses";
import { CourseLiveClasses } from "./course-live-classes";

/**
 * Student "Course Content" — `student/courses/[courseId]`. The entry point
 * from "My courses" into a course's modules → lessons → the existing lesson
 * materials page, plus the course's live classes (`CourseLiveClasses`; there
 * is no separate Student live classes page).
 *
 * Access follows the Student's current enrollment ("active term" = the paid
 * access window): the page first reads `GET /api/v1/enrollments/my` and only
 * fetches course/module data when this course's row is `ACTIVE`. An
 * `EXPIRED` row gets a calm "access ended" surface pointing back to
 * "My courses" (where Reactivate lives) instead of a request that would 403.
 * This is presentation only — the backend (`CourseAccessGuard`) re-checks the
 * enrollment on every course/module/lesson read regardless of what this page
 * shows.
 *
 * A 403/404 on the course read (e.g. the course is back in DRAFT, or the
 * window lapsed between the two reads) renders fixed local copy, never the
 * backend's `error.message`, matching the lesson materials page's
 * anti-enumeration convention.
 */
export default function StudentCourseContentPage() {
  const { courseId } = useParams<{ courseId: string }>();
  const router = useRouter();
  const enrollmentsQuery = useMyEnrollments();
  const enrollment = enrollmentsQuery.data?.find((row) => row.courseId === courseId);
  const backToCourses = { label: "Back to My courses", onClick: () => router.push("/student/courses") };

  if (enrollmentsQuery.status === "pending") {
    return <LoadingState label="Loading your course…" />;
  }

  if (enrollmentsQuery.status === "error") {
    const error = enrollmentsQuery.error;
    return (
      <ErrorState
        message={isApiClientError(error) ? error.message : "Something went wrong. Please try again."}
        code={isApiClientError(error) ? error.code : undefined}
        onRetry={() => enrollmentsQuery.refetch()}
      />
    );
  }

  if (!enrollment) {
    return (
      <EmptyState
        title="This course isn't available"
        description="You're not enrolled in this course. Browse the catalog to find a course to enroll in."
        action={backToCourses}
      />
    );
  }

  if (enrollment.state !== "ACTIVE") {
    return (
      <EmptyState
        title="Your access to this course has ended"
        description={
          enrollment.accessExpiresAt
            ? `Your paid access ended on ${formatDateTime(enrollment.accessExpiresAt)}. Renew it from My courses to continue.`
            : "Renew your access from My courses to continue."
        }
        action={backToCourses}
      />
    );
  }

  return <ActiveCourseContent courseId={courseId} enrollment={enrollment} />;
}

function ActiveCourseContent({
  courseId,
  enrollment,
}: {
  courseId: string;
  enrollment: EnrollmentSummaryResponse;
}) {
  const router = useRouter();
  const courseQuery = useCourse(courseId);
  const modulesQuery = useCourseModules(courseId);

  if (courseQuery.status === "pending" || modulesQuery.status === "pending") {
    return <LoadingState label="Loading course content…" />;
  }

  const failed = courseQuery.status === "error" ? courseQuery : modulesQuery.status === "error" ? modulesQuery : null;
  if (failed) {
    const error = failed.error;
    const status = isApiClientError(error) ? error.status : undefined;
    if (status === 403 || status === 404) {
      return (
        <EmptyState
          title="This course isn't open yet"
          description="Your teacher hasn't opened this course's content yet. Please check back later or contact your institute."
          action={{ label: "Back to My courses", onClick: () => router.push("/student/courses") }}
        />
      );
    }
    return (
      <ErrorState
        message={isApiClientError(error) ? error.message : "Something went wrong. Please try again."}
        code={isApiClientError(error) ? error.code : undefined}
        onRetry={() => failed.refetch()}
      />
    );
  }

  const course = courseQuery.data;
  if (!course) return null; // unreachable: both queries are in "success" here
  const modules = [...(modulesQuery.data ?? [])].sort((a, b) => a.sequence - b.sequence);

  return (
    <div className="flex flex-col gap-6">
      <nav aria-label="Breadcrumb">
        <Link href="/student/courses" className="text-sm text-muted-foreground hover:underline">
          ← My courses
        </Link>
      </nav>

      <header className="flex flex-col gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-xl font-semibold text-foreground">{course.name}</h1>
          <span className="rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
            {course.category}
          </span>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          <AccessStateBadge state={enrollment.state} />
          <span>
            {enrollment.accessExpiresAt
              ? `Access until ${formatDateTime(enrollment.accessExpiresAt)}`
              : "Lifetime access"}
          </span>
        </div>
        {course.description ? (
          <p className="whitespace-pre-wrap text-sm text-muted-foreground">{course.description}</p>
        ) : null}
      </header>

      <CourseLiveClasses courseId={courseId} />

      <section className="flex flex-col gap-3" aria-labelledby="course-lesson-materials-heading">
        <h2 id="course-lesson-materials-heading" className="text-base font-semibold text-foreground">
          Lesson materials
        </h2>
        {modules.length === 0 ? (
          <EmptyState
            title="No content yet"
            description="Your teacher hasn't added any modules to this course yet. Check back later."
          />
        ) : (
          <ol aria-label="Course modules" className="flex flex-col gap-3">
            {modules.map((module, index) => (
              <li key={module.id}>
                <Accordion title={module.title} defaultOpen={index === 0}>
                  <ModuleLessons courseId={courseId} module={module} />
                </Accordion>
              </li>
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}

/**
 * One lessons read per module. `Accordion` keeps collapsed children mounted
 * (`hidden`), so every module's lessons load with the page — acceptable at a
 * course's module count, and it keeps expand/collapse instant.
 */
function ModuleLessons({ courseId, module }: { courseId: string; module: CourseModuleResponse }) {
  const lessonsQuery = useCourseLessons(courseId, module.id);

  if (lessonsQuery.status === "pending") {
    return <LoadingState label="Loading lessons…" />;
  }

  if (lessonsQuery.status === "error") {
    return (
      <p role="alert" className="flex flex-wrap items-center gap-2 text-sm text-destructive">
        Couldn&apos;t load lessons for this module.
        <button
          type="button"
          onClick={() => lessonsQuery.refetch()}
          className="font-medium underline hover:no-underline"
        >
          Retry
        </button>
      </p>
    );
  }

  const lessons = [...lessonsQuery.data].sort((a, b) => a.sequence - b.sequence);
  if (lessons.length === 0) {
    return <p className="text-sm text-muted-foreground">No lessons in this module yet.</p>;
  }

  return (
    <ol aria-label={`Lessons in ${module.title}`} className="flex flex-col divide-y divide-border">
      {lessons.map((lesson) => (
        <li key={lesson.id}>
          <Link
            href={`/student/courses/${courseId}/modules/${module.id}/lessons/${lesson.id}/materials`}
            className="flex items-center justify-between gap-2 py-2 text-sm text-foreground hover:underline"
          >
            <span>{lesson.title}</span>
            <span className="shrink-0 text-xs text-muted-foreground">View materials →</span>
          </Link>
        </li>
      ))}
    </ol>
  );
}
