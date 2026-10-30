"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { FileClock, History, ListTodo, Plus, Search, Trophy, Zap } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs } from "@/components/ui/tabs";
import { LiveRegion } from "@/components/ui/live-region";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { ConfirmDeleteDialog, mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { ExamCard } from "@/components/exams/config/exam-card";
import { ExamFormDialog } from "@/components/exams/config/exam-form-dialog";
import { ExamPageTitle, examPaths, type ExamPortal } from "@/components/exams/config/exam-shared";
import { useAuth } from "@/lib/auth/auth-context";
import { canAuthorExams } from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import {
  useDeleteExam,
  useExamDirectory,
  type ExamCard as ExamCardData,
  type ExamDirectory as ExamDirectoryData,
} from "@/lib/api/exam-config";
import { cn } from "@/lib/utils";

type DirectoryTab = "in-progress" | "upcoming" | "previous";

const TABS: {
  value: DirectoryTab;
  label: string;
  icon: typeof Zap;
  key: keyof Pick<ExamDirectoryData, "inProgress" | "upcoming" | "previous">;
  empty: string;
}[] = [
  { value: "in-progress", label: "In progress exams", icon: Zap, key: "inProgress", empty: "No active exams" },
  { value: "upcoming", label: "Upcoming exams", icon: ListTodo, key: "upcoming", empty: "No upcoming exams" },
  { value: "previous", label: "Previous exams", icon: History, key: "previous", empty: "No previous exams" },
];

const DELETE_BLOCKED = "Students have already taken this exam, so it can't be deleted.";
const SEARCH_DEBOUNCE_MS = 300;

function DirectorySkeleton() {
  return (
    <div role="status" aria-busy="true" className="flex flex-col gap-4" data-testid="exam-directory-loading">
      <span className="sr-only">Loading exams…</span>
      <div className="flex gap-3">
        <Skeleton className="h-9 w-44" />
        <Skeleton className="h-9 w-44" />
        <Skeleton className="h-9 w-44" />
      </div>
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {Array.from({ length: 3 }, (_, index) => (
          <div key={index} className="flex flex-col gap-3 rounded-xl bg-card p-4 ring-1 ring-foreground/10">
            <Skeleton className="h-6 w-32" />
            <Skeleton className="h-5 w-3/4" />
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-2 w-full" />
            <Skeleton className="h-8 w-40" />
          </div>
        ))}
      </div>
    </div>
  );
}

/**
 * "Exams" directory (Klass Exam Module, Slice B): IN PROGRESS / UPCOMING /
 * PREVIOUS tabs grouped by the server (status from the server clock), server
 * search, card grid, and the shared create/edit dialog. Role checks here only
 * hide controls — every endpoint re-checks access server-side (a teacher only
 * ever receives exams of their own courses).
 */
export function ExamDirectory({ portal }: { portal: ExamPortal }) {
  const paths = examPaths(portal);
  const { session } = useAuth();
  const role = session?.role ?? null;
  const canEdit = canAuthorExams(role);
  const canDelete = canEdit && role !== "TEACHER_ASSISTANT";

  const [tab, setTab] = useState<DirectoryTab>("in-progress");
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [dialog, setDialog] = useState<{ key: number; examId: string | null } | null>(null);
  const [pendingDelete, setPendingDelete] = useState<ExamCardData | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [deleteBlocked, setDeleteBlocked] = useState<Record<string, true>>({});
  const toast = useAdminToast();

  useEffect(() => {
    const handle = setTimeout(() => setSearch(searchInput.trim()), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(handle);
  }, [searchInput]);

  const query = useExamDirectory(search);
  const deleteExam = useDeleteExam();
  const isRefetching = query.isFetching && query.data !== undefined;

  async function confirmDelete() {
    if (!pendingDelete) return;
    setDeleteError(null);
    try {
      await deleteExam.mutateAsync(pendingDelete.id);
      toast.show({ kind: "success", text: `“${pendingDelete.name}” was deleted.` });
      setPendingDelete(null);
    } catch (error) {
      if (isApiClientError(error) && error.status === 409) {
        setDeleteBlocked((current) => ({ ...current, [pendingDelete.id]: true }));
        setDeleteError(`${DELETE_BLOCKED}${error.message ? ` (${error.message})` : ""}`);
      } else {
        setDeleteError(mutationErrorMessage(error));
      }
    }
  }

  const toolbar = (
    <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
      <div className="relative w-full md:max-w-sm">
        <Input
          type="search"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          placeholder="Search exams"
          aria-label="Search exams"
          className="h-9 pr-9"
          data-testid="exam-search"
        />
        <Search className="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
      </div>
      <div className="flex flex-wrap gap-2 sm:justify-end">
        <Link
          href={paths.leaderboards}
          className={cn(buttonVariants({ variant: "secondary", size: "lg" }), "uppercase tracking-wide")}
          data-testid="exam-leaderboard-link"
        >
          <Trophy aria-hidden="true" />
          Leaderboard
        </Link>
        {canEdit ? (
          <Button
            type="button"
            size="lg"
            className="uppercase tracking-wide"
            onClick={() => setDialog({ key: Date.now(), examId: null })}
            data-testid="exam-new"
          >
            <Plus aria-hidden="true" />
            New exam
          </Button>
        ) : null}
      </div>
    </div>
  );

  function renderCards(cards: ExamCardData[], empty: string, serverNow: string) {
    if (cards.length === 0) {
      return (
        <div className="flex flex-wrap items-center gap-3 py-6">
          <p className="text-sm text-muted-foreground" data-testid="exam-directory-empty">
            {search ? `${empty} match “${search}”.` : empty}
          </p>
          {search ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => {
                setSearchInput("");
                setSearch("");
              }}
              data-testid="exam-search-clear"
            >
              Clear search
            </Button>
          ) : null}
        </div>
      );
    }
    return (
      <ul className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3" aria-label="Exams" data-testid="exam-card-grid">
        {cards.map((exam) => (
          <li key={exam.id} className="flex min-w-0">
            <ExamCard
              exam={exam}
              serverNow={serverNow}
              manageHref={paths.manage(exam.id)}
              statsHref={paths.stats(exam.id)}
              canEdit={canEdit}
              canDelete={canDelete}
              deleteBlockedReason={deleteBlocked[exam.id] ? DELETE_BLOCKED : null}
              onEdit={() => setDialog({ key: Date.now(), examId: exam.id })}
              onDelete={() => {
                setDeleteError(null);
                setPendingDelete(exam);
              }}
            />
          </li>
        ))}
      </ul>
    );
  }

  return (
    <div className="flex min-w-0 flex-col gap-6">
      <ExamPageTitle title="Exams" icon={<FileClock className="size-5" aria-hidden="true" />} />

      {portal === "teacher" ? (
        <nav aria-label="More exam tools" className="-mt-3 flex flex-wrap gap-x-4 gap-y-1 text-sm">
          <Link href="/teacher/exams/questions" className="text-primary underline-offset-4 hover:underline">
            Course question bank (legacy)
          </Link>
          <Link href="/teacher/exams/marking" className="text-primary underline-offset-4 hover:underline">
            Marking queue
          </Link>
        </nav>
      ) : null}

      {query.status === "pending" ? (
        <DirectorySkeleton />
      ) : (
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading exams…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: paths.dashboard }}
      >
        {(data) => (
          <div aria-busy={isRefetching} className="flex min-w-0 flex-col">
            <LiveRegion message={isRefetching ? "Updating…" : ""} />
            <Tabs
              aria-label="Exams by status"
              idPrefix="exam-dir-"
              value={tab}
              onValueChange={(next) => setTab(next as DirectoryTab)}
              items={TABS.map((entry) => {
                const cards = data[entry.key] ?? [];
                const Icon = entry.icon;
                return {
                  value: entry.value,
                  label: entry.label,
                  testId: `exam-tab-${entry.value}`,
                  icon: <Icon className="size-4" aria-hidden="true" />,
                  badge: (
                    <span
                      className="ml-1 inline-flex min-w-6 items-center justify-center rounded-full bg-muted px-1.5 text-xs font-medium text-foreground"
                      data-testid={`exam-tab-count-${entry.value}`}
                    >
                      {cards.length}
                    </span>
                  ),
                  content: (
                    <div className={cn("flex flex-col gap-5", isRefetching && "opacity-60")}>
                      {toolbar}
                      {renderCards(cards, entry.empty, data.serverNow)}
                    </div>
                  ),
                };
              })}
              listClassName="[&>[role=tab]]:uppercase [&>[role=tab]]:tracking-wide"
            />
          </div>
        )}
      </QueryStateBoundary>
      )}

      {dialog ? (
        <ExamFormDialog
          key={dialog.key}
          open
          examId={dialog.examId}
          onOpenChange={(open) => {
            if (!open) setDialog(null);
          }}
          onSaved={(exam, mode) => {
            setDialog(null);
            toast.show({ kind: "success", text: mode === "create" ? `“${exam.name}” was created.` : `“${exam.name}” was saved.` });
            void query.refetch();
          }}
        />
      ) : null}

      <ConfirmDeleteDialog
        open={pendingDelete !== null}
        onOpenChange={(open) => {
          if (!open && !deleteExam.isPending) setPendingDelete(null);
        }}
        title="Delete exam?"
        description={
          pendingDelete
            ? `“${pendingDelete.name}” will be removed from the exam directory. Exams that students have already taken can't be deleted.`
            : ""
        }
        confirmLabel="Delete exam"
        pending={deleteExam.isPending}
        error={deleteError}
        onConfirm={() => void confirmDelete()}
      />

      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </div>
  );
}
