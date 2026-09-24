"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageLiveClassesStaff } from "@/lib/auth/permissions";
import { Button, buttonVariants } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { ClassSessionStatusBadge } from "@/components/live-classes/class-session-status-badge";
import { ProviderStatusBadge } from "@/components/live-classes/provider-status-badge";
import { useCourses } from "@/lib/api/courses";
import { useTeachers } from "@/lib/api/teachers";
import {
  useClassSessions,
  useRetryClassSessionProvisioning,
  type ClassSessionProviderStatus,
  type ClassSessionResponse,
  type ClassSessionStatus,
} from "@/lib/api/class-sessions";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime, shortId } from "@/lib/format";

const ALL_VALUE = "all";

const STATUS_FILTER_OPTIONS: Array<{ value: "all" | ClassSessionStatus; label: string }> = [
  { value: "all", label: "All statuses" },
  { value: "SCHEDULED", label: "Scheduled" },
  { value: "LIVE", label: "Live" },
  { value: "COMPLETED", label: "Completed" },
  { value: "CANCELLED", label: "Cancelled" },
];

/**
 * The backend's `GET /v1/class-sessions` has no `providerStatus` query
 * param, so this filter is applied client-side over the (already
 * tenant-scoped) result set — same shape as the Teacher filter below. Kept
 * as its own filter (not folded into the lifecycle `status` filter above)
 * since the two are independent axes: a `SCHEDULED` session can be
 * `PENDING`, `PROVISIONED`, or `FAILED`. `"FAILED"` is listed first so a
 * Tenant Admin scanning for a stuck provisioning finds it immediately,
 * per plan §5's explicit requirement that this be "distinctly filterable".
 */
const PROVIDER_STATUS_FILTER_OPTIONS: Array<{ value: "all" | ClassSessionProviderStatus; label: string }> = [
  { value: "all", label: "All meeting statuses" },
  { value: "FAILED", label: "Provisioning failed" },
  { value: "PENDING", label: "Provisioning…" },
  { value: "PROVISIONED", label: "Meeting ready" },
];

