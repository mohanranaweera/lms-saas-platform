"use client";

import { useParams } from "next/navigation";
import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { ReactivationStatusBadge } from "@/components/students/enrollment-status-badges";
import { formatDateTime, shortId } from "@/lib/format";
import { useAuth } from "@/lib/auth/auth-context";
import { canApproveReactivation } from "@/lib/auth/permissions";
import { useStudent } from "@/lib/api/students";
import { useReactivationRequest } from "@/lib/api/enrollments";
import { ApproveReactivationDialog } from "./approve-reactivation-dialog";
import { RejectReactivationDialog } from "./reject-reactivation-dialog";

/** Resolves a student id to an email, degrading gracefully to the raw id while pending/on failure — see `RequestedByEmail` in the queue page for the same rationale. */
function ResolvedStudentEmail({ studentId }: { studentId: string }) {
  const query = useStudent(studentId);
  if (query.status === "success") return <>{query.data.email}</>;
  return <>{studentId}</>;
}

/**
 * Tenant Admin Reactivation Request Detail (MVP-012). `GET
 * /api/v1/reactivation-requests/{id}` — owner student OR staff
 * `ACCESS_EXPIRY`/`VIEW` (404 anti-enumeration for anyone else, never 403).
 *
 * No `courseId` exists anywhere on `ReactivationRequestResponse` — only
 * `enrollmentId` — so this page renders an enrollment id fragment in place of
 * a course name, per the approved MVP-012 workaround; it never calls
 * `GET /api/v1/courses/{id}` (there is no courseId to look up with anyway).
 *
 * Approve/Reject actions render only when `canApproveReactivation(role)` is
 * true (Tenant Admin only) AND `status === "SUBMITTED"` — both UX convenience
 * only; the mutation endpoints independently 403/409 otherwise, mirroring
 * `slip-review/[slipId]/page.tsx`'s exact gating discipline. A terminal
 * `APPROVED`/`REJECTED` request shows no actions at all — there is no
 * reversal endpoint.
 */
export default function ReactivationRequestDetailPage() {
  const params = useParams<{ id: string }>();
  const id = params.id;
  const { session } = useAuth();
  const canApprove = canApproveReactivation(session?.role ?? null);

  const query = useReactivationRequest(id);

  return (
    <div className="mx-auto flex w-full max-w-2xl flex-col gap-6">
      <div>
        <Link
          href="/tenant-admin/access-expiry/reactivation-approvals"
          className="inline-flex items-center gap-1.5 text-sm font-medium text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft className="size-4" aria-hidden="true" />
          Back to reactivation approvals
        </Link>
      </div>

      <div>
        <h1 className="text-xl font-semibold text-foreground">Reactivation request detail</h1>
        <p className="text-sm text-muted-foreground">
          Approving lets the student place a new order for this course — it never activates
          access by itself.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading reactivation request…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(reactivationRequest) => (
          <div className="flex flex-col gap-6">
            <div className="flex flex-col gap-4 rounded-lg border border-border p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-lg font-medium text-foreground">
                  {shortId(reactivationRequest.enrollmentId, "Enrollment")}
                </h2>
                <ReactivationStatusBadge status={reactivationRequest.status} />
              </div>

              <dl className="grid grid-cols-1 gap-x-4 gap-y-2 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Request id</dt>
                  <dd className="text-foreground">{reactivationRequest.id}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Enrollment id</dt>
                  <dd className="text-foreground">{reactivationRequest.enrollmentId}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Requested by</dt>
                  <dd className="text-foreground">
                    <ResolvedStudentEmail studentId={reactivationRequest.requestedBy} />
                  </dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">New order</dt>
                  <dd className="text-foreground">{reactivationRequest.newOrderId ?? "—"}</dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Submitted</dt>
                  <dd className="text-foreground">
                    {formatDateTime(reactivationRequest.createdAt)}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Last updated</dt>
                  <dd className="text-foreground">
                    {formatDateTime(reactivationRequest.updatedAt)}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Reviewed</dt>
                  <dd className="text-foreground">
                    {reactivationRequest.reviewedAt
                      ? formatDateTime(reactivationRequest.reviewedAt)
                      : "Not yet reviewed"}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Reviewed by</dt>
                  {/* The reviewer is a staff/Tenant Admin user, not a student
                      — `useStudent` (STUDENTS/VIEW) has no lookup for staff
                      accounts, so this renders the raw id rather than
                      attempting (and always failing) a student lookup. */}
                  <dd className="text-foreground">{reactivationRequest.reviewedBy ?? "—"}</dd>
                </div>
              </dl>
            </div>

            {canApprove && reactivationRequest.status === "SUBMITTED" ? (
              <div className="flex flex-wrap gap-2">
                <ApproveReactivationDialog reactivationRequest={reactivationRequest} />
                <RejectReactivationDialog reactivationRequest={reactivationRequest} />
              </div>
            ) : null}
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
