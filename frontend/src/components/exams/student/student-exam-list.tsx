"use client";

import { useState } from "react";
import Link from "next/link";
import { CalendarDays, ClipboardList, History, ListTodo, Zap } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs } from "@/components/ui/tabs";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EXAM_TYPE_LABELS, ExamLiveStatusChip, ExamPageTitle, ExamTypeIcon, formatExamMarks } from "@/components/exams/config/exam-shared";
import {
  AttemptStatusChip,
  questionCountText,
  studentExamCta,
} from "@/components/exams/student/student-exam-shared";
import { formatDuration, formatExamDateTime, relativeToServerNow } from "@/lib/exams/exam-time";
import { useStudentExams, type StudentExam, type StudentExamList } from "@/lib/api/student-exams";
import { cn } from "@/lib/utils";

type ListTab = "in-progress" | "upcoming" | "previous";

const TABS: {
  value: ListTab;
  label: string;
  icon: typeof Zap;
  key: keyof Pick<StudentExamList, "inProgress" | "upcoming" | "previous">;
  empty: { title: string; description: string };
}[] = [
  {
    value: "in-progress",
    label: "In progress",
    icon: Zap,
    key: "inProgress",
    empty: {
      title: "No exams are open right now",
      description: "Exams you're enrolled in appear here while they're open. Check Upcoming for scheduled exams.",
    },
  },
  {
    value: "upcoming",
    label: "Upcoming",
    icon: ListTodo,
    key: "upcoming",
    empty: {
      title: "No upcoming exams",
      description: "When you're enrolled in an exam that hasn't opened yet, it will be listed here with its start time.",
    },
  },
  {
    value: "previous",
    label: "Previous",
    icon: History,
    key: "previous",
    empty: {
      title: "No previous exams",
      description: "Exams that have closed appear here, with your result once it's available.",
    },
  },
];

function ListSkeleton() {
  return (
    <div role="status" aria-busy="true" className="flex flex-col gap-4" data-testid="student-exams-loading">
      <span className="sr-only">Loading your exams…</span>
      <div className="flex gap-3">
        <Skeleton className="h-9 w-32" />
        <Skeleton className="h-9 w-32" />
        <Skeleton className="h-9 w-32" />
      </div>
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {Array.from({ length: 3 }, (_, index) => (
          <div key={index} className="flex flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10">
            <Skeleton className="h-6 w-32" />
            <Skeleton className="h-5 w-3/4" />
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-8 w-28" />
          </div>
        ))}
      </div>
    </div>
  );
}

/** One enrolled exam: type, status, schedule (relative to the server clock), size, attempt status and the next step. */
export function StudentExamCard({ exam, serverNow }: { exam: StudentExam; serverNow: string }) {
  const headingId = `student-exam-${exam.id}`;
  const cta = studentExamCta(exam);
  const duration = formatDuration(exam.durationMinutes);
  const size = exam.type === "MCQ" ? questionCountText(exam.questionCount) : formatExamMarks(exam.totalMarks);
  const anchor = exam.status === "UPCOMING" ? exam.startAt : exam.endAt;
  const anchorLabel = exam.status === "UPCOMING" ? "Opens" : exam.status === "FINISHED" ? "Closed" : "Closes";
  return (
    <article
      aria-labelledby={headingId}
      data-testid="student-exam-card"
      data-exam-id={exam.id}
      className="flex w-full min-w-0 flex-col gap-3 rounded-xl bg-card p-4 text-card-foreground shadow-sm ring-1 ring-foreground/10"
    >
      <div className="flex flex-wrap items-center gap-2">
        <ExamTypeIcon type={exam.type} />
        <span className="text-sm font-semibold tracking-wide text-muted-foreground uppercase">{EXAM_TYPE_LABELS[exam.type]}</span>
        <ExamLiveStatusChip status={exam.status} />
      </div>
      <div className="flex min-w-0 flex-col gap-0.5">
        <h3 id={headingId} className="text-base font-semibold break-words text-foreground">
          {exam.name}
        </h3>
        <p className="text-sm break-words text-muted-foreground">{exam.courseName}</p>
      </div>
      <p className="flex items-start gap-1.5 text-sm text-foreground">
        <CalendarDays className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
        <span>
          {anchorLabel} <time dateTime={anchor}>{formatExamDateTime(anchor)}</time>
          <span className="text-muted-foreground"> · {relativeToServerNow(anchor, serverNow)}</span>
        </span>
      </p>
      {duration || size ? (
        <p className="text-sm text-muted-foreground">{[duration, size].filter(Boolean).join(" · ")}</p>
      ) : null}
      <div className="mt-auto flex flex-wrap items-center justify-between gap-2 pt-1">
        {exam.type === "MCQ" ? <AttemptStatusChip exam={exam} /> : <span />}
        {cta.kind === "link" ? (
          <Link
            href={cta.href}
            className={cn(buttonVariants({ variant: cta.testId === "exam-cta-details" ? "outline" : "default" }))}
            data-testid={cta.testId}
            aria-label={`${cta.label}: ${exam.name}`}
          >
            {cta.label}
          </Link>
        ) : (
          <span className="text-sm text-muted-foreground" data-testid={cta.testId}>
            {cta.text}
          </span>
        )}
      </div>
    </article>
  );
}

/**
 * Student "My Exams" (Klass Exam Module, Slice C): the exams the student is
 * exam-enrolled in, grouped by the server into In progress / Upcoming /
 * Previous using its own clock (`serverNow`). Legacy MVP-017 exams are no
 * longer listed here.
 */
export function StudentExamList() {
  const query = useStudentExams();
  const [tab, setTab] = useState<ListTab>("in-progress");

  return (
    <div className="flex min-w-0 flex-col gap-6">
      <ExamPageTitle title="My exams" icon={<ClipboardList className="size-5" aria-hidden="true" />} />
      {query.status === "pending" ? (
        <ListSkeleton />
      ) : (
        <QueryStateBoundary
          query={query}
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/student/dashboard" }}
          genericErrorMessage="We couldn't load your exams."
          isEmpty={(data) => data.inProgress.length + data.upcoming.length + data.previous.length === 0}
          emptyState={{
            title: "You aren't enrolled in any exams yet",
            description:
              "Your teacher enrolls you in exams, or you're added automatically when you pay for the course period an exam covers. Enrolled exams will appear here.",
          }}
        >
          {(data) => (
            <Tabs
              aria-label="Exam groups"
              value={tab}
              onValueChange={(next) => setTab(next as ListTab)}
              idPrefix="student-exams-"
              items={TABS.map((entry) => {
                const exams = data[entry.key];
                const Icon = entry.icon;
                return {
                  value: entry.value,
                  label: entry.label,
                  testId: `student-exams-tab-${entry.value}`,
                  icon: <Icon className="hidden size-4 sm:block" aria-hidden="true" />,
                  badge: (
                    <span
                      className="ml-1 rounded-full bg-muted px-1.5 text-xs text-muted-foreground tabular-nums"
                      data-testid={`student-exams-count-${entry.value}`}
                    >
                      {exams.length}
                    </span>
                  ),
                  content:
                    exams.length === 0 ? (
                      <EmptyState title={entry.empty.title} description={entry.empty.description} />
                    ) : (
                      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3" data-testid={`student-exams-panel-${entry.value}`}>
                        {exams.map((exam) => (
                          <StudentExamCard key={exam.id} exam={exam} serverNow={data.serverNow} />
                        ))}
                      </div>
                    ),
                };
              })}
            />
          )}
        </QueryStateBoundary>
      )}
    </div>
  );
}
