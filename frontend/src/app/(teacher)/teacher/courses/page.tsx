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
import { TeacherCourseManageMenu } from "@/components/courses/teacher/teacher-course-manage-menu";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { useCourses, type CourseResponse, type CourseStatus } from "@/lib/api/courses";
import { useT, type TranslateFn } from "@/i18n/i18n-provider";

const STATUS_FILTER_OPTIONS: Array<{ value: "all" | CourseStatus; label: (t: TranslateFn) => string }> = [
  { value: "all", label: (t) => t("teacher.courses.filter.allStatuses") },
  { value: "DRAFT", label: (t) => t("common.courseStatus.draft") },
  { value: "PRIVATE", label: (t) => t("common.courseStatus.private") },
  { value: "PUBLIC", label: (t) => t("common.courseStatus.public") },
];

/**
 * Teacher "My Courses" list (TP-01). `GET /api/v1/courses?assignedToMe=true`
 * returns only courses the signed-in user is assigned to (primary or
 * co-teacher, `course_teacher_assignment`) in the active tenant - applied
 * server-side before filters, sorting, paging and counts, for every role
 * (a staff user opening the Teacher Portal also sees only its own
 * assignments; the Tenant Admin course workspace stays the place for broader
 * management). No client-side ownership filtering happens here, per
 * `.claude/rules/ui-ux.md` §1. Category/status search is client-side over
 * that already-restricted set (one `size=100` page, see `useCourses()`).
 *
 * TP-02: "Open course" shows the student course layout; the existing
 * management actions sit behind "Manage course".
 */
export default function TeacherCoursesPage() {
  const router = useRouter();
  const query = useCourses({ assignedToMe: true });
  const [search, setSearch] = useState("");
  const [categoryFilter, setCategoryFilter] = useState<string>("all");
  const [statusFilter, setStatusFilter] = useState<"all" | CourseStatus>("all");
  const toast = useAdminToast();
  const t = useT();

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
          <h1 className="text-xl font-semibold break-words text-foreground">{t("teacher.courses.title")}</h1>
          <p className="text-sm text-muted-foreground">{t("teacher.courses.subtitle")}</p>
        </div>
        <Button render={<Link href="/teacher/courses/new" />}>
          <Plus aria-hidden="true" />
          {t("teacher.courses.newCourse")}
        </Button>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel={t("teacher.courses.loading")}
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={{
          title: t("teacher.courses.empty.title"),
          description: t("teacher.courses.empty.description"),
          action: {
            label: t("teacher.courses.empty.action"),
            onClick: () => router.push("/teacher/courses/new"),
          },
        }}
      >
        {(courses) => {
          return (
            <div className="flex flex-col gap-4">
              <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
                <div className="flex flex-1 flex-col gap-1.5">
                  <Label htmlFor="teacher-courses-search">{t("teacher.courses.filter.search")}</Label>
                  <Input
                    id="teacher-courses-search"
                    type="search"
                    placeholder={t("teacher.courses.filter.searchPlaceholder")}
                    value={search}
                    onChange={(event) => setSearch(event.target.value)}
                  />
                </div>
                <div className="flex flex-col gap-1.5 sm:w-56">
                  <Label htmlFor="teacher-courses-category">{t("teacher.courses.filter.category")}</Label>
                  <Select
                    value={categoryFilter}
                    onValueChange={(value) => setCategoryFilter(value ?? "all")}
                  >
                    <SelectTrigger id="teacher-courses-category" className="w-full">
                      <SelectValue placeholder={t("teacher.courses.filter.allCategories")}>
                        {(value: string | null) =>
                          !value || value === "all" ? t("teacher.courses.filter.allCategories") : value
                        }
                      </SelectValue>
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="all">{t("teacher.courses.filter.allCategories")}</SelectItem>
                      {categories.map((category) => (
                        <SelectItem key={category} value={category}>
                          {category}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <div className="flex flex-col gap-1.5 sm:w-56">
                  <Label htmlFor="teacher-courses-status">{t("teacher.courses.filter.status")}</Label>
                  <Select
                    value={statusFilter}
                    onValueChange={(value) => setStatusFilter(value as "all" | CourseStatus)}
                  >
                    <SelectTrigger id="teacher-courses-status" className="w-full">
                      <SelectValue placeholder={t("teacher.courses.filter.allStatuses")}>
                        {(value: string | null) =>
                          (STATUS_FILTER_OPTIONS.find((option) => option.value === value) ?? STATUS_FILTER_OPTIONS[0]).label(t)
                        }
                      </SelectValue>
                    </SelectTrigger>
                    <SelectContent>
                      {STATUS_FILTER_OPTIONS.map((option) => (
                        <SelectItem key={option.value} value={option.value}>
                          {option.label(t)}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              </div>

              {filtered.length === 0 ? (
                <EmptyState
                  title={t("teacher.courses.noMatch.title")}
                  description={t("teacher.courses.noMatch.description")}
                  action={
                    filtersActive
                      ? { label: t("teacher.courses.noMatch.clear"), onClick: clearFilters }
                      : undefined
                  }
                />
              ) : (
                <TeacherCourseCardGrid
                  courses={filtered}
                  renderActions={(course) => (
                    <>
                      <Link
                        href={`/teacher/courses/${course.id}`}
                        className={buttonVariants({ size: "sm" })}
                        data-testid="teacher-course-open"
                      >
                        {t("teacher.courses.openCourse")}
                      </Link>
                      <TeacherCourseManageMenu
                        courseId={course.id}
                        courseName={course.name}
                        onScheduleSaved={() =>
                          toast.show({ kind: "success", text: t("teacher.courses.scheduleSaved", { course: course.name }) })
                        }
                      />
                    </>
                  )}
                />
              )}

              <p className="text-xs text-muted-foreground" aria-live="polite">
                {t("teacher.courses.showing", { shown: filtered.length, total: courses.totalElements })}
              </p>
            </div>
          );
        }}
      </QueryStateBoundary>

      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </div>
  );
}
