"use client";

import { CheckCircle2, Clock, PauseCircle, Truck } from "lucide-react";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { cn } from "@/lib/utils";
import { isApiClientError } from "@/lib/api/error";
import { useMyCourseTutes, type StudentTute } from "@/lib/api/klass-student";
import { formatLessonDateTime } from "./student-format";

/**
 * "Tute Delivery" (TUTE-02): the student's own printed tutes of the course
 * and where each one is - the server's status only; the frontend just labels it.
 */
export function CourseTutesTab({ courseId, timeZone }: { courseId: string; timeZone: string | null }) {
  const query = useMyCourseTutes(courseId);

  if (query.status === "pending") return <LoadingState label="Loading your tutes…" />;
  if (query.status === "error") {
    return (
      <ErrorState
        message="We couldn't load your tutes for this course."
        code={isApiClientError(query.error) ? query.error.code : undefined}
        onRetry={() => query.refetch()}
      />
    );
  }

  const tutes = query.data;
  if (tutes.length === 0) {
    return (
      <div className="rounded-xl border border-border bg-card p-4 shadow-sm sm:p-6" data-testid="course-tutes-empty">
        <EmptyState
          icon={<Truck className="size-8" />}
          title="No tutes for this course yet"
          description="When your institute sends printed tutes, you can follow them here."
        />
      </div>
    );
  }

  return (
    <section
      aria-labelledby="course-tutes-heading"
      className="overflow-hidden rounded-xl border border-border bg-card shadow-sm"
      data-testid="course-tutes-panel"
    >
      <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-4 sm:px-6">
        <h2 id="course-tutes-heading" className="text-base font-semibold text-foreground">
          Your tutes
        </h2>
        <span className="rounded-full bg-blue-50 px-3 py-1 text-xs font-semibold text-blue-700 dark:bg-blue-950 dark:text-blue-200">
          {tutes.length} {tutes.length === 1 ? "tute" : "tutes"}
        </span>
      </div>
      <ul className="divide-y divide-border">
        {tutes.map((tute) => (
          <TuteRow key={tute.id} tute={tute} timeZone={timeZone} />
        ))}
      </ul>
    </section>
  );
}

type Tone = "pending" | "hold" | "done";

function describe(tute: StudentTute, timeZone: string | null): { label: string; detail: string; tone: Tone } {
  const collection = tute.method === "COLLECTION";
  if (tute.status === "DISPATCHED") {
    const when = tute.dispatchedAt ? formatLessonDateTime(tute.dispatchedAt, timeZone) : null;
    return collection
      ? { label: "Handed over", detail: when ? `Handed over on ${when}` : "Handed over at class", tone: "done" }
      : { label: "Dispatched", detail: when ? `Posted on ${when}` : "Posted to you", tone: "done" };
  }
  if (tute.status === "ON_HOLD") {
    return {
      label: "On hold",
      detail: "Your institute is checking the payment for this tute. Contact them if you have questions.",
      tone: "hold",
    };
  }
  return collection
    ? { label: "Pending", detail: "Collect this tute at class.", tone: "pending" }
    : { label: "Pending", detail: "Your institute will post this tute to you.", tone: "pending" };
}

const TONE_CLASS: Record<Tone, string> = {
  pending: "bg-amber-50 text-amber-800 dark:bg-amber-950 dark:text-amber-200",
  hold: "bg-red-50 text-red-700 dark:bg-red-950 dark:text-red-200",
  done: "bg-green-50 text-green-700 dark:bg-green-950 dark:text-green-200",
};

const TONE_ICON: Record<Tone, typeof Clock> = { pending: Clock, hold: PauseCircle, done: CheckCircle2 };

function TuteRow({ tute, timeZone }: { tute: StudentTute; timeZone: string | null }) {
  const { label, detail, tone } = describe(tute, timeZone);
  const Icon = TONE_ICON[tone];
  return (
    <li
      className="flex flex-col gap-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:px-6"
      data-testid="course-tute-row"
      data-status={tute.status}
    >
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className="text-sm font-medium break-words text-foreground">{tute.tuteName}</span>
        <span className="text-xs text-muted-foreground">
          {tute.method === "COLLECTION" ? "Collect at class" : "By post"} · {detail}
        </span>
      </div>
      <span
        className={cn(
          "inline-flex w-fit shrink-0 items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold",
          TONE_CLASS[tone]
        )}
        data-testid="course-tute-status"
      >
        <Icon className="size-3.5" aria-hidden="true" />
        {label}
      </span>
    </li>
  );
}
