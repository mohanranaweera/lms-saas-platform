"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { LoadingState } from "@/components/states/loading-state";
import { ErrorState } from "@/components/states/error-state";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { LiveRegion } from "@/components/ui/live-region";
import {
  useAttemptAnswers,
  useExam,
  useSaveAnswer,
  useStartAttempt,
  useSubmitAttempt,
  type ExamQuestionResponse,
} from "@/lib/api/exams";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import { rememberExamAttempt } from "@/lib/exam-attempt-storage";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * The binding submission deadline for an in-progress attempt: the per-attempt
 * time limit counted from `startedAt`, clamped against the exam window's own
 * `scheduledEnd` — a student who starts shortly before the window closes has
 * less than the full time limit available, never more than the window allows
 * (plan §11 accessibility requirement's countdown; window/limit precedence is
 * `ExamAccessCheckService`'s own server-side rule, mirrored here for display
 * only — this deadline is never sent back to the server or used to gate any
 * request; every attempt-related call is still re-validated server-side
 * against the current clock per `.claude/rules/security.md`).
 */
function computeAttemptDeadline(startedAt: string, timeLimitMinutes: number, scheduledEnd: string): number {
  const byTimeLimit = new Date(startedAt).getTime() + timeLimitMinutes * 60_000;
  const byWindow = new Date(scheduledEnd).getTime();
  return Math.min(byTimeLimit, byWindow);
}

