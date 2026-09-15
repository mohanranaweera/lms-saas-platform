"use client";

import { Suspense, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams, useParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { PageHeader } from "@/components/ui/page-header";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { LoadingState } from "@/components/states/loading-state";
import { LiveRegion } from "@/components/ui/live-region";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { PlatformAuditLogFilterForm } from "@/components/platform-admin/platform-audit-log-filter-form";
import {
  PlatformAuditLogTableSkeleton,
  buildPlatformAuditLogQueryString,
  platformAuditLogActionColumn,
  platformAuditLogActorColumn,
  platformAuditLogOccurredAtColumn,
  platformAuditLogTargetColumn,
  renderPlatformAuditLogExpandedRow,
} from "@/components/platform-admin/platform-audit-log-shared";
import { usePlatformAdminTenantDetail } from "@/lib/api/platform-admin-tenants";
import { StatusBadge } from "../../tenants/status-badge";
import {
  usePlatformAuditLogTenantDrillDown,
  type PlatformAuditLogEntryResponse,
} from "@/lib/api/platform-admin-audit-log";
import {
  PLATFORM_AUDIT_LOG_FILTER_DEFAULT_VALUES,
  toPlatformAuditLogQueryParams,
  type PlatformAuditLogFilterFormValues,
} from "@/lib/validation/platform-audit-log";
import { formatDateTime } from "@/lib/format";

const PAGE_SIZE = 20;

// No `tenant` column here (unlike the platform-wide list) — every row on
// this drill-down already belongs to the one tenant named in the banner.
// Actor added (MVP-020 review finding — previously omitted entirely despite
// `actorId` being on the response type).
const columns: DataTableColumn<PlatformAuditLogEntryResponse>[] = [
  platformAuditLogActorColumn,
  platformAuditLogActionColumn,
  platformAuditLogTargetColumn,
  platformAuditLogOccurredAtColumn,
];

/**
 * Tenant Audit Log Drill-down (PADASH-2). Same drill-down pattern as the
 * Tenant Payment Drill-down: fetches the tenant detail (for the
 * banner/breadcrumb) and the tenant-scoped audit-log page as two independent
 * queries, primary-erroring on a 404 tenant before attempting the log table.
 * Same filter form as the platform-wide list, minus needing the tenant
 * column. Zero update/delete affordance anywhere — there is none to
 * accidentally add.
 *
 * Shared skeleton/expanded-row/query-string helpers and common columns live in
 * `components/platform-admin/platform-audit-log-shared.tsx` — this page and
 * the platform-wide list (`../page.tsx`) previously carried byte-identical
 * copies of each (MVP-020 review finding).
 */
function TenantAuditLogDrillDownContent({ tenantId }: { tenantId: string }) {
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

  const tenantQuery = usePlatformAdminTenantDetail(tenantId);
  const logQuery = usePlatformAuditLogTenantDrillDown(tenantId, {
    ...apiParams,
    page,
    size: PAGE_SIZE,
  });
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
  const isRefetching = logQuery.isFetching && logQuery.data !== undefined;

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
      <QueryStateBoundary
        query={tenantQuery}
        loginPath="/platform-admin/login"
        permissionDenied={{ dashboardHref: "/platform-admin/dashboard" }}
        genericErrorMessage="Something went wrong loading this tenant. Please try again."
      >
        {(tenant) => (
          <>
            <Breadcrumbs
              items={[
                { label: "Audit Log", href: "/platform-admin/audit-log" },
                { label: tenant.name },
              ]}
            />
            <PageHeader
              title="Audit log"
              description="Privileged actions recorded for this tenant only. No row here can ever be edited or removed."
              showTenantContext
              tenantContext={{
                id: tenant.id,
                name: tenant.name,
                statusBadge: <StatusBadge status={tenant.status} />,
              }}
            />

            <PlatformAuditLogFilterForm
              idPrefix="platform-admin-tenant-audit-log"
              initialValues={filters}
              onApply={handleApply}
              onClear={handleClear}
              disabled={logQuery.isFetching}
            />

            {logQuery.status === "pending" ? (
              <>
                <p role="status" aria-live="polite" aria-busy="true" className="sr-only">
                  Loading audit log…
                </p>
                <PlatformAuditLogTableSkeleton />
              </>
            ) : (
              <QueryStateBoundary
                query={logQuery}
                loginPath="/platform-admin/login"
                permissionDenied={{ dashboardHref: "/platform-admin/dashboard" }}
                genericErrorMessage="Something went wrong loading this tenant's audit log. Please try again."
                isEmpty={(data) => data.content.length === 0 && page === 0 && !filtersActive}
                emptyState={{
                  title: "No audit events for this tenant yet",
                  description: `Privileged actions recorded for ${tenant.name} will appear here as they happen.`,
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
                          caption={`Audit log for ${tenant.name}`}
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
                        disabled={page === 0 || logQuery.isFetching}
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
                        disabled={data.page + 1 >= data.totalPages || logQuery.isFetching}
                      >
                        Next
                      </Button>
                    </div>
                  </div>
                )}
              </QueryStateBoundary>
            )}
          </>
        )}
      </QueryStateBoundary>
    </div>
  );
}

export default function PlatformAdminTenantAuditLogDrillDownPage() {
  const params = useParams<{ tenantId: string }>();
  const tenantId = params.tenantId;
  return (
    <Suspense fallback={<LoadingState label="Loading audit log…" />}>
      <TenantAuditLogDrillDownContent tenantId={tenantId} />
    </Suspense>
  );
}
