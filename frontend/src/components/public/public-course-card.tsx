"use client";

import Link from "next/link";
import { ArrowRight, UserRound } from "lucide-react";
import { CourseCover } from "@/components/courses/student/course-visuals";
import { CoursePriceText } from "@/components/courses/course-price-display";
import type { PublicCourseResponse } from "@/lib/api/public-courses";
import { useContentLang } from "@/i18n/content-lang";

/**
 * Public catalog course card (`/courses` and the STORE-01 teacher storefront
 * homepage). Pricing is display-only; the link opens the backend-scoped
 * course detail page.
 */
export function PublicCourseCard({ course, showTeacher = true }: { course: PublicCourseResponse; showTeacher?: boolean }) {
  const contentLang = useContentLang(course.contentLocale);
  return (
    <li>
      <Link
        href={`/courses/${course.slug}`}
        className="group flex h-full flex-col overflow-hidden rounded-xl border border-border bg-card shadow-sm outline-none transition-all hover:-translate-y-1 hover:border-primary/40 hover:shadow-lg focus-visible:ring-3 focus-visible:ring-ring/50"
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
          <h2 className="line-clamp-2 text-lg font-semibold break-words text-foreground" lang={contentLang}>
            {course.name}
          </h2>
          {showTeacher && course.teacherName ? (
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
