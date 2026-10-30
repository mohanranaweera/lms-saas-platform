"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { ArrowLeft, Info, KeyRound, Play } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { LiveRegion } from "@/components/ui/live-region";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { FormField, describedBy, fieldIds } from "@/components/courses/admin/form-field";
import { StudentExamSummary, studentExamPaths } from "@/components/exams/student/student-exam-shared";
import { formatDuration, formatExamDateTime } from "@/lib/exams/exam-time";
import { isApiClientError } from "@/lib/api/error";
import {
  startAttemptErrorMessage,
  useStartAttempt,
  useStudentExam,
  type StudentExam,
} from "@/lib/api/student-exams";
import { cn } from "@/lib/utils";

function startSchema(requiresPassword: boolean) {
  return z.object({
    password: requiresPassword
      ? z.string().trim().min(1, "Enter the exam password your teacher gave you.").max(64, "The password is at most 64 characters.")
      : z.string(),
  });
}

type StartValues = { password: string };

/** Rules summary shown before starting (D2). Display only; the server enforces every rule. */
function ExamRules({ exam }: { exam: StudentExam }) {
  const duration = formatDuration(exam.durationMinutes);
  return (
    <section aria-labelledby="exam-rules-title" className="flex flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5">
      <h2 id="exam-rules-title" className="flex items-center gap-2 text-base font-semibold text-foreground">
        <Info className="size-4 text-primary" aria-hidden="true" />
        Before you start
      </h2>
      <ul className="flex list-disc flex-col gap-1.5 pl-5 text-sm text-foreground" data-testid="exam-rules">
        <li>
          {duration ? (
            <>
              You have <strong>{duration}</strong> from the moment you start, but never past the exam&apos;s closing
              time ({formatExamDateTime(exam.endAt)}). Your deadline is whichever comes first.
            </>
          ) : (
            <>You can work until the exam closes at {formatExamDateTime(exam.endAt)}.</>
          )}
        </li>
        <li>You can attempt this exam <strong>once</strong>. Leaving the page doesn&apos;t pause the timer.</li>
        <li>Your answers are saved automatically each time you choose an option.</li>
        <li>When the time runs out, your saved answers are submitted automatically.</li>
        <li>Your score is shown after the exam closes. Unanswered questions score 0; there are no negative marks.</li>
      </ul>
    </section>
  );
}

function StartForm({ exam }: { exam: StudentExam }) {
  const router = useRouter();
  const start = useStartAttempt(exam.id);
  const schema = useMemo(() => startSchema(exam.requiresPassword), [exam.requiresPassword]);
  const form = useForm<StartValues>({ resolver: zodResolver(schema), defaultValues: { password: "" } });
  const [stateError, setStateError] = useState<{ message: string; alreadyAttempted: boolean } | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const ids = fieldIds("exam-start", "password");
  const passwordError = form.formState.errors.password?.message ?? null;

  const onSubmit = form.handleSubmit(async (values) => {
    setStateError(null);
    setAnnouncement("Starting your exam…");
    try {
      await start.mutateAsync(exam.requiresPassword ? values.password.trim() : null);
      setAnnouncement("Exam started.");
      router.push(studentExamPaths.take(exam.id));
    } catch (error) {
      const mapped = startAttemptErrorMessage(error);
      setAnnouncement("");
      if (mapped.kind === "password") {
        form.setError("password", { type: "server", message: mapped.message }, { shouldFocus: true });
        return;
      }
      setStateError({
        message: mapped.message,
        alreadyAttempted: isApiClientError(error) && error.code === "ALREADY_ATTEMPTED",
      });
    }
  });

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      aria-labelledby="exam-start-title"
      className="flex flex-col gap-4 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5"
      data-testid="exam-start-form"
    >
      <LiveRegion message={announcement} />
      <h2 id="exam-start-title" className="flex items-center gap-2 text-base font-semibold text-foreground">
        <Play className="size-4 text-primary" aria-hidden="true" />
        Start the exam
      </h2>
      {exam.requiresPassword ? (
        <FormField
          ids={ids}
          label="Exam password"
          required
          helper="Your teacher gives you this password when the exam begins."
          error={passwordError}
          className="max-w-sm"
        >
          <div className="relative">
            <KeyRound className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <Input
              id={ids.inputId}
              type="password"
              autoComplete="off"
              className="pl-8"
              aria-invalid={passwordError ? true : undefined}
              aria-describedby={describedBy(ids, true, Boolean(passwordError))}
              {...form.register("password")}
            />
          </div>
        </FormField>
      ) : null}
      {stateError ? (
        <Alert variant="destructive" data-testid="exam-start-error">
          <AlertDescription>
            {stateError.message}{" "}
            {stateError.alreadyAttempted ? (
              <Link href={studentExamPaths.result(exam.id)} className="font-medium underline">
                See your result
              </Link>
            ) : null}
          </AlertDescription>
        </Alert>
      ) : null}
      <div>
        <Button type="submit" size="lg" disabled={start.isPending} aria-busy={start.isPending} data-testid="exam-start-button">
          <Play aria-hidden="true" />
          {start.isPending ? "Starting…" : "Start exam"}
        </Button>
      </div>
    </form>
  );
}

