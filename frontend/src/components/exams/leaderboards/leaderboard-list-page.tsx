"use client";

import Link from "next/link";
import { useState } from "react";
import { ArrowLeft, Eye, EyeOff, Pencil, Plus, Trash2, Trophy, Users } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { Skeleton } from "@/components/ui/skeleton";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { ConfirmDeleteDialog, mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { ExamPageTitle, examPaths, type ExamPortal } from "@/components/exams/config/exam-shared";
import { LeaderboardFormDialog } from "@/components/exams/leaderboards/leaderboard-form-dialog";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageExamResults } from "@/lib/auth/permissions";
import { useDeleteLeaderboard, useLeaderboards, type LeaderboardSummary } from "@/lib/api/exam-results";

/** "Visible to students" / "Staff only" — icon + text, never colour alone. */
export function VisibilityBadge({ published }: { published: boolean }) {
  return published ? (
    <span className="inline-flex h-6 items-center gap-1 rounded-full bg-green-600/10 px-2 text-xs font-medium whitespace-nowrap text-green-800 dark:text-green-300" data-testid="leaderboard-visibility" data-published="true">
      <Eye className="size-3" aria-hidden="true" />
      Visible to students
    </span>
  ) : (
    <span className="inline-flex h-6 items-center gap-1 rounded-full bg-muted px-2 text-xs font-medium whitespace-nowrap text-muted-foreground" data-testid="leaderboard-visibility" data-published="false">
      <EyeOff className="size-3" aria-hidden="true" />
      Staff only
    </span>
  );
}

