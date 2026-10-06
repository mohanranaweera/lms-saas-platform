"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useCourse, useCourseRoster, type CourseRosterEntryResponse } from "@/lib/api/courses";

const columns: DataTableColumn<CourseRosterEntryResponse>[] = [
  { key: "name", header: "Name", cell: (row) => row.name, hideOnCard: true },
  { key: "email", header: "Email", cell: (row) => row.email },
];

/** Tenant Admin course roster, using the same tenant-scoped backend contract as Teacher roster. */
export default function TenantAdminCourseRosterPage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;
  const courseQuery = useCourse(courseId);
  const rosterQuery = useCourseRoster(courseId);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-2">
        <Link
          href={`/tenant-admin/courses/${courseId}`}
          className="inline-flex w-fit items-center gap-1 text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          <ArrowLeft className="size-4" aria-hidden="true" />
          Back to course
        </Link>
        <div>
          <h1 className="text-xl font-semibold text-foreground">Roster</h1>
          <p className="text-sm text-muted-foreground">Students currently enrolled in this course.</p>
        </div>
      </div>

      <QueryStateBoundary
        query={courseQuery}
        loadingLabel="Loading course…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(course) => (
          <div className="flex flex-col gap-4">
            <p className="text-sm text-muted-foreground">
              Roster for <span className="font-medium text-foreground">{course.name}</span>.
            </p>
            <QueryStateBoundary
              query={rosterQuery}
              loadingLabel="Loading roster…"
              permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
              isEmpty={(data) => data.length === 0}
              emptyState={{
                title: "No enrolled students",
                description: "No student is currently enrolled in this course.",
              }}
            >
              {(roster) => (
                <DataTable
                  columns={columns}
                  rows={roster}
                  rowKey={(row) => row.studentId}
                  caption="Course roster"
                  cardHeading={(row) => row.name}
                />
              )}
            </QueryStateBoundary>
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
