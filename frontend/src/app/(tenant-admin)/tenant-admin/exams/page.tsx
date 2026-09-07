"use client";

import { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { LiveRegion } from "@/components/ui/live-region";
import { ExamStatusChip } from "@/components/exams/exam-status-chip";
import { useTenantExams, type ExamStatus, type ExamSummaryResponse } from "@/lib/api/exams";
import { formatDateTime, shortId } from "@/lib/format";

const PAGE_SIZE = 20;

const STATUS_FILTERS: { label: string; value: ExamStatus | undefined }[] = [
  { label: "All", value: undefined },
  { label: "Draft", value: "DRAFT" },
  { label: "Scheduled", value: "SCHEDULED" },
  { label: "Published", value: "PUBLISHED" },
  { label: "Closed", value: "CLOSED" },
];

/**
 * Shared Scheduler/Publish/Marking-queue action links — used both as the
 * desktop table's "Actions" column cell and as the mobile card's
 * `cardFooter`, so nothing is silently lost below `md` (`.claude/rules/ui-ux.md` §5).
 */
function ExamActionLinks({ examId }: { examId: string }) {
  return (
    <div className="flex flex-col gap-1 text-sm">
      <Link href={`/teacher/exams/${examId}/schedule`} className="text-foreground hover:underline">
        Scheduler
      </Link>
      <Link href={`/teacher/exams/${examId}/publish`} className="text-foreground hover:underline">
        Publish
      </Link>
      <Link href={`/teacher/exams/marking?examId=${examId}`} className="text-foreground hover:underline">
        Marking queue
      </Link>
    </div>
  );
}

const columns: DataTableColumn<ExamSummaryResponse>[] = [
  { key: "title", header: "Title", cell: (exam) => exam.title },
  { key: "course", header: "Course", cell: (exam) => shortId(exam.courseId, "Course") },
  { key: "status", header: "Status", cell: (exam) => <ExamStatusChip status={exam.status} /> },
  {
    key: "window",
    header: "Window",
    cell: (exam) => `${formatDateTime(exam.scheduledStart)} → ${formatDateTime(exam.scheduledEnd)}`,
  },
  {
    key: "actions",
    header: "Actions",
    hideOnCard: true,
    cell: (exam) => <ExamActionLinks examId={exam.id} />,
  },
];

/**
 * Tenant Admin / Exam Manager Exam Oversight (MVP-017 plan §11 screen #8).
 *
 * Rebuilt post-review onto the real `GET /api/v1/exams` tenant-wide list
 * endpoint (previously an ID-paste-only lookup tool, disclosed as a stopgap
 * pending that endpoint — see `lib/api/exams.ts`'s doc comment for the
 * closed-gap record). Staff-only server-side
 * (`ExamAccessGuard#requireStaffTenantWideViewAccess`); this page renders for
 * Tenant Admin, Exam Manager, and Read-only Auditor (view-only — no mutating
 * control exists on this screen itself, only links out to the
 * Scheduler/Publish/Marking-queue screens, each independently re-enforced
 * server-side).
 */
export default function TenantAdminExamOversightPage() {
  const [status, setStatus] = useState<ExamStatus | undefined>(undefined);
  const [page, setPage] = useState(0);
  const query = useTenantExams(status, { page, size: PAGE_SIZE });
  const isRefetching = query.isFetching && query.data !== undefined;

  function handleStatusChange(next: ExamStatus | undefined) {
    setStatus(next);
    setPage(0);
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Exam Oversight</h1>
        <p className="text-sm text-muted-foreground">Every exam across your tenant, filterable by status.</p>
      </div>

      <div className="flex flex-wrap gap-2" role="group" aria-label="Filter by status">
        {STATUS_FILTERS.map((filter) => (
          <Button
            key={filter.label}
            type="button"
            variant={status === filter.value ? "default" : "outline"}
            size="sm"
            aria-pressed={status === filter.value}
            onClick={() => handleStatusChange(filter.value)}
          >
            {filter.label}
          </Button>
        ))}
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading exams…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => data.content.length === 0 && page === 0}
        emptyState={
          status
            ? {
                title: `No ${status.toLowerCase()} exams`,
                description: "Try a different status filter, or view all exams.",
                action: { label: "View all", onClick: () => handleStatusChange(undefined) },
              }
            : {
                title: "No exams exist yet",
                description: "Exams created by teachers across your tenant will appear here.",
              }
        }
      >
        {(data) => (
          <div className="flex flex-col gap-4" aria-busy={isRefetching}>
            <LiveRegion message={isRefetching ? "Updating…" : ""} />
            {data.content.length === 0 ? (
              <EmptyState
                title="No more results"
                description="There are no exams on this page. Go back to an earlier page."
              />
            ) : (
              <div className={isRefetching ? "opacity-60" : undefined}>
                <DataTable
                  columns={columns}
                  rows={data.content}
                  rowKey={(row) => row.id}
                  caption="Exam oversight"
                  cardHeading={(row) => row.title}
                  cardHeadingAdornment={(row) => <ExamStatusChip status={row.status} />}
                  cardFooter={(row) => (
                    <div className="border-t border-border pt-2">
                      <ExamActionLinks examId={row.id} />
                    </div>
                  )}
                />
              </div>
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
    </div>
  );
}
