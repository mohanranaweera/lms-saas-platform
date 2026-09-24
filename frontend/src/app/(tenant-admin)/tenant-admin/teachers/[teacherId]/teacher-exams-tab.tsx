"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useCourses } from "@/lib/api/courses";
import { useCourseExams, type ExamSummaryResponse } from "@/lib/api/exams";
import { formatDateTime } from "@/lib/format";
import { CourseScopeSelect } from "./course-scope-select";

const PAGE_SIZE = 10;

const columns: DataTableColumn<ExamSummaryResponse>[] = [
  { key: "title", header: "Title", cell: (row) => row.title, hideOnCard: true },
  { key: "status", header: "Status", cell: (row) => row.status },
  { key: "scheduledStart", header: "Starts", cell: (row) => formatDateTime(row.scheduledStart) },
];

/**
 * Exams tab (Wave 3, PAR-04-03) — `GET /v1/exams/courses/{courseId}/exams`
 * (existing, owning-Teacher-or-staff-`VIEW`), scoped to one of this teacher's
 * own courses, picked via `CourseScopeSelect` — mirrors
 * `teacher-attendance-tab.tsx`'s reasoning (no teacherId-scoped exam read
 * exists on the backend).
 */
export function TeacherExamsTab({ teacherId }: { teacherId: string }) {
  const coursesQuery = useCourses({ teacherId, size: 100 });
  // `null` means "no explicit choice yet" — see `teacher-roster-tab.tsx`'s
  // identical reasoning for why this is derived during render, not via a
  // `setState`-in-effect round trip.
  const [explicitCourseId, setExplicitCourseId] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const selectedCourseId = explicitCourseId ?? coursesQuery.data?.content[0]?.id ?? "";

  const examsQuery = useCourseExams(selectedCourseId, { page, size: PAGE_SIZE });

  return (
    <QueryStateBoundary
      query={coursesQuery}
      loadingLabel="Loading assigned courses…"
      isEmpty={(data) => data.content.length === 0}
      emptyState={{
        title: "No assigned courses",
        description: "This teacher has no courses assigned to them, so there are no exams to show.",
      }}
    >
      {(courseData) => (
        <div className="flex flex-col gap-4">
          <CourseScopeSelect
            idPrefix="teacher-exams"
            courses={courseData.content}
            value={selectedCourseId}
            onChange={(next) => {
              setExplicitCourseId(next);
              setPage(0);
            }}
          />
          {selectedCourseId ? (
            <QueryStateBoundary
              query={examsQuery}
              loadingLabel="Loading exams…"
              isEmpty={(data) => data.content.length === 0 && page === 0}
              emptyState={{
                title: "No exams for this course",
                description: "No exam has been created for this course yet.",
              }}
            >
              {(data) => (
                <div className="flex flex-col gap-4">
                  {data.content.length === 0 ? (
                    <p className="text-sm text-muted-foreground">No exams on this page.</p>
                  ) : (
                    <DataTable
                      columns={columns}
                      rows={data.content}
                      rowKey={(row) => row.id}
                      caption="Exams"
                      cardHeading={(row) => row.title}
                      cardHeadingAdornment={(row) => (
                        <span className="text-xs text-muted-foreground">{row.status}</span>
                      )}
                    />
                  )}
                  <div className="flex items-center justify-between">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => setPage((current) => Math.max(0, current - 1))}
                      disabled={page === 0 || examsQuery.isFetching}
                    >
                      Previous
                    </Button>
                    <span className="text-xs text-muted-foreground">
                      Page {data.page + 1} of {Math.max(data.totalPages, 1)}
                    </span>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => setPage((current) => current + 1)}
                      disabled={data.page + 1 >= data.totalPages || examsQuery.isFetching}
                    >
                      Next
                    </Button>
                  </div>
                </div>
              )}
            </QueryStateBoundary>
          ) : null}
        </div>
      )}
    </QueryStateBoundary>
  );
}
