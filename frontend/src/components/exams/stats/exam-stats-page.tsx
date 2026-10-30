"use client";

import Link from "next/link";
import { useState, type ReactNode } from "react";
import {
  Activity,
  ArrowDownCircle,
  ArrowLeft,
  ArrowUpCircle,
  BadgeCheck,
  CheckCircle2,
  ChartColumn,
  ChevronDown,
  CircleAlert,
  GraduationCap,
  ListOrdered,
  Users,
} from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EXAM_TYPE_LABELS, ExamLiveStatusChip, ExamPageTitle, examPaths, type ExamPortal } from "@/components/exams/config/exam-shared";
import { RichContent } from "@/components/exams/rich-content";
import { QuestionDistributionChart } from "@/components/exams/stats/question-distribution-chart";
import { useExamDetail, type ExamDetail } from "@/lib/api/exam-config";
import { finiteOrNull, useExamStats, type ExamStats, type ExamStatsQuestion } from "@/lib/api/exam-results";
import { cn } from "@/lib/utils";

const DASH = "—";

/** Marks as text: up to 2 decimals, "—" for no value. Never NaN/undefined. */
export function statMarks(value: number | string | null | undefined): string {
  const n = finiteOrNull(value);
  if (n === null) return DASH;
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(2)));
}

function percent(value: number | string | null | undefined): string {
  const n = finiteOrNull(value);
  if (n === null) return DASH;
  return `${Number.isInteger(n) ? n : Number(n.toFixed(1))}%`;
}

function count(n: number, singular: string, plural = `${singular}s`): string {
  return `${n} ${n === 1 ? singular : plural}`;
}

/** Plain-text excerpt of sanitized question HTML (rendered as escaped text). */
function excerpt(html: string): string {
  const text = html
    .replace(/<(span|div)\b[^>]*data-latex="([^"]*)"[^>]*>[\s\S]*?<\/\1>/g, " $2 ")
    .replace(/<[^>]*>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/\s+/g, " ")
    .trim();
  return text || "Question with an image or formula";
}

function StatTile({
  icon,
  label,
  value,
  caption,
  testId,
}: {
  icon: ReactNode;
  label: string;
  value: string;
  caption: string;
  testId: string;
}) {
  return (
    <div className="flex min-w-0 flex-col items-center gap-1 rounded-lg px-3 py-4 text-center" data-testid={testId}>
      {icon}
      <dt className="text-sm font-medium text-foreground">{label}</dt>
      <dd className="text-xl font-semibold break-words text-foreground tabular-nums" data-testid={`${testId}-value`}>
        {value}
      </dd>
      <dd className="text-xs text-muted-foreground" data-testid={`${testId}-caption`}>
        {caption}
      </dd>
    </div>
  );
}

function SummaryTiles({ exam, stats }: { exam: ExamDetail; stats: ExamStats }) {
  const { attended, graded, enrolled } = stats.population;
  const total = statMarks(stats.totalMarks ?? exam.totalMarks);
  const missingReason = attended === 0 ? "No one has attended yet" : graded === 0 ? "No marks entered yet" : null;
  const marksCaption = (value: number | string | null) =>
    finiteOrNull(value) === null ? (missingReason ?? "Not available yet") : total === DASH ? "Marks" : `Out of ${total} marks`;

  const cutoff = finiteOrNull(stats.cutoffMarks);
  const passedOf = stats.passedOf ?? attended;
  let passedValue: string;
  let passedCaption: string;
  if (cutoff === null) {
    passedValue = "No cut-off set";
    passedCaption = "Set a cut-off mark on the exam to count passes";
  } else if (stats.passed === null || stats.passed === undefined) {
    passedValue = DASH;
    passedCaption = missingReason ?? "Not available yet";
  } else {
    passedValue = `${stats.passed} of ${count(passedOf, "student")}`;
    passedCaption = `Cut-off: ${statMarks(cutoff)} marks · out of attended students`;
  }

  return (
    <section aria-labelledby="stats-summary-title" className="flex min-w-0 flex-col gap-3 rounded-xl bg-card p-4 shadow-sm ring-1 ring-foreground/10 sm:p-5" data-testid="stats-summary">
      <div className="flex min-w-0 flex-wrap items-center gap-2">
        <GraduationCap className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
        <h2 id="stats-summary-title" className="min-w-0 font-semibold break-words text-foreground" data-testid="stats-exam-name">
          {exam.name}
        </h2>
        <span className="text-sm text-muted-foreground">
          {EXAM_TYPE_LABELS[exam.type]} · {exam.courseName}
        </span>
        <ExamLiveStatusChip status={exam.status} />
      </div>
      <dl className="grid grid-cols-2 gap-1 rounded-lg border border-border bg-muted/20 lg:grid-cols-4">
        <StatTile
          icon={<Activity className="size-6 text-sky-600 dark:text-sky-400" aria-hidden="true" />}
          label="Average Marks"
          value={statMarks(stats.averageMarks)}
          caption={marksCaption(stats.averageMarks)}
          testId="stat-average"
        />
        <StatTile
          icon={<ArrowUpCircle className="size-6 text-green-700 dark:text-green-400" aria-hidden="true" />}
          label="Maximum Marks"
          value={statMarks(stats.maxMarks)}
          caption={marksCaption(stats.maxMarks)}
          testId="stat-max"
        />
        <StatTile
          icon={<ArrowDownCircle className="size-6 text-red-600 dark:text-red-400" aria-hidden="true" />}
          label="Minimum Marks"
          value={statMarks(stats.minMarks)}
          caption={marksCaption(stats.minMarks)}
          testId="stat-min"
        />
        <StatTile
          icon={<BadgeCheck className="size-6 text-green-700 dark:text-green-400" aria-hidden="true" />}
          label="Passed"
          value={passedValue}
          caption={passedCaption}
          testId="stat-passed"
        />
      </dl>
      <p className="flex items-start gap-1.5 text-sm text-muted-foreground" data-testid="stats-population">
        <Users className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        <span>
          Based on {graded} graded of {attended} attended ({enrolled} enrolled). Average, maximum and minimum count graded
          students only.
        </span>
      </p>
      {attended > graded ? (
        <p className="flex items-start gap-1.5 text-sm text-muted-foreground" data-testid="stats-awaiting">
          <CircleAlert className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
          <span>{count(attended - graded, "attended student")} still {attended - graded === 1 ? "needs" : "need"} marks.</span>
        </p>
      ) : null}
    </section>
  );
}