/** What the student can do next, from server facts only. */
function StartAction({ exam }: { exam: StudentExam }) {
  if (exam.type !== "MCQ") {
    return (
      <EmptyState
        title={exam.type === "PHYSICAL" ? "This exam is held in class" : "Online submission isn't available yet"}
        description={
          exam.type === "PHYSICAL"
            ? "Attend the exam at the scheduled time. Your teacher records your attendance and marks."
            : "Written exam papers and answer-sheet uploads will be available here in a later update. Follow your teacher's instructions for this exam."
        }
      />
    );
  }
  const attempt = exam.attempt;
  if (attempt && attempt.status === "IN_PROGRESS" && exam.status === "IN_PROGRESS") {
    return (
      <div className="flex flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5">
        <p className="text-sm text-foreground">
          You have already started this exam. Your deadline is{" "}
          <strong>
            <time dateTime={attempt.deadlineAt}>{formatExamDateTime(attempt.deadlineAt)}</time>
          </strong>
          .
        </p>
        <div>
          <Link href={studentExamPaths.take(exam.id)} className={buttonVariants({ size: "lg" })} data-testid="exam-resume-link">
            <Play aria-hidden="true" />
            Resume exam
          </Link>
        </div>
      </div>
    );
  }
  if (attempt) {
    return (
      <div className="flex flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5">
        <p className="text-sm text-foreground">Your answers were submitted.</p>
        <div>
          <Link href={studentExamPaths.result(exam.id)} className={buttonVariants({ variant: "outline" })} data-testid="exam-result-link">
            {exam.resultAvailable ? "View result" : "View submission status"}
          </Link>
        </div>
      </div>
    );
  }
  if (exam.status === "UPCOMING" || exam.status === "DRAFT") {
    return (
      <EmptyState
        title="This exam hasn't opened yet"
        description={`You can start it from ${formatExamDateTime(exam.startAt)}. Come back then with the exam password.`}
      />
    );
  }
  if (exam.status === "FINISHED") {
    return (
      <EmptyState
        title="This exam has closed"
        description="The exam window ended before you started, so it can no longer be attempted."
      />
    );
  }
  return <StartForm exam={exam} />;
}

/** Exam details + start (Slice C): rules summary, password, Start. */
export function StudentExamStart({ examId }: { examId: string }) {
  const query = useStudentExam(examId);
  const router = useRouter();
  const notFound = query.status === "error" && isApiClientError(query.error) && query.error.status === 404;
  return (
    <div className="flex min-w-0 flex-col gap-6">
      <Breadcrumbs items={[{ label: "My exams", href: studentExamPaths.list }, { label: query.data?.name ?? "Exam" }]} />
      {notFound ? (
        <EmptyState
          title="Exam not found"
          description="This exam doesn't exist, or you aren't enrolled in it. Ask your teacher if you expected to see it."
          action={{ label: "Back to my exams", onClick: () => router.push(studentExamPaths.list) }}
        />
      ) : (
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading exam…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/student/dashboard" }}
          genericErrorMessage="We couldn't load this exam."
        >
          {(exam) => (
            <div className="flex flex-col gap-6">
              <StudentExamSummary exam={exam} />
              {exam.type === "MCQ" && !exam.attempt && exam.status !== "FINISHED" ? <ExamRules exam={exam} /> : null}
              <StartAction exam={exam} />
            </div>
          )}
        </QueryStateBoundary>
      )}
      <div>
        <Link href={studentExamPaths.list} className={cn(buttonVariants({ variant: "ghost" }))}>
          <ArrowLeft aria-hidden="true" />
          Back to my exams
        </Link>
      </div>
    </div>
  );
}