/** Exam leaderboards (Slice E, D7): list with View / Edit / Delete and "Add leaderboard". Writes are re-authorized server-side. */
export function LeaderboardListPage({ portal }: { portal: ExamPortal }) {
  const paths = examPaths(portal);
  const { session } = useAuth();
  const canManage = canManageExamResults(session?.role ?? null);
  const query = useLeaderboards();
  const remove = useDeleteLeaderboard();
  const toast = useAdminToast();
  const [dialog, setDialog] = useState<{ id: string | null } | null>(null);
  const [pendingDelete, setPendingDelete] = useState<LeaderboardSummary | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  async function confirmDelete() {
    if (!pendingDelete) return;
    setDeleteError(null);
    try {
      await remove.mutateAsync(pendingDelete.id);
      toast.show({ kind: "success", text: `Leaderboard “${pendingDelete.name}” deleted.` });
      setPendingDelete(null);
    } catch (error) {
      setDeleteError(mutationErrorMessage(error));
    }
  }

  const actions = (row: LeaderboardSummary) => (
    <div className="flex flex-wrap items-center justify-end gap-1">
      <Link
        href={paths.leaderboard(row.id)}
        className={buttonVariants({ variant: "outline", size: "sm" })}
        aria-label={`View ${row.name}`}
        data-testid="leaderboard-view"
      >
        <Eye aria-hidden="true" />
        View
      </Link>
      {canManage ? (
        <>
          <Button type="button" variant="ghost" size="sm" onClick={() => setDialog({ id: row.id })} aria-label={`Edit ${row.name}`} data-testid="leaderboard-edit">
            <Pencil aria-hidden="true" />
            Edit
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="text-destructive"
            onClick={() => {
              setDeleteError(null);
              setPendingDelete(row);
            }}
            aria-label={`Delete ${row.name}`}
            data-testid="leaderboard-delete"
          >
            <Trash2 aria-hidden="true" />
            Delete
          </Button>
        </>
      ) : null}
    </div>
  );

  const columns: DataTableColumn<LeaderboardSummary>[] = [
    {
      key: "name",
      header: "Name",
      hideOnCard: true,
      cell: (row) => (
        <Link href={paths.leaderboard(row.id)} className="font-medium text-foreground hover:underline" data-testid="leaderboard-name-link">
          {row.name}
        </Link>
      ),
    },
    {
      key: "exams",
      header: "Exams",
      cell: (row) => (
        <span className="tabular-nums" data-testid="leaderboard-exam-count">
          {row.examCount} {row.examCount === 1 ? "exam" : "exams"}
        </span>
      ),
    },
    { key: "visibility", header: "Visibility", hideOnCard: true, cell: (row) => <VisibilityBadge published={Boolean(row.published)} /> },
    { key: "actions", header: "Actions", hideOnCard: true, className: "text-right", cell: actions },
  ];

  return (
    <div className="flex min-w-0 flex-col gap-5">
      <div className="flex flex-col gap-2">
        <ExamPageTitle
          title="Leaderboards"
          icon={<Trophy className="size-5" aria-hidden="true" />}
          actions={
            <div className="flex flex-wrap gap-2">
              <Link href={paths.directory} className={buttonVariants({ variant: "outline" })}>
                <ArrowLeft aria-hidden="true" />
                All exams
              </Link>
              {canManage ? (
                <Button type="button" onClick={() => setDialog({ id: null })} data-testid="leaderboard-add">
                  <Plus aria-hidden="true" />
                  Add leaderboard
                </Button>
              ) : null}
            </div>
          }
        />
        <Breadcrumbs items={[{ label: "Exams", href: paths.directory }, { label: "Leaderboards" }]} />
      </div>
      {query.status === "pending" ? (
        <div role="status" aria-busy="true" className="flex flex-col gap-2">
          <span className="sr-only">Loading leaderboards…</span>
          {Array.from({ length: 3 }, (_, index) => (
            <Skeleton key={index} className="h-12 w-full" />
          ))}
        </div>
      ) : (
        <QueryStateBoundary
          query={query}
          loginPath="/login"
          permissionDenied={{ dashboardHref: paths.directory }}
          genericErrorMessage="We couldn't load the leaderboards."
          isEmpty={(rows) => rows.length === 0}
          emptyState={{
            title: "No leaderboards yet",
            description: canManage
              ? "Group two or more exams into a leaderboard to rank students by their combined published marks."
              : "No leaderboard has been created yet. Exam managers and teachers can create one.",
            action: canManage ? { label: "Add leaderboard", onClick: () => setDialog({ id: null }) } : undefined,
            icon: <Users className="size-8" aria-hidden="true" />,
          }}
        >
          {(rows) => (
            <section aria-label="Leaderboards" className="rounded-xl bg-card p-3 shadow-sm ring-1 ring-foreground/10 [contain:inline-size] sm:p-4" data-testid="leaderboard-list">
              <DataTable
                columns={columns}
                rows={rows}
                rowKey={(row) => row.id}
                caption="Exam leaderboards"
                cardHeading={(row) => <span className="break-words">{row.name}</span>}
                cardHeadingAdornment={(row) => <VisibilityBadge published={Boolean(row.published)} />}
                cardFooter={actions}
              />
            </section>
          )}
        </QueryStateBoundary>
      )}
      {dialog ? (
        <LeaderboardFormDialog
          key={dialog.id ?? "new"}
          leaderboardId={dialog.id}
          onOpenChange={(open) => !open && setDialog(null)}
          onSaved={(_, name) => toast.show({ kind: "success", text: dialog.id ? `Leaderboard “${name}” updated.` : `Leaderboard “${name}” added.` })}
        />
      ) : null}
      <ConfirmDeleteDialog
        open={pendingDelete !== null}
        onOpenChange={(open) => {
          if (!open) setPendingDelete(null);
        }}
        title={`Delete “${pendingDelete?.name ?? "leaderboard"}”?`}
        description="Only the leaderboard is removed. Its exams, marks and results stay as they are."
        confirmLabel="Delete leaderboard"
        pending={remove.isPending}
        error={deleteError}
        onConfirm={() => void confirmDelete()}
      />
      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </div>
  );
}