function questionTotal(question: ExamStatsQuestion): number {
  return question.correct + question.incorrect + question.unanswered;
}

function Card({ title, titleId, icon, adornment, children, testId }: { title: string; titleId: string; icon: ReactNode; adornment?: ReactNode; children: ReactNode; testId: string }) {
  return (
    <section aria-labelledby={titleId} className="flex min-w-0 flex-col gap-4 rounded-xl bg-card p-4 shadow-sm ring-1 ring-foreground/10 [contain:inline-size] sm:p-5" data-testid={testId}>
      <div className="flex flex-wrap items-center gap-2 border-b border-border pb-3">
        {icon}
        <h2 id={titleId} className="text-lg font-semibold text-foreground">
          {title}
        </h2>
        {adornment}
      </div>
      {children}
    </section>
  );
}

function FrequentlyMissed({
  questions,
  sequences,
  onOpen,
}: {
  questions: ExamStatsQuestion[];
  sequences: number[];
  onOpen: (sequence: number) => void;
}) {
  const items = sequences
    .map((sequence) => questions.find((question) => question.sequence === sequence))
    .filter((question): question is ExamStatsQuestion => Boolean(question) && question!.incorrect + question!.unanswered > 0);
  return (
    <Card
      title="Frequently Missed Questions"
      titleId="stats-missed-title"
      icon={<CircleAlert className="size-5 text-red-600 dark:text-red-400" aria-hidden="true" />}
      testId="stats-missed"
    >
      {items.length === 0 ? (
        <p className="text-sm text-muted-foreground" data-testid="stats-missed-empty">
          No question was missed — every response so far was correct.
        </p>
      ) : (
        <ol className="flex flex-col gap-2">
          {items.map((question) => {
            const missed = question.incorrect + question.unanswered;
            const responses = questionTotal(question);
            return (
              <li key={question.sequence} className="flex min-w-0 flex-col gap-1 rounded-lg border border-border p-3 sm:flex-row sm:items-center sm:gap-3" data-testid="stats-missed-item">
                <button
                  type="button"
                  onClick={() => onOpen(question.sequence)}
                  className="shrink-0 self-start rounded font-semibold text-primary underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
                >
                  Question {question.sequence}
                </button>
                <span className="min-w-0 flex-1 truncate text-sm text-muted-foreground">{excerpt(question.bodyHtml)}</span>
                <span className="shrink-0 text-sm text-foreground tabular-nums">
                  Missed by {missed} of {responses} ({question.incorrect} incorrect, {question.unanswered} unanswered)
                </span>
              </li>
            );
          })}
        </ol>
      )}
    </Card>
  );
}

const LETTERS = "ABCDEFGHIJ";