function RetryProvisioningCell({ session }: { session: ClassSessionResponse }) {
  const mutation = useRetryClassSessionProvisioning(session.id);
  const [error, setError] = useState<string | null>(null);
  if (session.providerStatus !== "FAILED" && session.providerStatus !== "PENDING") return null;

  return (
    <div className="flex flex-col items-start gap-1">
      <Button
        type="button"
        size="sm"
        variant="outline"
        disabled={mutation.isPending}
        aria-busy={mutation.isPending}
        onClick={async () => {
          setError(null);
          try {
            await mutation.mutateAsync();
          } catch (err) {
            setError(isApiClientError(err) ? err.message : "Retry failed. Please try again.");
          }
        }}
      >
        {mutation.isPending ? "Retrying…" : "Retry provisioning"}
      </Button>
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/**
 * Tenant Admin "Live Classes" oversight list (Wave 4 plan §5) — tenant-wide,
 * read-mostly (`LIVE_CLASSES`/`VIEW`: Tenant Admin, Course Coordinator).
 * `GET /v1/class-sessions` is dispatched tenant-wide server-side for a staff
 * caller holding that grant (`ClassSessionService#listSessions`) — this page
 * issues the real request unconditionally and lets `QueryStateBoundary`
 * render `PermissionDeniedState` on an actual 403; the nav entry
 * (`tenant-admin-nav.tsx`, gated on `canViewLiveClassesStaff`) is pure UX
 * convenience only.
 *
 * Course/status are server-side query params; Teacher is a client-side
 * filter over the (already tenant-scoped, unpaginated) result set — the
 * backend's `GET /v1/class-sessions` has no `teacherId` query param to push
 * this down to, unlike `courseId`/`status`.
 *
 * `providerStatus === "FAILED"` is surfaced as its own dedicated, always-
 * visible column (not hidden behind an expand toggle) so a stuck
 * provisioning is visible at a glance across the whole tenant, per plan §5's
 * explicit requirement — and the "Retry provisioning" action is shown only
 * for a caller holding `LIVE_CLASSES`/`CREATE_EDIT` (`canManageLiveClassesStaff`,
 * UX convenience only; `LiveClassAccessGuard` remains the real enforcement).
 */
export default function TenantAdminLiveClassesPage() {
  const { session } = useAuth();
  const canManage = canManageLiveClassesStaff(session?.role ?? null);

  const coursesQuery = useCourses();
  const teachersQuery = useTeachers();
  const [courseFilter, setCourseFilter] = useState<string>(ALL_VALUE);
  const [teacherFilter, setTeacherFilter] = useState<string>(ALL_VALUE);
  const [statusFilter, setStatusFilter] = useState<"all" | ClassSessionStatus>("all");
  const [providerStatusFilter, setProviderStatusFilter] = useState<"all" | ClassSessionProviderStatus>("all");

  const query = useClassSessions({
    courseId: courseFilter === ALL_VALUE ? undefined : courseFilter,
    status: statusFilter === "all" ? undefined : statusFilter,
  });

  const courseOptions = coursesQuery.data?.content ?? [];
  const teacherOptions = teachersQuery.data ?? [];
  const courseNameById = useMemo(
    () => new Map((coursesQuery.data?.content ?? []).map((course) => [course.id, course.name])),
    [coursesQuery.data]
  );
  const teacherNameById = useMemo(
    () => new Map((teachersQuery.data ?? []).map((teacher) => [teacher.id, teacher.name])),
    [teachersQuery.data]
  );

  const columns: DataTableColumn<ClassSessionResponse>[] = [
    { key: "title", header: "Title", cell: (row) => row.title },
    {
      key: "course",
      header: "Course",
      cell: (row) => courseNameById.get(row.courseId) ?? shortId(row.courseId, "Course"),
    },
    {
      key: "teacher",
      header: "Teacher",
      cell: (row) => teacherNameById.get(row.teacherId) ?? shortId(row.teacherId, "Teacher"),
    },
    { key: "scheduledStart", header: "Start", cell: (row) => formatDateTime(row.scheduledStart) },
    {
      key: "status",
      header: "Status",
      cell: (row) => <ClassSessionStatusBadge status={row.status} />,
      hideOnCard: true,
    },
    {
      key: "providerStatus",
      header: "Meeting",
      cell: (row) => <ProviderStatusBadge status={row.providerStatus} />,
    },
    ...(canManage
      ? [
          {
            key: "actions",
            header: "Actions",
            cell: (row: ClassSessionResponse) => <RetryProvisioningCell session={row} />,
          } satisfies DataTableColumn<ClassSessionResponse>,
        ]
      : []),
    {
      key: "view",
      header: "",
      cell: (row) => (
        <Link
          href={`/tenant-admin/live-classes/${row.id}`}
          className={buttonVariants({ variant: "ghost", size: "sm" })}
        >
          View
        </Link>
      ),
    },
  ];

  const rows = (query.data ?? []).filter(
    (row) =>
      (teacherFilter === ALL_VALUE || row.teacherId === teacherFilter) &&
      (providerStatusFilter === "all" || row.providerStatus === providerStatusFilter)
  );
  const filtersActive =
    courseFilter !== ALL_VALUE ||
    teacherFilter !== ALL_VALUE ||
    statusFilter !== "all" ||
    providerStatusFilter !== "all";

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Live Classes</h1>
        <p className="text-sm text-muted-foreground">
          Tenant-wide oversight of every live class session, across all courses and teachers.
        </p>
      </div>

      <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-end">
        <div className="flex flex-col gap-1.5 sm:w-56">
          <Label htmlFor="tenant-admin-live-classes-course">Course</Label>
          <Select value={courseFilter} onValueChange={(value) => setCourseFilter(value ?? ALL_VALUE)}>
            <SelectTrigger id="tenant-admin-live-classes-course" className="w-full">
              <SelectValue placeholder="All courses">
                {(selected: string | null) =>
                  selected && selected !== ALL_VALUE ? courseNameById.get(selected) ?? selected : "All courses"
                }
              </SelectValue>
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_VALUE}>All courses</SelectItem>
              {courseOptions.map((course) => (
                <SelectItem key={course.id} value={course.id}>
                  {course.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1.5 sm:w-56">
          <Label htmlFor="tenant-admin-live-classes-teacher">Teacher</Label>
          <Select value={teacherFilter} onValueChange={(value) => setTeacherFilter(value ?? ALL_VALUE)}>
            <SelectTrigger id="tenant-admin-live-classes-teacher" className="w-full">
              <SelectValue placeholder="All teachers">
                {(selected: string | null) =>
                  selected && selected !== ALL_VALUE ? teacherNameById.get(selected) ?? selected : "All teachers"
                }
              </SelectValue>
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_VALUE}>All teachers</SelectItem>
              {teacherOptions.map((teacher) => (
                <SelectItem key={teacher.id} value={teacher.id}>
                  {teacher.name}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1.5 sm:w-56">
          <Label htmlFor="tenant-admin-live-classes-status">Status</Label>
          <Select value={statusFilter} onValueChange={(value) => setStatusFilter((value as "all" | ClassSessionStatus) ?? "all")}>
            <SelectTrigger id="tenant-admin-live-classes-status" className="w-full">
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
          <Label htmlFor="tenant-admin-live-classes-provider-status">Meeting status</Label>
          <Select
            value={providerStatusFilter}
            onValueChange={(value) => setProviderStatusFilter((value as "all" | ClassSessionProviderStatus) ?? "all")}
          >
            <SelectTrigger id="tenant-admin-live-classes-provider-status" className="w-full">
              <SelectValue placeholder="All meeting statuses" />
            </SelectTrigger>
            <SelectContent>
              {PROVIDER_STATUS_FILTER_OPTIONS.map((option) => (
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
        loadingLabel="Loading live classes…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={() => rows.length === 0}
        emptyState={
          filtersActive
            ? {
                title: "No live classes match your filters",
                description: "Try a different course, teacher, or status, or clear your filters.",
                action: {
                  label: "Clear filters",
                  onClick: () => {
                    setCourseFilter(ALL_VALUE);
                    setTeacherFilter(ALL_VALUE);
                    setStatusFilter("all");
                    setProviderStatusFilter("all");
                  },
                },
              }
            : {
                title: "No live classes scheduled yet",
                description: "Live classes scheduled by teachers across your tenant will appear here.",
              }
        }
      >
        {() => (
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(row) => row.id}
            caption="Live classes"
            cardHeading={(row) => row.title}
            cardHeadingAdornment={(row) => <ClassSessionStatusBadge status={row.status} />}
            cardFooter={(row) => (
              <div className="flex items-center justify-between gap-2 pt-1">
                {canManage ? <RetryProvisioningCell session={row} /> : <span />}
                <Link
                  href={`/tenant-admin/live-classes/${row.id}`}
                  className={buttonVariants({ variant: "outline", size: "sm" })}
                >
                  View
                </Link>
              </div>
            )}
          />
        )}
      </QueryStateBoundary>
    </div>
  );
}
