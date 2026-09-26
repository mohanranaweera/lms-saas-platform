"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { LiveRegion } from "@/components/ui/live-region";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import {
  AttendanceSegmentedControl,
  AttendanceStatusChip,
} from "@/components/attendance/attendance-status-chip";
import {
  CLASS_SESSION_STATUS_LABELS,
  ClassSessionStatusBadge,
} from "@/components/live-classes/class-session-status-badge";
import { useCourses } from "@/lib/api/courses";
import { useClassSessions, type ClassSessionResponse } from "@/lib/api/class-sessions";
import {
  useClassSessionRoster,
  useMarkClassSessionAttendance,
  type AttendanceMarkResultResponse,
  type AttendanceStatus,
} from "@/lib/api/attendance";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime, shortId } from "@/lib/format";

function sessionOptionLabel(session: ClassSessionResponse): string {
  return `${session.title} — ${formatDateTime(session.scheduledStart)} (${CLASS_SESSION_STATUS_LABELS[session.status]})`;
}

/**
 * Shared Mark Attendance UI for the Teacher
 * (`app/(teacher)/teacher/attendance/mark/page.tsx`) and Staff
 * (`app/(tenant-admin)/tenant-admin/attendance/mark/page.tsx`) screens. Wave
 * 8 workflow (master instruction §22): select Course → select Class Session
 * → load authorized roster → mark → save.
 *
 * Both lists come from the backend already role-scoped — `useCourses()`
 * (Teacher: own courses; staff: tenant-wide) and `useClassSessions({courseId})`
 * — never fetched unfiltered and filtered here (`.claude/rules/ui-ux.md` §1).
 *
 * The roster read reports `markingOpen` (the backend session-lifecycle
 * gate). When it is closed (cancelled / not started yet) the controls are
 * disabled and the reason is shown, but that is display only — the mark
 * endpoint re-enforces the gate (409), which this panel surfaces as an error.
 * Students who were marked earlier but are no longer enrolled stay visible
 * (historical marks are never hidden) as read-only rows.
 */