function QuestionItem({ question, open, onToggle }: { question: ExamStatsQuestion; open: boolean; onToggle: () => void }) {
  const [optionsOpen, setOptionsOpen] = useState(false);
  const panelId = `stats-question-panel-${question.sequence}`;
  const optionsId = `stats-question-options-${question.sequence}`;
  return (
    <li id={`stats-question-${question.sequence}`} className="min-w-0 rounded-lg border border-border" data-testid="stats-question" data-sequence={question.sequence}>
      <h3>
        <button
          type="button"
          id={`stats-question-toggle-${question.sequence}`}
          aria-expanded={open}
          aria-controls={panelId}
          onClick={onToggle}
          className="flex w-full min-w-0 items-center gap-3 rounded-lg px-3 py-2.5 text-left outline-none hover:bg-muted/40 focus-visible:ring-3 focus-visible:ring-ring/50"
          data-testid="stats-question-toggle"
        >
          <span className="shrink-0 text-sm font-semibold text-foreground">Question {question.sequence}</span>
          <span className="hidden min-w-0 flex-1 truncate text-sm text-muted-foreground sm:block">{excerpt(question.bodyHtml)}</span>
          <span className="ml-auto shrink-0 rounded-full bg-green-600/10 px-2 py-0.5 text-xs font-medium text-green-800 tabular-nums dark:text-green-300" data-testid="stats-question-percent">
            {percent(question.correctPercent)} correct
          </span>
          <ChevronDown className={cn("size-4 shrink-0 transition-transform motion-reduce:transition-none", open && "rotate-180")} aria-hidden="true" />
        </button>
      </h3>
      <div id={panelId} hidden={!open} className="flex min-w-0 flex-col gap-3 border-t border-border px-3 py-3">
        <RichContent html={question.bodyHtml} className="text-sm text-foreground" emptyText="No question text." />
        <p className="text-sm text-muted-foreground tabular-nums" data-testid="stats-question-counts">
          {question.correct} correct · {question.incorrect} incorrect · {question.unanswered} unanswered
          {finiteOrNull(question.marks) !== null ? ` · ${statMarks(question.marks)} ${finiteOrNull(question.marks) === 1 ? "mark" : "marks"}` : ""}
        </p>
        <div>
          <button
            type="button"
            aria-expanded={optionsOpen}
            aria-controls={optionsId}
            onClick={() => setOptionsOpen((value) => !value)}
            className="inline-flex items-center gap-1 rounded text-sm font-medium text-primary underline-offset-4 outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
            data-testid="stats-view-options"
          >
            <ChevronDown className={cn("size-4 transition-transform motion-reduce:transition-none", optionsOpen && "rotate-180")} aria-hidden="true" />
            {optionsOpen ? "Hide answer options" : "View answer options"}
          </button>
        </div>
        <ol id={optionsId} hidden={!optionsOpen} className="flex flex-col gap-2" aria-label={`Answer options for question ${question.sequence}`}>
          {question.options.map((option, index) => (
            <li
              key={option.id}
              className={cn(
                "flex min-w-0 items-start gap-3 rounded-md border px-3 py-2",
                option.correct ? "border-green-600/50 bg-green-600/5" : "border-border"
              )}
              data-testid="stats-option"
              data-correct={option.correct ? "true" : "false"}
            >
              <span className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold" aria-hidden="true">
                {LETTERS[index] ?? index + 1}
              </span>
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <span className="sr-only">Option {LETTERS[index] ?? index + 1}:</span>
                <RichContent html={option.html} className="text-sm text-foreground" />
                {option.correct ? (
                  <span className="inline-flex items-center gap-1 text-xs font-semibold text-green-800 dark:text-green-300" data-testid="stats-option-correct">
                    <CheckCircle2 className="size-3.5" aria-hidden="true" />
                    Correct answer
                  </span>
                ) : null}
              </div>
              <span className="shrink-0 text-sm text-muted-foreground tabular-nums" data-testid="stats-option-count">
                {count(option.selectedCount, "selection")}
              </span>
            </li>
          ))}
        </ol>
      </div>
    </li>
  );
}

