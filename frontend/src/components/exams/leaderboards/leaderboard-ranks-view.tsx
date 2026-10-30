"use client";

import { useEffect, useId, useState, type ReactNode } from "react";
import { ChevronLeft, ChevronRight, EyeOff, Medal, Search, Trophy } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { PermissionDeniedState } from "@/components/states/permission-denied-state";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { isApiClientError } from "@/lib/api/error";
import { finiteOrNull, type LeaderboardExam, type LeaderboardRankRow, type LeaderboardRanks } from "@/lib/api/exam-results";
import { cn } from "@/lib/utils";

export const RANKS_PAGE_SIZE = 20;
const DASH = "—";

export function marksValue(value: number | string | null | undefined): string {
  const n = finiteOrNull(value);
  if (n === null) return DASH;
  return Number.isInteger(n) ? String(n) : String(Number(n.toFixed(2)));
}

function shareText(value: number | string | null | undefined): string {
  const n = finiteOrNull(value);
  if (n === null) return DASH;
  return `${Number(n.toFixed(1))}%`;
}

const MEDALS: Record<number, { label: string; className: string }> = {
  1: { label: "Gold medal", className: "bg-amber-400 text-amber-950" },
  2: { label: "Silver medal", className: "bg-slate-300 text-slate-900" },
  3: { label: "Bronze medal", className: "bg-orange-700 text-white" },
};

/** Rank with a gold/silver/bronze medal for 1–3; the number is always shown (never colour alone). Ties share the rank. */
export function RankBadge({ rank, tied }: { rank: number; tied: boolean }) {
  const medal = MEDALS[rank];
  return (
    <span className="inline-flex items-center gap-1.5" data-testid="rank" data-rank={rank} data-tied={tied ? "true" : "false"}>
      {medal ? (
        <span className={cn("flex size-7 shrink-0 items-center justify-center rounded-full shadow-sm", medal.className)} data-testid="rank-medal" title={medal.label}>
          <Medal className="size-4" aria-hidden="true" />
          <span className="sr-only">{medal.label}, </span>
        </span>
      ) : (
        <span className="size-7 shrink-0" aria-hidden="true" />
      )}
      <span className="font-semibold text-foreground tabular-nums">{rank}</span>
      {tied ? (
        <span className="rounded-full bg-muted px-1.5 text-xs text-muted-foreground" data-testid="rank-tied">
          tie
        </span>
      ) : null}
    </span>
  );
}

/** Exams of the leaderboard; unpublished ones are flagged (they add nothing to totals, D7). */
export function IncludedExams({ exams }: { exams: LeaderboardExam[] }) {
  if (exams.length === 0) return null;
  return (
    <section aria-labelledby="leaderboard-exams-title" className="flex min-w-0 flex-col gap-2" data-testid="leaderboard-exams">
      <h2 id="leaderboard-exams-title" className="text-sm font-semibold text-foreground">
        Included exams
      </h2>
      <ul className="flex flex-wrap gap-2">
        {exams.map((exam) => (
          <li
            key={exam.id}
            className="flex min-w-0 max-w-full flex-wrap items-center gap-x-2 gap-y-1 rounded-lg border border-border bg-card px-3 py-1.5 text-sm"
            data-testid="leaderboard-exam"
            data-published={exam.published ? "true" : "false"}
          >
            <span className="min-w-0 font-medium break-words text-foreground">{exam.name}</span>
            <span className="text-muted-foreground tabular-nums">{marksValue(exam.totalMarks)} marks</span>
            {exam.published ? null : (
              <span className="inline-flex items-center gap-1 rounded-full bg-amber-500/15 px-2 py-0.5 text-xs font-medium text-amber-900 dark:text-amber-200" data-testid="leaderboard-exam-unpublished">
                <EyeOff className="size-3" aria-hidden="true" />
                Marks not published
              </span>
            )}
          </li>
        ))}
      </ul>
      {exams.some((exam) => !exam.published) ? (
        <p className="text-xs text-muted-foreground">Exams whose marks aren&apos;t published yet don&apos;t count towards the totals.</p>
      ) : null}
    </section>
  );
}

function isTied(rows: LeaderboardRankRow[], index: number): boolean {
  const rank = rows[index].rank;
  return (index > 0 && rows[index - 1].rank === rank) || (index < rows.length - 1 && rows[index + 1].rank === rank);
}

function perExamMarks(row: LeaderboardRankRow, examId: string): string {
  const entry = (row.perExam ?? []).find((item) => item.examId === examId);
  return entry ? marksValue(entry.marks) : DASH;
}

function TotalCell({ row }: { row: LeaderboardRankRow }) {
  return (
    <span className="tabular-nums" data-testid="rank-total">
      <span className="font-semibold text-foreground">{marksValue(row.totalMarks)}</span>
      <span className="text-muted-foreground"> / {marksValue(row.maxPossible)}</span>
    </span>
  );
}

