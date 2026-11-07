"use client";

import { useState, type ReactNode } from "react";
import { BookOpen, CalendarCheck, ClipboardList, CreditCard, MessageSquarePlus, PlayCircle, StickyNote } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorState } from "@/components/states/error-state";
import { StudentDialog } from "@/components/students/student-dialog";
import { LedgerEntryTypeBadge, PAYMENT_METHOD_LABEL } from "@/components/payments/status-badges";
import { isApiClientError } from "@/lib/api/error";
import { useStudentAttempts, type ExamAttemptResponse } from "@/lib/api/exams";
import { useStudentLedger } from "@/lib/api/ledger";
import { useStudentWatchTime } from "@/lib/api/watch-time";
import { useStudentRemarks, type DateRange, type StudentDetail } from "@/lib/api/student-management";
import { formatZonedDateTime, isWithinDateRange } from "@/lib/datetime";
import { formatMoney, shortId } from "@/lib/format";
import { AddRemarkDialog } from "../student-action-dialogs";
import { AttendanceTab } from "./attendance-tab";

const rangeActive = (range: DateRange) => Boolean(range.from || range.to);

function SectionCard({
  title,
  icon,
  action,
  children,
  testId,
}: {
  title: string;
  icon: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  testId: string;
}) {
  const headingId = `${testId}-heading`;
  return (
    <section
      aria-labelledby={headingId}
      className="rounded-xl border border-border bg-card shadow-sm"
      data-testid={testId}
    >
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-border px-4 py-3">
        <h2 id={headingId} className="flex items-center gap-2 text-base font-semibold text-foreground">
          <span className="text-muted-foreground [&_svg]:size-4" aria-hidden="true">
            {icon}
          </span>
          {title}
        </h2>
        {action}
      </div>
      <div className="p-4">{children}</div>
    </section>
  );
}

function SectionLoading({ label }: { label: string }) {
  return (
    <div className="flex flex-col gap-2" aria-busy="true">
      <span className="sr-only" role="status">
        {label}
      </span>
      <Skeleton className="h-6 w-full" />
      <Skeleton className="h-6 w-2/3" />
    </div>
  );
}

function SectionError({ error, label, onRetry }: { error: unknown; label: string; onRetry: () => void }) {
  if (isApiClientError(error) && error.status === 403) {
    return (
      <p role="alert" className="text-sm text-muted-foreground">
        Your role doesn&apos;t have access to {label}.
      </p>
    );
  }
  return (
    <ErrorState
      message={isApiClientError(error) ? error.message : `${label[0].toUpperCase()}${label.slice(1)} couldn't be loaded.`}
      onRetry={onRetry}
    />
  );
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="text-sm text-muted-foreground">{children}</p>;
}

export function RemarksSection({
  detail,
  range,
  timeZone,
  canManage,
}: {
  detail: StudentDetail;
  range: DateRange;
  timeZone: string;
  canManage: boolean;
}) {
  const query = useStudentRemarks(detail.id, range, timeZone);
  const [open, setOpen] = useState(false);
  return (
    <SectionCard
      title="Remarks"
      icon={<StickyNote />}
      testId="profile-remarks"
      action={
        canManage ? (
          <Button type="button" size="sm" onClick={() => setOpen(true)}>
            <MessageSquarePlus aria-hidden="true" />
            Add Remark
          </Button>
        ) : null
      }
    >
      {query.status === "pending" ? (
        <SectionLoading label="Loading remarks…" />
      ) : query.status === "error" ? (
        <SectionError error={query.error} label="remarks" onRetry={() => void query.refetch()} />
      ) : query.data.content.length === 0 ? (
        <Empty>{rangeActive(range) ? "No remarks in this date range." : "No remarks yet."}</Empty>
      ) : (
        <ul className="flex flex-col divide-y divide-border">
          {query.data.content.map((remark) => (
            <li key={remark.id} className="flex flex-col gap-1 py-2 first:pt-0 last:pb-0">
              {/* Plain text only — remarks are never rendered as HTML. */}
              <p className="whitespace-pre-wrap break-words text-sm text-foreground">{remark.body}</p>
              <p className="text-xs text-muted-foreground">
                {remark.authorName} · {formatZonedDateTime(remark.createdAt, timeZone)}
              </p>
            </li>
          ))}
        </ul>
      )}
      {canManage ? (
        <AddRemarkDialog
          student={{ id: detail.id, name: detail.fullName, indexNumber: detail.indexNumber }}
          open={open}
          onOpenChange={setOpen}
        />
      ) : null}
    </SectionCard>
  );
}

