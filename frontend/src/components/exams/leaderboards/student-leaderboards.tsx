"use client";

import Link from "next/link";
import { useCallback, useState } from "react";
import { ArrowLeft, ChevronRight, Trophy } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { Skeleton } from "@/components/ui/skeleton";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ExamPageTitle } from "@/components/exams/config/exam-shared";
import { LeaderboardRanksView, RANKS_PAGE_SIZE, RankBadge } from "@/components/exams/leaderboards/leaderboard-ranks-view";
import { useStudentLeaderboardRanks, useStudentLeaderboards } from "@/lib/api/exam-results";

const STUDENT_PATHS = {
  exams: "/student/exams",
  list: "/student/exams/leaderboards",
  detail: (id: string) => `/student/exams/leaderboards/${id}`,
};

/** Student: published leaderboards that include one of the student's exams (server-filtered). */
export function StudentLeaderboardList() {
  const query = useStudentLeaderboards();
  return (
    <div className="flex min-w-0 flex-col gap-5">
      <div className="flex flex-col gap-2">
        <ExamPageTitle
          title="Leaderboards"
          icon={<Trophy className="size-5" aria-hidden="true" />}
          actions={
            <Link href={STUDENT_PATHS.exams} className={buttonVariants({ variant: "outline" })}>
              <ArrowLeft aria-hidden="true" />
              My exams
            </Link>
          }
        />
        <Breadcrumbs items={[{ label: "My exams", href: STUDENT_PATHS.exams }, { label: "Leaderboards" }]} />
      </div>
      {query.status === "pending" ? (
        <div role="status" aria-busy="true" className="grid gap-3 md:grid-cols-2">
          <span className="sr-only">Loading leaderboards…</span>
          <Skeleton className="h-20 w-full rounded-xl" />
          <Skeleton className="h-20 w-full rounded-xl" />
        </div>
      ) : (
        <QueryStateBoundary
          query={query}
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/student/dashboard" }}
          genericErrorMessage="We couldn't load the leaderboards."
          isEmpty={(rows) => rows.length === 0}
          emptyState={{
            title: "No leaderboards yet",
            description: "When your teachers publish a leaderboard for exams you're enrolled in, it will appear here.",
            icon: <Trophy className="size-8" aria-hidden="true" />,
          }}
        >
          {(rows) => (
            <ul className="grid gap-3 md:grid-cols-2" data-testid="student-leaderboards">
              {rows.map((board) => (
                <li key={board.id}>
                  <Link
                    href={STUDENT_PATHS.detail(board.id)}
                    className="flex min-w-0 items-center gap-3 rounded-xl bg-card p-4 shadow-sm ring-1 ring-foreground/10 outline-none hover:ring-primary/40 focus-visible:ring-3 focus-visible:ring-ring/50"
                    data-testid="student-leaderboard"
                  >
                    <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-amber-400/20 text-amber-700 dark:text-amber-300">
                      <Trophy className="size-5" aria-hidden="true" />
                    </span>
                    <span className="flex min-w-0 flex-1 flex-col">
                      <span className="font-semibold break-words text-foreground">{board.name}</span>
                      <span className="text-sm text-muted-foreground">
                        {board.examCount} {board.examCount === 1 ? "exam" : "exams"}
                      </span>
                    </span>
                    <ChevronRight className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </QueryStateBoundary>
      )}
    </div>
  );
}

/** Student ranks: other students' index numbers and per-exam marks are masked by the server; the own row (`self`) is highlighted. */
export function StudentLeaderboardRanks({ leaderboardId }: { leaderboardId: string }) {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const query = useStudentLeaderboardRanks(leaderboardId, { search, page, size: RANKS_PAGE_SIZE });
  const name = query.data?.name ?? "Leaderboard";
  const own = query.data?.rows.find((row) => row.self) ?? null;
  const onSearch = useCallback((value: string) => {
    setSearch(value);
    setPage(0);
  }, []);
  return (
    <div className="flex min-w-0 flex-col gap-5">
      <div className="flex flex-col gap-2">
        <ExamPageTitle
          title={name}
          icon={<Trophy className="size-5" aria-hidden="true" />}
          actions={
            <Link href={STUDENT_PATHS.list} className={buttonVariants({ variant: "outline" })}>
              <ArrowLeft aria-hidden="true" />
              Leaderboards
            </Link>
          }
        />
        <Breadcrumbs items={[{ label: "My exams", href: STUDENT_PATHS.exams }, { label: "Leaderboards", href: STUDENT_PATHS.list }, { label: name }]} />
      </div>
      <LeaderboardRanksView
        query={query}
        search={search}
        onSearch={onSearch}
        page={page}
        onPage={setPage}
        studentView
        header={
          own ? (
            <p className="flex flex-wrap items-center gap-2 rounded-xl bg-primary/10 px-4 py-3 text-sm text-foreground" data-testid="student-own-rank">
              <span className="font-medium">Your rank:</span>
              <RankBadge rank={own.rank} tied={(query.data?.rows ?? []).filter((row) => row.rank === own.rank).length > 1} />
            </p>
          ) : null
        }
      />
    </div>
  );
}
