"use client";

import { Suspense, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/page-header";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { LoadingState } from "@/components/states/loading-state";
import { LiveRegion } from "@/components/ui/live-region";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { PlatformAuditLogFilterForm } from "@/components/platform-admin/platform-audit-log-filter-form";
import {
  PlatformAuditLogTableSkeleton,
  buildPlatformAuditLogQueryString,
  createPlatformAuditLogTenantColumn,
  platformAuditLogActionColumn,
  platformAuditLogActorColumn,
  platformAuditLogOccurredAtColumn,
  platformAuditLogTargetColumn,
  renderPlatformAuditLogExpandedRow,
} from "@/components/platform-admin/platform-audit-log-shared";
import {
  usePlatformAuditLog,
  type PlatformAuditLogEntryResponse,
} from "@/lib/api/platform-admin-audit-log";
import {
  PLATFORM_AUDIT_LOG_FILTER_DEFAULT_VALUES,
  toPlatformAuditLogQueryParams,
  type PlatformAuditLogFilterFormValues,
} from "@/lib/validation/platform-audit-log";
import { formatDateTime } from "@/lib/format";

const PAGE_SIZE = 20;

// Tenant column first (cross-tenant list), then Actor (MVP-020 review finding —
// previously omitted entirely despite `actorId` being on the response type),
// then the columns shared with the drill-down page.
const columns: DataTableColumn<PlatformAuditLogEntryResponse>[] = [
  createPlatformAuditLogTenantColumn("/platform-admin/audit-log"),
  platformAuditLogActorColumn,
  platformAuditLogActionColumn,
  platformAuditLogTargetColumn,
  platformAuditLogOccurredAtColumn,
];

/**
 * Platform Audit Log (PADASH-2). Structurally distinct from the Tenant
 * Admin/Read-only Auditor Audit Log Viewer (`useAuditLogSearch`) — different
 * endpoint, different response shape (every row here carries its own
 * `tenantId`/`tenantName`, since this view spans every tenant). `GET
 * /api/v1/platform-admin/audit-log` is enforced server-side via
 * `hasRole('PLATFORM_ADMIN')`; this page issues the real request
 * unconditionally and lets `QueryStateBoundary` render `PermissionDeniedState`
 * on an actual `403`.
 *
 * Read-only screen: no row action column, no bulk-action checkbox column, no
 * context menu with any mutating option — consistent with `audit_log` having
 * no `PUT`/`PATCH`/`DELETE` route for any role.
 *
 * Filter/pagination state lives in the URL's query string, mirroring
 * `(tenant-admin)/tenant-admin/audit-log/page.tsx`'s exact URL-sync pattern.
 *
 * Shared skeleton/expanded-row/query-string helpers and common columns live in
 * `components/platform-admin/platform-audit-log-shared.tsx` — this page and
 * its per-tenant drill-down (`[tenantId]/page.tsx`) previously carried
 * byte-identical copies of each (MVP-020 review finding).
 */
function PlatformAuditLogPageContent() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const fromParam = searchParams.get("from") ?? "";
  const toParam = searchParams.get("to") ?? "";
  const actionParam = searchParams.get("action") ?? "";
  const pageParamRaw = Number(searchParams.get("page"));
  const page = Number.isFinite(pageParamRaw) && pageParamRaw > 0 ? Math.trunc(pageParamRaw) : 0;

  const filters = useMemo<PlatformAuditLogFilterFormValues>(
    () => ({ from: fromParam, to: toParam, action: actionParam }),
    [fromParam, toParam, actionParam]
  );
  const apiParams = useMemo(() => toPlatformAuditLogQueryParams(filters), [filters]);

  const query = usePlatformAuditLog({ ...apiParams, page, size: PAGE_SIZE });
  const [metadataOpenKeys, setMetadataOpenKeys] = useState<ReadonlySet<string>>(new Set());

  function handleMetadataOpenChange(rowId: string, open: boolean) {
    setMetadataOpenKeys((current) => {
      const next = new Set(current);
      if (open) {
        next.add(rowId);
      } else {
        next.delete(rowId);
      }
      return next;
    });
  }

  const filtersActive = Boolean(filters.from || filters.to || filters.action);
  const isRefetching = query.isFetching && query.data !== undefined;

  function navigateTo(nextFilters: PlatformAuditLogFilterFormValues, nextPage: number) {
    const queryString = buildPlatformAuditLogQueryString(nextFilters, nextPage);
    router.replace(queryString ? `${pathname}?${queryString}` : pathname, { scroll: false });
  }

  function handleApply(values: PlatformAuditLogFilterFormValues) {
    navigateTo(values, 0);
  }

  function handleClear() {
    navigateTo(PLATFORM_AUDIT_LOG_FILTER_DEFAULT_VALUES, 0);
  }

  function goToPage(nextPage: number) {
    navigateTo(filters, Math.max(0, nextPage));
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Platform audit log"
        description="A read-only, platform-level trail of privileged cross-tenant actions — tenant approvals/rejections and other already-audited events. Every row names its tenant. No row here can ever be edited or removed."
      />

      <PlatformAuditLogFilterForm
        idPrefix="platform-admin-audit-log"
        initialValues={filters}
        onApply={handleApply}
        onClear={handleClear}
        disabled={query.isFetching}
      />

      {query.status === "pending" ? (
        <>
          <p role="status" aria-live="polite" aria-busy="true" className="sr-only">
            Loading audit log…
          </p>
          <PlatformAuditLogTableSkeleton />
        </>
      ) : (
        <QueryStateBoundary
          query={query}
          loginPath="/platform-admin/login"
          permissionDenied={{ dashboardHref: "/platform-admin/dashboard" }}
          genericErrorMessage="Something went wrong loading the platform audit log. Please try again."
          isEmpty={(data) => data.content.length === 0 && page === 0 && !filtersActive}
          emptyState={{
            title: "No platform audit events yet",
            description:
              "Privileged cross-tenant actions — tenant approvals/rejections, and more — will appear here as they happen.",
          }}
        >
          {(data) => (
            <div className="flex flex-col gap-4" aria-busy={isRefetching}>
              <LiveRegion
                message={
                  isRefetching
                    ? "Updating…"
                    : data.content.length === 0
                      ? ""
                      : `${data.totalElements} result${data.totalElements === 1 ? "" : "s"} found`
                }
              />
              {data.content.length === 0 ? (
                <EmptyState
                  title={filtersActive ? "No events match your filters" : "No more results"}
                  description={
                    filtersActive
                      ? "Try a different date range or action, or clear your filters."
                      : "There are no events on this page. Go back to an earlier page."
                  }
                  action={
                    filtersActive
                      ? { label: "Reset filters", onClick: handleClear }
                      : { label: "Go to previous page", onClick: () => goToPage(page - 1) }
                  }
                />
              ) : (
                <div className={isRefetching ? "opacity-60" : undefined}>
                  <DataTable
                    columns={columns}
                    rows={data.content}
                    rowKey={(row) => row.id}
                    caption="Platform audit log"
                    cardHeading={(row) => row.action}
                    cardHeadingAdornment={(row) => (
                      <span className="text-xs text-muted-foreground">
                        <span className="sr-only">Occurred at: </span>
                        {formatDateTime(row.occurredAt)}
                      </span>
                    )}
                    renderExpandedRow={(row) =>
                      renderPlatformAuditLogExpandedRow(row, metadataOpenKeys, handleMetadataOpenChange)
                    }
                  />
                </div>
              )}
              <div className="flex items-center justify-between">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => goToPage(page - 1)}
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
                  onClick={() => goToPage(page + 1)}
                  disabled={data.page + 1 >= data.totalPages || query.isFetching}
                >
                  Next
                </Button>
              </div>
            </div>
          )}
        </QueryStateBoundary>
      )}
    </div>
  );
}

/**
 * `useSearchParams()` requires a `Suspense` boundary — mirrors
 * `(tenant-admin)/tenant-admin/audit-log/page.tsx`'s identical wrapper and
 * its doc comment's exact rationale.
 */
export default function PlatformAdminAuditLogPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading audit log…" />}>
      <PlatformAuditLogPageContent />
    </Suspense>
  );
}
