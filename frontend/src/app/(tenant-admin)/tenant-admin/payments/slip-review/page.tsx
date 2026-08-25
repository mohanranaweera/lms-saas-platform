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
import { SlipStatusBadge, SlipFlagBadge } from "@/components/payments/status-badges";
import { formatDateTime } from "@/lib/format";
import {
  useSlipReviewQueue,
  type PaymentSlipResponse,
  type PaymentSlipStatus,
} from "@/lib/api/payment-slips";

const PAGE_SIZE = 20;

/** `"pending"` is a client-only sentinel mapping to `status: undefined` — the backend's real default (`SUBMITTED` + `UNDER_REVIEW` combined); there is no backend "ALL" enum value. */
type StatusFilter = "pending" | PaymentSlipStatus;

const STATUS_FILTER_OPTIONS: Array<{ value: StatusFilter; label: string }> = [
  { value: "pending", label: "Pending review" },
  { value: "SUBMITTED", label: "Submitted" },
  { value: "UNDER_REVIEW", label: "Under review" },
  { value: "APPROVED", label: "Approved" },
  { value: "REJECTED", label: "Rejected" },
];

/**
 * Tenant Admin Manual Slip Review Queue (SLIP-3). `GET
 * /api/v1/payment-slips/review-queue` — server-enforced
 * `PAYMENTS_SLIPS`/`VIEW` (Tenant Admin, Finance Staff, Student Support,
 * Read-only Auditor; 403 for a student). This page issues the real request
 * unconditionally and lets `QueryStateBoundary` render
 * `PermissionDeniedState` on an actual 403 — the nav entry
 * (`tenant-admin-nav.tsx`) is the only client-side gate, pure UX convenience.
 *
 * Two distinct empty states, per the module plan's named acceptance
 * criterion: true zero-data (default "Pending review" filter, first page,
 * nothing at all) swaps the whole surface via `QueryStateBoundary`'s
 * `emptyState`; any other empty result (a specific status filter, or a later
 * page) is rendered inline with a "reset filters" action instead.
 */
export default function TenantAdminSlipReviewPage() {
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("pending");
  const [page, setPage] = useState(0);
  const query = useSlipReviewQueue({
    status: statusFilter === "pending" ? undefined : statusFilter,
    page,
    size: PAGE_SIZE,
  });

  function handleStatusChange(value: string) {
    setStatusFilter(value as StatusFilter);
    setPage(0);
  }

  function resetFilters() {
    setStatusFilter("pending");
    setPage(0);
  }

  const columns: DataTableColumn<PaymentSlipResponse>[] = [
    {
      key: "referenceNumber",
      header: "Reference",
      cell: (row) => row.referenceNumber,
      // Already surfaced as the mobile card's unlabeled heading via
      // `cardHeading` below — showing it again as a labeled row would
      // duplicate the same value on every card.
      hideOnCard: true,
    },
    {
      key: "studentEmail",
      header: "Student",
      cell: (row) => row.studentEmail ?? "—",
    },
    {
      key: "status",
      header: "Status",
      cell: (row) => <SlipStatusBadge status={row.status} />,
      hideOnCard: true,
    },
    {
      key: "flags",
      header: "Flags",
      cell: (row) =>
        row.flags.length > 0 ? (
          <div className="flex flex-wrap gap-1">
            {row.flags.map((flag) => (
              <SlipFlagBadge key={flag.id} flagType={flag.flagType} />
            ))}
          </div>
        ) : (
          "—"
        ),
    },
    {
      key: "submittedAt",
      header: "Submitted",
      cell: (row) => formatDateTime(row.submittedAt),
    },
    {
      key: "view",
      header: "View",
      cell: (row) => (
        <Link
          href={`/tenant-admin/payments/slip-review/${row.id}`}
          aria-label={`View slip ${row.referenceNumber}`}
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
        <h1 className="text-xl font-semibold text-foreground">Manual payment slips</h1>
        <p className="text-sm text-muted-foreground">
          Review student-submitted bank transfer evidence. Approving a slip activates enrollment;
          this queue never computes duplicate flags itself — every flag shown was detected by the
          backend.
        </p>
      </div>

      <div className="flex flex-col gap-1.5 sm:w-56">
        <Label htmlFor="slip-review-status">Status</Label>
        <Select value={statusFilter} onValueChange={(value) => handleStatusChange(value ?? "pending")}>
          <SelectTrigger id="slip-review-status" className="w-full">
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
        loadingLabel="Loading review queue…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => statusFilter === "pending" && page === 0 && data.content.length === 0}
        emptyState={{
          title: "No pending slips",
          description:
            "Once a student submits a manual payment slip, it will appear here for review.",
        }}
      >
        {(data) => (
          <div className="flex flex-col gap-4">
            {data.content.length === 0 ? (
              <EmptyState
                title="No slips match your filter"
                description="Try a different status filter or go back to an earlier page."
                action={{ label: "Reset filters", onClick: resetFilters }}
              />
            ) : (
              <DataTable
                columns={columns}
                rows={data.content}
                rowKey={(row) => row.id}
                caption="Manual payment slip review queue"
                cardHeading={(row) => row.referenceNumber}
                cardHeadingAdornment={(row) => <SlipStatusBadge status={row.status} />}
                cardFooter={(row) => (
                  <Link
                    href={`/tenant-admin/payments/slip-review/${row.id}`}
                    aria-label={`View slip ${row.referenceNumber}`}
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
