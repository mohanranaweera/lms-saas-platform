"use client";

import { useCallback, useState } from "react";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { Skeleton } from "@/components/ui/skeleton";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { FileClock } from "lucide-react";
import { EXAM_TYPE_ICONS, ExamPageTitle, examPaths, type ExamPortal } from "@/components/exams/config/exam-shared";
import { ExamEnrollmentActions } from "@/components/exams/enrollment/exam-enrollment-actions";
import { ExamManagementHeader } from "@/components/exams/management/exam-management-header";
import { EnrolledStudentsTable, PAGE_SIZES } from "@/components/exams/management/enrolled-students-table";
import { percentText } from "@/components/exams/management/management-shared";
import { NotifyMarksDialog } from "@/components/exams/management/notify-marks-dialog";
import { useAuth } from "@/lib/auth/auth-context";
import { canAuthorExams, canManageExamResults } from "@/lib/auth/permissions";
import { useExamDetail, type ExamDetail } from "@/lib/api/exam-config";
import { useExamManagement, type ExamManagementSummary } from "@/lib/api/exam-management";

function SummaryCard({ value, label, percent, caption, testId }: { value: number; label: string; percent?: string; caption: string; testId: string }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5 rounded-xl bg-primary/5 px-4 py-3 ring-1 ring-primary/10" data-testid={testId}>
      <p className="text-2xl font-semibold tabular-nums text-foreground" data-testid={`${testId}-value`}>
        {value}
      </p>
      <p className="text-sm text-foreground">
        {label}
        {percent !== undefined ? (
          <span className="ml-1 text-muted-foreground" data-testid={`${testId}-percent`}>
            {percent}
          </span>
        ) : null}
      </p>
      <p className="text-sm text-muted-foreground">{caption}</p>
    </div>
  );
}

/** Klass summary cards; the third card follows the exam type. Counts and percentages come from the server. */
function SummaryCards({ exam, summary, loading }: { exam: ExamDetail; summary: ExamManagementSummary | undefined; loading: boolean }) {
  if (loading || !summary) {
    return (
      <div role="status" aria-busy="true" className="grid gap-3 sm:grid-cols-3">
        <span className="sr-only">Loading summary…</span>
        {Array.from({ length: 3 }, (_, index) => (
          <Skeleton key={index} className="h-24 w-full rounded-xl" />
        ))}
      </div>
    );
  }
  return (
    <section aria-label="Participation summary" className="grid gap-3 sm:grid-cols-3" data-testid="exam-summary">
      <SummaryCard value={summary.enrolled} label="Enrolled" caption="Students added to the exam" testId="summary-enrolled" />
      {exam.type === "PHYSICAL" ? (
        <SummaryCard
          value={summary.attended}
          label="Present"
          percent={percentText(summary.attendedPercent)}
          caption="Marked present for the exam"
          testId="summary-attended"
        />
      ) : (
        <SummaryCard
          value={summary.attended}
          label="Attended"
          percent={percentText(summary.attendedPercent)}
          caption="Opened the paper"
          testId="summary-attended"
        />
      )}
      {exam.type === "MCQ" ? (
        <SummaryCard
          value={summary.completed}
          label="Completed"
          percent={percentText(summary.completedPercent)}
          caption="Finished the paper with at least one answer"
          testId="summary-completed"
        />
      ) : exam.type === "WRITTEN" ? (
        <SummaryCard
          value={summary.submitted}
          label="Submitted"
          percent={percentText(summary.submittedPercent)}
          caption="Uploaded an answer sheet"
          testId="summary-submitted"
        />
      ) : (
        <SummaryCard
          value={summary.enrolled - summary.attended}
          label="Not present"
          caption="Absent or attendance not recorded"
          testId="summary-not-present"
        />
      )}
    </section>
  );
}

