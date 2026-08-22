"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
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
import { CourseListTable } from "@/components/courses/course-list-table";
import { useCourses, type CourseResponse, type CourseStatus } from "@/lib/api/courses";

const STATUS_FILTER_OPTIONS: Array<{ value: "all" | CourseStatus; label: string }> = [
  { value: "all", label: "All statuses" },
  { value: "DRAFT", label: "Draft" },
  { value: "PRIVATE", label: "Private" },
  { value: "PUBLIC", label: "Public" },
];

/**
 * Tenant Admin "All Courses" list. `GET /api/v1/courses` returns every course
 * in the tenant for a Tenant-Admin-role caller (server-enforced — the same
 * hook a Teacher uses returns only their own courses; no client-side
 * ownership filtering happens here either way). Category/status/teacher
 * search is client-side, over a single `size=100` page fetched via
 * `useCourses()` (see that hook's doc comment) — this keeps the existing
 * search-box/dropdown filter UX for MVP scope rather than building real
 * pagination controls for this tenant-scoped (not unboundedly-growable) list.
 *
 * There is no course-creation screen for Tenant Admin at MVP (courses are
 * created by teachers, see `CourseCreateRequest`'s doc comment in
 * `lib/api/courses.ts`), so this page never renders a "New course" CTA.
 */
export default function TenantAdminCoursesPage() {
  const query = useCourses();
  const [search, setSearch] = useState("");
  const [categoryFilter, setCategoryFilter] = useState<string>("all");
  const [statusFilter, setStatusFilter] = useState<"all" | CourseStatus>("all");
  const [teacherFilter, setTeacherFilter] = useState("");

  const categories = useMemo(() => {
    const values = new Set((query.data?.content ?? []).map((course) => course.category));
    return Array.from(values).sort((a, b) => a.localeCompare(b));
  }, [query.data]);

  const filtered = useMemo(() => {
    const all = query.data?.content ?? [];
    const term = search.trim().toLowerCase();
    const teacherTerm = teacherFilter.trim().toLowerCase();
    return all.filter((course: CourseResponse) => {
      if (statusFilter !== "all" && course.status !== statusFilter) return false;
      if (categoryFilter !== "all" && course.category !== categoryFilter) return false;
      if (term && !course.name.toLowerCase().includes(term) && !course.slug.toLowerCase().includes(term)) {
        return false;
      }
      if (teacherTerm && !course.teacherId.toLowerCase().includes(teacherTerm)) {
        return false;
      }
      return true;
    });
  }, [query.data, search, categoryFilter, statusFilter, teacherFilter]);

  const clearFilters = () => {
    setSearch("");
    setCategoryFilter("all");
    setStatusFilter("all");
    setTeacherFilter("");
  };

  const hasAnyCourses = (query.data?.content ?? []).length > 0;
  const filtersActive =
    search.trim() !== "" || categoryFilter !== "all" || statusFilter !== "all" || teacherFilter.trim() !== "";

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Courses</h1>
        <p className="text-sm text-muted-foreground">
          Every course in your tenant, across all teachers.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading courses…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(courses) => {
          if (!hasAnyCourses) {
            return (
              <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-12 text-center">
                <p className="text-sm font-medium text-foreground">No courses in this tenant yet</p>
                <p className="max-w-md text-sm text-muted-foreground">
                  Courses are created by teachers, not tenant admins. Once a teacher in your
                  tenant creates a course, it will appear here for you to review and manage.
                </p>
              </div>
            );
          }

          return (
            <div className="flex flex-col gap-4">
              <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
                <div className="flex flex-1 flex-col gap-1.5 sm:min-w-48">
                  <Label htmlFor="tenant-admin-courses-search">Search</Label>
                  <Input
                    id="tenant-admin-courses-search"
                    type="search"
                    placeholder="Search by course name or slug"
                    value={search}
                    onChange={(event) => setSearch(event.target.value)}
                  />
                </div>
                <div className="flex flex-col gap-1.5 sm:w-56">
                  <Label htmlFor="tenant-admin-courses-category">Category</Label>
                  <Select
                    value={categoryFilter}
                    onValueChange={(value) => setCategoryFilter(value ?? "all")}
                  >
                    <SelectTrigger id="tenant-admin-courses-category" className="w-full">
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
                  <Label htmlFor="tenant-admin-courses-status">Status</Label>
                  <Select
                    value={statusFilter}
                    onValueChange={(value) => setStatusFilter(value as "all" | CourseStatus)}
                  >
                    <SelectTrigger id="tenant-admin-courses-status" className="w-full">
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
                <div className="flex flex-col gap-1.5 sm:w-56">
                  <Label htmlFor="tenant-admin-courses-teacher">Teacher ID</Label>
                  <Input
                    id="tenant-admin-courses-teacher"
                    type="search"
                    placeholder="Filter by teacher ID"
                    value={teacherFilter}
                    onChange={(event) => setTeacherFilter(event.target.value)}
                  />
                </div>
              </div>

              {filtered.length === 0 ? (
                <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-12 text-center">
                  <p className="text-sm font-medium text-foreground">No courses match your filters</p>
                  <p className="text-sm text-muted-foreground">
                    Try a different search term, or clear your filters to see all courses in your
                    tenant.
                  </p>
                  {filtersActive ? (
                    <Button type="button" size="sm" variant="outline" onClick={clearFilters}>
                      Clear filters
                    </Button>
                  ) : null}
                </div>
              ) : (
                <CourseListTable
                  courses={filtered}
                  showTeacherColumn
                  renderActions={(course) => (
                    <Link
                      href={`/tenant-admin/courses/${course.id}`}
                      className={buttonVariants({ variant: "outline", size: "sm" })}
                    >
                      View
                    </Link>
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
