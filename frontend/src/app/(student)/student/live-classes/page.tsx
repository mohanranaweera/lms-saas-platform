"use client";

import { useMemo, useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { Tabs } from "@/components/ui/tabs";
import { ClassSessionStatusBadge } from "@/components/live-classes/class-session-status-badge";
import { useMyEnrolledCourseSummaries } from "@/lib/api/enrollments";
import {
  useClassSessionRecording,
  useClassSessions,
  useJoinClassSession,
  type ClassSessionResponse,
} from "@/lib/api/class-sessions";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime, shortId } from "@/lib/format";

function StudentJoinAction({ session }: { session: ClassSessionResponse }) {
  const mutation = useJoinClassSession(session.id);
  const [error, setError] = useState<string | null>(null);
  const canJoin = session.status === "LIVE" && session.providerStatus === "PROVISIONED";

  const disabledReason =
    session.status === "SCHEDULED"
      ? "Not started yet"
      : session.status === "LIVE"
        ? "The meeting is still being set up — try again shortly"
        : null;

  return (
    <div className="flex flex-col items-start gap-1">
      <Button
        type="button"
        size="sm"
        disabled={!canJoin || mutation.isPending}
        aria-busy={mutation.isPending}
        aria-describedby={!canJoin ? `student-live-class-${session.id}-join-reason` : undefined}
        onClick={async () => {
          setError(null);
          try {
            const result = await mutation.mutateAsync();
            window.open(result.joinUrl, "_blank", "noopener,noreferrer");
          } catch (err) {
            setError(isApiClientError(err) ? err.message : "Could not join the class. Please try again.");
          }
        }}
      >
        {mutation.isPending ? "Joining…" : "Join"}
      </Button>
      {!canJoin && disabledReason ? (
        <span id={`student-live-class-${session.id}-join-reason`} className="text-xs text-muted-foreground">
          {disabledReason}
        </span>
      ) : null}
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function StudentRecordingAction({ session }: { session: ClassSessionResponse }) {
  const mutation = useClassSessionRecording(session.id);
  const [error, setError] = useState<string | null>(null);

  return (
    <div className="flex flex-col items-start gap-1">
      <Button
        type="button"
        variant="outline"
        size="sm"
        disabled={mutation.isPending}
        aria-busy={mutation.isPending}
        onClick={async () => {
          setError(null);
          try {
            const result = await mutation.mutateAsync();
            window.open(result.playbackUrl, "_blank", "noopener,noreferrer");
          } catch (err) {
            setError(
              isApiClientError(err) ? err.message : "Could not load the recording. Please try again."
            );
          }
        }}
      >
        {mutation.isPending ? "Preparing…" : "Watch recording"}
      </Button>
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function LiveClassRow({
  session,
  courseName,
  action,
}: {
  session: ClassSessionResponse;
  courseName: string;
  action: ReactNode;
}) {
  return (
    <li className="flex flex-col gap-2 rounded-lg border border-border p-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-foreground">{session.title}</span>
          <ClassSessionStatusBadge status={session.status} />
        </div>
        <span className="text-xs text-muted-foreground">
          {courseName} · {formatDateTime(session.scheduledStart)} – {formatDateTime(session.scheduledEnd)}
        </span>
      </div>
      {action}
    </li>
  );
}

/**
 * Student "Live Classes" (Wave 4 plan §5) — Upcoming/Past tabs. `GET
 * /v1/class-sessions` is already entitlement-filtered server-side to only
 * this student's own currently-ACTIVE enrollments (per
 * `ClassSessionService#listSessions`) — the Upcoming/Past split below is a
 * purely presentational client-side split over that already-scoped set
 * (status-based, not a further access filter), per
 * `.claude/rules/ui-ux.md` §1.
 */
export default function StudentLiveClassesPage() {
  const courseSummariesQuery = useMyEnrolledCourseSummaries();
  const query = useClassSessions();
  // `Tabs` only tracks active-tab state itself when used in controlled mode
  // (`value`/`onValueChange`) — `defaultValue` alone renders the initial tab
  // but never updates on click, per that component's own doc comment on how
  // its existing callers use it.
  const [activeTab, setActiveTab] = useState("upcoming");

  const courseNameById = useMemo(
    () => new Map((courseSummariesQuery.data ?? []).map((course) => [course.id, course.name])),
    [courseSummariesQuery.data]
  );

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Live Classes</h1>
        <p className="text-sm text-muted-foreground">
          Live class sessions for the courses you&apos;re currently enrolled in.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading your live classes…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
      >
        {(sessions) => {
          const upcoming = sessions.filter((s) => s.status === "SCHEDULED" || s.status === "LIVE");
          const past = sessions.filter((s) => s.status === "COMPLETED" || s.status === "CANCELLED");

          return (
            <Tabs
              aria-label="Live classes"
              value={activeTab}
              onValueChange={setActiveTab}
              items={[
                {
                  value: "upcoming",
                  label: "Upcoming",
                  content:
                    upcoming.length === 0 ? (
                      <EmptyState
                        title="No upcoming live classes"
                        description="Once a teacher schedules a live class for one of your enrolled courses, it will appear here."
                      />
                    ) : (
                      <ul className="flex flex-col gap-2">
                        {upcoming.map((session) => (
                          <LiveClassRow
                            key={session.id}
                            session={session}
                            courseName={courseNameById.get(session.courseId) ?? shortId(session.courseId, "Course")}
                            action={<StudentJoinAction session={session} />}
                          />
                        ))}
                      </ul>
                    ),
                },
                {
                  value: "past",
                  label: "Past",
                  content:
                    past.length === 0 ? (
                      <EmptyState
                        title="No past live classes"
                        description="Completed and cancelled live classes for your enrolled courses will appear here."
                      />
                    ) : (
                      <ul className="flex flex-col gap-2">
                        {past.map((session) => (
                          <LiveClassRow
                            key={session.id}
                            session={session}
                            courseName={courseNameById.get(session.courseId) ?? shortId(session.courseId, "Course")}
                            action={
                              session.status === "COMPLETED" ? <StudentRecordingAction session={session} /> : null
                            }
                          />
                        ))}
                      </ul>
                    ),
                },
              ]}
            />
          );
        }}
      </QueryStateBoundary>
    </div>
  );
}
