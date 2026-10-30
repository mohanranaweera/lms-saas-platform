"use client";

import Link from "next/link";
import { useEffect, useId, useState, type ReactNode } from "react";
import {
  ChevronFirst,
  ChevronLast,
  ChevronLeft,
  ChevronRight,
  Download,
  EllipsisVertical,
  Eye,
  FileUp,
  PenLine,
  Search,
  Send,
  Upload,
  Users,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip } from "@/components/ui/tooltip";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { PermissionDeniedState } from "@/components/states/permission-denied-state";
import { nativeSelectClass } from "@/components/courses/admin/form-field";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { RemoveEnrollmentButton } from "@/components/exams/enrollment/exam-enrollment-actions";
import { FileLinkButton, ResultLabelBadge, marksText } from "@/components/exams/management/management-shared";
import {
  AnswerSheetDialog,
  AttemptSummaryDialog,
  AttendanceToggle,
  EnterMarksDialog,
  MarkedPaperDialog,
  type Toast,
} from "@/components/exams/management/student-row-dialogs";
import { candidateName } from "@/lib/api/exam-enrollment";
import { isApiClientError } from "@/lib/api/error";
import {
  csvFileName,
  useExamFileLink,
  useExportExamCsv,
  type ExamManagement,
  type ManagedStudentRow,
} from "@/lib/api/exam-management";
import type { ExamDetail } from "@/lib/api/exam-config";
import type { ExamPortal } from "@/components/exams/config/exam-shared";

export const PAGE_SIZES = [10, 25, 50] as const;

type Dialog = { kind: "marks" | "answer" | "marked" | "attempt"; studentId: string } | null;

/** Facts that block removal: any attempt, submission, attendance or marks (the server re-checks with 409 `HAS_ATTEMPT`). */
function hasParticipation(row: ManagedStudentRow): boolean {
  return (
    row.attemptStatus !== null ||
    (row.answerDocuments?.length ?? 0) > 0 ||
    (row.marks !== null && row.marks !== undefined && row.marks !== "") ||
    row.attendance !== null ||
    row.markedPaper !== null ||
    !["NOT_PARTICIPATED", "PENDING", "ABSENT"].includes(row.result)
  );
}

