"use client";

import { Suspense, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { LoadingState } from "@/components/states/loading-state";
import { LiveRegion } from "@/components/ui/live-region";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Accordion } from "@/components/ui/accordion";
import { AuditLogFilterForm } from "@/components/audit-log/audit-log-filter-form";
import { useAuditLogSearch, type AuditLogEntryResponse } from "@/lib/api/audit-log";
import {
  AUDIT_LOG_FILTER_DEFAULT_VALUES,
  toAuditLogQueryParams,
  type AuditLogFilterFormValues,
} from "@/lib/validation/audit-log";
import { formatDateTime, shortId } from "@/lib/format";

const PAGE_SIZE = 20;

/**
 * Skeleton rows shown while `useAuditLogSearch` is `pending`, mirroring
 * `attendance/reports/page.tsx`'s `AttendanceReportsTableSkeleton` exactly
 * (same rationale: `QueryStateBoundary`'s own loading branch has no slot for
 * a custom render, so this page checks `query.status === "pending"` itself).
 */
function AuditLogTableSkeleton() {
  return (
    <div aria-hidden="true" className="flex flex-col gap-3">
      <div className="hidden overflow-hidden rounded-lg border border-border md:block">
        <div className="divide-y divide-border">
          {Array.from({ length: 6 }).map((_, index) => (
            <div key={index} className="flex items-center gap-6 px-4 py-3">
              <Skeleton className="h-4 w-32" />
              <Skeleton className="h-4 w-40" />
              <Skeleton className="h-4 w-28" />
              <Skeleton className="h-4 w-36" />
            </div>
          ))}
        </div>
      </div>
      <ul className="flex flex-col gap-3 md:hidden">
        {Array.from({ length: 3 }).map((_, index) => (
          <li key={index} className="flex flex-col gap-2 rounded-lg border border-border p-4">
            <Skeleton className="h-4 w-32" />
            <Skeleton className="h-3 w-full" />
            <Skeleton className="h-3 w-2/3" />
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Row-detail content for `reason`/`metadata` — these don't fit a compact
 * cell or the mobile card body (plan §11). `reason` is plain text (`null`
 * for every action except `payment.refunded`); `metadata` is collapsed
 * behind an `Accordion` summarizing its field count, expanding to a
 * `<pre>`-formatted block — never an inline stringified JSON blob.
 *
 * `DataTable` renders this function's result twice per expanded row (once
 * for the desktop `<tr>`, once for the mobile `<li>` — see `data-table.tsx`),
 * each behind CSS `hidden`/`block` toggling by breakpoint rather than
 * conditional mounting, so both trees exist in the DOM simultaneously. The
 * metadata `Accordion`'s open/closed state is therefore lifted out of the
 * `Accordion` itself (controlled `open`/`onOpenChange`) and into
 * `metadataOpenKeys` below, keyed by row id, so the desktop and mobile copies
 * of the same logical row always agree — resizing across the `md` breakpoint
 * after expanding "Metadata" at one width shows it already expanded at the
 * other, instead of each width tracking independent internal state.
 */
function renderExpandedRow(
  row: AuditLogEntryResponse,
  metadataOpenKeys: ReadonlySet<string>,
  onMetadataOpenChange: (rowId: string, open: boolean) => void
) {
  const fieldCount = row.metadata ? Object.keys(row.metadata).length : 0;
  return (
    <div className="flex flex-col gap-3 text-sm">
      <p>
        <span className="font-medium text-foreground">Reason: </span>
        <span className="text-muted-foreground">{row.reason ?? "Not provided for this event."}</span>
      </p>
      {row.metadata ? (
        <Accordion
          title={`Metadata (${fieldCount} field${fieldCount === 1 ? "" : "s"})`}
          open={metadataOpenKeys.has(row.id)}
          onOpenChange={(open) => onMetadataOpenChange(row.id, open)}
        >
          <pre className="overflow-x-auto rounded-md bg-muted p-3 text-xs text-foreground">
            {JSON.stringify(row.metadata, null, 2)}
          </pre>
        </Accordion>
      ) : (
        <p className="text-xs text-muted-foreground">No metadata recorded for this event.</p>
      )}
    </div>
  );
}

const columns: DataTableColumn<AuditLogEntryResponse>[] = [
  {
    key: "actor",
    header: "Actor",
    cell: (row) => row.actorDisplayName ?? shortId(row.actorId, "Actor"),
  },
  {
    key: "action",
    header: "Action",
    cell: (row) => row.action,
    // Already surfaced as the mobile card's unlabeled heading via
    // `cardHeading` below — showing it again as a labeled row would
    // duplicate the same value on every card (mirrors
    // `slip-review/page.tsx`'s identical `referenceNumber` convention).
    hideOnCard: true,
  },
  {
    key: "target",
    header: "Target",
    cell: (row) => `${row.targetEntity} #${row.targetId.slice(0, 8)}`,
  },
  {
    key: "occurredAt",
    header: "Occurred at",
    cell: (row) => formatDateTime(row.occurredAt),
    // Already surfaced via `cardHeadingAdornment` below.
    hideOnCard: true,
  },
];

/**
 * Builds the query string this page reads its own filter/pagination state
 * from — bare `YYYY-MM-DD`/exact-string filter values (matching the filter
 * form's own value convention, `lib/validation/audit-log.ts`) plus `page`,
 * omitting any key at its default (empty filter value, `page=0`) so the URL
 * stays clean when nothing is filtered. Shared by every navigation this page
 * triggers (apply, clear, previous/next) — see `navigateTo` below.
 */
function buildAuditLogQueryString(
  filters: AuditLogFilterFormValues,
  page: number
): string {
  const search = new URLSearchParams();
  if (filters.from) search.set("from", filters.from);
  if (filters.to) search.set("to", filters.to);
  if (filters.action) search.set("action", filters.action);
  if (filters.targetEntity) search.set("targetEntity", filters.targetEntity);
  if (page > 0) search.set("page", String(page));
  return search.toString();
}

/**
 * Tenant Admin/Read-only Auditor Audit Log Viewer (MVP-019, AUDIT-3). `GET
 * /api/v1/audit-log` is enforced server-side in two layers — see
 * `lib/api/audit-log.ts#useAuditLogSearch`'s doc comment — so this page
 * issues the real request unconditionally and lets `QueryStateBoundary`
 * render `PermissionDeniedState` on an actual `403`; the nav entry
 * (`tenant-admin-nav.tsx`, gated on `canViewAuditLog`) is pure UX
 * convenience only.
 *
 * Interim access-scope note (plan §21 decision 1, option B, also documented
 * in `docs/api/audit-log-management.md`'s "Authorization model"): every
 * viewer who reaches this page currently sees the FULL tenant-scoped log,
 * with no "own area" partial view — no staff sub-role scoping mechanism
 * exists yet (only `TENANT_ADMIN`/`READ_ONLY_AUDITOR` even reach `200`).
 * Deliberately no label/badge here implying a scoped or partial view (e.g.
 * "your area") until a real own-area mechanism is built, so this isn't later
 * mistaken for an oversight — this comment is that explicit documentation.
 *
 * Read-only screen: no row action column, no bulk-action checkbox column, no
 * context menu with any mutating option — none of these are built at all
 * (verified by construction, not by hiding/disabling), consistent with
 * `audit_log` having no `PUT`/`PATCH`/`DELETE` route for any role.
 *
 * Filter/pagination state lives in the URL's query string (product-owner-
 * approved scope addition), not local component state — see
 * `buildAuditLogQueryString`/`navigateTo` below and
 * `AuditLogFilterForm`'s own `initialValues` doc comment for the
 * read-from-URL/write-to-URL split this enables.
 */
function AuditLogPageContent() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  // The URL's query string is this page's single source of truth for filter
  // and pagination state (approved scope addition — see this module's plan
  // doc and the review that requested it): reading these four filter values
  // and `page` as plain strings/numbers here, rather than depending on the
  // `URLSearchParams` object itself, means `filters`/`page` below only
  // change identity when one of these actually changes — not on every
  // unrelated re-render — which in turn keeps `AuditLogFilterForm`'s own
  // `initialValues`-changed reset (see that component) from firing on a
  // page-only navigation.
  const fromParam = searchParams.get("from") ?? "";
  const toParam = searchParams.get("to") ?? "";
  const actionParam = searchParams.get("action") ?? "";
  const targetEntityParam = searchParams.get("targetEntity") ?? "";
  const pageParamRaw = Number(searchParams.get("page"));
  const page = Number.isFinite(pageParamRaw) && pageParamRaw > 0 ? Math.trunc(pageParamRaw) : 0;

  const filters = useMemo<AuditLogFilterFormValues>(
    () => ({ from: fromParam, to: toParam, action: actionParam, targetEntity: targetEntityParam }),
    [fromParam, toParam, actionParam, targetEntityParam]
  );
  const apiParams = useMemo(() => toAuditLogQueryParams(filters), [filters]);

  const query = useAuditLogSearch({ ...apiParams, page, size: PAGE_SIZE });
  // Keyed by row id — shared between the desktop and mobile renders of the
  // same logical row's metadata `Accordion` (see `renderExpandedRow`'s doc
  // comment above).
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

  const filtersActive = Boolean(filters.from || filters.to || filters.action || filters.targetEntity);
  // Background refetch (page-turn / filter-apply) case only — the
  // `query.status === "pending"` branch below already owns the true
  // initial-load skeleton; this only dims the already-rendered table while a
  // new page/filter is in flight.
  const isRefetching = query.isFetching && query.data !== undefined;

  /**
   * `replace`, not `push` (product-owner-approved URL-sync scope, this
   * module's plan): every filter apply/clear and every page turn replaces
   * the current history entry rather than pushing a new one, so browsing
   * back from this screen doesn't require stepping through every
   * intermediate filter/page state one at a time — only genuinely distinct
   * arrivals at this page (e.g. from the nav link) get their own history
   * entry. `scroll: false` keeps the already-scrolled table/page position
   * from jumping on every filter/page change.
   */
  function navigateTo(nextFilters: AuditLogFilterFormValues, nextPage: number) {
    const queryString = buildAuditLogQueryString(nextFilters, nextPage);
    router.replace(queryString ? `${pathname}?${queryString}` : pathname, { scroll: false });
  }

  function handleApply(values: AuditLogFilterFormValues) {
    navigateTo(values, 0);
  }

  function handleClear() {
    navigateTo(AUDIT_LOG_FILTER_DEFAULT_VALUES, 0);
  }

  function goToPage(nextPage: number) {
    navigateTo(filters, Math.max(0, nextPage));
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Audit log</h1>
        <p className="text-sm text-muted-foreground">
          A read-only, tenant-scoped trail of privileged actions — course/session price
          changes, material deletions, payment refunds, and other already-audited events. No
          row here can ever be edited or removed.
        </p>
      </div>

      <AuditLogFilterForm
        idPrefix="tenant-admin-audit-log"
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
          <AuditLogTableSkeleton />
        </>
      ) : (
        <QueryStateBoundary
          query={query}
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => data.content.length === 0 && page === 0 && !filtersActive}
          emptyState={{
            title: "No audit events yet",
            description:
              "Privileged actions across your tenant — price changes, material deletions, payment refunds, and more — will appear here as they happen.",
          }}
        >
          {(data) => (
            <div className="flex flex-col gap-4" aria-busy={isRefetching}>
              {/*
                When this transition resolves to zero rows, the inline
                `EmptyState` below renders its own `role="status"
                aria-live="polite"` region — announcing that message a
                second time here would be a duplicate, near-simultaneous
                screen-reader announcement for the same transition (post-ship
                review finding). Rendering an empty string announces nothing
                from this region, leaving `EmptyState`'s own status as the
                single source of truth for the zero-result case; the
                non-empty and refetching cases are unaffected.
              */}
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
                      ? "Try a different date range, action, or target entity, or clear your filters."
                      : "There are no events on this page. Go back to an earlier page."
                  }
                  action={
                    filtersActive
                      ? { label: "Reset filters", onClick: handleClear }
                      : {
                          label: "Go to previous page",
                          onClick: () => goToPage(page - 1),
                        }
                  }
                />
              ) : (
                <div className={isRefetching ? "opacity-60" : undefined}>
                  <DataTable
                    columns={columns}
                    rows={data.content}
                    rowKey={(row) => row.id}
                    caption="Audit log"
                    cardHeading={(row) => row.action}
                    cardHeadingAdornment={(row) => (
                      <span className="text-xs text-muted-foreground">
                        <span className="sr-only">Occurred at: </span>
                        {formatDateTime(row.occurredAt)}
                      </span>
                    )}
                    renderExpandedRow={(row) =>
                      renderExpandedRow(row, metadataOpenKeys, handleMetadataOpenChange)
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
 * `useSearchParams()` (URL-sync, this module's approved scope addition)
 * requires a `Suspense` boundary around any component that calls it —
 * without one, Next.js bails the entire route out of static rendering
 * (mirrors the existing precedent at
 * `app/(student)/student/payments/reactivation/page.tsx`, this codebase's
 * only other `useSearchParams` consumer to date). This page is already
 * fully client-rendered (auth-gated via `RouteGuard`, `"use client"` at the
 * top of this file), so in practice this boundary never visibly suspends —
 * it exists to satisfy the framework's static-rendering contract, not to
 * introduce a real loading gap ahead of this page's own
 * `query.status === "pending"` skeleton above.
 */
export default function TenantAdminAuditLogPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading audit log…" />}>
      <AuditLogPageContent />
    </Suspense>
  );
}