export function MarkAttendancePanel({ dashboardHref }: { dashboardHref: string }) {
  const coursesQuery = useCourses();
  const [courseId, setCourseId] = useState("");
  const [classSessionId, setClassSessionId] = useState("");
  const [overrides, setOverrides] = useState<Record<string, AttendanceStatus>>({});
  const [results, setResults] = useState<AttendanceMarkResultResponse[] | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const sessionsQuery = useClassSessions({ courseId }, { enabled: courseId.length > 0 });
  const rosterQuery = useClassSessionRoster(classSessionId);
  const markMutation = useMarkClassSessionAttendance(classSessionId);

  // Most recent first — the class a teacher is most likely recording is today's/the latest one.
  const sortedSessions = useMemo(
    () =>
      [...(sessionsQuery.data ?? [])].sort((a, b) => b.scheduledStart.localeCompare(a.scheduledStart)),
    [sessionsQuery.data]
  );

  function resetMarks() {
    setOverrides({});
    setResults(null);
    setSubmitError(null);
  }

  function handleCourseChange(value: string) {
    setCourseId(value);
    setClassSessionId("");
    resetMarks();
  }

  function handleSessionChange(value: string) {
    setClassSessionId(value);
    resetMarks();
  }

  function handleMarkChange(studentId: string, status: AttendanceStatus) {
    setOverrides((prev) => ({ ...prev, [studentId]: status }));
  }

  async function handleSubmit() {
    const marks = Object.entries(overrides).map(([studentId, status]) => ({ studentId, status }));
    if (marks.length === 0) return;
    // Discard a response that arrives after the user switched to another session.
    const submittedSessionId = classSessionId;
    setSubmitError(null);
    try {
      const outcomes = await markMutation.mutateAsync({ marks });
      if (submittedSessionId !== classSessionId) return;
      setResults(outcomes);
      // Keep only failed rows' attempted selections so the user sees what failed and why.
      setOverrides((prev) => {
        const next = { ...prev };
        for (const outcome of outcomes) {
          if (outcome.success) delete next[outcome.studentId];
        }
        return next;
      });
    } catch (error) {
      if (submittedSessionId !== classSessionId) return;
      setSubmitError(isApiClientError(error) ? error.message : "Something went wrong. Please try again.");
    }
  }

  const successCount = results?.filter((outcome) => outcome.success).length ?? 0;
  const failureCount = results?.filter((outcome) => !outcome.success).length ?? 0;
  const failuresByStudentId = new Map(
    (results ?? []).filter((outcome) => !outcome.success).map((outcome) => [outcome.studentId, outcome])
  );
  const pendingChangeCount = Object.keys(overrides).length;

  // One polite region for pure success, ONE assertive region enumerating every
  // failure — never N per-row alerts competing with a summary (announcement storm).
  const saveAnnouncement = markMutation.isPending
    ? "Saving attendance…"
    : results === null || failureCount > 0
      ? ""
      : successCount > 0
        ? `${successCount} attendance record${successCount === 1 ? "" : "s"} saved.`
        : "";

  return (
    <div className="flex flex-col gap-6">
      <QueryStateBoundary
        query={coursesQuery}
        loadingLabel="Loading courses…"
        loginPath="/login"
        permissionDenied={{ dashboardHref }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={{
          title: "No courses available",
          description: "There are no courses available to take attendance for yet.",
        }}
      >
        {(coursesData) => (
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5 sm:max-w-sm">
              <Label htmlFor="mark-attendance-course">Course</Label>
              <Select
                value={courseId}
                onValueChange={(value) => handleCourseChange(value ?? "")}
                disabled={markMutation.isPending}
              >
                <SelectTrigger id="mark-attendance-course" className="w-full">
                  <SelectValue placeholder="Select a course">
                    {(selected: string | null) =>
                      selected
                        ? coursesData.content.find((course) => course.id === selected)?.name ?? selected
                        : "Select a course"
                    }
                  </SelectValue>
                </SelectTrigger>
                <SelectContent>
                  {coursesData.content.map((course) => (
                    <SelectItem key={course.id} value={course.id}>
                      {course.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            {courseId ? (
              <QueryStateBoundary
                query={sessionsQuery}
                loadingLabel="Loading class sessions…"
                loginPath="/login"
                permissionDenied={{ dashboardHref }}
                isEmpty={(sessions) => sessions.length === 0}
                emptyState={{
                  title: "No class sessions scheduled for this course",
                  description:
                    "Attendance is recorded per class session. Schedule a session under Live Classes first, then return here to take attendance.",
                }}
              >
                {() => (
                  <div className="flex flex-col gap-1.5 sm:max-w-md">
                    <Label htmlFor="mark-attendance-session">Class session</Label>
                    <Select
                      value={classSessionId}
                      onValueChange={(value) => handleSessionChange(value ?? "")}
                      disabled={markMutation.isPending}
                    >
                      <SelectTrigger id="mark-attendance-session" className="w-full">
                        <SelectValue placeholder="Select a class session">
                          {(selected: string | null) => {
                            const match = sortedSessions.find((session) => session.id === selected);
                            return match ? sessionOptionLabel(match) : "Select a class session";
                          }}
                        </SelectValue>
                      </SelectTrigger>
                      <SelectContent>
                        {sortedSessions.map((session) => (
                          <SelectItem key={session.id} value={session.id}>
                            {sessionOptionLabel(session)}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>
                )}
              </QueryStateBoundary>
            ) : null}
          </div>
        )}
      </QueryStateBoundary>

      {classSessionId ? (
        <QueryStateBoundary
          query={rosterQuery}
          loadingLabel="Loading roster…"
          loginPath="/login"
          permissionDenied={{ dashboardHref }}
          isEmpty={(roster) => roster.roster.length === 0}
          emptyState={{
            title: "No students enrolled",
            description:
              "This course currently has no active enrollments, so there is nothing to mark attendance for.",
          }}
        >
          {(roster) => (
            <div className="flex flex-col gap-4">
              <div className="flex flex-col gap-1 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <h2 className="text-base font-medium text-foreground">{roster.title}</h2>
                  <p className="text-xs text-muted-foreground">
                    {formatDateTime(roster.scheduledStart)} – {formatDateTime(roster.scheduledEnd)}
                  </p>
                </div>
                <ClassSessionStatusBadge status={roster.sessionStatus} />
              </div>

              {!roster.markingOpen ? (
                <Alert>
                  <AlertDescription>
                    {roster.markingClosedReason ?? "Attendance cannot be recorded for this class session."}
                  </AlertDescription>
                </Alert>
              ) : null}

              {submitError ? (
                <Alert variant="destructive">
                  <AlertDescription>{submitError}</AlertDescription>
                </Alert>
              ) : null}

              <LiveRegion message={saveAnnouncement} />
              <LiveRegion
                message={
                  !markMutation.isPending && results !== null && failureCount > 0
                    ? `${successCount} of ${results.length} saved. Failed: ${Array.from(failuresByStudentId.values())
                        .map((outcome) => {
                          const entry = roster.roster.find((row) => row.studentId === outcome.studentId);
                          const label = entry?.studentName ?? shortId(outcome.studentId, "Student");
                          return `${label} (${outcome.reason ?? "Unknown error."})`;
                        })
                        .join(", ")}.`
                    : ""
                }
                assertive
              />

              <ul className="flex flex-col gap-2" aria-label="Roster">
                {roster.roster.map((entry) => {
                  const label = entry.studentName ?? shortId(entry.studentId, "Student");
                  const currentValue = overrides[entry.studentId] ?? entry.status;
                  const failure = failuresByStudentId.get(entry.studentId);
                  const editable = roster.markingOpen && entry.currentlyEnrolled;
                  return (
                    <li
                      key={entry.studentId}
                      className="flex flex-col gap-2 rounded-lg border border-border p-3 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between"
                    >
                      <span className="flex items-center gap-2 font-medium text-foreground">
                        {label}
                        {!entry.currentlyEnrolled ? (
                          <Badge variant="outline">No longer enrolled</Badge>
                        ) : null}
                      </span>
                      {editable ? (
                        <AttendanceSegmentedControl
                          studentLabel={label}
                          value={currentValue}
                          onChange={(status) => handleMarkChange(entry.studentId, status)}
                          disabled={markMutation.isPending}
                        />
                      ) : (
                        <AttendanceStatusChip status={entry.status} />
                      )}
                      {failure ? (
                        // Visual only — the single assertive region above announces failures.
                        <p className="text-xs text-destructive sm:basis-full">
                          Could not save {label}: {failure.reason ?? "Unknown error."}
                        </p>
                      ) : null}
                    </li>
                  );
                })}
              </ul>

              {roster.markingOpen ? (
                <Button
                  type="button"
                  onClick={handleSubmit}
                  disabled={markMutation.isPending || pendingChangeCount === 0}
                  aria-busy={markMutation.isPending}
                  className="self-start"
                >
                  {markMutation.isPending
                    ? "Saving…"
                    : `Save attendance${pendingChangeCount > 0 ? ` (${pendingChangeCount})` : ""}`}
                </Button>
              ) : null}
            </div>
          )}
        </QueryStateBoundary>
      ) : null}
    </div>
  );
}
