"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { BookOpen, CheckCircle2, PencilLine } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { StatCard } from "@/components/dashboard/stat-card";
import { CourseStatusBadge } from "@/components/courses/course-status-badge";
import { useCourses } from "@/lib/api/courses";
import {
  TEACHER_NO_ASSIGNED_COURSES_TITLE,
  TEACHER_NO_ASSIGNED_COURSES_DESCRIPTION,
  TEACHER_NO_ASSIGNED_COURSES_ACTION_LABEL,
} from "@/lib/copy/teacher-empty-states";

const RECENT_COURSES_LIMIT = 5;

/**
 * Teacher Overview (MVP-014 TDASH-1 — rebuild of the prior static
 * `EmptyState`-only placeholder). Per plan §4.1: a single, already
 * teacher-ownership-scoped read (`useCourses()` -> `GET /api/v1/courses`,
 * the same call `/teacher/courses` already makes) composed client-side into
 * stat cards and a short "recent courses" list — pure display arithmetic,
 * no new fetch, no new hook, no business logic. Zero assigned courses
 * reuses the exact same empty-state copy already shipped on
 * `/teacher/courses` (`lib/copy/teacher-empty-states.ts`) so both screens
 * agree verbatim.
 */
export default function TeacherDashboardPage() {
  const router = useRouter();
  const query = useCourses();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Overview</h1>
        <p className="text-sm text-muted-foreground">
          Your assigned courses at a glance.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading your courses…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={{
          title: TEACHER_NO_ASSIGNED_COURSES_TITLE,
          description: TEACHER_NO_ASSIGNED_COURSES_DESCRIPTION,
          action: {
            label: TEACHER_NO_ASSIGNED_COURSES_ACTION_LABEL,
            onClick: () => router.push("/teacher/courses/new"),
          },
        }}
      >
        {(coursesPage) => {
          const courses = coursesPage.content;
          const publishedCount = courses.filter((course) => course.status === "PUBLIC").length;
          // Intentionally buckets every non-`PUBLIC` status (currently
          // `DRAFT` and `PRIVATE`) into one "Draft / private" stat, matching
          // the stat card's label below — if a third `CourseStatus` value is
          // ever added, revisit whether it belongs in this bucket too rather
          // than assuming this line was an oversight.
          const draftCount = courses.length - publishedCount;
          // Most-recently-updated-first — a display default, not a
          // backend-specified ordering (no document specifies one; see plan
          // §21 item 4). Compare numerically via `Date#getTime()` rather than
          // the raw ISO strings — lexicographic string comparison is only
          // safe if every timestamp shares identical precision/offset
          // formatting, which isn't guaranteed.
          const recentCourses = [...courses]
            .sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime())
            .slice(0, RECENT_COURSES_LIMIT);

          return (
            <div className="flex flex-col gap-6">
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                <StatCard
                  label="Assigned courses"
                  value={courses.length}
                  icon={<BookOpen className="size-4" />}
                />
                <StatCard
                  label="Published"
                  value={publishedCount}
                  icon={<CheckCircle2 className="size-4" />}
                />
                <StatCard
                  label="Draft / private"
                  value={draftCount}
                  icon={<PencilLine className="size-4" />}
                />
              </div>

              <div className="flex flex-col gap-3">
                <h2 className="text-base font-semibold text-foreground">Recent courses</h2>
                <ul
                  aria-label="Recent courses"
                  className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
                >
                  {recentCourses.map((course) => (
                    <li
                      key={course.id}
                      className="flex flex-col gap-2 rounded-xl border border-border bg-card p-4"
                    >
                      <div className="flex items-start justify-between gap-2">
                        <h3 className="text-sm font-medium text-foreground">{course.name}</h3>
                        <CourseStatusBadge status={course.status} />
                      </div>
                      <span className="w-fit rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
                        {course.category}
                      </span>
                    </li>
                  ))}
                </ul>
                <Link
                  href="/teacher/courses"
                  className="w-fit text-sm font-medium text-foreground hover:underline"
                >
                  View all my courses
                </Link>
              </div>
            </div>
          );
        }}
      </QueryStateBoundary>
    </div>
  );
}
