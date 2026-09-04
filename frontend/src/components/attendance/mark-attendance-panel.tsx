"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
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
import { AttendanceSegmentedControl } from "@/components/attendance/attendance-status-chip";
import { useCourses, useCourseLessons, useCourseModules } from "@/lib/api/courses";
import {
  useMarkAttendance,
  useSessionRoster,
  type AttendanceMarkResultResponse,
  type AttendanceStatus,
} from "@/lib/api/attendance";
import { isApiClientError } from "@/lib/api/error";
import { shortId } from "@/lib/format";

/**
 * Shared Mark Attendance UI for both the Teacher (#1,
 * `app/(teacher)/teacher/attendance/mark/page.tsx`) and Staff (#5,
 * `app/(tenant-admin)/tenant-admin/attendance/mark/page.tsx`) screens — the
 * two differ only in role/route/nav, never in data source: both use the
 * exact same `useCourses()` hook (already server-filtered to the caller's
 * own courses for a Teacher, tenant-wide for staff — never fetch-then-
 * filter client-side, per `.claude/rules/ui-ux.md` §1), so one component
 * covers both per the plan's §11 note that screen #5 is "the same Mark
 * Attendance UI as #1 but with a tenant-wide course selector".
 *
 * Session-equivalent = `course_lesson.id` (no `class_session` table at this
 * MVP) — the lesson selector below is deliberately labeled "Session". Course
 * -> Module -> Session is a real cascade (there is no flat "all lessons for
 * a course" endpoint): each level is only fetched once its parent is chosen.
 */
