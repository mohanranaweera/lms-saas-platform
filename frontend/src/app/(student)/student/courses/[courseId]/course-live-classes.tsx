"use client";

import { useState, type ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { Tabs } from "@/components/ui/tabs";
import { ClassSessionStatusBadge } from "@/components/live-classes/class-session-status-badge";
import {
  useClassSessionRecording,
  useClassSessions,
  useJoinClassSession,
  type ClassSessionResponse,
} from "@/lib/api/class-sessions";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";

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
        aria-describedby={
          !canJoin ? `student-live-class-${session.id}-join-reason` : `student-live-class-${session.id}-join-privacy`
        }
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
      {canJoin ? (
        <span id={`student-live-class-${session.id}-join-privacy`} className="text-xs text-muted-foreground">
          Joining shares your name and email with this class&apos;s Zoom meeting.
        </span>
      ) : null}
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

function LiveClassRow({ session, action }: { session: ClassSessionResponse; action: ReactNode }) {
  return (
    <li className="flex flex-col gap-2 rounded-lg border border-border p-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-foreground">{session.title}</span>
          <ClassSessionStatusBadge status={session.status} />
        </div>
        <span className="text-xs text-muted-foreground">
          {formatDateTime(session.scheduledStart)} – {formatDateTime(session.scheduledEnd)}
        </span>
      </div>
      {action}
    </li>
  );
}

/**
 * Live classes for one course, shown inside the Student course content page
 * (there is no separate Student "Live Classes" page). `GET
 * /v1/class-sessions?courseId=` is entitlement-filtered server-side to this
 * student's own currently-ACTIVE enrollments (`ClassSessionService#listSessions`);
 * `courseId` only narrows that already-scoped set, and the Upcoming/Past split
 * is purely presentational.
 */
export function CourseLiveClasses({ courseId }: { courseId: string }) {
  const query = useClassSessions({ courseId });
  // `Tabs` only tracks active-tab state in controlled mode.
  const [activeTab, setActiveTab] = useState("upcoming");

  return (
    <section className="flex flex-col gap-3" aria-labelledby="course-live-classes-heading">
      <h2 id="course-live-classes-heading" className="text-base font-semibold text-foreground">
        Live classes
      </h2>
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading live classes…"
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
                        description="When your teacher schedules a live class for this course, it will appear here."
                      />
                    ) : (
                      <ul className="flex flex-col gap-2">
                        {upcoming.map((session) => (
                          <LiveClassRow
                            key={session.id}
                            session={session}
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
                        description="Completed and cancelled live classes for this course will appear here."
                      />
                    ) : (
                      <ul className="flex flex-col gap-2">
                        {past.map((session) => (
                          <LiveClassRow
                            key={session.id}
                            session={session}
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
    </section>
  );
}