function McqSections({ stats }: { stats: ExamStats }) {
  const questions = [...(stats.questions ?? [])].sort((a, b) => a.sequence - b.sequence);
  const [open, setOpen] = useState<ReadonlySet<number>>(new Set());

  function toggle(sequence: number) {
    setOpen((current) => {
      const next = new Set(current);
      if (next.has(sequence)) next.delete(sequence);
      else next.add(sequence);
      return next;
    });
  }

  function openQuestion(sequence: number) {
    setOpen((current) => new Set(current).add(sequence));
    requestAnimationFrame(() => {
      document.getElementById(`stats-question-${sequence}`)?.scrollIntoView({ block: "start", behavior: "smooth" });
      document.getElementById(`stats-question-toggle-${sequence}`)?.focus({ preventScroll: true });
    });
  }

  return (
    <>
      <Card
        title="Question Distribution"
        titleId="stats-distribution-title"
        icon={<ChartColumn className="size-5 text-primary" aria-hidden="true" />}
        testId="stats-distribution"
      >
        <QuestionDistributionChart data={questions} />
      </Card>
      <FrequentlyMissed questions={questions} sequences={stats.frequentlyMissed ?? []} onOpen={openQuestion} />
      <section aria-labelledby="stats-breakdown-title" className="flex min-w-0 flex-col gap-3" data-testid="stats-breakdown">
        <div className="flex flex-wrap items-center gap-3">
          <ListOrdered className="size-5 text-primary" aria-hidden="true" />
          <h2 id="stats-breakdown-title" className="text-xl font-semibold text-foreground">
            Question-Wise Breakdown
          </h2>
          <span className="rounded-full bg-primary/10 px-2.5 py-0.5 text-xs font-medium text-primary" data-testid="stats-breakdown-count">
            {count(questions.length, "Question")}
          </span>
        </div>
        {questions.length === 0 ? (
          <EmptyState title="No questions on this paper" description="This exam has no drawn questions, so there is nothing to break down." />
        ) : (
          <ol className="flex flex-col gap-2">
            {questions.map((question) => (
              <QuestionItem key={question.sequence} question={question} open={open.has(question.sequence)} onToggle={() => toggle(question.sequence)} />
            ))}
          </ol>
        )}
      </section>
    </>
  );
}

function StatsBody({ exam, portal }: { exam: ExamDetail; portal: ExamPortal }) {
  const query = useExamStats(exam.id);
  if (query.status === "pending") {
    return (
      <div role="status" aria-busy="true" className="flex flex-col gap-4" data-testid="stats-loading">
        <span className="sr-only">Loading statistics…</span>
        <Skeleton className="h-48 w-full rounded-xl" />
        <Skeleton className="h-72 w-full rounded-xl" />
      </div>
    );
  }
  return (
    <QueryStateBoundary query={query} loginPath="/login" permissionDenied={{ dashboardHref: examPaths(portal).directory }} genericErrorMessage="We couldn't load the statistics.">
      {(stats) => {
        const type = stats.type ?? exam.type;
        const nobody = stats.population.attended === 0;
        return (
          <div className="flex min-w-0 flex-col gap-5">
            <SummaryTiles exam={exam} stats={stats} />
            {nobody ? (
              <EmptyState
                title="No one has attended this exam yet"
                description={
                  exam.status === "FINISHED"
                    ? `None of the ${count(stats.population.enrolled, "enrolled student")} sat this exam, so there are no statistics to show.`
                    : "Statistics appear here once students sit the exam and marks are recorded."
                }
                icon={<ChartColumn className="size-8" aria-hidden="true" />}
              />
            ) : type === "MCQ" ? (
              <McqSections stats={stats} />
            ) : (
              <p className="rounded-xl bg-card p-4 text-sm text-muted-foreground ring-1 ring-foreground/10" data-testid="stats-type-note">
                {type === "WRITTEN" ? "Written" : "In-class"} exams are marked as a whole, so there is no question-by-question
                breakdown. The figures above use the marks entered on the Manage page.
              </p>
            )}
          </div>
        );
      }}
    </QueryStateBoundary>
  );
}

/** Klass "Exam Stats" (Slice E): summary tiles, MCQ question distribution, frequently missed questions and the breakdown. */
export function ExamStatsPage({ portal, examId }: { portal: ExamPortal; examId: string }) {
  const paths = examPaths(portal);
  const detail = useExamDetail(examId);
  return (
    <div className="flex min-w-0 flex-col gap-5">
      <div className="flex flex-col gap-2">
        <ExamPageTitle
          title="Exam Stats"
          icon={<ChartColumn className="size-5" aria-hidden="true" />}
          actions={
            <Link href={paths.manage(examId)} className={buttonVariants({ variant: "outline" })} data-testid="exam-manage-link">
              <ArrowLeft aria-hidden="true" />
              Manage exam
            </Link>
          }
        />
        <Breadcrumbs items={[{ label: "Exams", href: paths.directory }, { label: detail.data?.name ?? "Exam" }]} />
      </div>
      {detail.status === "pending" ? (
        <div role="status" aria-busy="true" className="flex flex-col gap-3">
          <span className="sr-only">Loading exam…</span>
          <Skeleton className="h-48 w-full rounded-xl" />
        </div>
      ) : (
        <QueryStateBoundary query={detail} loginPath="/login" permissionDenied={{ dashboardHref: paths.directory }} genericErrorMessage="We couldn't load this exam.">
          {(exam) => <StatsBody exam={exam} portal={portal} />}
        </QueryStateBoundary>
      )}
    </div>
  );
}
