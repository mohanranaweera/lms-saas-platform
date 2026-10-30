"use client";

import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Skeleton } from "@/components/ui/skeleton";
import { QuestionForm } from "@/components/exams/bank/question-form";
import {
  BankHeaderSkeleton,
  BankPageHeader,
  bankPaths,
  useBankContext,
  type BankPortal,
} from "@/components/exams/bank/bank-shared";
import { useBankQuestion } from "@/lib/api/question-bank";

function FormSkeleton() {
  return (
    <div role="status" aria-busy="true" className="flex flex-col gap-5">
      <span className="sr-only">Loading question…</span>
      <Skeleton className="h-48 w-full rounded-xl" />
      <Skeleton className="h-72 w-full rounded-xl" />
      <Skeleton className="h-36 w-full rounded-xl" />
    </div>
  );
}

/**
 * "Add a Question to {course}" / "Edit a Question in {course}" page body,
 * shared by the Tenant Admin and Teacher route trees.
 */
export function QuestionEditorPage({
  portal,
  courseId,
  moduleId,
  questionId,
}: {
  portal: BankPortal;
  courseId: string;
  moduleId: string;
  /** Present on the edit route. */
  questionId?: string;
}) {
  const paths = bankPaths(portal, courseId, moduleId);
  const context = useBankContext(courseId, moduleId);
  const questionQuery = useBankQuestion(questionId ?? "");
  const editing = Boolean(questionId);

  if (context.status === "pending") {
    return (
      <div className="flex flex-col gap-6">
        <BankHeaderSkeleton label="Loading course…" />
        <FormSkeleton />
      </div>
    );
  }

  return (
    <QueryStateBoundary
      query={context}
      loadingLabel="Loading course…"
      loginPath="/login"
      permissionDenied={{ dashboardHref: paths.dashboard }}
    >
      {({ course, module }) => (
        <div className="mx-auto flex w-full max-w-5xl flex-col gap-6">
          <BankPageHeader
            title={editing ? `Edit a Question in ${course.name}` : `Add a Question to ${course.name}`}
            breadcrumbs={[
              { label: "Courses", href: paths.courses },
              { label: course.name, href: paths.course },
              { label: `Questions in ${module.title}`, href: paths.bank },
              { label: editing ? "Edit" : "Create" },
            ]}
          />
          {editing ? (
            questionQuery.status === "pending" ? (
              <FormSkeleton />
            ) : (
              <QueryStateBoundary
                query={questionQuery}
                loadingLabel="Loading question…"
                loginPath="/login"
                permissionDenied={{ dashboardHref: paths.dashboard }}
              >
                {(question) => (
                  <QuestionForm
                    key={question.id}
                    courseId={courseId}
                    moduleId={moduleId}
                    question={question}
                    cancelHref={paths.bank}
                  />
                )}
              </QueryStateBoundary>
            )
          ) : (
            <QuestionForm courseId={courseId} moduleId={moduleId} cancelHref={paths.bank} />
          )}
        </div>
      )}
    </QueryStateBoundary>
  );
}