function HeaderSkeleton() {
  return (
    <div role="status" aria-busy="true" className="flex flex-col gap-3 rounded-xl p-4 ring-1 ring-foreground/10">
      <span className="sr-only">Loading exam…</span>
      <Skeleton className="h-6 w-60" />
      <Skeleton className="h-4 w-40" />
      <Skeleton className="h-4 w-56" />
      <Skeleton className="h-4 w-56" />
    </div>
  );
}

/**
 * Manage exam (Slice D, Klass "previous exam manage" screen): header card,
 * participation summary, enrollment controls and the enrolled-students table.
 * The header comes from `GET /exams/{id}`; summary and students from
 * `GET /exams/{id}/management`. Action visibility is UX only — the server
 * re-authorizes every write.
 */
export function ExamManagementPage({ portal, examId }: { portal: ExamPortal; examId: string }) {
  const paths = examPaths(portal);
  const { session } = useAuth();
  const canAuthor = canAuthorExams(session?.role ?? null);
  const canNotify = canManageExamResults(session?.role ?? null);
  const detail = useExamDetail(examId);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState<number>(PAGE_SIZES[0]);
  const management = useExamManagement(examId, { search, page, size });
  const toast = useAdminToast();
  const showToast = toast.show;
  const [notifyOpen, setNotifyOpen] = useState(false);
  const resultsPublishedAt = management.data?.resultsPublishedAt ?? null;

  const onSearch = useCallback((value: string) => {
    setSearch(value);
    setPage(0);
  }, []);
  const onSize = useCallback((value: number) => {
    setSize(value);
    setPage(0);
  }, []);
  const TitleIcon = (detail.data ? EXAM_TYPE_ICONS[detail.data.type] : null) ?? FileClock;
  const onToast = useCallback((message: { kind: "success" | "error"; text: string }) => showToast(message), [showToast]);

  return (
    <div className="flex min-w-0 flex-col gap-5">
      <div className="flex flex-col gap-2">
        <ExamPageTitle
          title={detail.data?.name ?? "Manage exam"}
          icon={<TitleIcon className="size-5" aria-hidden="true" />}
        />
        <Breadcrumbs items={[{ label: "Exams", href: paths.directory }, { label: detail.data?.name ?? "Exam" }]} />
      </div>
      {detail.status === "pending" ? (
        <HeaderSkeleton />
      ) : (
        <QueryStateBoundary
          query={detail}
          loginPath="/login"
          permissionDenied={{ dashboardHref: paths.directory }}
          genericErrorMessage="We couldn't load this exam."
        >
          {(exam) => {
            // UX only: the server answers 409 NOT_ENDED and re-checks APPROVE / owning teacher.
            const serverNow = management.data?.serverNow ?? null;
            const ended =
              exam.status === "FINISHED" ||
              (serverNow !== null && new Date(serverNow).getTime() >= new Date(exam.endAt).getTime());
            return (
            <div className="flex min-w-0 flex-col gap-5">
              <ExamManagementHeader exam={exam} portal={portal} canAuthor={canAuthor} onToast={onToast} />
              <SummaryCards exam={exam} summary={management.data?.summary} loading={management.status === "pending"} />
              {canAuthor ? <ExamEnrollmentActions exam={exam} variant="toolbar" /> : null}
              <EnrolledStudentsTable
                exam={management.data?.exam ? { ...exam, ...management.data.exam } : exam}
                portal={portal}
                query={management}
                search={search}
                onSearch={onSearch}
                page={page}
                size={size}
                onPage={setPage}
                onSize={onSize}
                canAuthor={canAuthor}
                onToast={onToast}
                notifyMarks={
                  canNotify
                    ? { onClick: () => setNotifyOpen(true), disabledReason: ended ? null : "Available after the exam ends" }
                    : null
                }
                resultsPublishedAt={resultsPublishedAt}
              />
              {notifyOpen ? (
                <NotifyMarksDialog
                  exam={exam}
                  open
                  onOpenChange={setNotifyOpen}
                  onSent={(text) => onToast({ kind: "success", text })}
                />
              ) : null}
            </div>
            );
          }}
        </QueryStateBoundary>
      )}
      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </div>
  );
}