function YouBadge() {
  return (
    <span className="rounded-full bg-primary px-2 py-0.5 text-xs font-semibold text-primary-foreground" data-testid="rank-you">
      You
    </span>
  );
}

/**
 * Ranks table (desktop) / cards (below `md`). Ranks, totals, shares and ties
 * all come from the server. `showIndex` / `showPerExam` are off on the student
 * view, where the server masks other students' index numbers and per-exam marks.
 */
function RanksTable({ data, studentView }: { data: LeaderboardRanks; studentView: boolean }) {
  const perExamExams = data.exams.filter((exam) => exam.published);
  const showPerExam = !studentView;
  return (
    <>
      <div className="hidden overflow-x-auto rounded-lg border border-border md:block" data-testid="ranks-table">
        <table className="w-full text-left text-sm">
          <caption className="sr-only">Leaderboard ranks</caption>
          <thead className="border-b border-border bg-muted/40">
            <tr>
              <th scope="col" className="px-4 py-2 font-medium">Rank</th>
              {studentView ? null : <th scope="col" className="px-4 py-2 font-medium">Index Number</th>}
              <th scope="col" className="px-4 py-2 font-medium">Name</th>
              <th scope="col" className="px-4 py-2 font-medium whitespace-nowrap">Total Marks</th>
              <th scope="col" className="px-4 py-2 text-right font-medium">Share</th>
              {showPerExam
                ? perExamExams.map((exam) => (
                    <th key={exam.id} scope="col" className="px-4 py-2 text-right font-medium">
                      {exam.name}
                    </th>
                  ))
                : null}
            </tr>
          </thead>
          <tbody>
            {data.rows.map((row, index) => (
              <tr
                key={`${row.studentId ?? "row"}-${index}`}
                className={cn("border-b border-border last:border-0", row.self && "bg-primary/10 font-medium")}
                aria-current={row.self ? "true" : undefined}
                data-testid="rank-row"
                data-self={row.self ? "true" : "false"}
              >
                <td className="px-4 py-2">
                  <RankBadge rank={row.rank} tied={isTied(data.rows, index)} />
                </td>
                {studentView ? null : <td className="px-4 py-2 font-mono text-muted-foreground">{row.indexNumber ?? DASH}</td>}
                <td className="px-4 py-2 text-foreground">
                  <span className="inline-flex flex-wrap items-center gap-2">
                    <span data-testid="rank-name">{row.name}</span>
                    {row.self ? <YouBadge /> : null}
                  </span>
                </td>
                <td className="px-4 py-2 whitespace-nowrap">
                  <TotalCell row={row} />
                </td>
                <td className="px-4 py-2 text-right tabular-nums" data-testid="rank-share">
                  {shareText(row.share)}
                </td>
                {showPerExam
                  ? perExamExams.map((exam) => (
                      <td key={exam.id} className="px-4 py-2 text-right text-muted-foreground tabular-nums" data-testid="rank-exam-marks">
                        {perExamMarks(row, exam.id)}
                      </td>
                    ))
                  : null}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ol className="flex flex-col gap-2 md:hidden" aria-label="Leaderboard ranks">
        {data.rows.map((row, index) => (
          <li
            key={`${row.studentId ?? "card"}-${index}`}
            className={cn("flex min-w-0 flex-col gap-2 rounded-lg border p-3", row.self ? "border-primary bg-primary/10" : "border-border")}
            aria-current={row.self ? "true" : undefined}
            data-testid="rank-card"
            data-self={row.self ? "true" : "false"}
          >
            <div className="flex min-w-0 items-center justify-between gap-2">
              <RankBadge rank={row.rank} tied={isTied(data.rows, index)} />
              {row.self ? <YouBadge /> : null}
            </div>
            <p className="min-w-0 font-medium break-words text-foreground">
              {row.name}
              {!studentView && row.indexNumber ? <span className="ml-2 font-mono text-xs text-muted-foreground">{row.indexNumber}</span> : null}
            </p>
            <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-xs text-muted-foreground">
              <dt className="font-medium text-foreground">Total</dt>
              <dd>
                <TotalCell row={row} />
              </dd>
              <dt className="font-medium text-foreground">Share</dt>
              <dd className="tabular-nums">{shareText(row.share)}</dd>
              {showPerExam
                ? perExamExams.map((exam) => (
                    <div key={exam.id} className="contents">
                      <dt className="min-w-0 font-medium break-words text-foreground">{exam.name}</dt>
                      <dd className="tabular-nums">{perExamMarks(row, exam.id)}</dd>
                    </div>
                  ))
                : null}
            </dl>
          </li>
        ))}
      </ol>
    </>
  );
}

function RanksSkeleton() {
  return (
    <div role="status" aria-busy="true" className="flex flex-col gap-2" data-testid="ranks-loading">
      <span className="sr-only">Loading ranks…</span>
      {Array.from({ length: 5 }, (_, index) => (
        <Skeleton key={index} className="h-10 w-full" />
      ))}
    </div>
  );
}

export interface RanksQueryLike {
  status: "pending" | "error" | "success";
  data: LeaderboardRanks | undefined;
  error: unknown;
  isFetching: boolean;
  isPlaceholderData: boolean;
  refetch: () => Promise<unknown>;
}

/**
 * Included exams + searchable, paginated ranks for one leaderboard. Shared by
 * the staff page and the student page; the query (staff or student endpoint)
 * is supplied by the caller.
 */
export function LeaderboardRanksView({
  query,
  search,
  onSearch,
  page,
  onPage,
  studentView = false,
  header,
}: {
  query: RanksQueryLike;
  search: string;
  onSearch: (value: string) => void;
  page: number;
  onPage: (page: number) => void;
  studentView?: boolean;
  header?: ReactNode;
}) {
  const searchId = useId();
  const [input, setInput] = useState(search);

  useEffect(() => {
    const handle = setTimeout(() => {
      if (input.trim() !== search.trim()) onSearch(input.trim());
    }, 300);
    return () => clearTimeout(handle);
  }, [input, search, onSearch]);

  if (query.status === "pending") return <RanksSkeleton />;
  if (query.status === "error") {
    if (isApiClientError(query.error) && query.error.status === 403) return <PermissionDeniedState error={query.error} />;
    if (isApiClientError(query.error) && query.error.status === 404) {
      return (
        <EmptyState
          title="Leaderboard not found"
          description={studentView ? "This leaderboard isn't available to you. It may have been unpublished or removed." : "This leaderboard may have been deleted."}
        />
      );
    }
    return <ErrorState message={mutationErrorMessage(query.error)} onRetry={() => void query.refetch()} />;
  }
  const data = query.data!;
  const total = data.totalElements;
  const size = data.size || RANKS_PAGE_SIZE;
  const lastPage = Math.max(0, Math.ceil(total / size) - 1);
  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min(total, page * size + data.rows.length);

  let body: ReactNode;
  if (data.rows.length === 0 && !search) {
    body = (
      <EmptyState
        title={data.hasPublishedMarks ? "No students to rank yet" : "No ranks yet"}
        description={
          data.hasPublishedMarks
            ? "No enrolled student has published marks in these exams yet."
            : "Ranks will appear once marks are entered."
        }
        icon={<Trophy className="size-8" aria-hidden="true" />}
      />
    );
  } else if (data.rows.length === 0) {
    body = (
      <EmptyState
        title="No students match your search"
        description={`Nobody on this leaderboard matches “${search}”. Try a different name${studentView ? "" : " or index number"}.`}
        action={{
          label: "Clear search",
          onClick: () => {
            setInput("");
            onSearch("");
          },
        }}
      />
    );
  } else {
    body = (
      <div aria-busy={query.isFetching} className={query.isPlaceholderData ? "opacity-70 transition-opacity" : undefined}>
        <RanksTable data={data} studentView={studentView} />
      </div>
    );
  }

  return (
    <div className="flex min-w-0 flex-col gap-4">
      {header}
      <IncludedExams exams={data.exams} />
      <section aria-labelledby="ranks-title" className="flex min-w-0 flex-col gap-3 rounded-xl bg-card p-3 shadow-sm ring-1 ring-foreground/10 [contain:inline-size] sm:p-4" data-testid="ranks">
        <div className="flex flex-col gap-3 border-b border-border pb-3 sm:flex-row sm:items-center sm:justify-between">
          <h2 id="ranks-title" className="flex items-center gap-2 text-lg font-semibold text-foreground">
            <Trophy className="size-5 text-amber-500" aria-hidden="true" />
            Ranks
          </h2>
          <div className="relative w-full sm:w-72">
            <label htmlFor={searchId} className="sr-only">
              Search students
            </label>
            <Input
              id={searchId}
              type="search"
              value={input}
              onChange={(event) => setInput(event.target.value)}
              placeholder="Search"
              className="h-9 pr-8"
              data-testid="ranks-search"
            />
            <Search className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
          </div>
        </div>
        {body}
        {total > 0 ? (
          <nav aria-label="Ranks pages" className="flex items-center justify-end gap-3 text-sm" data-testid="ranks-pagination">
            <p className="tabular-nums text-foreground" aria-live="polite" data-testid="ranks-range">
              {from}-{to} of {total}
            </p>
            <Button type="button" variant="ghost" size="icon-sm" onClick={() => onPage(page - 1)} disabled={page === 0} aria-label="Previous page" data-testid="ranks-prev">
              <ChevronLeft aria-hidden="true" />
            </Button>
            <Button type="button" variant="ghost" size="icon-sm" onClick={() => onPage(page + 1)} disabled={page >= lastPage} aria-label="Next page" data-testid="ranks-next">
              <ChevronRight aria-hidden="true" />
            </Button>
          </nav>
        ) : null}
      </section>
    </div>
  );
}
