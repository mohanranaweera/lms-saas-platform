"use client";

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import {
  AlertTriangle,
  Check,
  ChevronLeft,
  ChevronRight,
  CloudOff,
  Loader2,
  Rows3,
  Send,
  Square,
  SquareStack,
  Timer,
} from "lucide-react";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { LiveRegion } from "@/components/ui/live-region";
import { EmptyState } from "@/components/states/empty-state";
import { LoadingState } from "@/components/states/loading-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AttemptQuestion } from "@/components/exams/student/attempt-question";
import { studentExamPaths } from "@/components/exams/student/student-exam-shared";
import { formatExamDateTime } from "@/lib/exams/exam-time";
import { formatCountdown, useServerCountdown } from "@/lib/exams/server-clock";
import { AttemptAutosaveQueue, type ItemSaveState } from "@/lib/exams/attempt-autosave";
import { isApiClientError } from "@/lib/api/error";
import {
  isAttemptClosedError,
  studentExamKeys,
  useAttempt,
  useSaveAttemptItem,
  useStudentExam,
  useSubmitAttempt,
  type Attempt,
  type StudentExam,
} from "@/lib/api/student-exams";
import { cn } from "@/lib/utils";

type FinishReason = "submitted" | "timeout" | "closed";

/** Client-side replace (effect) for states that belong on another page. */
function Redirect({ href, label }: { href: string; label: string }) {
  const router = useRouter();
  useEffect(() => {
    router.replace(href);
  }, [href, router]);
  return <LoadingState label={label} />;
}

function SaveIndicator({ states }: { states: Record<string, ItemSaveState> }) {
  const values = Object.values(states);
  const retrying = values.some((state) => state.status === "retrying");
  const saving = values.some((state) => state.status === "saving");
  const failed = values.filter((state) => state.status === "error");
  const anySaved = values.some((state) => state.status === "saved");
  let content: { icon: ReactNode; text: string; tone: string; state: string } | null = null;
  if (retrying) {
    content = {
      icon: <CloudOff className="size-4" aria-hidden="true" />,
      text: "Offline — retrying",
      tone: "text-amber-700 dark:text-amber-300",
      state: "retrying",
    };
  } else if (saving) {
    content = {
      icon: <Loader2 className="size-4 animate-spin motion-reduce:animate-none" aria-hidden="true" />,
      text: "Saving…",
      tone: "text-muted-foreground",
      state: "saving",
    };
  } else if (failed.length > 0) {
    content = {
      icon: <AlertTriangle className="size-4" aria-hidden="true" />,
      text: failed[0]?.message ? `Not saved — ${failed[0].message}` : "Not saved",
      tone: "text-destructive",
      state: "error",
    };
  } else if (anySaved) {
    content = {
      icon: <Check className="size-4" aria-hidden="true" />,
      text: "Saved",
      tone: "text-green-700 dark:text-green-400",
      state: "saved",
    };
  }
  return (
    <p
      role="status"
      aria-live="polite"
      className={cn("flex min-h-5 min-w-0 items-center gap-1 text-xs font-medium sm:text-sm", content?.tone)}
      data-testid="attempt-save-status"
      data-state={content?.state ?? "idle"}
    >
      {content ? (
        <>
          {content.icon}
          <span className="truncate">{content.text}</span>
        </>
      ) : (
        <span className="text-muted-foreground">Answers save automatically</span>
      )}
    </p>
  );
}

function CountdownTimer({ remainingMs }: { remainingMs: number | null }) {
  const low = remainingMs !== null && remainingMs <= 5 * 60_000;
  return (
    <div
      role="timer"
      aria-label="Time remaining"
      aria-live="off"
      data-testid="attempt-timer"
      className={cn(
        "flex shrink-0 items-center gap-1.5 rounded-lg px-2.5 py-1 font-mono text-base font-semibold tabular-nums sm:text-lg",
        low ? "bg-destructive/10 text-destructive" : "bg-muted text-foreground"
      )}
    >
      <Timer className="size-4" aria-hidden="true" />
      <span>{remainingMs === null ? "--:--" : formatCountdown(remainingMs)}</span>
      {low ? <span className="sr-only">(less than 5 minutes left)</span> : null}
    </div>
  );
}

