"use client";

import { useMemo } from "react";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AnswerResultChip } from "@/components/exams/answer-result-chip";
import { useExamResults, useMyAttempts } from "@/lib/api/exams";
import { getRememberedExamAttempt } from "@/lib/exam-attempt-storage";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "@/lib/api/error";

/**
 * Student Results & Review (MVP-017 plan §11 screen #3). Needs the
 * `attemptId`, not just `examId` — `GET /api/v1/exams/attempts/{attemptId}/results`
 * has no course/exam-scoped variant. Resolved, in order: (1) the `?attemptId=`
 * query param the Exam Taking screen (#2) appends when it navigates here
 * right after a successful (or already-submitted) submit; (2)
 * `useMyAttempts` (`GET /exams/attempts/my`, added post-review) searched for
 * an attempt matching this `examId` — the real, backend-authoritative fallback
 * that works from any device/session; (3) `lib/exam-attempt-storage.ts`'s
 * `localStorage` convenience, kept only as a same-browser fast path that
 * resolves before the attempts list finishes loading. If none resolve, that
 * is a real, honestly surfaced gap, shown as its own distinct state.
 */
export default function StudentExamResultsPage() {
  const { examId } = useParams<{ examId: string }>();
  const router = useRouter();
  const searchParams = useSearchParams();
  const queryAttemptId = searchParams.get("attemptId");
  const { session } = useAuth();
  const userId = session?.userId ?? null;

  const rememberedAttemptId = getRememberedExamAttempt(userId, examId);
  // Only queried when the URL doesn't already carry the answer — the normal
  // "just submitted" navigation path never needs this extra request.
  const myAttemptsQuery = useMyAttempts({ size: 100 }, { enabled: !queryAttemptId });
  const matchingAttempt = useMemo(
    () => myAttemptsQuery.data?.content.find((attempt) => attempt.examId === examId),
    [myAttemptsQuery.data, examId]
  );

  const attemptId = useMemo(
    () => queryAttemptId ?? matchingAttempt?.id ?? rememberedAttemptId ?? "",
    [queryAttemptId, matchingAttempt, rememberedAttemptId]
  );

  const resultsQuery = useExamResults(attemptId);

  if (!attemptId) {
    if (myAttemptsQuery.status === "pending") {
      return <EmptyState title="Looking for your attempt…" description="Checking your attempt history." />;
    }
    return (
      <EmptyState
        title="We don't know which attempt to show"
        description="Open this exam from your Exams list right after submitting, or use the link shown immediately after you submit. We also checked your attempt history and this browser's local storage and found no matching attempt."
      />
    );
  }

  // Dedicated not-found state for a cross-tenant/cross-student attempt id
  // (backend returns a plain 404, never 403 — plan §15) — never the generic
  // retryable ErrorState, which would misleadingly offer "Try again" for a
  // permanently nonexistent/not-yours resource.
  if (resultsQuery.status === "error" && isApiClientError(resultsQuery.error) && resultsQuery.error.status === 404) {
    return (
      <EmptyState
        title="Attempt not found"
        description="This attempt doesn't exist, or it isn't yours."
        action={{ label: "Back to Exams", onClick: () => router.push("/student/exams") }}
      />
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Results &amp; Review</h1>
      </div>

      <QueryStateBoundary
        query={resultsQuery}
        loadingLabel="Loading your results…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
      >
        {(data) =>
          data.published && data.result ? (
            <div className="flex flex-col gap-4">
              <div className="rounded-lg border border-border p-4">
                <p className="text-sm font-medium text-foreground">
                  Score: {Number(data.result.score)} / {Number(data.result.maxScore)}
                </p>
              </div>
              <ul className="flex flex-col gap-3">
                {data.result.answers.map((answer, index) => (
                  <li key={answer.questionId} className="flex flex-col gap-2 rounded-lg border border-border p-4">
                    <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
                      <span className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                        Question {index + 1}
                      </span>
                      <AnswerResultChip autoScore={answer.autoScore} manualScore={answer.manualScore} />
                    </div>
                    <p className="whitespace-pre-wrap text-sm text-foreground">
                      {answer.response ?? <span className="italic text-muted-foreground">No answer submitted.</span>}
                    </p>
                  </li>
                ))}
              </ul>
            </div>
          ) : (
            <EmptyState
              title="Your exam has been submitted — results will be published soon"
              description="Your teacher hasn't published results for this exam yet. Check back later, or watch for a notification once they're available."
            />
          )
        }
      </QueryStateBoundary>

      <p className="text-xs text-muted-foreground">
        <Link href="/student/exams" className="font-medium text-foreground hover:underline">
          Back to Exams
        </Link>
      </p>
    </div>
  );
}
