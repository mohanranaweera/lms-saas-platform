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
import { StudentPhysicalExam, StudentWrittenExam } from "@/components/exams/student/student-written-exam";
import { formatDuration, formatExamDateTime } from "@/lib/exams/exam-time";
import { isApiClientError } from "@/lib/api/error";
import {
  startAttemptErrorMessage,
  useStartAttempt,
  useStudentExam,
  type StudentExam,
} from "@/lib/api/student-exams";
import { cn } from "@/lib/utils";

import { useT, type TranslateFn } from "@/i18n/i18n-provider";
function startSchema(requiresPassword: boolean, t: TranslateFn) {
  return z.object({
    password: requiresPassword
      ? z.string().trim().min(1, t("exams.password.required")).max(64, t("exams.password.tooLong", { max: "64" }))
      : z.string(),
  });
}

type StartValues = { password: string };

/** Rules summary shown before starting (D2). Display only; the server enforces every rule. */
function ExamRules({ exam }: { exam: StudentExam }) {
  const t = useT();
  const duration = formatDuration(exam.durationMinutes, t);
  return (
    <section aria-labelledby="exam-rules-title" className="flex flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5">
      <h2 id="exam-rules-title" className="flex items-center gap-2 text-base font-semibold text-foreground">
        <Info className="size-4 text-primary" aria-hidden="true" />
        {t("exams.studentExamStart.beforeYouStart")}
      </h2>
      <ul className="flex list-disc flex-col gap-1.5 pl-5 text-sm text-foreground" data-testid="exam-rules">
        <li>
          {duration ? (
            <>{t("exams.rules.duration", { duration, closes: formatExamDateTime(exam.endAt) })}</>
          ) : (
            <>{t("exams.rules.untilClose", { closes: formatExamDateTime(exam.endAt) })}</>
          )}
        </li>
        <li>{t("exams.rules.once")}</li>
        <li>{t("exams.studentExamStart.yourAnswersAreSaved")}</li>
        <li>{t("exams.studentExamStart.whenTheTimeRuns")}</li>
        <li>{t("exams.rules.score")}</li>
      </ul>
    </section>
  );
}

function StartForm({ exam }: { exam: StudentExam }) {
  const t = useT();
  const router = useRouter();
  const start = useStartAttempt(exam.id);
  const schema = useMemo(() => startSchema(exam.requiresPassword, t), [exam.requiresPassword, t]);
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
        {t("exams.studentExamStart.startTheExam")}
      </h2>
      {exam.requiresPassword ? (
        <FormField
          ids={ids}
          label={t("exams.studentExamStart.examPassword")}
          required
          helper={t("exams.password.helper")}
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
                {t("exams.studentExamStart.seeYourResult")}
              </Link>
            ) : null}
          </AlertDescription>
        </Alert>
      ) : null}
      <div>
        <Button type="submit" size="lg" disabled={start.isPending} aria-busy={start.isPending} data-testid="exam-start-button">
          <Play aria-hidden="true" />
          {start.isPending ? t("exams.studentExamStart.starting") : t("exams.studentExamStart.startExam")}
        </Button>
      </div>
    </form>
  );
}

/** What the student can do next, from server facts only. */
function StartAction({ exam }: { exam: StudentExam }) {
  const t = useT();
  if (exam.type === "WRITTEN") return <StudentWrittenExam exam={exam} />;
  if (exam.type === "PHYSICAL") return <StudentPhysicalExam exam={exam} />;
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
            {t("exams.studentExamStart.resumeExam")}
          </Link>
        </div>
      </div>
    );
  }
  if (attempt) {
    return (
      <div className="flex flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10 sm:p-5">
        <p className="text-sm text-foreground">{t("exams.studentExamStart.yourAnswersWereSubmitted")}</p>
        <div>
          <Link href={studentExamPaths.result(exam.id)} className={buttonVariants({ variant: "outline" })} data-testid="exam-result-link">
            {exam.resultAvailable ? t("exams.studentExamStart.viewResult") : t("exams.studentExamStart.viewSubmissionStatus")}
          </Link>
        </div>
      </div>
    );
  }
  if (exam.status === "UPCOMING" || exam.status === "DRAFT") {
    return (
      <EmptyState
        title={t("exams.studentExamStart.thisExamHasntOpened")}
        description={t("exams.start.notOpenDescription", { when: formatExamDateTime(exam.startAt) })}
      />
    );
  }
  if (exam.status === "FINISHED") {
    return (
      <EmptyState
        title={t("exams.studentExamStart.thisExamHasClosed")}
        description={t("exams.studentExamStart.theExamWindowEnded")}
      />
    );
  }
  return <StartForm exam={exam} />;
}

/** Exam details + start (Slice C; WRITTEN/PHYSICAL from Slice D): rules summary, password, Start / Open paper. */
export function StudentExamStart({ examId }: { examId: string }) {
  const t = useT();
  const query = useStudentExam(examId);
  const router = useRouter();
  const notFound = query.status === "error" && isApiClientError(query.error) && query.error.status === 404;
  return (
    <div className="flex min-w-0 flex-col gap-6">
      <Breadcrumbs items={[{ label: t("exams.studentExamStart.myExams"), href: studentExamPaths.list }, { label: query.data?.name ?? t("exams.studentExamStart.exam") }]} />
      {notFound ? (
        <EmptyState
          title={t("exams.studentExamStart.examNotFound")}
          description={t("exams.studentExamStart.thisExamDoesntExist")}
          action={{ label: t("exams.studentExamStart.backToMyExams"), onClick: () => router.push(studentExamPaths.list) }}
        />
      ) : (
        <QueryStateBoundary
          query={query}
          loadingLabel={t("exams.studentExamStart.loadingExam")}
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/student/dashboard" }}
          genericErrorMessage={t("exams.studentExamStart.weCouldntLoadThis")}
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
          {t("exams.studentExamStart.backToMyExams")}
        </Link>
      </div>
    </div>
  );
}