function QuestionNavigator({
  attempt,
  answers,
  current,
  view,
  onGo,
}: {
  attempt: Attempt;
  answers: Record<string, string | null>;
  current: number;
  view: "one" | "all";
  onGo: (index: number) => void;
}) {
  const answered = attempt.items.filter((item) => answers[item.id]).length;
  return (
    <nav aria-label="Question navigator" className="flex flex-col gap-3 rounded-xl bg-card p-3 ring-1 ring-foreground/10 sm:p-4">
      <p className="text-sm font-medium text-foreground" data-testid="attempt-answered-count">
        {answered} of {attempt.items.length} answered
      </p>
      <ol className="grid grid-cols-[repeat(auto-fill,minmax(2.5rem,1fr))] gap-2">
        {attempt.items.map((item, index) => {
          const isAnswered = Boolean(answers[item.id]);
          const isCurrent = view === "one" && index === current;
          return (
            <li key={item.id}>
              <button
                type="button"
                onClick={() => onGo(index)}
                aria-current={isCurrent ? "step" : undefined}
                aria-label={`Question ${index + 1}, ${isAnswered ? "answered" : "not answered"}`}
                data-testid="attempt-nav-item"
                data-answered={isAnswered}
                className={cn(
                  "relative flex h-10 w-full items-center justify-center rounded-lg border text-sm font-semibold tabular-nums outline-none transition-colors focus-visible:ring-3 focus-visible:ring-ring/50",
                  isAnswered
                    ? "border-primary bg-primary text-primary-foreground hover:bg-primary/85"
                    : "border-dashed border-border bg-background text-foreground hover:bg-muted",
                  isCurrent && "ring-2 ring-foreground ring-offset-2 ring-offset-background"
                )}
              >
                {index + 1}
                {isAnswered ? <Check className="absolute top-0.5 right-0.5 size-3" aria-hidden="true" /> : null}
              </button>
            </li>
          );
        })}
      </ol>
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground" aria-hidden="true">
        <span className="flex items-center gap-1">
          <span className="inline-block size-3 rounded-sm border border-primary bg-primary" /> Answered
        </span>
        <span className="flex items-center gap-1">
          <span className="inline-block size-3 rounded-sm border border-dashed border-border" /> Not answered
        </span>
      </div>
    </nav>
  );
}

/**
 * The live attempt. Local answer state is seeded once from the server's
 * `AttemptResponse` (persisted order, saved selections). The countdown runs
 * on the server clock (offset from `serverNow`); at zero the page stops
 * saving and moves to the result page — the server finalizes the attempt at
 * the deadline on its own.
 */
