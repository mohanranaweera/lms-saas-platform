"use client";

import { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { usePublicCourses, type PublicCourseResponse } from "@/lib/api/public-courses";

function CourseCard({ course }: { course: PublicCourseResponse }) {
  return (
    <li>
      <Link
        href={`/courses/${course.slug}`}
        className="flex h-full flex-col gap-2 rounded-xl border border-border bg-card p-5 transition-colors hover:border-foreground/30 hover:bg-muted/40"
      >
        <div className="flex items-start justify-between gap-2">
          <h2 className="text-base font-semibold text-foreground">{course.name}</h2>
          <span className="shrink-0 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
            {course.category}
          </span>
        </div>
        {course.description ? (
          <p className="line-clamp-3 text-sm text-muted-foreground">{course.description}</p>
        ) : null}
        <p className="mt-auto pt-2 text-sm font-medium text-foreground">
          {course.price.toFixed(2)}
        </p>
      </Link>
    </li>
  );
}

/**
 * Public course catalog — consumer-style, mobile-first surface
 * (`.claude/rules/ui-ux.md` §5). Anonymous read, tenant resolved server-side
 * from the request subdomain (see `lib/api/public-courses.ts`); no
 * search/filter required by the module plan, so none is built here.
 *
 * This is the genuinely public, growth-unbounded course list (unlike the two
 * authenticated/internal course list views under `teacher/courses` and
 * `tenant-admin/courses`, which fetch a single large page and keep their
 * existing client-side-filter UX — see `lib/api/courses.ts`'s `useCourses`
 * doc comment), so it gets real Previous/Next pagination driven by
 * `PageResponse`'s `page`/`totalPages` instead of a fixed large page size.
 */
export default function PublicCoursesPage() {
  const [page, setPage] = useState(0);
  const query = usePublicCourses({ page });

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-4 py-10 sm:px-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-foreground sm:text-3xl">
          Courses
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Browse the courses currently available at this institute.
        </p>
      </div>

      <QueryStateBoundary query={query} loadingLabel="Loading courses…">
        {(coursesPage) => {
          if (coursesPage.content.length === 0) {
            return (
              <EmptyState
                title="No courses are available yet"
                description="This institute hasn't published any courses yet. Check back soon."
              />
            );
          }

          return (
            <div className="flex flex-col gap-4">
              <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {coursesPage.content.map((course) => (
                  <CourseCard key={course.id} course={course} />
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
