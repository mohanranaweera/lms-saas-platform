"use client";

import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useCourses, type CourseResponse } from "@/lib/api/courses";

const columns: DataTableColumn<CourseResponse>[] = [
  { key: "name", header: "Name", cell: (row) => row.name, hideOnCard: true },
  { key: "category", header: "Category", cell: (row) => row.category },
  { key: "status", header: "Status", cell: (row) => row.status },
];

/**
 * Assigned Courses tab (Wave 3, PAR-04-03) — `GET /v1/courses?teacherId={id}`
 * (staff-only effective filter, confirmed backend-enforced — see `useCourses`'s
 * own doc comment). Shares its React Query cache entry with the Roster/
 * Attendance/Exams tabs' course pickers (`courseKeys.list` with the identical
 * params), so switching tabs doesn't re-fetch.
 */
export function AssignedCoursesTab({ teacherId }: { teacherId: string }) {
  const query = useCourses({ teacherId, size: 100 });

  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading assigned courses…"
      isEmpty={(data) => data.content.length === 0}
      emptyState={{
        title: "No assigned courses",
        description: "This teacher has no courses assigned to them yet.",
      }}
    >
      {(data) => (
        <DataTable
          columns={columns}
          rows={data.content}
          rowKey={(row) => row.id}
          caption="Assigned courses"
          cardHeading={(row) => row.name}
          cardHeadingAdornment={(row) => (
            <span className="text-xs text-muted-foreground">{row.status}</span>
          )}
        />
      )}
    </QueryStateBoundary>
  );
}