function AttemptSession({ exam, attempt, receivedAtMs }: { exam: StudentExam; attempt: Attempt; receivedAtMs: number }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const items = attempt.items;
  const [answers, setAnswers] = useState<Record<string, string | null>>(() =>
    Object.fromEntries(items.map((item) => [item.id, item.selectedOptionId ?? null]))
  );
  const [saveStates, setSaveStates] = useState<Record<string, ItemSaveState>>({});
  const [view, setView] = useState<"one" | "all">("one");
  const [current, setCurrent] = useState(0);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [finishing, setFinishing] = useState<FinishReason | null>(null);
  const finishedRef = useRef(false);

  const { remainingMs, resync } = useServerCountdown(attempt.deadlineAt, attempt.serverNow, receivedAtMs);
  const timeUp = remainingMs !== null && remainingMs <= 0;
  const saver = useSaveAttemptItem(attempt.id);
  const submit = useSubmitAttempt(attempt.id, exam.id);

  const [queue] = useState(
    () =>
      new AttemptAutosaveQueue(
        attempt.id,
        items.map((item) => item.revision ?? 0),
        {
          saver,
          onState: (itemId, state) => setSaveStates((prev) => ({ ...prev, [itemId]: state })),
          onClosed: () => undefined,
          onServerNow: () => undefined,
          canRetry: () => true,
        }
      )
  );

  const finish = useCallback(
    (reason: FinishReason) => {
      if (finishedRef.current) return;
      finishedRef.current = true;
      queue.stop();
      setFinishing(reason);
      // Mark stale without refetching here: a refetch on this page would redirect without the `finished` reason.
      void queryClient.invalidateQueries({ queryKey: studentExamKeys.detail(exam.id), refetchType: "none" });
      void queryClient.invalidateQueries({ queryKey: studentExamKeys.list(), refetchType: "none" });
      router.replace(studentExamPaths.result(exam.id, reason));
    },
    [exam.id, queryClient, queue, router]
  );

  // Keep the queue's callbacks current (deadline check, clock resync, closed handling).
  useEffect(() => {
    queue.update({
      saver,
      onClosed: () => finish("closed"),
      onServerNow: resync,
      canRetry: () => remainingMs === null || remainingMs > 0,
    });
  }, [queue, saver, finish, resync, remainingMs]);

  useEffect(() => {
    queue.resume();
    return () => queue.stop();
  }, [queue]);

  useEffect(() => {
    const onOnline = () => queue.retryNow();
    window.addEventListener("online", onOnline);
    return () => window.removeEventListener("online", onOnline);
  }, [queue]);

  // Deadline reached on the server clock: stop and hand over to the result page.
  useEffect(() => {
    if (timeUp) finish("timeout");
  }, [timeUp, finish]);

  const hasUnsaved = Object.values(saveStates).some(
    (state) => state.status === "saving" || state.status === "retrying" || state.status === "error"
  );
  useEffect(() => {
    if (!hasUnsaved || finishing) return;
    const handler = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [hasUnsaved, finishing]);

  const locked = timeUp || finishing !== null || submit.isPending;
  const unanswered = items.filter((item) => !answers[item.id]).length;
  const notSaved = Object.values(saveStates).filter((state) => state.status === "retrying" || state.status === "error").length;

  function select(itemId: string, optionId: string | null) {
    if (locked) return;
    setAnswers((prev) => ({ ...prev, [itemId]: optionId }));
    queue.save(itemId, optionId);
  }

  function goTo(index: number) {
    const bounded = Math.max(0, Math.min(items.length - 1, index));
    setCurrent(bounded);
    const item = items[bounded];
    if (!item) return;
    // Move focus to the question heading so keyboard and screen-reader users land on it.
    window.requestAnimationFrame(() => {
      const heading = document.getElementById(`attempt-q-${item.id}-heading`);
      if (view === "all") document.getElementById(`attempt-q-${item.id}`)?.scrollIntoView({ block: "start" });
      heading?.focus({ preventScroll: view === "all" });
    });
  }

  async function confirmSubmit() {
    setSubmitError(null);
    await queue.flush(5_000);
    try {
      await submit.mutateAsync();
      setConfirmOpen(false);
      finish("submitted");
    } catch (error) {
      if (isAttemptClosedError(error)) {
        setConfirmOpen(false);
        finish("closed");
        return;
      }
      setSubmitError(
        isApiClientError(error) && error.status !== 0
          ? error.message
          : "We couldn't reach the server to submit. Check your connection and try again — your saved answers are kept."
      );
    }
  }

  const announcement =
    remainingMs === null
      ? ""
      : remainingMs <= 0
        ? "Time is up. Your answers were submitted."
        : remainingMs <= 60_000
          ? "Less than 1 minute remaining."
          : remainingMs <= 5 * 60_000
            ? "Less than 5 minutes remaining."
            : "";

  if (timeUp) {
    return (
      <div role="alert" className="flex flex-col items-center gap-2 rounded-xl bg-card px-6 py-12 text-center ring-1 ring-foreground/10" data-testid="attempt-time-up">
        <Timer className="size-8 text-primary" aria-hidden="true" />
        <p className="text-lg font-semibold text-foreground">Time is up — your answers were submitted</p>
        <p className="text-sm text-muted-foreground">Opening your submission…</p>
      </div>
    );
  }

  const shown = view === "one" ? items.slice(current, current + 1) : items;

  return (
    <div className="flex min-w-0 flex-col gap-4" data-testid="attempt-session" aria-busy={submit.isPending}>
      <LiveRegion message={announcement} />
      <header className="sticky top-0 z-20 -mx-1 flex flex-col gap-2 rounded-xl border border-border bg-background/95 px-3 py-2 shadow-sm backdrop-blur supports-backdrop-filter:bg-background/80 sm:flex-row sm:items-center sm:gap-4 sm:px-4">
        <div className="flex min-w-0 flex-1 items-center justify-between gap-3">
          <div className="min-w-0">
            <h1 className="truncate text-base font-semibold text-foreground sm:text-lg" data-testid="attempt-exam-name">
              {exam.name}
            </h1>
            <SaveIndicator states={saveStates} />
          </div>
          <CountdownTimer remainingMs={remainingMs} />
        </div>
        <Button
          type="button"
          onClick={() => {
            setSubmitError(null);
            setConfirmOpen(true);
          }}
          disabled={locked}
          data-testid="attempt-submit-open"
          className="w-full sm:w-auto"
        >
          <Send aria-hidden="true" />
          Submit
        </Button>
      </header>

      <p className="text-xs text-muted-foreground">
        Deadline <time dateTime={attempt.deadlineAt}>{formatExamDateTime(attempt.deadlineAt)}</time>
. Answers are submitted automatically when time runs out.
      </p>

      <div className="grid min-w-0 gap-4 lg:grid-cols-[16rem_minmax(0,1fr)] lg:items-start">
        <div className="flex min-w-0 flex-col gap-3 lg:sticky lg:top-28">
          <QuestionNavigator attempt={attempt} answers={answers} current={current} view={view} onGo={goTo} />
          <div role="group" aria-label="Question layout" className="grid grid-cols-2 gap-1 rounded-lg bg-muted p-1">
            <button
              type="button"
              aria-pressed={view === "one"}
              onClick={() => setView("one")}
              data-testid="attempt-view-one"
              className={cn(
                "flex items-center justify-center gap-1.5 rounded-md px-2 py-1.5 text-xs font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
                view === "one" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
              )}
            >
              <Square className="size-3.5" aria-hidden="true" />
              One at a time
            </button>
            <button
              type="button"
              aria-pressed={view === "all"}
              onClick={() => setView("all")}
              data-testid="attempt-view-all"
              className={cn(
                "flex items-center justify-center gap-1.5 rounded-md px-2 py-1.5 text-xs font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
                view === "all" ? "bg-background text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground"
              )}
            >
              {view === "all" ? <Rows3 className="size-3.5" aria-hidden="true" /> : <SquareStack className="size-3.5" aria-hidden="true" />}
              All questions
            </button>
          </div>
        </div>

        <div className="flex min-w-0 flex-col gap-4">
          {shown.map((item) => {
            const index = items.indexOf(item);
            return (
              <AttemptQuestion
                key={item.id}
                item={item}
                number={index + 1}
                total={items.length}
                selectedOptionId={answers[item.id] ?? null}
                disabled={locked}
                onSelect={(optionId) => select(item.id, optionId)}
              />
            );
          })}
          {view === "one" ? (
            <div className="flex items-center justify-between gap-2">
              <Button type="button" variant="outline" onClick={() => goTo(current - 1)} disabled={current === 0} data-testid="attempt-prev">
                <ChevronLeft aria-hidden="true" />
                Previous
              </Button>
              {current < items.length - 1 ? (
                <Button type="button" variant="outline" onClick={() => goTo(current + 1)} data-testid="attempt-next">
                  Next
                  <ChevronRight aria-hidden="true" />
                </Button>
              ) : (
                <Button type="button" onClick={() => setConfirmOpen(true)} disabled={locked} data-testid="attempt-finish">
                  <Send aria-hidden="true" />
                  Finish
                </Button>
              )}
            </div>
          ) : (
            <div>
              <Button type="button" onClick={() => setConfirmOpen(true)} disabled={locked} data-testid="attempt-finish">
                <Send aria-hidden="true" />
                Submit exam
              </Button>
            </div>
          )}
        </div>
      </div>

      <AlertDialog open={confirmOpen} onOpenChange={(open) => !submit.isPending && setConfirmOpen(open)}>
        <AlertDialogContent data-testid="attempt-submit-dialog" className="max-w-md">
          <AlertDialogHeader>
            <AlertDialogTitle>Submit your exam?</AlertDialogTitle>
            <AlertDialogDescription data-testid="attempt-submit-summary">
              {unanswered === 0
                ? `You have answered all ${items.length} questions.`
                : `You have ${unanswered} unanswered ${unanswered === 1 ? "question" : "questions"} out of ${items.length}. Unanswered questions score 0.`}{" "}
              You can&apos;t change your answers after submitting.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {notSaved > 0 ? (
            <Alert variant="destructive" role="status">
              <AlertDescription>
                {notSaved === 1 ? "1 answer hasn't" : `${notSaved} answers haven't`} reached the server yet. If you submit
                now {notSaved === 1 ? "it" : "they"} may not count.
              </AlertDescription>
            </Alert>
          ) : null}
          {submitError ? (
            <Alert variant="destructive" data-testid="attempt-submit-error">
              <AlertDescription>{submitError}</AlertDescription>
            </Alert>
          ) : null}
          <AlertDialogFooter>
            <AlertDialogClose render={<Button type="button" variant="outline" disabled={submit.isPending} />}>
              Keep working
            </AlertDialogClose>
            <Button
              type="button"
              onClick={() => void confirmSubmit()}
              disabled={submit.isPending}
              aria-busy={submit.isPending}
              data-testid="attempt-submit-confirm"
            >
              {submit.isPending ? "Submitting…" : "Submit exam"}
            </Button>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}

function AttemptLoader({ exam, attemptId }: { exam: StudentExam; attemptId: string }) {
  const query = useAttempt(attemptId);
  // Mount the session once, from a settled (fresh) fetch; later refetches never reset local answers.
  const [mounted, setMounted] = useState(false);
  if (!mounted && query.data && !query.isFetching) setMounted(true);

  // Only before the session mounts: afterwards the session itself routes to the result with the reason.
  if (!mounted && query.data && !query.isFetching && query.data.status !== "IN_PROGRESS") {
    return <Redirect href={studentExamPaths.result(exam.id, "closed")} label="Opening your submission…" />;
  }
  if (query.status === "error") {
    if (isAttemptClosedError(query.error)) {
      return <Redirect href={studentExamPaths.result(exam.id, "closed")} label="Opening your submission…" />;
    }
    return (
      <QueryStateBoundary query={query} loginPath="/login" permissionDenied={{ dashboardHref: studentExamPaths.list }}>
        {() => null}
      </QueryStateBoundary>
    );
  }
  if (!mounted || !query.data) return <LoadingState label="Loading your answers…" />;
  return <AttemptSession key={attemptId} exam={exam} attempt={query.data} receivedAtMs={query.dataUpdatedAt} />;
}

/** Take page (Slice C): finds the attempt via `GET /student/exams/{id}`, then resumes it with `GET /student/attempts/{id}`. */
export function StudentExamTake({ examId }: { examId: string }) {
  const query = useStudentExam(examId);
  const router = useRouter();
  const notFound = query.status === "error" && isApiClientError(query.error) && query.error.status === 404;
  if (notFound) {
    return (
      <EmptyState
        title="Exam not found"
        description="This exam doesn't exist, or you aren't enrolled in it."
        action={{ label: "Back to my exams", onClick: () => router.push(studentExamPaths.list) }}
      />
    );
  }

  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading your exam…"
      loginPath="/login"
      permissionDenied={{ dashboardHref: "/student/dashboard" }}
      genericErrorMessage="We couldn't load this exam."
    >
      {(exam) => {
        if (exam.type !== "MCQ") {
          return <Redirect href={studentExamPaths.details(exam.id)} label="Opening exam details…" />;
        }
        if (!exam.attempt) {
          return (
            <EmptyState
              title="You haven't started this exam"
              description="Start the exam from its details page. You'll need the exam password from your teacher."
              action={{ label: "Go to exam details", onClick: () => router.push(studentExamPaths.details(exam.id)) }}
            />
          );
        }
        if (exam.attempt.status !== "IN_PROGRESS") {
          return <Redirect href={studentExamPaths.result(exam.id)} label="Opening your submission…" />;
        }
        return <AttemptLoader exam={exam} attemptId={exam.attempt.id} />;
      }}
    </QueryStateBoundary>
  );
}
