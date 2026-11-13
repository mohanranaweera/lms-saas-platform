"use client";

import type { ReactNode } from "react";
import { CourseStatusBadge } from "@/components/courses/course-status-badge";
import { useT } from "@/i18n/i18n-provider";
import { useLocaleFormat } from "@/i18n/use-locale-format";
import type { CourseResponse } from "@/lib/api/courses";

/**
 * Teacher "My Courses" card grid (MVP-014 TDASH-2) — mobile-first,
 * card-based at every width, per `.claude/rules/ui-ux.md` §5's
 * consumer-style-surface pattern (Student/Teacher dashboards and course
 * pages), unlike `CourseListTable`'s table-on-`md`+/card-fallback-below-`md`
 * pattern used by the admin-heavy Tenant Admin Course List. Co-located here
 * under `components/courses/` alongside `CourseListTable`/`CourseStatusBadge`
 * since it renders the same `CourseResponse[]` shape those components do —
 * `CourseListTable` itself is untouched and still used by Tenant Admin.
 *
 * Markup mirrors `app/(public)/courses/page.tsx`'s existing `CourseCard`
 * convention, extended with `CourseStatusBadge` (status is never
 * color-only, per `.claude/rules/ui-ux.md` §4) and caller-supplied
 * Edit/Modules actions (unchanged from what `CourseListTable` rendered).
 */
export function TeacherCourseCardGrid({
  courses,
  renderActions,
}: {
  courses: CourseResponse[];
  renderActions: (course: CourseResponse) => ReactNode;
}) {
  const t = useT();
  const { formatMoney } = useLocaleFormat();
  return (
    <ul aria-label={t("teacher.courses.listLabel")} className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
      {courses.map((course) => (
        <li
          key={course.id}
          className="flex h-full min-w-0 flex-col gap-3 rounded-xl border border-border bg-card p-5"
        >
          <div className="flex items-start justify-between gap-2">
            <div className="min-w-0">
              <h3 className="text-base font-semibold break-words text-foreground">{course.name}</h3>
              <p className="text-xs break-all text-muted-foreground">{course.slug}</p>
            </div>
            <CourseStatusBadge status={course.status} />
          </div>
          <span className="w-fit rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium text-muted-foreground">
            {course.category}
          </span>
          <p className="mt-auto pt-2 text-sm font-medium text-foreground">
            {formatMoney(course.price)}
          </p>
          <div className="flex flex-wrap gap-2">{renderActions(course)}</div>
        </li>
      ))}
    </ul>
  );
}