/**
 * Optional display fields a future attempt payload may carry. The current
 * `GET /exams/students/{id}/attempts` contract has none of them, so the card
 * falls back to a short exam id and the attempt status (reported as a gap).
 */
type AttemptDisplay = ExamAttemptResponse & {
  examTitle?: string | null;
  courseName?: string | null;
  passed?: boolean | null;
};

export function ExamsSection({ studentId, range, timeZone }: { studentId: string; range: DateRange; timeZone: string }) {
  const query = useStudentAttempts(studentId, { page: 0, size: 100 });
  return (
    <SectionCard title="Attended Exams" icon={<ClipboardList />} testId="profile-exams">
      {query.status === "pending" ? (
        <SectionLoading label="Loading attended exams…" />
      ) : query.status === "error" ? (
        <SectionError error={query.error} label="exams" onRetry={() => void query.refetch()} />
      ) : (
        (() => {
          const attended = (query.data.content as AttemptDisplay[]).filter(
            (attempt) => attempt.submittedAt && isWithinDateRange(attempt.submittedAt, range, timeZone)
          );
          if (attended.length === 0) {
            return <Empty>{rangeActive(range) ? "No exams attended in this date range." : "No exams attended yet."}</Empty>;
          }
          return (
            <ul className="flex flex-col divide-y divide-border">
              {attended.map((attempt) => (
                <li key={attempt.id} className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
                  <div className="min-w-0">
                    <p className="font-medium text-foreground">{attempt.examTitle ?? shortId(attempt.examId, "Exam")}</p>
                    {attempt.courseName ? <p className="text-xs text-muted-foreground">{attempt.courseName}</p> : null}
                    <p className="text-xs text-muted-foreground">{formatZonedDateTime(attempt.submittedAt, timeZone).slice(0, 10)}</p>
                  </div>
                  {attempt.passed === true ? (
                    <span className="rounded-full bg-green-100 px-2.5 py-0.5 text-xs font-semibold text-green-800 dark:bg-green-900 dark:text-green-100">
                      PASS
                    </span>
                  ) : attempt.passed === false ? (
                    <span className="rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-semibold text-red-800 dark:bg-red-900 dark:text-red-100">
                      FAIL
                    </span>
                  ) : (
                    <span className="text-xs text-muted-foreground">{attempt.status.replace(/_/g, " ")}</span>
                  )}
                </li>
              ))}
            </ul>
          );
        })()
      )}
    </SectionCard>
  );
}

export function EnrolledCoursesSection({
  detail,
  courseName,
}: {
  detail: StudentDetail;
  courseName: (courseId: string) => string;
}) {
  const [attendanceFor, setAttendanceFor] = useState<string | null>(null);
  return (
    <SectionCard title="Enrolled Courses" icon={<BookOpen />} testId="profile-courses">
      {detail.enrolledCourseIds.length === 0 ? (
        <Empty>Not enrolled in any course yet.</Empty>
      ) : (
        <ul className="flex flex-col divide-y divide-border">
          {detail.enrolledCourseIds.map((courseId) => (
            <li key={courseId} className="flex flex-wrap items-center justify-between gap-2 py-2 first:pt-0 last:pb-0">
              <span className="font-medium text-foreground">{courseName(courseId)}</span>
              <Button type="button" size="sm" variant="outline" onClick={() => setAttendanceFor(courseId)}>
                <CalendarCheck aria-hidden="true" />
                Show Attendance
              </Button>
            </li>
          ))}
        </ul>
      )}
      <StudentDialog
        open={attendanceFor !== null}
        onOpenChange={(open) => (!open ? setAttendanceFor(null) : undefined)}
        title={attendanceFor ? `Attendance — ${courseName(attendanceFor)}` : "Attendance"}
        icon={<CalendarCheck className="size-5 text-primary" aria-hidden="true" />}
        testId="course-attendance-dialog"
        className="sm:max-w-3xl"
        footer={
          <Button type="button" variant="outline" onClick={() => setAttendanceFor(null)}>
            Close
          </Button>
        }
      >
        {attendanceFor ? <AttendanceTab key={attendanceFor} studentId={detail.id} courseId={attendanceFor} /> : null}
      </StudentDialog>
    </SectionCard>
  );
}

