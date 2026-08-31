"use client";

import { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { ReactivationStatusBadge } from "@/components/students/enrollment-status-badges";
import { formatDateTime, shortId } from "@/lib/format";
import { useStudent } from "@/lib/api/students";
import {
  useReactivationQueue,
  type ReactivationRequestResponse,
  type ReactivationRequestStatus,
} from "@/lib/api/enrollments";

const PAGE_SIZE = 20;

/** `"all"` is a client-only sentinel mapping to `status: undefined` — unlike the slip queue, the backend genuinely supports "every status", so this is a real option, not a fabricated one. */
type StatusFilter = "all" | ReactivationRequestStatus;

const STATUS_FILTER_OPTIONS: Array<{ value: StatusFilter; label: string }> = [
  { value: "SUBMITTED", label: "Submitted" },
  { value: "APPROVED", label: "Approved" },
  { value: "REJECTED", label: "Rejected" },
  { value: "all", label: "All" },
];

/**
 * Resolves a student id to an email for display, degrading gracefully to the
 * raw id while the lookup is pending or if it fails — this queue is small and
 * tenant-scoped, so a per-row lookup (rather than a batched/joined response)
 * is acceptable here, mirroring `PaymentSlipResponse.studentEmail` being
 * pre-joined server-side elsewhere, except there is no server-side join for
 * this endpoint so the client performs it instead.
 */
function RequestedByEmail({ studentId }: { studentId: string }) {
  const query = useStudent(studentId);
  if (query.status === "success") return <>{query.data.email}</>;
  return <>{studentId}</>;
}

/**
 * Tenant Admin Reactivation Approvals queue (MVP-012). `GET
 * /api/v1/reactivation-requests` — server-enforced `ACCESS_EXPIRY`/`VIEW`
 * (Tenant Admin, Finance Staff, Student Support, Read-only Auditor; 403 for
 * a student or an unlisted staff role). This page issues the real request
 * unconditionally and lets `QueryStateBoundary` render
 * `PermissionDeniedState` on an actual 403 — the nav entry
 * (`tenant-admin-nav.tsx`, gated on `canViewAccessExpiryQueue`) is pure UX
 * convenience only.
 *
 * Defaults the UI filter to `SUBMITTED` (the practically useful default —
 * mirrors the slip queue's "pending first" UX) but "All" is a real,
 * selectable backend option here (`status` omitted entirely), unlike the
 * slip queue's fabricated "pending" sentinel.
 *
 * No course name is resolvable from `ReactivationRequestResponse` (it has no
 * `courseId` field at all) — each row renders an enrollment id fragment
 * instead (`shortId(..., "Enrollment")`), per the approved MVP-012 workaround.
 */
export default function ReactivationApprovalsQueuePage() {
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("SUBMITTED");
  const [page, setPage] = useState(0);
  const query = useReactivationQueue({
    status: statusFilter === "all" ? undefined : statusFilter,
    page,
    size: PAGE_SIZE,
  });

  function handleStatusChange(value: string) {
    setStatusFilter(value as StatusFilter);
    setPage(0);
  }

  function resetFilters() {
    setStatusFilter("SUBMITTED");
    setPage(0);
  }

  const columns: DataTableColumn<ReactivationRequestResponse>[] = [
    {
      key: "enrollment",
      header: "Enrollment",
      cell: (row) => shortId(row.enrollmentId, "Enrollment"),
      hideOnCard: true,
    },
    {
      key: "requestedBy",
      header: "Requested by",
      cell: (row) => <RequestedByEmail studentId={row.requestedBy} />,
    },
    {
      key: "status",
      header: "Status",
      cell: (row) => <ReactivationStatusBadge status={row.status} />,
      hideOnCard: true,
    },
    {
      key: "createdAt",
      header: "Submitted",
      cell: (row) => formatDateTime(row.createdAt),
    },
    {
      key: "view",
      header: "View",
      cell: (row) => (
        <Link
          href={`/tenant-admin/access-expiry/reactivation-approvals/${row.id}`}
          aria-label={`View reactivation request for ${shortId(row.enrollmentId, "Enrollment")}`}
          className="text-sm font-medium text-foreground hover:underline"
        >
          View
        </Link>
      ),
      hideOnCard: true,
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Reactivation approvals</h1>
        <p className="text-sm text-muted-foreground">
          Review student requests to reactivate an expired course. Approving a request lets the
          student place a new order — it never activates access by itself.
        </p>
      </div>

      <div className="flex flex-col gap-1.5 sm:w-56">
        <Label htmlFor="reactivation-queue-status">Status</Label>
        <Select
          value={statusFilter}
          onValueChange={(value) => handleStatusChange(value ?? "SUBMITTED")}
        >
          <SelectTrigger id="reactivation-queue-status" className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            {STATUS_FILTER_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading reactivation requests…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => statusFilter === "SUBMITTED" && page === 0 && data.content.length === 0}
        emptyState={{
          title: "No pending reactivation requests",
          description:
            "Once a student requests reactivation for an expired course, it will appear here for review.",
        }}
      >
        {(data) => (
          <div className="flex flex-col gap-4">
            {data.content.length === 0 ? (
              <EmptyState
                title="No requests match your filter"
                description="Try a different status filter or go back to an earlier page."
                action={{ label: "Reset filters", onClick: resetFilters }}
              />
            ) : (
              <DataTable
                columns={columns}
                rows={data.content}
                rowKey={(row) => row.id}
                caption="Reactivation approvals queue"
                cardHeading={(row) => shortId(row.enrollmentId, "Enrollment")}
                cardHeadingAdornment={(row) => <ReactivationStatusBadge status={row.status} />}
                cardFooter={(row) => (
                  <Link
                    href={`/tenant-admin/access-expiry/reactivation-approvals/${row.id}`}
                    aria-label={`View reactivation request for ${shortId(row.enrollmentId, "Enrollment")}`}
                    className="text-sm font-medium text-foreground hover:underline"
                  >
                    View
                  </Link>
                )}
              />
            )}
            <div className="flex items-center justify-between">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setPage((current) => Math.max(0, current - 1))}
                disabled={page === 0}
              >
                Previous
              </Button>
              <span className="text-xs text-muted-foreground">
                Page {data.page + 1} of {Math.max(data.totalPages, 1)}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setPage((current) => current + 1)}
                disabled={data.page + 1 >= data.totalPages}
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
