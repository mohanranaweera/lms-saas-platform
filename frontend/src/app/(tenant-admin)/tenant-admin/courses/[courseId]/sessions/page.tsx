"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { buttonVariants } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { CourseWorkspaceShell } from "@/components/courses/course-workspace-shell";
import { ClassSessionStatusBadge } from "@/components/live-classes/class-session-status-badge";
import { ProviderStatusBadge } from "@/components/live-classes/provider-status-badge";
import { useClassSessions } from "@/lib/api/class-sessions";
import { formatDateTime } from "@/lib/format";

/**
 * Tenant Admin course workspace — Sessions tab (Wave 4). Unblocks the
 * Wave 2 structural placeholder noted in
 * `implementation-roadmap.md` §8 item 4 (PAR-05-06) — "blocked on Live
 * Sessions (Wave 4)". Scoped to THIS course only (`courseId` passed as a
 * server-side filter on the already tenant-wide-entitled
 * `GET /v1/class-sessions` read for a staff caller with `LIVE_CLASSES`/
 * `VIEW` — never a client-side re-filter over an unscoped fetch). Read-only
 * here (list + link to the full oversight detail/retry action at
 * `/tenant-admin/live-classes/[sessionId]`) — Schedule/Recordings/Analytics
 * remain untouched placeholders (`CourseWorkspacePlaceholder`), out of this
 * wave's explicit scope.
 */
export default function TenantAdminCourseSessionsPage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;

  return (
    <CourseWorkspaceShell
      courseId={courseId}
      basePath={`/tenant-admin/courses/${courseId}`}
      dashboardHref="/tenant-admin/dashboard"
    >
      {() => <CourseSessionsList courseId={courseId} />}
    </CourseWorkspaceShell>
  );
}

function CourseSessionsList({ courseId }: { courseId: string }) {
  const query = useClassSessions({ courseId });

  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading live class sessions…"
      loginPath="/login"
      permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      isEmpty={(data) => data.length === 0}
      emptyState={{
        title: "No live class sessions for this course yet",
        description: "Live classes scheduled by this course's teacher will appear here.",
      }}
    >
      {(sessions) => (
        <ul className="flex flex-col gap-2">
          {sessions.map((session) => (
            <li
              key={session.id}
              className="flex flex-col gap-2 rounded-lg border border-border p-4 sm:flex-row sm:items-center sm:justify-between"
            >
              <div className="flex flex-col gap-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-medium text-foreground">{session.title}</span>
                  <ClassSessionStatusBadge status={session.status} />
                  {session.providerStatus !== "PROVISIONED" ? (
                    <ProviderStatusBadge status={session.providerStatus} />
                  ) : null}
                </div>
                <span className="text-xs text-muted-foreground">
                  {formatDateTime(session.scheduledStart)} – {formatDateTime(session.scheduledEnd)}
                </span>
              </div>
              <Link
                href={`/tenant-admin/live-classes/${session.id}`}
                className={buttonVariants({ variant: "outline", size: "sm" })}
              >
                View
              </Link>
            </li>
          ))}
        </ul>
      )}
    </QueryStateBoundary>
  );
}