export function PaymentsSection({ studentId, range, timeZone }: { studentId: string; range: DateRange; timeZone: string }) {
  const query = useStudentLedger(studentId);
  return (
    <SectionCard title="Payments" icon={<CreditCard />} testId="profile-payments">
      {query.status === "pending" ? (
        <SectionLoading label="Loading payments…" />
      ) : query.status === "error" ? (
        <SectionError error={query.error} label="payments" onRetry={() => void query.refetch()} />
      ) : (
        (() => {
          const entries = query.data.filter((entry) => isWithinDateRange(entry.createdAt, range, timeZone));
          if (entries.length === 0) {
            return (
              <Empty>
                {rangeActive(range) && query.data.length > 0
                  ? "No payments in this date range."
                  : "No ledger-confirmed payments on record yet."}
              </Empty>
            );
          }
          return (
            <ul className="flex flex-col divide-y divide-border">
              {entries.map((entry) => (
                <li key={entry.id} className="flex flex-wrap items-center justify-between gap-2 py-2 first:pt-0 last:pb-0">
                  <div className="min-w-0">
                    <p className="font-medium text-foreground">{entry.courseTitle ?? shortId(entry.orderId, "Order")}</p>
                    <p className="text-xs text-muted-foreground">
                      {formatZonedDateTime(entry.createdAt, timeZone)}
                      {entry.method ? ` · ${PAYMENT_METHOD_LABEL[entry.method] ?? entry.method}` : ""}
                      {entry.reference ? ` · Ref ${entry.reference}` : ""}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="font-semibold tabular-nums text-foreground">{formatMoney(entry.amount)}</span>
                    <LedgerEntryTypeBadge entryType={entry.entryType} />
                  </div>
                </li>
              ))}
            </ul>
          );
        })()
      )}
    </SectionCard>
  );
}

function minutes(seconds: number | null): string {
  if (seconds === null) return "—";
  return `${Math.round(seconds / 60)} min`;
}

export function SessionViewsSection({
  studentId,
  range,
  timeZone,
  canView,
}: {
  studentId: string;
  range: DateRange;
  timeZone: string;
  canView: boolean;
}) {
  return (
    <SectionCard title="Session Views" icon={<PlayCircle />} testId="profile-session-views">
      {canView ? (
        <SessionViewsBody studentId={studentId} range={range} timeZone={timeZone} />
      ) : (
        <Empty>Your role can&apos;t view this student&apos;s session views.</Empty>
      )}
    </SectionCard>
  );
}

function SessionViewsBody({ studentId, range, timeZone }: { studentId: string; range: DateRange; timeZone: string }) {
  const query = useStudentWatchTime(studentId);
  if (query.status === "pending") return <SectionLoading label="Loading session views…" />;
  if (query.status === "error") {
    return <SectionError error={query.error} label="session views" onRetry={() => void query.refetch()} />;
  }
  const watched = query.data.filter(
    (video) => video.lastWatchedAt && video.usedSeconds > 0 && isWithinDateRange(video.lastWatchedAt, range, timeZone)
  );
  if (watched.length === 0) {
    return <Empty>{rangeActive(range) ? "No session views in this date range." : "No session views yet."}</Empty>;
  }
  return (
    <ul className="flex flex-col divide-y divide-border">
      {watched.map((video) => (
        <li key={video.videoAssetId} className="flex flex-wrap items-center justify-between gap-2 py-2 first:pt-0 last:pb-0">
          <div className="min-w-0">
            <p className="font-medium text-foreground">{video.title ?? shortId(video.videoAssetId, "Video")}</p>
            <p className="text-xs text-muted-foreground">Last watched {formatZonedDateTime(video.lastWatchedAt, timeZone)}</p>
          </div>
          <span className="text-sm tabular-nums text-foreground">
            {minutes(video.usedSeconds)}
            {video.allowedSeconds !== null ? ` of ${minutes(video.allowedSeconds)}` : ""}
          </span>
        </li>
      ))}
    </ul>
  );
}
