"use client";

import { useEffect, useId, useState, type MouseEvent } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { CheckCircle2, ChevronDown, ChevronLeft, ChevronRight, Copy, Eye, EyeOff, Pencil, Plus, Trash2 } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs } from "@/components/ui/tabs";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { ConfirmDeleteDialog, mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { RichContent } from "@/components/exams/rich-content";
import { CopyQuestionsDialog } from "@/components/exams/bank/copy-questions-dialog";
import { QuestionImportsTab } from "@/components/exams/bank/question-imports-tab";
import {
  BankHeaderSkeleton,
  BankPageHeader,
  SectionLabel,
  bankPaths,
  useBankContext,
  type BankPortal,
} from "@/components/exams/bank/bank-shared";
import {
  formatMarks,
  useDeleteBankQuestion,
  useModuleQuestions,
  type BankQuestion,
} from "@/lib/api/question-bank";
import { cn } from "@/lib/utils";
import { CAP } from "@/lib/auth/capabilities";
import { useCapabilities } from "@/lib/api/my-permissions";

const PAGE_SIZE = 50;

function QuestionListSkeleton() {
  return (
    <div role="status" aria-busy="true" className="flex flex-col gap-3" data-testid="questions-loading">
      <span className="sr-only">Loading questions…</span>
      {Array.from({ length: 5 }, (_, index) => (
        <div key={index} className="flex items-center gap-3 rounded-xl bg-card px-4 py-4 ring-1 ring-foreground/10">
          <Skeleton className="h-6 w-10 rounded-full" />
          <Skeleton className="h-4 flex-1" />
          <Skeleton className="h-6 w-16 rounded-full" />
        </div>
      ))}
    </div>
  );
}

function QuestionRow({
  question,
  number,
  editHref,
  onDelete,
  canEdit,
  canDelete,
}: {
  question: BankQuestion;
  number: number;
  editHref: string;
  onDelete: () => void;
  canEdit: boolean;
  canDelete: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [showAnswers, setShowAnswers] = useState(true);
  const contentId = useId();
  const options = [...question.options].sort((a, b) => a.sequence - b.sequence);

  const onHeaderClick = (event: MouseEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement;
    if (target.closest("button, a, input, select, textarea")) return;
    setOpen((current) => !current);
  };

  return (
    <li className="overflow-hidden rounded-xl bg-card ring-1 ring-foreground/10" data-testid="question-row">
      {/* Mouse convenience: the whole header toggles; the chevron button is the accessible control. */}
      <div
        className={cn("flex cursor-pointer items-start gap-3 px-3 py-3 sm:px-5", open && "border-b border-border")}
        onClick={onHeaderClick}
      >
        <span className="mt-0.5 inline-flex h-6 min-w-9 shrink-0 items-center justify-center rounded-full bg-primary/10 px-2 text-xs font-semibold text-primary" data-testid="question-number">
          Q{number}
        </span>
        <RichContent html={question.bodyHtml} className="flex-1 text-sm text-foreground" testId="question-body" />
        <div className="flex shrink-0 items-center gap-1">
          <span className="inline-flex h-6 items-center rounded-full bg-muted px-2.5 text-xs whitespace-nowrap text-muted-foreground" data-testid="question-marks-chip">
            {formatMarks(question.marks)}
          </span>
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            aria-expanded={open}
            aria-controls={contentId}
            aria-label={`${open ? "Collapse" : "Expand"} question ${number}`}
            onClick={() => setOpen((current) => !current)}
            data-testid="question-toggle"
          >
            <ChevronDown
              aria-hidden="true"
              className={cn("transition-transform duration-150 motion-reduce:transition-none", open && "rotate-180")}
            />
          </Button>
        </div>
      </div>
      <div id={contentId} hidden={!open} className="px-4 pt-4 pb-3 sm:px-10" data-testid="question-details">
        {open ? (
          <div className="flex flex-col gap-4">
            <ol className="flex flex-col gap-3">
              {options.map((option, index) => {
                const highlighted = showAnswers && option.correct;
                return (
                  <li key={option.id} className="flex flex-col gap-1.5" data-testid="question-answer" data-correct={highlighted ? "true" : undefined}>
                    <div className="flex items-center gap-2">
                      <SectionLabel>Answer {index + 1}</SectionLabel>
                      {highlighted ? (
                        <span className="inline-flex items-center gap-1 text-xs font-medium text-green-700 dark:text-green-400">
                          <CheckCircle2 className="size-3.5" aria-hidden="true" />
                          Correct answer
                        </span>
                      ) : null}
                    </div>
                    <div
                      className={cn(
                        "rounded-md border border-border bg-muted/30 px-4 py-3 text-sm",
                        highlighted && "border-green-600/50 bg-green-600/10"
                      )}
                    >
                      <RichContent html={option.html} />
                    </div>
                  </li>
                );
              })}
            </ol>
            <div className="flex flex-col gap-1.5">
              <SectionLabel>Explanation</SectionLabel>
              <div className="rounded-md border border-dashed border-border px-4 py-3 text-sm">
                <RichContent html={question.explanationHtml} emptyText="No explanation added." />
              </div>
            </div>
            <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border pt-3">
              <Button
                type="button"
                variant="ghost"
                aria-pressed={showAnswers}
                title="Toggle correct answer highlight"
                onClick={() => setShowAnswers((current) => !current)}
                className="h-8 bg-green-600/10 px-3 text-xs font-semibold tracking-wide text-green-700 uppercase hover:bg-green-600/20 hover:text-green-800 dark:text-green-400"
                data-testid="question-toggle-answers"
              >
                {showAnswers ? <EyeOff aria-hidden="true" /> : <Eye aria-hidden="true" />}
                {showAnswers ? "Hide answers" : "Show answers"}
              </Button>
              <div className="flex items-center gap-1">
                {canEdit ? (
                  <Link
                    href={editHref}
                    className={cn(buttonVariants({ variant: "ghost" }), "h-8 text-xs font-semibold tracking-wide text-primary uppercase hover:text-primary")}
                    aria-label={`Edit question ${number}`}
                    data-testid="question-edit"
                  >
                    <Pencil aria-hidden="true" />
                    Edit
                  </Link>
                ) : null}
                {canDelete ? (
                  <Button
                    type="button"
                    variant="ghost"
                    className="h-8 text-xs font-semibold tracking-wide text-destructive uppercase hover:bg-destructive/10 hover:text-destructive"
                    aria-label={`Delete question ${number}`}
                    onClick={onDelete}
                    data-testid="question-delete"
                  >
                    <Trash2 aria-hidden="true" />
                    Delete
                  </Button>
                ) : null}
              </div>
            </div>
          </div>
        ) : null}
      </div>
    </li>
  );
}

/**
 * Klass "Questions in {module}" page (QB1/QB2): ALL QUESTIONS (accordion of
 * the module's bank) and IMPORTS tabs, COPY QUESTION / CREATE QUESTION.
 * Shared by the Tenant Admin and Teacher route trees; the backend enforces
 * authoring access on every call.
 */
export function ModuleQuestionBank({
  portal,
  courseId,
  moduleId,
}: {
  portal: BankPortal;
  courseId: string;
  moduleId: string;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const paths = bankPaths(portal, courseId, moduleId);
  const context = useBankContext(courseId, moduleId);
  const [tab, setTab] = useState("all");
  const [page, setPage] = useState(0);
  const questionsQuery = useModuleQuestions(courseId, moduleId, page, PAGE_SIZE);
  const remove = useDeleteBankQuestion(courseId, moduleId);
  const toast = useAdminToast();
  const showToast = toast.show;
  const [deleteTarget, setDeleteTarget] = useState<{ question: BankQuestion; number: number } | null>(null);
  const [copyDialog, setCopyDialog] = useState({ open: false, key: 0 });
  // STAFF-MGMT-03: add / copy, edit and import follow MODULES_QUESTIONS_ADD /
  // _EDIT / _IMPORT for capability-driven roles; delete needs
  // MODULES_QUESTIONS_DELETE (owning teacher only, never a staff role). Teachers
  // and Tenant Admin keep everything. UX only; every endpoint re-checks.
  const { can } = useCapabilities();
  const canAdd = can(CAP.MODULES_QUESTIONS_ADD, true);
  const canEditQuestion = can(CAP.MODULES_QUESTIONS_EDIT, true);
  const canImport = can(CAP.MODULES_QUESTIONS_IMPORT, true);
  const canDeleteQuestion = can("MODULES_QUESTIONS_DELETE", true);

  // One-time confirmation after the create/edit page redirects back here.
  const saved = searchParams.get("saved");
  useEffect(() => {
    if (saved !== "created" && saved !== "updated") return;
    showToast({ kind: "success", text: saved === "created" ? "Question created." : "Question saved." });
    router.replace(pathname);
  }, [saved, showToast, router, pathname]);

  if (context.status === "pending") {
    return (
      <div className="flex flex-col gap-6">
        <BankHeaderSkeleton label="Loading module…" />
        <QuestionListSkeleton />
      </div>
    );
  }

  const totalQuestions = questionsQuery.data?.totalElements ?? 0;

  return (
    <QueryStateBoundary
      query={context}
      loadingLabel="Loading module…"
      loginPath="/login"
      permissionDenied={{ dashboardHref: paths.dashboard }}
    >
      {({ course, module }) => {
        const allQuestions = (
          <div className="flex flex-col gap-4">
            {canAdd ? (
              <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
                <Button
                  type="button"
                  variant="outline"
                  className="h-9 border-primary/60 px-4 tracking-wide text-primary uppercase hover:text-primary"
                  onClick={() => setCopyDialog((current) => ({ open: true, key: current.key + 1 }))}
                  disabled={questionsQuery.status !== "success" || totalQuestions === 0}
                  title={totalQuestions === 0 ? "There are no questions to copy yet" : undefined}
                  data-testid="bank-copy"
                >
                  <Copy aria-hidden="true" />
                  Copy question
                </Button>
                <Link
                  href={paths.create}
                  className={cn(buttonVariants({ variant: "default" }), "h-9 px-4 tracking-wide uppercase")}
                  data-testid="bank-create"
                >
                  <Plus aria-hidden="true" />
                  Create question
                </Link>
              </div>
            ) : null}

            {questionsQuery.status === "pending" ? (
              <QuestionListSkeleton />
            ) : (
              <QueryStateBoundary
                query={questionsQuery}
                loadingLabel="Loading questions…"
                loginPath="/login"
                permissionDenied={{ dashboardHref: paths.dashboard }}
                genericErrorMessage="The questions could not be loaded."
              >
                {(result) => {
                  if (result.content.length === 0) {
                    return (
                      <EmptyState
                        title="No questions in this module yet"
                        description={
                          canAdd || canImport
                            ? "Create questions one by one, or import many at once from a CSV file in the Imports tab. Exams draw their questions from this bank."
                            : "Questions added to this module by your exam team will appear here."
                        }
                        action={canAdd ? { label: "Create question", onClick: () => router.push(paths.create) } : undefined}
                      />
                    );
                  }
                  const offset = result.page * result.size;
                  return (
                    <div
                      className={cn("flex flex-col gap-3", questionsQuery.isFetching && "opacity-70")}
                      aria-busy={questionsQuery.isFetching || undefined}
                    >
                      <p className="text-sm text-muted-foreground" data-testid="bank-count">
                        {result.totalElements} {result.totalElements === 1 ? "question" : "questions"}
                      </p>
                      <ol className="flex flex-col gap-3" data-testid="question-list">
                        {result.content.map((question, index) => (
                          <QuestionRow
                            key={question.id}
                            question={question}
                            number={offset + index + 1}
                            editHref={paths.edit(question.id)}
                            canEdit={canEditQuestion}
                            canDelete={canDeleteQuestion}
                            onDelete={() => {
                              remove.reset();
                              setDeleteTarget({ question, number: offset + index + 1 });
                            }}
                          />
                        ))}
                      </ol>
                      {result.totalPages > 1 ? (
                        <nav aria-label="Questions pagination" className="flex items-center justify-end gap-2 text-sm text-muted-foreground">
                          <span aria-live="polite">
                            Page {result.page + 1} of {result.totalPages}
                          </span>
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon-sm"
                            aria-label="Previous page"
                            disabled={result.page <= 0}
                            onClick={() => setPage((current) => Math.max(0, current - 1))}
                          >
                            <ChevronLeft aria-hidden="true" />
                          </Button>
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon-sm"
                            aria-label="Next page"
                            disabled={result.page + 1 >= result.totalPages}
                            onClick={() => setPage((current) => current + 1)}
                          >
                            <ChevronRight aria-hidden="true" />
                          </Button>
                        </nav>
                      ) : null}
                    </div>
                  );
                }}
              </QueryStateBoundary>
            )}
          </div>
        );

        return (
          <div className="flex flex-col gap-6">
            <BankPageHeader
              title={`Questions in ${module.title}`}
              breadcrumbs={[
                { label: "Courses", href: paths.courses },
                { label: course.name, href: paths.course },
                { label: `Questions in ${module.title}` },
              ]}
            />
            <Tabs
              aria-label="Question bank"
              value={tab}
              onValueChange={setTab}
              className="[&_[role=tab]]:tracking-wide [&_[role=tab]]:uppercase"
              items={[
                { value: "all", label: "All questions", content: allQuestions },
                ...(canImport
                  ? [
                      {
                        value: "imports",
                        label: "Imports",
                        content: (
                          <QuestionImportsTab
                            courseId={courseId}
                            moduleId={moduleId}
                            dashboardHref={paths.dashboard}
                            onImported={(result) =>
                              toast.show({
                                kind: "success",
                                text: `Imported ${result.questionCount} ${result.questionCount === 1 ? "question" : "questions"} from ${result.fileName}.`,
                              })
                            }
                          />
                        ),
                      },
                    ]
                  : []),
              ]}
            />

            <CopyQuestionsDialog
              key={copyDialog.key}
              open={copyDialog.open}
              onOpenChange={(open) => setCopyDialog((current) => ({ ...current, open }))}
              courseId={courseId}
              courseName={course.name}
              moduleId={moduleId}
              moduleTitle={module.title}
              questionCount={totalQuestions}
              onCopied={(copied, targetTitle) => {
                setCopyDialog((current) => ({ ...current, open: false }));
                toast.show({
                  kind: "success",
                  text: `Copied ${copied} ${copied === 1 ? "question" : "questions"} to “${targetTitle}”.`,
                });
              }}
            />
            <ConfirmDeleteDialog
              open={deleteTarget !== null}
              onOpenChange={(open) => {
                if (!open) setDeleteTarget(null);
              }}
              title={`Delete question ${deleteTarget?.number ?? ""}?`}
              description="The question is removed from this module's bank. Exams that already include it keep their own copy, and student attempts are not changed."
              confirmLabel="Delete question"
              pending={remove.isPending}
              error={remove.isError ? mutationErrorMessage(remove.error) : null}
              onConfirm={() => {
                if (!deleteTarget) return;
                const target = deleteTarget;
                remove.mutate(target.question.id, {
                  onSuccess: () => {
                    setDeleteTarget(null);
                    toast.show({ kind: "success", text: `Question ${target.number} deleted.` });
                  },
                });
              }}
            />
            <AdminToast message={toast.message} onDismiss={toast.dismiss} />
          </div>
        );
      }}
    </QueryStateBoundary>
  );
}