/** Formats a millisecond duration as `M:SS` (clamped to zero), for the always-updating visible countdown. */
function formatRemaining(remainingMs: number): string {
  const totalSeconds = Math.max(0, Math.floor(remainingMs / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

const TEXTAREA_CLASSNAME =
  "w-full rounded-lg border border-input bg-transparent px-2.5 py-1.5 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30";

function QuestionField({
  question,
  value,
  onChange,
  disabled,
}: {
  question: ExamQuestionResponse;
  value: string;
  onChange: (value: string) => void;
  disabled: boolean;
}) {
  if (question.questionType === "MCQ") {
    return (
      <fieldset className="flex flex-col gap-2" disabled={disabled}>
        <legend className="text-sm font-medium text-foreground">{question.body}</legend>
        {question.options.map((option) => {
          const id = `question-${question.id}-option-${option.id}`;
          return (
            <div key={option.id} className="flex items-center gap-2">
              <input
                type="radio"
                id={id}
                name={`question-${question.id}`}
                value={option.id}
                checked={value === option.id}
                onChange={() => onChange(option.id)}
                className="size-4"
              />
              <label htmlFor={id} className="text-sm text-foreground">
                {option.optionText}
              </label>
            </div>
          );
        })}
      </fieldset>
    );
  }

  const textareaId = `question-${question.id}-response`;
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={textareaId}>{question.body}</Label>
      <textarea
        id={textareaId}
        rows={4}
        disabled={disabled}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className={TEXTAREA_CLASSNAME}
      />
    </div>
  );
}

/**
 * Student Exam Taking (MVP-017 plan §11 screen #2). Owner-only by
 * construction — `POST /{examId}/attempts` always starts/resumes the calling
 * student's own attempt, no id param.
 *
 * **Confirmed contract nuance (re-verified directly against
 * `ExamAttemptService.startAttempt`):** the three sub-states below are
 * `NOT_YET_OPEN`, `WINDOW_CLOSED`, and a genuine `409 CONFLICT` (the
 * concurrent-`IN_PROGRESS`-attempt race, mapped by `GlobalExceptionHandler`
 * from the DB's partial unique index) — NOT a "you already submitted, here
 * are your results" case. The backend explicitly allows unlimited
 * *sequential* re-attempts while the window remains open (attempt-count
 * limiting is Phase 2, plan §6/§21 item 3): revisiting this page after
 * already submitting, while the window is still open, silently starts a
 * brand-new attempt rather than erroring — there is no backend signal to
 * distinguish that case from a first attempt, so this page does not
 * fabricate one. The third state below is labeled honestly for the race it
 * actually represents, not mislabeled "Already submitted".
 *
 * **Fix 2 (getting back to Results & Review later):** on a successful start
 * or submit, this page best-effort persists `{ examId: attemptId }` via
 * `lib/exam-attempt-storage.ts` so the Results & Review screen can fall back
 * to it if the student ever reopens this exam without the `?attemptId=` link
 * this page normally appends on navigation. This is a frontend-only, same-
 * browser mitigation, not a real fix — see that module's doc comment for the
 * full (a)/(b)/(c) caveats (doesn't survive another device, cleared storage,
 * etc.); the real fix is a backend "list my attempts" endpoint, which does
 * not exist today and is out of scope here.
 */
export default function StudentExamTakePage() {
  const { examId } = useParams<{ examId: string }>();
  const router = useRouter();
  const { session } = useAuth();
  const userId = session?.userId ?? null;

  // --- Starting/resuming the attempt: a `useQuery`, not a `useMutation` ---
  //
  // `POST /{examId}/attempts` starts OR RESUMES the caller's single
  // `IN_PROGRESS` attempt (backed by a partial-unique-index guard), which
  // makes it idempotent from this page's perspective — the same shape
  // TanStack Query's own guidance uses to distinguish "safe to fire
  // automatically on mount" (`useQuery`) from a genuinely non-idempotent,
  // user-triggered action (`useMutation`, which the library explicitly
  // recommends never firing from a mount effect). This page originally fired
  // it as a `useMutation` from a mount `useEffect` guarded by a ref; a live
  // debug session against this exact page confirmed that pattern resolves
  // the network call correctly but never notifies the component under
  // React's default `reactStrictMode: true` in `next dev` (a StrictMode/
  // mutation-observer interaction specific to firing `mutate()`
  // synchronously on mount) — `useQuery`'s automatic-fetch path is the one
  // TanStack Query hardens for exactly this timing, so `useStartAttempt` is
  // built on it instead (see that hook's own doc comment in `lib/api/exams.ts`).
  const startQuery = useStartAttempt(examId);
  const examQuery = useExam(examId);
  const attempt = startQuery.data ?? null;
  const startError = startQuery.error;

  const saveAnswerMutation = useSaveAnswer(attempt?.id ?? "");
  const submitMutation = useSubmitAttempt(attempt?.id ?? "");

  // --- Fix 1: rehydrate previously saved answers on resume ---------------
  const answersQuery = useAttemptAnswers(attempt?.id ?? "", { enabled: Boolean(attempt) });

  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [savedQuestionIds, setSavedQuestionIds] = useState<Set<string>>(new Set());
  const [answerError, setAnswerError] = useState<{ questionId: string; message: string } | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitAnnouncement, setSubmitAnnouncement] = useState("");

  // `handleSaveAnswer` reads from this ref (not the `answers` state closure
  // directly) so it stays correct when invoked from a debounced autosave
  // timeout (Fix 5) scheduled by an earlier, now-stale render's closure.
  const answersRef = useRef<Record<string, string>>({});
  useEffect(() => {
    answersRef.current = answers;
  }, [answers]);

  // One-time seed of `answers`/`savedQuestionIds` from the server's
  // saved-answers snapshot. Deliberately NOT a `useEffect` — React's own
  // "adjusting state based on a prop/data change" escape hatch (see
  // https://react.dev/learn/you-might-not-need-an-effect#adjusting-state-based-on-a-prop-or-state-change)
  // calls `setState` directly in the render body, guarded by a condition
  // that only holds once, which avoids the extra deferred render pass (and
  // this project's `react-hooks/set-state-in-effect` lint rule) that an
  // effect-based version of this same one-time seed would trigger. Never
  // re-runs after the first successful fetch — once the student starts
  // editing locally, a later refetch must never clobber in-progress edits.
  const [hasSeededAnswers, setHasSeededAnswers] = useState(false);
  if (!hasSeededAnswers && answersQuery.status === "success") {
    setHasSeededAnswers(true);
    const savedAnswers = answersQuery.data ?? [];
    if (savedAnswers.length > 0) {
      setAnswers((current) => {
        const next = { ...current };
        for (const row of savedAnswers) {
          next[row.questionId] = row.response ?? "";
        }
        return next;
      });
      setSavedQuestionIds((current) => {
        const next = new Set(current);
        for (const row of savedAnswers) next.add(row.questionId);
        return next;
      });
    }
  }

  // --- Fix 5: debounced autosave (per-question, ~2s pause in typing) -----
  // Plain `number` (not `ReturnType<typeof window.setTimeout>`): this
  // project's TS config loads both `dom` and `@types/node` libs, and
  // `ReturnType` on an overloaded signature always resolves to the *last*
  // merged overload — which here would be Node's `NodeJS.Timeout`, not the
  // `number` `window.setTimeout` actually returns in a browser.
  const autosaveTimeoutsRef = useRef<Map<string, number>>(new Map());
  useEffect(() => {
    const timeouts = autosaveTimeoutsRef.current;
    return () => {
      timeouts.forEach((timeoutId) => window.clearTimeout(timeoutId));
      timeouts.clear();
    };
  }, []);

  // --- Fix 1: time-remaining countdown (plan §11 accessibility requirement) ---
  const [nowMs, setNowMs] = useState<number>(() => Date.now());
  const [timeAnnouncement, setTimeAnnouncement] = useState("");
  const lastAnnouncedMinuteRef = useRef<number | null>(null);

  useEffect(() => {
    const interval = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(interval);
  }, []);

  const deadlineMs = useMemo(() => {
    if (!attempt || !examQuery.data) return null;
    return computeAttemptDeadline(attempt.startedAt, examQuery.data.timeLimitMinutes, examQuery.data.scheduledEnd);
  }, [attempt, examQuery.data]);

  const remainingMs = deadlineMs !== null ? deadlineMs - nowMs : null;
  const isTimeExpired = remainingMs !== null && remainingMs <= 0;

  // Sparse aria-live announcement: every 5 minutes remaining, then every 1
  // minute in the final 5 (plan §11) — never every tick. Gated by a ref so a
  // new announcement string is only pushed once per checkpoint, not on every
  // second-by-second re-render.
  useEffect(() => {
    if (remainingMs === null) return;
    const remainingMinutes = Math.max(0, Math.ceil(remainingMs / 60_000));
    const isCheckpoint = remainingMinutes <= 5 || remainingMinutes % 5 === 0;
    if (isCheckpoint && lastAnnouncedMinuteRef.current !== remainingMinutes) {
      lastAnnouncedMinuteRef.current = remainingMinutes;
      setTimeAnnouncement(
        remainingMinutes <= 0
          ? "Time's up."
          : `Time remaining: about ${remainingMinutes} minute${remainingMinutes === 1 ? "" : "s"}.`
      );
    }
  }, [remainingMs]);

  useEffect(() => {
    if (attempt) {
      rememberExamAttempt(userId, examId, attempt.id);
    }
  }, [attempt, examId, userId]);

  // No effect needed to fire the attempt-start request: `useStartAttempt`
  // (a `useQuery`) fetches automatically once `enabled` (see its own doc
  // comment) — no mount-effect/ref-guard dance required.

  // No effect needed to seed `answers` from `examQuery.data`: every read of
  // `answers[question.id]` below already falls back to `""` for a
  // not-yet-touched question, so there's nothing to synchronize eagerly
  // (server-saved values are seeded separately, from `useAttemptAnswers`,
  // by the one-time render-body seed above).

  // Reads `answersRef.current` (not the `answers` state closure) so this
  // remains correct whether it's invoked from the manual "Save answer"
  // button (current render) or from a `scheduleAutosave` timeout callback
  // captured by an earlier, now-stale render.
  const handleSaveAnswer = async (questionId: string) => {
    if (!attempt) return;
    const timeouts = autosaveTimeoutsRef.current;
    const pendingTimeout = timeouts.get(questionId);
    if (pendingTimeout !== undefined) {
      window.clearTimeout(pendingTimeout);
      timeouts.delete(questionId);
    }
    setAnswerError(null);
    try {
      await saveAnswerMutation.mutateAsync({ questionId, response: answersRef.current[questionId] || null });
      setSavedQuestionIds((current) => new Set(current).add(questionId));
    } catch (error) {
      setAnswerError({
        questionId,
        message: isApiClientError(error) ? error.message : "Could not save this answer. Please try again.",
      });
    }
  };

  // Debounced autosave (Fix 5): reschedules on every keystroke, so only the
  // last edit within a ~2s pause actually fires a save. Reuses
  // `handleSaveAnswer` itself (not a duplicate mutation call) so autosave and
  // the manual button share identical success/error handling.
  const scheduleAutosave = (questionId: string) => {
    const timeouts = autosaveTimeoutsRef.current;
    const existingTimeout = timeouts.get(questionId);
    if (existingTimeout !== undefined) {
      window.clearTimeout(existingTimeout);
    }
    const timeoutId = window.setTimeout(() => {
      timeouts.delete(questionId);
      void handleSaveAnswer(questionId);
    }, 2000);
    timeouts.set(questionId, timeoutId);
  };

  // --- Fix 6: warn before an accidental tab close/navigation while there's
  // an unsaved edit. A question counts as "dirty" only if it has a non-empty
  // local value that hasn't made it into `savedQuestionIds` yet — never after
  // a successful submit, and never for a question that was never touched.
  useEffect(() => {
    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!attempt || submitMutation.isSuccess) return;
      const hasDirtyAnswer = Object.entries(answers).some(
        ([questionId, value]) => value !== "" && !savedQuestionIds.has(questionId)
      );
      if (!hasDirtyAnswer) return;
      event.preventDefault();
      // Older browsers require `returnValue` to be set to trigger the prompt.
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [attempt, answers, savedQuestionIds, submitMutation.isSuccess]);

  const handleSubmit = async () => {
    if (!attempt) return;
    setSubmitError(null);
    setSubmitAnnouncement("Submitting your exam…");
    try {
      await submitMutation.mutateAsync();
      setSubmitAnnouncement("Exam submitted successfully.");
      rememberExamAttempt(userId, examId, attempt.id);
      router.push(`/student/exams/${examId}/results?attemptId=${attempt.id}`);
    } catch (error) {
      if (isApiClientError(error) && error.status === 409) {
        // Already submitted (e.g. a double-click race) — not a fatal error,
        // treat as success and route to the results screen (plan §13).
        setSubmitAnnouncement("This exam was already submitted.");
        rememberExamAttempt(userId, examId, attempt.id);
        router.push(`/student/exams/${examId}/results?attemptId=${attempt.id}`);
        return;
      }
      // `requireAttemptableWindow` re-verifies the exam window on every
      // submit call (confirmed directly against `ExamAttemptService.submit`),
      // so a submission attempted right as/after the window closes can
      // genuinely 403 `WINDOW_CLOSED` — a real, distinguishable case, not the
      // generic fallback below. `NOT_YET_OPEN` is the same guard's other
      // branch but is not reachable here (an attempt can't exist before the
      // window opened), so it's deliberately not fabricated as a submit-time
      // state.
      if (isApiClientError(error) && error.code === "WINDOW_CLOSED") {
        const message =
          "This exam's window closed before your submission went through, so it was not recorded as submitted. Contact your teacher if you believe this is a mistake.";
        setSubmitError(message);
        setSubmitAnnouncement(`Submission failed: ${message}`);
        return;
      }
      const message = isApiClientError(error) ? error.message : "Could not submit your exam. Please try again.";
      setSubmitError(message);
      setSubmitAnnouncement(`Submission failed: ${message}`);
    }
  };

  // --- Sub-state 1/3 & 2/3: NOT_YET_OPEN / WINDOW_CLOSED -----------------
  if (startError) {
    const error = startError;
    if (isApiClientError(error) && error.code === "NOT_YET_OPEN") {
      return (
        <EmptyState
          title="This exam is not yet open"
          description={`${error.message}. Check the Exams list for its scheduled start time, then come back once the window opens.`}
          action={{ label: "Back to Exams", onClick: () => router.push("/student/exams") }}
        />
      );
    }
    if (isApiClientError(error) && error.code === "WINDOW_CLOSED") {
      return (
        <EmptyState
          title="This exam's window has closed"
          description="You can no longer start or continue this attempt. If results have been published, you'll find them from the Exams list once this exam shows as Closed."
          action={{ label: "Back to Exams", onClick: () => router.push("/student/exams") }}
        />
      );
    }
    // --- Sub-state 3/3: a genuine 409 concurrent-attempt race ------------
    if (isApiClientError(error) && error.status === 409) {
      return (
        <EmptyState
          title="This exam attempt is already in progress"
          description="It looks like this exam is already open in another tab or session. Close any other open attempt, then reload this page."
          action={{ label: "Reload", onClick: () => window.location.reload() }}
        />
      );
    }
    return (
      <ErrorState
        message={isApiClientError(error) ? error.message : "Could not start this exam. Please try again."}
        onRetry={() => startQuery.refetch()}
      />
    );
  }

  if (!attempt) {
    return <LoadingState label="Starting your attempt…" />;
  }

  // Dedicated not-found state for a cross-tenant exam id (backend returns a
  // plain 404 — plan §14) — never the generic retryable ErrorState, which
  // would misleadingly offer "Try again" for a permanently nonexistent
  // resource.
  if (examQuery.status === "error" && isApiClientError(examQuery.error) && examQuery.error.status === 404) {
    return (
      <EmptyState
        title="Exam not found"
        description="This exam doesn't exist, or it isn't available to you."
        action={{ label: "Back to Exams", onClick: () => router.push("/student/exams") }}
      />
    );
  }

  // Fix 1: avoid a flash of blank-then-filled question fields on resume —
  // hold `LoadingState` a beat longer while the saved-answers seed is still
  // in flight. An `error` here (e.g. a transient network failure) still
  // falls through to the normal render below rather than blocking
  // indefinitely; there's simply nothing to seed in that case.
  if (answersQuery.status === "pending") {
    return <LoadingState label="Loading your saved answers…" />;
  }

  return (
    <div className="flex flex-col gap-6">
      <LiveRegion message={submitAnnouncement} assertive={Boolean(submitError)} />
      {/* Sparse, checkpoint-gated announcement (every 5 min, then every 1 min in
          the final 5) — deliberately a SEPARATE live region from the always-
          updating visible countdown below, so `aria-live` never fires every tick. */}
      <LiveRegion message={timeAnnouncement} />

      <QueryStateBoundary
        query={examQuery}
        loadingLabel="Loading exam questions…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
      >
        {(examData) => (
          <div className="flex flex-col gap-6" aria-busy={submitMutation.isPending}>
            <div>
              <h1 className="text-xl font-semibold text-foreground">{examData.title}</h1>
              <p className="text-sm text-muted-foreground">
                Time limit: {examData.timeLimitMinutes} minutes · Window closes{" "}
                {formatDateTime(examData.scheduledEnd)}
              </p>
            </div>

            {remainingMs !== null ? (
              <div className="flex flex-col gap-1 rounded-lg border border-border p-3 sm:flex-row sm:items-center sm:justify-between">
                <p className="text-sm font-medium text-foreground">
                  Time remaining: <span className="tabular-nums">{formatRemaining(remainingMs)}</span>
                </p>
                {isTimeExpired ? (
                  <p role="alert" className="text-sm font-medium text-destructive">
                    Time&apos;s up — submit now.
                  </p>
                ) : null}
              </div>
            ) : null}

            <ul className="flex flex-col gap-4">
              {examData.questions.map((question, index) => (
                <li key={question.id} className="flex flex-col gap-2 rounded-lg border border-border p-4">
                  <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                    Question {index + 1}
                  </span>
                  <QuestionField
                    question={question}
                    value={answers[question.id] ?? ""}
                    onChange={(value) => {
                      setAnswers((current) => ({ ...current, [question.id]: value }));
                      setSavedQuestionIds((current) => {
                        if (!current.has(question.id)) return current;
                        const next = new Set(current);
                        next.delete(question.id);
                        return next;
                      });
                      scheduleAutosave(question.id);
                    }}
                    disabled={submitMutation.isPending}
                  />
                  <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:gap-3">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="w-full sm:w-fit"
                      disabled={saveAnswerMutation.isPending || submitMutation.isPending}
                      onClick={() => handleSaveAnswer(question.id)}
                    >
                      {saveAnswerMutation.isPending ? "Saving…" : "Save answer"}
                    </Button>
                    {savedQuestionIds.has(question.id) ? (
                      <span className="text-xs text-muted-foreground">Saved</span>
                    ) : null}
                  </div>
                  {answerError?.questionId === question.id ? (
                    <p role="alert" className="text-xs text-destructive">
                      {answerError.message}
                    </p>
                  ) : null}
                </li>
              ))}
            </ul>

            {submitError ? (
              <p role="alert" className="text-sm text-destructive">
                {submitError}
              </p>
            ) : null}

            <Button
              type="button"
              onClick={handleSubmit}
              disabled={submitMutation.isPending}
              aria-busy={submitMutation.isPending}
              className="w-full sm:w-fit"
            >
              {submitMutation.isPending ? "Submitting…" : "Submit exam"}
            </Button>
          </div>
        )}
      </QueryStateBoundary>

      <p className="text-xs text-muted-foreground">
        Having trouble?{" "}
        <Link href="/student/exams" className="font-medium text-foreground hover:underline">
          Back to Exams
        </Link>
      </p>
    </div>
  );
}