export function MarkAttendancePanel({ dashboardHref }: { dashboardHref: string }) {
  const coursesQuery = useCourses();
  const [courseId, setCourseId] = useState("");
  const [moduleId, setModuleId] = useState("");
  const [sessionId, setSessionId] = useState("");
  const [overrides, setOverrides] = useState<Record<string, AttendanceStatus>>({});
  const [results, setResults] = useState<AttendanceMarkResultResponse[] | null>(null);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const modulesQuery = useCourseModules(courseId);
  const lessonsQuery = useCourseLessons(courseId, moduleId, { enabled: moduleId.length > 0 });
  const rosterQuery = useSessionRoster(sessionId);
  const markMutation = useMarkAttendance(sessionId);

  function handleCourseChange(value: string) {
    setCourseId(value);
    setModuleId("");
    setSessionId("");
    setOverrides({});
    setResults(null);
    setSubmitError(null);
  }

  function handleModuleChange(value: string) {
    setModuleId(value);
    setSessionId("");
    setOverrides({});
    setResults(null);
    setSubmitError(null);
  }

  function handleSessionChange(value: string) {
    setSessionId(value);
    setOverrides({});
    setResults(null);
    setSubmitError(null);
  }

  function handleMarkChange(studentId: string, status: AttendanceStatus) {
    setOverrides((prev) => ({ ...prev, [studentId]: status }));
  }

  async function handleSubmit() {
    const marks = Object.entries(overrides).map(([studentId, status]) => ({ studentId, status }));
    if (marks.length === 0) return;
    // Capture the session this submit is actually for — if the user
    // navigates to a different session while `mutateAsync` below is still
    // resolving (e.g. selects are re-enabled before this awaits settles),
    // the response is stale for whatever session is now selected and must
    // be discarded rather than painted onto the new session's roster.
    const submittedSessionId = sessionId;
    setSubmitError(null);
    try {
      const outcomes = await markMutation.mutateAsync({ marks });
      if (submittedSessionId !== sessionId) return;
      setResults(outcomes);
      // Clear only the succeeded rows' overrides — the roster refetch (via
      // `useMarkAttendance`'s cache invalidation) will reflect their saved
      // status. A failed row's attempted selection stays in `overrides` so
      // the user still sees what they tried alongside the failure reason.
      setOverrides((prev) => {
        const next = { ...prev };
        for (const outcome of outcomes) {
          if (outcome.success) delete next[outcome.studentId];
        }
        return next;
      });
    } catch (error) {
      if (submittedSessionId !== sessionId) return;
      setSubmitError(
        isApiClientError(error) ? error.message : "Something went wrong. Please try again."
      );
    }
  }

  const successCount = results?.filter((outcome) => outcome.success).length ?? 0;
  const failureCount = results?.filter((outcome) => !outcome.success).length ?? 0;
  const failuresByStudentId = new Map(
    (results ?? []).filter((outcome) => !outcome.success).map((outcome) => [outcome.studentId, outcome])
  );
  const pendingChangeCount = Object.keys(overrides).length;

  // A pure "N saved, 0 failed" outcome (and the pending/no-results states)
  // is announced through this single polite region — nothing is
  // interrupting, so there's no competing-announcement risk. The moment ANY
  // row fails, this stays silent and `failureAnnouncement` below takes over
  // instead: firing N per-row `role="alert"`s plus this polite summary at
  // the same moment is a known "announcement storm" that drops/overlaps
  // announcements for screen-reader users, so failures get exactly ONE
  // assertive region rather than N+1 competing ones.
  const saveAnnouncement = markMutation.isPending
    ? "Saving attendance…"
    : results === null || failureCount > 0
      ? ""
      : successCount > 0
        ? `${successCount} attendance record${successCount === 1 ? "" : "s"} saved.`
        : "";

  // Consolidated assertive failure summary — enumerates every failed row
  // using the same `shortId`-derived label the roster row below renders, so
  // a screen reader user gets one interrupting announcement naming every
  // failure instead of one per row. The roster rows' own inline failure text
  // stays visual-only (no `role="alert"` there) so it doesn't also fire.
  const failureAnnouncement =
    !markMutation.isPending && results !== null && failureCount > 0
      ? `${successCount} of ${results.length} saved. Failed: ${Array.from(failuresByStudentId.values())
          .map(
            (outcome) => `${shortId(outcome.studentId, "Student")} (${outcome.reason ?? "Unknown error."})`
          )
          .join(", ")}.`
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
                query={modulesQuery}
                loadingLabel="Loading modules…"
                loginPath="/login"
                permissionDenied={{ dashboardHref }}
                isEmpty={(modules) => modules.length === 0}
                // Same title as the lessons-cascade empty state below
                // ("no lessons exist for this course yet" is equally true
                // whether the course has no modules at all, or a module
                // with no lessons) — this is the plan's own literal
                // required copy for this case, not an accidental
                // duplicate; the description still distinguishes the two.
                emptyState={{
                  title: "No lessons exist for this course yet",
                  description:
                    "Add modules and lessons to this course before you can take attendance.",
                }}
              >
                {(modules) => (
                  <div className="flex flex-col gap-1.5 sm:max-w-sm">
                    <Label htmlFor="mark-attendance-module">Module</Label>
                    <Select
                      value={moduleId}
                      onValueChange={(value) => handleModuleChange(value ?? "")}
                      disabled={markMutation.isPending}
                    >
                      <SelectTrigger id="mark-attendance-module" className="w-full">
                        <SelectValue placeholder="Select a module">
                          {(selected: string | null) =>
                            selected
                              ? modules.find((module) => module.id === selected)?.title ?? selected
                              : "Select a module"
                          }
                        </SelectValue>
                      </SelectTrigger>
                      <SelectContent>
                        {modules.map((module) => (
                          <SelectItem key={module.id} value={module.id}>
                            {module.title}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>

                    {moduleId ? (
                      <div className="mt-4 flex flex-col gap-1.5">
                        <QueryStateBoundary
                          query={lessonsQuery}
                          loadingLabel="Loading sessions…"
                          loginPath="/login"
                          permissionDenied={{ dashboardHref }}
                          isEmpty={(lessons) => lessons.length === 0}
                          emptyState={{
                            title: "No lessons exist for this course yet",
                            description:
                              "This module has no lessons yet — choose a different module, or add lessons to this one before taking attendance.",
                          }}
                        >
                          {(lessons) => (
                            <>
                              <Label htmlFor="mark-attendance-session">Session</Label>
                              <Select
                                value={sessionId}
                                onValueChange={(value) => handleSessionChange(value ?? "")}
                                disabled={markMutation.isPending}
                              >
                                <SelectTrigger id="mark-attendance-session" className="w-full">
                                  <SelectValue placeholder="Select a session">
                                    {(selected: string | null) =>
                                      selected
                                        ? lessons.find((lesson) => lesson.id === selected)?.title ?? selected
                                        : "Select a session"
                                    }
                                  </SelectValue>
                                </SelectTrigger>
                                <SelectContent>
                                  {lessons.map((lesson) => (
                                    <SelectItem key={lesson.id} value={lesson.id}>
                                      {lesson.title}
                                    </SelectItem>
                                  ))}
                                </SelectContent>
                              </Select>
                            </>
                          )}
                        </QueryStateBoundary>
                      </div>
                    ) : null}
                  </div>
                )}
              </QueryStateBoundary>
            ) : null}
          </div>
        )}
      </QueryStateBoundary>

      {sessionId ? (
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
              <h2 className="text-base font-medium text-foreground">Roster</h2>

              {submitError ? (
                <Alert variant="destructive">
                  <AlertDescription>{submitError}</AlertDescription>
                </Alert>
              ) : null}

              <LiveRegion message={saveAnnouncement} />
              <LiveRegion message={failureAnnouncement} assertive />

              <ul className="flex flex-col gap-2">
                {roster.roster.map((entry) => {
                  const label = shortId(entry.studentId, "Student");
                  const currentValue = overrides[entry.studentId] ?? entry.status;
                  const failure = failuresByStudentId.get(entry.studentId);
                  return (
                    <li
                      key={entry.studentId}
                      className="flex flex-col gap-2 rounded-lg border border-border p-3 sm:flex-row sm:items-center sm:justify-between"
                    >
                      <span className="font-medium text-foreground">{label}</span>
                      <AttendanceSegmentedControl
                        studentLabel={label}
                        value={currentValue}
                        onChange={(status) => handleMarkChange(entry.studentId, status)}
                        disabled={markMutation.isPending}
                      />
                      {failure ? (
                        // Visual-only: the consolidated `failureAnnouncement`
                        // region above already covers this for screen
                        // readers (see its comment) — no `role="alert"`/
                        // `aria-live` here, so this doesn't also fire its own
                        // competing assertive announcement per row.
                        <p className="text-xs text-destructive sm:basis-full">
                          Could not save {label}: {failure.reason ?? "Unknown error."}
                        </p>
                      ) : null}
                    </li>
                  );
                })}
              </ul>

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
            </div>
          )}
        </QueryStateBoundary>
      ) : null}
    </div>
  );
}
