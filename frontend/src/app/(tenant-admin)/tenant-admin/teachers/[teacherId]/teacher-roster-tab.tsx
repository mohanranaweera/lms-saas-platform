"use client";

import { useState } from "react";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useCourses, useCourseRoster, type CourseRosterEntryResponse } from "@/lib/api/courses";
import { CourseScopeSelect } from "./course-scope-select";

const columns: DataTableColumn<CourseRosterEntryResponse>[] = [
  { key: "name", header: "Name", cell: (row) => row.name, hideOnCard: true },
  { key: "email", header: "Email", cell: (row) => row.email },
];

/**
 * Roster tab (Wave 3, PAR-04-03) — `GET /v1/courses/{courseId}/roster`
 * (staff `STUDENTS`/`VIEW` or `COURSES`/`VIEW`), scoped to one of this
 * teacher's own courses (picked via `CourseScopeSelect`). Staff viewing this
 * tab see the same backend-filtered roster the Teacher role sees on their own
 * course-scoped roster route — never a client-side-filtered wider fetch.
 */
export function TeacherRosterTab({ teacherId }: { teacherId: string }) {
  const coursesQuery = useCourses({ teacherId, size: 100 });
  // `null` means "no explicit choice yet" — the render below then falls back
  // to the first assigned course, computed directly from the just-resolved
  // query data rather than via a `setState`-in-effect round trip (which would
  // cost an extra render and trip this repo's `react-hooks/set-state-in-effect`
  // lint rule for no benefit — there's nothing external to synchronize with
  // here, just a derived default).
  const [explicitCourseId, setExplicitCourseId] = useState<string | null>(null);

  return (
    <QueryStateBoundary
      query={coursesQuery}
      loadingLabel="Loading assigned courses…"
      isEmpty={(data) => data.content.length === 0}
      emptyState={{
        title: "No assigned courses",
        description: "This teacher has no courses assigned to them, so there is no roster to show.",
      }}
    >
      {(data) => {
        const selectedCourseId = explicitCourseId ?? data.content[0]?.id ?? "";
        return (
          <div className="flex flex-col gap-4">
            <CourseScopeSelect
              idPrefix="teacher-roster"
              courses={data.content}
              value={selectedCourseId}
              onChange={setExplicitCourseId}
            />
            {selectedCourseId ? <RosterForCourse courseId={selectedCourseId} /> : null}
          </div>
        );
      }}
    </QueryStateBoundary>
  );
}

function RosterForCourse({ courseId }: { courseId: string }) {
  const query = useCourseRoster(courseId);
  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading roster…"
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
  );
}
