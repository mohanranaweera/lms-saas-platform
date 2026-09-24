"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useStudentAttempts } from "@/lib/api/exams";
import type { ExamAttemptResponse } from "@/lib/api/exams";
import { formatDateTime, shortId } from "@/lib/format";

const PAGE_SIZE = 10;

const columns: DataTableColumn<ExamAttemptResponse>[] = [
  { key: "exam", header: "Exam", cell: (row) => shortId(row.examId, "Exam") },
  { key: "status", header: "Status", cell: (row) => row.status },
  { key: "startedAt", header: "Started", cell: (row) => formatDateTime(row.startedAt) },
  {
    key: "submittedAt",
    header: "Submitted",
    cell: (row) => (row.submittedAt ? formatDateTime(row.submittedAt) : "Not submitted"),
  },
];

/** Exams tab (Wave 3) — `GET /v1/exams/students/{id}/attempts`, paginated. */
export function ExamsTab({ studentId }: { studentId: string }) {
  const [page, setPage] = useState(0);
  const query = useStudentAttempts(studentId, { page, size: PAGE_SIZE });

  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading exam attempts…"
      isEmpty={(data) => data.content.length === 0 && page === 0}
      emptyState={{
        title: "No exam attempts yet",
        description: "This student hasn't attempted any exam yet.",
      }}
    >
      {(data) => (
        <div className="flex flex-col gap-4">
          {data.content.length === 0 ? (
            <p className="text-sm text-muted-foreground">No records on this page.</p>
          ) : (
            <DataTable
              columns={columns}
              rows={data.content}
              rowKey={(row) => row.id}
              caption="Exam attempts"
              cardHeading={(row) => shortId(row.examId, "Exam")}
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
              disabled={page === 0 || query.isFetching}
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
              disabled={data.page + 1 >= data.totalPages || query.isFetching}
            >
              Next
            </Button>
          </div>
        </div>
      )}
    </QueryStateBoundary>
  );
}