function Pagination({
  page,
  size,
  total,
  onPage,
  onSize,
}: {
  page: number;
  size: number;
  total: number;
  onPage: (page: number) => void;
  onSize: (size: number) => void;
}) {
  const selectId = useId();
  const lastPage = Math.max(0, Math.ceil(total / size) - 1);
  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min(total, (page + 1) * size);
  return (
    <nav aria-label="Enrolled students pages" className="flex flex-wrap items-center justify-end gap-x-6 gap-y-2 px-1 pt-3 text-sm" data-testid="students-pagination">
      <div className="flex items-center gap-2">
        <label htmlFor={selectId} className="whitespace-nowrap text-muted-foreground">
          Items per page:
        </label>
        <select
          id={selectId}
          value={size}
          onChange={(event) => onSize(Number(event.target.value))}
          className={`${nativeSelectClass} w-20`}
          data-testid="students-page-size"
        >
          {PAGE_SIZES.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      </div>
      <p className="tabular-nums text-foreground" aria-live="polite" data-testid="students-range">
        {from}-{to} of {total}
      </p>
      <div className="flex items-center gap-1">
        <Button type="button" variant="ghost" size="icon-sm" onClick={() => onPage(0)} disabled={page === 0} aria-label="First page" data-testid="students-first">
          <ChevronFirst aria-hidden="true" />
        </Button>
        <Button type="button" variant="ghost" size="icon-sm" onClick={() => onPage(page - 1)} disabled={page === 0} aria-label="Previous page" data-testid="students-prev">
          <ChevronLeft aria-hidden="true" />
        </Button>
        <Button type="button" variant="ghost" size="icon-sm" onClick={() => onPage(page + 1)} disabled={page >= lastPage} aria-label="Next page" data-testid="students-next">
          <ChevronRight aria-hidden="true" />
        </Button>
        <Button type="button" variant="ghost" size="icon-sm" onClick={() => onPage(lastPage)} disabled={page >= lastPage} aria-label="Last page" data-testid="students-last">
          <ChevronLast aria-hidden="true" />
        </Button>
      </div>
    </nav>
  );
}

/** Row actions per exam type. Every write is re-authorized server-side. */
function RowActions({
  exam,
  row,
  canAuthor,
  openDialog,
  onToast,
  onRemoved,
}: {
  exam: ExamDetail;
  row: ManagedStudentRow;
  canAuthor: boolean;
  openDialog: (dialog: NonNullable<Dialog>) => void;
  onToast: Toast;
  onRemoved: () => void;
}) {
  const name = candidateName(row);
  const physicalNoTotal = exam.type === "PHYSICAL" && (exam.totalMarks === null || exam.totalMarks === "");
  return (
    <div className="flex items-center justify-end gap-1">
      {exam.type === "MCQ" ? (
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          onClick={() => openDialog({ kind: "attempt", studentId: row.studentId })}
          aria-label={`View attempt summary for ${name}`}
          data-testid="row-attempt-summary"
        >
          <Eye aria-hidden="true" />
        </Button>
      ) : null}
      {canAuthor && exam.type !== "MCQ" ? (
        <DropdownMenu>
          <DropdownMenuTrigger
            render={<Button type="button" variant="ghost" size="icon-sm" />}
            aria-label={`Marking actions for ${name}`}
            data-testid="row-actions-menu"
          >
            <EllipsisVertical aria-hidden="true" />
          </DropdownMenuTrigger>
          <DropdownMenuContent>
            {exam.type === "WRITTEN" ? (
              <DropdownMenuItem onClick={() => openDialog({ kind: "answer", studentId: row.studentId })} data-testid="row-enter-answer-sheet">
                <FileUp aria-hidden="true" />
                Enter answer sheet
              </DropdownMenuItem>
            ) : null}
            <DropdownMenuItem
              onClick={() => openDialog({ kind: "marks", studentId: row.studentId })}
              disabled={physicalNoTotal}
              data-testid="row-enter-marks"
              className="items-start"
            >
              <PenLine className="mt-0.5" aria-hidden="true" />
              <span className="flex flex-col">
                {row.marks === null ? "Enter marks" : "Change marks"}
                {physicalNoTotal ? <span className="text-xs font-normal text-muted-foreground">Set the total marks first</span> : null}
              </span>
            </DropdownMenuItem>
            {exam.type === "WRITTEN" ? (
              <DropdownMenuItem onClick={() => openDialog({ kind: "marked", studentId: row.studentId })} data-testid="row-upload-marked-paper">
                <Upload aria-hidden="true" />
                Upload marked paper
              </DropdownMenuItem>
            ) : null}
          </DropdownMenuContent>
        </DropdownMenu>
      ) : null}
      {canAuthor ? (
        <RemoveEnrollmentButton
          examId={exam.id}
          studentId={row.studentId}
          studentName={name}
          hasAttempt={hasParticipation(row)}
          onRemoved={() => {
            onToast({ kind: "success", text: `${name} removed from this exam.` });
            onRemoved();
          }}
          onError={(text) => onToast({ kind: "error", text })}
        />
      ) : null}
    </div>
  );
}

function TableSkeleton() {
  return (
    <div role="status" aria-busy="true" className="flex flex-col gap-2 py-2">
      <span className="sr-only">Loading enrolled students…</span>
      {Array.from({ length: 5 }, (_, index) => (
        <Skeleton key={index} className="h-10 w-full" />
      ))}
    </div>
  );
}

/**
 * `contain: inline-size` keeps the wide md+ table from widening the portal
 * layout's flex column (it scrolls inside its own `overflow-x-auto` box).
 *
 * "Enrolled Students" (Klass manage screen): server-side search (debounced),
 * CSV export of the full filtered set, Notify Marks (Slice E), the result
 * table (cards below `md`) and pagination.
 */
export function EnrolledStudentsTable({
  exam,
  portal,
  query,
  search,
  onSearch,
  page,
  size,
  onPage,
  onSize,
  canAuthor,
  onToast,
  notifyMarks,
}: {
  exam: ExamDetail;
  portal: ExamPortal;
  query: {
    status: "pending" | "error" | "success";
    data: ExamManagement | undefined;
    error: unknown;
    isFetching: boolean;
    isPlaceholderData: boolean;
    refetch: () => Promise<unknown>;
  };
  search: string;
  onSearch: (value: string) => void;
  page: number;
  size: number;
  onPage: (page: number) => void;
  onSize: (size: number) => void;
  canAuthor: boolean;
  onToast: Toast;
  /** Slice E wires Notify Marks; until then the button is disabled with a tooltip. */
  notifyMarks?: { onClick: () => void; disabledReason?: string | null };
}) {
  const searchId = useId();
  const [input, setInput] = useState(search);
  const [dialog, setDialog] = useState<Dialog>(null);
  const exportCsv = useExportExamCsv(exam.id);
  const fileLink = useExamFileLink(exam.id);

  useEffect(() => {
    const handle = setTimeout(() => {
      if (input.trim() !== search.trim()) onSearch(input.trim());
    }, 300);
    return () => clearTimeout(handle);
  }, [input, search, onSearch]);

  const rows = query.data?.students.content ?? [];
  const total = query.data?.students.totalElements ?? 0;
  const activeRow = dialog ? (rows.find((row) => row.studentId === dialog.studentId) ?? null) : null;

  // A page past the end (e.g. after a removal) steps back to the last page.
  useEffect(() => {
    if (query.status === "success" && !query.isPlaceholderData && rows.length === 0 && page > 0 && total > 0) {
      onPage(Math.max(0, Math.ceil(total / size) - 1));
    }
  }, [query.status, query.isPlaceholderData, rows.length, page, total, size, onPage]);

  async function openFile(target: { kind: "answer"; studentId: string; documentId: string } | { kind: "marked"; studentId: string }) {
    try {
      const result = await fileLink.mutateAsync(target);
      window.open(result.url, "_blank", "noopener,noreferrer");
    } catch (error) {
      onToast({ kind: "error", text: mutationErrorMessage(error) });
    }
  }

  async function runExport() {
    try {
      const result = await exportCsv.mutateAsync({ search, fallbackName: csvFileName(exam.name) });
      onToast({ kind: "success", text: `Downloaded ${result.fileName}.` });
    } catch (error) {
      onToast({ kind: "error", text: mutationErrorMessage(error) });
    }
  }

  const columns: DataTableColumn<ManagedStudentRow>[] = [
    {
      key: "index",
      header: "Index Number",
      hideOnCard: true,
      cell: (row) =>
        row.studentProfileId && portal === "tenant-admin" ? (
          <Link href={`/tenant-admin/students/${encodeURIComponent(row.studentProfileId)}`} className="font-medium text-primary underline underline-offset-4" data-testid="row-index">
            {row.indexNumber ?? "—"}
          </Link>
        ) : (
          <span className="font-mono" data-testid="row-index">
            {row.indexNumber ?? "—"}
          </span>
        ),
    },
    { key: "first", header: "First Name", hideOnCard: true, cell: (row) => row.firstName || "—" },
    { key: "last", header: "Last Name", hideOnCard: true, cell: (row) => row.lastName || "—" },
    { key: "result", header: "Result", hideOnCard: true, cell: (row) => <ResultLabelBadge result={row.result} /> },
    {
      key: "marks",
      header: "Marks",
      cell: (row) => (
        <span className="tabular-nums" data-testid="row-marks">
          {marksText(row.marks)}
        </span>
      ),
    },
  ];
  if (exam.type === "WRITTEN") {
    columns.push(
      {
        key: "answer",
        header: "Answer Document",
        cell: (row) =>
          (row.answerDocuments?.length ?? 0) > 0 ? (
            <ul className="flex min-w-0 flex-col gap-0.5" data-testid="row-answer-docs">
              {row.answerDocuments!.map((document) => (
                <li key={document.id} className="min-w-0">
                  <FileLinkButton
                    fileName={document.fileName}
                    label={`Open answer document ${document.fileName} for ${candidateName(row)}`}
                    onOpen={() => void openFile({ kind: "answer", studentId: row.studentId, documentId: document.id })}
                  />
                </li>
              ))}
            </ul>
          ) : (
            <span className="text-muted-foreground" aria-label="No answer document">
              —
            </span>
          ),
      },
      {
        key: "markedPaper",
        header: "Marked Paper Document",
        cell: (row) =>
          row.markedPaper ? (
            <FileLinkButton
              fileName={row.markedPaper.fileName}
              label={`Open marked paper ${row.markedPaper.fileName} for ${candidateName(row)}`}
              onOpen={() => void openFile({ kind: "marked", studentId: row.studentId })}
              testId="row-marked-paper"
            />
          ) : (
            <span className="text-muted-foreground" aria-label="No marked paper">
              —
            </span>
          ),
      }
    );
  }
  if (exam.type === "PHYSICAL") {
    columns.push({
      key: "attendance",
      header: "Attendance",
      cell: (row) =>
        canAuthor ? (
          <AttendanceToggle examId={exam.id} row={row} onToast={onToast} />
        ) : (
          <span data-testid="row-attendance">{row.attendance === "PRESENT" ? "Present" : row.attendance === "ABSENT" ? "Absent" : "—"}</span>
        ),
    });
  }
  columns.push({
    key: "actions",
    header: "Actions",
    hideOnCard: true,
    className: "text-right",
    cell: (row) => (
      <RowActions
        exam={exam}
        row={row}
        canAuthor={canAuthor}
        openDialog={setDialog}
        onToast={onToast}
        onRemoved={() => void query.refetch()}
      />
    ),
  });

  let body: ReactNode;
  if (query.status === "pending") {
    body = <TableSkeleton />;
  } else if (query.status === "error") {
    body =
      isApiClientError(query.error) && query.error.status === 403 ? (
        <PermissionDeniedState error={query.error} />
      ) : (
        <ErrorState message="We couldn't load the enrolled students." onRetry={() => void query.refetch()} />
      );
  } else if (rows.length === 0 && total === 0) {
    body = search ? (
      <EmptyState
        title="No students match your search"
        description={`No enrolled student matches “${search}”. Try a different name or index number.`}
        action={{
          label: "Clear search",
          onClick: () => {
            setInput("");
            onSearch("");
          },
        }}
      />
    ) : (
      <EmptyState
        title="No students are enrolled yet"
        description={
          exam.enrollAllStudents
            ? "Every student of the course is enrolled automatically; they appear here once the course has students."
            : exam.autoEnrollOnPayment
              ? "Students appear here when a matching payment is confirmed, or when you enroll them."
              : "Use “Enroll students” to add students of this course to the exam."
        }
        icon={<Users className="size-8" aria-hidden="true" />}
      />
    );
  } else {
    body = (
      <div aria-busy={query.isFetching} className={query.isPlaceholderData ? "opacity-70 transition-opacity" : undefined} data-testid="students-table">
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(row) => row.studentId}
          caption={`Students enrolled in ${exam.name}`}
          cardHeading={(row) => (
            <span className="flex min-w-0 flex-col">
              <span className="break-words">{candidateName(row)}</span>
              <span className="font-mono text-xs font-normal text-muted-foreground">{row.indexNumber ?? "—"}</span>
            </span>
          )}
          cardHeadingAdornment={(row) => <ResultLabelBadge result={row.result} />}
          cardFooter={(row) => (
            <RowActions
              exam={exam}
              row={row}
              canAuthor={canAuthor}
              openDialog={setDialog}
              onToast={onToast}
              onRemoved={() => void query.refetch()}
            />
          )}
        />
      </div>
    );
  }

  const notifyDisabledReason = notifyMarks ? (notifyMarks.disabledReason ?? null) : "Available after results are published";
  const notifyButton = (
    <Button
      type="button"
      aria-disabled={notifyDisabledReason ? true : undefined}
      onClick={() => {
        if (!notifyDisabledReason) notifyMarks?.onClick();
      }}
      className={notifyDisabledReason ? "cursor-not-allowed opacity-50" : undefined}
      aria-label={notifyDisabledReason ? `Notify marks: ${notifyDisabledReason}` : "Notify marks"}
      data-testid="exam-notify-marks"
    >
      <Send aria-hidden="true" />
      Notify marks
    </Button>
  );

  return (
    <section aria-labelledby="enrolled-students-title" className="flex min-w-0 flex-col rounded-xl bg-card p-3 shadow-sm ring-1 ring-foreground/10 [contain:inline-size] sm:p-4" data-testid="enrolled-students">
      <div className="flex flex-col gap-3 border-b border-border pb-3 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex min-w-0 flex-col gap-3 sm:flex-row sm:items-center">
          <h2 id="enrolled-students-title" className="shrink-0 text-xl font-normal text-foreground">
            Enrolled Students
          </h2>
          <div className="relative w-full sm:w-72">
            <label htmlFor={searchId} className="sr-only">
              Search enrolled students
            </label>
            <Input
              id={searchId}
              type="search"
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder="Search"
              className="h-9 pr-8"
              data-testid="students-search"
            />
            <Search className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
          </div>
        </div>
        <div className="flex flex-col gap-2 sm:flex-row">
          <Button
            type="button"
            variant="outline"
            onClick={() => void runExport()}
            disabled={exportCsv.isPending}
            aria-busy={exportCsv.isPending}
            data-testid="exam-export-csv"
          >
            <Download aria-hidden="true" />
            {exportCsv.isPending ? "Exporting…" : "Export as CSV"}
          </Button>
          {notifyDisabledReason ? <Tooltip label={notifyDisabledReason}>{notifyButton}</Tooltip> : notifyButton}
        </div>
      </div>
      <div className="pt-3">{body}</div>
      {query.status === "success" && total > 0 ? (
        <Pagination page={page} size={size} total={total} onPage={onPage} onSize={onSize} />
      ) : null}

      {dialog?.kind === "marks" ? (
        <EnterMarksDialog
          key={dialog.studentId}
          exam={exam}
          row={activeRow}
          open
          onOpenChange={(open) => !open && setDialog(null)}
          onToast={onToast}
        />
      ) : null}
      {dialog?.kind === "answer" ? (
        <AnswerSheetDialog
          key={dialog.studentId}
          examId={exam.id}
          row={activeRow}
          open
          onOpenChange={(open) => !open && setDialog(null)}
          onOpenDocument={(row, documentId) => void openFile({ kind: "answer", studentId: row.studentId, documentId })}
          onToast={onToast}
        />
      ) : null}
      {dialog?.kind === "marked" ? (
        <MarkedPaperDialog
          key={dialog.studentId}
          examId={exam.id}
          row={activeRow}
          open
          onOpenChange={(open) => !open && setDialog(null)}
          onToast={onToast}
        />
      ) : null}
      {dialog?.kind === "attempt" ? (
        <AttemptSummaryDialog exam={exam} row={activeRow} open onOpenChange={(open) => !open && setDialog(null)} />
      ) : null}
    </section>
  );
}
