"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowLeft, Pencil, Trash2, Trophy } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { ConfirmDeleteDialog, mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { ExamPageTitle, examPaths, type ExamPortal } from "@/components/exams/config/exam-shared";
import { LeaderboardFormDialog } from "@/components/exams/leaderboards/leaderboard-form-dialog";
import { VisibilityBadge } from "@/components/exams/leaderboards/leaderboard-list-page";
import { LeaderboardRanksView, RANKS_PAGE_SIZE } from "@/components/exams/leaderboards/leaderboard-ranks-view";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageExamResults } from "@/lib/auth/permissions";
import { useDeleteLeaderboard, useLeaderboardRanks } from "@/lib/api/exam-results";

/** Staff view of one leaderboard (Slice E, D7): included exams, ranks, search and paging. */
export function LeaderboardRanksPage({ portal, leaderboardId }: { portal: ExamPortal; leaderboardId: string }) {
  const paths = examPaths(portal);
  const router = useRouter();
  const { session } = useAuth();
  const canManage = canManageExamResults(session?.role ?? null);
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const query = useLeaderboardRanks(leaderboardId, { search, page, size: RANKS_PAGE_SIZE });
  const remove = useDeleteLeaderboard();
  const toast = useAdminToast();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const name = query.data?.name ?? "Leaderboard";

  const onSearch = useCallback((value: string) => {
    setSearch(value);
    setPage(0);
  }, []);

  async function confirmDelete() {
    setDeleteError(null);
    try {
      await remove.mutateAsync(leaderboardId);
      router.push(paths.leaderboards);
    } catch (error) {
      setDeleteError(mutationErrorMessage(error));
    }
  }

  return (
    <div className="flex min-w-0 flex-col gap-5">
      <div className="flex flex-col gap-2">
        <ExamPageTitle
          title={name}
          icon={<Trophy className="size-5" aria-hidden="true" />}
          actions={
            <div className="flex flex-wrap gap-2">
              <Link href={paths.leaderboards} className={buttonVariants({ variant: "outline" })}>
                <ArrowLeft aria-hidden="true" />
                Leaderboards
              </Link>
              {canManage && query.status === "success" ? (
                <>
                  <Button type="button" variant="outline" onClick={() => setEditing(true)} data-testid="leaderboard-edit">
                    <Pencil aria-hidden="true" />
                    Edit
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    className="text-destructive"
                    onClick={() => {
                      setDeleteError(null);
                      setDeleting(true);
                    }}
                    data-testid="leaderboard-delete"
                  >
                    <Trash2 aria-hidden="true" />
                    Delete
                  </Button>
                </>
              ) : null}
            </div>
          }
        />
        <Breadcrumbs items={[{ label: "Exams", href: paths.directory }, { label: "Leaderboards", href: paths.leaderboards }, { label: name }]} />
      </div>
      <LeaderboardRanksView
        query={query}
        search={search}
        onSearch={onSearch}
        page={page}
        onPage={setPage}
        header={
          query.data ? (
            <div className="flex">
              <VisibilityBadge published={query.data.published} />
            </div>
          ) : null
        }
      />
      {editing ? (
        <LeaderboardFormDialog
          leaderboardId={leaderboardId}
          onOpenChange={setEditing}
          onSaved={(_, saved) => toast.show({ kind: "success", text: `Leaderboard “${saved}” updated.` })}
        />
      ) : null}
      <ConfirmDeleteDialog
        open={deleting}
        onOpenChange={setDeleting}
        title={`Delete “${name}”?`}
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
