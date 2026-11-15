"use client";

import { Suspense, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Plus } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { LoadingState } from "@/components/states/loading-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ClassSessionStatusBadge } from "@/components/live-classes/class-session-status-badge";
import { ProviderStatusBadge } from "@/components/live-classes/provider-status-badge";
import { useCourses } from "@/lib/api/courses";
import { useClassSessions, type ClassSessionStatus } from "@/lib/api/class-sessions";
import { formatDateTime } from "@/lib/format";

import { useT, type TranslateFn } from "@/i18n/i18n-provider";
const STATUS_FILTER_OPTIONS = (t: TranslateFn): Array<{ value: "all" | ClassSessionStatus; label: string }> => ([
  { value: "all", label: t("teacher.page.liveClasses.allStatuses") },
  { value: "SCHEDULED", label: t("teacher.page.liveClasses.scheduled") },
  { value: "LIVE", label: t("teacher.page.liveClasses.live") },
  { value: "COMPLETED", label: t("teacher.page.liveClasses.completed") },
  { value: "CANCELLED", label: t("teacher.page.liveClasses.cancelled") },
]);

const ALL_COURSES_VALUE = "all";

/**
 * Teacher "Live Classes" list (Wave 4 plan §5) — `GET /v1/class-sessions`
 * server-scopes results to this Teacher's own courses (never client-filtered
 * for "convenience", per `.claude/rules/ui-ux.md` §1); `courseId`/`status`
 * here are additional narrowing filters passed straight through to the
 * server, not a client-side re-filter over a wider fetch.
 *
 * Accepts an optional `?courseId=` query param as the initial course filter
 * — the "Manage live classes" link on the Teacher course edit page
 * (`components/courses/course-visibility-control.tsx`'s sibling controls)
 * deep-links here pre-filtered to that course; the value is only ever a
 * starting point for this page's own `Select`, never itself trusted as an
 * access grant (the underlying `GET /v1/class-sessions` read is still
 * server-scoped to this Teacher's own courses regardless).
 */
function TeacherLiveClassesPageContent() {
  const t = useT();
  const router = useRouter();
  const searchParams = useSearchParams();
  const coursesQuery = useCourses();
  const [courseFilter, setCourseFilter] = useState<string>(searchParams.get("courseId") ?? ALL_COURSES_VALUE);
  const [statusFilter, setStatusFilter] = useState<"all" | ClassSessionStatus>("all");

  const query = useClassSessions({
    courseId: courseFilter === ALL_COURSES_VALUE ? undefined : courseFilter,
    status: statusFilter === "all" ? undefined : statusFilter,
  });

  const courseOptions = coursesQuery.data?.content ?? [];
  const courseNameById = useMemo(
    () => new Map((coursesQuery.data?.content ?? []).map((course) => [course.id, course.name])),
    [coursesQuery.data]
  );

  const filtersActive = courseFilter !== ALL_COURSES_VALUE || statusFilter !== "all";

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-foreground">{t("teacher.page.liveClasses.liveClasses")}</h1>
          <p className="text-sm text-muted-foreground">
            {t("teacher.page.liveClasses.scheduleAndManageLive")}
          </p>
        </div>
        <Button render={<Link href="/teacher/live-classes/new" />}>
          <Plus aria-hidden="true" />
          {t("teacher.page.liveClasses.scheduleLiveClass")}
        </Button>
      </div>

      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="flex flex-col gap-1.5 sm:w-64">
          <Label htmlFor="teacher-live-classes-course">{t("teacher.page.liveClasses.course")}</Label>
          <Select value={courseFilter} onValueChange={(value) => setCourseFilter(value ?? ALL_COURSES_VALUE)}>
            <SelectTrigger id="teacher-live-classes-course" className="w-full">
              <SelectValue placeholder={t("teacher.page.liveClasses.allCourses")}>
                {(selected: string | null) =>
                  selected && selected !== ALL_COURSES_VALUE
                    ? courseNameById.get(selected) ?? selected
                    : t("teacher.page.liveClasses.allCourses")
                }
              </SelectValue>
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_COURSES_VALUE}>{t("teacher.page.liveClasses.allCourses")}</SelectItem>
              {courseOptions.map((course) => (
                <SelectItem key={course.id} value={course.id}>
                  {course.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1.5 sm:w-56">
          <Label htmlFor="teacher-live-classes-status">{t("teacher.page.liveClasses.status")}</Label>
          <Select value={statusFilter} onValueChange={(value) => setStatusFilter((value as "all" | ClassSessionStatus) ?? "all")}>
            <SelectTrigger id="teacher-live-classes-status" className="w-full">
              <SelectValue placeholder={t("teacher.page.liveClasses.allStatuses")} />
            </SelectTrigger>
            <SelectContent>
              {STATUS_FILTER_OPTIONS(t).map((option) => (
                <SelectItem key={option.value} value={option.value}>
                  {option.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel={t("teacher.page.liveClasses.loadingYourLiveClasses")}
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
        isEmpty={(data) => data.length === 0}
        emptyState={
          filtersActive
            ? {
                title: t("teacher.page.liveClasses.noLiveClassesMatch"),
                description: t("teacher.page.liveClasses.tryADifferentCourse"),
                action: { label: t("teacher.page.liveClasses.clearFilters"), onClick: () => {
                  setCourseFilter(ALL_COURSES_VALUE);
                  setStatusFilter("all");
                } },
              }
            : {
                title: t("teacher.page.liveClasses.noLiveClassesScheduled"),
                description: t("teacher.page.liveClasses.scheduleYourFirstLive"),
                action: { label: t("teacher.page.liveClasses.scheduleLiveClass"), onClick: () => router.push("/teacher/live-classes/new") },
              }
        }
      >
        {(sessions) => (
          <ul className="flex flex-col gap-2">
            {sessions.map((session) => (
              <li
                key={session.id}
                className="flex flex-col gap-2 rounded-lg border border-border p-4 sm:flex-row sm:items-center sm:justify-between"
              >
                <div className="flex flex-col gap-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-sm font-medium text-foreground">{session.title}</span>
                    <ClassSessionStatusBadge status={session.status} />
                    {session.providerStatus !== "PROVISIONED" ? (
                      <ProviderStatusBadge status={session.providerStatus} />
                    ) : null}
                  </div>
                  <span className="text-xs text-muted-foreground">
                    {courseNameById.get(session.courseId) ?? t("teacher.page.liveClasses.course")} · {formatDateTime(session.scheduledStart)} –{" "}
                    {formatDateTime(session.scheduledEnd)}
                  </span>
                </div>
                <Link
                  href={`/teacher/live-classes/${session.id}`}
                  className={buttonVariants({ variant: "outline", size: "sm" })}
                >
                  View
                </Link>
              </li>
            ))}
          </ul>
        )}
      </QueryStateBoundary>
    </div>
  );
}

/** `useSearchParams()` requires a `Suspense` boundary, mirroring `tenant-admin/courses/[courseId]/page.tsx`'s identical wrapper. */
export default function TeacherLiveClassesPage() {
  const t = useT();
  return (
    <Suspense fallback={<LoadingState label={t("teacher.page.liveClasses.loadingYourLiveClasses")} />}>
      <TeacherLiveClassesPageContent />
    </Suspense>
  );
}
