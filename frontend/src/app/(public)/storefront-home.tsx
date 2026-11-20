"use client";

import { useState } from "react";
import { BookOpen, Search, UserRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { PublicCourseCard } from "@/components/public/public-course-card";
import { usePublicCourses } from "@/lib/api/public-courses";
import type { SiteStorefront } from "@/lib/storefront/site-context";

const STOREFRONT_PAGE_SIZE = 12;

/**
 * STORE-01 / ADR-037: a teacher's personal homepage on
 * `<slug>.<tenant>.<platform-domain>`. Body only - the tenant header, footer
 * and theme come from `PublicChrome`. The catalog is the ordinary public
 * course list: on a storefront host the backend returns only this
 * storefront's published products (ADR-037 decision 6), so nothing is
 * filtered here for scope. Search narrows the current page only (the public
 * list endpoint has no text search), like `/courses`.
 */
export function StorefrontHome({ storefront }: { storefront: SiteStorefront }) {
  return (
    <div className="flex flex-col" data-testid="storefront-home">
      <TeacherHero storefront={storefront} />
      <StorefrontCatalog teacherName={storefront.displayName} />
    </div>
  );
}

function TeacherHero({ storefront }: { storefront: SiteStorefront }) {
  const [photoFailed, setPhotoFailed] = useState(false);
  return (
    <section className="relative overflow-hidden bg-primary text-primary-foreground" aria-labelledby="storefront-teacher-name">
      <span className="pointer-events-none absolute -right-24 -top-24 size-80 rounded-full bg-current opacity-[0.07]" aria-hidden="true" />
      <div className="relative mx-auto flex w-full max-w-6xl flex-col items-center gap-6 px-4 py-12 text-center sm:px-6 md:flex-row md:items-center md:gap-10 md:py-16 md:text-left lg:px-8">
        {storefront.photoUrl && !photoFailed ? (
          // eslint-disable-next-line @next/next/no-img-element -- signed profile photo URL
          <img
            src={storefront.photoUrl}
            alt={`${storefront.displayName}`}
            className="size-32 shrink-0 rounded-full object-cover ring-4 ring-primary-foreground/30 sm:size-40"
            onError={() => setPhotoFailed(true)}
            data-testid="storefront-teacher-photo"
          />
        ) : (
          <span className="flex size-32 shrink-0 items-center justify-center rounded-full bg-primary-foreground/15 sm:size-40" aria-hidden="true">
            <UserRound className="size-14" />
          </span>
        )}
        <div className="flex min-w-0 flex-col gap-3">
          <p className="text-xs font-semibold uppercase tracking-[0.2em] opacity-80">Teacher</p>
          <h1 id="storefront-teacher-name" className="font-heading text-3xl font-bold tracking-tight break-words sm:text-5xl">
            {storefront.displayName}
          </h1>
          {storefront.bio ? (
            <p className="max-w-2xl whitespace-pre-line text-base opacity-90 sm:text-lg" data-testid="storefront-teacher-bio">
              {storefront.bio}
            </p>
          ) : null}
          <a
            href="#storefront-courses"
            className="mt-2 inline-flex w-fit items-center gap-2 self-center rounded-lg bg-card px-5 py-2.5 text-sm font-semibold text-card-foreground shadow-sm outline-none hover:bg-card/90 focus-visible:ring-3 focus-visible:ring-primary-foreground md:self-start"
          >
            <BookOpen className="size-4" aria-hidden="true" />
            Browse courses
          </a>
        </div>
      </div>
    </section>
  );
}

function StorefrontCatalog({ teacherName }: { teacherName: string }) {
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const query = usePublicCourses({ page, size: STOREFRONT_PAGE_SIZE });

  return (
    <section
      id="storefront-courses"
      className="mx-auto flex w-full max-w-6xl scroll-mt-24 flex-col gap-6 px-4 py-10 sm:px-6 lg:px-8"
      aria-labelledby="storefront-courses-heading"
    >
      <div className="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h2 id="storefront-courses-heading" className="text-2xl font-bold tracking-tight break-words sm:text-3xl">
            Courses by {teacherName}
          </h2>
          <p className="mt-1 text-sm text-muted-foreground">Choose a course to see its details and enroll.</p>
        </div>
        <div className="relative w-full sm:max-w-xs">
          <Label htmlFor="storefront-course-search" className="sr-only">
            Search courses
          </Label>
          <Search aria-hidden="true" className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            id="storefront-course-search"
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Search courses"
            className="h-11 rounded-full bg-background pl-9"
          />
        </div>
      </div>

      <QueryStateBoundary query={query} loadingLabel="Loading courses…" genericErrorMessage="We couldn't load this teacher's courses.">
        {(coursesPage) => {
          const term = search.trim().toLowerCase();
          const courses = term
            ? coursesPage.content.filter((course) =>
                [course.name, course.category, course.subject ?? ""].some((value) => value.toLowerCase().includes(term))
              )
            : coursesPage.content;

          if (coursesPage.content.length === 0) {
            return (
              <EmptyState
                icon={<BookOpen className="size-6" aria-hidden="true" />}
                title="No courses here yet"
                description={`${teacherName} hasn't published any courses on this page yet. Check back soon.`}
              />
            );
          }
          if (courses.length === 0) {
            return (
              <EmptyState
                title="No courses match your search"
                description="Try another course name or category."
                action={{ label: "Clear search", onClick: () => setSearch("") }}
              />
            );
          }
          return (
            <div className="flex flex-col gap-5">
              <p className="text-sm font-medium text-muted-foreground" aria-live="polite">
                {term ? `${courses.length} matching courses` : `${coursesPage.totalElements} courses`}
              </p>
              <ul className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3" aria-label={`Courses by ${teacherName}`}>
                {courses.map((course) => (
                  <PublicCourseCard key={course.id} course={course} showTeacher={false} />
                ))}
              </ul>
              {coursesPage.totalPages > 1 ? (
                <nav aria-label="Storefront course pagination" className="flex items-center justify-between gap-3 pt-3">
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
    </section>
  );
}
