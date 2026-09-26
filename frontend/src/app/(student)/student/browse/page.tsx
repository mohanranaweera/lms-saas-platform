"use client";

import { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { CoursePriceText } from "@/components/courses/course-price-display";
import { usePublicCourses, type PublicCourseResponse } from "@/lib/api/public-courses";
import { useMyEnrollments } from "@/lib/api/enrollments";

/**
 * Student "Browse Courses" — the institute's catalog rendered inside the
 * student portal shell. The public `/courses` page uses the public layout
 * ("Sign in" / "Get started" header, no portal nav), which made a
 * signed-in Student look signed out and stranded them outside the portal.
 *
 * Reads the same `GET /api/v1/public/courses` storefront list (PUBLIC courses
 * of the resolved tenant only). A course the Student already has ACTIVE
 * access to links to its content instead of checkout; every other course
 * links to `/student/checkout/[courseId]`, where price and payment options
 * are resolved server-side. `useMyEnrollments` only chooses the CTA — if it
 * fails, cards fall back to "Enroll" (checkout itself still re-validates).
 */
export default function StudentBrowseCoursesPage() {
  const [page, setPage] = useState(0);
  const query = usePublicCourses({ page });
  const enrollmentsQuery = useMyEnrollments();
  const activeCourseIds = new Set(
    (enrollmentsQuery.data ?? []).filter((row) => row.state === "ACTIVE").map((row) => row.courseId)
  );

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Browse courses</h1>
        <p className="text-sm text-muted-foreground">
          Courses currently available at your institute.
        </p>
      </div>

      <QueryStateBoundary query={query} loadingLabel="Loading courses…">
        {(coursesPage) => {
          if (coursesPage.content.length === 0) {
            return (
              <EmptyState
                title="No courses are available yet"
                description="Your institute hasn't published any courses yet. Check back soon."
              />
            );
          }

          return (
            <div className="flex flex-col gap-4">
              <ul
                aria-label="Available courses"
                className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
              >
                {coursesPage.content.map((course) => (
                  <BrowseCourseCard
                    key={course.id}
                    course={course}
                    enrolled={activeCourseIds.has(course.id)}
                  />
                ))}
              </ul>

              {coursesPage.totalPages > 1 ? (
                <nav
                  aria-label="Course catalog pagination"
                  className="flex items-center justify-between gap-3 pt-2"
                >
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={coursesPage.page <= 0}
                    onClick={() => setPage((current) => Math.max(current - 1, 0))}
                  >
                    Previous
                  </Button>
                  <span className="text-sm text-muted-foreground" aria-live="polite">
                    Page {coursesPage.page + 1} of {coursesPage.totalPages}
                  </span>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={coursesPage.page + 1 >= coursesPage.totalPages}
                    onClick={() => setPage((current) => current + 1)}
                  >
                    Next
                  </Button>
                </nav>
              ) : null}
            </div>
          );
        }}
      </QueryStateBoundary>
    </div>
  );
}

function BrowseCourseCard({ course, enrolled }: { course: PublicCourseResponse; enrolled: boolean }) {
  return (
    <li className="flex h-full flex-col gap-3 rounded-xl border border-border bg-card p-5">
      <div className="flex items-start justify-between gap-2">
        <h2 className="text-base font-semibold text-foreground">{course.name}</h2>
        <span className="shrink-0 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
          {course.category}
        </span>
      </div>
      {course.description ? (
        <p className="line-clamp-3 text-sm text-muted-foreground">{course.description}</p>
      ) : null}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        <CoursePriceText course={course} className="font-medium text-foreground" />
        {course.accessDurationDays ? (
          <span className="text-muted-foreground">{course.accessDurationDays} days access</span>
        ) : null}
      </div>
      <div className="mt-auto pt-2">
        {enrolled ? (
          <Link
            href={`/student/courses/${course.id}`}
            aria-label={`Open course ${course.name}`}
            className="text-sm font-medium text-foreground hover:underline"
          >
            Open course
          </Link>
        ) : (
          <Link
            href={`/student/checkout/${course.id}`}
            aria-label={`Enroll in ${course.name}`}
            className="inline-flex rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/80"
          >
            Enroll
          </Link>
        )}
      </div>
    </li>
  );
}
