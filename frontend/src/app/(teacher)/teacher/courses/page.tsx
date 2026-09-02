"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { Plus } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { TeacherCourseCardGrid } from "@/components/courses/teacher-course-card-grid";
import { useCourses, type CourseResponse, type CourseStatus } from "@/lib/api/courses";
import {
  TEACHER_NO_ASSIGNED_COURSES_TITLE,
  TEACHER_NO_ASSIGNED_COURSES_DESCRIPTION,
  TEACHER_NO_ASSIGNED_COURSES_ACTION_LABEL,
} from "@/lib/copy/teacher-empty-states";

const STATUS_FILTER_OPTIONS: Array<{ value: "all" | CourseStatus; label: string }> = [
  { value: "all", label: "All statuses" },
  { value: "DRAFT", label: "Draft" },
  { value: "PRIVATE", label: "Private" },
  { value: "PUBLIC", label: "Public" },
];

/**
 * Teacher "My Courses" list. `GET /api/v1/courses` returns only this
 * teacher's own courses (server-enforced) — no client-side ownership
 * filtering happens here, per `.claude/rules/ui-ux.md` §1 (never fetch an
 * unfiltered dataset and filter client-side "for convenience"). Category/
 * status search *is* client-side, over a single `size=100` page fetched via
 * `useCourses()` (see that hook's doc comment) — a Teacher's own course count
 * is expected to comfortably fit one page at MVP scope, so this keeps the
 * existing search-box/dropdown filter UX instead of building real pagination
 * controls for a bounded, per-teacher list.
 */
export default function TeacherCoursesPage() {
  const router = useRouter();
  const query = useCourses();
  const [search, setSearch] = useState("");
  const [categoryFilter, setCategoryFilter] = useState<string>("all");
  const [statusFilter, setStatusFilter] = useState<"all" | CourseStatus>("all");

  const categories = useMemo(() => {
    const values = new Set((query.data?.content ?? []).map((course) => course.category));
    return Array.from(values).sort((a, b) => a.localeCompare(b));
  }, [query.data]);

  const filtered = useMemo(() => {
    const all = query.data?.content ?? [];
    const term = search.trim().toLowerCase();
    return all.filter((course: CourseResponse) => {
      if (statusFilter !== "all" && course.status !== statusFilter) return false;
      if (categoryFilter !== "all" && course.category !== categoryFilter) return false;
      if (term && !course.name.toLowerCase().includes(term) && !course.slug.toLowerCase().includes(term)) {
        return false;
      }
      return true;
    });
  }, [query.data, search, categoryFilter, statusFilter]);

  const clearFilters = () => {
    setSearch("");
    setCategoryFilter("all");
    setStatusFilter("all");
  };

  const filtersActive = search.trim() !== "" || categoryFilter !== "all" || statusFilter !== "all";

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-foreground">My Courses</h1>
          <p className="text-sm text-muted-foreground">
            Courses you own as their teacher. Create, edit, and manage content here.
          </p>
        </div>
        <Button render={<Link href="/teacher/courses/new" />}>
          <Plus aria-hidden="true" />
          New course
        </Button>
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
        {(courses) => {
          return (
            <div className="flex flex-col gap-4">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
                <div className="flex flex-1 flex-col gap-1.5">
                  <Label htmlFor="teacher-courses-search">Search</Label>
                  <Input
                    id="teacher-courses-search"
                    type="search"
                    placeholder="Search by course name or slug"
                    value={search}
                    onChange={(event) => setSearch(event.target.value)}
                  />
                </div>
                <div className="flex flex-col gap-1.5 sm:w-56">
                  <Label htmlFor="teacher-courses-category">Category</Label>
                  <Select
                    value={categoryFilter}
                    onValueChange={(value) => setCategoryFilter(value ?? "all")}
                  >
                    <SelectTrigger id="teacher-courses-category" className="w-full">
                      <SelectValue placeholder="All categories" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">All categories</SelectItem>
                      {categories.map((category) => (
                        <SelectItem key={category} value={category}>
                          {category}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="flex flex-col gap-1.5 sm:w-56">
                  <Label htmlFor="teacher-courses-status">Status</Label>
                  <Select
                    value={statusFilter}
                    onValueChange={(value) => setStatusFilter(value as "all" | CourseStatus)}
                  >
                    <SelectTrigger id="teacher-courses-status" className="w-full">
                      <SelectValue placeholder="All statuses" />
                    </SelectTrigger>
                    <SelectContent>
                      {STATUS_FILTER_OPTIONS.map((option) => (
                        <SelectItem key={option.value} value={option.value}>
                          {option.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>

              {filtered.length === 0 ? (
                <EmptyState
                  title="No courses match your filters"
                  description="Try a different search term, or clear your filters to see all your courses."
                  action={
                    filtersActive
                      ? { label: "Clear filters", onClick: clearFilters }
                      : undefined
                  }
                />
              ) : (
                <TeacherCourseCardGrid
                  courses={filtered}
                  renderActions={(course) => (
                    <>
                      <Link
                        href={`/teacher/courses/${course.id}/edit`}
                        className={buttonVariants({ variant: "outline", size: "sm" })}
                      >
                        Edit
                      </Link>
                      <Link
                        href={`/teacher/courses/${course.id}/modules`}
                        className={buttonVariants({ variant: "outline", size: "sm" })}
                      >
                        Modules
                      </Link>
                    </>
                  )}
                />
              )}

              <p className="text-xs text-muted-foreground">
                Showing {filtered.length} of {courses.content.length} courses.
              </p>
            </div>
          );
        }}
      </QueryStateBoundary>
    </div>
  );
}
