"use client";

import type { ReactNode } from "react";
import { BookOpenCheck } from "lucide-react";
import { Breadcrumbs, type BreadcrumbItem } from "@/components/ui/breadcrumbs";
import { Skeleton } from "@/components/ui/skeleton";
import { useCourse } from "@/lib/api/courses";
import { useKlassModules } from "@/lib/api/klass-admin";
import { ApiClientError } from "@/lib/api/error";

/** Which portal route tree the module question bank is rendered in. */
export type BankPortal = "tenant-admin" | "teacher";

export function bankPaths(portal: BankPortal, courseId: string, moduleId: string) {
  const courseBase = `/${portal}/courses/${courseId}`;
  const bank = `${courseBase}/modules/${moduleId}/questions`;
  return {
    courses: `/${portal}/courses`,
    /** The course's module list — where "Manage Questions" lives. */
    course: `${courseBase}/modules`,
    bank,
    create: `${bank}/new`,
    edit: (questionId: string) => `${bank}/${questionId}/edit`,
    dashboard: `/${portal}/dashboard`,
  };
}

/**
 * Course + module names for the page title and breadcrumbs. A module id that
 * isn't in the course's module list is reported as a 404 (the backend
 * enforces the same rule on every bank endpoint).
 */
export function useBankContext(courseId: string, moduleId: string) {
  const courseQuery = useCourse(courseId);
  const modulesQuery = useKlassModules(courseId);
  const found = modulesQuery.data?.find((item) => item.id === moduleId) ?? null;

  const status: "pending" | "error" | "success" =
    courseQuery.status === "error" || modulesQuery.status === "error"
      ? "error"
      : courseQuery.status === "pending" || modulesQuery.status === "pending"
        ? "pending"
        : found
          ? "success"
          : "error";

  const error =
    courseQuery.error ??
    modulesQuery.error ??
    (status === "error"
      ? new ApiClientError({ code: "NOT_FOUND", message: "This module was not found in the course.", fieldErrors: [] }, 404)
      : null);

  return {
    status,
    error,
    data: status === "success" && courseQuery.data && found ? { course: courseQuery.data, module: found } : undefined,
    refetch: async () => {
      await Promise.all([courseQuery.refetch(), modulesQuery.refetch()]);
    },
  };
}

export function BankPageHeader({
  title,
  breadcrumbs,
  actions,
}: {
  title: string;
  breadcrumbs: BreadcrumbItem[];
  actions?: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
      <div className="flex min-w-0 flex-col gap-2">
        <div className="flex items-center gap-3">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <BookOpenCheck className="size-5" aria-hidden="true" />
          </span>
          <h1 className="min-w-0 text-xl font-semibold break-words text-foreground" data-testid="bank-heading">
            {title}
          </h1>
        </div>
        <div className="sm:pl-13">
          <Breadcrumbs items={breadcrumbs} />
        </div>
      </div>
      {actions ? <div className="flex flex-wrap gap-2">{actions}</div> : null}
    </div>
  );
}

export function BankHeaderSkeleton({ label }: { label: string }) {
  return (
    <div role="status" aria-busy="true" className="flex flex-col gap-3">
      <span className="sr-only">{label}</span>
      <div className="flex items-center gap-3">
        <Skeleton className="size-10 rounded-lg" />
        <Skeleton className="h-6 w-64 max-w-full" />
      </div>
      <Skeleton className="h-4 w-80 max-w-full" />
    </div>
  );
}

/** Small uppercase section label (Klass "ANSWER 1", "EXPLANATION"). */
export function SectionLabel({ id, children }: { id?: string; children: ReactNode }) {
  return (
    <p id={id} className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
      {children}
    </p>
  );
}
