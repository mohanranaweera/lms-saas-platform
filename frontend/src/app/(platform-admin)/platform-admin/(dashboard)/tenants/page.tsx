"use client";

import { useState } from "react";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/page-header";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { LiveRegion } from "@/components/ui/live-region";
import { ApproveTenantDialog } from "@/components/platform-admin/approve-tenant-dialog";
import { RejectTenantDialog } from "@/components/platform-admin/reject-tenant-dialog";
import {
  usePlatformAdminTenants,
  type TenantSummaryResponse,
} from "@/lib/api/platform-admin-tenants";
import { StatusBadge, TENANT_STATUS_LABELS, type TenantStatus } from "./status-badge";
import { formatDateTime } from "@/lib/format";

/**
 * Tenant List / Approval Queue (PADASH-1). Rewired from the local-mock
 * scaffold (`mock-tenants.ts`, now deleted) to `GET
 * /api/v1/platform-admin/tenants` via `usePlatformAdminTenants`. Table shape,
 * status filter, and responsive table→card breakpoint carry over from the
 * scaffold unchanged; the scaffold's client-side name/subdomain search is
 * deliberately dropped — the live endpoint has no search query param, and
 * filtering only the current server-paginated page client-side would
 * silently produce incomplete results (missing matches on other pages).
 */

const PAGE_SIZE = 20;

const STATUS_FILTER_OPTIONS: Array<{ value: "all" | TenantStatus; label: string }> = [
  { value: "all", label: "All statuses" },
  { value: "pending_approval", label: TENANT_STATUS_LABELS.pending_approval },
  { value: "trial", label: TENANT_STATUS_LABELS.trial },
  { value: "active", label: TENANT_STATUS_LABELS.active },
  { value: "suspended", label: TENANT_STATUS_LABELS.suspended },
  { value: "cancelled", label: TENANT_STATUS_LABELS.cancelled },
  { value: "rejected", label: TENANT_STATUS_LABELS.rejected },
];

const columns: DataTableColumn<TenantSummaryResponse>[] = [
  {
    key: "name",
    header: "Tenant name",
    cell: (row) => (
      <Link
        href={`/platform-admin/tenants/${row.id}`}
        className="font-medium text-foreground hover:underline"
      >
        {row.name}
      </Link>
    ),
    // Already surfaced as the mobile card's heading via `cardHeading` below.
    hideOnCard: true,
  },
  {
    key: "subdomain",
    header: "Subdomain",
    cell: (row) => row.subdomain,
  },
  {
    key: "status",
    header: "Status",
    cell: (row) => <StatusBadge status={row.status} />,
    // Already surfaced via `cardHeadingAdornment` below.
    hideOnCard: true,
  },
  {
    key: "requestedPlan",
    header: "Requested plan",
    cell: (row) => row.requestedPlan,
  },
  {
    key: "createdAt",
    header: "Submitted",
    cell: (row) => formatDateTime(row.createdAt),
  },
  {
    key: "actions",
    header: "Actions",
    cell: (row) =>
      row.status === "pending_approval" ? (
        <div className="flex flex-wrap gap-2">
          <ApproveTenantDialog tenant={{ id: row.id, name: row.name }} />
          <RejectTenantDialog tenant={{ id: row.id, name: row.name }} />
        </div>
      ) : (
        <span className="text-xs text-muted-foreground">No action available</span>
      ),
    // Already surfaced via `cardFooter` below.
    hideOnCard: true,
  },
];

export default function PlatformAdminTenantsPage() {
  const [statusFilter, setStatusFilter] = useState<"all" | TenantStatus>("all");
  const [page, setPage] = useState(0);

  const query = usePlatformAdminTenants({
    status: statusFilter === "all" ? undefined : statusFilter,
    page,
    size: PAGE_SIZE,
  });

  function handleStatusChange(value: "all" | TenantStatus) {
    setStatusFilter(value);
    setPage(0);
  }

  function clearFilters() {
    setStatusFilter("all");
    setPage(0);
  }

  // Background refetch (status-filter change / page-turn) only — the
  // `QueryStateBoundary`'s own pending branch already owns the true
  // initial-load state; `keepPreviousData` (see `usePlatformAdminTenants`)
  // keeps `query.status === "success"` with stale content during this, so it
  // must be surfaced separately, mirroring `audit-log/page.tsx`'s exact
  // pattern.
  const isRefetching = query.isFetching && query.data !== undefined;

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Tenants"
        description="Cross-tenant registration list and approval queue — every row names the tenant."
      />

      <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
        <div className="flex flex-col gap-1.5 sm:w-56">
          <label
            htmlFor="tenant-status-filter"
            className="text-sm font-medium text-foreground"
          >
            Status
          </label>
          <select
            id="tenant-status-filter"
            value={statusFilter}
            onChange={(event) => handleStatusChange(event.target.value as "all" | TenantStatus)}
            className="h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
          >
            {STATUS_FILTER_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>
      </div>

      <QueryStateBoundary
        query={query}
        loginPath="/platform-admin/login"
        permissionDenied={{ dashboardHref: "/platform-admin/dashboard" }}
        isEmpty={(data) => data.content.length === 0 && page === 0}
        emptyState={
          statusFilter === "all"
            ? {
                title: "No tenant registrations yet",
                description:
                  "No institute has registered on the platform yet. New self-registrations will appear here for approval.",
              }
            : {
                title: "No tenants with this status",
                description: `There are no tenant registrations currently in "${TENANT_STATUS_LABELS[statusFilter]}" status.`,
                action: { label: "View all tenants", onClick: clearFilters },
              }
        }
      >
        {(data) =>
          data.content.length === 0 && page > 0 ? (
            <EmptyState
              title="No more results"
              description="There are no tenants on this page. Go back to an earlier page."
              action={{ label: "Go to previous page", onClick: () => setPage((p) => Math.max(0, p - 1)) }}
            />
          ) : (
            <div className="flex flex-col gap-4" aria-busy={isRefetching}>
              <LiveRegion
                message={
                  isRefetching
                    ? "Updating…"
                    : `${data.totalElements} tenant${data.totalElements === 1 ? "" : "s"} found`
                }
              />
              <div className={isRefetching ? "opacity-60" : undefined}>
                <DataTable
                  columns={columns}
                  rows={data.content}
                  rowKey={(row) => row.id}
                  caption="Tenants"
                  cardHeading={(row) => (
                    <Link href={`/platform-admin/tenants/${row.id}`} className="hover:underline">
                      {row.name}
                    </Link>
                  )}
                  cardHeadingAdornment={(row) => <StatusBadge status={row.status} />}
                  cardFooter={(row) =>
                    row.status === "pending_approval" ? (
                      <div className="flex flex-wrap gap-2 pt-1">
                        <ApproveTenantDialog tenant={{ id: row.id, name: row.name }} />
                        <RejectTenantDialog tenant={{ id: row.id, name: row.name }} />
                      </div>
                    ) : null
                  }
                />
              </div>

              <div className="flex items-center justify-between">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page === 0 || query.isFetching}
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
                  onClick={() => setPage((p) => p + 1)}
                  disabled={data.page + 1 >= data.totalPages || query.isFetching}
                >
                  Next
                </Button>
              </div>
            </div>
          )
        }
      </QueryStateBoundary>
    </div>
  );
}
