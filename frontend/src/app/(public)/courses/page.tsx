"use client";

import { useState } from "react";
import Link from "next/link";
import { ArrowRight, Search, UserRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { CourseCover } from "@/components/courses/student/course-visuals";
import { CoursePriceText } from "@/components/courses/course-price-display";
import { usePublicCourses, usePublicCourseTags, type PublicCourseResponse } from "@/lib/api/public-courses";

function CourseCard({ course }: { course: PublicCourseResponse }) {
  return (
    <li>
      <Link
        href={`/courses/${course.slug}`}
        className="group flex h-full flex-col overflow-hidden rounded-xl border border-border bg-card shadow-sm transition-all hover:-translate-y-1 hover:border-primary/40 hover:shadow-lg"
      >
        <div className="relative">
          <CourseCover src={course.coverImageUrl} title={course.name} className="rounded-none" />
          <span className="absolute bottom-3 left-3 rounded-full bg-background/95 px-3 py-1 text-sm font-semibold text-primary shadow-sm">
            <CoursePriceText course={course} />
          </span>
        </div>
        <div className="flex flex-1 flex-col gap-3 p-5">
          <span className="w-fit rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
            {course.category}
          </span>
          <h2 className="line-clamp-2 text-lg font-semibold text-foreground">{course.name}</h2>
          {course.teacherName ? (
            <p className="flex items-center gap-2 text-sm text-muted-foreground">
              <UserRound className="size-4" aria-hidden="true" />
              {course.teacherName}
            </p>
          ) : null}
          <span className="mt-auto flex items-center justify-end gap-1 border-t border-border pt-4 text-sm font-semibold text-primary">
            View details
            <ArrowRight className="size-4 transition-transform group-hover:translate-x-1" aria-hidden="true" />
          </span>
        </div>
      </Link>
    </li>
  );
}

/** Public course catalog with tenant-scoped courses and responsive course cards. */
export default function PublicCoursesPage() {
  const [page, setPage] = useState(0);
  const [search, setSearch] = useState("");
  const [tagId, setTagId] = useState<string | null>(null);
  const query = usePublicCourses({ page, tagId });
  const tagsQuery = usePublicCourseTags();

  return (
    <div className="flex flex-1 flex-col bg-gradient-to-b from-primary/5 via-background to-background">
      <section className="border-b border-border bg-primary/5 px-4 py-8 sm:px-6 lg:py-10">
        <div className="mx-auto flex w-full max-w-6xl flex-col items-center gap-3 text-center">
          <h1 className="text-4xl font-bold tracking-tight text-foreground sm:text-5xl">All courses</h1>
          <p className="text-base text-muted-foreground sm:text-lg">Enroll in any course here</p>
          <div className="relative mt-3 w-full max-w-2xl">
            <Label htmlFor="public-course-search" className="sr-only">Search courses</Label>
            <Search aria-hidden="true" className="pointer-events-none absolute left-4 top-1/2 size-5 -translate-y-1/2 text-muted-foreground" />
            <Input
              id="public-course-search"
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search courses"
              className="h-14 rounded-full border-border bg-background pl-12 text-base shadow-sm"
            />
          </div>
          <div className="mt-2 flex w-full max-w-6xl flex-wrap justify-center gap-2" aria-label="Course tags">
            <button type="button" aria-pressed={tagId === null} onClick={() => { setTagId(null); setPage(0); }} className={`rounded-full px-4 py-2 text-sm font-medium ${tagId === null ? "bg-primary text-primary-foreground" : "border border-border bg-background text-muted-foreground hover:text-foreground"}`}>All</button>
            {(tagsQuery.data ?? []).map((tag) => <button key={tag.id} type="button" aria-pressed={tagId === tag.id} onClick={() => { setTagId(tag.id); setPage(0); }} className={`rounded-full px-4 py-2 text-sm font-medium ${tagId === tag.id ? "bg-primary text-primary-foreground" : "border border-border bg-background text-muted-foreground hover:text-foreground"}`}>{tag.name}</button>)}
          </div>
        </div>
      </section>

      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-5 px-4 py-10 sm:px-6">
        <QueryStateBoundary query={query} loadingLabel="Loading courses…">
          {(coursesPage) => {
            const normalizedSearch = search.trim().toLowerCase();
            const courses = normalizedSearch
              ? coursesPage.content.filter((course) =>
                  [course.name, course.category, course.teacherName ?? ""].some((value) => value.toLowerCase().includes(normalizedSearch))
                )
              : coursesPage.content;

            if (courses.length === 0) {
              return (
                <EmptyState
                  title={normalizedSearch ? "No courses match your search" : "No courses are available yet"}
                  description={normalizedSearch ? "Try another course name, category, or teacher." : "This institute hasn't published any courses yet. Check back soon."}
                  action={normalizedSearch ? { label: "Clear search", onClick: () => setSearch("") } : undefined}
                />
              );
            }

            return (
              <div className="flex flex-col gap-5">
                <p className="text-sm font-medium text-muted-foreground">
                  {normalizedSearch ? `${courses.length} matching courses` : `${coursesPage.totalElements} courses`}
                </p>
                <ul className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
                  {courses.map((course) => <CourseCard key={course.id} course={course} />)}
                </ul>
                {coursesPage.totalPages > 1 ? (
                  <nav aria-label="Course catalog pagination" className="flex items-center justify-between gap-3 pt-3">
                    <Button type="button" variant="outline" size="sm" disabled={coursesPage.page <= 0} onClick={() => setPage((current) => Math.max(current - 1, 0))}>Previous</Button>
                    <span className="text-sm text-muted-foreground" aria-live="polite">Page {coursesPage.page + 1} of {coursesPage.totalPages}</span>
                    <Button type="button" variant="outline" size="sm" disabled={coursesPage.page + 1 >= coursesPage.totalPages} onClick={() => setPage((current) => current + 1)}>Next</Button>
                  </nav>
                ) : null}
              </div>
            );
          }}
        </QueryStateBoundary>
      </main>
    </div>
  );
}
