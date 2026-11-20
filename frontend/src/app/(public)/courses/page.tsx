"use client";

import { useState } from "react";
import { Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { PublicCourseCard } from "@/components/public/public-course-card";
import { usePublicCourses, usePublicCourseTags } from "@/lib/api/public-courses";

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
                  {courses.map((course) => <PublicCourseCard key={course.id} course={course} />)}
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
